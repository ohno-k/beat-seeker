package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 IIDX 非公式団体戦 1 大会分の親レコード。
 *
 * 現実世界の概念: 5 チーム × 4 人の総当たり (10 matchup × 3 戦 = 30 試合) で行う団体戦。
 * マッピング先テーブル: {@code competitions}。
 *
 * status の取りうる値:
 *  - {@code draft}: 主催が編成中。チーム名や参加者を編集可能。
 *  - {@code open}: matchup / match が自動生成済み。TL の起用編成と参加者の自選曲提出を受付中。
 *  - {@code locked}: 全試合の両サイドが個別ロック済み。Reveal フェーズに移行。
 *  - {@code finished}: 全試合の Reveal/結果記録が完了。
 *
 * 作成者 {@link #createdBy} はサイドバー Competition セクションへアクセス可能な 4 ID
 * (18 / 19 / 23 / 210) のいずれかが想定されている (バックエンド側の権限判定は
 * {@code OrganizerAuthService} で実施)。
 */
@Entity
@Table(name = "competitions")
@Data
@NoArgsConstructor
public class Competition {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 大会名 (例: "BPL 模擬戦 2026 春")。 */
    @Column(length = 200, nullable = false)
    private String name;

    /**
     * 状態。{@code draft / open / locked / finished}。
     * 文字列で保持し、サービス層で許容遷移をチェックする。
     */
    @Column(length = 16, nullable = false)
    private String status = "draft";

    /** 自選曲提出の締切。null の場合は締切未設定。 */
    private LocalDateTime deadlineAt;

    /** 主催ユーザー (= 大会を作成したログインユーザー)。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    /** 大会レコードの作成日時。 */
    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 全試合がロック完了し locked ステータスに移った日時。 */
    private LocalDateTime lockedAt;
}
