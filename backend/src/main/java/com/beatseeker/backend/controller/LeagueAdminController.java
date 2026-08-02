package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.*;
import com.beatseeker.backend.repository.*;
import com.beatseeker.backend.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 【クラスの役割】 リーグモードの管理者向け REST コントローラ。
 *
 * draft 週の課題曲差し替え・再抽選、週次処理の手動トリガー（テスト・初回起動・障害復旧）、
 * エントリー階級の手動修正を提供する。
 *
 * 認可方針: Spring Security 側では「要ログイン」までガードし（/api/league/**）、
 * 管理者判定はここで {@link AdminAuthService} を使って行う（/api/support の管理系と同じ流儀）。
 *
 * 主なエンドポイント:
 *  - GET  /api/league/admin/overview                      … 両ラダーの draft/active 週の状況
 *  - POST /api/league/admin/weeks/{weekId}/songs/{songId}/replace … 課題曲の差し替え（draft のみ）
 *  - POST /api/league/admin/weeks/{weekId}/redraw          … 指定階級の課題曲を再抽選（draft のみ）
 *  - POST /api/league/admin/run-weekly                     … 週次処理の手動実行
 *  - POST /api/league/admin/create-draft                   … draft 週の手動作成
 *  - GET  /api/league/admin/preview                        … 仮編成プレビュー（DB 非更新）
 *  - POST /api/league/admin/preview/apply                  … 仮編成プレビューを draft へ適用
 *  - PUT  /api/league/admin/entries/{entryId}/tier         … エントリー階級の手動修正
 */
@RestController
@RequestMapping("/api/league/admin")
public class LeagueAdminController {

    private final UserRepository userRepository;
    private final AdminAuthService adminAuthService;
    private final LeagueService leagueService;
    private final LeagueWeekLifecycleService lifecycleService;
    private final LeagueSongDrawService songDrawService;
    private final LeagueWeekRepository leagueWeekRepository;
    private final LeagueSongRepository leagueSongRepository;
    private final LeagueMemberRepository leagueMemberRepository;
    private final LeagueEntryRepository leagueEntryRepository;
    private final SongDefinitionRepository songDefinitionRepository;
    private final ScoreRepository scoreRepository;

    public LeagueAdminController(UserRepository userRepository,
                                 AdminAuthService adminAuthService,
                                 LeagueService leagueService,
                                 LeagueWeekLifecycleService lifecycleService,
                                 LeagueSongDrawService songDrawService,
                                 LeagueWeekRepository leagueWeekRepository,
                                 LeagueSongRepository leagueSongRepository,
                                 LeagueMemberRepository leagueMemberRepository,
                                 LeagueEntryRepository leagueEntryRepository,
                                 SongDefinitionRepository songDefinitionRepository,
                                 ScoreRepository scoreRepository) {
        this.userRepository = userRepository;
        this.adminAuthService = adminAuthService;
        this.leagueService = leagueService;
        this.lifecycleService = lifecycleService;
        this.songDrawService = songDrawService;
        this.leagueWeekRepository = leagueWeekRepository;
        this.leagueSongRepository = leagueSongRepository;
        this.leagueMemberRepository = leagueMemberRepository;
        this.leagueEntryRepository = leagueEntryRepository;
        this.songDefinitionRepository = songDefinitionRepository;
        this.scoreRepository = scoreRepository;
    }

    /**
     * 【メソッドの役割】 両ラダーの draft / active 週の状況（課題曲・参加者数）を返す。
     *
     * @param auth 認証情報（管理者限定）
     * @return {@code {ladders: [{ladder, activeEntryCount, draftWeek, activeWeek}]}}
     */
    @GetMapping("/overview")
    public ResponseEntity<?> overview(Authentication auth) {
        if (requireAdmin(auth) == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみアクセスできます"));
        }
        List<Map<String, Object>> ladders = new ArrayList<>();
        for (String ladder : LeagueService.LADDERS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ladder", ladder);
            m.put("activeEntryCount", leagueEntryRepository.findByLadderTypeAndActiveTrue(ladder).size());
            m.put("draftWeek", weekDetail(leagueWeekRepository
                    .findFirstByLadderTypeAndStatusOrderByStartsAtDesc(ladder, "draft").orElse(null)));
            m.put("activeWeek", weekDetail(leagueWeekRepository
                    .findFirstByLadderTypeAndStatusOrderByStartsAtDesc(ladder, "active").orElse(null)));
            ladders.add(m);
        }
        return ResponseEntity.ok(Map.of("ladders", ladders));
    }

