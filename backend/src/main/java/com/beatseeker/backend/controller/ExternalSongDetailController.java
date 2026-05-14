package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.ChartTendencyProfile;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.entity.UserSongOption;
import com.beatseeker.backend.entity.UserSongRank;
import com.beatseeker.backend.repository.ChartTendencyProfileRepository;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.repository.UserSongOptionRepository;
import com.beatseeker.backend.repository.UserSongRankRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 【クラスの役割】 連携アプリ（iidx-memo 等）に向けて「ユーザー単位の楽曲詳細情報」を
 * 1 リクエストで返す外部公開 API。
 *
 * 認証: {@link com.beatseeker.backend.config.ApiTokenAuthFilter} が
 * {@code Authorization: Bearer <個人API トークン>} を検証し、SecurityContext に
 * トークン所有者の iidxId を載せた状態で本コントローラに到達する。
 * したがって本コントローラ自体は通常の {@code Authentication} から
 * 対象ユーザーを引くだけでよい。
 *
 * エンドポイント:
 *  - {@code GET /api/external/v1/song-detail?title=...&difficulty=ANOTHER}
 *
 * レスポンス構造（user / song / score / rank / history / chartTendency）は
 * docs/external_api.md に明記。
 *
 * 設計方針:
 *  - 「トークン所有者のデータ」だけを返す。クエリパラメータでユーザーを切り替えさせない
 *    （= トークンの所有範囲を超えないようにする）。
 *  - スコアが未プレイの場合も song / chartTendency は返す（連携先で「未プレイ」表示が可能）。
 *  - chart tendency の重い JSON（measure 系・interval 系）はサマリだけ返す。
 *    必要なら別エンドポイントで full を提供する想定。
 */
@RestController
@RequestMapping("/api/external/v1")
public class ExternalSongDetailController {

    private final UserRepository userRepository;
    private final ScoreRepository scoreRepository;
    private final SongDefinitionRepository songDefinitionRepository;
    private final UserSongRankRepository userSongRankRepository;
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;
    private final ChartTendencyProfileRepository chartTendencyProfileRepository;
    private final UserSongOptionRepository userSongOptionRepository;

    public ExternalSongDetailController(UserRepository userRepository,
                                        ScoreRepository scoreRepository,
                                        SongDefinitionRepository songDefinitionRepository,
                                        UserSongRankRepository userSongRankRepository,
                                        ScoreHistoryLogRepository scoreHistoryLogRepository,
                                        ChartTendencyProfileRepository chartTendencyProfileRepository,
                                        UserSongOptionRepository userSongOptionRepository) {
        this.userRepository = userRepository;
        this.scoreRepository = scoreRepository;
        this.songDefinitionRepository = songDefinitionRepository;
        this.userSongRankRepository = userSongRankRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.chartTendencyProfileRepository = chartTendencyProfileRepository;
        this.userSongOptionRepository = userSongOptionRepository;
    }

