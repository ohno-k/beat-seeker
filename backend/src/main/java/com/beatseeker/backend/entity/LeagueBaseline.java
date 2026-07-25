package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 【エンティティの役割】 週開始時点のスコア状態スナップショット（「週内プレー必須」判定の基準値）。
 *
 * 現実世界の概念: リーグの週が始まる瞬間に、各メンバーの課題曲のベスト記録
 * （EX スコア / ミスカウント / プレー回数 / クリア種別）を控えておくレコード。
 * 週内にこの値から進展（プレー回数増加 or いずれかの記録更新）があった曲だけを
 * 集計対象にすることで、「過去の貯金だけで順位が付く」ことを防ぐ。
 * マッピング先テーブル: {@code league_baselines}。
 *
 * 行の作成規則（重要）: スナップショット時に {@link Score} 行が存在した
 * (課題曲, source) の組み合わせにのみ行を作る。行が存在しない組み合わせは
 * 「週開始時点で記録なし」を意味し、その source に週内に Score 行が現れたこと
 * （uploadedAt >= week.snapshotAt）をもって週内プレーと判定する。
 *
 * リーグはアーケード記録（CSV / ブックマークレット）限定のため、スナップショット対象は
 * source = "arcade" の行のみ（INFINITAS の記録はライン・集計に一切関与しない）。
 */
@Entity
@Table(name = "league_baselines", uniqueConstraints = {
        @UniqueConstraint(name = "uk_league_baselines_week_user_chart",
                columnNames = { "week_id", "user_id", "title", "difficulty_name", "source" })
})
@Data
@NoArgsConstructor
public class LeagueBaseline {

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

    /** 課題曲タイトル。 */
    @Column(nullable = false)
    private String title;

    /** 難易度名（"ANOTHER" 等）。 */
    @Column(name = "difficulty_name", length = 16, nullable = false)
    private String difficultyName;

    /** スコアの取得元。{@code "arcade"} または {@code "infinitas"}（Score.source と同じ値域）。 */
    @Column(length = 20, nullable = false)
    private String source;

    /** 週開始時点の EX スコア。 */
    @Column(name = "base_score")
    private Integer baseScore;

    /** 週開始時点のミスカウント（BP）。未記録なら null。 */
    @Column(name = "base_miss")
    private Integer baseMiss;

    /** 週開始時点のプレー回数。null の場合はプレー回数比較をスキップする。 */
    @Column(name = "base_play_count")
    private Integer basePlayCount;

    /** 週開始時点のクリア種別（"HARD CLEAR" 等）。ランプ改善判定に使う。 */
    @Column(name = "base_clear_type", length = 32)
    private String baseClearType;
}
