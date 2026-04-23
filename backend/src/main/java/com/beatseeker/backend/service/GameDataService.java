package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRankSong;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.repository.DifficultyRankRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 【Service の役割】 曲定義（SongDefinition）と非公式難易度テーブル（DifficultyRank）の
 * マスタデータを「active（本番）」「draft（編集中）」の 2 リビジョン体制で管理する。
 *
 * 責務:
 *  - active リビジョンの JSON 書き出し（フロントエンド配信用）
 *  - draft リビジョンの CRUD（管理者画面から曲・難易度テーブルを編集）
 *  - draft → active への昇格（applyDraft）と、昇格後の全ユーザー BEAT-PT 再計算トリガ
 *  - 起動時に resources の JSON からシード投入（テーブルが空のときだけ）
 *
 * 依存:
 *  - {@link SongDefinitionRepository}: 曲定義テーブル
 *  - {@link DifficultyRankRepository}: 非公式難易度テーブル
 *  - {@link ScoreRecalculationService}: applyDraft 後の BEAT-PT 再計算
 *  - {@link ObjectMapper}: 既存 JSON の解釈と配信用 JSON の生成
 *
 * 主要ロジックの概観:
 *  - revision カラムで "active" / "draft" を区別し、同じテーブルを 2 世代運用
 *  - ANOTHER(4) / LEGGENDARIA(10) のみ wr/avg/coef/textage などの詳細を持つ
 *  - draft 難易度テーブルの保存は「既存 draft 全削除 → JSON を 1 件ずつ新規作成」の置換式
 */
@Service
public class GameDataService {

    /** 曲定義テーブル（active / draft 両方を扱う） */
    private final SongDefinitionRepository songDefRepo;
    /** 非公式難易度テーブル（active / draft 両方を扱う） */
    private final DifficultyRankRepository diffRankRepo;
    /** applyDraft 後に全ユーザーの BEAT-PT を再計算させる Service */
    private final ScoreRecalculationService recalcService;
    /** JSON 読み書き用 Jackson マッパー */
    private final ObjectMapper objectMapper;

    /** ネイティブクエリで statement_timeout を設定するために利用する EntityManager */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 【コンストラクタ】 Spring が各 Repository と共通 {@link ObjectMapper} を注入する。
     */
    public GameDataService(SongDefinitionRepository songDefRepo,
                           DifficultyRankRepository diffRankRepo,
                           ScoreRecalculationService recalcService,
                           ObjectMapper objectMapper) {
        this.songDefRepo = songDefRepo;
        this.diffRankRepo = diffRankRepo;
        this.recalcService = recalcService;
        this.objectMapper = objectMapper;
    }

    // ── アクティブデータ（公開用）────────────────────────────

    /**
     * active リビジョンの曲定義から song_data JSON を生成する。
     * フロントエンドの初期ロードで利用される。
     */
    public String getActiveSongDataJson() throws Exception {
        List<SongDefinition> songs = songDefRepo.findByRevision("active");
        return buildSongDataJson(songs);
    }

    /**
     * active リビジョンの非公式難易度テーブルから difficulty_table JSON を生成する。
     */
    public String getActiveDifficultyTableJson() throws Exception {
        List<DifficultyRank> ranks = diffRankRepo.findByRevisionOrderBySortOrderAsc("active");
        return buildDifficultyTableJson(ranks);
    }

    // ── Draft 曲（管理者用）──────────────────────────────────

    /** draft リビジョンの曲定義一覧を返す */
    public List<SongDefinition> getDraftSongs() {
        return songDefRepo.findByRevision("draft");
    }

