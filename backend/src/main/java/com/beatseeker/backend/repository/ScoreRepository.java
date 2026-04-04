package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
        "WITH user_tier AS (" +
        "  SELECT id AS user_id," +
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
        "    END AS beat_tier," +
        "    CASE" +
        "      WHEN total_beat_pt >= 18000 THEN 0" +
        "      WHEN total_beat_pt >= 17500 THEN FLOOR((total_beat_pt - 17500)/100) + 1" +
        "      WHEN total_beat_pt >= 17000 THEN FLOOR((total_beat_pt - 17000)/100) + 1" +
        "      WHEN total_beat_pt >= 16500 THEN FLOOR((total_beat_pt - 16500)/100) + 1" +
        "      WHEN total_beat_pt >= 16000 THEN FLOOR((total_beat_pt - 16000)/100) + 1" +
        "      WHEN total_beat_pt >= 15500 THEN FLOOR((total_beat_pt - 15500)/100) + 1" +
        "      WHEN total_beat_pt >= 15000 THEN FLOOR((total_beat_pt - 15000)/100) + 1" +
        "      WHEN total_beat_pt >= 14000 THEN FLOOR((total_beat_pt - 14000)/200) + 1" +
        "      WHEN total_beat_pt >= 13000 THEN FLOOR((total_beat_pt - 13000)/200) + 1" +
        "      WHEN total_beat_pt >= 12000 THEN FLOOR((total_beat_pt - 12000)/200) + 1" +
        "      WHEN total_beat_pt >= 10000 THEN FLOOR((total_beat_pt - 10000)/400) + 1" +
        "      ELSE 0" +
        "    END AS tier_level" +
        "  FROM users" +
        "  WHERE total_beat_pt > 0" +
        "), " +
        "best_scores AS (" +
        "  SELECT s.user_id, s.title, s.difficulty_name, s.difficulty_level, MAX(s.score) AS score" +
        "  FROM scores s" +
        "  WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND s.score > 0" +
        "    AND s.difficulty_level IN (11, 12)" +
        "  GROUP BY s.user_id, s.title, s.difficulty_name, s.difficulty_level" +
        "), " +
        "agg_scores AS (" +
        "  SELECT b.title, b.difficulty_name, b.difficulty_level," +
        "    t.beat_tier," +
        "    t.tier_level," +
        "    ROUND(AVG(b.score)) as avg_score," +
        "    COUNT(*) as user_count" +
        "  FROM best_scores b " +
        "  JOIN user_tier t ON b.user_id = t.user_id " +
        "  JOIN song_definitions sd " +
        "    ON b.title = sd.title " +
        "    AND sd.revision = 'active' " +
        "    AND ((b.difficulty_name = 'ANOTHER' AND sd.difficulty = '4') OR (b.difficulty_name = 'LEGGENDARIA' AND sd.difficulty = '10')) " +
        "  WHERE (b.score * 3) >= (sd.notes * 4) " +
        "  GROUP BY b.title, b.difficulty_name, b.difficulty_level, t.beat_tier, t.tier_level" +
        ") " +
        "SELECT title as \"title\", difficulty_name as \"difficultyName\", difficulty_level as \"difficultyLevel\"," +
        "  json_agg(" +
        "    json_build_object(" +
        "      'beatTier', beat_tier," +
        "      'tierLevel', tier_level," +
        "      'avgScore', avg_score," +
        "      'userCount', user_count" +
        "    )" +
        "  )\\:\\:text as \"tierData\" " +
        "FROM agg_scores " +
        "GROUP BY title, difficulty_name, difficulty_level " +
        "ORDER BY title, difficulty_name",
        nativeQuery = true)
    List<Map<String, Object>> findRawSongScoresWithBeatTier();
    @Query(value =
        "WITH weight_map(rv, wt) AS ( " +
        "  VALUES ('11.0', 145), ('11.1', 147), ('11.2', 149), ('11.3', 151), ('11.4', 153), " +
        "  ('11.5', 155), ('11.6', 157), ('11.7', 159), ('11.8', 161), ('11.9', 163), " +
        "  ('12.0', 165), ('12.1', 167), ('12.2', 169), ('12.3', 171), ('12.4', 173), " +
        "  ('12.5', 175), ('12.6', 178), ('12.7', 181), ('12.8', 184), ('12.9', 187), ('13.0', 190) " +
        "), " +
        "song_ranks AS ( " +
        "  SELECT drs.song_title AS mapped_title, dr.rank_value, wm.wt AS weight " +
        "  FROM difficulty_ranks dr " +
        "  JOIN difficulty_rank_songs drs ON dr.id = drs.difficulty_rank_id " +
        "  LEFT JOIN weight_map wm ON wm.rv = SUBSTRING(dr.rank_value FROM '^\\d+\\.\\d+') " +
        "  WHERE dr.revision = 'active' " +
        "), " +
        "scored_data AS ( " +
        "  SELECT " +
        "    s.user_id, s.title, s.difficulty_name, " +
        "    sr.rank_value AS informal_rank, sr.weight, " +
        "    (s.score * 100.0 / NULLIF(sd.notes * 2.0, 0)) AS score_rate " +
        "  FROM scores s " +
        "  JOIN song_definitions sd ON s.title = sd.title AND sd.revision = 'active' " +
        "    AND ((s.difficulty_name = 'ANOTHER' AND sd.difficulty = '4') OR (s.difficulty_name = 'LEGGENDARIA' AND sd.difficulty = '10')) " +
        "  JOIN song_ranks sr ON sr.mapped_title = (CASE WHEN s.difficulty_name = 'LEGGENDARIA' THEN s.title || ' [L]' ELSE s.title END) " +
        "  WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND s.score > 0 " +
        "), " +
        "valid_scores AS ( " +
        "  SELECT " +
        "    user_id, title, difficulty_name, informal_rank, " +
        "    (POWER(score_rate / 100.0, 1.3) * weight) + " +
        "    (weight * CASE " +
        "      WHEN score_rate > 94.44 THEN 0.03 " +
        "      WHEN score_rate > 88.88 THEN 0.02 " +
        "      WHEN score_rate > 77.77 THEN 0.01 " +
        "      ELSE 0.0 END) AS beat_pt " +
        "  FROM scored_data " +
        "  WHERE score_rate > 66.666 AND weight IS NOT NULL " +
        "), " +
        "ranked_scores AS ( " +
        "  SELECT *, ROW_NUMBER() OVER(PARTITION BY user_id ORDER BY beat_pt DESC) AS rn " +
        "  FROM valid_scores " +
        ") " +
        "SELECT " +
        "  title AS \"title\", difficulty_name AS \"difficultyName\", informal_rank AS \"informalRank\", " +
        "  COUNT(*) AS \"userCount\", ROUND(AVG(beat_pt)::numeric, 1) AS \"avgBeatPt\", ROUND(MAX(beat_pt)::numeric, 1) AS \"maxBeatPt\" " +
        "FROM ranked_scores " +
        "WHERE rn <= 100 " +
        "GROUP BY title, difficulty_name, informal_rank " +
        "ORDER BY \"userCount\" DESC, \"avgBeatPt\" DESC", nativeQuery = true)
    List<Map<String, Object>> findAllSongRankingAggregates();

    @Query(value =
        "WITH weight_map(rv, wt) AS ( " +
        "  VALUES ('11.0', 145), ('11.1', 147), ('11.2', 149), ('11.3', 151), ('11.4', 153), " +
        "  ('11.5', 155), ('11.6', 157), ('11.7', 159), ('11.8', 161), ('11.9', 163), " +
        "  ('12.0', 165), ('12.1', 167), ('12.2', 169), ('12.3', 171), ('12.4', 173), " +
        "  ('12.5', 175), ('12.6', 178), ('12.7', 181), ('12.8', 184), ('12.9', 187), ('13.0', 190) " +
        "), " +
        "ranks AS ( " +
        "  SELECT drs.song_title AS mapped_title, wm.wt AS weight, dr.revision " +
        "  FROM difficulty_ranks dr " +
        "  JOIN difficulty_rank_songs drs ON dr.id = drs.difficulty_rank_id " +
        "  LEFT JOIN weight_map wm ON wm.rv = SUBSTRING(dr.rank_value FROM '^\\d+\\.\\d+') " +
        "  WHERE dr.revision IN ('active', 'draft') " +
        "), " +
        "base_scores AS ( " +
        "  SELECT " +
        "    s.user_id, u.display_name, u.iidx_id, " +
        "    (CASE WHEN s.difficulty_name = 'LEGGENDARIA' THEN s.title || '[L]' ELSE s.title END) AS mapped_title, " +
        "    (s.score * 100.0 / NULLIF(sd.notes * 2.0, 0)) AS score_rate " +
        "  FROM scores s " +
        "  JOIN users u on s.user_id = u.id " +
        "  JOIN song_definitions sd ON s.title = sd.title AND sd.revision = 'active' " +
        "    AND ((s.difficulty_name = 'ANOTHER' AND sd.difficulty = '4') OR (s.difficulty_name = 'LEGGENDARIA' AND sd.difficulty = '10')) " +
        "  WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND s.score > 0 " +
        "), " +
        "calc_points AS ( " +
        "  SELECT b.user_id, b.display_name, b.iidx_id, r.revision, " +
        "         (POWER(b.score_rate / 100.0, 1.3) * r.weight) + " +
        "         (r.weight * CASE WHEN b.score_rate > 94.44 THEN 0.03 WHEN b.score_rate > 88.88 THEN 0.02 WHEN b.score_rate > 77.77 THEN 0.01 ELSE 0.0 END) AS bg_pt " +
        "  FROM base_scores b " +
        "  JOIN ranks r ON b.mapped_title = r.mapped_title " +
        "  WHERE b.score_rate > 66.666 AND r.weight IS NOT NULL " +
        "), " +
        "rn_points AS ( " +
        "  SELECT user_id, display_name, iidx_id, revision, bg_pt, " +
        "         ROW_NUMBER() OVER(PARTITION BY user_id, revision ORDER BY bg_pt DESC) AS rn " +
        "  FROM calc_points " +
        "), " +
        "sum_active AS ( " +
        "  SELECT user_id, display_name, iidx_id, SUM(bg_pt) as total_active " +
        "  FROM rn_points WHERE rn <= 100 AND revision = 'active' GROUP BY user_id, display_name, iidx_id " +
        "), " +
        "sum_draft AS ( " +
        "  SELECT user_id, SUM(bg_pt) as total_draft " +
        "  FROM rn_points WHERE rn <= 100 AND revision = 'draft' GROUP BY user_id " +
        ") " +
        "SELECT a.display_name as \"displayName\", a.iidx_id as \"iidxId\", " +
        "       ROUND(a.total_active::numeric, 1) as \"currentBeatPt\", " +
        "       ROUND(COALESCE(d.total_draft, a.total_active)::numeric, 1) as \"simulatedBeatPt\", " +
        "       ROUND((COALESCE(d.total_draft, a.total_active) - a.total_active)::numeric, 1) as \"ptDelta\" " +
        "FROM sum_active a " +
        "LEFT JOIN sum_draft d ON a.user_id = d.user_id " +
        "ORDER BY \"simulatedBeatPt\" DESC", nativeQuery = true)
    List<Map<String, Object>> calculateDifficultySimulation();

    @Modifying
    @Query(value =
        "INSERT INTO user_song_ranks (user_id, title, difficulty_name, difficulty_level, rank, total, calculated_at) " +
        "WITH best_scores AS ( " +
        "  SELECT title, difficulty_name, difficulty_level, user_id, MAX(score) AS score " +
        "  FROM scores " +
        "  WHERE difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND score > 0 " +
        "  GROUP BY title, difficulty_name, difficulty_level, user_id " +
        "), " +
        "all_ranks AS ( " +
        "  SELECT title, difficulty_name, difficulty_level, user_id, score, " +
        "    RANK() OVER (PARTITION BY title, difficulty_name ORDER BY score DESC) AS rank, " +
        "    COUNT(*) OVER (PARTITION BY title, difficulty_name) AS total " +
        "  FROM best_scores " +
        ") " +
        "SELECT user_id, title, difficulty_name, difficulty_level, rank, total, NOW() " +
        "FROM all_ranks", nativeQuery = true)
    void insertAllUserSongRanks();

    @Modifying
    @Query(value = "TRUNCATE TABLE user_song_ranks", nativeQuery = true)
    void truncateUserSongRanks();
}
