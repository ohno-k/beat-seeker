package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.entity.CompetitionMatchup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【Repository の役割】 {@link CompetitionMatchup} (チーム vs チーム 1 対戦カード) を扱うリポジトリ。
 */
public interface CompetitionMatchupRepository extends JpaRepository<CompetitionMatchup, Long> {

    /**
     * 【メソッドの役割】 指定大会の全 matchup を表示順で取得する。
     * 5 チーム総当たりで常に 10 件返る想定。
     */
    List<CompetitionMatchup> findByCompetitionOrderByMatchupOrderAsc(Competition competition);
}
