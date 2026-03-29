package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.TierVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface TierVoteRepository extends JpaRepository<TierVote, Long> {

    List<TierVote> findByUserId(Long userId);

    Optional<TierVote> findByUserIdAndTitleAndDifficultyName(Long userId, String title, String difficultyName);

    @Query(value =
        "SELECT title as \"title\", difficulty_name as \"difficultyName\", vote as \"vote\", COUNT(*) as \"count\" " +
        "FROM tier_votes GROUP BY title, difficulty_name, vote",
        nativeQuery = true)
    List<Map<String, Object>> findAggregatedVotes();
}
