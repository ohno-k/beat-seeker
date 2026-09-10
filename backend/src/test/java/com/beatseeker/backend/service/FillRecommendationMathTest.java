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
 *  - 目標が「達成率 × 達成時の増分」の最大点として選ばれ、期待獲得 pt がその積に一致すること
 *  - 予測どおりに出せている譜面で「あと 1 点」を目標にしないこと（表示の矛盾の再発防止）
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
            new FillRecommendationService(null, null, null, calc, null, null);

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

    // ── 目標の選び方と期待値 ─────────────────────────────────────────────

    private static FillRecommendationService.Stat stat(double mu, double sigma) {
        FillRecommendationService.Stat st = new FillRecommendationService.Stat();
        st.mu = mu;
        st.sigma = sigma;
        return st;
    }

    /** 候補と同じ式 P(S ≥ s) × (pt(s) − baseline) をテスト側で独立に評価する。 */
    private double valueAt(int score, FillRecommendationService.Stat st, double baseline) {
        return service.tailProbability(score, MAX_SCORE, st.mu, st.sigma)
                * service.goalGain(score, MAX_SCORE, RANK, baseline);
    }

    @Test
    void 期待値は目標の達成率と達成時増分の積でその最大点が目標になる() {
        // 未プレイ譜面。100 位ラインが 150 pt、予測は 90% ± 少し。
        double baseline = 150.0;
        FillRecommendationService.Stat st = stat(logitOfRate(90.0), 0.2);
        int breakEven = service.breakEvenScore(MAX_SCORE, RANK, baseline, 0);

        FillRecommendationService.Target t =
                service.pickTarget(st, MAX_SCORE, MAX_SCORE, 0, RANK, baseline, breakEven);

        // 表示する 3 つの数字（達成率・達成時増分・期待値）が同じ目標を指す。
        assertThat(t.expectedGain).isCloseTo(t.probability * t.gain, within(1e-9));
        assertThat(t.probability).isCloseTo(service.tailProbability(t.score, MAX_SCORE, st.mu, st.sigma), within(1e-12));
        assertThat(t.gain).isCloseTo(service.goalGain(t.score, MAX_SCORE, RANK, baseline), within(1e-12));
        // 目標は損益分岐点以上・上限以下。
        assertThat(t.score).isBetween(breakEven, MAX_SCORE);
        // 損益分岐点・予測中央値・上限のどこを狙うより見返りが大きい（最大点を選んでいる）。
        assertThat(t.expectedGain).isGreaterThanOrEqualTo(valueAt(breakEven, st, baseline));
        assertThat(t.expectedGain).isGreaterThanOrEqualTo(valueAt((int) (MAX_SCORE * 0.90), st, baseline));
        assertThat(t.expectedGain).isGreaterThanOrEqualTo(valueAt(MAX_SCORE, st, baseline));
    }

    @Test
    void 予測どおりに出せている譜面では一点先を目標にせず期待値も控えめになる() {
        // 現在スコアが予測中央値ちょうど（TOP100 圏内なので baseline = 現在 pt）。
        // 旧実装はここで「目標 = 現在 + 1 点」「期待値 = 上側の裾の積分」となり、
        // 「あと 1 点で +0.7 pt」のような矛盾した表示になっていた。
        int current = (int) (MAX_SCORE * 0.96);
        double baseline = calc.calculatePoints(current * 100.0 / MAX_SCORE, RANK);
        FillRecommendationService.Stat st = stat(logitOfRate(96.0), 0.3);
        int breakEven = service.breakEvenScore(MAX_SCORE, RANK, baseline, current);
        assertThat(breakEven).isEqualTo(current + 1);

        FillRecommendationService.Target t =
                service.pickTarget(st, MAX_SCORE, MAX_SCORE, current, RANK, baseline, breakEven);

        // 1 点先は増分がほぼ 0 なので目標にならず、伸びる可能性に見合った先の点が目標になる。
        assertThat(t.score).isGreaterThan(current + 1);
        // 中央値より上を狙うので達成率は 50% 未満、期待値は「達成率 × 増分」の範囲に収まる。
        assertThat(t.probability).isLessThan(0.5);
        assertThat(t.expectedGain).isCloseTo(t.probability * t.gain, within(1e-9));
        assertThat(t.expectedGain).isLessThan(t.gain);
        // 分布全体の期待値 E[max(0, pt(S) − baseline)]（旧定義）より小さい。
        assertThat(t.expectedGain).isLessThan(fullExpectedGain(st, current, baseline));
    }

    /** 旧定義の期待値（分布全体の積分）。新定義がこれを超えないことの確認用。 */
    private double fullExpectedGain(FillRecommendationService.Stat st, int current, double baseline) {
        double sum = 0, wsum = 0;
        for (int i = 0; i <= 400; i++) {
            double z = -4 + i * 0.02;
            double w = Math.exp(-0.5 * z * z);
            double score = Math.max(current, PairRegressionService.logitToScoreRate(st.mu + st.sigma * z) * MAX_SCORE);
            sum += w * Math.max(0.0, calc.calculatePoints(score * 100.0 / MAX_SCORE, RANK) - baseline);
            wsum += w;
        }
        return sum / wsum;
    }

    @Test
    void ボーダーが射程内ならボーダーちょうどが目標になりラベルが付く() {
        // 現在 88.5%（AAA 未満）、予測中央値 89.2% でわずかに AAA を超える。
        // AAA の段差（weight × 1%）を跨ぐので、ボーダー直上が「達成率 × 増分」の最大点になる。
        int current = (int) (MAX_SCORE * 0.885);
        double baseline = calc.calculatePoints(current * 100.0 / MAX_SCORE, RANK);
        FillRecommendationService.Stat st = stat(logitOfRate(89.2), 0.06);
        int breakEven = service.breakEvenScore(MAX_SCORE, RANK, baseline, current);

        FillRecommendationService.Target t =
                service.pickTarget(st, MAX_SCORE, MAX_SCORE, current, RANK, baseline, breakEven);

        assertThat(t.label).isEqualTo("AAA");
        assertThat(t.score).isEqualTo(FillRecommendationService.borderScore(MAX_SCORE, 88.88));
        assertThat(t.score * 100.0 / MAX_SCORE).isGreaterThan(88.88);
        // 段差ぶんは必ず増分に乗る。
        assertThat(t.gain).isGreaterThan(calc.getWeight(RANK) * 0.01);
    }

    @Test
    void 届かない譜面は目標が立たないか期待値がほぼゼロになる() {
        // 損益分岐点がコミュニティ最高を超えている → 狙える点が無い。
        assertThat(service.pickTarget(stat(logitOfRate(90.0), 0.2), MAX_SCORE, (int) (MAX_SCORE * 0.9),
                0, RANK, 150.0, (int) (MAX_SCORE * 0.95))).isNull();

        // 予測が損益分岐点のはるか下なら、達成率がほぼ 0 なので期待値もほぼ 0。
        double baseline = calc.calculatePoints(95.0, RANK);
        int breakEven = service.breakEvenScore(MAX_SCORE, RANK, baseline, 0);
        FillRecommendationService.Target t = service.pickTarget(
                stat(logitOfRate(70.0), 0.1), MAX_SCORE, MAX_SCORE, 0, RANK, baseline, breakEven);
        assertThat(t == null || t.expectedGain < 1e-6).isTrue();
    }

    @Test
    void 能力が高いほど同じ譜面の期待値が大きくなる() {
        double baseline = calc.calculatePoints(88.0, RANK);
        int breakEven = service.breakEvenScore(MAX_SCORE, RANK, baseline, 0);
        FillRecommendationService.Target weak = service.pickTarget(
                stat(logitOfRate(88.5), 0.15), MAX_SCORE, MAX_SCORE, 0, RANK, baseline, breakEven);
        FillRecommendationService.Target strong = service.pickTarget(
                stat(logitOfRate(93.0), 0.15), MAX_SCORE, MAX_SCORE, 0, RANK, baseline, breakEven);

        assertThat(strong.expectedGain).isGreaterThan(weak.expectedGain);
        assertThat(weak.expectedGain).isGreaterThan(0.0);
    }

    @Test
    void 目標はコミュニティ最高スコアで頭打ちになる() {
        // 誰も 95% を超えていない譜面では、予測が上振れしても 95% より上を目標にしない。
        int communityMax = (int) (MAX_SCORE * 0.95);
        FillRecommendationService.Stat st = stat(logitOfRate(99.0), 0.2);
        int breakEven = service.breakEvenScore(MAX_SCORE, RANK, 0.0, 0);

        FillRecommendationService.Target t =
                service.pickTarget(st, MAX_SCORE, communityMax, 0, RANK, 0.0, breakEven);

        assertThat(t.score).isLessThanOrEqualTo(communityMax);
        assertThat(t.gain).isLessThanOrEqualTo(calc.calculatePoints(95.0, RANK) + 1e-9);
    }

    @Test
    void 達成時の増分は自己ベスト未満や損益分岐点未満でゼロになる() {
        double baseline = calc.calculatePoints(90.0, RANK);
        assertThat(service.goalGain((int) (MAX_SCORE * 0.89), MAX_SCORE, RANK, baseline)).isEqualTo(0.0);
        assertThat(service.goalGain((int) (MAX_SCORE * 0.91), MAX_SCORE, RANK, baseline)).isGreaterThan(0.0);
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

    // ── 挑戦済み（直近に更新したが目標未達）の判定 ───────────────────────

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static com.beatseeker.backend.entity.ScoreHistoryLog historyLog(
            java.time.LocalDateTime at, int updatedCount, String diffJson) {
        com.beatseeker.backend.entity.ScoreHistoryLog hl = new com.beatseeker.backend.entity.ScoreHistoryLog();
        hl.setUploadedAt(at);
        hl.setUpdatedCount(updatedCount);
        hl.setDiffJson(diffJson);
        return hl;
    }

    private static final java.time.LocalDateTime T0 = java.time.LocalDateTime.of(2026, 9, 8, 21, 0);

    @Test
    void 更新履歴からスコアが伸びた譜面だけを挑戦済みとして拾う() {
        // フロントの diffJson と同じ形。ランプだけ改善した要素（scoreIncrease 0）は挑戦済みにしない。
        String diff = "[{\"title\":\"A\",\"difficulty\":\"ANOTHER\",\"oldScore\":1700,\"newScore\":1750,\"scoreIncrease\":50},"
                + "{\"title\":\"L\",\"difficulty\":\"ANOTHER\",\"oldScore\":1800,\"newScore\":1800,\"scoreIncrease\":0,\"clearTypeImproved\":true}]";
        java.util.List<com.beatseeker.backend.entity.ScoreHistoryLog> logs = java.util.List.of(
                historyLog(T0, 2, diff),
                historyLog(T0.plusDays(1), 1, "{broken"));   // 壊れた JSON は読み飛ばす

        java.util.Map<String, FillRecommendationService.Attempt> attempts =
                FillRecommendationService.collectAttempts(logs, false, JSON);

        assertThat(attempts).containsOnlyKeys("A\0ANOTHER");
        FillRecommendationService.Attempt a = attempts.get("A\0ANOTHER");
        assertThat(a.oldScore).isEqualTo(1700);
        assertThat(a.newScore).isEqualTo(1750);
        assertThat(a.lastAt).isEqualTo(T0);
    }

    @Test
    void 同じ譜面を複数回更新したら最初の更新前と最後の更新後を残す() {
        java.util.List<com.beatseeker.backend.entity.ScoreHistoryLog> logs = java.util.List.of(
                historyLog(T0, 1, "[{\"title\":\"A\",\"difficulty\":\"ANOTHER\",\"oldScore\":1700,\"newScore\":1720,\"scoreIncrease\":20}]"),
                historyLog(T0.plusDays(2), 1, "[{\"title\":\"A\",\"difficulty\":\"ANOTHER\",\"oldScore\":1720,\"newScore\":1760,\"scoreIncrease\":40}]"));

        FillRecommendationService.Attempt a =
                FillRecommendationService.collectAttempts(logs, false, JSON).get("A\0ANOTHER");

        assertThat(a.oldScore).isEqualTo(1700);
        assertThat(a.newScore).isEqualTo(1760);
        assertThat(a.lastAt).isEqualTo(T0.plusDays(2));
    }

    @Test
    void アカウント初回の履歴と一括取り込みは挑戦済みに数えない() {
        String one = "[{\"title\":\"%s\",\"difficulty\":\"ANOTHER\",\"oldScore\":0,\"newScore\":1500,\"scoreIncrease\":1500}]";
        java.util.List<com.beatseeker.backend.entity.ScoreHistoryLog> logs = java.util.List.of(
                historyLog(T0, 1, String.format(one, "first")),                         // 初回取り込み（件数は少ない）
                historyLog(T0.plusDays(1), FillRecommendationService.BULK_UPLOAD_UPDATED_COUNT + 1,
                        String.format(one, "bulk")),                                     // 再取り込み
                historyLog(T0.plusDays(2), 1, String.format(one, "played")));           // 通常のプレー

        java.util.Map<String, FillRecommendationService.Attempt> attempts =
                FillRecommendationService.collectAttempts(logs, true, JSON);

        // 初回は skipFirst、再取り込みは件数超過でそれぞれ弾かれ、通常のプレーだけ残る
        assertThat(attempts).containsOnlyKeys("played\0ANOTHER");
        // 先頭がアカウント初回でなければ（それより前に履歴がある）、先頭の履歴も通常どおり数える
        assertThat(FillRecommendationService.collectAttempts(logs, false, JSON))
                .containsOnlyKeys("first\0ANOTHER", "played\0ANOTHER");
    }

    @Test
    void 挑戦済みでも目標に届いていれば除外しない() {
        FillRecommendationService.Attempt a = new FillRecommendationService.Attempt();
        a.oldScore = 1700;
        a.newScore = 1800;

        // 記録が無ければ通常の候補
        assertThat(FillRecommendationService.isUnreachedAttempt(null, 1800, 1900)).isFalse();
        // 更新したが目標（1900）に届いていない → 除外
        assertThat(FillRecommendationService.isUnreachedAttempt(a, 1800, 1900)).isTrue();
        // 目標に届いた → 次の目標で通常どおり評価する
        assertThat(FillRecommendationService.isUnreachedAttempt(a, 1900, 1900)).isFalse();
        // scores に反映されていない幽霊履歴（現在スコアが記録の更新後より低い）は信用しない
        assertThat(FillRecommendationService.isUnreachedAttempt(a, 1750, 1900)).isFalse();
    }
}
