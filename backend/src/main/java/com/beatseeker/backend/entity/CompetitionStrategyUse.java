package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 「相手の自選曲をランダム化する」StrategyCard を、ある試合の特定の
 * 参加者が使うかどうかの意思決定レコード。
 *
 * 試合の両サイドがロック (= 相手の自選が開示) された後、各プレイヤーは
 * 「相手の自選に対して StrategyCard を発動するか」を {@code /competition/player/...} の UI で確定する。
 * Reveal フェーズではこの {@link #enabled} を見て、true の試合は通常の Song Reveal ではなく
 * Strategy Card のスピン演出を自動再生する。
 *
 * 発動時の抽選プールは
 * {@code strategy_card_songs[ 相手の自選の songGenre ][ match の matchKind に対応する Lv 帯 ]}
 * で決まる (使用者はジャンルを指定しない)。
 *
 * UNIQUE 制約: ({@code match_id}, {@code used_by_participant_id}) = 同一試合で
 * 同一プレイヤーが二重にカード使用レコードを持たない。
 *
 * マッピング先テーブル: {@code competition_strategy_uses}。
 */
@Entity
@Table(
        name = "competition_strategy_uses",
        uniqueConstraints = @UniqueConstraint(columnNames = { "match_id", "used_by_participant_id" })
)
@Data
@NoArgsConstructor
public class CompetitionStrategyUse {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 対象試合。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id", nullable = false)
    private CompetitionMatch match;

    /** カードを切る側のプレイヤー (= 相手の自選曲がランダム化される試合の自分自身)。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "used_by_participant_id", nullable = false)
    private CompetitionParticipant usedByParticipant;

    /** カードを切るか。false で保存しておくこともできる (未決定→明示的に「切らない」に切替可)。 */
    @Column(nullable = false)
    private Boolean enabled = false;

    /** 意思決定日時 (= 最終更新日時)。 */
    private LocalDateTime decidedAt = LocalDateTime.now();
}
