package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.UserRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scores")
public class ScoreController {

    private final ScoreRepository scoreRepository;
    private final UserRepository userRepository;
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;

    public ScoreController(ScoreRepository scoreRepository, UserRepository userRepository,
            ScoreHistoryLogRepository scoreHistoryLogRepository) {
        this.scoreRepository = scoreRepository;
        this.userRepository = userRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
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

                int oldRank = getClearTypeRank(oldClearType);
                int newRank = getClearTypeRank(req.clearType());

                boolean scoreBetter = req.score() > oldScore;
                boolean rankBetter = newRank > oldRank;

                if (scoreBetter || rankBetter) {
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

    /**
     * Returns all users' ANOTHER and LEGGENDARIA scores for song ranking aggregation.
     * The frontend uses this to determine which songs appear in each user's top-100.
     */
    @GetMapping("/all-user-scores")
    public ResponseEntity<List<Map<String, Object>>> getAllUserScores() {
        List<Map<String, Object>> scores = scoreRepository.findAllUserAnotherAndLeggendariaScores();
        return ResponseEntity.ok(scores);
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
     * Import scores from raw CSV text using a link token (for bookmarklet use).
     * No JWT required – authenticated via the user's link token.
     */
    @CrossOrigin(origins = "*")
    @PostMapping("/import-csv")
    @Transactional
    public ResponseEntity<Map<String, Object>> importCsv(@RequestBody ImportCsvRequest req) {
        if (req.token() == null || req.token().isBlank()) {
            return ResponseEntity.status(401).body(Map.of("message", "トークンが必要です"));
        }

        User user = userRepository.findByLinkToken(req.token()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "無効なトークンです。beat-seekerで新しいトークンを発行してください。"));
        }

        List<ScoreUploadRequest> requests;
        try {
            requests = parseCsvText(req.csvText());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "CSV解析エラー: " + e.getMessage()));
        }

        if (requests.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "有効なスコアデータが見つかりませんでした"));
        }

        // Upsert using same logic as /upload
        List<Score> existingScores = scoreRepository.findByUserOrderByUploadedAtAsc(user);
        Map<String, Score> scoreMap = new HashMap<>();
        for (Score s : existingScores) {
            String key = s.getTitle() + "_" + s.getDifficultyName() + "_" + s.getDifficultyLevel();
            scoreMap.put(key, s);
        }

        List<Map<String, Object>> updatedSongs = new ArrayList<>();
        for (ScoreUploadRequest r : requests) {
            String key = r.title() + "_" + r.difficultyName() + "_" + r.difficultyLevel();
            Score existing = scoreMap.get(key);
            boolean improved = false;
            int oldScore = 0;
            String oldClearType = "NO PLAY";

            if (existing == null) {
                improved = true;
                Score s = new Score();
                s.setUser(user);
                updateScoreFields(s, r);
                scoreRepository.save(s);
            } else {
                oldScore = existing.getScore() != null ? existing.getScore() : 0;
                oldClearType = existing.getClearType() != null ? existing.getClearType() : "NO PLAY";
                if (r.score() > oldScore || getClearTypeRank(r.clearType()) > getClearTypeRank(oldClearType)) {
                    improved = true;
                    updateScoreFields(existing, r);
                    scoreRepository.save(existing);
                }
            }

            if (improved) {
                Map<String, Object> diff = new HashMap<>();
                diff.put("title", r.title());
                diff.put("difficulty", r.difficultyName());
                diff.put("oldScore", oldScore);
                diff.put("newScore", r.score());
                diff.put("scoreIncrease", Math.max(0, r.score() - oldScore));
                diff.put("oldClearType", oldClearType);
                diff.put("newClearType", r.clearType());
                diff.put("clearTypeImproved", getClearTypeRank(r.clearType()) > getClearTypeRank(oldClearType));
                updatedSongs.add(diff);
            }
        }

        if (!updatedSongs.isEmpty()) {
            updateLastUploadTime(user);
        }

        return ResponseEntity.ok(Map.of(
                "updatedCount", updatedSongs.size(),
                "updatedSongs", updatedSongs,
                "message", "インポート完了！" + updatedSongs.size() + "曲のスコアを更新しました"));
    }

    private List<ScoreUploadRequest> parseCsvText(String csvText) throws Exception {
        // Strip BOM if present
        if (csvText.startsWith("\uFEFF")) {
            csvText = csvText.substring(1);
        }

        List<ScoreUploadRequest> result = new ArrayList<>();
        String[] difficulties = {"BEGINNER", "NORMAL", "HYPER", "ANOTHER", "LEGGENDARIA"};

        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .build()
                .parse(new StringReader(csvText))) {

            for (CSVRecord row : parser) {
                String title = getCol(row, "タイトル");
                if (title == null || title.isBlank()) continue;

                String artist = getCol(row, "アーティスト");
                String genre = getCol(row, "ジャンル");
                int playCount = parseIntSafe(getCol(row, "プレー回数"));

                for (String diff : difficulties) {
                    String clearType = getCol(row, diff + " クリアタイプ");
                    if (clearType == null || clearType.equals("NO PLAY") || clearType.equals("---")) continue;

                    int score = parseIntSafe(getCol(row, diff + " スコア"));
                    int diffLevel = parseIntSafe(getCol(row, diff + " 難易度"));
                    int pgreat = parseIntSafe(getCol(row, diff + " PGreat"));
                    int great = parseIntSafe(getCol(row, diff + " Great"));
                    String missStr = getCol(row, diff + " ミスカウント");
                    Integer missCount = (missStr == null || missStr.equals("---") || missStr.isBlank())
                            ? null : parseIntSafe(missStr);
                    String djLevel = getCol(row, diff + " DJ LEVEL");
                    if (djLevel == null) djLevel = "---";

                    result.add(new ScoreUploadRequest(
                            title, artist, genre, diff, diffLevel,
                            score, clearType, djLevel, pgreat, great, missCount, playCount));
                }
            }
        }
        return result;
    }

    private String getCol(CSVRecord row, String col) {
        try {
            return row.get(col);
        } catch (Exception e) {
            return null;
        }
    }

    private int parseIntSafe(String val) {
        if (val == null || val.isBlank() || val.equals("---")) return 0;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return 0;
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

    public record ImportCsvRequest(String token, String csvText) {
    }
}
