package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 大会の参加者 1 人。チームに紐づく。
 *
 * 1 大会あたり常に 20 人 (5 チーム × 4 人)。各参加者には自選曲提出用の
 * {@link #inviteToken} が発行され、参加者は {@code /competition/player/{inviteToken}}
 * から自分のページにアクセスする (beat-seeker アカウントは不要)。
 *
 * チーム内 1 人だけ {@link #isTl} が {@code true} になり、TL 兼プレイヤーとして
 * ラインアップ管理権限を併せ持つ (TL 専用 URL は {@link CompetitionTeam#getTlToken()})。
 *
 * マッピング先テーブル: {@code competition_participants}。
 */
@Entity
@Table(name = "competition_participants")
@Data
@NoArgsConstructor
public class CompetitionParticipant {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属する大会。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "competition_id", nullable = false)
    private Competition competition;

    /**
     * 所属チーム。team5 フォーマットでは必須、individual4 フォーマットでは null。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private CompetitionTeam team;

    /** 参加者の表示名 (DJ ネーム相当。主催または TL が登録)。 */
    @Column(length = 100, nullable = false)
    private String displayName;

    /** 自選曲提出 URL に埋め込むトークン。ユニーク。 */
    @Column(length = 64, unique = true, nullable = false)
    private String inviteToken;

    /**
     * チームリーダー (TL) を兼任しているか。
     * チーム内で {@code true} になるのは常に 1 人だけになるよう運用ルールで担保する。
     */
    @Column(nullable = false)
    private Boolean isTl = false;

    /** 参加者レコードの作成日時。 */
    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
