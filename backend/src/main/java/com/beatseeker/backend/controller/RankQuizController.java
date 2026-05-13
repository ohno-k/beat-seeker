package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.RankQuizMistake;
import com.beatseeker.backend.entity.RankQuizProgress;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.RankQuizMistakeRepository;
import com.beatseeker.backend.repository.RankQuizProgressRepository;
import com.beatseeker.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 【クラスの役割】 「非公式難易度クイズ」の進捗永続化と、復習プール提供を担うコントローラ。
 *
 * クライアントは以下の流れで使う:
 *  1. {@code GET /api/rank-quiz/progress} で現在の Lv/XP と復習対象リストを取得
 *  2. クライアント側で「自分のプレイ済み曲」と「復習対象」を混ぜて 5 問のセッションを組む
 *  3. 1 問回答するごとに {@code POST /api/rank-quiz/answer} を投げ、XP 加算と Lv 更新を受け取る
 *
 * クライアントが問題生成を担うことで、譜面情報（曲名・難易度名・正解ランク）の DB 保持を最小化している。
 *
 * 【XP / Lv 規則】
 *  - 正答（新規）: +10 XP
 *  - 正答（復習プール由来）: +20 XP
 *  - 不正答: +0 XP（mistake レコードに加算）
 *  - Lv N の到達閾値（累計）: {@code 100 * N * (N+1) / 2} XP
 *      Lv1: 0 / Lv2: 100 / Lv3: 300 / Lv4: 600 / Lv5: 1000 / ...
 */
@RestController
@RequestMapping("/api/rank-quiz")
public class RankQuizController {

    /** 1 回答あたりの基本獲得 XP。 */
    private static final int XP_BASE = 10;
    /** 復習プール由来の正答ボーナス XP。 */
    private static final int XP_REVIEW_BONUS = 10;

    private final RankQuizProgressRepository progressRepository;
    private final RankQuizMistakeRepository mistakeRepository;
    private final UserRepository userRepository;

    public RankQuizController(
            RankQuizProgressRepository progressRepository,
            RankQuizMistakeRepository mistakeRepository,
            UserRepository userRepository) {
        this.progressRepository = progressRepository;
        this.mistakeRepository = mistakeRepository;
        this.userRepository = userRepository;
    }

