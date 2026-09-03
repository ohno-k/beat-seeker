package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.PracticeMenu;
import com.beatseeker.backend.entity.PracticeMenuItem;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.entity.UserTrainingSettings;
import com.beatseeker.backend.repository.PracticeMenuRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.UserTrainingSettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【Service の役割】 週次の練習メニューを組み、採点する（設計書 §4・§5）。
 *
 * <h3>1 週の単位</h3>
 * 月曜 0:00 JST 〜 日曜 23:59 JST。{@link #currentWeekStart()} がその週の月曜日を返す。
 * リーグ（月 12:00 〜 日 21:00）とはあえて揃えず、暦の週に合わせている。
 *
 * <h3>枠</h3>
 * <pre>
 *   計測 2 曲 … 次のティアを分ける「登竜門譜面」（{@link TierBenchmarkCacheService}）
 *   課題 6 曲 … 弱点軸（{@link TendencyAxisService}）の譜面から達成確率 40〜70% の帯
 *   埋め 4 曲 … 期待獲得 BEAT-PT の大きい順（{@link FillRecommendationService} の計算を再利用）
 * </pre>
 *
 * <h3>スコアの参照範囲（重要）</h3>
 * 能力推定・baseline・「未プレイか」の判定はすべて <b>歴代スコア</b>
 * （現行 {@code scores} ＋ 過去作 {@code past_scores}）を使う。
 * 前作で AAA を出している譜面を「未プレイなので埋めましょう」と勧めるのは
 * 練習の助言として誤りになるため。
 * INFINITAS 由来（{@code source = 'infinitas'}）は対象外
 * （プレー回数を持たず、アーケードと前提が違う）。
 *
 * <p>これは BEAT-PT やランキングの集計とは別の話で、それらは従来どおり現行作のみを見る。
 * 本サービスが出す {@code totalBeatPt} も現行作のみで計算する（ティア判定の整合のため）。
 *
 * <h3>採点</h3>
 * アップロードのフックは持たず、メニューを開いたときに
 * 「今の歴代ベスト」と提示時点の {@code baselineScore} を突き合わせて状態を確定する
 * （遅延採点）。目標到達なら ACHIEVED、更新があれば PROGRESSED。
 * 週をまたいだメニューは開いた時点で締め、未更新を UNTOUCHED にして集計を書く。
 */
@Service
public class PracticeMenuService {

    private static final Logger log = LoggerFactory.getLogger(PracticeMenuService.class);

    /** 週の境界を決めるタイムゾーン。IIDX の稼働と同じ JST 固定。 */
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    /** BEAT-PT 合計の対象譜面数。{@code beatTier.ts} の TOP_CHART_LIMIT と同値。 */
    private static final int TOP_CHART_LIMIT = 100;

    /** 既定の週プレイ数。枠の基準サイズはこの数を前提に決めてある。 */
    public static final int DEFAULT_WEEKLY_PLAYS = 20;

    /** 1 クレジットの曲数。IIDX は 1 クレジット 4 曲が基本なので、設定はこの単位で刻む。 */
    public static final int PLAYS_PER_CREDIT = 4;

    /**
     * 週プレイ数として受け付ける範囲。
     * 下限は 1 クレジット（4 曲）、上限は 100 クレジット（400 曲）。
     */
    public static final int MIN_WEEKLY_PLAYS = PLAYS_PER_CREDIT;
    public static final int MAX_WEEKLY_PLAYS = PLAYS_PER_CREDIT * 100;

    /** 枠の基準サイズ（週 {@value #DEFAULT_WEEKLY_PLAYS} プレイのとき）。 */
    private static final int BASE_SLOT_MEASURE = 2;
    private static final int BASE_SLOT_TASK = 6;
    private static final int BASE_SLOT_FILL = 4;

    /** 想定プレイ回数。課題曲だけ 2 回、他は 1 回。 */
    private static final int PLAYS_MEASURE = 1;
    private static final int PLAYS_TASK = 2;
    private static final int PLAYS_FILL = 1;

    /** 課題曲として採用する達成確率の帯。「届くが簡単ではない」ライン。 */
    private static final double TASK_PROB_MIN = 0.40;
    private static final double TASK_PROB_MAX = 0.70;

    /** 埋め枠として採用する最低達成確率。 */
    private static final double FILL_PROB_MIN = 0.50;

    /**
     * 計測曲として採用する最低達成確率。
     * 登竜門譜面は判別力（ティアを分ける力）で選ぶので、そのままだと
     * 「実力的にまだ手が届かない譜面」が上位に来る。実測では達成確率 2% の譜面が
     * 週の目標として出てしまい、目安として機能しなかった。
     * 課題曲の帯（40%）より低くして「今週は届かないかもしれないが計測の意味はある」
     * 範囲は残しつつ、明らかな無理は落とす。
     */
    private static final double MEASURE_PROB_MIN = 0.15;

    /** 1 つの軸から採る課題曲の上限。1 軸に偏らせない。 */
    private static final int MAX_TASK_PER_AXIS = 3;

    /** 課題曲に使う弱点軸の数（基準の枠のとき）。画面に「弱点軸」として出すのもこの本数。 */
    private static final int WEAK_AXES_USED = 2;

    /**
     * 1 つの軸から供給できる課題曲の目安。課題枠がこれより多いときは弱点軸を増やす。
     *
     * 実測では、週 40 プレイ（課題 12 曲）を弱点 2 軸だけで埋めようとすると 9 曲までしか
     * 埋まらなかった。軸に属する譜面のうち達成確率 40〜70% の帯に入るものが尽きるため。
     * 枠が広いときは 3 番目・4 番目に沈んでいる軸まで手を広げる。
     */
    private static final int TASK_PER_AXIS_TARGET = 3;

    /** 直近この週数に出した譜面は再提示しない。 */
    private static final int BAN_WEEKS = 2;

    /** 持ち越しの上限週数。 */
    private static final int MAX_CARRY_WEEKS = 3;

    /** 「組み直す」の週あたり上限回数。 */
    private static final int MAX_REGENERATE = 3;

    /**
     * 週プレイ数に応じた枠の大きさ。
     *
     * 週 {@value #DEFAULT_WEEKLY_PLAYS} プレイの基準サイズ（計測 2 / 課題 6 / 埋め 4）を
     * {@code weeklyPlays / 20} 倍する。想定回数が課題曲だけ 2 回なので、
     * 基準では 2×1 + 6×2 + 4×1 = 18 回ぶんとなり、週 20 プレイにほぼ収まる。
     *
     * どの枠も最低 1 曲は残す。少ないプレイ数のときに枠が丸ごと消えると
     * 「計測はしないが課題だけ出る」といった片寄った献立になってしまうため。
     *
     * @param weeklyPlays ユーザー設定の週プレイ数
     * @return {@code [計測, 課題, 埋め]} の曲数
     */
    static int[] slotsFor(int weeklyPlays) {
        double ratio = weeklyPlays / (double) DEFAULT_WEEKLY_PLAYS;
        return new int[]{
                Math.max(1, (int) Math.round(BASE_SLOT_MEASURE * ratio)),
                Math.max(1, (int) Math.round(BASE_SLOT_TASK * ratio)),
                Math.max(1, (int) Math.round(BASE_SLOT_FILL * ratio)),
        };
    }

    /**
     * 週プレイ数を受け付け可能な範囲に丸める。
     * 範囲外は端に寄せるだけで、クレジット単位への丸めはしない
     * （4 の倍数でない値を送られても、その曲数として素直に扱う）。
     */
    static int clampWeeklyPlays(Integer weeklyPlays) {
        if (weeklyPlays == null) return DEFAULT_WEEKLY_PLAYS;
        return Math.max(MIN_WEEKLY_PLAYS, Math.min(MAX_WEEKLY_PLAYS, weeklyPlays));
    }

    /**
     * logit 空間の予測標準偏差のクランプ。
     * {@link FillRecommendationService} の MIN/MAX_SIGMA_LOGIT と同じ値。
     * 下限は「参照が偶然揃っただけで確率 100%」を防ぎ、上限は参照の乏しい高難度譜面に
     * 期待値が張り付くのを防ぐ。
     */
    private static final double MIN_SIGMA_LOGIT = 0.04;
    private static final double MAX_SIGMA_LOGIT = 1.2;

    /** BEAT-PT のボーナス段差。目標ボーダーの候補でもある。 */
    private static final double[] BORDER_RATES = {77.77, 88.88, 94.44};
    private static final String[] BORDER_LABELS = {"AA", "AAA", "MAX-"};

    /** クリアタイプ → 数値ランク。{@code SkillTreeService.CLEAR_RANK} と同じ並び。 */
    private static final Map<String, Integer> CLEAR_RANK = Map.of(
            "FAILED", 0, "ASSIST CLEAR", 1, "EASY CLEAR", 2,
            "CLEAR", 3, "HARD CLEAR", 4, "EX HARD CLEAR", 5, "FULLCOMBO CLEAR", 6);

    private final PracticeMenuRepository menuRepository;
    private final ScoreRepository scoreRepository;
    private final PairRegressionService pairRegressionService;
    private final FillRecommendationService fillRecommendationService;
    private final ScoreRecalculationService scoreRecalculationService;
    private final BeatPtCalculator beatPtCalculator;
    private final TierBenchmarkCacheService tierBenchmarkCacheService;
    private final TendencyAxisService tendencyAxisService;
    private final UserTrainingSettingsRepository settingsRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PracticeMenuService(PracticeMenuRepository menuRepository,
                               ScoreRepository scoreRepository,
                               PairRegressionService pairRegressionService,
                               FillRecommendationService fillRecommendationService,
                               ScoreRecalculationService scoreRecalculationService,
                               BeatPtCalculator beatPtCalculator,
                               TierBenchmarkCacheService tierBenchmarkCacheService,
                               TendencyAxisService tendencyAxisService,
                               UserTrainingSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
        this.menuRepository = menuRepository;
        this.scoreRepository = scoreRepository;
        this.pairRegressionService = pairRegressionService;
        this.fillRecommendationService = fillRecommendationService;
        this.scoreRecalculationService = scoreRecalculationService;
        this.beatPtCalculator = beatPtCalculator;
        this.tierBenchmarkCacheService = tierBenchmarkCacheService;
        this.tendencyAxisService = tendencyAxisService;
    }

    // ── 公開 API ────────────────────────────────────────────────────────

    /**
     * 【メソッドの役割】 今週のメニューを返す。無ければ生成する。
     *
     * 既存メニューがある場合も、開いた時点で採点し直して状態を最新にする。
     * 併せて、締め忘れている過去週があれば締める。
     *
     * @param user 対象ユーザー
     * @return API レスポンス相当の Map
     */
    @Transactional
    public Map<String, Object> getOrCreateMenu(User user) {
        LocalDate weekStart = currentWeekStart();
        closeStaleMenus(user, weekStart);

        PracticeMenu menu = menuRepository.findWithItems(user, weekStart).orElse(null);
        UserState state = loadUserState(user);

        if (menu == null) {
            menu = buildAndSave(user, weekStart, state, null);
        } else {
            grade(menu, state);
            menuRepository.save(menu);
        }
        return toResponse(menu, state);
    }

    /**
     * 【メソッドの役割】 今週のメニューを組み直す。週 {@value #MAX_REGENERATE} 回まで。
     *
     * @return 組み直し後のメニュー。上限に達している場合は {@code error} を含む Map
     */
    @Transactional
    public Map<String, Object> regenerate(User user) {
        LocalDate weekStart = currentWeekStart();
        PracticeMenu existing = menuRepository.findWithItems(user, weekStart).orElse(null);
        if (existing != null && existing.getRegenerateCount() >= MAX_REGENERATE) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "今週の組み直しは上限（" + MAX_REGENERATE + " 回）に達しています");
            return err;
        }
        UserState state = loadUserState(user);
        PracticeMenu menu = buildAndSave(user, weekStart, state, existing);
        return toResponse(menu, state);
    }

    /**
     * 【メソッドの役割】 弱点レーダーを返す。
     *
     * 8 軸それぞれについて「実測平均レート」と「予測平均レート」を返す。
     * 残差（実測 − 予測）が負に大きい軸ほど弱点。
     */
    public Map<String, Object> getRadar(User user) {
        UserState state = loadUserState(user);
        List<Map<String, Object>> axes = new ArrayList<>();
        for (String axis : TendencyAxisService.AXES) {
            TendencyAxisService.AxisScore s = state.axisScores.get(axis);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("axis", axis);
            if (s == null) {
                // 判定に足りる譜面数が無い軸。レーダーではスポークを欠く。
                m.put("available", false);
                m.put("chartCount", 0);
            } else {
                m.put("available", true);
                m.put("actualRate", s.actualRate());
                m.put("predictedRate", s.actualRate() - s.residual());
                m.put("residual", s.residual());
                m.put("chartCount", s.chartCount());
            }
            axes.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("axes", axes);
        result.put("weakAxes", state.weakAxes);
        result.put("currentTier", state.currentTier);
        result.put("nextTier", state.nextTier == null ? null : state.nextTier.name());
        result.put("referenceChartCount", state.referenceCount);
        return result;
    }

    /**
     * 【メソッドの役割】 直前に締めた週の振り返りを返す。
     *
     * @param weekStart 対象週の月曜。null なら直近の締め済み週
     */
    @Transactional
    public Map<String, Object> getReview(User user, LocalDate weekStart) {
        LocalDate thisWeek = currentWeekStart();
        closeStaleMenus(user, thisWeek);

        List<PracticeMenu> past = menuRepository.findRecentBefore(user, thisWeek);
        PracticeMenu target = null;
        for (PracticeMenu m : past) {
            if (weekStart == null || m.getWeekStart().equals(weekStart)) {
                target = m;
                break;
            }
        }
        if (target == null) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("weekStart", null);
            empty.put("items", List.of());
            return empty;
        }

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        grouped.put("achieved", new ArrayList<>());
        grouped.put("progressed", new ArrayList<>());
        grouped.put("untouched", new ArrayList<>());
        for (PracticeMenuItem item : target.getItems()) {
            String bucket = switch (item.getStatus()) {
                case "ACHIEVED" -> "achieved";
                case "PROGRESSED" -> "progressed";
                default -> "untouched";
            };
            grouped.get(bucket).add(itemToMap(item));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("weekStart", target.getWeekStart().toString());
        result.put("targetTier", target.getTargetTier());
        result.put("achieved", grouped.get("achieved"));
        result.put("progressed", grouped.get("progressed"));
        result.put("untouched", grouped.get("untouched"));
        int total = target.getItems().size();
        int done = grouped.get("achieved").size() + grouped.get("progressed").size();
        result.put("total", total);
        result.put("completionRate", total > 0 ? done / (double) total : 0.0);
        return result;
    }

    /**
     * 【メソッドの役割】 週プレイ数の設定を更新し、その場でメニューを組み直して返す。
     *
     * 枠の大きさが変わるので、設定だけ保存して古い曲数のメニューを残すと
     * 「設定したのに変わらない」状態になる。保存と同時に組み直す。
     *
     * <p>この組み直しは {@code regenerateCount} を増やさない。曲の引き直し（＝運の再抽選）ではなく
     * 献立の量を変える操作なので、「組み直す」の回数制限とは別物として扱う。
     *
     * @param weeklyPlays 週あたりの想定プレイ数。範囲外は丸める
     * @return 組み直し後のメニュー
     */
    @Transactional
    public Map<String, Object> updateWeeklyPlays(User user, Integer weeklyPlays) {
        int value = clampWeeklyPlays(weeklyPlays);
        UserTrainingSettings settings = settingsRepository.findById(user.getId())
                .orElseGet(() -> {
                    UserTrainingSettings s = new UserTrainingSettings();
                    s.setUserId(user.getId());
                    return s;
                });
        settings.setWeeklyPlays(value);
        settingsRepository.save(settings);

        LocalDate weekStart = currentWeekStart();
        PracticeMenu existing = menuRepository.findWithItems(user, weekStart).orElse(null);
        UserState state = loadUserState(user);
        // 組み直し回数は据え置く。設定変更でリセット扱いにならないよう、増やしてから戻すのではなく
        // buildAndSave に渡す前後で保持する。
        int keepRegenerateCount = existing == null ? 0 : existing.getRegenerateCount();
        PracticeMenu menu = buildAndSave(user, weekStart, state, existing);
        if (menu.getRegenerateCount() != keepRegenerateCount) {
            menu.setRegenerateCount(keepRegenerateCount);
            menu = menuRepository.save(menu);
        }
        return toResponse(menu, state);
    }

    /** 設定行が無いユーザーは既定値。範囲外の値が入っていても丸めて使う。 */
    private int weeklyPlaysOf(User user) {
        return settingsRepository.findById(user.getId())
                .map(s -> clampWeeklyPlays(s.getWeeklyPlays()))
                .orElse(DEFAULT_WEEKLY_PLAYS);
    }

    /** 【メソッドの役割】 その日が属する週の月曜日（JST）を返す。 */
    public static LocalDate currentWeekStart() {
        return LocalDate.now(JST).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    // ── メニュー生成 ────────────────────────────────────────────────────

    /**
     * 【メソッドの役割】 メニューを組んで保存する。
     *
     * @param existing 既存メニュー。非 null なら中身を差し替えて組み直し回数を増やす
     */
    private PracticeMenu buildAndSave(User user, LocalDate weekStart, UserState state, PracticeMenu existing) {
        PracticeMenu menu = existing;
        if (menu == null) {
            menu = new PracticeMenu();
            menu.setUser(user);
            menu.setWeekStart(weekStart);
            menu.setGeneratedAt(LocalDateTime.now());
        } else {
            menu.setRegenerateCount(menu.getRegenerateCount() + 1);
            menu.getItems().clear();
        }
        menu.setFromTotalBeatPt(state.totalBeatPt);
        menu.setFromTier(state.currentTier);
        menu.setTargetTier(state.nextTier == null ? null : state.nextTier.name());
        menu.setStatus("OPEN");

        List<PracticeMenuItem> items = selectItems(user, weekStart, state, menu);
        menu.getItems().addAll(items);

        // 生成直後にも一度採点する。既に目標を超えている譜面があれば最初から達成として出す。
        grade(menu, state);
        return menuRepository.save(menu);
    }

    /**
     * 【メソッドの役割】 計測・課題・埋めの 3 枠を埋めて項目リストを作る。
     */
    private List<PracticeMenuItem> selectItems(User user, LocalDate weekStart,
                                               UserState state, PracticeMenu menu) {
        History history = loadHistory(user, weekStart);
        Set<String> used = new HashSet<>();
        Set<String> usedTitles = new HashSet<>();
        List<PracticeMenuItem> result = new ArrayList<>();

        // 枠の大きさはユーザーの週プレイ数で伸縮する。
        int[] slots = slotsFor(state.weeklyPlays);
        result.addAll(pickMeasure(state, history, used, usedTitles, menu, slots[0]));
        result.addAll(pickTask(state, history, used, usedTitles, menu, slots[1]));
        result.addAll(pickFill(state, history, used, usedTitles, menu, slots[2]));
        return result;
    }

    /**
     * 【メソッドの役割】 計測枠。次のティアを分ける登竜門譜面のうち、まだ到達基準に届いていないもの。
     */
    private List<PracticeMenuItem> pickMeasure(UserState state, History history,
                                               Set<String> used, Set<String> usedTitles,
                                               PracticeMenu menu, int slotCount) {
        List<PracticeMenuItem> picked = new ArrayList<>();
        if (state.currentTier == null) return picked;

        for (TierBenchmarkCacheService.Gate gate : tierBenchmarkCacheService.getGates(state.currentTier)) {
            if (picked.size() >= slotCount) break;
            String key = gate.title() + "\0" + gate.difficultyName();
            if (used.contains(key) || usedTitles.contains(gate.title())) continue;
            if (history.banned.contains(key)) continue;

            Integer maxScore = state.maxScores.get(key);
            if (maxScore == null || maxScore <= 0) continue;
            int current = state.bestScore(key);
            double currentRate = current * 100.0 / maxScore;
            // すでに上位ティアの平均を超えている譜面は「計測」の意味がない。
            if (currentRate >= gate.standardRate()) continue;

            int target = borderScore(maxScore, gate.borderRate());
            if (target <= current) continue;

            // 予測が立つ譜面については、届く見込みが無いものを落とす。
            // 予測が立たない譜面（参照不足）は判定できないので、計測の意味を優先して残す。
            Prediction pred = state.predictions.get(key);
            Double probability = pred == null ? null : probabilityOf(target, maxScore, pred);
            if (probability != null && probability < MEASURE_PROB_MIN) continue;

            PracticeMenuItem item = newItem(menu, gate.title(), gate.difficultyName(), state);
            item.setRole("MEASURE");
            item.setTargetType("BORDER");
            item.setTargetLabel(gate.borderLabel());
            item.setTargetValue(target);
            item.setPlannedPlays(PLAYS_MEASURE);
            item.setAchieveProbability(probability);
            item.setSortOrder(picked.size());
            picked.add(item);
            used.add(key);
            usedTitles.add(gate.title());
        }
        return picked;
    }

    /**
     * 【メソッドの役割】 課題枠。弱点軸の譜面から、達成確率 40〜70% のものを選ぶ。
     *
     * 前週に「前進」だった譜面を先に持ち越し、残りを軸から補充する。
     */
    private List<PracticeMenuItem> pickTask(UserState state, History history,
                                            Set<String> used, Set<String> usedTitles,
                                            PracticeMenu menu, int slotCount) {
        List<PracticeMenuItem> picked = new ArrayList<>();

        // 1) 前週の「前進」を持ち越す（3 週まで）。
        for (Map.Entry<String, Integer> carried : history.carried.entrySet()) {
            if (picked.size() >= slotCount) break;
            String key = carried.getKey();
            if (used.contains(key)) continue;
            String[] parts = key.split("\0", 2);
            if (parts.length < 2) continue;
            if (usedTitles.contains(parts[0])) continue;

            Candidate c = evaluate(key, parts[0], parts[1], state);
            if (c == null || c.target <= c.currentScore) continue;

            PracticeMenuItem item = newItem(menu, parts[0], parts[1], state);
            item.setRole("TASK");
            item.setAxis(history.carriedAxis.get(key));
            item.setTargetType("BORDER");
            item.setTargetLabel(c.targetLabel);
            item.setTargetValue(c.target);
            item.setPlannedPlays(PLAYS_TASK);
            item.setAchieveProbability(c.probability);
            item.setCarriedWeeks(carried.getValue() + 1);
            item.setSortOrder(picked.size());
            picked.add(item);
            used.add(key);
            usedTitles.add(parts[0]);
        }

        // 2) 弱点軸から補充する。軸ごとに上限を設けて偏りを防ぐ。
        // 枠が広いときは使う軸の数を増やす。1 軸から採れる譜面（達成確率 40〜70% の帯）は
        // 限りがあるので、軸を増やさないと枠が埋まらない。
        int wantAxes = Math.max(WEAK_AXES_USED,
                (int) Math.ceil(slotCount / (double) TASK_PER_AXIS_TARGET));
        List<String> axes = state.weakAxes.subList(0, Math.min(wantAxes, state.weakAxes.size()));

        // 1 軸あたりの上限は「実際に使える軸の数」で割る。軸は最大 8 本しか無いので、
        // 欲しい軸数で割ると枠が広いときに上限が小さいままになり、枠を埋めきれない。
        int maxPerAxis = axes.isEmpty() ? 0
                : Math.max(MAX_TASK_PER_AXIS, (int) Math.ceil(slotCount / (double) axes.size()));

        for (String axis : axes) {
            if (picked.size() >= slotCount) break;
            int fromThisAxis = 0;

            List<Candidate> candidates = new ArrayList<>();
            for (String key : tendencyAxisService.chartsOf(axis)) {
                if (used.contains(key)) continue;
                if (history.banned.contains(key)) continue;
                String[] parts = key.split("\0", 2);
                if (parts.length < 2 || usedTitles.contains(parts[0])) continue;

                Candidate c = evaluate(key, parts[0], parts[1], state);
                if (c == null) continue;
                if (c.probability < TASK_PROB_MIN || c.probability > TASK_PROB_MAX) continue;
                candidates.add(c);
            }
            // 同じ確率帯なら、期待獲得 pt が大きい方が練習の見返りも大きい。
            candidates.sort(Comparator.comparingDouble((Candidate c) -> c.expectedGain).reversed());

            for (Candidate c : candidates) {
                if (picked.size() >= slotCount || fromThisAxis >= maxPerAxis) break;
                if (usedTitles.contains(c.title)) continue;

                PracticeMenuItem item = newItem(menu, c.title, c.difficultyName, state);
                item.setRole("TASK");
                item.setAxis(axis);
                item.setTargetType("BORDER");
                item.setTargetLabel(c.targetLabel);
                item.setTargetValue(c.target);
                item.setPlannedPlays(PLAYS_TASK);
                item.setAchieveProbability(c.probability);
                item.setExpectedGain(c.expectedGain);
                item.setSortOrder(picked.size());
                picked.add(item);
                used.add(c.key);
                usedTitles.add(c.title);
                fromThisAxis++;
            }
        }
        return picked;
    }

    /**
     * 【メソッドの役割】 埋め枠。期待獲得 BEAT-PT の大きい順。
     *
     * 期待値の定義と数値積分は {@link FillRecommendationService} のものをそのまま使うが、
     * 候補の現在スコア・未プレイ判定は歴代スコア基準で置き換えてある。
     */
    private List<PracticeMenuItem> pickFill(UserState state, History history,
                                            Set<String> used, Set<String> usedTitles,
                                            PracticeMenu menu, int slotCount) {
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, Prediction> e : state.predictions.entrySet()) {
            String key = e.getKey();
            if (used.contains(key) || history.banned.contains(key)) continue;
            String[] parts = key.split("\0", 2);
            if (parts.length < 2 || usedTitles.contains(parts[0])) continue;

            Candidate c = evaluate(key, parts[0], parts[1], state);
            if (c == null) continue;
            if (c.probability < FILL_PROB_MIN) continue;
            if (c.expectedGain <= 0) continue;
            candidates.add(c);
        }
        candidates.sort(Comparator.comparingDouble((Candidate c) -> c.expectedGain).reversed());

        List<PracticeMenuItem> picked = new ArrayList<>();
        for (Candidate c : candidates) {
            if (picked.size() >= slotCount) break;
            if (usedTitles.contains(c.title)) continue;

            PracticeMenuItem item = newItem(menu, c.title, c.difficultyName, state);
            item.setRole("FILL");
            item.setTargetType("BORDER");
            item.setTargetLabel(c.targetLabel);
            item.setTargetValue(c.target);
            item.setPlannedPlays(PLAYS_FILL);
            item.setAchieveProbability(c.probability);
            item.setExpectedGain(c.expectedGain);
            item.setSortOrder(picked.size());
            picked.add(item);
            used.add(c.key);
            usedTitles.add(c.title);
        }
        return picked;
    }

    /** 新しい項目に、譜面キーと提示時点のスナップショットを詰めた状態で作る。 */
    private PracticeMenuItem newItem(PracticeMenu menu, String title, String diffName, UserState state) {
        String key = title + "\0" + diffName;
        PracticeMenuItem item = new PracticeMenuItem();
        item.setMenu(menu);
        item.setTitle(title);
        item.setDifficultyName(diffName);
        item.setInformalRank(state.informalRanks.get(title + "_" + diffName));
        item.setBaselineScore(state.bestScore(key));
        item.setBaselineClear(state.bestClear.get(key));
        item.setStatus("PENDING");
        return item;
    }

    // ── 候補評価 ────────────────────────────────────────────────────────

    /** 1 譜面ぶんの評価結果。 */
    private static class Candidate {
        String key;
        String title;
        String difficultyName;
        int currentScore;
        int target;
        String targetLabel;
        double probability;
        double expectedGain;
    }

    /**
     * 【メソッドの役割】 譜面 1 件について、目標ボーダー・達成確率・期待獲得 pt を求める。
     *
     * 目標は「現在レートより上で、予測中央値までに届く一番上のボーダー」。
     * 届くボーダーが無い譜面は候補にしない（目標が「予測どおりのスコア」では練習の的にならない）。
     *
     * @return 評価結果。予測が無い / 難易度表に無い / 目標が立たない場合は null
     */
    private Candidate evaluate(String key, String title, String diffName, UserState state) {
        Prediction pred = state.predictions.get(key);
        if (pred == null) return null;

        Integer maxScore = state.maxScores.get(key);
        if (maxScore == null || maxScore <= 0) return null;

        String informalRank = state.informalRanks.get(title + "_" + diffName);
        if (informalRank == null || beatPtCalculator.getWeight(informalRank) == 0) return null;

        int current = state.bestScore(key);
        // 予測の上限はコミュニティ実測最高。誰も出していないスコアは目標にしない。
        Integer communityMax = state.communityMax.get(key);
        int scoreCap = (communityMax != null && communityMax > 0 && communityMax < maxScore)
                ? communityMax : maxScore;
        if (current >= scoreCap) return null;

        double predictedScore = Math.min(scoreCap,
                PairRegressionService.logitToScoreRate(pred.mu) * maxScore);
        double currentRate = current * 100.0 / maxScore;

        // 現在レートより上のボーダーのうち、予測中央値で届く一番上のものを目標にする。
        int target = -1;
        String label = null;
        for (int i = BORDER_RATES.length - 1; i >= 0; i--) {
            if (currentRate > BORDER_RATES[i]) continue;
            int need = borderScore(maxScore, BORDER_RATES[i]);
            if (need > scoreCap || need > predictedScore) continue;
            target = need;
            label = BORDER_LABELS[i];
            break;
        }
        if (target < 0) return null;

        double currentPt = state.myPoints.getOrDefault(key, 0.0);
        boolean inTop100 = currentPt > 0 && currentPt >= state.top100Threshold;
        double baseline = inTop100 ? currentPt : state.top100Threshold;

        Candidate c = new Candidate();
        c.key = key;
        c.title = title;
        c.difficultyName = diffName;
        c.currentScore = current;
        c.target = target;
        c.targetLabel = label;
        c.probability = probabilityOf(target, maxScore, pred);
        // 期待値の数値積分は既存のコスパ埋めレコメンドの実装をそのまま使う。
        c.expectedGain = fillRecommendationService.expectedGain(
                pred.mu, pred.sigma, maxScore, scoreCap, current, informalRank, baseline);
        return c;
    }

    /** P(S ≥ score) を返す。 */
    private double probabilityOf(int score, int maxScore, Prediction pred) {
        return fillRecommendationService.tailProbability(score, maxScore, pred.mu, pred.sigma);
    }

    /** ボーダーレート（%）を「超える」のに必要な最小スコア。段差は超えて初めて付くので +1。 */
    private static int borderScore(int maxScore, double borderRate) {
        return (int) Math.ceil(maxScore * borderRate / 100.0) + 1;
    }

    // ── ユーザー状態の読み込み ──────────────────────────────────────────

    /** メニュー生成・採点に必要なユーザー 1 人ぶんの状態。 */
    private static class UserState {
        /** 譜面キー → 歴代ベスト EX スコア（現行 + 過去作、INFINITAS 除く）。 */
        Map<String, Integer> lifetimeBest = new HashMap<>();
        /** 譜面キー → 現行作のベストクリアタイプ。過去作はランプを持ち込まない。 */
        Map<String, String> bestClear = new HashMap<>();
        /** 譜面キー → 現行作スコアによる BEAT-PT。ティア判定はここだけを見る。 */
        Map<String, Double> myPoints = new HashMap<>();
        /** 譜面キー → maxScore（notes × 2）。 */
        Map<String, Integer> maxScores = new HashMap<>();
        /** "title_diffName" → 非公式難易度。 */
        Map<String, String> informalRanks = Collections.emptyMap();
        /** 譜面キー → コミュニティ実測最高スコア。 */
        Map<String, Integer> communityMax = Collections.emptyMap();
        /** 譜面キー → 能力推定。 */
        Map<String, Prediction> predictions = new HashMap<>();
        /** 軸名 → 残差ベースの判定。 */
        Map<String, TendencyAxisService.AxisScore> axisScores = new HashMap<>();
        /** 弱点軸（残差の小さい順に {@value #WEAK_AXES_USED} 本）。 */
        List<String> weakAxes = new ArrayList<>();

        double totalBeatPt;
        double top100Threshold;
        String currentTier;
        BeatTierScale.Tier nextTier;
        int referenceCount;
        /** ユーザー設定の週プレイ数。枠の大きさをこれで伸縮する。 */
        int weeklyPlays = DEFAULT_WEEKLY_PLAYS;

        int bestScore(String key) {
            return lifetimeBest.getOrDefault(key, 0);
        }
    }

    /** 1 譜面ぶんの能力推定（logit 空間）。 */
    private static class Prediction {
        double mu;
        double sigma;
        int support;
        String accuracy;
    }

    /**
     * 【メソッドの役割】 ユーザーの現状を一括で読み込む。
     *
     * 手順:
     *  1. 歴代スコア（現行 + 過去作、INFINITAS 除く）を集約して自己ベストを作る
     *  2. 現行作スコアから BEAT-PT と TOP100 ラインを出し、ティアを決める
     *  3. 歴代ベストを参照譜面として、全譜面の能力推定（μ, σ）を作る
     *  4. 実測と予測の差を軸ごとに畳んで弱点軸を決める
     */
    private UserState loadUserState(User user) {
        pairRegressionService.ensureBuilt();
        // 登竜門譜面はティア別ベンチマークが要る。定期実行が止まっている環境（prod-db）でも
        // 計測枠が空にならないよう、必要になった時点で作らせる。
        tierBenchmarkCacheService.ensureBuilt();

        UserState state = new UserState();
        state.weeklyPlays = weeklyPlaysOf(user);
        state.informalRanks = scoreRecalculationService.loadInformalRanks();
        state.communityMax = pairRegressionService.getCommunityMaxByKey();
        Map<String, Integer> notesByKey = pairRegressionService.getNotesByKey();
        Map<String, Integer> songMaxScores = scoreRecalculationService.loadSongMaxScores();

        // 曲マスタのキーは "title_難易度コード" なので、譜面キー "title\0難易度名" に引き直して
        // 全譜面ぶん先に持っておく。登竜門譜面のように「未プレイかつ予測も無い」譜面でも
        // maxScore は引ける必要があるため、都度解決ではなく一括で構える。
        for (Map.Entry<String, Integer> e : songMaxScores.entrySet()) {
            String entryKey = e.getKey();
            int sep = entryKey.lastIndexOf('_');
            if (sep < 0) continue;
            String title = entryKey.substring(0, sep);
            String diffName = switch (entryKey.substring(sep + 1)) {
                case "4" -> "ANOTHER";
                case "10" -> "LEGGENDARIA";
                default -> null;
            };
            if (diffName == null || e.getValue() == null || e.getValue() <= 0) continue;
            state.maxScores.put(title + "\0" + diffName, e.getValue());
        }

        // 1) 歴代スコア。同じ譜面が現行・過去で複数行来るので EX の最大に畳む。
        for (Map<String, Object> row : scoreRepository.findUserLifetimeAnotherLeggScores(user.getId())) {
            String title = (String) row.get("title");
            String diff = (String) row.get("difficultyName");
            if (title == null || diff == null) continue;
            String key = title + "\0" + diff;
            int score = intOf(row.get("score"));
            state.lifetimeBest.merge(key, score, Math::max);

            boolean fromPast = Boolean.TRUE.equals(row.get("fromPast"));
            if (!fromPast) {
                String clear = (String) row.get("clearType");
                if (clear != null) {
                    state.bestClear.merge(key, clear, (a, b) ->
                            CLEAR_RANK.getOrDefault(b, -1) > CLEAR_RANK.getOrDefault(a, -1) ? b : a);
                }
            }
        }

        // maxScore は難易度コード付きのキーで持たれているので、譜面キーに引き直す。
        for (String key : state.lifetimeBest.keySet()) {
            putMaxScore(state, key, songMaxScores, notesByKey);
        }

        // 2) BEAT-PT・ティア。ランキングと同じ「現行作のみ」で計算する。
        for (Map<String, Object> row : scoreRepository.findUserAnotherLeggScores(user.getId())) {
            String title = (String) row.get("title");
            String diff = (String) row.get("difficultyName");
            if (title == null || diff == null) continue;
            String key = title + "\0" + diff;
            Integer maxScore = songMaxScores.get(title + "_" + difficultyCode(diff));
            if (maxScore == null || maxScore <= 0) continue;
            String informalRank = state.informalRanks.get(title + "_" + diff);
            double pt = beatPtCalculator.calculatePoints(intOf(row.get("score")) * 100.0 / maxScore, informalRank);
            if (pt <= 0) continue;
            state.myPoints.merge(key, pt, Math::max);
        }
        List<Double> ptList = new ArrayList<>(state.myPoints.values());
        ptList.sort(Collections.reverseOrder());
        double total = 0;
        for (int i = 0; i < Math.min(TOP_CHART_LIMIT, ptList.size()); i++) total += ptList.get(i);
        state.totalBeatPt = total;
        state.top100Threshold = ptList.size() >= TOP_CHART_LIMIT ? ptList.get(TOP_CHART_LIMIT - 1) : 0.0;
        state.currentTier = BeatTierScale.tierOf(total);
        state.nextTier = BeatTierScale.nextTierOf(total);

        // 3) 能力推定。参照は「歴代ベストで A 以上を出している譜面」。
        Map<String, Integer> refCharts = new HashMap<>();
        for (Map.Entry<String, Integer> e : state.lifetimeBest.entrySet()) {
            Integer notes = notesByKey.get(e.getKey());
            if (notes == null || notes <= 0) continue;
            if (e.getValue() < notes * 2.0 * PairRegressionService.A_GRADE_RATE) continue;
            refCharts.put(e.getKey(), notes);
        }
        state.referenceCount = refCharts.size();
        buildPredictions(state, refCharts, notesByKey, songMaxScores);

        // 4) 弱点軸。予測が立つ譜面のうち、実際にプレイ済みのものだけで残差を取る。
        Map<String, Double> actualRates = new HashMap<>();
        Map<String, Double> predictedRates = new HashMap<>();
        for (Map.Entry<String, Prediction> e : state.predictions.entrySet()) {
            String key = e.getKey();
            Integer maxScore = state.maxScores.get(key);
            Integer best = state.lifetimeBest.get(key);
            if (maxScore == null || maxScore <= 0 || best == null || best <= 0) continue;
            actualRates.put(key, best * 100.0 / maxScore);
            predictedRates.put(key, PairRegressionService.logitToScoreRate(e.getValue().mu) * 100.0);
        }
        state.axisScores = tendencyAxisService.computeAxisScores(actualRates, predictedRates);
        // 判定できた軸を「沈んでいる順」に全部持つ。何本を課題曲に使うかは枠の大きさで決まる
        // （pickTask 側）ので、ここでは切り詰めない。
        state.weakAxes = state.axisScores.values().stream()
                .sorted(Comparator.comparingDouble(TendencyAxisService.AxisScore::residual))
                .map(TendencyAxisService.AxisScore::axis)
                .toList();

        return state;
    }

    /**
     * 【メソッドの役割】 参照譜面から全候補譜面への予測を積み上げる。
     *
     * {@link FillRecommendationService} と同じ 2 段構え（|r| ≧ 0.95 の HIGH を優先し、
     * 足りなければ 0.90 まで緩めた LOW）で、両機能の「予測が出る／出ない」の境界を揃える。
     * 参照譜面 A 側から回帰を引くので、候補 × 参照の空振りが出ない。
     */
    private void buildPredictions(UserState state, Map<String, Integer> refCharts,
                                  Map<String, Integer> notesByKey, Map<String, Integer> songMaxScores) {
        Map<String, Acc[]> accs = new HashMap<>(); // key → [high, low]

        for (Map.Entry<String, Integer> aEntry : refCharts.entrySet()) {
            String chartA = aEntry.getKey();
            Map<String, PairRegressionService.Reg> bMap = pairRegressionService.getRegressionsFrom(chartA);
            if (bMap == null) continue;
            double logitA = PairRegressionService.scoreToLogit(state.bestScore(chartA), aEntry.getValue());

            for (Map.Entry<String, PairRegressionService.Reg> bEntry : bMap.entrySet()) {
                String chartB = bEntry.getKey();
                if (chartB.equals(chartA)) continue;
                PairRegressionService.Reg reg = bEntry.getValue();
                double absR = Math.abs(reg.r);
                if (absR < PairRegressionService.FALLBACK_R) continue;

                double w = PairRegressionService.computeWeight(reg.r);
                if (w <= 0) continue;
                double predLogit = reg.slope * logitA + reg.intercept;
                double resid = reg.sdY * Math.sqrt(Math.max(0.0, 1.0 - reg.r * reg.r));

                Acc[] pair = accs.computeIfAbsent(chartB, k -> new Acc[]{new Acc(), new Acc()});
                pair[1].add(predLogit, w, resid);
                if (absR >= PairRegressionService.PRIMARY_R) pair[0].add(predLogit, w, resid);
            }
        }

        for (Map.Entry<String, Acc[]> e : accs.entrySet()) {
            Prediction p = e.getValue()[0].resolve("HIGH");
            if (p == null) p = e.getValue()[1].resolve("LOW");
            if (p == null) continue;
            state.predictions.put(e.getKey(), p);
            putMaxScore(state, e.getKey(), songMaxScores, notesByKey);
        }
    }

    /** 譜面キーの maxScore を、曲マスタ優先・回帰キャッシュの notes を予備として埋める。 */
    private void putMaxScore(UserState state, String key,
                             Map<String, Integer> songMaxScores, Map<String, Integer> notesByKey) {
        if (state.maxScores.containsKey(key)) return;
        String[] parts = key.split("\0", 2);
        if (parts.length < 2) return;
        Integer maxScore = songMaxScores.get(parts[0] + "_" + difficultyCode(parts[1]));
        if (maxScore == null || maxScore <= 0) {
            Integer notes = notesByKey.get(key);
            if (notes != null && notes > 0) maxScore = notes * 2;
        }
        if (maxScore != null && maxScore > 0) state.maxScores.put(key, maxScore);
    }

    /** HIGH / LOW それぞれの加重累積。{@link FillRecommendationService} の Acc と同じ式。 */
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

        Prediction resolve(String accuracy) {
            if (support < PairRegressionService.SUPPORT_MIN || sumW <= 0) return null;
            double mu = sumWP / sumW;
            double between = Math.max(0.0, sumWPP / sumW - mu * mu);
            double meanFactor = sumWW / (sumW * sumW);
            double resid = sumWResid / sumW;

            Prediction p = new Prediction();
            p.mu = mu;
            p.sigma = Math.max(MIN_SIGMA_LOGIT, Math.min(MAX_SIGMA_LOGIT,
                    Math.sqrt(resid * resid + between * meanFactor)));
            p.support = support;
            p.accuracy = accuracy;
            return p;
        }
    }

    // ── 履歴（禁止・持ち越し） ──────────────────────────────────────────

    /** 直近の週から引いてくる、生成時の制約。 */
    private static class History {
        /** 直近 {@value #BAN_WEEKS} 週に出した譜面キー。再提示しない。 */
        Set<String> banned = new HashSet<>();
        /** 前週に「前進」で終わった課題曲 → それまでの持ち越し週数。 */
        Map<String, Integer> carried = new LinkedHashMap<>();
        /** 持ち越し譜面の軸ラベル。 */
        Map<String, String> carriedAxis = new HashMap<>();
    }

    private History loadHistory(User user, LocalDate weekStart) {
        History h = new History();
        List<PracticeMenu> past = menuRepository.findRecentBefore(user, weekStart);
        for (int i = 0; i < past.size() && i < BAN_WEEKS; i++) {
            PracticeMenu m = past.get(i);
            for (PracticeMenuItem item : m.getItems()) {
                String key = item.getTitle() + "\0" + item.getDifficultyName();
                h.banned.add(key);
                // 直前の週の「前進」だけを持ち越す。上限に達したものは打ち切る。
                if (i == 0 && "PROGRESSED".equals(item.getStatus())
                        && "TASK".equals(item.getRole())
                        && item.getCarriedWeeks() < MAX_CARRY_WEEKS) {
                    h.carried.put(key, item.getCarriedWeeks());
                    if (item.getAxis() != null) h.carriedAxis.put(key, item.getAxis());
                }
            }
        }
        // 持ち越す譜面は「再提示しない」対象から外す。
        h.banned.removeAll(h.carried.keySet());
        return h;
    }

    // ── 採点 ────────────────────────────────────────────────────────────

    /**
     * 【メソッドの役割】 メニューの各項目を、今の歴代ベストと突き合わせて採点する。
     *
     * 目標到達なら ACHIEVED、提示時点より伸びていれば PROGRESSED、
     * どちらでもなければ PENDING のまま（週締め時に UNTOUCHED へ落ちる）。
     */
    private void grade(PracticeMenu menu, UserState state) {
        for (PracticeMenuItem item : menu.getItems()) {
            if ("REPLACED".equals(item.getStatus())) continue;
            String key = item.getTitle() + "\0" + item.getDifficultyName();
            int best = state.bestScore(key);
            String clear = state.bestClear.get(key);
            item.setResultScore(best);
            item.setResultClear(clear);

            int baseline = item.getBaselineScore() == null ? 0 : item.getBaselineScore();
            boolean achieved;
            if ("LAMP".equals(item.getTargetType())) {
                int need = item.getTargetValue() == null ? Integer.MAX_VALUE : item.getTargetValue();
                achieved = CLEAR_RANK.getOrDefault(clear, -1) >= need;
            } else {
                int need = item.getTargetValue() == null ? Integer.MAX_VALUE : item.getTargetValue();
                achieved = best >= need;
            }

            if (achieved) {
                item.setStatus("ACHIEVED");
            } else if (best > baseline) {
                item.setStatus("PROGRESSED");
            } else if (!"CLOSED".equals(menu.getStatus())) {
                item.setStatus("PENDING");
            }
        }
    }

    /**
     * 【メソッドの役割】 今週より前の OPEN なメニューを締める。
     *
     * 締めでは未更新の項目を UNTOUCHED にし、集計を {@code summaryJson} に書く。
     * 締めた後に採点し直すことはない（週の結果は確定させる）。
     */
    private void closeStaleMenus(User user, LocalDate currentWeek) {
        List<PracticeMenu> past = menuRepository.findRecentBefore(user, currentWeek);
        UserState state = null;
        for (PracticeMenu m : past) {
            if (!"OPEN".equals(m.getStatus())) continue;
            if (state == null) state = loadUserState(user);
            grade(m, state);
            int achieved = 0, progressed = 0, untouched = 0;
            for (PracticeMenuItem item : m.getItems()) {
                switch (item.getStatus()) {
                    case "ACHIEVED" -> achieved++;
                    case "PROGRESSED" -> progressed++;
                    default -> {
                        item.setStatus("UNTOUCHED");
                        untouched++;
                    }
                }
            }
            m.setStatus("CLOSED");
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("achieved", achieved);
            summary.put("progressed", progressed);
            summary.put("untouched", untouched);
            summary.put("total", m.getItems().size());
            try {
                m.setSummaryJson(objectMapper.writeValueAsString(summary));
            } catch (Exception e) {
                log.warn("Failed to serialize practice menu summary for menu {}", m.getId(), e);
            }
            menuRepository.save(m);
        }
    }

    // ── レスポンス整形 ──────────────────────────────────────────────────

    private Map<String, Object> toResponse(PracticeMenu menu, UserState state) {
        List<Map<String, Object>> items = menu.getItems().stream()
                .sorted(Comparator.comparingInt((PracticeMenuItem i) -> roleOrder(i.getRole()))
                        .thenComparingInt(PracticeMenuItem::getSortOrder))
                .map(this::itemToMap)
                .toList();

        int achieved = 0, progressed = 0;
        for (PracticeMenuItem item : menu.getItems()) {
            if ("ACHIEVED".equals(item.getStatus())) achieved++;
            else if ("PROGRESSED".equals(item.getStatus())) progressed++;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("achieved", achieved);
        summary.put("progressed", progressed);
        summary.put("untouched", menu.getItems().size() - achieved - progressed);
        summary.put("total", menu.getItems().size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("weekStart", menu.getWeekStart().toString());
        result.put("weekEnd", menu.getWeekStart().plusDays(6).toString());
        result.put("status", menu.getStatus());
        result.put("currentTier", state.currentTier);
        // 現ティアの下限も返す。ティアの幅は 500 / 1000 / 2000 pt とばらばらなので、
        // フロントで進捗バーを描くには次ティアの下限だけでは足りない。
        result.put("currentTierMinPoints", BeatTierScale.TIERS.stream()
                .filter(t -> t.name().equals(state.currentTier))
                .findFirst().map(BeatTierScale.Tier::minPoints).orElse(0.0));
        result.put("totalBeatPt", state.totalBeatPt);
        result.put("weeklyPlays", state.weeklyPlays);
        result.put("weeklyPlaysMin", MIN_WEEKLY_PLAYS);
        result.put("weeklyPlaysMax", MAX_WEEKLY_PLAYS);
        result.put("playsPerCredit", PLAYS_PER_CREDIT);
        // 想定プレイ回数の合計。設定した週プレイ数に対してどれくらいの量かを画面で示す。
        int plannedPlays = menu.getItems().stream()
                .mapToInt(i -> i.getPlannedPlays() == null ? 0 : i.getPlannedPlays()).sum();
        result.put("plannedPlays", plannedPlays);
        result.put("regenerateLeft", Math.max(0, MAX_REGENERATE - menu.getRegenerateCount()));
        if (state.nextTier != null) {
            Map<String, Object> next = new LinkedHashMap<>();
            next.put("name", state.nextTier.name());
            next.put("minPoints", state.nextTier.minPoints());
            next.put("gap", Math.max(0, state.nextTier.minPoints() - state.totalBeatPt));
            result.put("nextTier", next);
        } else {
            result.put("nextTier", null);
        }
        // 画面のサマリーに出す「弱点軸」は上位 2 本まで。実際に課題曲を採った軸は
        // 各項目の axis ラベルで分かるので、ここを枠の大きさで伸ばすと読みにくくなる。
        result.put("weakAxes", state.weakAxes.subList(0, Math.min(WEAK_AXES_USED, state.weakAxes.size())));
        result.put("referenceChartCount", state.referenceCount);
        result.put("benchmarkReady", tierBenchmarkCacheService.isReady());
        result.put("summary", summary);
        result.put("items", items);
        return result;
    }

    private Map<String, Object> itemToMap(PracticeMenuItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", item.getTitle());
        m.put("difficultyName", item.getDifficultyName());
        m.put("informalRank", item.getInformalRank());
        m.put("role", item.getRole());
        m.put("axis", item.getAxis());
        m.put("targetType", item.getTargetType());
        m.put("targetLabel", item.getTargetLabel());
        m.put("targetValue", item.getTargetValue());
        m.put("baselineScore", item.getBaselineScore());
        m.put("baselineClear", item.getBaselineClear());
        m.put("resultScore", item.getResultScore());
        m.put("resultClear", item.getResultClear());
        m.put("achieveProbability", item.getAchieveProbability());
        m.put("expectedGain", item.getExpectedGain());
        m.put("plannedPlays", item.getPlannedPlays());
        m.put("status", item.getStatus());
        m.put("carriedWeeks", item.getCarriedWeeks());
        m.put("unplayed", item.getBaselineScore() == null || item.getBaselineScore() <= 0);
        return m;
    }

    private static int roleOrder(String role) {
        return switch (role) {
            case "MEASURE" -> 0;
            case "TASK" -> 1;
            default -> 2;
        };
    }

    /** 難易度名 → song_definitions の difficulty コード。 */
    private static String difficultyCode(String difficultyName) {
        if ("ANOTHER".equals(difficultyName)) return "4";
        if ("LEGGENDARIA".equals(difficultyName)) return "10";
        return null;
    }

    private static int intOf(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }
}
