package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 【サービスの役割】 月末振り返り（Spotify Wrapped 風）の集計ロジックを提供する。
 *
 * 月境界 [月初, 翌月初) に属する ScoreHistoryLog 群を取り出し、
 * 「月初時点のベーススナップショット」「月末時点（=月内最新）スナップショット」「月内 diff の集約」
 * を組み合わせて、各種 PT 増分・クリア種別更新数・伸び曲ランキング・前月比・自己ベスト判定などを返す。
 *
 * 月内更新譜面は (title|difficulty) で集約し、{@link BeatPtCalculator} と
 * {@link ScoreRecalculationService} のマスタ取得メソッドを使って、譜面ごとの
 * 月初/月末 BEAT-PT / RATE-PT を計算した上で、PT 増加量での TOP10 ランキングも返す。
 */
@Service
public class MonthlyWrappedService {

    /** スコア履歴スナップショットのリポジトリ。 */
    private final ScoreHistoryLogRepository historyRepo;
    /** BEAT-PT / RATE-PT の単曲計算ユーティリティ。 */
    private final BeatPtCalculator beatPtCalculator;
    /** songMaxScores / informalRanks を再利用するため再計算サービスに依存。 */
    private final ScoreRecalculationService scoreRecalcService;
    /** diffJson の解析に使う Jackson ObjectMapper。スレッドセーフ。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 【コンストラクタ】 Spring が依存を DI で注入する。
     */
    public MonthlyWrappedService(ScoreHistoryLogRepository historyRepo,
                                 BeatPtCalculator beatPtCalculator,
                                 ScoreRecalculationService scoreRecalcService) {
        this.historyRepo = historyRepo;
        this.beatPtCalculator = beatPtCalculator;
        this.scoreRecalcService = scoreRecalcService;
    }

