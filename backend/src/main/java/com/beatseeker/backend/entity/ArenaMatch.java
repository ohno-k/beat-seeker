package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "arena_matches")
@Data
@NoArgsConstructor
public class ArenaMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    private String battleType;    // e.g. "ローカルバトル"
    private String matchDate;     // e.g. "2026-03-15 17:10"
    private String myDjName;
    private String myArenaClass;  // e.g. "A1"
    private Integer myRank;       // 1-4
    private Integer myTotalPt;

    @Column(columnDefinition = "TEXT")
    private String songsJson;     // [{title, difficulty}]

    @Column(columnDefinition = "TEXT")
    private String playersJson;   // [{djName, arenaClass, totalPt, rank, songScores:[{score,pt}]}]

    private LocalDateTime importedAt = LocalDateTime.now();
}
