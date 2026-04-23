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

        // 2) 全 ANOTHER/LEGG スコアを取得し、A 以上のみ user 別にグループ化
        List<Map<String, Object>> rows = scoreRepository.findAllAnotherLeggScores();
        Map<Long, List<Object[]>> byUser = new HashMap<>();
        for (Map<String, Object> row : rows) {
            long userId = ((Number) row.get("userId")).longValue();
            String title = (String) row.get("title");
            String diff = (String) row.get("difficultyName");
            int score = ((Number) row.get("score")).intValue();
            String key = title + "\0" + diff;

            Integer notes = notesMap.get(key);
            if (notes == null) continue;
            if (score < notes * 2.0 * A_GRADE_RATE) continue;

            byUser.computeIfAbsent(userId, k -> new ArrayList<>())
                  .add(new Object[]{key, score});
        }
        log.info("PairRegressionService: {} users with A-grade scores", byUser.size());

        // 3) 各ユーザーについて、譜面の全ペアの累積和を作る
        // 順序: A→B と B→A は別の回帰（slope が異なる）として両方積む。
        Map<String, Map<String, Acc>> accs = new HashMap<>();
        long pairContribCount = 0;
        for (List<Object[]> scores : byUser.values()) {
            int sz = scores.size();
            for (int i = 0; i < sz; i++) {
                Object[] a = scores.get(i);
                String aKey = (String) a[0];
                int aScore = (Integer) a[1];
                Map<String, Acc> aMap = accs.computeIfAbsent(aKey, k -> new HashMap<>());
                for (int j = 0; j < sz; j++) {
                    if (i == j) continue;
                    Object[] b = scores.get(j);
                    String bKey = (String) b[0];
                    int bScore = (Integer) b[1];
                    aMap.computeIfAbsent(bKey, k -> new Acc()).add(aScore, bScore);
                    pairContribCount++;
                }
            }
        }
        log.info("PairRegressionService: {} pair contributions accumulated", pairContribCount);

        // 4) 回帰計算 + フィルタ
        Map<String, Map<String, Reg>> newCache = new HashMap<>();
        long keptPairs = 0;
        for (Map.Entry<String, Map<String, Acc>> aEntry : accs.entrySet()) {
            Map<String, Reg> bMap = new HashMap<>();
            for (Map.Entry<String, Acc> bEntry : aEntry.getValue().entrySet()) {
                Reg reg = bEntry.getValue().compute();
                if (reg != null && reg.n >= MIN_N && Math.abs(reg.r) >= MIN_R) {
                    bMap.put(bEntry.getKey(), reg);
                    keptPairs++;
                }
            }
            if (!bMap.isEmpty()) newCache.put(aEntry.getKey(), bMap);
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
