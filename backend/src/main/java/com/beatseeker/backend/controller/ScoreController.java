package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.ActivityLog;
import com.beatseeker.backend.entity.AppNotification;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.entity.UserSongRank;
import com.beatseeker.backend.repository.ActivityLogRepository;
import com.beatseeker.backend.repository.AppNotificationRepository;
import com.beatseeker.backend.repository.FriendshipRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.repository.UserSongRankRepository;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.service.EmailService;
import com.beatseeker.backend.service.PushNotificationService;
import com.beatseeker.backend.service.ScoreRecalculationService;
import com.beatseeker.backend.service.SongRankBatchService;
import com.beatseeker.backend.service.TopRankersBeatPtService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scores")
public class ScoreController {

    private final ScoreRepository scoreRepository;
    private final UserRepository userRepository;
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;
    private final FriendshipRepository friendshipRepository;
    private final AppNotificationRepository appNotificationRepository;
    private final ActivityLogRepository activityLogRepository;
    private final PushNotificationService pushNotificationService;
    private final UserSongRankRepository userSongRankRepository;
    private final SongRankBatchService songRankBatchService;
    private final ScoreRecalculationService scoreRecalculationService;
    private final EmailService emailService;
    private final SongDefinitionRepository songDefinitionRepository;
    private final TopRankersBeatPtService topRankersBeatPtService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long ADMIN_USER_ID = 18L;

    public ScoreController(ScoreRepository scoreRepository, UserRepository userRepository,
            ScoreHistoryLogRepository scoreHistoryLogRepository,
            FriendshipRepository friendshipRepository,
            AppNotificationRepository appNotificationRepository,
            ActivityLogRepository activityLogRepository,
            PushNotificationService pushNotificationService,
            UserSongRankRepository userSongRankRepository,
            SongRankBatchService songRankBatchService,
            ScoreRecalculationService scoreRecalculationService,
            EmailService emailService,
            SongDefinitionRepository songDefinitionRepository,
            TopRankersBeatPtService topRankersBeatPtService) {
        this.scoreRepository = scoreRepository;
        this.userRepository = userRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.friendshipRepository = friendshipRepository;
        this.appNotificationRepository = appNotificationRepository;
        this.activityLogRepository = activityLogRepository;
        this.pushNotificationService = pushNotificationService;
        this.userSongRankRepository = userSongRankRepository;
        this.songRankBatchService = songRankBatchService;
        this.scoreRecalculationService = scoreRecalculationService;
        this.emailService = emailService;
        this.songDefinitionRepository = songDefinitionRepository;
        this.topRankersBeatPtService = topRankersBeatPtService;
    }

