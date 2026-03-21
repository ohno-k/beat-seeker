package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.ActivityLog;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    @Query("SELECT a FROM ActivityLog a JOIN FETCH a.user ORDER BY a.createdAt DESC")
    List<ActivityLog> findRecentActivity(PageRequest pageable);
}
