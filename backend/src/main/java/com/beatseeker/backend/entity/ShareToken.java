package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 ユーザーが発行する「URL共有トークン」を表す。
 *
 * 現実世界の概念: ログインなしで自分のダッシュボード／スコア一覧を見せるための
 * 一時的な公開リンク。トークンを知っている人なら誰でも閲覧できる。
 *
 * マッピング先テーブル: {@code share_tokens}。
 *
 * 主要フィールド:
 *  - {@link #token} … URL に埋め込むランダム文字列（UUID）。ユニーク制約あり。
 *  - {@link #scopeDashboard} / {@link #scopeScores} … 公開範囲フラグ。
 *  - {@link #expiresAt} … 有効期限。null は無期限。
 *  - {@link #revokedAt} … 失効日時。null なら有効。
 */
@Entity
@Table(name = "share_tokens", indexes = {
        @Index(name = "idx_share_tokens_user_id", columnList = "user_id")
})
@Data
@NoArgsConstructor
public class ShareToken {

    /** 主キー。DB 採番。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** トークンの所有者。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** URL に埋め込むランダム文字列。UUID（36文字）を想定。 */
    @Column(unique = true, nullable = false, length = 64)
    private String token;

    /** ダッシュボードを公開するか。 */
    @Column(nullable = false)
    private Boolean scopeDashboard = false;

    /** スコア一覧を公開するか。 */
    @Column(nullable = false)
    private Boolean scopeScores = false;

    /** 成長記録（アップロード履歴）を公開するか。 */
    @Column(nullable = false)
    private Boolean scopeHistory = false;

    /** プロフィール（成長軌跡＋スコア分析）を公開するか。URL共有・通知設定は含めない。 */
    @Column(nullable = false)
    private Boolean scopeProfile = false;

    /** 有効期限。null は無期限。 */
    private LocalDateTime expiresAt;

    /** 失効日時。null なら有効。 */
    private LocalDateTime revokedAt;

    /** 発行日時。 */
    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