    /**
     * 【メソッドの役割】 フォーム入力から draft 曲を追加する。
     *
     * 処理の流れ:
     *  - 手順1: 共通項目（title/artist/genre/bpm）をフォームから取り出す
     *  - 手順2: 5 つの難易度コード × それぞれの notes/level フォームキーを走査
     *  - 手順3: notes が 0 または null の難易度はスキップ
     *  - 手順4: ANOTHER(4)/LEGGENDARIA(10) のときは wr/avg/coef/textage/dpLevel も保存
     *  - 手順5: 各難易度を 1 SongDefinition レコードとして draft 保存
     *
     * @param songForm フロントエンドから POST されたフォームデータ
     * @return 新規保存した SongDefinition のリスト
     */
    @Transactional
    public List<SongDefinition> addDraftSong(Map<String, Object> songForm) {
        String title = (String) songForm.get("title");
        String artist = (String) songForm.get("artist");
        String genre = (String) songForm.get("genre");
        String bpm = (String) songForm.get("bpm");

        List<SongDefinition> created = new ArrayList<>();

        // 各難易度のメタ情報: { 難易度コード, notesフォームキー, levelフォームキー }
        String[][] diffs = {
            {"1", "beginnerNotes", "beginnerLevel"},
            {"2", "normalNotes", "normalLevel"},
            {"3", "hyperNotes", "hyperLevel"},
            {"4", "anotherNotes", "anotherLevel"},
            {"10", "leggendariaNotes", "leggendariaLevel"},
        };

        for (String[] d : diffs) {
            String code = d[0];
            Integer notes = toInteger(songForm.get(d[1]));
            Integer level = toInteger(songForm.get(d[2]));
            if (notes == null || notes <= 0) continue;

            SongDefinition sd = new SongDefinition();
            sd.setTitle(title);
            sd.setArtist(artist);
            sd.setGenre(genre);
            sd.setBpm(bpm);
            sd.setDifficulty(code);
            sd.setNotes(notes);
            sd.setLevel(level);
            sd.setRevision("draft");

            // ANOTHER(4) と LEGGENDARIA(10) のみ、WR・平均・係数・textage・dpLevel の追加情報を持つ
            if ("4".equals(code) || "10".equals(code)) {
                sd.setWr(toInteger(songForm.get("wr")));
                sd.setAvg(toInteger(songForm.get("avg")));
                sd.setCoef(toDouble(songForm.get("coef")));
                sd.setTextage((String) songForm.get("textage"));
                sd.setDifficultyLevel(level != null ? String.valueOf(level) : null);
                sd.setDpLevel(songForm.get("dpLevel") != null ? songForm.get("dpLevel").toString() : "0");
            }

            created.add(songDefRepo.save(sd));
        }

        return created;
    }

    /**
     * ID 指定で draft 曲を削除する。
     * active リビジョンのレコードに対しては例外を投げ、誤削除を防ぐ。
     */
    @Transactional
    public void deleteDraftSong(Long id) {
        SongDefinition sd = songDefRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Song not found: " + id));
        if (!"draft".equals(sd.getRevision())) {
            throw new RuntimeException("Can only delete draft songs");
        }
        songDefRepo.delete(sd);
    }

    // ── Draft 難易度テーブル（管理者用）────────────────────────

    /**
     * draft 難易度テーブルの JSON を返す。
     * draft が存在しない場合は active の内容を代替で返す（初回編集時のプリセット用途）。
     */
    public String getDraftDifficultyTableJson() throws Exception {
        List<DifficultyRank> draftRanks = diffRankRepo.findByRevisionOrderBySortOrderAsc("draft");
        if (draftRanks.isEmpty()) {
            return getActiveDifficultyTableJson();
        }
        return buildDifficultyTableJson(draftRanks);
    }

    /**
     * 【メソッドの役割】 管理者画面から送られた JSON で draft 難易度テーブルを置換保存する。
     *
     * 処理の流れ:
     *  - 手順1: 大量削除に備えて statement_timeout を 120 秒に拡張
     *  - 手順2: 既存の draft DifficultyRank 群を deleteAll で削除（子レコードも cascade）
     *  - 手順3: JSON の ranks 配列を走査し、新しい DifficultyRank レコードを 1 件ずつ生成
     *  - 手順4: 各 rank 配下の songs 配列を DifficultyRankSong として紐付けて保存
     *
     * @param json 「{ranks: [{rank, songs: [...]}]}」形式の難易度テーブル JSON
     * @throws Exception JSON パース失敗、あるいはフォーマット不整合時
     */
    @Transactional
    public void saveDraftDifficultyTable(String json) throws Exception {
        entityManager.createNativeQuery("SET LOCAL statement_timeout = '120s'").executeUpdate();
        // 既存の draft を deleteAll で削除（子 DifficultyRankSong も cascade される）
        List<DifficultyRank> existingDraft = diffRankRepo.findByRevisionOrderBySortOrderAsc("draft");
        diffRankRepo.deleteAll(existingDraft);
        diffRankRepo.flush();

        // JSON をパースして新しい draft レコードを構築
        JsonNode root = objectMapper.readTree(json);
        JsonNode ranksNode = root.path("ranks");
        if (!ranksNode.isArray()) {
            throw new RuntimeException("Invalid difficulty table JSON: 'ranks' array expected");
        }

        int sortOrder = 0;
        for (JsonNode rn : ranksNode) {
            DifficultyRank rank = new DifficultyRank();
            rank.setRankValue(rn.path("rank").asText());
            rank.setSortOrder(sortOrder++);
            rank.setRevision("draft");

            List<DifficultyRankSong> songList = new ArrayList<>();
            JsonNode songsNode = rn.path("songs");
            if (songsNode.isArray()) {
                int songOrder = 0;
                for (JsonNode sn : songsNode) {
                    DifficultyRankSong drs = new DifficultyRankSong();
                    drs.setDifficultyRank(rank);
                    drs.setSongTitle(sn.asText().trim());
                    drs.setSortOrder(songOrder++);
                    songList.add(drs);
                }
            }
            rank.setSongs(songList);
            diffRankRepo.save(rank);
        }
    }

