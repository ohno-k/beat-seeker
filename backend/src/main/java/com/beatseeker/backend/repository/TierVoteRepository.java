package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.TierVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 【Repository の役割】 {@code TierVote}（ティア表に対するユーザー投票）を扱うリポジトリ。
 *
 * 1 ユーザーが 1 つの曲×難易度に対して 1 票を投じる構造。集計結果から
 * コミュニティ主観の難易度ランキング（ティア）を生成する。
 *
 * {@link JpaRepository}{@code <TierVote, Long>} を継承しており、基本 CRUD は自動提供。
 *
 * 主要なクエリの目的:
 *  - 自分の投票履歴取得
 *  - 既存投票の存在チェック（重複投票抑止）
 *  - 全体集計（曲×難易度×票値ごとのカウント）
 */
@Repository
public interface TierVoteRepository extends JpaRepository<TierVote, Long> {

    /**
     * 【メソッドの役割】 指定ユーザーの投票を全件取得する。
     *
     * 派生クエリメソッド: {@code WHERE user_id = ?} に変換される。
     * 自分の投票履歴画面などで使う想定。0 件なら空リスト。
     *
     * @param userId ユーザー ID
     * @return 投票レコード一覧
     */
    List<TierVote> findByUserId(Long userId);

    /**
     * 【メソッドの役割】 ユーザー×曲×難易度の組で既存投票を一件取得する。
     *
     * 派生クエリメソッド: {@code WHERE user_id = ? AND title = ? AND difficulty_name = ?}。
     * 投票の登録 or 更新（upsert）判定で使う。未投票なら {@link Optional#empty()}。
     *
     * @param userId ユーザー ID
     * @param title 曲タイトル
     * @param difficultyName 難易度名
     * @return 既存投票（なければ空）
     */
    Optional<TierVote> findByUserIdAndTitleAndDifficultyName(Long userId, String title, String difficultyName);

    /**
     * 【メソッドの役割】 曲×難易度×票値の 3 軸で集計し、それぞれの票数を返す。
     *
     * ネイティブ SQL（PostgreSQL）。返却されるキー:
     *   - {@code title}: 曲タイトル
     *   - {@code difficultyName}: 難易度名
     *   - {@code vote}: 投票値（ティア記号など）
     *   - {@code count}: 票数
     * 集計結果をフロントで整形し、ティア分布グラフを描く。
     *
     * @return 集計結果のリスト
     */
    @Query(value =
        "SELECT title as \"title\", difficulty_name as \"difficultyName\", vote as \"vote\", COUNT(*) as \"count\" " +
        "FROM tier_votes GROUP BY title, difficulty_name, vote",
        nativeQuery = true)
    List<Map<String, Object>> findAggregatedVotes();
}
