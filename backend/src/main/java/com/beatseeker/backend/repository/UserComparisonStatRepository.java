package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.UserComparisonStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 【リポジトリの役割】 ユーザー間スコア比較の集計キャッシュ（{@link UserComparisonStat}）へのアクセス。
 *
 * 読み出しは「主体ユーザー 1 人ぶんを全件引く」だけ。
 * 書き込みはバッチが「対象ユーザーぶんを消してから入れ直す」形で使う。
 */
public interface UserComparisonStatRepository extends JpaRepository<UserComparisonStat, Long> {

    /** 指定ユーザーを主体とする全レコード（全相手 × 全レベル帯）を返す。 */
    List<UserComparisonStat> findByUserId(Long userId);

    /**
     * 指定ユーザーぶんの集計で最も新しい computedAt を返す。
     *
     * 「今日ぶんがまだ無いのでその場で集計する」の判定に使う。レコードが 1 件も無ければ null。
     */
    @Query("SELECT MAX(s.computedAt) FROM UserComparisonStat s WHERE s.userId = :userId")
    LocalDateTime findLatestComputedAtByUserId(@Param("userId") Long userId);

    /** 全レコードで最も新しい computedAt を返す。日次バッチの実行済み判定に使う。1 件も無ければ null。 */
    @Query("SELECT MAX(s.computedAt) FROM UserComparisonStat s")
    LocalDateTime findLatestComputedAt();

    /** 指定ユーザーを主体とするレコードを削除する（入れ直し前のクリア）。 */
    @Modifying
    @Query("DELETE FROM UserComparisonStat s WHERE s.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    /** 全レコードを削除する（日次バッチの全再構築用）。 */
    @Modifying
    @Query("DELETE FROM UserComparisonStat s")
    void deleteAllRows();
}
