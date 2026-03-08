package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Map;

public interface ScoreHistoryLogRepository extends JpaRepository<ScoreHistoryLog, Long> {
    List<ScoreHistoryLog> findByUserOrderByUploadedAtAsc(User user);

    @Query(value = "SELECT u.display_name as \"displayName\", u.iidx_id as \"iidxId\", latest_scores.total_beat_pt as \"totalBeatPt\" "
            + " FROM ( " +
            "    SELECT DISTINCT ON (user_id) user_id, total_beat_pt, uploaded_at " +
            "    FROM score_history_logs " +
            "    ORDER BY user_id, uploaded_at DESC " +
            ") AS latest_scores " +
            "JOIN users u ON latest_scores.user_id = u.id " +
            "ORDER BY latest_scores.total_beat_pt DESC", nativeQuery = true)
    List<Map<String, Object>> getGlobalRanking();
}
