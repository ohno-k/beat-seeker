package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "virtual_rivals", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "owner_id", "version_num", "prefecture_file_num" })
})
@Data
@NoArgsConstructor
public class VirtualRival {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "version_num", nullable = false)
    private Integer versionNum;

    @Column(name = "prefecture_file_num", nullable = false)
    private Integer prefectureFileNum;

    @Column(name = "version_name", length = 64)
    private String versionName;

    @Column(name = "prefecture_name", length = 64)
    private String prefectureName;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
