package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 譜面再生で一緒に流す原曲の YouTube 動画（曲ごと）。
 *
 * 同じ曲の譜面は音源が同じなので、動画は曲名ごとに 1 本（N/H/A/L で共有）。
 * 検索結果の候補を保存しておき、「別の動画」は再検索せずに次の候補へ進める（検索は割り当てを多く使うため）。
 * 譜面とのずれは譜面ごとに {@link ChartVideoOffset} が持つ。
 * マッピング先テーブル: {@code song_videos}。
 */
@Entity
@Table(name = "song_videos")
@Data
@NoArgsConstructor
public class SongVideo {

    /** 楽曲マスタの曲名。 */
    @Id
    @Column(length = 255)
    private String title;

    /** 使う動画の ID（null = 候補が見つからなかった）。 */
    @Column(length = 32)
    private String videoId;

    @Column(length = 300)
    private String videoTitle;

    @Column(length = 200)
    private String channelTitle;

    /** 動画の長さ（秒）。 */
    private Integer durationSec;

    /** 検索で見つかった候補（[{id, title, channel, durationSec}]、良い順）。 */
    @Column(columnDefinition = "TEXT")
    private String candidatesJson;

    /** 今使っている候補の位置（管理者が URL で指定した動画は -1）。 */
    private Integer candidateIndex;

    /** 検索した日時（JVM のローカル時刻）。 */
    private LocalDateTime searchedAt;

    /** 最後に動画を変えた日時・利用者。 */
    private LocalDateTime updatedAt;
    private Long updatedBy;
}
