package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.AppNotification;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.AppNotificationRepository;
import com.beatseeker.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final AppNotificationRepository notificationRepository;
    private final UserRepository userRepository;

    public NotificationController(AppNotificationRepository notificationRepository, UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getNotifications(Authentication auth) {
        User user = getUser(auth);
        List<AppNotification> notifications = notificationRepository.findByRecipientOrderByCreatedAtDesc(user);
        long unreadCount = notificationRepository.countByRecipientAndReadFalse(user);

        List<Map<String, Object>> items = notifications.stream().map(n -> Map.<String, Object>of(
                "id", n.getId(),
                "type", n.getType(),
                "message", n.getMessage(),
                "read", n.isRead(),
                "createdAt", n.getCreatedAt().toString()
        )).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("notifications", items, "unreadCount", unreadCount));
    }

    @PostMapping("/read-all")
    @Transactional
    public ResponseEntity<Map<String, Object>> markAllRead(Authentication auth) {
        User user = getUser(auth);
        notificationRepository.markAllReadByRecipient(user);
        return ResponseEntity.ok(Map.of("message", "全て既読にしました"));
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
