package com.beatseeker.backend.controller;

/**
 * 【DTO の役割】 プロフィール更新 API（{@code PUT /api/auth/me/profile}）で
 * 受け取るリクエストボディ。
 *
 * フィールドはすべてオプショナル（null 許容）で、null でないフィールドだけが
 * サーバー側で更新対象となる「部分更新（PATCH ライク）」なセマンティクスを持つ。
 * パスワード変更のみ、{@code currentPassword} と {@code newPassword} の
 * 両方が揃っていないと処理されない。
 */
public record ProfileUpdateRequest(
                /** 表示名（ニックネーム）。 */
                String displayName,
                /** IIDX ID。通常は変更されない。 */
                String iidxId,
                /** 段位（例: "十段", "皆伝"）。 */
                String danRank,
                /** アリーナランク（例: "A1", "SS"）。 */
                String arenaRank,
                /** プレイサイド。"1P" または "2P"。 */
                String playSide,
                /** 現在のパスワード。パスワード変更時のみ必須（照合用）。 */
                String currentPassword,
                /** 新しいパスワード。パスワード変更時のみ必須。 */
                String newPassword,
                /** プライバシーレベル。0=公開, 1=非公開 など。 */
                Integer privacyLevel,
                /** メールアドレス。小文字に正規化されてから保存される。 */
                String email,
                /** UI 表示言語コード（例: "ja", "en"）。 */
                String language,
                /** レートティアをプロフィール上で表示するか。 */
                Boolean showRateTier,
                /** KENBAN-TIER / SARA-TIER を表示するか（サポーター限定オプトイン）。 */
                Boolean showKenbanSaraTier,
                /** サポーター用ボーダー（枠）を表示するか。 */
                Boolean showSupporterBorder) {
}