    // ── Draft 昇格（管理者用）────────────────────────────────

    /**
     * 【メソッドの役割】 draft の楽曲のみを active に昇格し、全ユーザーの BEAT-PT を再計算する。
     * 難易度表の draft には触れない（別エンドポイントで独立に適用する）。
     *
     * @throws Exception JSON 生成や再計算キックに失敗した場合
     */
    @Transactional
    public void applyDraftSongs() throws Exception {
        List<SongDefinition> draftSongs = songDefRepo.findByRevision("draft");
        for (SongDefinition ds : draftSongs) {
            ds.setRevision("active");
            songDefRepo.save(ds);
        }

        String songDataJson = getActiveSongDataJson();
        String diffTableJson = getActiveDifficultyTableJson();
        recalcService.recalculateAllUsersAsync(songDataJson, diffTableJson);
    }

    /**
     * 【メソッドの役割】 draft の難易度表のみを active に昇格し、全ユーザーの BEAT-PT を再計算する。
     * 楽曲の draft には触れない（別エンドポイントで独立に適用する）。
     *
     * @throws Exception JSON 生成や再計算キックに失敗した場合
     */
    @Transactional
    public void applyDraftDifficultyTable() throws Exception {
        List<DifficultyRank> draftRanks = diffRankRepo.findByRevisionOrderBySortOrderAsc("draft");
        if (!draftRanks.isEmpty()) {
            List<DifficultyRank> activeRanks = diffRankRepo.findByRevisionOrderBySortOrderAsc("active");
            diffRankRepo.deleteAll(activeRanks);
            diffRankRepo.flush();
            for (DifficultyRank dr : draftRanks) {
                dr.setRevision("active");
                diffRankRepo.save(dr);
            }
        }

        String songDataJson = getActiveSongDataJson();
        String diffTableJson = getActiveDifficultyTableJson();
        recalcService.recalculateAllUsersAsync(songDataJson, diffTableJson);
    }

    // ── JSON からのシード（起動時）────────────────────────

    /**
     * 【メソッドの役割】 resources 同梱 JSON から active 曲定義をシード投入する。
     * 既に 1 件でも active 曲が存在する場合は何もしない（冪等）。
     *
     * @param json song_data.json 相当の JSON（body 配列に曲情報）
     * @throws Exception JSON パース失敗時
     */
    @Transactional
    public void seedSongData(String json) throws Exception {
        if (songDefRepo.countByRevision("active") > 0) return; // already seeded

        JsonNode root = objectMapper.readTree(json);
        JsonNode body = root.path("body");
        if (!body.isArray()) return;

        List<SongDefinition> batch = new ArrayList<>();
        for (JsonNode s : body) {
            SongDefinition sd = new SongDefinition();
            sd.setTitle(s.path("title").asText());
            sd.setArtist(s.path("artist").asText(null));
            sd.setGenre(s.path("genre").asText(null));
            sd.setNotes(s.path("notes").asInt(0));
            sd.setBpm(s.path("bpm").asText(null));
            sd.setDifficulty(s.path("difficulty").asText());
            sd.setLevel(s.path("level").asInt(0));
            if (s.has("wr")) sd.setWr(s.path("wr").asInt());
            if (s.has("avg")) sd.setAvg(s.path("avg").asInt());
            if (s.has("textage")) sd.setTextage(s.path("textage").asText());
            if (s.has("coef")) sd.setCoef(s.path("coef").asDouble());
            if (s.has("difficultyLevel")) sd.setDifficultyLevel(s.path("difficultyLevel").asText());
            if (s.has("dpLevel")) sd.setDpLevel(s.path("dpLevel").asText());
            sd.setRevision("active");
            batch.add(sd);
        }
        songDefRepo.saveAll(batch);
    }

