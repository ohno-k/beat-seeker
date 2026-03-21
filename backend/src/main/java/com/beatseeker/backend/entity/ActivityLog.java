package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "activity_logs")
@Data
@NoArgsConstructor
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** RANK_UP: Beat-Tierランクアップ */
    @Column(nullable = false, length = 30)
    private String type;

    /** 旧ランク名 (例: "Silver I") */
    @Column(length = 50)
    private String oldValue;

    /** 新ランク名 (例: "Gold I") */
    @Column(length = 50)
    private String newValue;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
