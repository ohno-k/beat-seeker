package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 【エンティティの役割】 beat-seeker に登録したユーザー（= IIDX プレイヤー）を表す。
 *
 * 現実世界の概念: IIDX 筐体の「IIDX ID（4桁-4桁）」を持つ 1 人のプレイヤー。
 * マッピング先テーブル: {@code users}。
 *
 * 主要な関連:
 *  - {@link #scores} … このユーザーがアップロードした全スコア（{@link Score}）への OneToMany。
 *    親である User が削除されれば紐づくスコアも orphanRemoval で一緒に消える。
 *
 * 識別子:
 *  - 主キーは DB 採番の {@link #id}（Long, IDENTITY）。
 *  - ログイン識別子はユニーク制約付きの {@link #iidxId}（"1234-5678" 形式）。
 */
@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
public class User {

    /** 主キー。DB 側で自動採番される代理キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** IIDX ID（例: "1234-5678"）。ユーザーの自然キーであり、ログイン時の識別子。 */
    @Column(unique = true, length = 9)
    private String iidxId; // 例: "1234-5678"

    /** ログインパスワードのハッシュ値（BCrypt 等）。平文は保存しない。 */
    private String passwordHash;

    /** プロフィール上で表示する名前（DJ ネーム）。他ユーザーへの公開名として使う。 */
    private String displayName;

    /** 段位（例: "皆伝"、"十段"）。IIDX の段位認定結果を保存する。 */
    @Column(length = 20)
    private String danRank; // 例: "皆伝"

    /** ARENA クラス（例: "A1"、"B2"）。IIDX の ARENA モードの階級。 */
    @Column(length = 10)
    private String arenaRank; // 例: "A1"

    /** プレイサイド。"1P" または "2P"。譜面オプションの正規化などに使う。 */
    @Column(length = 2)
    private String playSide = "1P"; // "1P" または "2P"

    /**
     * プロフィール公開範囲。
     *  - 0: Public（誰でも閲覧可）
     *  - 1: Friends Only（フレンドのみ）
     *  - 2: Private（自分のみ）
     */
    @ColumnDefault("1")
    private Integer privacyLevel = 1; // 0: Public, 1: Friends Only, 2: Private

    /** UI 表示言語コード。"ja"（日本語）/ "en"（英語）/ "ko"（韓国語）。 */
    @Column(length = 5)
    @ColumnDefault("'ja'")
    private String language = "ja"; // "ja", "en", "ko"

    /** Beat-Tier（レートランク）をプロフィールに表示するかどうか。 */
    @ColumnDefault("true")
    private Boolean showRateTier = true;

    /** サポーター（課金支援者）かどうか。true の場合は特別 UI が有効になる。 */
    @ColumnDefault("false")
    private Boolean isSupporter = false;

    // 旧「サポーター枠を表示する」フラグ（show_supporter_border 列）は 2026-09-15 に廃止。
    // 外枠は前作ティア（version_pt_snapshots）で全員に付き、サポーター特典は光沢だけになったため。
    // DB の列は残っているが参照しない。

    /** サポーター認証用トークン。外部決済システムから渡される一意な識別子。 */
    @Column(unique = true, length = 12)
    private String supporterToken;

    /** 最後にスコアデータをアップロードした日時。差分計算や通知タイミング判定に利用。 */
    private LocalDateTime lastUploadedAt;

    /** 累計 Beat-Pt（レート値）。全スコアから算出されるユーザーの総合力指標。 */
    @ColumnDefault("0")
    private Double totalBeatPt = 0.0;

    /**
     * 公式難易度 Lv11/Lv12 ANOTHER/LEGGENDARIA 全曲における平均順位（AVERAGE ランキング用）。
     * 未プレイ譜面は「その譜面のプレイ人数 + 1」を順位として算入し、それを全曲平均した値。
     * 対象セット内に 1 曲もプレイ実績が無いユーザーは null（ランキング除外）。
     * 日次バッチ {@code SongRankBatchService.recalculateAll()} で全員一括更新される。
     */
    private Double totalAverageRank;

    /** AVERAGE ランキングの「対象セット内で実際にプレイ済の譜面数」。表示用。 */
    private Integer totalAverageRankPlayed;

    /** Web Push 購読情報（ブラウザから発行される JSON）。通知配信時に利用。 */
    @Column(columnDefinition = "TEXT")
    private String pushSubscription; // Web Push の購読情報 JSON

    /**
     * リーグ関連の通知（開始・結果・同グループの更新・終盤リマインド）を受け取るか。
     *
     * false にするとブラウザ通知もアプリ内通知（ベル）も作られない。リーグは週次で複数の
     * 通知が出る唯一の機能なので、「うるさいから通知そのものを切る」（＝ライバル申請等も
     * 届かなくなる）に走らせないための逃げ道として用意している。
     *
     * <p>NOT NULL を付けていないのは、既存行のあるテーブルへ後付けする列だから
     * （NOT NULL の ALTER は失敗し得る）。読み取り側は {@link #isLeagueNotificationsEnabled()}
     * を使い、null は「有効」として扱う（既存ユーザーは今までどおり受け取る）。
     */
    @Column(name = "league_notifications_enabled")
    @org.hibernate.annotations.ColumnDefault("true")
    private Boolean leagueNotificationsEnabled = true;

    /** リーグ通知を受け取るか（null は有効扱い）。 */
    public boolean isLeagueNotificationsEnabled() {
        return !Boolean.FALSE.equals(leagueNotificationsEnabled);
    }

    /** メールアドレス。パスワードリセット通知等に使う。ユニーク制約あり。 */
    @Column(unique = true)
    private String email;

    /** パスワードリセット用のワンタイムトークン。リセットメール送信時に発行。 */
    private String passwordResetToken;

    /** 上記リセットトークンの有効期限。期限切れ後は無効扱い。 */
    private LocalDateTime passwordResetExpiredAt;

    /** アカウント作成日時。登録後は不変（updatable=false）。 */
    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * このユーザーがアップロードした全スコア。
     * {@link Score#user} で双方向マッピング。User 削除時に一緒に削除される。
     */
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Score> scores = new ArrayList<>();
}