    /**
     * 【メソッドの役割】 指定ユーザー・年月の月末振り返りデータを集計して返す。
     *
     * 集計手順:
     *  1. 月境界 [startDate, endDate) を作成し、月内ログ + 月初前ベース + 月末ログを揃える
     *  2. PT 系（BEAT/RATE/KENBAN/SARA）の差分を計算
     *  3. クリア種別・DJ レベル別カウントの差分を計算
     *  4. 月内アップロード回数・更新譜面数（延べ）を集計
     *  5. diffJson を全パースして同一譜面を集約（MergedSong リスト）
     *  6. 譜面ごとに BEAT-PT / RATE-PT を計算し、増加量で並べた TOP10 を 2 種抽出
     *  7. 前月の BEAT-PT 増分と全期間最高値を取得して比較情報を組み立て
     */
    public MonthlyWrappedResponse generate(User user, int year, int month) {
        LocalDateTime startDate = YearMonth.of(year, month).atDay(1).atStartOfDay();
        LocalDateTime endDate = startDate.plusMonths(1);

        List<ScoreHistoryLog> monthLogs = historyRepo
                .findByUserAndUploadedAtGreaterThanEqualAndUploadedAtLessThanOrderByUploadedAtAsc(
                        user, startDate, endDate);

        ScoreHistoryLog baseSnapshot = historyRepo
                .findFirstByUserAndUploadedAtLessThanOrderByUploadedAtDesc(user, startDate)
                .orElse(null);
        ScoreHistoryLog endSnapshot = monthLogs.isEmpty() ? null : monthLogs.get(monthLogs.size() - 1);
        ScoreHistoryLog effectiveEnd = endSnapshot != null ? endSnapshot : baseSnapshot;

        double startBeatPt = nullToZero(baseSnapshot != null ? baseSnapshot.getTotalBeatPt() : null);
        double endBeatPt = nullToZero(effectiveEnd != null ? effectiveEnd.getTotalBeatPt() : null);
        double startRatePt = nullToZero(baseSnapshot != null ? baseSnapshot.getTotalRatePt() : null);
        double endRatePt = nullToZero(effectiveEnd != null ? effectiveEnd.getTotalRatePt() : null);
        double startKenbanPt = nullToZero(baseSnapshot != null ? baseSnapshot.getTotalKenbanPt() : null);
        double endKenbanPt = nullToZero(effectiveEnd != null ? effectiveEnd.getTotalKenbanPt() : null);
        double startSaraPt = nullToZero(baseSnapshot != null ? baseSnapshot.getTotalSaraPt() : null);
        double endSaraPt = nullToZero(effectiveEnd != null ? effectiveEnd.getTotalSaraPt() : null);

        Map<String, Integer> clearTypeIncrease = new LinkedHashMap<>();
        clearTypeIncrease.put("fullcombo", intDiff(effectiveEnd, baseSnapshot, ScoreHistoryLog::getFcCount));
        clearTypeIncrease.put("exhard", intDiff(effectiveEnd, baseSnapshot, ScoreHistoryLog::getExhCount));
        clearTypeIncrease.put("hard", intDiff(effectiveEnd, baseSnapshot, ScoreHistoryLog::getHCount));
        clearTypeIncrease.put("clear", intDiff(effectiveEnd, baseSnapshot, ScoreHistoryLog::getClearCount));
        clearTypeIncrease.put("easy", intDiff(effectiveEnd, baseSnapshot, ScoreHistoryLog::getEasyCount));

        Map<String, Integer> djLevelIncrease = new LinkedHashMap<>();
        djLevelIncrease.put("aaa", intDiff(effectiveEnd, baseSnapshot, ScoreHistoryLog::getAaaCount));
        djLevelIncrease.put("aa", intDiff(effectiveEnd, baseSnapshot, ScoreHistoryLog::getAaCount));
        djLevelIncrease.put("a", intDiff(effectiveEnd, baseSnapshot, ScoreHistoryLog::getACount));

        int uploadCount = monthLogs.size();
        int totalUpdatedCount = monthLogs.stream()
                .mapToInt(l -> l.getUpdatedCount() != null ? l.getUpdatedCount() : 0)
                .sum();

        // 月内更新譜面を (title|difficulty) で集約し、各譜面の PT 値・新規達成・難易度を計算する
        List<MergedSong> merged = mergeAllSongs(monthLogs);
        enrichWithPtValues(merged);
        List<TopAchievement> topAchievements = extractTopAchievements(merged, 5);

        // TOP10:
        //  - BEAT-PT は「現在の newBeatPt 降順」で並べる。
        //    増加量降順だと weight の大きい高難易度譜面に初プレイで高スコアを出した曲が独占しやすいため、
        //    「全期間の BEAT-PT 上位のうち、月内に更新があった譜面」を抽出する形に変える。
        //  - RATE-PT は scoreRate しきい値計算 (77.77% 未満は 0pt) のため、初プレイ独占の問題が起きにくい。
        //    こちらは素直に増加量降順を維持する。
        List<TopUpdatedSong> topBeatPtSongs = merged.stream()
                .filter(s -> s.newBeatPt > 0)
                .sorted(Comparator.comparingDouble((MergedSong s) -> s.newBeatPt).reversed())
                .limit(10)
                .map(MonthlyWrappedService::toDto)
                .toList();
        List<TopUpdatedSong> topRatePtSongs = merged.stream()
                .filter(s -> ratePtDelta(s) > 0)
                .sorted(Comparator.comparingDouble(MonthlyWrappedService::ratePtDelta).reversed())
                .limit(10)
                .map(MonthlyWrappedService::toDto)
                .toList();
        // 既存 TOP5: scoreIncrease 降順（後方互換のため残す）
        List<TopUpdatedSong> topUpdatedSongs = merged.stream()
                .sorted(Comparator.comparingInt((MergedSong s) -> s.totalIncrease).reversed())
                .limit(20)
                .map(MonthlyWrappedService::toDto)
                .toList();

        double prevMonthBeatPtIncrease = computePreviousMonthBeatPtIncrease(user, startDate);

        Double allTimeMaxBeatPtBoxed = historyRepo.getLatestTotalBeatPtByUserId(user.getId());
        Double allTimeMaxRatePtBoxed = historyRepo.getLatestTotalRatePtByUserId(user.getId());
        double allTimeMaxBeatPt = nullToZero(allTimeMaxBeatPtBoxed);
        double allTimeMaxRatePt = nullToZero(allTimeMaxRatePtBoxed);

        boolean newBeatPtBest = endSnapshot != null && allTimeMaxBeatPtBoxed != null
                && Math.abs(endBeatPt - allTimeMaxBeatPt) < 0.001;
        boolean newRatePtBest = endSnapshot != null && allTimeMaxRatePtBoxed != null
                && Math.abs(endRatePt - allTimeMaxRatePt) < 0.001;

        // showRateTier はデフォルト true（User.java で @ColumnDefault("true")）。null も true 扱いに揃える。
        boolean showRateTier = !Boolean.FALSE.equals(user.getShowRateTier());

        return new MonthlyWrappedResponse(
                year, month,
                String.format("%d年%d月", year, month),
                startDate.toString(),
                endDate.toString(),
                user.getId(),
                user.getDisplayName() != null ? user.getDisplayName() : "",
                Boolean.TRUE.equals(user.getIsSupporter()),
                showRateTier,
                Boolean.TRUE.equals(user.getShowKenbanSaraTier()),
                startBeatPt, endBeatPt, endBeatPt - startBeatPt,
                startRatePt, endRatePt, endRatePt - startRatePt,
                startKenbanPt, endKenbanPt, endKenbanPt - startKenbanPt,
                startSaraPt, endSaraPt, endSaraPt - startSaraPt,
                uploadCount,
                totalUpdatedCount,
                merged.size(),
                clearTypeIncrease,
                djLevelIncrease,
                topUpdatedSongs,
                topBeatPtSongs,
                topRatePtSongs,
                topAchievements,
                prevMonthBeatPtIncrease,
                allTimeMaxBeatPt,
                allTimeMaxRatePt,
                newBeatPtBest,
                newRatePtBest
        );
    }

