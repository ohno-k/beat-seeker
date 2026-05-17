package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 個人戦フォーマット ({@code Competition.format = "individual4"}) の 1 試合。
 *
 * 1 試合 = 4 人対戦 × 各自 1 曲提出 × 計 4 曲プレイ。順位は試合終了時にスロット別スコアから自動算出。
 * ポイント: 1 位 = 2pt / 2 位 = 1pt / 3 位・4 位 = 0pt。
 *
 * 4 つのプレイヤースロット ({@link CompetitionIndividualMatchSlot}) を持つ。
 *
 * マッピング先テーブル: {@code competition_individual_matches}。
 */
@Entity
@Table(name = "competition_individual_matches")
@Data
@NoArgsConstructor
public class CompetitionIndividualMatch {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属する大会。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "competition_id", nullable = false)
    private Competition competition;

    /** 大会内での試合の表示順 (1〜N)。予選 → 決勝の順で採番。 */
    @Column(nullable = false)
    private Integer matchOrder;

    /**
     * 決勝試合フラグ。
     * <ul>
     *   <li>false: 予選試合 (12 人 → 18 試合 / 16 人 → 20 試合)。</li>
     *   <li>true: 決勝試合 (12 人 → 3 試合 / 16 人 → 4 試合)。</li>
     * </ul>
     */
    @Column(name = "is_finals", nullable = false)
    @ColumnDefault("false")
    private Boolean isFinals = false;

    /**
     * 決勝試合のバケット番号 (1〜4)。予選試合では null。
     * 1 = 予選 1〜4 位 / 2 = 予選 5〜8 位 / 3 = 予選 9〜12 位 / 4 = 予選 13〜16 位。
     */
    @Column(name = "finals_bucket")
    private Integer finalsBucket;

    /** 試合結果記録日時。null = 未記録。 */
    private LocalDateTime resultRecordedAt;
}
