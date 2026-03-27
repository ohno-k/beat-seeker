package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.DifficultyRank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DifficultyRankRepository extends JpaRepository<DifficultyRank, Long> {

    List<DifficultyRank> findByRevisionOrderBySortOrderAsc(String revision);

    long countByRevision(String revision);

    @Modifying
    @Query("DELETE FROM DifficultyRank d WHERE d.revision = :revision")
    void deleteByRevision(String revision);

    @Modifying
    @Query("UPDATE DifficultyRank d SET d.revision = :newRevision WHERE d.revision = :oldRevision")
    void updateRevision(String oldRevision, String newRevision);
}
