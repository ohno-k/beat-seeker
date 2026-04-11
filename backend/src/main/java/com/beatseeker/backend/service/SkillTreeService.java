package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.ChartTendencyProfile;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ChartTendencyProfileRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * スキルツリー生成サービス — 全譜面対象・カテゴリなし貪欲法チェーン構築。
 *
 * 全譜面を対象に類似度ベースで数珠つなぎチェーンを構築する。
 * 各チェーンは到達先（末端）の最高難度譜面名で識別される。
 * 類似譜面が見つからない譜面は独立ノードとして保持する。
 */
@Service
public class SkillTreeService {

    private final ChartTendencyProfileRepository profileRepo;
    private final ScoreRepository scoreRepo;

    /** チェーンを延長する最低類似度 */
    private static final double SIMILARITY_THRESHOLD = 0.20;

    private static final Map<String, Integer> CLEAR_RANK = Map.of(
            "FAILED", 0, "ASSIST CLEAR", 1, "EASY CLEAR", 2,
            "CLEAR", 3, "HARD CLEAR", 4, "EX HARD CLEAR", 5, "FULLCOMBO CLEAR", 6
    );

    public SkillTreeService(ChartTendencyProfileRepository profileRepo,
                            ScoreRepository scoreRepo) {
        this.profileRepo = profileRepo;
        this.scoreRepo = scoreRepo;
    }

