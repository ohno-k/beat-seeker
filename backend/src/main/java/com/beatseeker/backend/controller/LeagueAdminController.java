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

    public LeagueAdminController(UserRepository userRepository,
                                 AdminAuthService adminAuthService,
                                 LeagueService leagueService,
                                 LeagueWeekLifecycleService lifecycleService,
                                 LeagueSongDrawService songDrawService,
                                 LeagueWeekRepository leagueWeekRepository,
                                 LeagueSongRepository leagueSongRepository,
                                 LeagueMemberRepository leagueMemberRepository,
                                 LeagueEntryRepository leagueEntryRepository,
                                 SongDefinitionRepository songDefinitionRepository) {
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

        List<User> tierMembers = List.of();
        LeagueWeek active = leagueWeekRepository
                .findFirstByLadderTypeAndStatusOrderByStartsAtDesc(week.getLadderType(), "active").orElse(null);
        if (active != null) {
            tierMembers = leagueMemberRepository.findByWeek(active).stream()
                    .filter(m -> m.getTier().equals(tier))
                    .map(LeagueMember::getUser)
                    .collect(Collectors.toList());
        }
        List<LeagueSong> songs = songDrawService.drawSongsForTier(week, tier, tierMembers);
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
        Map<Integer, List<Map<String, Object>>> songsByTier = leagueSongRepository
                .findByWeekOrderByTierAscSlotAsc(week).stream()
                .collect(Collectors.groupingBy(LeagueSong::getTier, TreeMap::new,
                        Collectors.mapping(this::toSongMap, Collectors.toList())));
        List<Map<String, Object>> tiers = songsByTier.entrySet().stream()
                .map(e -> {
                    Map<String, Object> tm = new LinkedHashMap<String, Object>();
                    tm.put("tier", e.getKey());
                    tm.put("songs", e.getValue());
                    return tm;
                })
                .toList();
        m.put("tiers", tiers);
        m.put("memberCount", leagueMemberRepository.findByWeek(week).size());
        return m;
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

    /** 課題曲差し替えリクエストのボディ。 */
    public record ReplaceSongRequest(String title, String difficultyName) {
    }

    /** 階級手動修正リクエストのボディ。 */
    public record UpdateTierRequest(Integer tier) {
    }
}
