package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.OptionVote;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.OptionVoteRepository;
import com.beatseeker.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/votes")
public class OptionVoteController {

    private final OptionVoteRepository optionVoteRepository;
    private final UserRepository userRepository;

    public OptionVoteController(OptionVoteRepository optionVoteRepository, UserRepository userRepository) {
        this.optionVoteRepository = optionVoteRepository;
        this.userRepository = userRepository;
    }

    /**
     * Cast or update a vote for a song's recommended option.
     * Normalizes the vote to 1P perspective before saving.
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> vote(
            Authentication auth,
            @RequestBody VoteRequest request) {

        User user = getUser(auth);

        // Normalize the option to 1P perspective
        String normalizedOption = normalizeToFirstPlayer(request.optionType(), user.getPlaySide());

        // Upsert: find existing vote or create new
        Optional<OptionVote> existing = optionVoteRepository
                .findByUserAndTitleAndDifficultyName(user, request.title(), request.difficultyName());

        OptionVote vote;
        if (existing.isPresent()) {
            vote = existing.get();
            vote.setOptionType(normalizedOption);
            vote.setVotedAt(LocalDateTime.now());
        } else {
            vote = new OptionVote();
            vote.setUser(user);
            vote.setTitle(request.title());
            vote.setDifficultyName(request.difficultyName());
            vote.setOptionType(normalizedOption);
        }

        optionVoteRepository.save(vote);

        return ResponseEntity.ok(Map.of("message", "投票しました", "normalizedOption", normalizedOption));
    }

    /**
     * Delete a vote for a song.
     */
    @DeleteMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteVote(
            Authentication auth,
            @RequestParam String title,
            @RequestParam String difficultyName) {

        User user = getUser(auth);
        Optional<OptionVote> existing = optionVoteRepository
                .findByUserAndTitleAndDifficultyName(user, title, difficultyName);

        existing.ifPresent(optionVoteRepository::delete);

        return ResponseEntity.ok(Map.of("message", "投票を取り消しました"));
    }

    /**
     * Get vote results for a song, converting to the viewer's play side
     * perspective.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getVotes(
            @RequestParam String title,
            @RequestParam String difficultyName,
            Authentication auth) {

        List<OptionVote> votes = optionVoteRepository.findByTitleAndDifficultyName(title, difficultyName);

        // Determine viewer's play side
        String viewerSide = "1P";
        String myVote = null;
        if (auth != null && auth.isAuthenticated()) {
            try {
                User viewer = getUser(auth);
                viewerSide = viewer.getPlaySide() != null ? viewer.getPlaySide() : "1P";

                // Find viewer's own vote
                Optional<OptionVote> myVoteOpt = optionVoteRepository
                        .findByUserAndTitleAndDifficultyName(viewer, title, difficultyName);
                if (myVoteOpt.isPresent()) {
                    // Convert back to viewer's perspective
                    myVote = convertToViewerPerspective(myVoteOpt.get().getOptionType(), viewerSide);
                }
            } catch (Exception ignored) {
            }
        }

        // Count votes, converting to viewer's perspective
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("REGULAR", 0);
        counts.put("MIRROR", 0);
        counts.put("RANDOM", 0);
        counts.put("R-RANDOM", 0);
        counts.put("S-RANDOM", 0);

        for (OptionVote v : votes) {
            String displayed = convertToViewerPerspective(v.getOptionType(), viewerSide);
            counts.put(displayed, counts.getOrDefault(displayed, 0) + 1);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("counts", counts);
        result.put("totalVotes", votes.size());
        result.put("myVote", myVote);

        return ResponseEntity.ok(result);
    }

    /**
     * Normalize an option choice to 1P perspective for storage.
     * If the voter is 2P: REGULAR <-> MIRROR swap
     */
    private String normalizeToFirstPlayer(String optionType, String playSide) {
        if ("2P".equals(playSide)) {
            if ("REGULAR".equals(optionType))
                return "MIRROR";
            if ("MIRROR".equals(optionType))
                return "REGULAR";
        }
        return optionType;
    }

    /**
     * Convert a stored (1P-perspective) option to the viewer's perspective.
     * If the viewer is 2P: REGULAR <-> MIRROR swap
     */
    private String convertToViewerPerspective(String storedOption, String viewerSide) {
        if ("2P".equals(viewerSide)) {
            if ("REGULAR".equals(storedOption))
                return "MIRROR";
            if ("MIRROR".equals(storedOption))
                return "REGULAR";
        }
        return storedOption;
    }

    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public record VoteRequest(String title, String difficultyName, String optionType) {
    }
}
