package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.EmailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/kofi")
public class KofiWebhookController {

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long ADMIN_USER_ID = 18L;

    @Value("${KOFI_VERIFICATION_TOKEN:}")
    private String kofiVerificationToken;

    // Pattern to match supporter token like BS-XXXXXXXX
    private static final Pattern TOKEN_PATTERN = Pattern.compile("BS-[A-Z0-9]{8}");

    public KofiWebhookController(UserRepository userRepository, EmailService emailService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    /**
     * Ko-fi Webhook receiver.
     * Ko-fi sends POST with form-encoded "data" field containing JSON.
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(@RequestParam("data") String data) {
        try {
            JsonNode json = objectMapper.readTree(data);

            // Verify the webhook is from Ko-fi
            String verificationToken = json.has("verification_token")
                    ? json.get("verification_token").asText() : "";
            if (!kofiVerificationToken.isEmpty()
                    && !kofiVerificationToken.equals(verificationToken)) {
                System.err.println("[Ko-fi Webhook] Invalid verification token");
                return ResponseEntity.status(403).body("Invalid verification token");
            }

            // Extract message field
            String message = json.has("message") ? json.get("message").asText() : "";
            String type = json.has("type") ? json.get("type").asText() : "";
            String fromName = json.has("from_name") ? json.get("from_name").asText() : "";

            System.out.println("[Ko-fi Webhook] Received: type=" + type
                    + ", from=" + fromName + ", message=" + message);

            // Find supporter token in the message
            Matcher matcher = TOKEN_PATTERN.matcher(message);
            if (matcher.find()) {
                String supporterToken = matcher.group();
                Optional<User> optUser = userRepository.findBySupporterToken(supporterToken);

                if (optUser.isPresent()) {
                    User user = optUser.get();
                    user.setIsSupporter(true);
                    userRepository.save(user);
                    System.out.println("[Ko-fi Webhook] Supporter activated: "
                            + user.getIidxId() + " (token: " + supporterToken + ")");

                    // Notify admin via email
                    userRepository.findById(ADMIN_USER_ID).ifPresent(admin -> {
                        if (admin.getEmail() != null) {
                            emailService.sendSupporterActivatedNotification(
                                    admin.getEmail(),
                                    user.getDisplayName() != null ? user.getDisplayName() : user.getIidxId(),
                                    user.getIidxId() != null ? user.getIidxId() : "");
                        }
                    });
                } else {
                    System.err.println("[Ko-fi Webhook] No user found for token: "
                            + supporterToken);
                }
            } else {
                System.err.println("[Ko-fi Webhook] No supporter token found in message: "
                        + message);
            }

            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            System.err.println("[Ko-fi Webhook] Error processing webhook: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.ok("OK"); // Always return 200 to Ko-fi
        }
    }
}
