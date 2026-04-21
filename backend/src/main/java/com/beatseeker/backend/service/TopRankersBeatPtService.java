package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRankSong;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.repository.DifficultyRankRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Loads top-rankers CSV snapshots from classpath and computes BEAT-PT per
 * (version, prefecture) combination. Uses the same formula as
 * {@link ScoreRecalculationService} but drives it from the scraped TOP-scores
 * instead of a specific user's scores.
 */
@Service
public class TopRankersBeatPtService {

    private static final Logger log = LoggerFactory.getLogger(TopRankersBeatPtService.class);

    private static final String MANIFEST_PATH = "top-rankers-data/manifest.json";

    private static final int MAX_INIT_ATTEMPTS = 5;
    private static final long INIT_RETRY_BASE_DELAY_MS = 15_000L;

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

    // Mirrors ScoreRecalculationService.SCORE_RATE_THRESHOLDS.
    private static final double[][] SCORE_RATE_THRESHOLDS = {
            {77.77, 1.0},   {88.89, 2.0},   {94.44, 4.0},
            {97.22, 8.0},   {98.61, 16.0},  {99.31, 32.0},
            {99.65, 64.0},  {99.83, 128.0}, {99.91, 256.0},
            {100.0, 512.0}
    };

    // Columns in CSV: バージョン, タイトル,
    // BEGINNER EXスコア, BEGINNER DJName, BEGINNER 都道府県,
    // NORMAL   EXスコア, NORMAL   DJName, NORMAL   都道府県,
    // HYPER    EXスコア, HYPER    DJName, HYPER    都道府県,
    // ANOTHER  EXスコア, ANOTHER  DJName, ANOTHER  都道府県,
    // LEGGENDARIA EXスコア, LEGGENDARIA DJName, LEGGENDARIA 都道府県
    private static final String[] DIFF_NAMES = {"BEGINNER", "NORMAL", "HYPER", "ANOTHER", "LEGGENDARIA"};
    private static final String[] DIFF_CODES = {"1", "2", "3", "4", "10"};

    private final SongDefinitionRepository songDefinitionRepository;
    private final DifficultyRankRepository difficultyRankRepository;
    private final ObjectMapper objectMapper;

    private volatile List<Map<String, Object>> cached = Collections.emptyList();
    private volatile List<Map<String, Object>> cachedRate = Collections.emptyList();
    // key: title + "\0" + diffName  →  list of entries sorted desc by score
    private volatile Map<String, List<SongScoreEntry>> cachedSongScores = Collections.emptyMap();
    // key: versionNum + "\0" + prefectureFileNum  →  full score list for that area
    private volatile Map<String, AreaProfile> cachedAreaProfiles = Collections.emptyMap();

    public enum InitState { PENDING, RUNNING, SUCCESS, FAILED }

    private volatile InitState initState = InitState.PENDING;
    private volatile int initAttempts = 0;
    private volatile String lastError = null;
    private volatile long lastRecomputeDurationMs = -1L;
    private volatile long lastRecomputeFinishedAt = -1L;

    public record SongScoreEntry(int versionNum, String versionName,
                                  int prefectureFileNum, String prefectureName,
                                  String djName, int score) {}

    public record AreaScoreRow(String title, String difficultyName, int difficultyLevel,
                                int score, String djName, double scoreRate, String djLevel, String clearType) {}

    public record AreaProfile(int versionNum, String versionName,
                               int prefectureFileNum, String prefectureName,
                               List<AreaScoreRow> scores) {}

    public TopRankersBeatPtService(SongDefinitionRepository songDefinitionRepository,
                                   DifficultyRankRepository difficultyRankRepository,
                                   ObjectMapper objectMapper) {
        this.songDefinitionRepository = songDefinitionRepository;
        this.difficultyRankRepository = difficultyRankRepository;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        Thread t = new Thread(this::initWithRetry, "top-rankers-init");
        t.setDaemon(true);
        t.start();
    }

    private void initWithRetry() {
        for (int attempt = 1; attempt <= MAX_INIT_ATTEMPTS; attempt++) {
            initAttempts = attempt;
            initState = InitState.RUNNING;
            log.info("TopRankersBeatPtService init attempt {}/{} starting", attempt, MAX_INIT_ATTEMPTS);
            try {
                recompute();
                if (!cached.isEmpty()) {
                    initState = InitState.SUCCESS;
                    lastError = null;
                    log.info("TopRankersBeatPtService init succeeded on attempt {} (rows={})",
                            attempt, cached.size());
                    return;
                }
                lastError = "recompute produced empty result (manifest or CSVs unreadable)";
                log.warn("TopRankersBeatPtService attempt {} completed but cache is empty; {}",
                        attempt, lastError);
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.error("TopRankersBeatPtService attempt {} failed", attempt, e);
            }

            if (attempt < MAX_INIT_ATTEMPTS) {
                long delay = INIT_RETRY_BASE_DELAY_MS * attempt;
                log.info("TopRankersBeatPtService retrying in {} ms", delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    initState = InitState.FAILED;
                    log.warn("TopRankersBeatPtService init interrupted");
                    return;
                }
            }
        }
        initState = InitState.FAILED;
        log.error("TopRankersBeatPtService init giving up after {} attempts; lastError={}",
                MAX_INIT_ATTEMPTS, lastError);
    }

