package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.TierComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Map;

public interface TierCommentRepository extends JpaRepository<TierComment, Long> {
    
    List<TierComment> findByTitleAndDifficultyNameOrderByCreatedAtAsc(String title, String difficultyName);
    
    @Query("SELECT new map(c.title as title, c.difficultyName as difficultyName, COUNT(c) as commentCount, MAX(c.createdAt) as latestCommentAt) " +
           "FROM TierComment c GROUP BY c.title, c.difficultyName")
    List<Map<String, Object>> findCommentStats();
}
