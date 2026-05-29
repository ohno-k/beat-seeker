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

    /**
     * アーケード（CSV/ブックマークレット）由来のスコアを UI 上に表示するかどうか。
     * INFINITAS 取得スコアと並走させた場合に「アーケードのスコアだけ見たい / 逆」をユーザーが切り替えるためのフラグ。
     */
    @ColumnDefault("true")
    private Boolean showArcadeScores = true;

    /**
     * INFINITAS 画面共有 OCR 由来のスコアを UI 上に表示するかどうか。
     * INFINITAS モードを使うユーザーがアーケードのスコアと別フィルタで切り替えたい時に使う。
     */
    @ColumnDefault("true")
    private Boolean showInfinitasScores = true;

    /** KENBAN-TIER / SARA-TIER を UI に表示するかどうか。サポーター限定のオプトイン機能。 */
    @ColumnDefault("false")
    private Boolean showKenbanSaraTier = false;

    /** サポーター（課金支援者）かどうか。true の場合は特別 UI が有効になる。 */
    @ColumnDefault("false")
    private Boolean isSupporter = false;

    /** サポーター特典のアイコン枠を表示するかどうか（isSupporter が true のときのみ意味を持つ）。 */
    @ColumnDefault("true")
    private Boolean showSupporterBorder = true;

    /** サポーター認証用トークン。外部決済システムから渡される一意な識別子。 */
    @Column(unique = true, length = 12)
    private String supporterToken;

    /** 最後にスコアデータをアップロードした日時。差分計算や通知タイミング判定に利用。 */
    private LocalDateTime lastUploadedAt;

    /** 累計 Beat-Pt（レート値）。全スコアから算出されるユーザーの総合力指標。 */
    @ColumnDefault("0")
    private Double totalBeatPt = 0.0;

    /** 累計 KENBAN-PT（鍵盤側ティア指標）。BEAT-PT 算出時に同じスコアから派生計算する。 */
    @ColumnDefault("0")
    private Double totalKenbanPt = 0.0;

    /** 累計 SARA-PT（皿側ティア指標）。BEAT-PT 算出時に同じスコアから派生計算する。 */
    @ColumnDefault("0")
    private Double totalSaraPt = 0.0;

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
