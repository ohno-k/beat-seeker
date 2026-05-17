package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 【エンティティの役割】 個人戦 ({@link CompetitionIndividualMatch}) の 1 プレイヤースロット。
 *
 * 1 試合あたり常に 4 件 (slot 1〜4)。各スロットには参加者 1 名が配置され、4 曲すべての
 * スコアと、サーバ側で算出された 1 曲ごとの順位・ポイントを格納する。試合全体の totalPoints
 * は 4 曲ぶんのポイント合計。
 *
 * <p>順位算出ルール: 各曲ごとに「自分より高スコアの人数 + 1」を rank に。タイは両者を上位扱い
 * (例: 1 位タイ → 2 名とも 2pt、次は 3 位扱いで 0pt)。これは IIDX ARENA モードと同じ規則。
 *
 * <p>マッピング先テーブル: {@code competition_individual_match_slots}。
 *
 * <p>レガシ注意: 旧モデル ({@code song_strategy_id} / {@code score} / {@code rank_in_match} /
 * {@code points} / {@code song_title}) のカラムは現在は未使用。ddl-auto: update はカラムを
 * 自動削除しないため、本番 DB には残るが ORM 側からは参照しない。
 */
@Entity
@Table(
        name = "competition_individual_match_slots",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = { "match_id", "slot_position" }),
                @UniqueConstraint(columnNames = { "match_id", "participant_id" })
        }
)
@Data
@NoArgsConstructor
public class CompetitionIndividualMatchSlot {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属する試合。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id", nullable = false)
    private CompetitionIndividualMatch match;

    /** 試合内のスロット位置 (1〜4)。 */
    @Column(name = "slot_position", nullable = false)
    private Integer slotPosition;

    /** 配置された参加者。スケジュール生成時に確定する。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id", nullable = false)
    private CompetitionParticipant participant;

    // ── 4 曲ぶんのスコア (未入力時は null)。曲は match.song1〜song4 に対応 ──
    @Column(name = "score1") private Integer score1;
    @Column(name = "score2") private Integer score2;
    @Column(name = "score3") private Integer score3;
    @Column(name = "score4") private Integer score4;

    // ── 4 曲ぶんの順位 (1〜4)。サーバ側でスコア降順から自動算出。 ──
    @Column(name = "rank1") private Integer rank1;
    @Column(name = "rank2") private Integer rank2;
    @Column(name = "rank3") private Integer rank3;
    @Column(name = "rank4") private Integer rank4;

    // ── 4 曲ぶんのポイント (2/1/0/0)。rank から自動算出。 ──
    @Column(name = "points1") private Integer points1;
    @Column(name = "points2") private Integer points2;
    @Column(name = "points3") private Integer points3;
    @Column(name = "points4") private Integer points4;

    /** 試合全体での総ポイント (4 曲ぶんの points 合計)。 */
    @Column(name = "total_points")
    private Integer totalPoints;
}
