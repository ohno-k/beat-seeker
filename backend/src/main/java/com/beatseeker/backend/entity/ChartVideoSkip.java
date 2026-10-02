package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 管理者の埋め作業で「保存せず次へ」で飛ばした譜面（次の未調整の一覧から外す）。
 *
 * どの動画のときに飛ばしたかを {@link #videoId} で持ち（動画未検索なら空文字）、曲の動画が替わったら一覧に戻す。
 * マッピング先テーブル: {@code chart_video_skips}。
 */
@Entity
@Table(name = "chart_video_skips")
@Data
@NoArgsConstructor
public class ChartVideoSkip {

    /** 譜面の textage。 */
    @Id
    @Column(length = 128)
    private String textage;

    @Column(length = 32, nullable = false)
    private String videoId;

    private Long updatedBy;

    private LocalDateTime updatedAt;
}
