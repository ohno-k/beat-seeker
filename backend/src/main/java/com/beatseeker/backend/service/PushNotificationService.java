package com.beatseeker.backend.service;

import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.security.Security;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class PushNotificationService {

    @Value("${vapid.public.key}")
    private String publicKey;

    @Value("${vapid.private.key}")
    private String privateKey;

    @Value("${vapid.subject}")
    private String subject;

    private PushService pushService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        pushService = new PushService(publicKey, privateKey, subject);
    }

    public void sendNotification(String subscriptionJson, String title, String body, String url) {
        try {
            Subscription subscription = objectMapper.readValue(subscriptionJson, Subscription.class);
            Map<String, String> payload = Map.of(
                    "title", title,
                    "body", body,
                    "url", url);
            String payloadJson = objectMapper.writeValueAsString(payload);

            Notification notification = new Notification(subscription, payloadJson);
            pushService.send(notification);
        } catch (Exception e) {
            System.err.println("Error sending push notification: " + e.getMessage());
        }
    }
}
