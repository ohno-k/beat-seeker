package com.beatseeker.backend.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 【DTO の役割】 新規ユーザー登録 API（{@code POST /api/auth/register}）で
 * 受け取るリクエストボディ。
 *
 * IIDX ID とパスワードが必須。残りのプロフィール項目は任意で、
 * 未指定の場合はサーバー側でデフォルト値（例: displayName="No Name", playSide="1P"）に
 * フォールバックされる。
 */
public record RegisterRequest(
                /**
                 * IIDX ID。"XXXX-XXXX" の 4桁-4桁 形式でのみ受け付ける。
                 * 重複登録はサーバー側で 409 Conflict として弾かれる。
                 */
                @NotBlank(message = "IIDX ID is required") @Pattern(regexp = "^\\d{4}-\\d{4}$", message = "IIDX ID must be in format XXXX-XXXX") String iidxId,

                /** 平文パスワード。必須。サーバー側で BCrypt ハッシュ化される。 */
                @NotBlank(message = "Password is required") String password,

                /** 表示名（ニックネーム）。未指定なら "No Name"。 */
                String displayName,
                /** 段位（任意）。 */
                String danRank,
                /** アリーナランク（任意）。 */
                String arenaRank,
                /** プレイサイド。"1P" / "2P"。未指定なら "1P"。 */
                String playSide,
                /** メールアドレス（任意）。パスワードリセット通知に使う。 */
                String email) {
}
