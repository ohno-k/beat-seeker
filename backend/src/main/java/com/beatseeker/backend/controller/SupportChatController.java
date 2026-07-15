package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.AppNotification;
import com.beatseeker.backend.entity.SupportChatMessage;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.AppNotificationRepository;
import com.beatseeker.backend.repository.SupportChatMessageRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.AdminAuthService;
import com.beatseeker.backend.service.EmailService;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 【クラスの役割】 ログインユーザーと運営との「お問い合わせチャット」API。
 *
 * <p>大会機能の TL チャット ({@link CompetitionTlController} / {@link CompetitionAdminController}) と
 * 同じ発想だが、こちらはスレッドが <b>ユーザー単位</b> (ログイン済みユーザーごとに 1 会話)。
 *
 * <p>エンドポイント:
 * <ul>
 *   <li>ユーザー向け (要ログイン):
 *     <ul>
 *       <li>{@code GET  /api/support/chat}             … 自分のスレッドと未読件数</li>
 *       <li>{@code POST /api/support/chat}             … 運営へメッセージ送信 (運営にメール通知)</li>
 *       <li>{@code POST /api/support/chat/mark-read}   … 運営からの返信を既読化</li>
 *     </ul>
 *   </li>
 *   <li>運営向け (要ログイン + 管理者判定):
 *     <ul>
 *       <li>{@code GET  /api/support/admin/threads}                    … 全スレッド一覧 (未読数付き)</li>
 *       <li>{@code GET  /api/support/admin/threads/{userId}}           … 指定ユーザーの会話</li>
 *       <li>{@code POST /api/support/admin/threads/{userId}/reply}     … 返信 (ユーザーへアプリ内通知)</li>
 *       <li>{@code POST /api/support/admin/threads/{userId}/mark-read} … ユーザー発言を既読化</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>認可: SecurityConfig 側で {@code /api/support/**} を {@code authenticated()} にガード。
 * 管理者エンドポイントは本 Controller 内で {@link AdminAuthService#isAdmin(User)} を追加検証する。
 */
@RestController
@RequestMapping("/api/support")
public class SupportChatController {

    /** 本文の最大保存長。超過分は切り詰める。 */
    private static final int MAX_BODY_LENGTH = 2000;

    private final SupportChatMessageRepository chatRepository;
    private final UserRepository userRepository;
    private final AppNotificationRepository notificationRepository;
    private final AdminAuthService adminAuthService;
    private final EmailService emailService;

    public SupportChatController(SupportChatMessageRepository chatRepository,
                                 UserRepository userRepository,
                                 AppNotificationRepository notificationRepository,
                                 AdminAuthService adminAuthService,
                                 EmailService emailService) {
        this.chatRepository = chatRepository;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.adminAuthService = adminAuthService;
        this.emailService = emailService;
    }

    // ── ユーザー向け ─────────────────────────────────────────

    /**
     * 【メソッドの役割】 ログインユーザー自身のお問い合わせスレッドを古い順で返す。
     *
     * <p>レスポンス: {@code { messages:[{id,sender,body,createdAt}], unreadCount:N }}。
     * {@code unreadCount} は運営からの未読返信 (sender=admin かつ read_by_user=false) 件数。
     */
    @GetMapping("/chat")
    public ResponseEntity<Map<String, Object>> getMyChat(Authentication auth) {
        User me = getUser(auth);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (SupportChatMessage m : chatRepository.findByUserOrderByCreatedAtAsc(me)) {
            messages.add(chatMap(m));
        }
        long unread = chatRepository.countByUserAndSenderAndReadByUserFalse(me, "admin");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("messages", messages);
        root.put("unreadCount", unread);
        return ResponseEntity.ok(root);
    }

    /**
     * 【メソッドの役割】 ログインユーザーから運営へお問い合わせを送信する。
     *
     * <p>保存後、運営 (管理者ユーザー) のメールアドレス宛に「新着メッセージ」を非同期通知する。
     * メール失敗は握り潰してメッセージ保存自体は成功させる。
     */
    @PostMapping("/chat")
    @Transactional
    public ResponseEntity<Map<String, Object>> postMyChat(Authentication auth, @RequestBody ChatRequest req) {
        User me = getUser(auth);
        String body = normalizeBody(req);
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "メッセージを入力してください"));
        }

        SupportChatMessage m = new SupportChatMessage();
        m.setUser(me);
        m.setSender("user");
        m.setBody(body);
        m.setReadByAdmin(false); // 運営はまだ未読
        m.setReadByUser(true);   // 自分の発言は既読扱い
        m = chatRepository.save(m);

        // 運営 (管理者ユーザー) にメール通知。失敗してもメッセージ送信は成功扱い。
        final String notifyBody = body;
        try {
            userRepository.findById(adminAuthService.getAdminUserId()).ifPresent(admin -> {
                if (admin.getEmail() != null && !admin.getEmail().isBlank()) {
                    emailService.sendSupportChatNotification(
                            admin.getEmail(),
                            me.getDisplayName(),
                            me.getIidxId(),
                            notifyBody);
                }
            });
        } catch (Exception e) {
            System.err.println("Failed to enqueue support chat notification: " + e.getMessage());
        }

        return ResponseEntity.ok(chatMap(m));
    }

    /**
     * 【メソッドの役割】 運営からの返信 (sender="admin") をすべて既読 (read_by_user=true) にする。
     * ユーザーがウィジェットを開いたときに呼ぶ想定 (未読バッジのクリア用)。
     */
    @PostMapping("/chat/mark-read")
    @Transactional
    public ResponseEntity<Map<String, Object>> markMyChatRead(Authentication auth) {
        User me = getUser(auth);
        int updated = 0;
        for (SupportChatMessage m : chatRepository.findByUserOrderByCreatedAtAsc(me)) {
            if ("admin".equals(m.getSender()) && !m.isReadByUser()) {
                m.setReadByUser(true);
                chatRepository.save(m);
                updated++;
            }
        }
        return ResponseEntity.ok(Map.of("markedRead", updated));
    }

    // ── 運営向け ─────────────────────────────────────────────

    /**
     * 【メソッドの役割】 全ユーザーのお問い合わせスレッド一覧を返す (管理画面のスレッド一覧用)。
     *
     * <p>レスポンス: {@code [ { userId, displayName, iidxId, danRank, arenaRank, messageCount,
     * unreadCount, lastMessageBody, lastMessageAt, lastSender } ]}。最終メッセージが新しい順。
     * {@code unreadCount} は当該ユーザーの未読 (sender=user かつ read_by_admin=false) 件数。
     */
    @GetMapping("/admin/threads")
    public ResponseEntity<List<Map<String, Object>>> getThreads(Authentication auth) {
        requireAdmin(auth);

        // user_id → メッセージ列 (古い順) に振り分け。挿入順 = 会話の発生順を保つため LinkedHashMap。
        Map<Long, List<SupportChatMessage>> byUser = new LinkedHashMap<>();
        Map<Long, User> userById = new LinkedHashMap<>();
        for (SupportChatMessage m : chatRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"))) {
            User u = m.getUser();
            byUser.computeIfAbsent(u.getId(), k -> new ArrayList<>()).add(m);
            userById.putIfAbsent(u.getId(), u);
        }

        List<Map<String, Object>> threads = new ArrayList<>();
        for (Map.Entry<Long, List<SupportChatMessage>> e : byUser.entrySet()) {
            List<SupportChatMessage> msgs = e.getValue();
            User u = userById.get(e.getKey());
            int unread = 0;
            for (SupportChatMessage m : msgs) {
                if ("user".equals(m.getSender()) && !m.isReadByAdmin()) unread++;
            }
            SupportChatMessage last = msgs.get(msgs.size() - 1);

            Map<String, Object> thread = new LinkedHashMap<>();
            thread.put("userId", u.getId());
            thread.put("displayName", u.getDisplayName());
            thread.put("iidxId", u.getIidxId());
            thread.put("danRank", u.getDanRank());
            thread.put("arenaRank", u.getArenaRank());
            thread.put("messageCount", msgs.size());
            thread.put("unreadCount", unread);
            thread.put("lastMessageBody", last.getBody());
            thread.put("lastMessageAt", last.getCreatedAt());
            thread.put("lastSender", last.getSender());
            threads.add(thread);
        }

        // 最終メッセージが新しい順 (最近やり取りしたスレッドを上に)。
        threads.sort((a, b) -> {
            Comparable<Object> at = asComparable(a.get("lastMessageAt"));
            Object bt = b.get("lastMessageAt");
            if (at == null) return bt == null ? 0 : 1;
            if (bt == null) return -1;
            return bt.equals(at) ? 0 : -at.compareTo(bt);
        });
        return ResponseEntity.ok(threads);
    }

    /**
     * 【メソッドの役割】 指定ユーザーのお問い合わせ会話を古い順で返す。
     */
    @GetMapping("/admin/threads/{userId}")
    public ResponseEntity<List<Map<String, Object>>> getThread(Authentication auth, @PathVariable Long userId) {
        requireAdmin(auth);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SupportChatMessage m : chatRepository.findByUserOrderByCreatedAtAsc(target)) {
            out.add(chatMap(m));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 【メソッドの役割】 運営から指定ユーザーへ返信を送信する (sender="admin")。
     *
     * <p>返信保存後、ユーザーにアプリ内通知 (ベル) を 1 件作成して気付けるようにする。
     */
    @PostMapping("/admin/threads/{userId}/reply")
    @Transactional
    public ResponseEntity<Map<String, Object>> reply(Authentication auth,
                                                     @PathVariable Long userId,
                                                     @RequestBody ChatRequest req) {
        requireAdmin(auth);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        String body = normalizeBody(req);
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "メッセージを入力してください"));
        }

        SupportChatMessage m = new SupportChatMessage();
        m.setUser(target);
        m.setSender("admin");
        m.setBody(body);
        m.setReadByAdmin(true);  // 運営自身の発言
        m.setReadByUser(false);  // ユーザーはまだ未読
        m = chatRepository.save(m);

        // ユーザーにアプリ内通知 (ベル)。失敗しても返信保存は成功扱い。
        try {
            AppNotification notification = new AppNotification();
            notification.setRecipient(target);
            notification.setType("SUPPORT_REPLY");
            notification.setMessage("運営からお問い合わせの返信が届きました");
            notificationRepository.save(notification);
        } catch (Exception e) {
            System.err.println("Failed to create support reply notification: " + e.getMessage());
        }

        return ResponseEntity.ok(chatMap(m));
    }

    /**
     * 【メソッドの役割】 指定ユーザーのユーザー発メッセージをすべて既読 (read_by_admin=true) にする。
     * 管理画面でそのスレッドを開いたときに呼ぶ想定 (未読バッジのクリア用)。
     */
    @PostMapping("/admin/threads/{userId}/mark-read")
    @Transactional
    public ResponseEntity<Map<String, Object>> markThreadRead(Authentication auth, @PathVariable Long userId) {
        requireAdmin(auth);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        int updated = 0;
        for (SupportChatMessage m : chatRepository.findByUserOrderByCreatedAtAsc(target)) {
            if ("user".equals(m.getSender()) && !m.isReadByAdmin()) {
                m.setReadByAdmin(true);
                chatRepository.save(m);
                updated++;
            }
        }
        return ResponseEntity.ok(Map.of("markedRead", updated));
    }

    // ── 内部ヘルパ ───────────────────────────────────────────

    /** 認証情報から User を取り出す。JwtAuthFilter が principal に iidxId(String) を格納している。 */
    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /** 管理者権限を検証する。非管理者なら例外。 */
    private void requireAdmin(Authentication auth) {
        User user = getUser(auth);
        if (!adminAuthService.isAdmin(user)) {
            throw new RuntimeException("Access Denied: Admin privileges required.");
        }
    }

    /** リクエスト本文を trim + 最大長にクランプ。空なら null を返す。 */
    private String normalizeBody(ChatRequest req) {
        if (req == null || req.body() == null || req.body().isBlank()) return null;
        String body = req.body().trim();
        return body.length() > MAX_BODY_LENGTH ? body.substring(0, MAX_BODY_LENGTH) : body;
    }

    /** チャットメッセージ 1 件を表示用 Map に整形。 */
    private static Map<String, Object> chatMap(SupportChatMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("sender", m.getSender());
        map.put("body", m.getBody());
        map.put("createdAt", m.getCreatedAt());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Comparable<Object> asComparable(Object o) {
        return o instanceof Comparable ? (Comparable<Object>) o : null;
    }

    /** お問い合わせ / 返信の送信リクエスト。{@code body} が本文。 */
    public record ChatRequest(String body) {}
}
