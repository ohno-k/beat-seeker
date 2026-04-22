package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 【エンティティの役割】 楽曲メタ情報のマスタ定義。
 *
 * 現実世界の概念: IIDX に収録されている 1 譜面（= 1 曲 × 1 難易度）のマスタデータ。
 * 各ユーザーのスコア（{@link Score}）は title + difficulty で本マスタに紐づく。
 * マッピング先テーブル: {@code song_definitions}。
 *
 * ライフサイクル: バージョンアップや譜面定数見直しに伴い、editor 画面から
 * revision = "draft" として編集中のコピーを作り、反映時に "active" に切り替える運用。
 */
@Entity
@Table(name = "song_definitions")
@Data
@NoArgsConstructor
public class SongDefinition {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 楽曲タイトル。必須。 */
    @Column(nullable = false)
    private String title;

    /** アーティスト名。 */
    private String artist;

    /** ジャンル名。 */
    private String genre;

    /** ノーツ数（総オブジェクト数）。 */
    private Integer notes;

    /** BPM 表記（例: "196" や "12-300" のような範囲指定も入りうる）。 */
    private String bpm;

    /** 難易度コード: 1=BEGINNER, 2=NORMAL, 3=HYPER, 4=ANOTHER, 10=LEGGENDARIA */
    @Column(nullable = false)
    private String difficulty;

    /** 難易度レベル（★数）。通常 1〜12。 */
    private Integer level;

    /** 世界記録スコア（null 可。主に ANOTHER / LEGGENDARIA にのみ設定される）。 */
    private Integer wr;

    /** 平均スコア（null 可）。 */
    private Integer avg;

    /** Textage の URL フラグメント（null 可。譜面画像表示に使う）。 */
    private String textage;

    /** レート計算用の譜面係数（null 可）。 */
    private Double coef;

    /** 難易度レベル文字列（null 可。小数点付き譜面定数等を保持する拡張欄）。 */
    private String difficultyLevel;

    /** DP（ダブルプレー）レベル文字列（null 可）。 */
    private String dpLevel;

    /** リビジョン状態。"active"（本番適用中）または "draft"（編集中の下書き）。 */
    @Column(nullable = false)
    private String revision = "active";
}
