package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.entity.CompetitionMatch;
import com.beatseeker.backend.entity.CompetitionParticipant;
import com.beatseeker.backend.entity.CompetitionStrategyUse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@link CompetitionStrategyUse} (StrategyCard 使用意思決定) を扱うリポジトリ。
 *
 * 1 試合あたり最大 2 件 (両サイドそれぞれ使うかどうか)、大会全体で最大 60 件想定。
 */
public interface CompetitionStrategyUseRepository extends JpaRepository<CompetitionStrategyUse, Long> {

    /**
     * 【メソッドの役割】 ある試合における、特定プレイヤーのカード使用意思決定を 1 件引く。
     */
    Optional<CompetitionStrategyUse> findByMatchAndUsedByParticipant(
            CompetitionMatch match, CompetitionParticipant usedByParticipant);

    /**
     * 【メソッドの役割】 指定試合の両サイド分のカード使用レコードを取得する (0〜2 件)。
     */
    List<CompetitionStrategyUse> findByMatch(CompetitionMatch match);

    /**
     * 【メソッドの役割】 指定大会の全カード使用レコードを取得する。Reveal で一括判定に使う。
     */
    @Query("SELECT s FROM CompetitionStrategyUse s WHERE s.match.matchup.competition = :competition")
    List<CompetitionStrategyUse> findAllByCompetition(Competition competition);
}