    /**
     * Upload (upsert) scores for the current user.
     * Keeps only the best scores and returns the diff.
     */
    @PostMapping("/upload")
    @Transactional
    public ResponseEntity<Map<String, Object>> uploadScores(
            Authentication auth,
            @RequestBody List<ScoreUploadRequest> requests) {

        User user = getUser(auth);

        // Load existing scores for the user for lookup
        List<Score> existingScores = scoreRepository.findByUserOrderByUploadedAtAsc(user);
        Map<String, Score> scoreMap = new HashMap<>();
        for (Score s : existingScores) {
            String key = s.getTitle() + "_" + s.getDifficultyName() + "_" + s.getDifficultyLevel();
            scoreMap.put(key, s);
        }

        List<Map<String, Object>> updatedSongs = new java.util.ArrayList<>();

        for (ScoreUploadRequest req : requests) {
            String key = req.title() + "_" + req.difficultyName() + "_" + req.difficultyLevel();
            Score existing = scoreMap.get(key);

            boolean isImproved = false;
            int oldScore = 0;
            String oldClearType = "NO PLAY";

            if (existing == null) {
                // New score
                isImproved = true;
                Score newScore = new Score();
                newScore.setUser(user);
                updateScoreFields(newScore, req);
                scoreRepository.save(newScore);
            } else {
                oldScore = existing.getScore() != null ? existing.getScore() : 0;
                oldClearType = existing.getClearType() != null ? existing.getClearType() : "NO PLAY";

                int oldMiss = existing.getMissCount() != null ? existing.getMissCount() : Integer.MAX_VALUE;
                int newMiss = req.missCount() != null ? req.missCount() : Integer.MAX_VALUE;

                int oldRank = getClearTypeRank(oldClearType);
                int newRank = getClearTypeRank(req.clearType());

                boolean scoreBetter = req.score() > oldScore;
                boolean rankBetter = newRank > oldRank;
                boolean missBetter = newMiss < oldMiss;

                // Typical IIDX tracking: Best Score, Best Clear, Best BP are tracked.
                // Since this app has a single Score entity per song, any improvement triggers an update.
                if (scoreBetter || rankBetter || missBetter) {
                    isImproved = true;
                    updateScoreFields(existing, req);
                    scoreRepository.save(existing);
                }
            }

            if (isImproved) {
                Map<String, Object> diff = new HashMap<>();
                diff.put("title", req.title());
                diff.put("difficulty", req.difficultyName());
                diff.put("oldScore", oldScore);
                diff.put("newScore", req.score());
                diff.put("scoreIncrease", Math.max(0, req.score() - oldScore));
                diff.put("oldClearType", oldClearType);
                diff.put("newClearType", req.clearType());
                diff.put("clearTypeImproved", getClearTypeRank(req.clearType()) > getClearTypeRank(oldClearType));
                updatedSongs.add(diff);
            }
        }

        if (!updatedSongs.isEmpty()) {
            updateLastUploadTime(user);
            notifyFriendsOfScoreBeat(user, updatedSongs);
        }

        return ResponseEntity.ok(Map.of(
                "updatedCount", updatedSongs.size(),
                "updatedSongs", updatedSongs,
                "message", "スコアを更新しました"));
    }

    private void updateLastUploadTime(User user) {
        user.setLastUploadedAt(java.time.LocalDateTime.now());
        userRepository.save(user);
    }