    /**
     * 【メソッドの役割】 ログインユーザーの進捗と復習対象リストを返す。
     *
     * 進捗未作成（クイズ未プレイ）でも「Lv1 / 0XP」のデフォルト値で返す（DB には書かない）。
     */
    @GetMapping("/progress")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getProgress(Authentication auth) {
        User user = getUser(auth);

        RankQuizProgress progress = progressRepository.findByUser(user).orElse(null);
        long xp = progress != null ? progress.getXp() : 0L;
        int level = progress != null ? progress.getLevel() : 1;
        int totalAnswered = progress != null ? progress.getTotalAnswered() : 0;
        int totalCorrect = progress != null ? progress.getTotalCorrect() : 0;

        // 復習プール: 直近未出題順で最大 50 件まで返す（クライアント側でランダムに数件採用）。
        List<RankQuizMistake> mistakes = mistakeRepository
                .findByUserAndMasteredFalseOrderByLastSeenAtAsc(user);
        List<Map<String, Object>> reviewPool = new ArrayList<>();
        int limit = Math.min(50, mistakes.size());
        for (int i = 0; i < limit; i++) {
            RankQuizMistake m = mistakes.get(i);
            reviewPool.add(Map.of(
                    "title", m.getTitle(),
                    "difficultyName", m.getDifficultyName(),
                    "correctRank", m.getCorrectRank(),
                    "mistakeCount", m.getMistakeCount(),
                    "reviewStreak", m.getReviewStreak()
            ));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("xp", xp);
        body.put("level", level);
        body.put("xpForCurrentLevel", thresholdForLevel(level));
        body.put("xpForNextLevel", thresholdForLevel(level + 1));
        body.put("totalAnswered", totalAnswered);
        body.put("totalCorrect", totalCorrect);
        body.put("reviewPool", reviewPool);
        body.put("reviewPoolCount", reviewPool.size());
        return ResponseEntity.ok(body);
    }

    /**
     * 【メソッドの役割】 1 問分の回答を受理し、XP / Lv / 復習プールを更新する。
     *
     * リクエスト body:
     *  - title          (String)  曲名
     *  - difficultyName (String)  "ANOTHER" | "LEGGENDARIA"
     *  - correctRank    (String)  正解ランク（例 "12.3"）
     *  - chosenRank     (String)  ユーザーが選んだランク
     *  - isReview       (boolean) この問題が復習プール由来か（XP 加算判定用）
     *
     * レスポンス body:
     *  - correct, xpGained, xp, level, leveledUp, xpForCurrentLevel, xpForNextLevel
     */
    @PostMapping("/answer")
    @Transactional
    public ResponseEntity<Map<String, Object>> submitAnswer(
            Authentication auth,
            @RequestBody AnswerRequest req) {

        User user = getUser(auth);
        if (req.title() == null || req.title().isBlank() ||
                req.difficultyName() == null || req.difficultyName().isBlank() ||
                req.correctRank() == null || req.correctRank().isBlank() ||
                req.chosenRank() == null || req.chosenRank().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing fields"));
        }

        boolean correct = req.correctRank().equals(req.chosenRank());

        // ── 進捗 upsert ────────────────────────────────────────────────
        RankQuizProgress progress = progressRepository.findByUser(user).orElseGet(() -> {
            RankQuizProgress p = new RankQuizProgress();
            p.setUser(user);
            return p;
        });

        int xpGained = 0;
        if (correct) {
            xpGained = XP_BASE + (Boolean.TRUE.equals(req.isReview()) ? XP_REVIEW_BONUS : 0);
        }
        long oldXp = progress.getXp() == null ? 0L : progress.getXp();
        int oldLevel = progress.getLevel() == null ? 1 : progress.getLevel();
        long newXp = oldXp + xpGained;
        int newLevel = levelFromXp(newXp);
        boolean leveledUp = newLevel > oldLevel;

        progress.setXp(newXp);
        progress.setLevel(newLevel);
        progress.setTotalAnswered((progress.getTotalAnswered() == null ? 0 : progress.getTotalAnswered()) + 1);
        progress.setTotalCorrect((progress.getTotalCorrect() == null ? 0 : progress.getTotalCorrect())
                + (correct ? 1 : 0));
        progress.setLastPlayedAt(LocalDateTime.now());
        progressRepository.save(progress);

        // ── 復習プール upsert ────────────────────────────────────────
        RankQuizMistake mistake = mistakeRepository
                .findByUserAndTitleAndDifficultyName(user, req.title(), req.difficultyName())
                .orElse(null);

        if (!correct) {
            // 不正答: mistake レコードを作成 or mistakeCount++、reviewStreak リセット
            if (mistake == null) {
                mistake = new RankQuizMistake();
                mistake.setUser(user);
                mistake.setTitle(req.title());
                mistake.setDifficultyName(req.difficultyName());
                mistake.setCorrectRank(req.correctRank());
                mistake.setMistakeCount(1);
                mistake.setReviewStreak(0);
                mistake.setMastered(false);
            } else {
                mistake.setMistakeCount(mistake.getMistakeCount() + 1);
                mistake.setReviewStreak(0);
                mistake.setMastered(false); // 卒業後に間違えたら復活
                mistake.setCorrectRank(req.correctRank()); // 表が更新された場合に追従
            }
            mistake.setLastSeenAt(LocalDateTime.now());
            mistakeRepository.save(mistake);
        } else if (Boolean.TRUE.equals(req.isReview()) && mistake != null) {
            // 復習正答: streak++、規定回数で mastered=true
            int newStreak = (mistake.getReviewStreak() == null ? 0 : mistake.getReviewStreak()) + 1;
            mistake.setReviewStreak(newStreak);
            if (newStreak >= RankQuizMistake.MASTERY_STREAK) {
                mistake.setMastered(true);
            }
            mistake.setLastSeenAt(LocalDateTime.now());
            mistakeRepository.save(mistake);
        }
        // 新規問題で正答した場合は復習プールに何もしない（そもそも入っていない）。

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("correct", correct);
        body.put("xpGained", xpGained);
        body.put("xp", newXp);
        body.put("level", newLevel);
        body.put("leveledUp", leveledUp);
        body.put("xpForCurrentLevel", thresholdForLevel(newLevel));
        body.put("xpForNextLevel", thresholdForLevel(newLevel + 1));
        return ResponseEntity.ok(body);
    }

    /**
     * 【関数の役割】 累計 XP から現在 Lv を導出する。
     * Lv N の累計閾値: 100 * N * (N+1) / 2 → Lv1=0, Lv2=100, Lv3=300, Lv4=600, Lv5=1000 ...
     */
    private static int levelFromXp(long xp) {
        if (xp < 100) return 1;
        // 二分探索で十分速いが、現実的なレンジなら線形でも 100 回程度でループ抜けるので簡素化。
        int lv = 1;
        while (thresholdForLevel(lv + 1) <= xp) {
            lv++;
            if (lv > 200) break; // 安全弁
        }
        return lv;
    }

    /** Lv N に達するために必要な累計 XP しきい値。Lv1 は 0。 */
    private static long thresholdForLevel(int level) {
        if (level <= 1) return 0L;
        long n = level - 1L;
        return 100L * n * (n + 1L) / 2L; // 100 * (1+2+...+(N-1)) → Lv2=100, Lv3=300, Lv4=600 ...
    }

    /** 認証情報からユーザー解決。 */
    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /**
     * 【DTO】 回答送信のリクエストボディ。
     */
    public record AnswerRequest(
            String title,
            String difficultyName,
            String correctRank,
            String chosenRank,
            Boolean isReview
    ) {}
}
