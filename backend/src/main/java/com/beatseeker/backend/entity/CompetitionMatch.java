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
     * 戦種別。予選 3 戦は {@code vanguard} (先鋒 Lv8-10) / {@code middle} (中堅 Lv11) / {@code captain} (大将 Lv12)。
     * 決勝 7 戦はこれに {@code second} (次鋒 Lv10) / {@code fifth} (五将 Lv11) / {@code third} (三将 Lv12) /
     * {@code vice} (副将 Lv12) を加えた 先鋒 → 次鋒 → 五将 → 中堅 → 三将 → 副将 → 大将。
     * ラベル・Lv 帯・表示順・獲得ポイントの定義は {@code CompetitionMatchKinds} に集約している。
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

    /**
     * 試合結果: A 側が勝った曲数 (0/1/2)。1 戦 2 曲制を想定。
     * null = 未記録、0〜2 = 記録済。
     */
    @Column(name = "a_songs_won")
    private Integer aSongsWon;

    /** 試合結果: B 側が勝った曲数 (0/1/2)。null = 未記録。 */
    @Column(name = "b_songs_won")
    private Integer bSongsWon;

    /** 試合結果が記録された日時。null = 未記録。 */
    private LocalDateTime resultRecordedAt;

    // ── 詳細スコア記録 (1 戦 = 2 曲制) ───────────────────────
    // R-4: 「曲(管理番号)とスコア入力で勝敗自動表示」要件のために導入。
    // song1 / song2 は実際にプレイされた曲 (A 側自選 + B 側自選、もしくは Strategy 発動で
    // ランダム化された曲)。aSongsWon / bSongsWon は本フィールドから派生してサーバ側で計算する。

    /** 1 曲目の管理番号 ({@code strategy_card_songs.json} 内の id)。 */
    @Column(name = "song1_strategy_id")
    private Integer song1StrategyId;

    /** 1 曲目の曲タイトル (記録用、表示で使う)。 */
    @Column(name = "song1_title", length = 200)
    private String song1Title;

    /** 1 曲目 A 側スコア (0〜 上限なし)。null = 未入力。 */
    @Column(name = "song1_score_a")
    private Integer song1ScoreA;

    /** 1 曲目 B 側スコア。 */
    @Column(name = "song1_score_b")
    private Integer song1ScoreB;

    /**
     * 1 曲目を運営が手動指定したか。
     *
     * <p>通常 {@code song1_*} は「自選曲 / StrategyCard 抽選曲」から自動導出された値のスナップショットで、
     * 表示側は常に導出し直す ({@code CompetitionPlayedSongService} 参照)。しかし現地で曲が差し替わった等の
     * 理由で運営が結果記録 UI から曲を選び直した場合は、自動導出で上書きされては困る。
     * このフラグが true の枠は導出より記録値を優先し、🔄 再読込や StrategyCard の抽選確定でも変わらない。
     *
     * <p>既存行への追加を安全に行うため nullable。null は false (= 自動導出) と同義に扱う。
     */
    @Column(name = "song1_manual")
    private Boolean song1Manual = false;

    /** 2 曲目の管理番号。 */
    @Column(name = "song2_strategy_id")
    private Integer song2StrategyId;

    /** 2 曲目の曲タイトル。 */
    @Column(name = "song2_title", length = 200)
    private String song2Title;

    /** 2 曲目 A 側スコア。 */
    @Column(name = "song2_score_a")
    private Integer song2ScoreA;

    /** 2 曲目 B 側スコア。 */
    @Column(name = "song2_score_b")
    private Integer song2ScoreB;

    /** 2 曲目を運営が手動指定したか。詳細は {@link #song1Manual}。 */
    @Column(name = "song2_manual")
    private Boolean song2Manual = false;
}
