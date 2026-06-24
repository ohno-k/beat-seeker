package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.TimelineEvent;
import com.beatseeker.backend.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 【Repository の役割】 {@link TimelineEvent}（タイムラインの 1 イベント）を扱うリポジトリ。
 *
 * 基本 CRUD は {@link JpaRepository} が提供する。クエリで使う主用途は
 *  - 指定ユーザー集合のイベントを新しい順に取得（自分 + フレンドの混在タイムライン）
 */
@Repository
public interface TimelineEventRepository extends JpaRepository<TimelineEvent, Long> {

    /**
     * 指定ユーザー集合に紐づくイベントを発生日時の新しい順で取得する。
     * {@code JOIN FETCH e.user} で user を同時にロードして N+1 を回避する。
     *
     * @param users    取得対象ユーザー集合（自分 + フレンドなど）
     * @param pageable ページング（件数制限）
     * @return 発生日時降順のイベントリスト
     */
    @Query("SELECT e FROM TimelineEvent e JOIN FETCH e.user WHERE e.user IN :users ORDER BY e.createdAt DESC, e.id DESC")
    List<TimelineEvent> findRecentByUsers(@Param("users") Collection<User> users, Pageable pageable);

    /**
     * 指定ユーザー集合のイベントのうち、カーソル (beforeTime, beforeId) より「古い」ものを新しい順で取得する。
     * 無限スクロール（最下部で過去分を追加取得）用。createdAt が同値のイベントは id で安定的に区切る。
     *
     * @param users      取得対象ユーザー集合
     * @param beforeTime カーソルの createdAt（これより古い、または同値かつ id がより小さいもの）
     * @param beforeId   カーソルの id（createdAt 同値時のタイブレーク）
     * @param pageable   ページング（件数制限）
     * @return 発生日時降順のイベントリスト
     */
    @Query("SELECT e FROM TimelineEvent e JOIN FETCH e.user WHERE e.user IN :users " +
           "AND (e.createdAt < :beforeTime OR (e.createdAt = :beforeTime AND e.id < :beforeId)) " +
           "ORDER BY e.createdAt DESC, e.id DESC")
    List<TimelineEvent> findRecentByUsersBefore(@Param("users") Collection<User> users,
                                                @Param("beforeTime") LocalDateTime beforeTime,
                                                @Param("beforeId") Long beforeId,
                                                Pageable pageable);

    /**
     * 指定ユーザーがすでに 1 件でも TimelineEvent を持っているかを返す。
     * バックフィル実行時の二重生成防止に使う。
     *
     * 派生クエリ: {@code SELECT 1 FROM timeline_events WHERE user_id = ? LIMIT 1}。
     *
     * @param user 対象ユーザー
     * @return 1 件以上保存されていれば {@code true}
     */
    boolean existsByUser(User user);
}
