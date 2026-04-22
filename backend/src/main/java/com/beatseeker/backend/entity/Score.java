package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 ユーザーの個別楽曲スコア記録。
 *
 * 現実世界の概念: 「あるユーザーが、ある楽曲の、ある難易度で現在出している最新スコア」を 1 行で表す。
 * マッピング先テーブル: {@code scores}。
 *
 * 一意性制約: (user_id, title, difficultyName, difficultyLevel) の 4 項目でユニーク。
 *   つまり 1 ユーザーにつき「同じ曲・同じ難易度」のレコードは 1 つだけ存在する。
 *   スコア更新時は既存レコードを上書きする運用。
 *
 * 主要な関連:
 *  - {@link #user} … このスコアを保持するユーザーへの ManyToOne（多対一）。
 *
 * なお履歴の推移は {@link ScoreHistoryLog}（アップロード単位のサマリ）で別途保持する。
 */
@Entity
@Table(name = "scores", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "user_id", "title", "difficultyName", "difficultyLevel" })
})
@Data
@NoArgsConstructor
public class Score {

    /** 主キー。DB 採番の代理キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** このスコアの所有者。{@link User} への ManyToOne 関連。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 楽曲タイトル。IIDX の公式曲名をそのまま格納。 */
    private String title;
    /** アーティスト名（作曲者）。 */
    private String artist;
    /** ジャンル名。 */
    private String genre;
    /** 難易度名。"ANOTHER"、"HYPER"、"NORMAL"、"BEGINNER"、"LEGGENDARIA" のいずれか。 */
    private String difficultyName; // ANOTHER, HYPER など
    /** 難易度レベル（★数）。通常 1〜12。 */
    private Integer difficultyLevel;
    /** EX スコア値（0〜ノーツ数*2）。 */
    private Integer score;
    /** クリアタイプ。"NO PLAY"/"FAILED"/"ASSIST CLEAR"/"EASY CLEAR"/"CLEAR"/"HARD CLEAR"/"EX HARD CLEAR"/"FULLCOMBO CLEAR" のいずれか。 */
    private String clearType;
    /** DJ LEVEL。"AAA"/"AA"/"A"/"B"/"C"/"D"/"E"/"F"。 */
    private String djLevel;
    /** PGREAT 数。 */
    private Integer pgreat;
    /** GREAT 数。 */
    private Integer great;
    /** ミスカウント（BP）。 */
    private Integer missCount;
    /** プレー回数。 */
    private Integer playCount;

    /** ユーザーがこの譜面に残したメモ。長文可（TEXT 型）。 */
    @Column(columnDefinition = "TEXT")
    private String memo;

    /** スナップショット ID。過去時点のスコアを参照するためのラベル（任意）。 */
    private String snapshotId;

    /** このスコアがアップロード（更新）された日時。 */
    @Column(nullable = false)
    private LocalDateTime uploadedAt = LocalDateTime.now();
}
