package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRankSong;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ChartTendencyProfileRepository;
import com.beatseeker.backend.repository.DifficultyRankRepository;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 【Service の役割】 スコア取り込み後の BEAT-PT / RATE-PT を再計算するサービス。
 *
 * 責務:
 *  - 単発計算: {@link #calculateBeatPtFromActiveData(List)} / {@link #calculateRatePtFromActiveData(List)}
 *    は API エンドポイントなどから「今のスコア集合に対するポイント」を即時求めたいときに使う
 *  - 一括再計算: {@link #recalculateAllUsersAsync(String, String)} は曲データ/難易度テーブルを
 *    更新した際に、全ユーザーの BEAT-PT / RATE-PT を別スレッドで再計算する
 *  - 個別ユーザー再計算: {@link #processUserRecalculation(User, Map, Map)} は REQUIRES_NEW で
 *    独立トランザクションを切り、1 ユーザー分の集計結果を ScoreHistoryLog として追加記録する
 *  - パッチ: {@link #patchZeroRatePtLogs(Map)} は総 RATE-PT が 0 の履歴を救済する
 *
 * 依存:
 *  - {@link UserRepository} / {@link ScoreRepository} / {@link ScoreHistoryLogRepository}:
 *    ユーザー、スコア、履歴ログ
 *  - {@link SongDefinitionRepository} / {@link DifficultyRankRepository}: 曲定義と非公式難易度
 *  - {@link ObjectMapper}: recalculateAllUsersAsync 用の JSON パース
 *
 * 主要ロジックの概観:
 *  - BEAT-PT: scoreRate^1.3 × weight + しきい値ボーナス。HYPER でレベル 11 以上の譜面は対象外
 *  - RATE-PT: ANOTHER/LEGGENDARIA のみ対象。スコア率 77.77% から 100% までのしきい値で
 *    ピースワイズ線形補間。100% 超過は 100 件以降 +1pt ずつ加算
 *  - 上位 100 件までを合計して 0.1 桁で丸める（beatTier.ts と同じ公式）
 */
@Service
public class ScoreRecalculationService {

    /** ユーザーリポジトリ（全ユーザーループ用） */
    private final UserRepository userRepository;
    /** スコアリポジトリ */
    private final ScoreRepository scoreRepository;
    /** 集計結果の履歴ログリポジトリ */
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;
    /** 曲定義リポジトリ（notes からスコア最大値を計算） */
    private final SongDefinitionRepository songDefinitionRepository;
    /** 非公式難易度リポジトリ（ランク値→weight 取得用） */
    private final DifficultyRankRepository difficultyRankRepository;
    /** JSON パース用 Jackson マッパー */
    private final ObjectMapper objectMapper;
    /** BEAT-PT / RATE-PT 単曲計算の共通ユーティリティ */
    private final BeatPtCalculator beatPtCalculator;
    /** 譜面傾向プロファイル（KENBAN/SARA-PT 算出に必要な皿率を取得） */
    private final ChartTendencyProfileRepository chartTendencyProfileRepository;

    // ── KENBAN/SARA-TIER 算出定数（フロントの beatTier.ts と同期） ──────────────
    /** 皿率の按分しきい値 (%)。この値以上は完全に皿曲扱い。 */
    private static final double SCRATCH_FULL_THRESHOLD_PCT = 30.0;
    /** KENBAN-PT の BEAT 同等スケール補正倍率。 */
    private static final double KENBAN_PT_MULTIPLIER = 1.125;
    /** SARA-PT の BEAT 同等スケール補正倍率。 */
    private static final double SARA_PT_MULTIPLIER = 1.5;

    /**
     * 【コンストラクタ】 Spring が Repository 群と {@link ObjectMapper} を注入する。
     */
    public ScoreRecalculationService(UserRepository userRepository, ScoreRepository scoreRepository, ScoreHistoryLogRepository scoreHistoryLogRepository, SongDefinitionRepository songDefinitionRepository, DifficultyRankRepository difficultyRankRepository, ObjectMapper objectMapper, BeatPtCalculator beatPtCalculator, ChartTendencyProfileRepository chartTendencyProfileRepository) {
        this.userRepository = userRepository;
        this.scoreRepository = scoreRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.songDefinitionRepository = songDefinitionRepository;
        this.difficultyRankRepository = difficultyRankRepository;
        this.objectMapper = objectMapper;
        this.beatPtCalculator = beatPtCalculator;
        this.chartTendencyProfileRepository = chartTendencyProfileRepository;
    }

    /**
     * 【メソッドの役割】 与えられたスコア集合から BEAT-PT の総合値を計算する。
     *
     * DB 上の active な曲定義と非公式難易度テーブルを引き当てて、上位 100 件の合計を返す。
     * 履歴ログの total_beat_pt が欠損・0 の場合のサーバーサイドフォールバックとして使われる。
     *
     * 処理の流れ:
     *  - 手順1: active 曲定義から (title_difficultyCode → maxScore=notes*2) マップを作る
     *  - 手順2: active 難易度テーブルから (title_diffName → rankValue) マップを作る
     *    ※曲タイトル末尾 "[L]" は LEGGENDARIA として扱う
     *  - 手順3: スコアごとに score rate を計算し、HYPER レベル11以上の譜面は除外
     *  - 手順4: {@link #calculatePoints(double, String)} で点数を算出し上位 100 件合計を返す
     *
     * @param scores 対象スコア一覧
     * @return 0.1 桁まで丸めた BEAT-PT 合計値
     */
    /**
     * 【メソッドの役割】 active 曲定義から `(title_difficultyCode) → maxScore (= notes*2)` の Map を構築する。
     * 各種計算で繰り返し利用するため public で公開している（外部から再利用可）。
     */
    public Map<String, Integer> loadSongMaxScores() {
        Map<String, Integer> songMaxScores = new HashMap<>();
        for (SongDefinition s : songDefinitionRepository.findByRevision("active")) {
            if (s.getNotes() != null && s.getNotes() > 0) {
                songMaxScores.put(s.getTitle() + "_" + s.getDifficulty(), s.getNotes() * 2);
            }
        }
        return songMaxScores;
    }

    /**
     * 【メソッドの役割】 active 難易度表から `(title_diffName) → 非公式ランク文字列` の Map を構築する。
     * "[L]" サフィックスは LEGGENDARIA として展開する。
     */
    public Map<String, String> loadInformalRanks() {
        Map<String, String> informalRanks = new HashMap<>();
        for (DifficultyRank rank : difficultyRankRepository.findByRevisionOrderBySortOrderAsc("active")) {
            String rankText = rank.getRankValue();
            for (DifficultyRankSong song : rank.getSongs()) {
                String songTitle = song.getSongTitle() == null ? "" : song.getSongTitle().trim();
                if (songTitle.isEmpty()) continue;
                if (songTitle.endsWith("[L]")) {
                    informalRanks.put(songTitle.substring(0, songTitle.length() - 3).trim() + "_LEGGENDARIA", rankText);
                } else {
                    informalRanks.put(songTitle + "_ANOTHER", rankText);
                }
            }
        }
        return informalRanks;
    }

    /**
     * 【メソッドの役割】 chart_tendency_profiles から `(title_diffName) → 皿率(%)` の Map を構築する。
     *
     * KENBAN/SARA-PT 算出に必要な皿率マスタを一括読み込みする。`difficulty='4'`→ANOTHER、`difficulty='10'`→LEGGENDARIA。
     *
     * @return 皿率 Map（`scratchPct IS NOT NULL` のレコードのみ含む）
     */
    public Map<String, Double> loadScratchMap() {
        Map<String, Double> map = new HashMap<>();
        for (Object[] row : chartTendencyProfileRepository.findScratchSummaryForAnotherLegg()) {
            String title = (String) row[0];
            String diff = (String) row[1];
            String diffName = "4".equals(diff) ? "ANOTHER" : "10".equals(diff) ? "LEGGENDARIA" : null;
            if (diffName == null) continue;
            map.put(title + "_" + diffName, ((Number) row[2]).doubleValue());
        }
        return map;
    }

    /** KENBAN-TIER 側の重み: scratchPct が低いほど高い (0〜1)。null は 0。 */
    private static double kenbanWeight(Double scratchPct) {
        if (scratchPct == null) return 0.0;
        double w = 1.0 - scratchPct / SCRATCH_FULL_THRESHOLD_PCT;
        return Math.max(0.0, Math.min(1.0, w));
    }

    /** SARA-TIER 側の重み: scratchPct が高いほど高い (0〜1)。null は 0。 */
    private static double saraWeight(Double scratchPct) {
        if (scratchPct == null) return 0.0;
        double w = scratchPct / SCRATCH_FULL_THRESHOLD_PCT;
        return Math.max(0.0, Math.min(1.0, w));
    }

    /**
     * 【メソッドの役割】 BEAT-PT 計算と同じスコア集合から KENBAN-PT / SARA-PT を一括算出する。
     *
     * 計算ロジック:
     *  - 各 ANOTHER/LEGGENDARIA 譜面の BP に kenbanWeight / saraWeight を掛けた値を「貢献 pt」とする
     *  - 各 side ごとに独立に降順ソート → 上位 100 譜面の合計
     *  - 補正倍率 (KENBAN: ×1.125, SARA: ×1.5) を掛けて BEAT 同等スケールに揃え
     *  - 0.1 桁で丸める
     *
     * @param scores         対象ユーザーの全スコア（事前ロード済み）
     * @param songMaxScores  title_difficultyCode → maxScore(=notes*2)
     * @param informalRanks  title_diffName → 非公式ランク文字列
     * @param scratchMap     title_diffName → 皿率(%) （{@link #loadScratchMap()} の戻り値）
     * @return `[KENBAN-PT, SARA-PT]` の 2 要素配列
     */
    public double[] calculateKenbanSaraPtFromActiveData(List<Score> scores, Map<String, Integer> songMaxScores,
                                                       Map<String, String> informalRanks, Map<String, Double> scratchMap) {
        List<Double> kenContribs = new ArrayList<>();
        List<Double> sarContribs = new ArrayList<>();
        for (Score score : scores) {
            if ("---".equals(score.getClearType()) || "NO PLAY".equals(score.getClearType())) continue;
            String diffName = normalizeDiffName(score.getDifficultyName());
            // KENBAN/SARA は ANOTHER/LEGGENDARIA のみ対象
            if (!"ANOTHER".equals(diffName) && !"LEGGENDARIA".equals(diffName)) continue;
            String code = getDifficultyCode(diffName);
            if (code == null) continue;
            Integer maxScore = songMaxScores.get(score.getTitle() + "_" + code);
            if (maxScore == null || maxScore == 0) continue;
            double scoreRate = (score.getScore() != null ? score.getScore() : 0) * 100.0 / maxScore;
            String informalRankString = informalRanks.get(score.getTitle() + "_" + diffName);
            double bp = beatPtCalculator.calculatePoints(scoreRate, informalRankString);
            if (bp <= 0) continue;
            Double scratchPct = scratchMap.get(score.getTitle() + "_" + diffName);
            double kw = kenbanWeight(scratchPct);
            double sw = saraWeight(scratchPct);
            double kc = bp * kw;
            double sc = bp * sw;
            if (kc > 0) kenContribs.add(kc);
            if (sc > 0) sarContribs.add(sc);
        }
        kenContribs.sort(Collections.reverseOrder());
        sarContribs.sort(Collections.reverseOrder());
        double kenSum = 0;
        for (int i = 0; i < Math.min(100, kenContribs.size()); i++) kenSum += kenContribs.get(i);
        double sarSum = 0;
        for (int i = 0; i < Math.min(100, sarContribs.size()); i++) sarSum += sarContribs.get(i);
        double kenbanPt = Math.round(kenSum * KENBAN_PT_MULTIPLIER * 10.0) / 10.0;
        double saraPt   = Math.round(sarSum * SARA_PT_MULTIPLIER   * 10.0) / 10.0;
        return new double[]{kenbanPt, saraPt};
    }

    public double calculateBeatPtFromActiveData(List<Score> scores) {
        List<SongDefinition> activeSongs = songDefinitionRepository.findByRevision("active");
        Map<String, Integer> songMaxScores = new HashMap<>();
        for (SongDefinition s : activeSongs) {
            if (s.getNotes() != null && s.getNotes() > 0) {
                songMaxScores.put(s.getTitle() + "_" + s.getDifficulty(), s.getNotes() * 2);
            }
        }

        Map<String, String> informalRanks = new HashMap<>();
        List<DifficultyRank> ranks = difficultyRankRepository.findByRevisionOrderBySortOrderAsc("active");
        for (DifficultyRank rank : ranks) {
            String rankText = rank.getRankValue();
            for (DifficultyRankSong song : rank.getSongs()) {
                String songTitle = song.getSongTitle() == null ? "" : song.getSongTitle().trim();
                if (songTitle.isEmpty()) continue;
                if (songTitle.endsWith("[L]")) {
                    String baseTitle = songTitle.substring(0, songTitle.length() - 3).trim();
                    informalRanks.put(baseTitle + "_LEGGENDARIA", rankText);
                } else {
                    informalRanks.put(songTitle + "_ANOTHER", rankText);
                }
            }
        }
        System.out.println("[calcBeat] songMaxScores=" + songMaxScores.size() + " informalRanks=" + informalRanks.size() + " scores=" + scores.size());

        List<Double> beatPts = new ArrayList<>();
        int matchedRank = 0;
        int matchedMax = 0;
        for (Score score : scores) {
            if ("---".equals(score.getClearType()) || "NO PLAY".equals(score.getClearType())) continue;
            String diffName = normalizeDiffName(score.getDifficultyName());
            String code = getDifficultyCode(diffName);
            if (code == null) continue;
            Integer maxScore = songMaxScores.get(score.getTitle() + "_" + code);
            if (maxScore == null || maxScore == 0) continue;
            matchedMax++;
            double scoreRate = (score.getScore() != null ? score.getScore() : 0) * 100.0 / maxScore;
            String informalRankString = informalRanks.get(score.getTitle() + "_" + diffName);
            if (informalRankString != null) matchedRank++;
            boolean isHyperNonTarget = "HYPER".equals(diffName) && score.getDifficultyLevel() != null && score.getDifficultyLevel() >= 11;
            if (isHyperNonTarget) continue;
            double pt = beatPtCalculator.calculatePoints(scoreRate, informalRankString);
            if (pt > 0) beatPts.add(pt);
        }
        System.out.println("[calcBeat] matchedMax=" + matchedMax + " matchedRank=" + matchedRank + " beatPts=" + beatPts.size());
        beatPts.sort(Collections.reverseOrder());
        double total = 0;
        for (int i = 0; i < Math.min(100, beatPts.size()); i++) total += beatPts.get(i);
        return Math.round(total * 10.0) / 10.0;
    }

    /**
     * 【メソッドの役割】 与えられたスコア集合から RATE-PT の総合値を計算する。
     *
     * ANOTHER / LEGGENDARIA のみを対象に、{@link #SCORE_RATE_THRESHOLDS} で
     * ピースワイズ線形にポイント化し、上位 100 件の合計を返す。
     * フロントエンドから送られた値が 0/欠損の場合のサーバーサイドフォールバックとして使われる。
     *
     * 処理の流れ:
     *  - 手順1: active 曲定義から maxScore マップを作る
     *  - 手順2: ANOTHER/LEGGENDARIA 以外は除外して scoreRate を計算
     *  - 手順3: scoreRate を {@link #calculateScoreRateTierPoints(double)} で点数化し集める
     *  - 手順4: 上位 100 件を合計、さらに 100% 超過ぶんだけ +1pt ずつ上乗せ
     *
     * @param scores 対象スコア一覧
     * @return 0.1 桁まで丸めた RATE-PT 合計値
     */
    public double calculateRatePtFromActiveData(List<Score> scores) {
        List<SongDefinition> activeSongs = songDefinitionRepository.findByRevision("active");
        Map<String, Integer> songMaxScores = new HashMap<>();
        for (SongDefinition s : activeSongs) {
            if (s.getNotes() != null && s.getNotes() > 0) {
                songMaxScores.put(s.getTitle() + "_" + s.getDifficulty(), s.getNotes() * 2);
            }
        }

        List<Double> ratePts = new ArrayList<>();
        int perfectRateCount = 0;
        for (Score score : scores) {
            String diffName = normalizeDiffName(score.getDifficultyName());
            boolean isRateEligible = "ANOTHER".equals(diffName) || "LEGGENDARIA".equals(diffName);
            if (!isRateEligible) continue;
            String code = getDifficultyCode(diffName);
            if (code == null) continue;
            Integer maxScore = songMaxScores.get(score.getTitle() + "_" + code);
            if (maxScore == null || maxScore == 0) continue;
            double scoreRate = (score.getScore() != null ? score.getScore() : 0) * 100.0 / maxScore;
            if (scoreRate <= 0) continue;
            double rPt = beatPtCalculator.calculateScoreRateTierPoints(scoreRate);
            if (rPt > 0) ratePts.add(rPt);
            if (scoreRate >= 100.0) perfectRateCount++;
        }
        ratePts.sort(Collections.reverseOrder());
        double totalRatePtAcc = 0;
        for (int i = 0; i < Math.min(100, ratePts.size()); i++) totalRatePtAcc += ratePts.get(i);
        if (perfectRateCount > 100) totalRatePtAcc += (perfectRateCount - 100);
        return Math.round(totalRatePtAcc * 10.0) / 10.0;
    }

    /**
     * 【メソッドの役割】 渡された JSON を元に、全ユーザーの BEAT-PT / RATE-PT を非同期で再計算する。
     *
     * 管理者が曲定義や難易度テーブルのドラフトを適用した直後に GameDataService から呼ばれる。
     * 個別ユーザーの再計算は {@link #processUserRecalculation(User, Map, Map)} に委譲し、
     * 1 ユーザーの失敗が全体を止めないよう try/catch で包む。
     *
     * 処理の流れ:
     *  - 手順1: 渡された song_data JSON から (title_code → maxScore) マップを構築
     *  - 手順2: 渡された difficulty_table JSON から (title_diffName → rankValue) マップを構築
     *  - 手順3: 全ユーザーを走査し、各々について新規トランザクションで再計算を実行
     *
     * @param songDataJson       最新の曲定義 JSON
     * @param difficultyTableJson 最新の難易度テーブル JSON
     * @throws Exception JSON パース失敗時
     */
    @Async
    public void recalculateAllUsersAsync(String songDataJson, String difficultyTableJson) throws Exception {
        JsonNode songDataRoot = objectMapper.readTree(songDataJson);
        JsonNode diffTableRoot = objectMapper.readTree(difficultyTableJson);

        // 手順1: maxScore マップ構築（title_difficultyCode → notes*2）
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

        // 手順2: 非公式難易度マップ構築（title_diffName → rankValue 文字列）
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

        // 手順3 (前段): 皿率マスタを 1 度だけロード（KENBAN/SARA-PT 算出用）
        Map<String, Double> scratchMap = loadScratchMap();

        // 手順3: 全ユーザーを走査して、各々を独立トランザクションで再計算
        List<User> users = userRepository.findAll();
        for (User user : users) {
            try {
                processUserRecalculation(user, songMaxScores, informalRanks, scratchMap);
            } catch (Exception e) {
                System.err.println("Failed to recalculate user " + user.getId() + ": " + e.getMessage());
            }
        }
    }

    /**
     * 【メソッドの役割】 active な DB データ（曲定義 / 難易度表）から、指定 1 ユーザーの履歴ログを
     * 「初回登録扱い」で生成・追加する。
     *
     * 用途: アップロードはされているが {@code save-history-log} が呼ばれなかった等で
     * {@code score_history_logs} にレコードが無くランキングに出てこないユーザーを救済する。
     *
     * 内部的には active な SongDefinition / DifficultyRank からマップを組み、
     * {@link #processUserRecalculation(User, Map, Map)} に委譲して新規スナップショットを 1 件追加する。
     * 履歴が既にあるユーザーに呼んでも単に最新スナップショットが追加されるだけで害はない。
     *
     * @param user 対象ユーザー（必須）
     * @return 履歴ログを追加した場合 true、スコアが 1 件もなく追加されなかった場合 false
     */
    @Transactional
    public boolean recalculateSingleUserFromActiveData(User user) {
        // 手順1: active 曲定義から (title_difficultyCode → maxScore = notes*2) マップを作る。
        Map<String, Integer> songMaxScores = new HashMap<>();
        for (SongDefinition s : songDefinitionRepository.findByRevision("active")) {
            if (s.getNotes() != null && s.getNotes() > 0) {
                songMaxScores.put(s.getTitle() + "_" + s.getDifficulty(), s.getNotes() * 2);
            }
        }

        // 手順2: active 難易度表から (title_diffName → rankValue) マップを作る。
        Map<String, String> informalRanks = new HashMap<>();
        for (DifficultyRank rank : difficultyRankRepository.findByRevisionOrderBySortOrderAsc("active")) {
            String rankText = rank.getRankValue();
            for (DifficultyRankSong song : rank.getSongs()) {
                String songTitle = song.getSongTitle() == null ? "" : song.getSongTitle().trim();
                if (songTitle.isEmpty()) continue;
                if (songTitle.endsWith("[L]")) {
                    String baseTitle = songTitle.substring(0, songTitle.length() - 3).trim();
                    informalRanks.put(baseTitle + "_LEGGENDARIA", rankText);
                } else {
                    informalRanks.put(songTitle + "_ANOTHER", rankText);
                }
            }
        }

        // 手順3: スコア 0 件ならスキップ（履歴ログを作っても意味がない）。
        if (scoreRepository.findByUserOrderByUploadedAtAsc(user).isEmpty()) return false;

        // 手順4: 皿率マスタもロードし、既存の per-user 再計算ロジックに委譲。これが新規 ScoreHistoryLog を 1 件追加する。
        Map<String, Double> scratchMap = loadScratchMap();
        processUserRecalculation(user, songMaxScores, informalRanks, scratchMap);

        // 手順5: users.total_beat_pt / total_kenban_pt / total_sara_pt キャッシュを追従させる（ランキング集計の高速化用）。
        scoreHistoryLogRepository.findFirstByUserOrderByUploadedAtDesc(user).ifPresent(log -> {
            if (log.getTotalBeatPt()   != null) user.setTotalBeatPt(log.getTotalBeatPt());
            if (log.getTotalKenbanPt() != null) user.setTotalKenbanPt(log.getTotalKenbanPt());
            if (log.getTotalSaraPt()   != null) user.setTotalSaraPt(log.getTotalSaraPt());
            userRepository.save(user);
        });
        return true;
    }

    /**
     * 【メソッドの役割】 1 ユーザー分の BEAT-PT / RATE-PT と各種カウンタを再計算して履歴ログに保存する。
     *
     * {@code REQUIRES_NEW} で独立トランザクションを起動するため、一部ユーザーで失敗しても他に影響しない。
     *
     * 処理の流れ:
     *  - 手順1: ユーザーの全スコアを取得、空ならスキップ
     *  - 手順2: スコアを 1 件ずつ走査しながら、BEAT-PT / RATE-PT 用の値と各種カウンタを集計
     *  - 手順3: 上位 100 件合計で BEAT-PT / RATE-PT を確定、100% 超過ぶんは RATE-PT に +1ずつ加算
     *  - 手順4: 直近の履歴ログから旧 BEAT-PT を取得し、差分を計算
     *  - 手順5: 新しい {@link ScoreHistoryLog} を insert
     *
     * @param user          対象ユーザー
     * @param songMaxScores title_code → maxScore マップ
     * @param informalRanks title_diffName → rankValue マップ
     * @param scratchMap    title_diffName → 皿率(%) （KENBAN/SARA-PT 算出用）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processUserRecalculation(User user, Map<String, Integer> songMaxScores, Map<String, String> informalRanks, Map<String, Double> scratchMap) {
        List<Score> scores = scoreRepository.findByUserOrderByUploadedAtAsc(user);
        if (scores.isEmpty()) return;

        List<Double> beatPts = new ArrayList<>();
        List<Double> ratePts = new ArrayList<>();
        int perfectRateCount = 0;

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
                double pt = beatPtCalculator.calculatePoints(scoreRate, informalRankString);
                if (pt > 0) beatPts.add(pt);
            }

            // RATE-PT
            boolean isRateEligible = "ANOTHER".equals(diffName) || "LEGGENDARIA".equals(diffName);
            if (isRateEligible && scoreRate > 0) {
                double rPt = beatPtCalculator.calculateScoreRateTierPoints(scoreRate);
                if (rPt > 0) ratePts.add(rPt);
                if (scoreRate >= 100.0) perfectRateCount++;
            }
        }

        beatPts.sort(Collections.reverseOrder());
        double totalBeatPtAcc = 0;
        for (int i = 0; i < Math.min(100, beatPts.size()); i++) totalBeatPtAcc += beatPts.get(i);
        double finalBeatPt = Math.round(totalBeatPtAcc * 10.0) / 10.0;

        ratePts.sort(Collections.reverseOrder());
        double totalRatePtAcc = 0;
        for (int i = 0; i < Math.min(100, ratePts.size()); i++) totalRatePtAcc += ratePts.get(i);
        if (perfectRateCount > 100) totalRatePtAcc += (perfectRateCount - 100);
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

        // KENBAN-PT / SARA-PT を派生計算（同じスコア集合から皿率重み付きで集計）
        double[] kenbanSara = calculateKenbanSaraPtFromActiveData(scores, songMaxScores, informalRanks, scratchMap);
        newLog.setTotalKenbanPt(kenbanSara[0]);
        newLog.setTotalSaraPt(kenbanSara[1]);

        scoreHistoryLogRepository.save(newLog);

        // users テーブルのキャッシュも同期更新（ランキング クエリの高速化用）
        user.setTotalKenbanPt(kenbanSara[0]);
        user.setTotalSaraPt(kenbanSara[1]);
        userRepository.save(user);
    }

    /**
     * 【メソッドの役割】 total_rate_pt が 0 のまま残っている履歴ログを、現在のスコアから再計算して補正する。
     *
     * RATE-PT 機能導入以前の古い履歴ログを救済する目的の一括パッチ。
     * 全ユーザーを走査し、履歴内に 0 または null の total_rate_pt があれば
     * 現在のスコアから計算し直した値で上書きする。
     *
     * 処理の流れ:
     *  - 手順1: 全ユーザーについてログ一覧をチェック、0 が混ざるユーザーのみ対象
     *  - 手順2: 現在のスコアから RATE-PT を再計算
     *  - 手順3: 0 となっているログをその値で上書きして保存
     *
     * @param songMaxScores title_difficultyCode → maxScore のマップ
     * @return パッチ適用したログ件数
     */
    @Transactional
    public int patchZeroRatePtLogs(Map<String, Integer> songMaxScores) {
        List<User> users = userRepository.findAll();
        int patchedCount = 0;
        for (User user : users) {
            List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(user);
            boolean hasZero = logs.stream().anyMatch(l -> l.getTotalRatePt() == null || l.getTotalRatePt() == 0.0);
            if (!hasZero) continue;

            // 現在のスコアから RATE-PT を再計算する
            List<Score> scores = scoreRepository.findByUserOrderByUploadedAtAsc(user);
            List<Double> ratePts = new ArrayList<>();
            int perfectRateCount = 0;
            for (Score score : scores) {
                String diffName = normalizeDiffName(score.getDifficultyName());
                boolean isRateEligible = "ANOTHER".equals(diffName) || "LEGGENDARIA".equals(diffName);
                if (!isRateEligible) continue;
                String code = getDifficultyCode(diffName);
                if (code == null) continue;
                Integer maxScore = songMaxScores.get(score.getTitle() + "_" + code);
                if (maxScore == null || maxScore == 0) continue;
                double scoreRate = (score.getScore() != null ? score.getScore() : 0) * 100.0 / maxScore;
                if (scoreRate <= 0) continue;
                double rPt = beatPtCalculator.calculateScoreRateTierPoints(scoreRate);
                if (rPt > 0) ratePts.add(rPt);
                if (scoreRate >= 100.0) perfectRateCount++;
            }
            ratePts.sort(Collections.reverseOrder());
            double totalRatePtAcc = 0;
            for (int i = 0; i < Math.min(100, ratePts.size()); i++) totalRatePtAcc += ratePts.get(i);
            if (perfectRateCount > 100) totalRatePtAcc += (perfectRateCount - 100);
            double calculatedRatePt = Math.round(totalRatePtAcc * 10.0) / 10.0;
            if (calculatedRatePt <= 0) continue;

            for (ScoreHistoryLog log : logs) {
                if (log.getTotalRatePt() == null || log.getTotalRatePt() == 0.0) {
                    log.setTotalRatePt(calculatedRatePt);
                    scoreHistoryLogRepository.save(log);
                    patchedCount++;
                }
            }
        }
        return patchedCount;
    }

    /**
     * 【メソッドの役割】 全 ScoreHistoryLog の {@code diffJson} を走査し、
     * 譜面別 {@code newRatePt} が欠落／null／0 の要素を、現在の songMaxScores から
     * 再計算して埋め直す。
     *
     * 用途:
     *  - 外部 API (`/api/external/v1/song-detail`) の {@code history[].ratePt} が
     *    古いスナップショットだけ null になる問題を解消する一括パッチ。
     *  - {@link #patchZeroRatePtLogs(Map)} はユーザー単位の {@code total_rate_pt} だけを
     *    補正するが、こちらは「個別曲の RATE-PT スナップショット」を補正する。
     *
     * 計算式は {@link #patchZeroRatePtLogs(Map)} と同等:
     *  - ANOTHER / LEGGENDARIA のみ対象。それ以外は本来 0 が正なので触らない。
     *  - {@code scoreRate = newScore * 100 / (notes * 2)}
     *  - {@link BeatPtCalculator#calculateScoreRateTierPoints(double)} を適用
     *
     * 冪等性: 既に正の値が入っている要素はスキップする。
     *
     * @param songMaxScores {@code title + "_" + difficultyCode} → maxScore のマップ
     * @return 修正したログレコード件数（参考値、実際に書き換わった要素数ではない）
     */
    @Transactional
    public int patchZeroRatePtInDiffJson(Map<String, Integer> songMaxScores) {
        ObjectMapper mapper = new ObjectMapper();
        List<ScoreHistoryLog> allLogs = scoreHistoryLogRepository.findAll();
        int patchedLogs = 0;

        for (ScoreHistoryLog log : allLogs) {
            String diffJsonStr = log.getDiffJson();
            if (diffJsonStr == null || diffJsonStr.isBlank() || "[]".equals(diffJsonStr)) continue;

            try {
                List<Map<String, Object>> diffs = mapper.readValue(diffJsonStr,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                boolean modified = false;

                for (Map<String, Object> diff : diffs) {
                    // 既に正の数値が入っていれば触らない（冪等）。
                    Object existing = diff.get("newRatePt");
                    boolean isMissingOrZero = existing == null
                            || (existing instanceof Number && ((Number) existing).doubleValue() == 0.0);
                    if (!isMissingOrZero) continue;

                    Object titleObj = diff.get("title");
                    Object diffObj = diff.get("difficulty");
                    Object newScoreObj = diff.get("newScore");
                    if (!(titleObj instanceof String) || !(diffObj instanceof String) || !(newScoreObj instanceof Number)) {
                        continue;
                    }
                    String diffUpper = normalizeDiffName((String) diffObj);
                    boolean isRateEligible = "ANOTHER".equals(diffUpper) || "LEGGENDARIA".equals(diffUpper);
                    // RATE 対象外は元から 0 が正解なので、null も 0 のまま残す（ノイズを増やさない）。
                    if (!isRateEligible) continue;

                    String code = getDifficultyCode(diffUpper);
                    if (code == null) continue;
                    Integer maxScore = songMaxScores.get(((String) titleObj) + "_" + code);
                    if (maxScore == null || maxScore == 0) continue;

                    int newScore = ((Number) newScoreObj).intValue();
                    double scoreRate = newScore * 100.0 / maxScore;
                    if (scoreRate <= 0) continue;
                    double rPt = beatPtCalculator.calculateScoreRateTierPoints(scoreRate);
                    if (rPt <= 0) continue;

                    diff.put("newRatePt", rPt);
                    modified = true;
                }

                if (modified) {
                    log.setDiffJson(mapper.writeValueAsString(diffs));
                    scoreHistoryLogRepository.save(log);
                    patchedLogs++;
                }
            } catch (Exception e) {
                // 壊れた diffJson は静かにスキップ。1 件壊れててもバッチ全体は止めない。
            }
        }
        return patchedLogs;
    }

    /** 難易度名を大文字化して正規化する（"Another" → "ANOTHER"）。 */
    private String normalizeDiffName(String diff) {
        if (diff == null) return "UNKNOWN";
        return diff.toUpperCase();
    }

    /** 大文字難易度名 → 内部コード（1/2/3/4/10）。該当なしは null。 */
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

}
