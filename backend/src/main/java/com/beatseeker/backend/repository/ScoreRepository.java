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

    @Query("SELECT s FROM Score s WHERE s.user = :user AND s.title IN :titles AND s.difficultyName IN :difficulties")
    List<Score> findByUserAndTitlesAndDifficulties(@Param("user") User user, @Param("titles") List<String> titles, @Param("difficulties") List<String> difficulties);

    @Query(value = "SELECT s.user_id as \"userId\", s.title as \"title\", s.difficulty_name as \"difficultyName\", s.difficulty_level as \"difficultyLevel\", s.score as \"score\" FROM scores s WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA')", nativeQuery = true)
    List<Map<String, Object>> findAllUserAnotherAndLeggendariaScores();

    @Query(value = "SELECT s.user_id as \"userId\", u.display_name as \"displayName\", u.iidx_id as \"iidxId\", s.title as \"title\", s.difficulty_name as \"difficultyName\", s.score as \"score\" FROM scores s JOIN users u ON s.user_id = u.id WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA')", nativeQuery = true)
    List<Map<String, Object>> findAllUserAnotherAndLeggendariaScoresWithUserInfo();

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
        "WHERE user_id = :userId " +
        "ORDER BY rank ASC, title ASC",
        nativeQuery = true)
    List<Map<String, Object>> findUserSongRanks(@Param("userId") Long userId);

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
        "SELECT user_id as \"userId\", title as \"title\", difficulty_name as \"difficultyName\", difficulty_level as \"difficultyLevel\"," +
        "  rank as \"rank\", total as \"total\" " +
        "FROM all_ranks " +
        "ORDER BY user_id, rank ASC, title ASC",
        nativeQuery = true)
    List<Map<String, Object>> findAllUserSongRanks();

    @Query(value =
        "WITH user_latest_pt AS (" +
        "  SELECT DISTINCT ON (user_id) user_id, total_beat_pt" +
        "  FROM score_history_logs" +
        "  WHERE total_beat_pt > 0" +
        "  ORDER BY user_id, uploaded_at DESC" +
        "), " +
        "user_tier AS (" +
        "  SELECT user_id," +
        "    CASE" +
        "      WHEN total_beat_pt >= 18000 THEN 'Legend'" +
        "      WHEN total_beat_pt >= 17500 THEN 'Mythic'" +
        "      WHEN total_beat_pt >= 17000 THEN 'Ancient'" +
        "      WHEN total_beat_pt >= 16500 THEN 'Master'" +
        "      WHEN total_beat_pt >= 16000 THEN 'Elite'" +
        "      WHEN total_beat_pt >= 15500 THEN 'Commander'" +
        "      WHEN total_beat_pt >= 15000 THEN 'Veteran'" +
        "      WHEN total_beat_pt >= 14000 THEN 'Expert'" +
        "      WHEN total_beat_pt >= 13000 THEN 'Advanced'" +
        "      WHEN total_beat_pt >= 12000 THEN 'Intermediate'" +
        "      WHEN total_beat_pt >= 10000 THEN 'Novice'" +
        "      ELSE 'Beginner'" +
        "    END AS beat_tier" +
        "  FROM user_latest_pt" +
        "), " +
        "best_scores AS (" +
        "  SELECT s.user_id, s.title, s.difficulty_name, s.difficulty_level, MAX(s.score) AS score" +
        "  FROM scores s" +
        "  WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND s.score > 0" +
        "    AND s.difficulty_level IN (11, 12)" +
        "  GROUP BY s.user_id, s.title, s.difficulty_name, s.difficulty_level" +
        ") " +
        "SELECT b.title as \"title\", b.difficulty_name as \"difficultyName\", b.difficulty_level as \"difficultyLevel\"," +
        "  t.beat_tier as \"beatTier\", b.score as \"score\" " +
        "FROM best_scores b " +
        "JOIN user_tier t ON b.user_id = t.user_id " +
        "ORDER BY b.title, b.difficulty_name",
        nativeQuery = true)
    List<Map<String, Object>> findRawSongScoresWithBeatTier();
}
