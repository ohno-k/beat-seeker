package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 【エンティティの役割】 リーグの週次課題曲。1 週 × 1 階級 × 1 スロット（1..3）で 1 行。
 *
 * 現実世界の概念: 「スコアリーグ第 N 週・階級 2 の課題曲 3 曲」のうちの 1 曲。
 * 同一階級の全グループで共通の課題曲を使う（グループ間の公平性のため）。
 * マッピング先テーブル: {@code league_songs}。
 *
 * 譜面の同定はコードベースの他所と同じく title + difficultyName の文字列一致で行う
 * （song_definitions / scores に安定した譜面 ID が無いため）。週の途中でマスタが
 * 改名・削除されると突合できなくなるため、スコアレート計算に必須の {@link #notes} と
 * 表示用の {@link #level} を抽選時にスナップショットしておき、マスタが引けない場合の
 * フォールバックに使う。
 */
@Entity
@Table(name = "league_songs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_league_songs_week_tier_slot", columnNames = { "week_id", "tier", "slot" })
})
@Data
@NoArgsConstructor
public class LeagueSong {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属する週。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "week_id", nullable = false)
    private LeagueWeek week;

    /** 対象階級（1 が最上位）。 */
    @Column(nullable = false)
    private Integer tier;

    /** 課題曲スロット番号（1..3）。表示順に使う。 */
    @Column(nullable = false)
    private Integer slot;

    /** 楽曲タイトル。scores / song_definitions と文字列一致で突合する。 */
    @Column(nullable = false)
    private String title;

    /** 難易度名（"NORMAL" / "HYPER" / "ANOTHER" / "LEGGENDARIA"。Score 側の表記に合わせる）。 */
    @Column(name = "difficulty_name", length = 16, nullable = false)
    private String difficultyName;

    /** 難易度レベル（★数）。抽選時スナップショット。表示用。 */
    private Integer level;

    /**
     * ノーツ数。抽選時スナップショット。スコアレート計算（MAX = notes * 2）の分母で、
     * マスタから引けなくなった場合のフォールバック値。抽選プールの条件で notes > 0 を
     * 保証しているため必須カラムにできる。
     */
    @Column(nullable = false)
    private Integer notes;
}
