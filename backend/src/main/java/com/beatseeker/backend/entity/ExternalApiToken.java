package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 外部アプリ（iidx-memo 等）から beat-seeker の公開 API を叩くための
 * 個人発行 API トークンを表す。
 *
 * 現実世界の概念: GitHub の Personal Access Token と同じ位置付け。
 * ユーザーがプロフィール画面で発行 → 連携先アプリの設定にコピペで貼り付け → 以降は
 * 連携先アプリが {@code Authorization: Bearer <token>} ヘッダ付きで beat-seeker の
 * 公開 API（{@code /api/external/**}）を叩く。
 *
 * セキュリティ方針:
 *  - 生のトークン文字列は DB に保存しない。SHA-256 ハッシュのみ {@link #tokenHash} に保持し、
 *    比較もハッシュ突合で行う。
 *  - 発行時のみ平文を 1 回返却し、再表示はできない。
 *  - 表示用に末尾数文字だけを {@link #tokenPrefix} に持つ（一覧画面で識別用）。
 *
 * マッピング先テーブル: {@code external_api_tokens}。
 */
@Entity
@Table(name = "external_api_tokens", indexes = {
        @Index(name = "idx_external_api_tokens_user_id", columnList = "user_id"),
        @Index(name = "idx_external_api_tokens_token_hash", columnList = "token_hash", unique = true)
})
@Data
@NoArgsConstructor
public class ExternalApiToken {

    /** 主キー。DB 採番。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * トークンの所有者。
     *
     * EAGER 必須: 認証フィルタ ({@link com.beatseeker.backend.config.ApiTokenAuthFilter})
     * は Spring Boot の Open-in-View が効く前に走るため、LAZY だと
     * {@code token.getUser().getIidxId()} で {@link org.hibernate.LazyInitializationException}
     * になる。Repository に {@code @EntityGraph} を付けても derived query では
     * 確実に効かないケースがあったため、エンティティ側で EAGER を強制する。
     * トークンと User は 1:1 で常にペアで使うのでパフォーマンス影響も無い。
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * トークン本体（平文）の SHA-256 ハッシュを 16 進文字列で保持する。
     * 認証時はリクエストヘッダの平文を同じハッシュにかけて比較する。
     */
    @Column(name = "token_hash", unique = true, nullable = false, length = 64)
    private String tokenHash;

    /**
     * 表示用の末尾識別子（例: "...8aF2"）。token のうち末尾 4 文字程度を保持。
     * 一覧で「どのトークンか」をユーザーが識別する目的のみ。
     */
    @Column(name = "token_prefix", length = 16)
    private String tokenPrefix;

    /** ユーザーが付けたラベル（例: "iidx-memo 連携"）。任意。 */
    @Column(length = 80)
    private String name;

    /**
     * 連携先パートナー識別子。例: "iidx-memo"。
     * 将来複数の連携先が出てきたときに、どの相手向けに発行したかを記録する。
     */
    @Column(length = 40)
    private String partner;

    /** 有効期限。null は無期限。 */
    private LocalDateTime expiresAt;

    /** 失効日時。null なら有効。 */
    private LocalDateTime revokedAt;

    /** 最終利用日時。Filter から都度更新される（簡易監視用）。 */
    private LocalDateTime lastUsedAt;

    /** 発行日時。 */
    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
