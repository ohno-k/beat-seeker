package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 【エンティティの役割】 大会内 1 チームを表す。1 大会あたり常に 5 チーム。
 *
 * 1 チームは 4 人の {@link CompetitionParticipant} を持つ。チーム内 1 人が TL を兼任し、
 * その 1 人だけが「matchup ごとに先鋒/中堅/大将を誰に当てるか」のラインアップ管理権限を持つ。
 * TL 専用 URL のためにチームごとに {@link #tlToken} を発行する。
 *
 * マッピング先テーブル: {@code competition_teams}。
 */
@Entity
@Table(name = "competition_teams")
@Data
@NoArgsConstructor
public class CompetitionTeam {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属する大会。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "competition_id", nullable = false)
    private Competition competition;

    /** チーム名 (主催が編集可能)。 */
    @Column(length = 100, nullable = false)
    private String teamName;

    /** チームの表示順 (1〜5)。matchup 生成・表示順に使用。 */
    @Column(nullable = false)
    private Integer teamOrder;

    /**
     * TL (チームリーダー) 専用 URL に埋め込むトークン。
     * 主催が大会作成時に自動採番。ユニーク。
     */
    @Column(length = 64, unique = true, nullable = false)
    private String tlToken;
}
