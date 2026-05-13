package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.entity.CompetitionMatch;
import com.beatseeker.backend.entity.CompetitionMatchup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * 【Repository の役割】 {@link CompetitionMatch} (個別試合) を扱うリポジトリ。
 *
 * 1 matchup 3 件、大会全体で 30 件想定。Reveal フェーズでの一括取得や TL の
 * ラインアップ管理画面でまとめて読みたいので、大会単位の取得メソッドも用意する。
 */
public interface CompetitionMatchRepository extends JpaRepository<CompetitionMatch, Long> {

    /**
     * 【メソッドの役割】 1 つの matchup に紐づく 3 試合 (先鋒/中堅/大将) を取得する。
     * 戦の順序は {@code matchKind} 文字列のアルファベット順では意味のある順にならないため、
     * 呼び出し側で {@code vanguard → middle → captain} の固定順に並べ替える運用とする。
     */
    List<CompetitionMatch> findByMatchupOrderByIdAsc(CompetitionMatchup matchup);

    /**
     * 【メソッドの役割】 指定大会の全 30 試合を取得する。
     * {@code matchups} 経由でアクセスするための JPQL。Reveal/集計時に便利。
     */
    @Query("SELECT m FROM CompetitionMatch m WHERE m.matchup.competition = :competition")
    List<CompetitionMatch> findAllByCompetition(Competition competition);
}
