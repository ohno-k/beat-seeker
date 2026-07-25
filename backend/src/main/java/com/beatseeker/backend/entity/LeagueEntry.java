package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 リーグモードへの参加登録（エントリー）。1 ユーザー × 1 ラダーで 1 行。
 *
 * 現実世界の概念: 「スコアリーグ」「BP リーグ」という 2 本の独立した昇降格ラダーへの
 * オプトイン参加。ユーザーは片方または両方に参加できる。
 * マッピング先テーブル: {@code league_entries}。
 *
 * ライフサイクル:
 *  - 参加 (join): 行を作成。既存行があれば active=true に戻す（復帰。currentTier は維持）。
 *  - 離脱 (leave): active=false。進行中の週のメンバーシップ ({@link LeagueMember}) はそのまま残る。
 *  - 週次編成時に active=true のエントリーだけが翌週のグループへ配置される。
 *  - currentTier が null の間は「未配属」。次回編成で最下位階級に配置される
 *    （初回編成時のみ全員 null のため totalBeatPt 順にシードされる）。
 */
@Entity
@Table(name = "league_entries", uniqueConstraints = {
        @UniqueConstraint(name = "uk_league_entries_user_ladder", columnNames = { "user_id", "ladder_type" })
})
@Data
@NoArgsConstructor
public class LeagueEntry {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 参加ユーザー。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** ラダー種別。{@code "score"}（平均スコアレート）または {@code "bp"}（合計ミスカウント）。 */
    @Column(name = "ladder_type", length = 8, nullable = false)
    private String ladderType;

    /**
     * 現在の所属 DIVISION（0=LEGEND、1..10）。join 時に BEAT-TIER から即時配属され、
     * 以降は週次締め処理の昇降格でのみ増減する（管理者の手動修正を除く）。
     */
    @Column(name = "current_tier")
    private Integer currentTier;

    /**
     * 昇降格ポイント（-4..+4）。週の順位に応じて増減し、+4 到達で昇格・-4 到達で降格する。
     * 初回配属時と DIVISION 変動後（昇降格・管理者修正）は 0 にリセットされる。
     * LEGEND の +4 超過・DIVISION 10 の -4 超過は移動先が無いためクランプして保持する。
     */
    @Column(nullable = false)
    @ColumnDefault("0")
    private Integer points = 0;

    /** 参加中フラグ。false は離脱（休止）状態。復帰時は true に戻すだけで階級は保持される。 */
    @Column(nullable = false)
    @ColumnDefault("true")
    private Boolean active = true;

    /**
     * 連続不参加週数。週次締めで「有効曲 0 曲」だった場合にインクリメントされ、
     * 1 曲でも有効ならリセットされる。規定回数（3 週）に達すると active=false に自動休止される。
     */
    @Column(name = "inactive_weeks", nullable = false)
    @ColumnDefault("0")
    private Integer inactiveWeeks = 0;

    /** エントリー作成日時。 */
    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
