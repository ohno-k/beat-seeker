package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 譜面傾向プロファイル。
 * chart_cache/profiles/{ver}/{key}_{diff}.json の内容を DB に保存したもの。
 */
@Entity
@Table(name = "chart_tendency_profiles")
@Data
@NoArgsConstructor
public class ChartTendencyProfile implements Persistable<String> {

    @Transient
    private boolean isNewEntity = true;

    @Override
    public String getId() { return textage; }

    @Override
    public boolean isNew() { return isNewEntity; }

    /** textage URLフラグメント (例: "22/chrono_p.html?1AC00") */
    @Id
    @Column(name = "textage", nullable = false, length = 128)
    private String textage;

    private String title;
    private String artist;

    /** "4"=ANOTHER, "10"=LEGGENDARIA */
    @Column(nullable = false, length = 4)
    private String difficulty;

    private Integer level;

    /** BPM文字列 ("196" or "12-300") */
    private String bpmRaw;

    private Integer bpmMain;
    private Boolean isSoflan;

    private Integer notes;
    private Integer events;

    private Double dominantEff16;
    private Double weightedEff16;

    private Double scratchPct;
    private Double chordPct;
    private Double singlePct;

    private Integer ranuchi;

    /** タグリスト (JSON配列文字列, e.g. ["scratch_heavy","has_32nd"]) */
    @Column(columnDefinition = "TEXT")
    private String tagsJson;

    /** 上位10インターバルの詳細 (JSON オブジェクト) */
    @Column(columnDefinition = "TEXT")
    private String intervalDistJson;

    /** 和音分布 (JSON オブジェクト) */
    @Column(columnDefinition = "TEXT")
    private String chordDistJson;

    /** CNオブジェクト数 (各1本がstart+endで2ノーツ相当、CNなし曲はnull) */
    private Integer cnNotes;

    /** CN内のスクラッチ割合 (%) */
    private Double cnScratchPct;

    /** CN保持中に他の鍵盤が降ってくるノーツの割合 (%) */
    private Double cnKbdOverlapPct;

    /** CNインターバル分布 (JSON オブジェクト、CNなし曲はnull) */
    @Column(columnDefinition = "TEXT")
    private String cnIntervalDistJson;

    /** 鍵盤のみ（スクラッチ除外）のインターバル分布 (JSON オブジェクト) */
    @Column(columnDefinition = "TEXT")
    private String kbdIntervalDistJson;

    /** スクラッチ単体のインターバル分布 (JSON オブジェクト、スクラッチなし曲はnull) */
    @Column(columnDefinition = "TEXT")
    private String scrIntervalDistJson;

    // ── 配置パターン属性 ─────────────────────────────────────────

    /** 縦連打 (Jack) 出現回数 */
    private Integer jackCount;
    /** 縦連打に含まれるノーツ数 */
    private Integer jackNotes;
    /** 縦連打ノーツの全体に対する割合 (%) */
    private Double jackPct;

    /** トリル出現回数 */
    private Integer trillCount;
    /** トリルに含まれるノーツ数 */
    private Integer trillNotes;
    /** トリルノーツの全体に対する割合 (%) */
    private Double trillPct;

    /** 階段出現回数 */
    private Integer stairsCount;
    /** 階段に含まれるノーツ数 */
    private Integer stairsNotes;
    /** 階段ノーツの全体に対する割合 (%) */
    private Double stairsPct;

    /** 二重階段出現回数 */
    private Integer dstairsCount;
    /** 二重階段に含まれるノーツ数 */
    private Integer dstairsNotes;
    /** 二重階段ノーツの全体に対する割合 (%) */
    private Double dstairsPct;

    /** 小節ごとのノーツ数 (JSON配列) */
    @Column(columnDefinition = "TEXT")
    private String measureNotesJson;

    /** 小節ごとの鍵盤ノーツ数 (JSON配列) */
    @Column(columnDefinition = "TEXT")
    private String measureNotesKbdJson;

    /** 小節ごとの皿ノーツ数 (JSON配列) */
    @Column(columnDefinition = "TEXT")
    private String measureNotesScrJson;
}
