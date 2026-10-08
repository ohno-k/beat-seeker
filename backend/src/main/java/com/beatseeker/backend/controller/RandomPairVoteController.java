package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.OptionVote;
import com.beatseeker.backend.entity.RandomPairVote;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.OptionVoteRepository;
import com.beatseeker.backend.repository.RandomPairVoteRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.AdminAuthService;
import com.beatseeker.backend.util.JstTime;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 【クラスの役割】 RANDOM の 2 つの並びの「どっちが押しやすい？」の回答を保存・書き出す API（管理者専用）。
 *
 * 管理者の比較画面（/admin/random-compare）が 1 問ごとに POST し、学習スクリプト
 * （scripts/fit-random-weights.mts）が export で全回答とオプション投票の集計（弱い正解）をまとめて取り出す。
 * 判定は AdminAuthService。ログイン必須は SecurityConfig で掛ける。
 *
 * エンドポイント:
 *  - POST   /api/admin/random-pairs        … 1 問分の回答を保存 → { id, count }
 *  - DELETE /api/admin/random-pairs/{id}   … 自分の回答を取り消す（直前の 1 問のやり直し）
 *  - GET    /api/admin/random-pairs/stats  … 自分の回答数 { count, skipped }
 *  - GET    /api/admin/random-pairs/export … { votes: [...], optionVotes: [{ title, difficultyName, counts }] }
 *  - GET    /api/external/v1/random-pairs/export … 同じ内容を個人 API トークン（期限なしで発行できる）で取る。
 *    学習スクリプトを毎晩などに自動で回す用（ログインの JWT は 7 日で切れるため）
 */
@RestController
public class RandomPairVoteController {

    private static final Set<String> CHOICES = Set.of("LEFT", "RIGHT", "SAME", "SKIP");

    private final RandomPairVoteRepository randomPairVoteRepository;
    private final OptionVoteRepository optionVoteRepository;
    private final UserRepository userRepository;
    private final AdminAuthService adminAuthService;

    public RandomPairVoteController(RandomPairVoteRepository randomPairVoteRepository,
                                    OptionVoteRepository optionVoteRepository,
                                    UserRepository userRepository,
                                    AdminAuthService adminAuthService) {
        this.randomPairVoteRepository = randomPairVoteRepository;
        this.optionVoteRepository = optionVoteRepository;
        this.userRepository = userRepository;
        this.adminAuthService = adminAuthService;
    }

    /** 1 問分の回答を保存する。 */
    @PostMapping("/api/admin/random-pairs")
    @Transactional
    public ResponseEntity<?> save(Authentication auth, @RequestBody PairVoteRequest req) {
        User me = adminOrNull(auth);
        if (me == null) return forbidden();
        if (req.textage() == null || req.textage().isBlank() || req.title() == null || req.difficulty() == null
                || !isPattern(req.patternLeft()) || !isPattern(req.patternRight())
                || req.patternLeft().equals(req.patternRight())
                || !CHOICES.contains(req.choice())
                || req.startTime() == null || req.endTime() == null || !(req.endTime() > req.startTime())) {
            return ResponseEntity.badRequest().body(Map.of("error", "回答の内容が正しくありません"));
        }
        RandomPairVote v = new RandomPairVote();
        v.setUser(me);
        v.setTextage(req.textage());
        v.setTitle(req.title());
        v.setDifficulty(req.difficulty());
        v.setLevel(req.level());
        v.setSide(req.side() != null && req.side() == 2 ? 2 : 1);
        v.setPatternLeft(req.patternLeft());
        v.setPatternRight(req.patternRight());
        v.setStartTime(req.startTime());
        v.setEndTime(req.endTime());
        v.setStartMeasure(req.startMeasure());
        v.setEndMeasure(req.endMeasure());
        v.setChoice(req.choice());
        v.setStrategy(req.strategy());
        v.setModelLeft(req.modelLeft());
        v.setModelRight(req.modelRight());
        v.setResponseMs(req.responseMs());
        v.setCreatedAt(LocalDateTime.now());
        randomPairVoteRepository.save(v);
        return ResponseEntity.ok(Map.of("id", v.getId(), "count", randomPairVoteRepository.countByUser(me)));
    }

