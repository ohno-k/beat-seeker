package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.ChartTendencyProfile;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.AdminAuthService;
import com.beatseeker.backend.service.ChartTendencyService;
import com.beatseeker.backend.service.SkillTreeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 【クラスの役割】 譜面傾向プロファイル（chart tendency）関連の API を集約した REST コントローラ。
 *
 * beat-seeker では、各譜面を「スクラッチ率」「同時押し率」「16 分主体率」など
 * 多数の統計量で特徴づけた {@link ChartTendencyProfile} を DB に保持している。
 * 本コントローラは次の 3 系統のエンドポイントを提供する。
 *
 * 責務:
 *  - 管理者向け: chart_cache/profiles/ 以下の JSON 群を一括インポートする
 *  - 分析 API: フロントエンドから textage（譜面識別子）単位で傾向プロファイルを取得する
 *  - スコア予測: 既プレイ曲の傾向と類似度から未プレイ譜面のスコアを推定する
 *
 * 依存:
 *  - {@link ChartTendencyService}: プロファイルの永続化・類似度計算・スコア予測ロジック
 *  - {@link SkillTreeService}: スキルツリー（ユーザー進捗マップ）生成ロジック
 *  - {@link UserRepository}: iidxId → User 解決、管理者判定
 *
 * 主なエンドポイント:
 *  - POST /api/admin/chart-tendencies/import       … ディレクトリから一括取り込み
 *  - POST /api/admin/chart-tendencies/import-json  … JSON 配列を直接取り込み
 *  - GET  /api/admin/score-prediction              … 任意ユーザーの予測スコア
 *  - GET  /api/admin/similarity-debug              … 類似度計算のデバッグ出力
 *  - GET  /api/analysis/tendency-profile           … 1 譜面の傾向プロファイル
 *  - GET  /api/analysis/tendency-profiles-by-song  … 曲単位で全難易度を返す
 *  - GET  /api/analysis/tendency-profiles          … レベル・難易度で絞り込み
 *  - GET  /api/analysis/score-prediction           … ログインユーザーの予測スコア
 *  - GET  /api/analysis/score-prediction/all       … 全譜面の予測スコア（BEAT-TIER 算出用）
 *  - GET  /api/analysis/skill-tree                 … スキルツリー生成
 */
@RestController
public class ChartTendencyController {

    /** 傾向プロファイルの CRUD と予測ロジックを担うサービス。 */
    private final ChartTendencyService service;
    /** スキルツリー（傾向ベースの上達ロードマップ）生成サービス。 */
    private final SkillTreeService skillTreeService;
    /** ユーザー検索・管理者判定用のリポジトリ。 */
    private final UserRepository userRepository;
    /** 管理者判定のロジックを共通化した Service。 */
    private final AdminAuthService adminAuthService;

    /**
     * 【コンストラクタ】 Spring DI によりサービス・リポジトリを注入する。
     */
    public ChartTendencyController(ChartTendencyService service,
                                   SkillTreeService skillTreeService,
                                   UserRepository userRepository,
                                   AdminAuthService adminAuthService) {
        this.service = service;
        this.skillTreeService = skillTreeService;
        this.userRepository = userRepository;
        this.adminAuthService = adminAuthService;
    }

    // ── 管理者エンドポイント ─────────────────────────────────────

