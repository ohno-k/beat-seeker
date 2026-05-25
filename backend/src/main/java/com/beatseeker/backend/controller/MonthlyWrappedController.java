package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.MonthlyWrappedService;
import com.beatseeker.backend.service.MonthlyWrappedService.MonthlyWrappedResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 【クラスの役割】 月末振り返り（Spotify Wrapped 風）の REST エンドポイントを束ねるコントローラ。
 *
 * 自分用と公開用の 2 エンドポイントを 1 つのコントローラに同居させており、
 * パスはそれぞれ既存規約（自分=/api/scores/...、公開=/api/users/{userId}/...）に合わせている。
 *
 * 主要エンドポイント:
 *  - {@code GET /api/scores/monthly-wrapped?year=&month=}        … 認証必須・自分の振り返り
 *  - {@code GET /api/users/{userId}/monthly-wrapped?year=&month=} … 公開・privacyLevel=0 のみ
 *
 * バリデーション:
 *  - month は 1-12、year は 2000-9999 の範囲のみ受け付ける
 *
 * 依存:
 *  - {@link MonthlyWrappedService} … 月次集計のドメインロジック
 *  - {@link UserRepository}       … 公開エンドポイント時の User 解決
 */
@RestController
public class MonthlyWrappedController {

    /** 月次振り返り集計サービス。 */
    private final MonthlyWrappedService monthlyWrappedService;
    /** 公開エンドポイントで対象ユーザーを引くためのリポジトリ。 */
    private final UserRepository userRepository;

    /**
     * 【コンストラクタ】 Service と Repository を DI で受け取る。
     */
    public MonthlyWrappedController(MonthlyWrappedService monthlyWrappedService,
                                    UserRepository userRepository) {
        this.monthlyWrappedService = monthlyWrappedService;
        this.userRepository = userRepository;
    }

    /**
     * 【メソッドの役割】 ログインユーザー自身の指定年月の振り返りを返す。
     *
     * @param auth  認証情報（JWT 経由で iidxId が principal に入る）
     * @param year  年（例 2026）
     * @param month 月（1-12）
     * @return 月末振り返り DTO
     */
    @GetMapping("/api/scores/monthly-wrapped")
    public ResponseEntity<MonthlyWrappedResponse> getMyMonthlyWrapped(
            Authentication auth,
            @RequestParam int year,
            @RequestParam int month) {
        if (!isValidYearMonth(year, month)) {
            return ResponseEntity.badRequest().build();
        }
        User user = getUser(auth);
        return ResponseEntity.ok(monthlyWrappedService.generate(user, year, month));
    }

    /**
     * 【メソッドの役割】 指定ユーザーの公開振り返りを返す（OGP 展開・他人からの SNS 流入対応）。
     *
     * セキュリティ:
     *  - クエリ {@code token} は (userId, year, month, server-secret) から導かれる HMAC で、本人がシェアボタンを
     *    押した時にのみ発行される。これが正しくないとデータを返さない（403）。
     *    → URL の userId 部分を書き換えて他人の振り返りを覗き見るのを防ぐ。
     *  - 加えて privacyLevel != 0（非公開設定）も 403。
     *  - 存在しないユーザーは 404。
     *
     * @param userId 対象ユーザー ID
     * @param year   年
     * @param month  月
     * @param token  HMAC シェアトークン（必須）
     * @return 月末振り返り DTO
     */
    @GetMapping("/api/users/{userId}/monthly-wrapped")
    public ResponseEntity<MonthlyWrappedResponse> getPublicMonthlyWrapped(
            @PathVariable Long userId,
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(name = "token", required = false) String token) {
        if (!isValidYearMonth(year, month)) {
            return ResponseEntity.badRequest().build();
        }
        // トークン検証を先に行う（ユーザー存在の有無を漏らさないため）
        if (!monthlyWrappedService.isValidShareToken(userId, year, month, token)) {
            return ResponseEntity.status(403).build();
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        // null は「非公開 (1)」と同等に扱う（安全側に倒す）
        Integer privacyLevel = user.getPrivacyLevel() != null ? user.getPrivacyLevel() : 1;
        if (privacyLevel != 0) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(monthlyWrappedService.generate(user, year, month));
    }

    /** 年月のレンジバリデーション。 */
    private boolean isValidYearMonth(int year, int month) {
        return month >= 1 && month <= 12 && year >= 2000 && year <= 9999;
    }

    /** 認証情報からログインユーザーを解決する共通ヘルパー。 */
    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}
