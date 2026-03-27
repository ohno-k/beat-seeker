package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.GameDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class GameDataController {

    private final GameDataService gameDataService;
    private final UserRepository userRepository;

    public GameDataController(GameDataService gameDataService, UserRepository userRepository) {
        this.gameDataService = gameDataService;
        this.userRepository = userRepository;
    }

    // ── Public endpoints ────────────────────────────────────

    @GetMapping("/game-data/songs")
    public ResponseEntity<String> getActiveSongs() {
        try {
            String json = gameDataService.getActiveSongDataJson();
            return ResponseEntity.ok().header("Content-Type", "application/json").body(json);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @GetMapping("/game-data/difficulty-table")
    public ResponseEntity<String> getActiveDifficultyTable() {
        try {
            String json = gameDataService.getActiveDifficultyTableJson();
            return ResponseEntity.ok().header("Content-Type", "application/json").body(json);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // ── Admin endpoints ─────────────────────────────────────

    @GetMapping("/admin/game-data/songs/draft")
    public ResponseEntity<List<SongDefinition>> getDraftSongs(Authentication auth) {
        checkAdminAccess(auth);
        return ResponseEntity.ok(gameDataService.getDraftSongs());
    }

    @PostMapping("/admin/game-data/songs/draft")
    public ResponseEntity<Map<String, Object>> addDraftSong(
            Authentication auth,
            @RequestBody Map<String, Object> songForm) {
        checkAdminAccess(auth);
        try {
            List<SongDefinition> created = gameDataService.addDraftSong(songForm);
            Map<String, Object> result = new HashMap<>();
            result.put("message", created.size() + " 件のレコードをドラフトに追加しました");
            result.put("created", created);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/admin/game-data/songs/draft/{id}")
    public ResponseEntity<Map<String, Object>> deleteDraftSong(
            Authentication auth,
            @PathVariable Long id) {
        checkAdminAccess(auth);
        try {
            gameDataService.deleteDraftSong(id);
            return ResponseEntity.ok(Map.of("message", "ドラフト楽曲を削除しました"));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/admin/game-data/difficulty-table/draft")
    public ResponseEntity<String> getDraftDifficultyTable(Authentication auth) {
        checkAdminAccess(auth);
        try {
            String json = gameDataService.getDraftDifficultyTableJson();
            return ResponseEntity.ok().header("Content-Type", "application/json").body(json);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @PutMapping("/admin/game-data/difficulty-table/draft")
    public ResponseEntity<Map<String, Object>> saveDraftDifficultyTable(
            Authentication auth,
            @RequestBody String json) {
        checkAdminAccess(auth);
        try {
            gameDataService.saveDraftDifficultyTable(json);
            return ResponseEntity.ok(Map.of("message", "難易度表のドラフトを保存しました"));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/admin/game-data/apply")
    public ResponseEntity<Map<String, Object>> applyDraft(Authentication auth) {
        checkAdminAccess(auth);
        try {
            gameDataService.applyDraft();
            return ResponseEntity.accepted().body(Map.of("message", "ドラフトを適用しました。バックグラウンドでポイント再計算を開始します。"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message", "適用エラー: " + e.getMessage()));
        }
    }

    @GetMapping("/admin/game-data/status")
    public ResponseEntity<Map<String, Object>> getDraftStatus(Authentication auth) {
        checkAdminAccess(auth);
        Map<String, Object> status = new HashMap<>();
        status.put("hasDraftSongs", gameDataService.hasDraftSongs());
        status.put("hasDraftDifficultyTable", gameDataService.hasDraftDifficultyTable());
        return ResponseEntity.ok(status);
    }

    // ── Admin check ──────────────────────────────────────────

    private void checkAdminAccess(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        User user = userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getId() != 18L) {
            throw new RuntimeException("Access Denied: Admin privileges required.");
        }
    }
}
