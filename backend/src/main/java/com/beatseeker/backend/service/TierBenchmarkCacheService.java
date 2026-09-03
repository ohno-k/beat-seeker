package com.beatseeker.backend.service;

import com.beatseeker.backend.repository.ScoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【Service の役割】 練習メニューの「登竜門譜面」と「到達基準」を作る集計キャッシュ（設計書 §3.1 の集計 A）。
 *
 * <h3>何を出すのか</h3>
 * 譜面 × ティアのボーダー到達率から、隣接ティア T → T+1 を最もよく分ける譜面を選ぶ。
 * <pre>
 *   D(c, T→T+1) = max over b ∈ {AA, AAA, MAX-} [ P(b|c,T+1) − P(b|c,T) ]
 *                 ただし 0.5 ≤ P(b|c,T+1) ≤ 0.9
 * </pre>
 * 上位ティアでほぼ全員が出しているボーダーも、ほとんど誰も出していないボーダーも
 * 「ティアを分ける力」を持たないので、上側の到達率が 50〜90% の帯に入るものだけを見る。
 *
 * 到達基準（その譜面で「次のティアの人が普通どれくらい出しているか」）は
 * 上位ティアの平均スコアレートを使う。中央値の方が外れ値に強いが、
 * 集計 SQL 側で中央値を取ると重くなるため平均で代用している。
 *
 * <h3>人数の下限</h3>
 * ティア × 譜面のセルは {@link #MIN_CELL_USERS} 人未満だと採用しない。
 * 少人数のセルは到達率が 0% か 100% に張り付いて判別力を過大評価するうえ、
 * 他人のスコアが個人単位で推測できる粒度になるのを避ける意味もある。
 *
 * <h3>上位ティアの扱い</h3>
 * 実測（2026-09-04）で Mythic 20 人 / Legend 4 人しかおらず、ティア別統計が成立しない。
 * {@link #MERGE_FROM_TIER} 以上は 1 つのバンドに合算して扱う。
 *
 * <h3>コスト</h3>
 * 元クエリは scores × users × song_definitions の全件 JOIN で、
 * PostgreSQL の {@code statement_timeout = 30s} を超え得る。
 * {@link SongArenaAveragesCacheService} と同じく、専用トランザクション内で
 * タイムアウトを引き上げたうえで日次リフレッシュし、結果をメモリに持つ。
 */
@Service
public class TierBenchmarkCacheService {

    private static final Logger log = LoggerFactory.getLogger(TierBenchmarkCacheService.class);

    /** 再計算間隔（ミリ秒）。24 時間。ティア分布は日単位でしか動かない。 */
    private static final long REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    /**
     * 起動から初回リフレッシュまでの遅延（ミリ秒）。
     * 起動直後の DDL（DataInitializer）と重い SELECT のロック競合を避けるため、
     * 既存の重集計キャッシュ（2 分）よりさらに後ろにずらす。
     */
    private static final long INITIAL_DELAY_MS = 180L * 1000L;

    /** ティア × 譜面のセルを採用する最小人数。これ未満のセルは判定に使わない。 */
    static final int MIN_CELL_USERS = 20;

    /** このティア以上は 1 バンドに合算する（人数不足のため）。 */
    private static final String MERGE_FROM_TIER = "Ancient";

    /** 合算バンドの表示名。 */
    static final String MERGED_TIER_NAME = "Ancient+";

    /** 判別力を測るボーダー。ラベルは UI にそのまま出す。 */
    private static final String[] BORDER_KEYS = {"aaCount", "aaaCount", "maxMinusCount"};
    private static final String[] BORDER_LABELS = {"AA", "AAA", "MAX-"};
    /** {@link #BORDER_LABELS} と同じ並びのスコアレート（%）。 */
    private static final double[] BORDER_RATES = {77.77, 88.88, 94.44};

    /** 上位ティアでの到達率がこの範囲に入るボーダーだけを判別力の候補にする。 */
    private static final double GATE_UPPER_MIN = 0.50;
    private static final double GATE_UPPER_MAX = 0.90;

    /** 1 つのティア遷移について保持する登竜門譜面の数。 */
    private static final int GATE_CHARTS_PER_TIER = 20;

    private final ScoreRepository scoreRepository;
    private final JdbcTemplate jdbcTemplate;

    /** 譜面 × ティアのセル。key = "title\0difficultyName" → (tierName → Cell)。 */
    private volatile Map<String, Map<String, Cell>> cells = Collections.emptyMap();

    /** 「T→T+1」→ 登竜門譜面リスト（判別力降順）。key は下位ティア名。 */
    private volatile Map<String, List<Gate>> gatesByFromTier = Collections.emptyMap();

    /** 最終更新時刻（epoch ms）。0 なら未計算。 */
    private volatile long lastRefreshedAt = 0L;

    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public TierBenchmarkCacheService(ScoreRepository scoreRepository, JdbcTemplate jdbcTemplate) {
        this.scoreRepository = scoreRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 譜面 × ティアの 1 セル。到達率は人数で割った実数。 */
    public static class Cell {
        public int userCount;
        public int aaCount;
        public int aaaCount;
        public int maxMinusCount;
        public int hardCount;
        public double avgRate;

        /** ボーダー種別（{@link #BORDER_KEYS} の添字）の到達率。人数 0 なら 0。 */
        double rateOf(int borderIndex) {
            if (userCount <= 0) return 0;
            int hit = switch (borderIndex) {
                case 0 -> aaCount;
                case 1 -> aaaCount;
                default -> maxMinusCount;
            };
            return hit / (double) userCount;
        }
    }

    /** 登竜門譜面 1 件。 */
    public record Gate(String title, String difficultyName,
                       String borderLabel, double borderRate,
                       double discrimination,
                       double upperReachRate, double lowerReachRate,
                       double standardRate, int upperUserCount) {}

    /**
     * 【メソッドの役割】 指定ティアから 1 つ上のティアへの登竜門譜面を返す。
     *
     * @param fromTier 下位ティア名（例 "Expert"）
     * @return 判別力降順の登竜門譜面。データ不足なら空リスト
     */
    public List<Gate> getGates(String fromTier) {
        return gatesByFromTier.getOrDefault(fromTier, List.of());
    }

    /**
     * 【メソッドの役割】 指定譜面・指定ティアのセルを返す。
     *
     * @return セル。人数不足や未集計なら null
     */
    public Cell getCell(String title, String difficultyName, String tierName) {
        Map<String, Cell> byTier = cells.get(title + "\0" + difficultyName);
        if (byTier == null) return null;
        return byTier.get(mergeTierName(tierName));
    }

    /** 【メソッドの役割】 集計済みかどうか。未集計ならメニュー生成側は登竜門枠を諦める。 */
    public boolean isReady() {
        return lastRefreshedAt > 0;
    }

    /** 【メソッドの役割】 最終更新時刻（epoch ms）。0 なら未計算。 */
    public long getLastRefreshedAt() {
        return lastRefreshedAt;
    }

    /**
     * 【メソッドの役割】 集計を再計算してキャッシュを差し替える。
     *
     * 日次 + 起動 3 分後。多重起動は {@link #refreshing} で抑止し、
     * 失敗時は前回値を保持してログのみ残す（練習メニューは前日の集計でも十分成立する）。
     */
    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    @Transactional
    public void refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            log.debug("tier-benchmark cache refresh already in progress, skipping");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            // 重集計なので接続既定の 30s では足りない。暴走を防ぐため無制限にはせず有限上限にする。
            jdbcTemplate.execute("SET LOCAL statement_timeout = '180s'");
            List<Map<String, Object>> rows = scoreRepository.findChartTierBenchmarks();

            Map<String, Map<String, Cell>> nextCells = buildCells(rows);
            Map<String, List<Gate>> nextGates = buildGates(nextCells);

            this.cells = nextCells;
            this.gatesByFromTier = nextGates;
            this.lastRefreshedAt = System.currentTimeMillis();
            log.info("Refreshed tier-benchmark cache: {} charts, {} tier transitions with gates in {} ms",
                    nextCells.size(), nextGates.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Failed to refresh tier-benchmark cache after {} ms (keeping previous value)",
                    System.currentTimeMillis() - start, e);
        } finally {
            refreshing.set(false);
        }
    }

    /**
     * 【メソッドの役割】 SQL の生行を 譜面 → ティア → セル の 2 段マップに畳む。
     * 人数が {@link #MIN_CELL_USERS} 未満のセルは、合算後に判定してから捨てる。
     */
    private Map<String, Map<String, Cell>> buildCells(List<Map<String, Object>> rows) {
        Map<String, Map<String, Cell>> byChart = new HashMap<>();
        // 合算バンドは複数ティアの行を足し合わせるため、平均レートは人数で重み付けして持つ。
        Map<String, Map<String, double[]>> rateAccum = new HashMap<>(); // key → tier → [sumRate*users, users]

        for (Map<String, Object> row : rows) {
            String title = (String) row.get("title");
            String diff = (String) row.get("difficultyName");
            String tier = mergeTierName((String) row.get("beatTier"));
            if (title == null || diff == null || tier == null) continue;

            String key = title + "\0" + diff;
            Cell cell = byChart.computeIfAbsent(key, k -> new HashMap<>())
                    .computeIfAbsent(tier, t -> new Cell());

            int users = intOf(row.get("userCount"));
            cell.userCount += users;
            cell.aaCount += intOf(row.get("aaCount"));
            cell.aaaCount += intOf(row.get("aaaCount"));
            cell.maxMinusCount += intOf(row.get("maxMinusCount"));
            cell.hardCount += intOf(row.get("hardCount"));

            double avgRate = doubleOf(row.get("avgRate"));
            double[] acc = rateAccum.computeIfAbsent(key, k -> new HashMap<>())
                    .computeIfAbsent(tier, t -> new double[2]);
            acc[0] += avgRate * users;
            acc[1] += users;
        }

        // 重み付き平均レートを確定し、人数不足のセルを落とす。
        Map<String, Map<String, Cell>> result = new HashMap<>();
        for (Map.Entry<String, Map<String, Cell>> chartEntry : byChart.entrySet()) {
            Map<String, Cell> kept = new HashMap<>();
            for (Map.Entry<String, Cell> tierEntry : chartEntry.getValue().entrySet()) {
                Cell cell = tierEntry.getValue();
                if (cell.userCount < MIN_CELL_USERS) continue;
                double[] acc = rateAccum.get(chartEntry.getKey()).get(tierEntry.getKey());
                cell.avgRate = acc[1] > 0 ? acc[0] / acc[1] : 0;
                kept.put(tierEntry.getKey(), cell);
            }
            if (!kept.isEmpty()) result.put(chartEntry.getKey(), kept);
        }
        return result;
    }

    /**
     * 【メソッドの役割】 セルから、隣接ティアごとの登竜門譜面リストを作る。
     *
     * 各譜面について「上位ティアでの到達率が 50〜90% に入るボーダー」の中から
     * 判別力が最大のものを選び、その値で譜面を並べて上位を採用する。
     */
    private Map<String, List<Gate>> buildGates(Map<String, Map<String, Cell>> cellMap) {
        Map<String, List<Gate>> result = new HashMap<>();

        // Beginner(0) → Novice(1) → ... の順に、下位ティアごとに 1 つ上との差を見る。
        for (int ord = 0; ord < BeatTierScale.TIERS.size() - 1; ord++) {
            BeatTierScale.Tier from = BeatTierScale.byOrdinal(ord);
            BeatTierScale.Tier to = BeatTierScale.byOrdinal(ord + 1);
            if (from == null || to == null) continue;

            String fromName = mergeTierName(from.name());
            String toName = mergeTierName(to.name());
            // 合算バンドの内部（Ancient→Mythic など）は差が取れないので飛ばす。
            if (fromName.equals(toName)) continue;

            List<Gate> gates = new ArrayList<>();
            for (Map.Entry<String, Map<String, Cell>> e : cellMap.entrySet()) {
                Cell lower = e.getValue().get(fromName);
                Cell upper = e.getValue().get(toName);
                if (lower == null || upper == null) continue;

                int bestBorder = -1;
                double bestD = 0;
                for (int b = 0; b < BORDER_KEYS.length; b++) {
                    double upperRate = upper.rateOf(b);
                    // 上位ティアでも半分未満 / ほぼ全員、のボーダーは「ティアを分ける」働きをしない。
                    if (upperRate < GATE_UPPER_MIN || upperRate > GATE_UPPER_MAX) continue;
                    double d = upperRate - lower.rateOf(b);
                    if (d > bestD) {
                        bestD = d;
                        bestBorder = b;
                    }
                }
                if (bestBorder < 0) continue;

                String[] parts = e.getKey().split("\0", 2);
                if (parts.length < 2) continue;
                gates.add(new Gate(parts[0], parts[1],
                        BORDER_LABELS[bestBorder], BORDER_RATES[bestBorder],
                        bestD,
                        upper.rateOf(bestBorder), lower.rateOf(bestBorder),
                        upper.avgRate, upper.userCount));
            }

            gates.sort(Comparator.comparingDouble(Gate::discrimination).reversed());
            if (gates.size() > GATE_CHARTS_PER_TIER) {
                gates = new ArrayList<>(gates.subList(0, GATE_CHARTS_PER_TIER));
            }
            if (!gates.isEmpty()) result.put(from.name(), List.copyOf(gates));
        }
        return result;
    }

    /**
     * 【メソッドの役割】 人数不足の上位ティアを合算バンド名に読み替える。
     * Ancient / Mythic / Legend → "Ancient+"。それ以外はそのまま。
     */
    static String mergeTierName(String tierName) {
        if (tierName == null) return null;
        int ord = BeatTierScale.ordinalOf(tierName);
        int mergeOrd = BeatTierScale.ordinalOf(MERGE_FROM_TIER);
        if (ord >= 0 && mergeOrd >= 0 && ord >= mergeOrd) return MERGED_TIER_NAME;
        return tierName;
    }

    private static int intOf(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static double doubleOf(Object o) {
        return o == null ? 0.0 : ((Number) o).doubleValue();
    }
}
