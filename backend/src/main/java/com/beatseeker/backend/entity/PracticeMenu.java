package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 【エンティティの役割】 1 ユーザー・1 週ぶんの練習メニュー。
 *
 * 現実世界の概念: 「今週なにを、どこまで、何回やるか」の献立表。
 * 月曜 0:00 JST に始まり、日曜 23:59 JST で締める 1 週を単位とする。
 * マッピング先テーブル: {@code practice_menus}。
 *
 * 一意性制約: (user_id, week_start)。同じ週のメニューはユーザーごとに 1 枚しか持たない。
 * 「組み直す」は新しい行を作らず、この行の {@link #items} を差し替えて
 * {@link #regenerateCount} を増やす。
 *
 * 生成時点のスナップショット（{@link #targetTier} / {@link #fromTotalBeatPt}）を持つのは、
 * 週の途中でティアが上がっても「この週はどのティアを目指して組んだメニューか」を
 * 振り返りで正しく言えるようにするため。
 *
 * 主要な関連:
 *  - {@link #user} … このメニューの持ち主への ManyToOne。
 *  - {@link #items} … 提示された譜面（計測 / 課題 / 埋め）への OneToMany。
 */
@Entity
@Table(name = "practice_menus", uniqueConstraints = {
        @UniqueConstraint(name = "uk_practice_menus_user_week", columnNames = { "user_id", "week_start" })
}, indexes = {
        @Index(name = "idx_practice_menus_user_week", columnList = "user_id,week_start")
})
@Data
@NoArgsConstructor
public class PracticeMenu {

    /** 主キー。DB 採番の代理キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** このメニューの持ち主。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 週の開始日（その週の月曜、JST）。週の締めは翌週月曜 0:00 の直前 = 日曜 23:59:59。 */
    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    /**
     * 生成時点で目標にしていた「次の名前付きティア」（例 "Veteran"）。
     * 最上位（Legend）到達済みなら null。
     */
    @Column(length = 16)
    private String targetTier;

    /** 生成時点の総 BEAT-PT。振り返りで「この週にいくつ伸びたか」を出すための基準。 */
    private Double fromTotalBeatPt;

    /** 生成時点の現ティア名（例 "Expert"）。副ティア（I〜V）は含まない。 */
    @Column(length = 16)
    private String fromTier;

    /** 最初に生成した日時。 */
    @Column(nullable = false)
    private LocalDateTime generatedAt = LocalDateTime.now();

    /** 「組み直す」を実行した回数。週あたりの上限判定に使う。 */
    @Column(nullable = false)
    private Integer regenerateCount = 0;

    /** OPEN（進行中）/ CLOSED（週締め済み）。 */
    @Column(nullable = false, length = 8)
    private String status = "OPEN";

    /** 週締め時に確定した集計 {@code {achieved, progressed, untouched, total}} の JSON。 */
    @Column(columnDefinition = "TEXT")
    private String summaryJson;

    /**
     * この週に提示した譜面。
     * 組み直しで全入れ替えするため {@code orphanRemoval = true} を付ける。
     */
    @OneToMany(mappedBy = "menu", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PracticeMenuItem> items = new ArrayList<>();
}
