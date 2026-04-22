package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.AdminAuthService;
import com.beatseeker.backend.service.EmailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【クラスの役割】 Ko-fi（寄付プラットフォーム）からの Webhook を受信し、
 * サポーターフラグを自動で有効化するコントローラ。
 *
 * ユーザー側のフロー:
 *  1. マイページで発行される「サポータートークン（BS-XXXXXXXX 形式）」をコピー
 *  2. Ko-fi の寄付メッセージ欄にそのトークンを貼り付けて寄付
 *  3. Ko-fi が本エンドポイントに寄付情報を POST する
 *  4. 本コントローラがメッセージからトークンを正規表現で抽出し、
 *     対応ユーザーの {@code isSupporter=true} をセットする
 *
 * セキュリティ:
 *  - 環境変数 {@code KOFI_VERIFICATION_TOKEN} と Ko-fi 側の verification_token を照合し、
 *    一致しないリクエストは 403 で拒否する。
 *  - Ko-fi はリトライを行うため、異常系でも基本 200 を返す（再送させないポリシー）。
 *
 * リクエスト形式:
 *  - Ko-fi は {@code application/x-www-form-urlencoded} で {@code data} フィールドに
 *    JSON 文字列を入れて送信する特殊仕様。
 */
@RestController
@RequestMapping("/api/kofi")
public class KofiWebhookController {

    /** 寄付者 → User の紐付け用 Repository。 */
    private final UserRepository userRepository;
    /** 管理者への通知メール送信用 Service。 */
    private final EmailService emailService;
    /** 管理者ユーザーの設定値を保持する Service（通知宛先の User を引くのに使用）。 */
    private final AdminAuthService adminAuthService;
    /** Ko-fi からの JSON 文字列をパースするための Jackson マッパー。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Ko-fi 側で設定される検証用トークン。
     * application 設定または環境変数 {@code KOFI_VERIFICATION_TOKEN} で与える。
     * 未設定（空文字）の場合は検証をスキップする（開発環境用）。
     */
    @Value("${KOFI_VERIFICATION_TOKEN:}")
    private String kofiVerificationToken;

    /**
     * サポータートークンの書式パターン。{@code BS-} + 大文字英数字 8 桁で一致させる。
     * 例: "BS-A1B2C3D4"
     */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("BS-[A-Z0-9]{8}");

    /**
     * 【コンストラクタ】 Spring が DI で Repository/Service を注入する。
     */
    public KofiWebhookController(UserRepository userRepository,
                                 EmailService emailService,
                                 AdminAuthService adminAuthService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.adminAuthService = adminAuthService;
    }

    /**
     * 【メソッドの役割】 Ko-fi Webhook の受信口。
     *
     * Ko-fi は form-encoded の {@code data} フィールドに JSON を詰めて送ってくるので、
     * まず JSON をパースし、verification_token を照合、message 内からサポータートークンを抽出、
     * 該当ユーザーの isSupporter を有効化する流れ。
     *
     * 例外時も基本 200 を返す（Ko-fi のリトライ送信を防ぐため）。
     *
     * @param data Ko-fi が送信する JSON 文字列（form field "data"）
     * @return 常に "OK"（検証失敗時のみ 403）
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(@RequestParam("data") String data) {
        try {
            JsonNode json = objectMapper.readTree(data);

            // Ko-fi からのリクエストかを検証する（なりすまし防止）
            String verificationToken = json.has("verification_token")
                    ? json.get("verification_token").asText() : "";
            if (!kofiVerificationToken.isEmpty()
                    && !kofiVerificationToken.equals(verificationToken)) {
                System.err.println("[Ko-fi Webhook] Invalid verification token");
                return ResponseEntity.status(403).body("Invalid verification token");
            }

            // Ko-fi ペイロードの主要フィールドを取り出す（欠けていても空文字で続行）
            String message = json.has("message") ? json.get("message").asText() : "";
            String type = json.has("type") ? json.get("type").asText() : "";
            String fromName = json.has("from_name") ? json.get("from_name").asText() : "";

            System.out.println("[Ko-fi Webhook] Received: type=" + type
                    + ", from=" + fromName + ", message=" + message);

            // メッセージ本文からサポータートークン（BS-XXXXXXXX）を検索
            Matcher matcher = TOKEN_PATTERN.matcher(message);
            if (matcher.find()) {
                String supporterToken = matcher.group();
                Optional<User> optUser = userRepository.findBySupporterToken(supporterToken);

                if (optUser.isPresent()) {
                    User user = optUser.get();
                    // サポーターフラグを立てて永続化
                    user.setIsSupporter(true);
                    userRepository.save(user);
                    System.out.println("[Ko-fi Webhook] Supporter activated: "
                            + user.getIidxId() + " (token: " + supporterToken + ")");

                    // 管理者に通知メールを送る（存在＆メアド登録済みの場合のみ）
                    userRepository.findById(adminAuthService.getAdminUserId()).ifPresent(admin -> {
                        if (admin.getEmail() != null) {
                            emailService.sendSupporterActivatedNotification(
                                    admin.getEmail(),
                                    user.getDisplayName() != null ? user.getDisplayName() : user.getIidxId(),
                                    user.getIidxId() != null ? user.getIidxId() : "");
                        }
                    });
                } else {
                    // 有効な形式のトークンだが DB に該当ユーザーがいない（タイポ等）
                    System.err.println("[Ko-fi Webhook] No user found for token: "
                            + supporterToken);
                }
            } else {
                // メッセージにトークンが含まれない = 寄付者がトークン貼付を忘れた可能性
                System.err.println("[Ko-fi Webhook] No supporter token found in message: "
                        + message);
            }

            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            System.err.println("[Ko-fi Webhook] Error processing webhook: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.ok("OK"); // Ko-fi のリトライを避けるため常に 200 を返す
        }
    }
}
