package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

/**
 * 【エンティティの役割】 5 チーム総当たりにおける「チーム X vs チーム Y」1 対戦カード。
 *
 * 1 大会あたり {@code C(5, 2) = 10} 件。各 matchup の中に先鋒/中堅/大将の 3 試合
 * ({@link CompetitionMatch}) がぶら下がる構造になっている。
 *
 * マッピング先テーブル: {@code competition_matchups}。
 */
@Entity
@Table(name = "competition_matchups")
@Data
@NoArgsConstructor
public class CompetitionMatchup {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属する大会。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "competition_id", nullable = false)
    private Competition competition;

    /** チーム A 側。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_a_id", nullable = false)
    private CompetitionTeam teamA;

    /** チーム B 側。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_b_id", nullable = false)
    private CompetitionTeam teamB;

    /**
     * 大会内での実施・対戦表示順。
     * 未設定 (configured=false) の間は {@code 0}。運営が「設定」した順に 1, 2, ... と採番される。
     */
    @Column(nullable = false)
    private Integer matchupOrder;

    /**
     * 運営がこの対戦カードを「設定済み (実施対象)」にしたか。
     * <ul>
     *   <li>false (デフォルト): 未設定。プレイヤー / TL には表示されず、自選曲提出もできない。
     *       open 遷移時は全 matchup がこの状態で生成される。</li>
     *   <li>true: 運営が設定済み。{@link #matchupOrder} が 1 以上で採番され、プレイヤー / TL に公開される。</li>
     * </ul>
     * 決勝 matchup ({@link #isFinals}) は生成時から true。
     *
     * <p>列名を明示しているのは {@code SpringPhysicalNamingStrategy} 由来の命名ぶれを避けるため
     * ({@link #lineupPublishedA} のコメント参照)。
     */
    @Column(name = "configured", nullable = false)
    @ColumnDefault("false")
    private Boolean configured = false;

    /**
     * チーム A 側の起用 (ラインアップ) が相手 (B) に公開されているか。
     * 主催が任意のタイミングで true に切替可能。デフォルト false。
     * false の間、相手チームの TL / プレイヤーには「未公開」扱いで A の起用名は伏せられる。
     *
     * <p>{@code @Column(name = ...)} を明示しているのは、Hibernate の
     * {@code SpringPhysicalNamingStrategy} が末尾 1 文字の大文字前にアンダースコアを入れない
     * ({@code lineupPublishedA → lineup_publisheda} となってしまう) ため、
     * マイグレーション SQL 側の列名 {@code lineup_published_a} と揃える必要があるから。
     */
    @Column(name = "lineup_published_a", nullable = false)
    @ColumnDefault("false")
    private Boolean lineupPublishedA = false;

    /**
     * チーム B 側の起用が相手 (A) に公開されているか。挙動は {@link #lineupPublishedA} と対称。
     */
    @Column(name = "lineup_published_b", nullable = false)
    @ColumnDefault("false")
    private Boolean lineupPublishedB = false;

    /**
     * 決勝 matchup フラグ。
     * <ul>
     *   <li>true: 予選 10 試合終了後に勝ち点 TOP2 で組まれる決勝 matchup。
     *       コスト制限・StrategyCard 2-of-4 制限の対象外。</li>
     *   <li>false (デフォルト): 予選 matchup。コスト/StrategyCard 制限あり。</li>
     * </ul>
     */
    @Column(name = "is_finals", nullable = false)
    @ColumnDefault("false")
    private Boolean isFinals = false;
}
