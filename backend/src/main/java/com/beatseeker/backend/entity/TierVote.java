package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 譜面 Tier（難易度表の格付け）に対する昇降格投票 1 票。
 *
 * 現実世界の概念: 「この譜面は今の Tier より上（昇格）/ 下（降格）/ ちょうど良い（据え置き）」
 * というユーザー投票。集計結果を基に難易度表の再配置提案を出すのに使う。
 * マッピング先テーブル: {@code tier_votes}。
 *
 * 一意性制約: (user_id, title, difficulty_name) でユニーク。
 *   1 譜面に対して 1 ユーザー 1 票のみ。再投票時は上書き。
 *
 * 所有関係: TierComment 同様、User へは Long FK のみの緩い参照。
 */
@Entity
@Table(name = "tier_votes", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "title", "difficulty_name"})
})
public class TierVote {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 投票者のユーザー ID。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 対象楽曲タイトル。 */
    @Column(nullable = false)
    private String title;

    /** 対象譜面の難易度名。 */
    @Column(name = "difficulty_name", nullable = false)
    private String difficultyName;

    /**
     * 投票内容。
     *  - "PROMOTE" : 現在の Tier より上へ昇格すべき
     *  - "DEMOTE"  : 現在の Tier より下へ降格すべき
     *  - "STAY"    : 現在の Tier で妥当（据え置き）
     */
    @Column(nullable = false)
    private String vote;

    /** 投票日時。 */
    @Column(name = "voted_at")
    private LocalDateTime votedAt;

    // ── 以下、Getter / Setter（Lombok 非使用のため手書き） ──

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDifficultyName() { return difficultyName; }
    public void setDifficultyName(String difficultyName) { this.difficultyName = difficultyName; }

    public String getVote() { return vote; }
    public void setVote(String vote) { this.vote = vote; }

    public LocalDateTime getVotedAt() { return votedAt; }
    public void setVotedAt(LocalDateTime votedAt) { this.votedAt = votedAt; }
}
