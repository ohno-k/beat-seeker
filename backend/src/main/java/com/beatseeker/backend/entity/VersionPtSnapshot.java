package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 ある作品バージョンの終了時点における、ユーザー 1 人ぶんの最終 PT 記録。
 *
 * 現実世界の概念: IIDX は新作稼働のたびにスコアがリセットされる。beat-seeker でも新作初日に
 * BEAT-PT / RATE-PT を 0 から積み直すため、そのままでは「前作で自分が何位だったか」が失われる。
 * このテーブルは初期化の直前に全員の最終値を焼き付けておき、過去作ランキングとして後から
 * 参照できるようにするためのもの。マッピング先テーブル: {@code version_pt_snapshots}。
 *
 * 設計上の判断:
 *  - <b>1 作品だけの特別対応ではなく、作品ごとに積み上がるアーカイブ</b>として作る。
 *    {@code version} を持たせているのはこのため（33 の次は 34、35 …と同じ形で溜まる）。
 *  - <b>{@link User} への ManyToOne ではなく素の {@code userId} を持つ。</b>
 *    アーカイブは「その時点の記録」であり、後からユーザーが表示名を変えても
 *    当時の順位表の見え方が変わってはならない。そのため {@code iidxId} /
 *    {@code displayName} も撮影時点の値を非正規化して保持する。
 *  - <b>ティア名は保存しない。</b> ティアは PT から導出でき、導出ロジックは
 *    フロントエンド（{@code beatTier.ts}）に一本化されている。ここで文字列を二重に持つと
 *    閾値を調整したときに実体と表示がずれる。保存するのは元データ（PT）だけにする。
 *
 * 不変条件: 本テーブルは現行作のランキング・BEAT-PT・リーグ・大会のいかなる集計にも参加しない。
 *   参照は「過去作ランキングの表示」用途に限る。
 *
 * 一意性制約: (version, user_id)。同じ作品について 1 ユーザー 1 行。
 */
@Entity
@Table(name = "version_pt_snapshots", uniqueConstraints = {
        @UniqueConstraint(name = "uk_version_pt_snapshots_ver_user", columnNames = { "version", "user_id" })
}, indexes = {
        @Index(name = "idx_version_pt_snapshots_ver_beat", columnList = "version,total_beat_pt"),
        @Index(name = "idx_version_pt_snapshots_ver_rate", columnList = "version,total_rate_pt")
})
@Data
@NoArgsConstructor
public class VersionPtSnapshot {

    /** 主キー。DB 採番の代理キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 作品バージョン番号（33 = Sparkle Shower, 34 = ZINRAI …）。
     * 採番は {@link com.beatseeker.backend.service.IidxVersions} と同一。
     */
    @Column(nullable = false)
    private Integer version;

    /** 対象ユーザーの ID。外部キー制約は張らない（アーカイブを利用者側の変更から独立させるため）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 撮影時点の IIDX ID。表示名変更やアカウント削除の影響を受けないよう非正規化して持つ。 */
    @Column(length = 9)
    private String iidxId;

    /** 撮影時点の表示名。同上。 */
    private String displayName;

    /** 撮影時点の合計 BEAT-PT。 */
    private Double totalBeatPt;

    /** 撮影時点の合計 RATE-PT。 */
    private Double totalRatePt;

    /** 撮影時点の合計 鍵盤 PT。 */
    private Double totalKenbanPt;

    /** 撮影時点の合計 皿 PT。 */
    private Double totalSaraPt;

    /** 撮影時点の BEAT-PT 順位（同値は同順位）。 */
    private Integer beatRank;

    /**
     * 撮影時点の RATE-PT 順位（同値は同順位）。
     * 現行のランキング SQL と同じく {@code total_rate_pt > 0} のユーザーのみ採番するため、
     * RATE-PT が 0 のユーザーは null になる。
     */
    private Integer rateRank;

    /**
     * 撮影時点の公開範囲設定（0: Public / 1: Friends Only / 2: Private）。
     * 記録として残すためのもので、<b>表示時の判定には使わない</b>。
     * 過去作ランキングの公開可否は、現在のユーザー設定に従うのが利用者の期待に沿う。
     */
    private Integer privacyLevel;

    /** 集計元になった {@link ScoreHistoryLog} のアップロード日時（＝その人の最終更新時刻）。 */
    private LocalDateTime lastUploadedAt;

    /** このスナップショットを撮影した日時。 */
    @Column(nullable = false)
    private LocalDateTime capturedAt = LocalDateTime.now();
}
