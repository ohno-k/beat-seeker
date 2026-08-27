package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.ArenaMatch;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ArenaMatchRepository;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.repository.UserSongOptionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.beatseeker.backend.service.AdminAuthService;
import com.beatseeker.backend.service.ScoreRecalculationService;
import com.beatseeker.backend.service.TopRankersBeatPtService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
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
 *  - POST /api/admin/arena-top-rankers/recompute    … アリーナ仮想プレイヤー集計キャッシュの再構築（同期）
 *  - POST /api/admin/migrate-user-beat-pt           … users.total_beat_pt の一括移行
 *  - POST /api/admin/push/clear-all                 … 全ユーザーの push 購読を初期化
 *  - POST /api/admin/patch-rate-pt                  … RATE-PT = 0 の履歴ログを補正
 *  - POST /api/admin/patch-history-rate-pt          … diffJson 内の newRatePt = 0/null を補正
 *  - GET  /api/admin/users/{id}/comparison          … 1 ユーザー vs 全ユーザーの勝敗（日次バッチの集計結果）
 *  - POST /api/admin/user-comparison/recalculate    … 上記集計の全ユーザー再構築
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
    /** アリーナ仮想プレイヤー（アリーナTOP RANKER取り込み）の集計キャッシュサービス。 */
    private final com.beatseeker.backend.service.VirtualArenaRankerService virtualArenaRankerService;
    /** playersJson / songsJson をパースするための Jackson ObjectMapper。 */
    private final ObjectMapper objectMapper;
    /** 管理者判定のロジックを共通化した Service。 */
    private final AdminAuthService adminAuthService;
    /** 連携アプリから同期された譜面オプション。スコア応答に options を埋めるのに使う。 */
    private final UserSongOptionRepository userSongOptionRepository;
    /** 個別トランザクションで直接 SQL を流すための JDBC テンプレート（診断用途）。 */
    private final JdbcTemplate jdbcTemplate;
    /** SET LOCAL を効かせるための明示的トランザクション境界（診断用途）。 */
    private final TransactionTemplate transactionTemplate;
    /** ユーザー間スコア比較の日次バッチ／その場集計。 */
    private final com.beatseeker.backend.service.UserComparisonStatsService userComparisonStatsService;

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
                           com.beatseeker.backend.service.VirtualArenaRankerService virtualArenaRankerService,
                           ObjectMapper objectMapper,
                           AdminAuthService adminAuthService,
                           UserSongOptionRepository userSongOptionRepository,
                           JdbcTemplate jdbcTemplate,
                           TransactionTemplate transactionTemplate,
                           com.beatseeker.backend.service.UserComparisonStatsService userComparisonStatsService) {
        this.userComparisonStatsService = userComparisonStatsService;
        this.userSongOptionRepository = userSongOptionRepository;
        this.userRepository = userRepository;
        this.scoreRepository = scoreRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.arenaMatchRepository = arenaMatchRepository;
        this.scoreRecalculationService = scoreRecalculationService;
        this.topRankersBeatPtService = topRankersBeatPtService;
        this.virtualArenaRankerService = virtualArenaRankerService;
        this.objectMapper = objectMapper;
        this.adminAuthService = adminAuthService;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
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
        Map<String, List<String>> optionsMap = loadOptionsMap(targetUser);

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
            map.put("options", optionsMap.getOrDefault(optionsKey(s.getTitle(), s.getDifficultyName()), List.of()));
            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    /** ユーザーの UserSongOption を一括取得し (title||difficulty) キーの options マップに変換。 */
    private Map<String, List<String>> loadOptionsMap(User user) {
        Map<String, List<String>> result = new HashMap<>();
        for (com.beatseeker.backend.entity.UserSongOption o : userSongOptionRepository.findByUser(user)) {
            List<String> parsed;
            try {
                parsed = objectMapper.readValue(
                        o.getOptionsJson() == null ? "[]" : o.getOptionsJson(),
                        new TypeReference<List<String>>() {});
            } catch (Exception e) {
                parsed = List.of();
            }
            result.put(optionsKey(o.getTitle(), o.getDifficultyName()), parsed);
        }
        return result;
    }

    /** loadOptionsMap と整合する複合キー。 */
    private String optionsKey(String title, String difficultyName) {
        return (title == null ? "" : title) + "||" + (difficultyName == null ? "" : difficultyName);
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
            // 手順2b: アリーナ仮想プレイヤーの集計も同じ楽曲データ／難易度表に依存するため再構築。
            new Thread(() -> virtualArenaRankerService.recompute(), "virtual-arena-rankers-recompute").start();
            // 手順3: クライアントには即 202 を返す（ジョブは裏で継続中）。
            return ResponseEntity.accepted().body(Map.of("message", "Recalculation started in background"));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("message", "Error starting recalculation: " + e.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 アリーナ仮想プレイヤー（アリーナTOP RANKER取り込み）の
     * BEAT-PT / RATE-PT 集計キャッシュを **同期実行** で再構築する。
     *
     * スクレイプバッチ（{@code scripts/scrape-arena-top-rankers.js}）で
     * {@code virtual_arena_rankers} を更新した後に呼び出して、ランキング表示へ即時反映させる。
     * 同期実行し、完了後のキャッシュ状態を返す。
     *
     * @param auth 認証情報（管理者限定）
     * @return 再構築後のキャッシュ状態
     */
    @PostMapping("/arena-top-rankers/recompute")
    public ResponseEntity<Map<String, Object>> recomputeArenaTopRankers(Authentication auth) {
        checkAdminAccess(auth);
        try {
            virtualArenaRankerService.recompute();
            return ResponseEntity.ok(virtualArenaRankerService.getStatus());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("message", "Error recomputing arena top rankers: " + e.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 user_song_ranks キャッシュを **同期実行** で再構築する診断用エンドポイント。
     *
     * 通常版（{@code /api/scores/recalculate-song-ranks}）は非同期で 202 を返すため SQL 失敗が見えない。
     * このエンドポイントは TRUNCATE → INSERT を同期で走らせ、行数と例外メッセージを返す。
     *
     * 返却例（成功）: {@code {"ok": true, "rowCount": 24501, "columns": ["id", "user_id", ...]}}
     * 返却例（失敗）: {@code {"ok": false, "error": "...", "columns": [...]}}
     *
     * @param auth 認証情報（管理者限定）
     * @return 診断結果
     */
    @PostMapping("/recalculate-song-ranks-sync")
    public ResponseEntity<Map<String, Object>> recalculateSongRanksSync(Authentication auth) {
        checkAdminAccess(auth);
        Map<String, Object> result = new HashMap<>();

        // 手順1: user_song_ranks テーブルの実カラム名を information_schema から取得する。
        try {
            List<String> columns = jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_name = 'user_song_ranks' ORDER BY ordinal_position",
                    String.class);
            result.put("columns", columns);
        } catch (Exception e) {
            result.put("columnsError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // 手順2: INSERT 前の現在行数を記録する。
        try {
            Integer before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_song_ranks", Integer.class);
            result.put("rowCountBefore", before);
        } catch (Exception e) {
            result.put("rowCountBeforeError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // 手順3: TRUNCATE + INSERT を **単一トランザクション** で実行し、その中で
        // SET LOCAL statement_timeout = 0 を発行することで重い集計クエリのタイムアウトを回避する。
        // 失敗時は両方ロールバックされるためデータ消失も防げる。
        try {
            Integer inserted = transactionTemplate.execute(status -> {
                jdbcTemplate.execute("SET LOCAL statement_timeout = 0");
                jdbcTemplate.execute("TRUNCATE TABLE user_song_ranks");
                return jdbcTemplate.update(
                        "INSERT INTO user_song_ranks (user_id, title, difficulty_name, difficulty_level, rank_position, total, calculated_at) " +
                        "WITH best_scores AS ( " +
                        "  SELECT title, difficulty_name, difficulty_level, user_id, MAX(score) AS score " +
                        "  FROM scores " +
                        "  WHERE difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND score > 0 " +
                        "  GROUP BY title, difficulty_name, difficulty_level, user_id " +
                        "), " +
                        "all_ranks AS ( " +
                        "  SELECT title, difficulty_name, difficulty_level, user_id, score, " +
                        "    RANK() OVER (PARTITION BY title, difficulty_name ORDER BY score DESC) AS rank_position, " +
                        "    COUNT(*) OVER (PARTITION BY title, difficulty_name) AS total " +
                        "  FROM best_scores " +
                        ") " +
                        "SELECT user_id, title, difficulty_name, difficulty_level, rank_position, total, NOW() " +
                        "FROM all_ranks");
            });
            result.put("insertOk", true);
            result.put("inserted", inserted);
        } catch (Exception e) {
            result.put("insertOk", false);
            result.put("insertError", e.getClass().getName() + ": " + e.getMessage());
            Throwable cause = e.getCause();
            int depth = 0;
            while (cause != null && depth < 5) {
                result.put("insertCause" + depth, cause.getClass().getName() + ": " + cause.getMessage());
                cause = cause.getCause();
                depth++;
            }
        }

        // 手順4: INSERT 後の現在行数を返す。
        try {
            Integer after = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_song_ranks", Integer.class);
            result.put("rowCountAfter", after);
        } catch (Exception e) {
            result.put("rowCountAfterError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // 手順5: users.total_average_rank も同じ流れで更新する。
        // user_song_ranks が埋まった状態を前提に、リセット → 計算 UPDATE を単一トランザクションで実行。
        try {
            Integer updated = transactionTemplate.execute(status -> {
                jdbcTemplate.execute("SET LOCAL statement_timeout = 0");
                jdbcTemplate.update(
                        "UPDATE users SET total_average_rank = NULL, total_average_rank_played = NULL " +
                        "WHERE total_average_rank IS NOT NULL OR total_average_rank_played IS NOT NULL");
                return jdbcTemplate.update(
                        "WITH target_songs AS ( " +
                        "  SELECT title, difficulty_name, MAX(total) AS player_total " +
                        "  FROM user_song_ranks " +
                        "  WHERE difficulty_level IN (11, 12) " +
                        "    AND difficulty_name IN ('ANOTHER', 'LEGGENDARIA') " +
                        "  GROUP BY title, difficulty_name " +
                        "), " +
                        "played_users AS ( " +
                        "  SELECT DISTINCT user_id FROM user_song_ranks " +
                        "  WHERE difficulty_level IN (11, 12) " +
                        "    AND difficulty_name IN ('ANOTHER', 'LEGGENDARIA') " +
                        "), " +
                        "avg_per_user AS ( " +
                        "  SELECT pu.user_id, " +
                        "         AVG(COALESCE(usr.rank_position, ts.player_total + 1)) AS avg_rank, " +
                        "         COUNT(usr.rank_position) AS played_count " +
                        "  FROM played_users pu " +
                        "  CROSS JOIN target_songs ts " +
                        "  LEFT JOIN user_song_ranks usr " +
                        "    ON usr.user_id = pu.user_id " +
                        "   AND usr.title = ts.title " +
                        "   AND usr.difficulty_name = ts.difficulty_name " +
                        "  GROUP BY pu.user_id " +
                        ") " +
                        "UPDATE users u " +
                        "SET total_average_rank = ROUND(apu.avg_rank::numeric, 2), " +
                        "    total_average_rank_played = apu.played_count " +
                        "FROM avg_per_user apu " +
                        "WHERE u.id = apu.user_id");
            });
            result.put("avgUpdateOk", true);
            result.put("avgUpdated", updated);
        } catch (Exception e) {
            result.put("avgUpdateOk", false);
            result.put("avgUpdateError", e.getClass().getName() + ": " + e.getMessage());
            Throwable cause = e.getCause();
            int depth = 0;
            while (cause != null && depth < 5) {
                result.put("avgUpdateCause" + depth, cause.getClass().getName() + ": " + cause.getMessage());
                cause = cause.getCause();
                depth++;
            }
        }

        // 手順6: total_average_rank が埋まったユーザー数を確認する。
        try {
            Integer avgUsers = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE total_average_rank IS NOT NULL", Integer.class);
            result.put("usersWithAvgRank", avgUsers);
        } catch (Exception e) {
            result.put("usersWithAvgRankError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 指定 1 ユーザーの履歴ログを「初回登録扱い」で生成・追加する。
     *
     * 用途: スコアは登録されているが {@code score_history_logs} に行が無く、
     * ランキング・トレンド表示から漏れてしまっているユーザーを救済する。
     * active な曲定義 / 難易度表を使って BEAT-PT / RATE-PT / 各カウンタを再計算し、
     * 新しいスナップショットを 1 件追加する。
     *
     * @param auth   認証情報（管理者限定）
     * @param userId 対象ユーザーの DB 主キー
     * @return 成功メッセージ。スコア 0 件なら 400、ユーザー不在なら 404
     */
    @PostMapping("/users/{userId}/recalculate")
    public ResponseEntity<Map<String, Object>> recalculateSingleUser(Authentication auth,
                                                                     @PathVariable Long userId) {
        checkAdminAccess(auth);
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            Map<String, Object> body = new HashMap<>();
            body.put("message", "User " + userId + " not found");
            return ResponseEntity.status(404).body(body);
        }
        boolean added = scoreRecalculationService.recalculateSingleUserFromActiveData(user);
        Map<String, Object> body = new HashMap<>();
        if (!added) {
            body.put("message", "User " + userId + " has no scores to snapshot");
            return ResponseEntity.badRequest().body(body);
        }
        body.put("message", "User " + userId + " のスナップショットを追加しました");
        body.put("userId", userId);
        return ResponseEntity.ok(body);
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
     * 【メソッドの役割】 全ユーザーの KENBAN-PT / SARA-PT を現在のスコアから一括計算し、
     * users と各ユーザーの最新 ScoreHistoryLog の総合 pt カラムにバックフィルする。
     *
     * 暫定オンザフライ計算からの本実装移行（カラム追加直後の初回データ投入）用。
     * 2 回目以降は通常の {@link ScoreRecalculationService#recalculateAllUsersAsync} で
     * 自動的に値が更新されるため、本エンドポイントは原則 1 回だけ叩けばよい。
     *
     * 処理の流れ:
     *  1. songMaxScores / informalRanks / scratchMap を 1 度だけロード
     *  2. 全ユーザーのスコアを引いて KENBAN/SARA-PT を計算
     *  3. ユーザーの最新 ScoreHistoryLog があれば、その総合 pt 列を更新
     *  4. users テーブルのキャッシュ列も同期
     *
     * @param auth 認証情報（管理者限定）
     * @return 更新件数を含むメッセージ
     */
    @PostMapping("/backfill-kenban-sara-pt")
    public ResponseEntity<Map<String, Object>> backfillKenbanSaraPt(
            Authentication auth,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long from,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit) {
        checkAdminAccess(auth);

        var songMaxScores = scoreRecalculationService.loadSongMaxScores();
        var informalRanks = scoreRecalculationService.loadInformalRanks();
        var scratchMap    = scoreRecalculationService.loadScratchMap();

        // id 昇順で from より大きい id を limit 件取り出す。0 件取得 = 全件処理済み
        List<User> users = userRepository.findAll().stream()
            .filter(u -> u.getId() != null && u.getId() > from)
            .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
            .limit(Math.max(1, limit))
            .toList();

        int updatedLogs = 0;
        int updatedUsers = 0;
        long lastUserId = from;
        for (User u : users) {
            lastUserId = u.getId();
            var scores = scoreRepository.findByUserOrderByUploadedAtAsc(u);
            if (scores.isEmpty()) continue;
            double[] ks = scoreRecalculationService.calculateKenbanSaraPtFromActiveData(
                scores, songMaxScores, informalRanks, scratchMap);
            var latestLog = scoreHistoryLogRepository.findFirstByUserOrderByUploadedAtDesc(u);
            if (latestLog.isPresent()) {
                var log = latestLog.get();
                log.setTotalKenbanPt(ks[0]);
                log.setTotalSaraPt(ks[1]);
                scoreHistoryLogRepository.save(log);
                updatedLogs++;
            }
            u.setTotalKenbanPt(ks[0]);
            u.setTotalSaraPt(ks[1]);
            userRepository.save(u);
            updatedUsers++;
        }

        // limit に満たなければ「もう次は無い」とみなす
        boolean hasMore = users.size() >= limit && users.size() > 0;

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("processed",   users.size());
        body.put("updatedUsers", updatedUsers);
        body.put("updatedLogs",  updatedLogs);
        body.put("lastUserId",   lastUserId);
        body.put("hasMore",      hasMore);
        return ResponseEntity.ok(body);
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

    /**
     * 【メソッドの役割】 score_history_logs.diffJson 内の譜面別 {@code newRatePt} が
     * 欠落／null／0 になっている要素を、現在の楽曲マスタから再計算して埋める。
     *
     * 用途: 外部公開 API {@code /api/external/v1/song-detail} のレスポンス
     * {@code history[].ratePt} が、RATE-PT 機能の導入前に作られた古いスナップショットだけ
     * null になる問題を解消する一括パッチ。
     *
     * {@link #patchRatePt(Authentication, RecalculatePointsRequest)} はユーザー単位の
     * 累計 {@code total_rate_pt} だけを補正するのに対し、こちらは個別曲の RATE-PT
     * スナップショットを diffJson 内で補正する。
     *
     * リクエストボディは {@code /patch-rate-pt} と共用（songDataJson のみ使用）。
     *
     * @param auth 認証情報（管理者限定）
     * @param req  songDataJson を含むリクエスト
     * @return 修正したログ件数を含むメッセージ
     */
    @PostMapping("/patch-history-rate-pt")
    public ResponseEntity<Map<String, Object>> patchHistoryRatePt(
            Authentication auth,
            @RequestBody RecalculatePointsRequest req) {

        checkAdminAccess(auth);

        try {
            // patch-rate-pt と同じ手順で songMaxScores ルックアップを作る。
            com.fasterxml.jackson.databind.JsonNode songDataRoot = objectMapper.readTree(req.songDataJson());
            java.util.Map<String, Integer> songMaxScores = new java.util.HashMap<>();
            if (songDataRoot.has("body") && songDataRoot.get("body").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode s : songDataRoot.get("body")) {
                    String title = s.path("title").asText().trim();
                    String diffCode = s.path("difficulty").asText();
                    int notes = s.path("notes").asInt(0);
                    if (notes > 0) {
                        songMaxScores.put(title + "_" + diffCode, notes * 2);
                    }
                }
            }
            int patched = scoreRecalculationService.patchZeroRatePtInDiffJson(songMaxScores);
            return ResponseEntity.ok(Map.of(
                    "message", patched + " 件のスナップショットの diffJson を補正しました"));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("message", "Error: " + e.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 1 ユーザーを主体に、全ユーザーとの EX-SCORE 勝敗を返す。
     *
     * 管理画面「ユーザー間スコア比較」用。以前は 2 ユーザーを選んで突き合わせていたが、
     * 「1 人選んだら全員との勝敗が勝率降順で出る」形に変えたため、
     * リクエスト毎の総当たりをやめて日次バッチの集計結果
     * （{@link com.beatseeker.backend.service.UserComparisonStatsService}）を配る。
     *
     * 今日ぶんの集計がまだ無ければ、その場でこのユーザー 1 人ぶんだけ集計してから返す
     * （初回アクセスやバッチ未実行でも空表を返さない）。
     *
     * レベル帯（LV10MINUS / LV11 / LV12）別に数えた行をそのまま返し、
     * 画面のレベルトグルに応じてフロント側で足し合わせる。
     *
     * @param auth   認証情報（管理者限定）
     * @param userId 主体となるユーザーの DB 主キー
     * @return 相手ユーザーごとのレベル帯別勝敗
     * @throws RuntimeException 対象ユーザーが存在しない場合
     */
    @GetMapping("/users/{userId}/comparison")
    public ResponseEntity<Map<String, Object>> getUserComparison(
            Authentication auth,
            @PathVariable Long userId) {

        checkAdminAccess(auth);

        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Target user not found"));

        List<com.beatseeker.backend.entity.UserComparisonStat> stats =
                userComparisonStatsService.ensureStatsForUser(userId);

        // 相手 ID → 表示名／IIDX ID を引くための lookup。ユーザー数ぶんの 1 クエリで済ませる。
        Map<Long, User> userById = new HashMap<>();
        for (User u : userRepository.findAll()) {
            userById.put(u.getId(), u);
        }

        // 相手ごとに 1 件へ畳み、その中でレベル帯別の内訳を持たせる。
        Map<Long, Map<String, Object>> byOpponent = new java.util.LinkedHashMap<>();
        java.time.LocalDateTime computedAt = null;
        for (com.beatseeker.backend.entity.UserComparisonStat stat : stats) {
            User opponent = userById.get(stat.getOpponentId());
            // 集計後に退会した相手の行は表示できないので落とす。
            if (opponent == null) continue;

            Map<String, Object> entry = byOpponent.computeIfAbsent(stat.getOpponentId(), id -> {
                Map<String, Object> m = new HashMap<>();
                m.put("userId", id);
                m.put("displayName", opponent.getDisplayName() != null ? opponent.getDisplayName() : "");
                m.put("iidxId", opponent.getIidxId() != null ? opponent.getIidxId() : "");
                m.put("levels", new HashMap<String, Object>());
                return m;
            });

            @SuppressWarnings("unchecked")
            Map<String, Object> levels = (Map<String, Object>) entry.get("levels");
            Map<String, Object> counts = new HashMap<>();
            counts.put("win", stat.getWin());
            counts.put("loss", stat.getLoss());
            counts.put("draw", stat.getDraw());
            counts.put("onlySelf", stat.getOnlySelf());
            counts.put("onlyOpponent", stat.getOnlyOpponent());
            levels.put(stat.getLevelCategory().name(), counts);

            // 表示用の「集計時刻」は最新の行に合わせる。
            if (computedAt == null || stat.getComputedAt().isAfter(computedAt)) {
                computedAt = stat.getComputedAt();
            }
        }

        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("displayName", targetUser.getDisplayName() != null ? targetUser.getDisplayName() : "");
        body.put("iidxId", targetUser.getIidxId() != null ? targetUser.getIidxId() : "");
        body.put("computedAt", computedAt != null ? computedAt.toString() : null);
        body.put("opponents", new ArrayList<>(byOpponent.values()));
        return ResponseEntity.ok(body);
    }

    /**
     * 【メソッドの役割】 ユーザー間スコア比較の集計を全ユーザーぶん作り直す。
     *
     * 日次バッチを待たずに最新スコアを反映させたい時の手動トリガー。
     * 同期実行なのでユーザー数が多いと数十秒かかり得る。
     *
     * @param auth 認証情報（管理者限定）
     * @return 書き込んだ行数を含むメッセージ
     */
    @PostMapping("/user-comparison/recalculate")
    public ResponseEntity<Map<String, Object>> recalculateUserComparison(Authentication auth) {
        checkAdminAccess(auth);
        try {
            int rows = userComparisonStatsService.refreshAll();
            return ResponseEntity.ok(Map.of(
                    "message", "ユーザー間スコア比較の集計を再構築しました (" + rows + " 行)",
                    "rows", rows));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("message", "Error: " + e.getMessage()));
        }
    }
}
