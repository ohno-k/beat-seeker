package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.TierComment;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.TierCommentRepository;
import com.beatseeker.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tier-comments")
public class TierCommentController {

    private final TierCommentRepository tierCommentRepository;
    private final UserRepository userRepository;

    public TierCommentController(TierCommentRepository tierCommentRepository, UserRepository userRepository) {
        this.tierCommentRepository = tierCommentRepository;
        this.userRepository = userRepository;
    }

    /**
     * Get aggregate comment stats (count and max time) for all charts
     */
    @GetMapping("/stats")
    public ResponseEntity<List<Map<String, Object>>> getCommentStats() {
        return ResponseEntity.ok(tierCommentRepository.findCommentStats());
    }

    /**
     * Get comments for a specific chart
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getComments(
            @RequestParam String title,
            @RequestParam String difficultyName) {
            
        List<TierComment> comments = tierCommentRepository.findByTitleAndDifficultyNameOrderByCreatedAtAsc(title, difficultyName);
        
        // Fetch users to attach display names
        Set<Long> userIds = comments.stream().map(TierComment::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<Map<String, Object>> result = comments.stream().map(c -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.getId());
            map.put("userId", c.getUserId());
            User u = userMap.get(c.getUserId());
            map.put("displayName", u != null ? u.getDisplayName() : "Unknown");
            map.put("title", c.getTitle());
            map.put("difficultyName", c.getDifficultyName());
            map.put("content", c.getContent());
            map.put("createdAt", c.getCreatedAt());
            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    /**
     * Post a new comment
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> postComment(
            Authentication auth,
            @RequestBody CommentRequest request) {
            
        Long userId = getUserId(auth);

        if (request.content() == null || request.content().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Comment content cannot be empty"));
        }
        
        if (request.content().length() > 1000) {
            return ResponseEntity.badRequest().body(Map.of("error", "Comment is too long (max 1000 chars)"));
        }

        TierComment comment = new TierComment();
        comment.setUserId(userId);
        comment.setTitle(request.title());
        comment.setDifficultyName(request.difficultyName());
        comment.setContent(request.content().trim());
        comment.setCreatedAt(LocalDateTime.now());
        
        tierCommentRepository.save(comment);

        return ResponseEntity.ok(Map.of("message", "Comment posted successfully"));
    }

    /**
     * Delete a comment (must be the owner)
     */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteComment(
            Authentication auth,
            @PathVariable Long id) {
            
        Long userId = getUserId(auth);
        
        Optional<TierComment> commentOpt = tierCommentRepository.findById(id);
        if (commentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        TierComment comment = commentOpt.get();
        if (!comment.getUserId().equals(userId)) {
             return ResponseEntity.status(403).body(Map.of("error", "Not authorized to delete this comment"));
        }
        
        tierCommentRepository.delete(comment);
        return ResponseEntity.ok(Map.of("message", "Comment deleted"));
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

    public record CommentRequest(String title, String difficultyName, String content) {}
}
