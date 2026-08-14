package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 ユーザーの個別楽曲スコア記録。
 *
 * 現実世界の概念: 「あるユーザーが、ある楽曲の、ある難易度で現在出している最新スコア」を 1 行で表す。
 * マッピング先テーブル: {@code scores}。
 *
 * 一意性制約: (user_id, title, difficultyName, difficultyLevel, source) の 5 項目でユニーク。
 *   1 ユーザーにつき「同じ曲・同じ難易度・同じ取得元（arcade/infinitas）」のレコードは 1 つだけ存在し、
 *   アーケード（CSV）由来と INFINITAS 画面取得由来のスコアは別レコードとして並走する。
 *   旧 4 項目制約は DataInitializer で起動時に DROP する（PostgreSQL の autogen 名 {@code uk_xxxx} を直叩き）。
 *
 * 主要な関連:
 *  - {@link #user} … このスコアを保持するユーザーへの ManyToOne（多対一）。
 *
 * なお履歴の推移は {@link ScoreHistoryLog}（アップロード単位のサマリ）で別途保持する。
 */
@Entity
@Table(name = "scores", uniqueConstraints = {
        @UniqueConstraint(name = "uk_scores_user_title_diff_source",
                columnNames = { "user_id", "title", "difficultyName", "difficultyLevel", "source" })
})
@Data
@NoArgsConstructor
public class Score {

    /** 主キー。DB 採番の代理キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** このスコアの所有者。{@link User} への ManyToOne 関連。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 楽曲タイトル。IIDX の公式曲名をそのまま格納。 */
    private String title;
    /** アーティスト名（作曲者）。 */
    private String artist;
    /** ジャンル名。 */
    private String genre;
    /** 難易度名。"ANOTHER"、"HYPER"、"NORMAL"、"BEGINNER"、"LEGGENDARIA" のいずれか。 */
    private String difficultyName; // ANOTHER, HYPER など
    /** 難易度レベル（★数）。通常 1〜12。 */
    private Integer difficultyLevel;
    /** EX スコア値（0〜ノーツ数*2）。 */
    private Integer score;
    /** クリアタイプ。"NO PLAY"/"FAILED"/"ASSIST CLEAR"/"EASY CLEAR"/"CLEAR"/"HARD CLEAR"/"EX HARD CLEAR"/"FULLCOMBO CLEAR" のいずれか。 */
    private String clearType;
    /** DJ LEVEL。"AAA"/"AA"/"A"/"B"/"C"/"D"/"E"/"F"。 */
    private String djLevel;
    /** PGREAT 数。 */
    private Integer pgreat;
    /** GREAT 数。 */
    private Integer great;
    /** ミスカウント（BP）。 */
    private Integer missCount;
    /** プレー回数。 */
    private Integer playCount;

    /** スナップショット ID。過去時点のスコアを参照するためのラベル（任意）。 */
    private String snapshotId;

    /**
     * このスコアの取得元。
     *  - {@code "arcade"}: e-amusement の CSV / ブックマークレットから取り込んだスコア
     *  - {@code "infinitas"}: ブラウザの画面共有 OCR で取り込んだ INFINITAS のリザルト
     * UI の表示フィルタや集計対象切り替えに利用する。
     */
    @Column(length = 20, nullable = false)
    @ColumnDefault("'arcade'")
    private String source = "arcade";

    /** このスコアがアップロード（更新）された日時。 */
    @Column(nullable = false)
    private LocalDateTime uploadedAt = LocalDateTime.now();

    /**
     * 譜面の最終プレー日時（JST の壁時計時刻）。eagate の公式 CSV の「最終プレー日時」列に由来する。
     *
     * <p>{@link #uploadedAt} とは別物であることに注意。uploadedAt は「サーバー時計で記録が
     * 書き換わった時刻」なので、プレーしても自己ベストが伸びなければ進まない。こちらは
     * 記録の更新有無に関係なく「実際に遊んだ時刻」として進む。
     *
     * <p>粒度の注意: 公式 CSV は 1 曲 1 行で、この列も曲単位（同じ曲の全譜面が同じ値）。
     * したがって厳密には「この譜面を最後に遊んだ時刻」ではなく「この曲を最後に遊んだ時刻」。
     *
     * <p>列を持たない取り込み経路（ブックマークレット CSV は空欄・INFINITAS は非対応）では
     * null のままになる。利用側は null を「不明」として扱うこと。
     * リーグモードの活動判定（課題曲をリーグ期間中に遊んだか）がこの値を根拠にする。
     */
    @Column(name = "last_played_at")
    private LocalDateTime lastPlayedAt;
}
