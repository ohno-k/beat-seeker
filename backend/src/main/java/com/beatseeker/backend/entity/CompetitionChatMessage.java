package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 TL (チームリーダー) ⇄ システム運営 のチャット 1 メッセージ。
 *
 * <p>スレッドはチーム単位 (TL 専用 URL がチームに紐づくため)。同一 {@link #team} の
 * メッセージ列が 1 つの会話を成す。
 *
 * <p>マッピング先テーブル: {@code competition_chat_messages}。
 */
@Entity
@Table(name = "competition_chat_messages")
@Data
@NoArgsConstructor
public class CompetitionChatMessage {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属チーム (= TL スレッド単位)。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private CompetitionTeam team;

    /** 送信者種別: {@code "tl"} (チームリーダー) / {@code "admin"} (システム運営)。 */
    @Column(length = 16, nullable = false)
    private String sender;

    /** 本文 (プレーンテキスト)。 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    /**
     * 運営が既読にしたか。{@code sender="tl"} のメッセージの未読バッジ算出に使う。
     * {@code sender="admin"} のメッセージでは常に true 扱い (運営自身の発言)。
     */
    @Column(name = "read_by_admin", nullable = false)
    private boolean readByAdmin = false;

    /** 送信日時。 */
    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
