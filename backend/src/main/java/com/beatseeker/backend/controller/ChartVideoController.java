package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.AdminAuthService;
import com.beatseeker.backend.service.ChartVideoService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 【クラスの役割】 譜面再生の原曲動画（YouTube）の API。
 *
 *  - GET  /api/analysis/chart-video?textage=...&search=true … 動画とずれ（search=true で未検索なら検索する）。ログイン不要
 *  - POST /api/analysis/chart-video/offset {textage, videoId, offsetSec} … ずれを保存（ログイン必須、全員で共有）
 *  - POST /api/analysis/chart-video/next {textage} … 次の候補の動画へ（ログイン必須）
 *  - POST /api/analysis/chart-video/manual {textage, url} … 動画を URL で指定（管理者）
 *  - GET  /api/analysis/chart-video/queue … ずれ合わせの進み具合と次に合わせる譜面（管理者）
 *  - POST /api/analysis/chart-video/skip {textage} … 埋め作業で譜面を飛ばす（管理者）
 *
 * 失敗は {@code {"error": 文言}} とステータス。
 */
@RestController
public class ChartVideoController {

    private final ChartVideoService service;
    private final UserRepository userRepository;
    private final AdminAuthService adminAuthService;

    public ChartVideoController(ChartVideoService service, UserRepository userRepository, AdminAuthService adminAuthService) {
        this.service = service;
        this.userRepository = userRepository;
        this.adminAuthService = adminAuthService;
    }

    @GetMapping("/api/analysis/chart-video")
    public ResponseEntity<Map<String, Object>> get(@RequestParam String textage,
                                                   @RequestParam(defaultValue = "false") boolean search) {
        return run(() -> service.get(textage, search));
    }

    public record OffsetRequest(String textage, String videoId, Double offsetSec) {}

    @PostMapping("/api/analysis/chart-video/offset")
    public ResponseEntity<Map<String, Object>> saveOffset(@RequestBody OffsetRequest req, Authentication auth) {
        User user = userOf(auth);
        if (user == null) return error(401, "ずれの保存にはログインが必要です");
        if (req.textage() == null || req.videoId() == null || req.offsetSec() == null) return error(400, "入力が不足しています");
        return run(() -> service.saveOffset(req.textage(), req.videoId(), req.offsetSec(), user.getId()));
    }

    public record TextageRequest(String textage, String url) {}

    @PostMapping("/api/analysis/chart-video/next")
    public ResponseEntity<Map<String, Object>> next(@RequestBody TextageRequest req, Authentication auth) {
        User user = userOf(auth);
        if (user == null) return error(401, "動画の切り替えにはログインが必要です");
        return run(() -> service.next(req.textage(), user.getId()));
    }

    @PostMapping("/api/analysis/chart-video/manual")
    public ResponseEntity<Map<String, Object>> manual(@RequestBody TextageRequest req, Authentication auth) {
        User user = userOf(auth);
        if (user == null || !adminAuthService.isAdmin(user)) return error(403, "管理者のみ指定できます");
        return run(() -> service.setManual(req.textage(), req.url(), user.getId()));
    }

    /** ずれ合わせの進み具合と、次に合わせる譜面（管理者の埋め作業用）。 */
    @GetMapping("/api/analysis/chart-video/queue")
    public ResponseEntity<Map<String, Object>> queue(Authentication auth) {
        User user = userOf(auth);
        if (user == null || !adminAuthService.isAdmin(user)) return error(403, "管理者のみ使えます");
        return run(() -> service.queue(30));
    }

    /** 埋め作業で譜面を飛ばす（次の未調整の一覧から外す）。 */
    @PostMapping("/api/analysis/chart-video/skip")
    public ResponseEntity<Map<String, Object>> skip(@RequestBody TextageRequest req, Authentication auth) {
        User user = userOf(auth);
        if (user == null || !adminAuthService.isAdmin(user)) return error(403, "管理者のみ使えます");
        return run(() -> {
            service.skip(req.textage(), user.getId());
            return Map.of("ok", true);
        });
    }

    private User userOf(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof String iidxId)) return null;
        return userRepository.findByIidxId(iidxId).orElse(null);
    }

    private static ResponseEntity<Map<String, Object>> run(Supplier<Map<String, Object>> body) {
        try {
            return ResponseEntity.ok(body.get());
        } catch (ChartVideoService.VideoException e) {
            return error(e.getStatus(), e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