    /**
     * スキルツリーを生成。
     * 全譜面をEDS順にソートし、貪欲法で類似チェーンを構築する。
     * チェーン名はチェーン末端（最高難度）の譜面名。
     */
    public Map<String, Object> generateSkillTree(User user) {
        // 全譜面対象（レベルフィルタなし）
        List<ChartTendencyProfile> allProfiles = profileRepo.findAll().stream()
                .filter(p -> p.getNotes() != null && p.getNotes() > 0)
                .collect(Collectors.toList());

        // EDS計算
        Map<String, Double> edsMap = new HashMap<>();
        for (ChartTendencyProfile p : allProfiles) {
            edsMap.put(p.getTextage(), computeEDS(p));
        }

        // EDS昇順ソート
        List<ChartTendencyProfile> sorted = allProfiles.stream()
                .sorted(Comparator.comparingDouble(p -> edsMap.get(p.getTextage())))
                .collect(Collectors.toList());

        // 貪欲法で複数チェーン構築
        Set<String> used = new HashSet<>();
        List<List<ChainLink>> rawChains = new ArrayList<>();

        for (ChartTendencyProfile start : sorted) {
            if (used.contains(start.getTextage())) continue;

            List<ChainLink> chain = new ArrayList<>();
            chain.add(new ChainLink(start, 0.0, ""));
            used.add(start.getTextage());

            ChartTendencyProfile current = start;
            while (true) {
                double currentEds = edsMap.get(current.getTextage());
                ChartTendencyProfile bestNext = null;
                double bestScore = -1;
                double bestSimilarity = 0;

                for (ChartTendencyProfile candidate : sorted) {
                    if (used.contains(candidate.getTextage())) continue;
                    double candidateEds = edsMap.get(candidate.getTextage());
                    if (candidateEds <= currentEds) continue;

                    double similarity = computeQuickSimilarity(current, candidate);
                    double edsDiff = candidateEds - currentEds;
                    double proximityBonus = Math.exp(-0.3 * edsDiff);
                    double score = similarity * 0.7 + proximityBonus * 0.3;

                    if (score > bestScore) {
                        bestScore = score;
                        bestNext = candidate;
                        bestSimilarity = similarity;
                    }
                }

                if (bestNext == null || bestSimilarity < SIMILARITY_THRESHOLD) break;

                String reason = buildConnectionReason(current, bestNext);
                chain.add(new ChainLink(bestNext, bestSimilarity, reason));
                used.add(bestNext.getTextage());
                current = bestNext;
            }

            rawChains.add(chain);
        }

        // チェーン長降順でソート（長いチェーンが先）
        rawChains.sort((a, b) -> Integer.compare(b.size(), a.size()));

        // レスポンス構築
        List<Map<String, Object>> chains = new ArrayList<>();
        for (List<ChainLink> chain : rawChains) {
            ChainLink top = chain.get(chain.size() - 1);
            ChartTendencyProfile topP = top.profile;

            Map<String, Object> cd = new LinkedHashMap<>();
            cd.put("categoryId", topP.getTextage());
            cd.put("categoryLabel", topP.getTitle());
            cd.put("categoryDescription",
                    diffShort(topP.getDifficulty())
                    + (topP.getLevel() != null && topP.getLevel() > 0 ? topP.getLevel() : "?"));
            cd.put("totalInCategory", chain.size());
            cd.put("isIndependent", chain.size() == 1);

            List<Map<String, Object>> nodes = new ArrayList<>();
            for (ChainLink link : chain) {
                Map<String, Object> node = profileToNode(link.profile);
                node.put("similarityToPrev", Math.round(link.similarityToPrev * 100.0) / 100.0);
                node.put("connectionReason", link.connectionReason);
                nodes.add(node);
            }
            cd.put("nodes", nodes);
            chains.add(cd);
        }

        // ユーザー進捗
        Map<String, Object> userProgress = new LinkedHashMap<>();
        if (user != null) {
            userProgress = buildUserProgress(
                    scoreRepo.findByUserOrderByUploadedAtDesc(user), allProfiles);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chains", chains);
        result.put("userProgress", userProgress);
        return result;
    }

    // ── EDS（実効難易度スコア）──────────────────────────────

    /**
     * 実効難易度スコア。
     * レベルを基盤に、密度・ノーツ数で小数点以下を付与。
     * レベル未設定の場合は難易度コードからの推定値を使用。
     */
    private double computeEDS(ChartTendencyProfile p) {
        double level;
        if (p.getLevel() != null && p.getLevel() > 0) {
            level = p.getLevel();
        } else {
            level = estimateLevelFromDifficulty(p.getDifficulty());
        }

        double eff = safe(p.getDominantEff16());
        double densityComponent = Math.min(eff / 300.0, 1.0) * 0.4;

        int notes = p.getNotes() != null ? p.getNotes() : 0;
        double notesComponent = Math.min(notes / 2000.0, 1.0) * 0.3;

        return level + densityComponent + notesComponent;
    }

    /** レベル未設定時の推定値 */
    private double estimateLevelFromDifficulty(String dc) {
        if (dc == null) return 5;
        return switch (dc) {
            case "1" -> 3;   // BEGINNER
            case "2" -> 5;   // NORMAL
            case "3" -> 8;   // HYPER
            case "4" -> 10;  // ANOTHER
            case "10" -> 12; // LEGGENDARIA
            default -> 5;
        };
    }

    // ── 類似度 ──────────────────────────────────────────

    private double computeQuickSimilarity(ChartTendencyProfile a, ChartTendencyProfile b) {
        double sim = 1.0;

        double effA = safe(a.getDominantEff16()), effB = safe(b.getDominantEff16());
        if (effA > 0 && effB > 0) {
            double d = Math.abs(effA - effB) / Math.max(effA, effB);
            sim *= Math.exp(-2.0 * d * d);
        }

        double dScr = Math.abs(safe(a.getScratchPct()) - safe(b.getScratchPct())) / 50.0;
        sim *= Math.exp(-1.5 * dScr * dScr);

        double dChord = Math.abs(safe(a.getChordPct()) - safe(b.getChordPct())) / 50.0;
        sim *= Math.exp(-1.0 * dChord * dChord);

        int cnA = a.getCnNotes() != null ? a.getCnNotes() : 0;
        int cnB = b.getCnNotes() != null ? b.getCnNotes() : 0;
        if ((cnA > 0) != (cnB > 0)) {
            double cnRatio = Math.max(cnA, cnB) / (double) Math.max(
                    a.getNotes() != null ? a.getNotes() : 1,
                    b.getNotes() != null ? b.getNotes() : 1);
            sim *= Math.exp(-50.0 * cnRatio);
        }

        if (Boolean.TRUE.equals(a.getIsSoflan()) != Boolean.TRUE.equals(b.getIsSoflan())) {
            sim *= 0.6;
        }

        return Math.min(1.0, sim);
    }

    // ── 接続理由 ──────────────────────────────────────────

    private String buildConnectionReason(ChartTendencyProfile from, ChartTendencyProfile to) {
        List<String> r = new ArrayList<>();
        double effFrom = safe(from.getDominantEff16()), effTo = safe(to.getDominantEff16());
        if (effFrom > 0 && effTo > 0) {
            double ratio = effTo / effFrom;
            if (ratio >= 0.9 && ratio <= 1.1) r.add("密度が近い");
            else if (ratio > 1.1) r.add("密度↑");
        }
        if (Math.abs(safe(from.getScratchPct()) - safe(to.getScratchPct())) < 5
                && safe(from.getScratchPct()) > 8) r.add("皿が近い");
        if (Math.abs(safe(from.getChordPct()) - safe(to.getChordPct())) < 8) r.add("同時押し率が近い");
        int cnF = from.getCnNotes() != null ? from.getCnNotes() : 0;
        int cnT = to.getCnNotes() != null ? to.getCnNotes() : 0;
        if (cnF > 0 && cnT > 0) r.add("CN");
        if (Boolean.TRUE.equals(from.getIsSoflan()) && Boolean.TRUE.equals(to.getIsSoflan()))
            r.add("ソフラン");
        if (r.isEmpty()) r.add("傾向が類似");
        return String.join("、", r.subList(0, Math.min(r.size(), 3)));
    }

    // ── ノード変換 ──────────────────────────────────────────

    private Map<String, Object> profileToNode(ChartTendencyProfile p) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("textage", p.getTextage());
        n.put("title", p.getTitle());
        n.put("artist", p.getArtist());
        n.put("level", p.getLevel());
        n.put("difficulty", p.getDifficulty());
        n.put("notes", p.getNotes());
        n.put("bpmMain", p.getBpmMain());
        n.put("bpmRaw", p.getBpmRaw());
        n.put("isSoflan", p.getIsSoflan());
        n.put("scratchPct", p.getScratchPct());
        n.put("chordPct", p.getChordPct());
        n.put("cnNotes", p.getCnNotes());
        n.put("dominantEff16", p.getDominantEff16());
        return n;
    }

