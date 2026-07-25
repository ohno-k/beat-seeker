package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.LeagueEntry;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

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
