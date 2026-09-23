package com.beatseeker.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【Service の役割】 AAA ロードマップページ（{@code /api/scores/score-roadmap}、管理者専用）の
 * 推定結果を作り、表示するサービス。
 *
 * 目的:
 *  - ANOTHER / LEGGENDARIA 全譜面（☆1〜12）について「AAA（または MAX-）を取る難しさ」を
 *    1 本の目盛りに並べ、初めての AAA から全 AAA までの段階的な道のりを作る。
 *  - 到達率をそのまま比べると、☆10 以下は遊んだ人の実力が譜面ごとに違う（選択バイアス）ため
 *    並びが崩れる。そこで実力アンカー付きのラッシュモデルで推定する。
 *
 * モデル（ラインごとに独立に推定。ライン = AAA は桶 160、MAX- は桶 170。桶は理論値の 1/180 単位）:
 * （2026-09-23 に AAA / MAX- を 1 本のロードマップへ統合。目標 = 譜面 × ライン、1 人 1 つの θ）
 *  - P(プレイヤー p が目標 (譜面 j, ライン L) を達成) = σ(k_L・(θ_p − d_{j,L}))
 *  - ☆11/12 のうち active 難易度表に載っている譜面の MAX- 目標は d を表の値に固定（アンカー）。
 *    これで θ と全目標の d が難易度表と同じ目盛り（11.0〜13.1）に載り、AAA 目標と MAX- 目標を直接比べられる。
 *  - 手順: (1) アンカーを 30 譜面以上遊んだ人の θ と傾き k を交互に推定
 *          (2) θ を固定して、ラインごとに全目標の d と k_L を交互に推定（弱い事前分布: 公式レベルからの目安、σ=1）
 *          (3) d・k を固定して全ユーザーの θ を両ラインの全目標から推定し直す（☆11/12 を遊ばない人にも θ を出す）
 *  - Newton 法の 1 歩は ±0.3 に制限する（初期値が遠いとヘッセ行列がほぼ 0 で発散するため）。
 *  - 2026-09-23 に Node で同じ手順を本番データに当てた検算: 表 11.0〜11.4 を隠して予測した平均誤差 0.10。
 *
 * 「土台 + 差分」の二層構成（2026-09-23 ユーザー要望: 読み込みを速く）:
 *  - 土台（バッチ）: 全譜面の d・到達率、全プレイヤーの θ 分布。約 100 万行を読む重い計算（本番 60 秒前後）なので
 *    {@link #tick()} が 10 分おきに鮮度を見て、無い or {@link #BASE_STALE_AFTER_MS}（3 時間）超なら作り直す。
 *    作った土台は {@code score_roadmap_snapshot} テーブルに JSON で保存し、再起動後はそこから即座に復元する
 *    （起動直後に 1 分待たされない）。
 *  - 差分（表示のたび）: 表示するユーザー 1 人分の歴代ベストだけをその場で読み、土台の d を固定したまま
 *    達成状況と θ を計算し直す。バッチ後にアップロードされたスコアもすぐ反映される。1 人分なので数十 ms。
 *  - 他の人のスコア更新が d・到達率・θ 分布に効くのは次のバッチ（最大 3 時間後）から。
 *
 * レベル表の固定（2026-09-23 ユーザー要望: 各レベルの課題曲が勝手に変わらないように）:
 *  - 「目標（譜面 × ライン）→ レベル番号」の対応表を {@code score_roadmap_level_table} に版ごとに保存し、
 *    判定とランキングは常にこの表で行う。バッチが d を推定し直しても、既存の割り当てと番号は変わらない。
 *  - 表に入れるのはプレー人数 {@link #MIN_PLAYERS_FOR_LEVEL} 人以上の譜面だけ。後から 200 人に達した譜面は、
 *    その時点の d に一番近い既存レベルへ追加する（レベルの数・番号は増やさない）。
 *  - 表の作り直しは管理者の「レベル表を作り直す」（{@link #refreeze()}）だけ。版番号を 1 つ上げて新しい行を足す。
 *
 * AA ラインの追加（2026-09-24 ユーザー指定・サイレント追加）:
 *  - 目標に AA（桶 140）を足し、1 譜面 3 目標（AA / AAA / MAX-）にした。d・k は他のラインと同じ手順で推定する。
 *  - 既存の Lv.1〜 の番号と 0.02 枠はそのまま。AA 目標のうち Lv.1 の枠より易しいものは、その 0.02 枠を
 *    Lv.0, −1, −2 … と下へ詰めた「負のレベル」に置き、それ以外は一番近い既存レベルへ入れる（判定にも使う）。
 *  - 旧版（AA の無い表）は次のバッチで同じ版のまま AA を足して保存し直す（{@link #withAa}）。
 *  - レベル 0 が実在のレベルになったので、「どのレベルも未達成」は null（内部では {@link #NO_LEVEL}）で表す。
 *
 * 生データ約 100 万行は List&lt;Map&gt; に展開せず、カーソルで受けて int 配列へ詰める。
 */
@Service
public class ScoreRoadmapService {

    private static final Logger log = LoggerFactory.getLogger(ScoreRoadmapService.class);

    /** 土台を作り直す間隔。3 時間。 */
    private static final long BASE_STALE_AFTER_MS = 3L * 60L * 60L * 1000L;
    /** 鮮度チェックの間隔。10 分。 */
    private static final long TICK_INTERVAL_MS = 10L * 60L * 1000L;
    /** 起動直後の DataInitializer とロック競合しないよう初回チェックを遅らせる。 */
    private static final long TICK_INITIAL_DELAY_MS = 90L * 1000L;

    private static final int STREAM_FETCH_SIZE = 5000;
    private static final String BUILD_STATEMENT_TIMEOUT = "300s";

    /** ライン定義（桶番号）。AA = 14/18、AAA = 16/18、MAX- = 17/18。 */
    public static final int LINE_AA = 140;
    public static final int LINE_AAA = 160;
    public static final int LINE_MAX_MINUS = 170;

    /** レベル番号の「無し」（どのレベルも未達成 / 表に無い目標）。レベル 0 は実在するので 0 は使えない。 */
    private static final int NO_LEVEL = Integer.MIN_VALUE;

    /** θ 推定に使う最低アンカー譜面数。 */
    private static final int MIN_ANCHOR_PLAYS = 30;
    /** θ の事前分布（アンカー推定時）。 */
    private static final double THETA_PRIOR_MU = 11.8, THETA_PRIOR_VAR = 1.0;
    /** θ の事前分布（全譜面での推定時。初心者も含むので弱く）。差分計算でも同じ値を使う。 */
    private static final double THETA_ALL_PRIOR_MU = 11.0, THETA_ALL_PRIOR_VAR = 4.0;
    /** d の事前分布の分散。 */
    private static final double D_PRIOR_VAR = 1.0;
    private static final double MAX_STEP = 0.3;

    /** 土台の保存先（1 行だけ持つ）。ddl-auto=none の prod-db プロファイルでも動くよう自前で作る。 */
    private static final String CREATE_TABLE_SQL =
        "CREATE TABLE IF NOT EXISTS score_roadmap_snapshot (" +
        "  id INTEGER PRIMARY KEY," +
        "  computed_at VARCHAR(40) NOT NULL," +
        "  payload TEXT NOT NULL)";

    /** 固定したレベル表の保存先（版ごとに 1 行。一番大きい revision が現行）。 */
    private static final String CREATE_LEVEL_TABLE_SQL =
        "CREATE TABLE IF NOT EXISTS score_roadmap_level_table (" +
        "  revision INTEGER PRIMARY KEY," +
        "  frozen_at VARCHAR(40) NOT NULL," +
        "  updated_at VARCHAR(40) NOT NULL," +
        "  payload TEXT NOT NULL)";

    /** レベル表に入れる譜面の最低プレー人数（歴代ベストがある人数）。 */
    private static final int MIN_PLAYERS_FOR_LEVEL = 200;

    private static final String CHARTS_SQL =
        "SELECT sd.id, sd.title, CASE WHEN sd.difficulty = '4' THEN 'ANOTHER' ELSE 'LEGGENDARIA' END AS difficulty_name, " +
        "       sd.level, sd.notes " +
        "FROM song_definitions sd " +
        "WHERE sd.revision = 'active' AND sd.difficulty IN ('4', '10') AND sd.level BETWEEN 1 AND 12 AND sd.notes > 0";

    private static final String RANKS_SQL =
        "SELECT dr.rank_value, drs.song_title FROM difficulty_ranks dr " +
        "JOIN difficulty_rank_songs drs ON dr.id = drs.difficulty_rank_id WHERE dr.revision = 'active'";

    /** 歴代ベスト（現行 scores ∪ 過去作 past_scores の MAX）を user × 譜面で返す。列: user_id, chart_id, bucket。 */
    private static final String BUCKETS_SQL =
        "WITH charts AS ( " + CHARTS_SQL.replace("SELECT sd.id, sd.title,", "SELECT sd.id AS chart_id, sd.title,") + " ), " +
        "lifetime_best AS ( " +
        "  SELECT x.user_id, c.chart_id, MAX(x.score) AS score " +
        "  FROM ( " +
        "    SELECT user_id, title, difficulty_name, score FROM scores " +
        "     WHERE difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND score > 0 " +
        "    UNION ALL " +
        "    SELECT user_id, title, difficulty_name, score FROM past_scores " +
        "     WHERE difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND score > 0 " +
        "  ) x " +
        "  JOIN charts c ON c.title = x.title AND c.difficulty_name = x.difficulty_name " +
        "  GROUP BY x.user_id, c.chart_id " +
        ") " +
        "SELECT b.user_id, b.chart_id, LEAST(b.score * 90 / c.notes, 180) AS bucket " +
        "FROM lifetime_best b JOIN charts c ON c.chart_id = b.chart_id";

    /** 差分: 1 人分の歴代ベスト（曲名・難易度ごとの MAX）。 */
    private static final String USER_BEST_SQL =
        "SELECT title, difficulty_name, MAX(score) FROM ( " +
        "  SELECT title, difficulty_name, score FROM scores " +
        "   WHERE user_id = ? AND difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND score > 0 " +
        "  UNION ALL " +
        "  SELECT title, difficulty_name, score FROM past_scores " +
        "   WHERE user_id = ? AND difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND score > 0 " +
        ") x GROUP BY title, difficulty_name";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 現在の土台。null は未作成（DB にも無い）。 */
    private volatile Base base = null;
    private volatile String lastError = null;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    /** 実行中のバッチが終わったら、もう 1 回作り直す（レベル表の作り直しを反映するため）。 */
    private final AtomicBoolean rebuildAgain = new AtomicBoolean(false);
    /** DB からの復元を 1 回だけ試みたか。 */
    private final AtomicBoolean restoreTried = new AtomicBoolean(false);

    /** 現行のレベル表（null = DB 未読込 or まだ無い）。読み書きは levelLock の中で行う。 */
    private LevelTable levelTable = null;
    private boolean levelTableLoaded = false;
    private final Object levelLock = new Object();

    public ScoreRoadmapService(DataSource dataSource, JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /**
     * 土台（応答にそのまま載せる部分 + 差分計算に使う配列）。不変として扱う。
     *
     * @param computedAt 土台を作った時刻（ISO-8601 UTC）
     * @param model      {kAaa, kMaxMinus, thetas}（応答用。thetas は全プレイヤーの θ 昇順）
     * @param charts     譜面ごとの {i, title, difficultyName, level, notes, playerCount, aaa{...}, maxMinus{...}}（応答用）
     */
    private record Base(String computedAt, Map<String, Object> model, List<Map<String, Object>> charts,
                        List<Map<String, Object>> ranking,
                        Map<String, Integer> keyToIdx, int[] notes, double[] dAa, double[] dAaa, double[] dMaxMinus,
                        double kAa, double kAaa, double kMaxMinus) {}

    /** 1 ライン分の目標（譜面 × ライン）の推定結果。 */
    private record LineResult(double k, double[] d, double[] se, int[] nFit, double[] rate) {}

    /** 統合モデルの推定結果（1 人 1 つの θ）。 */
    private record JointResult(LineResult aa, LineResult aaa, LineResult maxMinus, double[] thetaAll) {}

    /** 全体計算の結果一式（土台に変換して捨てる）。userIds / userStart / userChart / userBucket はランキング計算用。 */
    private record Snapshot(String[] titles, String[] diffs, int[] levels, int[] notes, int[] playerCounts,
                            JointResult joint, long[] userIds, int[] userStart, int[] userChart, byte[] userBucket) {}

    /**
     * 固定したレベル表。
     *
     * @param slots    レベル番号 − 1 → そのレベルの 0.02 枠（枠番号 = floor(d / 0.02)）。昇順
     * @param negSlots −レベル番号 → 負のレベル（Lv.0, −1, …）の 0.02 枠。降順（negSlots[0] が Lv.0）。
     *                 null = AA を足す前の旧版（次のバッチで {@link #withAa} が埋める）
     * @param levelOf  譜面キー（曲名 + NUL + 難易度名）→ {AAA, MAX-, AA のレベル番号}（{@link #LINE_KEYS} の順。
     *                 旧版や AA の d が無い譜面の AA は {@link #NO_LEVEL}）
     * @param addedAt  譜面キー → 表に入った時刻（作り直し時の譜面は frozenAt と同じ）
     */
    private record LevelTable(int revision, String frozenAt, String updatedAt, int minPlayers, long[] slots,
                              long[] negSlots, Map<String, int[]> levelOf, Map<String, String> addedAt) {
        /** 一番易しいレベルの番号（負のレベルが無ければ 1）。 */
        int minLevel() { return negSlots == null || negSlots.length == 0 ? 1 : 1 - negSlots.length; }
        int maxLevel() { return slots.length; }
        /** レベル番号 → 0.02 枠。 */
        long slotOfLevel(int lv) { return lv >= 1 ? slots[lv - 1] : negSlots[-lv]; }
    }

    /**
     * 保存形式の版。モデルや形を変えたら上げる（古い版は復元せず作り直す）。
     * v3 = ランキング追加、v4 = レベル表の固定、v5 = AA ライン・負のレベル。
     */
    private static final int PAYLOAD_VERSION = 5;

    /** レベルの幅（難度の目盛りで 0.02）。フロント（ScoreRoadmapView.vue の LEVEL_W）と必ず揃える。 */
    private static final double LEVEL_W = 0.02;

    // ===================================================================================
    // 公開 API
    // ===================================================================================

    /**
     * 【メソッドの役割】 土台 + 指定ユーザーの差分を返す。
     *
     * 土台がメモリに無ければ DB から復元し、それでも無ければ作成を起動して {@code ready=false} を返す。
     *
     * @param force     true なら土台の作り直しを起動する（管理画面の「再計算」）
     * @param userId    現在地を表示するユーザー（null なら user は返さない）
     * @param userLabel 表示名（そのまま返す）
     */
    public Map<String, Object> requestSnapshot(boolean force, Long userId, String userLabel) {
        if (base == null) restoreFromDbOnce();
        if (force || base == null) startRebuild();

        Base b = base;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", b != null);
        body.put("refreshing", refreshing.get());
        body.put("computedAt", b == null ? null : b.computedAt());
        body.put("error", lastError);
        if (b == null) return body;

        body.put("model", b.model());
        body.put("charts", b.charts());
        if (userId != null) body.put("user", userDelta(b, userId, userLabel));
        return body;
    }

    /**
     * 【メソッドの役割】 ロードマップのレベルランキング（土台作成時点。最大 3 時間前）を返す。
     *
     * 名前やティアは呼び出し側（Controller）が最新のユーザー情報で付ける。
     *
     * @return {ready, computedAt, maxLevel, entries:[{rank, userId, level, clearedLevels, completeLevels}]}
     */
    public Map<String, Object> ranking() {
        if (base == null) restoreFromDbOnce();
        if (base == null) startRebuild();
        Base b = base;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", b != null);
        if (b == null) return body;
        body.put("computedAt", b.computedAt());
        body.put("maxLevel", b.model().get("maxLevel"));
        body.put("minLevel", b.model().get("minLevel"));
        body.put("entries", b.ranking());
        return body;
    }

    /**
     * 【メソッドの役割】 土台の鮮度チェック（10 分おき）。無い or 3 時間超なら作り直す。
     *
     * 起動直後は DB から復元できれば作り直さない（直前のバッチから 3 時間以内なら、そのまま使う）。
     */
    @Scheduled(fixedDelay = TICK_INTERVAL_MS, initialDelay = TICK_INITIAL_DELAY_MS)
    public void tick() {
        if (base == null) restoreFromDbOnce();
        Base b = base;
        boolean stale = b == null
                || System.currentTimeMillis() - Instant.parse(b.computedAt()).toEpochMilli() > BASE_STALE_AFTER_MS;
        if (stale) startRebuild();
    }

    // ===================================================================================
    // 差分（1 ユーザー分）
    // ===================================================================================

    /**
     * 【メソッドの役割】 1 人分の歴代ベストをその場で読み、土台の d 固定で達成状況と θ を出す。
     *
     * @return {userId, label, found, theta, plays:[[譜面 i, 桶]...]}
     */
    private Map<String, Object> userDelta(Base b, long userId, String label) {
        List<int[]> plays = readUserPlays(b, userId);

        Map<String, Object> u = new LinkedHashMap<>();
        u.put("userId", userId);
        u.put("label", label);
        u.put("found", !plays.isEmpty());
        if (!plays.isEmpty()) {
            u.put("theta", round2(thetaFor(plays, b)));
            u.put("plays", plays);
        }
        return u;
    }

    /** 【メソッドの役割】 1 人分の歴代ベストを [譜面 i, 桶] で読む（土台に載っていない譜面は捨てる）。 */
    private List<int[]> readUserPlays(Base b, long userId) {
        List<int[]> plays = new ArrayList<>();
        jdbcTemplate.query(USER_BEST_SQL, rs -> {
            Integer i = b.keyToIdx().get(rs.getString(1) + "\u0000" + rs.getString(2));
            if (i == null) return;
            int bucket = (int) Math.min(180L, (long) rs.getInt(3) * 90L / b.notes()[i]);
            plays.add(new int[] { i, bucket });
        }, userId, userId);
        return plays;
    }

    /**
     * 【メソッドの役割】 1 人分のロードマップレベルだけを返す（ダッシュボードの表示用。2026-09-23 追加）。
     *
     * 画面（/score-roadmap）は全譜面の JSON（約 600 KB）を受け取ってフロントで判定するが、ダッシュボードで
     * 毎回それを読むのは重いので、ここで同じ規則（{@link #computeRanking} と utils/roadmapLevels.ts）で判定して数字だけ返す。
     * 最新のスコアで判定する（土台の d・レベル表は固定、スコアはその場で読む）。
     *
     * @return {ready, level（どのレベルも未達成なら null）, minLevel, maxLevel, clearedLevels, completeLevels}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> userLevel(long userId) {
        if (base == null) restoreFromDbOnce();
        if (base == null) startRebuild();
        Base b = base;
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> lt = b == null ? null : (Map<String, Object>) b.model().get("levelTable");
        body.put("ready", lt != null);
        if (lt == null) return body;
        int maxLevel = ((Number) lt.get("maxLevel")).intValue();
        int minLevel = ((Number) lt.get("minLevel")).intValue();
        int off = -minLevel, size = maxLevel - minLevel + 1; // 配列の添字 = レベル番号 + off

        int[] bucketOf = new int[b.charts().size()];
        Arrays.fill(bucketOf, -1);
        for (int[] p : readUserPlays(b, userId)) bucketOf[p[0]] = p[1];
        int[] n = new int[size], played = new int[size], done = new int[size];
        for (int i = 0; i < b.charts().size(); i++) {
            Map<String, Object> lv = (Map<String, Object>) b.charts().get(i).get("levels");
            if (lv == null) continue;
            for (int li = 0; li < LINE_KEYS.length; li++) {
                Object v = lv.get(LINE_KEYS[li]);
                if (v == null) continue;
                int no = ((Number) v).intValue();
                if (no < minLevel || no > maxLevel) continue;
                n[no + off]++;
                if (bucketOf[i] < 0) continue;
                played[no + off]++;
                if (bucketOf[i] >= LINE_BUCKETS[li]) done[no + off]++;
            }
        }
        int level = NO_LEVEL, cleared = 0, complete = 0;
        for (int x = 0; x < size; x++) {
            int minPlayed = Math.min(n[x], Math.max(2, (n[x] + 2) / 3));
            if (n[x] > 0 && played[x] >= minPlayed && done[x] * 3 >= played[x] * 2) { level = x - off; cleared++; }
            if (n[x] > 0 && done[x] == n[x]) complete++;
        }
        body.put("level", level == NO_LEVEL ? null : level);
        body.put("minLevel", minLevel);
        body.put("maxLevel", maxLevel);
        body.put("clearedLevels", cleared);
        body.put("completeLevels", complete);
        return body;
    }

    /** 【メソッドの役割】 d・k 固定での θ（バッチの手順 (3) と同じ Newton・事前分布。AA・AAA・MAX- の全目標を使う）。 */
    private static double thetaFor(List<int[]> plays, Base b) {
        double th = THETA_ALL_PRIOR_MU;
        double[] ks = { b.kAaa(), b.kMaxMinus(), b.kAa() };
        double[][] ds = { b.dAaa(), b.dMaxMinus(), b.dAa() };
        for (int it = 0; it < 50; it++) {
            double g = -(th - THETA_ALL_PRIOR_MU) / THETA_ALL_PRIOR_VAR, h = -1.0 / THETA_ALL_PRIOR_VAR;
            for (int[] p : plays) {
                for (int li = 0; li < LINE_BUCKETS.length; li++) {
                    int line = LINE_BUCKETS[li];
                    double k = ks[li];
                    double dj = ds[li][p[0]];
                    double pr = sig(k * (th - dj));
                    int y = p[1] >= line ? 1 : 0;
                    g += k * (y - pr);
                    h -= k * k * pr * (1 - pr);
                }
            }
            double step = clip(g / h);
            th -= step;
            if (Math.abs(step) < 1e-5) break;
        }
        return th;
    }

    // ===================================================================================
    // 土台の作成・保存・復元
    // ===================================================================================

    /** 【メソッドの役割】 土台の作り直しを非同期で起動する（多重起動しない）。 */
    private void startRebuild() {
        if (refreshing.compareAndSet(false, true)) CompletableFuture.runAsync(this::rebuild);
    }

    /**
     * 【メソッドの役割】 土台の作り直しを必ずもう 1 回走らせる（実行中なら、終わった後にもう 1 回）。
     * レベル表を作り直したとき、実行中のバッチが古い表で土台を作っていても上書きされるようにする。
     */
    private void startRebuildAfterCurrent() {
        rebuildAgain.set(true);
        if (refreshing.compareAndSet(false, true)) {
            rebuildAgain.set(false);
            CompletableFuture.runAsync(this::rebuild);
        }
    }

    private void rebuild() {
        long start = System.currentTimeMillis();
        try {
            Snapshot s = build();
            Base next = toBase(s, Instant.now().toString());
            persist(next);
            this.base = next;
            this.lastError = null;
            log.info("Built score roadmap base: {} charts in {} ms", next.charts().size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            this.lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("Failed to build score roadmap base after {} ms (keeping previous)", System.currentTimeMillis() - start, e);
        } finally {
            refreshing.set(false);
            if (rebuildAgain.getAndSet(false)) startRebuild();
        }
    }

    // ===================================================================================
    // レベル表（固定）
    // ===================================================================================

    /**
     * 【メソッドの役割】 レベル表を作り直した場合の変化を返す（まだ保存しない）。管理画面の確認用。
     *
     * 作り直しは今の土台の d（最大 3 時間前の集計）で行う。{@link #refreeze()} も同じ土台を使うので、結果は一致する。
     *
     * @return {ready, basedOn, current{revision, frozenAt, levels, targets}, next{levels, targets},
     *          moved, movedUp, movedDown, added, removed}（件数は目標 = 譜面 × ライン 単位）
     */
    public Map<String, Object> refreezePreview() {
        if (base == null) restoreFromDbOnce();
        Base b = base;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", b != null);
        if (b == null) return body;
        synchronized (levelLock) {
            LevelTable cur = loadLevelTableOnce();
            LevelTable next = freezeLevels(b.charts(), cur == null ? 1 : cur.revision() + 1, Instant.now().toString());
            int moved = 0, up = 0, down = 0, added = 0, removed = 0;
            for (Map.Entry<String, int[]> e : next.levelOf().entrySet()) {
                int[] old = cur == null ? null : cur.levelOf().get(e.getKey());
                for (int li = 0; li < LINE_KEYS.length; li++) {
                    int to = e.getValue()[li];
                    if (to == NO_LEVEL) continue;
                    if (old == null || old[li] == NO_LEVEL) { added++; continue; }
                    // 番号は作り直しで振り直されるので、レベルの 0.02 枠どうしで比べる
                    long fromSlot = cur.slotOfLevel(old[li]), toSlot = next.slotOfLevel(to);
                    if (fromSlot != toSlot) { moved++; if (toSlot > fromSlot) up++; else down++; }
                }
            }
            if (cur != null) {
                for (Map.Entry<String, int[]> e : cur.levelOf().entrySet()) {
                    int[] now = next.levelOf().get(e.getKey());
                    for (int li = 0; li < LINE_KEYS.length; li++) {
                        if (e.getValue()[li] != NO_LEVEL && (now == null || now[li] == NO_LEVEL)) removed++;
                    }
                }
            }
            body.put("basedOn", b.computedAt());
            if (cur != null) {
                body.put("current", Map.of("revision", cur.revision(), "frozenAt", cur.frozenAt(),
                        "levels", cur.maxLevel() - cur.minLevel() + 1, "targets", countTargets(cur)));
            }
            body.put("next", Map.of("levels", next.maxLevel() - next.minLevel() + 1, "targets", countTargets(next)));
            body.put("moved", moved);
            body.put("movedUp", up);
            body.put("movedDown", down);
            body.put("added", added);
            body.put("removed", removed);
        }
        return body;
    }

    /**
     * 【メソッドの役割】 今の土台の d でレベル表を作り直し、新しい版として保存する。管理者の操作でだけ呼ぶ。
     *
     * 土台（各譜面のレベル番号・ランキング）への反映は続けて走らせるバッチで行う（1〜2 分）。
     *
     * @return {ready, revision, levels}
     */
    public Map<String, Object> refreeze() {
        if (base == null) restoreFromDbOnce();
        Base b = base;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", b != null);
        if (b == null) return body;
        synchronized (levelLock) {
            LevelTable cur = loadLevelTableOnce();
            LevelTable next = freezeLevels(b.charts(), cur == null ? 1 : cur.revision() + 1, Instant.now().toString());
            saveLevelTable(next);
            levelTable = next;
            body.put("revision", next.revision());
            body.put("levels", next.maxLevel() - next.minLevel() + 1);
            log.info("Refroze score roadmap level table: revision {} ({} levels, {} charts)",
                    next.revision(), next.slots().length, next.levelOf().size());
        }
        startRebuildAfterCurrent();
        return body;
    }

    /**
     * 【メソッドの役割】 バッチの結果に合わせてレベル表を用意する。無ければ作り（第 1 版）、AA の無い旧版なら
     * 同じ版のまま AA を足し（{@link #withAa}）、200 人に達した譜面を一番近い既存レベルへ追加する。
     * 既存の割り当て（AAA / MAX- の番号）は変えない。
     */
    private LevelTable syncLevelTable(List<Map<String, Object>> charts, String now) {
        synchronized (levelLock) {
            LevelTable cur = loadLevelTableOnce();
            if (cur == null) {
                LevelTable first = freezeLevels(charts, 1, now);
                saveLevelTable(first);
                levelTable = first;
                log.info("Froze score roadmap level table: revision 1 ({} levels, {} charts)",
                        first.maxLevel() - first.minLevel() + 1, first.levelOf().size());
                return first;
            }
            boolean changed = false;
            if (cur.negSlots() == null) {
                cur = withAa(cur, charts, now);
                changed = true;
                log.info("Added AA targets to score roadmap level table revision {} (levels Lv.{}..Lv.{})",
                        cur.revision(), cur.minLevel(), cur.maxLevel());
            }
            LevelTable grown = withNewCharts(cur, charts, now);
            if (grown != null) {
                log.info("Added {} charts to score roadmap level table revision {}",
                        grown.levelOf().size() - cur.levelOf().size(), grown.revision());
                cur = grown;
                changed = true;
            }
            if (changed) {
                saveLevelTable(cur);
                levelTable = cur;
            }
            return cur;
        }
    }

    private static String chartKey(Map<String, Object> c) {
        return c.get("title") + "\u0000" + c.get("difficultyName");
    }

    private static boolean eligible(Map<String, Object> c) {
        return ((Number) c.get("playerCount")).intValue() >= MIN_PLAYERS_FOR_LEVEL;
    }

    /** 難度 → 0.02 枠。丸め後の d（応答と同じ小数 4 桁）で決める（境目でフロントとずれないように）。 */
    @SuppressWarnings("unchecked")
    private static long slotOf(Map<String, Object> c, String line) {
        double d = ((Number) ((Map<String, Object>) c.get(line)).get("d")).doubleValue();
        return (long) Math.floor(d / LEVEL_W + 1e-9);
    }

    /** レベル表・応答の levels のライン（この順で levelOf の int[] に入る）と、その桶。 */
    private static final String[] LINE_KEYS = { "aaa", "maxMinus", "aa" };
    private static final int[] LINE_BUCKETS = { LINE_AAA, LINE_MAX_MINUS, LINE_AA };
    /** levelOf の int[] での AA の位置。 */
    private static final int AA = 2;

    /**
     * 【メソッドの役割】 200 人以上の譜面の AAA / MAX- 目標がある 0.02 枠を易しい順に Lv.1 から連番にし、
     * AA 目標を足した表を作る（Lv.1 より易しい AA は負のレベル）。
     */
    private static LevelTable freezeLevels(List<Map<String, Object>> charts, int revision, String now) {
        java.util.TreeSet<Long> set = new java.util.TreeSet<>();
        for (Map<String, Object> c : charts) {
            if (!eligible(c)) continue;
            set.add(slotOf(c, "aaa"));
            set.add(slotOf(c, "maxMinus"));
        }
        long[] slots = set.stream().mapToLong(Long::longValue).toArray();
        Map<String, int[]> levelOf = new HashMap<>();
        Map<String, String> addedAt = new HashMap<>();
        for (Map<String, Object> c : charts) {
            if (!eligible(c)) continue;
            int[] no = { Arrays.binarySearch(slots, slotOf(c, "aaa")) + 1,
                         Arrays.binarySearch(slots, slotOf(c, "maxMinus")) + 1, NO_LEVEL };
            levelOf.put(chartKey(c), no);
            addedAt.put(chartKey(c), now);
        }
        return withAa(new LevelTable(revision, now, now, MIN_PLAYERS_FOR_LEVEL, slots, null, levelOf, addedAt), charts, now);
    }

    /**
     * 【メソッドの役割】 表の全譜面に AA 目標のレベルを付けた表を返す（AAA / MAX- の番号と枠はそのまま）。
     *
     * 表にある譜面の AA のうち Lv.1 の枠より易しいものの 0.02 枠を、易しくない順に Lv.0, −1, −2 … と連番にし
     * （負のレベル）、各 AA は一番近いレベル（負のレベルを含む）へ入れる。今の土台に無い譜面（マスタから消えた等）の
     * AA は付けない。版番号・固定日時は変えない。
     */
    private static LevelTable withAa(LevelTable t, List<Map<String, Object>> charts, String now) {
        Map<String, Map<String, Object>> byKey = new HashMap<>();
        for (Map<String, Object> c : charts) byKey.put(chartKey(c), c);
        long lowest = t.slots()[0];
        java.util.TreeSet<Long> below = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
        for (String key : t.levelOf().keySet()) {
            Map<String, Object> c = byKey.get(key);
            if (c == null) continue;
            long s = slotOf(c, "aa");
            if (s < lowest) below.add(s);
        }
        long[] negSlots = below.stream().mapToLong(Long::longValue).toArray();
        LevelTable withNeg = new LevelTable(t.revision(), t.frozenAt(), now, t.minPlayers(), t.slots(), negSlots,
                t.levelOf(), t.addedAt());
        Map<String, int[]> levelOf = new HashMap<>();
        for (Map.Entry<String, int[]> e : t.levelOf().entrySet()) {
            int[] no = Arrays.copyOf(e.getValue(), LINE_KEYS.length);
            Map<String, Object> c = byKey.get(e.getKey());
            no[AA] = c == null ? NO_LEVEL : nearestAnyLevel(withNeg, slotOf(c, "aa"));
            levelOf.put(e.getKey(), no);
        }
        return new LevelTable(t.revision(), t.frozenAt(), now, t.minPlayers(), t.slots(), negSlots, levelOf, t.addedAt());
    }

    /** 【メソッドの役割】 表に無い 200 人以上の譜面を、一番近い既存レベルへ足した表を返す（足すものが無ければ null）。 */
    private static LevelTable withNewCharts(LevelTable t, List<Map<String, Object>> charts, String now) {
        Map<String, int[]> levelOf = null;
        Map<String, String> addedAt = null;
        for (Map<String, Object> c : charts) {
            String key = chartKey(c);
            if (!eligible(c) || t.levelOf().containsKey(key)) continue;
            if (levelOf == null) { levelOf = new HashMap<>(t.levelOf()); addedAt = new HashMap<>(t.addedAt()); }
            // AAA / MAX- は従来どおり Lv.1 以上へ、AA は負のレベルも含めて一番近いレベルへ
            int[] no = { nearestLevel(t.slots(), slotOf(c, "aaa")), nearestLevel(t.slots(), slotOf(c, "maxMinus")),
                         nearestAnyLevel(t, slotOf(c, "aa")) };
            levelOf.put(key, no);
            addedAt.put(key, now);
        }
        if (levelOf == null) return null;
        return new LevelTable(t.revision(), t.frozenAt(), now, t.minPlayers(), t.slots(), t.negSlots(), levelOf, addedAt);
    }

    /** 枠 s に一番近いレベルの番号（同じ距離なら易しい方）。 */
    private static int nearestLevel(long[] slots, long s) {
        int i = Arrays.binarySearch(slots, s);
        if (i >= 0) return i + 1;
        int ins = -i - 1; // s より大きい最初の枠
        if (ins == 0) return 1;
        if (ins == slots.length) return slots.length;
        return s - slots[ins - 1] <= slots[ins] - s ? ins : ins + 1;
    }

    /** 枠 s に一番近いレベルの番号。負のレベルも候補に入れる（同じ距離なら易しい方）。 */
    private static int nearestAnyLevel(LevelTable t, long s) {
        int best = NO_LEVEL;
        long bestDist = Long.MAX_VALUE;
        for (int lv = t.minLevel(); lv <= t.maxLevel(); lv++) {
            long dist = Math.abs(t.slotOfLevel(lv) - s);
            if (dist < bestDist) { bestDist = dist; best = lv; }
        }
        return best;
    }

    /** 表の目標数（AA の付いていない譜面は 2、付いている譜面は 3）。 */
    private static int countTargets(LevelTable t) {
        int n = 0;
        for (int[] no : t.levelOf().values()) for (int v : no) if (v != NO_LEVEL) n++;
        return n;
    }

    /** 【メソッドの役割】 現行の版を DB から 1 回だけ読む（levelLock の中で呼ぶ）。読めない時は例外（勝手に作り直さない）。 */
    private LevelTable loadLevelTableOnce() {
        if (levelTableLoaded) return levelTable;
        jdbcTemplate.execute(CREATE_LEVEL_TABLE_SQL);
        List<String> rows = jdbcTemplate.query(
                "SELECT payload FROM score_roadmap_level_table ORDER BY revision DESC LIMIT 1",
                (rs, n) -> rs.getString(1));
        if (!rows.isEmpty()) {
            try {
                JsonLevelTable j = objectMapper.readValue(rows.get(0), JsonLevelTable.class);
                Map<String, int[]> levelOf = new HashMap<>();
                Map<String, String> addedAt = new HashMap<>();
                for (JsonLevelChart c : j.charts()) {
                    String key = c.title() + "\u0000" + c.difficultyName();
                    levelOf.put(key, new int[] { c.aaa(), c.maxMinus(), c.aa() == null ? NO_LEVEL : c.aa() });
                    addedAt.put(key, c.addedAt());
                }
                // negSlots が無い = AA を足す前の旧版（次のバッチで withAa が埋める）
                levelTable = new LevelTable(j.revision(), j.frozenAt(), j.updatedAt(), j.minPlayers(), j.slots(),
                        j.negSlots(), levelOf, addedAt);
            } catch (Exception e) {
                throw new IllegalStateException("score_roadmap_level_table を読めません: " + e.getMessage(), e);
            }
        }
        levelTableLoaded = true;
        return levelTable;
    }

    /** 保存形式（JSON）。negSlots・aa は 2026-09-24 追加（旧版には無い = null）。 */
    private record JsonLevelTable(int revision, String frozenAt, String updatedAt, int minPlayers, long[] slots,
                                  long[] negSlots, List<JsonLevelChart> charts) {}
    private record JsonLevelChart(String title, String difficultyName, int aaa, int maxMinus, Integer aa, String addedAt) {}

    /** 【メソッドの役割】 レベル表をその版の行として保存する（同じ版は上書き）。 */
    private void saveLevelTable(LevelTable t) {
        List<JsonLevelChart> list = new ArrayList<>();
        for (Map.Entry<String, int[]> e : t.levelOf().entrySet()) {
            String[] k = e.getKey().split("\u0000", 2);
            int[] no = e.getValue();
            list.add(new JsonLevelChart(k[0], k[1], no[0], no[1], no[AA] == NO_LEVEL ? null : no[AA], t.addedAt().get(e.getKey())));
        }
        list.sort((a, b) -> a.aaa() != b.aaa() ? Integer.compare(a.aaa(), b.aaa()) : a.title().compareTo(b.title()));
        String json;
        try {
            json = objectMapper.writeValueAsString(
                    new JsonLevelTable(t.revision(), t.frozenAt(), t.updatedAt(), t.minPlayers(), t.slots(), t.negSlots(), list));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        writeTx.executeWithoutResult(status -> {
            jdbcTemplate.execute(CREATE_LEVEL_TABLE_SQL);
            jdbcTemplate.update("DELETE FROM score_roadmap_level_table WHERE revision = ?", t.revision());
            jdbcTemplate.update("INSERT INTO score_roadmap_level_table (revision, frozen_at, updated_at, payload) VALUES (?, ?, ?, ?)",
                    t.revision(), t.frozenAt(), t.updatedAt(), json);
        });
    }

    /** 【メソッドの役割】 応答用の譜面に、レベル表の番号 {aaa, maxMinus, aa} を付ける（表に無い譜面は付けない）。 */
    private static List<Map<String, Object>> withLevels(List<Map<String, Object>> charts, LevelTable t) {
        List<Map<String, Object>> out = new ArrayList<>(charts.size());
        for (Map<String, Object> c : charts) {
            Map<String, Object> m = new LinkedHashMap<>(c);
            int[] no = t.levelOf().get(chartKey(c));
            if (no != null) {
                Map<String, Object> lv = new LinkedHashMap<>();
                for (int li = 0; li < LINE_KEYS.length; li++) if (no[li] != NO_LEVEL) lv.put(LINE_KEYS[li], no[li]);
                m.put("levels", lv);
            } else {
                m.remove("levels");
            }
            out.add(m);
        }
        return out;
    }

    /**
     * 応答の model に載せるレベル表の情報。フロントは slots（Lv.1〜）と negSlots（Lv.0, −1, …）で
     * レベルの枠を表示する。minLevel / maxLevel = 一番易しい / 難しいレベルの番号。
     */
    private static Map<String, Object> levelTableInfo(LevelTable t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("revision", t.revision());
        m.put("frozenAt", t.frozenAt());
        m.put("updatedAt", t.updatedAt());
        m.put("minPlayers", t.minPlayers());
        m.put("slots", t.slots());
        m.put("negSlots", t.negSlots() == null ? new long[0] : t.negSlots());
        m.put("minLevel", t.minLevel());
        m.put("maxLevel", t.maxLevel());
        return m;
    }

    /** 【メソッドの役割】 土台を JSON にして 1 行テーブルへ保存する（前の行は消す）。 */
    private void persist(Base b) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", PAYLOAD_VERSION);
        payload.put("model", b.model());
        payload.put("charts", b.charts());
        payload.put("ranking", b.ranking());
        String json = objectMapper.writeValueAsString(payload);
        writeTx.executeWithoutResult(status -> {
            jdbcTemplate.execute(CREATE_TABLE_SQL);
            jdbcTemplate.update("DELETE FROM score_roadmap_snapshot");
            jdbcTemplate.update("INSERT INTO score_roadmap_snapshot (id, computed_at, payload) VALUES (1, ?, ?)",
                    b.computedAt(), json);
        });
    }

    /** 【メソッドの役割】 DB に保存済みの土台をメモリへ戻す（起動後に 1 回だけ試す）。 */
    private void restoreFromDbOnce() {
        if (!restoreTried.compareAndSet(false, true)) return;
        try {
            jdbcTemplate.execute(CREATE_TABLE_SQL);
            // getString で読む（H2 では TEXT が CLOB になり、getObject だと String にならない）
            List<String[]> rows = jdbcTemplate.query(
                    "SELECT computed_at, payload FROM score_roadmap_snapshot WHERE id = 1",
                    (rs, n) -> new String[] { rs.getString(1), rs.getString(2) });
            if (rows.isEmpty()) return;
            String computedAt = rows.get(0)[0];
            Map<String, Object> payload = objectMapper.readValue(rows.get(0)[1],
                    new TypeReference<Map<String, Object>>() {});
            Object v = payload.get("v");
            if (!(v instanceof Number n) || n.intValue() != PAYLOAD_VERSION) {
                log.info("Stored score roadmap base has old format (v={}), rebuilding", v);
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) payload.get("model");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> charts = (List<Map<String, Object>>) payload.get("charts");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ranking = (List<Map<String, Object>>) payload.get("ranking");
            this.base = baseFromResponse(computedAt, model, charts, ranking);
            log.info("Restored score roadmap base computed at {} ({} charts)", computedAt, charts.size());
        } catch (Exception e) {
            log.warn("Could not restore score roadmap base from DB: {}", e.getMessage());
        }
    }

    /** 【メソッドの役割】 全体計算の結果を、応答用の形 + 差分計算用の配列に変換する（レベル表の用意もここで行う）。 */
    private Base toBase(Snapshot s, String computedAt) {
        JointResult jr = s.joint();
        double[] th = jr.thetaAll().clone();
        Arrays.sort(th);
        double[] rounded = new double[th.length];
        for (int i = 0; i < th.length; i++) rounded[i] = round2(th[i]);
        Map<String, Object> model = new LinkedHashMap<>();
        // k は差分の θ 計算に使うので丸めすぎない
        model.put("kAa", Math.round(jr.aa().k() * 10000.0) / 10000.0);
        model.put("kAaa", Math.round(jr.aaa().k() * 10000.0) / 10000.0);
        model.put("kMaxMinus", Math.round(jr.maxMinus().k() * 10000.0) / 10000.0);
        model.put("thetas", rounded); // 全ユーザーの θ（昇順）。「そのレベルに届いている人の割合」に使う
        List<Map<String, Object>> charts = new ArrayList<>();
        for (int j = 0; j < s.titles().length; j++) {
            if (Double.isNaN(jr.aaa().d()[j])) continue; // 重複マスタ行
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("i", charts.size()); // 応答内の位置（plays の譜面番号と共通）
            c.put("title", s.titles()[j]);
            c.put("difficultyName", s.diffs()[j]);
            c.put("level", s.levels()[j]);
            c.put("notes", s.notes()[j]);
            c.put("playerCount", s.playerCounts()[j]);
            c.put("aaa", chartLine(jr.aaa(), j));
            c.put("maxMinus", chartLine(jr.maxMinus(), j));
            c.put("aa", chartLine(jr.aa(), j));
            charts.add(c);
        }
        LevelTable t = syncLevelTable(charts, computedAt);
        List<Map<String, Object>> leveled = withLevels(charts, t);
        Ranking r = computeRanking(s, leveled, t.minLevel(), t.maxLevel());
        model.put("maxLevel", t.maxLevel());
        model.put("minLevel", t.minLevel());
        model.put("levelTable", levelTableInfo(t));
        return baseFromResponse(computedAt, model, leveled, r.entries());
    }

    private record Ranking(List<Map<String, Object>> entries) {}

    /**
     * 【メソッドの役割】 全ユーザーのロードマップレベルを計算し、ランキングにする。
     *
     * 判定はフロント（ScoreRoadmapView.vue の levels / myLevel）と同じ規則:
     *  - 目標 = 譜面 × ライン（AA / AAA / MAX-）。レベル番号は固定したレベル表（charts[].levels）のもの（負のレベルを含む）。
     *    表に無い譜面（プレー人数 200 人未満）は判定に使わない。
     *  - 達成 = プレー済み目標 ≥ min(n, max(2, ⌈n/3⌉)) かつ 達成数 × 3 ≥ プレー済み × 2。完全制覇 = 全目標達成。
     *    目標が 0 件のレベル（マスタから消えた譜面だけのレベル）は達成にしない。
     *  - その人のレベル = 達成レベルの最大番号。どのレベルも未達成の人は載せない。
     * 並びはレベル降順 → 達成レベル数 → 完全制覇数。順位は同じレベルなら同順位（1, 1, 3…）。
     */
    @SuppressWarnings("unchecked")
    private static Ranking computeRanking(Snapshot s, List<Map<String, Object>> charts, int minLevel, int maxLevel) {
        // 手順1: 譜面（元の添字 j）× ライン → レベル番号（NO_LEVEL = 表に無い）。配列の添字 = レベル番号 + off
        int nc = s.titles().length, nl = LINE_KEYS.length;
        int off = -minLevel, size = maxLevel - minLevel + 1;
        // 曲名・難易度 → 元の添字（重複マスタ行は応答に載らないので、載っている譜面は一意）
        Map<String, Integer> idxOf = new HashMap<>(nc * 2);
        for (int j = 0; j < nc; j++) {
            if (!Double.isNaN(s.joint().aaa().d()[j])) idxOf.put(s.titles()[j] + "\u0000" + s.diffs()[j], j);
        }
        int[][] levelOf = new int[nl][nc];
        for (int[] row : levelOf) Arrays.fill(row, NO_LEVEL);
        int[] targetsPerLevel = new int[size];
        for (Map<String, Object> c : charts) {
            Map<String, Object> lv = (Map<String, Object>) c.get("levels");
            if (lv == null) continue;
            int j = idxOf.get(chartKey(c));
            for (int li = 0; li < nl; li++) {
                Object v = lv.get(LINE_KEYS[li]);
                if (v == null) continue;
                levelOf[li][j] = ((Number) v).intValue();
                targetsPerLevel[levelOf[li][j] + off]++;
            }
        }

        // 手順2: ユーザーごとにレベル別のプレー済み数・達成数を数えて判定
        List<long[]> rows = new ArrayList<>(); // [userId, level, cleared, complete]
        int[] played = new int[size], done = new int[size];
        for (int u = 0; u < s.userIds().length; u++) {
            Arrays.fill(played, 0);
            Arrays.fill(done, 0);
            for (int p = s.userStart()[u]; p < s.userStart()[u + 1]; p++) {
                int j = s.userChart()[p], b = s.userBucket()[p] & 0xFF;
                for (int li = 0; li < nl; li++) {
                    int lv = levelOf[li][j];
                    if (lv == NO_LEVEL) continue;
                    played[lv + off]++;
                    if (b >= LINE_BUCKETS[li]) done[lv + off]++;
                }
            }
            int level = NO_LEVEL, cleared = 0, complete = 0;
            for (int x = 0; x < size; x++) {
                int n = targetsPerLevel[x];
                int minPlayed = Math.min(n, Math.max(2, (n + 2) / 3));
                if (n > 0 && played[x] >= minPlayed && done[x] * 3 >= played[x] * 2) { level = x - off; cleared++; }
                if (n > 0 && done[x] == n) complete++;
            }
            if (level != NO_LEVEL) rows.add(new long[] { s.userIds()[u], level, cleared, complete });
        }

        // 手順3: 並べて順位（同レベルは同順位）
        rows.sort((a, b) -> a[1] != b[1] ? Long.compare(b[1], a[1])
                : a[2] != b[2] ? Long.compare(b[2], a[2]) : Long.compare(b[3], a[3]));
        List<Map<String, Object>> entries = new ArrayList<>(rows.size());
        int rank = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (i == 0 || rows.get(i)[1] != rows.get(i - 1)[1]) rank = i + 1;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("rank", rank);
            e.put("userId", rows.get(i)[0]);
            e.put("level", rows.get(i)[1]);
            e.put("clearedLevels", rows.get(i)[2]);
            e.put("completeLevels", rows.get(i)[3]);
            entries.add(e);
        }
        return new Ranking(entries);
    }

    /** 【メソッドの役割】 応答用の形（DB 保存形式と同じ）から差分計算用の配列を組み立てる。 */
    @SuppressWarnings("unchecked")
    private static Base baseFromResponse(String computedAt, Map<String, Object> model, List<Map<String, Object>> charts,
                                         List<Map<String, Object>> ranking) {
        int n = charts.size();
        Map<String, Integer> keyToIdx = new HashMap<>(n * 2);
        int[] notes = new int[n];
        double[] dAa = new double[n], dAaa = new double[n], dMm = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Object> c = charts.get(i);
            keyToIdx.put(c.get("title") + "\u0000" + c.get("difficultyName"), i);
            notes[i] = ((Number) c.get("notes")).intValue();
            dAa[i] = ((Number) ((Map<String, Object>) c.get("aa")).get("d")).doubleValue();
            dAaa[i] = ((Number) ((Map<String, Object>) c.get("aaa")).get("d")).doubleValue();
            dMm[i] = ((Number) ((Map<String, Object>) c.get("maxMinus")).get("d")).doubleValue();
        }
        double kAa = ((Number) model.get("kAa")).doubleValue();
        double kAaa = ((Number) model.get("kAaa")).doubleValue();
        double kMm = ((Number) model.get("kMaxMinus")).doubleValue();
        return new Base(computedAt, model, charts, ranking == null ? List.of() : ranking, keyToIdx, notes,
                dAa, dAaa, dMm, kAa, kAaa, kMm);
    }

    private static Map<String, Object> chartLine(LineResult r, int j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("d", Math.round(r.d()[j] * 10000.0) / 10000.0);
        m.put("se", round2(r.se()[j]));
        m.put("n", r.nFit()[j]);
        m.put("rate", Math.round(r.rate()[j] * 1000.0) / 10.0);
        return m;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double sig(double x) { return 1.0 / (1.0 + Math.exp(-x)); }
    private static double clip(double v) { return Math.max(-MAX_STEP, Math.min(MAX_STEP, v)); }

    // ===================================================================================
    // 全体計算（土台の中身）
    // ===================================================================================

    private Snapshot build() {
        // 手順1: 譜面マスタと難易度表（アンカー）、生データ（user_id, 譜面 idx, 桶）を int 配列に詰める
        List<Object[]> chartRows = new ArrayList<>();
        Map<String, Double> rankOf = new HashMap<>();
        IntList rowUser = new IntList(), rowChart = new IntList(), rowBucket = new IntList();
        Map<Long, Integer> userIdx = new HashMap<>();
        Map<Long, Integer> chartIdxById = new HashMap<>();

        readTx.execute(status -> {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.setFetchSize(STREAM_FETCH_SIZE);
            String product = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
            if (product != null && product.toLowerCase().contains("postgres")) {
                jdbc.execute("SET LOCAL statement_timeout = '" + BUILD_STATEMENT_TIMEOUT + "'");
            }
            jdbc.query(CHARTS_SQL, rs -> {
                chartIdxById.put(rs.getLong(1), chartRows.size());
                chartRows.add(new Object[] { rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5) });
            });
            jdbc.query(RANKS_SQL, rs -> {
                try {
                    rankOf.put(rs.getString(2), Double.parseDouble(rs.getString(1)));
                } catch (NumberFormatException ignored) {
                    // "Uncategorized" 等は数値でないので除外
                }
            });
            jdbc.query(BUCKETS_SQL, rs -> {
                Integer ci = chartIdxById.get(rs.getLong(2));
                if (ci == null) return;
                long uid = rs.getLong(1);
                Integer ui = userIdx.get(uid);
                if (ui == null) {
                    ui = userIdx.size();
                    userIdx.put(uid, ui);
                }
                rowUser.add(ui);
                rowChart.add(ci);
                rowBucket.add(Math.max(0, Math.min(180, rs.getInt(3))));
            });
            return null;
        });

        int nc = chartRows.size(), nu = userIdx.size(), nr = rowUser.size;
        String[] titles = new String[nc], diffs = new String[nc];
        int[] levels = new int[nc], notes = new int[nc];
        double[] table = new double[nc], prior = new double[nc];
        boolean[] dup = new boolean[nc];
        Map<String, Integer> keyCount = new HashMap<>();
        for (int j = 0; j < nc; j++) {
            Object[] r = chartRows.get(j);
            titles[j] = (String) r[0];
            diffs[j] = (String) r[1];
            levels[j] = (Integer) r[2];
            notes[j] = (Integer) r[3];
            keyCount.merge(titles[j] + "\u0000" + diffs[j], 1, Integer::sum);
        }
        for (int j = 0; j < nc; j++) {
            // 同名同難易度の重複マスタ行（Do it!! Do it!! [L] の ☆10/☆12 等）はスコアの割り当てが曖昧なので除外
            dup[j] = keyCount.get(titles[j] + "\u0000" + diffs[j]) > 1;
            Double t = levels[j] >= 11
                    ? rankOf.get("LEGGENDARIA".equals(diffs[j]) ? titles[j] + "[L]" : titles[j])
                    : null;
            table[j] = (t == null || dup[j]) ? Double.NaN : t;
            prior[j] = levels[j] >= 12 ? 12.2 : levels[j] == 11 ? 11.6 : 11.2 - 0.3 * (10 - levels[j]);
        }

        // 手順2: user 別 / 譜面別の CSR インデックス
        int[] userStart = new int[nu + 1], chartStart = new int[nc + 1];
        for (int i = 0; i < nr; i++) { userStart[rowUser.a[i] + 1]++; chartStart[rowChart.a[i] + 1]++; }
        for (int i = 0; i < nu; i++) userStart[i + 1] += userStart[i];
        for (int j = 0; j < nc; j++) chartStart[j + 1] += chartStart[j];
        int[] userChart = new int[nr];
        byte[] userBucket = new byte[nr];
        int[] chartUser = new int[nr];
        byte[] chartBucket = new byte[nr];
        int[] uf = Arrays.copyOf(userStart, nu), cf = Arrays.copyOf(chartStart, nc);
        for (int i = 0; i < nr; i++) {
            int u = rowUser.a[i], c = rowChart.a[i];
            byte b = (byte) rowBucket.a[i];
            userChart[uf[u]] = c; userBucket[uf[u]++] = b;
            chartUser[cf[c]] = u; chartBucket[cf[c]++] = b;
        }
        int[] playerCounts = new int[nc];
        for (int j = 0; j < nc; j++) playerCounts[j] = chartStart[j + 1] - chartStart[j];

        Model m = new Model(nc, nu, table, prior, dup, userStart, userChart, userBucket, chartStart, chartUser, chartBucket);
        long[] userIds = new long[nu];
        for (Map.Entry<Long, Integer> e : userIdx.entrySet()) userIds[e.getValue()] = e.getKey();
        return new Snapshot(titles, diffs, levels, notes, playerCounts, m.fitJoint(), userIds, userStart, userChart, userBucket);
    }

    /** 推定計算（純粋計算。DB に触らない）。 */
    private record Model(int nc, int nu, double[] table, double[] prior, boolean[] dup,
                         int[] userStart, int[] userChart, byte[] userBucket,
                         int[] chartStart, int[] chartUser, byte[] chartBucket) {

        /** AAA 目標の d の事前分布は、同じ譜面の MAX- の目安よりこれだけ易しい所に置く（試算の中央値差 0.76）。 */
        private static final double AAA_PRIOR_SHIFT = 0.7;
        /**
         * AA 目標の事前分布のずらし幅（桶 1 つあたり 0.07 = AAA の 0.7 / 10 桶を AA の 30 桶へ延長）。
         * ほぼ全員が AA を取る譜面は d が事前分布に寄るので、負のレベルの下の方の並びはこの値で決まる。
         * 2026-09-24 に同じ値で試算し、ユーザーに見せた表（負のレベル 29、☆12 の AA は負に入らない）と揃えてある。
         */
        private static final double AA_PRIOR_SHIFT = 2.1;
        /** (2) の d と k の交互推定の回数。 */
        private static final int JOINT_ROUNDS = 8;

        /**
         * 統合モデル: 1 人 1 つの θ、目標 = 譜面 × ライン（AAA / MAX-）、ラインごとの傾き k。
         *  (1) 難易度表にある譜面の MAX- 目標をアンカー（d = 表の値）にして θ と k を推定
         *  (2) θ 固定で、ラインごとに全目標の d と k を交互に推定（AAA 目標も同じ目盛りに載る）
         *  (3) d・k 固定で全ユーザーの θ を AAA・MAX- 両方の目標から推定し直す
         * 2026-09-23 の Node 試作（本番データ）: k は AAA 6.72 / MAX- 6.73、全譜面で MAX- 目標 > AAA 目標（差の中央値 0.76）。
         */
        JointResult fitJoint() {
            // (1) アンカー（MAX- 目標）を十分遊んだユーザーの θ と k
            int[] anchorCount = new int[nu];
            for (int u = 0; u < nu; u++) {
                for (int p = userStart[u]; p < userStart[u + 1]; p++) if (!Double.isNaN(table[userChart[p]])) anchorCount[u]++;
            }
            boolean[] fitUser = new boolean[nu];
            double[] theta = new double[nu];
            for (int u = 0; u < nu; u++) { fitUser[u] = anchorCount[u] >= MIN_ANCHOR_PLAYS; theta[u] = THETA_PRIOR_MU; }
            double kAnchor = 3.0;
            for (int outer = 0; outer < 15; outer++) {
                for (int u = 0; u < nu; u++) {
                    if (fitUser[u]) theta[u] = estThetaOneLine(u, kAnchor, theta[u], table, LINE_MAX_MINUS);
                }
                double g = 0, h = 0;
                for (int u = 0; u < nu; u++) {
                    if (!fitUser[u]) continue;
                    for (int p = userStart[u]; p < userStart[u + 1]; p++) {
                        double d = table[userChart[p]];
                        if (Double.isNaN(d)) continue;
                        double x = theta[u] - d, pr = sig(kAnchor * x);
                        int y = (userBucket[p] & 0xFF) >= LINE_MAX_MINUS ? 1 : 0;
                        g += x * (y - pr);
                        h -= x * x * pr * (1 - pr);
                    }
                }
                if (h < 0) kAnchor -= g / h;
            }

            // (2) θ 固定で、ラインごとに d と k を交互推定
            LineResult aa = fitLine(LINE_AA, kAnchor, fitUser, theta);
            LineResult aaa = fitLine(LINE_AAA, kAnchor, fitUser, theta);
            LineResult mm = fitLine(LINE_MAX_MINUS, kAnchor, fitUser, theta);

            // (3) d・k 固定で全ユーザーの θ を全ラインの目標から
            LineResult[] lrs = { aaa, mm, aa };
            double[] thetaAll = new double[nu];
            for (int u = 0; u < nu; u++) {
                double th = fitUser[u] ? theta[u] : THETA_ALL_PRIOR_MU;
                for (int it = 0; it < 50; it++) {
                    double g = -(th - THETA_ALL_PRIOR_MU) / THETA_ALL_PRIOR_VAR, h = -1.0 / THETA_ALL_PRIOR_VAR;
                    for (int p = userStart[u]; p < userStart[u + 1]; p++) {
                        int c = userChart[p], b = userBucket[p] & 0xFF;
                        for (int li = 0; li < lrs.length; li++) {
                            LineResult lr = lrs[li];
                            double dj = lr.d()[c];
                            if (Double.isNaN(dj)) continue;
                            int line = LINE_BUCKETS[li];
                            double pr = sig(lr.k() * (th - dj));
                            g += lr.k() * ((b >= line ? 1 : 0) - pr);
                            h -= lr.k() * lr.k() * pr * (1 - pr);
                        }
                    }
                    double step = clip(g / h);
                    th -= step;
                    if (Math.abs(step) < 1e-5) break;
                }
                thetaAll[u] = th;
            }
            return new JointResult(aa, aaa, mm, thetaAll);
        }

        /** (2) の 1 ライン分: θ 固定で全譜面の d（弱い事前分布）と、そのラインの k を交互に推定する。 */
        private LineResult fitLine(int line, double k0, boolean[] fitUser, double[] theta) {
            double[] d = new double[nc], se = new double[nc], rate = new double[nc];
            int[] nFit = new int[nc];
            double shift = line == LINE_AA ? AA_PRIOR_SHIFT : line == LINE_AAA ? AAA_PRIOR_SHIFT : 0.0;
            for (int j = 0; j < nc; j++) {
                int hit = 0;
                for (int p = chartStart[j]; p < chartStart[j + 1]; p++) if ((chartBucket[p] & 0xFF) >= line) hit++;
                int all = chartStart[j + 1] - chartStart[j];
                rate[j] = all == 0 ? 0 : (double) hit / all;
                d[j] = dup[j] ? Double.NaN : prior[j] - shift;
            }
            double k = k0;
            for (int round = 0; round < JOINT_ROUNDS; round++) {
                for (int j = 0; j < nc; j++) {
                    if (dup[j]) { se[j] = Double.NaN; continue; }
                    double mu = prior[j] - shift, dj = d[j], info = 1.0 / D_PRIOR_VAR;
                    int n = 0;
                    for (int it = 0; it < 200; it++) {
                        double g = -(dj - mu) / D_PRIOR_VAR, h = -1.0 / D_PRIOR_VAR;
                        info = 1.0 / D_PRIOR_VAR;
                        n = 0;
                        for (int p = chartStart[j]; p < chartStart[j + 1]; p++) {
                            int u = chartUser[p];
                            if (!fitUser[u]) continue;
                            n++;
                            double pr = sig(k * (theta[u] - dj));
                            int y = (chartBucket[p] & 0xFF) >= line ? 1 : 0;
                            g -= k * (y - pr);
                            double w = k * k * pr * (1 - pr);
                            h -= w;
                            info += w;
                        }
                        double step = clip(g / h);
                        dj -= step;
                        if (Math.abs(step) < 1e-6) break;
                    }
                    d[j] = dj;
                    se[j] = 1.0 / Math.sqrt(info);
                    nFit[j] = n;
                }
                // k のニュートン 1 歩（θ, d 固定）
                double g = 0, h = 0;
                for (int j = 0; j < nc; j++) {
                    if (dup[j]) continue;
                    for (int p = chartStart[j]; p < chartStart[j + 1]; p++) {
                        int u = chartUser[p];
                        if (!fitUser[u]) continue;
                        double x = theta[u] - d[j], pr = sig(k * x);
                        int y = (chartBucket[p] & 0xFF) >= line ? 1 : 0;
                        g += x * (y - pr);
                        h -= x * x * pr * (1 - pr);
                    }
                }
                if (h < 0) k -= g / h;
            }
            return new LineResult(k, d, se, nFit, rate);
        }

        /** (1) 用: ユーザー u の θ を、1 ラインの難易度 dOf（NaN の譜面は無視）固定で Newton 推定する（事前分布はアンカー用）。 */
        private double estThetaOneLine(int u, double k, double th, double[] dOf, int line) {
            for (int it = 0; it < 50; it++) {
                double g = -(th - THETA_PRIOR_MU) / THETA_PRIOR_VAR, h = -1.0 / THETA_PRIOR_VAR;
                for (int p = userStart[u]; p < userStart[u + 1]; p++) {
                    double d = dOf[userChart[p]];
                    if (Double.isNaN(d)) continue;
                    double pr = sig(k * (th - d));
                    int y = (userBucket[p] & 0xFF) >= line ? 1 : 0;
                    g += k * (y - pr);
                    h -= k * k * pr * (1 - pr);
                }
                double step = clip(g / h);
                th -= step;
                if (Math.abs(step) < 1e-5) break;
            }
            return th;
        }
    }

    /** 伸長する int 配列（100 万行を boxing せずに受けるため）。 */
    private static final class IntList {
        int[] a = new int[1 << 16];
        int size = 0;
        void add(int v) {
            if (size == a.length) a = Arrays.copyOf(a, a.length * 2);
            a[size++] = v;
        }
    }
}
