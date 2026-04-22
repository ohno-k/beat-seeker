package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.AppNotification;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 【Repository の役割】 {@code AppNotification}（アプリ内通知）を扱うリポジトリ。
 *
 * {@link JpaRepository}{@code <AppNotification, Long>} を継承しており、
 * 基本 CRUD は自動で提供される。第一型引数はエンティティ、第二型引数は主キーの型。
 *
 * 主要なクエリの目的:
 *  - ユーザー宛の通知一覧を新着順で取得
 *  - 未読件数のカウント
 *  - 全通知を既読化する一括 UPDATE
 */
@Repository
public interface AppNotificationRepository extends JpaRepository<AppNotification, Long> {

    /**
     * 【メソッドの役割】 指定ユーザー宛の通知を作成日時降順で全件取得する。
     *
     * 派生クエリメソッド: メソッド名の {@code findByRecipientOrderByCreatedAtDesc} が
     * 自動的に {@code WHERE recipient = ? ORDER BY created_at DESC} の SQL に変換される。
     * 該当 0 件なら空の {@code List} を返す。
     *
     * @param recipient 受信者（通知の宛先ユーザー）
     * @return 新着順の通知リスト
     */
    List<AppNotification> findByRecipientOrderByCreatedAtDesc(User recipient);

    /**
     * 【メソッドの役割】 指定ユーザー宛の「未読」通知件数を返す。
     *
     * 派生クエリメソッド: {@code countByRecipientAndReadFalse} は
     * {@code SELECT COUNT(*) WHERE recipient = ? AND read = false} に変換される。
     * ヘッダーのバッジ表示などで使う想定。0 件の場合は {@code 0L} が返る。
     *
     * @param recipient 受信者
     * @return 未読通知の件数
     */
    long countByRecipientAndReadFalse(User recipient);

    /**
     * 【メソッドの役割】 指定ユーザー宛の通知をすべて既読にする一括更新。
     *
     * {@link Modifying} アノテーションは「この {@link Query} が SELECT ではなく
     * 更新系（UPDATE/DELETE）である」ことを Spring に知らせる必須指定。
     * トランザクション境界は呼び出し側サービスで管理する想定。
     *
     * @param recipient 既読化対象ユーザー
     */
    @Modifying
    @Query("UPDATE AppNotification n SET n.read = true WHERE n.recipient = :recipient")
    void markAllReadByRecipient(@org.springframework.data.repository.query.Param("recipient") User recipient);
}
