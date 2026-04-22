package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 【エンティティの役割】 難易度ランクに属する個別の楽曲エントリ。
 *
 * 現実世界の概念: 「☆12.5 ランクに含まれる『冥 [A]』」のような、ランクと曲の対応関係 1 行。
 * マッピング先テーブル: {@code difficulty_rank_songs}。
 *
 * 主要な関連:
 *  - {@link #difficultyRank} … 親ランクへの ManyToOne。{@link DifficultyRank#songs} の逆側。
 */
@Entity
@Table(name = "difficulty_rank_songs")
@Data
@NoArgsConstructor
public class DifficultyRankSong {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 親ランクへの参照。{@link DifficultyRank} への ManyToOne。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "difficulty_rank_id", nullable = false)
    private DifficultyRank difficultyRank;

    /** 楽曲タイトル。LEGGENDARIA 譜面の場合は末尾に "[L]" が付く。 */
    @Column(nullable = false)
    private String songTitle;

    /** ランク内での表示順。値が小さいほど上に表示。 */
    private Integer sortOrder;
}
