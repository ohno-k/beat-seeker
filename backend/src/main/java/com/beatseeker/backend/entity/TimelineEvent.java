package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 タイムラインに表示する出来事 1 件分。
 *
 * 現実世界の概念: 「いつ、どのユーザーに、どんな出来事が起きたか」のレコード。
 * フレンド画面のタイムラインタブで自分とフレンドの活動を時系列表示するために使う。
 * マッピング先テーブル: {@code timeline_events}。
 *
 * イベント種別 ({@link #type}):
 *  - "SCORE_UPDATE"   : CSV アップロードでスコアが更新された（payload に updatedSongs 配列を JSON で格納）
 *  - "OVERTAKE_SONG"  : 譜面単位の EX スコアでフレンド／仮想ライバルを抜いた
 *  - "OVERTAKE_TOTAL" : 総合 BEAT-PT でフレンド／仮想ライバルを抜いた
 *
 * 主要な関連:
 *  - {@link #user} … この出来事の主体（ManyToOne）。
 */
@Entity
@Table(name = "timeline_events", indexes = {
        @Index(name = "idx_timeline_user_created", columnList = "user_id, createdAt DESC")
})
@Data
@NoArgsConstructor
public class TimelineEvent {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 出来事の主体ユーザー（自分視点・フレンド視点どちらでも、その出来事の中心人物）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * イベント種別。
     *  - "SCORE_UPDATE" : スコア更新（CSV アップロード時の差分）
     *  - "OVERTAKE_SONG" : 譜面単位でフレンド/仮想ライバルを抜いた
     *  - "OVERTAKE_TOTAL" : 総合 BEAT-PT でフレンド/仮想ライバルを抜いた
     */
    @Column(nullable = false, length = 30)
    private String type;

    /** イベント固有の詳細データを格納する JSON 文字列。 */
    @Column(columnDefinition = "TEXT")
    private String payload;

    /** 発生日時。作成後は不変。 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
