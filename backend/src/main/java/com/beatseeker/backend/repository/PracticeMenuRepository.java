package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.PracticeMenu;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 【リポジトリの役割】 練習メニュー（{@link PracticeMenu}）の永続化アクセス。
 *
 * 週は「ユーザー × week_start」で一意なので、基本の引き方は
 * 「今週のメニューを取る」「直近 N 週を取る」の 2 通りだけ。
 */
public interface PracticeMenuRepository extends JpaRepository<PracticeMenu, Long> {

    /**
     * 指定ユーザーの指定週のメニューを取得する。
     * items は LAZY なので、必要な呼び出し側は {@link #findWithItems} を使う。
     */
    Optional<PracticeMenu> findByUserAndWeekStart(User user, LocalDate weekStart);

    /**
     * 【メソッドの役割】 指定週のメニューを items ごと 1 クエリで取得する。
     *
     * {@code LEFT JOIN FETCH} で N+1 を避ける。items が空のメニューもあり得る
     * （参照譜面が足りず 1 件も出せなかったケース）ので LEFT で結合する。
     */
    @Query("SELECT DISTINCT m FROM PracticeMenu m LEFT JOIN FETCH m.items " +
           "WHERE m.user = :user AND m.weekStart = :weekStart")
    Optional<PracticeMenu> findWithItems(@Param("user") User user,
                                         @Param("weekStart") LocalDate weekStart);

    /**
     * 【メソッドの役割】 指定ユーザーの直近のメニューを新しい順に取得する。
     *
     * 「直前に締めた週の振り返り」と「連続 2 週の未着手判定」「3 週連続の再提示禁止」で使う。
     * 呼び出し側は必要な件数だけ先頭から使う。
     */
    @Query("SELECT DISTINCT m FROM PracticeMenu m LEFT JOIN FETCH m.items " +
           "WHERE m.user = :user AND m.weekStart < :weekStart ORDER BY m.weekStart DESC")
    List<PracticeMenu> findRecentBefore(@Param("user") User user,
                                        @Param("weekStart") LocalDate weekStart);

    /** 週締めバッチ用: 指定週で status = OPEN のまま残っているメニュー。 */
    List<PracticeMenu> findByWeekStartAndStatus(LocalDate weekStart, String status);
}
