package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.FriendRequestRepository;
import com.beatseeker.backend.repository.FriendshipRepository;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final ScoreRepository scoreRepository;
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;
    private final FriendshipRepository friendshipRepository;
    private final FriendRequestRepository friendRequestRepository;

    public UserController(UserRepository userRepository,
                          ScoreRepository scoreRepository,
                          ScoreHistoryLogRepository scoreHistoryLogRepository,
                          FriendshipRepository friendshipRepository,
                          FriendRequestRepository friendRequestRepository) {
        this.userRepository = userRepository;
        this.scoreRepository = scoreRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.friendshipRepository = friendshipRepository;
        this.friendRequestRepository = friendRequestRepository;
    }

    @GetMapping("/{userId}/profile")
    public ResponseEntity<Map<String, Object>> getPublicProfile(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        Integer privacyLevel = user.getPrivacyLevel() != null ? user.getPrivacyLevel() : 1;
        if (privacyLevel != 0) {
            return ResponseEntity.status(403).build();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("id", user.getId());
        body.put("displayName", user.getDisplayName() != null ? user.getDisplayName() : "");
        body.put("iidxId", user.getIidxId());
        body.put("danRank", user.getDanRank() != null ? user.getDanRank() : "");
        body.put("arenaRank", user.getArenaRank() != null ? user.getArenaRank() : "");
        body.put("playSide", user.getPlaySide() != null ? user.getPlaySide() : "1P");
        body.put("privacyLevel", privacyLevel);
        body.put("showRateTier", user.getShowRateTier() != null ? user.getShowRateTier() : true);
        body.put("isSupporter", user.getIsSupporter() != null ? user.getIsSupporter() : false);
        body.put("showSupporterBorder", user.getShowSupporterBorder() != null ? user.getShowSupporterBorder() : true);
        body.put("lastUploadedAt", user.getLastUploadedAt());
        body.put("totalBeatPt", user.getTotalBeatPt() != null ? user.getTotalBeatPt() : 0.0);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{userId}/scores")
    public ResponseEntity<List<Map<String, Object>>> getPublicScores(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        Integer privacyLevel = user.getPrivacyLevel() != null ? user.getPrivacyLevel() : 1;
        if (privacyLevel != 0) {
            return ResponseEntity.status(403).build();
        }

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

    @GetMapping("/{userId}/history")
    public ResponseEntity<List<Map<String, Object>>> getPublicHistory(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        Integer privacyLevel = user.getPrivacyLevel() != null ? user.getPrivacyLevel() : 1;
        if (privacyLevel != 0) {
            return ResponseEntity.status(403).build();
        }

        List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(user);
        List<Map<String, Object>> history = logs.stream().map(log -> {
            Map<String, Object> m = new HashMap<>();
            m.put("snapshotId", log.getId().toString());
            m.put("date", log.getUploadedAt().toString());
            m.put("totalScore", log.getTotalScore());
            m.put("fcCount", log.getFcCount());
            m.put("exhCount", log.getExhCount());
            m.put("hCount", log.getHCount());
            m.put("clearCount", log.getClearCount());
            m.put("easyCount", log.getEasyCount());
            m.put("aaaCount", log.getAaaCount());
            m.put("aaCount", log.getAaCount());
            m.put("aCount", log.getACount());
            m.put("totalBeatPt", log.getTotalBeatPt());
            m.put("beatPtIncrease", log.getBeatPtIncrease());
            m.put("updatedCount", log.getUpdatedCount());
            m.put("diffJson", log.getDiffJson());
            m.put("totalRatePt", log.getTotalRatePt());
            return m;
        }).toList();

        return ResponseEntity.ok(history);
    }

    /**
     * Returns the friendship / request status between the authenticated user
     * and the specified target user. Used by the dashboard to decide whether
     * to show the friend-request banner.
     *
     * Possible statuses:
     *   - "self"     : same user
     *   - "friend"   : already friends
     *   - "requested": pending request sent by current user
     *   - "incoming" : pending request received from target user
     *   - "none"     : no relationship
     */
    @GetMapping("/{userId}/friend-status")
    public ResponseEntity<Map<String, String>> getFriendStatus(Authentication auth, @PathVariable Long userId) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.ok(Map.of("status", "none"));
        }
        String iidxId = (String) auth.getPrincipal();
        User currentUser = userRepository.findByIidxId(iidxId).orElse(null);
        if (currentUser == null) return ResponseEntity.ok(Map.of("status", "none"));

        User target = userRepository.findById(userId).orElse(null);
        if (target == null) return ResponseEntity.notFound().build();

        if (currentUser.getId().equals(target.getId())) {
            return ResponseEntity.ok(Map.of("status", "self"));
        }
        if (friendshipRepository.findByUserAndFriend(currentUser, target).isPresent()) {
            return ResponseEntity.ok(Map.of("status", "friend"));
        }
        if (friendRequestRepository.findBySenderAndReceiverAndStatus(currentUser, target, "PENDING").isPresent()) {
            return ResponseEntity.ok(Map.of("status", "requested"));
        }
        if (friendRequestRepository.findBySenderAndReceiverAndStatus(target, currentUser, "PENDING").isPresent()) {
            return ResponseEntity.ok(Map.of("status", "incoming"));
        }
        return ResponseEntity.ok(Map.of("status", "none"));
    }
}
