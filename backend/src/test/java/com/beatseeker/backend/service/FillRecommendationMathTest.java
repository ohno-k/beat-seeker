package com.beatseeker.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 【テストの目的】 コスパ埋めレコメンドの期待値計算そのものが正しいか検証する。
 *
 * DB や回帰キャッシュに依存しない純粋な計算部分だけを対象にする:
 *  - 損益分岐スコア（pt(s) が baseline を超える最小スコア）の二分探索
 *  - 標準正規 CDF の近似精度
 *  - 期待獲得 pt が「確実に取れるケース」で決定論的な増分に一致すること
 *  - 期待獲得 pt が達成確率に対して単調に増えること
 *
 * 「埋め」の肝である「TOP100 の 100 位を押し出して初めて合計 BEAT-PT が増える」という
 * baseline の扱いも、損益分岐スコアの形で確認する。
 */
class FillRecommendationMathTest {

    /** ☆12.3 の譜面。weight = 178、maxScore = 2000（notes 1000）を想定する。 */
    private static final String RANK = "12.3";
    private static final int MAX_SCORE = 2000;

    private final BeatPtCalculator calc = new BeatPtCalculator();
    /** 計算メソッドしか触らないので、リポジトリ類は注入しない。 */
    private final FillRecommendationService service =
            new FillRecommendationService(null, null, null, calc);

    /** スコアレート(%) を logit へ。テスト側でも同じ変換を使って μ を組み立てる。 */
    private static double logitOfRate(double ratePct) {
        return PairRegressionService.scoreRateToLogit(ratePct / 100.0);
    }

    @Test
    void 標準正規CDFが既知の値と一致する() {
        assertThat(FillRecommendationService.normalCdf(0.0)).isCloseTo(0.5, within(1e-7));
        assertThat(FillRecommendationService.normalCdf(1.0)).isCloseTo(0.8413447, within(1e-6));
        assertThat(FillRecommendationService.normalCdf(-1.0)).isCloseTo(0.1586553, within(1e-6));
        assertThat(FillRecommendationService.normalCdf(1.96)).isCloseTo(0.9750021, within(1e-6));
        assertThat(FillRecommendationService.normalCdf(-2.5)).isCloseTo(0.0062097, within(1e-6));
    }

    @Test
    void 損益分岐スコアは百位ラインをちょうど超えるスコアになる() {
        // 100 位ラインが 150.0 pt のとき、未プレイ譜面はここを超えて初めて合計に効く。
        double baseline = 150.0;
        int breakEven = service.breakEvenScore(MAX_SCORE, RANK, baseline, 0);

        // 分岐点そのものは baseline を超え、1 点下は超えない。
        assertThat(calc.calculatePoints(breakEven * 100.0 / MAX_SCORE, RANK)).isGreaterThan(baseline);
        assertThat(calc.calculatePoints((breakEven - 1) * 100.0 / MAX_SCORE, RANK))
                .isLessThanOrEqualTo(baseline);
    }

    @Test
    void すでにラインを超えている譜面は次の一点が分岐点になる() {
        // TOP100 圏内の譜面は baseline = 自分の現在 pt なので、1 点でも伸びれば増分が出る。
        int currentScore = 1900;
        double currentPt = calc.calculatePoints(currentScore * 100.0 / MAX_SCORE, RANK);

        int breakEven = service.breakEvenScore(MAX_SCORE, RANK, currentPt, currentScore);

        assertThat(breakEven).isEqualTo(currentScore + 1);
    }

    @Test
    void 理論値でも届かない譜面は分岐点が理論値超えになる() {
        // 100 位ラインが ☆12.3 の理論値 pt より高いなら、この譜面は埋めても合計に効かない。
        double unreachable = calc.calculatePoints(100.0, RANK) + 10.0;

        int breakEven = service.breakEvenScore(MAX_SCORE, RANK, unreachable, 0);

        assertThat(breakEven).isGreaterThan(MAX_SCORE);
    }

    @Test
    void ばらつきが無ければ期待値は決定論的な増分に一致する() {
        // σ を極小にすると分布が μ の 1 点に潰れるので、期待値 = pt(予測スコア) − baseline。
        double targetRate = 92.0;
        double mu = logitOfRate(targetRate);
        double baseline = 150.0;

        double expected = service.expectedGain(mu, 1e-6, MAX_SCORE, MAX_SCORE, 0, RANK, baseline);
        double deterministic = calc.calculatePoints(targetRate, RANK) - baseline;

        assertThat(expected).isCloseTo(deterministic, within(0.01));
    }