    // ── ユーザー進捗 ──────────────────────────────────────────

    private Map<String, Object> buildUserProgress(List<Score> scores, List<ChartTendencyProfile> profiles) {
        Map<String, ChartTendencyProfile> byTitle = new HashMap<>();
        for (ChartTendencyProfile p : profiles) {
            byTitle.put(p.getTitle() + "\t" + diffCodeToName(p.getDifficulty()), p);
        }
        Map<String, Score> best = new HashMap<>();
        for (Score s : scores) {
            if (s.getClearType() == null) continue;
            String key = s.getTitle() + "\t" + s.getDifficultyName();
            Score ex = best.get(key);
            if (ex == null || compareClear(s.getClearType(), ex.getClearType()) > 0) best.put(key, s);
        }
        Map<String, Object> progress = new LinkedHashMap<>();
        for (Map.Entry<String, Score> e : best.entrySet()) {
            ChartTendencyProfile p = byTitle.get(e.getKey());
            if (p == null) continue;
            int rank = CLEAR_RANK.getOrDefault(e.getValue().getClearType(), -1);
            if (rank < 0) continue;
            Map<String, Object> prog = new LinkedHashMap<>();
            prog.put("bestClear", e.getValue().getClearType());
            prog.put("clearRank", rank);
            prog.put("missCount", e.getValue().getMissCount());
            progress.put(p.getTextage(), prog);
        }
        return progress;
    }

    // ── ヘルパー ──────────────────────────────────────────

    private double safe(Double d) { return d != null ? d : 0.0; }

    private String diffShort(String dc) {
        if (dc == null) return "A";
        return switch (dc) {
            case "1" -> "B"; case "2" -> "N"; case "3" -> "H";
            case "4" -> "A"; case "10" -> "L"; default -> "A";
        };
    }

    private String diffCodeToName(String dc) {
        if (dc == null) return "ANOTHER";
        return switch (dc) {
            case "1" -> "BEGINNER"; case "2" -> "NORMAL"; case "3" -> "HYPER";
            case "4" -> "ANOTHER"; case "10" -> "LEGGENDARIA"; default -> "ANOTHER";
        };
    }

    private int compareClear(String a, String b) {
        return Integer.compare(CLEAR_RANK.getOrDefault(a, -1), CLEAR_RANK.getOrDefault(b, -1));
    }

    private static class ChainLink {
        final ChartTendencyProfile profile;
        final double similarityToPrev;
        final String connectionReason;
        ChainLink(ChartTendencyProfile p, double sim, String reason) {
            this.profile = p; this.similarityToPrev = sim; this.connectionReason = reason;
        }
    }
}
