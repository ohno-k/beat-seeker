package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.SongVideo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SongVideoRepository extends JpaRepository<SongVideo, String> {
}
