package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.TierVote;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.TierVoteRepository;
import com.beatseeker.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.Collections;

@RestController
@RequestMapping("/api/tier-votes")
public class TierVoteController {

    private static final Set<String> VALID_VOTES = buildValidVotes();

    private static Set<String> buildValidVotes() {
        Set<String> votes = new HashSet<>(Set.of("PROMOTE", "DEMOTE", "STAY"));
        // Tier placement values for Uncategorized charts: 11.0 ~ 13.0 in 0.1 steps
        for (int i = 110; i <= 130; i++) {
            votes.add(String.format("%.1f", i / 10.0));
        }
        return Collections.unmodifiableSet(votes);
    }

    private final TierVoteRepository tierVoteRepository;
    private final UserRepository userRepository;

    public TierVoteController(TierVoteRepository tierVoteRepository, UserRepository userRepository) {
        this.tierVoteRepository = tierVoteRepository;
        this.userRepository = userRepository;
    }

    /**
     * Cast or update a tier vote for a chart.
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> vote(Authentication auth, @RequestBody VoteRequest request) {
        Long userId = getUserId(auth);

        if (!VALID_VOTES.contains(request.vote())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid vote value"));
        }

        Optional<TierVote> existing = tierVoteRepository
                .findByUserIdAndTitleAndDifficultyName(userId, request.title(), request.difficultyName());

        TierVote vote;
        if (existing.isPresent()) {
            vote = existing.get();
            vote.setVote(request.vote());
            vote.setVotedAt(LocalDateTime.now());
        } else {
            vote = new TierVote();
            vote.setUserId(userId);
            vote.setTitle(request.title());
            vote.setDifficultyName(request.difficultyName());
            vote.setVote(request.vote());
            vote.setVotedAt(LocalDateTime.now());
        }

        tierVoteRepository.save(vote);
        return ResponseEntity.ok(Map.of("message", "投票しました"));
    }

    /**
     * Remove a tier vote.
     */
    @DeleteMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteVote(
            Authentication auth,
            @RequestParam String title,
            @RequestParam String difficultyName) {

        Long userId = getUserId(auth);
        tierVoteRepository.findByUserIdAndTitleAndDifficultyName(userId, title, difficultyName)
                .ifPresent(tierVoteRepository::delete);

        return ResponseEntity.ok(Map.of("message", "投票を取り消しました"));
    }

    /**
     * Get aggregated vote counts for all charts (public).
     */
    @GetMapping("/all")
    public ResponseEntity<List<Map<String, Object>>> getAllVotes() {
        List<Map<String, Object>> raw = tierVoteRepository.findAggregatedVotes();

        // Aggregate per (title, difficultyName)
        Map<String, Map<String, Object>> songMap = new LinkedHashMap<>();

        for (Map<String, Object> row : raw) {
            String title = (String) row.get("title");
            String difficultyName = (String) row.get("difficultyName");
            String voteType = (String) row.get("vote");
            int count = ((Number) row.get("count")).intValue();

            String key = title + "|" + difficultyName;
            songMap.computeIfAbsent(key, k -> {
                Map<String, Object> m = new HashMap<>();
                m.put("title", title);
                m.put("difficultyName", difficultyName);
                return m;
            });
            songMap.get(key).put(voteType, count);
        }

        return ResponseEntity.ok(new ArrayList<>(songMap.values()));
    }

    /**
     * Get current user's own votes (authenticated).
     */
    @GetMapping("/mine")
    public ResponseEntity<List<Map<String, Object>>> getMyVotes(Authentication auth) {
        Long userId = getUserId(auth);
        List<TierVote> votes = tierVoteRepository.findByUserId(userId);

        List<Map<String, Object>> result = votes.stream().map(v -> {
            Map<String, Object> m = new HashMap<>();
            m.put("title", v.getTitle());
            m.put("difficultyName", v.getDifficultyName());
            m.put("vote", v.getVote());
            return m;
        }).toList();

        return ResponseEntity.ok(result);
    }

    private Long getUserId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        User user = userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return user.getId();
    }

    public record VoteRequest(String title, String difficultyName, String vote) {}
}
