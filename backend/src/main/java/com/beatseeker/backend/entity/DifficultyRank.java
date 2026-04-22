package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 【エンティティの役割】 難易度表における 1 つのランク階級。
 *
 * 現実世界の概念: 例えば「☆12.5 の詐称譜面群」「IIDX 32 追加の未分類枠」といった、
 * 譜面をまとめるグループ単位。各ランクは複数の楽曲（{@link DifficultyRankSong}）を持つ。
 * マッピング先テーブル: {@code difficulty_ranks}。
 *
 * 主要な関連:
 *  - {@link #songs} … このランクに属する楽曲リストへの OneToMany（sortOrder 昇順）。
 *    親削除時は子も一緒に削除される（orphanRemoval）。
 */
@Entity
@Table(name = "difficulty_ranks")
@Data
@NoArgsConstructor
public class DifficultyRank {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ランク表記（例: "12.5"、"Uncategorized(IIDX 32)"）。 */
    @Column(nullable = false)
    private String rankValue;

    /** 表示時の並び順。値が小さいほど上位に表示される。 */
    private Integer sortOrder;

    /** リビジョン状態。"active"（本番適用中）または "draft"（編集中）。 */
    @Column(nullable = false)
    private String revision = "active";

    /**
     * このランクに属する楽曲。{@link DifficultyRankSong#difficultyRank} で双方向マッピング。
     * EAGER フェッチで取得し、sortOrder 昇順で並べる。
     */
    @OneToMany(mappedBy = "difficultyRank", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("sortOrder ASC")
    private List<DifficultyRankSong> songs = new ArrayList<>();
}
