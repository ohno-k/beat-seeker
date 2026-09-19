package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.AppNotification;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.AppNotificationRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.PushNotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【クラスの役割】 ログインユーザー宛ての「アプリ内通知（ベルアイコンのドロップダウン）」
 * を管理する REST コントローラ。
 *
 * 通知は {@code AppNotification} エンティティとして DB に蓄積されており、
 * 本コントローラを介してフロントが「未読件数」「通知一覧」を取得し、
 * 既読化することができる。
 *
 * 主要エンドポイント:
 *  - {@code GET  /api/notifications}             … 通知一覧と未読件数を取得
 *  - {@code POST /api/notifications/read-all}    … 全通知を既読化
 *  - {@code GET  /api/notifications/push-status} … ブラウザ通知（Web Push）の稼働状態を返す
 *
 * 認証: 全エンドポイントが JWT 認証必須。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    /** 通知エンティティの永続化 Repository。 */
    private final AppNotificationRepository notificationRepository;
    /** 認証プリンシパル（iidxId）から User エンティティを引くための Repository。 */
    private final UserRepository userRepository;
    /** Web Push の稼働状態と VAPID 公開鍵の配布元。 */
    private final PushNotificationService pushNotificationService;

    /**
     * 【コンストラクタ】 Spring が DI で各 Repository / Service を注入する。
     */
    public NotificationController(AppNotificationRepository notificationRepository,
                                  UserRepository userRepository,
                                  PushNotificationService pushNotificationService) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.pushNotificationService = pushNotificationService;
    }

    /**
     * 【メソッドの役割】 ブラウザ通知（Web Push）の稼働状態を返す。
     *
     * 「通知が全く来ない」ときに、原因がサーバー側（VAPID 鍵未設定）なのか
     * クライアント側（未購読・許可拒否）なのかを切り分けるための診断 API。
     * 併せて、クライアントが {@code pushManager.subscribe} に使うべき VAPID 公開鍵を配る。
     * フロントに鍵をハードコードしていると、鍵をローテーションしたときに
     * サーバーとずれて全ユーザーの購読が黙って無効になるため、サーバー配布を正とする。
     *
     * @param auth 認証情報
     * @return {@code {serverEnabled, publicKey, subscribed}}
     */
    @GetMapping("/push-status")
    public ResponseEntity<Map<String, Object>> pushStatus(Authentication auth) {
        User user = getUser(auth);
        String subscription = user.getPushSubscription();
        return ResponseEntity.ok(Map.of(
                // サーバーに VAPID 鍵が投入されていて送信できる状態か
                "serverEnabled", pushNotificationService.isEnabled(),
                // クライアントが subscribe に使うべき公開鍵
                "publicKey", pushNotificationService.getPublicKey(),
                // このユーザーの購読が保存されているか
                "subscribed", subscription != null && !subscription.isBlank()
        ));
    }

    /**
     * 【メソッドの役割】 ログインユーザー宛の通知一覧と未読件数を返す。
     *
     * @param auth Spring Security が注入する認証情報（JWT フィルタ経由）
     * @return {@code {"notifications": [...], "unreadCount": N}} 形式の Map
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getNotifications(Authentication auth) {
        User user = getUser(auth);
        // 作成日時の新しい順で全件取得（件数制限は現状なし）
        List<AppNotification> notifications = notificationRepository.findByRecipientOrderByCreatedAtDesc(user);
        // 未読バッジ表示のため、未読だけを別途カウントする
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

    /**
     * 【メソッドの役割】 ログインユーザーの未読通知をまとめて既読化する。
     *
     * ベルドロップダウンを開いた瞬間等にフロントから呼び出される想定。
     * {@code @Transactional} を付与しているのは、Repository 側の更新クエリが
     * トランザクション内で実行される必要があるため。
     *
     * @param auth 認証情報
     * @return 固定メッセージ入りの OK レスポンス
     */
    @PostMapping("/read-all")
    @Transactional
    public ResponseEntity<Map<String, Object>> markAllRead(Authentication auth) {
        User user = getUser(auth);
        notificationRepository.markAllReadByRecipient(user);
        return ResponseEntity.ok(Map.of("message", "全て既読にしました"));
    }

    /**
     * 認証情報からユーザーエンティティを取り出すヘルパ。
     * 未認証の場合は例外を投げて 500/401 に変換される。
     *
     * @param auth Spring Security の Authentication
     * @return 認証済みユーザー
     */
    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        // JwtAuthFilter が principal に iidxId（String）を格納している
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}
