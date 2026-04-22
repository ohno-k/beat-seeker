package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 ユーザー × 譜面単位の順位キャッシュ。
 *
 * 現実世界の概念: 「この譜面（曲 × 難易度）における、このユーザーの全体順位」を
 * 事前計算して保持するキャッシュレコード。オンデマンド計算のコストを避けるため、
 * バッチまたはスコア更新タイミングで洗い替えされる。
 * マッピング先テーブル: {@code user_song_ranks}（user_id にインデックス）。
 *
 * 所有関係: TierComment / TierVote と同様、User へは Long FK のみの緩い参照。
 */
@Entity
@Table(name = "user_song_ranks", indexes = {
    @Index(name = "idx_user_song_ranks_user_id", columnList = "user_id")
})
public class UserSongRank {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 対象ユーザー ID。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 対象楽曲タイトル。 */
    @Column(nullable = false)
    private String title;

    /** 対象譜面の難易度名（例: "ANOTHER"）。 */
    @Column(name = "difficulty_name", nullable = false)
    private String difficultyName;

    /** 対象譜面の難易度レベル（★数）。 */
    @Column(name = "difficulty_level")
    private Integer difficultyLevel;

    /**
     * 順位。1 が最上位（ベスト）。
     * カラム名は SQL 予約語との衝突を避けるため {@code rank_position} にマッピング。
     */
    @Column(name = "rank_position", nullable = false)
    private Integer rank;

    /** この譜面の総プレイヤー数（母数）。rank / total で「上位 X%」を計算できる。 */
    @Column(nullable = false)
    private Integer total;

    /** 順位を計算した日時。古いレコードは再計算対象の判定に使える。 */
    @Column(name = "calculated_at")
    private LocalDateTime calculatedAt;

    // ── 以下、Getter / Setter（Lombok 非使用のため手書き） ──

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDifficultyName() { return difficultyName; }
    public void setDifficultyName(String difficultyName) { this.difficultyName = difficultyName; }

    public Integer getDifficultyLevel() { return difficultyLevel; }
    public void setDifficultyLevel(Integer difficultyLevel) { this.difficultyLevel = difficultyLevel; }

    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }

    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }

    public LocalDateTime getCalculatedAt() { return calculatedAt; }
    public void setCalculatedAt(LocalDateTime calculatedAt) { this.calculatedAt = calculatedAt; }
}
