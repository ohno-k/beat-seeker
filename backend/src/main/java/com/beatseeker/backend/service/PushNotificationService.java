package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.UserRepository;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *  - 期限切れ購読（404/410）を検出したら {@code users.push_subscription} を掃除する
 *    （{@link #sendToUser}）。放置すると「届かない購読」に送り続けることになる
 *
 * 依存:
 *  - nl.martijndwars.webpush: Web Push プロトコル実装
 *  - BouncyCastle: VAPID の ECDSA 署名に必要な JCE プロバイダ
 *  - application.yml の {@code vapid.public.key} / {@code vapid.private.key} / {@code vapid.subject}
 *
 * <p><b>運用上の注意:</b> VAPID 秘密鍵は環境変数 {@code VAPID_PRIVATE_KEY} でのみ渡す
 * （application.yml の既定値は空）。未投入のまま起動すると Push は全面的に無効化され、
 * {@link #sendNotification} は静かに何もしない。無効状態は起動ログ（ERROR）と
 * {@code GET /api/notifications/push-status} の両方から確認できる。
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    /** VAPID 公開鍵（Base64URL）。クライアント側にも配布され、pushManager.subscribe で使われる */
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

    /** 期限切れ購読を掃除するために使う（{@link #sendToUser} 専用）。 */
    private final UserRepository userRepository;

    /**
     * Subscription JSON のパーサ。未知フィールドが混ざっていても無視するよう設定。
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 【コンストラクタ】 Spring が DI で Repository を注入する。
     */
    public PushNotificationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

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
            // 無効化は「通知が全く来ない」という形でしか表面化せず気付きにくいので ERROR で出す。
            log.error("[PushNotificationService] VAPID keys are not configured; push notifications are DISABLED. "
                    + "環境変数 VAPID_PRIVATE_KEY（と必要なら VAPID_PUBLIC_KEY）を設定してください。");
            return;
        }
        pushService = new PushService(publicKey, privateKey, subject);
        log.info("[PushNotificationService] Web Push enabled (publicKey={}...)",
                publicKey.substring(0, Math.min(12, publicKey.length())));
    }

    /**
     * 【メソッドの役割】 Push 送信が有効かどうか（VAPID 鍵が投入されているか）を返す。
     *
     * 管理画面・診断 API から「サーバー側で無効になっていないか」を確認するために使う。
     *
     * @return 送信可能なら true
     */
    public boolean isEnabled() {
        return pushService != null;
    }

    /**
     * 【メソッドの役割】 クライアントが {@code pushManager.subscribe} で使うべき VAPID 公開鍵を返す。
     *
     * フロント側にハードコードすると鍵ローテーション時にサーバーとずれて全ユーザーの購読が
     * 無効になるため、サーバーが配る値を正とする。
     *
     * @return VAPID 公開鍵（Base64URL）。未設定なら空文字
     */
    public String getPublicKey() {
        return publicKey != null ? publicKey : "";
    }

    /**
     * 【メソッドの役割】 ユーザー宛に Push 通知を送る（購読の解決と掃除つき）。
     *
     * 処理の流れ:
     *  - 手順1: 購読未登録なら何もしない（通知を有効にしていないユーザー）
     *  - 手順2: 送信を試みる
     *  - 手順3: 404/410（購読が失効・削除済み）なら {@code push_subscription} を NULL に戻す。
     *           こうしておくと次回ログイン時の再購読で正しい値に入れ替わる
     *
     * 送信失敗で呼び出し元の処理（スコア保存やリーグ開始）を巻き戻さない。
     *
     * @param recipient 宛先ユーザー
     * @param title     通知タイトル
     * @param body      通知本文
     * @param url       通知クリック時の遷移先 URL
     * @return 実際に送信できたら true
     */
    public boolean sendToUser(User recipient, String title, String body, String url) {
        if (recipient == null) return false;
        String subscription = recipient.getPushSubscription();
        if (subscription == null || subscription.isBlank()) {
            return false; // 通知を有効にしていないユーザー
        }
        if (pushService == null) {
            log.warn("Push 無効のため送信をスキップ: userId={} title={}", recipient.getId(), title);
            return false;
        }
        try {
            int status = send(subscription, title, body, url);
            if (status == 404 || status == 410) {
                // 購読が失効している。次回ログイン時に再購読されるよう空にしておく。
                log.info("失効した Push 購読をクリア: userId={} status={}", recipient.getId(), status);
                recipient.setPushSubscription(null);
                userRepository.save(recipient);
                return false;
            }
            if (status >= 400) {
                log.warn("Push 送信が拒否された: userId={} status={} title={}", recipient.getId(), status, title);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Push 送信に失敗: userId={} title={} : {}", recipient.getId(), title, e.toString());
            return false;
        }
    }

    /**
     * 【メソッドの役割】 Push 通知を送信する（例外握り潰し版）。
     *
     * 購読 JSON を直接持っている呼び出し元向け。ユーザーが分かっている場合は
     * 失効購読の掃除も行う {@link #sendToUser} を使うこと。
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
            log.warn("Push 送信に失敗（握り潰し）: title={} : {}", title, e.toString());
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
        int statusCode = send(subscriptionJson, title, body, url);
        if (statusCode >= 400) {
            throw new RuntimeException("Push Server returned " + statusCode);
        }
    }

    /**
     * 【メソッドの役割】 実際の送信処理。HTTP ステータスコードをそのまま返す。
     *
     * 404/410 の判定を呼び出し元でしたいので、ここでは 4xx/5xx を例外にせずコードを返す。
     *
     * @param subscriptionJson 購読情報の JSON
     * @param title            通知タイトル
     * @param body             通知本文
     * @param url              クリック時の遷移先
     * @return Push サーバーの HTTP ステータスコード
     * @throws Exception 鍵未設定、デシリアライズ失敗、通信失敗
     */
    private int send(String subscriptionJson, String title, String body, String url) throws Exception {
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
        return response.getStatusLine().getStatusCode();
    }
}
