package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.ArenaMatch;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ArenaMatchRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/arena")
public class ArenaController {

    private final ArenaMatchRepository arenaMatchRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public ArenaController(ArenaMatchRepository arenaMatchRepository,
                           UserRepository userRepository,
                           ObjectMapper objectMapper) {
        this.arenaMatchRepository = arenaMatchRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Import ARENA battle data from bookmarklet JSON.
     * Skips duplicates (same user + matchDate).
     */
    @PostMapping("/import")
    @Transactional
    public ResponseEntity<Map<String, Object>> importMatches(
            Authentication auth,
            @RequestBody ImportRequest req) {

        User user = getUser(auth);
        int savedCount = 0;
        int skippedCount = 0;

        for (BattleData battle : req.battles()) {
            // Build full date string with year
            String fullDate = req.year() + "-" + battle.date().replace("/", "-").replace(" ", " ").trim();
            // Normalize: "03/15 17:10" → "2026-03-15 17:10"
            // req.year() = "2026", battle.date() = "03/15 17:10"
            // Result: "2026-03-15 17:10"

            if (arenaMatchRepository.existsByUserAndMatchDate(user, fullDate)) {
                skippedCount++;
                continue;
            }

            // Find my row
            String myDjName = req.myDjName();
            int myRank = 0;
            int myTotalPt = 0;
            String myArenaClass = "";

            for (PlayerData p : battle.players()) {
                if (myDjName.equals(p.djName())) {
                    myRank = p.rank();
                    myTotalPt = p.totalPt();
                    myArenaClass = p.arenaClass();
                    break;
                }
            }

            ArenaMatch match = new ArenaMatch();
            match.setUser(user);
            match.setBattleType(battle.battleType());
            match.setMatchDate(fullDate);
            match.setMyDjName(myDjName);
            match.setMyArenaClass(myArenaClass);
            match.setMyRank(myRank);
            match.setMyTotalPt(myTotalPt);

            try {
                match.setSongsJson(objectMapper.writeValueAsString(battle.songs()));
                match.setPlayersJson(objectMapper.writeValueAsString(battle.players()));
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("message", "JSONシリアライズに失敗しました"));
            }

            arenaMatchRepository.save(match);
            savedCount++;
        }

        return ResponseEntity.ok(Map.of(
                "savedCount", savedCount,
                "skippedCount", skippedCount,
                "message", savedCount + "件保存しました（" + skippedCount + "件重複スキップ）"));
    }

    /**
     * Get all ARENA matches for the current user.
     */
    @GetMapping("/matches")
    public ResponseEntity<List<Map<String, Object>>> getMatches(Authentication auth) {
        User user = getUser(auth);
        List<ArenaMatch> matches = arenaMatchRepository.findByUserOrderByMatchDateDesc(user);

        List<Map<String, Object>> result = new ArrayList<>();
        for (ArenaMatch m : matches) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", m.getId());
            map.put("battleType", m.getBattleType());
            map.put("matchDate", m.getMatchDate());
            map.put("myDjName", m.getMyDjName());

            List<Map<String, Object>> players = List.of();
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parsed = objectMapper.readValue(m.getPlayersJson(), List.class);
                players = parsed;
            } catch (Exception ignored) {}
            map.put("players", players);

            try {
                map.put("songs", objectMapper.readValue(m.getSongsJson(), List.class));
            } catch (Exception ignored) {
                map.put("songs", List.of());
            }

            // Recalculate my stats from playersJson when stored values are missing
            int myRank = m.getMyRank() != null ? m.getMyRank() : 0;
            int myTotalPt = m.getMyTotalPt() != null ? m.getMyTotalPt() : 0;
            String myArenaClass = m.getMyArenaClass() != null ? m.getMyArenaClass() : "";
            String myDjName = m.getMyDjName();

            if (myRank == 0 && myDjName != null && !myDjName.isEmpty()) {
                for (Map<String, Object> p : players) {
                    if (myDjName.equals(p.get("djName"))) {
                        Object rankObj = p.get("rank");
                        Object ptObj = p.get("totalPt");
                        Object clsObj = p.get("arenaClass");
                        if (rankObj instanceof Number) myRank = ((Number) rankObj).intValue();
                        if (ptObj instanceof Number) myTotalPt = ((Number) ptObj).intValue();
                        if (clsObj instanceof String) myArenaClass = (String) clsObj;
                        break;
                    }
                }
            }

            map.put("myArenaClass", myArenaClass);
            map.put("myRank", myRank);
            map.put("myTotalPt", myTotalPt);

            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Delete a specific match.
     */
    @DeleteMapping("/matches/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteMatch(
            Authentication auth,
            @PathVariable Long id) {

        User user = getUser(auth);
        ArenaMatch match = arenaMatchRepository.findById(id)
                .orElse(null);

        if (match == null || !match.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).body(Map.of("message", "権限がありません"));
        }

        arenaMatchRepository.delete(match);
        return ResponseEntity.ok(Map.of("message", "削除しました"));
    }

    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        return userRepository.findByIidxId((String) auth.getPrincipal())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // DTOs
    public record ImportRequest(
            String myDjName,
            String year,
            List<BattleData> battles) {}

    public record BattleData(
            String battleType,
            String date,
            List<SongData> songs,
            List<PlayerData> players) {}

    public record SongData(String title, String difficulty) {}

    public record PlayerData(
            String djName,
            String arenaClass,
            int totalPt,
            int rank,
            List<SongScore> songScores) {}

    public record SongScore(int score, int pt) {}
}
