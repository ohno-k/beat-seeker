package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.ExternalApiToken;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ExternalApiTokenRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.ExternalApiTokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【クラスの役割】 外部 API トークン（iidx-memo 等と連携するための個人トークン）の
 * 発行・一覧・失効を行う認証必須コントローラ。
 *
 * 本コントローラ自体は beat-seeker の通常ログイン（JWT）で守られる。
 * 発行された平文トークンは {@link #issueToken} の戻り値で 1 回だけ返却され、
 * 以降は DB にハッシュしか残らない（再表示不可）。
 *
 * 連携先アプリ（iidx-memo）は別経路 {@code /api/external/**} を
 * {@code Authorization: Bearer <plain>} で叩く。そちらの認証は
 * {@code ApiTokenAuthFilter} が担当する。
 *
 * 主要エンドポイント:
 *  - {@code POST   /api/integrations/tokens}           … 発行（平文を 1 回だけ返す）
 *  - {@code GET    /api/integrations/tokens}           … 自分の発行履歴
 *  - {@code DELETE /api/integrations/tokens/{id}}      … 失効（ソフト）
 *  - {@code DELETE /api/integrations/tokens/{id}/permanent} … 一覧から完全削除
 */
@RestController
@RequestMapping("/api/integrations/tokens")
public class IntegrationTokenController {

    private final ExternalApiTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final ExternalApiTokenService tokenService;

    public IntegrationTokenController(ExternalApiTokenRepository tokenRepository,
                                      UserRepository userRepository,
                                      ExternalApiTokenService tokenService) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
    }

    /**
     * 【メソッドの役割】 新しい外部 API トークンを発行する。
     *
     * リクエスト body:
     *  - {@code name}      (string):  ユーザーが付けるラベル（任意、80 字以内）
     *  - {@code partner}   (string):  連携先識別子（任意、例: "iidx-memo"、40 字以内）
     *  - {@code expiresIn} (string):  "30d" | "90d" | "1y" | "unlimited"。null は無期限扱い
     *
     * レスポンスには 1 度だけ平文トークン {@code plainToken} が含まれる。
     * 以後の API では返らない（DB に保存していないため）。
     */
    @PostMapping
    public ResponseEntity<?> issueToken(Authentication auth, @RequestBody IssueTokenRequest req) {
        User user = getUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        String plain = tokenService.generatePlainToken();
        String hash = tokenService.hash(plain);

        ExternalApiToken entity = new ExternalApiToken();
        entity.setUser(user);
        entity.setTokenHash(hash);
        entity.setTokenPrefix(tokenService.prefixForDisplay(plain));
        entity.setName(trim(req.name(), 80));
        entity.setPartner(trim(req.partner(), 40));
        entity.setExpiresAt(computeExpiresAt(req.expiresIn()));
        entity.setCreatedAt(LocalDateTime.now());
        ExternalApiToken saved = tokenRepository.save(entity);

        Map<String, Object> body = toMap(saved);
        // 平文は発行時のレスポンスにだけ含める。DB には保存しない。
        body.put("plainToken", plain);
        return ResponseEntity.ok(body);
    }

    /**
     * 【メソッドの役割】 自分が発行した外部 API トークン一覧を返す（新しい順）。
     *
     * 平文は返さない。末尾識別子 {@code tokenPrefix} のみで識別する。
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listTokens(Authentication auth) {
        User user = getUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> result = tokenRepository
                .findByUserOrderByCreatedAtDesc(user)
                .stream()
                .map(this::toMap)
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 外部 API トークンをソフト失効させる（DB レコードは保持）。
     *
     * 自分が発行したトークン以外は 403。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> revokeToken(Authentication auth, @PathVariable Long id) {
        User user = getUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        ExternalApiToken token = tokenRepository.findById(id).orElse(null);
        if (token == null) return ResponseEntity.notFound().build();
        if (!token.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).build();
        }
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(LocalDateTime.now());
            tokenRepository.save(token);
        }
        return ResponseEntity.ok(toMap(token));
    }

    /**
     * 【メソッドの役割】 外部 API トークンを DB から完全削除する。
     *
     * 失効済み・期限切れのトークンを一覧から整理する用途。
     * アクティブなトークンを誤って消すと連携先 API が突然 401 を返すため、
     * サーバ側でアクティブ判定をブロックする。
     */
    @DeleteMapping("/{id}/permanent")
    public ResponseEntity<?> deleteToken(Authentication auth, @PathVariable Long id) {
        User user = getUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        ExternalApiToken token = tokenRepository.findById(id).orElse(null);
        if (token == null) return ResponseEntity.notFound().build();
        if (!token.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).build();
        }
        if (isActive(token)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "アクティブなトークンは削除できません。先に失効させてください。"));
        }
        tokenRepository.delete(token);
        return ResponseEntity.noContent().build();
    }

    /** トークンが現在有効か（未失効かつ期限内）。 */
    private boolean isActive(ExternalApiToken t) {
        if (t.getRevokedAt() != null) return false;
        if (t.getExpiresAt() != null && t.getExpiresAt().isBefore(LocalDateTime.now())) return false;
        return true;
    }

    /** 期限指定文字列 → 期限日時の変換。"unlimited" or 不正値は null（無期限）。 */
    private LocalDateTime computeExpiresAt(String expiresIn) {
        if (expiresIn == null) return null;
        LocalDateTime now = LocalDateTime.now();
        return switch (expiresIn) {
            case "30d" -> now.plusDays(30);
            case "90d" -> now.plusDays(90);
            case "1y"  -> now.plusYears(1);
            case "unlimited" -> null;
            default -> null;
        };
    }

    /** ExternalApiToken を JSON レスポンス用 Map に変換（平文は含めない）。 */
    private Map<String, Object> toMap(ExternalApiToken t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("partner", t.getPartner());
        m.put("tokenPrefix", t.getTokenPrefix());
        m.put("expiresAt", t.getExpiresAt());
        m.put("revokedAt", t.getRevokedAt());
        m.put("lastUsedAt", t.getLastUsedAt());
        m.put("createdAt", t.getCreatedAt());
        m.put("active", isActive(t));
        return m;
    }

    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId).orElse(null);
    }

    /** 文字列を指定長で切り詰める。null は null のまま返す。 */
    private String trim(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 発行リクエスト DTO。 */
    public record IssueTokenRequest(String name, String partner, String expiresIn) {}
}
