package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.ShareToken;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ShareTokenRepository;
import com.beatseeker.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 【クラスの役割】 共有トークンの「発行・一覧・失効」を行う認証必須コントローラ。
 *
 * 公開ビュー側（未ログインで叩ける /api/share/{token}/...）は
 * {@link ShareViewController} に分離している。
 *
 * 主要エンドポイント:
 *  - {@code POST   /api/share/tokens}      … 発行
 *  - {@code GET    /api/share/tokens}      … 自分が発行した一覧
 *  - {@code DELETE /api/share/tokens/{id}} … 失効
 */
@RestController
@RequestMapping("/api/share/tokens")
public class ShareTokenController {

    private final ShareTokenRepository shareTokenRepository;
    private final UserRepository userRepository;

    public ShareTokenController(ShareTokenRepository shareTokenRepository,
                                UserRepository userRepository) {
        this.shareTokenRepository = shareTokenRepository;
        this.userRepository = userRepository;
    }

    /**
     * 【メソッドの役割】 共有トークンを発行する。
     *
     * リクエスト body:
     *  - {@code scopeDashboard} (boolean): ダッシュボードを公開するか
     *  - {@code scopeScores}    (boolean): スコア一覧を公開するか
     *  - {@code expiresIn}      (string):  "24h" | "1w" | "1m" | "unlimited"
     *
     * 両 scope とも false の場合は 400 を返す。
     */
    @PostMapping
    public ResponseEntity<?> issueToken(Authentication auth, @RequestBody IssueTokenRequest req) {
        User user = getUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        boolean dashboard = Boolean.TRUE.equals(req.scopeDashboard());
        boolean scores = Boolean.TRUE.equals(req.scopeScores());
        boolean history = Boolean.TRUE.equals(req.scopeHistory());
        boolean profile = Boolean.TRUE.equals(req.scopeProfile());
        if (!dashboard && !scores && !history && !profile) {
            return ResponseEntity.badRequest().body(Map.of("error", "公開範囲を1つ以上選択してください"));
        }

        LocalDateTime expiresAt = computeExpiresAt(req.expiresIn());

        ShareToken token = new ShareToken();
        token.setUser(user);
        token.setToken(UUID.randomUUID().toString());
        token.setScopeDashboard(dashboard);
        token.setScopeScores(scores);
        token.setScopeHistory(history);
        token.setScopeProfile(profile);
        token.setExpiresAt(expiresAt);
        token.setCreatedAt(LocalDateTime.now());
        ShareToken saved = shareTokenRepository.save(token);

        return ResponseEntity.ok(toMap(saved));
    }

    /**
     * 【メソッドの役割】 自分が発行した共有トークン一覧を返す（新しい順）。
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listTokens(Authentication auth) {
        User user = getUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> result = shareTokenRepository
                .findByUserOrderByCreatedAtDesc(user)
                .stream()
                .map(this::toMap)
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 共有トークンをソフト失効させる（DB レコードは保持）。
     *
     * 自分が発行したトークン以外は 403。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> revokeToken(Authentication auth, @PathVariable Long id) {
        User user = getUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        ShareToken token = shareTokenRepository.findById(id).orElse(null);
        if (token == null) return ResponseEntity.notFound().build();
        if (!token.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).build();
        }
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(LocalDateTime.now());
            shareTokenRepository.save(token);
        }
        return ResponseEntity.ok(toMap(token));
    }

    /**
     * 【メソッドの役割】 共有トークンを DB から完全削除する（一覧から消す）。
     *
     * 失効済み・期限切れのトークンを整理する用途。アクティブなトークンを誤って消すと
     * 共有先の URL が突然 404 になるため、サーバ側でアクティブ判定をブロックする。
     */
    @DeleteMapping("/{id}/permanent")
    public ResponseEntity<?> deleteToken(Authentication auth, @PathVariable Long id) {
        User user = getUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        ShareToken token = shareTokenRepository.findById(id).orElse(null);
        if (token == null) return ResponseEntity.notFound().build();
        if (!token.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).build();
        }
        if (isActive(token)) {
            return ResponseEntity.badRequest().body(Map.of("error", "アクティブなトークンは削除できません。先に失効させてください。"));
        }
        shareTokenRepository.delete(token);
        return ResponseEntity.noContent().build();
    }

    /** トークンが現在有効か（未失効かつ期限内）。 */
    private boolean isActive(ShareToken t) {
        if (t.getRevokedAt() != null) return false;
        if (t.getExpiresAt() != null && t.getExpiresAt().isBefore(LocalDateTime.now())) return false;
        return true;
    }

    /** 期限指定文字列 → 期限日時の変換。"unlimited" or 不正値は null（無期限）。 */
    private LocalDateTime computeExpiresAt(String expiresIn) {
        if (expiresIn == null) return null;
        LocalDateTime now = LocalDateTime.now();
        return switch (expiresIn) {
            case "24h" -> now.plusHours(24);
            case "1w"  -> now.plusWeeks(1);
            case "1m"  -> now.plusMonths(1);
            case "unlimited" -> null;
            default -> null;
        };
    }

    /** ShareToken を JSON レスポンス用 Map に変換。 */
    private Map<String, Object> toMap(ShareToken t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.getId());
        m.put("token", t.getToken());
        m.put("scopeDashboard", t.getScopeDashboard());
        m.put("scopeScores", t.getScopeScores());
        m.put("scopeHistory", t.getScopeHistory());
        m.put("scopeProfile", t.getScopeProfile());
        m.put("expiresAt", t.getExpiresAt());
        m.put("revokedAt", t.getRevokedAt());
        m.put("createdAt", t.getCreatedAt());
        m.put("active", isActive(t));
        return m;
    }

    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId).orElse(null);
    }

    /** 発行リクエスト DTO。 */
    public record IssueTokenRequest(
            Boolean scopeDashboard,
            Boolean scopeScores,
            Boolean scopeHistory,
            Boolean scopeProfile,
            String expiresIn) {}
}
