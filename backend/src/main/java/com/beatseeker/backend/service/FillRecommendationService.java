package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
 * 各譜面について「目標スコア s を 1 つ決めて狙う」ことを前提に、
 * <pre>
 *   v(s) = P(S ≥ s | 推定能力) × ( pt(s) − baseline )
 * </pre>
 * を最大にする s を目標（targetScore）とし、そのときの P(S ≥ s) を達成率（achieveProbability）、
 * pt(s) − baseline を達成時の増分（targetGain）、v(s) を期待獲得 pt（expectedGain）として返す。
 * S は「推定能力から見た、その譜面で最終的に出せるスコア」の確率変数、
 * pt(s) は単曲 BEAT-PT、baseline は後述の押し出しライン。
 *
 * 以前は期待獲得 pt を分布全体の期待値 E[max(0, pt(S) − baseline)] で出し、目標は
 * 「予測中央値までに届くボーダー、無ければ中央値」と別々に決めていた。すると分布の上側の裾
 * （大きく伸びる薄い可能性）が期待値に丸ごと乗る一方で目標は中央値止まりになり、
 * 現在スコアが中央値付近の譜面では「目標 あと 1 点」に「期待 +0.7 pt」が並ぶ矛盾した表示になっていた。
 * 目標・達成率・期待値を同じ事象（目標に届く）で揃えたのが本実装で、
 * pt が単調増加なので v(s) ≤ E[max(0, pt(S) − baseline)] が常に成り立ち、期待値は以前より控えめになる。
 *
 * 候補 s は損益分岐スコア s*（後述）以上・コミュニティ最高以下の範囲で、分布を ±{@link #SIGMA_SPAN}σ で
 * 刻んだ各点と AA / AAA / MAX- のボーダー（ボーナス段差で pt が跳ねる点）を評価する（{@link #pickTarget}）。
 *
 * <h3>baseline（＝何と比べた増分か）</h3>
 * BEAT-PT は上位 100 譜面の合計なので、増分は TOP100 の出入りで決まる。
 *  - すでに TOP100 圏内の譜面 … baseline = その譜面の現在 pt（純粋な上積み）
 *  - TOP100 圏外／未プレイの譜面 … baseline = 100 位の pt（そこを超えて初めて合計に効く）
 * 損益分岐スコア s* は pt(s*) = baseline となるスコアで、二分探索で求める。
 * P(S ≥ s*) は「この譜面を触る価値がそもそもあるか」の確率で、breakEvenProbability として返す
 * （達成率 achieveProbability は目標に対する確率で、これとは別）。
 *
 * <h3>並びと打ち切り</h3>
 * 候補は達成率（P(S ≥ s*)）の降順に並べ、期待獲得 pt を先頭から足して
 * 「次ランクまでの差分」を満たした所で打ち切る。差分はフロントが表示している値を受け取る。
 *
 * <h3>枯渇対策</h3>
 * ペア回帰の参照が足りない譜面には、同じスコアから推定した加法モデル
 * {@code logit = θ_u + δ_c}（{@link PairRegressionService.AdditiveModel}）で予測を付ける
 * （accuracy = BASE）。δ_c すら立たない新曲は同ランクの平均 δ に落とす（RANK）。
 * 難易度表に載っている譜面は原則すべて候補になる。
 *
 * <h3>挑戦済みの除外</h3>
 * 直近 {@link #ATTEMPT_COOLDOWN_DAYS} 日の更新履歴（{@link ScoreHistoryLog#getDiffJson()}）で
 * スコアを更新したのに目標に届かなかった譜面は、候補から外して {@code attemptedItems} に分けて返す。
 * 「挑戦したが取れなかった」譜面を出し続けても次の一手にならないので、残りの候補で差分を埋め直す。
 * 目標に届いた譜面は外さず、次の目標（例: AAA の次は MAX-）で通常どおり再評価する。
 * 判定は状態を持たず、リクエストのたびに履歴から引き直す。
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

    /** 目標候補グリッドの幅（標準正規の ±SIGMA_SPAN σ を刻む）。 */
    private static final double SIGMA_SPAN = 3.5;
    /** 目標候補グリッドの分割数（奇数にして中央 = 予測中央値を必ず含める）。 */
    private static final int QUAD_NODES = 41;

    /** 期待獲得 pt がこの値未満の候補は返さない（UI 上 "+0.0 pt" になるため）。 */
    private static final double MIN_EXPECTED_GAIN = 0.05;

    /**
     * 次ランクまでの差分が分からない（すでに最高位など）ときの返却件数上限。
     * 差分が分かるときは「達成率降順に足して差分を満たすまで」で切るので上限は掛けない。
     */
    static final int MAX_ITEMS_WITHOUT_GAP = 300;

    /**
     * 加法モデルの譜面効果 δ を単独で信用する最小ユーザー数。
     * これ未満の譜面は同ランクの平均 δ（RANK フォールバック）に落とす。
     */
    static final int MIN_CHART_EFFECT_N = 5;
    /** 譜面ごとの残差 sd をプール値へ縮約する仮想サンプル数。 */
    private static final int CHART_SD_SHRINK_N = 10;
    /**
     * ユーザー実力 θ_u を推定するのに必要な参照譜面数。
     * 1 譜面でも推定する（始めたばかりのユーザーにも候補を出すため）。参照が少ないぶんは
     * θ_u の分散をプール残差² / n で下支えして σ に反映する。
     */
    private static final int MIN_USER_ABILITY_REFS = 1;

    /** 予測の出どころ。達成率と一緒に UI へ返し、フロントは概算系にバッジを付ける。 */
    static final String ACC_HIGH = "HIGH";
    static final String ACC_LOW = "LOW";
    /** 加法モデル（θ_u + δ_c）。ペア回帰の参照が足りない譜面向け。 */
    static final String ACC_BASE = "BASE";
    /** 同ランク平均 δ。δ すら立たない新曲・不人気曲向け。 */
    static final String ACC_RANK = "RANK";

    /** 曲マスタ・難易度表のメモリ保持時間（ms）。マスタは日次更新なので 10 分で十分。 */
    private static final long MASTER_CACHE_TTL_MS = 10 * 60 * 1000L;

    /**
     * 挑戦済み判定の冷却期間（日）。この期間内にスコア更新したのに目標未達の譜面は候補から外す。
     * 練習メニューの週サイクルに合わせて 7 日。過ぎれば再び候補に戻る。
     */
    static final int ATTEMPT_COOLDOWN_DAYS = 7;
    /**
     * 1 回のアップロードでこの件数を超えて更新されていたら「一括取り込み」とみなし、挑戦済み判定から外す。
     * 次回作移行直後の再取り込みや長期離脱後の再開では数百譜面が一度に「更新」になるが、
     * それは挑戦の結果ではない。通常のプレーセッションは多くても数十件。
     */
    static final int BULK_UPLOAD_UPDATED_COUNT = 100;

    /** BEAT-PT のボーナス段差。UI に「AA 狙い」「AAA 狙い」と出すための目標候補でもある。 */
    private static final double[] BORDER_RATES = {77.77, 88.88, 94.44};
    /** {@link #BORDER_RATES} と同じ並びのラベル。 */
    private static final String[] BORDER_LABELS = {"AA", "AAA", "MAX-"};

    private final PairRegressionService pairRegressionService;
    private final ScoreRecalculationService scoreRecalculationService;
    private final ScoreRepository scoreRepository;
    private final BeatPtCalculator beatPtCalculator;
    /** 更新履歴（挑戦済み判定用）。テストでは null を許容し、その場合は挑戦済み無しとして扱う。 */
    private final ScoreHistoryLogRepository historyLogRepository;
    /** diffJson の解釈用。テストでは null を許容する。 */
    private final ObjectMapper objectMapper;

    /** 曲マスタ（title_difficultyCode → maxScore）のキャッシュ。 */
    private volatile Map<String, Integer> cachedMaxScores = Collections.emptyMap();
    /** 難易度表（title_diffName → 非公式ランク文字列）のキャッシュ。 */
    private volatile Map<String, String> cachedInformalRanks = Collections.emptyMap();
    /**
     * 非公式ランク → 同ランク譜面の平均 δ（RANK フォールバック用）。
     * 難易度表と加法モデルの両方から作るので、マスタと同じタイミングで読み直す。
     */
    private volatile Map<String, RankEffect> cachedRankEffects = Collections.emptyMap();
    /** 上記を読み込んだ時刻（epoch ms）。0 なら未読み込み。 */
    private volatile long masterLoadedAt = 0L;

    /** 同ランク譜面の δ の集計。δ の平均と、譜面ごとのばらつき（分散）を持つ。 */
    static final class RankEffect {
        /** n_c で重み付けした δ の平均。 */
        double meanDelta;
        /** 同ランク内の δ のばらつき（分散）。「どの譜面かが分からない」ぶんの不確実性。 */
        double betweenVar;
        /** 集計に入った譜面数。 */
        int chartCount;
    }

    public FillRecommendationService(PairRegressionService pairRegressionService,
                                     ScoreRecalculationService scoreRecalculationService,
                                     ScoreRepository scoreRepository,
                                     BeatPtCalculator beatPtCalculator,
                                     ScoreHistoryLogRepository historyLogRepository,
                                     ObjectMapper objectMapper) {
        this.pairRegressionService = pairRegressionService;
        this.scoreRecalculationService = scoreRecalculationService;
        this.scoreRepository = scoreRepository;
        this.beatPtCalculator = beatPtCalculator;
        this.historyLogRepository = historyLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 【メソッドの役割】 指定ユーザーのコスパ埋めレコメンドを返す（次ランクまでの差分はサーバー側で算出）。
     *
     * @see #computeFillRecommendation(Long, Double)
     */
    public Map<String, Object> computeFillRecommendation(Long userId) {
        return computeFillRecommendation(userId, null);
    }

    /**
     * 【メソッドの役割】 指定ユーザーのコスパ埋めレコメンドを「達成率の高い順」に、
     * 期待獲得 pt の累積が次ランクまでの差分を満たすまで返す。
     *
     * <h3>候補が枯渇しないための 4 段構え</h3>
     * <ol>
     *   <li>HIGH … ペア回帰（|r| ≧ 0.95）の参照 3 譜面以上</li>
     *   <li>LOW … ペア回帰（|r| ≧ 0.90）の参照 3 譜面以上</li>
     *   <li>BASE … 加法モデル θ_u + δ_c（その譜面を 5 人以上がプレイ済み）</li>
     *   <li>RANK … θ_u + 同ランク平均 δ（新曲・不人気曲。難易度表に載っていれば必ず出る）</li>
     * </ol>
     * 下の段ほど σ が大きくなるので、達成率順に並べると自然に後ろへ回る。
     *
     * @param userId      対象ユーザー ID
     * @param gapOverride 次ランクまでの差分 pt。フロントが表示している値をそのまま渡す。
     *                    null ならサーバー側の副ティア境界（{@link BeatTierScale#nextSubTierOf}）から求める。
     * @return {@code {top100Threshold, totalBeatPt, scoredChartCount, referenceChartCount,
     *          nextTierGap, nextTierLabel, cumulativeExpectedGain, gapCovered,
     *          candidateCount, tierCounts, items:[...],
     *          attemptedItems:[...], attemptedCount, attemptCooldownDays}}。
     *         items の各要素は
     *         {@code {title, difficultyName, informalRank, difficultyLevel, unplayed, currentScore,
     *          currentRate, currentBeatPt, maxScore, predictedScore, predictedRate, sigmaRate,
     *          breakEvenScore, breakEvenProbability, achieveProbability, targetScore, targetRate,
     *          targetLabel, targetProbability, targetGain, expectedGain, supportCount, accuracy}}。
     *         achieveProbability（= targetProbability）は目標 targetScore に届く確率、
     *         expectedGain = achieveProbability × targetGain。
     *         attemptedItems は同じ形に {@code attemptOldScore, attemptNewScore, lastAttemptAt} を足したもので、
     *         直近の更新が新しい順。
     */
    public Map<String, Object> computeFillRecommendation(Long userId, Double gapOverride) {
        long t0 = System.currentTimeMillis();
        pairRegressionService.ensureBuilt();
        loadMastersIfStale();

        Map<String, Integer> notesByKey = pairRegressionService.getNotesByKey();
        Map<String, Integer> communityMaxByKey = pairRegressionService.getCommunityMaxByKey();
        Map<String, PairRegressionService.ChartEffect> chartEffects = pairRegressionService.getChartEffects();
        double pooledSd = pairRegressionService.getPooledResidSd();
        Map<String, RankEffect> rankEffects = cachedRankEffects;
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

        // 2.5) 加法モデルのユーザー実力 θ_u を、参照譜面の (logit − δ_c) の平均で推定する。
        //      δ_c が 5 人以上で立っている譜面だけを使う（自分 1 人の残差で δ が決まる譜面は循環参照になる）。
        //      キャッシュ側の θ は構築時点のものなので使わず、今のスコアから毎回引き直す。
        double abilitySum = 0, abilitySq = 0;
        int abilityN = 0;
        for (Map.Entry<String, Integer> e : refCharts.entrySet()) {
            PairRegressionService.ChartEffect ce = chartEffects.get(e.getKey());
            if (ce == null || ce.n < MIN_CHART_EFFECT_N) continue;
            double v = PairRegressionService.scoreToLogit(myScores.get(e.getKey()), e.getValue()) - ce.delta;
            abilitySum += v;
            abilitySq += v * v;
            abilityN++;
        }
        Double ability = null;
        double abilityVar = 0;
        if (abilityN >= MIN_USER_ABILITY_REFS) {
            ability = abilitySum / abilityN;
            // θ_u の標準誤差² = 参照間のばらつき / n。参照が多いほど θ は安定する。
            // 参照が 1〜2 譜面だと標本分散が 0 に潰れるので、プール残差² / n を下限にする。
            double between = Math.max(0.0, abilitySq / abilityN - ability * ability);
            abilityVar = Math.max(between, pooledSd * pooledSd) / abilityN;
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

        // 3.5) 直近の更新履歴から「挑戦済み（スコア更新あり）」の譜面を集める。
        //      候補ループで目標未達のものを除外し、attemptedItems に分けて返す。
        Map<String, Attempt> attempts = loadRecentAttempts(userId);

        // 4) 候補ごとに期待獲得 pt を計算する。
        //    候補は「難易度表に載っている Lv11+ の ANOTHER / LEGGENDARIA 全譜面」。
        //    ペア回帰の参照が無い譜面も加法モデル → 同ランク平均の順に落として必ず予測を付ける。
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> attemptedItems = new ArrayList<>();
        Map<String, Integer> tierCounts = new HashMap<>();
        int noPrediction = 0;
        for (Map.Entry<String, Integer> chart : notesByKey.entrySet()) {
            String key = chart.getKey();
            Integer notes = chart.getValue();
            if (notes == null || notes <= 0) continue;
            int maxScore = notes * 2;

            String[] parts = key.split("\0", 2);
            if (parts.length < 2) continue;
            String title = parts[0];
            String diffName = parts[1];

            String informalRank = informalRanks.get(title + "_" + diffName);
            // 難易度表に載っていない譜面はそもそも BEAT-PT が付かないので候補にならない。
            if (informalRank == null || beatPtCalculator.getWeight(informalRank) == 0) continue;

            Pred pred = preds.get(key);
            Stat stat = pred == null ? null : pred.resolve();
            if (stat == null) {
                stat = fallbackStat(key, informalRank, ability, abilityVar,
                        chartEffects, rankEffects, pooledSd);
            }
            if (stat == null) { // θ_u が立たない、または難易度表のランクに δ が無い
                noPrediction++;
                continue;
            }
            tierCounts.merge(stat.accuracy, 1, Integer::sum);

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

            // 目標: P(S ≥ s) × (pt(s) − baseline) が最大になるスコア。達成率と期待獲得 pt はこの目標に対する値。
            Target target = pickTarget(stat, maxScore, scoreCap, currentScore, informalRank, baseline, breakEven);
            if (target == null || target.expectedGain < MIN_EXPECTED_GAIN) continue;

            // 「そもそも合計に効く可能性」。目標の達成率とは別に診断用に返す。
            double breakEvenProb = tailProbability(breakEven, maxScore, stat.mu, stat.sigma);
            double predictedScore = Math.min(scoreCap,
                    PairRegressionService.logitToScoreRate(stat.mu) * maxScore);

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
            // 根拠モーダル用: フロントが同じ分布（logit 空間の正規分布）と損益分岐・目標の選び方を図示するのに使う。
            item.put("muLogit", stat.mu);
            item.put("sigmaLogit", stat.sigma);
            item.put("baselinePt", baseline);
            item.put("scoreCap", scoreCap);
            item.put("breakEvenScore", breakEven);
            item.put("breakEvenProbability", breakEvenProb);
            item.put("achieveProbability", target.probability);
            item.put("targetScore", target.score);
            item.put("targetRate", target.score * 100.0 / maxScore);
            item.put("targetLabel", target.label);
            item.put("targetProbability", target.probability);
            item.put("targetGain", target.gain);
            item.put("expectedGain", target.expectedGain);
            item.put("supportCount", stat.support);
            item.put("accuracy", stat.accuracy);

            // 直近に更新したのに目標へ届かなかった譜面は候補から外す。
            // 目標がボーダー（AA / AAA / MAX-）ならそのスコア、無ければ予測中央値を「届いたか」の基準にする。
            // 目標は常に現在スコアより上に立つので、目標そのものを基準にすると直近に更新した譜面が
            // すべて未達扱いになる。中央値を既に超えている（実力どおり出せている）譜面は未達と見なさない。
            double goalScore = target.label.isEmpty() ? predictedScore : target.score;
            Attempt attempt = attempts.get(key);
            if (isUnreachedAttempt(attempt, currentScore, goalScore)) {
                item.put("attemptOldScore", attempt.oldScore);
                item.put("attemptNewScore", attempt.newScore);
                item.put("lastAttemptAt", com.beatseeker.backend.util.JstTime.toIsoString(attempt.lastAt));
                attemptedItems.add(item);
                continue;
            }
            items.add(item);
        }

        // 5) 達成率降順（同率なら期待値降順）。「確実に取れるものから順に」が先頭に来る。
        int candidateCount = items.size();
        sortByAchievability(items);
        // 挑戦済みは「直近に更新した順」。ユーザーが見返す用途なので時系列が自然。
        attemptedItems.sort((a, b) -> ((String) b.get("lastAttemptAt")).compareTo((String) a.get("lastAttemptAt")));

        // 6) 次ランクまでの差分を満たすまで採用する。差分が分からなければ件数上限で切る。
        double gap;
        String nextTierLabel = null;
        if (gapOverride != null) {
            gap = gapOverride;
        } else {
            BeatTierScale.SubTier next = BeatTierScale.nextSubTierOf(totalBeatPt);
            gap = next == null ? 0.0 : next.minPoints() - totalBeatPt;
            nextTierLabel = next == null ? null : next.label();
        }
        items = cutAtGap(items, gap, MAX_ITEMS_WITHOUT_GAP);
        double cumulative = 0;
        for (Map<String, Object> it : items) cumulative += ((Number) it.get("expectedGain")).doubleValue();

        Map<String, Object> result = new HashMap<>();
        result.put("top100Threshold", threshold);
        result.put("totalBeatPt", totalBeatPt);
        result.put("scoredChartCount", myPoints.size());
        result.put("referenceChartCount", refCharts.size());
        result.put("nextTierGap", gap);
        result.put("nextTierLabel", nextTierLabel);
        result.put("cumulativeExpectedGain", cumulative);
        result.put("gapCovered", gap > 0 && cumulative >= gap);
        result.put("candidateCount", candidateCount);
        result.put("noPredictionCount", noPrediction);
        result.put("tierCounts", tierCounts);
        result.put("items", items);
        result.put("attemptedItems", attemptedItems);
        result.put("attemptedCount", attemptedItems.size());
        result.put("attemptCooldownDays", ATTEMPT_COOLDOWN_DAYS);
        log.debug("computeFillRecommendation(user={}) -> {} items of {} candidates (tiers {}, noPrediction {}, attempted {}), gap {} covered={} in {} ms",
                userId, items.size(), candidateCount, tierCounts, noPrediction, attemptedItems.size(),
                String.format("%.1f", gap), cumulative >= gap, System.currentTimeMillis() - t0);
        return result;
    }

    // ── 挑戦済み（直近に更新したが目標未達）の判定 ───────────────────────

    /** 直近の冷却期間内に自己ベストを更新した譜面 1 件ぶんの記録。 */
    static final class Attempt {
        /** 期間内で最初に更新したときの更新前スコア。 */
        int oldScore = Integer.MAX_VALUE;
        /** 期間内で最後に更新したときの更新後スコア。 */
        int newScore = 0;
        /** 最後に更新したアップロードの日時。 */
        LocalDateTime lastAt;
    }

    /**
     * 【メソッドの役割】 直近 {@link #ATTEMPT_COOLDOWN_DAYS} 日の更新履歴から挑戦済み譜面を集める。
     *
     * リポジトリ未注入（テスト）や履歴の読み取り失敗時は空を返し、除外を行わない。
     * 除外は「あると嬉しい」機能なので、失敗してもレコメンド本体を止めない。
     */
    private Map<String, Attempt> loadRecentAttempts(Long userId) {
        if (historyLogRepository == null || objectMapper == null) return Collections.emptyMap();
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(ATTEMPT_COOLDOWN_DAYS);
            List<ScoreHistoryLog> logs =
                    historyLogRepository.findByUser_IdAndUploadedAtGreaterThanEqualOrderByUploadedAtAsc(userId, since);
            if (logs.isEmpty()) return Collections.emptyMap();
            boolean hasOlder = historyLogRepository.existsByUser_IdAndUploadedAtLessThan(userId, since);
            return collectAttempts(logs, !hasOlder, objectMapper);
        } catch (RuntimeException e) {
            log.warn("loadRecentAttempts(user={}) failed; skipping attempted-chart exclusion", userId, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 【メソッドの役割】 更新履歴の diffJson を読んで、譜面キー → {@link Attempt} を作る。
     *
     * diffJson はフロントが送る更新曲の配列で、各要素に
     * {@code title, difficulty(またはdifficultyName), oldScore, newScore, scoreIncrease} が入る。
     * ランプだけ改善した要素（scoreIncrease = 0）は「スコアを更新した」とは見なさない。
     *
     * 一括取り込みの誤判定を避けるため、次の履歴は読み飛ばす。
     * <ul>
     *   <li>アカウント初回の履歴（{@code skipFirst}）… 全譜面が oldScore = 0 の差分として並ぶ</li>
     *   <li>updatedCount が {@link #BULK_UPLOAD_UPDATED_COUNT} を超える履歴 … 再取り込みや長期離脱後の再開</li>
     * </ul>
     *
     * @param logsAsc   uploadedAt 昇順の履歴
     * @param skipFirst 先頭の履歴がアカウント初回なら true
     * @return 譜面キー（title\0difficultyName）→ 挑戦記録。無ければ空
     */
    static Map<String, Attempt> collectAttempts(List<ScoreHistoryLog> logsAsc, boolean skipFirst,
                                                ObjectMapper objectMapper) {
        Map<String, Attempt> out = new HashMap<>();
        boolean first = true;
        for (ScoreHistoryLog hl : logsAsc) {
            boolean isFirst = first;
            first = false;
            if (isFirst && skipFirst) continue;
            if (hl.getUpdatedCount() != null && hl.getUpdatedCount() > BULK_UPLOAD_UPDATED_COUNT) continue;
            String json = hl.getDiffJson();
            if (json == null || json.isBlank() || "[]".equals(json)) continue;

            JsonNode root;
            try {
                root = objectMapper.readTree(json);
            } catch (Exception e) {
                continue; // 壊れた diffJson は無視（他の履歴に影響させない）
            }
            if (root == null || !root.isArray()) continue;

            for (JsonNode d : root) {
                String title = d.path("title").asText(null);
                String diff = d.hasNonNull("difficulty") ? d.get("difficulty").asText()
                        : d.path("difficultyName").asText(null);
                if (title == null || diff == null) continue;
                int oldScore = d.path("oldScore").asInt(0);
                int newScore = d.path("newScore").asInt(0);
                int increase = d.has("scoreIncrease") ? d.path("scoreIncrease").asInt(0) : newScore - oldScore;
                if (increase <= 0 || newScore <= 0) continue;

                Attempt a = out.computeIfAbsent(title + "\0" + diff, k -> new Attempt());
                a.oldScore = Math.min(a.oldScore, oldScore);
                a.newScore = Math.max(a.newScore, newScore);
                if (a.lastAt == null || (hl.getUploadedAt() != null && hl.getUploadedAt().isAfter(a.lastAt))) {
                    a.lastAt = hl.getUploadedAt();
                }
            }
        }
        // 日時が取れなかった記録は並び替えできないので落とす（実際には uploadedAt は NOT NULL）。
        out.values().removeIf(a -> a.lastAt == null);
        return out;
    }

    /**
     * 【メソッドの役割】 「直近に更新したが目標未達」なら true。
     *
     * <ul>
     *   <li>挑戦記録が無ければ false（通常の候補）</li>
     *   <li>現在スコアが記録の更新後スコアに満たなければ false … scores に保存されていない幽霊履歴なので信用しない</li>
     *   <li>現在スコアが目標以上なら false … 届いたので次の目標で通常どおり評価する</li>
     * </ul>
     *
     * @param attempt      挑戦記録（null 可）
     * @param currentScore 現在の自己ベスト
     * @param goalScore    届いたかの基準。ボーダー目標ならそのスコア、無ければ予測中央値
     */
    static boolean isUnreachedAttempt(Attempt attempt, int currentScore, double goalScore) {
        if (attempt == null) return false;
        if (currentScore < attempt.newScore) return false;
        return currentScore < goalScore;
    }

    // ── 並び替えと打ち切り ──────────────────────────────────────────────

    /**
     * 【メソッドの役割】 達成率の高い順に並べる。
     *
     * 達成率は表示上 1% 刻みなので、同じ表示になるものは期待獲得 pt の大きい順にする。
     * 生の確率で比べると 0.999 と 0.998 の差でノイズ順になり、見た目と並びが合わなくなる。
     */
    static void sortByAchievability(List<Map<String, Object>> items) {
        items.sort((a, b) -> {
            long pa = Math.round(((Number) a.get("achieveProbability")).doubleValue() * 100);
            long pb = Math.round(((Number) b.get("achieveProbability")).doubleValue() * 100);
            if (pa != pb) return Long.compare(pb, pa);
            return Double.compare(
                    ((Number) b.get("expectedGain")).doubleValue(),
                    ((Number) a.get("expectedGain")).doubleValue());
        });
    }

    /**
     * 【メソッドの役割】 期待獲得 pt を先頭から足していき、差分を満たした時点で打ち切る。
     *
     * 差分に届く候補が足りなければ全件返す（フロントは「不足」と表示する）。
     * 差分が 0 以下（最高位など）なら {@code maxWithoutGap} 件で切る。
     *
     * @param items 達成率降順に並んだ候補
     * @param gap   次ランクまでの差分 pt
     * @return 採用した候補（先頭から連続）
     */
    static List<Map<String, Object>> cutAtGap(List<Map<String, Object>> items, double gap, int maxWithoutGap) {
        if (gap <= 0) {
            return items.size() > maxWithoutGap ? new ArrayList<>(items.subList(0, maxWithoutGap)) : items;
        }
        double acc = 0;
        for (int i = 0; i < items.size(); i++) {
            acc += ((Number) items.get(i).get("expectedGain")).doubleValue();
            if (acc >= gap) {
                return new ArrayList<>(items.subList(0, i + 1));
            }
        }
        return items;
    }

    // ── フォールバック予測（加法モデル） ─────────────────────────────────

    /**
     * 【メソッドの役割】 ペア回帰の参照が足りない譜面の予測を加法モデルで作る。
     *
     * <ul>
     *   <li>BASE: μ = θ_u + δ_c、σ² = s_c² + s_c²/n_c + Var(θ_u)。s_c は譜面残差 sd をプール値へ縮約したもの。</li>
     *   <li>RANK: μ = θ_u + mean δ(同ランク)、σ² = s_pool² + Var_rank(δ) + Var(θ_u)。</li>
     * </ul>
     *
     * @param key          譜面キー
     * @param informalRank 非公式ランク
     * @param ability      θ_u。null なら予測できない
     * @param abilityVar   θ_u の標準誤差²
     * @return 予測。作れなければ null
     */
    static Stat fallbackStat(String key, String informalRank, Double ability, double abilityVar,
                             Map<String, PairRegressionService.ChartEffect> chartEffects,
                             Map<String, RankEffect> rankEffects, double pooledSd) {
        if (ability == null) return null;

        PairRegressionService.ChartEffect ce = chartEffects.get(key);
        if (ce != null && ce.n >= MIN_CHART_EFFECT_N) {
            double sdC = Double.isNaN(ce.residSd) ? pooledSd : ce.residSd;
            double shrunkVar = (ce.n * sdC * sdC + CHART_SD_SHRINK_N * pooledSd * pooledSd)
                    / (ce.n + CHART_SD_SHRINK_N);
            double sigma = Math.sqrt(shrunkVar + shrunkVar / ce.n + abilityVar);
            return makeStat(ability + ce.delta, sigma, ce.n, ACC_BASE);
        }

        RankEffect re = rankEffects.get(informalRank);
        if (re == null || re.chartCount <= 0) return null;
        double sigma = Math.sqrt(pooledSd * pooledSd + re.betweenVar + abilityVar);
        return makeStat(ability + re.meanDelta, sigma, re.chartCount, ACC_RANK);
    }

    /** σ をクランプして {@link Stat} を組み立てる。 */
    private static Stat makeStat(double mu, double sigma, int support, String accuracy) {
        Stat s = new Stat();
        s.mu = mu;
        s.sigma = Math.max(MIN_SIGMA_LOGIT, Math.min(MAX_SIGMA_LOGIT, sigma));
        s.support = support;
        s.accuracy = accuracy;
        return s;
    }

    /**
     * 【メソッドの役割】 非公式ランクごとに同ランク譜面の δ を集計する（RANK フォールバック用）。
     *
     * n_c ≧ {@link #MIN_CHART_EFFECT_N} の譜面だけを n_c 重みで平均し、
     * 同ランク内の δ の分散も持つ。分散は「そのランクのどの譜面かが分からない」不確実性として σ に足す。
     */
    static Map<String, RankEffect> buildRankEffects(Map<String, String> informalRanks,
                                                    Map<String, PairRegressionService.ChartEffect> chartEffects) {
        Map<String, double[]> acc = new HashMap<>(); // rank → [Σw, Σwδ, Σwδ², count]
        for (Map.Entry<String, PairRegressionService.ChartEffect> e : chartEffects.entrySet()) {
            PairRegressionService.ChartEffect ce = e.getValue();
            if (ce.n < MIN_CHART_EFFECT_N) continue;
            String[] parts = e.getKey().split("\0", 2);
            if (parts.length < 2) continue;
            String rank = informalRanks.get(parts[0] + "_" + parts[1]);
            if (rank == null) continue;
            double[] a = acc.computeIfAbsent(rank, k -> new double[4]);
            a[0] += ce.n;
            a[1] += ce.n * ce.delta;
            a[2] += ce.n * ce.delta * ce.delta;
            a[3] += 1;
        }
        Map<String, RankEffect> out = new HashMap<>();
        for (Map.Entry<String, double[]> e : acc.entrySet()) {
            double[] a = e.getValue();
            if (a[0] <= 0) continue;
            RankEffect re = new RankEffect();
            re.meanDelta = a[1] / a[0];
            re.betweenVar = Math.max(0.0, a[2] / a[0] - re.meanDelta * re.meanDelta);
            re.chartCount = (int) a[3];
            out.put(e.getKey(), re);
        }
        return out;
    }

    // ── 期待値まわりの計算 ────────────────────────────────────────────────

    /**
     * 【メソッドの役割】 目標スコアに届いたときの合計 BEAT-PT の増分 = max(0, pt(目標) − baseline)。
     *
     * 期待獲得 pt はこれに達成率 P(S ≥ 目標) を掛けたもの。
     * 目標・達成率・増分・期待値が同じ事象を指すように、期待値は分布全体を積分せずこの形で出す。
     *
     * @param targetScore  目標スコア
     * @param maxScore     理論値スコア（notes × 2）
     * @param informalRank 非公式難易度（weight 決定用）
     * @param baseline     この pt を超えたぶんだけが合計 BEAT-PT の増分になる
     * @return 達成時の増分 pt（≥ 0）
     */
    double goalGain(int targetScore, int maxScore, String informalRank, double baseline) {
        double pt = beatPtCalculator.calculatePoints(targetScore * 100.0 / maxScore, informalRank);
        return Math.max(0.0, pt - baseline);
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

    /** 目標スコアと、その達成確率・達成時の増分・期待獲得 pt（= 確率 × 増分）。 */
    static final class Target {
        int score;
        /** 'AA' / 'AAA' / 'MAX-'。目標がボーダーちょうどでなければ空文字。 */
        String label = "";
        double probability;
        double gain;
        double expectedGain;
    }

    /**
     * 【メソッドの役割】 目標スコアを「達成率 × 達成時の増分」が最大になる点として決める。
     *
     * 候補は、損益分岐スコア（と現在スコア + 1）からコミュニティ最高までの範囲にある
     * <ul>
     *   <li>分布を ±{@link #SIGMA_SPAN}σ で等間隔に刻んだ各点のスコア</li>
     *   <li>AA / AAA / MAX- のボーダー（ボーナス段差で pt が跳ねるので、段差の直上が最適になりやすい）</li>
     *   <li>範囲の両端（損益分岐スコア、コミュニティ最高）</li>
     * </ul>
     * で、同点なら低い（届きやすい）方を採る。目標がボーダーちょうどなら label を付ける。
     *
     * 現在スコアが予測中央値付近の譜面では、中央値より上の「伸びる薄い可能性」に賭ける目標になるので
     * 達成率は 50% を下回り、期待値もそれに見合って小さくなる（「あと 1 点で +0.7 pt」にはならない）。
     *
     * @return 目標。狙える点が無ければ（損益分岐スコアがコミュニティ最高を超えている）null
     */
    Target pickTarget(Stat stat, int maxScore, int scoreCap, int currentScore,
                      String informalRank, double baseline, int breakEven) {
        int lo = Math.max(currentScore + 1, breakEven);
        if (lo > scoreCap) return null;

        java.util.TreeSet<Integer> candidates = new java.util.TreeSet<>();
        candidates.add(lo);
        candidates.add(scoreCap);
        double step = 2.0 * SIGMA_SPAN / (QUAD_NODES - 1);
        for (int i = 0; i < QUAD_NODES; i++) {
            double z = -SIGMA_SPAN + i * step;
            int score = (int) Math.round(PairRegressionService.logitToScoreRate(stat.mu + stat.sigma * z) * maxScore);
            if (score >= lo && score <= scoreCap) candidates.add(score);
        }
        Map<Integer, String> borderLabels = new HashMap<>();
        for (int i = 0; i < BORDER_RATES.length; i++) {
            int need = borderScore(maxScore, BORDER_RATES[i]);
            if (need >= lo && need <= scoreCap) {
                candidates.add(need);
                borderLabels.put(need, BORDER_LABELS[i]);
            }
        }

        Target best = null;
        for (int score : candidates) {
            double gain = goalGain(score, maxScore, informalRank, baseline);
            if (gain <= 0) continue;
            double probability = tailProbability(score, maxScore, stat.mu, stat.sigma);
            double expected = probability * gain;
            if (best != null && expected <= best.expectedGain) continue; // 同点は低い方（先に来る方）を残す
            best = new Target();
            best.score = score;
            best.label = borderLabels.getOrDefault(score, "");
            best.probability = probability;
            best.gain = gain;
            best.expectedGain = expected;
        }
        return best;
    }

    /**
     * 【メソッドの役割】 ボーダーレート（%）を「超える」のに必要な最小スコア。
     *
     * 段差は超えて初めて付く（{@link BeatPtCalculator#calculatePoints} は {@code scoreRate > border}）ので、
     * maxScore × border / 100 を超える最小の整数を返す。以前は ceil + 1 で、端数があるときに 1 点多かった。
     * 浮動小数の丸めで境界ちょうどが下に落ちても、最後に実際のレート比較で補正する。
     */
    static int borderScore(int maxScore, double borderRate) {
        int need = (int) Math.floor(maxScore * borderRate / 100.0) + 1;
        while (need * 100.0 / maxScore <= borderRate) need++;
        return need;
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
    static final class Stat {
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
            // 同ランク平均 δ は難易度表 × 加法モデルの積なので、マスタと同じ周期で作り直す。
            cachedRankEffects = buildRankEffects(cachedInformalRanks, pairRegressionService.getChartEffects());
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
