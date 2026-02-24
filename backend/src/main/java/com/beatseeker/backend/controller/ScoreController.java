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

import java.util.List;
import java.util.Map;

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

        // Replace all existing scores with the new upload
        scoreRepository.deleteByUser(user);

        List<Score> scores = requests.stream().map(req -> {
            Score score = new Score();
            score.setUser(user);
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
        List<Score> scores = scoreRepository.findByUserOrderByUploadedAtDesc(user);

        List<Map<String, Object>> result = scores.stream().map(s -> Map.<String, Object>of(
                "id", s.getId(),
                "title", s.getTitle() != null ? s.getTitle() : "",
                "difficultyName", s.getDifficultyName() != null ? s.getDifficultyName() : "",
                "difficultyLevel", s.getDifficultyLevel() != null ? s.getDifficultyLevel() : 0,
                "score", s.getScore() != null ? s.getScore() : 0,
                "clearType", s.getClearType() != null ? s.getClearType() : "",
                "djLevel", s.getDjLevel() != null ? s.getDjLevel() : "")).toList();

        return ResponseEntity.ok(result);
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
}