    /** 自分の回答を取り消す（無ければ何もしない）。 */
    @DeleteMapping("/api/admin/random-pairs/{id}")
    @Transactional
    public ResponseEntity<?> delete(Authentication auth, @PathVariable Long id) {
        User me = adminOrNull(auth);
        if (me == null) return forbidden();
        randomPairVoteRepository.findById(id)
                .filter(v -> v.getUser().getId().equals(me.getId()))
                .ifPresent(randomPairVoteRepository::delete);
        return ResponseEntity.ok(Map.of("count", randomPairVoteRepository.countByUser(me)));
    }

    /** 自分の回答数。 */
    @GetMapping("/api/admin/random-pairs/stats")
    @Transactional(readOnly = true)
    public ResponseEntity<?> stats(Authentication auth) {
        User me = adminOrNull(auth);
        if (me == null) return forbidden();
        return ResponseEntity.ok(Map.of(
                "count", randomPairVoteRepository.countByUser(me),
                "skipped", randomPairVoteRepository.countByUserAndChoice(me, "SKIP")));
    }

    /** 学習用の書き出し: 全回答と、譜面ごとのオプション投票の集計（1P 視点）。 */
    @GetMapping({"/api/admin/random-pairs/export", "/api/external/v1/random-pairs/export"})
    @Transactional(readOnly = true)
    public ResponseEntity<?> export(Authentication auth) {
        User me = adminOrNull(auth);
        if (me == null) return forbidden();

        List<Map<String, Object>> votes = new ArrayList<>();
        for (RandomPairVote v : randomPairVoteRepository.findAllByOrderByIdAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("userId", v.getUser().getId());
            m.put("textage", v.getTextage());
            m.put("title", v.getTitle());
            m.put("difficulty", v.getDifficulty());
            m.put("level", v.getLevel());
            m.put("side", v.getSide());
            m.put("patternLeft", v.getPatternLeft());
            m.put("patternRight", v.getPatternRight());
            m.put("startTime", v.getStartTime());
            m.put("endTime", v.getEndTime());
            m.put("startMeasure", v.getStartMeasure());
            m.put("endMeasure", v.getEndMeasure());
            m.put("choice", v.getChoice());
            m.put("strategy", v.getStrategy());
            m.put("modelLeft", v.getModelLeft());
            m.put("modelRight", v.getModelRight());
            m.put("responseMs", v.getResponseMs());
            m.put("createdAt", JstTime.toIsoString(v.getCreatedAt()));
            votes.add(m);
        }

        // 譜面（曲名 + 難易度名）ごとのオプション別の票数。保存時に 1P 視点へそろえてある
        Map<String, Map<String, Object>> byChart = new TreeMap<>();
        for (OptionVote ov : optionVoteRepository.findAll()) {
            Map<String, Object> row = byChart.computeIfAbsent(ov.getTitle() + "\u0000" + ov.getDifficultyName(), k -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("title", ov.getTitle());
                r.put("difficultyName", ov.getDifficultyName());
                r.put("counts", new TreeMap<String, Integer>());
                return r;
            });
            @SuppressWarnings("unchecked")
            Map<String, Integer> counts = (Map<String, Integer>) row.get("counts");
            counts.merge(ov.getOptionType(), 1, Integer::sum);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exportedAt", JstTime.toIsoString(LocalDateTime.now()));
        result.put("votes", votes);
        result.put("optionVotes", new ArrayList<>(byChart.values()));
        return ResponseEntity.ok(result);
    }

    private static boolean isPattern(String p) {
        if (p == null || p.length() != 7) return false;
        boolean[] seen = new boolean[8];
        for (char c : p.toCharArray()) {
            if (c < '1' || c > '7' || seen[c - '0']) return false;
            seen[c - '0'] = true;
        }
        return true;
    }

    /** ログイン中の管理者。管理者でなければ null。 */
    private User adminOrNull(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        User me = userRepository.findByIidxId((String) auth.getPrincipal()).orElse(null);
        return me != null && adminAuthService.isAdmin(me) ? me : null;
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "この機能は管理者のみ利用できます"));
    }

    /** 回答 API のリクエストボディ。 */
    public record PairVoteRequest(String textage, String title, String difficulty, Integer level, Integer side,
                                  String patternLeft, String patternRight, Double startTime, Double endTime,
                                  Integer startMeasure, Integer endMeasure, String choice, String strategy,
                                  Double modelLeft, Double modelRight, Integer responseMs) {
    }
}
