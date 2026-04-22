package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.ArenaMatch;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ArenaMatchRepository;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.beatseeker.backend.service.AdminAuthService;
import com.beatseeker.backend.service.ScoreRecalculationService;
import com.beatseeker.backend.service.TopRankersBeatPtService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【クラスの役割】 管理者専用の運用系 API を集約する REST コントローラ。
 *
 * 全エンドポイントが {@code /api/admin/**} 配下にマッピングされ、
 * 呼び出し時にまず {@link #checkAdminAccess(Authentication)} で管理者権限を検証する。
 *
 * 責務:
 *  - ユーザー一覧・個別ユーザーのスコア／履歴／ARENA 試合の閲覧
 *  - スコア一括集計（シミュレーション・アグリゲート）
 *  - BEAT-PT / RATE-PT の再計算、ランキングキャッシュ再構築
 *  - 新カラム導入時のデータ移行バッチ、push 購読情報の一括クリア
 *
 * 依存:
 *  - {@link UserRepository} / {@link ScoreRepository} / {@link ScoreHistoryLogRepository}
 *  - {@link ArenaMatchRepository}: ARENA 試合ログ
 *  - {@link ScoreRecalculationService}: BEAT-PT / RATE-PT の全ユーザー再計算
 *  - {@link TopRankersBeatPtService}: トップランカー一覧キャッシュ
 *  - {@link ObjectMapper}: DB に JSON 文字列で保持している playersJson / songsJson のパース
 *  - {@link EntityManager}: ネイティブ SQL で statement_timeout を一時的に引き上げる
 *
 * 主なエンドポイント:
 *  - GET  /api/admin/users                          … 全ユーザー一覧
 *  - GET  /api/admin/users/{userId}/scores          … 指定ユーザーのスコア一覧
 *  - GET  /api/admin/scores/all-user-scores-with-info … 全ユーザーのスコアをユーザー情報付きで返す
 *  - GET  /api/admin/scores/simulation-aggregate    … 難易度シミュレーション集計
 *  - GET  /api/admin/users/{userId}/history         … 指定ユーザーのスナップショット履歴
 *  - GET  /api/admin/users/{userId}/arena/matches   … 指定ユーザーの ARENA 試合履歴
 *  - POST /api/admin/recalculate-points             … 全ユーザーの BEAT-PT / RATE-PT 再計算（非同期）
 *  - POST /api/admin/migrate-user-beat-pt           … users.total_beat_pt の一括移行
 *  - POST /api/admin/push/clear-all                 … 全ユーザーの push 購読を初期化
 *  - POST /api/admin/patch-rate-pt                  … RATE-PT = 0 の履歴ログを補正
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /** ユーザー CRUD・検索用リポジトリ。 */
    private final UserRepository userRepository;
    /** スコア CRUD・集計クエリ用リポジトリ。 */
    private final ScoreRepository scoreRepository;
    /** スコアスナップショット（履歴）リポジトリ。 */
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;
    /** ARENA 対戦記録リポジトリ。 */
    private final ArenaMatchRepository arenaMatchRepository;
    /** BEAT-PT / RATE-PT の再計算を非同期で実行するサービス。 */
    private final ScoreRecalculationService scoreRecalculationService;
    /** トップランカー BEAT-PT ランキングのキャッシュサービス。 */
    private final TopRankersBeatPtService topRankersBeatPtService;
    /** playersJson / songsJson をパースするための Jackson ObjectMapper。 */
    private final ObjectMapper objectMapper;
    /** 管理者判定のロジックを共通化した Service。 */
    private final AdminAuthService adminAuthService;

    /**
     * JPA が注入する永続化コンテキスト。
     * 長時間クエリのために statement_timeout を一時引き上げる等、
     * ネイティブ SQL を直接発行する用途に利用する。
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 【コンストラクタ】 Spring DI で全依存を受け取って初期化する。
     */
    public AdminController(UserRepository userRepository,
                           ScoreRepository scoreRepository,
                           ScoreHistoryLogRepository scoreHistoryLogRepository,
                           ArenaMatchRepository arenaMatchRepository,
                           ScoreRecalculationService scoreRecalculationService,
                           TopRankersBeatPtService topRankersBeatPtService,
                           ObjectMapper objectMapper,
                           AdminAuthService adminAuthService) {
        this.userRepository = userRepository;
        this.scoreRepository = scoreRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.arenaMatchRepository = arenaMatchRepository;
        this.scoreRecalculationService = scoreRecalculationService;
        this.topRankersBeatPtService = topRankersBeatPtService;
        this.objectMapper = objectMapper;
        this.adminAuthService = adminAuthService;
    }

    /**
     * 【メソッドの役割】 全エンドポイントが共通で呼ぶ管理者権限チェック。
     *
     * 未認証・ユーザー不在・非管理者のいずれかの場合に RuntimeException を投げ、
     * グローバル例外ハンドラで 500 相当にマッピングされる。
     * 管理者判定ロジックは {@link AdminAuthService} に集約されている。
     *
     * @param auth Spring Security 由来の認証情報
     * @throws RuntimeException 認証不成立・ユーザー不在・非管理者の場合
     */
    private void checkAdminAccess(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        User user = userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Role 管理テーブルは未実装のため、AdminAuthService に判定ロジックを集約している。
        if (!adminAuthService.isAdmin(user)) {
            throw new RuntimeException("Access Denied: Admin privileges required.");
        }
    }

    /**
     * 【メソッドの役割】 全ユーザー一覧を軽量サマリ（id／iidxId／表示名／段位／ARENA ランク）で返す。
     *
     * 管理画面のユーザー選択ドロップダウン等で使用。
     *
     * @param auth 認証情報（管理者限定）
     * @return ユーザーサマリの List
     */
    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> getAllUsers(Authentication auth) {
        checkAdminAccess(auth);

        List<User> users = userRepository.findAll();
        // ユーザー全件を管理画面向けの Map サマリに畳む。個人情報系（email 等）は含めない。
        List<Map<String, Object>> result = users.stream().map(u -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.getId());
            map.put("iidxId", u.getIidxId());
            map.put("displayName", u.getDisplayName());
            map.put("danRank", u.getDanRank());
            map.put("arenaRank", u.getArenaRank());
            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 指定ユーザーの全スコアを取得する。
     *
     * 管理画面で「このユーザーがどの曲でどんなスコアを持っているか」を確認する用途。
     * null 値はフロント描画を壊さないよう、すべて既定値に置換してから返す。
     *
     * @param auth   認証情報（管理者限定）
     * @param userId 対象ユーザーの DB 主キー
     * @return スコア一覧（曲名・難易度・クリア種別・DJ ランクなど）
     * @throws RuntimeException 対象ユーザーが存在しない場合
     */
    @GetMapping("/users/{userId}/scores")
    public ResponseEntity<List<Map<String, Object>>> getUserScores(
            Authentication auth,
            @PathVariable Long userId) {

        checkAdminAccess(auth);

        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Target user not found"));

        List<Score> scores = scoreRepository.findByUserOrderByUploadedAtAsc(targetUser);

        List<Map<String, Object>> result = scores.stream().map(s -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", s.getId());
            // フロントでの描画事故防止のため、文字列系 null は空文字、数値系 null は 0 に置換する。
            map.put("title", s.getTitle() != null ? s.getTitle() : "");
            map.put("difficultyName", s.getDifficultyName() != null ? s.getDifficultyName() : "");
            map.put("difficultyLevel", s.getDifficultyLevel() != null ? s.getDifficultyLevel() : 0);
            map.put("score", s.getScore() != null ? s.getScore() : 0);
            map.put("clearType", s.getClearType() != null ? s.getClearType() : "");
            map.put("djLevel", s.getDjLevel() != null ? s.getDjLevel() : "");
            map.put("pgreat", s.getPgreat() != null ? s.getPgreat() : 0);
            map.put("great", s.getGreat() != null ? s.getGreat() : 0);
            // missCount だけは null を許容（未計測を意味する）。
            map.put("missCount", s.getMissCount());
            map.put("memo", s.getMemo() != null ? s.getMemo() : "");
            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 全ユーザーの ANOTHER / LEGGENDARIA スコアをユーザー情報付きで返す集計用 API。
     *
     * 管理画面でのランキング／集計作成の基礎データ。DB 層で JOIN 済みの結果を素通しする。
     *
     * @param auth 認証情報（管理者限定）
     * @return ユーザー情報付きスコアの List
     */
    @GetMapping("/scores/all-user-scores-with-info")
    public ResponseEntity<List<Map<String, Object>>> getAllUserScoresWithInfo(Authentication auth) {
        checkAdminAccess(auth);
        return ResponseEntity.ok(scoreRepository.findAllUserAnotherAndLeggendariaScoresWithUserInfo());
    }

    /**
     * 【メソッドの役割】 難易度別シミュレーション集計を重量クエリで実行する。
     *
     * 処理時間が長いため、statement_timeout を 120 秒に一時引き上げてからクエリを実行する。
     * {@code @Transactional(readOnly = true)} により SET LOCAL がこのトランザクション内に閉じる。
     *
     * @param auth 認証情報（管理者限定）
     * @return 難易度シミュレーションの集計結果
     */
    @GetMapping("/scores/simulation-aggregate")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getSimulationAggregate(Authentication auth) {
        checkAdminAccess(auth);
        // 手順1: 本トランザクションに限り statement_timeout を 120 秒に延長する（重量クエリ対策）。
        entityManager.createNativeQuery("SET LOCAL statement_timeout = '120s'").executeUpdate();
        // 手順2: リポジトリ経由で集計クエリを実行する。
        return ResponseEntity.ok(scoreRepository.calculateDifficultySimulation());
    }

    /**
     * 【メソッドの役割】 指定ユーザーのスコアスナップショット履歴（score_history_logs）を返す。
     *
     * BEAT-PT 変遷グラフやランクアップ履歴の元データとして利用される。
     *
     * @param auth   認証情報（管理者限定）
     * @param userId 対象ユーザーの DB 主キー
     * @return スナップショット Map の List
     * @throws RuntimeException 対象ユーザー不在時
     */
    @GetMapping("/users/{userId}/history")
    public ResponseEntity<List<Map<String, Object>>> getUserHistory(
            Authentication auth,
            @PathVariable Long userId) {

        checkAdminAccess(auth);

        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Target user not found"));

        List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(targetUser);

        List<Map<String, Object>> history = new ArrayList<>();

        for (ScoreHistoryLog log : logs) {
            Map<String, Object> snapshotData = new HashMap<>();
            snapshotData.put("snapshotId", log.getId().toString()); // ID を擬似的なスナップショット ID として利用する
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

            snapshotData.put("totalBeatPt", log.getTotalBeatPt());
            snapshotData.put("beatPtIncrease", log.getBeatPtIncrease());
            snapshotData.put("updatedCount", log.getUpdatedCount());
            snapshotData.put("diffJson", log.getDiffJson());
            snapshotData.put("totalRatePt", log.getTotalRatePt());

            history.add(snapshotData);
        }

        return ResponseEntity.ok(history);
    }

    /**
     * 【メソッドの役割】 指定ユーザーの ARENA 対戦履歴を整形して返す。
     *
     * 処理の流れ:
     *  1. 対象ユーザーを解決し、ARENA 試合を日付降順で取得。
     *  2. DB に JSON 文字列で格納されている players / songs を ObjectMapper で List 構造に復元。
     *  3. 試合ログに myRank が未設定の古いレコードについては、players 配列から自分の DJ 名を
     *     照合して rank / totalPt / arenaClass を補完する。
     *
     * @param auth   認証情報（管理者限定）
     * @param userId 対象ユーザーの DB 主キー
     * @return ARENA 試合サマリの List
     */
    @GetMapping("/users/{userId}/arena/matches")
    public ResponseEntity<List<Map<String, Object>>> getUserArenaMatches(
            Authentication auth,
            @PathVariable Long userId) {

        checkAdminAccess(auth);

        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Target user not found"));

        // 手順1: 試合を新しい順（日付降順）で取得する。
        List<ArenaMatch> matches = arenaMatchRepository.findByUserOrderByMatchDateDesc(targetUser);

        List<Map<String, Object>> result = new ArrayList<>();
        for (ArenaMatch m : matches) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", m.getId());
            map.put("battleType", m.getBattleType());
            map.put("matchDate", m.getMatchDate());
            map.put("myDjName", m.getMyDjName());

            // 手順2: 4 人分の参加者情報を JSON 文字列からパース。失敗時は空 List に畳む。
            List<Map<String, Object>> players = List.of();
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parsed = objectMapper.readValue(m.getPlayersJson(), List.class);
                players = parsed;
            } catch (Exception ignored) {}
            map.put("players", players);

            try {
                // 手順2b: 選曲リストを同様にパース。
                map.put("songs", objectMapper.readValue(m.getSongsJson(), List.class));
            } catch (Exception ignored) {
                map.put("songs", List.of());
            }

            // 手順3: 自身の結果データを DB 列から取得。古レコードは 0/空のままのことがある。
            int myRank = m.getMyRank() != null ? m.getMyRank() : 0;
            int myTotalPt = m.getMyTotalPt() != null ? m.getMyTotalPt() : 0;
            String myArenaClass = m.getMyArenaClass() != null ? m.getMyArenaClass() : "";
            String myDjName = m.getMyDjName();

            // 手順3b: myRank が未設定（0）の場合、players 配列内の自分と一致する要素から補完する。
            if (myRank == 0 && myDjName != null && !myDjName.isEmpty()) {
                for (Map<String, Object> p : players) {
                    if (myDjName.equals(p.get("djName"))) {
                        // JSON 由来の Object を instanceof で絞り込んでから型変換する（ClassCast 防止）。
                        Object rankObj = p.get("rank");
                        Object ptObj = p.get("totalPt");
                        Object clsObj = p.get("arenaClass");
                        if (rankObj instanceof Number) myRank = ((Number) rankObj).intValue();
                        if (ptObj instanceof Number) myTotalPt = ((Number) ptObj).intValue();
                        if (clsObj instanceof String) myArenaClass = (String) clsObj;
                        break;
                    }
                }
            }

            map.put("myArenaClass", myArenaClass);
            map.put("myRank", myRank);
            map.put("myTotalPt", myTotalPt);

            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 全ユーザーの BEAT-PT / RATE-PT を非同期で一括再計算する。
     *
     * 処理の流れ:
     *  1. 管理者チェック後、{@link ScoreRecalculationService} へ楽曲データ・難易度表を渡して
     *     全ユーザーの再計算ジョブをバックグラウンドで開始。
     *  2. 再計算内容に応じてトップランカー BEAT-PT キャッシュも別スレッドで再構築。
     *  3. HTTP は即 202 Accepted を返し、呼び出し元をブロックしない。
     *
     * @param auth 認証情報（管理者限定）
     * @param req  楽曲 JSON と難易度表 JSON を含むリクエスト
     * @return 202 + 非同期開始メッセージ。初期化段階で失敗したら 500
     */
    @PostMapping("/recalculate-points")
    public ResponseEntity<Map<String, Object>> recalculatePoints(
            Authentication auth,
            @RequestBody RecalculatePointsRequest req) {

        checkAdminAccess(auth);

        try {
            // 手順1: 全ユーザー再計算ジョブを非同期で起動する。
            scoreRecalculationService.recalculateAllUsersAsync(req.songDataJson(), req.difficultyTableJson());
            // 手順2: 楽曲データ／難易度表が変化した → トップランカー BEAT-PT キャッシュも再構築。
            new Thread(() -> topRankersBeatPtService.recompute(), "top-rankers-recompute").start();
            // 手順3: クライアントには即 202 を返す（ジョブは裏で継続中）。
            return ResponseEntity.accepted().body(Map.of("message", "Recalculation started in background"));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("message", "Error starting recalculation: " + e.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 score_history_logs の最新レコードから users.total_beat_pt を一括移行する。
     *
     * 新カラム {@code users.total_beat_pt} 追加直後に一度だけ実行する用途のマイグレーション API。
     * 各ユーザーの最新スナップショットの totalBeatPt を users テーブルにコピーする。
     *
     * @param auth 認証情報（管理者限定）
     * @return 更新件数を含むメッセージ
     */
    @PostMapping("/migrate-user-beat-pt")
    public ResponseEntity<Map<String, Object>> migrateUserBeatPt(Authentication auth) {
        checkAdminAccess(auth);
        List<User> users = userRepository.findAll();
        int updated = 0;
        for (User u : users) {
            // 各ユーザーの最新スナップショット（uploadedAt 降順の最初の 1 件）を取得。
            scoreHistoryLogRepository.findFirstByUserOrderByUploadedAtDesc(u).ifPresent(log -> {
                // totalBeatPt が null/0 のレコードは意味のないデータなのでコピーしない。
                if (log.getTotalBeatPt() != null && log.getTotalBeatPt() > 0) {
                    u.setTotalBeatPt(log.getTotalBeatPt());
                    userRepository.save(u);
                }
            });
            // 注意: updated は「処理対象ユーザー数」であり、実際に更新された件数ではない。
            updated++;
        }
        return ResponseEntity.ok(Map.of("message", updated + " 件のユーザーのtotalBeatPtを更新しました"));
    }

    /**
     * 【メソッドの役割】 全ユーザーの Web Push 購読情報を一括で null クリアする。
     *
     * VAPID 鍵の再発行等、購読情報を強制的に捨てたい運用時に使う緊急ボタン。
     *
     * @param auth 認証情報（管理者限定）
     * @return 完了メッセージ
     */
    @PostMapping("/push/clear-all")
    public ResponseEntity<Map<String, Object>> clearAllPushSubscriptions(Authentication auth) {
        checkAdminAccess(auth);
        userRepository.clearAllPushSubscriptions();
        return ResponseEntity.ok(Map.of("message", "全てのユーザーのプッシュ通知設定を初期化しました。"));
    }

    /**
     * 【メソッドの役割】 total_rate_pt = 0 の履歴ログを、現在のスコアから再計算して補正する。
     *
     * 処理の流れ:
     *  1. songDataJson をパースし「曲 + 難易度 → ノート数 × 2（= MAX スコア）」の lookup を作る。
     *  2. lookup を {@link ScoreRecalculationService#patchZeroRatePtLogs} に渡し、0 レコードを補完させる。
     *
     * recalculate-points と同じリクエストボディを流用するが、songDataJson のみ使用する。
     *
     * @param auth 認証情報（管理者限定）
     * @param req  songDataJson / difficultyTableJson を含むリクエスト（difficultyTableJson は未使用）
     * @return 補正件数を含むメッセージ。JSON パース失敗時は 500
     */
    @PostMapping("/patch-rate-pt")
    public ResponseEntity<Map<String, Object>> patchRatePt(
            Authentication auth,
            @RequestBody RecalculatePointsRequest req) {

        checkAdminAccess(auth);

        try {
            // 手順1: songDataJson をツリー構造に展開する（巨大 JSON のためストリームではなく全読み）。
            com.fasterxml.jackson.databind.JsonNode songDataRoot = objectMapper.readTree(req.songDataJson());
            java.util.Map<String, Integer> songMaxScores = new java.util.HashMap<>();
            // 手順1b: body 配列を走査して MAX スコア（ノート数 × 2）lookup を構築。
            if (songDataRoot.has("body") && songDataRoot.get("body").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode s : songDataRoot.get("body")) {
                    String title = s.path("title").asText().trim();
                    String diffCode = s.path("difficulty").asText();
                    int notes = s.path("notes").asInt(0);
                    if (notes > 0) {
                        // キーは "タイトル_難易度コード"。IIDX のスコア上限はノート数 × 2。
                        songMaxScores.put(title + "_" + diffCode, notes * 2);
                    }
                }
            }
            // 手順2: サービスに RATE-PT=0 ログの補正を委譲する。
            int patched = scoreRecalculationService.patchZeroRatePtLogs(songMaxScores);
            return ResponseEntity.ok(Map.of("message", patched + " 件の履歴ログを補正しました"));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("message", "Error: " + e.getMessage()));
        }
    }
}
