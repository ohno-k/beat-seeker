package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.UserSongRank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@code UserSongRank}（曲ごとのユーザー順位キャッシュ）を扱うリポジトリ。
 *
 * 曲×難易度のスコアランキングにおけるユーザーの順位を、毎回計算せずに
 * 事前集計してキャッシュするためのテーブル。
 * 集計は {@link ScoreRepository#insertAllUserSongRanks()} で一括実行される。
 *
 * {@link JpaRepository}{@code <UserSongRank, Long>} を継承しており、基本 CRUD は自動提供。
 *
 * 主要なクエリの目的:
 *  - 特定ユーザーの全曲順位を取得
 */
@Repository
public interface UserSongRankRepository extends JpaRepository<UserSongRank, Long> {
    /**
     * 【メソッドの役割】 指定ユーザーの曲ごとの順位キャッシュを全件取得する。
     *
     * 派生クエリメソッド: {@code WHERE user_id = ?} に変換される。
     * 該当 0 件なら空リスト（まだ集計されていない可能性あり）。
     *
     * @param userId ユーザー ID
     * @return 順位キャッシュのリスト
     */
    List<UserSongRank> findByUserId(Long userId);

    /**
     * 【メソッドの役割】 指定ユーザー × 曲 × 難易度名で順位キャッシュを 1 件取得する。
     *
     * 派生クエリメソッド: {@code WHERE user_id = ? AND title = ? AND difficulty_name = ?}。
     * 外部公開 API の楽曲詳細応答で「この譜面における自分の順位」を返す用途。
     *
     * 注意: {@code user_song_ranks} はバッチで再構築されるキャッシュテーブル。
     * 集計対象外（ANOTHER / LEGGENDARIA 以外）のレコードは存在しない。
     *
     * @param userId         ユーザー ID
     * @param title          曲タイトル
     * @param difficultyName 難易度名（"ANOTHER" / "LEGGENDARIA" を想定）
     * @return 順位キャッシュ（未集計の場合は空）
     */
    Optional<UserSongRank> findByUserIdAndTitleAndDifficultyName(Long userId, String title, String difficultyName);
}
