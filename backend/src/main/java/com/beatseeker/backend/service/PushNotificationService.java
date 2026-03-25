package com.beatseeker.backend.service;

import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.security.Security;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

@Service
public class PushNotificationService {

    @Value("${vapid.public.key}")
    private String publicKey;

    @Value("${vapid.private.key}")
    private String privateKey;

    @Value("${vapid.subject}")
    private String subject;

    private PushService pushService;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @PostConstruct
    public void init() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        pushService = new PushService(publicKey, privateKey, subject);
    }

    public void sendNotification(String subscriptionJson, String title, String body, String url) {
        try {
            sendNotificationWithEx(subscriptionJson, title, body, url);
        } catch (Exception e) {
            System.err.println("Error sending push notification (suppressed): " + e.getMessage());
        }
    }

    public void sendNotificationWithEx(String subscriptionJson, String title, String body, String url) throws Exception {
        Subscription subscription = objectMapper.readValue(subscriptionJson, Subscription.class);
        Map<String, String> payload = Map.of(
                "title", title,
                "body", body,
                "url", url);
        String payloadJson = objectMapper.writeValueAsString(payload);

        Notification notification = new Notification(subscription, payloadJson);
        HttpResponse response = pushService.send(notification);
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode >= 400) {
            throw new RuntimeException("Push Server returned " + statusCode + " " + response.getStatusLine().getReasonPhrase());
        }
    }
}
