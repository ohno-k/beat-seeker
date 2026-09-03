package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 【エンティティの役割】 練習メニュー 1 行 ＝ 「この譜面を、ここまで、何回」。
 *
 * マッピング先テーブル: {@code practice_menu_items}。
 * 譜面の指し方は {@link Score} と同じ (title, difficultyName) のペア。
 *
 * <h3>なぜ提示時点の値を焼き込むのか</h3>
 * {@link #baselineScore} / {@link #achieveProbability} / {@link #informalRank} は
 * 提示した瞬間の値をそのまま保存する。後から難易度表が改定されても、
 * 「この週はこの前提で出した」が動かないようにするため。
 * 特に {@link #baselineScore} は前進判定の基準そのものなので、
 * ここが動くと「更新したのに前進と判定されない」事故になる。
 *
 * <h3>状態</h3>
 * PENDING（提示）→ PROGRESSED（更新あり・目標未達）→ ACHIEVED（目標到達）と進む。
 * 週末まで一度も更新が無ければ UNTOUCHED、2 週続けて未着手なら REPLACED。
 */
@Entity
@Table(name = "practice_menu_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_practice_menu_items_chart",
                columnNames = { "menu_id", "title", "difficulty_name" })
}, indexes = {
        @Index(name = "idx_practice_menu_items_menu", columnList = "menu_id")
})
@Data
@NoArgsConstructor
public class PracticeMenuItem {

    /** 主キー。DB 採番の代理キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属するメニュー。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_id", nullable = false)
    private PracticeMenu menu;

    /** 楽曲タイトル。{@code scores.title} と同じ表記。 */
    @Column(nullable = false)
    private String title;

    /** 難易度名。"ANOTHER" / "LEGGENDARIA" のみ。 */
    @Column(name = "difficulty_name", nullable = false, length = 16)
    private String difficultyName;

    /** 提示時点の非公式難易度（例 "12.4"）。表示用に焼き込む。 */
    @Column(length = 16)
    private String informalRank;

    /** 役割。"MEASURE"（計測曲）/ "TASK"（課題曲）/ "FILL"（コスパ埋め）。 */
    @Column(nullable = false, length = 8)
    private String role;

    /** 課題曲の場合の傾向軸（"皿" / "乱打" / "同時押し" など）。他の役割では null。 */
    @Column(length = 16)
    private String axis;

    /** 目標種別。"BORDER"（AA/AAA/MAX-）/ "SCORE"（到達基準・大台）/ "LAMP"（クリアランプ）。 */
    @Column(nullable = false, length = 8)
    private String targetType;

    /** 目標の表示名。"AAA" / "HARD CLEAR" / "Veteran 中央値" など。 */
    @Column(length = 32)
    private String targetLabel;

    /** 目標値。BORDER / SCORE では EX スコア、LAMP ではクリアランプの数値ランク。 */
    private Integer targetValue;

    /** 提示時点の自己ベスト EX スコア（歴代 = 現行 + 過去作）。前進判定の基準。 */
    private Integer baselineScore;

    /** 提示時点の自己ベストクリアタイプ（現行作のみ。過去作はランプを持ち込まない）。 */
    @Column(length = 24)
    private String baselineClear;

    /** 提示時点の達成確率 P(S ≥ 目標)。後から推薦精度を検証するために保存する。 */
    private Double achieveProbability;

    /** 提示時点の期待獲得 BEAT-PT。FILL 以外では null のことがある。 */
    private Double expectedGain;

    /** 想定プレイ回数。 */
    private Integer plannedPlays;

    /** PENDING / PROGRESSED / ACHIEVED / UNTOUCHED / REPLACED。 */
    @Column(nullable = false, length = 12)
    private String status = "PENDING";

    /** 採点後の最新 EX スコア。未更新なら null。 */
    private Integer resultScore;

    /** 採点後の最新クリアタイプ。未更新なら null。 */
    @Column(length = 24)
    private String resultClear;

    /** 持ち越した週数。0 なら今週が初出。 */
    @Column(nullable = false)
    private Integer carriedWeeks = 0;

    /** 表示順。役割ごとに 0 から振る。 */
    @Column(nullable = false)
    private Integer sortOrder = 0;
}