    /**
     * 前月の BEAT-PT 増分を「前月末スナップショット - 前々月末スナップショット」で算出する。
     * 当月の startDate を渡すと、それより前の最新が前月末になる。
     */
    private double computePreviousMonthBeatPtIncrease(User user, LocalDateTime currentMonthStart) {
        LocalDateTime prevMonthStart = currentMonthStart.minusMonths(1);
        Optional<ScoreHistoryLog> prevMonthEnd = historyRepo
                .findFirstByUserAndUploadedAtLessThanOrderByUploadedAtDesc(user, currentMonthStart);
        if (prevMonthEnd.isEmpty()) {
            return 0.0;
        }
        // 前月末が「先々月以前の」スナップショットなら、前月内のアップロードは無いとみなして 0
        if (prevMonthEnd.get().getUploadedAt().isBefore(prevMonthStart)) {
            return 0.0;
        }
        Optional<ScoreHistoryLog> prevPrev = historyRepo
                .findFirstByUserAndUploadedAtLessThanOrderByUploadedAtDesc(user, prevMonthStart);
        double end = nullToZero(prevMonthEnd.get().getTotalBeatPt());
        double base = prevPrev.map(s -> nullToZero(s.getTotalBeatPt())).orElse(0.0);
        return end - base;
    }

