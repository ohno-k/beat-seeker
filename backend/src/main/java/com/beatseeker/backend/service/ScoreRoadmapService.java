package com.beatseeker.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 推定結果を計算・キャッシュするサービス。
 *
 * 目的:
 *  - ANOTHER / LEGGENDARIA 全譜面（☆1〜12）について「AAA（または MAX-）を取る難しさ」を
 *    1 本の目盛りに並べ、初めての AAA から全 AAA までの段階的な道のりを作る。
 *  - 到達率をそのまま比べると、☆10 以下は遊んだ人の実力が譜面ごとに違う（選択バイアス）ため
 *    並びが崩れる。そこで実力アンカー付きのラッシュモデルで推定する。
 *
 * モデル（ラインごとに独立に推定。ライン = AAA は桶 160、MAX- は桶 170。桶は理論値の 1/180 単位）:
 *  - P(プレイヤー p が譜面 j でライン到達) = σ(k・(θ_p − d_j))
 *  - ☆11/12 のうち active 難易度表に載っている譜面は d_j を表の値に固定（アンカー）。
 *    これで θ と d がどちらも難易度表と同じ目盛り（11.0〜13.1）になる。
 *  - 手順: (1) アンカーを 30 譜面以上遊んだ人の θ と共通の傾き k を交互に推定
 *          (2) θ を固定して全譜面の d を推定（弱い事前分布: 公式レベルからの目安、σ=1）
 *          (3) d を固定して全ユーザーの θ を全譜面から推定し直す（☆11/12 を遊ばない人にも θ を出す）
 *  - Newton 法の 1 歩は ±0.3 に制限する（初期値が遠いとヘッセ行列がほぼ 0 で発散するため）。
 *  - 2026-09-23 に Node で同じ手順を本番データに当てた検算: 表 11.0〜11.4 を隠して予測した平均誤差 0.10。
 *
 * 動作は {@link SongScoreSpectrumCacheService} と同じオンデマンド非同期方式
 * （空または 1 時間超で再計算を起動し、計算中はフロントがポーリング）。
 * 生データ約 100 万行は List&lt;Map&gt; に展開せず、カーソルで受けて int 配列へ詰める。
 */
@Service
public class ScoreRoadmapService {

    private static final Logger log = LoggerFactory.getLogger(ScoreRoadmapService.class);

    private static final long STALE_AFTER_MS = 60L * 60L * 1000L;
    private static final int STREAM_FETCH_SIZE = 5000;
    private static final String BUILD_STATEMENT_TIMEOUT = "300s";

    /** ライン定義（桶番号）。AAA = 16/18、MAX- = 17/18。 */
    public static final int LINE_AAA = 160;
    public static final int LINE_MAX_MINUS = 170;

    /** θ 推定に使う最低アンカー譜面数。 */
    private static final int MIN_ANCHOR_PLAYS = 30;
    /** θ の事前分布（アンカー推定時）。 */
    private static final double THETA_PRIOR_MU = 11.8, THETA_PRIOR_VAR = 1.0;
    /** θ の事前分布（全譜面での再推定時。初心者も含むので弱く）。 */
    private static final double THETA_ALL_PRIOR_MU = 11.0, THETA_ALL_PRIOR_VAR = 4.0;
    /** d の事前分布の分散。 */
    private static final double D_PRIOR_VAR = 1.0;
    private static final double MAX_STEP = 0.3;

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

    private final DataSource dataSource;
    private final TransactionTemplate txTemplate;

    private volatile Snapshot snapshot = null;
    private volatile Instant computedAt = null;
    private volatile String lastError = null;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public ScoreRoadmapService(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setReadOnly(true);
    }

    /** 1 ライン分の推定結果。 */
    private record LineResult(double k, double[] d, double[] se, int[] nFit, double[] rate, double[] thetaAll) {}

    /** 不変の計算結果一式。 */
    private record Snapshot(
            String[] titles, String[] diffs, int[] levels, int[] playerCounts,
            long[] userIds, Map<Long, Integer> userIdx,
            int[] userStart, int[] userChart, byte[] userBucket,
            LineResult aaa, LineResult maxMinus) {}

