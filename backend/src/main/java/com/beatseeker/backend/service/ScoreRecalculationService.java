package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ScoreRecalculationService {

    private final UserRepository userRepository;
    private final ScoreRepository scoreRepository;
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;
    private final ObjectMapper objectMapper;

    // Weights configuration mapped from beatTier.ts
    private static final Map<String, Integer> WEIGHTS = new HashMap<>();

    static {
        int weight = 145;
        for (int i = 0; i <= 20; i++) {
            double rankValue = 11.0 + i * 0.1;
            String rank = String.format(Locale.US, "%.1f", rankValue);
            WEIGHTS.put(rank, weight);
            weight += (rankValue >= 12.49) ? 3 : 2;
        }
    }

    private static final double[][] SCORE_RATE_THRESHOLDS = {
            {77.77, 1.0},
            {88.89, 2.0},
            {94.44, 4.0},
            {97.22, 8.0},
            {98.61, 16.0},
            {99.31, 32.0},
            {99.65, 64.0},
            {99.83, 128.0},
            {99.91, 256.0},
            {100.0, 512.0}
    };

    public ScoreRecalculationService(UserRepository userRepository, ScoreRepository scoreRepository, ScoreHistoryLogRepository scoreHistoryLogRepository, ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.scoreRepository = scoreRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.objectMapper = objectMapper;
    }

    @Async
    public void recalculateAllUsersAsync(String songDataJson, String difficultyTableJson) throws Exception {
        JsonNode songDataRoot = objectMapper.readTree(songDataJson);
        JsonNode diffTableRoot = objectMapper.readTree(difficultyTableJson);

        // 1. Build map for maxScore (title_code -> maxScore)
        Map<String, Integer> songMaxScores = new HashMap<>();
        if (songDataRoot.has("body") && songDataRoot.get("body").isArray()) {
            for (JsonNode s : songDataRoot.get("body")) {
                String title = s.path("title").asText().trim();
                String diffCode = s.path("difficulty").asText();
                int notes = s.path("notes").asInt(0);
                if (notes > 0) {
                    songMaxScores.put(title + "_" + diffCode, notes * 2);
                }
            }
        }

        // 2. Build map for informal rank (title_diffName -> informalRank text)
        Map<String, String> informalRanks = new HashMap<>();
        if (diffTableRoot.has("ranks") && diffTableRoot.get("ranks").isArray()) {
            for (JsonNode r : diffTableRoot.get("ranks")) {
                String rankText = r.path("rank").asText();
                if (r.has("songs") && r.get("songs").isArray()) {
                    for (JsonNode songTitleNode : r.get("songs")) {
                        String songTitle = songTitleNode.asText().trim();
                        if (songTitle.endsWith("[L]")) {
                            String baseTitle = songTitle.substring(0, songTitle.length() - 3).trim();
                            informalRanks.put(baseTitle + "_LEGGENDARIA", rankText);
                        } else {
                            informalRanks.put(songTitle + "_ANOTHER", rankText);
                        }
                    }
                }
            }
        }

        // 3. Iterate users — each user saved in its own transaction
        List<User> users = userRepository.findAll();
        for (User user : users) {
            try {
                processUserRecalculation(user, songMaxScores, informalRanks);
            } catch (Exception e) {
                System.err.println("Failed to recalculate user " + user.getId() + ": " + e.getMessage());
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processUserRecalculation(User user, Map<String, Integer> songMaxScores, Map<String, String> informalRanks) {
        List<Score> scores = scoreRepository.findByUserOrderByUploadedAtAsc(user);
        if (scores.isEmpty()) return;

        List<Double> beatPts = new ArrayList<>();
        List<Double> ratePts = new ArrayList<>();

        long totalScore = 0;
        int fcCount = 0;
        int exhCount = 0;
        int hCount = 0;
        int clearCount = 0;
        int easyCount = 0;
        int aaaCount = 0;
        int aaCount = 0;
        int aCount = 0;

        for (Score score : scores) {
            if ("---".equals(score.getClearType()) || "NO PLAY".equals(score.getClearType())) continue;

            if (score.getScore() != null) totalScore += score.getScore();
            if ("FULLCOMBO CLEAR".equals(score.getClearType())) fcCount++;
            if ("EX HARD CLEAR".equals(score.getClearType())) exhCount++;
            if ("HARD CLEAR".equals(score.getClearType())) hCount++;
            if ("CLEAR".equals(score.getClearType())) clearCount++;
            if ("EASY CLEAR".equals(score.getClearType())) easyCount++;
            if ("AAA".equals(score.getDjLevel())) aaaCount++;
            if ("AA".equals(score.getDjLevel())) aaCount++;
            if ("A".equals(score.getDjLevel())) aCount++;

            String diffName = normalizeDiffName(score.getDifficultyName());
            String code = getDifficultyCode(diffName);
            if (code == null) continue;

            Integer maxScore = songMaxScores.get(score.getTitle() + "_" + code);
            if (maxScore == null || maxScore == 0) continue;

            double scoreRate = (score.getScore() != null ? score.getScore() : 0) * 100.0 / maxScore;

            String informalRankString = informalRanks.get(score.getTitle() + "_" + diffName);

            // BEAT-PT
            boolean isHyperNonTarget = "HYPER".equals(diffName) && score.getDifficultyLevel() != null && score.getDifficultyLevel() >= 11;
            if (!isHyperNonTarget) {
                double pt = calculatePoints(scoreRate, informalRankString);
                if (pt > 0) beatPts.add(pt);
            }

            // RATE-PT
            boolean isRateEligible = "ANOTHER".equals(diffName) || "LEGGENDARIA".equals(diffName);
            if (isRateEligible && scoreRate > 0) {
                double rPt = calculateScoreRateTierPoints(scoreRate);
                if (rPt > 0) ratePts.add(rPt);
            }
        }

        beatPts.sort(Collections.reverseOrder());
        double totalBeatPtAcc = 0;
        for (int i = 0; i < Math.min(100, beatPts.size()); i++) totalBeatPtAcc += beatPts.get(i);
        double finalBeatPt = Math.round(totalBeatPtAcc * 10.0) / 10.0;

        ratePts.sort(Collections.reverseOrder());
        double totalRatePtAcc = 0;
        for (int i = 0; i < Math.min(100, ratePts.size()); i++) totalRatePtAcc += ratePts.get(i);
        double finalRatePt = Math.round(totalRatePtAcc * 10.0) / 10.0;

        List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(user);
        double oldBeatPt = logs.isEmpty() ? 0 : (logs.get(logs.size() - 1).getTotalBeatPt() != null ? logs.get(logs.size() - 1).getTotalBeatPt() : 0);

        ScoreHistoryLog newLog = new ScoreHistoryLog();
        newLog.setUser(user);
        newLog.setUploadedAt(LocalDateTime.now());
        newLog.setTotalScore(totalScore);
        newLog.setFcCount(fcCount);
        newLog.setExhCount(exhCount);
        newLog.setHCount(hCount);
        newLog.setClearCount(clearCount);
        newLog.setEasyCount(easyCount);
        newLog.setAaaCount(aaaCount);
        newLog.setAaCount(aaCount);
        newLog.setACount(aCount);
        newLog.setTotalBeatPt(finalBeatPt);
        newLog.setBeatPtIncrease(finalBeatPt - oldBeatPt);
        newLog.setUpdatedCount(0);
        newLog.setDiffJson("[]");
        newLog.setTotalPrecisionPt(0.0);
        newLog.setTotalRatePt(finalRatePt);

        scoreHistoryLogRepository.save(newLog);
    }

    private String normalizeDiffName(String diff) {
        if (diff == null) return "UNKNOWN";
        return diff.toUpperCase();
    }

    private String getDifficultyCode(String upperDiff) {
        return switch (upperDiff) {
            case "BEGINNER" -> "1";
            case "NORMAL" -> "2";
            case "HYPER" -> "3";
            case "ANOTHER" -> "4";
            case "LEGGENDARIA" -> "10";
            default -> null;
        };
    }

    private int getWeight(String informalRank) {
        if (informalRank == null || informalRank.isEmpty()) return 0;
        Matcher m = Pattern.compile("(\\d+\\.\\d+)").matcher(informalRank);
        String key = m.find() ? m.group(1) : informalRank;
        return WEIGHTS.getOrDefault(key, 0);
    }

    private double calculatePoints(double scoreRate, String informalRank) {
        if (informalRank == null) return 0.0;
        int weight = getWeight(informalRank);
        if (weight == 0 || scoreRate <= 66.666) return 0.0;

        double basePoints = Math.pow(scoreRate / 100.0, 1.3) * weight;

        double bonus = 0;
        if (scoreRate > 77.77) bonus += weight * 0.01;
        if (scoreRate > 88.88) bonus += weight * 0.01;
        if (scoreRate > 94.44) bonus += weight * 0.01;

        return basePoints + bonus;
    }

    private double calculateScoreRateTierPoints(double scoreRate) {
        if (scoreRate <= 0 || scoreRate < SCORE_RATE_THRESHOLDS[0][0]) return 0.0;
        double lastRate = SCORE_RATE_THRESHOLDS[SCORE_RATE_THRESHOLDS.length - 1][0];
        double lastPt = SCORE_RATE_THRESHOLDS[SCORE_RATE_THRESHOLDS.length - 1][1];
        if (scoreRate >= lastRate) return lastPt;

        for (int i = 0; i < SCORE_RATE_THRESHOLDS.length - 1; i++) {
            double loRate = SCORE_RATE_THRESHOLDS[i][0];
            double loPt = SCORE_RATE_THRESHOLDS[i][1];
            double hiRate = SCORE_RATE_THRESHOLDS[i + 1][0];
            double hiPt = SCORE_RATE_THRESHOLDS[i + 1][1];

            if (scoreRate < hiRate) {
                double t = (scoreRate - loRate) / (hiRate - loRate);
                return loPt + t * (hiPt - loPt);
            }
        }
        return 0.0;
    }
}
