package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.SongDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SongDefinitionRepository extends JpaRepository<SongDefinition, Long> {

    List<SongDefinition> findByRevision(String revision);

    long countByRevision(String revision);

    @Modifying
    @Query("DELETE FROM SongDefinition s WHERE s.revision = :revision")
    void deleteByRevision(String revision);

    @Modifying
    @Query("UPDATE SongDefinition s SET s.revision = :newRevision WHERE s.revision = :oldRevision")
    void updateRevision(String oldRevision, String newRevision);
}
