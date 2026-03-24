package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "score_history_logs")
@Data
public class ScoreHistoryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    private Long totalScore = 0L;

    private Integer fcCount = 0;
    private Integer exhCount = 0;
    private Integer hCount = 0;
    private Integer clearCount = 0;
    private Integer easyCount = 0;

    private Integer aaaCount = 0;
    private Integer aaCount = 0;
    private Integer aCount = 0;

    private Double totalBeatPt = 0.0;
    private Double beatPtIncrease = 0.0;
    private Integer updatedCount = 0;
    private Double totalPrecisionPt = 0.0;
    private Double totalRatePt = 0.0;

    @Column(columnDefinition = "TEXT")
    private String diffJson;
}
