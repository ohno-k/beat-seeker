package com.beatseeker.backend.controller;

import jakarta.validation.constraints.NotBlank;

/**
 * 【DTO の役割】 ログインリクエストのボディを受け取るレコード。
 *
 * {@code POST /api/auth/login} のリクエスト JSON を Jackson が
 * このレコードにマッピングする。{@code @NotBlank} により、
 * いずれかのフィールドが空文字 or null の場合は Spring が 400 を返す。
 */
public record LoginRequest(
        /** IIDX ID（例: "1234-5678"）。必須。 */
        @NotBlank(message = "IIDX ID is required") String iidxId,

        /** 平文パスワード。必須。サーバー側で BCrypt ハッシュと照合する。 */
        @NotBlank(message = "Password is required") String password) {
}
