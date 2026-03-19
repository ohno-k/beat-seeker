package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface ScoreRepository extends JpaRepository<Score, Long> {
    List<Score> findByUserOrderByUploadedAtDesc(User user);

    List<Score> findByUserOrderByUploadedAtAsc(User user);

    Optional<Score> findFirstByUserOrderByUploadedAtDesc(User user);

    List<Score> findByUserAndSnapshotId(User user, String snapshotId);

    void deleteByUser(User user);

    java.util.Optional<Score> findFirstByUserAndTitleAndDifficultyNameOrderByUploadedAtDesc(User user, String title, String difficultyName);

    @Query(value = "SELECT s.user_id as \"userId\", s.title as \"title\", s.difficulty_name as \"difficultyName\", s.difficulty_level as \"difficultyLevel\", s.score as \"score\" FROM scores s WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA')", nativeQuery = true)
    List<Map<String, Object>> findAllUserAnotherAndLeggendariaScores();

    @Query(value = "SELECT s.user_id as \"userId\", s.title as \"title\", s.difficulty_name as \"difficultyName\", s.difficulty_level as \"difficultyLevel\", s.score as \"score\" FROM scores s WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND s.user_id IN :userIds", nativeQuery = true)
    List<Map<String, Object>> findScoresByUserIds(@Param("userIds") List<Long> userIds);

    @Query(value =
        "SELECT u.display_name as \"displayName\", s.score as \"score\", " +
        "COALESCE(latest.total_beat_pt, 0) as \"totalBeatPt\" " +
        "FROM scores s " +
        "JOIN users u ON s.user_id = u.id " +
        "LEFT JOIN (" +
        "  SELECT DISTINCT ON (user_id) user_id, total_beat_pt " +
        "  FROM score_history_logs " +
        "  ORDER BY user_id, uploaded_at DESC" +
        ") latest ON u.id = latest.user_id " +
        "WHERE s.title = :title AND s.difficulty_name = :difficultyName " +
        "ORDER BY s.score DESC",
        nativeQuery = true)
    List<Map<String, Object>> findSongRanking(@Param("title") String title, @Param("difficultyName") String difficultyName);

    @Query(value =
        "WITH best_scores AS (" +
        "  SELECT title, difficulty_name, difficulty_level, user_id, MAX(score) AS score" +
        "  FROM scores" +
        "  WHERE difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND score > 0" +
        "  GROUP BY title, difficulty_name, difficulty_level, user_id" +
        "), " +
        "all_ranks AS (" +
        "  SELECT title, difficulty_name, difficulty_level, user_id, score," +
        "    RANK() OVER (PARTITION BY title, difficulty_name ORDER BY score DESC) AS rank," +
        "    COUNT(*) OVER (PARTITION BY title, difficulty_name) AS total" +
        "  FROM best_scores" +
        ") " +
        "SELECT title as \"title\", difficulty_name as \"difficultyName\", difficulty_level as \"difficultyLevel\"," +
        "  rank as \"rank\", total as \"total\" " +
        "FROM all_ranks " +
        "WHERE user_id = :adminUserId " +
        "ORDER BY rank ASC, title ASC",
        nativeQuery = true)
    List<Map<String, Object>> findAdminSongRanks(@Param("adminUserId") Long adminUserId);
}
