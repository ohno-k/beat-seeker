package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.LeagueMember;
import com.beatseeker.backend.entity.LeagueSong;
import com.beatseeker.backend.entity.LeagueWeek;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.LeagueMemberRepository;
import com.beatseeker.backend.repository.LeagueSongRepository;
import com.beatseeker.backend.repository.LeagueWeekRepository;
import com.beatseeker.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【Service の役割】 リーグ参加者が「今週の課題曲」を更新したときに管理者へメール通知する。
 *
 * スコアのアップロード（{@code POST /api/scores/upload}）で更新された譜面のうち、
 * 開催中の週で自分のグループに割り当てられている課題曲に一致するものがあれば、
 * その差分（旧 EX → 新 EX・レート・ライン・有効化されたか）をまとめて管理者宛に送る。
 *
 * 判定条件:
 *  - 更新者が管理者本人でない（自分の更新で自分に届くのを避ける。他の管理者通知と同じ流儀）
 *  - 開催中(active)の週があり、更新者がその週のメンバーである
 *  - 更新された譜面が、そのメンバーのグループの課題曲に一致する
 *  - アーケード記録の更新のみを対象にする（リーグの集計対象がアーケード限定のため、
 *    INFINITAS 由来の更新は無視する）
 *
 * 失敗しても呼び出し元（スコア保存）を巻き戻さない。例外はログのみ。
 */
@Service
public class LeagueUpdateNotificationService {

    private static final Logger log = LoggerFactory.getLogger(LeagueUpdateNotificationService.class);

    private final LeagueWeekRepository leagueWeekRepository;
    private final LeagueMemberRepository leagueMemberRepository;
    private final LeagueSongRepository leagueSongRepository;
    private final LeagueStandingsService standingsService;
    private final UserRepository userRepository;
    private final AdminAuthService adminAuthService;
    private final EmailService emailService;

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     */
    public LeagueUpdateNotificationService(LeagueWeekRepository leagueWeekRepository,
                                           LeagueMemberRepository leagueMemberRepository,
                                           LeagueSongRepository leagueSongRepository,
                                           LeagueStandingsService standingsService,
                                           UserRepository userRepository,
                                           AdminAuthService adminAuthService,
                                           EmailService emailService) {
        this.leagueWeekRepository = leagueWeekRepository;
        this.leagueMemberRepository = leagueMemberRepository;
        this.leagueSongRepository = leagueSongRepository;
        this.standingsService = standingsService;
        this.userRepository = userRepository;
        this.adminAuthService = adminAuthService;
        this.emailService = emailService;
    }

    /**
     * 【メソッドの役割】 今回の更新に課題曲が含まれていれば、管理者へメール通知する。
     *
     * @param user         スコアを更新したユーザー
     * @param updatedSongs 今回の更新差分（{@code title / difficulty / oldScore / newScore / source} を含む Map のリスト）
     */
    public void notifyLeagueSongUpdate(User user, List<Map<String, Object>> updatedSongs) {
        if (user == null || updatedSongs == null || updatedSongs.isEmpty()) return;
        if (adminAuthService.isAdmin(user)) return; // 管理者自身の更新では送らない

        LeagueWeek week = leagueWeekRepository
                .findFirstByLadderTypeAndStatusOrderByStartsAtDesc(LeagueService.LADDER_SCORE, "active")
                .orElse(null);
        if (week == null) return; // 開催中の週なし

        LeagueMember member = leagueMemberRepository.findByWeekAndUser(week, user).orElse(null);
        if (member == null) return; // 今週の参加者ではない

        // 無効化された課題曲（管理者が集計対象から外した曲）は通知しない。
        List<LeagueSong> songs = leagueSongRepository
                .findByWeekAndTierAndGroupIndexOrderBySlotAsc(week, member.getTier(), member.getGroupIndex()).stream()
                .filter(s -> !s.isDisabled())
                .toList();
        if (songs.isEmpty()) return;

        // 今回の更新を (曲名|難易度) で引けるようにする。リーグはアーケード記録のみ見るので他ソースは除外。
        Map<String, Map<String, Object>> updatedByChart = new HashMap<>();
        for (Map<String, Object> d : updatedSongs) {
            Object source = d.get("source");
            if (source != null && !"arcade".equals(source)) continue;
            updatedByChart.put(d.get("title") + "|" + d.get("difficulty"), d);
        }
        if (updatedByChart.isEmpty()) return;

        // ライン（週開始時点のグループ最高記録）をスロット別に引く。
        Map<Integer, Map<String, Object>> lineBySlot = new HashMap<>();
        for (Map<String, Object> line : standingsService.computeGroupSongLines(
                week, member.getTier(), member.getGroupIndex())) {
            lineBySlot.put(toInt(line.get("slot")), line);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (LeagueSong song : songs) {
            Map<String, Object> diff = updatedByChart.get(song.getTitle() + "|" + song.getDifficultyName());
            if (diff == null) continue; // この課題曲は今回更新されていない

            int newScore = toInt(diff.get("newScore"));
            int oldScore = toInt(diff.get("oldScore"));
            Map<String, Object> line = lineBySlot.get(song.getSlot());
            Integer lineEx = line != null ? asInteger(line.get("lineEx")) : null;

            // ラインちょうどは無効（超えて初めて有効）。今回初めて超えたものは「今回有効化」として区別する。
            boolean nowValid = lineEx == null || newScore > lineEx;
            boolean wasValid = lineEx != null && oldScore > lineEx;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", song.getTitle());
            row.put("difficulty", song.getDifficultyName());
            row.put("level", song.getLevel());
            row.put("oldScore", oldScore);
            row.put("newScore", newScore);
            row.put("increase", Math.max(0, newScore - oldScore));
            row.put("rate", rateOf(newScore, song.getNotes()));
            row.put("lineEx", lineEx);
            row.put("lineRate", lineEx != null ? rateOf(lineEx, song.getNotes()) : null);
            row.put("status", nowValid ? (wasValid ? "valid" : "activated") : "below");
            row.put("toLine", !nowValid ? (lineEx - newScore + 1) : null);
            rows.add(row);
        }
        if (rows.isEmpty()) return; // 更新はあったが課題曲は含まれていない

        String division = member.getTier() != null && member.getTier() == LeagueDivision.LEGEND
                ? "DIVISION LEGEND"
                : "DIVISION " + member.getTier();
        String group = "グループ" + (member.getGroupIndex() + 1);

        try {
            userRepository.findById(adminAuthService.getAdminUserId()).ifPresent(admin -> {
                if (admin.getEmail() == null || admin.getEmail().isBlank()) return;
                emailService.sendLeagueSongUpdateNotification(
                        admin.getEmail(),
                        user.getDisplayName() != null && !user.getDisplayName().isBlank()
                                ? user.getDisplayName() : String.valueOf(user.getIidxId()),
                        user.getIidxId() != null ? user.getIidxId() : "",
                        division, group, rows);
            });
        } catch (Exception e) {
            // 通知失敗でスコア保存を巻き戻さない
            log.error("リーグ課題曲更新の管理者通知に失敗: userId={}", user.getId(), e);
        }
    }

    /** EX からスコアレート(%・小数第 2 位)。notes 不明なら null。 */
    private Double rateOf(int ex, Integer notes) {
        if (notes == null || notes <= 0) return null;
        return Math.round(ex * 100.0 / (notes * 2) * 100.0) / 100.0;
    }

    /** Map から取り出した数値を int 化する（null は 0）。 */
    private int toInt(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** Map から取り出した数値を Integer 化する（null はそのまま null）。 */
    private Integer asInteger(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }
}
