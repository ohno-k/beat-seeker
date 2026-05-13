package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 各プレイヤーが各試合に対して提出する自選曲。
 *
 * 単位はプレイヤー × {@link CompetitionMatch} (試合)。
 * 1 プレイヤーが複数 matchup の同じ matchKind に起用された場合、
 * 試合ごとに別の曲を提出できる (主催が試合ごとに別ジャンルを指定する想定)。
 *
 * 楽曲ソースは {@code frontend/src/data/strategy_card_songs.json} (Strategy Card プール)。
 * 提出時の制約:
 *  - 試合の {@link CompetitionMatch#getRequiredGenre()} が指定されていれば、それに一致する {@link #songGenre}
 *  - 試合の {@link CompetitionMatch#getMatchKind()} に応じた Lv 帯 (vanguard:8-10 / middle:11 / captain:12)
 *
 * UNIQUE 制約: ({@code match_id}, {@code participant_id}) = 同一プレイヤーが同一試合に 2 曲提出することはない。
 *
 * マッピング先テーブル: {@code competition_picks}。
 */
@Entity
@Table(
        name = "competition_picks",
        uniqueConstraints = @UniqueConstraint(columnNames = { "match_id", "participant_id" })
)
@Data
@NoArgsConstructor
public class CompetitionPick {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 対象試合。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id", nullable = false)
    private CompetitionMatch match;

    /** 提出者。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id", nullable = false)
    private CompetitionParticipant participant;

    /**
     * ジャンル。Strategy Card 7 種のいずれか
     * ({@code NOTES / PEAK / CHORD / CHARGE / SCRATCH / SOF-LAN / INSANE})。
     * 試合の {@code requiredGenre} と一致する必要がある (提出時にコントローラ層で検証)。
     */
    @Column(length = 16, nullable = false)
    private String songGenre;

    /** 曲の Lv (8-12)。matchKind の Lv 帯と整合するようフロント側で validation 済み。 */
    @Column(nullable = false)
    private Integer songLevel;

    /**
     * {@code strategy_card_songs.json} 内での曲 id。
     * 同タイトル違い譜面の区別を id で取れるようにしておく。
     */
    @Column(nullable = false)
    private Integer songStrategyId;

    /** 曲タイトル (表示・Song Reveal 用)。 */
    @Column(length = 200, nullable = false)
    private String songTitle;

    /** 譜面難易度。{@code A} (ANOTHER) / {@code L} (LEGGENDARIA)。 */
    @Column(length = 2, nullable = false)
    private String songDiff;

    /** 初回提出日時。 */
    @Column(updatable = false)
    private LocalDateTime submittedAt = LocalDateTime.now();

    /** 最終更新日時 (ロック前は何度でも書き換え可能)。 */
    private LocalDateTime updatedAt = LocalDateTime.now();
}
