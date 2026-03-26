package com.beatseeker.backend.controller;

import com.beatseeker.backend.config.JwtUtil;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.EmailService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil, EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.emailService = emailService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        try {
            if (userRepository.findByIidxId(request.iidxId()).isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("message", "IIDX ID is already registered"));
            }

            User user = new User();
            user.setIidxId(request.iidxId());
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            user.setDisplayName(request.displayName() != null ? request.displayName() : "No Name");
            user.setDanRank(request.danRank());
            user.setArenaRank(request.arenaRank());
            user.setPlaySide(request.playSide() != null ? request.playSide() : "1P");
            if (request.email() != null && !request.email().isBlank()) {
                user.setEmail(request.email().trim().toLowerCase());
            }
            userRepository.save(user);

            String token = jwtUtil.generateToken(user.getIidxId());
            return ResponseEntity.ok(Map.of("message", "Registration successful", "token", token));
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException
                | org.hibernate.NonUniqueResultException e) {
            System.err.println("Duplicate user found for IIDX ID: " + request.iidxId());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message",
                            "Database integrity error: Multiple users found with same IIDX ID. Please contact admin."));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Registration failed: " + e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        try {
            Optional<User> optionalUser = userRepository.findByIidxId(request.iidxId());

            if (optionalUser.isEmpty()
                    || !passwordEncoder.matches(request.password(), optionalUser.get().getPasswordHash())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid IIDX ID or Password"));
            }

            String token = jwtUtil.generateToken(optionalUser.get().getIidxId());
            return ResponseEntity.ok(Map.of("message", "Login successful", "token", token));
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException
                | org.hibernate.NonUniqueResultException e) {
            System.err.println("Duplicate user found for login attempt: " + request.iidxId());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message",
                            "Database integrity error: Multiple users found with same IIDX ID. Please contact admin."));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Login failed: " + e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(Authentication auth) {
        try {
            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(401).build();
            }

            String iidxId = (String) auth.getPrincipal();
            User user = userRepository.findByIidxId(iidxId)
                    .orElseThrow(() -> new RuntimeException("User not found: " + iidxId));

            java.util.Map<String, Object> responseBody = new java.util.HashMap<>();
            responseBody.put("id", user.getId());
            responseBody.put("displayName", user.getDisplayName() != null ? user.getDisplayName() : "");
            responseBody.put("iidxId", user.getIidxId());
            responseBody.put("danRank", user.getDanRank() != null ? user.getDanRank() : "");
            responseBody.put("arenaRank", user.getArenaRank() != null ? user.getArenaRank() : "");
            responseBody.put("playSide", user.getPlaySide() != null ? user.getPlaySide() : "1P");
            responseBody.put("privacyLevel", user.getPrivacyLevel());
            responseBody.put("language", user.getLanguage() != null ? user.getLanguage() : "ja");
            responseBody.put("showRateTier", user.getShowRateTier() != null ? user.getShowRateTier() : true);
            responseBody.put("lastUploadedAt", user.getLastUploadedAt());
            responseBody.put("email", user.getEmail() != null ? user.getEmail() : "");
            return ResponseEntity.ok(responseBody);
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException
                | org.hibernate.NonUniqueResultException e) {
            System.err.println("Duplicate user found in getCurrentUser for IIDX ID: "
                    + (auth != null ? auth.getPrincipal() : "unknown"));
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Database integrity error: Multiple users found. Please contact admin."));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Error fetching user: " + e.getMessage()));
        }
    }

    @PutMapping("/me/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(Authentication auth,
            @RequestBody ProfileUpdateRequest request) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        String iidxId = (String) auth.getPrincipal();
        User user = userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Password change logic
        if (request.currentPassword() != null && request.newPassword() != null) {
            if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("message", "現在のパスワードが正しくありません。"));
            }
            user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        }

        if (request.displayName() != null)
            user.setDisplayName(request.displayName());
        if (request.danRank() != null)
            user.setDanRank(request.danRank());
        if (request.arenaRank() != null)
            user.setArenaRank(request.arenaRank());
        if (request.playSide() != null)
            user.setPlaySide(request.playSide());
        if (request.privacyLevel() != null)
            user.setPrivacyLevel(request.privacyLevel());
        if (request.language() != null)
            user.setLanguage(request.language());
        if (request.showRateTier() != null)
            user.setShowRateTier(request.showRateTier());
        if (request.email() != null && !request.email().isBlank()) {
            String newEmail = request.email().trim().toLowerCase();
            Optional<User> existing = userRepository.findByEmail(newEmail);
            if (existing.isPresent() && !existing.get().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("message", "そのメールアドレスは既に使用されています。"));
            }
            user.setEmail(newEmail);
        }

        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Profile updated successfully"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(@RequestBody Map<String, String> payload) {
        String iidxId = payload.get("iidxId");
        String email = payload.get("email");

        if (iidxId == null || email == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "IIDX IDとメールアドレスを入力してください。"));
        }

        Optional<User> optUser = userRepository.findByIidxId(iidxId.trim());
        if (optUser.isEmpty() || !email.trim().equalsIgnoreCase(optUser.get().getEmail())) {
            // Return generic message to prevent user enumeration
            return ResponseEntity.ok(Map.of("message", "該当するアカウントが見つかった場合、パスワードリセット手順を送信します。"));
        }

        User user = optUser.get();
        String token = UUID.randomUUID().toString();
        user.setPasswordResetToken(token);
        user.setPasswordResetExpiredAt(LocalDateTime.now().plusHours(1));
        userRepository.save(user);

        try {
            emailService.sendPasswordResetEmail(user.getEmail(), iidxId.trim(), token);
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to send password reset email: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "メールの送信に失敗しました。しばらく経ってから再試行してください。"));
        }

        return ResponseEntity.ok(Map.of("message", "パスワードリセットの手順をメールで送信しました。"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody Map<String, String> payload) {
        String token = payload.get("token");
        String newPassword = payload.get("newPassword");

        if (token == null || newPassword == null || newPassword.length() < 4) {
            return ResponseEntity.badRequest().body(Map.of("message", "無効なリクエストです。"));
        }

        Optional<User> optUser = userRepository.findByPasswordResetToken(token);
        if (optUser.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "無効または期限切れのトークンです。"));
        }

        User user = optUser.get();
        if (user.getPasswordResetExpiredAt() == null || LocalDateTime.now().isAfter(user.getPasswordResetExpiredAt())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "トークンの有効期限が切れています。再度リセットを申請してください。"));
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordResetToken(null);
        user.setPasswordResetExpiredAt(null);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "パスワードをリセットしました。新しいパスワードでログインしてください。"));
    }
}
