package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 【エンティティの役割】 個人戦 ({@link CompetitionIndividualMatch}) の 1 プレイヤースロット。
 *
 * 1 試合あたり常に 4 件。スロット位置 (1〜4) ごとに参加者をひもづけ、入力された曲管理番号 / スコアから
 * 順位・ポイントを派生する (順位はスコア降順、ポイントは 1 位 = 2 / 2 位 = 1 / 3 位・4 位 = 0)。
 *
 * 同順位 (タイ) は両者を上位扱いとする (例: 1 位タイ → 2 名とも 2pt、3 位スロット欠番、次が 3 位)。
 *
 * マッピング先テーブル: {@code competition_individual_match_slots}。
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

    /** 試合内のスロット位置 (1〜4)。表示順 = 選曲順の固定インデックス。 */
    @Column(name = "slot_position", nullable = false)
    private Integer slotPosition;

    /** 配置された参加者。スケジュール生成時に確定する。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id", nullable = false)
    private CompetitionParticipant participant;

    /** プレイした曲の管理番号 ({@code strategy_card_songs.json} 内の id)。未記録時 null。 */
    @Column(name = "song_strategy_id")
    private Integer songStrategyId;

    /** プレイした曲のタイトル (表示用)。未記録時 null。 */
    @Column(name = "song_title", length = 200)
    private String songTitle;

    /** プレイヤーが獲得したスコア (EX SCORE)。未記録時 null。 */
    @Column(name = "score")
    private Integer score;

    /** 試合内の順位 (1〜4)。サーバ側でスコア降順から自動算出。 */
    @Column(name = "rank_in_match")
    private Integer rankInMatch;

    /** 獲得ポイント (2 / 1 / 0 / 0)。ranking から自動算出。 */
    @Column(name = "points")
    private Integer points;
}
