package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 ARENA モードのバトル履歴 1 試合分。
 *
 * 現実世界の概念: IIDX の ARENA（4 人対戦）モードで行われた 1 回のバトルと、
 * その 4 人のプレイヤー全員のスコア・順位・DJ 名等を含むスナップショット。
 * マッピング先テーブル: {@code arena_matches}。
 *
 * 主要な関連:
 *  - {@link #user} … このバトル履歴をインポートしたユーザー（ManyToOne）。
 *
 * なお対戦相手プレイヤーの情報は正規化せず、{@link #playersJson} / {@link #songsJson} に
 * JSON 形式でまとめて格納する（スキーマ柔軟性・書き込み効率重視）。
 */
@Entity
@Table(name = "arena_matches")
@Data
@NoArgsConstructor
public class ArenaMatch {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** このバトル履歴をインポートしたユーザー。 */
    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    /** バトル種別（例: "ローカルバトル"、"マッチングバトル"）。 */
    private String battleType;    // 例: "ローカルバトル"
    /** 対戦日時（文字列のまま保持。例: "2026-03-15 17:10"）。 */
    private String matchDate;     // 例: "2026-03-15 17:10"
    /** 自分の DJ ネーム。 */
    private String myDjName;
    /** 自分の ARENA クラス（例: "A1"）。 */
    private String myArenaClass;  // 例: "A1"
    /** 自分の順位（1〜4）。 */
    private Integer myRank;       // 1-4
    /** 自分の合計獲得ポイント。 */
    private Integer myTotalPt;

    /** 選曲された楽曲リスト（JSON 配列、例: [{title, difficulty}, ...]）。 */
    @Column(columnDefinition = "TEXT")
    private String songsJson;     // [{title, difficulty}]

    /** 対戦プレイヤー全員の情報（JSON 配列、各曲のスコア・獲得 pt・最終順位を含む）。 */
    @Column(columnDefinition = "TEXT")
    private String playersJson;   // [{djName, arenaClass, totalPt, rank, songScores:[{score,pt}]}]

    /** 履歴のインポート日時。 */
    private LocalDateTime importedAt = LocalDateTime.now();
}
