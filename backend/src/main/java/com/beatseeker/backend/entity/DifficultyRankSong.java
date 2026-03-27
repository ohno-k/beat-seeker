package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "difficulty_rank_songs")
@Data
@NoArgsConstructor
public class DifficultyRankSong {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "difficulty_rank_id", nullable = false)
    private DifficultyRank difficultyRank;

    /** Song title, including [L] suffix for LEGGENDARIA */
    @Column(nullable = false)
    private String songTitle;

    /** Sort order within the rank */
    private Integer sortOrder;
}
