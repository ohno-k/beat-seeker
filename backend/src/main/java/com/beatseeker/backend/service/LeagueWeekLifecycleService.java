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
     * 単独で卓を成立させる最小人数（3 人以上で卓成立）。これ未満（&lt;3）の DIVISION は
     * 少人数同士で束ねるか、最寄りの成立卓へ吸収される（格上=チャレンジ / 格下=ディフェンス）。
     */
    static final int MIN_STANDALONE = 3;
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
    private final SongDefinitionRepository songDefinitionRepository;

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
                                      LeagueSongDrawService songDrawService,
                                      SongDefinitionRepository songDefinitionRepository) {
        this.leagueEntryRepository = leagueEntryRepository;
        this.leagueWeekRepository = leagueWeekRepository;
        this.leagueMemberRepository = leagueMemberRepository;
        this.leagueSongRepository = leagueSongRepository;
        this.leagueBaselineRepository = leagueBaselineRepository;
        this.scoreRepository = scoreRepository;
        this.standingsService = standingsService;
        this.songDrawService = songDrawService;
        this.songDefinitionRepository = songDefinitionRepository;
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

        // 課題曲はグループごと（各グループの参加者の実力に合わせて）抽選するため、
        // グループ確定前の draft 段階では抽選しない。編成（activateWeek）時にグループ単位で抽選する。
        log.info("リーグ draft 週を作成: ladder={} startsAt={}", ladder, startsAt);
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
     * 【メソッドの役割】 draft 週を「開始せずに」編成だけ確定する（参加締切後の事前確認用）。
     *
     * この瞬間の active エントリーで卓・グループ・課題曲を確定して draft 週に保存するが、
     * status は draft のまま・ベースラインも取らない（＝プレイヤーには未公開）。管理者は
     * 開始（月曜 15:00）までの間に、実際に使われる編成と課題曲を overview / 順位表で確認し、
     * 必要なら課題曲を差し替え・再抽選できる。押すたびに組み直す（{@link #formWeek} が掃除する）。
     *
     * <p>{@link #activateWeek} はこの事前編成があればそれをそのまま使う（再抽選しない）。
     * 参加締切（{@code app.league.signup-close}）でロスターがロックされている前提で使う。
     *
     * @param ladder ラダー種別
     * @return 編成した draft 週。参加者不在・draft 週が無いなどで編成しなかった場合は null
     */
    @Transactional
    public LeagueWeek formDraft(String ladder) {
        LeagueWeek week = createDraftWeek(ladder);
        if (week == null || !"draft".equals(week.getStatus())) {
            return null;
        }
        List<LeagueEntry> entries = leagueEntryRepository.findByLadderTypeAndActiveTrue(ladder);
        if (entries.isEmpty()) {
            return null;
        }
        formWeek(week, entries);
        return week;
    }

    /**
     * 【メソッドの役割】 まだ編成されていない draft 週を自動で編成する（参加締切後の cron 用）。
     *
     * {@link #formDraft} と違い、<b>既に編成済みなら何もしない</b>（管理者が手動で編成・調整した
     * 結果を自動編成が上書きしないようにする）。参加締切〜開始の窓で複数回呼ばれても安全（冪等）。
     *
     * @param ladder ラダー種別
     * @return 自動編成した draft 週。既編成・draft 無し・参加者 0 で編成しなかった場合は null
     */
    @Transactional
    public LeagueWeek autoFormDraft(String ladder) {
        LeagueWeek draft = leagueWeekRepository
                .findFirstByLadderTypeAndStatusOrderByStartsAtDesc(ladder, "draft").orElse(null);
        if (draft != null && !leagueMemberRepository.findByWeek(draft).isEmpty()) {
            return null; // 既に編成済み（手動編成・調整含む）→ 上書きしない
        }
        return formDraft(ladder);
    }

    /**
     * 【メソッドの役割】 draft 週を編成して開始する（月曜 15:00 の処理）。
     *
     * この瞬間の active エントリーが参加者として確定する（= 途中参加不可の締切）。
     * ただし {@link #formDraft} で事前編成済みの場合はそれをそのまま使う（再抽選しない）ため、
     * 管理者が締切後に確認・調整した編成がそのまま開始される。未編成ならその場で編成する。
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

        // 事前編成（formDraft）済みならそれをそのまま使う。未編成ならこの場で編成する。
        List<LeagueMember> members = leagueMemberRepository.findByWeek(week);
        if (members.isEmpty()) {
            List<LeagueEntry> entries = leagueEntryRepository.findByLadderTypeAndActiveTrue(ladder);
            if (entries.isEmpty()) {
                return null;
            }
            members = formWeek(week, entries);
        }

        // --- active 化とベースラインスナップショット（この瞬間に課題曲が公開される） ---
        week.setStatus("active");
        week.setSnapshotAt(LocalDateTime.now());
        leagueWeekRepository.save(week);
        snapshotBaselines(week, members);

        log.info("リーグ週を開始: ladder={} weekId={} members={}", ladder, week.getId(), members.size());
        return week;
    }

    /**
     * 【メソッドの役割】 進行中(active)の週を「開始前」の状態に戻す（誤って開始した場合の取り消し）。
     *
     * 対象週の status を draft に戻し、開始時刻(snapshotAt)を消し、編成物（メンバー・課題曲・
     * ベースライン）を削除して空の draft にする。開始(activation)では PT/DIVISION は一切変化しない
     * ため、エントリーには触れない（＝この取り消しは順位・昇降格に影響しない）。空 draft に戻るので、
     * 通常のスケジュール（自動編成→開始）がそのまま正しく走る。
     *
     * <p><b>注意:</b> 週を締めて（{@link #closeWeek}）昇降格を確定した後には使えない
     * （締めで既に PT/DIVISION が変わっているため、この取り消しでは戻せない）。締め済みの週は
     * closed になっていて active では無いので、この処理の対象にはならない。
     *
     * @param ladder ラダー種別
     * @return 取り消した週。取り消せる active 週が無ければ null
     */
    @Transactional
    public LeagueWeek abortWeek(String ladder) {
        LeagueWeek active = leagueWeekRepository
                .findFirstByLadderTypeAndStatusOrderByStartsAtDesc(ladder, "active").orElse(null);
        if (active == null) {
            return null;
        }
        leagueBaselineRepository.deleteByWeek(active);
        leagueSongRepository.deleteByWeek(active);
        leagueMemberRepository.deleteByWeek(active);
        active.setStatus("draft");
        active.setSnapshotAt(null);
        leagueWeekRepository.save(active);
        log.info("リーグ週を中止(draft へ差し戻し): ladder={} weekId={}", ladder, active.getId());
        return active;
    }

    /**
     * 【メソッドの役割】 現在の参加者で draft 週の卓・グループ・課題曲を確定して保存する（編成の中核）。
     *
     * {@link #formDraft}（事前確認）と {@link #activateWeek}（開始）の両方から使う共通処理。
     * status の変更やベースライン取得は行わない（それらは activateWeek の責務）。再実行に備えて
     * 既存のメンバー・ベースライン・課題曲を先に掃除してから組み直す（グループ数が変わっても残らない）。
     *
     * @param week    対象 draft 週
     * @param entries その週の参加者（active エントリー）
     * @return 保存した {@link LeagueMember} 一覧
     */
    private List<LeagueMember> formWeek(LeagueWeek week, List<LeagueEntry> entries) {
        // DIVISION の確定: 通常は join 時に設定済み。欠けている場合のみ BEAT-TIER から補完する。
        for (LeagueEntry e : entries) {
            if (!LeagueDivision.isValid(e.getCurrentTier())) {
                e.setCurrentTier(LeagueDivision.forBeatPt(
                        e.getUser().getTotalBeatPt() != null ? e.getUser().getTotalBeatPt() : 0.0));
            }
        }
        leagueEntryRepository.saveAll(entries);

        // --- グループ分割とメンバー配置（DIVISION ごと） ---
        // 編成やり直し（事前編成の押し直し・手動トリガーの再実行など）で古い配置や課題曲が
        // 残らないよう先に掃除する（グループ数が減っても孤立した課題曲を残さない）。
        leagueMemberRepository.deleteByWeek(week);
        leagueBaselineRepository.deleteByWeek(week);
        leagueSongRepository.deleteByWeek(week);
        // 削除を先に DB へ流す。Hibernate は flush 時に INSERT を DELETE より先に実行するため、
        // 同じ参加者を入れ直す組み直しでユニーク制約 (week_id, user_id) に衝突してしまう。
        leagueMemberRepository.flush();

        // --- 少人数 DIVISION の合流（チャレンジ / ディフェンス） ---
        // ホーム DIVISION ごとの人数を数え、卓(host)と立場(role)を決める。
        // 3 人以上は単独卓。少人数は「隣接する少人数同士を束ねて 3 人以上になれば独立卓を作る」
        // ／束ねても 4 に満たなければ最寄りの成立卓へ吸収する。詳細は computeHostAndRole 参照。
        Map<Integer, Long> countByTier = entries.stream()
                .collect(Collectors.groupingBy(LeagueEntry::getCurrentTier, Collectors.counting()));
        Map<Integer, Integer> hostOf = new HashMap<>();  // ホーム DIVISION → 着席する卓(host)
        Map<Integer, String> roleOf = new HashMap<>();   // ホーム DIVISION → 立場(role)
        computeHostAndRole(countByTier, hostOf, roleOf);

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
            List<List<LeagueEntry>> groups = splitPoolIntoGroups(he.getValue());
            for (int gi = 0; gi < groups.size(); gi++) {
                for (LeagueEntry e : groups.get(gi)) {
                    LeagueMember member = new LeagueMember();
                    member.setWeek(week);
                    member.setUser(e.getUser());
                    member.setTier(host);
                    member.setHomeTier(e.getCurrentTier());
                    member.setRole(roleOf.get(e.getCurrentTier()));
                    member.setGroupIndex(gi);
                    newMembers.add(member);
                }
            }
        }
        leagueMemberRepository.saveAll(newMembers);

        // --- 課題曲の抽選（グループごと） ---
        // 各グループの参加者の実力に合わせて 3 曲ずつ抽選する（drawSongsForGroup）。
        // グループ確定後にしか選べないため、draft 段階では抽選せずここで一括抽選する。
        Map<String, List<User>> usersByGroup = new LinkedHashMap<>();
        Map<String, int[]> groupTierIndex = new LinkedHashMap<>(); // key -> {tier, groupIndex}
        for (LeagueMember m : newMembers) {
            String key = m.getTier() + "-" + m.getGroupIndex();
            usersByGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(m.getUser());
            groupTierIndex.putIfAbsent(key, new int[]{ m.getTier(), m.getGroupIndex() });
        }
        for (Map.Entry<String, List<User>> e : usersByGroup.entrySet()) {
            int[] tg = groupTierIndex.get(e.getKey());
            songDrawService.drawSongsForGroup(week, tg[0], tg[1], e.getValue());
        }

        log.info("リーグ週を編成: ladder={} weekId={} 卓={} members={}",
                week.getLadderType(), week.getId(), byHost.keySet(), newMembers.size());
        return newMembers;
    }

    /**
     * 【メソッドの役割】 卓のプールを完全ランダムにグループへ分割する。
     *
     * 定員 {@link #GROUP_CAPACITY}（8）以下になる最小グループ数 = ceil(n/8) を作り、シャッフル済み
     * プールをラウンドロビンで配る（サイズは均等・差は最大 1。例: 9 人 → [5,4]、17 人 → [6,6,5]）。
     * 本編成（{@link #activateWeek}）と仮編成（{@link #previewFormation}）で同じアルゴリズムを使う。
     *
     * @param pool 卓（host）に属する参加者
     * @return グループのリスト（インデックス = groupIndex）
     */
    private List<List<LeagueEntry>> splitPoolIntoGroups(List<LeagueEntry> pool) {
        List<LeagueEntry> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled);
        int n = shuffled.size();
        int groupCount = Math.max(1, (int) Math.ceil((double) n / GROUP_CAPACITY));
        List<List<LeagueEntry>> groups = new ArrayList<>();
        for (int g = 0; g < groupCount; g++) groups.add(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            groups.get(i % groupCount).add(shuffled.get(i)); // ラウンドロビン配分
        }
        return groups;
    }

    /**
     * 【メソッドの役割】 DIVISION ごとの人数から、各 DIVISION の「着席する卓(host)」と「立場(role)」を決める。
     *
     * <ul>
     *   <li>3 人以上（{@link #MIN_STANDALONE}）の DIVISION はそのまま単独卓（role=normal）。</li>
     *   <li>少人数（&lt;4）の DIVISION は、アンカー（成立卓）で区切られた「連続した少人数区間(gap)」ごとに
     *       {@link #assignGap} で処理する。区間内で上位から人数を積み上げ、3 人に達したら
     *       「その塊の中で人数最多の DIVISION をホストにした 1 卓」を成立させる（少人数同士を束ねる）。
     *       束ねても 4 に満たない端数は、既存アンカーがあれば最寄りへ吸収、無ければ直前の卓へ合流。</li>
     * </ul>
     * 立場は host より上位(小さい tier)から来た人=ディフェンス、下位(大きい tier)から来た人=チャレンジ。
     *
     * @param countByTier DIVISION ごとの人数
     * @param hostOf      （出力）ホーム DIVISION → 着席する卓(host)
     * @param roleOf      （出力）ホーム DIVISION → 立場(normal/challenge/defense)
     */
    private void computeHostAndRole(Map<Integer, Long> countByTier,
                                    Map<Integer, Integer> hostOf, Map<Integer, String> roleOf) {
        Set<Integer> anchors = countByTier.entrySet().stream()
                .filter(en -> en.getValue() >= MIN_STANDALONE)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
        List<Integer> tiers = new ArrayList<>(new TreeSet<>(countByTier.keySet())); // 昇順（上位→下位）

        int i = 0;
        while (i < tiers.size()) {
            int t = tiers.get(i);
            if (anchors.contains(t)) {          // 3 人以上 = 単独卓
                hostOf.put(t, t);
                roleOf.put(t, "normal");
                i++;
                continue;
            }
            // アンカーで区切られた「連続する少人数 DIVISION」の区間を集める
            List<Integer> gap = new ArrayList<>();
            while (i < tiers.size() && !anchors.contains(tiers.get(i))) {
                gap.add(tiers.get(i));
                i++;
            }
            assignGap(gap, countByTier, anchors, hostOf, roleOf);
        }
    }

    /**
     * 【メソッドの役割】 連続する少人数 DIVISION（gap）を卓に割り当てる。
     *
     * 上位（区間の先頭）から人数を積み上げ、合計が {@link #MIN_STANDALONE} に達するごとに 1 卓を成立させる
     * （少人数同士を束ねて卓を作る）。ホストは束ねた中で人数最多の DIVISION（同数なら下位側）。
     * 端数（4 に満たない残り）は、既存アンカーがあれば最寄りへ吸収、無ければ直前に作った卓へ合流、
     * それも無ければ端数だけで 1 卓にする。
     */
    private void assignGap(List<Integer> gap, Map<Integer, Long> count, Set<Integer> anchors,
                           Map<Integer, Integer> hostOf, Map<Integer, String> roleOf) {
        List<List<Integer>> tables = new ArrayList<>();
        List<Integer> acc = new ArrayList<>();
        long accTotal = 0;
        for (int t : gap) {
            acc.add(t);
            accTotal += count.getOrDefault(t, 0L);
            if (accTotal >= MIN_STANDALONE) {
                tables.add(new ArrayList<>(acc));
                acc.clear();
                accTotal = 0;
            }
        }
        if (!acc.isEmpty()) { // 4 に満たない端数
            if (!anchors.isEmpty()) {
                for (int u : acc) {
                    int h = nearestAnchor(u, anchors, count);
                    hostOf.put(u, h);
                    roleOf.put(u, h < u ? "challenge" : "defense");
                }
            } else if (!tables.isEmpty()) {
                tables.get(tables.size() - 1).addAll(acc); // 直前の卓へ合流
            } else {
                tables.add(new ArrayList<>(acc)); // 区間全体が 4 未満・アンカー無し → そのまま 1 卓
            }
        }
        // 成立した各卓を割り当て（ホスト＝人数最多、同数なら tier が大きい＝下位側）
        for (List<Integer> table : tables) {
            int host = table.get(0);
            long best = -1;
            for (int u : table) {
                long cn = count.getOrDefault(u, 0L);
                if (cn > best || (cn == best && u > host)) {
                    best = cn;
                    host = u;
                }
            }
            for (int u : table) {
                hostOf.put(u, host);
                roleOf.put(u, u == host ? "normal" : (host < u ? "challenge" : "defense"));
            }
        }
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
     * 【メソッドの役割】 現在の active エントリーで「仮編成」を組み、各グループの課題曲候補と
     * 参加者の自己ベスト（＋ライン）を計算して返す（<b>DB には一切書き込まない</b>）。
     *
     * 週の締切が無く事前に確定編成を用意できないため、管理者が「編成・選曲・各選手の到達度」を
     * 目視確認するためのテスト用プレビュー。グループ割りと選曲はランダムなので、実際の開始時
     * （{@link #activateWeek}）とは変わり得る（あくまで一例のスナップショット）。ライン＝グループ内の
     * 各課題曲のアーケード自己ベスト最高値（{@code lineEx}）で、その値を持つ選手のセルを強調表示できる。
     *
     * @param ladder ラダー種別
     * @return {@code {ladder, entryCount, tiers:[{host, groups:[{groupIndex, memberCount, songs:[...], players:[...]}]}]}}
     */
    @Transactional(readOnly = true)
    public Map<String, Object> previewFormation(String ladder) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ladder", ladder);
        List<LeagueEntry> entries = leagueEntryRepository.findByLadderTypeAndActiveTrue(ladder);
        result.put("entryCount", entries.size());
        if (entries.isEmpty()) {
            result.put("tiers", List.of());
            return result;
        }

        // ホーム DIVISION を確定（不正・未設定は BEAT-TIER から補完）。
        // readOnly トランザクションだが、managed entity を汚さないようローカル map に持つ。
        Map<Long, Integer> tierOf = new HashMap<>();
        for (LeagueEntry e : entries) {
            int t = LeagueDivision.isValid(e.getCurrentTier())
                    ? e.getCurrentTier()
                    : LeagueDivision.forBeatPt(e.getUser().getTotalBeatPt() != null ? e.getUser().getTotalBeatPt() : 0.0);
            tierOf.put(e.getUser().getId(), t);
        }

        // 卓(host)/立場(role)の決定（本編成と同じロジック）。
        Map<Integer, Long> countByTier = tierOf.values().stream()
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));
        Map<Integer, Integer> hostOf = new HashMap<>();
        Map<Integer, String> roleOf = new HashMap<>();
        computeHostAndRole(countByTier, hostOf, roleOf);

        Map<Integer, List<LeagueEntry>> byHost = new TreeMap<>();
        for (LeagueEntry e : entries) {
            byHost.computeIfAbsent(hostOf.get(tierOf.get(e.getUser().getId())), k -> new ArrayList<>()).add(e);
        }

        LocalDateTime refStart = upcomingStartJst();
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (Map.Entry<Integer, List<LeagueEntry>> he : byHost.entrySet()) {
            int host = he.getKey();
            List<List<LeagueEntry>> groups = splitPoolIntoGroups(he.getValue());
            // 同一階級の他グループと課題曲が重複しないよう、選んだタイトルを積み上げて除外していく
            // （本抽選は保存済み課題曲を DB 経由で除外するが、保存しないプレビューでは明示的に渡す）。
            Set<String> usedInTier = new HashSet<>();
            List<Map<String, Object>> groupList = new ArrayList<>();
            for (int gi = 0; gi < groups.size(); gi++) {
                List<LeagueEntry> g = groups.get(gi);
                List<User> users = g.stream().map(LeagueEntry::getUser).toList();
                List<SongDefinition> songs = songDrawService.selectSongsForGroup(host, users, refStart, usedInTier);
                for (SongDefinition sd : songs) usedInTier.add(sd.getTitle());
                groupList.add(buildPreviewGroup(gi, g, songs, roleOf, tierOf));
            }
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("host", host);
            tm.put("memberCount", he.getValue().size());
            tm.put("groups", groupList);
            tiers.add(tm);
        }
        result.put("tiers", tiers);
        return result;
    }

    /**
     * 仮編成の 1 グループ分（課題曲メタ＋各選手の自己ベスト＋ライン）を組み立てる。
     *
     * ライン（{@code lineEx}）＝グループ内のアーケード自己ベスト最高 EX。各選手セルの
     * {@code isLine} が true ＝その選手がラインを持っている（＝強調表示対象）。未プレーは {@code played=false}。
     */
    private Map<String, Object> buildPreviewGroup(int groupIndex, List<LeagueEntry> members,
                                                  List<SongDefinition> songs,
                                                  Map<Integer, String> roleOf, Map<Long, Integer> tierOf) {
        int slots = songs.size();
        List<String> titles = songs.stream().map(SongDefinition::getTitle).distinct().toList();
        List<String> diffs = songs.stream()
                .map(sd -> LeagueChartNotation.codeToName(sd.getDifficulty())).distinct().toList();

        // 表示は名前順で安定させる。
        List<LeagueEntry> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing(e -> {
            String n = e.getUser().getDisplayName();
            return n != null ? n : "";
        }, String.CASE_INSENSITIVE_ORDER));

        int[] lineEx = new int[slots];            // 各スロットのライン（最高 EX、未プレーのみなら 0）
        List<int[]> exRows = new ArrayList<>();    // 選手ごとのスロット別 EX（-1 = 未プレー）
        for (LeagueEntry e : sorted) {
            int[] exBySlot = new int[slots];
            Arrays.fill(exBySlot, -1);
            if (!titles.isEmpty()) {
                Map<String, Integer> best = new HashMap<>();
                for (Score s : scoreRepository.findByUserAndTitlesAndDifficulties(e.getUser(), titles, diffs)) {
                    if (s.getSource() != null && !"arcade".equals(s.getSource())) continue; // アーケード限定
                    if (s.getScore() == null || s.getScore() <= 0) continue;
                    best.merge(s.getTitle() + "|" + s.getDifficultyName(), s.getScore(), Math::max);
                }
                for (int i = 0; i < slots; i++) {
                    SongDefinition sd = songs.get(i);
                    Integer ex = best.get(sd.getTitle() + "|" + LeagueChartNotation.codeToName(sd.getDifficulty()));
                    if (ex != null) {
                        exBySlot[i] = ex;
                        if (ex > lineEx[i]) lineEx[i] = ex;
                    }
                }
            }
            exRows.add(exBySlot);
        }

        // 選手行（ライン確定後に isLine を付与）。
        List<Map<String, Object>> players = new ArrayList<>();
        for (int p = 0; p < sorted.size(); p++) {
            LeagueEntry e = sorted.get(p);
            int[] exBySlot = exRows.get(p);
            int homeTier = tierOf.get(e.getUser().getId());
            List<Map<String, Object>> bests = new ArrayList<>();
            for (int i = 0; i < slots; i++) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("slot", i + 1);
                if (exBySlot[i] >= 0) {
                    int ex = exBySlot[i];
                    cell.put("ex", ex);
                    cell.put("rate", roundRate(ex, songs.get(i).getNotes()));
                    cell.put("isLine", lineEx[i] > 0 && ex == lineEx[i]);
                    cell.put("played", true);
                } else {
                    cell.put("ex", null);
                    cell.put("rate", null);
                    cell.put("isLine", false);
                    cell.put("played", false);
                }
                bests.add(cell);
            }
            Map<String, Object> pm = new LinkedHashMap<>();
            // userId は「このプレビューをそのまま draft へ適用する」（applyPreview）ときの同定に使う。
            pm.put("userId", e.getUser().getId());
            pm.put("displayName", e.getUser().getDisplayName() != null ? e.getUser().getDisplayName() : "");
            pm.put("homeTier", homeTier);
            pm.put("role", roleOf.getOrDefault(homeTier, "normal"));
            pm.put("bests", bests);
            players.add(pm);
        }

        // 課題曲メタ（ライン付き）。
        List<Map<String, Object>> songMeta = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            SongDefinition sd = songs.get(i);
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("slot", i + 1);
            sm.put("title", sd.getTitle());
            sm.put("difficultyName", LeagueChartNotation.codeToName(sd.getDifficulty()));
            sm.put("level", sd.getLevel());
            sm.put("notes", sd.getNotes());
            sm.put("lineEx", lineEx[i] > 0 ? lineEx[i] : null);
            sm.put("lineRate", lineEx[i] > 0 ? roundRate(lineEx[i], sd.getNotes()) : null);
            songMeta.add(sm);
        }

        Map<String, Object> gm = new LinkedHashMap<>();
        gm.put("groupIndex", groupIndex);
        gm.put("memberCount", members.size());
        gm.put("songs", songMeta);
        gm.put("players", players);
        return gm;
    }

    // ---------------------------------------------------------------------
    // 仮編成プレビューの適用（プレビューで見た編成をそのまま draft 週に保存する）
    // ---------------------------------------------------------------------

    /** 適用リクエストの課題曲指定（タイトル＋難易度名）。level / notes は active マスタから取り直す。 */
    public record PreviewSongRef(String title, String difficultyName) {}

    /** 適用リクエストの 1 グループ（メンバーの userId と課題曲）。 */
    public record PreviewGroupRef(Integer groupIndex, List<Long> userIds, List<PreviewSongRef> songs) {}

    /** 適用リクエストの 1 卓（host DIVISION とそのグループ）。 */
    public record PreviewTierRef(Integer host, List<PreviewGroupRef> groups) {}

    /**
     * 【メソッドの役割】 管理者が確認した仮編成（{@link #previewFormation} の結果）を draft 週へ適用する。
     *
     * プレビューは DB を更新しないため、そのまま採用したい場合にこのメソッドで確定させる。
     * 既存の編成物（メンバー・課題曲・ベースライン）は削除して、渡された編成で置き換える。
     *
     * <p>プレビュー生成後に参加者が増減していると編成が実態とずれるため、
     * <b>渡された参加者集合が現在の active エントリーと完全一致しない場合は適用しない</b>
     * （少人数 DIVISION の合流結果が変わっている場合も同様）。この場合はプレビューを
     * 作り直してから適用する。ホーム DIVISION・立場（チャレンジ / ディフェンス）は
     * クライアントの値を信用せず、現在のエントリーから再計算する。
     *
     * @param ladder ラダー種別
     * @param tiers  適用する編成（卓 → グループ → メンバー・課題曲）
     * @return 適用した draft 週
     * @throws IllegalArgumentException 編成データが不正（曲が引けない・グループ重複など）
     * @throws IllegalStateException    draft 週が無い・参加者が居ない・プレビューが古い
     */
    @Transactional
    public LeagueWeek applyPreview(String ladder, List<PreviewTierRef> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException("適用する編成データがありません");
        }
        LeagueWeek week = createDraftWeek(ladder);
        if (week == null || !"draft".equals(week.getStatus())) {
            throw new IllegalStateException("編成を適用できる draft 週がありません");
        }
        List<LeagueEntry> entries = leagueEntryRepository.findByLadderTypeAndActiveTrue(ladder);
        if (entries.isEmpty()) {
            throw new IllegalStateException("参加者が居ません");
        }

        // ホーム DIVISION の確定（formWeek と同じ補完）。
        for (LeagueEntry e : entries) {
            if (!LeagueDivision.isValid(e.getCurrentTier())) {
                e.setCurrentTier(LeagueDivision.forBeatPt(
                        e.getUser().getTotalBeatPt() != null ? e.getUser().getTotalBeatPt() : 0.0));
            }
        }
        leagueEntryRepository.saveAll(entries);
        Map<Long, LeagueEntry> entryByUserId = new LinkedHashMap<>();
        for (LeagueEntry e : entries) {
            entryByUserId.put(e.getUser().getId(), e);
        }

        // --- 検証 1: プレビューの参加者が現在の参加者と完全一致するか（生成後の増減を検出） ---
        List<Long> postedIds = new ArrayList<>();
        for (PreviewTierRef t : tiers) {
            for (PreviewGroupRef g : (t.groups() != null ? t.groups() : List.<PreviewGroupRef>of())) {
                if (g.userIds() != null) postedIds.addAll(g.userIds());
            }
        }
        Set<Long> postedSet = new HashSet<>(postedIds);
        if (postedSet.size() != postedIds.size()) {
            throw new IllegalStateException("同じ参加者が複数のグループに含まれています。プレビューを作り直してください。");
        }
        Set<Long> missing = new HashSet<>(entryByUserId.keySet());
        missing.removeAll(postedSet);
        Set<Long> unknown = new HashSet<>(postedSet);
        unknown.removeAll(entryByUserId.keySet());
        if (!missing.isEmpty() || !unknown.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "プレビューが現在の参加者と一致しません（参加者 %d 人 / プレビューに含まれない %d 人 / 参加していない %d 人）。"
                            + "プレビューを作り直してから適用してください。",
                    entryByUserId.size(), missing.size(), unknown.size()));
        }

        // --- 検証 2: 卓(host)と立場(role)は現在のエントリーから再計算し、プレビューと矛盾しないか確認 ---
        Map<Integer, Long> countByTier = entries.stream()
                .collect(Collectors.groupingBy(LeagueEntry::getCurrentTier, Collectors.counting()));
        Map<Integer, Integer> hostOf = new HashMap<>();
        Map<Integer, String> roleOf = new HashMap<>();
        computeHostAndRole(countByTier, hostOf, roleOf);

        // --- 組み立て（検証がすべて通ってから保存する） ---
        List<LeagueMember> newMembers = new ArrayList<>();
        List<LeagueSong> newSongs = new ArrayList<>();
        Set<String> seenGroups = new HashSet<>();
        for (PreviewTierRef t : tiers) {
            Integer host = t.host();
            if (!LeagueDivision.isValid(host)) {
                throw new IllegalArgumentException("卓の DIVISION が不正です: " + host);
            }
            for (PreviewGroupRef g : (t.groups() != null ? t.groups() : List.<PreviewGroupRef>of())) {
                Integer groupIndex = g.groupIndex();
                if (groupIndex == null || groupIndex < 0) {
                    throw new IllegalArgumentException("グループ番号が不正です: " + groupIndex);
                }
                if (!seenGroups.add(host + "-" + groupIndex)) {
                    throw new IllegalArgumentException(
                            "同じグループが重複しています: DIVISION " + host + " グループ " + (groupIndex + 1));
                }
                List<Long> userIds = g.userIds() != null ? g.userIds() : List.of();
                if (userIds.isEmpty()) {
                    throw new IllegalArgumentException("メンバーが 0 人のグループがあります");
                }
                for (Long userId : userIds) {
                    LeagueEntry e = entryByUserId.get(userId);
                    int homeTier = e.getCurrentTier();
                    if (!Objects.equals(hostOf.get(homeTier), host)) {
                        throw new IllegalStateException(
                                "少人数 DIVISION の合流結果がプレビューと変わっています。プレビューを作り直してから適用してください。");
                    }
                    LeagueMember m = new LeagueMember();
                    m.setWeek(week);
                    m.setUser(e.getUser());
                    m.setTier(host);
                    m.setHomeTier(homeTier);
                    m.setRole(roleOf.get(homeTier));
                    m.setGroupIndex(groupIndex);
                    newMembers.add(m);
                }
                List<PreviewSongRef> songs = g.songs() != null ? g.songs() : List.of();
                if (songs.isEmpty()) {
                    throw new IllegalArgumentException("課題曲が空のグループがあります");
                }
                int slot = 1;
                for (PreviewSongRef s : songs) {
                    newSongs.add(resolveSong(week, host, groupIndex, slot++, s));
                }
            }
        }

        // --- 既存の編成物を掃除して置き換える（formWeek と同じ順序・同じ理由で flush する） ---
        leagueMemberRepository.deleteByWeek(week);
        leagueBaselineRepository.deleteByWeek(week);
        leagueSongRepository.deleteByWeek(week);
        leagueMemberRepository.flush();
        leagueMemberRepository.saveAll(newMembers);
        leagueSongRepository.saveAll(newSongs);

        log.info("仮編成を draft へ適用: ladder={} weekId={} members={} songs={}",
                ladder, week.getId(), newMembers.size(), newSongs.size());
        return week;
    }

    /** プレビューの課題曲指定を active マスタで解決し、保存用の {@link LeagueSong} を組み立てる。 */
    private LeagueSong resolveSong(LeagueWeek week, int tier, int groupIndex, int slot, PreviewSongRef ref) {
        if (ref == null || ref.title() == null || ref.title().isBlank()) {
            throw new IllegalArgumentException("課題曲のタイトルが空です");
        }
        String code = LeagueChartNotation.nameToCode(ref.difficultyName());
        if (code == null) {
            throw new IllegalArgumentException("課題曲の難易度が不正です: " + ref.difficultyName());
        }
        SongDefinition def = songDefinitionRepository
                .findAllByTitleAndDifficultyAndRevision(ref.title().trim(), code, "active").stream()
                .filter(sd -> sd.getNotes() != null && sd.getNotes() > 0)
                .findFirst().orElse(null);
        if (def == null) {
            throw new IllegalArgumentException(
                    "課題曲が active マスタに見つからないか、ノーツ数が未登録です: " + ref.title() + " " + ref.difficultyName());
        }
        LeagueSong song = new LeagueSong();
        song.setWeek(week);
        song.setTier(tier);
        song.setGroupIndex(groupIndex);
        song.setSlot(slot);
        song.setTitle(def.getTitle());
        song.setDifficultyName(LeagueChartNotation.codeToName(def.getDifficulty()));
        song.setLevel(def.getLevel());
        song.setNotes(def.getNotes());
        return song;
    }

    /** EX からスコアレート（%・小数第 2 位）を計算する。notes は課題曲抽選時点のスナップショット。 */
    private double roundRate(int ex, Integer notes) {
        if (notes == null || notes <= 0) return 0.0;
        return Math.round(ex * 100.0 / (notes * 2) * 100.0) / 100.0;
    }

    /**
     * 全メンバーの課題曲の現在スコアを {@link LeagueBaseline} として保存する。
     * 同一 (譜面, source) に difficultyLevel 違いの重複行があり得るため、ベスト値に集約して 1 行にする。
     */
    private void snapshotBaselines(LeagueWeek week, List<LeagueMember> members) {
        // 課題曲はグループ単位なので (tier, groupIndex) でまとめる。
        Map<String, List<LeagueSong>> songsByGroup = leagueSongRepository
                .findByWeekOrderByTierAscSlotAsc(week).stream()
                .collect(Collectors.groupingBy(ls -> ls.getTier() + "-" + ls.getGroupIndex()));

        List<LeagueBaseline> baselines = new ArrayList<>();
        for (LeagueMember member : members) {
            List<LeagueSong> songs = songsByGroup.getOrDefault(
                    member.getTier() + "-" + member.getGroupIndex(), List.of());
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
