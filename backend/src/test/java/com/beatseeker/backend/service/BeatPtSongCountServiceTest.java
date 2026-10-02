package com.beatseeker.backend.service;

import com.beatseeker.backend.repository.ScoreRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 【テストの目的】 対象曲数のメモリ保持: 初回だけその場で集計し、以降は手元の値を返す。古い扱いになったら裏で取り直す。
 */
class BeatPtSongCountServiceTest {

    @Test
    void firstCallLoads_thenServesCached_invalidateRefreshesInBackground() throws Exception {
        ScoreRepository repo = mock(ScoreRepository.class);
        AtomicInteger calls = new AtomicInteger();
        when(repo.findBeatPtSongCounts()).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            return List.of(Map.<String, Object>of("userId", 7L, "beatPtSongCount", n == 1 ? 40 : 100));
        });
        BeatPtSongCountService svc = new BeatPtSongCountService(repo);

        assertThat(svc.get()).containsEntry(7L, 40);
        assertThat(svc.get()).containsEntry(7L, 40);
        assertThat(calls.get()).isEqualTo(1);

        // アップロード後: その場では古い値を返し、裏で取り直す
        svc.invalidate();
        assertThat(svc.get()).containsEntry(7L, 40);
        for (int i = 0; i < 50 && !svc.get().containsValue(100); i++) Thread.sleep(20);
        assertThat(svc.get()).containsEntry(7L, 100);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void failedRefreshKeepsPreviousValue() throws Exception {
        ScoreRepository repo = mock(ScoreRepository.class);
        AtomicInteger calls = new AtomicInteger();
        when(repo.findBeatPtSongCounts()).thenAnswer(inv -> {
            if (calls.incrementAndGet() > 1) throw new RuntimeException("timeout");
            return List.of(Map.<String, Object>of("userId", 1L, "beatPtSongCount", 55));
        });
        BeatPtSongCountService svc = new BeatPtSongCountService(repo);
        assertThat(svc.get()).containsEntry(1L, 55);

        svc.invalidate();
        svc.get();
        for (int i = 0; i < 50 && calls.get() < 2; i++) Thread.sleep(20);
        Thread.sleep(50);
        assertThat(svc.get()).containsEntry(1L, 55);
    }
}
