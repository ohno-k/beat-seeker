package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.PastScore;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 【リポジトリの役割】 過去作スコア（{@link PastScore}）の永続化アクセス。
 *
 * 本リポジトリはランキングや BEAT-PT の集計には一切関与しない。
 * 「本人の過去スコアを引く / 取り込む / 作品ごと消す」だけを担う。
 */
public interface PastScoreRepository extends JpaRepository<PastScore, Long> {

    /**
     * 指定ユーザー・指定作品の全レコードを取得する。
     * 取り込み時にベストレコードマージの突き合わせ元として使う。
     */
    List<PastScore> findByUserAndVersion(User user, Integer version);

    /**
     * 指定ユーザーの過去作スコアを全件取得する（作品昇順 → 曲名昇順）。
     * 歴代ベスト表示用の一括取得に使う。
     */
    List<PastScore> findByUserOrderByVersionAscTitleAsc(User user);

    /** 指定ユーザー・指定作品のレコード件数。 */
    long countByUserAndVersion(User user, Integer version);

    /**
     * 指定ユーザー・指定作品のレコードを全削除する。
     * 取り込みミスのリカバリ手段。テーブルが分かれているため既存集計への影響はない。
     */
    @Modifying
    void deleteByUserAndVersion(User user, Integer version);

    /** ユーザー削除時などに使う一括削除。 */
    @Modifying
    void deleteByUser(User user);

    /**
     * 作品ごとのサマリ（譜面数 / 最終取り込み日時 / CSV 上の最終プレー日時）を返す。
     *
     * 返り値 1 行の配列レイアウト: [0]=version(Integer), [1]=count(Long),
     * [2]=importedAt(LocalDateTime), [3]=lastPlayedAt(String)
     */
    @Query("SELECT p.version, COUNT(p), MAX(p.importedAt), MAX(p.lastPlayedAt) " +
            "FROM PastScore p WHERE p.user = :user GROUP BY p.version ORDER BY p.version DESC")
    List<Object[]> findSummaryByUser(@Param("user") User user);
}
