package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 ログインユーザー ⇄ システム運営 のお問い合わせチャット 1 メッセージ。
 *
 * <p>大会機能の TL チャット ({@link CompetitionChatMessage}) と同じ発想の「運営宛お問い合わせ」だが、
 * こちらはスレッドが <b>ユーザー単位</b> (ログイン済みユーザーごとに 1 会話)。同一 {@link #user} の
 * メッセージ列が 1 つの会話を成す。
 *
 * <p>マッピング先テーブル: {@code support_chat_messages}。
 */
@Entity
@Table(name = "support_chat_messages")
@Data
@NoArgsConstructor
public class SupportChatMessage {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** スレッドの持ち主 (= 問い合わせをしたログインユーザー)。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 送信者種別: {@code "user"} (問い合わせ主) / {@code "admin"} (システム運営)。 */
    @Column(length = 16, nullable = false)
    private String sender;

    /** 本文 (プレーンテキスト)。 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    /**
     * 運営が既読にしたか。{@code sender="user"} のメッセージの未読バッジ算出に使う。
     * {@code sender="admin"} のメッセージでは常に true 扱い (運営自身の発言)。
     */
    @Column(name = "read_by_admin", nullable = false)
    private boolean readByAdmin = false;

    /**
     * ユーザーが既読にしたか。{@code sender="admin"} のメッセージの未読バッジ算出に使う。
     * {@code sender="user"} のメッセージでは常に true 扱い (ユーザー自身の発言)。
     */
    @Column(name = "read_by_user", nullable = false)
    private boolean readByUser = false;

    /** 送信日時。 */
    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
