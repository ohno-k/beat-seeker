package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 9)
    private String iidxId; // e.g. "1234-5678"

    private String passwordHash;

    private String displayName;

    @Column(length = 20)
    private String danRank; // e.g. "皆伝"

    @Column(length = 10)
    private String arenaRank; // e.g. "A1"

    @Column(length = 2)
    private String playSide = "1P"; // "1P" or "2P"

    @Column(columnDefinition = "integer default 2")
    private Integer privacyLevel = 2; // 0: Public, 1: Friends Only, 2: Private

    private LocalDateTime lastUploadedAt;

    @Column(columnDefinition = "TEXT")
    private String pushSubscription; // Web Push subscription JSON

    @Column(unique = true)
    private String linkToken; // For bookmarklet CSV import

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Score> scores = new ArrayList<>();
}