    /**
     * Save history log (snapshot) with diff data from frontend
     */
    @PostMapping("/save-history-log")
    @Transactional
    public ResponseEntity<Map<String, Object>> saveHistoryLog(
            Authentication auth,
            @RequestBody SaveHistoryLogRequest req) {

        User user = getUser(auth);
        List<Score> allScores = scoreRepository.findByUserOrderByUploadedAtAsc(user);
        if (allScores.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "No scores found to snapshot"));
        }

        ScoreHistoryLog log = new ScoreHistoryLog();
        log.setUser(user);
        log.setUploadedAt(java.time.LocalDateTime.now());
        log.setTotalBeatPt(req.totalBeatPt());
        log.setBeatPtIncrease(req.beatPtIncrease());
        log.setUpdatedCount(req.updatedCount());
        log.setDiffJson(req.diffJson());
        log.setTotalPrecisionPt(req.totalPrecisionPt() != null ? req.totalPrecisionPt() : 0.0);
        double totalRatePt = req.totalRatePt() != null ? req.totalRatePt() : 0.0;
        if (totalRatePt <= 0) {
            totalRatePt = scoreRecalculationService.calculateRatePtFromActiveData(allScores);
        }
        log.setTotalRatePt(totalRatePt);

        long totalScore = 0;
        int fcCount = 0;
        int exhCount = 0;
        int hCount = 0;
        int clearCount = 0;
        int easyCount = 0;
        int aaaCount = 0;
        int aaCount = 0;
        int aCount = 0;

        for (Score s : allScores) {
            if (s.getScore() != null)
                totalScore += s.getScore();
            if ("FULLCOMBO CLEAR".equals(s.getClearType()))
                fcCount++;
            if ("EX HARD CLEAR".equals(s.getClearType()))
                exhCount++;
            if ("HARD CLEAR".equals(s.getClearType()))
                hCount++;
            if ("CLEAR".equals(s.getClearType()))
                clearCount++;
            if ("EASY CLEAR".equals(s.getClearType()))
                easyCount++;
            if ("AAA".equals(s.getDjLevel()))
                aaaCount++;
            if ("AA".equals(s.getDjLevel()))
                aaCount++;
            if ("A".equals(s.getDjLevel()))
                aCount++;
        }

        log.setTotalScore(totalScore);
        log.setFcCount(fcCount);
        log.setExhCount(exhCount);
        log.setHCount(hCount);
        log.setClearCount(clearCount);
        log.setEasyCount(easyCount);
        log.setAaaCount(aaaCount);
        log.setAaCount(aaCount);
        log.setACount(aCount);

        scoreHistoryLogRepository.save(log);

        // ユーザーのtotalBeatPtをキャッシュ（ティア別平均クエリの高速化）
        if (req.totalBeatPt() != null) {
            user.setTotalBeatPt(req.totalBeatPt());
            userRepository.save(user);
        }

        // ランクアップ通知とActivityLog
        if (req.tierName() != null && req.prevTierName() != null
                && !req.tierName().equals(req.prevTierName())) {
            notifyFriendsOfRankUp(user, req.prevTierName(), req.tierName());
            ActivityLog activity = new ActivityLog();
            activity.setUser(user);
            activity.setType("RANK_UP");
            activity.setOldValue(req.prevTierName());
            activity.setNewValue(req.tierName());
            activityLogRepository.save(activity);
        }

        // 管理者以外のユーザーが更新した場合、管理者(ID=18)にメール通知
        if (!ADMIN_USER_ID.equals(user.getId()) && req.diffJson() != null && !req.diffJson().isBlank()) {
            userRepository.findById(ADMIN_USER_ID).ifPresent(admin -> {
                if (admin.getEmail() != null) {
                    try {
                        List<Map<String, Object>> diffs = objectMapper.readValue(
                                req.diffJson(), new TypeReference<>() {});
                        emailService.sendScoreUpdateNotification(
                                admin.getEmail(),
                                user.getDisplayName() != null ? user.getDisplayName() : user.getIidxId(),
                                user.getIidxId() != null ? user.getIidxId() : "",
                                diffs,
                                req.totalBeatPt() != null ? req.totalBeatPt() : 0.0,
                                req.beatPtIncrease() != null ? req.beatPtIncrease() : 0.0,
                                req.tierName(),
                                req.prevTierName());
                    } catch (Exception e) {
                        System.err.println("Failed to parse diffJson for email: " + e.getMessage());
                    }
                }
            });
        }

        return ResponseEntity.ok(Map.of("message", "History log saved"));
    }

    private void updateScoreFields(Score score, ScoreUploadRequest req) {
        score.setTitle(req.title());
        score.setArtist(req.artist());
        score.setGenre(req.genre());
        score.setDifficultyName(req.difficultyName());
        score.setDifficultyLevel(req.difficultyLevel());
        score.setScore(req.score());
        score.setClearType(req.clearType());
        score.setDjLevel(req.djLevel());
        score.setPgreat(req.pgreat());
        score.setGreat(req.great());
        score.setMissCount(req.missCount());
        score.setPlayCount(req.playCount());
        score.setUploadedAt(java.time.LocalDateTime.now());
    }

    private int getClearTypeRank(String clearType) {
        if (clearType == null)
            return 0;
        return switch (clearType) {
            case "FULLCOMBO CLEAR" -> 7;
            case "EX HARD CLEAR" -> 6;
            case "HARD CLEAR" -> 5;
            case "CLEAR" -> 4;
            case "EASY CLEAR" -> 3;
            case "ASSIST CLEAR" -> 2;
            case "FAILED" -> 1;
            default -> 0;
        };
    }

    /**
     * Get all scores for the current user.
     */
    @GetMapping("/me")
    public ResponseEntity<List<Map<String, Object>>> getMyScores(
            Authentication auth) {

        User user = getUser(auth);

        List<Score> scores = scoreRepository.findByUserOrderByUploadedAtAsc(user);

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

    /**
     * Get history aggregates for the current user from score_history_logs.
     */
    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> getHistory(
            Authentication auth) {

        User user = getUser(auth);
        List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(user);

        List<Map<String, Object>> history = new java.util.ArrayList<>();

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

            // New fields
            snapshotData.put("totalBeatPt", log.getTotalBeatPt());
            snapshotData.put("beatPtIncrease", log.getBeatPtIncrease());
            snapshotData.put("updatedCount", log.getUpdatedCount());
            snapshotData.put("diffJson", log.getDiffJson());
            snapshotData.put("totalRatePt", log.getTotalRatePt());

            history.add(snapshotData);
        }

        return ResponseEntity.ok(history);
    }

    /**
     * Get global BEAT-PT ranking.
     */
    @GetMapping("/debug-ranking")
    public ResponseEntity<Map<String, Object>> debugRanking() {
        Map<String, Object> result = new HashMap<>();

        List<Map<String, Object>> userSummary = userRepository.findAll().stream().map(u -> {
            Map<String, Object> map = new HashMap<>();
            map.put("displayName", u.getDisplayName());
            map.put("privacyLevel", u.getPrivacyLevel());
            List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(u);
            map.put("historyCount", logs.size());
            if (!logs.isEmpty()) {
                ScoreHistoryLog latest = logs.get(logs.size() - 1);
                map.put("latestPt", latest.getTotalBeatPt());
                map.put("latestAt", latest.getUploadedAt().toString());
            }
            return map;
        }).toList();

        result.put("users", userSummary);
        result.put("top10", scoreHistoryLogRepository.getGlobalRanking().stream().limit(10).toList());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/debug-user-scores/{userId}")
    public ResponseEntity<List<Map<String, Object>>> debugUserScores(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        List<Score> scores = scoreRepository.findByUserOrderByUploadedAtAsc(user);

        List<Map<String, Object>> result = scores.stream().map(s -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", s.getId());
            map.put("title", s.getTitle());
            map.put("difficultyName", s.getDifficultyName());
            map.put("difficultyLevel", s.getDifficultyLevel());
            map.put("score", s.getScore());
            map.put("clearType", s.getClearType());
            map.put("uploadedAt", s.getUploadedAt().toString());
            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    @GetMapping("/ranking")
    public ResponseEntity<List<Map<String, Object>>> getGlobalRanking() {
        List<Map<String, Object>> ranking = scoreHistoryLogRepository.getGlobalRanking();
        return ResponseEntity.ok(ranking);
    }

    @GetMapping("/ranking/arena-averages")
    public ResponseEntity<List<Map<String, Object>>> getArenaRankAverages() {
        List<Map<String, Object>> averages = scoreHistoryLogRepository.getArenaRankAverageBeatPt();
        return ResponseEntity.ok(averages);
    }

    @GetMapping("/ranking/top-rankers")
    public ResponseEntity<List<Map<String, Object>>> getTopRankersBeatPt() {
        return ResponseEntity.ok(topRankersBeatPtService.getRanking());
    }

    @GetMapping("/rate-ranking/arena-averages")
    public ResponseEntity<List<Map<String, Object>>> getArenaRankAverageRatePt() {
        return ResponseEntity.ok(scoreHistoryLogRepository.getArenaRankAverageRatePt());
    }

    @GetMapping("/rate-ranking/top-rankers")
    public ResponseEntity<List<Map<String, Object>>> getTopRankersRatePt() {
        return ResponseEntity.ok(topRankersBeatPtService.getRateRanking());
    }

    @GetMapping("/song-top-rankers")
    public ResponseEntity<List<com.beatseeker.backend.service.TopRankersBeatPtService.SongScoreEntry>> getSongTopRankers(
            @RequestParam String title,
            @RequestParam String difficultyName) {
        return ResponseEntity.ok(topRankersBeatPtService.getSongTopRankers(title, difficultyName));
    }

    @GetMapping("/top-ranker-profile")
    public ResponseEntity<com.beatseeker.backend.service.TopRankersBeatPtService.AreaProfile> getTopRankerProfile(
            @RequestParam int versionNum,
            @RequestParam int prefectureFileNum) {
        var profile = topRankersBeatPtService.getAreaProfile(versionNum, prefectureFileNum);
        if (profile == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/precision-ranking")
    public ResponseEntity<List<Map<String, Object>>> getPrecisionRanking() {
        List<Map<String, Object>> ranking = scoreHistoryLogRepository.getPrecisionRanking();
        return ResponseEntity.ok(ranking);
    }

    @GetMapping("/rate-ranking")
    public ResponseEntity<List<Map<String, Object>>> getRateRanking() {
        List<Map<String, Object>> ranking = scoreHistoryLogRepository.getRateTierRanking();
        return ResponseEntity.ok(ranking);
    }

    @GetMapping("/user-tier-totals/{userId}")
    public ResponseEntity<Map<String, Object>> getUserTierTotals(@PathVariable Long userId) {
        Double beatPt = userRepository.findById(userId).map(User::getTotalBeatPt).orElse(0.0);
        Double ratePt = scoreHistoryLogRepository.getLatestTotalRatePtByUserId(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("totalBeatPt", beatPt != null ? beatPt : 0.0);
        result.put("totalRatePt", ratePt != null ? ratePt : 0.0);
        return ResponseEntity.ok(result);
    }

    /**
     * Returns all users' ANOTHER and LEGGENDARIA scores for song ranking aggregation.
     * The frontend uses this to determine which songs appear in each user's top-100.
     */
    @GetMapping("/all-user-scores")
    public ResponseEntity<List<Map<String, Object>>> getAllUserScores() {
        List<Map<String, Object>> scores = scoreRepository.findAllUserAnotherAndLeggendariaScores();
        return ResponseEntity.ok(scores);
    }

    @GetMapping("/song-ranking-aggregate")
    public ResponseEntity<List<Map<String, Object>>> getSongRankingAggregate() {
        return ResponseEntity.ok(scoreRepository.findAllSongRankingAggregates());
    }

    private static final String ADMIN_IIDX_ID = "5787-1145";

    /**
     * Get raw best scores per user per song with BEAT-TIER label.
     * Only Lv11 and Lv12 ANOTHER/LEGGENDARIA. Aggregation (A-rank filter) done client-side.
     */
    @GetMapping("/song-arena-averages")
    public ResponseEntity<List<Map<String, Object>>> getSongBeatTierAverages() {
        return ResponseEntity.ok(scoreRepository.findRawSongScoresWithBeatTier());
    }

    /**
     * Get average score rates per song (ANOTHER/LEGGENDARIA, score rate >= 66.67%).
     * Uses lightweight single-table aggregation + Java-side notes resolution.
     */
    @GetMapping("/song-avg-score-rates")
    public ResponseEntity<List<Map<String, Object>>> getSongAvgScoreRates() {
        // 1. Build notes lookup: "title|difficultyName" -> notes (fast, single table)
        List<SongDefinition> songDefs = songDefinitionRepository.findByRevision("active");
        Map<String, Integer> notesMap = new HashMap<>();
        for (SongDefinition sd : songDefs) {
            if (sd.getLevel() == null || sd.getLevel() < 11) continue;
            if ("4".equals(sd.getDifficulty())) {
                notesMap.put(sd.getTitle() + "|ANOTHER", sd.getNotes());
            } else if ("10".equals(sd.getDifficulty())) {
                notesMap.put(sd.getTitle() + "|LEGGENDARIA", sd.getNotes());
            }
        }

        // 2. Fetch per-song avg scores (lightweight, no JOIN, single-table GROUP BY)
        List<Map<String, Object>> songAvgs = scoreRepository.findSongAvgScores();

        // 3. Fetch MAX- counts per song (aggregated JOIN, returns ~1000 rows)
        List<Map<String, Object>> maxMinusData = scoreRepository.findSongMaxMinusCounts();
        Map<String, int[]> maxMinusStats = new HashMap<>();
        for (Map<String, Object> row : maxMinusData) {
            String key = row.get("title") + "|" + row.get("difficultyName");
            int maxMinusCount = ((Number) row.get("maxMinusCount")).intValue();
            int totalCount = ((Number) row.get("totalCount")).intValue();
            maxMinusStats.put(key, new int[]{maxMinusCount, totalCount});
        }

        // 3b. Fetch AAA counts per song
        List<Map<String, Object>> aaaData = scoreRepository.findSongAaaCounts();
        Map<String, int[]> aaaStats = new HashMap<>();
        for (Map<String, Object> row : aaaData) {
            String key = row.get("title") + "|" + row.get("difficultyName");
            int aaaCount = ((Number) row.get("aaaCount")).intValue();
            int totalCount = ((Number) row.get("totalCount")).intValue();
            aaaStats.put(key, new int[]{aaaCount, totalCount});
        }

        // 4. Convert avg scores to score rates using notes, filter >= 66.667%
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Map<String, Object> row : songAvgs) {
            String title = (String) row.get("title");
            String diffName = (String) row.get("difficultyName");
            double avgScore = ((Number) row.get("avgScore")).doubleValue();
            int playerCount = ((Number) row.get("playerCount")).intValue();

            String key = title + "|" + diffName;
            Integer notes = notesMap.get(key);
            if (notes == null || notes <= 0) continue;

            double avgScoreRate = avgScore * 100.0 / (notes * 2.0);

            int[] stats = maxMinusStats.get(key);
            double maxMinusRate = (stats != null && stats[1] > 0)
                ? Math.round(stats[0] * 10000.0 / stats[1]) / 100.0
                : 0.0;

            Map<String, Object> entry = new HashMap<>();
            entry.put("title", title);
            entry.put("difficultyName", diffName);
            entry.put("avgScoreRate", Math.round(avgScoreRate * 100.0) / 100.0);
            entry.put("playerCount", playerCount);
            entry.put("maxMinusRate", maxMinusRate);
            entry.put("maxMinusCount", stats != null ? stats[0] : 0);

            int[] aStats = aaaStats.get(key);
            double aaaRate = (aStats != null && aStats[1] > 0)
                ? Math.round(aStats[0] * 10000.0 / aStats[1]) / 100.0
                : 0.0;
            entry.put("aaaRate", aaaRate);
            entry.put("aaaCount", aStats != null ? aStats[0] : 0);
            result.add(entry);
        }

        result.sort((a, b) -> Double.compare(
            ((Number) a.get("avgScoreRate")).doubleValue(),
            ((Number) b.get("avgScoreRate")).doubleValue()));

        return ResponseEntity.ok(result);
    }

    /**
     * Get per-song ranking for a specific song.
     * Only returns rows visible to the caller: public users, self, or friends whose privacyLevel == 1.
     */
    @GetMapping("/song-ranking")
    public ResponseEntity<List<Map<String, Object>>> getSongRanking(
            Authentication auth,
            @RequestParam String title,
            @RequestParam String difficultyName) {
        User me = getUser(auth);
        List<Long> friendIds = friendshipRepository.findByUser(me).stream()
                .map(f -> f.getFriend().getId())
                .toList();
        if (friendIds.isEmpty()) friendIds = List.of(-1L);
        List<Map<String, Object>> ranking = scoreRepository.findSongRanking(
                title, difficultyName, me.getId(), friendIds);
        return ResponseEntity.ok(ranking);
    }

    /**
     * Get current user's rank for every ANOTHER/LEGGENDARIA song they have played.
     * Reads from the pre-calculated cache table (refreshed daily by SongRankBatchService).
     */
    @GetMapping("/my-song-ranks")
    public ResponseEntity<List<Map<String, Object>>> getMySongRanks(Authentication auth) {
        User user = getUser(auth);
        List<UserSongRank> ranks = userSongRankRepository.findByUserId(user.getId());
        List<Map<String, Object>> result = ranks.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("title", r.getTitle());
            map.put("difficultyName", r.getDifficultyName());
            map.put("difficultyLevel", r.getDifficultyLevel());
            map.put("rank", r.getRank());
            map.put("total", r.getTotal());
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Get admin's rank for every song (admin only).
     * Reads from the pre-calculated cache table.
     */
    @GetMapping("/admin-song-ranks")
    public ResponseEntity<List<Map<String, Object>>> getAdminSongRanks(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || !ADMIN_IIDX_ID.equals(auth.getPrincipal())) {
            return ResponseEntity.ok(List.of());
        }
        List<UserSongRank> ranks = userSongRankRepository.findByUserId(18L);
        List<Map<String, Object>> result = ranks.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("title", r.getTitle());
            map.put("difficultyName", r.getDifficultyName());
            map.put("difficultyLevel", r.getDifficultyLevel());
            map.put("rank", r.getRank());
            map.put("total", r.getTotal());
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Manually trigger song rank recalculation (admin only).
     * Use after deploying or when ranks seem stale.
     */
    @PostMapping("/recalculate-song-ranks")
    public ResponseEntity<Map<String, Object>> recalculateSongRanks(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || !ADMIN_IIDX_ID.equals(auth.getPrincipal())) {
            return ResponseEntity.status(403).build();
        }
        songRankBatchService.recalculateAllAsync();
        return ResponseEntity.accepted().body(Map.of("message", "Song rank recalculation started"));
    }

    /**
     * Get score update history for a specific song by parsing diffJson in score_history_logs.
     */
    @GetMapping("/song-history")
    public ResponseEntity<List<Map<String, Object>>> getSongHistory(
            Authentication auth,
            @RequestParam String title,
            @RequestParam String difficultyName) {
        User user = getUser(auth);
        List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(user);

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<Map<String, Object>> result = new java.util.ArrayList<>();

        for (ScoreHistoryLog log : logs) {
            String diffJson = log.getDiffJson();
            if (diffJson == null || diffJson.isEmpty() || "[]".equals(diffJson)) continue;
            try {
                List<Map<String, Object>> diffs = mapper.readValue(diffJson,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> diff : diffs) {
                    Object t = diff.get("title");
                    Object d = diff.get("difficulty");
                    if (title.equals(t) && difficultyName.equals(d)) {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("uploadedAt", log.getUploadedAt().toString());
                        entry.put("score", diff.get("newScore"));
                        entry.put("beatPt", diff.get("newBeatPt"));
                        result.add(entry);
                    }
                }
            } catch (Exception e) {
                // skip malformed diffJson
            }
        }

        result.sort((a, b) -> ((String) b.get("uploadedAt")).compareTo((String) a.get("uploadedAt")));
        return ResponseEntity.ok(result);
    }

    /**
     * Update the memo for a specific score.
     */
    @PutMapping("/{id}/memo")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateMemo(
            Authentication auth,
            @PathVariable Long id,
            @RequestBody MemoUpdateRequest request) {

        User user = getUser(auth);
        Score score = scoreRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Score not found"));

        if (!score.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).build();
        }

        score.setMemo(request.memo());
        scoreRepository.save(score);

        return ResponseEntity.ok(Map.of("message", "メモを保存しました"));
    }

    /**
     * フレンドのスコアを自分のスコアが上回った場合、そのフレンドに通知する
     */
    private void notifyFriendsOfScoreBeat(User uploader, List<Map<String, Object>> updatedSongs) {
        if (uploader.getPrivacyLevel() != null && uploader.getPrivacyLevel() == 2) return;
        if (updatedSongs.isEmpty()) return;
        List<com.beatseeker.backend.entity.Friendship> friendships = friendshipRepository.findByUser(uploader);

        List<String> titles = updatedSongs.stream().map(s -> (String) s.get("title")).toList();
        List<String> difficulties = updatedSongs.stream().map(s -> (String) s.get("difficulty")).distinct().toList();

        for (com.beatseeker.backend.entity.Friendship friendship : friendships) {
            User friend = friendship.getFriend();
            if (friend.getPrivacyLevel() != null && friend.getPrivacyLevel() == 2) continue;

            // Batch-fetch all relevant friend scores in one query (instead of N×M individual queries)
            List<com.beatseeker.backend.entity.Score> friendScores =
                    scoreRepository.findByUserAndTitlesAndDifficulties(friend, titles, difficulties);
            Map<String, Integer> friendScoreMap = new HashMap<>();
            for (com.beatseeker.backend.entity.Score fs : friendScores) {
                String key = fs.getTitle() + "_" + fs.getDifficultyName();
                friendScoreMap.put(key, fs.getScore() != null ? fs.getScore() : 0);
            }

            for (Map<String, Object> song : updatedSongs) {
                String title = (String) song.get("title");
                String difficulty = (String) song.get("difficulty");
                int newScore = (int) song.get("newScore");
                int oldScore = (int) song.get("oldScore");
                int friendScoreVal = friendScoreMap.getOrDefault(title + "_" + difficulty, 0);

                // 更新前はフレンドに負けていて、更新後に抜かした場合のみ通知する
                if (newScore > friendScoreVal && friendScoreVal > 0 && oldScore <= friendScoreVal) {
                    AppNotification notification = new AppNotification();
                    notification.setRecipient(friend);
                    notification.setType("SCORE_BEAT");
                    notification.setMessage(
                            uploader.getDisplayName() + "さんが「" + title + "」(" + difficulty + ") で " +
                            newScore + " を記録し、あなたのスコア " + friendScoreVal + " を上回りました！");
                    appNotificationRepository.save(notification);

                    if (friend.getPushSubscription() != null) {
                        pushNotificationService.sendNotification(
                                friend.getPushSubscription(),
                                "スコアを抜かれました！",
                                uploader.getDisplayName() + "さんに「" + title + "」で抜かれました",
                                "/");
                    }
                }
            }
        }
    }

    /**
     * ランクアップした際にフレンド全員に通知する
     */
    private void notifyFriendsOfRankUp(User user, String oldTier, String newTier) {
        if (user.getPrivacyLevel() != null && user.getPrivacyLevel() == 2) return;
        List<com.beatseeker.backend.entity.Friendship> friendships = friendshipRepository.findByUser(user);
        for (com.beatseeker.backend.entity.Friendship friendship : friendships) {
            User friend = friendship.getFriend();
            AppNotification notification = new AppNotification();
            notification.setRecipient(friend);
            notification.setType("FRIEND_RANK_UP");
            notification.setMessage(
                    user.getDisplayName() + "さんが Beat-Tier「" + oldTier + "」から「" + newTier + "」にランクアップしました！");
            appNotificationRepository.save(notification);

            if (friend.getPushSubscription() != null) {
                pushNotificationService.sendNotification(
                        friend.getPushSubscription(),
                        "フレンドがランクアップ！",
                        user.getDisplayName() + "さんが " + newTier + " にランクアップしました！",
                        "/");
            }
        }
    }

    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // DTO for upload request
    public record ScoreUploadRequest(
            String title,
            String artist,
            String genre,
            String difficultyName,
            Integer difficultyLevel,
            Integer score,
            String clearType,
            String djLevel,
            Integer pgreat,
            Integer great,
            Integer missCount,
            Integer playCount) {
    }

    public record MemoUpdateRequest(String memo) {
    }
}
