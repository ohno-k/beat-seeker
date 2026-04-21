package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.entity.VirtualRival;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VirtualRivalRepository extends JpaRepository<VirtualRival, Long> {
    List<VirtualRival> findByOwner(User owner);

    Optional<VirtualRival> findByOwnerAndVersionNumAndPrefectureFileNum(
            User owner, Integer versionNum, Integer prefectureFileNum);

    void deleteByOwnerAndVersionNumAndPrefectureFileNum(
            User owner, Integer versionNum, Integer prefectureFileNum);
}
