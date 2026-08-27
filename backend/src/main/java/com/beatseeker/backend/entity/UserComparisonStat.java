package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 「ユーザー X から見た対戦相手 Y との EX-SCORE 勝敗」をレベル帯ごとに 1 行で持つ集計キャッシュ。
 *
 * 現実世界の概念: 管理画面の「ユーザー間スコア比較」で、1 人を選ぶと<b>全ユーザーとの勝敗</b>を
 * 勝率の降順で一覧表示する。その 1 行 1 行の元データがこのテーブル。
 * マッピング先テーブル: {@code user_comparison_stats}。
 *
 * なぜキャッシュテーブルなのか:
 *   全ユーザー × 全ユーザー × 全譜面の突き合わせはリクエスト毎に回すには重すぎる。
 *   結果は各ユーザーのベストスコアにしか依存せずリアルタイム性も不要なため、
 *   1 日 1 回のバッチ（{@link com.beatseeker.backend.service.UserComparisonStatsService}）で
 *   作り直した結果を読むだけにしている。
 *
 * 方向性: 「A→B」と「B→A」の 2 行を両方持つ<b>有向</b>の持ち方をしている。
 *   片方向だけ持って読み出し時に反転させることもできるが、
 *   「user_id = ? の行を全部引く」だけで一覧が完成する読みやすさを優先した。
 *
 * レベル帯: {@link LevelCategory} 単位に分けて保持する。画面のレベルトグル
 *   （Lv.10 以下 / Lv.11 / Lv.12）は、有効な行を足し合わせるだけで実現できる。
 *
 * 一意性制約: (user_id, opponent_id, level_category)。
 */
@Entity
@Table(name = "user_comparison_stats", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_comparison_stats_pair_level",
                columnNames = { "user_id", "opponent_id", "level_category" })
}, indexes = {
        @Index(name = "idx_user_comparison_stats_user", columnList = "user_id")
})
@Data
@NoArgsConstructor
public class UserComparisonStat {

    /**
     * 集計を分ける公式レベル帯。
     *
     * ANOTHER / LEGGENDARIA 以外の譜面はそもそも集計対象外なので、ここには現れない。
     */
    public enum LevelCategory {
        /** 公式レベル 10 以下。 */
        LV10MINUS,
        /** 公式レベル 11。 */
        LV11,
        /** 公式レベル 12。 */
        LV12
    }

    /** 主キー。DB 採番の代理キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 集計の主体となるユーザーの ID。WIN / LOSS はこのユーザーから見た勝敗。
     *
     * 派生データのキャッシュなので {@code @ManyToOne} ではなく素の ID を持つ。
     * 全ユーザー × 全ユーザーぶんを一括 INSERT する都合上、エンティティ参照を張ると
     * ロード・フラッシュのコストが跳ね上がるため。
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 比較相手のユーザー ID。 */
    @Column(name = "opponent_id", nullable = false)
    private Long opponentId;

    /** このレコードが対象とする公式レベル帯。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "level_category", nullable = false, length = 16)
    private LevelCategory levelCategory;

    /** 両者プレイ済みで userId 側の EX-SCORE が高かった譜面数。 */
    @Column(nullable = false)
    private int win;

    /** 両者プレイ済みで opponentId 側の EX-SCORE が高かった譜面数。 */
    @Column(nullable = false)
    private int loss;

    /** 両者プレイ済みで EX-SCORE が同点だった譜面数。 */
    @Column(nullable = false)
    private int draw;

    /** userId 側だけがプレイ済みの譜面数。 */
    @Column(name = "only_self", nullable = false)
    private int onlySelf;

    /** opponentId 側だけがプレイ済みの譜面数。 */
    @Column(name = "only_opponent", nullable = false)
    private int onlyOpponent;

    /** この行を集計した日時。「今日ぶんがまだ無い」の判定に使う。 */
    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt = LocalDateTime.now();
}
