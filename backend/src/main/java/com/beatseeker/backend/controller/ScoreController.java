package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.ActivityLog;
import com.beatseeker.backend.entity.AppNotification;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.TimelineEvent;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.entity.UserSongRank;
import com.beatseeker.backend.entity.VirtualRival;
import com.beatseeker.backend.repository.ActivityLogRepository;
import com.beatseeker.backend.repository.AppNotificationRepository;
import com.beatseeker.backend.repository.FriendshipRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.beatseeker.backend.repository.TimelineEventRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.repository.UserSongRankRepository;
import com.beatseeker.backend.repository.VirtualRivalRepository;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.service.EmailService;
import com.beatseeker.backend.service.PushNotificationService;
import com.beatseeker.backend.service.ScoreRecalculationService;
import com.beatseeker.backend.service.SongArenaAveragesCacheService;
import com.beatseeker.backend.service.SongRankBatchService;
import com.beatseeker.backend.service.SongRankingAggregateCacheService;
import com.beatseeker.backend.service.TopRankersBeatPtService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【クラスの役割】 スコアの登録・閲覧・ランキング・履歴・曲別分析を集約する REST コントローラ。
 *
 * 本アプリ最大級のコントローラで、eagate CSV 由来の成績をアップロードする中核 API と、
 * そこから派生する多数の集計・ランキング・予測系エンドポイントを抱える。
 *
 * 責務:
 *  - スコア upsert（/upload）: 新規 or ベスト更新時のみ Score を書き換え、差分を返す
 *  - 履歴ログ（/save-history-log）: フロントで集計した差分 JSON をスナップショットに保存
 *  - 個別スコア取得（/me）・履歴取得（/history）
 *  - 大域 / ARENA 平均 / 精度 / RATE / トップランカー等の各種ランキング
 *  - 曲単位のランキング・平均スコアレート・傾向分析用データ
 *  - メモ編集、フレンドのスコア追い抜き通知、ランクアップ通知
 *
 * 依存:
 *  - {@link ScoreRepository}: 最新スコア
 *  - {@link ScoreHistoryLogRepository}: スナップショット（BEAT-PT 履歴）
 *  - {@link FriendshipRepository}: 通知先・スコア可視範囲計算
 *  - {@link AppNotificationRepository} / {@link ActivityLogRepository}: アプリ内通知・アクティビティ
 *  - {@link PushNotificationService}: Web Push 送信
 *  - {@link UserSongRankRepository} / {@link SongRankBatchService}: 曲別ランクの事前計算キャッシュ
 *  - {@link ScoreRecalculationService}: 再計算系
 *  - {@link EmailService}: 管理者向けメール通知
 *  - {@link SongDefinitionRepository}: 曲の正規データ（notes など）
 *  - {@link TopRankersBeatPtService}: トップランカー／エリアプロファイル
 *
 * 主なエンドポイント:
 *  - POST /api/scores/upload
 *  - POST /api/scores/save-history-log
 *  - GET  /api/scores/me / /history
 *  - GET  /api/scores/ranking, /ranking/*, /rate-ranking/*
 *  - GET  /api/scores/song-ranking, /my-song-ranks, /song-history, /song-avg-score-rates
 */
@RestController
@RequestMapping("/api/scores")
public class ScoreController {

    /** スコア本体のリポジトリ。曲単位の最新ベスト値を保持。 */
    private final ScoreRepository scoreRepository;
    /** ユーザーリポジトリ。iidxId 解決・プロフィール取得に使う。 */
    private final UserRepository userRepository;
    /** スコアスナップショット履歴リポジトリ（BEAT-PT 推移の元データ）。 */
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;
    /** 双方向フレンド関係リポジトリ。ランキング可視範囲や通知先の計算に使う。 */
    private final FriendshipRepository friendshipRepository;
    /** アプリ内通知（ベル通知）を保存するリポジトリ。 */
    private final AppNotificationRepository appNotificationRepository;
    /** ランクアップ等のアクティビティログを保存するリポジトリ。 */
    private final ActivityLogRepository activityLogRepository;
    /** Web Push の送信サービス。 */
    private final PushNotificationService pushNotificationService;
    /** ユーザー × 曲 のランキングキャッシュテーブル。 */
    private final UserSongRankRepository userSongRankRepository;
    /** UserSongRank を一括再計算するバッチサービス。 */
    private final SongRankBatchService songRankBatchService;
    /** BEAT-PT/RATE-PT の再計算サービス。 */
    private final ScoreRecalculationService scoreRecalculationService;
    /** 管理者向けメール送信サービス。 */
    private final EmailService emailService;
    /** 曲の正規定義（title, difficulty, level, notes 等）リポジトリ。 */
    private final SongDefinitionRepository songDefinitionRepository;
    /** トップランカー BEAT-PT/RATE-PT ランキングのキャッシュサービス。 */
    private final TopRankersBeatPtService topRankersBeatPtService;
    /** ティア別平均スコア（song-arena-averages）の集計結果キャッシュ。 */
    private final SongArenaAveragesCacheService songArenaAveragesCacheService;
    /** 人気曲ランキング（song-ranking-aggregate）の集計結果キャッシュ。 */
    private final SongRankingAggregateCacheService songRankingAggregateCacheService;
    /** 連携アプリ（iidx-memo 等）から同期された譜面オプション。スコア応答に options を埋めるのに使う。 */
    private final com.beatseeker.backend.repository.UserSongOptionRepository userSongOptionRepository;
    /** タイムライン用イベント（スコア更新／フレンド・仮想ライバル抜き）を保存するリポジトリ。 */
    private final TimelineEventRepository timelineEventRepository;
    /** 仮想ライバル登録の取得に使う。タイムラインで「自分が抜いた仮想ライバル」を判定するため。 */
    private final VirtualRivalRepository virtualRivalRepository;
    /** diffJson など JSON 文字列を List/Map に復元するための Jackson。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 管理者判定のロジックを共通化した Service（id / iidxId どちらでも判定可能）。 */
    private final com.beatseeker.backend.service.AdminAuthService adminAuthService;

    /**
     * 【コンストラクタ】 Spring DI で全依存を受け取る。
     */
    public ScoreController(ScoreRepository scoreRepository, UserRepository userRepository,
            ScoreHistoryLogRepository scoreHistoryLogRepository,
            FriendshipRepository friendshipRepository,
            AppNotificationRepository appNotificationRepository,
            ActivityLogRepository activityLogRepository,
            PushNotificationService pushNotificationService,
            UserSongRankRepository userSongRankRepository,
            SongRankBatchService songRankBatchService,
            ScoreRecalculationService scoreRecalculationService,
            EmailService emailService,
            SongDefinitionRepository songDefinitionRepository,
            TopRankersBeatPtService topRankersBeatPtService,
            SongArenaAveragesCacheService songArenaAveragesCacheService,
            SongRankingAggregateCacheService songRankingAggregateCacheService,
            com.beatseeker.backend.repository.UserSongOptionRepository userSongOptionRepository,
            TimelineEventRepository timelineEventRepository,
            VirtualRivalRepository virtualRivalRepository,
            com.beatseeker.backend.service.AdminAuthService adminAuthService) {
        this.scoreRepository = scoreRepository;
        this.userRepository = userRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.friendshipRepository = friendshipRepository;
        this.appNotificationRepository = appNotificationRepository;
        this.activityLogRepository = activityLogRepository;
        this.pushNotificationService = pushNotificationService;
        this.userSongRankRepository = userSongRankRepository;
        this.songRankBatchService = songRankBatchService;
        this.scoreRecalculationService = scoreRecalculationService;
        this.emailService = emailService;
        this.songDefinitionRepository = songDefinitionRepository;
        this.topRankersBeatPtService = topRankersBeatPtService;
        this.songArenaAveragesCacheService = songArenaAveragesCacheService;
        this.songRankingAggregateCacheService = songRankingAggregateCacheService;
        this.userSongOptionRepository = userSongOptionRepository;
        this.timelineEventRepository = timelineEventRepository;
        this.virtualRivalRepository = virtualRivalRepository;
        this.adminAuthService = adminAuthService;
    }

