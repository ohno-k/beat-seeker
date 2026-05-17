package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.entity.CompetitionIndividualMatch;
import com.beatseeker.backend.entity.CompetitionIndividualMatchSlot;
import com.beatseeker.backend.entity.CompetitionParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * 【Repository の役割】 個人戦試合のスロット ({@link CompetitionIndividualMatchSlot}) を扱うリポジトリ。
 *
 * 1 試合あたり 4 件。試合単位 (集計用)・参加者単位 (本人スケジュール表示用)・大会単位 (cascade 削除用)
 * の取得を提供する。
 */
public interface CompetitionIndividualMatchSlotRepository extends JpaRepository<CompetitionIndividualMatchSlot, Long> {

    /**
     * 【メソッドの役割】 1 試合の 4 スロットを {@code slotPosition} 昇順で取得する。
     */
    List<CompetitionIndividualMatchSlot> findByMatchOrderBySlotPositionAsc(CompetitionIndividualMatch match);

    /**
     * 【メソッドの役割】 指定参加者が出場する全スロットを取得する (大会内での担当試合一覧用)。
     */
    List<CompetitionIndividualMatchSlot> findByParticipant(CompetitionParticipant participant);

    /**
     * 【メソッドの役割】 指定大会のすべてのスロットを取得 (集計 / cascade 削除用)。
     */
    @Query("SELECT s FROM CompetitionIndividualMatchSlot s WHERE s.match.competition = :competition")
    List<CompetitionIndividualMatchSlot> findAllByCompetition(Competition competition);
}
