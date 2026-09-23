package com.beatseeker.backend.service;

import com.beatseeker.backend.repository.ScoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【Service の役割】 スコア分布ページ（{@code /api/scores/song-score-spectrum}、管理者専用）の
 * 譜面別スコアレート分布を in-memory にキャッシュするサービス。
 *
 * 背景:
 *  - 曲別平均スコアレートページは MAX- / AAA の 2 点だけで譜面の難しさを見ていたが、
 *    「MAX- 率は同じでも AAA は取りやすい」といった分布の形の違いが見えない。
 *    そこで各譜面のスコアレート分布そのもの（ヒストグラム）を返し、フロントで
 *    分布帯・到達率カーブとして描く。
 *
 * 動作（{@link SongAvgScoreRatesCacheService} と違い定期実行しない）:
 *  - 管理者専用ページでしか使わないため、30 分毎に重い歴代集計を回すのは無駄。
 *    リクエスト時に {@link #requestSnapshot()} がキャッシュの鮮度を見て、
 *    空または {@link #STALE_AFTER_MS} 超過ならバックグラウンドで再計算を起動する。
 *  - 集計は歴代ベスト CTE で本番 1 分前後かかるため、HTTP リクエストはブロックせず
 *    「計算中」を返し、フロントがポーリングする。
 *  - 再計算は {@code SET LOCAL statement_timeout = '180s'} を張ったトランザクション内で行う
 *    （接続レベルの 30 秒タイムアウトを緩和。他リクエストには影響しない）。
 *  - 失敗時は前回値を保持する。
 */
@Service
public class SongScoreSpectrumCacheService {

    private static final Logger log = LoggerFactory.getLogger(SongScoreSpectrumCacheService.class);

    /** キャッシュをこれより古いとみなして再計算する閾値（ミリ秒）。1 時間。 */
    private static final long STALE_AFTER_MS = 60L * 60L * 1000L;

    /** 桶の最大値。理論値 = 180。 */
    private static final int MAX_BUCKET = 180;

    private final ScoreRepository scoreRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate txTemplate;

    /** 公開する集計結果。null は未計算。 */
    private volatile List<Map<String, Object>> cache = null;
    /** 最終計算完了時刻。 */
    private volatile Instant computedAt = null;
    /** 直近の失敗メッセージ（成功で消える）。 */
    private volatile String lastError = null;

    /** 再計算中フラグ。多重起動を抑止。 */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public SongScoreSpectrumCacheService(ScoreRepository scoreRepository,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        this.scoreRepository = scoreRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 【メソッドの役割】 現在のキャッシュ状態を返し、必要なら再計算をバックグラウンドで起動する。
     *
     * 返却キー: ready（キャッシュあり）/ refreshing / computedAt / error / charts
     *
     * @param force true なら鮮度に関係なく再計算を起動する
     */
    public Map<String, Object> requestSnapshot(boolean force) {
        Instant at = computedAt;
        boolean stale = at == null
                || System.currentTimeMillis() - at.toEpochMilli() > STALE_AFTER_MS;
        if ((force || stale) && refreshing.compareAndSet(false, true)) {
            CompletableFuture.runAsync(this::refresh);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> charts = cache;
        body.put("ready", charts != null);
        body.put("refreshing", refreshing.get());
        body.put("computedAt", at == null ? null : at.toString());
        body.put("error", lastError);
        body.put("charts", charts == null ? List.of() : charts);
        return body;
    }

    /** 【メソッドの役割】 再計算本体。呼び出し側で refreshing を true にしてから呼ぶ。 */
    private void refresh() {
        long start = System.currentTimeMillis();
        try {
            List<Map<String, Object>> next = txTemplate.execute(status -> {
                jdbcTemplate.execute("SET LOCAL statement_timeout = '180s'");
                return build(scoreRepository.findLifetimeSongScoreHistogram());
            });
            this.cache = next;
            this.computedAt = Instant.now();
            this.lastError = null;
            log.info("Refreshed song-score-spectrum cache: {} charts in {} ms",
                    next == null ? 0 : next.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            this.lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("Failed to refresh song-score-spectrum cache after {} ms",
                    System.currentTimeMillis() - start, e);
        } finally {
            refreshing.set(false);
        }
    }

    /**
     * 【メソッドの役割】 (譜面, bucket) 単位の行を譜面ごとの 1 行にまとめる。
     *
     * 出力 1 譜面分: title / difficultyName / level / notes / playerCount / avgScoreRate /
     * hist（長さ 181 の人数配列。index = bucket = 理論値の 1/180 単位の到達度）
     */
    private static List<Map<String, Object>> build(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byChart = new LinkedHashMap<>();
        Map<String, int[]> hists = new HashMap<>();
        Map<String, double[]> sums = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String title = (String) row.get("title");
            String diffName = (String) row.get("difficultyName");
            String key = title + "\u0000" + diffName;
            Map<String, Object> entry = byChart.get(key);
            if (entry == null) {
                entry = new LinkedHashMap<>();
                entry.put("title", title);
                entry.put("difficultyName", diffName);
                entry.put("level", ((Number) row.get("level")).intValue());
                entry.put("notes", ((Number) row.get("notes")).intValue());
                byChart.put(key, entry);
                hists.put(key, new int[MAX_BUCKET + 1]);
                sums.put(key, new double[1]);
            }
            int bucket = Math.max(0, Math.min(MAX_BUCKET, ((Number) row.get("bucket")).intValue()));
            hists.get(key)[bucket] += ((Number) row.get("cnt")).intValue();
            sums.get(key)[0] += ((Number) row.get("scoreSum")).doubleValue();
        }

        List<Map<String, Object>> result = new ArrayList<>(byChart.size());
        for (Map.Entry<String, Map<String, Object>> e : byChart.entrySet()) {
            Map<String, Object> entry = e.getValue();
            int[] hist = hists.get(e.getKey());
            int players = 0;
            for (int c : hist) players += c;
            if (players <= 0) continue;
            int notes = (Integer) entry.get("notes");
            double avgRate = sums.get(e.getKey())[0] * 100.0 / (notes * 2.0) / players;
            entry.put("playerCount", players);
            entry.put("avgScoreRate", Math.round(avgRate * 100.0) / 100.0);
            entry.put("hist", hist);
            result.add(entry);
        }
        return result;
    }
}
