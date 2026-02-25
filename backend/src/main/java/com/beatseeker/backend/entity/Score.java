package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "scores")
@Data
@NoArgsConstructor
public class Score {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private String title;
    private String artist;
    private String genre;
    private String difficultyName; // ANOTHER, HYPER, etc.
    private Integer difficultyLevel;
    private Integer score;
    private String clearType;
    private String djLevel;
    private Integer pgreat;
    private Integer great;
    private Integer missCount;
    private Integer playCount;

    @Column(columnDefinition = "TEXT")
    private String memo;

    private String snapshotId;

    @Column(nullable = false)
    private LocalDateTime uploadedAt = LocalDateTime.now();
}
