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
 * 【Service の役割】 スキルツリー生成サービス。全譜面対象・カテゴリなし貪欲法でチェーンを構築する。
 *
 * 責務:
 *  - 全譜面の {@link ChartTendencyProfile} を EDS（実効難易度スコア）で昇順ソート
 *  - 類似度と難易度近接度をスコア化して貪欲法で「数珠つなぎチェーン」を複数本生成
 *  - ユーザーの最高クリアタイプを textage ごとに集計し、進捗マップを返す
 *  - フロントエンドが「このチェーンを順番に伸ばせばよい」と可視化できる形に整形
 *
 * 依存:
 *  - {@link ChartTendencyProfileRepository}: 譜面傾向プロファイル（DB 上に全譜面分）
 *  - {@link ScoreRepository}: ユーザースコアを取得し進捗表示に利用
 *
 * 主要ロジックの概観:
 *  - EDS = レベル + 密度成分(最大0.4) + ノーツ数成分(最大0.3)
 *  - チェーン延長: current から EDS が大きい譜面のうち「類似度×0.7 + 近接ボーナス×0.3」が最大のものを貪欲に選択
 *  - 類似度 0.20 未満で打ち切り（独立ノード扱い）
 *  - 各チェーンは末端（最高 EDS）の譜面名で識別される
 */
@Service
public class SkillTreeService {

    /** 譜面傾向プロファイルのリポジトリ */
    private final ChartTendencyProfileRepository profileRepo;
    /** ユーザースコアのリポジトリ */
    private final ScoreRepository scoreRepo;

    /** チェーンを延長する最低類似度しきい値。これ未満は独立ノード扱い。 */
    private static final double SIMILARITY_THRESHOLD = 0.20;

    /** クリアタイプ文字列 → 数値ランク。大小比較のマップ。 */
    private static final Map<String, Integer> CLEAR_RANK = Map.of(
            "FAILED", 0, "ASSIST CLEAR", 1, "EASY CLEAR", 2,
            "CLEAR", 3, "HARD CLEAR", 4, "EX HARD CLEAR", 5, "FULLCOMBO CLEAR", 6
    );

    /**
     * 【コンストラクタ】 Spring が 2 つの Repository を注入する。
     */
    public SkillTreeService(ChartTendencyProfileRepository profileRepo,
                            ScoreRepository scoreRepo) {
        this.profileRepo = profileRepo;
        this.scoreRepo = scoreRepo;
    }

