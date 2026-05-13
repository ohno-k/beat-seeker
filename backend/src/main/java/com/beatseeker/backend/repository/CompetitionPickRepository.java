package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.entity.CompetitionMatch;
import com.beatseeker.backend.entity.CompetitionParticipant;
import com.beatseeker.backend.entity.CompetitionPick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@link CompetitionPick} (各プレイヤーの試合別自選曲) を扱うリポジトリ。
 *
 * 1 (プレイヤー, 試合) で 1 件、最大 12 件 (1 人 4 matchup × 3 戦) 想定。
 * 大会全体では 20 プレイヤー × 12 = 240 件まで。
 */
public interface CompetitionPickRepository extends JpaRepository<CompetitionPick, Long> {

    /** あるプレイヤーが特定試合に提出済みの自選曲を 1 件引く。 */
    Optional<CompetitionPick> findByMatchAndParticipant(CompetitionMatch match, CompetitionParticipant participant);

    /** あるプレイヤーの全自選曲 (最大 12 件) を取得する。 */
    List<CompetitionPick> findByParticipant(CompetitionParticipant participant);

    /**
     * 指定大会の全自選曲をまとめて取得する。Reveal フェーズでの一括取得に使う。
     */
    @Query("SELECT p FROM CompetitionPick p WHERE p.match.matchup.competition = :competition")
    List<CompetitionPick> findAllByCompetition(Competition competition);
}
