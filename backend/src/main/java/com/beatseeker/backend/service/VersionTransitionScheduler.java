package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.SystemTaskRun;
import com.beatseeker.backend.repository.SystemTaskRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/**
 * 【クラスの役割】 新作稼働日を待って、世代切り替えの安全な手順を自動実行するタイマー。
 *
 * <b>既定では存在しない。</b> {@code app.version-transition.launch-at} が設定されていない限り
 * {@link ConditionalOnProperty} によりこの Bean 自体が生成されないため、通常の運用では
 * タイマーは 1 つも増えず、利用者から見える挙動も一切変わらない。
 *
 * ■ なぜ cron ではなくポーリングなのか
 * 新作稼働は「一度きり」の処理で、狙った時刻に取りこぼすと困る。Render のインスタンスは
 * デプロイ・再起動・スリープで停止し得るため、その瞬間に cron が発火する保証がない。
 * そこで「予定時刻を過ぎていて、かつ未実行なら走らせる」というポーリング方式にしている。
 * 発火の瞬間に落ちていても、次に起きたときに追いつける（キャッチアップ）。
 * 二重実行は {@link SystemTaskRun} の記録で防ぐ。
 *
 * ■ 稼働日を環境変数にしてある理由
 * IIDX の稼働日は KONAMI の告知が出るまで確定しない。cron 式に直書きすると日付が動くたびに
 * 再デプロイが必要になるため、{@link LeagueScheduler} の {@code app.league.season-start} と
 * 同じく設定値で外に出している。日付がずれても環境変数の変更と再起動だけで追随できる。
 *
 * ■ 設定（環境変数名は Spring の relaxed binding に従う）
 * <pre>
 *   app.version-transition.launch-at    APP_VERSION_TRANSITION_LAUNCH_AT
 *       起動日時（JST, 例 "2026-09-16T05:00"）。未設定＝この機能ごと無効。
 *   app.version-transition.dry-run      APP_VERSION_TRANSITION_DRY_RUN
 *       既定 true。件数をログに出すだけで DB を変更しない。実行済み記録も残さないので
 *       false に切り替えれば改めて本番実行される。
 *   app.version-transition.from-version APP_VERSION_TRANSITION_FROM_VERSION   既定 33
 *   app.version-transition.to-version   APP_VERSION_TRANSITION_TO_VERSION     既定 34
 *   app.version-transition.min-users    APP_VERSION_TRANSITION_MIN_USERS      既定 100
 *       スナップショット対象がこの人数に満たなければ中断する（取り違え・空撃ち防止）。
 *   app.version-transition.apply-difficulty  APP_VERSION_TRANSITION_APPLY_DIFFICULTY
 *       既定 false。難易度表 draft の自動適用は明示的に有効化したときだけ行う。
 *   app.version-transition.reset-scores APP_VERSION_TRANSITION_RESET_SCORES
 *       既定 false。スコアの初期化（唯一の破壊的手順）は明示的に有効化したときだけ行う。
 * </pre>
 *
 * ■ スコア初期化の扱い
 * 手順 4（{@code scores} の削除と派生データのリセット）は取り返しがつかないため、
 * <b>稼働日を設定しただけでは走らない</b>。{@code reset-scores} を true にして初めて対象になり、
 * さらに前作の退避（スナップショットと過去作スコアの複製）が DB に無ければ
 * {@link VersionTransitionService#resetCurrentScores} 自身が例外で止める。二重の歯止めにしてある。
 *
 * ■ 自動化しない手順
 * フロントエンドの {@code CURRENT_VERSION} はビルド時定数なので、タイマーからは変えられない
 * （再デプロイが必要）。beta 表記の削除も同様にデプロイ側の作業になる。
 */
@Component
@ConditionalOnProperty(name = "app.version-transition.launch-at")
public class VersionTransitionScheduler {

    private static final Logger log = LoggerFactory.getLogger(VersionTransitionScheduler.class);
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final VersionTransitionService transitionService;
    private final SystemTaskRunRepository taskRunRepository;

    /** 切り替えを実行する日時（JST）。パースに失敗した場合は null にして機能を止める。 */
    private final LocalDateTime launchAt;
    private final boolean dryRun;
    private final int fromVersion;
    private final int toVersion;
    private final int minUsers;
    private final boolean applyDifficulty;
    private final boolean resetScores;

