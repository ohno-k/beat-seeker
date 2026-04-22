package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.ChartTendencyProfile;
import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRankSong;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ChartTendencyProfileRepository;
import com.beatseeker.backend.repository.DifficultyRankRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 【Service の役割】 譜面傾向プロファイル（ChartTendencyProfile）の
 * インポート／参照と、類似度ベースのスコア予測を提供する中核サービス。
 *
 * 責務:
 *  - chart_cache/profiles/ 配下の JSON（あるいは API ボディの JSON 配列）を一括取り込み
 *  - textage / level / difficulty などの条件で譜面プロファイルを取り出す
 *  - 指定譜面に対するユーザーの予測スコア算出（{@link #predictScore(User, String)}）
 *  - 全 ANOTHER/LEGGENDARIA 譜面について予測スコアを一括算出（BEAT-TIER 表示の元データ）
 *  - 2 曲間の類似度詳細のデバッグ出力
 *
 * 依存:
 *  - {@link ChartTendencyProfileRepository}: 譜面傾向プロファイル
 *  - {@link ScoreRepository}: ユーザースコア
 *  - {@link SongDefinitionRepository}: 公式曲情報（notes 数など）
 *  - {@link DifficultyRankRepository}: 非公式難易度テーブル
 *  - {@link ObjectMapper}: JSON シリアライズ／パース
 *
 * 主要ロジックの概観:
 *  - 類似度は 4 グループ（密度 / スクラッチ / 鍵盤パターン / CN）+ 難易度近さで独立計算し、
 *    各々 Math.exp(-α × d²) の形で正規化した後に乗算で結合する
 *  - 予測スコアは「類似度上位 20 件、重み = sim^4」の加重 50% 点（加重中央値）を採用し、
 *    対象譜面がプレイ済みの場合は自曲類似度を他曲の最高類似度に置き換えて支配を防ぐ
 *  - 計算で必要になる難易度・notes 情報は 1 度に一括取得してメモリ内マップに展開し、
 *    N+1 クエリを避ける
 */
@Service
public class ChartTendencyService {

    // ── 類似度・予測計算で用いる定数群（値は既存コード中のマジックナンバーを定数化） ──

    /**
     * 【類似度】 ガウシアン減衰のベース σ = 0.15 から作る 2σ² 相当値の入力側係数。
     * Math.exp(-d² / (2·σ²)) 形式で使うとき、σ の基準値を 0.15 とみなす設計。
     */
    private static final double SIMILARITY_SIGMA_BASE = 0.15;

    /**
     * 【類似度: 非公式難易度】 rankSim を exp(-3.0·dRank² / 2σ²) で減衰させる際の係数。
     * 大きいほど難易度差に対するペナルティが強くなる。
     */
    private static final double RANK_SIM_DECAY_COEF = 3.0;

    /**
     * 【類似度: 非公式難易度】 難易度差 (irA - irB) を正規化する分母。
     * 1.0 ランク差を基準単位として扱う。
     */
    private static final double RANK_DIFF_NORMALIZER = 1.0;

    /**
     * 【類似度: 密度】 nps 差を正規化する分母。秒間ノーツ 10 分の差を基準とみなす。
     */
    private static final double DENSITY_NPS_NORMALIZER = 10.0;

    /**
     * 【類似度: 密度】 eff16 / weightedEff16 差を正規化する分母。
     * 200 は eff16 の取りうるレンジに対して大きめに設定した正規化係数。
     */
    private static final double DENSITY_EFF16_NORMALIZER = 200.0;

    /**
     * 【類似度: 密度】 weightedEff16 を「秒間ノーツ近似」に換算するときの分母。
     */
    private static final double DENSITY_WEFF16_TO_NPS_DIVISOR = 15.0;

    /** 【類似度: 密度】 densityDist² 内で dNps² に掛ける重み係数。 */
    private static final double DENSITY_WEIGHT_NPS = 2.5;
    /** 【類似度: 密度】 densityDist² 内で dEff16² に掛ける重み係数。 */
    private static final double DENSITY_WEIGHT_EFF16 = 2.0;
    /** 【類似度: 密度】 densityDist² 内で dWEff16² に掛ける重み係数。 */
    private static final double DENSITY_WEIGHT_WEFF16 = 1.5;

    /** 【類似度: スクラッチ】 scrScalar の exp 係数。 */
    private static final double SCRATCH_SCALAR_DECAY_COEF = 1.5;
    /** 【類似度: スクラッチ】 scratchSim 合成時の scrScalar 比率。 */
    private static final double SCRATCH_SCALAR_WEIGHT = 0.5;
    /** 【類似度: スクラッチ】 scratchSim 合成時の scrIntervalSim 比率。 */
    private static final double SCRATCH_INTERVAL_WEIGHT = 0.5;

    /** 【類似度: 鍵盤パターン】 chordScalar の exp 係数。 */
    private static final double CHORD_SCALAR_DECAY_COEF = 1.0;
    /** 【類似度: 鍵盤パターン】 patternSim 合成時の chordScalar 比率。 */
    private static final double PATTERN_CHORD_WEIGHT = 0.35;
    /** 【類似度: 鍵盤パターン】 patternSim 合成時の intervalSim 比率。 */
    private static final double PATTERN_INTERVAL_WEIGHT = 0.65;

    /**
     * 【類似度: CN】 片側だけ CN がある場合のペナルティ係数。
     * cnRatio² に -3000 を掛けて exp することで 5% の CN 差で類似度がほぼ 0 になる急峻な減衰を作る。
     */
    private static final double CN_ONE_SIDED_DECAY_COEF = -3000.0;

    /** 【類似度: CN】 dCnRatio の正規化分母（0.25 = 25% を基準単位に）。 */
    private static final double CN_RATIO_NORMALIZER = 0.25;

    /** 【類似度: CN】 cnScalar 内の dCnRatio² 重み。 */
    private static final double CN_WEIGHT_RATIO = 1.5;
    /** 【類似度: CN】 cnScalar 内の dCnScratch² 重み。 */
    private static final double CN_WEIGHT_SCRATCH = 0.2;
    /** 【類似度: CN】 cnScalar 内の dCnKbdOverlap² 重み（本処理のみ。debug 版には存在しない）。 */
    private static final double CN_WEIGHT_KBD_OVERLAP = 1.2;
    /** 【類似度: CN】 cnSim 合成時の cnScalar 比率。 */
    private static final double CN_SCALAR_WEIGHT = 0.6;
    /** 【類似度: CN】 cnSim 合成時の cnIntervalSim 比率。 */
    private static final double CN_INTERVAL_WEIGHT = 0.4;

    /** 【類似度: 比重】 scrW の正規化分母（scratchPct 33% 以上でフルウェイト 1.0）。 */
    private static final double SCRATCH_WEIGHT_SATURATION_PCT = 33.0;
    /** 【類似度: 比重】 CN overlap / maxCnRatio を cnW に変換する倍率。 */
    private static final double CN_WEIGHT_MULTIPLIER = 3.0;

    /** 【予測】 重み = sim^EXP で指数関数的に類似度が高い曲を重視する指数値。 */
    private static final double PREDICTION_SIM_EXPONENT = 4.0;

    /** 【予測】 加重パーセンタイル位置（0.50 = 中央値）。 */
    private static final double PREDICTION_PERCENTILE = 0.50;

    /** 【予測】 類似度下限（これ以下の曲は除外）。 */
    private static final double SIMILARITY_MIN_THRESHOLD = 0.01;

    /** 【予測】 対象譜面の notes から最大スコアを算出する倍率（IIDX は 1 note = 2 点）。 */
    private static final int MAX_SCORE_PER_NOTE = 2;

    /** 【予測】 A ランクの下限 score rate（%）。これ未満は捨てスコアとして除外。 */
    private static final double SCORE_RATE_A_MIN = 66.67;

    /** 【予測】 難易度差 1.0 ランクにつき差し引く予測 score rate の減点（%）。 */
    private static final double RANK_GAP_PENALTY_PER_UNIT = 5.0;

    /** 【予測】 類似度の上位抽出件数（表示用・予測用とも）。 */
    private static final int PREDICTION_TOP_LIMIT = 20;

    /** 【表示丸め】 小数第 1 位まで丸める倍率（× → round → ÷）。 */
    private static final double ROUND_ONE_DECIMAL = 10.0;

    /** 【表示丸め】 小数第 2 位まで丸める倍率。 */
    private static final double ROUND_TWO_DECIMAL = 100.0;

    /** 【表示丸め】 小数第 3 位まで丸める倍率。類似度表示用。 */
    private static final double ROUND_THREE_DECIMAL = 1000.0;

    /** 【表示丸め】 小数第 4 位まで丸める倍率。デバッグ表示用。 */
    private static final double ROUND_FOUR_DECIMAL = 10000.0;

    /** 【表示】 比率 → パーセンテージ換算の倍率。 */
    private static final double PERCENT_MULTIPLIER = 100.0;

    /** 【インポート】 バルク INSERT のバッチサイズ。JPA の batch insert が効く件数に合わせる。 */
    private static final int BULK_INSERT_BATCH_SIZE = 500;

    /**
     * 【intervalコサイン類似度】 eff16 ≤ 50 のエントリ（極端に間隔が長い音符）は
     * 類似度計算から除外するための閾値。
     */
    private static final double INTERVAL_EFF16_IGNORE_THRESHOLD = 50.0;

    /** 【スクラッチ間隔類似度】 両方ともこの rate 未満なら「スクラッチ少ない曲同士」と判定（%）。 */
    private static final double SCRATCH_PCT_LOW = 10.0;

    /** 【スクラッチ間隔類似度】 この rate 以上なら「スクラッチ濃度高い曲」と判定（%）。 */
    private static final double SCRATCH_PCT_HIGH = 15.0;

    /** 【スクラッチ間隔類似度】 片側低・片側高のときに返す差異を示す中立値。 */
    private static final double SCRATCH_INTERVAL_DIFFERENT_SIDE = 0.2;

    /** 【スクラッチ間隔類似度】 片方未取得のときに返す中立値。 */
    private static final double SCRATCH_INTERVAL_NEUTRAL = 0.5;

    /** 【CN間隔類似度】 データ未取得時 / 空ベクトル時に返す中立値。 */
    private static final double CN_INTERVAL_NEUTRAL = 0.5;

    /** 譜面傾向プロファイルのリポジトリ（JPA） */
    private final ChartTendencyProfileRepository profileRepo;
    /** ユーザースコアのリポジトリ */
    private final ScoreRepository scoreRepo;
    /** 曲定義のリポジトリ（active 曲の notes / textage を参照） */
    private final SongDefinitionRepository songDefRepo;
    /** 非公式難易度テーブルのリポジトリ */
    private final DifficultyRankRepository difficultyRankRepo;
    /** Jackson JSON マッパー（JSON フィールドの読み書き） */
    private final ObjectMapper objectMapper;

    /** バルク INSERT 中に EntityManager#clear を呼ぶために保持 */
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    /**
     * 【コンストラクタ】 Spring が各 Repository と ObjectMapper を注入する。
     */
    public ChartTendencyService(ChartTendencyProfileRepository profileRepo,
                                ScoreRepository scoreRepo,
                                SongDefinitionRepository songDefRepo,
                                DifficultyRankRepository difficultyRankRepo,
                                ObjectMapper objectMapper) {
        this.profileRepo = profileRepo;
        this.scoreRepo = scoreRepo;
        this.songDefRepo = songDefRepo;
        this.difficultyRankRepo = difficultyRankRepo;
        this.objectMapper = objectMapper;
    }

    // ── インポート ──────────────────────────────────────────────

    /**
     * 【メソッドの役割】 chart_cache/profiles/ 以下の全 JSON ファイルを
     * 一気に DB へ登録する（全置換）。
     *
     * 処理の流れ:
     *  - 手順1: 再帰的に .json を走査して {@link ChartTendencyProfile} に変換
     *  - 手順2: textage をキーにしたマップに詰め、重複は後勝ちで排除
     *  - 手順3: 既存レコードを deleteAllInBatch で全削除
     *  - 手順4: 500 件ずつ saveAll + flush + entityManager.clear で効率的にバルク INSERT
     *
     * @param profilesDirPath profiles ディレクトリのパス (例: "../chart_cache/profiles")
     * @return 処理件数サマリー（inserted / replaced / skipped / total）
     * @throws IOException ディレクトリ走査に失敗した場合
     */
    @Transactional
    public Map<String, Object> importFromDirectory(String profilesDirPath) throws IOException {
        Path root = Paths.get(profilesDirPath);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + profilesDirPath);
        }

        int skipped = 0;

        // textage をキーにして重複を排除（後勝ち）
        Map<String, ChartTendencyProfile> profileMap = new LinkedHashMap<>();

        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> jsonFiles = paths
                    .filter(p -> p.toString().endsWith(".json"))
                    .collect(Collectors.toList());

            for (Path file : jsonFiles) {
                try {
                    JsonNode node = objectMapper.readTree(file.toFile());
                    ChartTendencyProfile profile = jsonToProfile(node);
                    if (profile == null || profile.getNotes() == null || profile.getNotes() == 0) {
                        skipped++;
                        continue;
                    }
                    profileMap.put(profile.getTextage(), profile);
                } catch (Exception e) {
                    skipped++;
                }
            }
        }

        List<ChartTendencyProfile> toSave = new ArrayList<>(profileMap.values());

        // 既存を全削除 → バルク INSERT（SELECT 不要で高速）
        int previousCount = (int) profileRepo.count();
        profileRepo.deleteAllInBatch();
        profileRepo.flush();

        // 500 件ずつ saveAll（JPA バッチ INSERT が効く）
        int batchSize = BULK_INSERT_BATCH_SIZE;
        for (int i = 0; i < toSave.size(); i += batchSize) {
            int end = Math.min(i + batchSize, toSave.size());
            profileRepo.saveAll(toSave.subList(i, end));
            profileRepo.flush();
            entityManager.clear();
        }

        return Map.of(
                "inserted", toSave.size(),
                "replaced", previousCount,
                "skipped", skipped,
                "total", toSave.size()
        );
    }

    /**
     * 【メソッドの役割】 API リクエストボディから渡された JSON 配列を直接インポートする。
     *
     * chart_cache ディレクトリが存在しない本番環境（コンテナデプロイ時）に使う。
     * 仕組みは {@link #importFromDirectory(String)} と同じで、既存を全削除してから
     * バッチ INSERT する。
     *
     * @param arrayNode 譜面プロファイルを含む JSON 配列
     * @return 処理件数サマリー
     */
    @Transactional
    public Map<String, Object> importFromJsonArray(JsonNode arrayNode) {
        if (!arrayNode.isArray()) {
            throw new IllegalArgumentException("Expected a JSON array");
        }

        int skipped = 0;
        Map<String, ChartTendencyProfile> profileMap = new LinkedHashMap<>();

        for (JsonNode node : arrayNode) {
            try {
                ChartTendencyProfile profile = jsonToProfile(node);
                if (profile == null || profile.getNotes() == null || profile.getNotes() == 0) {
                    skipped++;
                    continue;
                }
                profileMap.put(profile.getTextage(), profile);
            } catch (Exception e) {
                skipped++;
            }
        }

        List<ChartTendencyProfile> toSave = new ArrayList<>(profileMap.values());

        int previousCount = (int) profileRepo.count();
        profileRepo.deleteAllInBatch();
        profileRepo.flush();

        int batchSize = BULK_INSERT_BATCH_SIZE;
        for (int i = 0; i < toSave.size(); i += batchSize) {
            int end = Math.min(i + batchSize, toSave.size());
            profileRepo.saveAll(toSave.subList(i, end));
            profileRepo.flush();
            entityManager.clear();
        }

        return Map.of(
                "inserted", toSave.size(),
                "replaced", previousCount,
                "skipped", skipped,
                "total", toSave.size()
        );
    }

    /**
     * 譜面プロファイル JSON を ChartTendencyProfile エンティティに変換する。
     * textage が欠落している場合は null を返し、インポート側でスキップさせる。
     * tags / interval_dist / chord_dist / cn_interval_dist などの JSON フィールドは
     * 再シリアライズして文字列として保存する（エンティティ側は JSON TEXT カラム）。
     */
    private ChartTendencyProfile jsonToProfile(JsonNode n) {
        String textage = n.path("textage").asText(null);
        if (textage == null || textage.isBlank()) return null;

        ChartTendencyProfile p = new ChartTendencyProfile();
        p.setTextage(textage);
        p.setTitle(n.path("title").asText(null));
        p.setArtist(n.path("artist").asText(null));
        p.setDifficulty(n.path("difficulty").asText("4"));
        p.setLevel(n.has("level") ? n.path("level").asInt() : null);
        p.setBpmRaw(n.path("bpm_raw").asText(null));
        p.setBpmMain(n.has("bpm_main") ? n.path("bpm_main").asInt() : null);
        p.setIsSoflan(n.has("is_soflan") ? n.path("is_soflan").asBoolean() : null);
        p.setNotes(n.has("notes") ? n.path("notes").asInt() : null);
        p.setEvents(n.has("events") ? n.path("events").asInt() : null);
        p.setDominantEff16(n.has("dominant_eff16") ? n.path("dominant_eff16").asDouble() : null);
        p.setWeightedEff16(n.has("weighted_eff16") ? n.path("weighted_eff16").asDouble() : null);
        p.setScratchPct(n.has("scratch_pct") ? n.path("scratch_pct").asDouble() : null);
        p.setChordPct(n.has("chord_pct") ? n.path("chord_pct").asDouble() : null);
        p.setSinglePct(n.has("single_pct") ? n.path("single_pct").asDouble() : null);
        p.setRanuchi(n.has("ranuchi") ? n.path("ranuchi").asInt() : null);

        if (n.has("tags")) {
            try { p.setTagsJson(objectMapper.writeValueAsString(n.get("tags"))); }
            catch (Exception ignored) {}
        }
        if (n.has("interval_dist")) {
            try { p.setIntervalDistJson(objectMapper.writeValueAsString(n.get("interval_dist"))); }
            catch (Exception ignored) {}
        }
        if (n.has("chord_dist")) {
            try { p.setChordDistJson(objectMapper.writeValueAsString(n.get("chord_dist"))); }
            catch (Exception ignored) {}
        }
        if (n.has("cn_notes")) {
            p.setCnNotes(n.path("cn_notes").asInt());
        }
        if (n.has("cn_scratch_pct")) {
            p.setCnScratchPct(n.path("cn_scratch_pct").asDouble());
        }
        if (n.has("cn_kbd_overlap_pct")) {
            p.setCnKbdOverlapPct(n.path("cn_kbd_overlap_pct").asDouble());
        }
        if (n.has("cn_interval_dist")) {
            try { p.setCnIntervalDistJson(objectMapper.writeValueAsString(n.get("cn_interval_dist"))); }
            catch (Exception ignored) {}
        }
        if (n.has("kbd_interval_dist")) {
            try { p.setKbdIntervalDistJson(objectMapper.writeValueAsString(n.get("kbd_interval_dist"))); }
            catch (Exception ignored) {}
        }
        if (n.has("scr_interval_dist")) {
            try { p.setScrIntervalDistJson(objectMapper.writeValueAsString(n.get("scr_interval_dist"))); }
            catch (Exception ignored) {}
        }

        // 配置パターン属性（縦連・トリル・階段・二重階段）
        if (n.has("jack_count"))  p.setJackCount(n.path("jack_count").asInt());
        if (n.has("jack_notes"))  p.setJackNotes(n.path("jack_notes").asInt());
        if (n.has("jack_pct"))    p.setJackPct(n.path("jack_pct").asDouble());
        if (n.has("trill_count")) p.setTrillCount(n.path("trill_count").asInt());
        if (n.has("trill_notes")) p.setTrillNotes(n.path("trill_notes").asInt());
        if (n.has("trill_pct"))   p.setTrillPct(n.path("trill_pct").asDouble());
        if (n.has("stairs_count"))  p.setStairsCount(n.path("stairs_count").asInt());
        if (n.has("stairs_notes"))  p.setStairsNotes(n.path("stairs_notes").asInt());
        if (n.has("stairs_pct"))    p.setStairsPct(n.path("stairs_pct").asDouble());
        if (n.has("dstairs_count")) p.setDstairsCount(n.path("dstairs_count").asInt());
        if (n.has("dstairs_notes")) p.setDstairsNotes(n.path("dstairs_notes").asInt());
        if (n.has("dstairs_pct"))   p.setDstairsPct(n.path("dstairs_pct").asDouble());

        if (n.has("measure_notes")) {
            try { p.setMeasureNotesJson(objectMapper.writeValueAsString(n.get("measure_notes"))); }
            catch (Exception ignored) {}
        }
        if (n.has("measure_notes_kbd")) {
            try { p.setMeasureNotesKbdJson(objectMapper.writeValueAsString(n.get("measure_notes_kbd"))); }
            catch (Exception ignored) {}
        }
        if (n.has("measure_notes_scr")) {
            try { p.setMeasureNotesScrJson(objectMapper.writeValueAsString(n.get("measure_notes_scr"))); }
            catch (Exception ignored) {}
        }

        return p;
    }

    // ── 参照 ────────────────────────────────────────────────────

    /** textage による 1 件参照。存在しない場合は Optional.empty を返す。 */
    public Optional<ChartTendencyProfile> getByTextage(String textage) {
        return profileRepo.findById(textage);
    }

    /** 指定レベル（例: 12）の全譜面を返す。 */
    public List<ChartTendencyProfile> getByLevel(int level) {
        return profileRepo.findByLevel(level);
    }

    /** 指定レベル+難易度コード（"4"=ANOTHER 等）で絞り込む。 */
    public List<ChartTendencyProfile> getByLevelAndDifficulty(int level, String difficulty) {
        return profileRepo.findByLevelAndDifficulty(level, difficulty);
    }

    /** textage の共通プレフィックス（曲全体を示す部分）で全難易度のレコードを取得。 */
    public List<ChartTendencyProfile> getByTextageBase(String textageBase) {
        return profileRepo.findByTextageBase(textageBase);
    }

    // ── スコア予測 ───────────────────────────────────────────────

    /**
     * 【メソッドの役割】 指定 textage 譜面についてユーザーの予測スコアを算出して返す。
     *
     * 処理の流れ:
     *  - 手順1: 対象譜面のプロファイルと notes / informalRank を取得
     *  - 手順2: 全プロファイル・ユーザー全スコアを一括取得（N+1 クエリ回避）
     *  - 手順3: 対象に対してプロファイルごとの類似度を計算し、表示用/予測用のリストを作成
     *  - 手順4: 対象曲自身がプレイ済みの場合、自曲類似度を他曲の最大類似度に差し替える
     *    （そうしないと自分自身が支配して「現在値のまま」が予測になってしまう）
     *  - 手順5: 類似度 sim^4 で重み付けし、スコア率の加重 50% 点（加重中央値）を予測率とする
     *  - 手順6: 対象譜面が未プレイなら「難易度差」ペナルティを減点（1.0 ランク差で 5%）
     *  - 手順7: 予測率 × maxScore を丸めて予測スコアを算出
     *
     * @param user    ログインユーザー
     * @param textage 対象譜面の textage
     * @return 予測スコア、予測率、類似曲 TOP20 表示用データ等を含む Map
     */
    public Map<String, Object> predictScore(User user, String textage) {
        Optional<ChartTendencyProfile> targetOpt = profileRepo.findById(textage);
        if (targetOpt.isEmpty()) {
            return Map.of("error", "プロファイルが見つかりません: " + textage);
        }
        ChartTendencyProfile target = targetOpt.get();

        // song_data.json 由来のノーツ数マップ: "title\tdiff" → notes
        // ChartTendencyProfile.notes はtextage解析値で不正確なため使わない
        Map<String, Integer> notesMap = songDefRepo.findByRevision("active").stream()
                .filter(sd -> sd.getNotes() != null && sd.getNotes() > 0)
                .collect(Collectors.toMap(
                        sd -> sd.getTitle() + "\t" + sd.getDifficulty(),
                        SongDefinition::getNotes,
                        (a, b) -> a
                ));

        // 非公式難易度マップ: "title\tdiffCode" → rankValue
        Map<String, String> informalRankMap = buildInformalRankMap();
        Double irTarget = parseRank(informalRankMap.get(target.getTitle() + "\t" + target.getDifficulty()));

        int targetNotes = notesMap.getOrDefault(
                target.getTitle() + "\t" + target.getDifficulty(),
                target.getNotes() != null ? target.getNotes() : 0);
        if (targetNotes == 0) {
            return Map.of("error", "対象譜面のノーツ数が不明です");
        }

        // 全プロファイルを1回で取得してメモリ内 Map に展開（N+1クエリ回避）
        Map<String, ChartTendencyProfile> profileMap = profileRepo.findAll().stream()
                .collect(Collectors.toMap(
                        p -> p.getTitle() + "\t" + p.getDifficulty(),
                        p -> p,
                        (a, b) -> a
                ));

        // ユーザーの全スコアを1回で取得
        List<Score> userScores = scoreRepo.findByUserOrderByUploadedAtAsc(user);

        // ターゲット曲の現在スコアを取得
        String targetDiffName = target.getDifficulty().equals("10") ? "LEGGENDARIA" : "ANOTHER";
        Integer currentScore = userScores.stream()
                .filter(s -> target.getTitle().equals(s.getTitle()) && targetDiffName.equals(s.getDifficultyName()))
                .mapToInt(s -> s.getScore() != null ? s.getScore() : 0)
                .max().stream().boxed().findFirst().orElse(null);

        // ユーザーのスコアを "title\tdiffCode" → Score にマッピング（最高スコアのみ保持）
        Map<String, Score> bestScoreMap = new HashMap<>();
        for (Score s : userScores) {
            if (s.getScore() == null || s.getScore() <= 0) continue;
            String diffCode = diffNameToCode(s.getDifficultyName());
            if (diffCode == null) continue;
            String key = s.getTitle() + "\t" + diffCode;
            bestScoreMap.merge(key, s, (a, b) -> a.getScore() >= b.getScore() ? a : b);
        }

        // 全プロファイルに対して類似度を計算（未プレイ含む）
        List<Map<String, Object>> allSimilarities = new ArrayList<>();
        // プレイ済み＆A以上のみ予測に使用
        List<Map<String, Object>> playedForPrediction = new ArrayList<>();
        boolean targetSongPlayed = false; // 対象曲自身がプレイ済みかどうか

        for (ChartTendencyProfile sp : profileMap.values()) {
            // 対象曲自身もプレイ済みなら類似度100%で含める
            String diff = sp.getDifficulty();
            if (!"4".equals(diff) && !"10".equals(diff)) continue;

            Double irSong = parseRank(informalRankMap.get(sp.getTitle() + "\t" + diff));
            double sim = computeSimilarity(target, irTarget, sp, irSong);
            if (sim <= SIMILARITY_MIN_THRESHOLD) continue;

            String diffName = diff.equals("10") ? "LEGGENDARIA" : "ANOTHER";
            int spNotes = notesMap.getOrDefault(
                    sp.getTitle() + "\t" + diff,
                    sp.getNotes() != null ? sp.getNotes() : 0);

            Map<String, Object> entry = new HashMap<>();
            entry.put("title", sp.getTitle());
            entry.put("difficultyName", diffName);
            entry.put("textage", sp.getTextage() != null ? sp.getTextage() : "");
            entry.put("similarity", Math.round(sim * ROUND_THREE_DECIMAL) / ROUND_THREE_DECIMAL);
            entry.put("informalRank", irSong);

            // プレイ済みの場合はスコア情報を付与
            String scoreKey = sp.getTitle() + "\t" + diff;
            Score bestScore = bestScoreMap.get(scoreKey);
            if (bestScore != null && spNotes > 0) {
                int maxScore = spNotes * MAX_SCORE_PER_NOTE;
                double scoreRate = bestScore.getScore() * PERCENT_MULTIPLIER / maxScore;
                entry.put("score", bestScore.getScore());
                entry.put("scoreRate", Math.round(scoreRate * ROUND_ONE_DECIMAL) / ROUND_ONE_DECIMAL);
                entry.put("played", true);

                // A以上のプレイ済みスコアのみ予測計算に使用
                if (scoreRate >= SCORE_RATE_A_MIN) {
                    playedForPrediction.add(entry);
                    if (sp.getTextage() != null && sp.getTextage().equals(textage)) {
                        targetSongPlayed = true;
                    }
                }
            } else {
                entry.put("played", false);
            }

            allSimilarities.add(entry);
        }

        // 対象曲自身がプレイ済みの場合、予測計算用の類似度を
        // 他の類似譜面の最高類似度に合わせる（自分自身が支配的になるのを防ぐ）
        if (targetSongPlayed) {
            double maxOtherSim = 0;
            for (Map<String, Object> e : playedForPrediction) {
                String t = (String) e.get("textage");
                if (t != null && t.equals(textage)) continue;
                double s = (Double) e.get("similarity");
                if (s > maxOtherSim) maxOtherSim = s;
            }
            for (Map<String, Object> e : playedForPrediction) {
                String t = (String) e.get("textage");
                if (t != null && t.equals(textage)) {
                    e.put("similarityForPrediction", Math.round(maxOtherSim * ROUND_THREE_DECIMAL) / ROUND_THREE_DECIMAL);
                    break;
                }
            }
        }

        // 類似度降順、上位 20 件（表示用: 未プレイ含む）
        allSimilarities.sort((a, b) -> Double.compare((Double) b.get("similarity"), (Double) a.get("similarity")));
        List<Map<String, Object>> topDisplay = allSimilarities.stream().limit(PREDICTION_TOP_LIMIT).collect(Collectors.toList());

        // 予測計算用: プレイ済みのみ、類似度上位20件
        playedForPrediction.sort((a, b) -> Double.compare((Double) b.get("similarity"), (Double) a.get("similarity")));
        List<Map<String, Object>> topForPrediction = playedForPrediction.stream().limit(PREDICTION_TOP_LIMIT).collect(Collectors.toList());

        if (topForPrediction.isEmpty()) {
            Map<String, Object> noDataResult = new HashMap<>();
            noDataResult.put("textage", textage);
            noDataResult.put("title", orEmpty(target.getTitle()));
            noDataResult.put("notes", targetNotes);
            noDataResult.put("predictedScore", 0);
            noDataResult.put("predictedScoreRate", 0.0);
            noDataResult.put("similarSongs", topDisplay);
            noDataResult.put("message", "類似譜面のスコアデータがありません");
            return noDataResult;
        }

        // 加重50パーセンタイルスコア率（プレイ済みのみで予測）
        // 重み = sim^4 で指数関数的に類似度が高い曲を重視
        // 対象曲自身は similarityForPrediction（他曲の最高類似度）を使用
        final double EXP_POWER = PREDICTION_SIM_EXPONENT;
        double weightSum = 0;
        double weightedRank = 0;
        boolean hasRankData = false;
        for (Map<String, Object> e : topForPrediction) {
            double simForCalc = e.containsKey("similarityForPrediction")
                    ? (Double) e.get("similarityForPrediction")
                    : (Double) e.get("similarity");
            double w = Math.pow(simForCalc, EXP_POWER);
            weightSum += w;
            Double refRank = (Double) e.get("informalRank");
            if (refRank != null) {
                weightedRank += w * refRank;
                hasRankData = true;
            }
        }
        // スコア率の昇順にソートして加重累積50%の値を取得
        List<double[]> rateWeightPairs = topForPrediction.stream()
                .map(e -> {
                    double simForCalc = e.containsKey("similarityForPrediction")
                            ? (Double) e.get("similarityForPrediction")
                            : (Double) e.get("similarity");
                    return new double[]{(Double) e.get("scoreRate"), Math.pow(simForCalc, EXP_POWER)};
                })
                .sorted(Comparator.comparingDouble(p -> p[0]))
                .collect(Collectors.toList());
        double targetW50 = PREDICTION_PERCENTILE * weightSum;
        double cumW = 0;
        double predictedRate = rateWeightPairs.get(rateWeightPairs.size() - 1)[0];
        for (double[] rw : rateWeightPairs) {
            cumW += rw[1];
            if (cumW >= targetW50) { predictedRate = rw[0]; break; }
        }

        // ── 難易度差補正（対象曲自身がプレイ済みの場合は不要）────────
        if (!targetSongPlayed && hasRankData && irTarget != null && weightSum > 0) {
            double avgRefRank = weightedRank / weightSum;
            double rankGap = irTarget - avgRefRank;
            if (rankGap > 0) {
                predictedRate -= rankGap * RANK_GAP_PENALTY_PER_UNIT;
                predictedRate = Math.max(0, predictedRate);
            }
        }

        int predictedScore = (int) Math.round(predictedRate / PERCENT_MULTIPLIER * targetNotes * MAX_SCORE_PER_NOTE);

        Map<String, Object> result = new HashMap<>();
        result.put("textage", textage);
        result.put("title", orEmpty(target.getTitle()));
        result.put("difficulty", orEmpty(target.getDifficulty()));
        result.put("level", target.getLevel() != null ? target.getLevel() : 0);
        result.put("notes", targetNotes);
        result.put("dominantEff16", target.getDominantEff16() != null ? target.getDominantEff16() : 0);
        result.put("predictedScore", predictedScore);
        result.put("predictedScoreRate", Math.round(predictedRate * ROUND_ONE_DECIMAL) / ROUND_ONE_DECIMAL);
        result.put("similarSongs", topDisplay);
        if (currentScore != null) {
            double currentRate = currentScore * PERCENT_MULTIPLIER / (targetNotes * MAX_SCORE_PER_NOTE);
            result.put("currentScore", currentScore);
            result.put("currentScoreRate", Math.round(currentRate * ROUND_ONE_DECIMAL) / ROUND_ONE_DECIMAL);
        }
        return result;
    }

    /**
     * 【メソッドの役割】 全 ANOTHER/LEGGENDARIA 譜面についてユーザーの予測スコアを一括計算する。
     *
     * BEAT-TIER のページや一覧画面で、未プレイ曲の伸びしろを見積もるために使われる。
     * 難易度テーブル未収録（informalRank が無い）曲は BEAT-TIER の対象外なのでスキップする。
     *
     * 処理の流れ:
     *  - 手順1: 曲定義から notes / textage マップ、難易度テーブルから informalRank マップを構築
     *  - 手順2: 全プロファイルを取得し、{@code title\tdiffCode} をキーに索引化
     *  - 手順3: ユーザースコアを走査し、曲・難易度ごとの最高スコア率マップを作成
     *  - 手順4: 対象譜面 1 件ごとに、ユーザーの各スコアとの類似度と scoreRate ペアを集める
     *  - 手順5: sim^4 重み付きの中央値から予測率を算出、難易度差補正を適用
     *  - 手順6: プロファイル未収録だが実績スコアがある曲は、実績値そのものをそのまま予測値として補完
     *
     * @param user ログインユーザー
     * @return BEAT-TIER 向けに整形された予測スコアのリスト（各要素が 1 譜面に対応）
     */
    public List<Map<String, Object>> predictAllScores(User user) {
        // notes map + textage map (SongDefinition から一括取得)
        List<SongDefinition> activeSongDefs = songDefRepo.findByRevision("active");
        Map<String, Integer> notesMap = activeSongDefs.stream()
                .filter(sd -> sd.getNotes() != null && sd.getNotes() > 0)
                .collect(Collectors.toMap(
                        sd -> sd.getTitle() + "\t" + sd.getDifficulty(),
                        SongDefinition::getNotes,
                        (a, b) -> a
                ));
        // "title\tdiffCode" -> textage
        Map<String, String> textageMap = activeSongDefs.stream()
                .filter(sd -> sd.getTextage() != null)
                .collect(Collectors.toMap(
                        sd -> sd.getTitle() + "\t" + sd.getDifficulty(),
                        SongDefinition::getTextage,
                        (a, b) -> a
                ));

        // 非公式難易度マップ: "title\tdiffCode" -> rankValue
        Map<String, String> informalRankMap = buildInformalRankMap();

        // all profiles as map
        List<ChartTendencyProfile> allProfiles = profileRepo.findAll();
        Map<String, ChartTendencyProfile> profileMap = allProfiles.stream()
                .collect(Collectors.toMap(
                        p -> p.getTitle() + "\t" + p.getDifficulty(),
                        p -> p,
                        (a, b) -> a
                ));

        // user scores
        List<Score> userScores = scoreRepo.findByUserOrderByUploadedAtAsc(user);

        // actual score map: "title\tdiffCode" -> actual score rate (最高スコアのみ保持)
        Map<String, Double> actualRateMap = new HashMap<>();
        for (Score s : userScores) {
            if (s.getScore() == null || s.getScore() <= 0) continue;
            String diffCode = diffNameToCode(s.getDifficultyName());
            if (diffCode == null) continue;
            int spNotes = notesMap.getOrDefault(s.getTitle() + "\t" + diffCode, 0);
            if (spNotes == 0) continue;
            double rate = s.getScore() * PERCENT_MULTIPLIER / (spNotes * MAX_SCORE_PER_NOTE);
            String key = s.getTitle() + "\t" + diffCode;
            actualRateMap.merge(key, rate, (a, b) -> a > b ? a : b);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> coveredKeys = new HashSet<>();

        for (ChartTendencyProfile target : allProfiles) {
            String diff = target.getDifficulty();
            if (!"4".equals(diff) && !"10".equals(diff)) continue;

            String informalRank = informalRankMap.get(target.getTitle() + "\t" + diff);
            if (informalRank == null) continue;
            Double irTarget = parseRank(informalRank);

            int targetNotes = notesMap.getOrDefault(
                    target.getTitle() + "\t" + diff,
                    target.getNotes() != null ? target.getNotes() : 0);
            if (targetNotes == 0) continue;

            // compute similarities against all user scores
            List<double[]> simRatePairs = new ArrayList<>();
            for (Score s : userScores) {
                if (s.getScore() == null || s.getScore() <= 0) continue;
                String diffCode = diffNameToCode(s.getDifficultyName());
                if (diffCode == null) continue;

                ChartTendencyProfile sp = profileMap.get(s.getTitle() + "\t" + diffCode);
                if (sp == null) continue;

                Double irSong = parseRank(informalRankMap.get(s.getTitle() + "\t" + diffCode));
                double sim = computeSimilarity(target, irTarget, sp, irSong);
                if (sim <= 0) continue;

                int spNotes = notesMap.getOrDefault(
                        s.getTitle() + "\t" + diffCode,
                        sp.getNotes() != null ? sp.getNotes() : 0);
                if (spNotes == 0) continue;
                double scoreRate = s.getScore() * PERCENT_MULTIPLIER / (spNotes * MAX_SCORE_PER_NOTE);
                // A未満（66.67%未満）は捨てスコアとして除外
                if (scoreRate < SCORE_RATE_A_MIN) continue;
                // [sim, scoreRate, informalRank(なければNaN)]
                simRatePairs.add(new double[]{sim, scoreRate, irSong != null ? irSong : Double.NaN});
            }

            if (simRatePairs.isEmpty()) continue;

            simRatePairs.sort((a, b) -> Double.compare(b[0], a[0]));
            // 重み = sim^4 で指数関数的に類似度が高い曲を重視
            final double EXP_POW = PREDICTION_SIM_EXPONENT;
            double weightSum = 0, weightedRank = 0;
            boolean hasRank = false;
            int limit = Math.min(PREDICTION_TOP_LIMIT, simRatePairs.size());
            for (int i = 0; i < limit; i++) {
                double w = Math.pow(simRatePairs.get(i)[0], EXP_POW);
                double rk = simRatePairs.get(i)[2];
                weightSum += w;
                if (!Double.isNaN(rk)) {
                    weightedRank += w * rk;
                    hasRank = true;
                }
            }
            // 加重50パーセンタイルスコア率（中央値ベース → 伸びしろを残す）
            List<double[]> sorted50 = simRatePairs.subList(0, limit).stream()
                    .map(p -> new double[]{p[1], Math.pow(p[0], EXP_POW)}) // [scoreRate, weight]
                    .sorted(Comparator.comparingDouble(p -> p[0]))
                    .collect(Collectors.toList());
            double targetW50 = PREDICTION_PERCENTILE * weightSum;
            double cumW50 = 0;
            double predictedRate = sorted50.get(sorted50.size() - 1)[0];
            for (double[] rw : sorted50) {
                cumW50 += rw[1];
                if (cumW50 >= targetW50) { predictedRate = rw[0]; break; }
            }

            // 難易度差補正
            if (hasRank && irTarget != null && weightSum > 0) {
                double avgRefRank = weightedRank / weightSum;
                double rankGap = irTarget - avgRefRank;
                if (rankGap > 0) {
                    predictedRate -= rankGap * RANK_GAP_PENALTY_PER_UNIT;
                    predictedRate = Math.max(0, predictedRate);
                }
            }

            // 実績スコア（表示用に保持するが、予測の下限にはしない → 伸びしろを残す）
            Double actualRate = actualRateMap.get(target.getTitle() + "\t" + diff);

            int predictedScore = (int) Math.round(predictedRate / PERCENT_MULTIPLIER * targetNotes * MAX_SCORE_PER_NOTE);

            Map<String, Object> entry = new HashMap<>();
            entry.put("textage", target.getTextage());
            entry.put("title", orEmpty(target.getTitle()));
            entry.put("difficulty", diff);
            entry.put("level", target.getLevel() != null ? target.getLevel() : 0);
            entry.put("informalRank", informalRank);
            entry.put("notes", targetNotes);
            entry.put("predictedScore", predictedScore);
            entry.put("predictedScoreRate", Math.round(predictedRate * ROUND_ONE_DECIMAL) / ROUND_ONE_DECIMAL);
            coveredKeys.add(target.getTitle() + "\t" + diff);
            results.add(entry);
        }

        // プロファイル未収録だが実績スコアがある曲を補完
        // （難易度テーブル収録済みのもののみBEAT-TIER対象）
        for (Map.Entry<String, Double> e : actualRateMap.entrySet()) {
            String key = e.getKey();
            if (coveredKeys.contains(key)) continue;
            String informalRank = informalRankMap.get(key);
            if (informalRank == null) continue;
            int notes = notesMap.getOrDefault(key, 0);
            if (notes == 0) continue;

            double actualRate = e.getValue();
            int predictedScore = (int) Math.round(actualRate / PERCENT_MULTIPLIER * notes * MAX_SCORE_PER_NOTE);

            String[] parts = key.split("\t", 2);
            String title = parts[0];
            String diff = parts.length > 1 ? parts[1] : "";

            Map<String, Object> entry = new HashMap<>();
            entry.put("textage", textageMap.getOrDefault(key, ""));
            entry.put("title", title);
            entry.put("difficulty", diff);
            entry.put("level", 0);
            entry.put("informalRank", informalRank);
            entry.put("notes", notes);
            entry.put("predictedScore", predictedScore);
            entry.put("predictedScoreRate", Math.round(actualRate * ROUND_ONE_DECIMAL) / ROUND_ONE_DECIMAL);
            results.add(entry);
        }

        return results;
    }

    /**
     * 2 つの譜面プロファイルの類似度 (0〜1) を計算する。
     *
     * 4 つの独立グループの類似度を個別に求めて乗算することで、
     * どれか 1 グループが大きく外れれば全体が強く低下する設計。
     *
     * - 密度グループ: nps + dominantEff16 + weightedEff16 の差をガウシアンで減衰
     * - 難易度グループ: irA と irB の差を独立ガウシアン（1.0 差でほぼ 0 近くまで下げる）
     * - スクラッチグループ: scratchPct スカラー差 + scr_interval のコサイン類似度
     * - 鍵盤パターングループ: chordPct スカラー差 + kbd_interval のコサイン類似度
     * - CN グループ: cnRatio / cnKbdOverlap / cn_interval から計算し、片側だけ CN のときは強く減衰
     *
     * @param a   比較元プロファイル
     * @param irA a の非公式難易度値（未収録時は null）
     * @param b   比較先プロファイル
     * @param irB b の非公式難易度値（未収録時は null）
     * @return 0〜1 の類似度（0=完全に異なる、1=同一）
     */
    private double computeSimilarity(ChartTendencyProfile a, Double irA,
                                     ChartTendencyProfile b, Double irB) {
        // 同一譜面は必ず 1.0
        if (a.getTextage() != null && a.getTextage().equals(b.getTextage())) return 1.0;

        if (a.getDominantEff16() == null || b.getDominantEff16() == null) return 0;
        if (a.getScratchPct()    == null || b.getScratchPct()    == null) return 0;
        if (a.getChordPct()      == null || b.getChordPct()      == null) return 0;

        final double s2 = 2 * SIMILARITY_SIGMA_BASE * SIMILARITY_SIGMA_BASE;

        // ── Group1: 密度 ──────────────────────────────────────────────
        // nps: (notes/events) × (weightedEff16/15) ≈ 秒間ノーツ密度
        double npsA = (a.getNotes() != null && a.getEvents() != null && a.getEvents() > 0
                       && a.getWeightedEff16() != null)
                ? (a.getNotes().doubleValue() / a.getEvents()) * (a.getWeightedEff16() / DENSITY_WEFF16_TO_NPS_DIVISOR) : 0;
        double npsB = (b.getNotes() != null && b.getEvents() != null && b.getEvents() > 0
                       && b.getWeightedEff16() != null)
                ? (b.getNotes().doubleValue() / b.getEvents()) * (b.getWeightedEff16() / DENSITY_WEFF16_TO_NPS_DIVISOR) : 0;
        double dNps    = (npsA > 0 && npsB > 0) ? (npsA - npsB) / DENSITY_NPS_NORMALIZER : 0;
        double dEff16  = (a.getDominantEff16() - b.getDominantEff16()) / DENSITY_EFF16_NORMALIZER;
        double dWEff16 = (a.getWeightedEff16() != null && b.getWeightedEff16() != null)
                ? (a.getWeightedEff16() - b.getWeightedEff16()) / DENSITY_EFF16_NORMALIZER : 0;
        // ── 非公式難易度の類似度（独立グループ） ───────────────────────
        // dRank は密度グループから分離し、独立した乗算要因として扱う。
        // 難易度差 0.5 → ~0.76, 1.0 → ~0.33, 1.5 → ~0.11, 2.0 → ~0.02
        double rankSim = 1.0;
        if (irA != null && irB != null) {
            double dRank = (irA - irB) / RANK_DIFF_NORMALIZER; // 正規化: 1.0ランク差で大きく減衰
            rankSim = Math.exp(-RANK_SIM_DECAY_COEF * dRank * dRank / s2);
        }

        double densityDist2 = DENSITY_WEIGHT_NPS*dNps*dNps + DENSITY_WEIGHT_EFF16*dEff16*dEff16 + DENSITY_WEIGHT_WEFF16*dWEff16*dWEff16;
        double densitySim   = Math.exp(-densityDist2 / s2);

        // ── Group2: スクラッチ ────────────────────────────────────────
        // scratchPctの差（存在量）+ scrIntervalDistのコサイン類似度（リズムパターン）
        double dScratch     = (a.getScratchPct() - b.getScratchPct()) / PERCENT_MULTIPLIER;
        double scrScalar    = Math.exp(-SCRATCH_SCALAR_DECAY_COEF * dScratch * dScratch / s2);
        double scrIntervalSim = computeScrIntervalCosineSim(
                a.getScrIntervalDistJson(), b.getScrIntervalDistJson(),
                a.getScratchPct(), b.getScratchPct());
        double scratchSim   = SCRATCH_SCALAR_WEIGHT * scrScalar + SCRATCH_INTERVAL_WEIGHT * scrIntervalSim;

        // ── Group3: 鍵盤パターン ──────────────────────────────────────
        // chordPctの差（同時押し割合）+ kbdIntervalDist（スクラッチ除外）のコサイン類似度
        double dChord       = (a.getChordPct() - b.getChordPct()) / PERCENT_MULTIPLIER;
        double chordScalar  = Math.exp(-CHORD_SCALAR_DECAY_COEF * dChord * dChord / s2);
        double intervalSim  = computeIntervalCosineSim(a.getKbdIntervalDistJson(), b.getKbdIntervalDistJson());
        double patternSim   = PATTERN_CHORD_WEIGHT * chordScalar + PATTERN_INTERVAL_WEIGHT * intervalSim;

        // ── Group4: CN ────────────────────────────────────────────────
        // CN率・CNスクラッチ率・CN保持中の他鍵盤割合の差 + cnIntervalDistのコサイン類似度
        double cnRatioA = (a.getCnNotes() != null && a.getNotes() != null && a.getNotes() > 0)
                ? a.getCnNotes().doubleValue() / a.getNotes() : -1;
        double cnRatioB = (b.getCnNotes() != null && b.getNotes() != null && b.getNotes() > 0)
                ? b.getCnNotes().doubleValue() / b.getNotes() : -1;
        double cnSim;
        boolean cnOneSided = (cnRatioA >= 0) != (cnRatioB >= 0);
        if (cnOneSided) {
            // 片方だけCNがある場合: CN率が高いほど類似度を大きく下げる
            // cnRatio=0.01(1%) → 0.74, 0.03(3%) → 0.07, 0.05(5%) → 0.00, 0.07(7%) → 0.00
            double cnRatioPresent = Math.max(cnRatioA, cnRatioB);
            cnSim = Math.exp(CN_ONE_SIDED_DECAY_COEF * cnRatioPresent * cnRatioPresent);
        } else {
            double dCnRatio   = (cnRatioA >= 0 && cnRatioB >= 0) ? (cnRatioA - cnRatioB) / CN_RATIO_NORMALIZER : 0;
            double dCnScratch = (a.getCnScratchPct() != null && b.getCnScratchPct() != null)
                    ? (a.getCnScratchPct() - b.getCnScratchPct()) / PERCENT_MULTIPLIER : 0;
            // cn_kbd_overlap_pct: CN保持中に他の鍵盤が降ってくる割合 — CNの「難しさ」を最もよく表す
            double dCnKbdOverlap = (a.getCnKbdOverlapPct() != null && b.getCnKbdOverlapPct() != null)
                    ? (a.getCnKbdOverlapPct() - b.getCnKbdOverlapPct()) / PERCENT_MULTIPLIER : 0;
            // dCnRatio重み1.5: CN量の差を強く反映（5%差→軽微, 10%差→中程度, 15%差→大きなペナルティ）
            double cnScalar      = Math.exp(-(CN_WEIGHT_RATIO*dCnRatio*dCnRatio + CN_WEIGHT_SCRATCH*dCnScratch*dCnScratch + CN_WEIGHT_KBD_OVERLAP*dCnKbdOverlap*dCnKbdOverlap) / s2);
            double cnIntervalSim = computeCnIntervalCosineSim(a.getCnIntervalDistJson(), b.getCnIntervalDistJson());
            cnSim = CN_SCALAR_WEIGHT * cnScalar + CN_INTERVAL_WEIGHT * cnIntervalSim;
        }

        // ── 比重: 各グループのノーツ割合に応じてべき乗で重み付け ──────
        double scrW = Math.min(1.0, Math.max(a.getScratchPct(), b.getScratchPct()) / SCRATCH_WEIGHT_SATURATION_PCT);
        double kbdW = 1.0 - ((a.getScratchPct() + b.getScratchPct()) / 2.0) / PERCENT_MULTIPLIER;
        // cnW: CN量の大きい方を基準に重みを決定（overlap + cnRatio両方考慮）
        // CN量に差がある場合もペナルティが確実に効くよう max ベースで計算
        double overlapA = (a.getCnKbdOverlapPct() != null) ? a.getCnKbdOverlapPct() / PERCENT_MULTIPLIER : 0;
        double overlapB = (b.getCnKbdOverlapPct() != null) ? b.getCnKbdOverlapPct() / PERCENT_MULTIPLIER : 0;
        double maxCnRatio = Math.max(cnRatioA >= 0 ? cnRatioA : 0, cnRatioB >= 0 ? cnRatioB : 0);
        double cnW;
        if (cnOneSided) {
            // 片方だけCNがある場合: 常にフルウェイト（CNの有無は決定的な差異）
            cnW = 1.0;
        } else {
            // 両方CNあり: overlap平均×3 と maxCnRatio×3 の大きい方を採用
            double overlapBased = (overlapA + overlapB) / 2.0 * CN_WEIGHT_MULTIPLIER;
            double ratioBased   = maxCnRatio * CN_WEIGHT_MULTIPLIER;
            cnW = Math.min(1.0, Math.max(overlapBased, ratioBased));
        }

        // ── 統合: 密度・難易度は常にフル、他は割合ベきで乗算 ──────────
        double combined = densitySim
                * rankSim
                * Math.pow(scratchSim, scrW)
                * Math.pow(patternSim, kbdW)
                * Math.pow(cnSim, cnW);

        return Math.min(1.0, combined);
    }

    /**
     * 【メソッドの役割】 2 曲間の類似度計算の中間値を全て返すデバッグ用エンドポイント。
     *
     * {@link #computeSimilarity(ChartTendencyProfile, Double, ChartTendencyProfile, Double)}
     * と同じロジックを辿りつつ、各グループのスカラー差、コサイン類似度、最終寄与（べき乗後）などを
     * すべて LinkedHashMap に詰めて返す。管理画面で類似度の妥当性を確認する用途。
     *
     * @param textageA 比較元譜面の textage
     * @param textageB 比較先譜面の textage
     * @return 各グループの計算過程を詰めたネスト Map
     */
    public Map<String, Object> computeSimilarityDebug(String textageA, String textageB) {
        ChartTendencyProfile a = profileRepo.findById(textageA).orElse(null);
        ChartTendencyProfile b = profileRepo.findById(textageB).orElse(null);
        if (a == null || b == null) return Map.of("error", (Object)"プロファイルが見つかりません");

        Map<String, String> informalRankMap = buildInformalRankMap();
        Double irA = parseRank(informalRankMap.get(a.getTitle() + "\t" + a.getDifficulty()));
        Double irB = parseRank(informalRankMap.get(b.getTitle() + "\t" + b.getDifficulty()));

        final double s2 = 2 * SIMILARITY_SIGMA_BASE * SIMILARITY_SIGMA_BASE;

        // Group1: 密度
        double npsA = (a.getNotes() != null && a.getEvents() != null && a.getEvents() > 0 && a.getWeightedEff16() != null)
                ? (a.getNotes().doubleValue() / a.getEvents()) * (a.getWeightedEff16() / DENSITY_WEFF16_TO_NPS_DIVISOR) : 0;
        double npsB = (b.getNotes() != null && b.getEvents() != null && b.getEvents() > 0 && b.getWeightedEff16() != null)
                ? (b.getNotes().doubleValue() / b.getEvents()) * (b.getWeightedEff16() / DENSITY_WEFF16_TO_NPS_DIVISOR) : 0;
        double dNps    = (npsA > 0 && npsB > 0) ? (npsA - npsB) / DENSITY_NPS_NORMALIZER : 0;
        double dEff16  = (a.getDominantEff16() != null && b.getDominantEff16() != null) ? (a.getDominantEff16() - b.getDominantEff16()) / DENSITY_EFF16_NORMALIZER : 0;
        double dWEff16 = (a.getWeightedEff16() != null && b.getWeightedEff16() != null) ? (a.getWeightedEff16() - b.getWeightedEff16()) / DENSITY_EFF16_NORMALIZER : 0;
        // 非公式難易度の類似度（独立グループ）
        double rankSim_d = 1.0;
        double dRank = 0;
        if (irA != null && irB != null) {
            dRank = (irA - irB) / RANK_DIFF_NORMALIZER;
            rankSim_d = Math.exp(-RANK_SIM_DECAY_COEF * dRank * dRank / s2);
        }
        double densityDist2 = DENSITY_WEIGHT_NPS*dNps*dNps + DENSITY_WEIGHT_EFF16*dEff16*dEff16 + DENSITY_WEIGHT_WEFF16*dWEff16*dWEff16;
        double densitySim   = Math.exp(-densityDist2 / s2);

        // Group2: スクラッチ
        double dScratch      = (a.getScratchPct() != null && b.getScratchPct() != null) ? (a.getScratchPct() - b.getScratchPct()) / PERCENT_MULTIPLIER : 0;
        double scrScalar     = Math.exp(-SCRATCH_SCALAR_DECAY_COEF * dScratch * dScratch / s2);
        double scrIntervalSim = computeScrIntervalCosineSim(a.getScrIntervalDistJson(), b.getScrIntervalDistJson(), a.getScratchPct(), b.getScratchPct());
        double scratchSim    = SCRATCH_SCALAR_WEIGHT * scrScalar + SCRATCH_INTERVAL_WEIGHT * scrIntervalSim;

        // Group3: 鍵盤パターン
        double dChord      = (a.getChordPct() != null && b.getChordPct() != null) ? (a.getChordPct() - b.getChordPct()) / PERCENT_MULTIPLIER : 0;
        double chordScalar = Math.exp(-CHORD_SCALAR_DECAY_COEF * dChord * dChord / s2);
        double intervalSim = computeIntervalCosineSim(a.getIntervalDistJson(), b.getIntervalDistJson());
        double patternSim  = PATTERN_CHORD_WEIGHT * chordScalar + PATTERN_INTERVAL_WEIGHT * intervalSim;

        // Group4: CN
        double cnRatioA = (a.getCnNotes() != null && a.getNotes() != null && a.getNotes() > 0) ? a.getCnNotes().doubleValue() / a.getNotes() : -1;
        double cnRatioB = (b.getCnNotes() != null && b.getNotes() != null && b.getNotes() > 0) ? b.getCnNotes().doubleValue() / b.getNotes() : -1;
        double cnSim;
        double cnScalar = 0, cnIntervalSim = 0;
        double dCnRatio = 0, dCnScratch = 0;
        boolean cnOneSided_d = (cnRatioA >= 0) != (cnRatioB >= 0);
        if (cnOneSided_d) {
            double cnRatioPresent = Math.max(cnRatioA, cnRatioB);
            cnSim = Math.exp(CN_ONE_SIDED_DECAY_COEF * cnRatioPresent * cnRatioPresent);
        } else {
            dCnRatio   = (cnRatioA >= 0 && cnRatioB >= 0) ? (cnRatioA - cnRatioB) / CN_RATIO_NORMALIZER : 0;
            dCnScratch = (a.getCnScratchPct() != null && b.getCnScratchPct() != null) ? (a.getCnScratchPct() - b.getCnScratchPct()) / PERCENT_MULTIPLIER : 0;
            cnScalar      = Math.exp(-(CN_WEIGHT_RATIO*dCnRatio*dCnRatio + CN_WEIGHT_SCRATCH*dCnScratch*dCnScratch) / s2);
            cnIntervalSim = computeCnIntervalCosineSim(a.getCnIntervalDistJson(), b.getCnIntervalDistJson());
            cnSim         = CN_SCALAR_WEIGHT * cnScalar + CN_INTERVAL_WEIGHT * cnIntervalSim;
        }

        // 比重
        double scrW = (a.getScratchPct() != null && b.getScratchPct() != null) ? Math.min(1.0, Math.max(a.getScratchPct(), b.getScratchPct()) / SCRATCH_WEIGHT_SATURATION_PCT) : 0;
        double kbdW = (a.getScratchPct() != null && b.getScratchPct() != null) ? 1.0 - ((a.getScratchPct() + b.getScratchPct()) / 2.0) / PERCENT_MULTIPLIER : 1.0;
        double overlapA_d = (a.getCnKbdOverlapPct() != null) ? a.getCnKbdOverlapPct() / PERCENT_MULTIPLIER : 0;
        double overlapB_d = (b.getCnKbdOverlapPct() != null) ? b.getCnKbdOverlapPct() / PERCENT_MULTIPLIER : 0;
        double maxCnRatio_d = Math.max(cnRatioA >= 0 ? cnRatioA : 0, cnRatioB >= 0 ? cnRatioB : 0);
        double cnW;
        if (cnOneSided_d) {
            cnW = 1.0;
        } else {
            double overlapBased = (overlapA_d + overlapB_d) / 2.0 * CN_WEIGHT_MULTIPLIER;
            double ratioBased   = maxCnRatio_d * CN_WEIGHT_MULTIPLIER;
            cnW = Math.min(1.0, Math.max(overlapBased, ratioBased));
        }

        double combined = densitySim * rankSim_d * Math.pow(scratchSim, scrW) * Math.pow(patternSim, kbdW) * Math.pow(cnSim, cnW);
        double finalSim = Math.min(1.0, combined);

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("songA", Map.of("title", orEmpty(a.getTitle()), "difficulty", orEmpty(a.getDifficulty()), "informalRank", irA != null ? irA : "N/A"));
        debug.put("songB", Map.of("title", orEmpty(b.getTitle()), "difficulty", orEmpty(b.getDifficulty()), "informalRank", irB != null ? irB : "N/A"));

        Map<String, Object> rawA = new LinkedHashMap<>();
        rawA.put("nps", Math.round(npsA * ROUND_TWO_DECIMAL) / ROUND_TWO_DECIMAL);
        rawA.put("dominantEff16", a.getDominantEff16());
        rawA.put("weightedEff16", a.getWeightedEff16());
        rawA.put("scratchPct", a.getScratchPct());
        rawA.put("chordPct", a.getChordPct());
        rawA.put("cnRatio", cnRatioA >= 0 ? Math.round(cnRatioA * ROUND_THREE_DECIMAL) / ROUND_ONE_DECIMAL + "%" : "N/A");
        debug.put("rawA", rawA);

        Map<String, Object> rawB = new LinkedHashMap<>();
        rawB.put("nps", Math.round(npsB * ROUND_TWO_DECIMAL) / ROUND_TWO_DECIMAL);
        rawB.put("dominantEff16", b.getDominantEff16());
        rawB.put("weightedEff16", b.getWeightedEff16());
        rawB.put("scratchPct", b.getScratchPct());
        rawB.put("chordPct", b.getChordPct());
        rawB.put("cnRatio", cnRatioB >= 0 ? Math.round(cnRatioB * ROUND_THREE_DECIMAL) / ROUND_ONE_DECIMAL + "%" : "N/A");
        debug.put("rawB", rawB);

        Map<String, Object> g1 = new LinkedHashMap<>();
        g1.put("dNps_norm", Math.round(dNps * ROUND_THREE_DECIMAL) / ROUND_THREE_DECIMAL);
        g1.put("dEff16_norm", Math.round(dEff16 * ROUND_THREE_DECIMAL) / ROUND_THREE_DECIMAL);
        g1.put("dWEff16_norm", Math.round(dWEff16 * ROUND_THREE_DECIMAL) / ROUND_THREE_DECIMAL);
        g1.put("dist2", Math.round(densityDist2 * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        g1.put("densitySim", Math.round(densitySim * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        debug.put("group1_density", g1);

        Map<String, Object> gRank = new LinkedHashMap<>();
        gRank.put("dRank_norm", Math.round(dRank * ROUND_THREE_DECIMAL) / ROUND_THREE_DECIMAL);
        gRank.put("rankSim", Math.round(rankSim_d * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        debug.put("group_rank", gRank);

        Map<String, Object> g2 = new LinkedHashMap<>();
        g2.put("dScratchPct_norm", Math.round(dScratch * ROUND_THREE_DECIMAL) / ROUND_THREE_DECIMAL);
        g2.put("scrScalar", Math.round(scrScalar * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        g2.put("scrIntervalCosineSim", Math.round(scrIntervalSim * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        g2.put("scratchSim", Math.round(scratchSim * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        g2.put("weight(scrW)", Math.round(scrW * ROUND_THREE_DECIMAL) / ROUND_THREE_DECIMAL);
        g2.put("contribution", Math.round(Math.pow(scratchSim, scrW) * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        debug.put("group2_scratch", g2);

        Map<String, Object> g3 = new LinkedHashMap<>();
        g3.put("dChordPct_norm", Math.round(dChord * ROUND_THREE_DECIMAL) / ROUND_THREE_DECIMAL);
        g3.put("chordScalar", Math.round(chordScalar * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        g3.put("kbdIntervalCosineSim", Math.round(intervalSim * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        g3.put("patternSim", Math.round(patternSim * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        g3.put("weight(kbdW)", Math.round(kbdW * ROUND_THREE_DECIMAL) / ROUND_THREE_DECIMAL);
        g3.put("contribution", Math.round(Math.pow(patternSim, kbdW) * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        debug.put("group3_pattern", g3);

        Map<String, Object> g4 = new LinkedHashMap<>();
        g4.put("cnOneSided", cnOneSided_d);
        g4.put("cnRatioA", cnRatioA >= 0 ? Math.round(cnRatioA * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL : "N/A");
        g4.put("cnRatioB", cnRatioB >= 0 ? Math.round(cnRatioB * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL : "N/A");
        g4.put("cnKbdOverlapPctA", a.getCnKbdOverlapPct());
        g4.put("cnKbdOverlapPctB", b.getCnKbdOverlapPct());
        g4.put("dCnRatio_norm", Math.round(dCnRatio * ROUND_THREE_DECIMAL) / ROUND_THREE_DECIMAL);
        g4.put("dCnScratch_norm", Math.round(dCnScratch * ROUND_THREE_DECIMAL) / ROUND_THREE_DECIMAL);
        g4.put("cnScalar", Math.round(cnScalar * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        g4.put("cnIntervalCosineSim", Math.round(cnIntervalSim * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        g4.put("cnSim", Math.round(cnSim * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        g4.put("weight(cnW)", Math.round(cnW * ROUND_THREE_DECIMAL) / ROUND_THREE_DECIMAL);
        g4.put("contribution", Math.round(Math.pow(cnSim, cnW) * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        debug.put("group4_cn", g4);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("combined", Math.round(combined * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        result.put("finalSimilarity", Math.round(finalSim * ROUND_FOUR_DECIMAL) / ROUND_FOUR_DECIMAL);
        result.put("finalSimilarityPct", Math.round(finalSim * ROUND_FOUR_DECIMAL) / ROUND_TWO_DECIMAL + "%");
        debug.put("result", result);

        return debug;
    }

    /** 非公式難易度マップを構築: "title\tdiffCode" → rankValue */
    private Map<String, String> buildInformalRankMap() {
        Map<String, String> map = new HashMap<>();
        for (DifficultyRank rank : difficultyRankRepo.findByRevisionOrderBySortOrderAsc("active")) {
            for (DifficultyRankSong song : rank.getSongs()) {
                String t = song.getSongTitle();
                if (t.endsWith(" [L]")) {
                    map.put(t.substring(0, t.length() - 4) + "\t10", rank.getRankValue());
                } else {
                    map.put(t + "\t4", rank.getRankValue());
                }
            }
        }
        return map;
    }

    /** "11.5" などの文字列を Double に変換。パース失敗時は null */
    private Double parseRank(String rankStr) {
        if (rankStr == null) return null;
        try { return Double.parseDouble(rankStr); }
        catch (NumberFormatException e) { return null; }
    }

    /**
     * interval_dist JSON 同士のコサイン類似度を返す (0〜1)。
     * 各インターバルキーの pct 値を疎ベクトルとして扱う。
     */
    private double computeIntervalCosineSim(String jsonA, String jsonB) {
        if (jsonA == null || jsonB == null) return 0;
        try {
            Map<String, Double> vecA = parseIntervalPctVector(jsonA);
            Map<String, Double> vecB = parseIntervalPctVector(jsonB);
            if (vecA.isEmpty() || vecB.isEmpty()) return 0;

            // dot product（共通キーのみ）
            double dot = 0;
            for (Map.Entry<String, Double> e : vecA.entrySet()) {
                Double bVal = vecB.get(e.getKey());
                if (bVal != null) dot += e.getValue() * bVal;
            }

            // ノルム
            double normA = Math.sqrt(vecA.values().stream().mapToDouble(v -> v * v).sum());
            double normB = Math.sqrt(vecB.values().stream().mapToDouble(v -> v * v).sum());

            if (normA == 0 || normB == 0) return 0;
            return dot / (normA * normB);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * interval_dist JSON → Map<intervalKey, pct>
     * eff16 ≤ 50 のエントリ（極端に間隔が長い音符）は類似度計算から除外する。
     */
    private Map<String, Double> parseIntervalPctVector(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        Map<String, Double> vec = new HashMap<>();
        root.fields().forEachRemaining(e -> {
            JsonNode entry = e.getValue();
            JsonNode eff16Node = entry.get("eff16");
            if (eff16Node != null && eff16Node.asDouble() <= INTERVAL_EFF16_IGNORE_THRESHOLD) return; // eff16 ≤ 50 は除外
            JsonNode pctNode = entry.get("pct");
            if (pctNode != null) vec.put(e.getKey(), pctNode.asDouble());
        });
        return vec;
    }

    /**
     * scr_interval_dist JSON 同士のコサイン類似度を返す (0〜1)。
     * 両方ともスクラッチが少ない曲（scratchPct < 3）の場合は中立値 1.0。
     * どちらか一方のみデータがない場合は中立値 0.5。
     */
    private double computeScrIntervalCosineSim(String jsonA, String jsonB,
                                               Double scrPctA, Double scrPctB) {
        double pA = scrPctA != null ? scrPctA : 0.0;
        double pB = scrPctB != null ? scrPctB : 0.0;
        boolean lowA  = pA < SCRATCH_PCT_LOW;
        boolean lowB  = pB < SCRATCH_PCT_LOW;
        boolean highA = pA >= SCRATCH_PCT_HIGH;
        boolean highB = pB >= SCRATCH_PCT_HIGH;

        if (lowA && lowB) return 1.0;   // 両方スクラッチ少ない → 差なし
        // 片方が低（<10%）、もう片方が高（≥15%）→ 明らかに異なる
        if ((lowA && highB) || (lowB && highA)) return SCRATCH_INTERVAL_DIFFERENT_SIDE;
        if (jsonA == null || jsonB == null) return SCRATCH_INTERVAL_NEUTRAL; // 片方未取得 → 中立
        try {
            Map<String, Double> vecA = parseIntervalPctVector(jsonA);
            Map<String, Double> vecB = parseIntervalPctVector(jsonB);
            if (vecA.isEmpty() && vecB.isEmpty()) return 1.0;
            if (vecA.isEmpty() || vecB.isEmpty()) return SCRATCH_INTERVAL_NEUTRAL;
            double dot = 0;
            for (Map.Entry<String, Double> e : vecA.entrySet()) {
                Double bVal = vecB.get(e.getKey());
                if (bVal != null) dot += e.getValue() * bVal;
            }
            double normA = Math.sqrt(vecA.values().stream().mapToDouble(v -> v * v).sum());
            double normB = Math.sqrt(vecB.values().stream().mapToDouble(v -> v * v).sum());
            if (normA == 0 || normB == 0) return SCRATCH_INTERVAL_NEUTRAL;
            return dot / (normA * normB);
        } catch (Exception e) {
            return SCRATCH_INTERVAL_NEUTRAL;
        }
    }

    /**
     * cn_interval_dist JSON 同士のコサイン類似度を返す (0〜1)。
     * どちらかが null（CNデータ未取得）の場合は中立値 0.5 を返す。
     */
    private double computeCnIntervalCosineSim(String jsonA, String jsonB) {
        if (jsonA == null || jsonB == null) return CN_INTERVAL_NEUTRAL; // データ未取得時は中立
        try {
            Map<String, Double> vecA = parseIntervalPctVector(jsonA);
            Map<String, Double> vecB = parseIntervalPctVector(jsonB);
            if (vecA.isEmpty() || vecB.isEmpty()) return CN_INTERVAL_NEUTRAL; // CN無し曲同士は中立

            double dot = 0;
            for (Map.Entry<String, Double> e : vecA.entrySet()) {
                Double bVal = vecB.get(e.getKey());
                if (bVal != null) dot += e.getValue() * bVal;
            }
            double normA = Math.sqrt(vecA.values().stream().mapToDouble(v -> v * v).sum());
            double normB = Math.sqrt(vecB.values().stream().mapToDouble(v -> v * v).sum());
            if (normA == 0 || normB == 0) return CN_INTERVAL_NEUTRAL;
            return dot / (normA * normB);
        } catch (Exception e) {
            return CN_INTERVAL_NEUTRAL;
        }
    }


    /**
     * 難易度名 → 難易度コード変換。BEAT-TIER 対象の ANOTHER/LEGGENDARIA 以外は null。
     */
    private String diffNameToCode(String difficultyName) {
        if ("ANOTHER".equalsIgnoreCase(difficultyName)) return "4";
        if ("LEGGENDARIA".equalsIgnoreCase(difficultyName)) return "10";
        return null;
    }

    /** null 文字列を空文字に畳むユーティリティ。 */
    private String orEmpty(String s) {
        return s != null ? s : "";
    }
}
