package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 譜面オプションに関するユーザー投票。
 *
 * 現実世界の概念: 「この譜面は RANDOM で攻略した」「MIRROR が有効だった」など、
 * ユーザーが推奨する譜面オプションを 1 票分記録したもの。
 * 集計結果は「この譜面はランダム派が多い」といった攻略情報として表示される。
 * マッピング先テーブル: {@code option_votes}。
 *
 * 一意性制約: (user_id, title, difficulty_name) でユニーク。同一譜面に対する票は 1 ユーザー 1 票のみ。
 * 再投票時は既存レコードを上書きする運用。
 *
 * 主要な関連:
 *  - {@link #user} … 投票したユーザーへの ManyToOne。
 */
@Entity
@Table(name = "option_votes", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "user_id", "title", "difficulty_name" })
})
@Data
@NoArgsConstructor
public class OptionVote {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 投票したユーザー。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 対象楽曲タイトル。 */
    @Column(nullable = false)
    private String title;

    /** 対象譜面の難易度名。"ANOTHER"、"LEGGENDARIA" など。 */
    @Column(nullable = false)
    private String difficultyName; // ANOTHER, LEGGENDARIA

    /**
     * 投票したオプション種別。1P 視点に正規化（2P 側の票も 1P 表記に揃えて集計する）。
     * 取り得る値: "REGULAR"（正規）, "MIRROR"（ミラー）, "RANDOM"（ランダム）,
     *   "R-RANDOM"（R-ランダム）, "S-RANDOM"（S-ランダム）。
     */
    @Column(nullable = false, length = 20)
    private String optionType; // REGULAR, MIRROR, RANDOM, R-RANDOM, S-RANDOM (1P 視点に正規化)

    /** 投票日時。 */
    @Column(nullable = false)
    private LocalDateTime votedAt = LocalDateTime.now();
}