    /** 「無効です」というログを起動後 1 回だけ出すためのフラグ。毎分ログを汚さないため。 */
    private boolean disabledLogged = false;
    /** 全手順を終えた後の案内ログを 1 回だけ出すためのフラグ。 */
    private boolean completionLogged = false;

    public VersionTransitionScheduler(
            VersionTransitionService transitionService,
            SystemTaskRunRepository taskRunRepository,
            @Value("${app.version-transition.launch-at:}") String launchAtRaw,
            @Value("${app.version-transition.dry-run:true}") boolean dryRun,
            @Value("${app.version-transition.from-version:33}") int fromVersion,
            @Value("${app.version-transition.to-version:34}") int toVersion,
            @Value("${app.version-transition.min-users:100}") int minUsers,
            @Value("${app.version-transition.apply-difficulty:false}") boolean applyDifficulty,
            @Value("${app.version-transition.reset-scores:false}") boolean resetScores) {
        this.transitionService = transitionService;
        this.taskRunRepository = taskRunRepository;
        this.dryRun = dryRun;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.minUsers = minUsers;
        this.applyDifficulty = applyDifficulty;
        this.resetScores = resetScores;

        LocalDateTime parsed = null;
        if (launchAtRaw != null && !launchAtRaw.isBlank()) {
            try {
                parsed = LocalDateTime.parse(launchAtRaw.trim());
            } catch (DateTimeParseException e) {
                log.error("[世代切替] app.version-transition.launch-at を解釈できないため無効化する: value={}（期待する書式: 2026-09-16T05:00）",
                        launchAtRaw, e);
            }
        }
        this.launchAt = parsed;
        if (parsed != null) {
            log.info("[世代切替] 有効: {} JST に {}→{} の切り替えを実行予定（dryRun={}, 難易度表の自動適用={}, スコア初期化={}）",
                    parsed, fromVersion, toVersion, dryRun, applyDifficulty, resetScores);
        }
    }

