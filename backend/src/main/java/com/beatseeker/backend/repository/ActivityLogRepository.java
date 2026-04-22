package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.ActivityLog;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 【Repository の役割】 {@code ActivityLog}（ユーザーのアクティビティ履歴）を扱うリポジトリ。
 *
 * {@link JpaRepository}{@code <ActivityLog, Long>} を継承しており、
 * 基本的な CRUD（save / findById / deleteById 等）は自動的に提供される。
 * 第一型引数がエンティティの型、第二型引数が主キー（id）の型。
 *
 * 主要なクエリの目的:
 *  - {@link #findRecentActivity(PageRequest)} … タイムライン表示用に最新順でまとめて取得する。
 */
@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    /**
     * 【メソッドの役割】 最新のアクティビティログを作成日時降順で取得する。
     *
     * {@code JOIN FETCH a.user} によりユーザーを同時にロードして N+1 問題を回避している。
     * 呼び出し側で {@code PageRequest.of(0, n)} を渡すことで件数制限を付ける想定。
     * 該当レコードが 0 件なら空の {@code List} を返す。
     *
     * @param pageable ページング指定（件数の絞り込みに使用）
     * @return 作成日時の新しい順で並んだ {@code ActivityLog} のリスト
     */
    @Query("SELECT a FROM ActivityLog a JOIN FETCH a.user ORDER BY a.createdAt DESC")
    List<ActivityLog> findRecentActivity(PageRequest pageable);
}
