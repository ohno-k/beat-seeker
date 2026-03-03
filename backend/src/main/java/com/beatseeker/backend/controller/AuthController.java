package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        if (userRepository.findByIidxId(request.iidxId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "IIDX ID is already registered"));
        }

        User user = new User();
        user.setIidxId(request.iidxId());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName() != null ? request.displayName() : "No Name");
        user.setDanRank(request.danRank());
        user.setArenaRank(request.arenaRank());
        userRepository.save(user);

        // Auto login
        authenticateUser(user, httpRequest);
        return ResponseEntity.ok(Map.of("message", "Registration successful"));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        Optional<User> optionalUser = userRepository.findByIidxId(request.iidxId());

        if (optionalUser.isEmpty()
                || !passwordEncoder.matches(request.password(), optionalUser.get().getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Invalid IIDX ID or Password"));
        }

        authenticateUser(optionalUser.get(), httpRequest);
        return ResponseEntity.ok(Map.of("message", "Login successful"));
    }

    private void authenticateUser(User user, HttpServletRequest request) {
        Authentication auth = new UsernamePasswordAuthenticationToken(user.getIidxId(), null, Collections.emptyList());
        SecurityContext sc = SecurityContextHolder.getContext();
        sc.setAuthentication(auth);
        HttpSession session = request.getSession(true);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, sc);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        String iidxId = (String) auth.getPrincipal(); // Authenticated principal is iidxId
        User user = userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "displayName", user.getDisplayName() != null ? user.getDisplayName() : "",
                "iidxId", user.getIidxId(),
                "danRank", user.getDanRank() != null ? user.getDanRank() : "",
                "arenaRank", user.getArenaRank() != null ? user.getArenaRank() : ""));
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
            user.setIidxId(request.iidxId()); // Note: changing iidxId might log them out if not handled, but keeping it
                                              // simple
        if (request.danRank() != null)
            user.setDanRank(request.danRank());
        if (request.arenaRank() != null)
            user.setArenaRank(request.arenaRank());

        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Profile updated successfully"));
    }
}
