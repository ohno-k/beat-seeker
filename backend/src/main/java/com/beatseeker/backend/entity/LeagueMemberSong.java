package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 【エンティティの役割】 締め済み週の「メンバー × 課題曲」1 マス分の確定結果。
 *
 * 現実世界の概念: 順位表の曲別セル（有効だったか・EX・その曲の着順と着順ポイント）。
 * 進行中の週はこれを scores とベースラインからライブ計算しているが、週が締まった後は
 * 元になる scores が更新され続ける（CSV は歴代ベストしか持たない）ため、
 * 締めの瞬間の値をここへ凍結して過去週の表を不変にする。
 * マッピング先テーブル: {@code league_member_songs}。
 *
 * 書き込みは {@code LeagueWeekLifecycleService.closeWeek} の 1 箇所だけ（週の締め時）。
 * 読み出しは {@code LeagueStandingsService.frozenStandings}。
 */
@Entity
@Table(name = "league_member_songs", uniqueConstraints = {
        // 1 メンバー・1 スロットにつき 1 行。締めの再実行で重複しないようにする。
        @UniqueConstraint(name = "uk_league_member_songs_member_slot", columnNames = { "member_id", "slot" })
})
@Data
@NoArgsConstructor
public class LeagueMemberSong {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** どのメンバー（週 × ユーザー）の結果か。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private LeagueMember member;

    /** 課題曲のスロット（1..3）。 */
    @Column(nullable = false)
    private Integer slot;

    /** その週にラインを超えて有効になったか。 */
    @Column(nullable = false)
    private Boolean valid = false;

    /** 自己ベスト EX スコア（記録なしは null）。 */
    @Column(name = "best_ex")
    private Integer bestEx;

    /** 自己ベストのスコアレート(%)。 */
    private Double rate;

    /** 最小ミスカウント（BP）。 */
    @Column(name = "best_miss")
    private Integer bestMiss;

    /** その曲のライン（グループ内の週開始時点の最高 EX）。誰も未プレーなら null。 */
    @Column(name = "line_ex")
    private Integer lineEx;

    /** その曲のライン（グループ内の週開始時点の最小 BP）。 */
    @Column(name = "line_miss")
    private Integer lineMiss;

    /** その曲の着順（有効化した人だけ・1 始まり・同着は同順位）。未有効は null。 */
    @Column(name = "song_rank")
    private Integer songRank;

    /** その曲で得た着順ポイント（1 位 = グループ人数、最下位 = 1。同着は平均）。 */
    @Column(name = "song_points")
    private Double songPoints;
}
