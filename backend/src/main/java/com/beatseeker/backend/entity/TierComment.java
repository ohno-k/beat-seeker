package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 Tier ページに書き込まれたユーザーコメント 1 件。
 *
 * 現実世界の概念: ある譜面の難易度評価ページにおいて、ユーザーが残した所感・攻略メモ・雑談など。
 * 楽曲 × 難易度ごとに複数のコメントが積み重なる。
 * マッピング先テーブル: {@code tier_comments}。
 *
 * 所有関係: userId のみを持つ緩い参照（ManyToOne 関連は張らずに Long FK）。
 * User 削除時に残留しうる点に注意（運用上は論理削除 / 参照元整合は上位で担保する想定）。
 */
@Entity
@Table(name = "tier_comments")
public class TierComment {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 投稿者のユーザー ID。{@link User#getId()} を素の Long として参照。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 対象楽曲タイトル。 */
    @Column(nullable = false)
    private String title;

    /** 対象譜面の難易度名（例: "ANOTHER"、"LEGGENDARIA"）。 */
    @Column(name = "difficulty_name", nullable = false)
    private String difficultyName;

    /** コメント本文。最大 1000 文字。 */
    @Column(nullable = false, length = 1000)
    private String content;

    /** 投稿日時。 */
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── 以下、Getter / Setter（Lombok 非使用のため手書き） ──

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDifficultyName() { return difficultyName; }
    public void setDifficultyName(String difficultyName) { this.difficultyName = difficultyName; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
