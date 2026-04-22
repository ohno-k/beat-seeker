package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.OptionVote;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@code OptionVote}（曲×譜面に対するオプション投票）を扱うリポジトリ。
 *
 * ユーザーが曲ごとの推奨オプション（RANDOM / MIRROR 等）に投票したデータを保持する。
 *
 * {@link JpaRepository}{@code <OptionVote, Long>} を継承しており、基本 CRUD は自動提供。
 *
 * 主要なクエリの目的:
 *  - 曲×譜面単位での全投票取得（集計用）
 *  - ユーザーが既に投票済みかの判定
 */
public interface OptionVoteRepository extends JpaRepository<OptionVote, Long> {

    /**
     * 【メソッドの役割】 指定曲・指定難易度に対する全投票を取得する。
     *
     * 派生クエリメソッド: {@code WHERE title = ? AND difficulty_name = ?} に変換される。
     * 集計 UI で割合を出すのに使う。0 件なら空リスト。
     *
     * @param title 曲タイトル
     * @param difficultyName 難易度名
     * @return 投票レコード一覧
     */
    List<OptionVote> findByTitleAndDifficultyName(String title, String difficultyName);

    /**
     * 【メソッドの役割】 ユーザーが該当曲・譜面に対して既に投票しているかを取得する。
     *
     * 派生クエリメソッド: {@code WHERE user_id = ? AND title = ? AND difficulty_name = ?}。
     * 投票の追加 or 更新（upsert 相当）判定で使う。未投票なら {@link Optional#empty()}。
     *
     * @param user 投票者
     * @param title 曲タイトル
     * @param difficultyName 難易度名
     * @return 既存の投票レコード（なければ空）
     */
    Optional<OptionVote> findByUserAndTitleAndDifficultyName(User user, String title, String difficultyName);
}
