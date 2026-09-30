package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 譜面分析ページの譜面再生（{@code ChartPlaybackService}）が textage から取得したページのスクリプト。
 *
 * 再生のたびに textage へ取りに行かないよう、一度取得したページのインライン script を保存しておく
 * （再生された譜面のページだけ。定期の textage 譜面同期はここへは書かない）。
 * 取得から一定期間が過ぎたものは次の再生時に取り直し、取り直しに失敗したら古いものを使う。
 * マッピング先テーブル: {@code textage_page_sources}。
 */
@Entity
@Table(name = "textage_page_sources")
@Data
@NoArgsConstructor
public class TextagePageSource {

    /** ページのパス（例: "34/showtime.html"）。 */
    @Id
    @Column(length = 128)
    private String page;

    /** ページのインライン script（{@code TextagePageRunner#extractScript} の結果）。 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String script;

    /** 取得した日時（JVM のローカル時刻）。 */
    @Column(nullable = false)
    private LocalDateTime fetchedAt;
}