    /**
     * 【メソッドの役割】 1 分おきに「予定時刻を過ぎたか」を見て、過ぎていれば未実行の手順を進める。
     *
     * 起動から 30 秒待つのは、アプリの初期化（DDL 更新やキャッシュ構築）と重ならないようにするため。
     * 判定・実行のいずれも未実行時は数ミリ秒で終わるので、常時動いていても負荷にはならない。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void tick() {
        if (launchAt == null) {
            if (!disabledLogged) {
                log.info("[世代切替] launch-at が未設定のため何もしない");
                disabledLogged = true;
            }
            return;
        }
        if (LocalDateTime.now(JST).isBefore(launchAt)) {
            return;
        }

        // 手順 1: 前作の最終 PT を保存する。これが失敗している間は先へ進めない。
        boolean snapshotDone = runOnce("snapshot", () -> {
            int expected = transitionService.captureSnapshot(fromVersion, true);
            if (expected < minUsers) {
                throw new PreconditionFailedException(
                        "スナップショット対象が " + expected + " 人で下限 " + minUsers + " 人に満たない");
            }
            int n = transitionService.captureSnapshot(fromVersion, dryRun);
            return "対象 " + expected + " 人 / 書き込み " + n + " 件";
        });
        if (!snapshotDone) return;

        // 手順 2: 現行スコアを過去作へ複製する（元データは消さない）。
        boolean copyDone = runOnce("copy-scores", () ->
                "複製 " + transitionService.copyScoresToPastScores(fromVersion, dryRun) + " 行");
        if (!copyDone) return;

        // 手順 3: 難易度表 draft の適用。既定では無効で、明示的に有効化したときだけ行う。
        if (applyDifficulty) {
            boolean applied = runOnce("apply-difficulty", () ->
                    transitionService.applyDifficultyDraft(dryRun) ? "draft を適用" : "適用対象の draft 無し");
            if (!applied) return;
        }

        // 手順 4: スコアの初期化。唯一の破壊的手順なので、明示的に有効化したときだけ実行する。
        // 前段（手順 1・2）が済んでいなければ resetCurrentScores 自身が例外を投げて止まる。
        if (resetScores) {
            boolean reset = runOnce("reset-scores", () -> {
                Map<String, Object> counts = transitionService.resetCurrentScores(fromVersion, dryRun);
                // キャッシュの温め直しはトランザクション確定後でなければ意味がないため、
                // resetCurrentScores から戻ってきたここで呼ぶ（dry-run 時は DB が変わらないので不要）。
                if (!dryRun) {
                    transitionService.refreshSongCaches();
                }
                return "scores " + counts.get("scoreRows") + " 行 / 譜面順位 " + counts.get("userSongRankRows") +
                        " 行 / 比較集計 " + counts.get("userComparisonStatRows") + " 行 / リセット行 " +
                        counts.get("resetLogRows") + " 人";
            });
            if (!reset) return;
        }

        if (!completionLogged) {
            log.warn("[世代切替] 自動実行できる手順は完了。残りは手作業: " +
                    (resetScores ? "" : "スコアの初期化（reset-scores が無効のため未実行） / ") +
                    "フロントエンドの CURRENT_VERSION を {} へ更新して再デプロイ / beta 表記の削除",
                    toVersion);
            completionLogged = true;
        }
    }

    /**
     * 【メソッドの役割】 1 度きりの手順を、実行済み記録を見ながら安全に走らせる。
     *
     * 記録の意味:
     *  - SUCCESS … 実行済み。何もせず true（次の手順へ進んでよい）。
     *  - RUNNING … 前回の実行が終わっていない（途中でプロセスが落ちた等）。
     *               自動での再実行は危険なので警告だけ出して止める。手動確認が必要。
     *  - FAILED / SKIPPED … 記録を消せば次のポーリングで再挑戦される。
     *
     * dry-run のときは記録を残さない。件数を確かめてから本番実行に切り替えられるようにするため。
     *
     * @param step 手順名（記録キーの一部になる）
     * @param body 実処理。結果の要約文字列を返す。事前条件を満たさない場合は
     *             {@link PreconditionFailedException} を投げると SKIPPED として記録される。
     * @return この手順が完了していて次へ進んでよいなら true
     */
    private boolean runOnce(String step, Step body) {
        String key = "version-transition:" + fromVersion + "->" + toVersion + ":" + step;

        if (dryRun) {
            try {
                log.info("[世代切替] dry-run {}: {}", step, body.run());
            } catch (PreconditionFailedException e) {
                log.warn("[世代切替] dry-run {} は事前条件を満たさない: {}", step, e.getMessage());
                return false;
            } catch (Exception e) {
                log.error("[世代切替] dry-run {} で例外", step, e);
                return false;
            }
            return true;
        }

        Optional<SystemTaskRun> existing = taskRunRepository.findByTaskKey(key);
        if (existing.isPresent()) {
            SystemTaskRun run = existing.get();
            switch (run.getStatus()) {
                case SUCCESS:
                    return true;
                case RUNNING:
                    log.warn("[世代切替] {} が RUNNING のまま残っている。前回の実行が中断した可能性があるため自動再実行はしない（key={}）",
                            step, key);
                    return false;
                default:
                    // FAILED / SKIPPED は記録を消して再挑戦する。
                    taskRunRepository.delete(run);
                    break;
            }
        }

        SystemTaskRun run = new SystemTaskRun();
        run.setTaskKey(key);
        run.setStatus(SystemTaskRun.Status.RUNNING);
        run.setStartedAt(LocalDateTime.now());
        taskRunRepository.saveAndFlush(run);

        try {
            String detail = body.run();
            run.setStatus(SystemTaskRun.Status.SUCCESS);
            run.setDetail(detail);
            log.info("[世代切替] {} 完了: {}", step, detail);
            return true;
        } catch (PreconditionFailedException e) {
            run.setStatus(SystemTaskRun.Status.SKIPPED);
            run.setDetail(e.getMessage());
            log.warn("[世代切替] {} を見送り: {}", step, e.getMessage());
            return false;
        } catch (Exception e) {
            run.setStatus(SystemTaskRun.Status.FAILED);
            run.setDetail(e.toString());
            log.error("[世代切替] {} が失敗", step, e);
            return false;
        } finally {
            run.setFinishedAt(LocalDateTime.now());
            taskRunRepository.save(run);
        }
    }

    /**
     * 1 手順ぶんの実処理。{@link java.util.function.Supplier} ではなくこれを使うのは、
     * 難易度表の適用（{@code GameDataService#applyDraftDifficultyTable}）が検査例外を投げるため。
     * 例外は {@link #runOnce} が受け取り、FAILED として記録する。
     */
    @FunctionalInterface
    private interface Step {
        String run() throws Exception;
    }

    /** 事前条件を満たさないため実行を見送る、という意思表示に使う内部例外。 */
    private static class PreconditionFailedException extends RuntimeException {
        PreconditionFailedException(String message) {
            super(message);
        }
    }
}
