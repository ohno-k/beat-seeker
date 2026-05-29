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
 * 【Service の役割】 ティア別平均スコアページ（{@code /api/scores/song-arena-averages}）の
 * 集計結果を in-memory にキャッシュするサービス。
 *
 * 背景:
 *  - 元のクエリ {@link ScoreRepository#findRawSongScoresWithBeatTier()} は scores × users ×
 *    song_definitions を全件 JOIN する重集計で、データ増加に伴い PostgreSQL の
 *    {@code statement_timeout = 30s}（{@code application.yml} の HikariCP 接続初期化 SQL）
 *    を超えて 500 を返すようになっていた。
 *  - 結果はユーザーの total_beat_pt とベストスコアにのみ依存し、リアルタイム性は不要のため
 *    定期リフレッシュ + in-memory 配信で十分。
 *
 * 動作:
 *  - 起動 1 分後に初回ロード、その後 {@link #REFRESH_INTERVAL_MS}（30 分）毎に再計算
 *  - リフレッシュ用トランザクション内で {@code SET LOCAL statement_timeout = 0} を発行し、
 *    集計クエリだけ接続レベルの 30 秒タイムアウトを無効化する（他リクエストには影響しない）
 *  - リフレッシュ中は {@link #refreshing} フラグで多重実行を防止
 *  - 失敗時は前回値を保持し続け、次回リフレッシュでリトライする
 */
@Service
public class SongArenaAveragesCacheService {

    private static final Logger log = LoggerFactory.getLogger(SongArenaAveragesCacheService.class);

    /** 再計算間隔（ミリ秒）。30 分。 */
    private static final long REFRESH_INTERVAL_MS = 30L * 60L * 1000L;

    /**
     * 起動から初回リフレッシュまでの遅延（ミリ秒）。
     *
     * 旧値 10 秒だと、起動直後に走る {@link com.beatseeker.backend.DataInitializer} の
     * {@code ALTER TABLE scores}（ACCESS EXCLUSIVE 必要）と、この重い SELECT（ACCESS SHARE を
     * 長時間保持）がロック競合し、DDL が statement_timeout で中断 → 起動失敗の一因になっていた。
     * 起動時の DDL が確実に終わってから初回集計が走るよう、十分な余裕（2 分）を取る。
     */
    private static final long INITIAL_DELAY_MS = 120L * 1000L;

    private final ScoreRepository scoreRepository;
    private final JdbcTemplate jdbcTemplate;

    /** 公開する集計結果。volatile で publish/subscribe を成立させる。要素は不変として扱う。 */
    private volatile List<Map<String, Object>> cache = List.of();

    /** リフレッシュ中フラグ。多重起動を抑止。 */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public SongArenaAveragesCacheService(ScoreRepository scoreRepository, JdbcTemplate jdbcTemplate) {
        this.scoreRepository = scoreRepository;
        this.jdbcTemplate = jdbcTemplate;
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
     *
     * トランザクション境界:
     *  - {@link Transactional} を付けて Spring に新規トランザクションを張らせる
     *  - その中で {@code SET LOCAL statement_timeout = 0} を発行し、
     *    本トランザクション内で実行される後続クエリのみタイムアウトを無効化する
     *  - JPA リポジトリ呼び出しは同一コネクション・同一トランザクション上で動く
     *
     * クエリが失敗した場合は前回値を保持し、ログのみ残す。
     */
    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    @Transactional
    public void refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            log.debug("song-arena-averages cache refresh already in progress, skipping");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            // 重集計クエリを接続レベルの 30s 上限から解放するが、無制限(0)にはしない。
            // 無制限だと暴走クエリが DB 接続を延々と握り、メモリ/DB 負荷の逼迫（→ Render の
            // メモリ逼迫 SIGTERM 再起動）を悪化させる。現状の所要(約50秒)に十分余裕を持たせた
            // 有限上限にして、想定外に長引いたら PostgreSQL 側でクリーンに中断させる。
            jdbcTemplate.execute("SET LOCAL statement_timeout = '180s'");
            List<Map<String, Object>> next = scoreRepository.findRawSongScoresWithBeatTier();
            this.cache = next;
            log.info("Refreshed song-arena-averages cache: {} rows in {} ms",
                    next.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Failed to refresh song-arena-averages cache after {} ms (keeping previous value of {} rows)",
                    System.currentTimeMillis() - start, cache.size(), e);
        } finally {
            refreshing.set(false);
        }
    }
}
