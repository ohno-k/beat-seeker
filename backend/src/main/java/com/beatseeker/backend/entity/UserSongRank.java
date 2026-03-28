package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_song_ranks", indexes = {
    @Index(name = "idx_user_song_ranks_user_id", columnList = "user_id")
})
public class UserSongRank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String title;

    @Column(name = "difficulty_name", nullable = false)
    private String difficultyName;

    @Column(name = "difficulty_level")
    private Integer difficultyLevel;

    /** Rank position (1 = best). Column named rank_position to avoid SQL reserved word. */
    @Column(name = "rank_position", nullable = false)
    private Integer rank;

    @Column(nullable = false)
    private Integer total;

    @Column(name = "calculated_at")
    private LocalDateTime calculatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDifficultyName() { return difficultyName; }
    public void setDifficultyName(String difficultyName) { this.difficultyName = difficultyName; }

    public Integer getDifficultyLevel() { return difficultyLevel; }
    public void setDifficultyLevel(Integer difficultyLevel) { this.difficultyLevel = difficultyLevel; }

    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }

    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }

    public LocalDateTime getCalculatedAt() { return calculatedAt; }
    public void setCalculatedAt(LocalDateTime calculatedAt) { this.calculatedAt = calculatedAt; }
}