    public Map<String, Object> getInitStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", initState.name());
        status.put("attempts", initAttempts);
        status.put("lastError", lastError);
        status.put("cachedRows", cached.size());
        status.put("cachedSongKeys", cachedSongScores.size());
        status.put("cachedAreas", cachedAreaProfiles.size());
        status.put("lastRecomputeDurationMs", lastRecomputeDurationMs);
        status.put("lastRecomputeFinishedAt", lastRecomputeFinishedAt);
        return status;
    }

    public List<Map<String, Object>> getRanking() {
        return cached;
    }

    public List<Map<String, Object>> getRateRanking() {
        return cachedRate;
    }

    public List<SongScoreEntry> getSongTopRankers(String title, String diffName) {
        if (title == null || diffName == null) return Collections.emptyList();
        return cachedSongScores.getOrDefault(title + "\0" + diffName, Collections.emptyList());
    }

    public AreaProfile getAreaProfile(int versionNum, int prefectureFileNum) {
        return cachedAreaProfiles.get(versionNum + "\0" + prefectureFileNum);
    }

    /** Rebuild the cache. Call this when song definitions or difficulty ranks change. */
    public synchronized void recompute() {
        long t0 = System.currentTimeMillis();

        // Build (title,diffCode) -> maxScore and (title,diffCode) -> level from active song definitions.
        List<SongDefinition> activeSongs = songDefinitionRepository.findByRevision("active");
        Map<String, Integer> maxScoreMap = new HashMap<>();
        Map<String, Integer> levelMap = new HashMap<>();
        for (SongDefinition s : activeSongs) {
            if (s.getNotes() == null || s.getNotes() <= 0) continue;
            String key = s.getTitle() + "\0" + s.getDifficulty();
            maxScoreMap.put(key, s.getNotes() * 2);
            if (s.getLevel() != null) levelMap.put(key, s.getLevel());
        }
        log.info("TopRankersBeatPtService: loaded {} active song definitions (maxScoreMap={}, levelMap={})",
                activeSongs.size(), maxScoreMap.size(), levelMap.size());

        // Build (title,diffName) -> informalRank from active difficulty ranks.
        Map<String, String> informalRankMap = new HashMap<>();
        List<DifficultyRank> ranks = difficultyRankRepository.findByRevisionOrderBySortOrderAsc("active");
        for (DifficultyRank r : ranks) {
            String rankText = r.getRankValue();
            for (DifficultyRankSong song : r.getSongs()) {
                String songTitle = song.getSongTitle();
                if (songTitle == null) continue;
                if (songTitle.endsWith("[L]")) {
                    String baseTitle = songTitle.substring(0, songTitle.length() - 3).trim();
                    informalRankMap.put(baseTitle + "\0LEGGENDARIA", rankText);
                } else {
                    informalRankMap.put(songTitle + "\0ANOTHER", rankText);
                }
            }
        }
        log.info("TopRankersBeatPtService: loaded {} active difficulty ranks (informalRankMap={})",
                ranks.size(), informalRankMap.size());

        List<Map<String, Object>> manifest;
        try (InputStream in = new ClassPathResource(MANIFEST_PATH).getInputStream()) {
            manifest = objectMapper.readValue(in, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            log.error("TopRankersBeatPtService: failed to load manifest {}", MANIFEST_PATH, e);
            throw new RuntimeException("manifest load failed: " + MANIFEST_PATH, e);
        }
        log.info("TopRankersBeatPtService: manifest loaded ({} entries)", manifest.size());

        List<Map<String, Object>> beatResults = new ArrayList<>(manifest.size());
        List<Map<String, Object>> rateResults = new ArrayList<>(manifest.size());
        Map<String, List<SongScoreEntry>> songScoresBuilder = new HashMap<>();
        Map<String, AreaProfile> areaProfilesBuilder = new HashMap<>();
        int csvFailureCount = 0;
        String firstCsvFailurePath = null;
        Exception firstCsvFailureCause = null;
        for (Map<String, Object> entry : manifest) {
            Number versionNum = (Number) entry.get("versionNum");
            String versionName = (String) entry.get("versionName");
            Number prefFileNum = (Number) entry.get("prefectureFileNum");
            String prefectureName = (String) entry.get("prefectureName");
            String resourcePath = (String) entry.get("resourcePath");

            List<AreaScoreRow> areaRows = new ArrayList<>();
            double[] pts;
            try {
                pts = computePtsForCsv(resourcePath, maxScoreMap, levelMap, informalRankMap,
                        versionNum.intValue(), versionName, prefFileNum.intValue(), prefectureName,
                        songScoresBuilder, areaRows);
            } catch (Exception e) {
                csvFailureCount++;
                if (firstCsvFailurePath == null) {
                    firstCsvFailurePath = resourcePath;
                    firstCsvFailureCause = e;
                    log.error("TopRankersBeatPtService: failed to read CSV {} ({}: {})",
                            resourcePath, e.getClass().getSimpleName(), e.getMessage(), e);
                }
                continue;
            }
            areaProfilesBuilder.put(versionNum.intValue() + "\0" + prefFileNum.intValue(),
                    new AreaProfile(versionNum.intValue(), versionName,
                            prefFileNum.intValue(), prefectureName,
                            Collections.unmodifiableList(areaRows)));

            Map<String, Object> beatRow = new LinkedHashMap<>();
            beatRow.put("versionNum", versionNum.intValue());
            beatRow.put("versionName", versionName);
            beatRow.put("prefectureFileNum", prefFileNum.intValue());
            beatRow.put("prefectureName", prefectureName);
            beatRow.put("beatPt", Math.round(pts[0] * 10.0) / 10.0);
            beatResults.add(beatRow);

            Map<String, Object> rateRow = new LinkedHashMap<>();
            rateRow.put("versionNum", versionNum.intValue());
            rateRow.put("versionName", versionName);
            rateRow.put("prefectureFileNum", prefFileNum.intValue());
            rateRow.put("prefectureName", prefectureName);
            rateRow.put("ratePt", Math.round(pts[1] * 10.0) / 10.0);
            rateResults.add(rateRow);
        }

        beatResults.sort((a, b) -> Double.compare(
                ((Number) b.get("beatPt")).doubleValue(),
                ((Number) a.get("beatPt")).doubleValue()));
        rateResults.sort((a, b) -> Double.compare(
                ((Number) b.get("ratePt")).doubleValue(),
                ((Number) a.get("ratePt")).doubleValue()));

        // Sort each (title, diff) list by score desc and freeze.
        Map<String, List<SongScoreEntry>> finalized = new HashMap<>(songScoresBuilder.size() * 2);
        for (Map.Entry<String, List<SongScoreEntry>> e : songScoresBuilder.entrySet()) {
            List<SongScoreEntry> list = e.getValue();
            list.sort((a, b) -> Integer.compare(b.score(), a.score()));
            finalized.put(e.getKey(), Collections.unmodifiableList(list));
        }

        cached = Collections.unmodifiableList(beatResults);
        cachedRate = Collections.unmodifiableList(rateResults);
        cachedSongScores = Collections.unmodifiableMap(finalized);
        cachedAreaProfiles = Collections.unmodifiableMap(areaProfilesBuilder);
        long t1 = System.currentTimeMillis();
        lastRecomputeDurationMs = t1 - t0;
        lastRecomputeFinishedAt = t1;
        log.info("TopRankersBeatPtService: computed {} rows, {} song-diff keys, {} areas in {} ms (csvFailures={}, firstFailurePath={})",
                beatResults.size(), finalized.size(), areaProfilesBuilder.size(),
                lastRecomputeDurationMs, csvFailureCount, firstCsvFailurePath);
        if (csvFailureCount > 0) {
            log.warn("TopRankersBeatPtService: {} CSV(s) failed to read (first: {}); cache may be partial",
                    csvFailureCount, firstCsvFailurePath, firstCsvFailureCause);
        }
        if (beatResults.isEmpty() && !manifest.isEmpty()) {
            throw new RuntimeException("All " + manifest.size() + " manifest entries failed; first failure: "
                    + firstCsvFailurePath + " -> " + (firstCsvFailureCause == null ? "(none)" : firstCsvFailureCause.getMessage()));
        }
    }

    /** Returns [beatPt, ratePt] for the CSV at resourcePath. Also appends per-song entries to songScoresBuilder and per-area score rows to areaRows. */
    private double[] computePtsForCsv(String resourcePath,
                                      Map<String, Integer> maxScoreMap,
                                      Map<String, Integer> levelMap,
                                      Map<String, String> informalRankMap,
                                      int versionNum, String versionName,
                                      int prefFileNum, String prefectureName,
                                      Map<String, List<SongScoreEntry>> songScoresBuilder,
                                      List<AreaScoreRow> areaRows) throws Exception {
        List<Double> beatPts = new ArrayList<>();
        List<Double> ratePts = new ArrayList<>();
        int perfectRateCount = 0;
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream();
             GZIPInputStream gz = new GZIPInputStream(in);
             BufferedReader reader = new BufferedReader(new InputStreamReader(gz, StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // header
            if (line == null) return new double[]{0.0, 0.0};
            while ((line = reader.readLine()) != null) {
                String[] cols = splitCsv(line);
                // Expect at least 2 + 5*3 = 17 columns
                if (cols.length < 2 + 5 * 3) continue;
                String title = cols[1];
                for (int d = 0; d < DIFF_NAMES.length; d++) {
                    String scoreStr = cols[2 + d * 3];
                    if (scoreStr == null || scoreStr.isEmpty()) continue;
                    int score;
                    try {
                        score = Integer.parseInt(scoreStr.trim());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (score <= 0) continue;

                    String diffName = DIFF_NAMES[d];
                    String diffCode = DIFF_CODES[d];
                    String keyCode = title + "\0" + diffCode;
                    Integer maxScore = maxScoreMap.get(keyCode);
                    if (maxScore == null || maxScore == 0) continue;
                    double scoreRate = score * 100.0 / maxScore;

                    // Per-song top-rankers index (for song-detail ranking tab).
                    String djName = cols[3 + d * 3];
                    songScoresBuilder
                            .computeIfAbsent(title + "\0" + diffName, k -> new ArrayList<>())
                            .add(new SongScoreEntry(versionNum, versionName, prefFileNum, prefectureName,
                                    djName == null ? "" : djName, score));

                    // Area profile row (for TOP ranker virtual profile view).
                    Integer level = levelMap.get(keyCode);
                    String djLevel = calcDjLevel(scoreRate);
                    areaRows.add(new AreaScoreRow(title, diffName, level == null ? 0 : level,
                            score, djName == null ? "" : djName, scoreRate, djLevel, "NO PLAY"));

                    // BEAT-PT: exclude HYPER with level >= 11 (matches ScoreRecalculationService).
                    boolean beatEligible = !("HYPER".equals(diffName)
                            && levelMap.get(keyCode) != null && levelMap.get(keyCode) >= 11);
                    if (beatEligible) {
                        String informalRank = informalRankMap.get(title + "\0" + diffName);
                        double pt = calculatePoints(scoreRate, informalRank);
                        if (pt > 0) beatPts.add(pt);
                    }

                    // RATE-PT: only ANOTHER and LEGGENDARIA.
                    if ("ANOTHER".equals(diffName) || "LEGGENDARIA".equals(diffName)) {
                        double rPt = calculateScoreRateTierPoints(scoreRate);
                        if (rPt > 0) ratePts.add(rPt);
                        if (scoreRate >= 100.0) perfectRateCount++;
                    }
                }
            }
        }
        beatPts.sort(Collections.reverseOrder());
        ratePts.sort(Collections.reverseOrder());
        double beatTotal = 0;
        for (int i = 0; i < Math.min(100, beatPts.size()); i++) beatTotal += beatPts.get(i);
        double rateTotal = 0;
        for (int i = 0; i < Math.min(100, ratePts.size()); i++) rateTotal += ratePts.get(i);
        if (perfectRateCount > 100) rateTotal += (perfectRateCount - 100);
        return new double[]{beatTotal, rateTotal};
    }

    private static double calculateScoreRateTierPoints(double scoreRate) {
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

    private static double calculatePoints(double scoreRate, String informalRank) {
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

    /** Compute DJ level (AAA/AA/…/F) from score rate, matching existing client-side logic. */
    private static String calcDjLevel(double scoreRate) {
        if (scoreRate >= 100.0 / 9 * 8) return "AAA";
        if (scoreRate >= 100.0 / 9 * 7) return "AA";
        if (scoreRate >= 100.0 / 9 * 6) return "A";
        if (scoreRate >= 100.0 / 9 * 5) return "B";
        if (scoreRate >= 100.0 / 9 * 4) return "C";
        if (scoreRate >= 100.0 / 9 * 3) return "D";
        if (scoreRate >= 100.0 / 9 * 2) return "E";
        return "F";
    }

    private static int getWeight(String informalRank) {
        if (informalRank == null || informalRank.isEmpty()) return 0;
        Matcher m = Pattern.compile("(\\d+\\.\\d+)").matcher(informalRank);
        String key = m.find() ? m.group(1) : informalRank;
        return WEIGHTS.getOrDefault(key, 0);
    }

    /** Minimal CSV splitter: handles double-quoted fields with embedded commas and escaped quotes. */
    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else if (c == '"' && cur.length() == 0) {
                    inQuotes = true;
                } else {
                    cur.append(c);
                }
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
