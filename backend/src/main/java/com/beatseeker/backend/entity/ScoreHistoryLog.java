package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 ユーザーのスコアアップロード単位のサマリ履歴。
 *
 * 現実世界の概念: 「このユーザーが X 時に一斉アップロードしたとき、累計スコアや
 * 各クリアタイプ数がどう推移したか」のスナップショット 1 件。
 * マッピング先テーブル: {@code score_history_logs}。
 *
 * 個別曲のスコア推移ではなく「その時点のトータル値」を記録するため、
 * グラフ表示や日次差分の算出に使われる。
 *
 * 主要な関連:
 *  - {@link #user} … 対象ユーザーへの ManyToOne。
 */
@Entity
@Table(name = "score_history_logs")
@Data
public class ScoreHistoryLog {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 集計対象のユーザー。{@link User} への ManyToOne。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** このサマリを作成した（= アップロードした）日時。 */
    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    /** 累計 EX スコア（全曲合計）。 */
    private Long totalScore = 0L;

    /** FULLCOMBO CLEAR した譜面数。 */
    private Integer fcCount = 0;
    /** EX HARD CLEAR 以上を達成した譜面数。 */
    private Integer exhCount = 0;
    /** HARD CLEAR 以上を達成した譜面数。 */
    private Integer hCount = 0;
    /** CLEAR 以上を達成した譜面数（EASY や ASSIST を含まない通常クリア）。 */
    private Integer clearCount = 0;
    /** EASY CLEAR 以上を達成した譜面数。 */
    private Integer easyCount = 0;

    /** DJ LEVEL AAA 以上の譜面数。 */
    private Integer aaaCount = 0;
    /** DJ LEVEL AA 以上の譜面数。 */
    private Integer aaCount = 0;
    /** DJ LEVEL A 以上の譜面数。 */
    private Integer aCount = 0;

    /** 累計 Beat-Pt（総合レート）。 */
    private Double totalBeatPt = 0.0;
    /** 前回アップロード時との差分 Beat-Pt（増分）。 */
    private Double beatPtIncrease = 0.0;
    /** 今回のアップロードで更新された譜面数。 */
    private Integer updatedCount = 0;
    /** 累計精度ポイント（DJ LEVEL 系の加点）。 */
    private Double totalPrecisionPt = 0.0;
    /** 累計レートポイント。 */
    private Double totalRatePt = 0.0;

    /** 更新された個別譜面の詳細 JSON（差分表示用）。 */
    @Column(columnDefinition = "TEXT")
    private String diffJson;
}