    /**
     * 月内ログの diffJson を全パースして (title|difficulty) で集約する。
     * 集約ルール:
     *  - firstOldScore / firstOldClearType: 最初に登場した時の oldScore / oldClearType（月初時点）
     *  - lastNewScore / lastNewClearType:   最後に登場した時の newScore / newClearType（月末時点）
     *  - totalIncrease: scoreIncrease を月内全更新ぶん合算
     *  - clearTypeImproved: 月内いずれかのログで改善があれば true
     */
    private List<MergedSong> mergeAllSongs(List<ScoreHistoryLog> logs) {
        Map<String, MergedSong> merged = new LinkedHashMap<>();
        for (ScoreHistoryLog log : logs) {
            String diffJson = log.getDiffJson();
            if (diffJson == null || diffJson.isBlank()) continue;
            List<Map<String, Object>> diffs;
            try {
                diffs = objectMapper.readValue(diffJson, new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) {
                continue;
            }
            for (Map<String, Object> diff : diffs) {
                String title = asString(diff.get("title"));
                String difficulty = asString(diff.get("difficulty"));
                if (title == null || difficulty == null) continue;
                String key = title + "|" + difficulty;

                Integer oldScore = toInt(diff.get("oldScore"));
                Integer newScore = toInt(diff.get("newScore"));
                Integer scoreIncrease = toInt(diff.get("scoreIncrease"));
                String oldClearType = asString(diff.get("oldClearType"));
                String newClearType = asString(diff.get("newClearType"));
                boolean clearTypeImproved = Boolean.TRUE.equals(diff.get("clearTypeImproved"));

                MergedSong existing = merged.get(key);
                if (existing == null) {
                    MergedSong ms = new MergedSong();
                    ms.title = title;
                    ms.difficulty = difficulty;
                    ms.firstOldScore = oldScore;
                    ms.lastNewScore = newScore;
                    ms.totalIncrease = scoreIncrease != null ? scoreIncrease : 0;
                    ms.firstOldClearType = oldClearType;
                    ms.lastNewClearType = newClearType;
                    ms.clearTypeImproved = clearTypeImproved;
                    merged.put(key, ms);
                } else {
                    existing.lastNewScore = newScore;
                    existing.totalIncrease += scoreIncrease != null ? scoreIncrease : 0;
                    existing.lastNewClearType = newClearType;
                    if (clearTypeImproved) {
                        existing.clearTypeImproved = true;
                    }
                }
            }
        }
        return new java.util.ArrayList<>(merged.values());
    }

    /** DJ Level AAA の閾値 (スコア率 %)。IIDX 公式: 8/9 ≒ 88.888...%。 */
    private static final double AAA_THRESHOLD_PCT = 100.0 * 8.0 / 9.0;
    /** MAX- の慣習的閾値 (スコア率 %)。AAA の中でも上位帯、IIDX では 17/18 ≒ 94.444...%。 */
    private static final double MAX_MINUS_THRESHOLD_PCT = 100.0 * 17.0 / 18.0;

    /**
     * 各 MergedSong に「月初時点 / 月末時点の BEAT-PT・RATE-PT」と
     * 「非公式難易度」「月内に到達した DJ レベル種別 (AAA / MAX-)」を計算して埋める。
     */
    private void enrichWithPtValues(List<MergedSong> merged) {
        Map<String, Integer> songMaxScores = scoreRecalcService.loadSongMaxScores();
        Map<String, String> informalRanks = scoreRecalcService.loadInformalRanks();

        for (MergedSong ms : merged) {
            String diffUpper = ms.difficulty != null ? ms.difficulty.toUpperCase() : "";
            String code = difficultyCode(diffUpper);
            if (code == null) continue;
            Integer maxScore = songMaxScores.get(ms.title + "_" + code);
            if (maxScore == null || maxScore == 0) continue;
            ms.maxScore = maxScore;

            double oldRate = nullToZero(ms.firstOldScore) * 100.0 / maxScore;
            double newRate = nullToZero(ms.lastNewScore) * 100.0 / maxScore;

            String informalRank = informalRanks.get(ms.title + "_" + diffUpper);
            ms.informalRank = informalRank;
            ms.oldBeatPt = beatPtCalculator.calculatePoints(oldRate, informalRank);
            ms.newBeatPt = beatPtCalculator.calculatePoints(newRate, informalRank);

            boolean rateEligible = "ANOTHER".equals(diffUpper) || "LEGGENDARIA".equals(diffUpper);
            if (rateEligible) {
                ms.oldRatePt = beatPtCalculator.calculateScoreRateTierPoints(oldRate);
                ms.newRatePt = beatPtCalculator.calculateScoreRateTierPoints(newRate);
            }

            // 新規到達判定: MAX- は AAA より優先（より上位の達成扱い）
            if (oldRate < MAX_MINUS_THRESHOLD_PCT && newRate >= MAX_MINUS_THRESHOLD_PCT) {
                ms.newAchievement = "MAX-";
            } else if (oldRate < AAA_THRESHOLD_PCT && newRate >= AAA_THRESHOLD_PCT) {
                ms.newAchievement = "AAA";
            }
        }
    }

    /**
     * 月内に AAA / MAX- に新規到達した譜面を、非公式難易度の数値降順で TOP N 抽出する。
     * 非公式難易度が無い (マスタ未登録) ものは降順比較で末尾に来るよう 0 扱い。
     */
    private List<TopAchievement> extractTopAchievements(List<MergedSong> merged, int limit) {
        return merged.stream()
                .filter(s -> s.newAchievement != null)
                .sorted(Comparator.comparingDouble(MonthlyWrappedService::parseRank).reversed())
                .limit(limit)
                .map(s -> new TopAchievement(
                        s.title, s.difficulty,
                        s.informalRank,
                        s.newAchievement,
                        s.lastNewScore,
                        s.maxScore))
                .toList();
    }

    /** "12.3" のような非公式難易度文字列を double に。null/parse 失敗は 0.0。 */
    private static double parseRank(MergedSong s) {
        if (s.informalRank == null) return 0.0;
        try { return Double.parseDouble(s.informalRank.trim()); } catch (NumberFormatException ignore) { return 0.0; }
    }

    /** ANOTHER → "4"、LEGGENDARIA → "10"。それ以外は null（songMaxScores に値なし）。 */
    private static String difficultyCode(String upperDiff) {
        return switch (upperDiff) {
            case "BEGINNER" -> "1";
            case "NORMAL" -> "2";
            case "HYPER" -> "3";
            case "ANOTHER" -> "4";
            case "LEGGENDARIA" -> "10";
            default -> null;
        };
    }

    private static double ratePtDelta(MergedSong s) {
        return s.newRatePt - s.oldRatePt;
    }

    private static TopUpdatedSong toDto(MergedSong ms) {
        double bpDelta = round1(ms.newBeatPt - ms.oldBeatPt);
        double rpDelta = round1(ms.newRatePt - ms.oldRatePt);
        return new TopUpdatedSong(
                ms.title, ms.difficulty,
                ms.firstOldScore, ms.lastNewScore, ms.totalIncrease,
                ms.firstOldClearType, ms.lastNewClearType, ms.clearTypeImproved,
                round1(ms.oldBeatPt), round1(ms.newBeatPt), bpDelta,
                round1(ms.oldRatePt), round1(ms.newRatePt), rpDelta
        );
    }

    private static double nullToZero(Double value) {
        return value != null ? value : 0.0;
    }

    private static double nullToZero(Integer value) {
        return value != null ? value : 0.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static int intDiff(ScoreHistoryLog end, ScoreHistoryLog base, Function<ScoreHistoryLog, Integer> getter) {
        int e = end != null && getter.apply(end) != null ? getter.apply(end) : 0;
        int b = base != null && getter.apply(base) != null ? getter.apply(base) : 0;
        return e - b;
    }

    private static Integer toInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignore) { return null; }
        }
        return null;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    /** diffJson マージ + PT 計算用の作業データ。 */
    private static class MergedSong {
        String title;
        String difficulty;
        Integer firstOldScore;
        Integer lastNewScore;
        int totalIncrease;
        String firstOldClearType;
        String lastNewClearType;
        boolean clearTypeImproved;
        double oldBeatPt;
        double newBeatPt;
        double oldRatePt;
        double newRatePt;
        /** 非公式難易度文字列（"12.3" 等）。マスタに無い場合は null。 */
        String informalRank;
        /** 月内に新規到達した DJ レベルの種別。"MAX-" / "AAA" / null。MAX- は AAA より優先表示。 */
        String newAchievement;
        /** その譜面の最大スコア (notes * 2)。マスタに無ければ 0。 */
        int maxScore;
    }

