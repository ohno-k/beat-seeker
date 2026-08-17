package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.LeagueMember;
import com.beatseeker.backend.entity.LeagueWeek;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@code LeagueMember}（週次グループ配置と確定成績）を扱うリポジトリ。
 *
 * 週 × ユーザーで 1 行。tier + groupIndex がグループを表す。
 * {@link JpaRepository}{@code <LeagueMember, Long>} を継承しており、基本 CRUD は自動提供。
 */
public interface LeagueMemberRepository extends JpaRepository<LeagueMember, Long> {

    /**
     * 【メソッドの役割】 指定週の全メンバーを取得する。
     *
     * 週次締め処理（全グループの成績凍結）や overview（階級/グループ構成）に使う。
     *
     * @param week 対象週
     * @return メンバー一覧
     */
    List<LeagueMember> findByWeek(LeagueWeek week);

    /**
     * 【メソッドの役割】 指定週の指定ユーザーのメンバーシップを取得する。
     *
     * 「自分のグループの順位表」を引くための起点。(week, user) はユニーク。
     *
     * @param week 対象週
     * @param user 対象ユーザー
     * @return 見つかればメンバーシップ、なければ empty（= その週は不参加）
     */
    Optional<LeagueMember> findByWeekAndUser(LeagueWeek week, User user);

    /**
     * 【メソッドの役割】 指定週の 1 グループ分のメンバーを取得する。
     *
     * 順位計算（LeagueStandingsService）の母集団取得に使う。
     *
     * @param week       対象週
     * @param tier       階級
     * @param groupIndex グループ番号（0 始まり）
     * @return グループのメンバー一覧
     */
    List<LeagueMember> findByWeekAndTierAndGroupIndex(LeagueWeek week, Integer tier, Integer groupIndex);

    /**
     * 【メソッドの役割】 指定週のメンバーを全削除する。
     *
     * 編成のやり直し（テスト・障害復旧）時に古い配置が残らないようにする防御用。
     *
     * @param week 対象週
     */
    void deleteByWeek(LeagueWeek week);

    /**
     * 【メソッドの役割】 指定ユーザーの締め済み週の成績履歴を新しい順に取得する。
     *
     * 過去週の履歴表示（GET /api/league/history）に使う。週を JOIN FETCH して
     * N+1 を避ける。
     *
     * @param user   対象ユーザー
     * @param ladder ラダー種別
     * @return 締め済み週のメンバーシップ一覧（週の開始日時降順）
     */
    @Query("SELECT m FROM LeagueMember m JOIN FETCH m.week w " +
           "WHERE m.user = :user AND w.ladderType = :ladder AND w.status = 'closed' " +
           "ORDER BY w.startsAt DESC")
    List<LeagueMember> findClosedHistory(@Param("user") User user, @Param("ladder") String ladder);

    /**
     * 【メソッドの役割】 指定ラダーの全週について、週 × 卓 × グループ単位の人数を一括集計する。
     *
     * 管理者の全リーグ履歴（GET /api/league/admin/history）で、各週の DIVISION / グループ構成を
     * 組み立てるために使う。週ごとに {@link #findByWeek} を回すと週数ぶんクエリが増える（N+1）ので、
     * 1 クエリでまとめて数える。
     *
     * @param ladder ラダー種別
     * @return {@code [週ID, tier, groupIndex, 人数]} の配列一覧（週ID 降順 → tier 昇順 → グループ昇順）
     */
    @Query("SELECT m.week.id, m.tier, m.groupIndex, COUNT(m) FROM LeagueMember m " +
           "WHERE m.week.ladderType = :ladder " +
           "GROUP BY m.week.id, m.tier, m.groupIndex " +
           "ORDER BY m.week.id DESC, m.tier ASC, m.groupIndex ASC")
    List<Object[]> countByWeekTierGroup(@Param("ladder") String ladder);
}
