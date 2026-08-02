package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 【エンティティの役割】 リーグの週次グループ配置と確定成績。1 週 × 1 ユーザーで 1 行。
 *
 * 現実世界の概念: 「第 N 週・階級 2・グループ 0 に配置されたプレイヤーとその最終成績」。
 * グループは独立エンティティにせず tier + groupIndex の 2 カラムで表す
 * （課題曲は階級単位で共通なのでグループ自体に固有の状態が無いため）。
 * マッピング先テーブル: {@code league_members}。
 *
 * 成績カラム（finalRank / movement / validSongs / resultValue）は週の進行中は null で、
 * 週次締め処理が順位計算の結果を凍結して書き込む。進行中のライブ順位は都度
 * LeagueStandingsService が scores から遅延計算するため、ここには保存しない。
 */
@Entity
@Table(name = "league_members", uniqueConstraints = {
        @UniqueConstraint(name = "uk_league_members_week_user", columnNames = { "week_id", "user_id" })
})
@Data
@NoArgsConstructor
public class LeagueMember {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属する週。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "week_id", nullable = false)
    private LeagueWeek week;

    /** 対象ユーザー。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * この週に着席した卓（ホスト）の DIVISION。通常は自分のホーム DIVISION と同じ。
     * 少人数 DIVISION が近い卓へ吸収された場合、卓側（ホスト）の DIVISION が入る。
     */
    @Column(nullable = false)
    private Integer tier;

    /**
     * 自分のホーム DIVISION（＝ league_entries.currentTier のコピー）。昇降格はこの値を ±1 する。
     * 通常は {@link #tier} と同じ。チャレンジ/ディフェンスで他卓に着席したときだけ異なる。
     */
    @Column(name = "home_tier")
    private Integer homeTier;

    /**
     * 卓での立場。{@code "normal"}（自分のホーム卓）/ {@code "challenge"}（格上の卓に着席＝挑戦）
     * / {@code "defense"}（格下の卓に着席＝防衛）。週の昇降格 PT に ±2 の補正が入る。
     */
    @Column(length = 12, nullable = false)
    @org.hibernate.annotations.ColumnDefault("'normal'")
    private String role = "normal";

    /** 同一卓内のグループ番号（0 始まり）。卓の人数が多い場合に分割される。 */
    @Column(name = "group_index", nullable = false)
    private Integer groupIndex = 0;

    /** 最終順位（グループ内、1 始まり）。週次締めで凍結。進行中は null。 */
    @Column(name = "final_rank")
    private Integer finalRank;

    /** 昇降格結果。{@code "promote" / "stay" / "relegate"}。週次締めで凍結。 */
    @Column(length = 12)
    private String movement;

    /**
     * この週の順位で得た昇降格ポイントの増減（例: 8 人グループの 1 位なら +4）。
     * 週次締めで凍結。有効曲 0 のメンバーはプラス分が 0 に丸められた後の値。
     */
    @Column(name = "point_delta")
    private Integer pointDelta;

    /** 有効曲数（週内プレー要件を満たした課題曲の数、0..3）。週次締めで凍結。 */
    @Column(name = "valid_songs")
    private Integer validSongs;

    /**
     * 成績値。スコアラダーは有効曲の平均スコアレート（%）、BP ラダーは有効曲の合計ミスカウント。
     * 有効曲 0 の場合は null。週次締めで凍結。
     */
    @Column(name = "result_value")
    private Double resultValue;
}
