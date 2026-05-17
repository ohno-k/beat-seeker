package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.entity.CompetitionIndividualMatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【Repository の役割】 個人戦試合 ({@link CompetitionIndividualMatch}) を扱うリポジトリ。
 *
 * 1 大会あたり 12 人 = 18 + 3 = 21 試合 / 16 人 = 20 + 4 = 24 試合。
 */
public interface CompetitionIndividualMatchRepository extends JpaRepository<CompetitionIndividualMatch, Long> {

    /**
     * 【メソッドの役割】 指定大会の全試合を {@code matchOrder} 昇順で取得する。
     */
    List<CompetitionIndividualMatch> findByCompetitionOrderByMatchOrderAsc(Competition competition);
}
