package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.*;
import com.beatseeker.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 【Service の役割】 リーグの週次ライフサイクル（締め → 昇降格 → 編成 → 開始）を担うサービス。
 *
 * 週の流れ（JST 基準）:
 *  - 週の開催期間は「月曜 15:00 〜 日曜 21:00」。日曜 21:00 〜 月曜 15:00 は集計・準備の空白時間。
 *  - 金曜 0:00: 翌週の draft 週を作成し、現行の DIVISION 構成で課題曲を先行抽選する
 *    （管理者が開始までの間に差し替え・再抽選できる）。
 *  - 日曜 21:00: {@link #closeWeek} が active 週を締め、順位を凍結して昇降格を確定する。
 *  - 月曜 15:00: {@link #activateWeek} が draft 週を編成して active 化し、ベースラインを
 *    スナップショットする。課題曲はこの瞬間（= 開始と同時）にプレイヤーへ公開される。
 *    参加の締切もこの瞬間で、途中参加はできない（週の途中の join は次週から）。
 *
 * DIVISION は固定 11 階級（{@link LeagueDivision}: LEGEND=0, 1..10）。階級の併合・
 * 振り直しは行わず、昇降格はこの範囲内での ±1 移動のみ。初回配属は join 時に
 * BEAT-TIER から決まる（{@link LeagueService#join}）。
 *
 * 冪等性: 週の二重作成は (ladder_type, starts_at) のユニーク制約と存在チェックで防ぎ、
 * 締め/開始はステータス遷移でガードされるため、cron と管理者の手動トリガーが重複しても安全。
 * cron の起動は {@code LeagueScheduler} が担う（自己呼び出しで {@code @Transactional} が
 * 効かなくなるのを避けるため、本サービスには @Scheduled を付けない）。
 */
@Service
public class LeagueWeekLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(LeagueWeekLifecycleService.class);

    /**
     * グループの定員。ポイント制の増減幅（8 人で +4〜-4）が定義される最大人数なので、
     * これを超える DIVISION は必ず複数グループへ分割する。
     */
    static final int GROUP_CAPACITY = 8;
    /**
     * 単独で卓を成立させる最小人数。これ未満の DIVISION は最寄りの成立卓へ吸収される
     * （格上の卓ならチャレンジ、格下ならディフェンス）。
     */
    static final int MIN_STANDALONE = 4;
    /** 連続でこの週数「有効曲 0」が続いたエントリーは自動休止する。 */
    static final int AUTO_DEACTIVATE_AFTER = 3;

    /** 週の開始時刻（JST・月曜）。 */
    static final int START_HOUR = 15;
    /** 週の終了時刻（JST・日曜）。 */
    static final int END_HOUR = 21;

    private final LeagueEntryRepository leagueEntryRepository;
    private final LeagueWeekRepository leagueWeekRepository;
    private final LeagueMemberRepository leagueMemberRepository;
    private final LeagueSongRepository leagueSongRepository;
    private final LeagueBaselineRepository leagueBaselineRepository;
    private final ScoreRepository scoreRepository;
    private final LeagueStandingsService standingsService;
    private final LeagueSongDrawService songDrawService;

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     */
    public LeagueWeekLifecycleService(LeagueEntryRepository leagueEntryRepository,
                                      LeagueWeekRepository leagueWeekRepository,
                                      LeagueMemberRepository leagueMemberRepository,
                                      LeagueSongRepository leagueSongRepository,
                                      LeagueBaselineRepository leagueBaselineRepository,
                                      ScoreRepository scoreRepository,
                                      LeagueStandingsService standingsService,
                                      LeagueSongDrawService songDrawService) {
        this.leagueEntryRepository = leagueEntryRepository;
        this.leagueWeekRepository = leagueWeekRepository;
        this.leagueMemberRepository = leagueMemberRepository;
        this.leagueSongRepository = leagueSongRepository;
        this.leagueBaselineRepository = leagueBaselineRepository;
        this.scoreRepository = scoreRepository;
        this.standingsService = standingsService;
        this.songDrawService = songDrawService;
    }

    /**
     * 【メソッドの役割】 翌週の draft 週を作成し、課題曲を先行抽選する。
     *
     * 既に draft 週があればそれを返すだけ（冪等）。開始日時は最新週の翌週の月曜 15:00 を
     * 基本とし、長期間止まっていた場合は直近の月曜 15:00 まで飛ばす（欠けた週は開催しない）。
     * 課題曲は「active エントリーの現在の DIVISION 構成」で抽選する（開始時と構成が
     * 変わり得る分は activate 時に過不足調整される）。
     *
     * @param ladder ラダー種別
     * @return 作成（または既存）の draft 週。同一開始日時の週が既にある場合は null
     */
    @Transactional
    public LeagueWeek createDraftWeek(String ladder) {
        LeagueWeek existingDraft = leagueWeekRepository
                .findFirstByLadderTypeAndStatusOrderByStartsAtDesc(ladder, "draft").orElse(null);
        if (existingDraft != null) {
            return existingDraft;
        }

        LocalDateTime upcoming = upcomingStartJst();
        LeagueWeek latest = leagueWeekRepository.findFirstByLadderTypeOrderByStartsAtDesc(ladder).orElse(null);
        LocalDateTime startsAt;
        if (latest == null) {
            startsAt = upcoming;
        } else {
            LocalDateTime next = latest.getStartsAt().plusWeeks(1);
            startsAt = next.isAfter(upcoming) ? next : upcoming; // 長期停止時は直近の週へキャッチアップ
        }
        if (leagueWeekRepository.existsByLadderTypeAndStartsAt(ladder, startsAt)) {
            return null;
        }

        LeagueWeek week = new LeagueWeek();
        week.setLadderType(ladder);
        week.setStartsAt(startsAt);
        week.setEndsAt(endOfWeek(startsAt));
        week.setStatus("draft");
        week = leagueWeekRepository.save(week);

        // active エントリーの現 DIVISION 構成で課題曲を先行抽選（管理者のレビュー期間を作る）
        Map<Integer, List<User>> usersByTier = leagueEntryRepository.findByLadderTypeAndActiveTrue(ladder).stream()
                .filter(e -> LeagueDivision.isValid(e.getCurrentTier()))
                .collect(Collectors.groupingBy(LeagueEntry::getCurrentTier,
                        Collectors.mapping(LeagueEntry::getUser, Collectors.toList())));
        for (Map.Entry<Integer, List<User>> e : usersByTier.entrySet()) {
            songDrawService.drawSongsForTier(week, e.getKey(), e.getValue());
        }
        log.info("リーグ draft 週を作成: ladder={} startsAt={} divisions={}", ladder, startsAt, usersByTier.keySet());
        return week;
    }

    /**
     * 【メソッドの役割】 active 週を締める（日曜 21:00 の処理）。
     *
     * 全グループの順位を確定して {@link LeagueMember} に凍結し、順位に応じた
     * ポイント増減を {@link LeagueEntry} に反映する。累積 ±{@code POINT_CAP} 到達で
     * 昇降格し、DIVISION が変わったらポイントは 0 にリセット。移動先が無い場合
     * （LEGEND のプラス超過・DIVISION 10 のマイナス超過）は範囲内にクランプして保持する。
     * 有効 0 曲の連続週数を数えて自動休止も行う。
     *
     * @param ladder ラダー種別
     * @return 締めた週。active 週が無ければ null
     */
    @Transactional
    public LeagueWeek closeWeek(String ladder) {
        LeagueWeek active = leagueWeekRepository
                .findFirstByLadderTypeAndStatusOrderByStartsAtDesc(ladder, "active").orElse(null);
        if (active == null) {
            return null;
        }

        List<LeagueMember> members = leagueMemberRepository.findByWeek(active);
        // (tier, groupIndex) の組ごとに順位を計算して凍結する
        Set<String> groups = members.stream()
                .map(m -> m.getTier() + ":" + m.getGroupIndex())
                .collect(Collectors.toCollection(TreeSet::new));
        Map<Long, LeagueMember> byUserId = members.stream()
                .collect(Collectors.toMap(m -> m.getUser().getId(), m -> m));

        for (String g : groups) {
            String[] parts = g.split(":");
            int tier = Integer.parseInt(parts[0]);
            int groupIndex = Integer.parseInt(parts[1]);
            List<Map<String, Object>> standings = standingsService.computeGroupStandings(active, tier, groupIndex);
            for (Map<String, Object> row : standings) {
                LeagueMember member = byUserId.get((Long) row.get("userId"));
                if (member == null) continue;
                member.setFinalRank((Integer) row.get("rank"));
                member.setValidSongs((Integer) row.get("validSongs"));
                member.setResultValue((Double) row.get("resultValue"));
                member.setMovement((String) row.get("zone"));
                member.setPointDelta((Integer) row.get("pointDelta"));
            }
        }
        leagueMemberRepository.saveAll(members);

        // ポイント増減・昇降格・自動休止をエントリーへ反映する
        for (LeagueMember member : members) {
            LeagueEntry entry = leagueEntryRepository
                    .findByUserAndLadderType(member.getUser(), ladder).orElse(null);
            if (entry == null) continue;

            // 昇降格はホーム DIVISION を ±1 する（チャレンジ/ディフェンスで卓が異なっても、
            // 昇降格するのは自分のホーム。2 段階の昇降格は起きない = 常に ±1）。
            int homeTier = member.getHomeTier() != null ? member.getHomeTier() : member.getTier();
            int delta = member.getPointDelta() != null ? member.getPointDelta() : 0;
            int oldPoints = entry.getPoints() != null ? entry.getPoints() : 0;
            if ("promote".equals(member.getMovement())) {
                entry.setCurrentTier(Math.max(LeagueDivision.LEGEND, homeTier - 1));
                entry.setPoints(0); // DIVISION が変わったらポイントはリセット
            } else if ("relegate".equals(member.getMovement())) {
                entry.setCurrentTier(Math.min(LeagueDivision.LOWEST, homeTier + 1));
                entry.setPoints(0);
            } else {
                entry.setCurrentTier(homeTier);
                // 移動なし: 累積を ±POINT_CAP にクランプして保持
                // （LEGEND のプラス超過・DIVISION 10 のマイナス超過はここで頭打ちになる）
                entry.setPoints(Math.max(-LeagueStandingsService.POINT_CAP,
                        Math.min(LeagueStandingsService.POINT_CAP, oldPoints + delta)));
            }

            if (member.getValidSongs() != null && member.getValidSongs() == 0) {
                int inactive = (entry.getInactiveWeeks() != null ? entry.getInactiveWeeks() : 0) + 1;
                entry.setInactiveWeeks(inactive);
                if (inactive >= AUTO_DEACTIVATE_AFTER) {
                    entry.setActive(false);
                }
            } else {
                entry.setInactiveWeeks(0);
            }
            leagueEntryRepository.save(entry);
        }

        active.setStatus("closed");
        leagueWeekRepository.save(active);
        log.info("リーグ週を締め: ladder={} weekId={} members={}", ladder, active.getId(), members.size());
        return active;
    }

    /**
     * 【メソッドの役割】 draft 週を編成して開始する（月曜 15:00 の処理）。
     *
     * この瞬間の active エントリーが参加者として確定する（= 途中参加不可の締切）。
     * DIVISION は固定制のため振り直しは行わず、エントリーの currentTier のまま配置する。
     * active 化と同時に課題曲が公開され、ベースライン（週内プレー判定の基準値）を
     * スナップショットする。参加者が 0 人の場合は draft のまま何もしない。
     *
     * @param ladder ラダー種別
     * @return active 化した週。参加者不在などで開始しなかった場合は null
     */
    @Transactional
    public LeagueWeek activateWeek(String ladder) {
        LeagueWeek week = createDraftWeek(ladder);
        if (week == null || !"draft".equals(week.getStatus())) {
            return null;
        }
        List<LeagueEntry> entries = leagueEntryRepository.findByLadderTypeAndActiveTrue(ladder);
        if (entries.isEmpty()) {
            return null;
        }

        // DIVISION の確定: 通常は join 時に設定済み。欠けている場合のみ BEAT-TIER から補完する。
        for (LeagueEntry e : entries) {
            if (!LeagueDivision.isValid(e.getCurrentTier())) {
                e.setCurrentTier(LeagueDivision.forBeatPt(
                        e.getUser().getTotalBeatPt() != null ? e.getUser().getTotalBeatPt() : 0.0));
            }
        }
        leagueEntryRepository.saveAll(entries);

        // --- グループ分割とメンバー配置（DIVISION ごと） ---
        // 編成やり直し（手動トリガーの再実行など）で古い配置が残らないよう先に掃除する。
        leagueMemberRepository.deleteByWeek(week);
        leagueBaselineRepository.deleteByWeek(week);

        // --- 少人数 DIVISION の合流（チャレンジ / ディフェンス） ---
        // ホーム DIVISION ごとの人数を数え、MIN_STANDALONE 未満の DIVISION は最も近い成立卓
        // （人数 >= MIN_STANDALONE の DIVISION）へ吸収する。格上の卓に入ればチャレンジ、格下ならディフェンス。
        Map<Integer, Long> countByTier = entries.stream()
                .collect(Collectors.groupingBy(LeagueEntry::getCurrentTier, Collectors.counting()));
        Set<Integer> anchors = countByTier.entrySet().stream()
                .filter(en -> en.getValue() >= MIN_STANDALONE)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));

        Map<Integer, Integer> hostOf = new HashMap<>();  // ホーム DIVISION → 着席する卓(host)
        Map<Integer, String> roleOf = new HashMap<>();   // ホーム DIVISION → 立場(role)
        for (Integer t : countByTier.keySet()) {
            if (anchors.isEmpty() || anchors.contains(t)) {
                hostOf.put(t, t);
                roleOf.put(t, "normal");
            } else {
                int host = nearestAnchor(t, anchors, countByTier);
                hostOf.put(t, host);
                roleOf.put(t, host < t ? "challenge" : "defense"); // 卓が格上(小さい tier) = 挑戦
            }
        }

        // 卓(host)ごとにプールを作る（吸収されたメンバーも含める）。
        Map<Integer, List<LeagueEntry>> byHost = new TreeMap<>();
        for (LeagueEntry e : entries) {
            byHost.computeIfAbsent(hostOf.get(e.getCurrentTier()), k -> new ArrayList<>()).add(e);
        }

        // --- グループ分割とメンバー配置（卓ごと） ---
        // グループ分けは実力（BEAT-PT）を一切考慮しない完全ランダム。BEAT-PT を参照するのは
        // 初回の DIVISION 配属だけで、同一 DIVISION（卓）内のどのグループに入るかは毎週シャッフルで決める。
        List<LeagueMember> newMembers = new ArrayList<>();
        for (Map.Entry<Integer, List<LeagueEntry>> he : byHost.entrySet()) {
            int host = he.getKey();
            List<LeagueEntry> pool = new ArrayList<>(he.getValue());
            Collections.shuffle(pool); // 完全ランダムなグループ割り
            int n = pool.size();
            // グループ数は「各グループが定員(8)以下になる最小数」= ceil(n/8)。
            // シャッフル済みプールをラウンドロビンで配るため、グループサイズは必ず均等（差は最大 1）に
            // なり、極端に人数の少ないグループを作らない。例: 9 人 → 2 グループ [5, 4]、17 人 → [6, 6, 5]。
            int groupCount = (int) Math.ceil((double) n / GROUP_CAPACITY);
            for (int i = 0; i < n; i++) {
                int groupIndex = i % groupCount; // ラウンドロビン配分（メンバーはシャッフル済み＝ランダム）
                LeagueEntry e = pool.get(i);
                LeagueMember member = new LeagueMember();
                member.setWeek(week);
                member.setUser(e.getUser());
                member.setTier(host);
                member.setHomeTier(e.getCurrentTier());
                member.setRole(roleOf.get(e.getCurrentTier()));
                member.setGroupIndex(groupIndex);
                newMembers.add(member);
            }
        }
        leagueMemberRepository.saveAll(newMembers);

        // --- 課題曲の過不足調整 ---
        // draft 抽選時と編成時で DIVISION 構成が変わり得る（昇降格・新規参加による出現/消滅）。
        Map<Integer, List<LeagueSong>> songsByTier = leagueSongRepository
                .findByWeekOrderByTierAscSlotAsc(week).stream()
                .collect(Collectors.groupingBy(LeagueSong::getTier));
        Map<Integer, List<User>> usersByTier = newMembers.stream()
                .collect(Collectors.groupingBy(LeagueMember::getTier,
                        Collectors.mapping(LeagueMember::getUser, Collectors.toList())));
        for (Map.Entry<Integer, List<User>> e : usersByTier.entrySet()) {
            List<LeagueSong> songs = songsByTier.get(e.getKey());
            if (songs == null || songs.size() < LeagueSongDrawService.SONGS_PER_WEEK) {
                songDrawService.drawSongsForTier(week, e.getKey(), e.getValue());
            }
        }
        for (Integer songTier : songsByTier.keySet()) {
            if (!usersByTier.containsKey(songTier)) {
                leagueSongRepository.deleteByWeekAndTier(week, songTier);
            }
        }

        // --- active 化とベースラインスナップショット（この瞬間に課題曲が公開される） ---
        week.setStatus("active");
        week.setSnapshotAt(LocalDateTime.now());
        leagueWeekRepository.save(week);
        snapshotBaselines(week, newMembers);

        log.info("リーグ週を開始: ladder={} weekId={} 卓={} members={}",
                ladder, week.getId(), byHost.keySet(), newMembers.size());
        return week;
    }

    /**
     * 【メソッドの役割】 少人数 DIVISION({@code t}) を吸収する最寄りの成立卓（anchor）を返す。
     *
     * 距離 1, 2, ... と広げながら格上（小さい tier）・格下（大きい tier）の両方向を探す。
     * 同距離に両方あれば人数の多い方（同数なら格上＝挑戦側）を選ぶ。見つからなければ自分自身。
     *
     * @param t            吸収される DIVISION
     * @param anchors      成立卓（>= MIN_STANDALONE）の DIVISION 集合
     * @param countByTier  DIVISION ごとの人数
     * @return 着席する卓の DIVISION
     */
    private int nearestAnchor(int t, Set<Integer> anchors, Map<Integer, Long> countByTier) {
        for (int d = 1; d <= 10; d++) {
            boolean up = anchors.contains(t - d);    // 格上（小さい tier）
            boolean down = anchors.contains(t + d);  // 格下（大きい tier）
            if (up && down) {
                long cu = countByTier.getOrDefault(t - d, 0L);
                long cd = countByTier.getOrDefault(t + d, 0L);
                return cu >= cd ? t - d : t + d;
            }
            if (up) return t - d;
            if (down) return t + d;
        }
        return t;
    }

    /**
     * 【メソッドの役割】 締めと開始をまとめて実行する（初回ブートストラップ・手動復旧・テスト用）。
     *
     * 通常運用では cron が {@link #closeWeek}（日曜 21:00）と {@link #activateWeek}（月曜 15:00）を
     * 個別に呼ぶが、管理者の手動トリガーは両方まとめて進められた方が扱いやすい。
     *
     * @param ladder ラダー種別
     * @return 処理結果サマリ（closedWeekId / activatedWeekId / memberCount）
     */
    @Transactional
    public Map<String, Object> runWeekly(String ladder) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ladder", ladder);
        LeagueWeek closed = closeWeek(ladder);
        summary.put("closedWeekId", closed != null ? closed.getId() : null);
        LeagueWeek activated = activateWeek(ladder);
        summary.put("activatedWeekId", activated != null ? activated.getId() : null);
        if (activated != null) {
            summary.put("memberCount", leagueMemberRepository.findByWeek(activated).size());
        }
        return summary;
    }

    /**
     * 全メンバーの課題曲の現在スコアを {@link LeagueBaseline} として保存する。
     * 同一 (譜面, source) に difficultyLevel 違いの重複行があり得るため、ベスト値に集約して 1 行にする。
     */
    private void snapshotBaselines(LeagueWeek week, List<LeagueMember> members) {
        Map<Integer, List<LeagueSong>> songsByTier = leagueSongRepository
                .findByWeekOrderByTierAscSlotAsc(week).stream()
                .collect(Collectors.groupingBy(LeagueSong::getTier));

        List<LeagueBaseline> baselines = new ArrayList<>();
        for (LeagueMember member : members) {
            List<LeagueSong> songs = songsByTier.getOrDefault(member.getTier(), List.of());
            if (songs.isEmpty()) continue;
            List<String> titles = songs.stream().map(LeagueSong::getTitle).distinct().toList();
            List<String> diffs = songs.stream().map(LeagueSong::getDifficultyName).distinct().toList();

            // (title|diff|source) → 集約ベースライン。title IN × diff IN の直積で余分な行が
            // 返り得るため (title, difficultyName) の完全一致で絞る。
            // リーグはアーケード記録限定のため、INFINITAS 行はスナップショットしない。
            Map<String, LeagueBaseline> merged = new LinkedHashMap<>();
            for (Score s : scoreRepository.findByUserAndTitlesAndDifficulties(member.getUser(), titles, diffs)) {
                boolean isTargetChart = songs.stream().anyMatch(ls ->
                        ls.getTitle().equals(s.getTitle()) && ls.getDifficultyName().equals(s.getDifficultyName()));
                if (!isTargetChart) continue;
                String source = s.getSource() != null ? s.getSource() : "arcade";
                if (!"arcade".equals(source)) continue;
                String key = s.getTitle() + "|" + s.getDifficultyName() + "|" + source;
                LeagueBaseline b = merged.get(key);
                if (b == null) {
                    b = new LeagueBaseline();
                    b.setWeek(week);
                    b.setUser(member.getUser());
                    b.setTitle(s.getTitle());
                    b.setDifficultyName(s.getDifficultyName());
                    b.setSource(source);
                    merged.put(key, b);
                }
                if (s.getScore() != null && (b.getBaseScore() == null || s.getScore() > b.getBaseScore())) {
                    b.setBaseScore(s.getScore());
                }
                if (s.getMissCount() != null && (b.getBaseMiss() == null || s.getMissCount() < b.getBaseMiss())) {
                    b.setBaseMiss(s.getMissCount());
                }
                if (s.getPlayCount() != null && (b.getBasePlayCount() == null || s.getPlayCount() > b.getBasePlayCount())) {
                    b.setBasePlayCount(s.getPlayCount());
                }
                if (LeagueChartNotation.clearTypeRank(s.getClearType())
                        > LeagueChartNotation.clearTypeRank(b.getBaseClearType())) {
                    b.setBaseClearType(s.getClearType());
                }
            }
            baselines.addAll(merged.values());
        }
        leagueBaselineRepository.saveAll(baselines);
    }

    /**
     * 次に開始すべき週の「月曜 15:00 JST」を返す。
     *
     * - 月曜 15:00 前（同日早朝を含む）: 今日を含む週の月曜 15:00（= まだ始まっていない今週分）
     * - それ以降（開催中〜日曜 21:00 後の空白時間）: 翌週の月曜 15:00
     */
    private LocalDateTime upcomingStartJst() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Tokyo"));
        LocalDate monday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDateTime mondayStart = monday.atStartOfDay().withHour(START_HOUR);
        return now.isBefore(mondayStart) ? mondayStart : mondayStart.plusWeeks(1);
    }

    /** 週の終了日時（開始と同じ週の日曜 21:00）を返す。 */
    private LocalDateTime endOfWeek(LocalDateTime startsAt) {
        return startsAt.plusDays(6).withHour(END_HOUR).withMinute(0).withSecond(0).withNano(0);
    }
}
