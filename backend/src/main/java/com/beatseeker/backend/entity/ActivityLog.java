package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 ユーザーの活動履歴（タイムライン）。
 *
 * 現実世界の概念: 「いつ、どのユーザーが、どんなイベント（ランクアップ等）を起こしたか」の記録。
 * プロフィール画面の活動履歴やフレンドタイムラインへの表示に使われる。
 * マッピング先テーブル: {@code activity_logs}。
 *
 * 主要な関連:
 *  - {@link #user} … この活動の主体（ManyToOne）。
 */
@Entity
@Table(name = "activity_logs")
@Data
@NoArgsConstructor
public class ActivityLog {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 活動の主体ユーザー。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 活動種別。
     *  - "RANK_UP" : Beat-Tier のランクが上がった
     *  （将来追加される種別あり）
     */
    @Column(nullable = false, length = 30)
    private String type;

    /** 変化前の値（例: ランクアップ前の "Silver I"）。 */
    @Column(length = 50)
    private String oldValue;

    /** 変化後の値（例: ランクアップ後の "Gold I"）。 */
    @Column(length = 50)
    private String newValue;

    /** 発生日時。作成後は不変。 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
