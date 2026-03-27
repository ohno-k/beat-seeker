package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "difficulty_ranks")
@Data
@NoArgsConstructor
public class DifficultyRank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Rank value, e.g. "12.5", "Uncategorized(IIDX 32)" */
    @Column(nullable = false)
    private String rankValue;

    /** Display sort order */
    private Integer sortOrder;

    /** "active" or "draft" */
    @Column(nullable = false)
    private String revision = "active";

    @OneToMany(mappedBy = "difficultyRank", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("sortOrder ASC")
    private List<DifficultyRankSong> songs = new ArrayList<>();
}
