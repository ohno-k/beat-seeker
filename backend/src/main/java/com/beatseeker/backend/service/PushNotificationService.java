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

/**
 * 【Service の役割】 Web Push（VAPID）を用いたブラウザ通知送信サービス。
 *
 * 責務:
 *  - 起動時に BouncyCastle を JCE プロバイダとして登録し、{@link PushService} を初期化する
 *  - クライアント Service Worker から購読された Subscription JSON に対して
 *    タイトル・本文・遷移 URL のペイロードを暗号化付きで送信する
 *  - 送信失敗は上位処理をブロックしないよう、例外握り潰し版（{@link #sendNotification}）と
 *    例外送出版（{@link #sendNotificationWithEx}）を分離している
 *
 * 依存:
 *  - nl.martijndwars.webpush: Web Push プロトコル実装
 *  - BouncyCastle: VAPID の ECDSA 署名に必要な JCE プロバイダ
 *  - application.yml の {@code vapid.public.key} / {@code vapid.private.key} / {@code vapid.subject}
 */
@Service
public class PushNotificationService {

    /** VAPID 公開鍵（Base64URL）。クライアント側にも登録され、pushManager.subscribe で使われる */
    @Value("${vapid.public.key}")
    private String publicKey;

    /** VAPID 秘密鍵（Base64URL）。サーバーから Push サービスに対して認証するために使う */
    @Value("${vapid.private.key}")
    private String privateKey;

    /** VAPID subject（通知 owner の連絡先。mailto: URL が推奨） */
    @Value("${vapid.subject}")
    private String subject;

    /** 実際に Push サービスへ HTTPS POST を行う送信クライアント */
    private PushService pushService;

    /**
     * Subscription JSON のパーサ。未知フィールドが混ざっていても無視するよう設定。
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 【メソッドの役割】 Bean 初期化時に一度だけ呼ばれる初期化処理。
     *
     * - BouncyCastle を JCE プロバイダとして追加（ECDSA 署名で利用）
     * - {@link PushService} を鍵情報付きで生成
     *
     * @throws Exception 鍵のデコードや BouncyCastle 登録に失敗した場合
     */
    @PostConstruct
    public void init() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        // VAPID 鍵が未設定（環境変数 VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY 未投入など）の場合は
        // Push を無効化した状態で起動する。空鍵のまま PushService を生成すると起動時に例外となり
        // アプリ全体が起動不能（＝デプロイ失敗）になるため、ここでガードする。
        // （R2 ストレージ未設定時に他機能へ影響させない設計方針に合わせている）
        if (publicKey == null || publicKey.isBlank() || privateKey == null || privateKey.isBlank()) {
            System.err.println("[PushNotificationService] VAPID keys are not configured; push notifications are disabled.");
            return;
        }
        pushService = new PushService(publicKey, privateKey, subject);
    }

    /**
     * 【メソッドの役割】 Push 通知を送信する（例外握り潰し版）。
     *
     * スコア更新後通知などバックグラウンド処理から呼ばれ、送信エラーは
     * stderr にログ出力するのみでアプリ本体の処理は止めない。
     *
     * @param subscriptionJson ブラウザ側 pushManager.subscribe() から返った Subscription の JSON
     * @param title            通知タイトル
     * @param body             通知本文
     * @param url              通知クリック時の遷移先 URL
     */
    public void sendNotification(String subscriptionJson, String title, String body, String url) {
        try {
            sendNotificationWithEx(subscriptionJson, title, body, url);
        } catch (Exception e) {
            System.err.println("Error sending push notification (suppressed): " + e.getMessage());
        }
    }

    /**
     * 【メソッドの役割】 Push 通知を送信する（例外送出版）。
     *
     * 処理の流れ:
     *  - 手順1: Subscription JSON をデシリアライズして購読情報を得る
     *  - 手順2: title/body/url を Map で組み、JSON 文字列化してペイロードとする
     *  - 手順3: {@link PushService#send(Notification)} で Push サービスへ HTTPS POST
     *  - 手順4: 4xx/5xx が返った場合は例外を投げて呼び出し元で失敗を検知可能にする
     *
     * @param subscriptionJson 購読情報の JSON
     * @param title            通知タイトル
     * @param body             通知本文
     * @param url              クリック時の遷移先
     * @throws Exception デシリアライズ失敗、送信失敗、Push サーバーが 4xx/5xx を返した場合
     */
    public void sendNotificationWithEx(String subscriptionJson, String title, String body, String url) throws Exception {
        if (pushService == null) {
            // VAPID 鍵未設定で Push 無効の状態。呼び出し元で失敗として扱えるよう明示的に投げる。
            throw new IllegalStateException("Push notifications are disabled (VAPID keys not configured).");
        }
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
