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
 *  - POST /api/league/admin/weeks/{weekId}/songs/{songId}/disabled … 課題曲の無効化 / 復帰（draft・active）
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
    /** 締め済み週の曲別確定結果（後追い凍結の重複チェックに使う）。 */
    private final LeagueMemberSongRepository leagueMemberSongRepository;
    /** 順位表の計算（後追い凍結で保存する値を求めるために使う）。 */
    private final LeagueStandingsService standingsService;

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
                                 ScoreRepository scoreRepository,
                                 LeagueMemberSongRepository leagueMemberSongRepository,
                                 LeagueStandingsService standingsService) {
        this.leagueMemberSongRepository = leagueMemberSongRepository;
        this.standingsService = standingsService;
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
     * 【メソッドの役割】 課題曲 1 曲の有効 / 無効を切り替える（draft・開催中(active) の両方で可）。
     *
     * 現在解禁できない譜面が抽選で入ってしまった場合の救済。無効にした曲は
     * {@link LeagueStandingsService} の集計から外れる（有効曲に数えず、着順ポイントも配らない）。
     * 曲の行自体は残すので、順位表・課題曲一覧には「無効」表示の列として残る。
     *
     * <p>開催中でも切り替えられるのが差し替え（{@link #replaceSong}）との違い。開始時にラインを
     * 凍結済みの週では別の曲へ差し替えるとラインが無いまま走ることになるため、走り出した後の
     * 救済手段はこちらになる。締め済み(closed)の週は結果が凍結されているので変更できない。
     *
     * @param auth     認証情報（管理者限定）
     * @param weekId   対象週 ID（draft / active）
     * @param songId   対象の課題曲 ID
     * @param req      {@code {"disabled": true}}
     * @return 更新後の課題曲
     */
    @PostMapping("/weeks/{weekId}/songs/{songId}/disabled")
    @Transactional
    public ResponseEntity<?> setSongDisabled(Authentication auth,
                                             @PathVariable Long weekId,
                                             @PathVariable Long songId,
                                             @RequestBody SetSongDisabledRequest req) {
        if (requireAdmin(auth) == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみアクセスできます"));
        }
        LeagueWeek week = leagueWeekRepository.findById(weekId).orElse(null);
        if (week == null) {
            return ResponseEntity.status(404).body(Map.of("error", "指定した週が見つかりません"));
        }
        if ("closed".equals(week.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "締め済み(closed)の週の課題曲は変更できません"));
        }
        LeagueSong song = leagueSongRepository.findById(songId).orElse(null);
        if (song == null || !song.getWeek().getId().equals(weekId)) {
            return ResponseEntity.status(404).body(Map.of("error", "指定した課題曲が見つかりません"));
        }
        if (req == null || req.disabled() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "disabled は必須です"));
        }
        song.setDisabled(req.disabled());
        leagueSongRepository.save(song);
        return ResponseEntity.ok(Map.of(
                "message", req.disabled() ? "課題曲を無効化しました（集計から外れます）" : "課題曲を有効に戻しました",
                "song", toSongMap(song)));
    }

    /**
     * 【メソッドの役割】 指定 DIVISION の選曲プール（その階級で出題され得る譜面）を返す。
     *
     * 管理者が課題曲を差し替えるときの選択肢に使う。実際の抽選で掛かる絞り込み（直近出題の除外・
     * グループ内の拮抗判定）はかけず、その階級の難易度帯に入る譜面をタイトル順で全件返す。
     *
     * @param auth 認証情報（管理者限定）
     * @param tier 階級（0=LEGEND .. 10）
     * @return {@code {tier, songs:[{title, difficultyName, level, notes}]}}
     */
    @GetMapping("/song-pool")
    public ResponseEntity<?> songPool(Authentication auth, @RequestParam("tier") Integer tier) {
        if (requireAdmin(auth) == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみアクセスできます"));
        }
        if (!LeagueDivision.isValid(tier)) {
            return ResponseEntity.badRequest().body(Map.of("error", "tier は 0 (DIVISION LEGEND) 〜 10 で指定してください"));
        }
        List<Map<String, Object>> songs = songDrawService.poolForTier(tier).stream()
                .map(sd -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("title", sd.getTitle());
                    m.put("difficultyName", LeagueChartNotation.codeToName(sd.getDifficulty()));
                    m.put("level", sd.getLevel());
                    m.put("notes", sd.getNotes());
                    return m;
                })
                .sorted(Comparator.comparing(x -> String.valueOf(x.get("title")), String.CASE_INSENSITIVE_ORDER))
                .toList();
        return ResponseEntity.ok(Map.of("tier", tier, "songs", songs));
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
     * （月曜 12:00）までの間に overview / 順位表で確認し、必要なら課題曲を差し替え・再抽選できる。
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
     * 【メソッドの役割】 締め済み週の曲別内訳を後追いで凍結する（凍結機能より前に締まった週の救済）。
     *
     * 週の締め（{@code closeWeek}）は曲別内訳を {@code league_member_songs} へ保存するが、
     * その機能より前に締まった週（プレシーズン）には保存が無く、表示のたびに現在の scores から
     * 再計算している。再計算はアップロードのたびに結果が動くため、いま計算できる値を 1 度だけ
     * 保存して固定するための管理者操作。
     *
     * <p>既に凍結済みのグループは<b>触らない</b>（再実行しても上書きしない）。締めからの経過が
     * 長いほど再計算値は当時からズレるため、救済は早いほど正確。
     *
     * @param auth   認証情報（管理者限定）
     * @param weekId 対象週 ID（締め済みであること）
     * @return 凍結したグループ数・メンバー数・スキップしたグループ数
     */
    @PostMapping("/weeks/{weekId}/freeze-songs")
    public ResponseEntity<?> freezeSongs(Authentication auth, @PathVariable Long weekId) {
        if (requireAdmin(auth) == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみアクセスできます"));
        }
        LeagueWeek week = leagueWeekRepository.findById(weekId).orElse(null);
        if (week == null) {
            return ResponseEntity.status(404).body(Map.of("error", "指定した週が見つかりません"));
        }
        if (!"closed".equals(week.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "凍結できるのは締め済み(closed)の週のみです"));
        }

        List<LeagueMember> members = leagueMemberRepository.findByWeek(week);
        // (tier, groupIndex) ごとに、その週の順位表を求めて曲別内訳を保存する。
        Map<String, List<LeagueMember>> byGroup = members.stream()
                .collect(Collectors.groupingBy(m -> m.getTier() + ":" + m.getGroupIndex(),
                        TreeMap::new, Collectors.toList()));
        int frozenGroups = 0;
        int frozenMembers = 0;
        int skippedGroups = 0;
        for (Map.Entry<String, List<LeagueMember>> g : byGroup.entrySet()) {
            List<LeagueMember> groupMembers = g.getValue();
            if (!leagueMemberSongRepository.findByMemberIn(groupMembers).isEmpty()) {
                skippedGroups++; // 既に凍結済み → 再計算値で上書きしない
                continue;
            }
            String[] parts = g.getKey().split(":");
            List<Map<String, Object>> standings = standingsService.computeGroupStandings(
                    week, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            standingsService.freezePerSong(groupMembers, standings);
            frozenGroups++;
            frozenMembers += groupMembers.size();
        }
        return ResponseEntity.ok(Map.of(
                "message", "曲別内訳を凍結しました。",
                "weekId", weekId,
                "frozenGroups", frozenGroups,
                "frozenMembers", frozenMembers,
                "skippedGroups", skippedGroups));
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
        m.put("weekNo", week.getWeekNo());
        m.put("startsAt", week.getStartsAt());
        m.put("endsAt", week.getEndsAt());
        m.put("status", week.getStatus());
        List<LeagueSong> songs = leagueSongRepository.findByWeekOrderByTierAscSlotAsc(week);
        // 卓(tier) → グループ → メンバー。誰がどのグループに入ったかを管理者が確認できるようにする。
        List<LeagueMember> members = leagueMemberRepository.findByWeek(week);

        // draft 週は各グループのライン（＝開始時にベースラインとして凍結される見込み値）とその保持者、
        // および各メンバーの課題曲別自己ベストを返す（仮編成プレビューと同じ表を出せるようにする）。
        // active/closed 週の確定ラインはベースライン由来なのでここでは計算しない（順位表側が表示する）。
        DraftLineData lines = "draft".equals(week.getStatus())
                ? computeDraftLineData(songs, members)
                : new DraftLineData(Map.of(), Map.of());
        Map<Integer, List<Map<String, Object>>> songsByTier = songs.stream()
                .collect(Collectors.groupingBy(LeagueSong::getTier, TreeMap::new,
                        Collectors.mapping(s -> toSongMap(s, lines.lineBySongId().get(s.getId())), Collectors.toList())));
        Map<Integer, Map<Integer, List<Map<String, Object>>>> membersByTierGroup = new TreeMap<>();
        for (LeagueMember mem : members) {
            membersByTierGroup
                    .computeIfAbsent(mem.getTier(), k -> new TreeMap<>())
                    .computeIfAbsent(mem.getGroupIndex(), k -> new ArrayList<>())
                    .add(toMemberMap(mem, lines.bestsByUserId().get(mem.getUser().getId())));
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

    /**
     * メンバー 1 人分（誰がどの卓・グループに、どの立場で入ったか）をレスポンス用 Map に変換する。
     *
     * @param bests 課題曲別の自己ベストセル（draft 週のみ。無ければ空配列）
     */
    private Map<String, Object> toMemberMap(LeagueMember member, List<Map<String, Object>> bests) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", member.getUser().getId());
        m.put("displayName", member.getUser().getDisplayName() != null ? member.getUser().getDisplayName() : "");
        m.put("iidxId", member.getUser().getIidxId());
        m.put("homeTier", member.getHomeTier() != null ? member.getHomeTier() : member.getTier());
        m.put("role", member.getRole() != null ? member.getRole() : "normal");
        m.put("bests", bests != null ? bests : List.of());
        return m;
    }

    /**
     * draft 週のライン計算結果。
     *
     * @param lineBySongId  課題曲 ID → {@code {lineEx, lineRate, lineHolders:[表示名]}}（ライン無しの曲は含まない）
     * @param bestsByUserId ユーザー ID → 課題曲別の自己ベストセル（スロット順）
     */
    private record DraftLineData(Map<Long, Map<String, Object>> lineBySongId,
                                 Map<Long, List<Map<String, Object>>> bestsByUserId) {
    }

    /**
     * 【メソッドの役割】 draft 週の各グループについて「ライン・ライン保持者・各メンバーの自己ベスト」を求める。
     *
     * ライン＝そのグループのメンバーが持つアーケード自己ベストの最高 EX。開始（activateWeek）時に
     * ベースラインとして凍結される値と同じ計算（アーケード記録限定・EX &gt; 0）なので、管理者は開始前に
     * 「どの曲が誰の記録でどこまで塞がっているか」を確認できる。同値の保持者が複数居れば全員返す。
     * 仮編成プレビューと同じ表を描けるよう、メンバーごとの課題曲別セル（EX・レート・ライン保持フラグ）も返す。
     *
     * <p>全メンバー × 全課題曲を 1 クエリで引いてから (tier, groupIndex) 単位に集計する
     * （人数分のクエリを撃たない）。
     *
     * @param songs   週の全課題曲
     * @param members 週の全メンバー
     * @return ライン情報とメンバー別セル
     */
    private DraftLineData computeDraftLineData(List<LeagueSong> songs, List<LeagueMember> members) {
        if (songs.isEmpty() || members.isEmpty()) return new DraftLineData(Map.of(), Map.of());
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

        // (tier|groupIndex) 単位に課題曲（スロット順）とメンバーを集める。
        Map<String, List<LeagueSong>> songsByGroup = new LinkedHashMap<>();
        for (LeagueSong song : songs) {
            songsByGroup.computeIfAbsent(song.getTier() + "|" + song.getGroupIndex(), k -> new ArrayList<>()).add(song);
        }
        for (List<LeagueSong> list : songsByGroup.values()) {
            list.sort(Comparator.comparing(LeagueSong::getSlot));
        }
        Map<String, List<LeagueMember>> membersByGroup = new LinkedHashMap<>();
        for (LeagueMember mem : members) {
            membersByGroup.computeIfAbsent(mem.getTier() + "|" + mem.getGroupIndex(), k -> new ArrayList<>()).add(mem);
        }

        Map<Long, Map<String, Object>> lineBySongId = new HashMap<>();
        Map<Long, List<Map<String, Object>>> bestsByUserId = new HashMap<>();
        for (Map.Entry<String, List<LeagueMember>> ge : membersByGroup.entrySet()) {
            List<LeagueSong> groupSongs = songsByGroup.getOrDefault(ge.getKey(), List.of());
            List<LeagueMember> groupMembers = ge.getValue();
            if (groupSongs.isEmpty()) continue;

            // 1) 曲ごとのライン（最高 EX）と保持者
            int[] lineEx = new int[groupSongs.size()];
            for (int i = 0; i < groupSongs.size(); i++) {
                LeagueSong song = groupSongs.get(i);
                List<String> holders = new ArrayList<>();
                for (LeagueMember mem : groupMembers) {
                    Integer ex = bestByUserSong.get(
                            mem.getUser().getId() + "|" + song.getTitle() + "|" + song.getDifficultyName());
                    if (ex == null) continue;
                    if (ex > lineEx[i]) {
                        lineEx[i] = ex;
                        holders.clear();
                    }
                    if (ex == lineEx[i]) holders.add(nameOf(mem.getUser()));
                }
                if (lineEx[i] <= 0) continue; // 誰も未プレー = ライン無し（週内に記録を出せばそのまま有効）
                holders.sort(String.CASE_INSENSITIVE_ORDER);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("lineEx", lineEx[i]);
                line.put("lineRate", rateOf(lineEx[i], song.getNotes()));
                line.put("lineHolders", holders);
                lineBySongId.put(song.getId(), line);
            }

            // 2) メンバーごとの課題曲別セル（ライン確定後に isLine を付与）
            for (LeagueMember mem : groupMembers) {
                List<Map<String, Object>> cells = new ArrayList<>();
                for (int i = 0; i < groupSongs.size(); i++) {
                    LeagueSong song = groupSongs.get(i);
                    Integer ex = bestByUserSong.get(
                            mem.getUser().getId() + "|" + song.getTitle() + "|" + song.getDifficultyName());
                    Map<String, Object> cell = new LinkedHashMap<>();
                    cell.put("slot", song.getSlot());
                    cell.put("ex", ex);
                    cell.put("rate", ex != null ? rateOf(ex, song.getNotes()) : null);
                    cell.put("isLine", ex != null && lineEx[i] > 0 && ex == lineEx[i]);
                    cell.put("played", ex != null);
                    cells.add(cell);
                }
                bestsByUserId.put(mem.getUser().getId(), cells);
            }
        }
        return new DraftLineData(lineBySongId, bestsByUserId);
    }

    /** 表示名（未設定なら IIDX ID）。 */
    private String nameOf(User user) {
        String name = user.getDisplayName();
        return name != null && !name.isBlank() ? name : String.valueOf(user.getIidxId());
    }

    /** EX からスコアレート(%・小数第 2 位)。notes 不明なら null。 */
    private Double rateOf(int ex, Integer notes) {
        if (notes == null || notes <= 0) return null;
        return Math.round(ex * 100.0 / (notes * 2) * 100.0) / 100.0;
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
        m.put("disabled", song.isDisabled());
        return m;
    }

    /** 課題曲差し替えリクエストのボディ。 */
    public record ReplaceSongRequest(String title, String difficultyName) {
    }

    /** 課題曲の有効 / 無効切り替えリクエストのボディ。 */
    public record SetSongDisabledRequest(Boolean disabled) {
    }

    /** 階級手動修正リクエストのボディ。 */
    public record UpdateTierRequest(Integer tier) {
    }

    /** 仮編成プレビュー適用リクエストのボディ（GET /preview の応答をそのまま送り返す形）。 */
    public record ApplyPreviewRequest(List<LeagueWeekLifecycleService.PreviewTierRef> tiers) {
    }
}
