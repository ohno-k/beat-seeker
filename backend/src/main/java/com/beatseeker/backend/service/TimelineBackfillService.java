package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.TimelineEvent;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.TimelineEventRepository;
import com.beatseeker.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 【Service の役割】 過去の {@link ScoreHistoryLog} を元に SCORE_UPDATE 種別の
 * {@link TimelineEvent} をバックフィル生成する。
 *
 * 動作:
 *  - アプリ起動完了 ({@link ApplicationReadyEvent}) 後に別スレッドで 1 度だけ実行。
 *  - 管理者の手動トリガー ({@link #backfillAllAsync(boolean)}) からも非同期で実行可能。
 *  - すでに 1 件でも TimelineEvent を持つユーザーはスキップ（force=true で強制再生成）。
 *  - 対象ユーザーの ScoreHistoryLog を古い順に走査し、{@code diffJson} が非空のものを
 *    SCORE_UPDATE イベントとして 1 件保存する。createdAt は当時の uploadedAt を使う。
 *
 * 進捗:
 *  - {@link #getStatus()} で現在の処理状況（実行中フラグ・処理件数・直近実行サマリ）を取得できる。
 *  - 二重起動を防ぐため、{@link #running} フラグで排他制御している。
 *
 * 制約:
 *  - OVERTAKE_SONG / OVERTAKE_TOTAL は当時の相手スコアが履歴に残らないため再現しない。
 *
 * 失敗時はログ出力のみ。アプリ起動・HTTP リクエストを巻き込まない。
 */
@Service
public class TimelineBackfillService {

    private static final Logger log = LoggerFactory.getLogger(TimelineBackfillService.class);

    /** 1 トランザクションあたりの保存上限。大きすぎると OOM、小さすぎると遅い。 */
    private static final int BATCH_SIZE = 500;

    private final UserRepository userRepository;
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;
    private final TimelineEventRepository timelineEventRepository;

    /** 二重起動防止用フラグ。バックフィル走行中は true。 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 現在実行中の進捗用カウンタ（合計対象ユーザ数）。 */
    private final AtomicInteger totalUsers = new AtomicInteger(0);
    /** 処理済みユーザ数（成功・スキップ含む）。 */
    private final AtomicInteger processedUsers = new AtomicInteger(0);
    /** これまで作成したイベント数。 */
    private final AtomicInteger createdEvents = new AtomicInteger(0);
    /** これまでスキップしたユーザ数（既存イベントあり）。 */
    private final AtomicInteger skippedUsers = new AtomicInteger(0);
    /** 直近の開始エポックミリ秒。未実行なら 0。 */
    private volatile long startedAtMs = 0L;
    /** 直近の完了エポックミリ秒。未実行 / 実行中なら 0。 */
    private volatile long finishedAtMs = 0L;
    /** 直近のエラー文字列（成功時は null）。 */
    private volatile String lastError = null;

    public TimelineBackfillService(UserRepository userRepository,
                                   ScoreHistoryLogRepository scoreHistoryLogRepository,
                                   TimelineEventRepository timelineEventRepository) {
        this.userRepository = userRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.timelineEventRepository = timelineEventRepository;
    }

    /**
     * アプリ起動完了後にバックフィルを開始する。
     * {@link Async} で別スレッドに退避させ、起動を遅らせない。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void runOnStartup() {
        try {
            backfillAllAsync(false);
        } catch (Exception e) {
            log.error("Timeline backfill on startup failed", e);
        }
    }

    /**
     * 全ユーザーを対象にバックフィルを実行する（非同期）。
     *
     * 既に走行中ならスキップ（二重起動防止）。
     * 各ユーザーごとに独立したトランザクションで処理するため、長時間ロックを取らない。
     *
     * @param force 既に TimelineEvent を持つユーザーも対象に含めるか
     */
    @Async
    public void backfillAllAsync(boolean force) {
        if (!running.compareAndSet(false, true)) {
            log.info("Timeline backfill already running; ignoring trigger");
            return;
        }
        try {
            // 進捗カウンタを初期化。
            startedAtMs = System.currentTimeMillis();
            finishedAtMs = 0L;
            lastError = null;
            totalUsers.set(0);
            processedUsers.set(0);
            createdEvents.set(0);
            skippedUsers.set(0);

            List<User> users = userRepository.findAll();
            totalUsers.set(users.size());

            for (User user : users) {
                try {
                    int c = backfillSingleUser(user, force);
                    if (c < 0) {
                        skippedUsers.incrementAndGet();
                    } else if (c > 0) {
                        createdEvents.addAndGet(c);
                    }
                } catch (Exception ue) {
                    // 1 ユーザの失敗は全体を止めない。ログだけ残して次へ。
                    log.warn("Timeline backfill failed for userId={} ({}): {}",
                            user.getId(), user.getDisplayName(), ue.getMessage());
                } finally {
                    processedUsers.incrementAndGet();
                }
            }

            finishedAtMs = System.currentTimeMillis();
            log.info("Timeline backfill done. processed={}, skipped={}, created={}, elapsedMs={}",
                    processedUsers.get(), skippedUsers.get(), createdEvents.get(),
                    finishedAtMs - startedAtMs);
        } catch (Exception e) {
            lastError = e.getMessage();
            finishedAtMs = System.currentTimeMillis();
            log.error("Timeline backfill failed", e);
        } finally {
            running.set(false);
        }
    }

    /**
     * 進捗・直近結果を取得する。管理者画面 / フロントのポーリングから参照される想定。
     *
     * @return running / startedAt / finishedAt / totalUsers / processedUsers / createdEvents /
     *         skippedUsers / lastError を含む Map
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", running.get());
        status.put("startedAt", startedAtMs > 0 ? Instant.ofEpochMilli(startedAtMs).toString() : null);
        status.put("finishedAt", finishedAtMs > 0 ? Instant.ofEpochMilli(finishedAtMs).toString() : null);
        status.put("totalUsers", totalUsers.get());
        status.put("processedUsers", processedUsers.get());
        status.put("createdEvents", createdEvents.get());
        status.put("skippedUsers", skippedUsers.get());
        status.put("lastError", lastError);
        return status;
    }

    /** バックフィルが現在走っているかを返す。{@link #backfillAllAsync(boolean)} の二重起動制御用。 */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 指定ユーザー 1 名分について、過去の {@link ScoreHistoryLog} から SCORE_UPDATE イベントを作る。
     * 手動トリガー（管理エンドポイント）からも呼べるよう public にしてある。
     *
     * @param user  対象ユーザー
     * @param force 既存イベントがあっても処理を続けるか（true なら既存をスキップせず追加）
     * @return 作成件数。スキップした場合は -1。
     */
    @Transactional
    public int backfillSingleUser(User user, boolean force) {
        // 既存イベントを持つ場合は二重生成を避けるためスキップ（force=true なら追加）。
        if (!force && timelineEventRepository.existsByUser(user)) {
            return -1;
        }
        List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(user);
        if (logs.isEmpty()) return 0;

        int created = 0;
        List<TimelineEvent> batch = new ArrayList<>(BATCH_SIZE);
        for (ScoreHistoryLog sh : logs) {
            String diffJson = sh.getDiffJson();
            // 差分なしの履歴（空 or null）はタイムラインに出す意味がないので捨てる。
            if (diffJson == null || diffJson.isBlank() || "[]".equals(diffJson.trim())) continue;

            TimelineEvent ev = new TimelineEvent();
            ev.setUser(user);
            ev.setType("SCORE_UPDATE");
            ev.setPayload(diffJson);
            // 当時の発生時刻を採用。new TimelineEvent() のデフォルトは now() なので上書き必須。
            ev.setCreatedAt(sh.getUploadedAt());
            batch.add(ev);
            if (batch.size() >= BATCH_SIZE) {
                timelineEventRepository.saveAll(batch);
                created += batch.size();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            timelineEventRepository.saveAll(batch);
            created += batch.size();
        }
        return created;
    }
}
