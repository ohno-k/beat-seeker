package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 RANDOM の 2 つの並びを見比べて「どっちが押しやすいか」を答えた 1 件（サイドバーの「配置アンケート」）。
 *
 * 現実世界の概念: 譜面の同じ区間を 2 通りの鍵盤の並びで画像にして左右に並べ、押しやすい方を選んだもの。
 * 当たり配置ランキング（frontend/src/utils/randomEval.ts）の減点の形ごとの係数を学習する正解データになる
 * （scripts/fit-random-weights.mts が {@code GET /api/admin/random-pairs/export} で取り出す）。
 * 評価の数え方を後から変えても学び直せるよう、減点の数ではなく「どの譜面のどの区間をどの並びで見せたか」を残す。
 * マッピング先テーブル: {@code random_pair_votes}。
 */
@Entity
@Table(name = "random_pair_votes", indexes = {
        @Index(name = "idx_random_pair_votes_user_id", columnList = "user_id")
})
@Data
@NoArgsConstructor
public class RandomPairVote {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 答えたユーザー。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 譜面の textage の URL（GET /api/analysis/chart-playback の textage と同じ） */
    @Column(nullable = false, length = 200)
    private String textage;

    /** 曲名と難易度（当たり配置ランキングの summary.json の t・d と同じ。"4" = ANOTHER、"10" = LEGGENDARIA）とレベル。表示・集計用 */
    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 10)
    private String difficulty;

    private Integer level;

    /** 見せたプレイサイド（1 = 1P、2 = 2P）。並びはこのサイドでの左のレーンからの元の鍵盤番号 */
    @Column(nullable = false)
    private Integer side;

    /** 左・右に出した並び（例: "3726145"） */
    @Column(name = "pattern_left", nullable = false, length = 7)
    private String patternLeft;

    @Column(name = "pattern_right", nullable = false, length = 7)
    private String patternRight;

    /** 見せた区間（秒）と、表示上の小節番号 */
    @Column(name = "start_time", nullable = false)
    private Double startTime;

    @Column(name = "end_time", nullable = false)
    private Double endTime;

    @Column(name = "start_measure")
    private Integer startMeasure;

    @Column(name = "end_measure")
    private Integer endMeasure;

    /** 答え: LEFT（左が押しやすい）/ RIGHT / SAME（同じくらい）/ SKIP（判断できない） */
    @Column(nullable = false, length = 8)
    private String choice;

    /**
     * 2 つの並びの選び方: close（今の評価で僅差）/ random（無作為）/ offmir（正規・MIRROR と比べる）、
     * 確認問題の obvious（上位 1% と下位 1%）/ repeat（前の問題の左右を入れ替えた出し直し）
     */
    @Column(length = 16)
    private String strategy;

    /** 出題したときの評価で、その区間の左・右の総合（少ないほど押しやすい。今の評価がどれだけ当たっているかを見る用） */
    @Column(name = "model_left")
    private Double modelLeft;

    @Column(name = "model_right")
    private Double modelRight;

    /** 確認問題 repeat のとき、出し直した元の回答の ID */
    @Column(name = "repeat_of")
    private Long repeatOf;

    /** 問題を出してから答えるまでのミリ秒（速すぎる回答は学習で除く） */
    @Column(name = "response_ms")
    private Integer responseMs;

    /** 答えた日時。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
