package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.ArenaMatch;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ArenaMatchRepository;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.beatseeker.backend.service.ScoreRecalculationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final ScoreRepository scoreRepository;
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;
    private final ArenaMatchRepository arenaMatchRepository;
    private final ScoreRecalculationService scoreRecalculationService;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    public AdminController(UserRepository userRepository,
                           ScoreRepository scoreRepository,
                           ScoreHistoryLogRepository scoreHistoryLogRepository,
                           ArenaMatchRepository arenaMatchRepository,
                           ScoreRecalculationService scoreRecalculationService,
                           ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.scoreRepository = scoreRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.arenaMatchRepository = arenaMatchRepository;
        this.scoreRecalculationService = scoreRecalculationService;
        this.objectMapper = objectMapper;
    }

    private void checkAdminAccess(Authentication auth) {
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

    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> getAllUsers(Authentication auth) {
        checkAdminAccess(auth);

        List<User> users = userRepository.findAll();
        List<Map<String, Object>> result = users.stream().map(u -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.getId());
            map.put("iidxId", u.getIidxId());
            map.put("displayName", u.getDisplayName());
            map.put("danRank", u.getDanRank());
            map.put("arenaRank", u.getArenaRank());
            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    @GetMapping("/users/{userId}/scores")
    public ResponseEntity<List<Map<String, Object>>> getUserScores(
            Authentication auth,
            @PathVariable Long userId) {

        checkAdminAccess(auth);

        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Target user not found"));

        List<Score> scores = scoreRepository.findByUserOrderByUploadedAtAsc(targetUser);

        List<Map<String, Object>> result = scores.stream().map(s -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", s.getId());
            map.put("title", s.getTitle() != null ? s.getTitle() : "");
            map.put("difficultyName", s.getDifficultyName() != null ? s.getDifficultyName() : "");
            map.put("difficultyLevel", s.getDifficultyLevel() != null ? s.getDifficultyLevel() : 0);
            map.put("score", s.getScore() != null ? s.getScore() : 0);
            map.put("clearType", s.getClearType() != null ? s.getClearType() : "");
            map.put("djLevel", s.getDjLevel() != null ? s.getDjLevel() : "");
            map.put("pgreat", s.getPgreat() != null ? s.getPgreat() : 0);
            map.put("great", s.getGreat() != null ? s.getGreat() : 0);
            map.put("missCount", s.getMissCount());
            map.put("memo", s.getMemo() != null ? s.getMemo() : "");
            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    @GetMapping("/scores/all-user-scores-with-info")
    public ResponseEntity<List<Map<String, Object>>> getAllUserScoresWithInfo(Authentication auth) {
        checkAdminAccess(auth);
        return ResponseEntity.ok(scoreRepository.findAllUserAnotherAndLeggendariaScoresWithUserInfo());
    }

    @GetMapping("/scores/simulation-aggregate")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getSimulationAggregate(Authentication auth) {
        checkAdminAccess(auth);
        entityManager.createNativeQuery("SET LOCAL statement_timeout = '120s'").executeUpdate();
        return ResponseEntity.ok(scoreRepository.calculateDifficultySimulation());
    }

    @GetMapping("/users/{userId}/history")
    public ResponseEntity<List<Map<String, Object>>> getUserHistory(
            Authentication auth,
            @PathVariable Long userId) {

        checkAdminAccess(auth);

        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Target user not found"));

        List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(targetUser);

        List<Map<String, Object>> history = new ArrayList<>();

        for (ScoreHistoryLog log : logs) {
            Map<String, Object> snapshotData = new HashMap<>();
            snapshotData.put("snapshotId", log.getId().toString()); // Use ID as pseudo-snapshot ID
            snapshotData.put("date", log.getUploadedAt().toString());
            snapshotData.put("totalScore", log.getTotalScore());
            snapshotData.put("fcCount", log.getFcCount());
            snapshotData.put("exhCount", log.getExhCount());
            snapshotData.put("hCount", log.getHCount());
            snapshotData.put("clearCount", log.getClearCount());
            snapshotData.put("easyCount", log.getEasyCount());
            snapshotData.put("aaaCount", log.getAaaCount());
            snapshotData.put("aaCount", log.getAaCount());
            snapshotData.put("aCount", log.getACount());

            snapshotData.put("totalBeatPt", log.getTotalBeatPt());
            snapshotData.put("beatPtIncrease", log.getBeatPtIncrease());
            snapshotData.put("updatedCount", log.getUpdatedCount());
            snapshotData.put("diffJson", log.getDiffJson());
            snapshotData.put("totalRatePt", log.getTotalRatePt());

            history.add(snapshotData);
        }

        return ResponseEntity.ok(history);
    }

    @GetMapping("/users/{userId}/arena/matches")
    public ResponseEntity<List<Map<String, Object>>> getUserArenaMatches(
            Authentication auth,
            @PathVariable Long userId) {

        checkAdminAccess(auth);

        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Target user not found"));

        List<ArenaMatch> matches = arenaMatchRepository.findByUserOrderByMatchDateDesc(targetUser);

        List<Map<String, Object>> result = new ArrayList<>();
        for (ArenaMatch m : matches) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", m.getId());
            map.put("battleType", m.getBattleType());
            map.put("matchDate", m.getMatchDate());
            map.put("myDjName", m.getMyDjName());

            List<Map<String, Object>> players = List.of();
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parsed = objectMapper.readValue(m.getPlayersJson(), List.class);
                players = parsed;
            } catch (Exception ignored) {}
            map.put("players", players);

            try {
                map.put("songs", objectMapper.readValue(m.getSongsJson(), List.class));
            } catch (Exception ignored) {
                map.put("songs", List.of());
            }

            int myRank = m.getMyRank() != null ? m.getMyRank() : 0;
            int myTotalPt = m.getMyTotalPt() != null ? m.getMyTotalPt() : 0;
            String myArenaClass = m.getMyArenaClass() != null ? m.getMyArenaClass() : "";
            String myDjName = m.getMyDjName();

            if (myRank == 0 && myDjName != null && !myDjName.isEmpty()) {
                for (Map<String, Object> p : players) {
                    if (myDjName.equals(p.get("djName"))) {
                        Object rankObj = p.get("rank");
                        Object ptObj = p.get("totalPt");
                        Object clsObj = p.get("arenaClass");
                        if (rankObj instanceof Number) myRank = ((Number) rankObj).intValue();
                        if (ptObj instanceof Number) myTotalPt = ((Number) ptObj).intValue();
                        if (clsObj instanceof String) myArenaClass = (String) clsObj;
                        break;
                    }
                }
            }

            map.put("myArenaClass", myArenaClass);
            map.put("myRank", myRank);
            map.put("myTotalPt", myTotalPt);

            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/recalculate-points")
    public ResponseEntity<Map<String, Object>> recalculatePoints(
            Authentication auth,
            @RequestBody RecalculatePointsRequest req) {

        checkAdminAccess(auth);

        try {
            scoreRecalculationService.recalculateAllUsersAsync(req.songDataJson(), req.difficultyTableJson());
            return ResponseEntity.accepted().body(Map.of("message", "Recalculation started in background"));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("message", "Error starting recalculation: " + e.getMessage()));
        }
    }

    /**
     * score_history_logs の最新レコードから users.total_beat_pt を一括移行する。
     * 新カラム追加後に一度だけ実行する。
     */
    @PostMapping("/migrate-user-beat-pt")
    public ResponseEntity<Map<String, Object>> migrateUserBeatPt(Authentication auth) {
        checkAdminAccess(auth);
        List<User> users = userRepository.findAll();
        int updated = 0;
        for (User u : users) {
            scoreHistoryLogRepository.findFirstByUserOrderByUploadedAtDesc(u).ifPresent(log -> {
                if (log.getTotalBeatPt() != null && log.getTotalBeatPt() > 0) {
                    u.setTotalBeatPt(log.getTotalBeatPt());
                    userRepository.save(u);
                }
            });
            updated++;
        }
        return ResponseEntity.ok(Map.of("message", updated + " 件のユーザーのtotalBeatPtを更新しました"));
    }

    @PostMapping("/push/clear-all")
    public ResponseEntity<Map<String, Object>> clearAllPushSubscriptions(Authentication auth) {
        checkAdminAccess(auth);
        userRepository.clearAllPushSubscriptions();
        return ResponseEntity.ok(Map.of("message", "全てのユーザーのプッシュ通知設定を初期化しました。"));
    }

    /**
     * total_rate_pt = 0 の履歴ログを現在のスコアから再計算して補正する。
     * recalculate-points と同じリクエストボディ（songDataJson のみ使用）。
     */
    @PostMapping("/patch-rate-pt")
    public ResponseEntity<Map<String, Object>> patchRatePt(
            Authentication auth,
            @RequestBody RecalculatePointsRequest req) {

        checkAdminAccess(auth);

        try {
            com.fasterxml.jackson.databind.JsonNode songDataRoot = objectMapper.readTree(req.songDataJson());
            java.util.Map<String, Integer> songMaxScores = new java.util.HashMap<>();
            if (songDataRoot.has("body") && songDataRoot.get("body").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode s : songDataRoot.get("body")) {
                    String title = s.path("title").asText().trim();
                    String diffCode = s.path("difficulty").asText();
                    int notes = s.path("notes").asInt(0);
                    if (notes > 0) {
                        songMaxScores.put(title + "_" + diffCode, notes * 2);
                    }
                }
            }
            int patched = scoreRecalculationService.patchZeroRatePtLogs(songMaxScores);
            return ResponseEntity.ok(Map.of("message", patched + " 件の履歴ログを補正しました"));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("message", "Error: " + e.getMessage()));
        }
    }
}
