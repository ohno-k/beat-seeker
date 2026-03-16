package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.ArenaMatch;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArenaMatchRepository extends JpaRepository<ArenaMatch, Long> {
    List<ArenaMatch> findByUserOrderByMatchDateDesc(User user);
    boolean existsByUserAndMatchDate(User user, String matchDate);
}
