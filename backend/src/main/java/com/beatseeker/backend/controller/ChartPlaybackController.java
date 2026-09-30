package com.beatseeker.backend.controller;

import com.beatseeker.backend.service.ChartPlaybackService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * 【クラスの役割】 譜面分析ページの譜面再生 API。ログイン不要（譜面分析ページ自体が未ログインでも見られるため）。
 *
 *  - GET /api/analysis/chart-playback?textage=33%2Fshowtime.html%3F1AC00 … 1 譜面の再生データ
 */
@RestController
public class ChartPlaybackController {

    private final ChartPlaybackService service;

    public ChartPlaybackController(ChartPlaybackService service) {
        this.service = service;
    }

    /**
     * 【メソッドの役割】 譜面 1 つ分の再生データ（ノーツ・CN・BPM 変化・小節線。位置は tick）を返す。
     * 失敗は {@code {"error": 文言}} と 404（譜面が無い・一致しない）/ 502（textage から取れない）。
     */
    @GetMapping("/api/analysis/chart-playback")
    public ResponseEntity<Map<String, Object>> getPlayback(@RequestParam String textage) {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                    .body(service.getPlayback(textage));
        } catch (ChartPlaybackService.PlaybackException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }
}
