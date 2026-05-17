package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 個人戦フォーマット ({@code Competition.format = "individual4"}) の 1 試合。
 *
 * IIDX ARENA モードと同じ構造で、4 人のプレイヤーが共通の 4 曲を全員プレイする。
 * 1 曲ごとに 4 人のスコアを比較して順位を付け、順位ごとのポイント (1 位 = 2pt / 2 位 = 1pt /
 * 3 位・4 位 = 0pt) を集計する。試合全体での総ポイント = 4 曲ぶんの合計。
 *
 * 4 つの楽曲は本テーブルの song1〜song4 カラムに格納し、4 名のプレイヤーは
 * {@link CompetitionIndividualMatchSlot} に slot 1〜4 として配置される。
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

    // ── 試合で使う 4 曲 (ARENA モード相当) ──
    @Column(name = "song1_strategy_id") private Integer song1StrategyId;
    @Column(name = "song1_title", length = 200) private String song1Title;
    @Column(name = "song2_strategy_id") private Integer song2StrategyId;
    @Column(name = "song2_title", length = 200) private String song2Title;
    @Column(name = "song3_strategy_id") private Integer song3StrategyId;
    @Column(name = "song3_title", length = 200) private String song3Title;
    @Column(name = "song4_strategy_id") private Integer song4StrategyId;
    @Column(name = "song4_title", length = 200) private String song4Title;
}
