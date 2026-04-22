package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.TierComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Map;

/**
 * 【Repository の役割】 {@code TierComment}（ティア表に対するユーザーコメント）を扱うリポジトリ。
 *
 * 曲×難易度に紐づくコメントが複数件ぶら下がる構造。ティア投票と合わせて
 * コミュニティ議論の場として利用される。
 *
 * {@link JpaRepository}{@code <TierComment, Long>} を継承しており、基本 CRUD は自動提供。
 *
 * 主要なクエリの目的:
 *  - 曲×譜面ごとのコメント時系列取得
 *  - 曲×譜面ごとのコメント件数・最新投稿時刻の集計
 */
public interface TierCommentRepository extends JpaRepository<TierComment, Long> {

    /**
     * 【メソッドの役割】 指定曲・難易度のコメントを投稿日時の昇順（古い順）で取得する。
     *
     * 派生クエリメソッド: {@code WHERE title = ? AND difficulty_name = ? ORDER BY created_at ASC}。
     * スレッド表示（古い順にスクロール）するための取得順。0 件なら空リスト。
     *
     * @param title 曲タイトル
     * @param difficultyName 難易度名
     * @return コメント一覧
     */
    List<TierComment> findByTitleAndDifficultyNameOrderByCreatedAtAsc(String title, String difficultyName);

    /**
     * 【メソッドの役割】 曲×難易度ごとのコメント件数と最新投稿時刻を集計して返す。
     *
     * JPQL の {@code new map(...)} コンストラクタで任意のキー名の {@link Map} を構築している。
     * 返却される各要素のキー:
     *   - {@code title}: 曲タイトル
     *   - {@code difficultyName}: 難易度名
     *   - {@code commentCount}: 件数
     *   - {@code latestCommentAt}: 最新コメントの {@code createdAt}
     * 一覧画面で「議論が盛んな曲」を並べるのに便利。
     *
     * @return 集計結果のリスト
     */
    @Query("SELECT new map(c.title as title, c.difficultyName as difficultyName, COUNT(c) as commentCount, MAX(c.createdAt) as latestCommentAt) " +
           "FROM TierComment c GROUP BY c.title, c.difficultyName")
    List<Map<String, Object>> findCommentStats();
}
