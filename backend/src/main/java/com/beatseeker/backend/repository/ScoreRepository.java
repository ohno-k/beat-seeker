package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
}
