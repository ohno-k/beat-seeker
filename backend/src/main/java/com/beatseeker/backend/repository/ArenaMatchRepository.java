package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.ArenaMatch;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【Repository の役割】 {@code ArenaMatch}（ARENA モードの対戦記録）を扱うリポジトリ。
 *
 * {@link JpaRepository}{@code <ArenaMatch, Long>} を継承しているため、
 * save / findById / delete などの基本 CRUD は自動で提供される。
 *
 * 主要なクエリの目的:
 *  - ユーザーごとの対戦履歴取得（新しい順）
 *  - 指定日のマッチ登録済み判定
 */
public interface ArenaMatchRepository extends JpaRepository<ArenaMatch, Long> {

    /**
     * 【メソッドの役割】 指定ユーザーの対戦記録を {@code matchDate} 降順で取得する。
     *
     * 派生クエリメソッド: {@code findByUserOrderByMatchDateDesc} の命名により
     * {@code WHERE user_id = ? ORDER BY match_date DESC} が自動生成される。
     * 該当 0 件なら空の {@code List} を返す。
     *
     * @param user 対象ユーザー
     * @return 新しい対戦日順の {@code ArenaMatch} リスト
     */
    List<ArenaMatch> findByUserOrderByMatchDateDesc(User user);

    /**
     * 【メソッドの役割】 指定ユーザー・指定日付の対戦記録が既に存在するかを判定する。
     *
     * 派生クエリメソッド: {@code existsByUserAndMatchDate} は
     * {@code SELECT COUNT(*) > 0 WHERE user_id = ? AND match_date = ?} に相当する。
     * 同一日に二重登録しないためのチェックに使う。
     *
     * @param user 対象ユーザー
     * @param matchDate 対戦日（文字列フォーマット、例 "2025-04-23"）
     * @return 既に登録があれば true
     */
    boolean existsByUserAndMatchDate(User user, String matchDate);
}
