package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.ChartTendencyProfile;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.ChartTendencyService;
import com.beatseeker.backend.service.SkillTreeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
public class ChartTendencyController {

    private final ChartTendencyService service;
    private final SkillTreeService skillTreeService;
    private final UserRepository userRepository;

    public ChartTendencyController(ChartTendencyService service,
                                   SkillTreeService skillTreeService,
                                   UserRepository userRepository) {
        this.service = service;
        this.skillTreeService = skillTreeService;
        this.userRepository = userRepository;
    }

    // ── 管理者エンドポイント ─────────────────────────────────────

    /**
     * POST /api/admin/chart-tendencies/import
     * chart_cache/profiles/ の JSON を DB に一括インポートする。
     *
     * Body: { "profilesDir": "../chart_cache/profiles" }  (省略時はデフォルトパスを使用)
     */
    @PostMapping("/api/admin/chart-tendencies/import")
    public ResponseEntity<Map<String, Object>> importProfiles(
            Authentication auth,
            @RequestBody(required = false) Map<String, String> body) {

        checkAdmin(auth);

        String dir = (body != null && body.containsKey("profilesDir"))
                ? body.get("profilesDir")
                : "../chart_cache/profiles";

        try {
            Map<String, Object> result = service.importFromDirectory(dir);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/admin/chart-tendencies/import-json
     * JSON 配列をリクエストボディから直接インポートする（本番環境用）。
     *
     * Body: [ { "textage": "...", "title": "...", ... }, ... ]
     */
    @PostMapping("/api/admin/chart-tendencies/import-json")
    public ResponseEntity<Map<String, Object>> importJsonBody(
            Authentication auth,
            @RequestBody com.fasterxml.jackson.databind.JsonNode body) {

        checkAdmin(auth);

        try {
            Map<String, Object> result = service.importFromJsonArray(body);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/admin/score-prediction?textage=...&userId=...
     * 指定ユーザーのスコアを使ってスコア予測を返す（管理者専用）。
     */
    @GetMapping("/api/admin/score-prediction")
    public ResponseEntity<Map<String, Object>> scorePredictionForUser(
            Authentication auth,
            @RequestParam String textage,
            @RequestParam long userId) {

        checkAdmin(auth);
        User user = userRepository.findById(userId)
                .orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }
        Map<String, Object> result = service.predictScore(user, textage);
        return ResponseEntity.ok(result);
    }

    // ── 分析エンドポイント (認証済みユーザー) ──────────────────────

    /**
     * GET /api/analysis/tendency-profile?textage=22%2Fchrono_p.html%3F1AC00
     * 指定 textage の傾向プロファイルを返す。
     */
    @GetMapping("/api/analysis/tendency-profile")
    public ResponseEntity<Map<String, Object>> getProfile(@RequestParam String textage) {
        Optional<ChartTendencyProfile> opt = service.getByTextage(textage);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(profileToMap(opt.get()));
    }

    /**
     * GET /api/analysis/tendency-profiles-by-song?textageBase=22/foo.html
     * 曲のベースURL（クエリパラメータなし）で全難易度のプロファイルを返す。
     */
    @GetMapping("/api/analysis/tendency-profiles-by-song")
    public ResponseEntity<List<Map<String, Object>>> getProfilesBySong(
            @RequestParam String textageBase) {
        List<ChartTendencyProfile> profiles = service.getByTextageBase(textageBase);
        List<Map<String, Object>> result = profiles.stream()
                .map(this::profileToMap)
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/analysis/tendency-profiles?level=11&difficulty=4
     * レベル・難易度でプロファイル一覧を返す。
     */
    @GetMapping("/api/analysis/tendency-profiles")
    public ResponseEntity<List<Map<String, Object>>> getProfiles(
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) String difficulty) {

        List<ChartTendencyProfile> profiles;
        if (level != null && difficulty != null) {
            profiles = service.getByLevelAndDifficulty(level, difficulty);
        } else if (level != null) {
            profiles = service.getByLevel(level);
        } else {
            return ResponseEntity.badRequest().build();
        }

        List<Map<String, Object>> result = profiles.stream()
                .map(this::profileToMap)
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/analysis/score-prediction?textage=...
     * 現在のログインユーザーの予測スコアを返す。
     */
    @GetMapping("/api/analysis/score-prediction")
    public ResponseEntity<Map<String, Object>> scorePrediction(
            Authentication auth,
            @RequestParam String textage) {

        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        String iidxId = (String) auth.getPrincipal();
        User user = userRepository.findByIidxId(iidxId)
                .orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }

        Map<String, Object> result = service.predictScore(user, textage);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/analysis/score-prediction/all
     * 全曲の予測スコアと informalRank を返す（BEAT-TIER算出用）。
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
        return ResponseEntity.ok(service.predictAllScores(user));
    }

    /**
     * GET /api/admin/similarity-debug?textageA=...&textageB=...
     * 2曲間の類似度計算過程を詳細に返す（管理者専用）。
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
     * GET /api/analysis/skill-tree
     * スキルツリーを生成して返す。認証済みの場合はユーザー進捗を含む。
     */
    @GetMapping("/api/analysis/skill-tree")
    public ResponseEntity<Map<String, Object>> getSkillTree(Authentication auth) {
        User user = null;
        if (auth != null && auth.isAuthenticated()) {
            String iidxId = (String) auth.getPrincipal();
            user = userRepository.findByIidxId(iidxId).orElse(null);
        }
        Map<String, Object> result = skillTreeService.generateSkillTree(user);
        return ResponseEntity.ok(result);
    }

    // ── ヘルパー ─────────────────────────────────────────────────

    private void checkAdmin(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        User user = userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getId() != 18L) {
            throw new RuntimeException("Access Denied: Admin privileges required.");
        }
    }

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
