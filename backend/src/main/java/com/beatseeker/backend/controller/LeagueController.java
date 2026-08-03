package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.*;
import com.beatseeker.backend.repository.*;
import com.beatseeker.backend.service.LeagueService;
import com.beatseeker.backend.service.LeagueStandingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 【クラスの役割】 リーグモードのプレイヤー向け REST コントローラ。
 *
 * スコアリーグ / BP リーグへの参加・離脱、進行中の週の課題曲と順位表の取得、
 * 過去週の成績履歴の取得を提供する。
 *
 * 認可方針: 全エンドポイントで要ログイン（SecurityConfig で /api/league/** を
 * authenticated に設定）。順位表は参加者の displayName と<b>その週に有効になったリザルト
 * （ラインを超えて着順に乗った記録）の EX・スコアレート</b>を、同じくログイン済みの閲覧者へ
 * 公開する（リーグはオプトイン参加のため、参加 = 掲載同意とみなす。参加 UI とルール説明にも
 * その旨を明記する）。未達の自己ベスト（週内に更新していない過去の記録）は本人以外には
 * 返さない（{@link #stripUnvalidatedScores}）。
 *
 * 主なエンドポイント:
 *  - GET  /api/league/me         … 自分の両ラダーのエントリー状態
 *  - POST /api/league/join       … 参加（または休止から復帰）
 *  - POST /api/league/leave      … 離脱（休止）
 *  - GET  /api/league/current    … 進行中の週・自分のグループの課題曲と順位表
 *  - GET  /api/league/standings  … 任意グループの順位表（観戦・過去週）
 *  - GET  /api/league/overview   … 進行中の週の階級/グループ構成
 *  - GET  /api/league/history    … 自分の過去週成績
 */
@RestController
@RequestMapping("/api/league")
public class LeagueController {

    private final UserRepository userRepository;
    private final LeagueService leagueService;
    private final LeagueStandingsService standingsService;
    private final LeagueWeekRepository leagueWeekRepository;
    private final LeagueMemberRepository leagueMemberRepository;
    private final LeagueSongRepository leagueSongRepository;

    public LeagueController(UserRepository userRepository,
                            LeagueService leagueService,
                            LeagueStandingsService standingsService,
                            LeagueWeekRepository leagueWeekRepository,
                            LeagueMemberRepository leagueMemberRepository,
                            LeagueSongRepository leagueSongRepository) {
        this.userRepository = userRepository;
        this.leagueService = leagueService;
        this.standingsService = standingsService;
        this.leagueWeekRepository = leagueWeekRepository;
        this.leagueMemberRepository = leagueMemberRepository;
        this.leagueSongRepository = leagueSongRepository;
    }

