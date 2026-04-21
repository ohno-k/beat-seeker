package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.FriendRequest;
import com.beatseeker.backend.entity.Friendship;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.VirtualRival;
import com.beatseeker.backend.repository.FriendRequestRepository;
import com.beatseeker.backend.repository.FriendshipRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.VirtualRivalRepository;
import com.beatseeker.backend.service.PushNotificationService;
import com.beatseeker.backend.service.TopRankersBeatPtService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/friends")
public class FriendController {

    private final UserRepository userRepository;
    private final FriendRequestRepository friendRequestRepository;
    private final FriendshipRepository friendshipRepository;
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;
    private final ScoreRepository scoreRepository;
    private final VirtualRivalRepository virtualRivalRepository;
    private final PushNotificationService pushNotificationService;
    private final TopRankersBeatPtService topRankersBeatPtService;

    public FriendController(UserRepository userRepository,
            FriendRequestRepository friendRequestRepository,
            FriendshipRepository friendshipRepository,
            ScoreHistoryLogRepository scoreHistoryLogRepository,
            ScoreRepository scoreRepository,
            VirtualRivalRepository virtualRivalRepository,
            PushNotificationService pushNotificationService,
            TopRankersBeatPtService topRankersBeatPtService) {
        this.userRepository = userRepository;
        this.friendRequestRepository = friendRequestRepository;
        this.friendshipRepository = friendshipRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.scoreRepository = scoreRepository;
        this.virtualRivalRepository = virtualRivalRepository;
        this.pushNotificationService = pushNotificationService;
        this.topRankersBeatPtService = topRankersBeatPtService;
    }

