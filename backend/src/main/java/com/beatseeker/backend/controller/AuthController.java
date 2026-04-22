package com.beatseeker.backend.controller;

import com.beatseeker.backend.config.JwtUtil;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.EmailService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 【クラスの役割】 認証・アカウント管理に関する API をまとめたコントローラ。
 *
 * JWT ベースの認証を採用しており、ログイン／登録成功時に JWT を発行して返す。
 * パスワードは BCrypt でハッシュ化して保存する。
 *
 * 主要エンドポイント:
 *  - {@code POST /api/auth/register}         … 新規登録（JWT を返す）
 *  - {@code POST /api/auth/login}            … ログイン（JWT を返す）
 *  - {@code GET  /api/auth/me}               … ログイン中のユーザープロフィール取得
 *  - {@code PUT  /api/auth/me/profile}       … プロフィール部分更新（PATCH 的セマンティクス）
 *  - {@code POST /api/auth/forgot-password}  … パスワードリセット用のワンタイムリンクをメール送信
 *  - {@code POST /api/auth/reset-password}   … リセットトークンを用いたパスワード再設定
 *
 * セキュリティ上の工夫:
 *  - {@code forgot-password} は「アカウントの有無」を露呈しないよう、常に同じメッセージで 200 を返す。
 *  - IIDX ID の重複登録はユニーク制約に加え、{@code NonUniqueResultException} 捕捉で
 *    過去の不整合データに対するログ出力を行っている。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** ユーザー永続化 Repository。 */
    private final UserRepository userRepository;
    /** パスワードハッシュ化／照合に使う BCrypt エンコーダ。 */
    private final PasswordEncoder passwordEncoder;
    /** JWT の発行・検証ユーティリティ。 */
    private final JwtUtil jwtUtil;
    /** パスワードリセットメール等の送信を担う Service。 */
    private final EmailService emailService;

    /**
     * 【コンストラクタ】 Spring が DI で各依存を注入する。
     */
    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil, EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.emailService = emailService;
    }

    /**
     * 【メソッドの役割】 新規ユーザーを登録し、そのまま JWT を返す。
     *
     * 登録フロー:
     *  1. IIDX ID 重複チェック（あれば 409 Conflict）
     *  2. パスワードを BCrypt でハッシュ化
     *  3. User エンティティを保存
     *  4. JWT を発行して返す（クライアント側はそのままログイン状態になる）
     *
     * @param request 登録リクエスト（Bean Validation 検証済み）
     * @return 成功時 200 + JWT、失敗時は適切なエラーステータス
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        try {
            // 既に同 IIDX ID で登録されている場合は 409 を返して重複を防ぐ
            if (userRepository.findByIidxId(request.iidxId()).isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("message", "IIDX ID is already registered"));
            }

            User user = new User();
            user.setIidxId(request.iidxId());
            // 平文パスワードは保存せず、必ずハッシュ化した値だけを DB に格納する
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            // displayName 未指定時のフォールバックはここで適用
            user.setDisplayName(request.displayName() != null ? request.displayName() : "No Name");
            user.setDanRank(request.danRank());
            user.setArenaRank(request.arenaRank());
            // playSide 未指定時は 1P とみなす
            user.setPlaySide(request.playSide() != null ? request.playSide() : "1P");
            // メールアドレスは小文字に正規化して保存（大文字小文字差異による重複登録防止）
            if (request.email() != null && !request.email().isBlank()) {
                user.setEmail(request.email().trim().toLowerCase());
            }
            userRepository.save(user);

            // 登録成功と同時にログイン扱いにするため JWT を発行
            String token = jwtUtil.generateToken(user.getIidxId());
            return ResponseEntity.ok(Map.of("message", "Registration successful", "token", token));
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException
                | org.hibernate.NonUniqueResultException e) {
            // 過去の不整合で複数レコードが存在するケース。運用上の異常なので管理者対応を促す
            System.err.println("Duplicate user found for IIDX ID: " + request.iidxId());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message",
                            "Database integrity error: Multiple users found with same IIDX ID. Please contact admin."));
        } catch (Exception e) {
            // 想定外エラーはスタックトレースを吐いて 500
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Registration failed: " + e.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 ログイン処理。IIDX ID + パスワードを照合し、成功なら JWT を返す。
     *
     * セキュリティ配慮: ユーザー存在／パスワード不一致のどちらも「同じ 401 メッセージ」で返し、
     * ユーザー列挙攻撃を防ぐ。
     *
     * @param request ログインリクエスト（Bean Validation 検証済み）
     * @return JWT を含む成功レスポンス、または 401/500
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        try {
            Optional<User> optionalUser = userRepository.findByIidxId(request.iidxId());

            // ユーザー不在 or パスワード不一致のどちらも同一メッセージで 401（列挙防止）
            if (optionalUser.isEmpty()
                    || !passwordEncoder.matches(request.password(), optionalUser.get().getPasswordHash())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid IIDX ID or Password"));
            }

            String token = jwtUtil.generateToken(optionalUser.get().getIidxId());
            return ResponseEntity.ok(Map.of("message", "Login successful", "token", token));
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException
                | org.hibernate.NonUniqueResultException e) {
            System.err.println("Duplicate user found for login attempt: " + request.iidxId());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message",
                            "Database integrity error: Multiple users found with same IIDX ID. Please contact admin."));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Login failed: " + e.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 ログイン中のユーザー自身のプロフィール情報を返す。
     *
     * フロントのヘッダー／マイページ初期描画で呼ばれる。
     * サポータートークン（Ko-fi 連携用の識別子）がまだ無いユーザーには
     * ここで自動生成して永続化する副作用がある。
     *
     * @param auth 認証情報
     * @return プロフィール項目を含む Map
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(Authentication auth) {
        try {
            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(401).build();
            }

            String iidxId = (String) auth.getPrincipal();
            User user = userRepository.findByIidxId(iidxId)
                    .orElseThrow(() -> new RuntimeException("User not found: " + iidxId));

            // null 安全なデフォルト値を埋めながらレスポンスを組み立てる
            java.util.Map<String, Object> responseBody = new java.util.HashMap<>();
            responseBody.put("id", user.getId());
            responseBody.put("displayName", user.getDisplayName() != null ? user.getDisplayName() : "");
            responseBody.put("iidxId", user.getIidxId());
            responseBody.put("danRank", user.getDanRank() != null ? user.getDanRank() : "");
            responseBody.put("arenaRank", user.getArenaRank() != null ? user.getArenaRank() : "");
            responseBody.put("playSide", user.getPlaySide() != null ? user.getPlaySide() : "1P");
            responseBody.put("privacyLevel", user.getPrivacyLevel());
            responseBody.put("language", user.getLanguage() != null ? user.getLanguage() : "ja");
            responseBody.put("showRateTier", user.getShowRateTier() != null ? user.getShowRateTier() : true);
            responseBody.put("isSupporter", user.getIsSupporter() != null ? user.getIsSupporter() : false);
            responseBody.put("showSupporterBorder", user.getShowSupporterBorder() != null ? user.getShowSupporterBorder() : true);
            // サポータートークンが未発行なら、Ko-fi webhook が参照できるよう自動で付与する
            if (user.getSupporterToken() == null || user.getSupporterToken().isEmpty()) {
                // "BS-" + UUID 先頭 8 桁（大文字）の短いトークン
                String token = "BS-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                user.setSupporterToken(token);
                userRepository.save(user);
            }
            responseBody.put("supporterToken", user.getSupporterToken());
            responseBody.put("lastUploadedAt", user.getLastUploadedAt());
            responseBody.put("email", user.getEmail() != null ? user.getEmail() : "");
            return ResponseEntity.ok(responseBody);
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException
                | org.hibernate.NonUniqueResultException e) {
            System.err.println("Duplicate user found in getCurrentUser for IIDX ID: "
                    + (auth != null ? auth.getPrincipal() : "unknown"));
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Database integrity error: Multiple users found. Please contact admin."));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Error fetching user: " + e.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 ログイン中ユーザーのプロフィールを部分更新する。
     *
     * null でないフィールドだけが更新対象となる「PATCH 的」セマンティクス。
     * パスワード変更時は {@code currentPassword} の一致チェックを必ず通す。
     * メールアドレス更新時は他ユーザーとの重複を検出して 409 を返す。
     *
     * @param auth    認証情報
     * @param request プロフィール更新リクエスト
     */
    @PutMapping("/me/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(Authentication auth,
            @RequestBody ProfileUpdateRequest request) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        String iidxId = (String) auth.getPrincipal();
        User user = userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // ── パスワード変更ロジック ─────────────────────────
        // currentPassword と newPassword が両方揃っているときだけ実行
        if (request.currentPassword() != null && request.newPassword() != null) {
            // 現在パスワードの照合に失敗したら 400 で拒否（誤操作／なりすまし対策）
            if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("message", "現在のパスワードが正しくありません。"));
            }
            user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        }

        // 以下は「null でないもののみ上書き」する PATCH 的更新
        if (request.displayName() != null)
            user.setDisplayName(request.displayName());
        if (request.danRank() != null)
            user.setDanRank(request.danRank());
        if (request.arenaRank() != null)
            user.setArenaRank(request.arenaRank());
        if (request.playSide() != null)
            user.setPlaySide(request.playSide());
        if (request.privacyLevel() != null)
            user.setPrivacyLevel(request.privacyLevel());
        if (request.language() != null)
            user.setLanguage(request.language());
        if (request.showRateTier() != null)
            user.setShowRateTier(request.showRateTier());
        if (request.showSupporterBorder() != null)
            user.setShowSupporterBorder(request.showSupporterBorder());
        if (request.email() != null && !request.email().isBlank()) {
            // メールアドレスは小文字化して保存。他ユーザーと衝突しないかを確認してから更新する
            String newEmail = request.email().trim().toLowerCase();
            Optional<User> existing = userRepository.findByEmail(newEmail);
            if (existing.isPresent() && !existing.get().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("message", "そのメールアドレスは既に使用されています。"));
            }
            user.setEmail(newEmail);
        }

        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Profile updated successfully"));
    }

    /**
     * 【メソッドの役割】 パスワードを忘れたユーザーにリセット用リンクメールを送る。
     *
     * ユーザー列挙攻撃を防ぐため、以下はすべて同じ成功メッセージを 200 で返す:
     *  - 該当ユーザー不在
     *  - メールアドレスが一致しない
     *  - 正しい組み合わせでメール送信成功
     * メール送信そのものが失敗した場合のみ 500 を返す。
     *
     * @param payload {@code {"iidxId": "...", "email": "..."}} 形式
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(@RequestBody Map<String, String> payload) {
        String iidxId = payload.get("iidxId");
        String email = payload.get("email");

        if (iidxId == null || email == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "IIDX IDとメールアドレスを入力してください。"));
        }

        Optional<User> optUser = userRepository.findByIidxId(iidxId.trim());
        if (optUser.isEmpty() || !email.trim().equalsIgnoreCase(optUser.get().getEmail())) {
            // 該当がない or メール不一致の場合も、列挙防止のため一律成功メッセージで返す
            return ResponseEntity.ok(Map.of("message", "該当するアカウントが見つかった場合、パスワードリセット手順を送信します。"));
        }

        User user = optUser.get();
        // ワンタイムの UUID を発行し、1 時間の有効期限を設定する
        String token = UUID.randomUUID().toString();
        user.setPasswordResetToken(token);
        user.setPasswordResetExpiredAt(LocalDateTime.now().plusHours(1));
        userRepository.save(user);

        try {
            emailService.sendPasswordResetEmail(user.getEmail(), iidxId.trim(), token);
        } catch (Exception e) {
            // メール送信失敗時は 500。DB には既にトークンが入っているが、期限切れで自動失効する
            System.err.println("[ERROR] Failed to send password reset email: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "メールの送信に失敗しました。しばらく経ってから再試行してください。"));
        }

        return ResponseEntity.ok(Map.of("message", "パスワードリセットの手順をメールで送信しました。"));
    }

    /**
     * 【メソッドの役割】 リセットトークンを用いて新しいパスワードを設定する。
     *
     * バリデーション:
     *  - token / newPassword が null 不可
     *  - newPassword は最低 4 文字
     *  - 期限切れ or 不正トークンは 400
     *
     * 成功時は一度使ったトークンを即破棄する（再利用防止）。
     *
     * @param payload {@code {"token": "...", "newPassword": "..."}} 形式
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody Map<String, String> payload) {
        String token = payload.get("token");
        String newPassword = payload.get("newPassword");

        // 最低限のバリデーション（本格運用では強度チェック等を追加したい）
        if (token == null || newPassword == null || newPassword.length() < 4) {
            return ResponseEntity.badRequest().body(Map.of("message", "無効なリクエストです。"));
        }

        Optional<User> optUser = userRepository.findByPasswordResetToken(token);
        if (optUser.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "無効または期限切れのトークンです。"));
        }

        User user = optUser.get();
        // 期限切れは 400。有効期限カラムが null（過去の不整合）も期限切れ扱いとする
        if (user.getPasswordResetExpiredAt() == null || LocalDateTime.now().isAfter(user.getPasswordResetExpiredAt())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "トークンの有効期限が切れています。再度リセットを申請してください。"));
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // 使ったトークンは即破棄（再利用防止）
        user.setPasswordResetToken(null);
        user.setPasswordResetExpiredAt(null);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "パスワードをリセットしました。新しいパスワードでログインしてください。"));
    }
}
