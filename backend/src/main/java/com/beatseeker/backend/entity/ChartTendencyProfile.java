package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 【エンティティの役割】 各譜面の「譜面傾向」分析結果を保存するプロファイル。
 *
 * 現実世界の概念: ある 1 譜面について、スクラッチの割合・和音の割合・階段/トリル等の
 * 出現回数、BPM、譜面密度の小節別分布などを事前計算してキャッシュしたもの。
 * chart_cache/profiles/{ver}/{key}_{diff}.json の内容を DB に保存したもの。
 * マッピング先テーブル: {@code chart_tendency_profiles}。
 *
 * 識別子: textage URL フラグメント（例: "22/chrono_p.html?1AC00"）を主キーとする自然キー方式。
 * Spring Data JPA の {@link Persistable} を実装し、save() 時に INSERT/UPDATE を適切に切り替える。
 */
@Entity
@Table(name = "chart_tendency_profiles")
@Data
@NoArgsConstructor
public class ChartTendencyProfile implements Persistable<String> {

    /** 新規エンティティかどうかのフラグ（DB 非永続）。Persistable の実装で使用。 */
    @Transient
    private boolean isNewEntity = true;

    /** Persistable の ID 取得。textage を主キーとして返す。 */
    @Override
    public String getId() { return textage; }

    /** Persistable の新規判定。INSERT か UPDATE かを JPA に知らせる。 */
    @Override
    public boolean isNew() { return isNewEntity; }

    /** textage URL フラグメント（例: "22/chrono_p.html?1AC00"）。主キー。 */
    @Id
    @Column(name = "textage", nullable = false, length = 128)
    private String textage;

    /** 楽曲タイトル。 */
    private String title;
    /** アーティスト名。 */
    private String artist;

    /** 難易度コード。"4"=ANOTHER, "10"=LEGGENDARIA。 */
    @Column(nullable = false, length = 4)
    private String difficulty;

    /** 難易度レベル（★数）。 */
    private Integer level;

    /** BPM 文字列表記（例: "196" や "12-300" のようなソフラン範囲）。 */
    private String bpmRaw;

    /** メイン BPM（ソフラン曲でも代表値を整数化したもの）。 */
    private Integer bpmMain;
    /** ソフラン譜面かどうか。 */
    private Boolean isSoflan;

    /** 総ノーツ数。 */
    private Integer notes;
    /** イベント数（同時押しをまとめた単位）。 */
    private Integer events;

    /** 最頻 16 分間隔値（主要リズムを表す指標）。 */
    private Double dominantEff16;
    /** 加重平均 16 分間隔値。 */
    private Double weightedEff16;

    /** スクラッチ比率（全ノーツに対する皿の割合, %）。 */
    private Double scratchPct;
    /** 和音（複数同時押し）比率（%）。 */
    private Double chordPct;
    /** 単押し比率（%）。 */
    private Double singlePct;

    /** 乱打指標（randomness 的な値）。 */
    private Integer ranuchi;

    /** タグリスト（JSON 配列文字列, 例: ["scratch_heavy","has_32nd"]）。 */
    @Column(columnDefinition = "TEXT")
    private String tagsJson;

    /** 上位 10 インターバルの詳細（JSON オブジェクト）。 */
    @Column(columnDefinition = "TEXT")
    private String intervalDistJson;

    /** 和音分布（JSON オブジェクト）。 */
    @Column(columnDefinition = "TEXT")
    private String chordDistJson;

    /** CN オブジェクト数（各 1 本が start+end で 2 ノーツ相当。CN なし曲は null）。 */
    private Integer cnNotes;

    /** CN 内のスクラッチ割合（%）。 */
    private Double cnScratchPct;

    /** CN 保持中に他の鍵盤が降ってくるノーツの割合（%）。 */
    private Double cnKbdOverlapPct;

    /** CN インターバル分布（JSON オブジェクト。CN なし曲は null）。 */
    @Column(columnDefinition = "TEXT")
    private String cnIntervalDistJson;

    /** 鍵盤のみ（スクラッチ除外）のインターバル分布（JSON オブジェクト）。 */
    @Column(columnDefinition = "TEXT")
    private String kbdIntervalDistJson;

    /** スクラッチ単体のインターバル分布（JSON オブジェクト。スクラッチなし曲は null）。 */
    @Column(columnDefinition = "TEXT")
    private String scrIntervalDistJson;

    // ── 配置パターン属性 ─────────────────────────────────────────

    /** 縦連打（Jack）の出現回数。 */
    private Integer jackCount;
    /** 縦連打に含まれるノーツ数。 */
    private Integer jackNotes;
    /** 縦連打ノーツの全体に対する割合（%）。 */
    private Double jackPct;

    /** トリル出現回数。 */
    private Integer trillCount;
    /** トリルに含まれるノーツ数。 */
    private Integer trillNotes;
    /** トリルノーツの全体に対する割合（%）。 */
    private Double trillPct;

    /** 階段出現回数。 */
    private Integer stairsCount;
    /** 階段に含まれるノーツ数。 */
    private Integer stairsNotes;
    /** 階段ノーツの全体に対する割合（%）。 */
    private Double stairsPct;

    /** 二重階段出現回数。 */
    private Integer dstairsCount;
    /** 二重階段に含まれるノーツ数。 */
    private Integer dstairsNotes;
    /** 二重階段ノーツの全体に対する割合（%）。 */
    private Double dstairsPct;

    /** 小節ごとのノーツ数（JSON 配列）。譜面密度グラフの描画に使う。 */
    @Column(columnDefinition = "TEXT")
    private String measureNotesJson;

    /** 小節ごとの鍵盤ノーツ数（JSON 配列）。 */
    @Column(columnDefinition = "TEXT")
    private String measureNotesKbdJson;

    /** 小節ごとの皿ノーツ数（JSON 配列）。 */
    @Column(columnDefinition = "TEXT")
    private String measureNotesScrJson;
}
