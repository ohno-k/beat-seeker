package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 リーグモードの 1 週分（1 ラダー分）の開催レコード。
 *
 * 現実世界の概念: 「スコアリーグ第 N 週」のような週単位の開催回。課題曲 3 曲
 * ({@link LeagueSong}) とメンバー配置 ({@link LeagueMember}) の親になる。
 * マッピング先テーブル: {@code league_weeks}。ラダーごとに 1 行なので通常は週あたり 2 行できる。
 *
 * status の取りうる値:
 *  - {@code draft}: 抽選済みだが未開始。管理者が課題曲を差し替え・再抽選できる期間（金〜日想定）。
 *  - {@code active}: 開催中。メンバー配置とベースラインスナップショット済み。課題曲は変更不可。
 *  - {@code closed}: 締め済み。順位・昇降格が {@link LeagueMember} に凍結されている。
 *
 * 時刻の扱い（重要）:
 *  - {@link #startsAt} / {@link #endsAt} は JST の壁時計時刻（月曜 0:00 〜 翌月曜 0:00）。
 *    Competition の deadlineAt と同じ流儀で、表示・締切判定に使う。
 *  - {@link #snapshotAt} だけはサーバー時計 ({@code LocalDateTime.now()}) で記録する。
 *    Score.uploadedAt もサーバー時計なので、「週内アップロードか」の比較を同一の時計同士で
 *    行うための値。JST の startsAt と混ぜて比較してはならない。
 */
@Entity
@Table(name = "league_weeks", uniqueConstraints = {
        // cron の二重発火や手動トリガーとの併用で同じ週が二重作成されるのを DB レベルで防ぐ。
        @UniqueConstraint(name = "uk_league_weeks_ladder_starts", columnNames = { "ladder_type", "starts_at" })
})
@Data
@NoArgsConstructor
public class LeagueWeek {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ラダー種別。{@code "score"} または {@code "bp"}。 */
    @Column(name = "ladder_type", length = 8, nullable = false)
    private String ladderType;

    /** 週の開始日時（JST 壁時計、月曜 0:00）。 */
    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    /** 週の終了日時（JST 壁時計、翌月曜 0:00）。 */
    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    /** 状態。{@code draft / active / closed}。遷移はサービス層でガードする。 */
    @Column(length = 16, nullable = false)
    private String status = "draft";

    /**
     * ベースラインスナップショットを取得した瞬間のサーバー時計時刻。
     * active 遷移時に設定される。ベースライン行が無い譜面の「週内プレー判定」は
     * {@code Score.uploadedAt >= snapshotAt} で行う（両者ともサーバー時計なので TZ 問題が無い）。
     */
    @Column(name = "snapshot_at")
    private LocalDateTime snapshotAt;

    /** レコード作成日時。 */
    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
