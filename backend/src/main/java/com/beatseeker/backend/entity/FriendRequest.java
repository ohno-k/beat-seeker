package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 フレンド申請の記録。
 *
 * 現実世界の概念: 「ユーザー A がユーザー B にフレンド申請を送った」という行為 1 件。
 * 承認されれば {@link Friendship} レコードが新規作成される。
 * マッピング先テーブル: {@code friend_requests}。
 *
 * 主要な関連:
 *  - {@link #sender}   … 申請を送った側の {@link User}（ManyToOne）。
 *  - {@link #receiver} … 申請を受け取った側の {@link User}（ManyToOne）。
 */
@Entity
@Table(name = "friend_requests")
@Data
@NoArgsConstructor
public class FriendRequest {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 申請を送信したユーザー。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    /** 申請を受け取ったユーザー。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", nullable = false)
    private User receiver;

    /**
     * 申請ステータス。
     *  - "PENDING"  : 未対応（デフォルト）
     *  - "ACCEPTED" : 承認済み（この時点で Friendship が作成される）
     *  - "REJECTED" : 拒否された
     */
    @Column(length = 20)
    private String status = "PENDING"; // PENDING, ACCEPTED, REJECTED

    /** 申請時に添えた任意メッセージ（最大 255 文字）。 */
    @Column(length = 255)
    private String message;

    /** 申請作成日時。レコード作成後は不変。 */
    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
