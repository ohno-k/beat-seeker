package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.UserSongRank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserSongRankRepository extends JpaRepository<UserSongRank, Long> {
    List<UserSongRank> findByUserId(Long userId);
}
