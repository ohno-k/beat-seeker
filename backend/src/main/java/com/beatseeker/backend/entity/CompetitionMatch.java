package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 matchup の中の 1 試合 (先鋒戦 or 中堅戦 or 大将戦)。
 *
 * 1 matchup あたり 3 件、大会全体で 30 件が大会作成 (status: draft→open) 時に自動生成される。
 * 初期状態では {@link #playerA} / {@link #playerB} が null で、各チームの TL がアサインすることで埋まる。
 *
 * 個別ロックは A/B 側それぞれ独立に行う。両サイドがロックされた段階で、両者の自選曲が
 * 互いに開示される (Reveal 直前の StrategyCard 使用判断のため)。
 *
 * マッピング先テーブル: {@code competition_matches}。
 */
@Entity
@Table(name = "competition_matches")
@Data
@NoArgsConstructor
public class CompetitionMatch {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属する matchup (チーム vs チーム)。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matchup_id", nullable = false)
    private CompetitionMatchup matchup;

    /**
     * 戦種別。{@code vanguard} (先鋒戦 Lv8-10) / {@code middle} (中堅戦 Lv11) / {@code captain} (大将戦 Lv12)。
     * StrategyCard 発動時の Lv 帯制約に使う。
     */
    @Column(length = 16, nullable = false)
    private String matchKind;

    /** チーム A 側のプレイヤー (TL がアサインするまで null)。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_a_id")
    private CompetitionParticipant playerA;

    /** チーム B 側のプレイヤー (TL がアサインするまで null)。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_b_id")
    private CompetitionParticipant playerB;

    /** A 側の自選曲が主催によりロックされたか。 */
    @Column(nullable = false)
    private Boolean lockedA = false;

    /** B 側の自選曲が主催によりロックされたか。 */
    @Column(nullable = false)
    private Boolean lockedB = false;

    /** A 側ロック日時。 */
    private LocalDateTime lockedAAt;

    /** B 側ロック日時。 */
    private LocalDateTime lockedBAt;

    /**
     * 主催が指定するジャンル制約。
     * <p>{@code NOTES / PEAK / CHORD / CHARGE / SCRATCH / SOF-LAN / INSANE} のいずれか。
     * null の場合は未指定 (= プレイヤー側に提出 UI を出さない方針)。指定後はプレイヤーの
     * 自選曲はこのジャンルに一致しなければ受け付けない。
     * <p>StrategyCard 発動時の抽選プールも、相手の自選曲ジャンル (= この値) で固定される。
     */
    @Column(length = 16)
    private String requiredGenre;

    /**
     * A 側の自選曲が相手に公開されているか。主催が任意のタイミングで true に切替する。
     * {@link #lockedA} (編集禁止) とは独立: ロック後でも未公開 → 試合直前に公開して相手に開示。
     * StrategyCard 決定の前提条件は「相手側がここで true (= 公開済)」になる。
     *
     * <p>{@code @Column(name = ...)} を明示しているのは、Hibernate の
     * {@code SpringPhysicalNamingStrategy} が末尾 1 文字の大文字前にアンダースコアを入れない
     * ({@code pickPublishedA → pick_publisheda} となる) ため、
     * マイグレーション SQL 側 ({@code pick_published_a}) と揃えるため。
     */
    @Column(name = "pick_published_a", nullable = false)
    @ColumnDefault("false")
    private Boolean pickPublishedA = false;

    /** B 側の自選曲が相手に公開されているか。挙動は {@link #pickPublishedA} と対称。 */
    @Column(name = "pick_published_b", nullable = false)
    @ColumnDefault("false")
    private Boolean pickPublishedB = false;
}