    /**
     * 【レコード】 月末振り返りのレスポンス本体。フロントの Wrapped カード描画に必要な集計値を一括で返す。
     *
     * topUpdatedSongs はスコア増加量で並べた上位 20 件（後方互換）。
     * topBeatPtSongs / topRatePtSongs は BEAT-PT / RATE-PT の単曲増加量で並べた上位 10 件。
     */
    public record MonthlyWrappedResponse(
            int year,
            int month,
            String displayMonth,
            String startDate,
            String endDate,
            Long userId,
            String displayName,
            boolean isSupporter,
            boolean showRateTier,
            boolean showKenbanSaraTier,
            double startBeatPt,
            double endBeatPt,
            double beatPtIncrease,
            double startRatePt,
            double endRatePt,
            double ratePtIncrease,
            double startKenbanPt,
            double endKenbanPt,
            double kenbanPtIncrease,
            double startSaraPt,
            double endSaraPt,
            double saraPtIncrease,
            int uploadCount,
            int totalUpdatedCount,
            int uniqueUpdatedSongCount,
            Map<String, Integer> clearTypeIncrease,
            Map<String, Integer> djLevelIncrease,
            List<TopUpdatedSong> topUpdatedSongs,
            List<TopUpdatedSong> topBeatPtSongs,
            List<TopUpdatedSong> topRatePtSongs,
            List<TopAchievement> topAchievements,
            double prevMonthBeatPtIncrease,
            double allTimeMaxBeatPt,
            double allTimeMaxRatePt,
            boolean newBeatPtBest,
            boolean newRatePtBest
    ) {}

    /**
     * 月内に AAA / MAX- に新規到達した譜面 1 件分。
     * informalRank は "12.3" 等のマスタ値、null もあり得る。
     * achievementType は "AAA" または "MAX-"（MAX- は AAA より優先）。
     */
    public record TopAchievement(
            String title,
            String difficulty,
            String informalRank,
            String achievementType,
            Integer newScore,
            int maxScore
    ) {}

    /**
     * 月内に伸びた譜面 1 件分のサマリ。
     * 各 PT 系（BEAT/RATE）について月初値・月末値・増加量を持つ。
     */
    public record TopUpdatedSong(
            String title,
            String difficulty,
            Integer oldScore,
            Integer newScore,
            int scoreIncrease,
            String oldClearType,
            String newClearType,
            boolean clearTypeImproved,
            double oldBeatPt,
            double newBeatPt,
            double beatPtIncrease,
            double oldRatePt,
            double newRatePt,
            double ratePtIncrease
    ) {}
}
