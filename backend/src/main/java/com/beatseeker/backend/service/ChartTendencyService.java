package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.ChartTendencyProfile;
import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRankSong;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ChartTendencyProfileRepository;
import com.beatseeker.backend.repository.DifficultyRankRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ChartTendencyService {

    private final ChartTendencyProfileRepository profileRepo;
    private final ScoreRepository scoreRepo;
    private final SongDefinitionRepository songDefRepo;
    private final DifficultyRankRepository difficultyRankRepo;
    private final ObjectMapper objectMapper;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public ChartTendencyService(ChartTendencyProfileRepository profileRepo,
                                ScoreRepository scoreRepo,
                                SongDefinitionRepository songDefRepo,
                                DifficultyRankRepository difficultyRankRepo,
                                ObjectMapper objectMapper) {
        this.profileRepo = profileRepo;
        this.scoreRepo = scoreRepo;
        this.songDefRepo = songDefRepo;
        this.difficultyRankRepo = difficultyRankRepo;
        this.objectMapper = objectMapper;
    }

    // ── インポート ──────────────────────────────────────────────

    /**
     * chart_cache/profiles/ 以下の全 JSON を DB に一括登録/更新する。
     *
     * @param profilesDirPath profiles ディレクトリのパス (例: "../chart_cache/profiles")
     * @return 処理件数サマリー
     */
    @Transactional
    public Map<String, Object> importFromDirectory(String profilesDirPath) throws IOException {
        Path root = Paths.get(profilesDirPath);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + profilesDirPath);
        }

        int skipped = 0;

        // textage をキーにして重複を排除（後勝ち）
        Map<String, ChartTendencyProfile> profileMap = new LinkedHashMap<>();

        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> jsonFiles = paths
                    .filter(p -> p.toString().endsWith(".json"))
                    .collect(Collectors.toList());

            for (Path file : jsonFiles) {
                try {
                    JsonNode node = objectMapper.readTree(file.toFile());
                    ChartTendencyProfile profile = jsonToProfile(node);
                    if (profile == null || profile.getNotes() == null || profile.getNotes() == 0) {
                        skipped++;
                        continue;
                    }
                    profileMap.put(profile.getTextage(), profile);
                } catch (Exception e) {
                    skipped++;
                }
            }
        }

        List<ChartTendencyProfile> toSave = new ArrayList<>(profileMap.values());

        // 全削除→バルクINSERT（SELECT不要で高速）
        int previousCount = (int) profileRepo.count();
        profileRepo.deleteAllInBatch();
        profileRepo.flush();

        // チャンク分割してsaveAll（バッチINSERTが効く）
        int batchSize = 500;
        for (int i = 0; i < toSave.size(); i += batchSize) {
            int end = Math.min(i + batchSize, toSave.size());
            profileRepo.saveAll(toSave.subList(i, end));
            profileRepo.flush();
            entityManager.clear();
        }

        return Map.of(
                "inserted", toSave.size(),
                "replaced", previousCount,
                "skipped", skipped,
                "total", toSave.size()
        );
    }

    /**
     * JSON 配列をリクエストボディから直接インポートする。
     * chart_cache ディレクトリが存在しない本番環境用。
     */
    @Transactional
    public Map<String, Object> importFromJsonArray(JsonNode arrayNode) {
        if (!arrayNode.isArray()) {
            throw new IllegalArgumentException("Expected a JSON array");
        }

        int skipped = 0;
        Map<String, ChartTendencyProfile> profileMap = new LinkedHashMap<>();

        for (JsonNode node : arrayNode) {
            try {
                ChartTendencyProfile profile = jsonToProfile(node);
                if (profile == null || profile.getNotes() == null || profile.getNotes() == 0) {
                    skipped++;
                    continue;
                }
                profileMap.put(profile.getTextage(), profile);
            } catch (Exception e) {
                skipped++;
            }
        }

        List<ChartTendencyProfile> toSave = new ArrayList<>(profileMap.values());

        int previousCount = (int) profileRepo.count();
        profileRepo.deleteAllInBatch();
        profileRepo.flush();

        int batchSize = 500;
        for (int i = 0; i < toSave.size(); i += batchSize) {
            int end = Math.min(i + batchSize, toSave.size());
            profileRepo.saveAll(toSave.subList(i, end));
            profileRepo.flush();
            entityManager.clear();
        }

        return Map.of(
                "inserted", toSave.size(),
                "replaced", previousCount,
                "skipped", skipped,
                "total", toSave.size()
        );
    }

    private ChartTendencyProfile jsonToProfile(JsonNode n) {
        String textage = n.path("textage").asText(null);
        if (textage == null || textage.isBlank()) return null;

        ChartTendencyProfile p = new ChartTendencyProfile();
        p.setTextage(textage);
        p.setTitle(n.path("title").asText(null));
        p.setArtist(n.path("artist").asText(null));
        p.setDifficulty(n.path("difficulty").asText("4"));
        p.setLevel(n.has("level") ? n.path("level").asInt() : null);
        p.setBpmRaw(n.path("bpm_raw").asText(null));
        p.setBpmMain(n.has("bpm_main") ? n.path("bpm_main").asInt() : null);
        p.setIsSoflan(n.has("is_soflan") ? n.path("is_soflan").asBoolean() : null);
        p.setNotes(n.has("notes") ? n.path("notes").asInt() : null);
        p.setEvents(n.has("events") ? n.path("events").asInt() : null);
        p.setDominantEff16(n.has("dominant_eff16") ? n.path("dominant_eff16").asDouble() : null);
        p.setWeightedEff16(n.has("weighted_eff16") ? n.path("weighted_eff16").asDouble() : null);
        p.setScratchPct(n.has("scratch_pct") ? n.path("scratch_pct").asDouble() : null);
        p.setChordPct(n.has("chord_pct") ? n.path("chord_pct").asDouble() : null);
        p.setSinglePct(n.has("single_pct") ? n.path("single_pct").asDouble() : null);
        p.setRanuchi(n.has("ranuchi") ? n.path("ranuchi").asInt() : null);

        if (n.has("tags")) {
            try { p.setTagsJson(objectMapper.writeValueAsString(n.get("tags"))); }
            catch (Exception ignored) {}
        }
        if (n.has("interval_dist")) {
            try { p.setIntervalDistJson(objectMapper.writeValueAsString(n.get("interval_dist"))); }
            catch (Exception ignored) {}
        }
        if (n.has("chord_dist")) {
            try { p.setChordDistJson(objectMapper.writeValueAsString(n.get("chord_dist"))); }
            catch (Exception ignored) {}
        }
        if (n.has("cn_notes")) {
            p.setCnNotes(n.path("cn_notes").asInt());
        }
        if (n.has("cn_scratch_pct")) {
            p.setCnScratchPct(n.path("cn_scratch_pct").asDouble());
        }
        if (n.has("cn_kbd_overlap_pct")) {
            p.setCnKbdOverlapPct(n.path("cn_kbd_overlap_pct").asDouble());
        }
        if (n.has("cn_interval_dist")) {
            try { p.setCnIntervalDistJson(objectMapper.writeValueAsString(n.get("cn_interval_dist"))); }
            catch (Exception ignored) {}
        }
        if (n.has("kbd_interval_dist")) {
            try { p.setKbdIntervalDistJson(objectMapper.writeValueAsString(n.get("kbd_interval_dist"))); }
            catch (Exception ignored) {}
        }
        if (n.has("scr_interval_dist")) {
            try { p.setScrIntervalDistJson(objectMapper.writeValueAsString(n.get("scr_interval_dist"))); }
            catch (Exception ignored) {}
        }

        // 配置パターン属性
        if (n.has("jack_count"))  p.setJackCount(n.path("jack_count").asInt());
        if (n.has("jack_notes"))  p.setJackNotes(n.path("jack_notes").asInt());
        if (n.has("jack_pct"))    p.setJackPct(n.path("jack_pct").asDouble());
        if (n.has("trill_count")) p.setTrillCount(n.path("trill_count").asInt());
        if (n.has("trill_notes")) p.setTrillNotes(n.path("trill_notes").asInt());
        if (n.has("trill_pct"))   p.setTrillPct(n.path("trill_pct").asDouble());
        if (n.has("stairs_count"))  p.setStairsCount(n.path("stairs_count").asInt());
        if (n.has("stairs_notes"))  p.setStairsNotes(n.path("stairs_notes").asInt());
        if (n.has("stairs_pct"))    p.setStairsPct(n.path("stairs_pct").asDouble());
        if (n.has("dstairs_count")) p.setDstairsCount(n.path("dstairs_count").asInt());
        if (n.has("dstairs_notes")) p.setDstairsNotes(n.path("dstairs_notes").asInt());
        if (n.has("dstairs_pct"))   p.setDstairsPct(n.path("dstairs_pct").asDouble());

        if (n.has("measure_notes")) {
            try { p.setMeasureNotesJson(objectMapper.writeValueAsString(n.get("measure_notes"))); }
            catch (Exception ignored) {}
        }
        if (n.has("measure_notes_kbd")) {
            try { p.setMeasureNotesKbdJson(objectMapper.writeValueAsString(n.get("measure_notes_kbd"))); }
            catch (Exception ignored) {}
        }
        if (n.has("measure_notes_scr")) {
            try { p.setMeasureNotesScrJson(objectMapper.writeValueAsString(n.get("measure_notes_scr"))); }
            catch (Exception ignored) {}
        }

        return p;
    }

    // ── 参照 ────────────────────────────────────────────────────

    public Optional<ChartTendencyProfile> getByTextage(String textage) {
        return profileRepo.findById(textage);
    }

    public List<ChartTendencyProfile> getByLevel(int level) {
        return profileRepo.findByLevel(level);
    }

    public List<ChartTendencyProfile> getByLevelAndDifficulty(int level, String difficulty) {
        return profileRepo.findByLevelAndDifficulty(level, difficulty);
    }

    public List<ChartTendencyProfile> getByTextageBase(String textageBase) {
        return profileRepo.findByTextageBase(textageBase);
    }

    // ── スコア予測 ───────────────────────────────────────────────

    /**
     * 指定 textage 譜面についてユーザーの予測スコアを返す。
     *
     * アルゴリズム:
     * 1. 対象譜面のプロファイルを取得
     * 2. ユーザーの ANOTHER/LEGG スコア全件を取得
     * 3. 各スコアに対応する ChartTendencyProfile を参照し類似度を計算
     * 4. 上位 K 件の加重平均スコア率から予測スコアを算出
     */
    public Map<String, Object> predictScore(User user, String textage) {
        Optional<ChartTendencyProfile> targetOpt = profileRepo.findById(textage);
        if (targetOpt.isEmpty()) {
            return Map.of("error", "プロファイルが見つかりません: " + textage);
        }
        ChartTendencyProfile target = targetOpt.get();

        // song_data.json 由来のノーツ数マップ: "title\tdiff" → notes
        // ChartTendencyProfile.notes はtextage解析値で不正確なため使わない
        Map<String, Integer> notesMap = songDefRepo.findByRevision("active").stream()
                .filter(sd -> sd.getNotes() != null && sd.getNotes() > 0)
                .collect(Collectors.toMap(
                        sd -> sd.getTitle() + "\t" + sd.getDifficulty(),
                        SongDefinition::getNotes,
                        (a, b) -> a
                ));

        // 非公式難易度マップ: "title\tdiffCode" → rankValue
        Map<String, String> informalRankMap = buildInformalRankMap();
        Double irTarget = parseRank(informalRankMap.get(target.getTitle() + "\t" + target.getDifficulty()));

        int targetNotes = notesMap.getOrDefault(
                target.getTitle() + "\t" + target.getDifficulty(),
                target.getNotes() != null ? target.getNotes() : 0);
        if (targetNotes == 0) {
            return Map.of("error", "対象譜面のノーツ数が不明です");
        }

        // 全プロファイルを1回で取得してメモリ内 Map に展開（N+1クエリ回避）
        Map<String, ChartTendencyProfile> profileMap = profileRepo.findAll().stream()
                .collect(Collectors.toMap(
                        p -> p.getTitle() + "\t" + p.getDifficulty(),
                        p -> p,
                        (a, b) -> a
                ));

        // ユーザーの全スコアを1回で取得
        List<Score> userScores = scoreRepo.findByUserOrderByUploadedAtAsc(user);

        // ターゲット曲の現在スコアを取得
        String targetDiffName = target.getDifficulty().equals("10") ? "LEGGENDARIA" : "ANOTHER";
        Integer currentScore = userScores.stream()
                .filter(s -> target.getTitle().equals(s.getTitle()) && targetDiffName.equals(s.getDifficultyName()))
                .mapToInt(s -> s.getScore() != null ? s.getScore() : 0)
                .max().stream().boxed().findFirst().orElse(null);

        // ユーザーのスコアを "title\tdiffCode" → Score にマッピング（最高スコアのみ保持）
        Map<String, Score> bestScoreMap = new HashMap<>();
        for (Score s : userScores) {
            if (s.getScore() == null || s.getScore() <= 0) continue;
            String diffCode = diffNameToCode(s.getDifficultyName());
            if (diffCode == null) continue;
            String key = s.getTitle() + "\t" + diffCode;
            bestScoreMap.merge(key, s, (a, b) -> a.getScore() >= b.getScore() ? a : b);
        }

        // 全プロファイルに対して類似度を計算（未プレイ含む）
        List<Map<String, Object>> allSimilarities = new ArrayList<>();
        // プレイ済み＆A以上のみ予測に使用
        List<Map<String, Object>> playedForPrediction = new ArrayList<>();
        boolean targetSongPlayed = false; // 対象曲自身がプレイ済みかどうか

        for (ChartTendencyProfile sp : profileMap.values()) {
            // 対象曲自身もプレイ済みなら類似度100%で含める
            String diff = sp.getDifficulty();
            if (!"4".equals(diff) && !"10".equals(diff)) continue;

            Double irSong = parseRank(informalRankMap.get(sp.getTitle() + "\t" + diff));
            double sim = computeSimilarity(target, irTarget, sp, irSong);
            if (sim <= 0.01) continue;

            String diffName = diff.equals("10") ? "LEGGENDARIA" : "ANOTHER";
            int spNotes = notesMap.getOrDefault(
                    sp.getTitle() + "\t" + diff,
                    sp.getNotes() != null ? sp.getNotes() : 0);

            Map<String, Object> entry = new HashMap<>();
            entry.put("title", sp.getTitle());
            entry.put("difficultyName", diffName);
            entry.put("textage", sp.getTextage() != null ? sp.getTextage() : "");
            entry.put("similarity", Math.round(sim * 1000.0) / 1000.0);
            entry.put("informalRank", irSong);

            // プレイ済みの場合はスコア情報を付与
            String scoreKey = sp.getTitle() + "\t" + diff;
            Score bestScore = bestScoreMap.get(scoreKey);
            if (bestScore != null && spNotes > 0) {
                int maxScore = spNotes * 2;
                double scoreRate = bestScore.getScore() * 100.0 / maxScore;
                entry.put("score", bestScore.getScore());
                entry.put("scoreRate", Math.round(scoreRate * 10.0) / 10.0);
                entry.put("played", true);

                // A以上のプレイ済みスコアのみ予測計算に使用
                if (scoreRate >= 66.67) {
                    playedForPrediction.add(entry);
                    if (sp.getTextage() != null && sp.getTextage().equals(textage)) {
                        targetSongPlayed = true;
                    }
                }
            } else {
                entry.put("played", false);
            }

            allSimilarities.add(entry);
        }

        // 対象曲自身がプレイ済みの場合、予測計算用の類似度を
        // 他の類似譜面の最高類似度に合わせる（自分自身が支配的になるのを防ぐ）
        if (targetSongPlayed) {
            double maxOtherSim = 0;
            for (Map<String, Object> e : playedForPrediction) {
                String t = (String) e.get("textage");
                if (t != null && t.equals(textage)) continue;
                double s = (Double) e.get("similarity");
                if (s > maxOtherSim) maxOtherSim = s;
            }
            for (Map<String, Object> e : playedForPrediction) {
                String t = (String) e.get("textage");
                if (t != null && t.equals(textage)) {
                    e.put("similarityForPrediction", Math.round(maxOtherSim * 1000.0) / 1000.0);
                    break;
                }
            }
        }

        // 類似度降順、上位 20 件（表示用: 未プレイ含む）
        allSimilarities.sort((a, b) -> Double.compare((Double) b.get("similarity"), (Double) a.get("similarity")));
        List<Map<String, Object>> topDisplay = allSimilarities.stream().limit(20).collect(Collectors.toList());

        // 予測計算用: プレイ済みのみ、類似度上位20件
        playedForPrediction.sort((a, b) -> Double.compare((Double) b.get("similarity"), (Double) a.get("similarity")));
        List<Map<String, Object>> topForPrediction = playedForPrediction.stream().limit(20).collect(Collectors.toList());

        if (topForPrediction.isEmpty()) {
            Map<String, Object> noDataResult = new HashMap<>();
            noDataResult.put("textage", textage);
            noDataResult.put("title", orEmpty(target.getTitle()));
            noDataResult.put("notes", targetNotes);
            noDataResult.put("predictedScore", 0);
            noDataResult.put("predictedScoreRate", 0.0);
            noDataResult.put("similarSongs", topDisplay);
            noDataResult.put("message", "類似譜面のスコアデータがありません");
            return noDataResult;
        }

        // 加重50パーセンタイルスコア率（プレイ済みのみで予測）
        // 重み = sim^4 で指数関数的に類似度が高い曲を重視
        // 対象曲自身は similarityForPrediction（他曲の最高類似度）を使用
        final double EXP_POWER = 4.0;
        double weightSum = 0;
        double weightedRank = 0;
        boolean hasRankData = false;
        for (Map<String, Object> e : topForPrediction) {
            double simForCalc = e.containsKey("similarityForPrediction")
                    ? (Double) e.get("similarityForPrediction")
                    : (Double) e.get("similarity");
            double w = Math.pow(simForCalc, EXP_POWER);
            weightSum += w;
            Double refRank = (Double) e.get("informalRank");
            if (refRank != null) {
                weightedRank += w * refRank;
                hasRankData = true;
            }
        }
        // スコア率の昇順にソートして加重累積50%の値を取得
        List<double[]> rateWeightPairs = topForPrediction.stream()
                .map(e -> {
                    double simForCalc = e.containsKey("similarityForPrediction")
                            ? (Double) e.get("similarityForPrediction")
                            : (Double) e.get("similarity");
                    return new double[]{(Double) e.get("scoreRate"), Math.pow(simForCalc, EXP_POWER)};
                })
                .sorted(Comparator.comparingDouble(p -> p[0]))
                .collect(Collectors.toList());
        double targetW50 = 0.50 * weightSum;
        double cumW = 0;
        double predictedRate = rateWeightPairs.get(rateWeightPairs.size() - 1)[0];
        for (double[] rw : rateWeightPairs) {
            cumW += rw[1];
            if (cumW >= targetW50) { predictedRate = rw[0]; break; }
        }

        // ── 難易度差補正（対象曲自身がプレイ済みの場合は不要）────────
        if (!targetSongPlayed && hasRankData && irTarget != null && weightSum > 0) {
            double avgRefRank = weightedRank / weightSum;
            double rankGap = irTarget - avgRefRank;
            if (rankGap > 0) {
                predictedRate -= rankGap * 5.0;
                predictedRate = Math.max(0, predictedRate);
            }
        }

        int predictedScore = (int) Math.round(predictedRate / 100.0 * targetNotes * 2);

        Map<String, Object> result = new HashMap<>();
        result.put("textage", textage);
        result.put("title", orEmpty(target.getTitle()));
        result.put("difficulty", orEmpty(target.getDifficulty()));
        result.put("level", target.getLevel() != null ? target.getLevel() : 0);
        result.put("notes", targetNotes);
        result.put("dominantEff16", target.getDominantEff16() != null ? target.getDominantEff16() : 0);
        result.put("predictedScore", predictedScore);
        result.put("predictedScoreRate", Math.round(predictedRate * 10.0) / 10.0);
        result.put("similarSongs", topDisplay);
        if (currentScore != null) {
            double currentRate = currentScore * 100.0 / (targetNotes * 2);
            result.put("currentScore", currentScore);
            result.put("currentScoreRate", Math.round(currentRate * 10.0) / 10.0);
        }
        return result;
    }

    /**
     * 全プロファイルについてユーザーの予測スコアを一括計算し、BEAT-TIER算出用データを返す。
     * 各エントリに informalRank を付与する（難易度テーブル未収録曲はスキップ）。
     */
    public List<Map<String, Object>> predictAllScores(User user) {
        // notes map + textage map (SongDefinition から一括取得)
        List<SongDefinition> activeSongDefs = songDefRepo.findByRevision("active");
        Map<String, Integer> notesMap = activeSongDefs.stream()
                .filter(sd -> sd.getNotes() != null && sd.getNotes() > 0)
                .collect(Collectors.toMap(
                        sd -> sd.getTitle() + "\t" + sd.getDifficulty(),
                        SongDefinition::getNotes,
                        (a, b) -> a
                ));
        // "title\tdiffCode" -> textage
        Map<String, String> textageMap = activeSongDefs.stream()
                .filter(sd -> sd.getTextage() != null)
                .collect(Collectors.toMap(
                        sd -> sd.getTitle() + "\t" + sd.getDifficulty(),
                        SongDefinition::getTextage,
                        (a, b) -> a
                ));

        // 非公式難易度マップ: "title\tdiffCode" -> rankValue
        Map<String, String> informalRankMap = buildInformalRankMap();

        // all profiles as map
        List<ChartTendencyProfile> allProfiles = profileRepo.findAll();
        Map<String, ChartTendencyProfile> profileMap = allProfiles.stream()
                .collect(Collectors.toMap(
                        p -> p.getTitle() + "\t" + p.getDifficulty(),
                        p -> p,
                        (a, b) -> a
                ));

        // user scores
        List<Score> userScores = scoreRepo.findByUserOrderByUploadedAtAsc(user);

        // actual score map: "title\tdiffCode" -> actual score rate (最高スコアのみ保持)
        Map<String, Double> actualRateMap = new HashMap<>();
        for (Score s : userScores) {
            if (s.getScore() == null || s.getScore() <= 0) continue;
            String diffCode = diffNameToCode(s.getDifficultyName());
            if (diffCode == null) continue;
            int spNotes = notesMap.getOrDefault(s.getTitle() + "\t" + diffCode, 0);
            if (spNotes == 0) continue;
            double rate = s.getScore() * 100.0 / (spNotes * 2);
            String key = s.getTitle() + "\t" + diffCode;
            actualRateMap.merge(key, rate, (a, b) -> a > b ? a : b);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> coveredKeys = new HashSet<>();

        for (ChartTendencyProfile target : allProfiles) {
            String diff = target.getDifficulty();
            if (!"4".equals(diff) && !"10".equals(diff)) continue;

            String informalRank = informalRankMap.get(target.getTitle() + "\t" + diff);
            if (informalRank == null) continue;
            Double irTarget = parseRank(informalRank);

            int targetNotes = notesMap.getOrDefault(
                    target.getTitle() + "\t" + diff,
                    target.getNotes() != null ? target.getNotes() : 0);
            if (targetNotes == 0) continue;

            // compute similarities against all user scores
            List<double[]> simRatePairs = new ArrayList<>();
            for (Score s : userScores) {
                if (s.getScore() == null || s.getScore() <= 0) continue;
                String diffCode = diffNameToCode(s.getDifficultyName());
                if (diffCode == null) continue;

                ChartTendencyProfile sp = profileMap.get(s.getTitle() + "\t" + diffCode);
                if (sp == null) continue;

                Double irSong = parseRank(informalRankMap.get(s.getTitle() + "\t" + diffCode));
                double sim = computeSimilarity(target, irTarget, sp, irSong);
                if (sim <= 0) continue;

                int spNotes = notesMap.getOrDefault(
                        s.getTitle() + "\t" + diffCode,
                        sp.getNotes() != null ? sp.getNotes() : 0);
                if (spNotes == 0) continue;
                double scoreRate = s.getScore() * 100.0 / (spNotes * 2);
                // A未満（66.67%未満）は捨てスコアとして除外
                if (scoreRate < 66.67) continue;
                // [sim, scoreRate, informalRank(なければNaN)]
                simRatePairs.add(new double[]{sim, scoreRate, irSong != null ? irSong : Double.NaN});
            }

            if (simRatePairs.isEmpty()) continue;

            simRatePairs.sort((a, b) -> Double.compare(b[0], a[0]));
            // 重み = sim^4 で指数関数的に類似度が高い曲を重視
            final double EXP_POW = 4.0;
            double weightSum = 0, weightedRank = 0;
            boolean hasRank = false;
            int limit = Math.min(20, simRatePairs.size());
            for (int i = 0; i < limit; i++) {
                double w = Math.pow(simRatePairs.get(i)[0], EXP_POW);
                double rk = simRatePairs.get(i)[2];
                weightSum += w;
                if (!Double.isNaN(rk)) {
                    weightedRank += w * rk;
                    hasRank = true;
                }
            }
            // 加重50パーセンタイルスコア率（中央値ベース → 伸びしろを残す）
            List<double[]> sorted50 = simRatePairs.subList(0, limit).stream()
                    .map(p -> new double[]{p[1], Math.pow(p[0], EXP_POW)}) // [scoreRate, weight]
                    .sorted(Comparator.comparingDouble(p -> p[0]))
                    .collect(Collectors.toList());
            double targetW50 = 0.50 * weightSum;
            double cumW50 = 0;
            double predictedRate = sorted50.get(sorted50.size() - 1)[0];
            for (double[] rw : sorted50) {
                cumW50 += rw[1];
                if (cumW50 >= targetW50) { predictedRate = rw[0]; break; }
            }

            // 難易度差補正
            if (hasRank && irTarget != null && weightSum > 0) {
                double avgRefRank = weightedRank / weightSum;
                double rankGap = irTarget - avgRefRank;
                if (rankGap > 0) {
                    predictedRate -= rankGap * 5.0;
                    predictedRate = Math.max(0, predictedRate);
                }
            }

            // 実績スコア（表示用に保持するが、予測の下限にはしない → 伸びしろを残す）
            Double actualRate = actualRateMap.get(target.getTitle() + "\t" + diff);

            int predictedScore = (int) Math.round(predictedRate / 100.0 * targetNotes * 2);

            Map<String, Object> entry = new HashMap<>();
            entry.put("textage", target.getTextage());
            entry.put("title", orEmpty(target.getTitle()));
            entry.put("difficulty", diff);
            entry.put("level", target.getLevel() != null ? target.getLevel() : 0);
            entry.put("informalRank", informalRank);
            entry.put("notes", targetNotes);
            entry.put("predictedScore", predictedScore);
            entry.put("predictedScoreRate", Math.round(predictedRate * 10.0) / 10.0);
            coveredKeys.add(target.getTitle() + "\t" + diff);
            results.add(entry);
        }

        // プロファイル未収録だが実績スコアがある曲を補完
        // （難易度テーブル収録済みのもののみBEAT-TIER対象）
        for (Map.Entry<String, Double> e : actualRateMap.entrySet()) {
            String key = e.getKey();
            if (coveredKeys.contains(key)) continue;
            String informalRank = informalRankMap.get(key);
            if (informalRank == null) continue;
            int notes = notesMap.getOrDefault(key, 0);
            if (notes == 0) continue;

            double actualRate = e.getValue();
            int predictedScore = (int) Math.round(actualRate / 100.0 * notes * 2);

            String[] parts = key.split("\t", 2);
            String title = parts[0];
            String diff = parts.length > 1 ? parts[1] : "";

            Map<String, Object> entry = new HashMap<>();
            entry.put("textage", textageMap.getOrDefault(key, ""));
            entry.put("title", title);
            entry.put("difficulty", diff);
            entry.put("level", 0);
            entry.put("informalRank", informalRank);
            entry.put("notes", notes);
            entry.put("predictedScore", predictedScore);
            entry.put("predictedScoreRate", Math.round(actualRate * 10.0) / 10.0);
            results.add(entry);
        }

        return results;
    }

    /**
     * 2つのプロファイルの類似度 (0〜1) を計算する。
     * 4グループの類似度を個別に求めて乗算することで、
     * どれか1グループが大きく外れると全体が低下する設計。
     *
     * @param irA 対象譜面の非公式難易度値、未収録の場合 null
     * @param irB 比較譜面の非公式難易度値、未収録の場合 null
     */
    private double computeSimilarity(ChartTendencyProfile a, Double irA,
                                     ChartTendencyProfile b, Double irB) {
        // 同一譜面は必ず 1.0
        if (a.getTextage() != null && a.getTextage().equals(b.getTextage())) return 1.0;

        if (a.getDominantEff16() == null || b.getDominantEff16() == null) return 0;
        if (a.getScratchPct()    == null || b.getScratchPct()    == null) return 0;
        if (a.getChordPct()      == null || b.getChordPct()      == null) return 0;

        final double s2 = 2 * 0.15 * 0.15;

        // ── Group1: 密度 ──────────────────────────────────────────────
        // nps: (notes/events) × (weightedEff16/15) ≈ 秒間ノーツ密度
        double npsA = (a.getNotes() != null && a.getEvents() != null && a.getEvents() > 0
                       && a.getWeightedEff16() != null)
                ? (a.getNotes().doubleValue() / a.getEvents()) * (a.getWeightedEff16() / 15.0) : 0;
        double npsB = (b.getNotes() != null && b.getEvents() != null && b.getEvents() > 0
                       && b.getWeightedEff16() != null)
                ? (b.getNotes().doubleValue() / b.getEvents()) * (b.getWeightedEff16() / 15.0) : 0;
        double dNps    = (npsA > 0 && npsB > 0) ? (npsA - npsB) / 10.0 : 0;
        double dEff16  = (a.getDominantEff16() - b.getDominantEff16()) / 200.0;
        double dWEff16 = (a.getWeightedEff16() != null && b.getWeightedEff16() != null)
                ? (a.getWeightedEff16() - b.getWeightedEff16()) / 200.0 : 0;
        // ── 非公式難易度の類似度（独立グループ） ───────────────────────
        // dRank は密度グループから分離し、独立した乗算要因として扱う。
        // 難易度差 0.5 → ~0.76, 1.0 → ~0.33, 1.5 → ~0.11, 2.0 → ~0.02
        double rankSim = 1.0;
        if (irA != null && irB != null) {
            double dRank = (irA - irB) / 1.0; // 正規化: 1.0ランク差で大きく減衰
            rankSim = Math.exp(-3.0 * dRank * dRank / s2);
        }

        double densityDist2 = 2.5*dNps*dNps + 2.0*dEff16*dEff16 + 1.5*dWEff16*dWEff16;
        double densitySim   = Math.exp(-densityDist2 / s2);

        // ── Group2: スクラッチ ────────────────────────────────────────
        // scratchPctの差（存在量）+ scrIntervalDistのコサイン類似度（リズムパターン）
        double dScratch     = (a.getScratchPct() - b.getScratchPct()) / 100.0;
        double scrScalar    = Math.exp(-1.5 * dScratch * dScratch / s2);
        double scrIntervalSim = computeScrIntervalCosineSim(
                a.getScrIntervalDistJson(), b.getScrIntervalDistJson(),
                a.getScratchPct(), b.getScratchPct());
        double scratchSim   = 0.5 * scrScalar + 0.5 * scrIntervalSim;

        // ── Group3: 鍵盤パターン ──────────────────────────────────────
        // chordPctの差（同時押し割合）+ kbdIntervalDist（スクラッチ除外）のコサイン類似度
        double dChord       = (a.getChordPct() - b.getChordPct()) / 100.0;
        double chordScalar  = Math.exp(-1.0 * dChord * dChord / s2);
        double intervalSim  = computeIntervalCosineSim(a.getKbdIntervalDistJson(), b.getKbdIntervalDistJson());
        double patternSim   = 0.35 * chordScalar + 0.65 * intervalSim;

        // ── Group4: CN ────────────────────────────────────────────────
        // CN率・CNスクラッチ率・CN保持中の他鍵盤割合の差 + cnIntervalDistのコサイン類似度
        double cnRatioA = (a.getCnNotes() != null && a.getNotes() != null && a.getNotes() > 0)
                ? a.getCnNotes().doubleValue() / a.getNotes() : -1;
        double cnRatioB = (b.getCnNotes() != null && b.getNotes() != null && b.getNotes() > 0)
                ? b.getCnNotes().doubleValue() / b.getNotes() : -1;
        double cnSim;
        boolean cnOneSided = (cnRatioA >= 0) != (cnRatioB >= 0);
        if (cnOneSided) {
            // 片方だけCNがある場合: CN率が高いほど類似度を大きく下げる
            // cnRatio=0.01(1%) → 0.74, 0.03(3%) → 0.07, 0.05(5%) → 0.00, 0.07(7%) → 0.00
            double cnRatioPresent = Math.max(cnRatioA, cnRatioB);
            cnSim = Math.exp(-3000.0 * cnRatioPresent * cnRatioPresent);
        } else {
            double dCnRatio   = (cnRatioA >= 0 && cnRatioB >= 0) ? (cnRatioA - cnRatioB) / 0.25 : 0;
            double dCnScratch = (a.getCnScratchPct() != null && b.getCnScratchPct() != null)
                    ? (a.getCnScratchPct() - b.getCnScratchPct()) / 100.0 : 0;
            // cn_kbd_overlap_pct: CN保持中に他の鍵盤が降ってくる割合 — CNの「難しさ」を最もよく表す
            double dCnKbdOverlap = (a.getCnKbdOverlapPct() != null && b.getCnKbdOverlapPct() != null)
                    ? (a.getCnKbdOverlapPct() - b.getCnKbdOverlapPct()) / 100.0 : 0;
            // dCnRatio重み1.5: CN量の差を強く反映（5%差→軽微, 10%差→中程度, 15%差→大きなペナルティ）
            double cnScalar      = Math.exp(-(1.5*dCnRatio*dCnRatio + 0.2*dCnScratch*dCnScratch + 1.2*dCnKbdOverlap*dCnKbdOverlap) / s2);
            double cnIntervalSim = computeCnIntervalCosineSim(a.getCnIntervalDistJson(), b.getCnIntervalDistJson());
            cnSim = 0.6 * cnScalar + 0.4 * cnIntervalSim;
        }

        // ── 比重: 各グループのノーツ割合に応じてべき乗で重み付け ──────
        double scrW = Math.min(1.0, Math.max(a.getScratchPct(), b.getScratchPct()) / 33.0);
        double kbdW = 1.0 - ((a.getScratchPct() + b.getScratchPct()) / 2.0) / 100.0;
        // cnW: CN量の大きい方を基準に重みを決定（overlap + cnRatio両方考慮）
        // CN量に差がある場合もペナルティが確実に効くよう max ベースで計算
        double overlapA = (a.getCnKbdOverlapPct() != null) ? a.getCnKbdOverlapPct() / 100.0 : 0;
        double overlapB = (b.getCnKbdOverlapPct() != null) ? b.getCnKbdOverlapPct() / 100.0 : 0;
        double maxCnRatio = Math.max(cnRatioA >= 0 ? cnRatioA : 0, cnRatioB >= 0 ? cnRatioB : 0);
        double cnW;
        if (cnOneSided) {
            // 片方だけCNがある場合: 常にフルウェイト（CNの有無は決定的な差異）
            cnW = 1.0;
        } else {
            // 両方CNあり: overlap平均×3 と maxCnRatio×3 の大きい方を採用
            double overlapBased = (overlapA + overlapB) / 2.0 * 3.0;
            double ratioBased   = maxCnRatio * 3.0;
            cnW = Math.min(1.0, Math.max(overlapBased, ratioBased));
        }

        // ── 統合: 密度・難易度は常にフル、他は割合ベきで乗算 ──────────
        double combined = densitySim
                * rankSim
                * Math.pow(scratchSim, scrW)
                * Math.pow(patternSim, kbdW)
                * Math.pow(cnSim, cnW);

        return Math.min(1.0, combined);
    }

    /**
     * 2曲間の類似度計算過程を詳細に返す（デバッグ用）。
     */
    public Map<String, Object> computeSimilarityDebug(String textageA, String textageB) {
        ChartTendencyProfile a = profileRepo.findById(textageA).orElse(null);
        ChartTendencyProfile b = profileRepo.findById(textageB).orElse(null);
        if (a == null || b == null) return Map.of("error", (Object)"プロファイルが見つかりません");

        Map<String, String> informalRankMap = buildInformalRankMap();
        Double irA = parseRank(informalRankMap.get(a.getTitle() + "\t" + a.getDifficulty()));
        Double irB = parseRank(informalRankMap.get(b.getTitle() + "\t" + b.getDifficulty()));

        final double s2 = 2 * 0.15 * 0.15;

        // Group1: 密度
        double npsA = (a.getNotes() != null && a.getEvents() != null && a.getEvents() > 0 && a.getWeightedEff16() != null)
                ? (a.getNotes().doubleValue() / a.getEvents()) * (a.getWeightedEff16() / 15.0) : 0;
        double npsB = (b.getNotes() != null && b.getEvents() != null && b.getEvents() > 0 && b.getWeightedEff16() != null)
                ? (b.getNotes().doubleValue() / b.getEvents()) * (b.getWeightedEff16() / 15.0) : 0;
        double dNps    = (npsA > 0 && npsB > 0) ? (npsA - npsB) / 10.0 : 0;
        double dEff16  = (a.getDominantEff16() != null && b.getDominantEff16() != null) ? (a.getDominantEff16() - b.getDominantEff16()) / 200.0 : 0;
        double dWEff16 = (a.getWeightedEff16() != null && b.getWeightedEff16() != null) ? (a.getWeightedEff16() - b.getWeightedEff16()) / 200.0 : 0;
        // 非公式難易度の類似度（独立グループ）
        double rankSim_d = 1.0;
        double dRank = 0;
        if (irA != null && irB != null) {
            dRank = (irA - irB) / 1.0;
            rankSim_d = Math.exp(-3.0 * dRank * dRank / s2);
        }
        double densityDist2 = 2.5*dNps*dNps + 2.0*dEff16*dEff16 + 1.5*dWEff16*dWEff16;
        double densitySim   = Math.exp(-densityDist2 / s2);

        // Group2: スクラッチ
        double dScratch      = (a.getScratchPct() != null && b.getScratchPct() != null) ? (a.getScratchPct() - b.getScratchPct()) / 100.0 : 0;
        double scrScalar     = Math.exp(-1.5 * dScratch * dScratch / s2);
        double scrIntervalSim = computeScrIntervalCosineSim(a.getScrIntervalDistJson(), b.getScrIntervalDistJson(), a.getScratchPct(), b.getScratchPct());
        double scratchSim    = 0.5 * scrScalar + 0.5 * scrIntervalSim;

        // Group3: 鍵盤パターン
        double dChord      = (a.getChordPct() != null && b.getChordPct() != null) ? (a.getChordPct() - b.getChordPct()) / 100.0 : 0;
        double chordScalar = Math.exp(-1.0 * dChord * dChord / s2);
        double intervalSim = computeIntervalCosineSim(a.getIntervalDistJson(), b.getIntervalDistJson());
        double patternSim  = 0.35 * chordScalar + 0.65 * intervalSim;

        // Group4: CN
        double cnRatioA = (a.getCnNotes() != null && a.getNotes() != null && a.getNotes() > 0) ? a.getCnNotes().doubleValue() / a.getNotes() : -1;
        double cnRatioB = (b.getCnNotes() != null && b.getNotes() != null && b.getNotes() > 0) ? b.getCnNotes().doubleValue() / b.getNotes() : -1;
        double cnSim;
        double cnScalar = 0, cnIntervalSim = 0;
        double dCnRatio = 0, dCnScratch = 0;
        boolean cnOneSided_d = (cnRatioA >= 0) != (cnRatioB >= 0);
        if (cnOneSided_d) {
            double cnRatioPresent = Math.max(cnRatioA, cnRatioB);
            cnSim = Math.exp(-3000.0 * cnRatioPresent * cnRatioPresent);
        } else {
            dCnRatio   = (cnRatioA >= 0 && cnRatioB >= 0) ? (cnRatioA - cnRatioB) / 0.25 : 0;
            dCnScratch = (a.getCnScratchPct() != null && b.getCnScratchPct() != null) ? (a.getCnScratchPct() - b.getCnScratchPct()) / 100.0 : 0;
            cnScalar      = Math.exp(-(1.5*dCnRatio*dCnRatio + 0.2*dCnScratch*dCnScratch) / s2);
            cnIntervalSim = computeCnIntervalCosineSim(a.getCnIntervalDistJson(), b.getCnIntervalDistJson());
            cnSim         = 0.6 * cnScalar + 0.4 * cnIntervalSim;
        }

        // 比重
        double scrW = (a.getScratchPct() != null && b.getScratchPct() != null) ? Math.min(1.0, Math.max(a.getScratchPct(), b.getScratchPct()) / 33.0) : 0;
        double kbdW = (a.getScratchPct() != null && b.getScratchPct() != null) ? 1.0 - ((a.getScratchPct() + b.getScratchPct()) / 2.0) / 100.0 : 1.0;
        double overlapA_d = (a.getCnKbdOverlapPct() != null) ? a.getCnKbdOverlapPct() / 100.0 : 0;
        double overlapB_d = (b.getCnKbdOverlapPct() != null) ? b.getCnKbdOverlapPct() / 100.0 : 0;
        double maxCnRatio_d = Math.max(cnRatioA >= 0 ? cnRatioA : 0, cnRatioB >= 0 ? cnRatioB : 0);
        double cnW;
        if (cnOneSided_d) {
            cnW = 1.0;
        } else {
            double overlapBased = (overlapA_d + overlapB_d) / 2.0 * 3.0;
            double ratioBased   = maxCnRatio_d * 3.0;
            cnW = Math.min(1.0, Math.max(overlapBased, ratioBased));
        }

        double combined = densitySim * rankSim_d * Math.pow(scratchSim, scrW) * Math.pow(patternSim, kbdW) * Math.pow(cnSim, cnW);
        double finalSim = Math.min(1.0, combined);

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("songA", Map.of("title", orEmpty(a.getTitle()), "difficulty", orEmpty(a.getDifficulty()), "informalRank", irA != null ? irA : "N/A"));
        debug.put("songB", Map.of("title", orEmpty(b.getTitle()), "difficulty", orEmpty(b.getDifficulty()), "informalRank", irB != null ? irB : "N/A"));

        Map<String, Object> rawA = new LinkedHashMap<>();
        rawA.put("nps", Math.round(npsA * 100.0) / 100.0);
        rawA.put("dominantEff16", a.getDominantEff16());
        rawA.put("weightedEff16", a.getWeightedEff16());
        rawA.put("scratchPct", a.getScratchPct());
        rawA.put("chordPct", a.getChordPct());
        rawA.put("cnRatio", cnRatioA >= 0 ? Math.round(cnRatioA * 1000.0) / 10.0 + "%" : "N/A");
        debug.put("rawA", rawA);

        Map<String, Object> rawB = new LinkedHashMap<>();
        rawB.put("nps", Math.round(npsB * 100.0) / 100.0);
        rawB.put("dominantEff16", b.getDominantEff16());
        rawB.put("weightedEff16", b.getWeightedEff16());
        rawB.put("scratchPct", b.getScratchPct());
        rawB.put("chordPct", b.getChordPct());
        rawB.put("cnRatio", cnRatioB >= 0 ? Math.round(cnRatioB * 1000.0) / 10.0 + "%" : "N/A");
        debug.put("rawB", rawB);

        Map<String, Object> g1 = new LinkedHashMap<>();
        g1.put("dNps_norm", Math.round(dNps * 1000.0) / 1000.0);
        g1.put("dEff16_norm", Math.round(dEff16 * 1000.0) / 1000.0);
        g1.put("dWEff16_norm", Math.round(dWEff16 * 1000.0) / 1000.0);
        g1.put("dist2", Math.round(densityDist2 * 10000.0) / 10000.0);
        g1.put("densitySim", Math.round(densitySim * 10000.0) / 10000.0);
        debug.put("group1_density", g1);

        Map<String, Object> gRank = new LinkedHashMap<>();
        gRank.put("dRank_norm", Math.round(dRank * 1000.0) / 1000.0);
        gRank.put("rankSim", Math.round(rankSim_d * 10000.0) / 10000.0);
        debug.put("group_rank", gRank);

        Map<String, Object> g2 = new LinkedHashMap<>();
        g2.put("dScratchPct_norm", Math.round(dScratch * 1000.0) / 1000.0);
        g2.put("scrScalar", Math.round(scrScalar * 10000.0) / 10000.0);
        g2.put("scrIntervalCosineSim", Math.round(scrIntervalSim * 10000.0) / 10000.0);
        g2.put("scratchSim", Math.round(scratchSim * 10000.0) / 10000.0);
        g2.put("weight(scrW)", Math.round(scrW * 1000.0) / 1000.0);
        g2.put("contribution", Math.round(Math.pow(scratchSim, scrW) * 10000.0) / 10000.0);
        debug.put("group2_scratch", g2);

        Map<String, Object> g3 = new LinkedHashMap<>();
        g3.put("dChordPct_norm", Math.round(dChord * 1000.0) / 1000.0);
        g3.put("chordScalar", Math.round(chordScalar * 10000.0) / 10000.0);
        g3.put("kbdIntervalCosineSim", Math.round(intervalSim * 10000.0) / 10000.0);
        g3.put("patternSim", Math.round(patternSim * 10000.0) / 10000.0);
        g3.put("weight(kbdW)", Math.round(kbdW * 1000.0) / 1000.0);
        g3.put("contribution", Math.round(Math.pow(patternSim, kbdW) * 10000.0) / 10000.0);
        debug.put("group3_pattern", g3);

        Map<String, Object> g4 = new LinkedHashMap<>();
        g4.put("cnOneSided", cnOneSided_d);
        g4.put("cnRatioA", cnRatioA >= 0 ? Math.round(cnRatioA * 10000.0) / 10000.0 : "N/A");
        g4.put("cnRatioB", cnRatioB >= 0 ? Math.round(cnRatioB * 10000.0) / 10000.0 : "N/A");
        g4.put("cnKbdOverlapPctA", a.getCnKbdOverlapPct());
        g4.put("cnKbdOverlapPctB", b.getCnKbdOverlapPct());
        g4.put("dCnRatio_norm", Math.round(dCnRatio * 1000.0) / 1000.0);
        g4.put("dCnScratch_norm", Math.round(dCnScratch * 1000.0) / 1000.0);
        g4.put("cnScalar", Math.round(cnScalar * 10000.0) / 10000.0);
        g4.put("cnIntervalCosineSim", Math.round(cnIntervalSim * 10000.0) / 10000.0);
        g4.put("cnSim", Math.round(cnSim * 10000.0) / 10000.0);
        g4.put("weight(cnW)", Math.round(cnW * 1000.0) / 1000.0);
        g4.put("contribution", Math.round(Math.pow(cnSim, cnW) * 10000.0) / 10000.0);
        debug.put("group4_cn", g4);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("combined", Math.round(combined * 10000.0) / 10000.0);
        result.put("finalSimilarity", Math.round(finalSim * 10000.0) / 10000.0);
        result.put("finalSimilarityPct", Math.round(finalSim * 10000.0) / 100.0 + "%");
        debug.put("result", result);

        return debug;
    }

    /** 非公式難易度マップを構築: "title\tdiffCode" → rankValue */
    private Map<String, String> buildInformalRankMap() {
        Map<String, String> map = new HashMap<>();
        for (DifficultyRank rank : difficultyRankRepo.findByRevisionOrderBySortOrderAsc("active")) {
            for (DifficultyRankSong song : rank.getSongs()) {
                String t = song.getSongTitle();
                if (t.endsWith(" [L]")) {
                    map.put(t.substring(0, t.length() - 4) + "\t10", rank.getRankValue());
                } else {
                    map.put(t + "\t4", rank.getRankValue());
                }
            }
        }
        return map;
    }

    /** "11.5" などの文字列を Double に変換。パース失敗時は null */
    private Double parseRank(String rankStr) {
        if (rankStr == null) return null;
        try { return Double.parseDouble(rankStr); }
        catch (NumberFormatException e) { return null; }
    }

    /**
     * interval_dist JSON 同士のコサイン類似度を返す (0〜1)。
     * 各インターバルキーの pct 値を疎ベクトルとして扱う。
     */
    private double computeIntervalCosineSim(String jsonA, String jsonB) {
        if (jsonA == null || jsonB == null) return 0;
        try {
            Map<String, Double> vecA = parseIntervalPctVector(jsonA);
            Map<String, Double> vecB = parseIntervalPctVector(jsonB);
            if (vecA.isEmpty() || vecB.isEmpty()) return 0;

            // dot product（共通キーのみ）
            double dot = 0;
            for (Map.Entry<String, Double> e : vecA.entrySet()) {
                Double bVal = vecB.get(e.getKey());
                if (bVal != null) dot += e.getValue() * bVal;
            }

            // ノルム
            double normA = Math.sqrt(vecA.values().stream().mapToDouble(v -> v * v).sum());
            double normB = Math.sqrt(vecB.values().stream().mapToDouble(v -> v * v).sum());

            if (normA == 0 || normB == 0) return 0;
            return dot / (normA * normB);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * interval_dist JSON → Map<intervalKey, pct>
     * eff16 ≤ 50 のエントリ（極端に間隔が長い音符）は類似度計算から除外する。
     */
    private Map<String, Double> parseIntervalPctVector(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        Map<String, Double> vec = new HashMap<>();
        root.fields().forEachRemaining(e -> {
            JsonNode entry = e.getValue();
            JsonNode eff16Node = entry.get("eff16");
            if (eff16Node != null && eff16Node.asDouble() <= 50.0) return; // eff16 ≤ 50 は除外
            JsonNode pctNode = entry.get("pct");
            if (pctNode != null) vec.put(e.getKey(), pctNode.asDouble());
        });
        return vec;
    }

    /**
     * scr_interval_dist JSON 同士のコサイン類似度を返す (0〜1)。
     * 両方ともスクラッチが少ない曲（scratchPct < 3）の場合は中立値 1.0。
     * どちらか一方のみデータがない場合は中立値 0.5。
     */
    private double computeScrIntervalCosineSim(String jsonA, String jsonB,
                                               Double scrPctA, Double scrPctB) {
        double pA = scrPctA != null ? scrPctA : 0.0;
        double pB = scrPctB != null ? scrPctB : 0.0;
        boolean lowA  = pA < 10.0;
        boolean lowB  = pB < 10.0;
        boolean highA = pA >= 15.0;
        boolean highB = pB >= 15.0;

        if (lowA && lowB) return 1.0;   // 両方スクラッチ少ない → 差なし
        // 片方が低（<10%）、もう片方が高（≥15%）→ 明らかに異なる
        if ((lowA && highB) || (lowB && highA)) return 0.2;
        if (jsonA == null || jsonB == null) return 0.5; // 片方未取得 → 中立
        try {
            Map<String, Double> vecA = parseIntervalPctVector(jsonA);
            Map<String, Double> vecB = parseIntervalPctVector(jsonB);
            if (vecA.isEmpty() && vecB.isEmpty()) return 1.0;
            if (vecA.isEmpty() || vecB.isEmpty()) return 0.5;
            double dot = 0;
            for (Map.Entry<String, Double> e : vecA.entrySet()) {
                Double bVal = vecB.get(e.getKey());
                if (bVal != null) dot += e.getValue() * bVal;
            }
            double normA = Math.sqrt(vecA.values().stream().mapToDouble(v -> v * v).sum());
            double normB = Math.sqrt(vecB.values().stream().mapToDouble(v -> v * v).sum());
            if (normA == 0 || normB == 0) return 0.5;
            return dot / (normA * normB);
        } catch (Exception e) {
            return 0.5;
        }
    }

    /**
     * cn_interval_dist JSON 同士のコサイン類似度を返す (0〜1)。
     * どちらかが null（CNデータ未取得）の場合は中立値 0.5 を返す。
     */
    private double computeCnIntervalCosineSim(String jsonA, String jsonB) {
        if (jsonA == null || jsonB == null) return 0.5; // データ未取得時は中立
        try {
            Map<String, Double> vecA = parseIntervalPctVector(jsonA);
            Map<String, Double> vecB = parseIntervalPctVector(jsonB);
            if (vecA.isEmpty() || vecB.isEmpty()) return 0.5; // CN無し曲同士は中立

            double dot = 0;
            for (Map.Entry<String, Double> e : vecA.entrySet()) {
                Double bVal = vecB.get(e.getKey());
                if (bVal != null) dot += e.getValue() * bVal;
            }
            double normA = Math.sqrt(vecA.values().stream().mapToDouble(v -> v * v).sum());
            double normB = Math.sqrt(vecB.values().stream().mapToDouble(v -> v * v).sum());
            if (normA == 0 || normB == 0) return 0.5;
            return dot / (normA * normB);
        } catch (Exception e) {
            return 0.5;
        }
    }


    private String diffNameToCode(String difficultyName) {
        if ("ANOTHER".equalsIgnoreCase(difficultyName)) return "4";
        if ("LEGGENDARIA".equalsIgnoreCase(difficultyName)) return "10";
        return null;
    }

    private String orEmpty(String s) {
        return s != null ? s : "";
    }
}