    @Test
    void 届かない譜面の期待値はゼロになる() {
        // 予測が損益分岐点のはるか下なら、埋めても合計 BEAT-PT は動かない。
        double mu = logitOfRate(70.0);
        double baseline = calc.calculatePoints(95.0, RANK);

        double expected = service.expectedGain(mu, 0.1, MAX_SCORE, MAX_SCORE, 0, RANK, baseline);

        assertThat(expected).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void 能力が高いほど同じ譜面の期待値が大きくなる() {
        double baseline = calc.calculatePoints(88.0, RANK);
        double weak = service.expectedGain(logitOfRate(88.5), 0.15, MAX_SCORE, MAX_SCORE, 0, RANK, baseline);
        double strong = service.expectedGain(logitOfRate(93.0), 0.15, MAX_SCORE, MAX_SCORE, 0, RANK, baseline);

        assertThat(strong).isGreaterThan(weak);
        assertThat(weak).isGreaterThan(0.0);
    }

    @Test
    void 自己ベストを下回る引きは増分ゼロとして扱われる() {
        // 現在スコアより下の目が出ても自己ベストは更新されないので、期待値は非負のまま。
        // ばらつきを大きくしても「マイナスの期待値」にはならないことを確認する。
        int currentScore = 1800; // 90.0%
        double currentPt = calc.calculatePoints(90.0, RANK);
        double mu = logitOfRate(89.0); // 予測は現在より下

        double expected = service.expectedGain(mu, 0.4, MAX_SCORE, MAX_SCORE, currentScore, RANK, currentPt);

        assertThat(expected).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void コミュニティ最高スコアで期待値が頭打ちになる() {
        // 誰も 95% を超えていない譜面では、予測が上振れしても 95% ぶんの pt までしか期待できない。
        double mu = logitOfRate(99.0);
        int communityMax = (int) (MAX_SCORE * 0.95);

        double capped = service.expectedGain(mu, 0.2, MAX_SCORE, communityMax, 0, RANK, 0.0);
        double uncapped = service.expectedGain(mu, 0.2, MAX_SCORE, MAX_SCORE, 0, RANK, 0.0);

        assertThat(capped).isLessThan(uncapped);
        assertThat(capped).isLessThanOrEqualTo(calc.calculatePoints(95.0, RANK) + 1e-9);
    }

    // ── 並び替えと打ち切り ──────────────────────────────────────────────

    private static java.util.Map<String, Object> item(String title, double prob, double gain) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("title", title);
        m.put("achieveProbability", prob);
        m.put("expectedGain", gain);
        return m;
    }

    @Test
    void 達成率降順で並び同率なら期待値の大きい順になる() {
        java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>(java.util.List.of(
                item("low-prob-big-gain", 0.30, 9.0),
                item("high-prob-small", 0.95, 0.5),
                item("high-prob-big", 0.95, 2.0),
                // 0.951 と 0.949 は表示上どちらも 95% なので同率扱い → 期待値順
                item("high-prob-mid", 0.951, 1.0)));

        FillRecommendationService.sortByAchievability(items);

        assertThat(items).extracting(m -> (String) m.get("title"))
                .containsExactly("high-prob-big", "high-prob-mid", "high-prob-small", "low-prob-big-gain");
    }

    @Test
    void 差分を満たした時点で打ち切られる() {
        java.util.List<java.util.Map<String, Object>> items = java.util.List.of(
                item("a", 0.9, 3.0), item("b", 0.8, 3.0), item("c", 0.7, 3.0), item("d", 0.6, 3.0));

        // 3 + 3 = 6 < 7、3 + 3 + 3 = 9 ≧ 7 → 3 件目まで採用
        assertThat(FillRecommendationService.cutAtGap(items, 7.0, 300)).hasSize(3);
        // ちょうど届くケース
        assertThat(FillRecommendationService.cutAtGap(items, 6.0, 300)).hasSize(2);
    }

    @Test
    void 差分に届かなければ全件返し差分が無ければ上限で切る() {
        java.util.List<java.util.Map<String, Object>> items = java.util.List.of(
                item("a", 0.9, 1.0), item("b", 0.8, 1.0), item("c", 0.7, 1.0));

        // 合計 3 pt しか無いのに 100 pt 要る → 枯渇。手持ちは全部返す（フロントが「不足」を出す）。
        assertThat(FillRecommendationService.cutAtGap(items, 100.0, 300)).hasSize(3);
        // 最高位などで差分が 0 → 件数上限で切る
        assertThat(FillRecommendationService.cutAtGap(items, 0.0, 2)).hasSize(2);
    }

    // ── フォールバック予測（加法モデル） ─────────────────────────────────

    private static PairRegressionService.ChartEffect effect(double delta, int n, double sd) {
        PairRegressionService.ChartEffect ce = new PairRegressionService.ChartEffect();
        ce.delta = delta;
        ce.n = n;
        ce.residSd = sd;
        return ce;
    }

    @Test
    void 譜面効果があればBASEで予測し無ければ同ランク平均に落ちる() {
        java.util.Map<String, PairRegressionService.ChartEffect> effects = java.util.Map.of(
                "popular\0ANOTHER", effect(0.3, 40, 0.2),
                "rare\0ANOTHER", effect(0.9, 2, Double.NaN)); // 2 人しか居ない → δ を信用しない
        java.util.Map<String, String> ranks = java.util.Map.of(
                "popular_ANOTHER", RANK, "rare_ANOTHER", RANK, "brandnew_ANOTHER", RANK);
        java.util.Map<String, FillRecommendationService.RankEffect> rankEffects =
                FillRecommendationService.buildRankEffects(ranks, effects);
        double ability = 2.0;

        FillRecommendationService.Stat base = FillRecommendationService.fallbackStat(
                "popular\0ANOTHER", RANK, ability, 0.0, effects, rankEffects, 0.5);
        FillRecommendationService.Stat rank = FillRecommendationService.fallbackStat(
                "brandnew\0ANOTHER", RANK, ability, 0.0, effects, rankEffects, 0.5);
        FillRecommendationService.Stat rare = FillRecommendationService.fallbackStat(
                "rare\0ANOTHER", RANK, ability, 0.0, effects, rankEffects, 0.5);

        assertThat(base.accuracy).isEqualTo(FillRecommendationService.ACC_BASE);
        assertThat(base.mu).isCloseTo(2.3, within(1e-9));
        assertThat(base.support).isEqualTo(40);

        // 同ランク平均は n ≧ 5 の popular だけから作られる（rare は除外）→ mean δ = 0.3
        assertThat(rank.accuracy).isEqualTo(FillRecommendationService.ACC_RANK);
        assertThat(rank.mu).isCloseTo(2.3, within(1e-9));
        assertThat(rare.accuracy).isEqualTo(FillRecommendationService.ACC_RANK);

        // 情報が少ない段ほど σ が大きい（達成率順で自然に後ろへ回るための前提）
        assertThat(rank.sigma).isGreaterThan(base.sigma);
    }

    @Test
    void 実力が推定できなければフォールバック予測は出さない() {
        java.util.Map<String, PairRegressionService.ChartEffect> effects = java.util.Map.of(
                "popular\0ANOTHER", effect(0.3, 40, 0.2));
        java.util.Map<String, FillRecommendationService.RankEffect> rankEffects =
                FillRecommendationService.buildRankEffects(java.util.Map.of("popular_ANOTHER", RANK), effects);

        assertThat(FillRecommendationService.fallbackStat(
                "popular\0ANOTHER", RANK, null, 0.0, effects, rankEffects, 0.5)).isNull();
        // 難易度表に無いランクにも落とせない
        assertThat(FillRecommendationService.fallbackStat(
                "x\0ANOTHER", "99.9", 2.0, 0.0, effects, rankEffects, 0.5)).isNull();
    }

    @Test
    void 達成確率は損益分岐点が上がるほど下がる() {
        double mu = logitOfRate(90.0);
        double sigma = 0.2;

        double easy = service.tailProbability((int) (MAX_SCORE * 0.85), MAX_SCORE, mu, sigma);
        double even = service.tailProbability((int) (MAX_SCORE * 0.90), MAX_SCORE, mu, sigma);
        double hard = service.tailProbability((int) (MAX_SCORE * 0.95), MAX_SCORE, mu, sigma);

        assertThat(easy).isGreaterThan(even);
        assertThat(even).isGreaterThan(hard);
        // 予測中央値ちょうどの達成確率は 50% になる。
        assertThat(even).isCloseTo(0.5, within(0.02));
    }
}