    /**
     * 【メソッドの役割】 指定ユーザーの全 UserSongOption を (title, difficultyName) キーの
     * Map に展開し、options 配列に変換して返す。スコア応答ビルダから 1 回だけ呼ぶことで
     * N+1 を回避する。
     *
     * 値の取得失敗（壊れた JSON 等）は空配列にフォールバックする。
     *
     * @param user 対象ユーザー
     * @return キー = {@code "<title>||<difficultyName>"}、値 = options 配列
     */
    private Map<String, List<String>> loadOptionsMap(User user) {
        Map<String, List<String>> result = new HashMap<>();
        for (com.beatseeker.backend.entity.UserSongOption o : userSongOptionRepository.findByUser(user)) {
            String key = optionsKey(o.getTitle(), o.getDifficultyName());
            List<String> parsed;
            try {
                parsed = objectMapper.readValue(
                        o.getOptionsJson() == null ? "[]" : o.getOptionsJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            } catch (Exception e) {
                parsed = List.of();
            }
            result.put(key, parsed);
        }
        return result;
    }

    /** {@link #loadOptionsMap(User)} と整合する複合キー組み立て。 */
    private String optionsKey(String title, String difficultyName) {
        return (title == null ? "" : title) + "||" + (difficultyName == null ? "" : difficultyName);
    }

    /**
     * 【メソッドの役割】 現在ユーザーのスコアを upsert し、更新差分リストを返す。
     *
     * 処理の流れ:
     *  1. 既存スコアを一括取得して lookup Map を作る（曲+難易度+レベルをキー）。
     *  2. リクエスト毎に新規 or 更新判定。ベストスコア/ベストクリア/ベスト BP いずれかが
     *     改善した場合のみ Score を書き換える（ゲーム実機の「ベスト記録」挙動を模倣）。
     *  3. 差分があれば updatedSongs に積み、最終的にフレンドの通知処理を回す。
     *
     * @param auth     認証情報
     * @param requests フロントから送られる ScoreUploadRequest のリスト
     * @return 更新件数・差分リスト・メッセージを含む Map
     */
    @PostMapping("/upload")
    @Transactional
    public ResponseEntity<Map<String, Object>> uploadScores(
            Authentication auth,
            @RequestBody List<ScoreUploadRequest> requests) {

        User user = getUser(auth);

        // 手順0: INFINITAS のみ収録された曲は arcade マスタに存在しない。受け入れ可否を判定するため、
        // active リビジョンの SongDefinition を (title|difficultyName) でセット化しておく。
        // arcade ソースのスコアは従来通り無条件で受け入れる（マスタ未登録曲は新リリース時にあり得る）。
        java.util.Set<String> arcadeChartKeys = new java.util.HashSet<>();
        for (SongDefinition sd : songDefinitionRepository.findByRevision("active")) {
            String diffName = mapDifficultyCodeToName(sd.getDifficulty());
            if (diffName == null) continue;
            arcadeChartKeys.add(sd.getTitle() + "||" + diffName);
        }

        // 手順1: 既存スコアを 1 クエリで読み込み、キー文字列で O(1) lookup 可能にする。
        // 取得元（source）が異なれば別レコードとして並走するので、キーに source も含める。
        List<Score> existingScores = scoreRepository.findByUserOrderByUploadedAtAsc(user);
        Map<String, Score> scoreMap = new HashMap<>();
        for (Score s : existingScores) {
            String sourceKey = s.getSource() != null ? s.getSource() : "arcade";
            String key = s.getTitle() + "_" + s.getDifficultyName() + "_" + s.getDifficultyLevel() + "_" + sourceKey;
            scoreMap.put(key, s);
        }

        List<Map<String, Object>> updatedSongs = new java.util.ArrayList<>();
        int skippedInfinitasOnly = 0;

        for (ScoreUploadRequest req : requests) {
            String source = req.effectiveSource();

            // INFINITAS 由来のレコードについては、arcade マスタに存在しない曲を保存対象から除外する。
            // OCR フェーズで似た arcade 曲に誤マッチした場合、ここで安全側に倒す保険でもある。
            if ("infinitas".equals(source)
                    && !arcadeChartKeys.contains(req.title() + "||" + req.difficultyName())) {
                skippedInfinitasOnly++;
                continue;
            }

            String key = req.title() + "_" + req.difficultyName() + "_" + req.difficultyLevel() + "_" + source;
            Score existing = scoreMap.get(key);

            boolean isImproved = false;
            int oldScore = 0;
            String oldClearType = "NO PLAY";

            if (existing == null) {
                // 新規スコア: 既存行が無いので必ず保存する。
                isImproved = true;
                Score newScore = new Score();
                newScore.setUser(user);
                newScore.setSource(source);
                updateScoreFields(newScore, req);
                scoreRepository.save(newScore);
                // 同一ペイロード内に同じ譜面が複数回含まれた場合、2 件目以降を新規 INSERT して
                // 重複行を作らないよう、保存した行を lookup Map に登録しておく（以降は更新判定に回る）。
                scoreMap.put(key, newScore);
            } else {
                // 既存スコアとの比較用に各値を null セーフに取り出す。
                oldScore = existing.getScore() != null ? existing.getScore() : 0;
                oldClearType = existing.getClearType() != null ? existing.getClearType() : "NO PLAY";

                // ミスカウントは「小さいほど優秀」なので、null は最悪値（MAX_VALUE）に置換して比較する。
                int oldMiss = existing.getMissCount() != null ? existing.getMissCount() : Integer.MAX_VALUE;
                int newMiss = req.missCount() != null ? req.missCount() : Integer.MAX_VALUE;

                // クリア種別は文字列なので順序付きの整数（rank）に変換して比較。
                int oldRank = getClearTypeRank(oldClearType);
                int newRank = getClearTypeRank(req.clearType());

                boolean scoreBetter = req.score() > oldScore;
                boolean rankBetter = newRank > oldRank;
                boolean missBetter = newMiss < oldMiss;

                // IIDX 実機は「ベストスコア / ベストクリア / ベスト BP」を個別に保持するが、
                // このアプリでは 1 レコードで集約するため、いずれかが改善していたら一括更新する。
                if (scoreBetter || rankBetter || missBetter) {
                    isImproved = true;
                    updateScoreFields(existing, req);
                    scoreRepository.save(existing);
                }
            }

            // 差分が存在する曲のみ updatedSongs に記録。後続のフロント差分表示・通知に使う。
            if (isImproved) {
                Map<String, Object> diff = new HashMap<>();
                diff.put("title", req.title());
                diff.put("difficulty", req.difficultyName());
                diff.put("oldScore", oldScore);
                diff.put("newScore", req.score());
                // 負の差分が出ないよう Math.max で 0 にクランプする。
                diff.put("scoreIncrease", Math.max(0, req.score() - oldScore));
                diff.put("oldClearType", oldClearType);
                diff.put("newClearType", req.clearType());
                diff.put("clearTypeImproved", getClearTypeRank(req.clearType()) > getClearTypeRank(oldClearType));
                // 取得元（arcade/infinitas）を残す。INFINITAS 由来の更新だけを日次 INF 履歴へ集約する判定に使う。
                diff.put("source", source);
                updatedSongs.add(diff);
            }
        }

        // 1 曲でも更新された場合に限り、最終アップロード日時の更新とフレンド通知を行う。
        if (!updatedSongs.isEmpty()) {
            updateLastUploadTime(user);
            notifyFriendsOfScoreBeat(user, updatedSongs);

            // INFINITAS 由来の更新があれば、その日 1 レコードの成長記録（tag=INFINITAS）へ集約する。
            // 画面取り込みは 1 曲ずつ届くため、同日内は既存ログに曲を追記・各 PT を再計算する upsert。
            List<Map<String, Object>> infUpdated = updatedSongs.stream()
                    .filter(d -> "infinitas".equals(d.get("source")))
                    .collect(java.util.stream.Collectors.toList());
            if (!infUpdated.isEmpty()) {
                try {
                    scoreRecalculationService.upsertDailyInfinitasLog(user, infUpdated);
                } catch (Exception e) {
                    // 履歴集約の失敗はスコア保存自体を巻き戻さない（成長記録は事後再計算で救済可能）。
                    System.err.println("Failed to upsert daily INFINITAS history log: " + e.getMessage());
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("updatedCount", updatedSongs.size());
        response.put("updatedSongs", updatedSongs);
        response.put("skippedInfinitasOnly", skippedInfinitasOnly);
        response.put("message", "スコアを更新しました");
        return ResponseEntity.ok(response);
    }

    /**
     * 【メソッドの役割】 SongDefinition の難易度コード（文字列）を、Score/CSV 側の難易度名に変換する。
     *
     * 既知のコード:
     *  - "1" → BEGINNER, "2" → NORMAL, "3" → HYPER, "4" → ANOTHER, "10" → LEGGENDARIA
     * 未知のコードは null を返す（呼び出し側はマスタから除外する判断材料に使う）。
     */
    private String mapDifficultyCodeToName(String code) {
        if (code == null) return null;
        return switch (code) {
            case "1" -> "BEGINNER";
            case "2" -> "NORMAL";
            case "3" -> "HYPER";
            case "4" -> "ANOTHER";
            case "10" -> "LEGGENDARIA";
            default -> null;
        };
    }

    /**
     * 【メソッドの役割】 User.lastUploadedAt に現在時刻を記録する内部ヘルパー。
     *
     * @param user 更新対象ユーザー
     */
    private void updateLastUploadTime(User user) {
        user.setLastUploadedAt(java.time.LocalDateTime.now());
        userRepository.save(user);
    }

    /**
     * 【メソッドの役割】 フロントで計算済みのスナップショット情報を score_history_logs に保存する。
     *
     * 処理の流れ:
     *  1. 対象ユーザーのスコアを取得し、1 件もなければ 400 を返す。
     *  2. リクエストの BEAT-PT / RATE-PT / 差分 JSON 等を ScoreHistoryLog に詰める。
     *     RATE-PT が 0 以下（フロント未計算）の場合はバックエンドで再計算する。
     *  3. クリア種別・DJ ランクの各カウントを集計して保存。
     *  4. users.total_beat_pt のキャッシュ更新、ランクアップ通知、管理者メール送信を行う。
     *
     * @param auth 認証情報
     * @param req  スナップショット内容（BEAT-PT・差分 JSON・ティア名など）
     * @return 成功メッセージ。スコアが空なら 400
     */
    @PostMapping("/save-history-log")
    @Transactional
    public ResponseEntity<Map<String, Object>> saveHistoryLog(
            Authentication auth,
            @RequestBody SaveHistoryLogRequest req) {

        User user = getUser(auth);
        // 手順1: スコアが 1 件もない状態でのスナップショット保存は意味がないので弾く。
        List<Score> allScores = scoreRepository.findByUserOrderByUploadedAtAsc(user);
        if (allScores.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "No scores found to snapshot"));
        }

        // 手順2: フロントで集計済みの値を ScoreHistoryLog に詰める。
        ScoreHistoryLog log = new ScoreHistoryLog();
        log.setUser(user);
        log.setUploadedAt(java.time.LocalDateTime.now());
        log.setTotalBeatPt(req.totalBeatPt());
        log.setBeatPtIncrease(req.beatPtIncrease());
        log.setUpdatedCount(req.updatedCount());
        log.setDiffJson(req.diffJson());
        log.setTotalPrecisionPt(req.totalPrecisionPt() != null ? req.totalPrecisionPt() : 0.0);
        double totalRatePt = req.totalRatePt() != null ? req.totalRatePt() : 0.0;
        // フロント未計算・未送信（0 以下）の場合はバックエンド側で active 曲データから再計算。
        if (totalRatePt <= 0) {
            totalRatePt = scoreRecalculationService.calculateRatePtFromActiveData(allScores);
        }
        log.setTotalRatePt(totalRatePt);

        // KENBAN-PT / SARA-PT もバックエンドで一括算出してログに保存（フロントは送ってこない）。
        try {
            var songMaxScores = scoreRecalculationService.loadSongMaxScores();
            var informalRanks = scoreRecalculationService.loadInformalRanks();
            var scratchMap    = scoreRecalculationService.loadScratchMap();
            double[] kenbanSara = scoreRecalculationService.calculateKenbanSaraPtFromActiveData(
                allScores, songMaxScores, informalRanks, scratchMap);
            log.setTotalKenbanPt(kenbanSara[0]);
            log.setTotalSaraPt(kenbanSara[1]);
            user.setTotalKenbanPt(kenbanSara[0]);
            user.setTotalSaraPt(kenbanSara[1]);
        } catch (Exception e) {
            // 失敗してもメイン保存処理は続行（KENBAN/SARA は事後再計算で救済可能）。
            System.err.println("Failed to compute KENBAN/SARA-PT in saveHistoryLog: " + e.getMessage());
        }

        // 手順3: スコア全件を 1 周して totalScore と各クリア種別・DJ ランクのカウントを集計する。
        long totalScore = 0;
        int fcCount = 0;
        int exhCount = 0;
        int hCount = 0;
        int clearCount = 0;
        int easyCount = 0;
        int aaaCount = 0;
        int aaCount = 0;
        int aCount = 0;

        for (Score s : allScores) {
            // totalScore は null スキップし加算。
            if (s.getScore() != null)
                totalScore += s.getScore();
            // クリア種別は排他なので各カウンタを if の羅列でインクリメント（switch より可読）。
            if ("FULLCOMBO CLEAR".equals(s.getClearType()))
                fcCount++;
            if ("EX HARD CLEAR".equals(s.getClearType()))
                exhCount++;
            if ("HARD CLEAR".equals(s.getClearType()))
                hCount++;
            if ("CLEAR".equals(s.getClearType()))
                clearCount++;
            if ("EASY CLEAR".equals(s.getClearType()))
                easyCount++;
            // DJ ランクも同様。B 以下は集計対象外。
            if ("AAA".equals(s.getDjLevel()))
                aaaCount++;
            if ("AA".equals(s.getDjLevel()))
                aaCount++;
            if ("A".equals(s.getDjLevel()))
                aCount++;
        }

        log.setTotalScore(totalScore);
        log.setFcCount(fcCount);
        log.setExhCount(exhCount);
        log.setHCount(hCount);
        log.setClearCount(clearCount);
        log.setEasyCount(easyCount);
        log.setAaaCount(aaaCount);
        log.setAaCount(aaCount);
        log.setACount(aCount);

        scoreHistoryLogRepository.save(log);

        // 手順4a-pre: タイムライン用のイベントを発行する。
        // user.totalBeatPt は次の手順 4a でキャッシュ更新されるため、ここでは old/new を引数で受け取って処理する。
        double newTotalBeatPt = req.totalBeatPt() != null ? req.totalBeatPt() : 0.0;
        double increase = req.beatPtIncrease() != null ? req.beatPtIncrease() : 0.0;
        double oldTotalBeatPt = newTotalBeatPt - increase;
        recordTimelineEvents(user, req.diffJson(), oldTotalBeatPt, newTotalBeatPt);

        // 手順4a: ユーザーの totalBeatPt を users テーブルにキャッシュ（ティア別平均クエリを高速化するため）。
        if (req.totalBeatPt() != null) {
            user.setTotalBeatPt(req.totalBeatPt());
            // ランキング行の INF バッジ用フラグ。フロントが top-100(BEAT/RATE) から算出して送る。
            if (req.includesInfinitas() != null) user.setRankingIncludesInfinitas(req.includesInfinitas());
            userRepository.save(user);
        }

        // 手順4b: ティア名が変化していればランクアップとみなし、フレンド通知 + アクティビティログを残す。
        if (req.tierName() != null && req.prevTierName() != null
                && !req.tierName().equals(req.prevTierName())) {
            notifyFriendsOfRankUp(user, req.prevTierName(), req.tierName());
            ActivityLog activity = new ActivityLog();
            activity.setUser(user);
            activity.setType("RANK_UP");
            activity.setOldValue(req.prevTierName());
            activity.setNewValue(req.tierName());
            activityLogRepository.save(activity);
        }

        // 手順4c: 管理者以外のユーザーがスコア更新した場合、管理者にメール通知する。
        // diffJson が空だと通知する意味がないので早期スキップ。
        if (!adminAuthService.isAdmin(user) && req.diffJson() != null && !req.diffJson().isBlank()) {
            userRepository.findById(adminAuthService.getAdminUserId()).ifPresent(admin -> {
                if (admin.getEmail() != null) {
                    try {
                        // diffJson を List<Map<String,Object>> にパース（TypeReference で型情報を保持）。
                        List<Map<String, Object>> diffs = objectMapper.readValue(
                                req.diffJson(), new TypeReference<>() {});
                        emailService.sendScoreUpdateNotification(
                                admin.getEmail(),
                                // 表示名が空の場合は iidxId をフォールバックに使う。
                                user.getDisplayName() != null ? user.getDisplayName() : user.getIidxId(),
                                user.getIidxId() != null ? user.getIidxId() : "",
                                diffs,
                                req.totalBeatPt() != null ? req.totalBeatPt() : 0.0,
                                req.beatPtIncrease() != null ? req.beatPtIncrease() : 0.0,
                                req.tierName(),
                                req.prevTierName());
                    } catch (Exception e) {
                        // メール送信失敗はスコア保存の正当性を損ねないので、標準エラーに記録するのみ。
                        System.err.println("Failed to parse diffJson for email: " + e.getMessage());
                    }
                }
            });
        }

        return ResponseEntity.ok(Map.of("message", "History log saved"));
    }

    /**
     * 【メソッドの役割】 Score エンティティのフィールドを ScoreUploadRequest から一括で更新する内部ヘルパー。
     *
     * 新規／更新のどちらのパスからも呼ばれるため、ここではフィールド設定のみを行い
     * save() は呼び出し側で行う。uploadedAt は現在時刻で上書きする。
     *
     * @param score 更新対象の Score
     * @param req   リクエスト DTO
     */
    private void updateScoreFields(Score score, ScoreUploadRequest req) {
        score.setTitle(req.title());
        score.setArtist(req.artist());
        score.setGenre(req.genre());
        score.setDifficultyName(req.difficultyName());
        score.setDifficultyLevel(req.difficultyLevel());
        score.setScore(req.score());
        score.setClearType(req.clearType());
        score.setDjLevel(req.djLevel());
        score.setPgreat(req.pgreat());
        score.setGreat(req.great());
        score.setMissCount(req.missCount());
        score.setPlayCount(req.playCount());
        score.setUploadedAt(java.time.LocalDateTime.now());
    }

    /**
     * 【メソッドの役割】 クリア種別文字列を順序比較できる整数 rank に変換する。
     *
     * FULLCOMBO → EX HARD → HARD → CLEAR → EASY → ASSIST → FAILED → NO PLAY の順で
     * 大きいほど優秀とする慣習に従う。未知の値は 0 を返す。
     *
     * @param clearType "FULLCOMBO CLEAR" 等のクリア種別文字列
     * @return 0〜7 の整数 rank
     */
    private int getClearTypeRank(String clearType) {
        if (clearType == null)
            return 0;
        return switch (clearType) {
            case "FULLCOMBO CLEAR" -> 7;
            case "EX HARD CLEAR" -> 6;
            case "HARD CLEAR" -> 5;
            case "CLEAR" -> 4;
            case "EASY CLEAR" -> 3;
            case "ASSIST CLEAR" -> 2;
            case "FAILED" -> 1;
            default -> 0;
        };
    }

    /**
     * 【メソッドの役割】 ログインユーザーの全スコアを Map リストで返す。
     *
     * マイページ・スコア一覧画面の主要データソース。null は空文字 / 0 に置換する。
     *
     * @param auth 認証情報
     * @return スコア Map の List
     */
    @GetMapping("/me")
    public ResponseEntity<List<Map<String, Object>>> getMyScores(
            Authentication auth) {

        User user = getUser(auth);

        // 取得元（arcade / infinitas）の表示トグルに応じてフィルタする。
        // 未設定は true 扱い（既存ユーザーが取りこぼされないようにする）。
        boolean showArcade = user.getShowArcadeScores() == null || user.getShowArcadeScores();
        boolean showInfinitas = user.getShowInfinitasScores() == null || user.getShowInfinitasScores();

        List<Score> scores = scoreRepository.findByUserOrderByUploadedAtAsc(user);
        Map<String, List<String>> optionsMap = loadOptionsMap(user);

        List<Map<String, Object>> result = scores.stream()
            .filter(s -> {
                String src = s.getSource() != null ? s.getSource() : "arcade";
                if ("infinitas".equals(src)) return showInfinitas;
                return showArcade; // arcade（既定）として扱う
            })
            .map(s -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", s.getId());
                map.put("title", s.getTitle() != null ? s.getTitle() : "");
                map.put("difficultyName", s.getDifficultyName() != null ? s.getDifficultyName() : "");
                map.put("difficultyLevel", s.getDifficultyLevel() != null ? s.getDifficultyLevel() : 0);
                map.put("score", s.getScore() != null ? s.getScore() : 0);
                map.put("clearType", s.getClearType() != null ? s.getClearType() : "");
                map.put("djLevel", s.getDjLevel() != null ? s.getDjLevel() : "");
                map.put("pgreat", s.getPgreat() != null ? s.getPgreat() : 0);
                map.put("great", s.getGreat() != null ? s.getGreat() : 0);
                map.put("missCount", s.getMissCount());
                map.put("source", s.getSource() != null ? s.getSource() : "arcade");
                map.put("options", optionsMap.getOrDefault(optionsKey(s.getTitle(), s.getDifficultyName()), List.of()));
                return map;
            }).toList();

        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 ログインユーザーのスナップショット履歴を時系列順で返す。
     *
     * BEAT-PT 推移グラフ、クリア種別件数推移、差分 JSON のドリルダウン等に利用される。
     *
     * @param auth 認証情報
     * @return スナップショット Map の List（uploadedAt 昇順）
     */
    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> getHistory(
            Authentication auth) {

        User user = getUser(auth);
        List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(user);

        List<Map<String, Object>> history = new java.util.ArrayList<>();

        for (ScoreHistoryLog log : logs) {
            Map<String, Object> snapshotData = new HashMap<>();
            snapshotData.put("snapshotId", log.getId().toString()); // ID をスナップショット ID として流用
            snapshotData.put("date", log.getUploadedAt().toString());
            snapshotData.put("totalScore", log.getTotalScore());
            snapshotData.put("fcCount", log.getFcCount());
            snapshotData.put("exhCount", log.getExhCount());
            snapshotData.put("hCount", log.getHCount());
            snapshotData.put("clearCount", log.getClearCount());
            snapshotData.put("easyCount", log.getEasyCount());
            snapshotData.put("aaaCount", log.getAaaCount());
            snapshotData.put("aaCount", log.getAaCount());
            snapshotData.put("aCount", log.getACount());

            // 新フィールド（BEAT-PT / RATE-PT 系）。履歴画面の主要指標。
            snapshotData.put("totalBeatPt", log.getTotalBeatPt());
            snapshotData.put("beatPtIncrease", log.getBeatPtIncrease());
            snapshotData.put("updatedCount", log.getUpdatedCount());
            snapshotData.put("diffJson", log.getDiffJson());
            snapshotData.put("totalRatePt", log.getTotalRatePt());
            // 記録の種別タグ（"INFINITAS" など）。成長記録ページの「INF」バッジ表示に使う。null は無印。
            snapshotData.put("tag", log.getTag());

            history.add(snapshotData);
        }

        return ResponseEntity.ok(history);
    }

    // 開発用デバッグエンドポイント /debug-ranking と /debug-user-scores/{userId} は
    // 認証・privacyLevel チェックを一切持たず、非公開ユーザーのスコア・識別情報を
    // 匿名公開していたため削除した（SecurityConfig の permitAll リストからも除外済み）。

    /**
     * 【メソッドの役割】 全ユーザーの BEAT-PT グローバルランキングを返す。
     *
     * @return BEAT-PT 降順のランキング行 List
     */
    @GetMapping("/ranking")
    public ResponseEntity<List<Map<String, Object>>> getGlobalRanking() {
        List<Map<String, Object>> ranking = scoreHistoryLogRepository.getGlobalRanking();
        return ResponseEntity.ok(ranking);
    }

    /**
     * 【メソッドの役割】 ARENA ランクごとの BEAT-PT 平均を返す。
     *
     * ARENA クラス（A1〜SS 等）別の実力感覚を可視化するのに使う。
     */
    @GetMapping("/ranking/arena-averages")
    public ResponseEntity<List<Map<String, Object>>> getArenaRankAverages() {
        List<Map<String, Object>> averages = scoreHistoryLogRepository.getArenaRankAverageBeatPt();
        return ResponseEntity.ok(averages);
    }

    /**
     * 【メソッドの役割】 KONAMI 公式のエリア別トップランカー BEAT-PT ランキングを返す（キャッシュ）。
     */
    @GetMapping("/ranking/top-rankers")
    public ResponseEntity<List<Map<String, Object>>> getTopRankersBeatPt() {
        return ResponseEntity.ok(topRankersBeatPtService.getRanking());
    }

    /**
     * 【メソッドの役割】 指定した非公式難易度（☆11.0〜☆13.0）の BEAT-PT ランキングを返す。
     *
     * 非公式難易度表サマリーの「ランキングボタン」クリック時に呼ばれる。
     * 集計範囲はそのフォルダ内の全プレイ曲（top100 キャップなし）。
     *
     * @param rank 非公式ランクの先頭部分（例 "12.0"）
     * @return ランキング行リスト（合計 BEAT-PT 降順）
     */
    @GetMapping("/ranking/by-rank")
    public ResponseEntity<List<Map<String, Object>>> getRankingByInformalRank(@RequestParam String rank) {
        return ResponseEntity.ok(scoreRepository.findRankingByInformalRank(rank));
    }

    /**
     * 【メソッドの役割】 ARENA ランクごとの RATE-PT 平均を返す。
     */
    @GetMapping("/rate-ranking/arena-averages")
    public ResponseEntity<List<Map<String, Object>>> getArenaRankAverageRatePt() {
        return ResponseEntity.ok(scoreHistoryLogRepository.getArenaRankAverageRatePt());
    }

    /**
     * 【メソッドの役割】 公式トップランカーの RATE-PT ランキングを返す（キャッシュ）。
     */
    @GetMapping("/rate-ranking/top-rankers")
    public ResponseEntity<List<Map<String, Object>>> getTopRankersRatePt() {
        return ResponseEntity.ok(topRankersBeatPtService.getRateRanking());
    }

    /**
     * 【メソッドの役割】 曲 × 難易度の公式トップランカーのベストスコア一覧を返す。
     *
     * @param title          曲名
     * @param difficultyName 難易度名
     * @return SongScoreEntry の List
     */
    @GetMapping("/song-top-rankers")
    public ResponseEntity<List<com.beatseeker.backend.service.TopRankersBeatPtService.SongScoreEntry>> getSongTopRankers(
            @RequestParam String title,
            @RequestParam String difficultyName) {
        return ResponseEntity.ok(topRankersBeatPtService.getSongTopRankers(title, difficultyName));
    }

    /**
     * 【メソッドの役割】 特定エリア（version × 都道府県）のトップランカープロファイルを返す。
     *
     * @param versionNum        IIDX バージョン番号
     * @param prefectureFileNum 都道府県ファイル番号
     * @return プロファイル。見つからなければ 404
     */
    @GetMapping("/top-ranker-profile")
    public ResponseEntity<com.beatseeker.backend.service.TopRankersBeatPtService.AreaProfile> getTopRankerProfile(
            @RequestParam int versionNum,
            @RequestParam int prefectureFileNum) {
        var profile = topRankersBeatPtService.getAreaProfile(versionNum, prefectureFileNum);
        // キャッシュに該当エリアが無ければ 404 を返す。
        if (profile == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(profile);
    }

    /**
     * 【メソッドの役割】 精度 PT（totalPrecisionPt）に基づくランキングを返す。
     */
    @GetMapping("/precision-ranking")
    public ResponseEntity<List<Map<String, Object>>> getPrecisionRanking() {
        List<Map<String, Object>> ranking = scoreHistoryLogRepository.getPrecisionRanking();
        return ResponseEntity.ok(ranking);
    }

    /**
     * 【メソッドの役割】 RATE-PT に基づくランキング（ティア込み）を返す。
     */
    @GetMapping("/rate-ranking")
    public ResponseEntity<List<Map<String, Object>>> getRateRanking() {
        List<Map<String, Object>> ranking = scoreHistoryLogRepository.getRateTierRanking();
        return ResponseEntity.ok(ranking);
    }

    /**
     * 【メソッドの役割】 公式難易度 Lv11/Lv12 の ANOTHER/LEGGENDARIA 全曲を対象とした
     * 「平均順位ランキング」を返す（AVERAGE-RANKING）。
     *
     * 各譜面ごとに算出済みの順位（{@code user_song_ranks}）を全曲平均し、低い順に並べる。
     * 未プレイ譜面は「その譜面のプレイ人数 + 1」を順位とみなす。
     * 対象セット内で 1 曲もプレイ実績がないユーザーは結果から除外する。
     */
    @GetMapping("/average-ranking")
    public ResponseEntity<List<Map<String, Object>>> getAverageRanking() {
        return ResponseEntity.ok(userSongRankRepository.getAverageRanking());
    }

    /**
     * 【メソッドの役割】 指定ユーザーの BEAT-PT / RATE-PT 合計を取得する。
     *
     * プロフィール閲覧時に素早くユーザーの実力値を表示するためのエンドポイント。
     *
     * @param userId 対象ユーザー ID
     * @return {totalBeatPt, totalRatePt}（存在しない場合は 0.0）
     */
    @GetMapping("/user-tier-totals/{userId}")
    public ResponseEntity<Map<String, Object>> getUserTierTotals(@PathVariable Long userId) {
        // BEAT-PT は users テーブルにキャッシュ済みの値を使う。ユーザー不在なら 0.0 にフォールバック。
        Double beatPt = userRepository.findById(userId).map(User::getTotalBeatPt).orElse(0.0);
        // RATE-PT は最新スナップショットから引く。
        Double ratePt = scoreHistoryLogRepository.getLatestTotalRatePtByUserId(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("totalBeatPt", beatPt != null ? beatPt : 0.0);
        result.put("totalRatePt", ratePt != null ? ratePt : 0.0);
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 曲別ランキング集計に使う、全ユーザーの ANOTHER/LEGGENDARIA スコアを返す。
     *
     * フロントエンドはこの結果を使って「各ユーザーのトップ 100 にどの曲が入るか」を決定する。
     *
     * @return スコア行の List
     */
    @GetMapping("/all-user-scores")
    public ResponseEntity<List<Map<String, Object>>> getAllUserScores() {
        List<Map<String, Object>> scores = scoreRepository.findAllUserAnotherAndLeggendariaScores();
        return ResponseEntity.ok(scores);
    }

    /**
     * 【メソッドの役割】 人気曲ランキングの集計データ（DB 側で集計済み）を返す。
     *
     * 集計クエリは scores × song_definitions × difficulty_ranks の重 JOIN で
     * {@code statement_timeout = 30s} を超えるため、{@link SongRankingAggregateCacheService}
     * が定期的に in-memory にリフレッシュし、本エンドポイントはその結果を即時返す。
     * 初回ロード完了前は空リスト。
     */
    @GetMapping("/song-ranking-aggregate")
    public ResponseEntity<List<Map<String, Object>>> getSongRankingAggregate() {
        return ResponseEntity.ok(songRankingAggregateCacheService.get());
    }

    /**
     * 【メソッドの役割】 曲×難易度×ユーザーごとの素のベストスコアに BEAT-TIER ラベルを付けて返す。
     *
     * Lv11 / Lv12 の ANOTHER/LEGGENDARIA のみ対象。A ランク以上のフィルタ等の
     * さらに重い集計はクライアント側に任せる設計。
     *
     * 集計クエリ自体は重く {@code statement_timeout} を超えやすいため、
     * {@link SongArenaAveragesCacheService} が 30 分毎に再計算してメモリに保持し、
     * 本エンドポイントはその結果を即時返却する（初回ロード前は空リスト）。
     *
     * @return スコア行の List
     */
    @GetMapping("/song-arena-averages")
    public ResponseEntity<List<Map<String, Object>>> getSongBeatTierAverages() {
        return ResponseEntity.ok(songArenaAveragesCacheService.get());
    }

    /**
     * 【メソッドの役割】 曲単位で「平均スコアレート」「MAX- 率」「AAA 率」を返す。
     *
     * 処理の流れ:
     *  1. active リビジョンの SongDefinition から notes lookup（Lv11 以上の A/L のみ）を構築。
     *  2. DB から per-song 平均スコア、MAX- 数、AAA 数をそれぞれ取得して lookup を作る。
     *  3. 平均スコアを「notes × 2」で割って scoreRate に変換し、各種レートを整形して出力。
     *  4. 平均スコアレート昇順でソート（詰まり気味 → 緩い順）。
     *
     * 分離クエリ × Java 側マージ方式を採るのは、単一重量 JOIN を避けてレスポンスを短縮するため。
     *
     * @return 曲別集計 Map の List
     */
    @GetMapping("/song-avg-score-rates")
    public ResponseEntity<List<Map<String, Object>>> getSongAvgScoreRates() {
        // 手順1: 「曲名|難易度名」→ notes 数 の lookup を構築（高速・シングルテーブル）。
        List<SongDefinition> songDefs = songDefinitionRepository.findByRevision("active");
        Map<String, Integer> notesMap = new HashMap<>();
        for (SongDefinition sd : songDefs) {
            // Lv11 未満は対象外なのでスキップ。
            if (sd.getLevel() == null || sd.getLevel() < 11) continue;
            // 難易度コード 4 = ANOTHER、10 = LEGGENDARIA という IIDX の内部規約に従う。
            if ("4".equals(sd.getDifficulty())) {
                notesMap.put(sd.getTitle() + "|ANOTHER", sd.getNotes());
            } else if ("10".equals(sd.getDifficulty())) {
                notesMap.put(sd.getTitle() + "|LEGGENDARIA", sd.getNotes());
            }
        }

        // 手順2: 曲単位の平均スコアを取得（単一テーブル GROUP BY で軽量）。
        List<Map<String, Object>> songAvgs = scoreRepository.findSongAvgScores();

        // 手順3: MAX-（1 点差 AAA）の曲別カウントを集計 JOIN で取得（約 1000 行）。
        List<Map<String, Object>> maxMinusData = scoreRepository.findSongMaxMinusCounts();
        Map<String, int[]> maxMinusStats = new HashMap<>();
        for (Map<String, Object> row : maxMinusData) {
            String key = row.get("title") + "|" + row.get("difficultyName");
            int maxMinusCount = ((Number) row.get("maxMinusCount")).intValue();
            int totalCount = ((Number) row.get("totalCount")).intValue();
            // 値 2 要素を持つ int[] に詰めて lookup に保存する（Map<String, Stats> より軽量）。
            maxMinusStats.put(key, new int[]{maxMinusCount, totalCount});
        }

        // 手順3b: AAA の曲別カウントも同様に集計。
        List<Map<String, Object>> aaaData = scoreRepository.findSongAaaCounts();
        Map<String, int[]> aaaStats = new HashMap<>();
        for (Map<String, Object> row : aaaData) {
            String key = row.get("title") + "|" + row.get("difficultyName");
            int aaaCount = ((Number) row.get("aaaCount")).intValue();
            int totalCount = ((Number) row.get("totalCount")).intValue();
            aaaStats.put(key, new int[]{aaaCount, totalCount});
        }

        // 手順4: 平均スコアを scoreRate（%）に変換しつつ、各種レートを組み立てて返す。
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Map<String, Object> row : songAvgs) {
            String title = (String) row.get("title");
            String diffName = (String) row.get("difficultyName");
            double avgScore = ((Number) row.get("avgScore")).doubleValue();
            int playerCount = ((Number) row.get("playerCount")).intValue();

            String key = title + "|" + diffName;
            Integer notes = notesMap.get(key);
            // notes 情報が無い曲（SongDefinition 未登録、もしくは Lv11 未満）はスキップ。
            if (notes == null || notes <= 0) continue;

            // MAX スコアは notes × 2。% 化するために 100 倍を掛ける。
            double avgScoreRate = avgScore * 100.0 / (notes * 2.0);

            int[] stats = maxMinusStats.get(key);
            // 10000 倍してから丸めて /100 することで小数 2 桁の % を生成する。
            double maxMinusRate = (stats != null && stats[1] > 0)
                ? Math.round(stats[0] * 10000.0 / stats[1]) / 100.0
                : 0.0;

            Map<String, Object> entry = new HashMap<>();
            entry.put("title", title);
            entry.put("difficultyName", diffName);
            entry.put("avgScoreRate", Math.round(avgScoreRate * 100.0) / 100.0);
            entry.put("playerCount", playerCount);
            entry.put("maxMinusRate", maxMinusRate);
            entry.put("maxMinusCount", stats != null ? stats[0] : 0);

            int[] aStats = aaaStats.get(key);
            double aaaRate = (aStats != null && aStats[1] > 0)
                ? Math.round(aStats[0] * 10000.0 / aStats[1]) / 100.0
                : 0.0;
            entry.put("aaaRate", aaaRate);
            entry.put("aaaCount", aStats != null ? aStats[0] : 0);
            result.add(entry);
        }

        // 平均スコアレート昇順（= 難しい／詰まってない曲順）でソートして返す。
        result.sort((a, b) -> Double.compare(
            ((Number) a.get("avgScoreRate")).doubleValue(),
            ((Number) b.get("avgScoreRate")).doubleValue()));

        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 指定曲 × 難易度の曲別ランキングを「呼び出し元が見える範囲」だけに絞って返す。
     *
     * 処理の流れ:
     *  1. ログインユーザーのフレンド ID 配列を取得。フレンドが居なければ -1 だけ入れて SQL に NULL を渡さないようにする。
     *  2. リポジトリに「自分 / フレンド / 公開ユーザー」のいずれかに該当する行のみを返すクエリを委譲する。
     *
     * @param auth           認証情報
     * @param title          曲名
     * @param difficultyName 難易度名
     * @return 可視範囲に絞った曲別ランキング
     */
    @GetMapping("/song-ranking")
    public ResponseEntity<List<Map<String, Object>>> getSongRanking(
            Authentication auth,
            @RequestParam String title,
            @RequestParam String difficultyName) {
        User me = getUser(auth);
        // 手順1: フレンド ID を収集する。IN 句に空リストを渡すと SQL エラーになるので、
        // 空なら存在し得ない値（-1）を一件入れておく。
        List<Long> friendIds = friendshipRepository.findByUser(me).stream()
                .map(f -> f.getFriend().getId())
                .toList();
        if (friendIds.isEmpty()) friendIds = List.of(-1L);
        // 手順2: 可視性フィルタ付きクエリに委譲。
        List<Map<String, Object>> ranking = scoreRepository.findSongRanking(
                title, difficultyName, me.getId(), friendIds);
        return ResponseEntity.ok(ranking);
    }

    /**
     * 【メソッドの役割】 ログインユーザーが既プレイの全譜面の曲別順位を返す。
     *
     * UserSongRank キャッシュテーブル（日次バッチで再計算）から読み込むため高速。
     *
     * @param auth 認証情報
     * @return 各曲の順位 Map の List
     */
    @GetMapping("/my-song-ranks")
    public ResponseEntity<List<Map<String, Object>>> getMySongRanks(Authentication auth) {
        User user = getUser(auth);
        List<UserSongRank> ranks = userSongRankRepository.findByUserId(user.getId());
        List<Map<String, Object>> result = ranks.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("title", r.getTitle());
            map.put("difficultyName", r.getDifficultyName());
            map.put("difficultyLevel", r.getDifficultyLevel());
            map.put("rank", r.getRank());
            map.put("total", r.getTotal());
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 管理者の全曲順位を返す（管理者限定）。
     *
     * 認証が無い／管理者 iidxId と一致しない場合は空リストを 200 で返す。
     * UserSongRank キャッシュテーブルから読み込む。
     *
     * @param auth 認証情報
     * @return 管理者の曲順位 Map の List。非管理者は空リスト
     */
    @GetMapping("/admin-song-ranks")
    public ResponseEntity<List<Map<String, Object>>> getAdminSongRanks(Authentication auth) {
        // 権限が無い場合は空 List を返す（403 でも良いがフロントの分岐を減らすため 200 + []）。
        if (auth == null || !auth.isAuthenticated()
                || !adminAuthService.isAdminByIidxId((String) auth.getPrincipal())) {
            return ResponseEntity.ok(List.of());
        }
        List<UserSongRank> ranks = userSongRankRepository.findByUserId(adminAuthService.getAdminUserId());
        List<Map<String, Object>> result = ranks.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("title", r.getTitle());
            map.put("difficultyName", r.getDifficultyName());
            map.put("difficultyLevel", r.getDifficultyLevel());
            map.put("rank", r.getRank());
            map.put("total", r.getTotal());
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 管理者の手動操作で曲別ランクの全件再計算を非同期起動する。
     *
     * デプロイ直後やランクが古い疑いがある時に使用する運用ボタン。
     *
     * @param auth 認証情報（管理者限定）
     * @return 202 + 開始メッセージ。非管理者は 403
     */
    @PostMapping("/recalculate-song-ranks")
    public ResponseEntity<Map<String, Object>> recalculateSongRanks(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || !adminAuthService.isAdminByIidxId((String) auth.getPrincipal())) {
            return ResponseEntity.status(403).build();
        }
        songRankBatchService.recalculateAllAsync();
        return ResponseEntity.accepted().body(Map.of("message", "Song rank recalculation started"));
    }

    /**
     * 【メソッドの役割】 指定曲 × 難易度における、ログインユーザーのスコア更新履歴を返す。
     *
     * 処理の流れ:
     *  1. ScoreHistoryLog を昇順で走査し、各スナップショットの diffJson を解析。
     *  2. diffs の中から (title, difficulty) 一致する差分だけ拾って、uploadedAt / newScore / newBeatPt を集める。
     *  3. 最後に uploadedAt 降順に並び替えて返す。
     *
     * 壊れた diffJson は try-catch で無視する（他のスナップショットへの影響を防ぐため）。
     *
     * @param auth           認証情報
     * @param title          曲名
     * @param difficultyName 難易度名
     * @return 時系列の差分履歴
     */
    @GetMapping("/song-history")
    public ResponseEntity<List<Map<String, Object>>> getSongHistory(
            Authentication auth,
            @RequestParam String title,
            @RequestParam String difficultyName) {
        User user = getUser(auth);
        List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(user);

        // 本メソッド内だけで使うローカル ObjectMapper（クラスメンバと同じ挙動）。
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<Map<String, Object>> result = new java.util.ArrayList<>();

        for (ScoreHistoryLog log : logs) {
            String diffJson = log.getDiffJson();
            // diffJson が null/空/"[]" のスナップショットは該当差分がないのでスキップ。
            if (diffJson == null || diffJson.isEmpty() || "[]".equals(diffJson)) continue;
            try {
                List<Map<String, Object>> diffs = mapper.readValue(diffJson,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> diff : diffs) {
                    Object t = diff.get("title");
                    Object d = diff.get("difficulty");
                    // (title, difficulty) が一致する差分のみを拾う。
                    if (title.equals(t) && difficultyName.equals(d)) {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("uploadedAt", log.getUploadedAt().toString());
                        entry.put("score", diff.get("newScore"));
                        entry.put("beatPt", diff.get("newBeatPt"));
                        result.add(entry);
                    }
                }
            } catch (Exception e) {
                // 壊れた diffJson は静かにスキップ（他のログ表示を壊さないため）。
            }
        }

        // uploadedAt 文字列（ISO 形式なので辞書順＝時系列）で降順ソート。
        result.sort((a, b) -> ((String) b.get("uploadedAt")).compareTo((String) a.get("uploadedAt")));
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 アップロードしたユーザーがフレンドのスコアを上回った曲について、当該フレンドに通知する。
     *
     * 処理の流れ:
     *  1. アップロード者が非公開（privacyLevel==2）か更新が空なら即終了。
     *  2. フレンドごとに、更新対象曲のスコアを 1 クエリで一括取得（N×M クエリを避ける）。
     *  3. 各曲について「更新前は負けていたが、更新後に勝った」条件を満たす場合のみ AppNotification + push を送る。
     *
     * 通知条件の 3 点セット:
     *   - newScore > friendScoreVal ... 追い抜いた
     *   - friendScoreVal > 0         ... フレンドが未プレイではない（0 への追い越しは通知しない）
     *   - oldScore <= friendScoreVal ... 直前までは負けていた
     *
     * @param uploader     スコアをアップロードしたユーザー
     * @param updatedSongs uploadScores() が生成した差分情報リスト
     */
    private void notifyFriendsOfScoreBeat(User uploader, List<Map<String, Object>> updatedSongs) {
        // 手順1a: 非公開ユーザーは通知を飛ばさない（スコアが漏れないように）。
        if (uploader.getPrivacyLevel() != null && uploader.getPrivacyLevel() == 2) return;
        if (updatedSongs.isEmpty()) return;
        List<com.beatseeker.backend.entity.Friendship> friendships = friendshipRepository.findByUser(uploader);

        // 手順2a: 更新対象の title と difficulty を IN 句用に展開する。
        List<String> titles = updatedSongs.stream().map(s -> (String) s.get("title")).toList();
        List<String> difficulties = updatedSongs.stream().map(s -> (String) s.get("difficulty")).distinct().toList();

        for (com.beatseeker.backend.entity.Friendship friendship : friendships) {
            User friend = friendship.getFriend();
            // 非公開フレンドはスキップ（そもそも比較対象にしない）。
            if (friend.getPrivacyLevel() != null && friend.getPrivacyLevel() == 2) continue;

            // 手順2b: フレンドのスコアを IN 句で一括取得。N×M クエリ化を避けるための最適化。
            List<com.beatseeker.backend.entity.Score> friendScores =
                    scoreRepository.findByUserAndTitlesAndDifficulties(friend, titles, difficulties);
            Map<String, Integer> friendScoreMap = new HashMap<>();
            for (com.beatseeker.backend.entity.Score fs : friendScores) {
                String key = fs.getTitle() + "_" + fs.getDifficultyName();
                friendScoreMap.put(key, fs.getScore() != null ? fs.getScore() : 0);
            }

            // 手順3: 更新曲ごとに「追い抜いた」判定を行い、該当時のみ通知を作る。
            for (Map<String, Object> song : updatedSongs) {
                String title = (String) song.get("title");
                String difficulty = (String) song.get("difficulty");
                int newScore = (int) song.get("newScore");
                int oldScore = (int) song.get("oldScore");
                int friendScoreVal = friendScoreMap.getOrDefault(title + "_" + difficulty, 0);

                // 条件: 更新後は勝ち／フレンドがプレイ済み／更新前は負けていた、の 3 点セット。
                if (newScore > friendScoreVal && friendScoreVal > 0 && oldScore <= friendScoreVal) {
                    // アプリ内通知（ベル通知）を保存。
                    AppNotification notification = new AppNotification();
                    notification.setRecipient(friend);
                    notification.setType("SCORE_BEAT");
                    notification.setMessage(
                            uploader.getDisplayName() + "さんが「" + title + "」(" + difficulty + ") で " +
                            newScore + " を記録し、あなたのスコア " + friendScoreVal + " を上回りました！");
                    appNotificationRepository.save(notification);

                    // push 購読が登録済みなら Web Push も送る。
                    if (friend.getPushSubscription() != null) {
                        pushNotificationService.sendNotification(
                                friend.getPushSubscription(),
                                "スコアを抜かれました！",
                                uploader.getDisplayName() + "さんに「" + title + "」で抜かれました",
                                "/");
                    }
                }
            }
        }
    }

    /**
     * 【メソッドの役割】 タイムライン表示用に、今回のアップロード由来のイベントを {@link TimelineEvent} として保存する。
     *
     * 発行されるイベント:
     *  - SCORE_UPDATE: スコア更新譜面の配列 (diffJson) をそのまま payload にして 1 件
     *  - OVERTAKE_SONG: 譜面単位 EX スコアでフレンド／仮想ライバルを抜いた件、抜いた相手×譜面ごとに 1 件
     *  - OVERTAKE_TOTAL: 総合 BEAT-PT でフレンド／仮想ライバルを抜いた件、相手ごとに 1 件
     *
     * 仮想ライバルの曲別スコア・総合 BEAT-PT は {@link TopRankersBeatPtService} のキャッシュから取得する。
     * キャッシュ未ロード時は仮想ライバル比較をスキップする（フレンド比較は実施）。
     *
     * 例外は握りつぶす: タイムライン保存はメイン処理（スコア保存）を妨げてはいけない。
     *
     * @param uploader        アップロード主体
     * @param diffJson        更新譜面リストの JSON 文字列（フロントから渡されたもの）
     * @param oldTotalBeatPt  アップロード前の総合 BEAT-PT
     * @param newTotalBeatPt  アップロード後の総合 BEAT-PT
     */
    private void recordTimelineEvents(User uploader, String diffJson,
                                      double oldTotalBeatPt, double newTotalBeatPt) {
        try {
            // 非公開（privacyLevel == 2）ユーザーはタイムラインに出さない。
            if (uploader.getPrivacyLevel() != null && uploader.getPrivacyLevel() == 2) return;

            List<Map<String, Object>> diffs = List.of();
            if (diffJson != null && !diffJson.isBlank()) {
                try {
                    diffs = objectMapper.readValue(diffJson, new TypeReference<>() {});
                } catch (Exception e) {
                    // diffJson のフォーマットが想定外でも、Overtake 判定は続行する。
                    System.err.println("Failed to parse diffJson for timeline: " + e.getMessage());
                }
            }

            // 1) SCORE_UPDATE: 更新譜面が 1 件以上ある場合のみ発行。
            if (!diffs.isEmpty()) {
                TimelineEvent ev = new TimelineEvent();
                ev.setUser(uploader);
                ev.setType("SCORE_UPDATE");
                ev.setPayload(diffJson);
                timelineEventRepository.save(ev);
            }

            // 2) OVERTAKE_SONG / OVERTAKE_TOTAL のために、抜き判定対象を集める。
            List<com.beatseeker.backend.entity.Friendship> friendships = friendshipRepository.findByUser(uploader);
            List<VirtualRival> virtualRivals = virtualRivalRepository.findByOwner(uploader);

            // 2a) OVERTAKE_TOTAL（総合 BEAT-PT）: 旧 BEAT-PT < 相手 ≤ 新 BEAT-PT を満たす相手を抽出。
            for (com.beatseeker.backend.entity.Friendship friendship : friendships) {
                User friend = friendship.getFriend();
                if (friend.getPrivacyLevel() != null && friend.getPrivacyLevel() == 2) continue;
                double rivalPt = friend.getTotalBeatPt() != null ? friend.getTotalBeatPt() : 0.0;
                if (rivalPt > 0 && oldTotalBeatPt < rivalPt && rivalPt <= newTotalBeatPt) {
                    saveOvertakeTotalEvent(uploader, friend.getId(), false,
                            friend.getDisplayName() != null ? friend.getDisplayName() : "プレイヤー",
                            rivalPt, oldTotalBeatPt, newTotalBeatPt);
                }
            }
            // 仮想ライバルの BEAT-PT は TopRankersBeatPtService.getRanking() からルックアップ。
            Map<String, Map<String, Object>> areaRankingMap = new HashMap<>();
            for (Map<String, Object> row : topRankersBeatPtService.getRanking()) {
                Object v = row.get("versionNum");
                Object p = row.get("prefectureFileNum");
                if (v != null && p != null) {
                    areaRankingMap.put(v + "\0" + p, row);
                }
            }
            for (VirtualRival rival : virtualRivals) {
                Map<String, Object> row = areaRankingMap.get(rival.getVersionNum() + "\0" + rival.getPrefectureFileNum());
                if (row == null) continue;
                double rivalPt = toDouble(row.get("totalBeatPt"));
                if (rivalPt > 0 && oldTotalBeatPt < rivalPt && rivalPt <= newTotalBeatPt) {
                    String label = (rival.getPrefectureName() != null ? rival.getPrefectureName() : "?")
                            + " TOP (" + (rival.getVersionName() != null ? rival.getVersionName() : "?") + ")";
                    saveOvertakeTotalEvent(uploader, rival.getId(), true,
                            label, rivalPt, oldTotalBeatPt, newTotalBeatPt);
                }
            }

            // 2b) OVERTAKE_SONG（譜面単位 EX スコア）: 更新譜面ごとに相手スコアを比較。
            if (!diffs.isEmpty()) {
                // フロントの diffJson キーは "difficulty"、Score テーブルは "difficultyName"。混在に注意。
                List<String> titles = diffs.stream()
                        .map(d -> (String) d.get("title"))
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
                List<String> difficulties = diffs.stream()
                        .map(d -> (String) d.get("difficulty"))
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
                if (!titles.isEmpty() && !difficulties.isEmpty()) {
                    // 各フレンドのスコアを IN 句で取得して比較。
                    for (com.beatseeker.backend.entity.Friendship friendship : friendships) {
                        User friend = friendship.getFriend();
                        if (friend.getPrivacyLevel() != null && friend.getPrivacyLevel() == 2) continue;
                        Map<String, Integer> friendScoreMap = new HashMap<>();
                        for (Score fs : scoreRepository.findByUserAndTitlesAndDifficulties(friend, titles, difficulties)) {
                            friendScoreMap.put(fs.getTitle() + "\0" + fs.getDifficultyName(),
                                    fs.getScore() != null ? fs.getScore() : 0);
                        }
                        for (Map<String, Object> d : diffs) {
                            int oldScore = toInt(d.get("oldScore"));
                            int newScore = toInt(d.get("newScore"));
                            String title = (String) d.get("title");
                            String difficulty = (String) d.get("difficulty");
                            if (title == null || difficulty == null) continue;
                            int rivalScore = friendScoreMap.getOrDefault(title + "\0" + difficulty, 0);
                            if (rivalScore > 0 && oldScore < rivalScore && rivalScore <= newScore) {
                                saveOvertakeSongEvent(uploader, friend.getId(), false,
                                        friend.getDisplayName() != null ? friend.getDisplayName() : "プレイヤー",
                                        title, difficulty, rivalScore, oldScore, newScore);
                            }
                        }
                    }
                    // 仮想ライバルは AreaProfile から (title, difficultyName) で引く。
                    for (VirtualRival rival : virtualRivals) {
                        var profile = topRankersBeatPtService.getAreaProfile(
                                rival.getVersionNum(), rival.getPrefectureFileNum());
                        if (profile == null) continue;
                        Map<String, Integer> areaScoreMap = new HashMap<>();
                        for (var row : profile.scores()) {
                            areaScoreMap.put(row.title() + "\0" + row.difficultyName(), row.score());
                        }
                        String label = (rival.getPrefectureName() != null ? rival.getPrefectureName() : "?")
                                + " TOP (" + (rival.getVersionName() != null ? rival.getVersionName() : "?") + ")";
                        for (Map<String, Object> d : diffs) {
                            int oldScore = toInt(d.get("oldScore"));
                            int newScore = toInt(d.get("newScore"));
                            String title = (String) d.get("title");
                            String difficulty = (String) d.get("difficulty");
                            if (title == null || difficulty == null) continue;
                            int rivalScore = areaScoreMap.getOrDefault(title + "\0" + difficulty, 0);
                            if (rivalScore > 0 && oldScore < rivalScore && rivalScore <= newScore) {
                                saveOvertakeSongEvent(uploader, rival.getId(), true,
                                        label, title, difficulty, rivalScore, oldScore, newScore);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // タイムライン保存の失敗は致命的ではない（メイン処理を巻き込まない）。
            System.err.println("Failed to record timeline events: " + e.getMessage());
        }
    }

    /** OVERTAKE_TOTAL イベントを 1 件保存する。payload は JSON 文字列に変換して格納。 */
    private void saveOvertakeTotalEvent(User uploader, Long rivalId, boolean isVirtual,
                                        String rivalName, double rivalPt,
                                        double myOldPt, double myNewPt) {
        try {
            TimelineEvent ev = new TimelineEvent();
            ev.setUser(uploader);
            ev.setType("OVERTAKE_TOTAL");
            ev.setPayload(objectMapper.writeValueAsString(Map.of(
                    "rivalId", rivalId,
                    "isVirtual", isVirtual,
                    "rivalName", rivalName,
                    "rivalBeatPt", rivalPt,
                    "myOldBeatPt", myOldPt,
                    "myNewBeatPt", myNewPt
            )));
            timelineEventRepository.save(ev);
        } catch (Exception e) {
            System.err.println("Failed to save OVERTAKE_TOTAL: " + e.getMessage());
        }
    }

    /** OVERTAKE_SONG イベントを 1 件保存する。payload は JSON 文字列に変換して格納。 */
    private void saveOvertakeSongEvent(User uploader, Long rivalId, boolean isVirtual,
                                       String rivalName, String title, String difficulty,
                                       int rivalScore, int myOldScore, int myNewScore) {
        try {
            TimelineEvent ev = new TimelineEvent();
            ev.setUser(uploader);
            ev.setType("OVERTAKE_SONG");
            ev.setPayload(objectMapper.writeValueAsString(Map.of(
                    "rivalId", rivalId,
                    "isVirtual", isVirtual,
                    "rivalName", rivalName,
                    "title", title,
                    "difficulty", difficulty,
                    "rivalScore", rivalScore,
                    "myOldScore", myOldScore,
                    "myNewScore", myNewScore
            )));
            timelineEventRepository.save(ev);
        } catch (Exception e) {
            System.err.println("Failed to save OVERTAKE_SONG: " + e.getMessage());
        }
    }

    /** Number/String 混在の値を安全に double に変換する。 */
    private double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0.0; }
    }

    /** Number/String 混在の値を安全に int に変換する。 */
    private int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; }
    }

    /**
     * 【メソッドの役割】 ユーザーがティアアップ（Beat-Tier が変化）した際、フレンド全員に通知する。
     *
     * 非公開ユーザーの場合は通知を飛ばさない。
     *
     * @param user    ランクアップしたユーザー
     * @param oldTier 旧ティア名
     * @param newTier 新ティア名
     */
    private void notifyFriendsOfRankUp(User user, String oldTier, String newTier) {
        if (user.getPrivacyLevel() != null && user.getPrivacyLevel() == 2) return;
        List<com.beatseeker.backend.entity.Friendship> friendships = friendshipRepository.findByUser(user);
        for (com.beatseeker.backend.entity.Friendship friendship : friendships) {
            User friend = friendship.getFriend();
            // アプリ内通知は必ず保存する。
            AppNotification notification = new AppNotification();
            notification.setRecipient(friend);
            notification.setType("FRIEND_RANK_UP");
            notification.setMessage(
                    user.getDisplayName() + "さんが Beat-Tier「" + oldTier + "」から「" + newTier + "」にランクアップしました！");
            appNotificationRepository.save(notification);

            // push は購読者だけに送る。
            if (friend.getPushSubscription() != null) {
                pushNotificationService.sendNotification(
                        friend.getPushSubscription(),
                        "フレンドがランクアップ！",
                        user.getDisplayName() + "さんが " + newTier + " にランクアップしました！",
                        "/");
            }
        }
    }

    /**
     * 【メソッドの役割】 認証情報から User を解決する共通ヘルパー。
     *
     * @param auth 認証情報
     * @return ログインユーザー
     * @throws RuntimeException 未認証 or User 不在
     */
    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /**
     * 【レコード】 スコアアップロードのリクエスト DTO。
     *
     * eagate CSV パース結果の 1 行に相当し、曲・難易度・各種スコア値をフロントから送る。
     */
    public record ScoreUploadRequest(
            String title,
            String artist,
            String genre,
            String difficultyName,
            Integer difficultyLevel,
            Integer score,
            String clearType,
            String djLevel,
            Integer pgreat,
            Integer great,
            Integer missCount,
            Integer playCount,
            /**
             * スコア取得元。{@code "arcade"}（既定）または {@code "infinitas"}。
             * 旧クライアントは送ってこないので null フォールバックを {@link #effectiveSource()} で吸収する。
             */
            String source) {
        /**
         * null/空白を "arcade" にフォールバックして返す。旧フロントとの互換維持と、
         * 不正値（任意の文字列）が来た場合の安全策を兼ねる。
         */
        public String effectiveSource() {
            if (source == null || source.isBlank()) return "arcade";
            // 既知ソース以外は "arcade" に正規化する（将来の追加時はここに足す）。
            String s = source.toLowerCase();
            if ("infinitas".equals(s) || "arcade".equals(s)) return s;
            return "arcade";
        }
    }
}
