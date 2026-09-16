package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 BEMANIWiki 新曲リストの取り込み（{@code WikiSongSyncService}）の実行 1 回分の記録。
 *
 * 現実世界の概念: 「いつ wiki を見に行って、ページが前回から変わっていたか、何曲追加・更新したか」の履歴。
 * 管理画面の「bemaniwiki 新曲同期」パネルで直近の実行を表示し、障害時の切り分けにも使う。
 * マッピング先テーブル: {@code wiki_song_sync_runs}。
 *
 * 運用: 定期実行（1 日数回）と管理者の手動実行のたびに 1 行追加。dry-run（差分確認のみ）も記録する。
 */
@Entity
@Table(name = "wiki_song_sync_runs")
@Data
@NoArgsConstructor
public class WikiSongSyncRun {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 開始日時（サーバー TZ の壁時計。API で返すときは JstTime で JST に直す）。 */
    @Column(nullable = false)
    private LocalDateTime startedAt;

    /** 終了日時。 */
    private LocalDateTime finishedAt;

    /** きっかけ: scheduled（定期）/ manual（管理画面）。"trigger" は PostgreSQL の予約語なので列名を変えてある。 */
    @Column(name = "trigger_kind", nullable = false, length = 20)
    private String triggerKind;

    /** true なら差分の確認だけで DB は変更していない。 */
    @Column(nullable = false)
    private boolean dryRun = false;

    /** 結果: SUCCESS（変更あり）/ NO_CHANGE（変更なし）/ FAILED（取得・解析・保存の失敗）。 */
    @Column(nullable = false, length = 20)
    private String status;

    /** 解析結果の SHA-256。前回と比較してページが変わったかを判定する。失敗時は null。 */
    @Column(length = 64)
    private String pageHash;

    /** 前回の記録と比べてページ内容が変わっていたか。初回は true。 */
    private Boolean pageChanged;

    /** ページに載っていた曲数（未配信を含む）。 */
    private Integer songsOnPage;

    /** 新規に追加した譜面数。 */
    @Column(nullable = false)
    private Integer addedCount = 0;

    /** 既存譜面の値（レベル・ノーツ数など）を更新した数。 */
    @Column(nullable = false)
    private Integer updatedCount = 0;

    /** 未解禁・未記載などで保留した譜面数。 */
    @Column(nullable = false)
    private Integer heldCount = 0;

    /** 明細（追加・更新・保留・警告の一覧）。表示用 JSON。 */
    @Column(columnDefinition = "TEXT")
    private String summaryJson;

    /** 失敗時の例外メッセージ。 */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;
}
