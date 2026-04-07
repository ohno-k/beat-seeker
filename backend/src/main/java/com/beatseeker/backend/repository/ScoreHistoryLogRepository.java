package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ScoreHistoryLogRepository extends JpaRepository<ScoreHistoryLog, Long> {
    List<ScoreHistoryLog> findByUserOrderByUploadedAtAsc(User user);
    Optional<ScoreHistoryLog> findFirstByUserOrderByUploadedAtDesc(User user);

    @Query(value =
            "WITH current_ranks AS ( " +
            "    SELECT user_id, total_beat_pt, uploaded_at, " +
            "           RANK() OVER (ORDER BY total_beat_pt DESC) AS rank_pos " +
            "    FROM ( " +
            "        SELECT DISTINCT ON (user_id) user_id, total_beat_pt, uploaded_at " +
            "        FROM score_history_logs " +
            "        ORDER BY user_id, uploaded_at DESC " +
            "    ) AS latest " +
            "), " +
            "previous_ranks AS ( " +
            "    SELECT user_id, " +
            "           RANK() OVER (ORDER BY total_beat_pt DESC) AS rank_pos " +
            "    FROM ( " +
            "        SELECT DISTINCT ON (user_id) user_id, total_beat_pt, uploaded_at " +
            "        FROM score_history_logs " +
            "        WHERE uploaded_at < CURRENT_DATE " +
            "        ORDER BY user_id, uploaded_at DESC " +
            "    ) AS prev_latest " +
            ") " +
            "SELECT u.display_name AS \"displayName\", u.iidx_id AS \"iidxId\", " +
            "       cr.total_beat_pt AS \"totalBeatPt\", " +
            "       cr.uploaded_at AS \"lastUpdatedAt\", " +
            "       COALESCE(u.is_supporter, false) AND COALESCE(u.show_supporter_border, true) AS \"isSupporter\"," +
            "       CASE WHEN pr.rank_pos IS NULL THEN NULL " +
            "            ELSE (pr.rank_pos - cr.rank_pos)::integer END AS \"rankChange\" " +
            "FROM current_ranks cr " +
            "JOIN users u ON cr.user_id = u.id " +
            "LEFT JOIN previous_ranks pr ON cr.user_id = pr.user_id " +
            "ORDER BY cr.rank_pos", nativeQuery = true)
    List<Map<String, Object>> getGlobalRanking();

    @Query(value =
            "WITH current_ranks AS ( " +
            "    SELECT user_id, total_precision_pt, " +
            "           RANK() OVER (ORDER BY total_precision_pt DESC) AS rank_pos " +
            "    FROM ( " +
            "        SELECT DISTINCT ON (user_id) user_id, total_precision_pt, uploaded_at " +
            "        FROM score_history_logs " +
            "        WHERE total_precision_pt > 0 " +
            "        ORDER BY user_id, uploaded_at DESC " +
            "    ) AS latest " +
            "), " +
            "previous_ranks AS ( " +
            "    SELECT user_id, " +
            "           RANK() OVER (ORDER BY total_precision_pt DESC) AS rank_pos " +
            "    FROM ( " +
            "        SELECT DISTINCT ON (user_id) user_id, total_precision_pt, uploaded_at " +
            "        FROM score_history_logs " +
            "        WHERE total_precision_pt > 0 AND uploaded_at < CURRENT_DATE " +
            "        ORDER BY user_id, uploaded_at DESC " +
            "    ) AS prev_latest " +
            ") " +
            "SELECT u.display_name AS \"displayName\", u.iidx_id AS \"iidxId\", " +
            "       cr.total_precision_pt AS \"totalPrecisionPt\", " +
            "       COALESCE(u.is_supporter, false) AND COALESCE(u.show_supporter_border, true) AS \"isSupporter\"," +
            "       CASE WHEN pr.rank_pos IS NULL THEN NULL " +
            "            ELSE (pr.rank_pos - cr.rank_pos)::integer END AS \"rankChange\" " +
            "FROM current_ranks cr " +
            "JOIN users u ON cr.user_id = u.id " +
            "LEFT JOIN previous_ranks pr ON cr.user_id = pr.user_id " +
            "ORDER BY cr.rank_pos", nativeQuery = true)
    List<Map<String, Object>> getPrecisionRanking();

    @Query(value =
            "WITH current_ranks AS ( " +
            "    SELECT user_id, total_rate_pt, uploaded_at, " +
            "           RANK() OVER (ORDER BY total_rate_pt DESC) AS rank_pos " +
            "    FROM ( " +
            "        SELECT DISTINCT ON (user_id) user_id, total_rate_pt, uploaded_at " +
            "        FROM score_history_logs " +
            "        WHERE total_rate_pt > 0 " +
            "        ORDER BY user_id, uploaded_at DESC " +
            "    ) AS latest " +
            "), " +
            "previous_ranks AS ( " +
            "    SELECT user_id, " +
            "           RANK() OVER (ORDER BY total_rate_pt DESC) AS rank_pos " +
            "    FROM ( " +
            "        SELECT DISTINCT ON (user_id) user_id, total_rate_pt, uploaded_at " +
            "        FROM score_history_logs " +
            "        WHERE total_rate_pt > 0 AND uploaded_at < CURRENT_DATE " +
            "        ORDER BY user_id, uploaded_at DESC " +
            "    ) AS prev_latest " +
            ") " +
            "SELECT u.display_name AS \"displayName\", u.iidx_id AS \"iidxId\", " +
            "       cr.total_rate_pt AS \"totalRatePt\", " +
            "       cr.uploaded_at AS \"lastUpdatedAt\", " +
            "       COALESCE(u.is_supporter, false) AND COALESCE(u.show_supporter_border, true) AS \"isSupporter\"," +
            "       CASE WHEN pr.rank_pos IS NULL THEN NULL " +
            "            ELSE (pr.rank_pos - cr.rank_pos)::integer END AS \"rankChange\" " +
            "FROM current_ranks cr " +
            "JOIN users u ON cr.user_id = u.id " +
            "LEFT JOIN previous_ranks pr ON cr.user_id = pr.user_id " +
            "ORDER BY cr.rank_pos", nativeQuery = true)
    List<Map<String, Object>> getRateTierRanking();
}
