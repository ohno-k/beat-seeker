package com.beatseeker.backend.controller;

import com.beatseeker.backend.config.JwtUtil;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
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

            return ResponseEntity.ok(Map.of(
                    "id", user.getId(),
                    "displayName", user.getDisplayName() != null ? user.getDisplayName() : "",
                    "iidxId", user.getIidxId(),
                    "danRank", user.getDanRank() != null ? user.getDanRank() : "",
                    "arenaRank", user.getArenaRank() != null ? user.getArenaRank() : "",
                    "playSide", user.getPlaySide() != null ? user.getPlaySide() : "1P"));
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

        if (request.displayName() != null)
            user.setDisplayName(request.displayName());
        if (request.iidxId() != null)
            user.setIidxId(request.iidxId());
        if (request.danRank() != null)
            user.setDanRank(request.danRank());
        if (request.arenaRank() != null)
            user.setArenaRank(request.arenaRank());
        if (request.playSide() != null)
            user.setPlaySide(request.playSide());

        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Profile updated successfully"));
    }
}
