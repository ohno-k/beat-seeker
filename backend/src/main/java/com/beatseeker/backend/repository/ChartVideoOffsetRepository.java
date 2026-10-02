package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.ChartVideoOffset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChartVideoOffsetRepository extends JpaRepository<ChartVideoOffset, String> {

    List<ChartVideoOffset> findByTitleAndVideoId(String title, String videoId);
}