    /**
     * 【メソッドの役割】 楽曲詳細情報（ユーザーのスコア + 譜面メタ + 順位 + 履歴 + 譜面傾向サマリ）を
     * 1 リクエストで返す。
     *
     * クエリパラメータ:
     *  - {@code title}      … 楽曲タイトル（必須）
     *  - {@code difficulty} … "BEGINNER" / "NORMAL" / "HYPER" / "ANOTHER" / "LEGGENDARIA"（必須）
     *
     * 認証:
     *  - {@code Authorization: Bearer <bs_live_xxx>} が必須。
     *  - 該当ユーザーのスコアのみ返却（他人のスコアにはアクセスできない）。
     *
     * ステータスコード:
     *  - 200: 正常応答（スコア未登録でも song 情報があれば 200 で score=null）
     *  - 400: difficulty 値が不正
     *  - 401: トークン不一致 / 失効 / 期限切れ（Security 側で返る）
     *  - 404: title / difficulty に該当する song_definitions レコードが無い
     */
    @GetMapping("/song-detail")
    public ResponseEntity<?> getSongDetail(Authentication auth,
                                           @RequestParam String title,
                                           @RequestParam String difficulty) {

        // ── 1. パラメータ検証 ───────────────────────────────
        String difficultyName = difficulty == null ? null : difficulty.trim().toUpperCase();
        String difficultyCode = toDifficultyCode(difficultyName);
        if (difficultyCode == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown difficulty",
                    "allowed", List.of("BEGINNER", "NORMAL", "HYPER", "ANOTHER", "LEGGENDARIA")));
        }

        // ── 2. トークン所有ユーザー特定 ─────────────────────
        User user = getUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        // ── 3. 譜面メタ取得（song_definitions, active 限定） ──
        Optional<SongDefinition> songOpt = songDefinitionRepository
                .findByTitleAndDifficultyAndRevision(title, difficultyCode, "active");
        if (songOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Song not found",
                    "title", title,
                    "difficulty", difficultyName));
        }
        SongDefinition song = songOpt.get();

        // ── 4. ユーザースコア取得（未プレイなら null） ───────
        Optional<Score> scoreOpt = scoreRepository
                .findFirstByUserAndTitleAndDifficultyNameOrderByUploadedAtDesc(user, title, difficultyName);

        // ── 5. 順位キャッシュ取得（ANOTHER / LEGGENDARIA のみ通常存在） ──
        Optional<UserSongRank> rankOpt = userSongRankRepository
                .findByUserIdAndTitleAndDifficultyName(user.getId(), title, difficultyName);

        // ── 6. 譜面傾向プロファイル（任意。textage URL をキーにマップ） ──
        String textage = song.getTextage();
        Optional<ChartTendencyProfile> tendencyOpt = textage == null
                ? Optional.empty()
                : chartTendencyProfileRepository.findById(textage);

        // ── 7. 個別曲の履歴（diffJson から該当タイトル/難易度を抜粋） ──
        List<Map<String, Object>> history = collectSongHistory(user, title, difficultyName);

        // ── 8. 同期済みオプション（iidx-memo 等から POST されたもの） ──
        List<String> options = userSongOptionRepository
                .findByUserAndTitleAndDifficultyName(user, title, difficultyName)
                .map(o -> parseOptionsJson(o.getOptionsJson()))
                .orElse(List.of());

        // ── 9. レスポンス組み立て ───────────────────────────
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", buildUserBlock(user));
        body.put("song", buildSongBlock(song, difficultyName));
        body.put("score", scoreOpt.map(this::buildScoreBlock).orElse(null));
        body.put("rank", rankOpt.map(this::buildRankBlock).orElse(null));
        body.put("options", options);
        body.put("history", history);
        body.put("chartTendency", tendencyOpt.map(this::buildTendencyBlock).orElse(null));

        return ResponseEntity.ok(body);
    }

    /**
     * 【メソッドの役割】 連携アプリ（iidx-memo 等）から譜面オプションを一括 upsert する。
     *
     * 仕様（docs/bs-op-sync.md）:
     *  - Body: {@code { "options": [ { "title", "difficulty", "options": [...] }, ... ] }}
     *  - {@code difficulty} は beat-seeker 形式（"ANOTHER" / "LEGGENDARIA" / ...）
     *  - 既存レコードが (user, title, difficulty) で見つかれば options を上書き
     *  - 見つからなければ新規 INSERT
     *  - {@code options} が null / 空配列の場合はスキップ（削除はしない）
     *
     * レスポンス: {@code { "synced": <処理件数> }}
     *
     * ステータスコード:
     *  - 200: 成功
     *  - 400: body 不正 / 必須フィールド欠落 / difficulty 不正
     *  - 401: トークン無効（Security 側で返る）
     */
    @PostMapping("/sync-options")
    @Transactional
    public ResponseEntity<?> syncOptions(Authentication auth, @RequestBody SyncOptionsRequest req) {
        User user = getUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        if (req == null || req.options() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'options' array"));
        }

        ObjectMapper mapper = new ObjectMapper();
        int synced = 0;

        for (SyncOptionsItem item : req.options()) {
            if (item == null) continue;
            String title = item.title();
            String difficulty = item.difficulty();
            List<String> opts = item.options();

            // 必須項目チェック。一件不正でも他は処理を続ける（全件 400 にしない設計）。
            if (title == null || title.isBlank()) continue;
            if (difficulty == null || toDifficultyCode(difficulty.trim().toUpperCase()) == null) continue;
            if (opts == null || opts.isEmpty()) continue;

            String difficultyName = difficulty.trim().toUpperCase();
            String optionsJson;
            try {
                optionsJson = mapper.writeValueAsString(opts);
            } catch (Exception e) {
                // 文字列リストのシリアライズが失敗することは現実には起きないが、安全に skip。
                continue;
            }

            UserSongOption entity = userSongOptionRepository
                    .findByUserAndTitleAndDifficultyName(user, title, difficultyName)
                    .orElseGet(() -> {
                        UserSongOption created = new UserSongOption();
                        created.setUser(user);
                        created.setTitle(title);
                        created.setDifficultyName(difficultyName);
                        return created;
                    });
            entity.setOptionsJson(optionsJson);
            entity.setSource("iidx-memo");
            entity.setUpdatedAt(java.time.LocalDateTime.now());
            userSongOptionRepository.save(entity);
            synced++;
        }

        return ResponseEntity.ok(Map.of("synced", synced));
    }

    /**
     * options_json 列の JSON 配列文字列をパースする。壊れた値は空リストを返す（API を 500 にしない）。
     */
    private List<String> parseOptionsJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return new ObjectMapper().readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** sync-options のリクエスト body 全体。 */
    public record SyncOptionsRequest(List<SyncOptionsItem> options) {}

    /** sync-options の各レコード。{@code options} はオプション文字列の配列。 */
    public record SyncOptionsItem(String title, String difficulty, List<String> options) {}

    // ── ヘルパ群 ────────────────────────────────────────────

    /** 認証情報からトークン所有ユーザーを引く。 */
    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId).orElse(null);
    }

    /** 難易度名 → song_definitions の difficulty コードへ変換。未知名は null。 */
    private String toDifficultyCode(String difficultyName) {
        if (difficultyName == null) return null;
        return switch (difficultyName) {
            case "BEGINNER"    -> "1";
            case "NORMAL"      -> "2";
            case "HYPER"       -> "3";
            case "ANOTHER"     -> "4";
            case "LEGGENDARIA" -> "10";
            default -> null;
        };
    }

    /** ユーザー情報ブロック。連携先で「誰のスコアか」を表示するための最低限。 */
    private Map<String, Object> buildUserBlock(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("iidxId", u.getIidxId());
        m.put("djName", u.getDisplayName());
        m.put("danRank", u.getDanRank());
        m.put("arenaRank", u.getArenaRank());
        m.put("totalBeatPt", u.getTotalBeatPt());
        m.put("totalKenbanPt", u.getTotalKenbanPt());
        m.put("totalSaraPt", u.getTotalSaraPt());
        return m;
    }

    /** 譜面メタ情報ブロック。連携先のキャッシュキーにも使える。 */
    private Map<String, Object> buildSongBlock(SongDefinition sd, String difficultyName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", sd.getTitle());
        m.put("artist", sd.getArtist());
        m.put("genre", sd.getGenre());
        m.put("difficulty", difficultyName);
        m.put("level", sd.getLevel());
        m.put("difficultyLevel", sd.getDifficultyLevel());
        m.put("notes", sd.getNotes());
        m.put("bpm", sd.getBpm());
        m.put("textage", sd.getTextage());
        m.put("wr", sd.getWr());
        m.put("avg", sd.getAvg());
        return m;
    }

    /** スコア情報ブロック。 */
    private Map<String, Object> buildScoreBlock(Score s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", s.getScore());
        m.put("djLevel", s.getDjLevel());
        m.put("clearType", s.getClearType());
        m.put("missCount", s.getMissCount());
        m.put("pgreat", s.getPgreat());
        m.put("great", s.getGreat());
        m.put("playCount", s.getPlayCount());
        m.put("uploadedAt", s.getUploadedAt());
        return m;
    }

    /** 順位ブロック。母数が 0 のときは null を返す呼び出し側設計だが、データ上は出ない想定。 */
    private Map<String, Object> buildRankBlock(UserSongRank r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank", r.getRank());
        m.put("total", r.getTotal());
        m.put("calculatedAt", r.getCalculatedAt());
        return m;
    }

    /**
     * 譜面傾向プロファイルから連携先向けのサマリだけ抜き出す。
     * 重い JSON（measureNotesJson / intervalDistJson 等）は含めない。
     */
    private Map<String, Object> buildTendencyBlock(ChartTendencyProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bpmMain", p.getBpmMain());
        m.put("isSoflan", p.getIsSoflan());
        m.put("notes", p.getNotes());
        m.put("scratchPct", p.getScratchPct());
        m.put("chordPct", p.getChordPct());
        m.put("singlePct", p.getSinglePct());
        m.put("jackPct", p.getJackPct());
        m.put("trillPct", p.getTrillPct());
        m.put("stairsPct", p.getStairsPct());
        m.put("dstairsPct", p.getDstairsPct());
        m.put("cnNotes", p.getCnNotes());
        m.put("tagsJson", p.getTagsJson());
        return m;
    }

    /**
     * 個別曲の更新履歴を score_history_logs の diffJson から抜き出す。
     *
     * 全履歴をユーザー単位で走査するが、ユーザーごとに通常は数十〜数百レコード
     * 程度なのでオンデマンド計算で十分。重くなれば SQL JOIN 化を検討する。
     */
    private List<Map<String, Object>> collectSongHistory(User user, String title, String difficultyName) {
        List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(user);
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> result = new ArrayList<>();

        for (ScoreHistoryLog log : logs) {
            String diffJson = log.getDiffJson();
            if (diffJson == null || diffJson.isEmpty() || "[]".equals(diffJson)) continue;
            try {
                List<Map<String, Object>> diffs = mapper.readValue(diffJson,
                        new TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> diff : diffs) {
                    if (title.equals(diff.get("title")) && difficultyName.equals(diff.get("difficulty"))) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("uploadedAt", log.getUploadedAt());
                        entry.put("score", diff.get("newScore"));
                        entry.put("beatPt", diff.get("newBeatPt"));
                        result.add(entry);
                    }
                }
            } catch (Exception ignored) {
                // 壊れた diffJson は静かにスキップ。
            }
        }

        // 新しい順に並べ替えて返す。
        result.sort((a, b) -> {
            Object ta = a.get("uploadedAt");
            Object tb = b.get("uploadedAt");
            return tb == null ? -1 : tb.toString().compareTo(ta == null ? "" : ta.toString());
        });
        return result;
    }
}
