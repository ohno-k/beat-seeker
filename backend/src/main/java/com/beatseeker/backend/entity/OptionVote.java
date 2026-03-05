package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "option_votes", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "user_id", "title", "difficulty_name" })
})
@Data
@NoArgsConstructor
public class OptionVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String difficultyName; // ANOTHER, LEGGENDARIA

    @Column(nullable = false, length = 20)
    private String optionType; // REGULAR, MIRROR, RANDOM, R-RANDOM, S-RANDOM (normalized to 1P perspective)

    @Column(nullable = false)
    private LocalDateTime votedAt = LocalDateTime.now();
}
