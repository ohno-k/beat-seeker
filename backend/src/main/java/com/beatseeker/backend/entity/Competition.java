package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

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
     * 大会フォーマット。
     * <ul>
     *   <li>{@code team5} (デフォルト): 既存の 5 チーム×4 名・10 matchup 総当たり団体戦。</li>
     *   <li>{@code individual4}: 4 人対戦×1 曲制の個人戦。12 or 16 名で予選 → 上位 4 名ずつの決勝。</li>
     * </ul>
     * 文字列で保持し、コントローラ層で分岐する。
     */
    @Column(length = 16, nullable = false)
    @ColumnDefault("'team5'")
    private String format = "team5";

    /**
     * 状態。{@code draft / open / locked / finished}。
     * 文字列で保持し、サービス層で許容遷移をチェックする。
     */
    @Column(length = 16, nullable = false)
    private String status = "draft";

    /**
     * 起用 (オーダー) クローズ日時。null の場合は締切未設定。
     * TL の起用編集のみを締め切り、プレイヤーの自選曲提出は対象外 ({@link #isLineupClosed()} 参照)。
     */
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

    /**
     * OBS ブラウザソース公開用トークン。NULL の間は OBS 公開エンドポイントから到達不可。
     * 管理画面の「OBS URL 発行」で UUID を生成する。誤公開時はトークン再採番で旧 URL を無効化できる。
     */
    @Column(name = "obs_token", length = 64, unique = true)
    private String obsToken;

    /**
     * 観戦客向け対戦表公開用トークン (team5 用)。NULL の間は観戦公開エンドポイントから到達不可。
     * 管理画面の「観戦用 URL 発行」で UUID を生成する。誤公開時はトークン再採番で旧 URL を無効化できる。
     * 公開される内容は「起用公開済みのラインアップ・指定ジャンル・記録済みの結果」に限定される
     * (未公開の起用や自選曲は伏せられ、staged reveal を壊さない)。
     */
    @Column(name = "spectator_token", length = 64, unique = true)
    private String spectatorToken;

    /**
     * 【派生判定】 起用クローズ済みか。{@link #deadlineAt} を JST の壁時計時刻として解釈し、
     * 現在 (JST) がそれ以降なら true。
     *
     * <p>手動ロックの代替。クローズ後は <b>TL の起用編集 (選手の割り当て) のみ</b>が締め切られる。
     * プレイヤーの自選曲提出はクローズ対象外 (締切後も終了するまで提出/変更できる)。
     * {@code deadlineAt} が null の間は常に false (締切未設定 = 締め切らない)。
     * バックグラウンドジョブを使わず読み取り時に都度判定するため、締切変更/解除で即座に反映される。
     *
     * <p>{@code @Transient} で永続化対象外。エンティティはフィールドアクセスなので Hibernate は無視する。
     */
    @Transient
    public boolean isLineupClosed() {
        return deadlineAt != null
                && !LocalDateTime.now(java.time.ZoneId.of("Asia/Tokyo")).isBefore(deadlineAt);
    }
}
