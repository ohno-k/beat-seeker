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

    /** ライン定義（桶番号）。AAA = 16/18、MAX- = 17/18。 */
    public static final int LINE_AAA = 160;
    public static final int LINE_MAX_MINUS = 170;

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
    /** DB からの復元を 1 回だけ試みたか。 */
    private final AtomicBoolean restoreTried = new AtomicBoolean(false);

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
                        Map<String, Integer> keyToIdx, int[] notes, double[] dAaa, double[] dMaxMinus,
                        double kAaa, double kMaxMinus) {}

    /** 1 ライン分の目標（譜面 × ライン）の推定結果。 */
    private record LineResult(double k, double[] d, double[] se, int[] nFit, double[] rate) {}

    /** 統合モデルの推定結果（1 人 1 つの θ）。 */
    private record JointResult(LineResult aaa, LineResult maxMinus, double[] thetaAll) {}

    /** 全体計算の結果一式（土台に変換して捨てる）。userIds / userStart / userChart / userBucket はランキング計算用。 */
    private record Snapshot(String[] titles, String[] diffs, int[] levels, int[] notes, int[] playerCounts,
                            JointResult joint, long[] userIds, int[] userStart, int[] userChart, byte[] userBucket) {}

    /** 保存形式の版。モデルや形を変えたら上げる（古い版は復元せず作り直す）。v3 = ランキング追加。 */
    private static final int PAYLOAD_VERSION = 3;

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
        List<int[]> plays = new ArrayList<>();
        jdbcTemplate.query(USER_BEST_SQL, rs -> {
            Integer i = b.keyToIdx().get(rs.getString(1) + "\u0000" + rs.getString(2));
            if (i == null) return;
            int bucket = (int) Math.min(180L, (long) rs.getInt(3) * 90L / b.notes()[i]);
            plays.add(new int[] { i, bucket });
        }, userId, userId);

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

    /** 【メソッドの役割】 d・k 固定での θ（バッチの手順 (3) と同じ Newton・事前分布。AAA と MAX- の両目標を使う）。 */
    private static double thetaFor(List<int[]> plays, Base b) {
        double th = THETA_ALL_PRIOR_MU;
        for (int it = 0; it < 50; it++) {
            double g = -(th - THETA_ALL_PRIOR_MU) / THETA_ALL_PRIOR_VAR, h = -1.0 / THETA_ALL_PRIOR_VAR;
            for (int[] p : plays) {
                for (int line : new int[] { LINE_AAA, LINE_MAX_MINUS }) {
                    double k = line == LINE_AAA ? b.kAaa() : b.kMaxMinus();
                    double dj = line == LINE_AAA ? b.dAaa()[p[0]] : b.dMaxMinus()[p[0]];
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
        }
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

    /** 【メソッドの役割】 全体計算の結果を、応答用の形 + 差分計算用の配列に変換する。 */
    private static Base toBase(Snapshot s, String computedAt) {
        JointResult jr = s.joint();
        double[] th = jr.thetaAll().clone();
        Arrays.sort(th);
        double[] rounded = new double[th.length];
        for (int i = 0; i < th.length; i++) rounded[i] = round2(th[i]);
        Map<String, Object> model = new LinkedHashMap<>();
        // k は差分の θ 計算に使うので丸めすぎない
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
            charts.add(c);
        }
        Ranking r = computeRanking(s, charts);
        model.put("maxLevel", r.maxLevel());
        return baseFromResponse(computedAt, model, charts, r.entries());
    }

    private record Ranking(int maxLevel, List<Map<String, Object>> entries) {}

    /**
     * 【メソッドの役割】 全ユーザーのロードマップレベルを計算し、ランキングにする。
     *
     * 判定はフロント（ScoreRoadmapView.vue の levels / myLevel）と同じ規則:
     *  - 目標 = 譜面 × ライン（AAA / MAX-）。難度は応答に載せる丸め後の値（小数 4 桁）を使う
     *    （丸め前の値で枠を決めると、枠の境目でフロントと 1 レベルずれることがあるため）。
     *  - 0.02 枠で目標のある枠を易しい順に Lv.1 から連番。
     *  - 達成 = プレー済み目標 ≥ min(n, max(2, ⌈n/3⌉)) かつ 達成数 × 3 ≥ プレー済み × 2。完全制覇 = 全目標達成。
     *  - その人のレベル = 達成レベルの最大番号。レベル 0（どのレベルも未達成）の人は載せない。
     * 並びはレベル降順 → 達成レベル数 → 完全制覇数。順位は同じレベルなら同順位（1, 1, 3…）。
     */
    @SuppressWarnings("unchecked")
    private static Ranking computeRanking(Snapshot s, List<Map<String, Object>> charts) {
        // 手順1: 譜面（元の添字 j）× ライン → レベル番号
        int nc = s.titles().length;
        double[][] d = new double[2][nc];
        for (double[] row : d) Arrays.fill(row, Double.NaN);
        // 曲名・難易度 → 元の添字（重複マスタ行は応答に載らないので、載っている譜面は一意）
        Map<String, Integer> idxOf = new HashMap<>(nc * 2);
        for (int j = 0; j < nc; j++) {
            if (!Double.isNaN(s.joint().aaa().d()[j])) idxOf.put(s.titles()[j] + "\u0000" + s.diffs()[j], j);
        }
        for (Map<String, Object> c : charts) {
            int j = idxOf.get(c.get("title") + "\u0000" + c.get("difficultyName"));
            d[0][j] = ((Number) ((Map<String, Object>) c.get("aaa")).get("d")).doubleValue();
            d[1][j] = ((Number) ((Map<String, Object>) c.get("maxMinus")).get("d")).doubleValue();
        }
        java.util.TreeSet<Long> slots = new java.util.TreeSet<>();
        for (double[] row : d) for (double v : row) if (!Double.isNaN(v)) slots.add((long) Math.floor(v / LEVEL_W + 1e-9));
        Map<Long, Integer> noOfSlot = new HashMap<>();
        int no = 0;
        for (long slot : slots) noOfSlot.put(slot, ++no);
        int maxLevel = no;
        int[][] levelOf = new int[2][nc];
        int[] targetsPerLevel = new int[maxLevel + 1];
        for (int li = 0; li < 2; li++) {
            for (int j = 0; j < nc; j++) {
                if (Double.isNaN(d[li][j])) { levelOf[li][j] = 0; continue; }
                levelOf[li][j] = noOfSlot.get((long) Math.floor(d[li][j] / LEVEL_W + 1e-9));
                targetsPerLevel[levelOf[li][j]]++;
            }
        }
        int[] lines = { LINE_AAA, LINE_MAX_MINUS };

        // 手順2: ユーザーごとにレベル別のプレー済み数・達成数を数えて判定
        List<long[]> rows = new ArrayList<>(); // [userId, level, cleared, complete]
        int[] played = new int[maxLevel + 1], done = new int[maxLevel + 1];
        for (int u = 0; u < s.userIds().length; u++) {
            Arrays.fill(played, 0);
            Arrays.fill(done, 0);
            for (int p = s.userStart()[u]; p < s.userStart()[u + 1]; p++) {
                int j = s.userChart()[p], b = s.userBucket()[p] & 0xFF;
                for (int li = 0; li < 2; li++) {
                    int lv = levelOf[li][j];
                    if (lv == 0) continue;
                    played[lv]++;
                    if (b >= lines[li]) done[lv]++;
                }
            }
            int level = 0, cleared = 0, complete = 0;
            for (int lv = 1; lv <= maxLevel; lv++) {
                int n = targetsPerLevel[lv];
                int minPlayed = Math.min(n, Math.max(2, (n + 2) / 3));
                if (played[lv] >= minPlayed && done[lv] * 3 >= played[lv] * 2) { level = lv; cleared++; }
                if (n > 0 && done[lv] == n) complete++;
            }
            if (level > 0) rows.add(new long[] { s.userIds()[u], level, cleared, complete });
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
        return new Ranking(maxLevel, entries);
    }

    /** 【メソッドの役割】 応答用の形（DB 保存形式と同じ）から差分計算用の配列を組み立てる。 */
    @SuppressWarnings("unchecked")
    private static Base baseFromResponse(String computedAt, Map<String, Object> model, List<Map<String, Object>> charts,
                                         List<Map<String, Object>> ranking) {
        int n = charts.size();
        Map<String, Integer> keyToIdx = new HashMap<>(n * 2);
        int[] notes = new int[n];
        double[] dAaa = new double[n], dMm = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Object> c = charts.get(i);
            keyToIdx.put(c.get("title") + "\u0000" + c.get("difficultyName"), i);
            notes[i] = ((Number) c.get("notes")).intValue();
            dAaa[i] = ((Number) ((Map<String, Object>) c.get("aaa")).get("d")).doubleValue();
            dMm[i] = ((Number) ((Map<String, Object>) c.get("maxMinus")).get("d")).doubleValue();
        }
        double kAaa = ((Number) model.get("kAaa")).doubleValue();
        double kMm = ((Number) model.get("kMaxMinus")).doubleValue();
        return new Base(computedAt, model, charts, ranking == null ? List.of() : ranking, keyToIdx, notes, dAaa, dMm, kAaa, kMm);
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
            LineResult aaa = fitLine(LINE_AAA, kAnchor, fitUser, theta);
            LineResult mm = fitLine(LINE_MAX_MINUS, kAnchor, fitUser, theta);

            // (3) d・k 固定で全ユーザーの θ を両ラインの目標から
            double[] thetaAll = new double[nu];
            for (int u = 0; u < nu; u++) {
                double th = fitUser[u] ? theta[u] : THETA_ALL_PRIOR_MU;
                for (int it = 0; it < 50; it++) {
                    double g = -(th - THETA_ALL_PRIOR_MU) / THETA_ALL_PRIOR_VAR, h = -1.0 / THETA_ALL_PRIOR_VAR;
                    for (int p = userStart[u]; p < userStart[u + 1]; p++) {
                        int c = userChart[p], b = userBucket[p] & 0xFF;
                        for (LineResult lr : new LineResult[] { aaa, mm }) {
                            double dj = lr.d()[c];
                            if (Double.isNaN(dj)) continue;
                            int line = lr == aaa ? LINE_AAA : LINE_MAX_MINUS;
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
            return new JointResult(aaa, mm, thetaAll);
        }

        /** (2) の 1 ライン分: θ 固定で全譜面の d（弱い事前分布）と、そのラインの k を交互に推定する。 */
        private LineResult fitLine(int line, double k0, boolean[] fitUser, double[] theta) {
            double[] d = new double[nc], se = new double[nc], rate = new double[nc];
            int[] nFit = new int[nc];
            double shift = line == LINE_AAA ? AAA_PRIOR_SHIFT : 0.0;
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
