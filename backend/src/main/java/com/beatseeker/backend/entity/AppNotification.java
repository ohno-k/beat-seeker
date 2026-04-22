package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 アプリ内通知 1 件。
 *
 * 現実世界の概念: ベルアイコンから確認できる「フレンドに抜かれた」「フレンドが昇格した」等の
 * アプリ内お知らせメッセージ。未読/既読を持ち、受信者ごとに管理される。
 * マッピング先テーブル: {@code app_notifications}。
 *
 * 主要な関連:
 *  - {@link #recipient} … 通知を受け取るユーザーへの ManyToOne。
 */
@Entity
@Table(name = "app_notifications")
@Data
@NoArgsConstructor
public class AppNotification {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 通知の受信者（ベルを開いた本人）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    /**
     * 通知の種別。
     *  - "SCORE_BEAT"      : フレンドにスコアを抜かれた
     *  - "FRIEND_RANK_UP"  : フレンドが Beat-Tier ランクアップした
     */
    @Column(nullable = false, length = 30)
    private String type;

    /** 通知本文メッセージ（長文可）。 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    /** 既読フラグ。true なら既読、false なら未読。 */
    @Column(nullable = false)
    private boolean read = false;

    /** 通知発生日時。作成後は不変。 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