    /**
     * 【メソッドの役割】 自分の両ラダーのエントリー状態を返す。
     *
     * @param auth 認証情報
     * @return {@code {entries: [{ladderType, currentTier, active, inactiveWeeks}]}}
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication auth) {
        User user = getUser(auth);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "ログインが必要です"));
        }
        List<Map<String, Object>> entries = leagueService.myEntries(user).stream()
                .map(this::toEntryMap)
                .toList();
        return ResponseEntity.ok(Map.of("entries", entries));
    }

    /**
     * 【メソッドの役割】 指定ラダーへ参加（または休止から復帰）する。
     *
     * 反映は次回の週次編成から。進行中の週には追加されない。
     *
     * @param auth 認証情報
     * @param req  {@code {"ladderType": "score" | "bp"}}
     * @return 更新後のエントリー
     */
    @PostMapping("/join")
    public ResponseEntity<?> join(Authentication auth, @RequestBody LadderRequest req) {
        User user = getUser(auth);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "ログインが必要です"));
        }
        if (req == null || !leagueService.isValidLadder(req.ladderType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "ladderType は score / bp のいずれかです"));
        }
        if (leagueService.isRegistrationLocked()) {
            return ResponseEntity.badRequest().body(Map.of("error", leagueService.registrationLockMessage()));
        }
        LeagueEntry entry = leagueService.join(user, req.ladderType());
        return ResponseEntity.ok(Map.of("message", "参加を受け付けました。次回の週次編成から反映されます。",
                "entry", toEntryMap(entry)));
    }

    /**
     * 【メソッドの役割】 指定ラダーから離脱（休止）する。
     *
     * 進行中の週のメンバーシップはそのまま残り、次回編成から外れる。
     *
     * @param auth 認証情報
     * @param req  {@code {"ladderType": "score" | "bp"}}
     * @return 処理結果メッセージ
     */
    @PostMapping("/leave")
    public ResponseEntity<?> leave(Authentication auth, @RequestBody LadderRequest req) {
        User user = getUser(auth);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "ログインが必要です"));
        }
        if (req == null || !leagueService.isValidLadder(req.ladderType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "ladderType は score / bp のいずれかです"));
        }
        if (leagueService.isRegistrationLocked()) {
            return ResponseEntity.badRequest().body(Map.of("error", leagueService.registrationLockMessage()));
        }
        boolean left = leagueService.leave(user, req.ladderType());
        if (!left) {
            return ResponseEntity.status(404).body(Map.of("error", "このラダーには参加していません"));
        }
        return ResponseEntity.ok(Map.of("message", "離脱しました。次回の週次編成から外れます。"));
    }

    /**
     * 【メソッドの役割】 進行中の週の情報（課題曲・自分のグループの順位表）を返す。
     *
     * 未参加（今週メンバーでない）の場合は最下位階級の課題曲をプレビューとして返し、
     * 順位表は null にする。
     *
     * @param auth   認証情報
     * @param ladder ラダー種別（"score" / "bp"）
     * @return {@code {week, entry, member, songs, standings}}
     */
    @GetMapping("/current")
    public ResponseEntity<?> current(Authentication auth, @RequestParam("ladder") String ladder) {
        User user = getUser(auth);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "ログインが必要です"));
        }
        if (!leagueService.isValidLadder(ladder)) {
            return ResponseEntity.badRequest().body(Map.of("error", "ladder は score / bp のいずれかです"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        LeagueEntry entry = leagueService.myEntries(user).stream()
                .filter(e -> ladder.equals(e.getLadderType()))
                .findFirst().orElse(null);
        result.put("entry", entry != null ? toEntryMap(entry) : null);

        LeagueWeek week = leagueWeekRepository
                .findFirstByLadderTypeAndStatusOrderByStartsAtDesc(ladder, "active").orElse(null);
        if (week == null) {
            result.put("week", null);
            result.put("member", null);
            result.put("songs", List.of());
            result.put("standings", null);
            return ResponseEntity.ok(result);
        }
        result.put("week", toWeekMap(week));

        LeagueMember member = leagueMemberRepository.findByWeekAndUser(week, user).orElse(null);
        if (member != null) {
            // 有効になったリザルト（ラインを超えて着順に乗った記録）は EX を全員ぶん公開する。
            // 未達の自己ベストは競技結果ではないので本人以外には返さない（stripUnvalidatedScores）。
            List<Map<String, Object>> standings =
                    standingsService.computeGroupStandings(week, member.getTier(), member.getGroupIndex());
            stripUnvalidatedScores(standings, user.getId());
            Map<String, Object> memberInfo = new LinkedHashMap<>();
            memberInfo.put("tier", member.getTier());              // 着席した卓（ホスト）の DIVISION
            memberInfo.put("groupIndex", member.getGroupIndex());
            memberInfo.put("homeTier", member.getHomeTier() != null ? member.getHomeTier() : member.getTier());
            memberInfo.put("role", member.getRole() != null ? member.getRole() : "normal");
            result.put("member", memberInfo);
            result.put("songs", songMapsWithLines(week, member.getTier(), member.getGroupIndex()));
            result.put("standings", standings);
        } else {
            // 未参加者には「自分の BEAT-TIER 相当 DIVISION」の課題曲をプレビュー表示する。
            // その DIVISION が今週開催されていない場合は、開催中で最も近い DIVISION の曲を出す。
            int myDivision = com.beatseeker.backend.service.LeagueDivision.forBeatPt(user.getTotalBeatPt());
            Integer previewTier = leagueSongRepository.findByWeekOrderByTierAscSlotAsc(week).stream()
                    .map(LeagueSong::getTier)
                    .distinct()
                    .min(Comparator.comparingInt(t -> Math.abs(t - myDivision)))
                    .orElse(null);
            result.put("member", null);
            result.put("previewTier", previewTier);
            // 課題曲はグループごとに異なるため、プレビューは代表としてグループ 0 の曲を表示する。
            result.put("songs", previewTier != null ? songMaps(week, previewTier, 0) : List.of());
            result.put("standings", null);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 任意グループの順位表を返す（観戦・過去週の閲覧用）。
     *
     * @param weekId     週 ID
     * @param tier       階級
     * @param groupIndex グループ番号
     * @return {@code {week, songs, standings}}。週が無ければ 404
     */
    @GetMapping("/standings")
    public ResponseEntity<?> standings(Authentication auth,
                                       @RequestParam("weekId") Long weekId,
                                       @RequestParam("tier") Integer tier,
                                       @RequestParam("groupIndex") Integer groupIndex) {
        LeagueWeek week = leagueWeekRepository.findById(weekId).orElse(null);
        if (week == null) {
            return ResponseEntity.status(404).body(Map.of("error", "指定した週が見つかりません"));
        }
        // 観戦（他グループ閲覧）でも同じ条件: 有効なリザルトの EX は見せ、未達の自己ベストは伏せる。
        List<Map<String, Object>> standings = standingsService.computeGroupStandings(week, tier, groupIndex);
        User viewer = getUser(auth);
        stripUnvalidatedScores(standings, viewer != null ? viewer.getId() : null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("week", toWeekMap(week));
        result.put("songs", songMapsWithLines(week, tier, groupIndex));
        result.put("standings", standings);
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 進行中の週の階級/グループ構成と課題曲の一覧を返す。
     *
     * 他グループの順位表閲覧（観戦）の入り口に使う。
     *
     * @param ladder ラダー種別
     * @return {@code {week, tiers: [{tier, songs, groups: [{groupIndex, memberCount}]}]}}
     */
    @GetMapping("/overview")
    public ResponseEntity<?> overview(@RequestParam("ladder") String ladder) {
        if (!leagueService.isValidLadder(ladder)) {
            return ResponseEntity.badRequest().body(Map.of("error", "ladder は score / bp のいずれかです"));
        }
        LeagueWeek week = leagueWeekRepository
                .findFirstByLadderTypeAndStatusOrderByStartsAtDesc(ladder, "active").orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        if (week == null) {
            result.put("week", null);
            result.put("tiers", List.of());
            return ResponseEntity.ok(result);
        }
        result.put("week", toWeekMap(week));
        result.put("tiers", buildTierOverview(week));
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 自分の過去週の成績履歴を新しい順に返す。
     *
     * @param auth   認証情報
     * @param ladder ラダー種別
     * @return {@code [{weekId, startsAt, endsAt, tier, groupIndex, finalRank, movement, validSongs, resultValue}]}
     */
    @GetMapping("/history")
    public ResponseEntity<?> history(Authentication auth, @RequestParam("ladder") String ladder) {
        User user = getUser(auth);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "ログインが必要です"));
        }
        if (!leagueService.isValidLadder(ladder)) {
            return ResponseEntity.badRequest().body(Map.of("error", "ladder は score / bp のいずれかです"));
        }
        List<Map<String, Object>> history = leagueMemberRepository.findClosedHistory(user, ladder).stream()
                .map(m -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("weekId", m.getWeek().getId());
                    row.put("startsAt", m.getWeek().getStartsAt());
                    row.put("endsAt", m.getWeek().getEndsAt());
                    row.put("tier", m.getTier());
                    row.put("groupIndex", m.getGroupIndex());
                    row.put("finalRank", m.getFinalRank());
                    row.put("movement", m.getMovement());
                    row.put("pointDelta", m.getPointDelta());
                    row.put("validSongs", m.getValidSongs());
                    row.put("resultValue", m.getResultValue());
                    return row;
                })
                .toList();
        return ResponseEntity.ok(history);
    }

    // ---------------------------------------------------------------------
    // 内部ヘルパー
    // ---------------------------------------------------------------------

    /** 認証情報からユーザーを解決する。未認証・不在なら null。 */
    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        if (!(principal instanceof String iidxId)) return null;
        return userRepository.findByIidxId(iidxId).orElse(null);
    }

    /** エントリーをレスポンス用 Map に変換する。 */
    private Map<String, Object> toEntryMap(LeagueEntry entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ladderType", entry.getLadderType());
        m.put("currentTier", entry.getCurrentTier());
        m.put("points", entry.getPoints() != null ? entry.getPoints() : 0);
        m.put("active", entry.getActive() != null ? entry.getActive() : false);
        m.put("inactiveWeeks", entry.getInactiveWeeks() != null ? entry.getInactiveWeeks() : 0);
        return m;
    }

    /** 週をレスポンス用 Map に変換する。 */
    private Map<String, Object> toWeekMap(LeagueWeek week) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", week.getId());
        m.put("ladderType", week.getLadderType());
        m.put("startsAt", week.getStartsAt());
        m.put("endsAt", week.getEndsAt());
        m.put("status", week.getStatus());
        return m;
    }

    /** 指定週・階級・グループの課題曲をレスポンス用 Map リストに変換する。 */
    private List<Map<String, Object>> songMaps(LeagueWeek week, Integer tier, Integer groupIndex) {
        return leagueSongRepository.findByWeekAndTierAndGroupIndexOrderBySlotAsc(week, tier, groupIndex).stream()
                .map(this::toSongMap)
                .toList();
    }

    /**
     * 【メソッドの役割】 順位表から「自分以外のメンバーの未有効スコア」を除去する。
     *
     * リーグで公開するのは「その週にラインを超えて有効になったリザルト」だけ。有効な曲の
     * {@code bestEx / rate} は競技結果としてそのまま返し、未達の記録（＝週内に更新していない
     * ただの自己ベスト）は本人以外には返さない。ライン（グループ共通の閾値）は課題曲側で
     * 公開している情報なので触らない。
     *
     * @param standings 順位表（各行の perSong を破壊的に編集する）
     * @param viewerId  閲覧者のユーザー ID（この行は自分の記録なので残す。null なら全員に適用）
     */
    @SuppressWarnings("unchecked")
    private void stripUnvalidatedScores(List<Map<String, Object>> standings, Long viewerId) {
        if (standings == null) return;
        for (Map<String, Object> row : standings) {
            Object uid = row.get("userId");
            boolean isViewer = viewerId != null && uid instanceof Number
                    && ((Number) uid).longValue() == viewerId;
            if (isViewer) continue;
            Object perSong = row.get("perSong");
            if (!(perSong instanceof List<?> list)) continue;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> ps)) continue;
                Map<String, Object> m = (Map<String, Object>) ps;
                if (Boolean.TRUE.equals(m.get("valid"))) continue; // 有効なリザルトは公開する
                m.remove("bestEx");
                m.remove("rate");
                m.remove("bestMiss");
            }
        }
    }

    /**
     * 【メソッドの役割】 課題曲一覧に、指定グループの「ライン」（週開始時点の最高記録）を slot 単位で
     * 合成して返す。観戦（他グループ閲覧）でもライン（グループ共通の閾値）を表示できるようにする。
     *
     * @param week       対象週
     * @param tier       階級
     * @param groupIndex グループ番号
     * @return 課題曲マップのリスト（各要素に lineEx / lineMiss / lineRate を追加）
     */
    private List<Map<String, Object>> songMapsWithLines(LeagueWeek week, Integer tier, int groupIndex) {
        List<Map<String, Object>> songs = new ArrayList<>();
        for (Map<String, Object> s : songMaps(week, tier, groupIndex)) {
            songs.add(new LinkedHashMap<>(s)); // toList() は不変なので可変コピーにする
        }
        Map<Object, Map<String, Object>> lineBySlot = new HashMap<>();
        for (Map<String, Object> l : standingsService.computeGroupSongLines(week, tier, groupIndex)) {
            lineBySlot.put(l.get("slot"), l);
        }
        for (Map<String, Object> s : songs) {
            Map<String, Object> l = lineBySlot.get(s.get("slot"));
            if (l != null) {
                s.put("lineEx", l.get("lineEx"));
                s.put("lineMiss", l.get("lineMiss"));
                s.put("lineRate", l.get("lineRate"));
            }
        }
        return songs;
    }

    /** 課題曲をレスポンス用 Map に変換する。 */
    private Map<String, Object> toSongMap(LeagueSong song) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", song.getId());
        m.put("tier", song.getTier());
        m.put("slot", song.getSlot());
        m.put("title", song.getTitle());
        m.put("difficultyName", song.getDifficultyName());
        m.put("level", song.getLevel());
        m.put("notes", song.getNotes());
        return m;
    }

    /** 進行中の週の階級/グループ構成（メンバー数と課題曲）を組み立てる。 */
    private List<Map<String, Object>> buildTierOverview(LeagueWeek week) {
        List<LeagueMember> members = leagueMemberRepository.findByWeek(week);
        Map<Integer, List<LeagueMember>> byTier = members.stream()
                .collect(Collectors.groupingBy(LeagueMember::getTier, TreeMap::new, Collectors.toList()));
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (Map.Entry<Integer, List<LeagueMember>> e : byTier.entrySet()) {
            Map<Integer, Long> groupCounts = e.getValue().stream()
                    .collect(Collectors.groupingBy(LeagueMember::getGroupIndex, TreeMap::new, Collectors.counting()));
            List<Map<String, Object>> groups = groupCounts.entrySet().stream()
                    .map(g -> {
                        Map<String, Object> gm = new LinkedHashMap<String, Object>();
                        gm.put("groupIndex", g.getKey());
                        gm.put("memberCount", g.getValue());
                        return gm;
                    })
                    .toList();
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("tier", e.getKey());
            // 課題曲はグループごとに異なるため、階級単位の曲リストは持たない
            // （フロントはグループを開いて /standings から課題曲を取得する）。
            tm.put("songs", List.of());
            tm.put("groups", groups);
            tiers.add(tm);
        }
        return tiers;
    }

    /** 参加・離脱リクエストのボディ。 */
    public record LadderRequest(String ladderType) {
    }
}
