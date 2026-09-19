package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.AppNotification;
import com.beatseeker.backend.entity.LeagueMember;
import com.beatseeker.backend.entity.LeagueSong;
import com.beatseeker.backend.entity.LeagueWeek;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.AppNotificationRepository;
import com.beatseeker.backend.repository.LeagueMemberRepository;
import com.beatseeker.backend.repository.LeagueSongRepository;
import com.beatseeker.backend.repository.LeagueWeekRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【Service の役割】 リーグの週が開始（draft → active）したときに、参加者全員へ
 * 「今週が始まった・自分の卓とグループ・課題曲」を通知する。
 *
 * 通知は 2 系統:
 *  - アプリ内通知（ベルアイコン）: 参加者全員に必ず保存する
 *  - ブラウザ通知（Web Push）: 購読済みの参加者だけに送る
 *
 * 呼び出し元は {@link LeagueWeekLifecycleService#activateWeek}。開始トランザクションの
 * <b>コミット後</b>に非同期で呼ばれる。理由は 2 つ:
 *  - Push は 1 人ずつ外部 HTTPS を叩くので、参加者が増えると開始処理のトランザクションを
 *    その分長く握ることになる（cron でも管理者の run-weekly でも困る）
 *  - 通知の失敗でリーグ開始そのものを巻き戻してはいけない
 *
 * 課題曲は「週 × DIVISION（tier）× グループ」単位で異なるため、週の全課題曲を 1 度だけ
 * 読み出して {@code tier|groupIndex} をキーに引く（メンバーごとに引くと N+1 になる）。
 */
@Service
public class LeagueStartNotificationService {

    private static final Logger log = LoggerFactory.getLogger(LeagueStartNotificationService.class);

    private final LeagueWeekRepository leagueWeekRepository;
    private final LeagueMemberRepository leagueMemberRepository;
    private final LeagueSongRepository leagueSongRepository;
    private final AppNotificationRepository appNotificationRepository;
    private final PushNotificationService pushNotificationService;

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     */
    public LeagueStartNotificationService(LeagueWeekRepository leagueWeekRepository,
                                          LeagueMemberRepository leagueMemberRepository,
                                          LeagueSongRepository leagueSongRepository,
                                          AppNotificationRepository appNotificationRepository,
                                          PushNotificationService pushNotificationService) {
        this.leagueWeekRepository = leagueWeekRepository;
        this.leagueMemberRepository = leagueMemberRepository;
        this.leagueSongRepository = leagueSongRepository;
        this.appNotificationRepository = appNotificationRepository;
        this.pushNotificationService = pushNotificationService;
    }

    /**
     * 【メソッドの役割】 開始した週の参加者全員へ「リーグが始まった」通知を配る。
     *
     * 非同期（別スレッド）で走るため、呼び出し元のエンティティは使わず weekId から引き直す。
     * 例外は握り潰す（通知の失敗をリーグ開始の失敗にしない）。
     *
     * @param weekId 開始した週の ID
     */
    @Async
    @Transactional
    public void notifyWeekStarted(Long weekId) {
        try {
            LeagueWeek week = leagueWeekRepository.findById(weekId).orElse(null);
            if (week == null) {
                log.warn("リーグ開始通知: 週が見つからない weekId={}", weekId);
                return;
            }
            List<LeagueMember> members = leagueMemberRepository.findByWeek(week);
            if (members.isEmpty()) return;

            // 課題曲を tier|groupIndex でまとめて引けるようにしておく（メンバーごとのクエリを避ける）。
            Map<String, List<LeagueSong>> songsBySeat = leagueSongRepository
                    .findByWeekOrderByTierAscSlotAsc(week).stream()
                    .filter(s -> !s.isDisabled())
                    .collect(Collectors.groupingBy(
                            s -> seatKey(s.getTier(), s.getGroupIndex()),
                            HashMap::new,
                            Collectors.toList()));

            String roundLabel = roundLabel(week);
            String title = roundLabel + "が始まりました";

            int pushed = 0;
            for (LeagueMember member : members) {
                User user = member.getUser();
                if (user == null) continue;

                String seat = seatLabel(member);
                List<LeagueSong> songs = songsBySeat.getOrDefault(
                        seatKey(member.getTier(), member.getGroupIndex()), List.of());

                // アプリ内通知（ベル）: 課題曲まで載せる。ここは長くても読める場所。
                AppNotification notification = new AppNotification();
                notification.setRecipient(user);
                notification.setType("LEAGUE_START");
                notification.setMessage(roundLabel + "が始まりました。あなたは " + seat + " です。"
                        + (songs.isEmpty() ? "課題曲は追って公開されます。" : "課題曲: " + songList(songs)));
                appNotificationRepository.save(notification);

                // ブラウザ通知: 通知バナーに収まる長さに抑える。
                String body = seat + " / 課題曲" + songs.size() + "曲が公開されました（日曜 21:00 まで）";
                if (pushNotificationService.sendToUser(user, title, body, "/league")) {
                    pushed++;
                }
            }
            log.info("リーグ開始通知を送信: weekId={} members={} push={}", weekId, members.size(), pushed);
        } catch (Exception e) {
            // 通知の失敗でリーグ開始を巻き戻さない（そもそもコミット後なので巻き戻せない）。
            log.error("リーグ開始通知に失敗: weekId={}", weekId, e);
        }
    }

    /** 課題曲を引くためのキー（DIVISION × グループ）。 */
    private String seatKey(Integer tier, Integer groupIndex) {
        return tier + "|" + (groupIndex != null ? groupIndex : 0);
    }

    /**
     * 開催回の呼称。番号なし（プレシーズン）の週は「リーグ」とだけ呼ぶ。
     * 表示は画面と揃えて「リーグ #6」形式。
     */
    private String roundLabel(LeagueWeek week) {
        return week.getWeekNo() != null ? "リーグ #" + week.getWeekNo() : "リーグ";
    }

    /**
     * 参加者の座席表記（「DIVISION 3 グループ2」）。
     * {@link LeagueUpdateNotificationService} の管理者メールと同じ書式に揃える。
     */
    private String seatLabel(LeagueMember member) {
        String division = member.getTier() != null && member.getTier() == LeagueDivision.LEGEND
                ? "DIVISION LEGEND"
                : "DIVISION " + member.getTier();
        return division + " グループ" + ((member.getGroupIndex() != null ? member.getGroupIndex() : 0) + 1);
    }

    /** 課題曲を「曲名(ANOTHER☆12)」形式で並べる。 */
    private String songList(List<LeagueSong> songs) {
        List<String> parts = new ArrayList<>();
        for (LeagueSong s : songs) {
            String level = s.getLevel() != null ? "☆" + s.getLevel() : "";
            parts.add(s.getTitle() + "(" + s.getDifficultyName() + level + ")");
        }
        return String.join(" / ", parts);
    }
}
