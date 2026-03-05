package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.OptionVote;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OptionVoteRepository extends JpaRepository<OptionVote, Long> {

    List<OptionVote> findByTitleAndDifficultyName(String title, String difficultyName);

    Optional<OptionVote> findByUserAndTitleAndDifficultyName(User user, String title, String difficultyName);
}
