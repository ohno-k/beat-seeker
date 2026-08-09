package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.LeagueWeek;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@code LeagueWeek}（リーグの週次開催レコード）を扱うリポジトリ。
 *
 * ラダーごとに draft（編成前）/ active（開催中）/ closed（締め済み）の週を管理する。
 * 運用上の不変条件は「1 ラダーにつき draft と active は同時に高々 1 件ずつ」。
 * {@link JpaRepository}{@code <LeagueWeek, Long>} を継承しており、基本 CRUD は自動提供。
 */
public interface LeagueWeekRepository extends JpaRepository<LeagueWeek, Long> {

    /**
     * 【メソッドの役割】 指定ラダー・指定ステータスの週を 1 件取得する。
     *
     * 「現在 active な週」「編成待ちの draft 週」の取得に使う。上記の不変条件により
     * 高々 1 件だが、防御的に startsAt 降順の先頭を返す。
     *
     * @param ladderType ラダー種別（"score" / "bp"）
     * @param status     ステータス（"draft" / "active" / "closed"）
     * @return 見つかれば週、なければ empty
     */
    Optional<LeagueWeek> findFirstByLadderTypeAndStatusOrderByStartsAtDesc(String ladderType, String status);

    /**
     * 【メソッドの役割】 同一ラダー・同一開始日時の週が既に存在するか判定する。
     *
     * cron の二重発火や手動トリガー併用時の二重作成防止（アプリ側の事前チェック）。
     * 最終防衛線は (ladder_type, starts_at) のユニーク制約。
     *
     * @param ladderType ラダー種別
     * @param startsAt   週の開始日時（JST 壁時計）
     * @return 存在すれば true
     */
    boolean existsByLadderTypeAndStartsAt(String ladderType, LocalDateTime startsAt);

    /**
     * 【メソッドの役割】 指定ラダーの最新の週（ステータス不問）を 1 件取得する。
     *
     * 次週の開始日時の算出（最新週の endsAt から継続）に使う。
     *
     * @param ladderType ラダー種別
     * @return 最新の週。1 件も無ければ empty
     */
    Optional<LeagueWeek> findFirstByLadderTypeOrderByStartsAtDesc(String ladderType);

    /**
     * 【メソッドの役割】 指定ラダーの締め済み週を新しい順に取得する。
     *
     * 過去週の履歴表示（GET /api/league/history）に使う。
     *
     * @param ladderType ラダー種別
     * @param status     ステータス（通常 "closed"）
     * @return 週一覧（開始日時降順）
     */
    List<LeagueWeek> findByLadderTypeAndStatusOrderByStartsAtDesc(String ladderType, String status);

    /**
     * 【メソッドの役割】 指定ラダーで採番済みの最大の開催回番号を取得する。
     *
     * 次週の番号（最大 + 1）の採番に使う。まだ 1 週も採番していなければ empty
     * （＝プレシーズンしか無い状態）。
     *
     * @param ladderType ラダー種別
     * @return 最大の {@code weekNo}。採番済みの週が無ければ empty
     */
    @Query("select max(w.weekNo) from LeagueWeek w where w.ladderType = :ladderType")
    Optional<Integer> findMaxWeekNo(@Param("ladderType") String ladderType);
}
