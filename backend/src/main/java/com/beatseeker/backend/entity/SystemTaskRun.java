package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 「一度きり実行したい処理」の実行記録。冪等性の担保に使う。
 *
 * 現実世界の概念: 新作稼働時の世代切り替えのように、<b>二度実行してはいけない処理</b>がある。
 * 定期ジョブは再起動・デプロイ・インスタンスの再開などで何度でも走り得るため、
 * 「もう実行したか」を DB に残して二重実行を防ぐ。マッピング先テーブル: {@code system_task_runs}。
 *
 * なぜ定期 cron 一発ではなくこの方式なのか:
 *   Render のインスタンスはデプロイや再起動で停止し得る。狙った時刻に必ず起きている保証がないため、
 *   「予定時刻を過ぎていて、かつ未実行なら走らせる」というポーリング方式にしている。
 *   その判定材料になるのがこのテーブル。取りこぼしても次のポーリングで追いつける。
 *
 * 一意性制約: {@code taskKey}。1 タスク 1 行で、状態を上書きしていく。
 */
@Entity
@Table(name = "system_task_runs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_system_task_runs_key", columnNames = { "task_key" })
})
@Data
@NoArgsConstructor
public class SystemTaskRun {

    /** 実行状態。 */
    public enum Status {
        /** 実行中。プロセスが落ちた場合はこの状態のまま残るので、手動確認が必要。 */
        RUNNING,
        /** 正常終了。以降このタスクは実行されない。 */
        SUCCESS,
        /** 失敗。原因を直したうえで手動でこの行を消せば再実行される。 */
        FAILED,
        /** 事前条件を満たさず意図的に見送った。次回のポーリングで再判定される。 */
        SKIPPED
    }

    /** 主キー。DB 採番の代理キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** タスクの識別子。例: {@code "version-transition:33->34:snapshot"}。 */
    @Column(name = "task_key", nullable = false, length = 120)
    private String taskKey;

    /** 実行状態。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.RUNNING;

    /** 実行開始日時。 */
    @Column(nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    /** 実行終了日時。RUNNING のあいだは null。 */
    private LocalDateTime finishedAt;

    /** 実行結果の要約（件数や中断理由）。後から状況を追えるように残す。 */
    @Column(columnDefinition = "TEXT")
    private String detail;
}
