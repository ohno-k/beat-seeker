package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.LeagueEntry;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@code LeagueEntry}（リーグ参加エントリー）を扱うリポジトリ。
 *
 * 1 ユーザー × 1 ラダー（"score" / "bp"）で 1 行の参加登録を管理する。
 * {@link JpaRepository}{@code <LeagueEntry, Long>} を継承しており、基本 CRUD は自動提供。
 */
public interface LeagueEntryRepository extends JpaRepository<LeagueEntry, Long> {

    /**
     * 【メソッドの役割】 指定ユーザーの指定ラダーのエントリーを取得する。
     *
     * join / leave の対象行の特定に使う。(user, ladderType) にはユニーク制約があるため高々 1 件。
     *
     * @param user       対象ユーザー
     * @param ladderType ラダー種別（"score" / "bp"）
     * @return 見つかればエントリー、なければ empty
     */
    Optional<LeagueEntry> findByUserAndLadderType(User user, String ladderType);

    /**
     * 【メソッドの役割】 指定ラダーの参加中（active=true）エントリーを全件取得する。
     *
     * 週次編成でグループへ配置する母集団の取得に使う。
     *
     * @param ladderType ラダー種別
     * @return 参加中エントリー一覧
     */
    List<LeagueEntry> findByLadderTypeAndActiveTrue(String ladderType);

    /**
     * 【メソッドの役割】 指定ラダーの参加中エントリーを、ユーザーごと JOIN FETCH して全件取得する。
     *
     * DIVISION 別ランキング（表示名と昇降格ポイントを一覧する）のように、全エントリーの
     * {@code user} を必ず触る用途で使う。{@link #findByLadderTypeAndActiveTrue} は user が
     * LAZY なので人数分の追加クエリ（N+1）になってしまう。
     *
     * @param ladderType ラダー種別
     * @return 参加中エントリー一覧（user 取得済み）
     */
    @Query("SELECT e FROM LeagueEntry e JOIN FETCH e.user WHERE e.ladderType = :ladderType AND e.active = true")
    List<LeagueEntry> findActiveWithUser(@Param("ladderType") String ladderType);

    /**
     * 【メソッドの役割】 指定ラダーの全エントリーを、参加中・離脱中を問わずユーザーごと JOIN FETCH して取得する。
     *
     * DIVISION 別ランキングで離脱（休止）中の人も薄く並べるために使う。参加中だけで良い用途は
     * {@link #findActiveWithUser} を使うこと。
     *
     * @param ladderType ラダー種別
     * @return 全エントリー一覧（user 取得済み。active=false も含む）
     */
    @Query("SELECT e FROM LeagueEntry e JOIN FETCH e.user WHERE e.ladderType = :ladderType")
    List<LeagueEntry> findAllWithUser(@Param("ladderType") String ladderType);

    /**
     * 【メソッドの役割】 指定ユーザーの全ラダー分のエントリーを取得する。
     *
     * マイページ相当（GET /api/league/me）で両ラダーの参加状態をまとめて返すのに使う。
     *
     * @param user 対象ユーザー
     * @return エントリー一覧（0..2 件）
     */
    List<LeagueEntry> findByUser(User user);

    /**
     * 【メソッドの役割】 指定ラダーの複数ユーザー分のエントリーを一括取得する。
     *
     * 順位計算（LeagueStandingsService）で現在の昇降格ポイントを人数分の
     * 個別クエリなしで引くためのバルク取得。
     *
     * @param ladderType ラダー種別
     * @param users      対象ユーザー群（通常はグループの 8 人前後）
     * @return エントリー一覧
     */
    List<LeagueEntry> findByLadderTypeAndUserIn(String ladderType, Collection<User> users);
}
