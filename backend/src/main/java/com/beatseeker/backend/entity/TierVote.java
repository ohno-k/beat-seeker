package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tier_votes", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "title", "difficulty_name"})
})
public class TierVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String title;

    @Column(name = "difficulty_name", nullable = false)
    private String difficultyName;

    /** PROMOTE / DEMOTE / STAY */
    @Column(nullable = false)
    private String vote;

    @Column(name = "voted_at")
    private LocalDateTime votedAt;

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
