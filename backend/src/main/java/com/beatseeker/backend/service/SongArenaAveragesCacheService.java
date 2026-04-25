package com.beatseeker.backend.service;

import com.beatseeker.backend.repository.ScoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【Service の役割】 ティア別平均スコアページ（{@code /api/scores/song-arena-averages}）の
 * 集計結果を in-memory にキャッシュするサービス。
 *
 * 背景:
 *  - 元のクエリ {@link ScoreRepository#findRawSongScoresWithBeatTier()} は scores × users ×
 *    song_definitions を全件 JOIN する重集計で、データ増加に伴い PostgreSQL の
 *    {@code statement_timeout = 30s} を超えて 500 を返すようになっていた。
 *  - 結果はユーザーの total_beat_pt とベストスコアにのみ依存し、リアルタイム性は不要のため
 *    定期リフレッシュ + in-memory 配信で十分。
 *
 * 動作:
 *  - 起動 1 分後に初回ロード、その後 {@link #REFRESH_INTERVAL_MS}（30 分）毎に再計算
 *  - リフレッシュ中は {@link #refreshing} フラグで多重実行を防止
 *  - 失敗時は前回値を保持し続け、次回リフレッシュでリトライする
 */
@Service
public class SongArenaAveragesCacheService {

    private static final Logger log = LoggerFactory.getLogger(SongArenaAveragesCacheService.class);

    /** 再計算間隔（ミリ秒）。30 分。 */
    private static final long REFRESH_INTERVAL_MS = 30L * 60L * 1000L;

    /** 起動から初回リフレッシュまでの遅延（ミリ秒）。アプリ初期化が落ち着いてから走らせる。 */
    private static final long INITIAL_DELAY_MS = 60L * 1000L;

    private final ScoreRepository scoreRepository;

    /** 公開する集計結果。volatile で publish/subscribe を成立させる。要素は不変として扱う。 */
    private volatile List<Map<String, Object>> cache = List.of();

    /** リフレッシュ中フラグ。多重起動を抑止。 */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public SongArenaAveragesCacheService(ScoreRepository scoreRepository) {
        this.scoreRepository = scoreRepository;
    }

    /**
     * 【メソッドの役割】 キャッシュ済みの集計結果を返す。
     *
     * 初回リフレッシュ完了前は空リストを返す。フロントは「データ 0 件」として描画される。
     */
    public List<Map<String, Object>> get() {
        return cache;
    }

    /**
     * 【メソッドの役割】 集計結果を再計算してキャッシュを差し替える。
     *
     * 30 分毎の定期実行に加え、起動 1 分後にも 1 回走る（{@code initialDelay}）。
     * 既にリフレッシュ中なら何もしない（多重起動防止）。
     * クエリが失敗（タイムアウト等）した場合は前回値を保持し、ログのみ残す。
     */
    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            log.debug("song-arena-averages cache refresh already in progress, skipping");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            List<Map<String, Object>> next = scoreRepository.findRawSongScoresWithBeatTier();
            this.cache = next;
            log.info("Refreshed song-arena-averages cache: {} rows in {} ms",
                    next.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Failed to refresh song-arena-averages cache (keeping previous value of {} rows)",
                    cache.size(), e);
        } finally {
            refreshing.set(false);
        }
    }
}