    /**
     * 【メソッドの役割】 resources 同梱 JSON から active 難易度テーブルをシード投入する。
     * 既に 1 件でも active 難易度が存在する場合は何もしない（冪等）。
     *
     * @param json difficulty_table.json 相当の JSON（ranks 配列に各ランクと配下曲）
     * @throws Exception JSON パース失敗時
     */
    @Transactional
    public void seedDifficultyTable(String json) throws Exception {
        if (diffRankRepo.countByRevision("active") > 0) return; // 既にシード済み

        JsonNode root = objectMapper.readTree(json);
        JsonNode ranksNode = root.path("ranks");
        if (!ranksNode.isArray()) return;

        int sortOrder = 0;
        for (JsonNode rn : ranksNode) {
            DifficultyRank rank = new DifficultyRank();
            rank.setRankValue(rn.path("rank").asText());
            rank.setSortOrder(sortOrder++);
            rank.setRevision("active");

            List<DifficultyRankSong> songList = new ArrayList<>();
            JsonNode songsNode = rn.path("songs");
            if (songsNode.isArray()) {
                int songOrder = 0;
                for (JsonNode sn : songsNode) {
                    DifficultyRankSong drs = new DifficultyRankSong();
                    drs.setDifficultyRank(rank);
                    drs.setSongTitle(sn.asText().trim());
                    drs.setSortOrder(songOrder++);
                    songList.add(drs);
                }
            }
            rank.setSongs(songList);
            diffRankRepo.save(rank);
        }
    }

    // ── Draft 有無チェック ────────────────────────────────────

    /** draft 曲が 1 件でも存在するか */
    public boolean hasDraftSongs() {
        return songDefRepo.countByRevision("draft") > 0;
    }

    /** draft 難易度テーブルが 1 件でも存在するか */
    public boolean hasDraftDifficultyTable() {
        return diffRankRepo.countByRevision("draft") > 0;
    }

    // ── プライベートヘルパー ──────────────────────────────────

    /**
     * SongDefinition のリストを、フロントエンドが期待する song_data JSON 形式に整形する。
     * ANOTHER/LEGGENDARIA 固有フィールド（wr/avg/coef/textage/difficultyLevel/dpLevel）は
     * 値がある場合のみ出力して冗長性を抑える。
     */
    private String buildSongDataJson(List<SongDefinition> songs) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", 20241114);
        root.put("requireVersion", "87");

        ArrayNode body = objectMapper.createArrayNode();
        for (SongDefinition sd : songs) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("title", sd.getTitle());
            node.put("artist", sd.getArtist());
            node.put("genre", sd.getGenre());
            node.put("notes", sd.getNotes());
            node.put("bpm", sd.getBpm());
            node.put("difficulty", sd.getDifficulty());
            node.put("level", sd.getLevel());
            if (sd.getWr() != null) node.put("wr", sd.getWr());
            if (sd.getAvg() != null) node.put("avg", sd.getAvg());
            if (sd.getTextage() != null) node.put("textage", sd.getTextage());
            if (sd.getCoef() != null) node.put("coef", sd.getCoef());
            if (sd.getDifficultyLevel() != null) node.put("difficultyLevel", sd.getDifficultyLevel());
            if (sd.getDpLevel() != null) node.put("dpLevel", sd.getDpLevel());
            body.add(node);
        }
        root.set("body", body);
        return objectMapper.writeValueAsString(root);
    }

    /**
     * DifficultyRank のリストを「{ranks: [{rank, songs: [title...]}]}」形式の JSON に変換する。
     */
    private String buildDifficultyTableJson(List<DifficultyRank> ranks) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode ranksArr = objectMapper.createArrayNode();

        for (DifficultyRank dr : ranks) {
            ObjectNode rn = objectMapper.createObjectNode();
            rn.put("rank", dr.getRankValue());
            ArrayNode songsArr = objectMapper.createArrayNode();
            for (DifficultyRankSong drs : dr.getSongs()) {
                songsArr.add(drs.getSongTitle());
            }
            rn.set("songs", songsArr);
            ranksArr.add(rn);
        }

        root.set("ranks", ranksArr);
        return objectMapper.writeValueAsString(root);
    }

    /** フォーム値（Object）を Integer へ緩くキャスト。パース失敗時は null を返す */
    private Integer toInteger(Object val) {
        if (val == null) return null;
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(val.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }

    /** フォーム値（Object）を Double へ緩くキャスト。パース失敗時は null を返す */
    private Double toDouble(Object val) {
        if (val == null) return null;
        if (val instanceof Double) return (Double) val;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(val.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
