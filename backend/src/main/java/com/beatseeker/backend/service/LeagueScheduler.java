package com.beatseeker.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 【クラスの役割】 リーグモードの週次 cron 起動を担う薄いコンポーネント。
 *
 * 実処理は {@link LeagueWeekLifecycleService} に委譲する。別クラスに分けているのは、
 * 同一クラス内の自己呼び出しでは Spring のプロキシを経由せず
 * {@code @Transactional} が効かなくなるため（ラダーごとに独立したトランザクションで
 * 実行し、片方の失敗がもう片方を巻き込まないようにする）。
 *
 * cron はいずれも JST（Asia/Tokyo）指定。サーバーの TZ（Render では UTC）に依存しない。
 */
@Component
public class LeagueScheduler {

    private static final Logger log = LoggerFactory.getLogger(LeagueScheduler.class);

    private final LeagueWeekLifecycleService lifecycleService;
    private final LeagueService leagueService;

    /**
     * シーズン開始日（JST）。この日以降の月曜 12:00 のみ自動編成・開始する。
     * それより前は {@link #activateWeeks()} を何もせずスキップする（告知した開始日を守るため）。
     * {@code app.league.season-start}（例: "2026-08-03"）で変更可。未設定時は 2026-08-03。
     */
    private final LocalDate seasonStart;

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     */
    public LeagueScheduler(LeagueWeekLifecycleService lifecycleService,
                           LeagueService leagueService,
                           @Value("${app.league.season-start:2026-08-03}") String seasonStart) {
        this.lifecycleService = lifecycleService;
        this.leagueService = leagueService;
        this.seasonStart = LocalDate.parse(seasonStart);
    }

    /**
     * 【メソッドの役割】 金曜 0:00 JST に翌週の draft 週を作成し、課題曲を先行抽選する。
     *
     * 管理者は週開始（月曜 12:00）までの間に課題曲を差し替え・再抽選できる。
     */
    @Scheduled(cron = "0 0 0 * * FRI", zone = "Asia/Tokyo")
    public void createDraftWeeks() {
        for (String ladder : LeagueService.LADDERS) {
            try {
                lifecycleService.createDraftWeek(ladder);
            } catch (Exception e) {
                log.error("リーグ draft 週の作成に失敗: ladder={}", ladder, e);
            }
        }
    }

    /**
     * 【メソッドの役割】 日曜 21:00 JST に active 週を締める（順位凍結・昇降格確定・自動休止）。
     *
     * 週の開催期間は「月曜 12:00 〜 日曜 21:00」。締め以降〜翌月曜 12:00 は
     * 集計結果の閲覧と次週準備の空白時間になる。
     */
    @Scheduled(cron = "0 0 21 * * SUN", zone = "Asia/Tokyo")
    public void closeWeeks() {
        for (String ladder : LeagueService.LADDERS) {
            try {
                lifecycleService.closeWeek(ladder);
            } catch (Exception e) {
                log.error("リーグ週の締めに失敗: ladder={}", ladder, e);
            }
        }
    }

    /**
     * 【メソッドの役割】 参加締切後（プレシーズン確定窓）に、未編成の draft 週を自動編成する。
     *
     * 参加受付の締切（{@code app.league.signup-close}）〜開始（月曜 12:00）の間だけ動く。
     * 締切直後に本番と同じ卓・グループ・課題曲を確定しておき、管理者は開始までに確認・調整できる。
     * 既に編成済み（管理者の手動編成・調整を含む）なら {@link LeagueWeekLifecycleService#autoFormDraft}
     * が何もしないため、この窓で複数回起動しても上書きしない（冪等）。開始（activateWeek）は
     * この事前編成をそのまま使う。
     */
    @Scheduled(cron = "0 0 0 * * MON", zone = "Asia/Tokyo")
    public void autoFormWeeks() {
        if (!leagueService.isRegistrationLocked()) {
            return; // 参加締切〜開始の窓の外では自動編成しない（通常週は開始時に編成する）
        }
        for (String ladder : LeagueService.LADDERS) {
            try {
                lifecycleService.autoFormDraft(ladder);
            } catch (Exception e) {
                log.error("リーグ draft 週の自動編成に失敗: ladder={}", ladder, e);
            }
        }
    }

    /**
     * 【メソッドの役割】 月曜 12:00 JST に次週を編成して開始する。
     *
     * この時刻が参加締切（途中参加不可）であり、active 化と同時に課題曲が公開される
     * （= 開始した瞬間に課題曲がわかる）。金曜の draft 作成が何らかの理由で
     * 走っていなくても、activateWeek 内で draft をその場で作成するため単独で成立する。
     * 事前編成（{@link #autoFormWeeks()} や管理者の手動編成）済みならそれをそのまま使う。
     */
    @Scheduled(cron = "0 0 12 * * MON", zone = "Asia/Tokyo")
    public void activateWeeks() {
        // シーズン開始日より前は自動開始しない（告知した開始日まで待つ）。
        // 手動トリガー（管理者の run-weekly / activateWeek）はこのゲートを通らないので随時実行可能。
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        if (today.isBefore(seasonStart)) {
            log.info("リーグ自動開始はシーズン開始日({})より前のためスキップ: today={}", seasonStart, today);
            return;
        }
        for (String ladder : LeagueService.LADDERS) {
            try {
                lifecycleService.activateWeek(ladder);
            } catch (Exception e) {
                log.error("リーグ週の開始に失敗: ladder={}", ladder, e);
            }
        }
    }
}
