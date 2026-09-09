package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 非公式難易度表の改訂 1 回分（更新履歴ページの「第N版」）。
 *
 * 現実世界の概念: 管理者が「難易度表を適用」した瞬間に、公開中の表と draft の差分を
 * 「新規追加 / 既存変更 / 表から除外」として記録したもの。更新履歴ページの難易度改訂タブは
 * このテーブルを版数順に描画する。マッピング先テーブル: {@code difficulty_revisions}。
 *
 * 運用:
 *  - 第1〜4版は手書き JSON（{@code resources/data/difficulty_revisions.json}）から起動時にシードされる
 *    （テーブルが空のときだけ）。
 *  - それ以降は {@code GameDataService#applyDraftDifficultyTable()} のたびに自動追記される。
 *    公開中の表との差分が無い適用は記録しない。
 *  - 明細は表示用にそのまま使える JSON 文字列で持つ（曲数は最大で全曲分になり得るため列には正規化しない）。
 *
 * 一意性制約: {@code edition}（版数）。
 */
@Entity
@Table(name = "difficulty_revisions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_difficulty_revisions_edition", columnNames = { "edition" })
})
@Data
@NoArgsConstructor
public class DifficultyRevision {

    /** 主キー。DB 採番の代理キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 版数。1 から始まる連番（表示は「第N版」）。 */
    @Column(nullable = false)
    private Integer edition;

    /** 併記するアプリのバージョン表記（例: "Ver 1.8.0"）。適用ボタンによる自動記録では null。 */
    @Column(length = 40)
    private String appVersion;

    /** 適用日時（JST）。更新履歴の表示は年月まで。 */
    @Column(nullable = false)
    private LocalDateTime appliedAt;

    /** 新規追加（数値帯に初めて入った曲）の件数。 */
    @Column(nullable = false)
    private Integer addedCount = 0;

    /** 既存変更（数値帯の間で動いた曲）の件数。 */
    @Column(nullable = false)
    private Integer changedCount = 0;

    /** 表から除外（数値帯から Uncategorized へ戻った、または表から消えた曲）の件数。 */
    @Column(nullable = false)
    private Integer removedCount = 0;

    /** 新規追加の明細。{@code [{"title":"…","rank":"12.4"}, …]}。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String addedJson = "[]";

    /** 既存変更の明細。{@code [{"title":"…","from":"12.6","to":"12.4"}, …]}。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String changedJson = "[]";

    /** 表から除外の明細。{@code [{"title":"…","rank":"11.0"}, …]}（rank は除外前の帯）。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String removedJson = "[]";
}
