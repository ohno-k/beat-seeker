package com.beatseeker.backend.service;

import com.beatseeker.backend.repository.ScoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【Service の役割】 譜面ペア (A, B) の単純線形回帰（B を A から予測する係数）を
 * 全ペア分まとめてメモリに保持するキャッシュ。
 *
 * 用途:
 *  - 「伸びしろランキング」: ユーザーがプレイ済みの全譜面 B について、同じく
 *    プレイ済みの他譜面 A 達から予測スコアを算出 → (予測 − 実) を伸びしろとして提示する。
 *
 * 設計:
 *  - 対象は ANOTHER / LEGGENDARIA 譜面のみ（rate-tier 集計と同スコープ）
 *  - 集計対象スコアは A 以上 (score_rate >= 66.67%) のみ。捨てスコア相当を排除。
 *  - サンプル数 < 30 / |r| < 0.4 のペアは破棄（ノイズ除去）
 *  - 初回参照時に {@link #ensureBuilt()} で全件構築（数秒）→ 以降はオンメモリ参照
 *  - スコア更新による無効化は現状未対応（実験機能のため。将来は再構築コマンドを追加）
 */
@Service
public class PairRegressionService {

    private static final Logger log = LoggerFactory.getLogger(PairRegressionService.class);

    /** 集計に必要な最小サンプル数（両譜面プレイ済みユーザー数）。 */
    private static final int MIN_N = 30;
    /**
     * キャッシュ採用する最小相関係数の絶対値。
     * 予測時はさらに {@link #PRIMARY_R} / {@link #FALLBACK_R} で再フィルタする。
     */
    private static final double MIN_R = 0.90;
    /** 1段目（HIGH精度）の最小相関係数。 */
    public static final double PRIMARY_R = 0.95;
    /** 2段目（LOW精度フォールバック）の最小相関係数。 */
    public static final double FALLBACK_R = 0.90;
    /** 表示する gap (= predicted - actual) に掛ける補正係数。
     *  低相関ペア除外で予測が控えめになる傾向を補正し、現行ベースに近い体感の伸びしろを提示する。 */
    private static final double GAP_COEFFICIENT = 1.2;
    /** 加重平均に必要な最小サポート数（少なすぎる予測は不安定なので捨てる）。 */
    public static final int SUPPORT_MIN = 3;
    /**
     * 重み関数で使う底点。|r| からこの値を引いた残差に対して {@link #WEIGHT_EXP} 乗を適用する。
     * 0.85 に設定すると、|r| ∈ [0.90, 1.00] のレンジで残差が 0.05〜0.15 となり、
     * その間の高次乗が大きく差をつけるためコントラストが効く。
     */
    private static final double WEIGHT_BASE = 0.85;
    /**
     * 重み関数の指数。値を上げるほど高 |r| の寄与が支配的になり、貢献度が偏る。
     *  - 旧来の r² 相当: 約 2 乗（r=0.90 と r=0.95 で重み比 ~0.90 → ほぼ均等）
     *  - 6 乗: r=0.90 と r=0.95 で重み比 ~0.016 → 高相関譜面に強く集中
     */
    private static final double WEIGHT_EXP = 6.0;
    /**
     * logit 変換時のクランプ値。スコアレートが 0 または 1 の極値だと logit が ±∞ になるので、
     * [LOGIT_RATE_CLAMP, 1 − LOGIT_RATE_CLAMP] に押し込む。
     * 1e-4 は理論値貼り付き相当（rate=0.9999）まで許容するので、実データでは事実上ノークランプ。
     */
    private static final double LOGIT_RATE_CLAMP = 1e-4;
    /** A グレード閾値（scoreRate %）。 */
    public static final double A_GRADE_RATE = 0.6667;
    /**
     * 加法モデルの交互平均の反復回数。
     * θ と δ を交互に取り直すだけの単純な反復で、数回で十分収束する（スコア数万行で数十 ms）。
     */
    static final int ADDITIVE_ITERATIONS = 8;

    private final ScoreRepository scoreRepository;

    /** chartA(=title\0difficultyName) -> chartB -> Reg（B を A から予測する回帰）。volatile でロック無し公開。 */
    private volatile Map<String, Map<String, Reg>> cache = Collections.emptyMap();
    /** chartKey -> notes。max_score = notes * 2。再構築時に同時に作る。 */
    private volatile Map<String, Integer> notesByKey = Collections.emptyMap();
    /** chartKey -> 全ユーザー (A以上) の actual スコア最大値。
     *  予測スコアのクランプ上限に使い、誰も達成していない非現実的な予測 (例: 理論値貼り付き) を防ぐ。 */
    private volatile Map<String, Integer> communityMaxByKey = Collections.emptyMap();
    /**
     * chartKey -> 加法モデルの譜面効果 δ。
     * ペア回帰の参照が足りない譜面（不人気曲・新曲）でも予測を切らさないための事前分布として
     * コスパ埋めレコメンドが使う。詳細は {@link AdditiveModel}。
     */
    private volatile Map<String, ChartEffect> chartEffectByKey = Collections.emptyMap();
    /** 加法モデルの残差標準偏差（logit 空間、全スコアでプール）。譜面ごとの sd の縮約先。 */
    private volatile double pooledResidSd = 0.5;
    /** 構築済みフラグ。 */
    private volatile boolean built = false;
    /** 再構築の同時実行を防ぐロック。 */
    private final Object buildLock = new Object();

    /**
     * 加法モデル {@code logit(score) ≈ θ_user + δ_chart} の譜面側パラメータ。
     *
     * @see AdditiveModel
     */
    public static class ChartEffect {
        /** 譜面効果 δ（logit 空間）。大きいほど「同じ実力でスコアが出やすい」譜面。 */
        public double delta;
        /** δ の推定に使ったユーザー数（A 以上のスコアを持つ人数）。 */
        public int n;
        /** この譜面の残差標準偏差（logit 空間）。n が小さいときは NaN になり得るので pooled で縮約して使う。 */
        public double residSd;
    }

    /** 単一ペアの線形回帰結果。 */
    public static class Reg {
        public int n;
        /** B = slope * A + intercept */
        public double slope;
        public double intercept;
        /** ピアソン相関係数。 */
        public double r;
        /**
         * 目的変数 B（logit 空間）の標本標準偏差。
         * 残差の標準偏差は sdY × √(1 − r²) で求まるため、
         * 「予測がどれくらいブレるか」を知りたい呼び出し側（コスパ埋めレコメンド）が使う。
         */
        public double sdY;
    }

    public PairRegressionService(ScoreRepository scoreRepository) {
        this.scoreRepository = scoreRepository;
    }

    /**
     * 【メソッドの役割】 (chartA, chartB) の回帰を返す。未構築なら同期構築する。
     */
    public Reg getRegression(String chartA, String chartB) {
        ensureBuilt();
        Map<String, Reg> m = cache.get(chartA);
        return m == null ? null : m.get(chartB);
    }

    /**
     * 【メソッドの役割】 譜面 A から予測できる全譜面 B の回帰をまとめて返す（未構築なら同期構築）。
     *
     * {@link #getRegression(String, String)} を B 側全走査で呼ぶと
     * 「候補譜面数 × 参照譜面数」回の Map 検索になるが、キャッシュは
     * chartA → (chartB → Reg) の疎な入れ子なので、A 側から引けば
     * 「実際に相関のあるペアの数」だけで済む。コスパ埋めレコメンドのように
     * 未プレイを含む全譜面を候補にする用途ではこちらを使う。
     *
     * @param chartA 参照譜面のキー（title + "\0" + difficultyName）
     * @return chartB → Reg の Map。該当なしなら null
     */
    public Map<String, Reg> getRegressionsFrom(String chartA) {
        ensureBuilt();
        return cache.get(chartA);
    }

    /**
     * 【メソッドの役割】 全譜面の (title, difficultyName, notes) を返す。
     */
    public Map<String, Integer> getNotesByKey() {
        ensureBuilt();
        return notesByKey;
    }

    /**
     * 【メソッドの役割】 譜面キー → コミュニティ実測最高スコアの Map を返す。
     * 予測スコアのクランプ上限（誰も達成していないスコアは提示しない）に使う。
     */
    public Map<String, Integer> getCommunityMaxByKey() {
        ensureBuilt();
        return communityMaxByKey;
    }

    /**
     * 【メソッドの役割】 譜面キー → 加法モデルの譜面効果 δ の Map を返す。
     * ペア回帰が無い譜面の予測（コスパ埋めレコメンドのフォールバック）に使う。
     */
    public Map<String, ChartEffect> getChartEffects() {
        ensureBuilt();
        return chartEffectByKey;
    }

    /**
     * 【メソッドの役割】 加法モデルの残差標準偏差（logit 空間、全体プール）を返す。
     * 譜面ごとの残差 sd はサンプルが少ないと不安定なので、呼び出し側はこの値へ縮約して使う。
     */
    public double getPooledResidSd() {
        ensureBuilt();
        return pooledResidSd;
    }

    /**
     * 【メソッドの役割】 構築済みでなければ同期構築。多重呼び出しは内部ロックで安全。
     */
    public void ensureBuilt() {
        if (built) return;
        synchronized (buildLock) {
            if (built) return;
            rebuild();
        }
    }

    /**
     * 【メソッドの役割】 キャッシュ全体を再構築する。
     * 再構築コスト: 全 ANOTHER/LEGG スコア取得 + ユーザーごと譜面ペア生成 + 集計。
     * 数秒オーダー。
     */
    public void rebuild() {
        long t0 = System.currentTimeMillis();

        // 1) ノーツ数マップ
        Map<String, Integer> notesMap = new HashMap<>();
        for (Map<String, Object> row : scoreRepository.findAllAnotherLeggChartNotes()) {
            String key = row.get("title") + "\0" + row.get("difficultyName");
            int n = ((Number) row.get("notes")).intValue();
            if (n > 0) notesMap.put(key, n);
        }

        // 2) 譜面 → int インデックス変換テーブルを作る。
        // 以後はメモリ節約のため String キーではなく int でペアを扱う。
        String[] chartKeys = notesMap.keySet().toArray(new String[0]);
        Map<String, Integer> chartIdxMap = new HashMap<>(chartKeys.length * 2);
        for (int i = 0; i < chartKeys.length; i++) {
            chartIdxMap.put(chartKeys[i], i);
        }
        int numCharts = chartKeys.length;

        // 3) 全 ANOTHER/LEGG スコアを取得し、A 以上のみ user 別に
        // long[] へパック格納する。packed = (chartIdx << 32) | (score & 0xFFFFFFFFL)
        // String/Integer のボックス化を避けてヒープ消費を抑える。
        // 同時に各譜面の community max（A以上の中での最大値 = そのまま実測最高）を集計。
        Map<Long, java.util.List<long[]>> userScoresBuilder = new HashMap<>();
        Map<String, Integer> communityMaxMap = new HashMap<>();
        for (Map<String, Object> row : scoreRepository.findAllAnotherLeggScores()) {
            String key = row.get("title") + "\0" + row.get("difficultyName");
            Integer chartIdx = chartIdxMap.get(key);
            if (chartIdx == null) continue;
            Integer notes = notesMap.get(key);
            if (notes == null) continue;
            int score = ((Number) row.get("score")).intValue();
            if (score < notes * 2.0 * A_GRADE_RATE) continue;
            long userId = ((Number) row.get("userId")).longValue();
            long packed = ((long) chartIdx << 32) | (score & 0xFFFFFFFFL);
            userScoresBuilder.computeIfAbsent(userId, k -> new java.util.ArrayList<>()).add(new long[]{packed});
            Integer prevMax = communityMaxMap.get(key);
            if (prevMax == null || score > prevMax) communityMaxMap.put(key, score);
        }

        // List<long[]> → 単一 long[] に圧縮
        Map<Long, long[]> userScores = new HashMap<>(userScoresBuilder.size() * 2);
        // 同時に「譜面 idx → 演奏したユーザー集合」インデックスも作る（per-A 処理の高速化用）
        @SuppressWarnings("unchecked")
        java.util.List<Long>[] chartUsers = new java.util.List[numCharts];
        for (Map.Entry<Long, java.util.List<long[]>> e : userScoresBuilder.entrySet()) {
            java.util.List<long[]> list = e.getValue();
            long[] arr = new long[list.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = list.get(i)[0];
                int idx = (int) (arr[i] >>> 32);
                if (chartUsers[idx] == null) chartUsers[idx] = new java.util.ArrayList<>();
                chartUsers[idx].add(e.getKey());
            }
            userScores.put(e.getKey(), arr);
        }
        userScoresBuilder = null; // GC 促進
        log.info("PairRegressionService: {} users, {} charts in scope", userScores.size(), numCharts);

        // notes を idx でひける配列にしておく（タイトループ内の Map.get を避けるため）
        int[] notesByIdx = new int[numCharts];
        for (int i = 0; i < numCharts; i++) {
            Integer n = notesMap.get(chartKeys[i]);
            notesByIdx[i] = n == null ? 0 : n;
        }

        // 3.5) 加法モデル（θ_user + δ_chart）を同じデータで推定する。
        //      ペア回帰は「両方プレイ済み 30 人以上」が要るので不人気曲・新曲が丸ごと抜けるが、
        //      δ は「その譜面をプレイした人」だけで立つので、ほぼ全譜面に予測の足場ができる。
        java.util.List<int[]> fitIdx = new java.util.ArrayList<>(userScores.size());
        java.util.List<double[]> fitLogit = new java.util.ArrayList<>(userScores.size());
        for (long[] uScores : userScores.values()) {
            int[] idx = new int[uScores.length];
            double[] lg = new double[uScores.length];
            for (int i = 0; i < uScores.length; i++) {
                int cIdx = (int) (uScores[i] >>> 32);
                idx[i] = cIdx;
                lg[i] = scoreToLogit((int) uScores[i], notesByIdx[cIdx]);
            }
            fitIdx.add(idx);
            fitLogit.add(lg);
        }
        AdditiveModel.Result fit = AdditiveModel.fit(fitIdx, fitLogit, numCharts, ADDITIVE_ITERATIONS);
        Map<String, ChartEffect> newEffects = new HashMap<>();
        for (int c = 0; c < numCharts; c++) {
            if (fit.n[c] <= 0) continue;
            ChartEffect ce = new ChartEffect();
            ce.delta = fit.delta[c];
            ce.n = fit.n[c];
            ce.residSd = fit.residSd[c];
            newEffects.put(chartKeys[c], ce);
        }
        fitIdx = null;
        fitLogit = null;

        // 4) 譜面 A ごとに per-A 累積→回帰計算→フィルタ→キャッシュ格納
        // 各 A の処理が終わった時点で Acc[] は GC 対象になるため、ピークメモリ << 全ペア
        Map<String, Map<String, Reg>> newCache = new HashMap<>();
        long keptPairs = 0;
        Acc[] accsBuf = new Acc[numCharts]; // 再利用する固定サイズバッファ

        for (int aIdx = 0; aIdx < numCharts; aIdx++) {
            java.util.List<Long> users = chartUsers[aIdx];
            if (users == null) continue;
            int notesA = notesByIdx[aIdx];
            if (notesA <= 0) continue;
            // 前回のループ結果をクリア（new しない、null 代入で参照を切る）
            java.util.Arrays.fill(accsBuf, null);

            for (Long uid : users) {
                long[] uScores = userScores.get(uid);
                if (uScores == null) continue;
                // この user の A スコアを取り出す
                int aScore = -1;
                for (long packed : uScores) {
                    if ((int) (packed >>> 32) == aIdx) {
                        aScore = (int) packed;
                        break;
                    }
                }
                if (aScore < 0) continue; // 起こらないはずだが防御
                // 回帰は logit 空間で行う（飽和する境界を自然に表現するため）
                double logitA = scoreToLogit(aScore, notesA);
                // user のその他譜面ごとに (logitA, logitB) を Acc に追加
                for (long packed : uScores) {
                    int bIdx = (int) (packed >>> 32);
                    if (bIdx == aIdx) continue;
                    int notesB = notesByIdx[bIdx];
                    if (notesB <= 0) continue;
                    int bScore = (int) packed;
                    double logitB = scoreToLogit(bScore, notesB);
                    Acc acc = accsBuf[bIdx];
                    if (acc == null) {
                        acc = new Acc();
                        accsBuf[bIdx] = acc;
                    }
                    acc.add(logitA, logitB);
                }
            }

            // この A 分の回帰計算 + フィルタ
            Map<String, Reg> bMap = null;
            for (int bIdx = 0; bIdx < numCharts; bIdx++) {
                Acc acc = accsBuf[bIdx];
                if (acc == null) continue;
                Reg reg = acc.compute();
                if (reg != null && reg.n >= MIN_N && Math.abs(reg.r) >= MIN_R) {
                    if (bMap == null) bMap = new HashMap<>();
                    bMap.put(chartKeys[bIdx], reg);
                    keptPairs++;
                }
            }
            if (bMap != null) newCache.put(chartKeys[aIdx], bMap);
        }

        cache = newCache;
        notesByKey = notesMap;
        communityMaxByKey = communityMaxMap;
        chartEffectByKey = newEffects;
        pooledResidSd = fit.pooledSd;
        built = true;
        long elapsed = System.currentTimeMillis() - t0;
        log.info("PairRegressionService rebuilt in {} ms ({} chartA, {} kept pairs after filter, {} chart effects, pooled sd {})",
                elapsed, newCache.size(), keptPairs, newEffects.size(), String.format("%.3f", fit.pooledSd));
    }

    /**
     * 【メソッドの役割】 指定ユーザーの「伸びしろ」一覧を返す。
     *
     * 2段階の予測を行う:
     *  - 1段目 (HIGH 精度): |r| ≧ {@link #PRIMARY_R} のペアだけで加重平均
     *  - 2段目 (LOW 精度): 1段目の support が {@link #SUPPORT_MIN} 未満の譜面だけ
     *    |r| ≧ {@link #FALLBACK_R} で再計算し、accuracy=LOW として表示
     *
     * 表示用の predicted/gap は {@link #GAP_COEFFICIENT} でスケール後 maxScore でクランプ。
     *
     * @param userId 対象ユーザー ID
     * @return 各譜面の {title, difficultyName, difficultyLevel, currentScore, predictedScore,
     *         gap, supportCount, accuracy("HIGH"|"LOW"), ...}
     */
    public List<Map<String, Object>> computeGrowthPotential(Long userId) {
        ensureBuilt();

        // 1) 自分の ANOTHER/LEGG スコアを取得し、A 以上を残す
        List<Map<String, Object>> userRows = scoreRepository.findUserAnotherLeggScores(userId);
        Map<String, int[]> myByKey = new HashMap<>(); // key -> [score, difficultyLevel]
        for (Map<String, Object> row : userRows) {
            String title = (String) row.get("title");
            String diff = (String) row.get("difficultyName");
            int score = ((Number) row.get("score")).intValue();
            int level = row.get("difficultyLevel") == null ? 0 : ((Number) row.get("difficultyLevel")).intValue();
            String key = title + "\0" + diff;

            Integer notes = notesByKey.get(key);
            if (notes == null) continue;
            if (score < notes * 2.0 * A_GRADE_RATE) continue;
            myByKey.put(key, new int[]{score, level});
        }

        // 2) 各 B について HIGH (|r|≧PRIMARY_R) と LOW (|r|≧FALLBACK_R) の加重平均を同時に算出
        // 予測は logit 空間で行い、最後にスコア空間へ戻す。
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, int[]> bEntry : myByKey.entrySet()) {
            String chartB = bEntry.getKey();
            int actualB = bEntry.getValue()[0];
            int levelB = bEntry.getValue()[1];
            Integer notesB = notesByKey.get(chartB);
            if (notesB == null || notesB <= 0) continue;
            double maxScoreB = notesB * 2.0;

            double sumWHigh = 0.0, sumWPHigh = 0.0;
            double sumWLow  = 0.0, sumWPLow  = 0.0;
            int supHigh = 0, supLow = 0;

            for (Map.Entry<String, int[]> aEntry : myByKey.entrySet()) {
                String chartA = aEntry.getKey();
                if (chartA.equals(chartB)) continue;
                int actualA = aEntry.getValue()[0];
                Integer notesA = notesByKey.get(chartA);
                if (notesA == null || notesA <= 0) continue;

                Reg reg = getRegression(chartA, chartB);
                if (reg == null) continue;

                double absR = Math.abs(reg.r);
                if (absR < FALLBACK_R) continue; // キャッシュ側で既に弾かれているはずだが念のため

                // logit 空間での線形予測 → sigmoid でスコアレートへ → スコアへ
                double logitA = scoreToLogit(actualA, notesA);
                double predLogitB = reg.slope * logitA + reg.intercept;
                double predRateB = logitToScoreRate(predLogitB);
                double pred = predRateB * maxScoreB;
                double w = computeWeight(reg.r);
                sumWLow += w;
                sumWPLow += pred * w;
                supLow++;
                if (absR >= PRIMARY_R) {
                    sumWHigh += w;
                    sumWPHigh += pred * w;
                    supHigh++;
                }
            }

            // HIGH 優先、足りなければ LOW フォールバック
            String accuracy;
            int support;
            double rawPredicted;
            if (supHigh >= SUPPORT_MIN && sumWHigh > 0) {
                accuracy = "HIGH";
                support = supHigh;
                rawPredicted = sumWPHigh / sumWHigh;
            } else if (supLow >= SUPPORT_MIN && sumWLow > 0) {
                accuracy = "LOW";
                support = supLow;
                rawPredicted = sumWPLow / sumWLow;
            } else {
                continue; // どちらでもサポート不足
            }

            Integer notes = notesByKey.get(chartB);
            int maxScore = notes == null ? 0 : notes * 2;
            // 予測の上限はコミュニティ実測最高（無ければ理論値）。
            // 「誰も達成していないスコアは予測しない」原則で、理論値貼り付きの非現実的な提示を避ける。
            Integer communityMax = communityMaxByKey.get(chartB);
            int predCap = (communityMax != null && communityMax < maxScore) ? communityMax : maxScore;

            // raw を [0, predCap] にクランプし、(clamp - actual) を係数倍してから再クランプ
            double clampedRaw = rawPredicted;
            if (predCap > 0) clampedRaw = Math.min(predCap, Math.max(0, clampedRaw));
            double scaledPredicted = actualB + (clampedRaw - actualB) * GAP_COEFFICIENT;
            if (predCap > 0) scaledPredicted = Math.min(predCap, Math.max(0, scaledPredicted));
            double gap = scaledPredicted - actualB;

            String[] keyParts = chartB.split("\0");
            Map<String, Object> r = new HashMap<>();
            r.put("title", keyParts[0]);
            r.put("difficultyName", keyParts[1]);
            r.put("difficultyLevel", levelB);
            r.put("currentScore", actualB);
            r.put("predictedScore", scaledPredicted);
            r.put("gap", gap);
            r.put("supportCount", support);
            r.put("accuracy", accuracy);
            if (maxScore > 0) {
                r.put("maxScore", maxScore);
                r.put("currentRate", actualB * 100.0 / maxScore);
                r.put("predictedRate", scaledPredicted * 100.0 / maxScore);
            }
            results.add(r);
        }

        // 3) 伸びしろ降順でソート
        results.sort((a, b) -> Double.compare(
                ((Number) b.get("gap")).doubleValue(),
                ((Number) a.get("gap")).doubleValue()));
        return results;
    }

    /**
     * 【メソッドの役割】 指定ユーザーの「ある譜面 B の伸びしろ」を支えた参照譜面 A の一覧を返す。
     *
     * フロントの「○譜面から推定」表示の内訳モーダル用。
     * {@link #computeGrowthPotential(Long)} と同じスコープ（A 以上の myByKey）で
     * 各 A について B への単体予測 (slope·actualA + intercept) と相関係数を返す。
     * |r| ≧ {@link #FALLBACK_R} のものだけ採用し、HIGH 採用かどうかも `isPrimary` で示す。
     *
     * @param userId               対象ユーザー
     * @param targetTitle          対象譜面 B の曲名
     * @param targetDifficultyName 対象譜面 B の難易度名（"ANOTHER" / "LEGGENDARIA"）
     * @return 各 A について {title, difficultyName, difficultyLevel, actualA, notesA, notesB,
     *         r, n, slope, intercept, weight, predScore, isPrimary} を |r| 降順で並べたリスト
     */
    public List<Map<String, Object>> computePotentialRefs(Long userId, String targetTitle, String targetDifficultyName) {
        ensureBuilt();

        // 1) computeGrowthPotential と同じく自分の ANOTHER/LEGG スコアを取得して A 以上を残す
        List<Map<String, Object>> userRows = scoreRepository.findUserAnotherLeggScores(userId);
        Map<String, int[]> myByKey = new HashMap<>();
        for (Map<String, Object> row : userRows) {
            String title = (String) row.get("title");
            String diff = (String) row.get("difficultyName");
            int score = ((Number) row.get("score")).intValue();
            int level = row.get("difficultyLevel") == null ? 0 : ((Number) row.get("difficultyLevel")).intValue();
            String key = title + "\0" + diff;
            Integer notes = notesByKey.get(key);
            if (notes == null) continue;
            if (score < notes * 2.0 * A_GRADE_RATE) continue;
            myByKey.put(key, new int[]{score, level});
        }

        String chartB = targetTitle + "\0" + targetDifficultyName;
        Integer notesB = notesByKey.get(chartB);
        if (notesB == null || notesB <= 0) return new ArrayList<>();
        double maxScoreB = notesB * 2.0;

        // 2) 自分が A 以上で出している全譜面 A について、B への回帰を引いて単体予測値を集める
        // 予測は logit 空間で行い、最後に rate→score に戻す（モデルと整合）。
        List<Map<String, Object>> refs = new ArrayList<>();
        for (Map.Entry<String, int[]> aEntry : myByKey.entrySet()) {
            String chartA = aEntry.getKey();
            if (chartA.equals(chartB)) continue;
            Integer notesA = notesByKey.get(chartA);
            if (notesA == null || notesA <= 0) continue;

            Reg reg = getRegression(chartA, chartB);
            if (reg == null) continue;
            double absR = Math.abs(reg.r);
            if (absR < FALLBACK_R) continue;

            int actualA = aEntry.getValue()[0];
            int levelA = aEntry.getValue()[1];
            double logitA = scoreToLogit(actualA, notesA);
            double predLogitB = reg.slope * logitA + reg.intercept;
            double predScore = logitToScoreRate(predLogitB) * maxScoreB;
            double weight = computeWeight(reg.r);

            String[] parts = chartA.split("\0");
            Map<String, Object> ref = new HashMap<>();
            ref.put("title", parts[0]);
            ref.put("difficultyName", parts[1]);
            ref.put("difficultyLevel", levelA);
            ref.put("actualA", actualA);
            ref.put("notesA", notesA);
            ref.put("notesB", notesB);
            ref.put("r", reg.r);
            ref.put("n", reg.n);
            ref.put("slope", reg.slope);
            ref.put("intercept", reg.intercept);
            ref.put("weight", weight);
            ref.put("predScore", predScore);
            // HIGH 精度の加重平均に組み込まれた A かどうか（フロントでバッジ表示に使う）
            ref.put("isPrimary", absR >= PRIMARY_R);
            refs.add(ref);
        }

        // 3) |r| 降順で並べる（最も寄与の大きい譜面を上に）
        refs.sort((a, b) -> Double.compare(
                Math.abs(((Number) b.get("r")).doubleValue()),
                Math.abs(((Number) a.get("r")).doubleValue())));
        return refs;
    }

    /**
     * 【メソッドの役割】 加重平均の重み w を相関係数 r から計算する。
     *
     * 旧来は r² だったが、|r| が 0.90〜0.95 に集まる実データでは差がほぼ出ず、
     * 弱相関ペアまで均等に効いて予測がぼやけていた。
     * 底点を {@link #WEIGHT_BASE}、指数を {@link #WEIGHT_EXP} とするシフト乗数式に切り替え、
     * 高 |r| ほど重みが急峻に立ち上がるようにする。
     *
     * @param r 相関係数（負値もあり得るので abs を取る）
     * @return 加重平均で使う重み w（≥ 0）
     */
    public static double computeWeight(double r) {
        double absR = Math.abs(r);
        // フィルタ済みなので来ない想定だが、念のため底点未満は 0 に潰す。
        if (absR <= WEIGHT_BASE) return 0;
        return Math.pow(absR - WEIGHT_BASE, WEIGHT_EXP);
    }

    /**
     * 【メソッドの役割】 スコアレート (0〜1) を logit (= log(p/(1−p))) に変換する。
     *
     * IIDX のスコアは理論値（rate=1.0）に到達不可能で、MAX-→MAX 付近で
     * 1 点の重みが指数関数的に増す（飽和する）。生スコア空間で線形回帰を取ると
     * その飽和を表現できず、上位スコアの予測が回帰直線の上に逸脱する。
     * logit 空間で回帰すれば、sigmoid の逆関数なので 0〜1 の境界で自然に
     * 頭打ちになり、上端での 1 点の重みが指数的に効くようにモデル化される。
     *
     * @param rate 0〜1 のスコアレート（fraction、% ではない）
     * @return logit 変換後の値（クランプにより ±9.21 程度に収まる）
     */
    public static double scoreRateToLogit(double rate) {
        double clamped = Math.max(LOGIT_RATE_CLAMP, Math.min(1.0 - LOGIT_RATE_CLAMP, rate));
        return Math.log(clamped / (1.0 - clamped));
    }

    /**
     * 【メソッドの役割】 logit 値をスコアレート (0〜1) に戻す（sigmoid）。
     *
     * @param logit logit 空間の値
     * @return 0〜1 のスコアレート
     */
    public static double logitToScoreRate(double logit) {
        return 1.0 / (1.0 + Math.exp(-logit));
    }

    /**
     * 【メソッドの役割】 EX スコアと notes から logit 空間の値を計算する。
     * 内部的には rate = score / (notes×2) を取って logit へ。
     */
    public static double scoreToLogit(int score, int notes) {
        if (notes <= 0) return 0;
        return scoreRateToLogit(score / (notes * 2.0));
    }

    /**
     * 【クラスの役割】 加法モデル {@code logit(score_uc) = θ_u + δ_c + ε} の推定。
     *
     * <h3>なぜ要るか</h3>
     * ペア回帰は「A と B の両方を A 以上でプレイした人が 30 人以上」というペアしか使えない。
     * 不人気曲や稼働直後の新曲はこの条件をほぼ満たせず、コスパ埋めレコメンドの候補から
     * 丸ごと抜けて「提案が枯渇する」原因になる。
     * 加法モデルは譜面ごとに「その譜面をプレイした人」だけで δ が立つので、
     * 1 人でもプレイしていれば足場ができ、誰もいなければ同ランクの平均 δ に落とせる。
     *
     * <h3>推定</h3>
     * 交互平均（θ を固定して δ を平均、δ を固定して θ を平均）を数回回すだけ。
     * 最小二乗解の座標降下に相当し、スコア数万行でも数十 ms で収束する。
     * θ と δ には定数の分だけ不定性があるが、予測に使うのは θ_u + δ_c の和なので問題ない。
     *
     * <h3>限界</h3>
     * δ は「その譜面を選んでプレイした人」から推定するので、難曲ほど強い人に偏る（選択バイアス）。
     * ペア回帰と同じ性質で、フォールバックとしては許容する。σ は譜面ごとの残差 sd を
     * プール値へ縮約して使う（呼び出し側の責務）。
     */
    static final class AdditiveModel {

        /** 推定結果。配列は譜面 idx で引く。 */
        static final class Result {
            /** 譜面効果 δ_c。プレイヤーがいない譜面は 0。 */
            final double[] delta;
            /** 譜面ごとの推定に使ったユーザー数。 */
            final int[] n;
            /** 譜面ごとの残差標準偏差。n &lt; 2 なら NaN。 */
            final double[] residSd;
            /** 全体プールの残差標準偏差。 */
            final double pooledSd;
            /** ユーザーごとの実力 θ_u（入力リストと同じ順）。テストと検証用。 */
            final double[] theta;

            Result(double[] delta, int[] n, double[] residSd, double pooledSd, double[] theta) {
                this.delta = delta;
                this.n = n;
                this.residSd = residSd;
                this.pooledSd = pooledSd;
                this.theta = theta;
            }
        }

        private AdditiveModel() {}

        /**
         * 【メソッドの役割】 交互平均で θ と δ を推定する。
         *
         * @param userChartIdx ユーザーごとのプレイ譜面 idx 配列
         * @param userLogit    同じ並びの logit(score)
         * @param numCharts    譜面数（idx の上限）
         * @param iterations   反復回数
         * @return 推定結果
         */
        static Result fit(List<int[]> userChartIdx, List<double[]> userLogit, int numCharts, int iterations) {
            int numUsers = userChartIdx.size();
            double[] theta = new double[numUsers];
            double[] delta = new double[numCharts];
            int[] n = new int[numCharts];

            // 初期値: θ_u = ユーザーの平均 logit、δ_c = 0
            for (int u = 0; u < numUsers; u++) {
                double[] lg = userLogit.get(u);
                if (lg.length == 0) continue;
                double s = 0;
                for (double v : lg) s += v;
                theta[u] = s / lg.length;
            }

            double[] sumC = new double[numCharts];
            for (int it = 0; it < iterations; it++) {
                // δ_c = mean_u (logit − θ_u)
                java.util.Arrays.fill(sumC, 0.0);
                java.util.Arrays.fill(n, 0);
                for (int u = 0; u < numUsers; u++) {
                    int[] idx = userChartIdx.get(u);
                    double[] lg = userLogit.get(u);
                    for (int i = 0; i < idx.length; i++) {
                        sumC[idx[i]] += lg[i] - theta[u];
                        n[idx[i]]++;
                    }
                }
                for (int c = 0; c < numCharts; c++) {
                    delta[c] = n[c] > 0 ? sumC[c] / n[c] : 0.0;
                }
                // θ_u = mean_c (logit − δ_c)
                for (int u = 0; u < numUsers; u++) {
                    int[] idx = userChartIdx.get(u);
                    double[] lg = userLogit.get(u);
                    if (idx.length == 0) continue;
                    double s = 0;
                    for (int i = 0; i < idx.length; i++) s += lg[i] - delta[idx[i]];
                    theta[u] = s / idx.length;
                }
            }

            // 残差 sd（譜面ごと + 全体プール）
            double[] sq = new double[numCharts];
            double totalSq = 0;
            long total = 0;
            for (int u = 0; u < numUsers; u++) {
                int[] idx = userChartIdx.get(u);
                double[] lg = userLogit.get(u);
                for (int i = 0; i < idx.length; i++) {
                    double e = lg[i] - theta[u] - delta[idx[i]];
                    sq[idx[i]] += e * e;
                    totalSq += e * e;
                    total++;
                }
            }
            double[] residSd = new double[numCharts];
            for (int c = 0; c < numCharts; c++) {
                residSd[c] = n[c] >= 2 ? Math.sqrt(sq[c] / n[c]) : Double.NaN;
            }
            double pooled = total > 0 ? Math.sqrt(totalSq / total) : 0.0;
            return new Result(delta, n, residSd, pooled, theta);
        }
    }

    /** 単一 (A, B) ペアの累積和。compute() で回帰係数に変換する。 */
    static class Acc {
        long n = 0;
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0, sumYY = 0;

        void add(double x, double y) {
            n++;
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumXX += x * x;
            sumYY += y * y;
        }

        Reg compute() {
            if (n < 2) return null;
            double mx = sumX / n;
            double my = sumY / n;
            // (Σx² − n·mx²) と等価な (Σx² − mx·Σx)
            double sxx = sumXX - mx * sumX;
            double syy = sumYY - my * sumY;
            double sxy = sumXY - mx * sumY;
            if (sxx <= 0 || syy <= 0) return null;
            double slope = sxy / sxx;
            double intercept = my - slope * mx;
            double r = sxy / Math.sqrt(sxx * syy);

            Reg reg = new Reg();
            reg.n = (int) n;
            reg.slope = slope;
            reg.intercept = intercept;
            reg.r = r;
            reg.sdY = Math.sqrt(syy / n);
            return reg;
        }
    }
}
