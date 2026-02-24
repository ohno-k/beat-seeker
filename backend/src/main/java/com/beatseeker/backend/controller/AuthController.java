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
                "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : ""));
    }
}
