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
 * 【Service の役割】 「次に何を埋めれば BEAT-PT が一番伸びるか」を期待値で算出する
 * コスパ埋めレコメンド。
 *
 * <h3>何が既存のランクアップアドバイスと違うのか</h3>
 * 既存のアドバイス（フロント {@code RankUpAdvice.vue}）は
 *  - 「自分がすでに A 以上で出している譜面」しか候補にできず（＝埋め提案ができない）
 *  - 伸びしろ予測を「確実に出せる上限」として決定論的に扱っていた
 * ため、「未プレイ譜面を 1 曲埋めると TOP100 の 100 位を押し出して一気に伸びる」という
 * IIDX プレイヤーが実際に一番知りたいケースを提案できなかった。
 *
 * 本サービスは候補を <b>全譜面（未プレイ含む）</b> に広げ、各譜面の伸び幅を
 * 「取れるか分からないもの」として確率分布のまま扱い、期待値で順位づけする。
 *
 * <h3>計算式</h3>
 * 依頼された定式化は
 * <pre>  ∂BEAT-PT/∂スコア × P(達成 | 推定能力)  </pre>
 * だが、これはスコア軸で積分すると期待獲得 pt そのものになる（部分積分）。
 * <pre>
 *   E[ΔBEAT-PT] = ∫_{s*}^{∞} (∂BEAT-PT/∂s) · P(S ≥ s) ds
 *               = E[ max(0, pt(S) − baseline) ]
 * </pre>
 * ここで S は「推定能力から見た、その譜面で最終的に出せるスコア」の確率変数、
 * s* は損益分岐スコア（後述）。本実装は右辺（期待値そのもの）を数値積分で求める。
 * 微分の離散近似を経由しないので、AA / AAA / MAX- のボーナス段差
 * （BEAT-PT が不連続に跳ねる点）も取りこぼさない。
 *
 * <h3>baseline（＝何と比べた増分か）</h3>
 * BEAT-PT は上位 100 譜面の合計なので、増分は TOP100 の出入りで決まる。
 *  - すでに TOP100 圏内の譜面 … baseline = その譜面の現在 pt（純粋な上積み）
 *  - TOP100 圏外／未プレイの譜面 … baseline = 100 位の pt（そこを超えて初めて合計に効く）
 * 損益分岐スコア s* は pt(s*) = baseline となるスコアで、二分探索で求める。
 * P(S ≥ s*) が「この譜面を触る価値がそもそもあるか」の確率 = achieveProbability。
 *
 * <h3>能力推定（S の分布）</h3>
 * {@link PairRegressionService} の譜面ペア回帰（logit 空間）をそのまま流用する。
 * 参照譜面 A ごとの予測 ŷ_A = slope·logit(A) + intercept を重み w(r) で加重平均して μ とし、
 * 分散は次の 2 成分の和とする。
 * <pre>
 *   σ² = σ_resid²                       … その譜面固有の相性（参照を増やしても消えない）
 *      + σ_between² · Σw²/(Σw)²         … 参照譜面どうしの食い違い（平均の標準誤差）
 * </pre>
 * σ_resid はペアごとの残差 sd（= sdY·√(1−r²)）の加重平均。
 * S は logit 空間で正規分布とみなす（スコアレートは 0〜1 に飽和するため、
 * 生スコア空間の正規分布より実態に合う）。
 *
 * <h3>コスト</h3>
 * ペア回帰キャッシュ（全体で 1 本のバッチ相当）は {@link PairRegressionService} が既に持っており、
 * ユーザーごとの計算はキャッシュ参照のみ。曲マスタ／難易度表は {@link #MASTER_CACHE_TTL_MS}
 * の間だけメモリに保持して使い回す。
 */
@Service
public class FillRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(FillRecommendationService.class);

    /** BEAT-PT 合計の対象となる上位譜面数。フロント {@code beatTier.ts} の TOP_CHART_LIMIT と同値。 */
    private static final int TOP_CHART_LIMIT = 100;

    /**
     * logit 空間の予測標準偏差の下限。
     * 参照譜面が偶然きれいに揃うと σ がほぼ 0 になり「確率 100%」と言い切ってしまうため、
     * スコアレート 95% 付近で ±0.2% 程度に相当する 0.04 を最低値として残す。
     */
    private static final double MIN_SIGMA_LOGIT = 0.04;
    /**
     * logit 空間の予測標準偏差の上限。
     * 参照が乏しい譜面で σ が発散すると「何でも当たるかもしれない」判定になり、
     * 期待値が実力とかけ離れた高難度譜面に張り付くのを防ぐ。
     */
    private static final double MAX_SIGMA_LOGIT = 1.2;

    /** 数値積分のグリッド幅（標準正規の ±SIGMA_SPAN σ を刻む）。 */
    private static final double SIGMA_SPAN = 3.5;
    /** 数値積分のグリッド分割数（奇数にして中央 = μ を必ず含める）。 */
    private static final int QUAD_NODES = 41;

    /** 期待獲得 pt がこの値未満の候補は返さない（UI 上 "+0.0 pt" になるため）。 */
    private static final double MIN_EXPECTED_GAIN = 0.05;

    /** 返却件数の上限。フロントは上位から貪欲に選ぶだけなので、これで十分足りる。 */
    private static final int MAX_ITEMS = 200;

    /** 曲マスタ・難易度表のメモリ保持時間（ms）。マスタは日次更新なので 10 分で十分。 */
    private static final long MASTER_CACHE_TTL_MS = 10 * 60 * 1000L;

    /** BEAT-PT のボーナス段差。UI に「AA 狙い」「AAA 狙い」と出すための目標候補でもある。 */
    private static final double[] BORDER_RATES = {77.77, 88.88, 94.44};
    /** {@link #BORDER_RATES} と同じ並びのラベル。 */
    private static final String[] BORDER_LABELS = {"AA", "AAA", "MAX-"};

    private final PairRegressionService pairRegressionService;
    private final ScoreRecalculationService scoreRecalculationService;
    private final ScoreRepository scoreRepository;
    private final BeatPtCalculator beatPtCalculator;

    /** 曲マスタ（title_difficultyCode → maxScore）のキャッシュ。 */
    private volatile Map<String, Integer> cachedMaxScores = Collections.emptyMap();
    /** 難易度表（title_diffName → 非公式ランク文字列）のキャッシュ。 */
    private volatile Map<String, String> cachedInformalRanks = Collections.emptyMap();
    /** 上記 2 つを読み込んだ時刻（epoch ms）。0 なら未読み込み。 */
    private volatile long masterLoadedAt = 0L;

    public FillRecommendationService(PairRegressionService pairRegressionService,
                                     ScoreRecalculationService scoreRecalculationService,
                                     ScoreRepository scoreRepository,
                                     BeatPtCalculator beatPtCalculator) {
        this.pairRegressionService = pairRegressionService;
        this.scoreRecalculationService = scoreRecalculationService;
        this.scoreRepository = scoreRepository;
        this.beatPtCalculator = beatPtCalculator;
    }

    /**
     * 【メソッドの役割】 指定ユーザーのコスパ埋めレコメンドを期待値降順で返す。
     *
     * @param userId 対象ユーザー ID
     * @return {@code {top100Threshold, totalBeatPt, scoredChartCount, referenceChartCount, items:[...]}}。
     *         items の各要素は
     *         {@code {title, difficultyName, informalRank, difficultyLevel, unplayed, currentScore,
     *          currentRate, currentBeatPt, maxScore, predictedScore, predictedRate, sigmaRate,
     *          breakEvenScore, achieveProbability, targetScore, targetRate, targetLabel,
     *          targetProbability, targetGain, expectedGain, supportCount, accuracy}}
     */
    public Map<String, Object> computeFillRecommendation(Long userId) {
        long t0 = System.currentTimeMillis();
        pairRegressionService.ensureBuilt();
        loadMastersIfStale();

        Map<String, Integer> notesByKey = pairRegressionService.getNotesByKey();
        Map<String, Integer> communityMaxByKey = pairRegressionService.getCommunityMaxByKey();
        Map<String, String> informalRanks = cachedInformalRanks;
        Map<String, Integer> maxScores = cachedMaxScores;

        // 1) ユーザーの現状 pt を全譜面ぶん求め、TOP100 の 100 位 pt（＝押し出しライン）を得る。
        Map<String, Integer> myScores = new HashMap<>();   // key(title\0diff) → score
        Map<String, Double> myPoints = new HashMap<>();    // key → 現在の BEAT-PT
        for (Map<String, Object> row : scoreRepository.findUserAnotherLeggScores(userId)) {
            String title = (String) row.get("title");
            String diff = (String) row.get("difficultyName");
            int score = ((Number) row.get("score")).intValue();
            String key = title + "\0" + diff;
            myScores.merge(key, score, Math::max);

            Integer maxScore = maxScores.get(title + "_" + difficultyCode(diff));
            if (maxScore == null || maxScore <= 0) continue;
            String informalRank = informalRanks.get(title + "_" + diff);
            double pt = beatPtCalculator.calculatePoints(score * 100.0 / maxScore, informalRank);
            if (pt <= 0) continue;
            myPoints.merge(key, pt, Math::max);
        }
        List<Double> ptList = new ArrayList<>(myPoints.values());
        ptList.sort(Collections.reverseOrder());
        double totalBeatPt = 0;
        for (int i = 0; i < Math.min(TOP_CHART_LIMIT, ptList.size()); i++) totalBeatPt += ptList.get(i);
        // TOP100 が埋まっていないうちは押し出しライン 0（＝どの譜面でも素の pt がまるごと増分）。
        double threshold = ptList.size() >= TOP_CHART_LIMIT ? ptList.get(TOP_CHART_LIMIT - 1) : 0.0;

        // 2) 能力推定の参照譜面（自分が A 以上で出している譜面）を集める。
        Map<String, Integer> refCharts = new HashMap<>();
        for (Map.Entry<String, Integer> e : myScores.entrySet()) {
            Integer notes = notesByKey.get(e.getKey());
            if (notes == null || notes <= 0) continue;
            if (e.getValue() < notes * 2.0 * PairRegressionService.A_GRADE_RATE) continue;
            refCharts.put(e.getKey(), notes);
        }
        if (refCharts.isEmpty()) {
            return emptyResult(threshold, totalBeatPt, myPoints.size(), 0);
        }

        // 3) 参照譜面 A 側から回帰キャッシュを引いて、候補譜面 B ごとに予測を積み上げる。
        //    B 側から全譜面ぶん引くと「候補数 × 参照数」の空振りが出るので、A 側から回す。
        Map<String, Pred> preds = new HashMap<>();
        for (Map.Entry<String, Integer> aEntry : refCharts.entrySet()) {
            String chartA = aEntry.getKey();
            Map<String, PairRegressionService.Reg> bMap = pairRegressionService.getRegressionsFrom(chartA);
            if (bMap == null) continue;
            double logitA = PairRegressionService.scoreToLogit(myScores.get(chartA), aEntry.getValue());

            for (Map.Entry<String, PairRegressionService.Reg> bEntry : bMap.entrySet()) {
                String chartB = bEntry.getKey();
                if (chartB.equals(chartA)) continue;
                PairRegressionService.Reg reg = bEntry.getValue();
                double absR = Math.abs(reg.r);
                if (absR < PairRegressionService.FALLBACK_R) continue;

                double predLogit = reg.slope * logitA + reg.intercept;
                double w = PairRegressionService.computeWeight(reg.r);
                if (w <= 0) continue;
                // 残差 sd（logit 空間）: 回帰で説明しきれないぶん。
                double resid = reg.sdY * Math.sqrt(Math.max(0.0, 1.0 - reg.r * reg.r));

                Pred p = preds.computeIfAbsent(chartB, k -> new Pred());
                p.addLow(predLogit, w, resid);
                if (absR >= PairRegressionService.PRIMARY_R) p.addHigh(predLogit, w, resid);
            }
        }

        // 4) 候補ごとに期待獲得 pt を計算する。
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, Pred> e : preds.entrySet()) {
            String key = e.getKey();
            Pred pred = e.getValue();

            Integer notes = notesByKey.get(key);
            if (notes == null || notes <= 0) continue;
            int maxScore = notes * 2;

            String[] parts = key.split("\0", 2);
            if (parts.length < 2) continue;
            String title = parts[0];
            String diffName = parts[1];

            String informalRank = informalRanks.get(title + "_" + diffName);
            // 難易度表に載っていない譜面はそもそも BEAT-PT が付かないので候補にならない。
            if (informalRank == null || beatPtCalculator.getWeight(informalRank) == 0) continue;

            Stat stat = pred.resolve();
            if (stat == null) continue; // サポート不足

            int currentScore = myScores.getOrDefault(key, 0);
            boolean unplayed = !myScores.containsKey(key);
            double currentPt = myPoints.getOrDefault(key, 0.0);
            // TOP100 圏内かどうかは「現在 pt が 100 位ラインを超えているか」で判定する。
            boolean inTop100 = currentPt > 0 && currentPt >= threshold;
            double baseline = inTop100 ? currentPt : threshold;

            // 予測スコアの上限はコミュニティ実測最高（誰も出していないスコアは提示しない）。
            Integer communityMax = communityMaxByKey.get(key);
            int scoreCap = (communityMax != null && communityMax > 0 && communityMax < maxScore)
                    ? communityMax : maxScore;
            if (currentScore >= scoreCap) continue; // これ以上伸ばす余地がない

            // 損益分岐スコア s*: ここを超えて初めて合計 BEAT-PT が増える。
            int breakEven = breakEvenScore(maxScore, informalRank, baseline, currentScore);
            if (breakEven > scoreCap) continue; // 到達可能域では合計に効かない

            double achieveProb = tailProbability(breakEven, maxScore, stat.mu, stat.sigma);

            // 期待獲得 pt = E[max(0, pt(S) − baseline)]。S は logit 空間の正規分布。
            double expectedGain = expectedGain(stat.mu, stat.sigma, maxScore, scoreCap,
                    currentScore, informalRank, baseline);
            if (expectedGain < MIN_EXPECTED_GAIN) continue;

            double predictedScore = Math.min(scoreCap,
                    PairRegressionService.logitToScoreRate(stat.mu) * maxScore);
            // 提示用の目標: 予測の中央値までに届くボーダーがあればそれ、無ければ中央値そのもの。
            Target target = pickTarget(stat, maxScore, scoreCap, currentScore, informalRank,
                    baseline, predictedScore);

            Map<String, Object> item = new HashMap<>();
            item.put("title", title);
            item.put("difficultyName", diffName);
            item.put("informalRank", informalRank);
            item.put("difficultyLevel", difficultyLevelOf(informalRank));
            item.put("unplayed", unplayed);
            item.put("currentScore", currentScore);
            item.put("currentRate", currentScore * 100.0 / maxScore);
            item.put("currentBeatPt", currentPt);
            item.put("inTop100", inTop100);
            item.put("maxScore", maxScore);
            item.put("predictedScore", predictedScore);
            item.put("predictedRate", predictedScore * 100.0 / maxScore);
            item.put("sigmaRate", sigmaAsRatePct(stat.mu, stat.sigma));
            item.put("breakEvenScore", breakEven);
            item.put("achieveProbability", achieveProb);
            item.put("targetScore", target.score);
            item.put("targetRate", target.score * 100.0 / maxScore);
            item.put("targetLabel", target.label);
            item.put("targetProbability", target.probability);
            item.put("targetGain", target.gain);
            item.put("expectedGain", expectedGain);
            item.put("supportCount", stat.support);
            item.put("accuracy", stat.accuracy);
            items.add(item);
        }

        // 5) 期待値降順。「次に何を埋めると一番伸びるか」の答えがそのまま先頭に来る。
        items.sort((a, b) -> Double.compare(
                ((Number) b.get("expectedGain")).doubleValue(),
                ((Number) a.get("expectedGain")).doubleValue()));
        if (items.size() > MAX_ITEMS) items = new ArrayList<>(items.subList(0, MAX_ITEMS));

        Map<String, Object> result = new HashMap<>();
        result.put("top100Threshold", threshold);
        result.put("totalBeatPt", totalBeatPt);
        result.put("scoredChartCount", myPoints.size());
        result.put("referenceChartCount", refCharts.size());
        result.put("items", items);
        log.debug("computeFillRecommendation(user={}) -> {} items in {} ms",
                userId, items.size(), System.currentTimeMillis() - t0);
        return result;
    }

    // ── 期待値まわりの計算 ────────────────────────────────────────────────

    /**
     * 【メソッドの役割】 期待獲得 BEAT-PT を数値積分で求める。
     *
     * S を logit 空間の正規分布 N(μ, σ²) とみなし、
     * {@code E[max(0, pt(clamp(S)) − baseline)]} を ±{@link #SIGMA_SPAN}σ の
     * 等間隔グリッドで台形近似する（重みは標準正規密度、総和で正規化）。
     *
     * BEAT-PT は AA / AAA / MAX- で段差を持つ不連続関数なので、
     * 「∂BEAT-PT/∂スコアを 1 点で微分して確率を掛ける」形ではボーナス段差を
     * 取りこぼす。分布を刻んで pt をそのまま評価するこの形なら段差も拾える。
     *
     * @param mu           logit 空間の予測平均
     * @param sigma        logit 空間の予測標準偏差
     * @param maxScore     理論値スコア（notes × 2）
     * @param scoreCap     現実的な上限（コミュニティ実測最高）
     * @param currentScore 現在スコア（これを下回る結果は「更新なし」＝増分 0）
     * @param informalRank 非公式難易度（weight 決定用）
     * @param baseline     この pt を超えたぶんだけが合計 BEAT-PT の増分になる
     * @return 期待獲得 pt（≥ 0）
     */
    double expectedGain(double mu, double sigma, int maxScore, int scoreCap,
                        int currentScore, String informalRank, double baseline) {
        double sum = 0;
        double weightSum = 0;
        double step = 2.0 * SIGMA_SPAN / (QUAD_NODES - 1);
        for (int i = 0; i < QUAD_NODES; i++) {
            double z = -SIGMA_SPAN + i * step;
            double density = Math.exp(-0.5 * z * z);
            double rate = PairRegressionService.logitToScoreRate(mu + sigma * z);
            double score = rate * maxScore;
            // 現在スコアを下回る「引き」は自己ベストを更新しないので増分 0。上はコミュニティ最高で頭打ち。
            score = Math.max(currentScore, Math.min(scoreCap, score));
            double pt = beatPtCalculator.calculatePoints(score * 100.0 / maxScore, informalRank);
            sum += density * Math.max(0.0, pt - baseline);
            weightSum += density;
        }
        return weightSum > 0 ? sum / weightSum : 0.0;
    }

    /**
     * 【メソッドの役割】 損益分岐スコア（pt(s) > baseline となる最小スコア）を二分探索で求める。
     *
     * BEAT-PT はスコアについて単調増加なので二分探索できる。
     * 既に baseline を超えている譜面（TOP100 圏内）では「現在スコア + 1」が返る。
     *
     * @return 損益分岐スコア。理論値でも baseline に届かない場合は maxScore + 1
     */
    int breakEvenScore(int maxScore, String informalRank, double baseline, int currentScore) {
        int lo = Math.max(0, currentScore);
        int hi = maxScore;
        if (beatPtCalculator.calculatePoints(hi * 100.0 / maxScore, informalRank) <= baseline) {
            return maxScore + 1;
        }
        // pt(lo) > baseline なら「あと 1 点でも増える」= lo + 1 が分岐点。
        if (beatPtCalculator.calculatePoints(lo * 100.0 / maxScore, informalRank) > baseline) {
            return Math.min(maxScore, lo + 1);
        }
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (beatPtCalculator.calculatePoints(mid * 100.0 / maxScore, informalRank) > baseline) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /**
     * 【メソッドの役割】 P(S ≥ score) を返す。logit 空間の正規分布の上側確率。
     */
    double tailProbability(int score, int maxScore, double mu, double sigma) {
        if (score <= 0) return 1.0;
        if (score > maxScore) return 0.0;
        double z = (PairRegressionService.scoreRateToLogit(score / (double) maxScore) - mu) / sigma;
        return 1.0 - normalCdf(z);
    }

    /** 提示用の目標スコアとその達成確率・獲得 pt。 */
    private static class Target {
        int score;
        String label = "";
        double probability;
        double gain;
    }

    /**
     * 【メソッドの役割】 UI に出す「狙い目」を決める。
     *
     * 予測中央値までに届く AA / AAA / MAX- のボーダーがあれば、その中で一番上のものを目標にする
     * （ボーナス段差を跨ぐので体感の伸びが大きく、達成の手応えも分かりやすい）。
     * 届くボーダーが無ければ予測中央値そのものを目標にする。
     */
    private Target pickTarget(Stat stat, int maxScore, int scoreCap, int currentScore,
                              String informalRank, double baseline, double predictedScore) {
        Target best = new Target();
        best.score = (int) Math.round(Math.max(currentScore + 1, Math.min(scoreCap, predictedScore)));

        double currentRate = currentScore * 100.0 / maxScore;
        for (int i = BORDER_RATES.length - 1; i >= 0; i--) {
            double borderRate = BORDER_RATES[i];
            if (currentRate > borderRate) continue; // すでに超えている
            int need = (int) Math.ceil(maxScore * borderRate / 100.0) + 1; // 段差は「超えたら」付くので +1
            if (need > scoreCap) continue;
            if (need > predictedScore) continue; // 中央値で届かないボーダーは「狙い目」と呼ばない
            best.score = need;
            best.label = BORDER_LABELS[i];
            break;
        }

        best.probability = tailProbability(best.score, maxScore, stat.mu, stat.sigma);
        double pt = beatPtCalculator.calculatePoints(best.score * 100.0 / maxScore, informalRank);
        best.gain = Math.max(0.0, pt - baseline);
        return best;
    }

    /**
     * 【メソッドの役割】 logit 空間の σ を「スコアレート % の幅」に直す（UI 表示用）。
     * μ 近傍の傾き dp/dlogit = p(1−p) を使った一次近似。
     */
    private static double sigmaAsRatePct(double mu, double sigma) {
        double p = PairRegressionService.logitToScoreRate(mu);
        return p * (1 - p) * sigma * 100.0;
    }

    /**
     * 【メソッドの役割】 標準正規分布の累積分布関数。
     * Abramowitz &amp; Stegun 26.2.17（絶対誤差 &lt; 7.5e-8）。外部依存を増やさないための自前実装。
     */
    static double normalCdf(double z) {
        if (z < -8) return 0.0;
        if (z > 8) return 1.0;
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(z));
        double poly = t * (0.319381530
                + t * (-0.356563782
                + t * (1.781477937
                + t * (-1.821255978
                + t * 1.330274429))));
        double phi = Math.exp(-0.5 * z * z) / Math.sqrt(2 * Math.PI);
        double upper = phi * poly; // P(Z > |z|)
        return z >= 0 ? 1.0 - upper : upper;
    }

    // ── 予測の積み上げ ───────────────────────────────────────────────────

    /** 確定した予測（μ, σ, サポート数, 精度ラベル）。 */
    private static class Stat {
        double mu;
        double sigma;
        int support;
        String accuracy;
    }

    /**
     * 参照譜面ごとの予測を HIGH（|r| ≧ PRIMARY_R）と LOW（|r| ≧ FALLBACK_R）の
     * 2 段で同時に積み上げる加重統計。{@link PairRegressionService#computeGrowthPotential}
     * と同じ 2 段構えにして、両機能の「予測が出る／出ない」の境界を揃えている。
     */
    private static class Acc {
        double sumW, sumWP, sumWPP, sumWResid, sumWW;
        int support;

        void add(double pred, double w, double resid) {
            sumW += w;
            sumWP += w * pred;
            sumWPP += w * pred * pred;
            sumWResid += w * resid;
            sumWW += w * w;
            support++;
        }

        /** μ と σ を確定する。サポート不足なら null。 */
        Stat resolve(String accuracy) {
            if (support < PairRegressionService.SUPPORT_MIN || sumW <= 0) return null;
            double mu = sumWP / sumW;
            // 参照譜面どうしの食い違い（加重分散）。
            double between = Math.max(0.0, sumWPP / sumW - mu * mu);
            // 加重平均の実効サンプル数の逆数。参照が多く均等なほど平均は安定する。
            double meanFactor = sumWW / (sumW * sumW);
            // その譜面固有の相性ぶれ。参照を増やしても消えないので、そのまま残す。
            double resid = sumWResid / sumW;

            double sigma = Math.sqrt(resid * resid + between * meanFactor);
            sigma = Math.max(MIN_SIGMA_LOGIT, Math.min(MAX_SIGMA_LOGIT, sigma));

            Stat s = new Stat();
            s.mu = mu;
            s.sigma = sigma;
            s.support = support;
            s.accuracy = accuracy;
            return s;
        }
    }

    /** 1 譜面ぶんの HIGH / LOW 累積。HIGH を優先し、足りなければ LOW にフォールバックする。 */
    private static class Pred {
        final Acc high = new Acc();
        final Acc low = new Acc();

        void addHigh(double pred, double w, double resid) { high.add(pred, w, resid); }
        void addLow(double pred, double w, double resid) { low.add(pred, w, resid); }

        Stat resolve() {
            Stat s = high.resolve("HIGH");
            return s != null ? s : low.resolve("LOW");
        }
    }

    // ── マスタ読み込み ───────────────────────────────────────────────────

    /**
     * 【メソッドの役割】 曲マスタ・難易度表を必要に応じて読み直す。
     * どちらも 6,000 件規模の全件読みなので、リクエストごとに引かず
     * {@link #MASTER_CACHE_TTL_MS} の間は使い回す。
     */
    private void loadMastersIfStale() {
        long now = System.currentTimeMillis();
        if (masterLoadedAt > 0 && now - masterLoadedAt < MASTER_CACHE_TTL_MS) return;
        synchronized (this) {
            if (masterLoadedAt > 0 && System.currentTimeMillis() - masterLoadedAt < MASTER_CACHE_TTL_MS) return;
            cachedMaxScores = scoreRecalculationService.loadSongMaxScores();
            cachedInformalRanks = scoreRecalculationService.loadInformalRanks();
            masterLoadedAt = System.currentTimeMillis();
        }
    }

    /** 難易度名 → song_definitions の difficulty コード。ANOTHER/LEGGENDARIA 以外は null。 */
    private static String difficultyCode(String difficultyName) {
        if ("ANOTHER".equals(difficultyName)) return "4";
        if ("LEGGENDARIA".equals(difficultyName)) return "10";
        return null;
    }

    /**
     * 【メソッドの役割】 非公式ランク文字列（"12.3" など）から公式レベル（11 / 12）を取り出す。
     * 表示のフィルタ用途なので、パースできなければ 0 を返す。
     */
    private static int difficultyLevelOf(String informalRank) {
        if (informalRank == null) return 0;
        try {
            return (int) Math.floor(Double.parseDouble(informalRank.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 参照譜面が無い等で提案できないときの空レスポンス。 */
    private static Map<String, Object> emptyResult(double threshold, double totalBeatPt,
                                                   int scoredChartCount, int referenceChartCount) {
        Map<String, Object> result = new HashMap<>();
        result.put("top100Threshold", threshold);
        result.put("totalBeatPt", totalBeatPt);
        result.put("scoredChartCount", scoredChartCount);
        result.put("referenceChartCount", referenceChartCount);
        result.put("items", new ArrayList<>());
        return result;
    }
}
