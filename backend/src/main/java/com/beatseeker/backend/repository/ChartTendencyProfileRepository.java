package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.ChartTendencyProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChartTendencyProfileRepository extends JpaRepository<ChartTendencyProfile, String> {

    Optional<ChartTendencyProfile> findByTitleAndDifficulty(String title, String difficulty);

    List<ChartTendencyProfile> findByDifficulty(String difficulty);

    List<ChartTendencyProfile> findByLevel(Integer level);

    List<ChartTendencyProfile> findByLevelAndDifficulty(Integer level, String difficulty);

    @Query("SELECT p.textage FROM ChartTendencyProfile p")
    List<String> findAllIds();

    /** タグを含む曲を検索 */
    @Query("SELECT p FROM ChartTendencyProfile p WHERE p.tagsJson LIKE %:tag%")
    List<ChartTendencyProfile> findByTagContaining(@Param("tag") String tag);

    /** eff16が近い曲を取得 (傾向類似検索用) */
    @Query("""
            SELECT p FROM ChartTendencyProfile p
            WHERE p.difficulty = :difficulty
              AND p.dominantEff16 BETWEEN :minEff AND :maxEff
            """)
    List<ChartTendencyProfile> findByDifficultyAndEff16Range(
            @Param("difficulty") String difficulty,
            @Param("minEff") double minEff,
            @Param("maxEff") double maxEff);
}
