package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRankSong;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.entity.User;
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

    /**
     * 【コンストラクタ】 Spring が Repository 群と {@link ObjectMapper} を注入する。
     */
    public ScoreRecalculationService(UserRepository userRepository, ScoreRepository scoreRepository, ScoreHistoryLogRepository scoreHistoryLogRepository, SongDefinitionRepository songDefinitionRepository, DifficultyRankRepository difficultyRankRepository, ObjectMapper objectMapper, BeatPtCalculator beatPtCalculator) {
        this.userRepository = userRepository;
        this.scoreRepository = scoreRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.songDefinitionRepository = songDefinitionRepository;
        this.difficultyRankRepository = difficultyRankRepository;
        this.objectMapper = objectMapper;
        this.beatPtCalculator = beatPtCalculator;
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
        return loadInformalRanks("active");
    }

    /**
     * 【メソッドの役割】 指定 revision の難易度表から `(title_diffName) → 非公式ランク文字列` の Map を構築する。
     *
     * 過去作ランキングの再計算（{@link ArchivedVersionPtService}）が、世代切替時に凍結した表
     * （{@code archive:<version>}）で計算するために使う。
     *
     * @param revision "active" / "draft" / "archive:33" など
     */
    public Map<String, String> loadInformalRanks(String revision) {
        Map<String, String> informalRanks = new HashMap<>();
        for (DifficultyRank rank : difficultyRankRepository.findByRevisionOrderBySortOrderAsc(revision)) {
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
     * 【メソッドの役割】 スコア集合から BEAT / RATE の 2 指標を一度に計算する（履歴には書かない）。
     *
     * 集計規則は {@link #processUserRecalculation} と同一:
     *  - (曲, 難易度) ごとに EX SCORE が高い行だけを採用（同一譜面の重複行による二重計上防止）
     *  - BEAT: HYPER レベル 11 以上は対象外。上位 100 譜面の合計
     *  - RATE: ANOTHER / LEGGENDARIA のみ。上位 100 譜面の合計 ＋ 100% 超過 100 件以降の +1pt
     *  - いずれも 0.1 桁で丸める
     *
     * 過去作ランキングの再計算（{@link ArchivedVersionPtService}）が、凍結した難易度表を渡して呼ぶ。
     *
     * @param scores        対象スコア（永続化されていない一時オブジェクトでもよい）
     * @param songMaxScores title_difficultyCode → 理論値（notes×2）
     * @param informalRanks title_diffName → 非公式ランク文字列
     * @return {@code [BEAT-PT, RATE-PT]}
     */
    public double[] calculatePtTotals(List<Score> scores, Map<String, Integer> songMaxScores,
                                      Map<String, String> informalRanks) {
        Map<String, Score> bestByChart = new LinkedHashMap<>();
        for (Score s : scores) {
            String key = s.getTitle() + " " + s.getDifficultyName();
            Score cur = bestByChart.get(key);
            int sv = s.getScore() != null ? s.getScore() : 0;
            int cv = (cur != null && cur.getScore() != null) ? cur.getScore() : -1;
            if (cur == null || sv > cv) bestByChart.put(key, s);
        }
        List<Score> deduped = new ArrayList<>(bestByChart.values());

        List<Double> beatPts = new ArrayList<>();
        List<Double> ratePts = new ArrayList<>();
        int perfectRateCount = 0;
        for (Score score : deduped) {
            if ("---".equals(score.getClearType()) || "NO PLAY".equals(score.getClearType())) continue;
            String diffName = normalizeDiffName(score.getDifficultyName());
            String code = getDifficultyCode(diffName);
            if (code == null) continue;
            Integer maxScore = songMaxScores.get(score.getTitle() + "_" + code);
            if (maxScore == null || maxScore == 0) continue;
            double scoreRate = (score.getScore() != null ? score.getScore() : 0) * 100.0 / maxScore;
            String informalRankString = informalRanks.get(score.getTitle() + "_" + diffName);

            boolean isHyperNonTarget = "HYPER".equals(diffName) && score.getDifficultyLevel() != null && score.getDifficultyLevel() >= 11;
            if (!isHyperNonTarget) {
                double pt = beatPtCalculator.calculatePoints(scoreRate, informalRankString);
                if (pt > 0) beatPts.add(pt);
            }
            boolean isRateEligible = "ANOTHER".equals(diffName) || "LEGGENDARIA".equals(diffName);
            if (isRateEligible && scoreRate > 0) {
                double rPt = beatPtCalculator.calculateScoreRateTierPoints(scoreRate);
                if (rPt > 0) ratePts.add(rPt);
                if (scoreRate >= 100.0) perfectRateCount++;
            }
        }
        beatPts.sort(Collections.reverseOrder());
        double beatAcc = 0;
        for (int i = 0; i < Math.min(100, beatPts.size()); i++) beatAcc += beatPts.get(i);
        ratePts.sort(Collections.reverseOrder());
        double rateAcc = 0;
        for (int i = 0; i < Math.min(100, ratePts.size()); i++) rateAcc += ratePts.get(i);
        if (perfectRateCount > 100) rateAcc += (perfectRateCount - 100);

        return new double[] {
                Math.round(beatAcc * 10.0) / 10.0,
                Math.round(rateAcc * 10.0) / 10.0
        };
    }

    /**
     * 【メソッドの役割】 スコアアップロードの成長記録（通常ログ）をサーバー側で作る。
     *
     * 成長記録はもともとフロントが upload のレスポンスを受け取ってから
     * {@code /save-history-log} を呼ぶことで作られていた。そのためレスポンスが
     * ブラウザに届かなかった場合（タイムアウト・通信断・再読込）、スコアだけ保存されて
     * 成長記録が残らず、最新ログを読む RATE-Tier ランキングも古い値で据え置かれていた。
     * そこで upload と同じトランザクションでこの行を先に作り、フロントの
     * {@code /save-history-log} は「この行を仕上げる」役に変えている。
     *
     * 集計規則は {@code ScoreController.saveHistoryLog} と揃える:
     *  - totalScore / クリア種別・DJ ランクのカウント … scores 全行をそのまま数える
     *  - BEAT / RATE-PT … {@link #calculatePtTotals}（譜面ごと最高スコアの上位 100）
     *
     * @param user         対象ユーザー
     * @param updatedSongs 今回の upload で更新された譜面の差分（サーバー側で組み立てたもの）
     * @return 作成したログ。スコアが 1 件も無いときは null
     */
    // 注意: REQUIRES_NEW にしない。upload と同一トランザクションに参加させることで、
    // 直前に保存した（まだコミット前の）スコアも含めて再計算できる。
    @Transactional
    public ScoreHistoryLog upsertUploadLog(User user, List<Map<String, Object>> updatedSongs, List<Score> loadedScores) {
        // upload から呼ばれるときは、その場で更新した Score をそのまま渡してもらう（再クエリを省く）。
        List<Score> allScores = (loadedScores != null && !loadedScores.isEmpty())
                ? loadedScores
                : scoreRepository.findByUserOrderByUploadedAtAsc(user);
        if (allScores.isEmpty()) return null;

        long totalScore = 0;
        int fcCount = 0, exhCount = 0, hCount = 0, clearCount = 0, easyCount = 0;
        int aaaCount = 0, aaCount = 0, aCount = 0;
        for (Score s : allScores) {
            if (s.getScore() != null) totalScore += s.getScore();
            if ("FULLCOMBO CLEAR".equals(s.getClearType())) fcCount++;
            if ("EX HARD CLEAR".equals(s.getClearType())) exhCount++;
            if ("HARD CLEAR".equals(s.getClearType())) hCount++;
            if ("CLEAR".equals(s.getClearType())) clearCount++;
            if ("EASY CLEAR".equals(s.getClearType())) easyCount++;
            if ("AAA".equals(s.getDjLevel())) aaaCount++;
            if ("AA".equals(s.getDjLevel())) aaCount++;
            if ("A".equals(s.getDjLevel())) aCount++;
        }

        double[] totals = calculatePtTotals(allScores, loadSongMaxScores(), loadInformalRanks());
        double base = scoreHistoryLogRepository.findFirstByUserOrderByUploadedAtDesc(user)
                .map(l -> l.getTotalBeatPt() != null ? l.getTotalBeatPt() : 0.0).orElse(0.0);

        ScoreHistoryLog log = new ScoreHistoryLog();
        log.setUser(user);
        log.setUploadedAt(LocalDateTime.now());
        log.setVersion(IidxVersions.current());
        log.setClientConfirmed(false); // フロントが上書きしたら true になる
        log.setTotalScore(totalScore);
        log.setFcCount(fcCount);
        log.setExhCount(exhCount);
        log.setHCount(hCount);
        log.setClearCount(clearCount);
        log.setEasyCount(easyCount);
        log.setAaaCount(aaaCount);
        log.setAaCount(aaCount);
        log.setACount(aCount);
        log.setTotalBeatPt(totals[0]);
        log.setBeatPtIncrease(Math.max(0.0, Math.round((totals[0] - base) * 10.0) / 10.0));
        log.setTotalRatePt(totals[1]);
        log.setTotalPrecisionPt(0.0);
        log.setUpdatedCount(updatedSongs != null ? updatedSongs.size() : 0);
        try {
            log.setDiffJson(objectMapper.writeValueAsString(updatedSongs != null ? updatedSongs : List.of()));
        } catch (Exception e) {
            log.setDiffJson("[]");
        }
        scoreHistoryLogRepository.save(log);

        // ランキング用キャッシュもここで更新しておく（フロントの save-history-log が来なくても正しい値になる）。
        user.setTotalBeatPt(totals[0]);
        userRepository.save(user);

        return log;
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

        // 手順3: 全ユーザーを走査して、各々を独立トランザクションで再計算
        List<User> users = userRepository.findAll();
        for (User user : users) {
            try {
                processUserRecalculation(user, songMaxScores, informalRanks);
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

        // 手順4: 既存の per-user 再計算ロジックに委譲。これが新規 ScoreHistoryLog を 1 件追加する。
        processUserRecalculation(user, songMaxScores, informalRanks);

        // 手順5: users.total_beat_pt キャッシュを追従させる（ランキング集計の高速化用）。
        scoreHistoryLogRepository.findFirstByUserOrderByUploadedAtDesc(user).ifPresent(log -> {
            if (log.getTotalBeatPt() != null) user.setTotalBeatPt(log.getTotalBeatPt());
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
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processUserRecalculation(User user, Map<String, Integer> songMaxScores, Map<String, String> informalRanks) {
        List<Score> rawScores = scoreRepository.findByUserOrderByUploadedAtAsc(user);
        if (rawScores.isEmpty()) return;

        // 同一譜面の行が複数ある場合は (曲, 難易度) ごとに EX SCORE が高い方だけを採用し、
        // 二重計上を防ぐ。同点は先着を維持。
        java.util.Map<String, Score> bestByChart = new java.util.LinkedHashMap<>();
        for (Score s : rawScores) {
            String key = s.getTitle() + " " + s.getDifficultyName();
            Score cur = bestByChart.get(key);
            int sv = s.getScore() != null ? s.getScore() : 0;
            int cv = (cur != null && cur.getScore() != null) ? cur.getScore() : -1;
            if (cur == null || sv > cv) bestByChart.put(key, s);
        }
        List<Score> scores = new ArrayList<>(bestByChart.values());

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
        for (int i = 0; i < Math.min(100, beatPts.size()); i++) {
            totalBeatPtAcc += beatPts.get(i);
        }
        double finalBeatPt = Math.round(totalBeatPtAcc * 10.0) / 10.0;

        ratePts.sort(Collections.reverseOrder());
        double totalRatePtAcc = 0;
        for (int i = 0; i < Math.min(100, ratePts.size()); i++) {
            totalRatePtAcc += ratePts.get(i);
        }
        if (perfectRateCount > 100) totalRatePtAcc += (perfectRateCount - 100);
        double finalRatePt = Math.round(totalRatePtAcc * 10.0) / 10.0;

        List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(user);
        double oldBeatPt = logs.isEmpty() ? 0 : (logs.get(logs.size() - 1).getTotalBeatPt() != null ? logs.get(logs.size() - 1).getTotalBeatPt() : 0);

        ScoreHistoryLog newLog = new ScoreHistoryLog();
        newLog.setUser(user);
        newLog.setUploadedAt(LocalDateTime.now());
        newLog.setVersion(IidxVersions.current());
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
