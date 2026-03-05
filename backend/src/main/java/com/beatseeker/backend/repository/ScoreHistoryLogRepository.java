package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScoreHistoryLogRepository extends JpaRepository<ScoreHistoryLog, Long> {
    List<ScoreHistoryLog> findByUserOrderByUploadedAtAsc(User user);
}
