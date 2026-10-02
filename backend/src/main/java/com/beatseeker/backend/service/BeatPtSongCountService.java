package com.beatseeker.backend.service;

import com.beatseeker.backend.repository.ScoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【Service の役割】 BEAT-PT ランキングに添える「BEAT-PT 対象曲（上位 100 曲枠）の埋まり数」をメモリに持つ。
 *
 * 元の集計（{@link ScoreRepository#findBeatPtSongCounts()}）は scores 全体（約 99 万行・289MB）を読むため本番で毎回 6.5 秒かかり、
 * ランキング API（/api/scores/ranking）の応答 約 9 秒の大半を占めていた（2026-10-03 計測。ランキング本体の SQL は 0.2 秒）。
 *
 * 古くなったら（{@link #FRESH_MS} 経過、またはアップロードで {@link #invalidate()}）、手元の値をすぐ返しつつ裏で取り直す
 * （stale-while-revalidate）。値はランキングで 100 曲未満の行を薄く表示するのに使うだけなので、数秒〜1 分の遅れは許容する。
 * 手元に値が無いとき（起動直後）だけ、その場で集計して待つ。起動完了時に裏で 1 回読んでおく。
 */
@Service
public class BeatPtSongCountService {

    private static final Logger log = LoggerFactory.getLogger(BeatPtSongCountService.class);

    /** この時間内の値は取り直さない。 */
    static final long FRESH_MS = 60_000L;

    private final ScoreRepository scoreRepository;
    private final ExecutorService refresher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "beat-pt-song-count");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    private volatile Map<Long, Integer> counts;
    private volatile long loadedAt;

    public BeatPtSongCountService(ScoreRepository scoreRepository) {
        this.scoreRepository = scoreRepository;
    }

    /** 【メソッドの役割】 ユーザー ID → 対象曲数（0〜100、対象曲が無いユーザーは含まない）。 */
    public Map<Long, Integer> get() {
        Map<Long, Integer> c = counts;
        if (c == null) {
            synchronized (this) {
                if (counts == null) load();
                return counts;
            }
        }
        if (System.currentTimeMillis() - loadedAt > FRESH_MS) refreshAsync();
        return c;
    }

    /** 【メソッドの役割】 古い扱いにする（スコアのアップロード後に呼ぶ）。次の参照で裏の取り直しが始まる。 */
    public void invalidate() {
        loadedAt = 0L;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        refreshAsync();
    }

    private void refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) return;
        refresher.execute(() -> {
            try {
                load();
            } catch (Exception e) {
                log.warn("[BEAT-PT 対象曲数] 集計に失敗しました（前の値を使い続けます）: {}", e.getMessage());
            } finally {
                refreshing.set(false);
            }
        });
    }

    private void load() {
        long start = System.currentTimeMillis();
        Map<Long, Integer> m = new HashMap<>();
        for (Map<String, Object> row : scoreRepository.findBeatPtSongCounts()) {
            if (row.get("userId") instanceof Number id && row.get("beatPtSongCount") instanceof Number n) {
                m.put(id.longValue(), n.intValue());
            }
        }
        counts = Collections.unmodifiableMap(m);
        loadedAt = start; // 集計を始めた時点の値なので、その間のアップロードは次の取り直しで拾う
        log.debug("[BEAT-PT 対象曲数] {} 人分を {} ms で集計", m.size(), System.currentTimeMillis() - start);
    }
}
