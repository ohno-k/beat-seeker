package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;

    public AuthController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Returns current logged-in user info.
     * Returns 401 (handled by SecurityConfig) if not authenticated.
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(
            @AuthenticationPrincipal OAuth2User principal) {

        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String googleId = principal.getAttribute("sub");
        User user = userRepository.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "displayName", user.getDisplayName() != null ? user.getDisplayName() : "",
                "email", user.getEmail(),
                "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : "",
                "iidxId", user.getIidxId() != null ? user.getIidxId() : "",
                "danRank", user.getDanRank() != null ? user.getDanRank() : "",
                "arenaRank", user.getArenaRank() != null ? user.getArenaRank() : ""));
    }

    /**
     * Updates current user's profile information.
     */
    @PutMapping("/me/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestBody ProfileUpdateRequest request) {

        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String googleId = principal.getAttribute("sub");
        User user = userRepository.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (request.displayName() != null)
            user.setDisplayName(request.displayName());
        if (request.iidxId() != null)
            user.setIidxId(request.iidxId());
        if (request.danRank() != null)
            user.setDanRank(request.danRank());
        if (request.arenaRank() != null)
            user.setArenaRank(request.arenaRank());

        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Profile updated successfully"));
    }
}
