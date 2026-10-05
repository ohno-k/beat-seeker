package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.LeagueSongBan;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 【Repository の役割】 {@code LeagueSongBan}（リーグの楽曲 BAN）を扱うリポジトリ。
 *
 * ユーザー × DIVISION × タイトルで 1 行。
 */
public interface LeagueSongBanRepository extends JpaRepository<LeagueSongBan, Long> {

    /**
     * 【メソッドの役割】 ユーザーが指定 DIVISION に登録した BAN を返す。
     *
     * @param user 対象ユーザー
     * @param tier DIVISION（0=LEGEND .. 10）
     * @return BAN 一覧
     */
    List<LeagueSongBan> findByUserAndTierOrderByIdAsc(User user, Integer tier);

    /**
     * 【メソッドの役割】 ユーザーの DIVISION ごとの BAN 数を返す（モーダルの DIVISION 切り替えに件数を出す）。
     *
     * @param user 対象ユーザー
     * @return {@code [tier, 件数]} の行
     */
    @Query("select b.tier, count(b) from LeagueSongBan b where b.user = :user group by b.tier")
    List<Object[]> countByTier(@Param("user") User user);

    /**
     * 【メソッドの役割】 指定 DIVISION の、複数ユーザー分の BAN を返す（課題曲の抽選でグループ分を一括取得）。
     *
     * @param tier    DIVISION（卓の DIVISION）
     * @param userIds グループのメンバーのユーザー ID
     * @return BAN 一覧
     */
    @Query("select b from LeagueSongBan b where b.tier = :tier and b.user.id in :userIds")
    List<LeagueSongBan> findByTierAndUserIds(@Param("tier") Integer tier, @Param("userIds") Collection<Long> userIds);

    /**
     * 【メソッドの役割】 ユーザーの指定 DIVISION の BAN を全削除する（登録し直しの前に使う）。
     *
     * @param user 対象ユーザー
     * @param tier DIVISION
     */
    @Modifying
    @Query("delete from LeagueSongBan b where b.user = :user and b.tier = :tier")
    void deleteByUserAndTier(@Param("user") User user, @Param("tier") Integer tier);
}
