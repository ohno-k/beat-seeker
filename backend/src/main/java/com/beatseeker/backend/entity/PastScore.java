package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 過去作（IIDX 30 RESIDENT 〜 32 Pinky Crush）のスコア記録。
 *
 * 現実世界の概念: IIDX は毎作品でスコアがリセットされるため、ユーザーが手元に保存していた
 * 過去シリーズのスコア CSV を「その作品のスコア」として保管しておくためのテーブル。
 * マッピング先テーブル: {@code past_scores}。
 *
 * なぜ {@link Score} と別テーブルなのか:
 *   {@link Score} を集計する native SQL（ランキング / BEAT-PT / 曲別順位 / リーグ / 大会）が
 *   20 本以上あり、いずれもバージョン条件を持たない。{@code scores} にバージョン列を足すと
 *   その全てに絞り込みを入れ忘れた瞬間に集計値が静かに壊れる。テーブルを分ければ
 *   「過去作が集計に混ざらない」ことをスキーマで保証でき、既存 SQL は 1 本も触らずに済む。
 *
 * 不変条件: 本エンティティはランキング・BEAT-PT・RATE-PT・曲別順位・リーグ・大会・Tier 投票の
 *   いかなる計算にも参加しない。参照は「本人の過去スコア表示」用途に限る。
 *
 * 一意性制約: (user_id, version, title, difficultyName) の 4 項目。
 *   {@link Score} と異なり difficultyLevel（★）をキーに含めない。★は作品間で変動するため、
 *   キーに含めると同一譜面が作品をまたいで別行として二重登録されてしまう。
 *
 * 主要な関連:
 *  - {@link #user} … このスコアを保持するユーザーへの ManyToOne（多対一）。
 */
@Entity
@Table(name = "past_scores", uniqueConstraints = {
        @UniqueConstraint(name = "uk_past_scores_user_ver_chart",
                columnNames = { "user_id", "version", "title", "difficultyName" })
}, indexes = {
        @Index(name = "idx_past_scores_user_ver", columnList = "user_id,version")
})
@Data
@NoArgsConstructor
public class PastScore {

    /** 主キー。DB 採番の代理キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** このスコアの所有者。{@link User} への ManyToOne 関連。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 作品バージョン番号（30=RESIDENT, 31=EPOLIS, 32=Pinky Crush）。
     * 現行作（33）は {@code scores} テーブル側が正なのでここには入らない。
     * 採番は {@link com.beatseeker.backend.service.IidxVersions} を参照。
     */
    @Column(nullable = false)
    private Integer version;

    /** 楽曲タイトル。IIDX の公式曲名をそのまま格納（CSV の「タイトル」列）。 */
    @Column(nullable = false)
    private String title;
    /** アーティスト名（作曲者）。 */
    private String artist;
    /** ジャンル名。 */
    private String genre;
    /** 難易度名。"BEGINNER"/"NORMAL"/"HYPER"/"ANOTHER"/"LEGGENDARIA" のいずれか。 */
    @Column(nullable = false)
    private String difficultyName;
    /** 難易度レベル（★数）。その作品時点の値なので、現行作と一致しないことがある。 */
    private Integer difficultyLevel;
    /** EX スコア値。 */
    private Integer score;
    /** クリアタイプ。"FAILED"/"ASSIST CLEAR"/…/"FULLCOMBO CLEAR"。 */
    private String clearType;
    /** DJ LEVEL。"AAA"/"AA"/"A"/"B"/"C"/"D"/"E"/"F"。 */
    private String djLevel;
    /** PGREAT 数。 */
    private Integer pgreat;
    /** GREAT 数。 */
    private Integer great;
    /** ミスカウント（BP）。 */
    private Integer missCount;
    /** プレー回数。CSV では曲単位の値なので、同曲の全難易度に同じ値が入る。 */
    private Integer playCount;

    /**
     * CSV の「最終プレー日時」列を無加工で保持した文字列（例: {@code "2025-09-17 08:27"}）。
     * 取り込み済み CSV がどの時点のものかを管理画面で示すためだけに使う。
     * 日付型にせず文字列のままなのは、書式が変わっても取り込みを失敗させないため。
     * この書式は辞書順 = 時系列順なので MAX() で最新を取れる。
     */
    @Column(length = 32)
    private String lastPlayedAt;

    /** このレコードが取り込まれた日時。 */
    @Column(nullable = false)
    private LocalDateTime importedAt = LocalDateTime.now();
}
