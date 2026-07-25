package com.beatseeker.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     */
    public LeagueScheduler(LeagueWeekLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    /**
     * 【メソッドの役割】 金曜 0:00 JST に翌週の draft 週を作成し、課題曲を先行抽選する。
     *
     * 管理者は週開始（月曜 15:00）までの間に課題曲を差し替え・再抽選できる。
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
     * 週の開催期間は「月曜 15:00 〜 日曜 21:00」。締め以降〜翌月曜 15:00 は
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
     * 【メソッドの役割】 月曜 15:00 JST に次週を編成して開始する。
     *
     * この時刻が参加締切（途中参加不可）であり、active 化と同時に課題曲が公開される
     * （= 開始した瞬間に課題曲がわかる）。金曜の draft 作成が何らかの理由で
     * 走っていなくても、activateWeek 内で draft をその場で作成するため単独で成立する。
     */
    @Scheduled(cron = "0 0 15 * * MON", zone = "Asia/Tokyo")
    public void activateWeeks() {
        for (String ladder : LeagueService.LADDERS) {
            try {
                lifecycleService.activateWeek(ladder);
            } catch (Exception e) {
                log.error("リーグ週の開始に失敗: ladder={}", ladder, e);
            }
        }
    }
}