    /**
     * 【メソッドの役割】 キャッシュ状態を返し、必要なら再計算を起動する。
     *
     * @param force   true なら鮮度に関係なく再計算
     * @param userId  現在地を表示するユーザー（null なら user は返さない）
     * @param userLabel 表示名（そのまま返す）
     */
    public Map<String, Object> requestSnapshot(boolean force, Long userId, String userLabel) {
        Instant at = computedAt;
        boolean stale = at == null || System.currentTimeMillis() - at.toEpochMilli() > STALE_AFTER_MS;
        if ((force || stale) && refreshing.compareAndSet(false, true)) {
            CompletableFuture.runAsync(this::refresh);
        }
        Snapshot s = snapshot;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", s != null);
        body.put("refreshing", refreshing.get());
        body.put("computedAt", at == null ? null : at.toString());
        body.put("error", lastError);
        if (s == null) return body;

        body.put("lines", Map.of(
                "aaa", lineMeta(s.aaa()),
                "maxMinus", lineMeta(s.maxMinus())));

        List<Map<String, Object>> charts = new ArrayList<>(s.titles().length);
        for (int j = 0; j < s.titles().length; j++) {
            if (Double.isNaN(s.aaa().d()[j])) continue; // 重複マスタ行
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("i", j);
            c.put("title", s.titles()[j]);
            c.put("difficultyName", s.diffs()[j]);
            c.put("level", s.levels()[j]);
            c.put("playerCount", s.playerCounts()[j]);
            c.put("aaa", chartLine(s.aaa(), j));
            c.put("maxMinus", chartLine(s.maxMinus(), j));
            charts.add(c);
        }
        body.put("charts", charts);

        if (userId != null) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("userId", userId);
            u.put("label", userLabel);
            Integer ui = s.userIdx().get(userId);
            if (ui == null) {
                u.put("found", false);
            } else {
                u.put("found", true);
                u.put("thetaAaa", round2(s.aaa().thetaAll()[ui]));
                u.put("thetaMaxMinus", round2(s.maxMinus().thetaAll()[ui]));
                // [譜面 i, 桶] の組。桶 ≥ 160 が AAA、≥ 170 が MAX-。
                List<int[]> plays = new ArrayList<>();
                for (int p = s.userStart()[ui]; p < s.userStart()[ui + 1]; p++) {
                    plays.add(new int[] { s.userChart()[p], s.userBucket()[p] & 0xFF });
                }
                u.put("plays", plays);
            }
            body.put("user", u);
        }
        return body;
    }

    private static Map<String, Object> lineMeta(LineResult r) {
        double[] th = r.thetaAll().clone();
        Arrays.sort(th);
        double[] rounded = new double[th.length];
        for (int i = 0; i < th.length; i++) rounded[i] = round2(th[i]);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", round2(r.k()));
        m.put("thetas", rounded); // 全ユーザーの θ（昇順）。「この段階に達している人の割合」に使う
        return m;
    }

    private static Map<String, Object> chartLine(LineResult r, int j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("d", round2(r.d()[j]));
        m.put("se", round2(r.se()[j]));
        m.put("n", r.nFit()[j]);
        m.put("rate", Math.round(r.rate()[j] * 1000.0) / 10.0);
        return m;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 【メソッドの役割】 再計算本体。呼び出し側で refreshing を true にしてから呼ぶ。 */
    private void refresh() {
        long start = System.currentTimeMillis();
        try {
            this.snapshot = build();
            this.computedAt = Instant.now();
            this.lastError = null;
            log.info("Built score roadmap: {} charts, {} users in {} ms",
                    snapshot.titles().length, snapshot.userIds().length, System.currentTimeMillis() - start);
        } catch (Exception e) {
            this.lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("Failed to build score roadmap after {} ms", System.currentTimeMillis() - start, e);
        } finally {
            refreshing.set(false);
        }
    }

    private Snapshot build() {
        // 手順1: 譜面マスタと難易度表（アンカー）
        List<Object[]> chartRows = new ArrayList<>();
        Map<String, Double> rankOf = new HashMap<>();
        // 生データは int 配列に詰める（user_id, 譜面 idx, 桶）
        IntList rowUser = new IntList(), rowChart = new IntList(), rowBucket = new IntList();
        Map<Long, Integer> userIdx = new HashMap<>();
        List<Long> userIdList = new ArrayList<>();
        Map<Long, Integer> chartIdxById = new HashMap<>();

        txTemplate.execute(status -> {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.setFetchSize(STREAM_FETCH_SIZE);
            String product = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
            if (product != null && product.toLowerCase().contains("postgres")) {
                jdbc.execute("SET LOCAL statement_timeout = '" + BUILD_STATEMENT_TIMEOUT + "'");
            }
            jdbc.query(CHARTS_SQL, rs -> {
                chartIdxById.put(rs.getLong(1), chartRows.size());
                chartRows.add(new Object[] { rs.getString(2), rs.getString(3), rs.getInt(4) });
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
                    ui = userIdList.size();
                    userIdx.put(uid, ui);
                    userIdList.add(uid);
                }
                rowUser.add(ui);
                rowChart.add(ci);
                rowBucket.add(Math.max(0, Math.min(180, rs.getInt(3))));
            });
            return null;
        });

        int nc = chartRows.size(), nu = userIdList.size(), nr = rowUser.size;
        String[] titles = new String[nc], diffs = new String[nc];
        int[] levels = new int[nc];
        double[] table = new double[nc], prior = new double[nc];
        boolean[] dup = new boolean[nc];
        Map<String, Integer> keyCount = new HashMap<>();
        for (int j = 0; j < nc; j++) {
            Object[] r = chartRows.get(j);
            titles[j] = (String) r[0];
            diffs[j] = (String) r[1];
            levels[j] = (Integer) r[2];
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
        LineResult aaa = m.fit(LINE_AAA);
        LineResult mm = m.fit(LINE_MAX_MINUS);

        long[] userIds = new long[nu];
        for (int i = 0; i < nu; i++) userIds[i] = userIdList.get(i);
        return new Snapshot(titles, diffs, levels, playerCounts, userIds, userIdx,
                userStart, userChart, userBucket, aaa, mm);
    }

    /** 推定計算（純粋計算。DB に触らない）。 */
    private record Model(int nc, int nu, double[] table, double[] prior, boolean[] dup,
                         int[] userStart, int[] userChart, byte[] userBucket,
                         int[] chartStart, int[] chartUser, byte[] chartBucket) {

        private static double sig(double x) { return 1.0 / (1.0 + Math.exp(-x)); }
        private static double clip(double v) { return Math.max(-MAX_STEP, Math.min(MAX_STEP, v)); }

        LineResult fit(int line) {
            // (1) アンカーを十分遊んだユーザーの θ と k
            int[] anchorCount = new int[nu];
            for (int u = 0; u < nu; u++) {
                for (int p = userStart[u]; p < userStart[u + 1]; p++) if (!Double.isNaN(table[userChart[p]])) anchorCount[u]++;
            }
            boolean[] fitUser = new boolean[nu];
            double[] theta = new double[nu];
            for (int u = 0; u < nu; u++) { fitUser[u] = anchorCount[u] >= MIN_ANCHOR_PLAYS; theta[u] = THETA_PRIOR_MU; }
            double k = 3.0;
            for (int outer = 0; outer < 15; outer++) {
                for (int u = 0; u < nu; u++) {
                    if (fitUser[u]) theta[u] = estTheta(u, k, theta[u], table, line, THETA_PRIOR_MU, THETA_PRIOR_VAR);
                }
                double g = 0, h = 0;
                for (int u = 0; u < nu; u++) {
                    if (!fitUser[u]) continue;
                    for (int p = userStart[u]; p < userStart[u + 1]; p++) {
                        double d = table[userChart[p]];
                        if (Double.isNaN(d)) continue;
                        double x = theta[u] - d, pr = sig(k * x);
                        int y = (userBucket[p] & 0xFF) >= line ? 1 : 0;
                        g += x * (y - pr);
                        h -= x * x * pr * (1 - pr);
                    }
                }
                if (h < 0) k -= g / h;
            }

            // (2) θ 固定で全譜面の d
            double[] d = new double[nc], se = new double[nc], rate = new double[nc];
            int[] nFit = new int[nc];
            for (int j = 0; j < nc; j++) {
                int hit = 0;
                for (int p = chartStart[j]; p < chartStart[j + 1]; p++) if ((chartBucket[p] & 0xFF) >= line) hit++;
                int all = chartStart[j + 1] - chartStart[j];
                rate[j] = all == 0 ? 0 : (double) hit / all;
                if (dup[j]) { d[j] = Double.NaN; se[j] = Double.NaN; continue; }
                double dj = prior[j], info = 1.0 / D_PRIOR_VAR;
                int n = 0;
                for (int it = 0; it < 200; it++) {
                    double g = -(dj - prior[j]) / D_PRIOR_VAR, h = -1.0 / D_PRIOR_VAR;
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

            // (3) d 固定で全ユーザーの θ を全譜面から
            double[] thetaAll = new double[nu];
            for (int u = 0; u < nu; u++) {
                thetaAll[u] = estTheta(u, k, fitUser[u] ? theta[u] : THETA_ALL_PRIOR_MU, d, line,
                        THETA_ALL_PRIOR_MU, THETA_ALL_PRIOR_VAR);
            }
            return new LineResult(k, d, se, nFit, rate, thetaAll);
        }

        /** ユーザー u の θ を、難易度 dOf（NaN の譜面は無視）固定で Newton 推定する。 */
        private double estTheta(int u, double k, double th, double[] dOf, int line, double mu, double var) {
            for (int it = 0; it < 50; it++) {
                double g = -(th - mu) / var, h = -1.0 / var;
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