    /**
     * 【メソッドの役割】 draft 週の課題曲 1 曲を指定譜面に差し替える。
     *
     * active マスタに存在し notes が判明している譜面のみ許可する
     * （スコアレート計算と週内プレー判定を保証するため）。level / notes は差し替え時に
     * スナップショットし直す。
     *
     * @param auth   認証情報（管理者限定）
     * @param weekId 対象週 ID（draft のみ）
     * @param songId 差し替える課題曲の ID
     * @param req    {@code {"title": "...", "difficultyName": "ANOTHER"}}
     * @return 差し替え後の課題曲
     */
    @PostMapping("/weeks/{weekId}/songs/{songId}/replace")
    @Transactional
    public ResponseEntity<?> replaceSong(Authentication auth,
                                         @PathVariable Long weekId,
                                         @PathVariable Long songId,
                                         @RequestBody ReplaceSongRequest req) {
        if (requireAdmin(auth) == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみアクセスできます"));
        }
        LeagueWeek week = leagueWeekRepository.findById(weekId).orElse(null);
        if (week == null) {
            return ResponseEntity.status(404).body(Map.of("error", "指定した週が見つかりません"));
        }
        if (!"draft".equals(week.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "課題曲を差し替えられるのは draft 週のみです"));
        }
        LeagueSong song = leagueSongRepository.findById(songId).orElse(null);
        if (song == null || !song.getWeek().getId().equals(weekId)) {
            return ResponseEntity.status(404).body(Map.of("error", "指定した課題曲が見つかりません"));
        }
        if (req == null || req.title() == null || req.title().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title は必須です"));
        }
        String code = LeagueChartNotation.nameToCode(req.difficultyName());
        if (code == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "difficultyName は NORMAL / HYPER / ANOTHER / LEGGENDARIA 等で指定してください"));
        }

        // active マスタで実在と notes を検証（重複行があり得るため notes 有りの先頭を使う）
        SongDefinition def = songDefinitionRepository
                .findAllByTitleAndDifficultyAndRevision(req.title().trim(), code, "active").stream()
                .filter(sd -> sd.getNotes() != null && sd.getNotes() > 0)
                .findFirst().orElse(null);
        if (def == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "指定した譜面が active マスタに見つからないか、ノーツ数が未登録です"));
        }

        song.setTitle(def.getTitle());
        song.setDifficultyName(LeagueChartNotation.codeToName(def.getDifficulty()));
        song.setLevel(def.getLevel());
        song.setNotes(def.getNotes());
        leagueSongRepository.save(song);
        return ResponseEntity.ok(Map.of("message", "課題曲を差し替えました", "song", toSongMap(song)));
    }

    /**
     * 【メソッドの役割】 draft 週の指定階級の課題曲 3 曲を再抽選する。
     *
     * レベル帯の算出には現行 active 週の同階級メンバーを使う（draft 週にはまだ
     * メンバーが居ないため）。active 週が無い場合は空メンバー（最下位帯）で抽選する。
     *
     * @param auth   認証情報（管理者限定）
     * @param weekId 対象週 ID（draft のみ）
     * @param tier   階級
     * @return 再抽選後の課題曲 3 曲
     */
    @PostMapping("/weeks/{weekId}/redraw")
    @Transactional
    public ResponseEntity<?> redraw(Authentication auth,
                                    @PathVariable Long weekId,
                                    @RequestParam("tier") Integer tier) {
        if (requireAdmin(auth) == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみアクセスできます"));
        }
        LeagueWeek week = leagueWeekRepository.findById(weekId).orElse(null);
        if (week == null) {
            return ResponseEntity.status(404).body(Map.of("error", "指定した週が見つかりません"));
        }
        if (!"draft".equals(week.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "再抽選できるのは draft 週のみです"));
        }

        // 事前編成済み（formDraft 済み）なら、この週のメンバーをグループ単位で使って
        // グループごとに再抽選する（課題曲はグループ単位なので tier 一括では壊れる）。
        Map<Integer, List<User>> membersByGroup = leagueMemberRepository.findByWeek(week).stream()
                .filter(m -> m.getTier().equals(tier))
                .collect(Collectors.groupingBy(LeagueMember::getGroupIndex,
                        Collectors.mapping(LeagueMember::getUser, Collectors.toList())));

        List<LeagueSong> songs;
        if (!membersByGroup.isEmpty()) {
            songs = new ArrayList<>();
            for (Map.Entry<Integer, List<User>> g : membersByGroup.entrySet()) {
                songs.addAll(songDrawService.drawSongsForGroup(week, tier, g.getKey(), g.getValue()));
            }
        } else {
            // 未編成の draft: 現行 active 週の同階級メンバーで tier 一括抽選（従来動作）。
            List<User> tierMembers = List.of();
            LeagueWeek active = leagueWeekRepository
                    .findFirstByLadderTypeAndStatusOrderByStartsAtDesc(week.getLadderType(), "active").orElse(null);
            if (active != null) {
                tierMembers = leagueMemberRepository.findByWeek(active).stream()
                        .filter(m -> m.getTier().equals(tier))
                        .map(LeagueMember::getUser)
                        .collect(Collectors.toList());
            }
            songs = songDrawService.drawSongsForTier(week, tier, tierMembers);
        }
        return ResponseEntity.ok(Map.of("message", "課題曲を再抽選しました",
                "songs", songs.stream().map(this::toSongMap).toList()));
    }

    /**
     * 【メソッドの役割】 現在の参加者で「仮編成」を組み、各グループの課題曲候補と参加者の
     * 自己ベスト（＋ライン）を返す。<b>DB は一切更新しない</b>読み取り専用のテスト用途。
     *
     * 週の締切が無く事前に確定編成を用意できないため、開始前に編成・選曲・各選手の到達度を
     * 目視確認するために使う。グループ割り・選曲はランダムなので実際の開始時とは変わり得る。
     *
     * @param auth   認証情報（管理者限定）
     * @param ladder ラダー種別
     * @return {@code {ladder, entryCount, tiers:[{host, groups:[{songs, players}]}]}}
     */
    @GetMapping("/preview")
    public ResponseEntity<?> preview(Authentication auth, @RequestParam("ladder") String ladder) {
        if (requireAdmin(auth) == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみアクセスできます"));
        }
        if (!leagueService.isValidLadder(ladder)) {
            return ResponseEntity.badRequest().body(Map.of("error", "ladder は score / bp のいずれかです"));
        }
        return ResponseEntity.ok(lifecycleService.previewFormation(ladder));
    }

    /**
     * 【メソッドの役割】 週次処理（締め → 編成 → 開始）を手動実行する。
     *
     * 初回のリーグ開始（ブートストラップ）や、cron 失敗時の復旧、ローカルでの動作確認に使う。
     * 冪等なので二重実行しても週が二重に作られることはない。
     *
     * @param auth   認証情報（管理者限定）
     * @param ladder ラダー種別
     * @return 処理結果サマリ
     */
    @PostMapping("/run-weekly")
    public ResponseEntity<?> runWeekly(Authentication auth, @RequestParam("ladder") String ladder) {
        if (requireAdmin(auth) == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみアクセスできます"));
        }
        if (!leagueService.isValidLadder(ladder)) {
            return ResponseEntity.badRequest().body(Map.of("error", "ladder は score / bp のいずれかです"));
        }
        Map<String, Object> summary = lifecycleService.runWeekly(ladder);
        return ResponseEntity.ok(summary);
    }

    /**
     * 【メソッドの役割】 draft 週を手動作成する（課題曲の先行抽選込み）。
     *
     * @param auth   認証情報（管理者限定）
     * @param ladder ラダー種別
     * @return 作成（または既存）の draft 週
     */
    @PostMapping("/create-draft")
    public ResponseEntity<?> createDraft(Authentication auth, @RequestParam("ladder") String ladder) {
        if (requireAdmin(auth) == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみアクセスできます"));
        }
        if (!leagueService.isValidLadder(ladder)) {
            return ResponseEntity.badRequest().body(Map.of("error", "ladder は score / bp のいずれかです"));
        }
        LeagueWeek week = lifecycleService.createDraftWeek(ladder);
        if (week == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "draft 週を作成できませんでした（同じ開始日時の週が既に存在します）"));
        }
        return ResponseEntity.ok(Map.of("message", "draft 週を用意しました", "week", weekDetail(week)));
    }

    /**
     * 【メソッドの役割】 参加締切後に、現在の参加者で draft 週の編成（卓・グループ・課題曲）を確定する。
     *
     * 開始（activateWeek）はせず、実際に使われる編成を draft 週へ保存する。管理者は開始
     * （月曜 15:00）までの間に overview / 順位表で確認し、必要なら課題曲を差し替え・再抽選できる。
     * 開始処理はこの事前編成をそのまま使う（再抽選しない）。押すたびに組み直す。
     *
     * @param auth   認証情報（管理者限定）
     * @param ladder ラダー種別
     * @return 編成後の draft 週の詳細
     */
    @PostMapping("/form")
    public ResponseEntity<?> form(Authentication auth, @RequestParam("ladder") String ladder) {
        if (requireAdmin(auth) == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみアクセスできます"));
        }
        if (!leagueService.isValidLadder(ladder)) {
            return ResponseEntity.badRequest().body(Map.of("error", "ladder は score / bp のいずれかです"));
        }
        LeagueWeek week = lifecycleService.formDraft(ladder);
        if (week == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "編成できませんでした（draft 週が無いか、参加者が居ません）"));
        }
        return ResponseEntity.ok(Map.of(
                "message", "編成しました（グループ・課題曲を確定）。開始までに確認・調整できます。",
                "week", weekDetail(week)));
    }

    /**
     * 【メソッドの役割】 誤って開始した開催中(active)の週を取り消し、開始前の空 draft に戻す。
     *
     * 開始(activation)では昇降格 PT・DIVISION は変化しないため、この取り消しは順位・昇降格に
     * 影響しない（編成物＝メンバー・課題曲・ベースラインのみ削除して draft に戻す）。空 draft に
     * 戻るので通常のスケジュール（自動編成 → 開始）がそのまま走る。
     *
     * <p><b>注意:</b> 週を締めて（run-weekly / closeWeek）昇降格を確定した後は取り消せない。
     *
     * @param auth   認証情報（管理者限定）
     * @param ladder ラダー種別
     * @return 取り消した週の詳細
     */
    /**
     * 【メソッドの役割】 仮編成プレビュー（GET /preview）で確認した編成を、そのまま draft 週へ適用する。
     *
     * プレビューは DB を更新しないため、内容を見て採用したい場合にこれで確定させる。既存の編成物
     * （メンバー・課題曲・ベースライン）は削除して置き換える。プレビュー生成後に参加者が増減して
     * いた場合は適用せず 400 を返す（プレビューを作り直してから適用する）。
     *
     * @param auth   認証情報（管理者限定）
     * @param ladder ラダー種別
     * @param req    プレビューの卓・グループ・メンバー(userId)・課題曲
     * @return 適用後の draft 週の詳細
     */
    @PostMapping("/preview/apply")
    public ResponseEntity<?> applyPreview(Authentication auth,
                                          @RequestParam("ladder") String ladder,
                                          @RequestBody ApplyPreviewRequest req) {
        if (requireAdmin(auth) == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみアクセスできます"));
        }
        if (!leagueService.isValidLadder(ladder)) {
            return ResponseEntity.badRequest().body(Map.of("error", "ladder は score / bp のいずれかです"));
        }
        if (req == null || req.tiers() == null || req.tiers().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "適用する編成データがありません"));
        }
        try {
            LeagueWeek week = lifecycleService.applyPreview(ladder, req.tiers());
            return ResponseEntity.ok(Map.of(
                    "message", "プレビューの編成を draft に適用しました（グループ・課題曲を確定）。",
                    "week", weekDetail(week)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/abort")
    public ResponseEntity<?> abort(Authentication auth, @RequestParam("ladder") String ladder) {
        if (requireAdmin(auth) == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみアクセスできます"));
        }
        if (!leagueService.isValidLadder(ladder)) {
            return ResponseEntity.badRequest().body(Map.of("error", "ladder は score / bp のいずれかです"));
        }
        LeagueWeek week = lifecycleService.abortWeek(ladder);
        if (week == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "中止できる開催中(active)の週がありません"));
        }
        return ResponseEntity.ok(Map.of(
                "message", "開催中の週を中止し、開始前の状態に戻しました。",
                "week", weekDetail(week)));
    }

    /**
     * 【メソッドの役割】 エントリーの所属 DIVISION を手動修正する（配属の調整用）。
     *
     * 反映は次回の週次編成から（進行中の週の配置は変わらない）。
     *
     * @param auth    認証情報（管理者限定）
     * @param entryId エントリー ID
     * @param req     {@code {"tier": 2}}（0=DIVISION LEGEND, 1..10=DIVISION 1..10）
     * @return 更新後のエントリー概要
     */
    @PutMapping("/entries/{entryId}/tier")
    @Transactional
    public ResponseEntity<?> updateEntryTier(Authentication auth,
                                             @PathVariable Long entryId,
                                             @RequestBody UpdateTierRequest req) {
        if (requireAdmin(auth) == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみアクセスできます"));
        }
        LeagueEntry entry = leagueEntryRepository.findById(entryId).orElse(null);
        if (entry == null) {
            return ResponseEntity.status(404).body(Map.of("error", "指定したエントリーが見つかりません"));
        }
        if (req == null || !LeagueDivision.isValid(req.tier())) {
            return ResponseEntity.badRequest().body(Map.of("error", "tier は 0 (DIVISION LEGEND) 〜 10 で指定してください"));
        }
        entry.setCurrentTier(req.tier());
        // DIVISION が変わったので昇降格ポイントは初期値に戻す（仕様: 変動後の初期値は 0）
        entry.setPoints(0);
        leagueEntryRepository.save(entry);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", entry.getId());
        m.put("ladderType", entry.getLadderType());
        m.put("currentTier", entry.getCurrentTier());
        m.put("points", entry.getPoints());
        m.put("displayName", entry.getUser().getDisplayName() != null ? entry.getUser().getDisplayName() : "");
        return ResponseEntity.ok(Map.of("message", "DIVISIONを更新しました（ポイントは0にリセット）。次回編成から反映されます。", "entry", m));
    }

    // ---------------------------------------------------------------------
    // 内部ヘルパー
    // ---------------------------------------------------------------------

    /** 認証情報から管理者ユーザーを解決する。未認証・非管理者なら null（呼び出し側で 403）。 */
    private User requireAdmin(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        if (!(principal instanceof String iidxId)) return null;
        User user = userRepository.findByIidxId(iidxId).orElse(null);
        if (user == null || !adminAuthService.isAdmin(user)) return null;
        return user;
    }

    /** 週の詳細（課題曲を階級ごとにまとめたもの）。week が null なら null。 */
    private Map<String, Object> weekDetail(LeagueWeek week) {
        if (week == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", week.getId());
        m.put("ladderType", week.getLadderType());
        m.put("startsAt", week.getStartsAt());
        m.put("endsAt", week.getEndsAt());
        m.put("status", week.getStatus());
        List<LeagueSong> songs = leagueSongRepository.findByWeekOrderByTierAscSlotAsc(week);
        // 卓(tier) → グループ → メンバー。誰がどのグループに入ったかを管理者が確認できるようにする。
        List<LeagueMember> members = leagueMemberRepository.findByWeek(week);

        // draft 週は各グループのライン（＝開始時にベースラインとして凍結される見込み値）と
        // その保持者を課題曲に添えて返す。active/closed 週の確定ラインはベースライン由来なので
        // ここでは計算しない（順位表側が表示する）。
        Map<Long, Map<String, Object>> lineBySongId = "draft".equals(week.getStatus())
                ? computeDraftLines(songs, members)
                : Map.of();
        Map<Integer, List<Map<String, Object>>> songsByTier = songs.stream()
                .collect(Collectors.groupingBy(LeagueSong::getTier, TreeMap::new,
                        Collectors.mapping(s -> toSongMap(s, lineBySongId.get(s.getId())), Collectors.toList())));
        Map<Integer, Map<Integer, List<Map<String, Object>>>> membersByTierGroup = new TreeMap<>();
        for (LeagueMember mem : members) {
            membersByTierGroup
                    .computeIfAbsent(mem.getTier(), k -> new TreeMap<>())
                    .computeIfAbsent(mem.getGroupIndex(), k -> new ArrayList<>())
                    .add(toMemberMap(mem));
        }
        // 表示の安定のため各グループ内は名前順に並べる。
        for (Map<Integer, List<Map<String, Object>>> byGroup : membersByTierGroup.values()) {
            for (List<Map<String, Object>> list : byGroup.values()) {
                list.sort(Comparator.comparing(x -> String.valueOf(x.get("displayName")), String.CASE_INSENSITIVE_ORDER));
            }
        }

        // 課題曲もメンバーも無い階級が落ちないよう、両方のキーを合わせて階級一覧を作る。
        Set<Integer> tierKeys = new TreeSet<>(songsByTier.keySet());
        tierKeys.addAll(membersByTierGroup.keySet());
        List<Map<String, Object>> tiers = tierKeys.stream()
                .map(tier -> {
                    Map<String, Object> tm = new LinkedHashMap<String, Object>();
                    tm.put("tier", tier);
                    tm.put("songs", songsByTier.getOrDefault(tier, List.of()));
                    tm.put("groups", membersByTierGroup.getOrDefault(tier, Map.of()).entrySet().stream()
                            .map(g -> {
                                Map<String, Object> gm = new LinkedHashMap<String, Object>();
                                gm.put("groupIndex", g.getKey());
                                gm.put("members", g.getValue());
                                return gm;
                            })
                            .toList());
                    return tm;
                })
                .toList();
        m.put("tiers", tiers);
        m.put("memberCount", members.size());
        return m;
    }

    /** メンバー 1 人分（誰がどの卓・グループに、どの立場で入ったか）をレスポンス用 Map に変換する。 */
    private Map<String, Object> toMemberMap(LeagueMember member) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", member.getUser().getId());
        m.put("displayName", member.getUser().getDisplayName() != null ? member.getUser().getDisplayName() : "");
        m.put("iidxId", member.getUser().getIidxId());
        m.put("homeTier", member.getHomeTier() != null ? member.getHomeTier() : member.getTier());
        m.put("role", member.getRole() != null ? member.getRole() : "normal");
        return m;
    }

    /**
     * 【メソッドの役割】 draft 週の各課題曲について、グループ内の「ライン」とその保持者を求める。
     *
     * ライン＝そのグループのメンバーが持つアーケード自己ベストの最高 EX。開始（activateWeek）時に
     * ベースラインとして凍結される値と同じ計算（アーケード記録限定・EX &gt; 0）なので、管理者は開始前に
     * 「どの曲が誰の記録でどこまで塞がっているか」を確認できる。同値の保持者が複数居れば全員返す。
     *
     * <p>全メンバー × 全課題曲を 1 クエリで引いてから (tier, groupIndex) 単位に集計する
     * （人数分のクエリを撃たない）。
     *
     * @param songs   週の全課題曲
     * @param members 週の全メンバー
     * @return 課題曲 ID → {@code {lineEx, lineRate, lineHolders:[表示名]}}。ラインが無い曲は含めない
     */
    private Map<Long, Map<String, Object>> computeDraftLines(List<LeagueSong> songs, List<LeagueMember> members) {
        if (songs.isEmpty() || members.isEmpty()) return Map.of();
        List<String> titles = songs.stream().map(LeagueSong::getTitle).distinct().toList();
        List<String> diffs = songs.stream().map(LeagueSong::getDifficultyName).distinct().toList();
        List<User> users = members.stream().map(LeagueMember::getUser).toList();

        // (userId|title|difficultyName) → アーケード自己ベスト EX
        Map<String, Integer> bestByUserSong = new HashMap<>();
        for (Score s : scoreRepository.findByUsersAndTitlesAndDifficulties(users, titles, diffs)) {
            // source 未設定の古い行はアーケード扱い（仮編成プレビュー・順位計算と同じ判定）
            if (s.getSource() != null && !"arcade".equals(s.getSource())) continue;
            if (s.getScore() == null || s.getScore() <= 0) continue;
            bestByUserSong.merge(
                    s.getUser().getId() + "|" + s.getTitle() + "|" + s.getDifficultyName(),
                    s.getScore(), Math::max);
        }

        Map<String, List<LeagueMember>> byGroup = new HashMap<>();
        for (LeagueMember mem : members) {
            byGroup.computeIfAbsent(mem.getTier() + "|" + mem.getGroupIndex(), k -> new ArrayList<>()).add(mem);
        }

        Map<Long, Map<String, Object>> out = new HashMap<>();
        for (LeagueSong song : songs) {
            List<LeagueMember> group = byGroup.get(song.getTier() + "|" + song.getGroupIndex());
            if (group == null) continue;
            int lineEx = 0;
            List<String> holders = new ArrayList<>();
            for (LeagueMember mem : group) {
                Integer ex = bestByUserSong.get(
                        mem.getUser().getId() + "|" + song.getTitle() + "|" + song.getDifficultyName());
                if (ex == null) continue;
                if (ex > lineEx) {
                    lineEx = ex;
                    holders.clear();
                }
                if (ex == lineEx) {
                    String name = mem.getUser().getDisplayName();
                    holders.add(name != null && !name.isBlank() ? name : String.valueOf(mem.getUser().getIidxId()));
                }
            }
            if (lineEx <= 0) continue; // 誰も未プレー = ライン無し（週内に記録を出せばそのまま有効）
            holders.sort(String.CASE_INSENSITIVE_ORDER);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("lineEx", lineEx);
            line.put("lineRate", song.getNotes() != null && song.getNotes() > 0
                    ? Math.round(lineEx * 100.0 / (song.getNotes() * 2) * 100.0) / 100.0
                    : null);
            line.put("lineHolders", holders);
            out.put(song.getId(), line);
        }
        return out;
    }

    /** 課題曲をレスポンス用 Map に変換する（ライン情報があれば併記する）。 */
    private Map<String, Object> toSongMap(LeagueSong song, Map<String, Object> line) {
        Map<String, Object> m = toSongMap(song);
        if (line != null) m.putAll(line);
        return m;
    }

    /** 課題曲をレスポンス用 Map に変換する。 */
    private Map<String, Object> toSongMap(LeagueSong song) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", song.getId());
        m.put("tier", song.getTier());
        m.put("groupIndex", song.getGroupIndex());
        m.put("slot", song.getSlot());
        m.put("title", song.getTitle());
        m.put("difficultyName", song.getDifficultyName());
        m.put("level", song.getLevel());
        m.put("notes", song.getNotes());
        return m;
    }

    /** 課題曲差し替えリクエストのボディ。 */
    public record ReplaceSongRequest(String title, String difficultyName) {
    }

    /** 階級手動修正リクエストのボディ。 */
    public record UpdateTierRequest(Integer tier) {
    }

    /** 仮編成プレビュー適用リクエストのボディ（GET /preview の応答をそのまま送り返す形）。 */
    public record ApplyPreviewRequest(List<LeagueWeekLifecycleService.PreviewTierRef> tiers) {
    }
}
