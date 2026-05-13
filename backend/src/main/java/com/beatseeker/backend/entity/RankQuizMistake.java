package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 「非公式難易度クイズ」で間違えた譜面の復習プール 1 件。
 *
 * 現実世界の概念: 曲名↔★ランクのペアでユーザーが間違えた問題を覚えておき、
 * 次回以降のセッションに混ぜて再出題することで定着を狙う。
 * マッピング先テーブル: {@code rank_quiz_mistakes}。
 *
 * 一意性制約: (user_id, title, difficulty_name)。同一譜面の復習レコードは 1 件のみ。
 *
 * 状態遷移:
 *  - 間違えると mistakeCount++、reviewStreak=0
 *  - 復習出題で正答すると reviewStreak++（連続正答カウント）
 *  - reviewStreak が {@link #MASTERY_STREAK} に達すると mastered=true となり
 *    以降の復習プール対象から除外される
 */
@Entity
@Table(name = "rank_quiz_mistakes", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "user_id", "title", "difficulty_name" })
})
@Data
@NoArgsConstructor
public class RankQuizMistake {

    /** mastered 判定に必要な連続正答数。 */
    public static final int MASTERY_STREAK = 2;

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 復習プール所有者。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 対象楽曲タイトル。 */
    @Column(nullable = false)
    private String title;

    /** 対象譜面の難易度名。"ANOTHER" / "LEGGENDARIA"。 */
    @Column(nullable = false, length = 20)
    private String difficultyName;

    /** 正解の非公式難易度値（例: "12.3"）。出題時の選択肢生成にも使う。 */
    @Column(nullable = false, length = 20)
    private String correctRank;

    /** 累計間違え回数（同じ譜面で何度間違えたか）。 */
    @ColumnDefault("0")
    @Column(nullable = false)
    private Integer mistakeCount = 0;

    /** 復習で連続正答した回数。{@link #MASTERY_STREAK} 到達で mastered になる。 */
    @ColumnDefault("0")
    @Column(nullable = false)
    private Integer reviewStreak = 0;

    /** 卒業フラグ。true の譜面は復習プールから除外される。 */
    @ColumnDefault("false")
    @Column(nullable = false)
    private Boolean mastered = false;

    /** 直近の出題（または間違え記録）日時。 */
    @Column(nullable = false)
    private LocalDateTime lastSeenAt = LocalDateTime.now();

    /** レコード作成日時。 */
    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
