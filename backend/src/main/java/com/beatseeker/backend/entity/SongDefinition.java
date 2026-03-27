package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "song_definitions")
@Data
@NoArgsConstructor
public class SongDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String artist;

    private String genre;

    private Integer notes;

    private String bpm;

    /** Difficulty code: 1=BEGINNER, 2=NORMAL, 3=HYPER, 4=ANOTHER, 10=LEGGENDARIA */
    @Column(nullable = false)
    private String difficulty;

    private Integer level;

    /** World record score (nullable, mainly for ANOTHER/LEGGENDARIA) */
    private Integer wr;

    /** Average score (nullable) */
    private Integer avg;

    /** Textage page URL fragment (nullable) */
    private String textage;

    /** Coefficient (nullable) */
    private Double coef;

    /** Difficulty level string (nullable) */
    private String difficultyLevel;

    /** DP level (nullable) */
    private String dpLevel;

    /** "active" or "draft" */
    @Column(nullable = false)
    private String revision = "active";
}
