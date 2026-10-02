package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 譜面と原曲動画のずれ（譜面ごと、利用者全員で共有）。
 *
 * 動画の再生位置（秒）= 譜面の時刻（秒、最初の小節の頭が 0）+ {@link #offsetSec}。
 * どの動画に対するずれかを {@link #videoId} で持ち、曲の動画が変わったら使わない。
 * マッピング先テーブル: {@code chart_video_offsets}。
 */
@Entity
@Table(name = "chart_video_offsets")
@Data
@NoArgsConstructor
public class ChartVideoOffset {

    /** 譜面の textage（例: "21/_twentyl.html?1AC00"）。 */
    @Id
    @Column(length = 128)
    private String textage;

    /** 楽曲マスタの曲名（同じ曲の別譜面のずれを借りるときに引く）。 */
    @Column(length = 255, nullable = false)
    private String title;

    @Column(length = 32, nullable = false)
    private String videoId;

    @Column(nullable = false)
    private Double offsetSec;

    private Long updatedBy;

    private LocalDateTime updatedAt;
}