    /**
     * 【メソッドの役割】 chart_cache/profiles/ 以下の JSON を走査して DB に一括インポートする。
     *
     * 処理の流れ:
     *  1. 管理者権限をチェックし、権限がなければ例外を投げて終了。
     *  2. リクエストボディに profilesDir 指定があれば使用、なければデフォルトパスを使う。
     *  3. サービスに委譲して取り込み結果（件数・エラーなど）を返却。
     *
     * POST /api/admin/chart-tendencies/import
     * Body: { "profilesDir": "../chart_cache/profiles" }  (省略時はデフォルトパスを使用)
     *
     * @param auth Spring Security が注入する認証情報（JWT 由来）
     * @param body 任意パラメータの Map（profilesDir キー）
     * @return インポート結果サマリ、失敗時は 500 とエラーメッセージ
     */
    @PostMapping("/api/admin/chart-tendencies/import")
    public ResponseEntity<Map<String, Object>> importProfiles(
            Authentication auth,
            @RequestBody(required = false) Map<String, String> body) {

        // 手順1: 管理者権限の事前チェック（ID=18 のユーザー以外は即拒否）。
        checkAdmin(auth);

        // 手順2: ボディで明示指定があればそれを、なければデフォルトパスを採用する。
        String dir = (body != null && body.containsKey("profilesDir"))
                ? body.get("profilesDir")
                : "../chart_cache/profiles";

        try {
            // 手順3: サービスにディレクトリ配下の JSON 取り込みを委譲する。
            Map<String, Object> result = service.importFromDirectory(dir);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            // ファイル読み込み・DB 保存でのあらゆる失敗は 500 に畳んでエラー文字列を返す。
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 本番環境用に、JSON 配列を HTTP ボディで直接受けてインポートする。
     *
     * 本番はサーバのファイルシステムに chart_cache が無いことが多いため、
     * 開発環境で生成した JSON 配列をそのまま POST できるよう用意された代替経路。
     *
     * POST /api/admin/chart-tendencies/import-json
     * Body: [ { "textage": "...", "title": "...", ... }, ... ]
     *
     * @param auth 認証情報（管理者限定）
     * @param body JSON 配列（Jackson の JsonNode で受ける）
     * @return 取り込み結果サマリ。失敗時は 500 + エラーメッセージ
     */
    @PostMapping("/api/admin/chart-tendencies/import-json")
    public ResponseEntity<Map<String, Object>> importJsonBody(
            Authentication auth,
            @RequestBody com.fasterxml.jackson.databind.JsonNode body) {

        checkAdmin(auth);

        try {
            // 受け取った JsonNode をそのままサービスに渡して永続化する。
            Map<String, Object> result = service.importFromJsonArray(body);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 任意ユーザーの予測スコアを計算して返す（管理者専用デバッグ用）。
     *
     * 通常の /api/analysis/score-prediction は「ログイン中のユーザー」のみを対象にするが、
     * こちらは userId を明示指定できるので、調査・検証目的で他ユーザーの予測を確認できる。
     *
     * GET /api/admin/score-prediction?textage=...&userId=...
     *
     * @param auth    認証情報（管理者限定）
     * @param textage 予測対象の譜面識別子
     * @param userId  対象ユーザーの DB 上の主キー
     * @return 予測スコアを含む Map。ユーザー不在なら 404
     */
    @GetMapping("/api/admin/score-prediction")
    public ResponseEntity<Map<String, Object>> scorePredictionForUser(
            Authentication auth,
            @RequestParam String textage,
            @RequestParam long userId) {

        checkAdmin(auth);
        // 指定 ID のユーザーが見つからなければ 404 を返す。orElse(null) で明示的に null 化する。
        User user = userRepository.findById(userId)
                .orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }
        // サービスに予測ロジックを委譲。類似曲の実績から統計的に推定される。
        Map<String, Object> result = service.predictScore(user, textage);
        return ResponseEntity.ok(result);
    }

    // ── 分析エンドポイント (認証済みユーザー) ──────────────────────

    /**
     * 【メソッドの役割】 textage（譜面識別子）を指定して、単一の傾向プロファイルを返す。
     *
     * textage は IIDX の譜面 URL に由来する文字列で、URL エンコードされて渡ってくる。
     * 見つからない場合は 404 を返す。
     *
     * GET /api/analysis/tendency-profile?textage=22%2Fchrono_p.html%3F1AC00
     *
     * @param textage 譜面識別子（例: "22/chrono_p.html?1AC00"）
     * @return Map 化された傾向プロファイル。該当なしの場合 404
     */
    @GetMapping("/api/analysis/tendency-profile")
    public ResponseEntity<Map<String, Object>> getProfile(@RequestParam String textage) {
        Optional<ChartTendencyProfile> opt = service.getByTextage(textage);
        if (opt.isEmpty()) {
            // DB に該当プロファイルがない場合は 404 Not Found を返す。
            return ResponseEntity.notFound().build();
        }
        // Entity → Map 変換してフロントエンドに返す。
        return ResponseEntity.ok(profileToMap(opt.get()));
    }

    /**
     * 【メソッドの役割】 1 曲のベース URL（クエリ部除去）を指定して全難易度のプロファイルを一括取得。
     *
     * 譜面詳細画面で「同じ曲の別難易度」を並べて表示するケースで使う。
     *
     * GET /api/analysis/tendency-profiles-by-song?textageBase=22/foo.html
     *
     * @param textageBase 曲のベース URL（クエリパラメータなし）
     * @return 全難易度の傾向プロファイルリスト
     */
    @GetMapping("/api/analysis/tendency-profiles-by-song")
    public ResponseEntity<List<Map<String, Object>>> getProfilesBySong(
            @RequestParam String textageBase) {
        List<ChartTendencyProfile> profiles = service.getByTextageBase(textageBase);
        List<Map<String, Object>> result = profiles.stream()
                .map(this::profileToMap)   // 各 Entity を Map へ変換
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 レベル・難易度コードでプロファイル一覧を絞り込む。
     *
     * level のみ / level + difficulty の 2 パターンを許容し、どちらも未指定なら 400。
     *
     * GET /api/analysis/tendency-profiles?level=11&difficulty=4
     *
     * @param level      譜面レベル（1〜12）
     * @param difficulty 難易度コード（例: "4" = ANOTHER、"10" = LEGGENDARIA）
     * @return 条件に合う傾向プロファイルリスト。level 指定なしは 400
     */
    @GetMapping("/api/analysis/tendency-profiles")
    public ResponseEntity<List<Map<String, Object>>> getProfiles(
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) String difficulty) {

        List<ChartTendencyProfile> profiles;
        if (level != null && difficulty != null) {
            // 両方指定: レベル＋難易度で絞り込み（最も一般的なユースケース）。
            profiles = service.getByLevelAndDifficulty(level, difficulty);
        } else if (level != null) {
            // level のみ: 全難易度のプロファイルを返す。
            profiles = service.getByLevel(level);
        } else {
            // どちらも未指定: 全件返却は重すぎるため、400 Bad Request を返す。
            return ResponseEntity.badRequest().build();
        }

        List<Map<String, Object>> result = profiles.stream()
                .map(this::profileToMap)
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 ログイン中のユーザーに対して、指定譜面の予測スコアを返す。
     *
     * 処理の流れ:
     *  1. 認証情報の有無を確認（未認証なら 401）。
     *  2. iidxId から User を解決（見つからなければ 404）。
     *  3. ChartTendencyService が既プレイ曲の実績と類似度を使って予測スコアを算出。
     *
     * GET /api/analysis/score-prediction?textage=...
     *
     * @param auth    認証情報
     * @param textage 予測対象の譜面識別子
     * @return 予測スコアを含む Map。未認証 401 / ユーザー不在 404
     */
    @GetMapping("/api/analysis/score-prediction")
    public ResponseEntity<Map<String, Object>> scorePrediction(
            Authentication auth,
            @RequestParam String textage) {

        // 手順1: JWT 由来の認証が無ければ 401 を返す。
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        // 手順2: principal に格納された iidxId からユーザーを引く。
        String iidxId = (String) auth.getPrincipal();
        User user = userRepository.findByIidxId(iidxId)
                .orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }

        // 手順3: サービスに予測計算を委譲。
        Map<String, Object> result = service.predictScore(user, textage);
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 ログインユーザーの「全曲」に対する予測スコアと informalRank を返す。
     *
     * BEAT-TIER 算出用にクライアントが一括取得するエンドポイント。1 曲ごとに
     * /score-prediction を叩くと N 回 HTTP が発生するので、ここで一度に返す。
     *
     * GET /api/analysis/score-prediction/all
     *
     * @param auth 認証情報
     * @return 全曲の予測スコアを含む構造。未認証 401 / ユーザー不在 404
     */
    @GetMapping("/api/analysis/score-prediction/all")
    public ResponseEntity<?> scorePredictionAll(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        String iidxId = (String) auth.getPrincipal();
        User user = userRepository.findByIidxId(iidxId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }
        // predictAllScores は DB からユーザー全スコアを読み、全譜面に対して一括予測する。
        return ResponseEntity.ok(service.predictAllScores(user));
    }

    /**
     * 【メソッドの役割】 2 譜面の類似度計算の内訳を詳細に返すデバッグ用エンドポイント。
     *
     * 類似度アルゴリズムの調整・検証を目的としており、寄与ベクトルや重み付け等を
     * 詳細に可視化する。管理者限定。
     *
     * GET /api/admin/similarity-debug?textageA=...&textageB=...
     *
     * @param auth     認証情報（管理者限定）
     * @param textageA 比較元の譜面識別子
     * @param textageB 比較先の譜面識別子
     * @return 類似度計算の中間結果を含む Map
     */
    @GetMapping("/api/admin/similarity-debug")
    public ResponseEntity<Map<String, Object>> similarityDebug(
            Authentication auth,
            @RequestParam String textageA,
            @RequestParam String textageB) {

        checkAdmin(auth);
        return ResponseEntity.ok(service.computeSimilarityDebug(textageA, textageB));
    }

    // ── スキルツリー ──────────────────────────────────────────────

    /**
     * 【メソッドの役割】 スキルツリー（譜面傾向ベースの上達ロードマップ）を返す。
     *
     * 認証は任意。ログイン済みならユーザーの進捗ステータスが各ノードに付与され、
     * 未ログインでもツリー構造だけは閲覧できる。
     *
     * GET /api/analysis/skill-tree
     *
     * @param auth 認証情報（nullable）
     * @return スキルツリー構造の Map
     */
    @GetMapping("/api/analysis/skill-tree")
    public ResponseEntity<Map<String, Object>> getSkillTree(Authentication auth) {
        User user = null;
        // 認証済みかつユーザーが DB に存在する場合のみ、進捗付きのツリーを返す。
        if (auth != null && auth.isAuthenticated()) {
            String iidxId = (String) auth.getPrincipal();
            user = userRepository.findByIidxId(iidxId).orElse(null);
        }
        Map<String, Object> result = skillTreeService.generateSkillTree(user);
        return ResponseEntity.ok(result);
    }

    // ── ヘルパー ─────────────────────────────────────────────────

    /**
     * 【メソッドの役割】 管理者権限を確認し、無ければ例外で 500 応答にする共通ガード。
     *
     * 判定ロジックは {@link AdminAuthService} に集約しており、ユーザー ID または
     * IIDX ID のどちらかが設定値と一致した場合に管理者扱いとする。
     *
     * @param auth 認証情報
     * @throws RuntimeException 未認証・ユーザー不在・非管理者の場合
     */
    private void checkAdmin(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        User user = userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        // AdminAuthService に判定を委譲（Role 管理テーブルへの置換が容易になる）。
        if (!adminAuthService.isAdmin(user)) {
            throw new RuntimeException("Access Denied: Admin privileges required.");
        }
    }

    /**
     * 【メソッドの役割】 {@link ChartTendencyProfile} エンティティを HashMap 形式に変換する。
     *
     * DTO を別途用意せず、API 出力のキー名をこの場で決める方針。
     * JSON 生文字列系（*Json）はそのまま文字列として出力され、フロント側で再パースする想定。
     *
     * @param p 変換元の Entity
     * @return フロント向けのキー・値で構成された Map
     */
    private Map<String, Object> profileToMap(ChartTendencyProfile p) {
        Map<String, Object> m = new HashMap<>();
        m.put("textage", p.getTextage());
        m.put("title", p.getTitle());
        m.put("artist", p.getArtist());
        m.put("difficulty", p.getDifficulty());
        m.put("level", p.getLevel());
        m.put("bpmRaw", p.getBpmRaw());
        m.put("bpmMain", p.getBpmMain());
        m.put("isSoflan", p.getIsSoflan());
        m.put("notes", p.getNotes());
        m.put("events", p.getEvents());
        m.put("dominantEff16", p.getDominantEff16());
        m.put("weightedEff16", p.getWeightedEff16());
        m.put("scratchPct", p.getScratchPct());
        m.put("chordPct", p.getChordPct());
        m.put("singlePct", p.getSinglePct());
        m.put("ranuchi", p.getRanuchi());
        m.put("tagsJson", p.getTagsJson());
        m.put("intervalDistJson", p.getIntervalDistJson());
        m.put("chordDistJson", p.getChordDistJson());
        m.put("kbdIntervalDistJson", p.getKbdIntervalDistJson());
        m.put("scrIntervalDistJson", p.getScrIntervalDistJson());
        m.put("cnNotes", p.getCnNotes());
        m.put("cnScratchPct", p.getCnScratchPct());
        m.put("cnKbdOverlapPct", p.getCnKbdOverlapPct());
        m.put("cnIntervalDistJson", p.getCnIntervalDistJson());
        // 配置パターン属性
        m.put("jackCount", p.getJackCount());
        m.put("jackNotes", p.getJackNotes());
        m.put("jackPct", p.getJackPct());
        m.put("trillCount", p.getTrillCount());
        m.put("trillNotes", p.getTrillNotes());
        m.put("trillPct", p.getTrillPct());
        m.put("stairsCount", p.getStairsCount());
        m.put("stairsNotes", p.getStairsNotes());
        m.put("stairsPct", p.getStairsPct());
        m.put("dstairsCount", p.getDstairsCount());
        m.put("dstairsNotes", p.getDstairsNotes());
        m.put("dstairsPct", p.getDstairsPct());
        m.put("measureNotesJson", p.getMeasureNotesJson());
        m.put("measureNotesKbdJson", p.getMeasureNotesKbdJson());
        m.put("measureNotesScrJson", p.getMeasureNotesScrJson());
        return m;
    }
}