    /**
     * 【メソッドの役割】 ユーザー向けのスキルツリーを生成して返す。
     *
     * 処理の流れ:
     *  - 手順1: 全譜面プロファイルを取得し、notes が無いものは除外
     *  - 手順2: 各譜面の EDS（実効難易度スコア）を計算してキャッシュ
     *  - 手順3: EDS 昇順でソートし、未使用の最小 EDS 譜面を start として貪欲にチェーンを伸ばす
     *  - 手順4: 伸ばせなくなった（類似度しきい値未達）ら別チェーンへ移行、全譜面を使い切るまで繰り返し
     *  - 手順5: チェーンを長い順に並べ、ノード情報 + ユーザー進捗マップを返す
     *
     * @param user ログインユーザー。null の場合は userProgress を空で返す。
     * @return chains / userProgress を含むレスポンス Map
     */
    public Map<String, Object> generateSkillTree(User user) {
        // 手順1: 全譜面対象（レベルフィルタなし、notes が 0 や null の譜面のみ除外）
        List<ChartTendencyProfile> allProfiles = profileRepo.findAll().stream()
                .filter(p -> p.getNotes() != null && p.getNotes() > 0)
                .collect(Collectors.toList());

        // 手順2: 譜面ごとに EDS を計算して textage をキーにキャッシュ
        Map<String, Double> edsMap = new HashMap<>();
        for (ChartTendencyProfile p : allProfiles) {
            edsMap.put(p.getTextage(), computeEDS(p));
        }

        // EDS 昇順ソート（易しい譜面から開始する）
        List<ChartTendencyProfile> sorted = allProfiles.stream()
                .sorted(Comparator.comparingDouble(p -> edsMap.get(p.getTextage())))
                .collect(Collectors.toList());

        // 手順3: 貪欲法で複数チェーンを構築
        Set<String> used = new HashSet<>();
        List<List<ChainLink>> rawChains = new ArrayList<>();

        for (ChartTendencyProfile start : sorted) {
            if (used.contains(start.getTextage())) continue;

            // 新しいチェーンを start から開始
            List<ChainLink> chain = new ArrayList<>();
            chain.add(new ChainLink(start, 0.0, ""));
            used.add(start.getTextage());

            ChartTendencyProfile current = start;
            while (true) {
                double currentEds = edsMap.get(current.getTextage());
                ChartTendencyProfile bestNext = null;
                double bestScore = -1;
                double bestSimilarity = 0;

                // current より EDS が大きい未使用候補の中から最良スコアのものを探す
                for (ChartTendencyProfile candidate : sorted) {
                    if (used.contains(candidate.getTextage())) continue;
                    double candidateEds = edsMap.get(candidate.getTextage());
                    if (candidateEds <= currentEds) continue;

                    // 評価値 = 類似度×0.7 + 近接ボーナス×0.3
                    // 近接ボーナスは EDS 差が小さいほど大きくなる
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

                // 手順4: 類似度が閾値未満ならチェーン打ち切り
                if (bestNext == null || bestSimilarity < SIMILARITY_THRESHOLD) break;

                String reason = buildConnectionReason(current, bestNext);
                chain.add(new ChainLink(bestNext, bestSimilarity, reason));
                used.add(bestNext.getTextage());
                current = bestNext;
            }

            rawChains.add(chain);
        }

        // チェーン長降順でソート（長いチェーンが先＝UI で上位に配置する）
        rawChains.sort((a, b) -> Integer.compare(b.size(), a.size()));

        // 手順5: レスポンス用データの構築
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
     * 実効難易度スコアを計算する。
     * 公式レベルを整数部分とし、密度（dominantEff16 / 300）で最大 0.4、
     * ノーツ数（notes / 2000）で最大 0.3 の小数を加算する。
     * レベル未設定時は難易度コードからの推定値を用いる。
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

    /** レベル未設定時の推定値。難易度コード（"1".."10"）から仮レベルを返す。 */
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

    /**
     * 2 譜面間の簡易類似度を 0〜1 で返す。
     * 密度差・皿率差・同時押し率差・CN 有無・ソフラン有無を順次減衰係数として乗算していく。
     * {@link ChartTendencyService#computeSimilarity} より大幅に軽量なので、
     * 貪欲チェーン構築のように呼び出し回数が多い文脈で使う。
     */
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

    /**
     * チェーンの接続理由を日本語で最大 3 個まで並べて返す。
     * 「密度が近い」「皿が近い」「同時押し率が近い」「CN」「ソフラン」などの要素を拾う。
     * UI 側で矢印ラベルとして表示される。
     */
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

    /** プロファイルをフロントエンドで消費しやすい LinkedHashMap に変換する。 */
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

    /**
     * ユーザースコアから「textage → 最良クリア情報」のマップを作る。
     * 同じ曲の複数履歴を走査し、CLEAR_RANK の高い順に上書きしていくことで最高到達を残す。
     */
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

    /** null の Double を 0.0 に畳み込むユーティリティ */
    private double safe(Double d) { return d != null ? d : 0.0; }

    /** 難易度コード → 1 文字（B/N/H/A/L）。UI バッジ表示用。 */
    private String diffShort(String dc) {
        if (dc == null) return "A";
        return switch (dc) {
            case "1" -> "B"; case "2" -> "N"; case "3" -> "H";
            case "4" -> "A"; case "10" -> "L"; default -> "A";
        };
    }

    /** 難易度コード → 完全名（BEGINNER/NORMAL/...）。Score.difficultyName と整合させるため。 */
    private String diffCodeToName(String dc) {
        if (dc == null) return "ANOTHER";
        return switch (dc) {
            case "1" -> "BEGINNER"; case "2" -> "NORMAL"; case "3" -> "HYPER";
            case "4" -> "ANOTHER"; case "10" -> "LEGGENDARIA"; default -> "ANOTHER";
        };
    }

    /** クリアタイプ文字列同士を CLEAR_RANK に基づいて比較する。高い方が大きい値。 */
    private int compareClear(String a, String b) {
        return Integer.compare(CLEAR_RANK.getOrDefault(a, -1), CLEAR_RANK.getOrDefault(b, -1));
    }

    /**
     * 貪欲法で構築したチェーン内の 1 ノードを表す値クラス。
     * 対象プロファイルと、チェーンで 1 つ前のノードとの類似度・接続理由を保持する。
     */
    private static class ChainLink {
        final ChartTendencyProfile profile;
        final double similarityToPrev;
        final String connectionReason;
        ChainLink(ChartTendencyProfile p, double sim, String reason) {
            this.profile = p; this.similarityToPrev = sim; this.connectionReason = reason;
        }
    }
}
