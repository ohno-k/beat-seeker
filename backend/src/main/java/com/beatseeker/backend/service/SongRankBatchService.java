package com.beatseeker.backend.service;

import com.beatseeker.backend.repository.ScoreRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.annotation.Transactional;

/**
 * Batch service that pre-calculates per-song rankings for all users daily.
 * Results are stored in user_song_ranks for fast lookup during score uploads.
 */
@Service
public class SongRankBatchService {

    private final ScoreRepository scoreRepository;

    public SongRankBatchService(ScoreRepository scoreRepository) {
        this.scoreRepository = scoreRepository;
    }

    /**
     * Recalculates song ranks for all users and refreshes the cache table.
     * Runs daily at 03:00.
     */
    /** Called from the admin API — runs in a separate thread so the HTTP response returns immediately. */
    @Async
    public void recalculateAllAsync() {
        recalculateAll();
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void recalculateAll() {
        // メモリ不足（OOM）を回避するため、Java側にデータを全く取得せず、
        // PostgreSQLの内部で直接全データをINSERT/置換する
        scoreRepository.truncateUserSongRanks();
        scoreRepository.insertAllUserSongRanks();
    }
}
