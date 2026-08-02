package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.BeatPtCalculator;
import com.beatseeker.backend.service.ScoreRecalculationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【クラスの役割】 連携アプリ（iidx-memo 等）の「曲一覧画面」向けに、トークン所有者の
 * 全譜面スコア概要を 1 リクエストで返す外部公開 API。
 *
 * 背景: 既存の {@link ExternalSongDetailController#getSongDetail} は 1 譜面 1 リクエストで、
 * 履歴（全 score_history_logs のスキャン）・譜面傾向・オプション投票まで毎回組み立てる。
 * 一覧表示のために ANOTHER/LEGGENDARIA 全譜面（約 2,100 件）を叩くと上流タイムアウトを
 * 起こすため、一覧に必要な項目だけを返す軽量な一括エンドポイントを分離した。
 * 曲詳細画面では引き続き song-detail を使う想定（本 API は置き換えではなく用途分割）。
 *
 * エンドポイント:
 *  - {@code GET /api/external/v1/score-summaries?difficulties=ANOTHER,LEGGENDARIA}
 *
 * 認証は song-detail と同一（{@code Authorization: Bearer <bs_live_xxx>} を
 * {@link com.beatseeker.backend.config.ApiTokenAuthFilter} が検証）。
 *
 * 設計方針:
 *  - <b>全件スナップショット</b>: 未プレイ譜面もスコア項目 null で返す。連携先は
 *    「返ってきた配列 = その難易度の全譜面」として差分マージ無しで扱える。
 *  - <b>クエリは 4 本のみ</b>: 譜面マスタ / ユーザースコア / 難易度表 / 最大スコア表。
 *    譜面数に比例したクエリは発行しない。
 *  - <b>アーケードの記録のみを返す</b>（{@code source = "arcade"}。source 未設定の
 *    レガシー行も arcade 扱い）。INFINITAS の記録は除外するため、その譜面のベストが
 *    INFINITAS 側にあるユーザーでは、アプリ内表示・総 BEAT-PT と値が食い違い得る。
 *  - BEAT-PT / RATE-PT は DB に持っていないため {@link BeatPtCalculator} でその場計算する
 *    （アップロード時の再集計と同じ計算式）。
 */
@RestController
@RequestMapping("/api/external/v1")
public class ExternalScoreSummaryController {

    private static final Logger log = LoggerFactory.getLogger(ExternalScoreSummaryController.class);

    /** difficulties 未指定時の既定値。一覧表示の主用途である A/L のみ。 */
    private static final String DEFAULT_DIFFICULTIES = "ANOTHER,LEGGENDARIA";

    /** 難易度名 → song_definitions.difficulty コード。song-detail と同じ対応表。 */
    private static final Map<String, String> DIFFICULTY_CODES = Map.of(
            "BEGINNER", "1",
            "NORMAL", "2",
            "HYPER", "3",
            "ANOTHER", "4",
            "LEGGENDARIA", "10");

    /** 集計対象外を表す clearType。この譜面は「未プレイ」として BEAT-PT / RATE-PT を null にする。 */
    private static final Set<String> UNPLAYED_CLEAR_TYPES = Set.of("---", "NO PLAY");

    /** 除外するスコア取得元。本 API はアーケードの記録だけを返す。 */
    private static final String EXCLUDED_SOURCE = "infinitas";

    private final UserRepository userRepository;
    private final ScoreRepository scoreRepository;
    private final SongDefinitionRepository songDefinitionRepository;
    private final ScoreRecalculationService scoreRecalculationService;
    private final BeatPtCalculator beatPtCalculator;

    public ExternalScoreSummaryController(UserRepository userRepository,
                                          ScoreRepository scoreRepository,
                                          SongDefinitionRepository songDefinitionRepository,
                                          ScoreRecalculationService scoreRecalculationService,
                                          BeatPtCalculator beatPtCalculator) {
        this.userRepository = userRepository;
        this.scoreRepository = scoreRepository;
        this.songDefinitionRepository = songDefinitionRepository;
        this.scoreRecalculationService = scoreRecalculationService;
        this.beatPtCalculator = beatPtCalculator;
    }

    /**
     * 【メソッドの役割】 指定難易度の全譜面について、トークン所有者の
     * <b>アーケード</b>スコア概要を一括で返す（INFINITAS の記録は含まない）。
     *
     * クエリパラメータ:
     *  - {@code difficulties} … カンマ区切りの難易度名（任意。既定 {@code "ANOTHER,LEGGENDARIA"}）
     *
     * ステータスコード:
     *  - 200: 正常応答（未プレイ譜面はスコア項目が null）
     *  - 400: difficulties に未知の難易度名が含まれる
     *  - 401: トークン不一致 / 失効 / 期限切れ（Security 側で返る）
     */
    @GetMapping("/score-summaries")
    public ResponseEntity<?> getScoreSummaries(
            Authentication auth,
            @RequestParam(required = false, defaultValue = DEFAULT_DIFFICULTIES) String difficulties) {

        // ── 1. パラメータ検証 ───────────────────────────────
        // 重複指定を潰しつつ入力順を保つ（レスポンスの difficulties にそのまま返す）。
        Set<String> difficultyNames = new LinkedHashSet<>();
        for (String raw : difficulties.split(",")) {
            String name = raw.trim().toUpperCase();
            if (name.isEmpty()) continue;
            if (!DIFFICULTY_CODES.containsKey(name)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Unknown difficulty",
                        "value", name,
                        "allowed", List.of("BEGINNER", "NORMAL", "HYPER", "ANOTHER", "LEGGENDARIA")));
            }
            difficultyNames.add(name);
        }
        if (difficultyNames.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Empty difficulties",
                    "allowed", List.of("BEGINNER", "NORMAL", "HYPER", "ANOTHER", "LEGGENDARIA")));
        }
        // コード → 難易度名の逆引き。譜面マスタは難易度をコードで持つため。
        Map<String, String> nameByCode = new LinkedHashMap<>();
        for (String name : difficultyNames) {
            nameByCode.put(DIFFICULTY_CODES.get(name), name);
        }

        // ── 2. トークン所有ユーザー特定 ─────────────────────
        User user = getUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        String stage = "init";
        try {
            // ── 3. 対象譜面マスタ（active・指定難易度のみ） ──────
            stage = "songDefinitions";
            Map<String, SongDefinition> songs = new LinkedHashMap<>();
            for (SongDefinition sd : songDefinitionRepository.findByRevision("active")) {
                if (!nameByCode.containsKey(sd.getDifficulty())) continue;
                // (title, difficulty) は一意である前提（song-detail が Optional で引いている）。
                // 万一重複していても先勝ちで 1 行に畳み、スナップショットの件数を安定させる。
                songs.putIfAbsent(chartKey(sd.getTitle(), sd.getDifficulty()), sd);
            }

            // ── 4. ユーザースコアを 1 クエリで取得し譜面単位に畳む ──
            // アーケードの記録のみ対象（INFINITAS は除外）。
            // 同一譜面に複数のアーケード行が残っている場合（difficultyLevel 違い等）は
            // EX スコアが高い方を採用する。processUserRecalculation の bestByChart と同じ畳み方。
            stage = "scores";
            Map<String, Score> bestByChart = new LinkedHashMap<>();
            for (Score s : scoreRepository.findByUserOrderByUploadedAtAsc(user)) {
                // source 未設定のレガシー行は arcade 扱い（アプリ内の集計と同じ扱い）。
                if (EXCLUDED_SOURCE.equals(s.getSource())) continue;
                String code = DIFFICULTY_CODES.get(normalizeDifficultyName(s.getDifficultyName()));
                if (code == null || !nameByCode.containsKey(code)) continue;
                String key = chartKey(s.getTitle(), code);
                Score cur = bestByChart.get(key);
                int sv = s.getScore() != null ? s.getScore() : 0;
                int cv = (cur != null && cur.getScore() != null) ? cur.getScore() : -1;
                if (cur == null || sv > cv) bestByChart.put(key, s);
            }

            // ── 5. BEAT-PT / RATE-PT 算出用マスタ（各 1 クエリ） ──
            stage = "masters";
            Map<String, Integer> songMaxScores = scoreRecalculationService.loadSongMaxScores();
            Map<String, String> informalRanks = scoreRecalculationService.loadInformalRanks();

            // ── 6. 全譜面ぶんの行を組み立てる ────────────────────
            stage = "build";
            List<Map<String, Object>> summaries = new ArrayList<>(songs.size());
            int playedCount = 0;
            for (Map.Entry<String, SongDefinition> entry : songs.entrySet()) {
                SongDefinition sd = entry.getValue();
                String difficultyName = nameByCode.get(sd.getDifficulty());
                Score score = bestByChart.get(entry.getKey());
                if (isPlayed(score)) playedCount++;
                summaries.add(buildSummaryRow(sd, difficultyName, score, songMaxScores, informalRanks));
            }
            // 連携先が差分比較しやすいよう、曲名 → 難易度の安定順で返す。
            summaries.sort(Comparator
                    .comparing((Map<String, Object> m) -> String.valueOf(m.get("title")))
                    .thenComparing(m -> String.valueOf(m.get("difficulty"))));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("user", Map.of(
                    "iidxId", user.getIidxId(),
                    "djName", user.getDisplayName() == null ? "" : user.getDisplayName()));
            body.put("difficulties", new ArrayList<>(difficultyNames));
            // 本 API が対象にしているスコア取得元。全行共通なので直下に 1 度だけ載せる。
            body.put("source", "arcade");
            body.put("generatedAt", LocalDateTime.now());
            body.put("count", summaries.size());
            body.put("playedCount", playedCount);
            body.put("summaries", summaries);

            log.info("score-summaries: user={} difficulties={} count={} played={}",
                    user.getIidxId(), difficultyNames, summaries.size(), playedCount);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            // song-detail と同じ方針: どの段階で落ちたかをログとレスポンス双方に残し、
            // 連携先と相互にデバッグできるようにする。
            log.error("score-summaries: unhandled exception at stage={} (user={} difficulties={})",
                    stage, user.getIidxId(), difficultyNames, e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Internal failure during score-summaries");
            body.put("stage", stage);
            body.put("exception", e.getClass().getSimpleName());
            body.put("message", e.getMessage() == null ? "" : e.getMessage());
            return ResponseEntity.status(500).body(body);
        }
    }

    // ── ヘルパ群 ────────────────────────────────────────────

    /**
     * 1 譜面ぶんの行を組み立てる。未プレイ（スコア行なし / clearType が NO PLAY 等）の場合、
     * スコア由来の項目はすべて null になる。
     *
     * @param sd             譜面マスタ
     * @param difficultyName 難易度名（"ANOTHER" 等）
     * @param score          採用したアーケードのスコア行。未登録なら null
     * @param songMaxScores  title_difficultyCode → 最大スコア（notes×2）
     * @param informalRanks  title_difficultyName → 非公式難易度ランク文字列
     */
    private Map<String, Object> buildSummaryRow(SongDefinition sd, String difficultyName, Score score,
                                                Map<String, Integer> songMaxScores,
                                                Map<String, String> informalRanks) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("textage", sd.getTextage());
        m.put("title", sd.getTitle());
        m.put("difficulty", difficultyName);
        m.put("level", sd.getLevel());
        m.put("informalRank", informalRanks.get(sd.getTitle() + "_" + difficultyName));

        boolean played = isPlayed(score);
        m.put("clearType", score == null ? null : score.getClearType());
        m.put("score", score == null ? null : score.getScore());
        m.put("djLevel", score == null ? null : score.getDjLevel());
        m.put("missCount", score == null ? null : score.getMissCount());

        // BEAT-PT / RATE-PT は「集計に載る譜面」だけ算出する。未プレイ・ノーツ数不明の譜面は null。
        Double beatPt = null;
        Double ratePt = null;
        if (played) {
            Integer maxScore = songMaxScores.get(sd.getTitle() + "_" + sd.getDifficulty());
            if (maxScore != null && maxScore > 0) {
                double scoreRate = (score.getScore() != null ? score.getScore() : 0) * 100.0 / maxScore;
                String informalRank = informalRanks.get(sd.getTitle() + "_" + difficultyName);

                // BEAT-PT: 公式 Lv11 以上の HYPER は対象外（再集計側の isHyperNonTarget と同じ判定）。
                Integer level = score.getDifficultyLevel() != null ? score.getDifficultyLevel() : sd.getLevel();
                boolean hyperNonTarget = "HYPER".equals(difficultyName) && level != null && level >= 11;
                if (!hyperNonTarget) {
                    beatPt = round2(beatPtCalculator.calculatePoints(scoreRate, informalRank));
                }

                // RATE-PT: ANOTHER / LEGGENDARIA のみ対象。
                boolean rateEligible = "ANOTHER".equals(difficultyName) || "LEGGENDARIA".equals(difficultyName);
                if (rateEligible && scoreRate > 0) {
                    ratePt = round2(beatPtCalculator.calculateScoreRateTierPoints(scoreRate));
                }
            }
        }
        m.put("beatPt", beatPt);
        m.put("ratePt", ratePt);

        // source は全行 "arcade" で一定なので、行ごとには持たずレスポンス直下で 1 度だけ宣言する。
        m.put("updatedAt", score == null ? null : score.getUploadedAt());
        return m;
    }

    /** スコア行が「プレー済み」か。行が無い / clearType が NO PLAY・"---" の場合は未プレイ扱い。 */
    private boolean isPlayed(Score score) {
        return score != null && !UNPLAYED_CLEAR_TYPES.contains(score.getClearType());
    }

    /** 譜面の一意キー。scores（難易度名）と song_definitions（難易度コード）をコード側で突き合わせる。 */
    private String chartKey(String title, String difficultyCode) {
        return title + " " + difficultyCode;
    }

    /** scores.difficulty_name の表記ゆれ（小文字・前後空白）を吸収する。 */
    private String normalizeDifficultyName(String difficultyName) {
        return difficultyName == null ? null : difficultyName.trim().toUpperCase();
    }

    /** 浮動小数の桁ノイズを避けるため小数第 2 位で丸める（表示用の丸めは連携先の裁量）。 */
    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 認証情報からトークン所有ユーザーを引く。song-detail と同じ扱い。 */
    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId).orElse(null);
    }
}
