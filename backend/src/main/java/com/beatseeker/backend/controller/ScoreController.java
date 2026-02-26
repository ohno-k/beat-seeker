package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/scores")
public class ScoreController {

    private final ScoreRepository scoreRepository;
    private final UserRepository userRepository;

    public ScoreController(ScoreRepository scoreRepository, UserRepository userRepository) {
        this.scoreRepository = scoreRepository;
        this.userRepository = userRepository;
    }

    /**
     * Upload (replace) all scores for the current user.
     * Accepts a JSON array of score objects.
     */
    @PostMapping("/upload")
    @Transactional
    public ResponseEntity<Map<String, Object>> uploadScores(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestBody List<ScoreUploadRequest> requests) {

        User user = getUser(principal);

        // Find previous latest snapshot to carry over memos
        Optional<Score> latestScoreOpt = scoreRepository.findFirstByUserOrderByUploadedAtDesc(user);
        Map<String, String> previousMemos = new HashMap<>();
        if (latestScoreOpt.isPresent() && latestScoreOpt.get().getSnapshotId() != null) {
            String prevSnapshotId = latestScoreOpt.get().getSnapshotId();
            List<Score> prevScores = scoreRepository.findByUserAndSnapshotId(user, prevSnapshotId);
            for (Score s : prevScores) {
                if (s.getMemo() != null && !s.getMemo().trim().isEmpty()) {
                    String key = s.getTitle() + "_" + s.getDifficultyName() + "_" + s.getDifficultyLevel();
                    previousMemos.put(key, s.getMemo());
                }
            }
        }

        String newSnapshotId = UUID.randomUUID().toString();

        List<Score> scores = requests.stream().map(req -> {
            Score score = new Score();
            score.setUser(user);
            score.setSnapshotId(newSnapshotId);
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

            // Carry over memo
            String key = req.title() + "_" + req.difficultyName() + "_" + req.difficultyLevel();
            if (previousMemos.containsKey(key)) {
                score.setMemo(previousMemos.get(key));
            }

            return score;
        }).toList();

        scoreRepository.saveAll(scores);

        return ResponseEntity.ok(Map.of(
                "saved", scores.size(),
                "message", "スコアを保存しました"));
    }

    /**
     * Get all scores for the current user.
     */
    @GetMapping("/me")
    public ResponseEntity<List<Map<String, Object>>> getMyScores(
            @AuthenticationPrincipal OAuth2User principal) {

        User user = getUser(principal);

        Optional<Score> latestScoreOpt = scoreRepository.findFirstByUserOrderByUploadedAtDesc(user);
        if (latestScoreOpt.isEmpty() || latestScoreOpt.get().getSnapshotId() == null) {
            return ResponseEntity.ok(List.of());
        }

        String latestSnapshotId = latestScoreOpt.get().getSnapshotId();
        List<Score> scores = scoreRepository.findByUserAndSnapshotId(user, latestSnapshotId);

        List<Map<String, Object>> result = scores.stream().map(s -> Map.<String, Object>of(
                "id", s.getId(),
                "title", s.getTitle() != null ? s.getTitle() : "",
                "difficultyName", s.getDifficultyName() != null ? s.getDifficultyName() : "",
                "difficultyLevel", s.getDifficultyLevel() != null ? s.getDifficultyLevel() : 0,
                "score", s.getScore() != null ? s.getScore() : 0,
                "clearType", s.getClearType() != null ? s.getClearType() : "",
                "djLevel", s.getDjLevel() != null ? s.getDjLevel() : "",
                "memo", s.getMemo() != null ? s.getMemo() : "")).toList();

        return ResponseEntity.ok(result);
    }

    /**
     * Get history aggregates for the current user.
     */
    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> getHistory(
            @AuthenticationPrincipal OAuth2User principal) {

        User user = getUser(principal);
        List<Score> allScores = scoreRepository.findByUserOrderByUploadedAtAsc(user);

        // Group by snapshotId (or uploadedAt if missing)
        Map<String, List<Score>> grouped = new LinkedHashMap<>();
        for (Score s : allScores) {
            String key = s.getSnapshotId() != null ? s.getSnapshotId() : s.getUploadedAt().toString();
            grouped.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(s);
        }

        List<Map<String, Object>> history = new java.util.ArrayList<>();

        for (Map.Entry<String, List<Score>> entry : grouped.entrySet()) {
            List<Score> snapshotScores = entry.getValue();
            if (snapshotScores.isEmpty())
                continue;

            String dateStr = snapshotScores.get(0).getUploadedAt().toString();

            long totalScore = 0;
            int fcCount = 0;
            int exhCount = 0;
            int hCount = 0;
            int clearCount = 0;
            int easyCount = 0;
            int aaaCount = 0;
            int aaCount = 0;
            int aCount = 0;

            for (Score s : snapshotScores) {
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

            Map<String, Object> snapshotData = new HashMap<>();
            snapshotData.put("snapshotId", entry.getKey());
            snapshotData.put("date", dateStr);
            snapshotData.put("totalScore", totalScore);
            snapshotData.put("fcCount", fcCount);
            snapshotData.put("exhCount", exhCount);
            snapshotData.put("hCount", hCount);
            snapshotData.put("clearCount", clearCount);
            snapshotData.put("easyCount", easyCount);
            snapshotData.put("aaaCount", aaaCount);
            snapshotData.put("aaCount", aaCount);
            snapshotData.put("aCount", aCount);

            history.add(snapshotData);
        }

        return ResponseEntity.ok(history);
    }

    /**
     * Update the memo for a specific score.
     */
    @PutMapping("/{id}/memo")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateMemo(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable Long id,
            @RequestBody MemoUpdateRequest request) {

        User user = getUser(principal);
        Score score = scoreRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Score not found"));

        if (!score.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).build();
        }

        score.setMemo(request.memo());
        scoreRepository.save(score);

        return ResponseEntity.ok(Map.of("message", "メモを保存しました"));
    }

    private User getUser(OAuth2User principal) {
        String googleId = principal.getAttribute("sub");
        return userRepository.findByGoogleId(googleId)
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