    @GetMapping("/test")
    public String test() {
        return "FriendController is active";
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getFriends(Authentication auth) {
        User user = getUser(auth);
        List<Friendship> friendships = friendshipRepository.findByUser(user);

        List<Map<String, Object>> result = friendships.stream().map(f -> {
            User friend = f.getFriend();
            Map<String, Object> map = new HashMap<>();
            map.put("id", friend.getId());
            map.put("displayName", friend.getDisplayName());
            map.put("iidxId", friend.getIidxId());
            map.put("lastUploadedAt", friend.getLastUploadedAt());
            map.put("privacyLevel", friend.getPrivacyLevel() != null ? friend.getPrivacyLevel() : 0);

            // Get latest BEAT-PT from history
            List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(friend);
            if (!logs.isEmpty()) {
                ScoreHistoryLog latest = logs.get(logs.size() - 1);
                map.put("totalBeatPt", latest.getTotalBeatPt());
            } else {
                map.put("totalBeatPt", 0);
            }
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> searchUsers(Authentication auth, @RequestParam String query) {
        User currentUser = getUser(auth);
        String trimmedQuery = query.trim();

        if (trimmedQuery.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        String variant = trimmedQuery;
        if (trimmedQuery.matches("\\d{8}")) {
            variant = trimmedQuery.substring(0, 4) + "-" + trimmedQuery.substring(4);
        } else if (trimmedQuery.matches("\\d{4}-\\d{4}")) {
            variant = trimmedQuery.replace("-", "");
        }

        List<User> users = userRepository.searchUsers(trimmedQuery, variant).stream()
                .limit(20)
                .collect(Collectors.toList());

        List<Map<String, Object>> result = users.stream().map(u -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.getId());
            map.put("displayName", u.getDisplayName());
            map.put("iidxId", u.getIidxId());
            map.put("lastUploadedAt", u.getLastUploadedAt());

            List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(u);
            if (!logs.isEmpty()) {
                ScoreHistoryLog latest = logs.get(logs.size() - 1);
                map.put("totalBeatPt", latest.getTotalBeatPt());
            } else {
                map.put("totalBeatPt", 0);
            }

            // Check if already friends or if request sent
            boolean isFriend = friendshipRepository.findByUserAndFriend(currentUser, u).isPresent();
            boolean hasSentRequest = friendRequestRepository.findBySenderAndReceiverAndStatus(currentUser, u, "PENDING")
                    .isPresent();

            map.put("isFriend", isFriend);
            map.put("hasSentRequest", hasSentRequest);

            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @PostMapping("/request")
    @Transactional
    public ResponseEntity<Map<String, Object>> sendRequest(Authentication auth,
            @RequestBody Map<String, Object> payload) {
        User sender = getUser(auth);
        Long receiverId = payload.get("receiverId") != null ? Long.valueOf(payload.get("receiverId").toString()) : null;
        String message = payload.get("message") != null ? payload.get("message").toString() : null;
        User receiver = userRepository.findById(receiverId).orElseThrow();

        if (sender.getId().equals(receiver.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "自分自身にフレンド申請は送れません。"));
        }

        if (friendshipRepository.findByUserAndFriend(sender, receiver).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("message", "既にフレンドです。"));
        }

        if (friendRequestRepository.findBySenderAndReceiverAndStatus(sender, receiver, "PENDING").isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("message", "既に申請済みです。"));
        }

        FriendRequest request = new FriendRequest();
        request.setSender(sender);
        request.setReceiver(receiver);
        request.setMessage(message);
        request.setStatus("PENDING");
        friendRequestRepository.save(request);

        // Send push notification to receiver
        if (receiver.getPushSubscription() != null) {
            pushNotificationService.sendNotification(
                    receiver.getPushSubscription(),
                    "ライバル申請が届きました",
                    sender.getDisplayName() + "さんからライバル申請が届きました。",
                    "/friends");
        }

        return ResponseEntity.ok(Map.of("message", "フレンド申請を送信しました。"));
    }

    @GetMapping("/requests/pending")
    public ResponseEntity<List<Map<String, Object>>> getPendingRequests(Authentication auth) {
        User user = getUser(auth);
        List<FriendRequest> requests = friendRequestRepository.findByReceiverAndStatus(user, "PENDING");

        List<Map<String, Object>> result = requests.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.getId());
            map.put("senderId", r.getSender().getId());
            map.put("senderName", r.getSender().getDisplayName());
            map.put("senderIidxId", r.getSender().getIidxId());
            map.put("message", r.getMessage());
            map.put("createdAt", r.getCreatedAt());
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @PostMapping("/requests/{id}/accept")
    @Transactional
    public ResponseEntity<Map<String, Object>> acceptRequest(Authentication auth, @PathVariable Long id) {
        User receiver = getUser(auth);
        FriendRequest request = friendRequestRepository.findById(id).orElseThrow();

        if (!request.getReceiver().getId().equals(receiver.getId())) {
            return ResponseEntity.status(403).build();
        }

        request.setStatus("ACCEPTED");
        friendRequestRepository.save(request);

        // Create bidirectional friendships
        User sender = request.getSender();

        if (friendshipRepository.findByUserAndFriend(sender, receiver).isEmpty()) {
            Friendship f1 = new Friendship();
            f1.setUser(sender);
            f1.setFriend(receiver);
            friendshipRepository.save(f1);
        }

        if (friendshipRepository.findByUserAndFriend(receiver, sender).isEmpty()) {
            Friendship f2 = new Friendship();
            f2.setUser(receiver);
            f2.setFriend(sender);
            friendshipRepository.save(f2);
        }

        return ResponseEntity.ok(Map.of("message", "フレンド申請を承認しました。"));
    }

    @PostMapping("/requests/{id}/reject")
    @Transactional
    public ResponseEntity<Map<String, Object>> rejectRequest(Authentication auth, @PathVariable Long id) {
        User receiver = getUser(auth);
        FriendRequest request = friendRequestRepository.findById(id).orElseThrow();

        if (!request.getReceiver().getId().equals(receiver.getId())) {
            return ResponseEntity.status(403).build();
        }

        request.setStatus("REJECTED");
        friendRequestRepository.save(request);

        return ResponseEntity.ok(Map.of("message", "フレンド申請を拒否しました。"));
    }

    @GetMapping("/scores")
    public ResponseEntity<List<Map<String, Object>>> getFriendScores(
            Authentication auth,
            @RequestParam String title,
            @RequestParam String difficultyName) {
        User user = getUser(auth);
        List<Friendship> friendships = friendshipRepository.findByUser(user);

        List<Map<String, Object>> result = friendships.stream()
                .map(f -> {
                    User friend = f.getFriend();
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", friend.getId());
                    map.put("displayName", friend.getDisplayName());
                    map.put("iidxId", friend.getIidxId());
                    Integer privacyLevel = friend.getPrivacyLevel() != null ? friend.getPrivacyLevel() : 0;
                    map.put("privacyLevel", privacyLevel);

                    if (privacyLevel != 2) {
                        scoreRepository.findFirstByUserAndTitleAndDifficultyNameOrderByUploadedAtDesc(
                                friend, title, difficultyName)
                                .ifPresent(score -> {
                                    map.put("score", score.getScore());
                                    map.put("clearType", score.getClearType());
                                    map.put("djLevel", score.getDjLevel());
                                    map.put("pgreat", score.getPgreat());
                                    map.put("great", score.getGreat());
                                    map.put("missCount", score.getMissCount());
                                    map.put("difficultyLevel", score.getDifficultyLevel());
                                });
                    }

                    return map;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{friendId}/scores")
    public ResponseEntity<List<Map<String, Object>>> getFriendAllScores(
            Authentication auth,
            @PathVariable Long friendId) {
        User user = getUser(auth);
        User friend = userRepository.findById(friendId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Check if there's a friendship
        if (friendshipRepository.findByUserAndFriend(user, friend).isEmpty()) {
            return ResponseEntity.status(403).build();
        }

        Integer privacyLevel = friend.getPrivacyLevel() != null ? friend.getPrivacyLevel() : 0;
        if (privacyLevel == 2) {
            return ResponseEntity.status(403).build();
        }

        List<Score> scores = scoreRepository.findByUserOrderByUploadedAtAsc(friend);

        List<Map<String, Object>> result = scores.stream().map(s -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", s.getId());
            map.put("title", s.getTitle());
            map.put("difficultyName", s.getDifficultyName());
            map.put("difficultyLevel", s.getDifficultyLevel());
            map.put("score", s.getScore());
            map.put("clearType", s.getClearType());
            map.put("djLevel", s.getDjLevel());
            map.put("pgreat", s.getPgreat());
            map.put("great", s.getGreat());
            map.put("missCount", s.getMissCount());
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{friendId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> removeFriend(Authentication auth, @PathVariable Long friendId) {
        User user = getUser(auth);
        User friend = userRepository.findById(friendId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        friendshipRepository.findByUserAndFriend(user, friend)
                .ifPresent(friendshipRepository::delete);
        friendshipRepository.findByUserAndFriend(friend, user)
                .ifPresent(friendshipRepository::delete);

        return ResponseEntity.ok(Map.of("message", "フレンドを削除しました。"));
    }

    @GetMapping("/virtual-rivals")
    public ResponseEntity<List<Map<String, Object>>> getVirtualRivals(Authentication auth) {
        User user = getUser(auth);
        List<VirtualRival> rivals = virtualRivalRepository.findByOwner(user);

        Map<String, Number> beatPtLookup = new HashMap<>();
        for (Map<String, Object> row : topRankersBeatPtService.getRanking()) {
            beatPtLookup.put(row.get("versionNum") + "\0" + row.get("prefectureFileNum"),
                    (Number) row.get("beatPt"));
        }
        Map<String, Number> ratePtLookup = new HashMap<>();
        for (Map<String, Object> row : topRankersBeatPtService.getRateRanking()) {
            ratePtLookup.put(row.get("versionNum") + "\0" + row.get("prefectureFileNum"),
                    (Number) row.get("ratePt"));
        }

        List<Map<String, Object>> result = rivals.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.getId());
            map.put("versionNum", r.getVersionNum());
            map.put("versionName", r.getVersionName());
            map.put("prefectureFileNum", r.getPrefectureFileNum());
            map.put("prefectureName", r.getPrefectureName());
            map.put("createdAt", r.getCreatedAt());
            String key = r.getVersionNum() + "\0" + r.getPrefectureFileNum();
            Number beatPt = beatPtLookup.get(key);
            Number ratePt = ratePtLookup.get(key);
            map.put("totalBeatPt", beatPt != null ? beatPt.doubleValue() : 0.0);
            map.put("totalRatePt", ratePt != null ? ratePt.doubleValue() : 0.0);
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/virtual-rivals/status")
    public ResponseEntity<Map<String, Object>> getVirtualRivalStatus(Authentication auth,
            @RequestParam Integer versionNum,
            @RequestParam Integer prefectureFileNum) {
        User user = getUser(auth);
        boolean registered = virtualRivalRepository
                .findByOwnerAndVersionNumAndPrefectureFileNum(user, versionNum, prefectureFileNum)
                .isPresent();
        return ResponseEntity.ok(Map.of("registered", registered));
    }

    @PostMapping("/virtual-rivals")
    @Transactional
    public ResponseEntity<Map<String, Object>> addVirtualRival(Authentication auth,
            @RequestBody Map<String, Object> payload) {
        User user = getUser(auth);
        if (payload.get("versionNum") == null || payload.get("prefectureFileNum") == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "versionNum と prefectureFileNum は必須です。"));
        }
        Integer versionNum = Integer.valueOf(payload.get("versionNum").toString());
        Integer prefectureFileNum = Integer.valueOf(payload.get("prefectureFileNum").toString());
        String versionName = payload.get("versionName") != null ? payload.get("versionName").toString() : null;
        String prefectureName = payload.get("prefectureName") != null ? payload.get("prefectureName").toString() : null;

        if (virtualRivalRepository
                .findByOwnerAndVersionNumAndPrefectureFileNum(user, versionNum, prefectureFileNum)
                .isPresent()) {
            return ResponseEntity.ok(Map.of("message", "既に登録済みです。", "registered", true));
        }

        VirtualRival rival = new VirtualRival();
        rival.setOwner(user);
        rival.setVersionNum(versionNum);
        rival.setPrefectureFileNum(prefectureFileNum);
        rival.setVersionName(versionName);
        rival.setPrefectureName(prefectureName);
        virtualRivalRepository.save(rival);

        return ResponseEntity.ok(Map.of("message", "ライバルに登録しました。", "registered", true));
    }

    @DeleteMapping("/virtual-rivals")
    @Transactional
    public ResponseEntity<Map<String, Object>> removeVirtualRival(Authentication auth,
            @RequestParam Integer versionNum,
            @RequestParam Integer prefectureFileNum) {
        User user = getUser(auth);
        virtualRivalRepository.deleteByOwnerAndVersionNumAndPrefectureFileNum(user, versionNum, prefectureFileNum);
        return ResponseEntity.ok(Map.of("message", "ライバルを解除しました。", "registered", false));
    }

    @PostMapping("/push-subscription")
    @Transactional
    public ResponseEntity<Map<String, String>> updatePushSubscription(Authentication auth,
            @RequestBody Map<String, String> payload) {
        User user = getUser(auth);
        String subscription = payload.get("subscription");
        user.setPushSubscription(subscription);
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    @PostMapping("/push-test")
    public ResponseEntity<Map<String, String>> testPushNotification(Authentication auth) {
        try {
            User user = getUser(auth);
            if (user.getPushSubscription() != null && !user.getPushSubscription().isEmpty()) {
                pushNotificationService.sendNotificationWithEx(
                        user.getPushSubscription(),
                        "テスト通知",
                        "Push通知が正常に設定されています！",
                        "/");
                return ResponseEntity.ok(Map.of("status", "success", "message", "テスト通知を送信しました。"));
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "サーバー側に通知の購読情報が登録されていません。再度「通知を有効にする」をやり直すか、端末の設定を確認してください。"));
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "サーバー側でエラーが発生しました: " + e.getMessage()));
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
}
