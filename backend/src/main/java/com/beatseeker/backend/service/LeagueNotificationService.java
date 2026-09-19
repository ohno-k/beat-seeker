package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.AppNotification;
import com.beatseeker.backend.entity.LeagueEntry;
import com.beatseeker.backend.entity.LeagueMember;
import com.beatseeker.backend.entity.LeagueSong;
import com.beatseeker.backend.entity.LeagueWeek;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.AppNotificationRepository;
import com.beatseeker.backend.repository.LeagueEntryRepository;
import com.beatseeker.backend.repository.LeagueMemberRepository;
import com.beatseeker.backend.repository.LeagueSongRepository;
import com.beatseeker.backend.repository.LeagueWeekRepository;
import com.beatseeker.backend.repository.UserRepository;
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
 * 【Service の役割】 リーグの進行に合わせた各種通知（アプリ内通知 + ブラウザ通知）をまとめて担う。
 *
 * 通知の種類と契機:
 * <table>
 *   <tr><td>{@code LEAGUE_START}</td>        <td>週が active になった（月曜 12:00 / 管理者の run-weekly）</td></tr>
 *   <tr><td>{@code LEAGUE_RESULT}</td>       <td>週を締めた（日曜 21:00）。順位・昇降格・PT。自動休止の予告もここに含める</td></tr>
 *   <tr><td>{@code LEAGUE_DEADLINE}</td>     <td>日曜 12:00 のリマインド。<b>有効曲が揃っていない人だけ</b></td></tr>
 *   <tr><td>{@code LEAGUE_GROUP_UPDATE}</td> <td>同グループの誰かが課題曲を更新した（アプリ内通知のみ）</td></tr>
 *   <tr><td>{@code LEAGUE_OVERTAKEN}</td>    <td>↑の結果、自分の順位が下がった（ブラウザ通知あり）</td></tr>
 * </table>
 *
 * <p><b>push とベルの出し分け:</b> 同グループの更新は 1 グループ最大 8 人 × 課題曲 3 曲あり、
 * 全部 push すると週に数十通になって「うるさいから通知ごと切る」を誘発する。そこで
 * 更新そのものはアプリ内通知（ベル）にだけ積み、ブラウザ通知は「順位が下がった」ときだけ鳴らす。
 * ベルは DB に積むだけで鳴らないので、何件あっても害がない。
 *
 * <p><b>実行方式:</b> 週の開始・締めからは <b>コミット後</b>に {@code @Async} で呼ばれる。
 * 参加者の数だけ外部 HTTPS を待つ間 DB トランザクションを握らないため、かつ通知の失敗で
 * リーグの進行を巻き戻さないため。例外はすべてこのクラス内で握り潰す。
 *
 * <p><b>ユーザー設定:</b> {@code users.league_notifications_enabled} が false のユーザーには
 * ブラウザ通知もアプリ内通知も作らない（{@link #notifiable}）。
 */
@Service
public class LeagueNotificationService {

    private static final Logger log = LoggerFactory.getLogger(LeagueNotificationService.class);

    /** 課題曲の本数（この数に満たないメンバーが終盤リマインドの対象）。 */
    private static final int FULL_VALID_SONGS = 3;

    private final LeagueWeekRepository leagueWeekRepository;
    private final LeagueMemberRepository leagueMemberRepository;
    private final LeagueSongRepository leagueSongRepository;
    private final LeagueEntryRepository leagueEntryRepository;
    private final LeagueStandingsService standingsService;
    private final AppNotificationRepository appNotificationRepository;
    private final PushNotificationService pushNotificationService;
    private final UserRepository userRepository;

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     */
    public LeagueNotificationService(LeagueWeekRepository leagueWeekRepository,
                                     LeagueMemberRepository leagueMemberRepository,
                                     LeagueSongRepository leagueSongRepository,
                                     LeagueEntryRepository leagueEntryRepository,
                                     LeagueStandingsService standingsService,
                                     AppNotificationRepository appNotificationRepository,
                                     PushNotificationService pushNotificationService,
                                     UserRepository userRepository) {
        this.leagueWeekRepository = leagueWeekRepository;
        this.leagueMemberRepository = leagueMemberRepository;
        this.leagueSongRepository = leagueSongRepository;
        this.leagueEntryRepository = leagueEntryRepository;
        this.standingsService = standingsService;
        this.appNotificationRepository = appNotificationRepository;
        this.pushNotificationService = pushNotificationService;
        this.userRepository = userRepository;
    }

    // ========================================================================
    // 1. 週の開始
    // ========================================================================

    /**
     * 【メソッドの役割】 開始した週の参加者全員へ「リーグが始まった」通知を配る。
     *
     * 非同期（別スレッド）で走るため、呼び出し元のエンティティは使わず weekId から引き直す。
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

            Map<String, List<LeagueSong>> songsBySeat = songsBySeat(week);
            String roundLabel = roundLabel(week);
            String title = roundLabel + "が始まりました";

            int pushed = 0;
            for (LeagueMember member : members) {
                User user = member.getUser();
                if (!notifiable(user)) continue;

                String seat = seatLabel(member);
                List<LeagueSong> songs = songsBySeat.getOrDefault(seatKey(member), List.of());

                save(user, "LEAGUE_START", roundLabel + "が始まりました。あなたは " + seat + " です。"
                        + (songs.isEmpty() ? "課題曲は追って公開されます。" : "課題曲: " + songList(songs)));

                String body = seat + " / 課題曲" + songs.size() + "曲が公開されました（日曜 21:00 まで）";
                if (push(user, title, body)) pushed++;
            }
            log.info("リーグ開始通知を送信: weekId={} members={} push={}", weekId, members.size(), pushed);
        } catch (Exception e) {
            log.error("リーグ開始通知に失敗: weekId={}", weekId, e);
        }
    }

    // ========================================================================
    // 2. 週の締め（結果確定）
    // ========================================================================

    /**
     * 【メソッドの役割】 締めた週の参加者全員へ最終結果を配る。
     *
     * 締め処理が {@link LeagueMember} へ凍結した順位・昇降格・PT 増減をそのまま読む
     * （再計算しない）。自動休止が近い／休止になったメンバーへの警告も、同じタイミングで
     * 同じ相手に届くので 1 通にまとめる（日曜 21:00 に 2 通鳴らさない）。
     *
     * @param weekId 締めた週の ID
     */
    @Async
    @Transactional
    public void notifyWeekClosed(Long weekId) {
        try {
            LeagueWeek week = leagueWeekRepository.findById(weekId).orElse(null);
            if (week == null) {
                log.warn("リーグ結果通知: 週が見つからない weekId={}", weekId);
                return;
            }
            List<LeagueMember> members = leagueMemberRepository.findByWeek(week);
            if (members.isEmpty()) return;

            // グループの人数（「n人中 k位」の表示に使う）。
            Map<String, Long> sizeBySeat = members.stream()
                    .collect(Collectors.groupingBy(this::seatKey, Collectors.counting()));

            String roundLabel = roundLabel(week);
            int pushed = 0;
            for (LeagueMember member : members) {
                User user = member.getUser();
                if (!notifiable(user)) continue;

                long size = sizeBySeat.getOrDefault(seatKey(member), 1L);
                // 締めが entry を更新した後の値を読む（昇降格後の DIVISION と PT）。
                LeagueEntry entry = leagueEntryRepository
                        .findByUserAndLadderType(user, week.getLadderType()).orElse(null);

                String rankText = member.getFinalRank() != null
                        ? size + "人中 " + member.getFinalRank() + "位"
                        : "順位なし";
                String resultText = member.getResultValue() != null
                        ? "（有効" + nz(member.getValidSongs()) + "曲 / 平均 " + member.getResultValue() + "%）"
                        : "（有効曲なし）";
                String movementText = movementText(member, entry);
                String inactiveText = inactiveText(entry);

                save(user, "LEAGUE_RESULT", roundLabel + "が終了しました。" + seatLabel(member) + " で "
                        + rankText + resultText + "。" + movementText + inactiveText);

                if (push(user, roundLabel + "の結果", rankText + " / " + movementText)) pushed++;
            }
            log.info("リーグ結果通知を送信: weekId={} members={} push={}", weekId, members.size(), pushed);
        } catch (Exception e) {
            log.error("リーグ結果通知に失敗: weekId={}", weekId, e);
        }
    }

    /** 昇降格の結果と、次週に向けた残り PT の文言。 */
    private String movementText(LeagueMember member, LeagueEntry entry) {
        String movement = member.getMovement();
        if ("promote".equals(movement)) {
            return entry != null ? "DIVISION " + divisionLabel(entry.getCurrentTier()) + " へ昇格しました！" : "昇格しました！";
        }
        if ("relegate".equals(movement)) {
            return entry != null ? "DIVISION " + divisionLabel(entry.getCurrentTier()) + " へ降格しました。" : "降格しました。";
        }
        if (entry == null || entry.getPoints() == null) return "DIVISION 据え置きです。";
        int points = entry.getPoints();
        // 昇降格は ±POINT_CAP で確定する。どちら側に何 PT 残っているかを添えると次週の目標になる。
        int toPromote = LeagueStandingsService.POINT_CAP - points;
        int toRelegate = points + LeagueStandingsService.POINT_CAP;
        return "DIVISION 据え置き（現在 " + (points >= 0 ? "+" : "") + points + "PT / 昇格まであと "
                + toPromote + "PT・降格まであと " + toRelegate + "PT）。";
    }

    /** 自動休止が近い／休止になった場合の追記。該当しなければ空文字。 */
    private String inactiveText(LeagueEntry entry) {
        if (entry == null) return "";
        int inactive = entry.getInactiveWeeks() != null ? entry.getInactiveWeeks() : 0;
        if (inactive == 0) return "";
        if (Boolean.FALSE.equals(entry.getActive())) {
            return " ※" + inactive + "週連続で活動が無かったため自動休止しました。リーグ画面から再参加できます。";
        }
        int remain = LeagueWeekLifecycleService.AUTO_DEACTIVATE_AFTER - inactive;
        if (remain <= 1) {
            return " ※" + inactive + "週連続で活動がありません。次の週も課題曲を遊ばないと自動休止になります。";
        }
        return "";
    }

    // ========================================================================
    // 3. 終盤リマインド
    // ========================================================================

    /**
     * 【メソッドの役割】 締め前のリマインドを、<b>課題曲が揃っていないメンバーにだけ</b>送る。
     *
     * 有効曲 0 曲だと着順ポイントのプラスを一切受け取れず（放置昇格の防止）、活動が無い週が
     * 続くと自動休止になる。「参加しているのに気付かないまま不戦敗」を防ぐのが目的なので、
     * 既に 3 曲すべて有効な人には送らない。
     *
     * @param ladder ラダー種別
     */
    @Async
    @Transactional
    public void notifyDeadlineReminder(String ladder) {
        try {
            LeagueWeek week = leagueWeekRepository
                    .findFirstByLadderTypeAndStatusOrderByStartsAtDesc(ladder, "active").orElse(null);
            if (week == null) return; // 開催中の週なし

            List<LeagueMember> members = leagueMemberRepository.findByWeek(week);
            if (members.isEmpty()) return;

            String roundLabel = roundLabel(week);
            int pushed = 0;
            // 順位表はグループ単位でしか計算できないので、座席ごとにまとめて 1 回ずつ計算する。
            for (String seat : members.stream().map(this::seatKey).distinct().toList()) {
                String[] parts = seat.split("\\|");
                int tier = Integer.parseInt(parts[0]);
                int groupIndex = Integer.parseInt(parts[1]);

                for (Map<String, Object> row : standingsService.computeGroupStandings(week, tier, groupIndex)) {
                    int validSongs = intOf(row.get("validSongs"));
                    if (validSongs >= FULL_VALID_SONGS) continue; // 揃っている人には送らない

                    User user = userRepository.findById((Long) row.get("userId")).orElse(null);
                    if (!notifiable(user)) continue;

                    List<String> pending = pendingSongLabels(row);
                    String head = validSongs == 0
                            ? "まだ有効な課題曲がありません。"
                            : "有効な課題曲が " + validSongs + "/" + FULL_VALID_SONGS + " 曲です。";
                    save(user, "LEAGUE_DEADLINE", roundLabel + "は今日 21:00 に締め切ります。" + head
                            + (pending.isEmpty() ? "" : "未達: " + String.join(" / ", pending)));

                    if (push(user, roundLabel + "は今日 21:00 まで", head + "課題曲のラインを超えると順位に入ります")) {
                        pushed++;
                    }
                }
            }
            log.info("リーグ終盤リマインドを送信: weekId={} push={}", week.getId(), pushed);
        } catch (Exception e) {
            log.error("リーグ終盤リマインドに失敗: ladder={}", ladder, e);
        }
    }

    /**
     * 順位表の行から「まだ有効になっていない課題曲」のラベルを作る。
     * 管理者が無効化した曲は誰も有効化できないので除く。
     */
    private List<String> pendingSongLabels(Map<String, Object> row) {
        List<String> labels = new ArrayList<>();
        if (!(row.get("perSong") instanceof List<?> perSong)) return labels;
        for (Object o : perSong) {
            if (!(o instanceof Map<?, ?> ps)) continue;
            if (Boolean.TRUE.equals(ps.get("valid")) || Boolean.TRUE.equals(ps.get("disabled"))) continue;
            Object lineEx = ps.get("lineEx");
            String need = lineEx instanceof Number n ? "（ライン " + n.intValue() + "）" : "（ライン無し）";
            labels.add(ps.get("title") + need);
        }
        return labels;
    }

    // ========================================================================
    // 4. 同グループの更新／順位低下
    // ========================================================================

    /**
     * 【メソッドの役割】 アップロードで課題曲が更新されたことを同グループへ知らせ、
     * 順位が下がったメンバーにはブラウザ通知も送る。
     *
     * 処理の流れ:
     *  - 手順1: アップロード主が開催中の週のメンバーか調べる
     *  - 手順2: 更新譜面のうち、自分のグループの課題曲に当たるものを拾う（無ければ終了）
     *  - 手順3: 同グループの他メンバーへアプリ内通知（ベル）を積む
     *  - 手順4: グループの順位を 1 度だけ計算し、{@code lastNotifiedRank} より順位が下がった
     *           メンバーへブラウザ通知を送る。送信有無に関わらず順位は記録し直す
     *
     * 手順 4 で順位を 2 回（更新前・更新後）計算せず前回値との比較にしているのは、
     * 順位計算がグループ全員 × 課題曲 3 曲のスコア照会を伴うため。アップロード 1 回につき
     * 1 回に抑える。
     *
     * <p>有効曲 0 曲のメンバーには順位低下の通知を送らない。週の開始直後は全員が
     * 有効 0 曲の同着 1 位なので、誰か 1 人が記録を出した瞬間に全員へ「抜かれた」が
     * 飛んでしまう。まだ走り出していない人に必要なのは順位ではなく終盤リマインドの方。
     *
     * @param uploaderId   スコアをアップロードしたユーザーの ID
     * @param ladder       ラダー種別
     * @param updatedCharts 更新された譜面の {@code title|difficultyName} 集合（アーケードのみ）
     */
    @Async
    @Transactional
    public void notifyGroupUpdate(Long uploaderId, String ladder, List<String> updatedCharts) {
        try {
            if (updatedCharts == null || updatedCharts.isEmpty()) return;
            User uploader = userRepository.findById(uploaderId).orElse(null);
            if (uploader == null) return;

            LeagueWeek week = leagueWeekRepository
                    .findFirstByLadderTypeAndStatusOrderByStartsAtDesc(ladder, "active").orElse(null);
            if (week == null) return;

            LeagueMember me = leagueMemberRepository.findByWeekAndUser(week, uploader).orElse(null);
            if (me == null) return; // 今週の参加者ではない

            // 自分のグループの課題曲のうち、今回更新されたもの。
            List<LeagueSong> hit = leagueSongRepository
                    .findByWeekAndTierAndGroupIndexOrderBySlotAsc(week, me.getTier(), me.getGroupIndex()).stream()
                    .filter(s -> !s.isDisabled())
                    .filter(s -> updatedCharts.contains(s.getTitle() + "|" + s.getDifficultyName()))
                    .toList();
            if (hit.isEmpty()) return; // 課題曲以外の更新なら何もしない

            List<LeagueMember> group = leagueMemberRepository
                    .findByWeekAndTierAndGroupIndex(week, me.getTier(), me.getGroupIndex());

            // 手順3: 同グループの他メンバーのベルへ「誰が何を更新したか」を積む（push はしない）。
            String uploaderName = displayName(uploader);
            String updateMessage = uploaderName + "さんが課題曲「" + hit.stream()
                    .map(LeagueSong::getTitle).collect(Collectors.joining("」「")) + "」を更新しました。";
            for (LeagueMember m : group) {
                if (m.getUser() == null || m.getUser().getId().equals(uploaderId)) continue;
                if (!notifiable(m.getUser())) continue;
                save(m.getUser(), "LEAGUE_GROUP_UPDATE", updateMessage);
            }

            // 手順4: 順位を計算し直し、下がった人にだけ push する。
            Map<Long, LeagueMember> byUserId = group.stream()
                    .filter(m -> m.getUser() != null)
                    .collect(Collectors.toMap(m -> m.getUser().getId(), m -> m, (a, b) -> a));
            List<Map<String, Object>> standings =
                    standingsService.computeGroupStandings(week, me.getTier(), me.getGroupIndex());

            int pushed = 0;
            for (Map<String, Object> row : standings) {
                Long userId = (Long) row.get("userId");
                LeagueMember m = byUserId.get(userId);
                if (m == null) continue;

                int rank = intOf(row.get("rank"));
                Integer before = m.getLastNotifiedRank();
                // 記録は必ず更新する（通知しなかった場合も次回の基準にする）。
                m.setLastNotifiedRank(rank);

                if (userId.equals(uploaderId)) continue;          // 抜いた側には送らない
                if (before == null || rank <= before) continue;   // 順位が下がっていない
                if (intOf(row.get("validSongs")) == 0) continue;  // まだ走り出していない人は対象外
                if (!notifiable(m.getUser())) continue;

                save(m.getUser(), "LEAGUE_OVERTAKEN",
                        uploaderName + "さんに抜かれ、グループ順位が " + before + "位 → " + rank + "位 に下がりました。");
                if (push(m.getUser(), "リーグで順位が下がりました",
                        uploaderName + "さんに抜かれて " + rank + "位です")) {
                    pushed++;
                }
            }
            leagueMemberRepository.saveAll(group);
            log.info("リーグ同グループ更新通知: weekId={} uploaderId={} group={} push={}",
                    week.getId(), uploaderId, group.size(), pushed);
        } catch (Exception e) {
            log.error("リーグ同グループ更新通知に失敗: uploaderId={}", uploaderId, e);
        }
    }

    // ========================================================================
    // 共通ヘルパー
    // ========================================================================

    /** リーグ通知を送ってよい相手か（存在する / 設定で切っていない）。 */
    private boolean notifiable(User user) {
        return user != null && user.isLeagueNotificationsEnabled();
    }

    /** アプリ内通知（ベル）を 1 件積む。 */
    private void save(User recipient, String type, String message) {
        AppNotification notification = new AppNotification();
        notification.setRecipient(recipient);
        notification.setType(type);
        notification.setMessage(message);
        appNotificationRepository.save(notification);
    }

    /** ブラウザ通知を送る。遷移先はリーグ画面で固定。 */
    private boolean push(User recipient, String title, String body) {
        return pushNotificationService.sendToUser(recipient, title, body, "/league");
    }

    /** 週の全課題曲を「DIVISION × グループ」のキーで引けるようにまとめる（N+1 回避）。 */
    private Map<String, List<LeagueSong>> songsBySeat(LeagueWeek week) {
        return leagueSongRepository.findByWeekOrderByTierAscSlotAsc(week).stream()
                .filter(s -> !s.isDisabled())
                .collect(Collectors.groupingBy(
                        s -> s.getTier() + "|" + (s.getGroupIndex() != null ? s.getGroupIndex() : 0),
                        HashMap::new,
                        Collectors.toList()));
    }

    /** メンバーの座席キー（DIVISION × グループ）。 */
    private String seatKey(LeagueMember member) {
        return member.getTier() + "|" + (member.getGroupIndex() != null ? member.getGroupIndex() : 0);
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
        return "DIVISION " + divisionLabel(member.getTier())
                + " グループ" + ((member.getGroupIndex() != null ? member.getGroupIndex() : 0) + 1);
    }

    /** tier 値の表示名（0 は LEGEND）。 */
    private String divisionLabel(Integer tier) {
        if (tier == null) return "-";
        return tier == LeagueDivision.LEGEND ? "LEGEND" : String.valueOf(tier);
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

    /** null を 0 として int 化する。 */
    private int nz(Integer v) {
        return v != null ? v : 0;
    }

    /** Map から取り出した数値を int 化する（null は 0）。 */
    private int intOf(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** 表示名。未設定なら IIDX ID で代用する。 */
    private String displayName(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) return user.getDisplayName();
        return user.getIidxId() != null ? user.getIidxId() : "ユーザー";
    }
}
