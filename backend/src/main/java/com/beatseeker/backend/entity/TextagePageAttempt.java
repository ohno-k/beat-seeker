package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 textage 譜面同期（{@code TextageChartSyncService}）が textage のページを最後に取りに行った記録。
 *
 * 1 回の同期で取りに行くページ数には上限があるので、「まだ一度も取りに行っていないページ → 取りに行ったのが古いページ」の順に
 * 回すための並び替えに使う（未解禁の譜面などで毎回照合に失敗するページが、新しい曲の順番を奪い続けないようにする）。
 * マッピング先テーブル: {@code textage_page_attempts}。
 */
@Entity
@Table(name = "textage_page_attempts")
@Data
@NoArgsConstructor
public class TextagePageAttempt {

    /** ページのパス（例: "34/showtime.html"）。 */
    @Id
    @Column(length = 128)
    private String page;

    /** 最後に取りに行った日時（JVM のローカル時刻）。 */
    @Column(nullable = false)
    private LocalDateTime lastAttemptAt;

    /** 最後の結果の要約（"解析 3 譜面 / 保留 1 譜面" や取得エラー）。 */
    @Column(length = 500)
    private String lastResult;
}
