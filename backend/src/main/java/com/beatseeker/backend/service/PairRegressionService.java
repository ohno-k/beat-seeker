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
    /** 採用する最小相関係数の絶対値。これ未満はノイズとして捨てる。 */
    private static final double MIN_R = 0.4;
    /** A グレード閾値（scoreRate %）。 */
    private static final double A_GRADE_RATE = 0.6667;

    private final ScoreRepository scoreRepository;

    /** chartA(=title\0difficultyName) -> chartB -> Reg（B を A から予測する回帰）。volatile でロック無し公開。 */
    private volatile Map<String, Map<String, Reg>> cache = Collections.emptyMap();
    /** chartKey -> notes。max_score = notes * 2。再構築時に同時に作る。 */
    private volatile Map<String, Integer> notesByKey = Collections.emptyMap();
    /** 構築済みフラグ。 */
    private volatile boolean built = false;
    /** 再構築の同時実行を防ぐロック。 */
    private final Object buildLock = new Object();

    /** 単一ペアの線形回帰結果。 */
    public static class Reg {
        public int n;
        /** B = slope * A + intercept */
        public double slope;
        public double intercept;
        /** ピアソン相関係数。 */
        public double r;
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
     * 【メソッドの役割】 全譜面の (title, difficultyName, notes) を返す。
     */
    public Map<String, Integer> getNotesByKey() {
        ensureBuilt();
        return notesByKey;
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
        Map<Long, java.util.List<long[]>> userScoresBuilder = new HashMap<>();
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

        // 4) 譜面 A ごとに per-A 累積→回帰計算→フィルタ→キャッシュ格納
        // 各 A の処理が終わった時点で Acc[] は GC 対象になるため、ピークメモリ << 全ペア
        Map<String, Map<String, Reg>> newCache = new HashMap<>();
        long keptPairs = 0;
        Acc[] accsBuf = new Acc[numCharts]; // 再利用する固定サイズバッファ

        for (int aIdx = 0; aIdx < numCharts; aIdx++) {
            java.util.List<Long> users = chartUsers[aIdx];
            if (users == null) continue;
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
                // user のその他譜面ごとに (aScore, bScore) を Acc に追加
                for (long packed : uScores) {
                    int bIdx = (int) (packed >>> 32);
                    if (bIdx == aIdx) continue;
                    int bScore = (int) packed;
                    Acc acc = accsBuf[bIdx];
                    if (acc == null) {
                        acc = new Acc();
                        accsBuf[bIdx] = acc;
                    }
                    acc.add(aScore, bScore);
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
        built = true;
        long elapsed = System.currentTimeMillis() - t0;
        log.info("PairRegressionService rebuilt in {} ms ({} chartA, {} kept pairs after filter)",
                elapsed, newCache.size(), keptPairs);
    }

    /**
     * 【メソッドの役割】 指定ユーザーの「伸びしろ」一覧を返す。
     *
     * 各譜面 B（ユーザーが A 以上で出している）について、同じく A 以上で出している他譜面 A 達から
     * 回帰式で予測スコアを算出し、|r|^2 を重みに加重平均する。
     * ＞ 0 な (predicted − actual) を伸びしろとして降順で並べる。
     *
     * @param userId 対象ユーザー ID
     * @return 各譜面の {title, difficultyName, difficultyLevel, currentScore, predictedScore, gap, ...}
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

        // 2) 各 B について加重平均予測
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, int[]> bEntry : myByKey.entrySet()) {
            String chartB = bEntry.getKey();
            int actualB = bEntry.getValue()[0];
            int levelB = bEntry.getValue()[1];

            double sumW = 0.0;
            double sumWP = 0.0;
            int support = 0;
            for (Map.Entry<String, int[]> aEntry : myByKey.entrySet()) {
                String chartA = aEntry.getKey();
                if (chartA.equals(chartB)) continue;
                int actualA = aEntry.getValue()[0];

                Reg reg = getRegression(chartA, chartB);
                if (reg == null) continue;

                double pred = reg.slope * actualA + reg.intercept;
                double w = reg.r * reg.r; // r²
                sumW += w;
                sumWP += pred * w;
                support++;
            }
            if (support < 3 || sumW <= 0) continue; // サポート薄すぎ → スキップ

            double predicted = sumWP / sumW;
            Integer notes = notesByKey.get(chartB);
            int maxScore = notes == null ? 0 : notes * 2;
            // 物理的にあり得ない範囲をクランプ
            if (maxScore > 0) predicted = Math.min(maxScore, Math.max(0, predicted));
            double gap = predicted - actualB;

            String[] keyParts = chartB.split("\0");
            Map<String, Object> r = new HashMap<>();
            r.put("title", keyParts[0]);
            r.put("difficultyName", keyParts[1]);
            r.put("difficultyLevel", levelB);
            r.put("currentScore", actualB);
            r.put("predictedScore", predicted);
            r.put("gap", gap);
            r.put("supportCount", support);
            if (maxScore > 0) {
                r.put("maxScore", maxScore);
                r.put("currentRate", actualB * 100.0 / maxScore);
                r.put("predictedRate", predicted * 100.0 / maxScore);
            }
            results.add(r);
        }

        // 3) 伸びしろ降順でソート
        results.sort((a, b) -> Double.compare(
                ((Number) b.get("gap")).doubleValue(),
                ((Number) a.get("gap")).doubleValue()));
        return results;
    }

    /** 単一 (A, B) ペアの累積和。compute() で回帰係数に変換する。 */
    static class Acc {
        long n = 0;
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0, sumYY = 0;

        void add(int x, int y) {
            n++;
            sumX += x;
            sumY += y;
            sumXY += (double) x * y;
            sumXX += (double) x * x;
            sumYY += (double) y * y;
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
            return reg;
        }
    }
}
