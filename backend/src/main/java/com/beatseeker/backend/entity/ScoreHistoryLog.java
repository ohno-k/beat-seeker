package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 ユーザーのスコアアップロード単位のサマリ履歴。
 *
 * 現実世界の概念: 「このユーザーが X 時に一斉アップロードしたとき、累計スコアや
 * 各クリアタイプ数がどう推移したか」のスナップショット 1 件。
 * マッピング先テーブル: {@code score_history_logs}。
 *
 * 個別曲のスコア推移ではなく「その時点のトータル値」を記録するため、
 * グラフ表示や日次差分の算出に使われる。
 *
 * 主要な関連:
 *  - {@link #user} … 対象ユーザーへの ManyToOne。
 */
@Entity
@Table(name = "score_history_logs")
@Data
public class ScoreHistoryLog {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 集計対象のユーザー。{@link User} への ManyToOne。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** このサマリを作成した（= アップロードした）日時。 */
    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    /**
     * 記録の種別タグ。通常の一括アップロードは null（無印）。
     * 世代切替時に入れる 0PT の履歴行は {@link com.beatseeker.backend.service.VersionTransitionService#RESET_TAG}。
     */
    @Column(length = 20)
    private String tag;

    /**
     * この記録が属する作品バージョン（33 = Sparkle Shower, 34 = ZINRAI ...）。
     * 保存時に {@link com.beatseeker.backend.service.IidxVersions#current()} を入れる。
     * 成長記録ページの作品切り替えに使う。世代切り替え前の行は null で入っており、
     * 起動時のバックフィル（DataInitializer）と取得クエリの COALESCE で 33 として扱う。
     */
    private Integer version;

    /** 累計 EX スコア（全曲合計）。 */
    private Long totalScore = 0L;

    /** FULLCOMBO CLEAR した譜面数。 */
    private Integer fcCount = 0;
    /** EX HARD CLEAR 以上を達成した譜面数。 */
    private Integer exhCount = 0;
    /** HARD CLEAR 以上を達成した譜面数。 */
    private Integer hCount = 0;
    /** CLEAR 以上を達成した譜面数（EASY や ASSIST を含まない通常クリア）。 */
    private Integer clearCount = 0;
    /** EASY CLEAR 以上を達成した譜面数。 */
    private Integer easyCount = 0;

    /** DJ LEVEL AAA 以上の譜面数。 */
    private Integer aaaCount = 0;
    /** DJ LEVEL AA 以上の譜面数。 */
    private Integer aaCount = 0;
    /** DJ LEVEL A 以上の譜面数。 */
    private Integer aCount = 0;

    /** 累計 Beat-Pt（総合レート）。 */
    private Double totalBeatPt = 0.0;
    /** 前回アップロード時との差分 Beat-Pt（増分）。 */
    private Double beatPtIncrease = 0.0;
    /** 今回のアップロードで更新された譜面数。 */
    private Integer updatedCount = 0;
    /** 累計精度ポイント（DJ LEVEL 系の加点）。 */
    private Double totalPrecisionPt = 0.0;
    /** 累計レートポイント。 */
    private Double totalRatePt = 0.0;

    /** 更新された個別譜面の詳細 JSON（差分表示用）。 */
    @Column(columnDefinition = "TEXT")
    private String diffJson;

    /**
     * アップロード時点のリーグモードの進捗（プレイ成果レポートのリーグ欄）の JSON。2026-09-23 追加。
     *
     * 今回の更新に開催中リーグの課題曲が含まれたときだけ、フロントの {@code /save-history-log} が入れる
     * （形はフロントの utils/leagueReport.ts の LeagueReportSnapshot）。成長記録から開いた過去のレポートで
     * 「その週のリーグ」を固定で見せるために使う。null = 課題曲の更新なし or 導入前の行。
     */
    @Column(columnDefinition = "TEXT")
    private String leagueJson;

    /**
     * フロントの {@code /save-history-log} がこの行を上書き済みかどうか。
     *
     * この行はまず {@code /api/scores/upload} がサーバー側で作る（false）。続けてフロントが
     * BEAT-PT 差分・ティア名・リッチな diffJson を載せて上書きすると true になる。
     * upload のレスポンスがブラウザに届かなかった場合は false のまま残り、
     * 「スコアだけ保存されて成長記録が無い」状態にはならない。
     *
     * null は本フラグ導入前に保存された行（= フロント由来）。
     */
    private Boolean clientConfirmed;
}
