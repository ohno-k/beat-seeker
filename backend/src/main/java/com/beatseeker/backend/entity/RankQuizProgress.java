package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 ユーザーごとの「非公式難易度クイズ」累計進捗。
 *
 * 現実世界の概念: 曲名↔★ランクの記憶を強化するためのミニゲーム。
 * 1ユーザー1レコード（user_id 一意）。XPとLvを累積記録する。
 * マッピング先テーブル: {@code rank_quiz_progress}。
 *
 * 主要な関連:
 *  - {@link #user} … 進捗の所有者。OneToOne 相当（user_id ユニーク）。
 */
@Entity
@Table(name = "rank_quiz_progress", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "user_id" })
})
@Data
@NoArgsConstructor
public class RankQuizProgress {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 進捗の所有者。 */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /** 累計獲得 XP。正答 1 問につき新規 +10 / 復習 +20 を加算する想定。 */
    @ColumnDefault("0")
    @Column(nullable = false)
    private Long xp = 0L;

    /** 現在の Lv。XP から導出可能だが UI 即値表示と将来的なボーナス算出用にキャッシュする。 */
    @ColumnDefault("1")
    @Column(nullable = false)
    private Integer level = 1;

    /** 累計回答数（正誤合算）。 */
    @ColumnDefault("0")
    @Column(nullable = false)
    private Integer totalAnswered = 0;

    /** 累計正答数。正答率の表示等に使う。 */
    @ColumnDefault("0")
    @Column(nullable = false)
    private Integer totalCorrect = 0;

    /** 直近のセッション開始（または最終回答）日時。デイリー判定の起点。 */
    private LocalDateTime lastPlayedAt;

    /** レコード作成日時。 */
    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
