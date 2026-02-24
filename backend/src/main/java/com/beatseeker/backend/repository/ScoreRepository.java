package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScoreRepository extends JpaRepository<Score, Long> {
    List<Score> findByUserOrderByUploadedAtDesc(User user);

    void deleteByUser(User user);
}
