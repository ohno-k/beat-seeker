package com.beatseeker.backend.service;

import com.beatseeker.backend.repository.ScoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【Service の役割】 人気曲ランキング（{@code /api/scores/song-ranking-aggregate}）の
 * 集計結果を in-memory にキャッシュするサービス。
 *
 * 背景:
 *  - 元のクエリ {@link ScoreRepository#findAllSongRankingAggregates()} は scores ×
 *    song_definitions × difficulty_ranks の重集計で、データ増加に伴い PostgreSQL の
 *    {@code statement_timeout = 30s} を超えて 500 を返すようになっていた。
 *  - 結果はユーザーのベストスコアにのみ依存し、リアルタイム性は不要のため
 *    定期リフレッシュ + in-memory 配信で十分。
 *
 * 動作:
 *  - 起動 10 秒後に初回ロード、その後 {@link #REFRESH_INTERVAL_MS}（30 分）毎に再計算
 *  - リフレッシュ用トランザクション内で {@code SET LOCAL statement_timeout = 0} を発行し、
 *    集計クエリだけ接続レベルの 30 秒タイムアウトを無効化する
 *  - リフレッシュ中は {@link #refreshing} フラグで多重実行を防止
 *  - 失敗時は前回値を保持し続け、次回リフレッシュでリトライする
 *
 * {@link SongArenaAveragesCacheService} と同じ設計パターンの兄弟サービス。
 */
@Service
public class SongRankingAggregateCacheService {

    private static final Logger log = LoggerFactory.getLogger(SongRankingAggregateCacheService.class);

    private static final long REFRESH_INTERVAL_MS = 30L * 60L * 1000L;
    // 起動直後の DataInitializer の ALTER TABLE とロック競合しないよう初回を遅延（[[SongArenaAveragesCacheService]] と同様）。
    private static final long INITIAL_DELAY_MS = 120L * 1000L;

    private final ScoreRepository scoreRepository;
    private final JdbcTemplate jdbcTemplate;

    private volatile List<Map<String, Object>> cache = List.of();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public SongRankingAggregateCacheService(ScoreRepository scoreRepository, JdbcTemplate jdbcTemplate) {
        this.scoreRepository = scoreRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> get() {
        return cache;
    }

    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    @Transactional
    public void refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            log.debug("song-ranking-aggregate cache refresh already in progress, skipping");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            // 無制限(0)ではなく有限上限。暴走クエリが接続を握り続けてインスタンスを不安定化させない。
            jdbcTemplate.execute("SET LOCAL statement_timeout = '180s'");
            List<Map<String, Object>> next = scoreRepository.findAllSongRankingAggregates();
            this.cache = next;
            log.info("Refreshed song-ranking-aggregate cache: {} rows in {} ms",
                    next.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Failed to refresh song-ranking-aggregate cache after {} ms (keeping previous value of {} rows)",
                    System.currentTimeMillis() - start, cache.size(), e);
        } finally {
            refreshing.set(false);
        }
    }
}
