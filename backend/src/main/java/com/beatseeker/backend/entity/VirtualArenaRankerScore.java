package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 【エンティティの役割】 {@link VirtualArenaRanker}（アリーナ仮想プレイヤー）1 人の、
 * 1 譜面分のベストスコアを表す。
 *
 * 現実世界の概念: eagate の楽曲データページから取得した「曲 × 難易度」単位のベスト。
 * BEAT-PT / RATE-PT はここに保存せず、{@code VirtualArenaRankerService} が
 * {@code BeatPtCalculator} で都度集計する（実ユーザーの {@link Score} と同方針で raw を保持）。
 * マッピング先テーブル: {@code virtual_arena_ranker_scores}。
 */
@Entity
@Table(name = "virtual_arena_ranker_scores",
        indexes = { @Index(name = "idx_vars_ranker_id", columnList = "ranker_id") })
@Data
@NoArgsConstructor
public class VirtualArenaRankerScore {

    /** 主キー。DB 側で自動採番される代理キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** このスコアの所有者（アリーナ仮想プレイヤー）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ranker_id", nullable = false)
    private VirtualArenaRanker ranker;

    /** 曲タイトル。 */
    @Column(length = 255)
    private String title;

    /** 難易度名（BEGINNER / NORMAL / HYPER / ANOTHER / LEGGENDARIA）。 */
    @Column(name = "difficulty_name", length = 20)
    private String difficultyName;

    /** 難易度レベル（例: 12）。 */
    @Column(name = "difficulty_level")
    private Integer difficultyLevel;

    /** EX スコア。 */
    private Integer score;

    /** PGREAT 数（取得できた場合）。 */
    private Integer pgreat;

    /** GREAT 数（取得できた場合）。 */
    private Integer great;

    /** ミスカウント（取得できた場合）。 */
    @Column(name = "miss_count")
    private Integer missCount;

    /** クリアタイプ（例: "FULLCOMBO CLEAR"、"NO PLAY"）。 */
    @Column(name = "clear_type", length = 20)
    private String clearType;

    /** DJ LEVEL（AAA/AA/…）。 */
    @Column(name = "dj_level", length = 4)
    private String djLevel;
}
