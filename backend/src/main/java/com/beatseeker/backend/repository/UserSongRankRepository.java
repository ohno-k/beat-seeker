package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.UserSongRank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
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

    /**
     * 【メソッドの役割】 公式難易度 Lv11/Lv12 の ANOTHER/LEGGENDARIA 全譜面に対する「平均順位ランキング」を取得する。
     *
     * 集計値は {@code users.total_average_rank} に日次バッチで事前計算済み。本クエリは
     * 単純な ORDER BY で読み出すだけのため高速。{@code total_average_rank IS NULL} の
     * ユーザー（対象セット内で 1 曲もプレイしていない / バッチ未実行）は除外される。
     *
     * 返却キー: userId / displayName / iidxId / privacyLevel / averageRank / playedCount / totalSongs / isSupporter
     *
     * totalSongs（対象セットの譜面数）は全ユーザー共通の値なので、サブクエリで
     * 全行に同じ値を埋め込む（追加の往復クエリを避けるため）。
     */
    @Query(value =
            "SELECT u.id AS \"userId\", " +
            "       u.display_name AS \"displayName\", " +
            "       u.iidx_id AS \"iidxId\", " +
            "       COALESCE(u.privacy_level, 1) AS \"privacyLevel\", " +
            "       u.total_average_rank AS \"averageRank\", " +
            "       COALESCE(u.total_average_rank_played, 0) AS \"playedCount\", " +
            "       (SELECT COUNT(*) FROM ( " +
            "           SELECT DISTINCT title, difficulty_name FROM user_song_ranks " +
            "           WHERE difficulty_level IN (11, 12) " +
            "             AND difficulty_name IN ('ANOTHER', 'LEGGENDARIA') " +
            "       ) t) AS \"totalSongs\", " +
            "       COALESCE(u.is_supporter, false) AND COALESCE(u.show_supporter_border, true) AS \"isSupporter\" " +
            "FROM users u " +
            "WHERE u.total_average_rank IS NOT NULL " +
            "ORDER BY u.total_average_rank ASC", nativeQuery = true)
    List<Map<String, Object>> getAverageRanking();
}
