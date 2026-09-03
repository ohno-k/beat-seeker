package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.ChartTendencyProfile;
import com.beatseeker.backend.repository.ChartTendencyProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * 【Service の役割】 譜面を 8 つの「傾向軸」に振り分け、ユーザーの弱点軸を出す（設計書 §3.3 の集計 C）。
 *
 * <h3>軸の定義</h3>
 * {@link ChartTendencyProfile} の列をそのまま軸にする。
 * <pre>
 *   皿       scratchPct              階段     stairsPct + dstairsPct
 *   乱打     ranuchi                 同時押し chordPct
 *   縦連     jackPct                 ソフラン isSoflan
 *   トリル   trillPct                CN       cnNotes / notes
 * </pre>
 * 「同時押し」は元の列名では和音（chordPct）だが、プレイヤーが使う語に合わせて
 * 表示・API とも「同時押し」で統一する（ユーザー指示）。
 *
 * <h3>どの譜面がどの軸か</h3>
 * 各列を ☆11/12 の全譜面の中での百分位に直し、上位 {@link #AXIS_TOP_PERCENTILE} に
 * 入る譜面を「その軸の譜面」とする。絶対値の閾値ではなく相対順位で切るのは、
 * 列ごとに値のスケールが違う（皿率は %、乱打は整数指標）ため。
 * ソフランだけは真偽値なので、true の譜面をそのまま軸の譜面とする。
 *
 * <h3>ユーザーの弱点</h3>
 * ARCADE の伸びしろ／得意曲と同じく、ペア回帰の予測と実測の差（残差）を使う。
 * <pre>
 *   a_k(u) = mean over c ∈ 軸 k の譜面 [ 実測レート(c) − 予測レート(c) ]
 * </pre>
 * 負が大きいほど「自分の実力から期待されるより出せていない」＝弱点。
 * 軸あたり {@link #MIN_CHARTS_PER_AXIS} 譜面ぶんの実測が無い軸は判定不能として欠かす。
 *
 * <p>プロファイルは日次更新の静的データなので、{@link #CACHE_TTL_MS} の間メモリに持つ。
 */
@Service
public class TendencyAxisService {

    private static final Logger log = LoggerFactory.getLogger(TendencyAxisService.class);

    /** 軸の表示名。API・UI ともこの文字列をそのまま使う。 */
    public static final List<String> AXES = List.of(
            "皿", "乱打", "縦連", "トリル", "階段", "同時押し", "ソフラン", "CN");

    /**
     * その軸の譜面とみなす上位百分位（0.2 = 上位 20%）。
     *
     * 8 軸それぞれが全譜面の上位 40% を取ると、軸どうしの譜面集合がほとんど重なり、
     * 軸別の平均レートが横並びになって弱点が読めなくなる（実測で 94.3〜95.8% に圧縮された）。
     * 「その傾向が明確に強い譜面」だけに絞ることで軸の差を出す。
     */
    private static final double AXIS_TOP_PERCENTILE = 0.20;

    /** 弱点を判定するのに必要な、軸あたりの最小プレイ譜面数。 */
    private static final int MIN_CHARTS_PER_AXIS = 8;

    /** プロファイルのメモリ保持時間（ms）。譜面傾向は日次更新なので 30 分で十分。 */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    private final ChartTendencyProfileRepository profileRepo;

    /** 軸名 → その軸に属する譜面キー（"title\0difficultyName"）の集合。 */
    private volatile Map<String, Set<String>> chartsByAxis = Collections.emptyMap();
    /** 譜面キー → その譜面が属する軸名のリスト。課題曲に軸ラベルを付けるのに使う。 */
    private volatile Map<String, List<String>> axesByChart = Collections.emptyMap();
    private volatile long loadedAt = 0L;

    public TendencyAxisService(ChartTendencyProfileRepository profileRepo) {
        this.profileRepo = profileRepo;
    }

    /**
     * 【メソッドの役割】 指定軸に属する譜面キーの集合を返す。
     *
     * @param axis 軸名（{@link #AXES} のいずれか）
     * @return 譜面キー（"title\0difficultyName"）の集合。未知の軸なら空
     */
    public Set<String> chartsOf(String axis) {
        ensureLoaded();
        return chartsByAxis.getOrDefault(axis, Set.of());
    }

    /**
     * 【メソッドの役割】 指定譜面が属する軸名を返す（複数あり得る）。
     *
     * @param chartKey "title\0difficultyName"
     * @return 軸名のリスト。どの軸にも入らなければ空
     */
    public List<String> axesOf(String chartKey) {
        ensureLoaded();
        return axesByChart.getOrDefault(chartKey, List.of());
    }

    /**
     * 【メソッドの役割】 ユーザーの軸別スコア（残差の平均）を返す。
     *
     * @param actualRates 譜面キー → 実測スコアレート（%）
     * @param predictedRates 譜面キー → ペア回帰による予測スコアレート（%）
     * @return 軸名 → {@link AxisScore}。判定不能な軸は含まれない
     */
    public Map<String, AxisScore> computeAxisScores(Map<String, Double> actualRates,
                                                    Map<String, Double> predictedRates) {
        ensureLoaded();
        Map<String, AxisScore> result = new HashMap<>();

        for (String axis : AXES) {
            Set<String> charts = chartsByAxis.getOrDefault(axis, Set.of());
            double sumResidual = 0;
            double sumActual = 0;
            int n = 0;
            for (String key : charts) {
                Double actual = actualRates.get(key);
                Double predicted = predictedRates.get(key);
                if (actual == null || predicted == null) continue;
                sumResidual += actual - predicted;
                sumActual += actual;
                n++;
            }
            if (n < MIN_CHARTS_PER_AXIS) continue;
            result.put(axis, new AxisScore(axis, sumResidual / n, sumActual / n, n));
        }
        return result;
    }

    /**
     * 1 軸ぶんの判定結果。
     *
     * @param axis       軸名
     * @param residual   実測 − 予測 の平均（%ポイント）。負が大きいほど弱点
     * @param actualRate その軸の譜面での実測平均レート（%）。レーダー描画に使う
     * @param chartCount 判定に使った譜面数
     */
    public record AxisScore(String axis, double residual, double actualRate, int chartCount) {}

    /**
     * 【メソッドの役割】 プロファイルを読み込み、軸ごとの譜面集合を作り直す。
     * TTL 内なら何もしない。
     */
    private void ensureLoaded() {
        long now = System.currentTimeMillis();
        if (loadedAt > 0 && now - loadedAt < CACHE_TTL_MS) return;
        synchronized (this) {
            if (loadedAt > 0 && System.currentTimeMillis() - loadedAt < CACHE_TTL_MS) return;
            rebuild();
            loadedAt = System.currentTimeMillis();
        }
    }

    /**
     * 【メソッドの役割】 ☆11/12 の ANOTHER・LEGGENDARIA プロファイルから軸を構築する。
     *
     * 手順:
     *  1. 対象プロファイルを集める（notes を持ち、レベル 11/12 のもの）
     *  2. 数値軸ごとに値の降順で並べ、上位 {@link #AXIS_TOP_PERCENTILE} を採用
     *  3. ソフランだけは真偽値なので true をそのまま採用
     */
    private void rebuild() {
        List<ChartTendencyProfile> profiles = new ArrayList<>();
        for (ChartTendencyProfile p : profileRepo.findAll()) {
            // ANOTHER("4") / LEGGENDARIA("10") 以外は BEAT-PT 対象外なので見ない。
            if (!"4".equals(p.getDifficulty()) && !"10".equals(p.getDifficulty())) continue;
            if (p.getLevel() == null || (p.getLevel() != 11 && p.getLevel() != 12)) continue;
            if (p.getTitle() == null || p.getTitle().isBlank()) continue;
            profiles.add(p);
        }

        Map<String, Set<String>> byAxis = new HashMap<>();
        // 数値軸: 値が大きいほど「その傾向が強い」列を、上位百分位で切る。
        byAxis.put("皿", topPercentile(profiles, p -> nz(p.getScratchPct())));
        byAxis.put("乱打", topPercentile(profiles, p -> p.getRanuchi() == null ? 0 : p.getRanuchi()));
        byAxis.put("縦連", topPercentile(profiles, p -> nz(p.getJackPct())));
        byAxis.put("トリル", topPercentile(profiles, p -> nz(p.getTrillPct())));
        byAxis.put("階段", topPercentile(profiles, p -> nz(p.getStairsPct()) + nz(p.getDstairsPct())));
        byAxis.put("同時押し", topPercentile(profiles, p -> nz(p.getChordPct())));
        byAxis.put("CN", topPercentile(profiles, p -> {
            if (p.getCnNotes() == null || p.getNotes() == null || p.getNotes() <= 0) return 0;
            return p.getCnNotes() * 100.0 / p.getNotes();
        }));

        // ソフランは真偽値。閾値で切るものではないので true を全部採る。
        Set<String> soflan = new HashSet<>();
        for (ChartTendencyProfile p : profiles) {
            if (Boolean.TRUE.equals(p.getIsSoflan())) soflan.add(keyOf(p));
        }
        byAxis.put("ソフラン", soflan);

        // 逆引き（譜面 → 軸）を作る。課題曲に「なぜこの曲か」を表示するため。
        Map<String, List<String>> byChart = new HashMap<>();
        for (String axis : AXES) {
            for (String key : byAxis.getOrDefault(axis, Set.of())) {
                byChart.computeIfAbsent(key, k -> new ArrayList<>()).add(axis);
            }
        }

        this.chartsByAxis = Map.copyOf(byAxis);
        this.axesByChart = Map.copyOf(byChart);
        log.info("Rebuilt tendency axes from {} profiles: {}", profiles.size(),
                AXES.stream().map(a -> a + "=" + byAxis.getOrDefault(a, Set.of()).size()).toList());
    }

    /**
     * 【メソッドの役割】 指定の値取り出し関数について、上位 {@link #AXIS_TOP_PERCENTILE} の譜面キーを返す。
     * 値が 0 以下の譜面は「その傾向を持たない」ので、上位に入っても採用しない。
     */
    private Set<String> topPercentile(List<ChartTendencyProfile> profiles,
                                      ToDoubleFunction<ChartTendencyProfile> extractor) {
        List<ChartTendencyProfile> sorted = new ArrayList<>(profiles);
        sorted.sort((a, b) -> Double.compare(extractor.applyAsDouble(b), extractor.applyAsDouble(a)));
        int cut = (int) Math.round(sorted.size() * AXIS_TOP_PERCENTILE);

        Set<String> result = new HashSet<>();
        for (int i = 0; i < cut && i < sorted.size(); i++) {
            ChartTendencyProfile p = sorted.get(i);
            if (extractor.applyAsDouble(p) <= 0) break; // 以降はすべて 0 以下
            result.add(keyOf(p));
        }
        return result;
    }

    /** プロファイルから譜面キー（"title\0difficultyName"）を作る。 */
    private static String keyOf(ChartTendencyProfile p) {
        String diffName = "10".equals(p.getDifficulty()) ? "LEGGENDARIA" : "ANOTHER";
        return p.getTitle() + "\0" + diffName;
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }
}
