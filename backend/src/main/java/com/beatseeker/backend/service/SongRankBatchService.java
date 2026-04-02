package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.UserSongRank;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.UserSongRankRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Batch service that pre-calculates per-song rankings for all users daily.
 * Results are stored in user_song_ranks for fast lookup during score uploads.
 */
@Service
public class SongRankBatchService {

    private final ScoreRepository scoreRepository;
    private final UserSongRankRepository userSongRankRepository;

    public SongRankBatchService(ScoreRepository scoreRepository,
                                UserSongRankRepository userSongRankRepository) {
        this.scoreRepository = scoreRepository;
        this.userSongRankRepository = userSongRankRepository;
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
        List<Map<String, Object>> allRanks = scoreRepository.findAllUserSongRanks();

        List<UserSongRank> newRanks = new ArrayList<>(allRanks.size());
        LocalDateTime now = LocalDateTime.now();

        for (Map<String, Object> row : allRanks) {
            UserSongRank r = new UserSongRank();
            r.setUserId(((Number) row.get("userId")).longValue());
            r.setTitle((String) row.get("title"));
            r.setDifficultyName((String) row.get("difficultyName"));
            Object level = row.get("difficultyLevel");
            r.setDifficultyLevel(level != null ? ((Number) level).intValue() : null);
            r.setRank(((Number) row.get("rank")).intValue());
            r.setTotal(((Number) row.get("total")).intValue());
            r.setCalculatedAt(now);
            newRanks.add(r);
        }

        userSongRankRepository.deleteAllInBatch();
        userSongRankRepository.saveAll(newRanks);
    }
}
