package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.AdminAuthService;
import com.beatseeker.backend.service.BeatTierScale;
import com.beatseeker.backend.service.PracticeMenuService;
import com.beatseeker.backend.service.TierBenchmarkCacheService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【クラスの役割】 練習メニュー（Novice → Legend の週次カリキュラム）の REST コントローラ。
 *
 * <h3>公開範囲</h3>
 * 現在は <b>管理者専用</b>。全エンドポイントが {@link AdminAuthService} の判定を通る。
 * 集計の妥当性（登竜門譜面の選ばれ方、達成確率の当たり方）を実データで検証してから
 * 一般公開する前提の段階的リリースのため。
 *
 * <h3>エンドポイント</h3>
 * <pre>
 *   GET  /api/training/menu              … 今週のメニュー（無ければ生成）
 *   POST /api/training/menu/regenerate   … 今週のメニューを組み直す（週 3 回まで）
 *   GET  /api/training/radar             … 8 軸の弱点レーダー
 *   GET  /api/training/review            … 直前に締めた週の振り返り
 *   GET  /api/training/ladder            … 全ティアの到達 pt と登竜門譜面
 *   PUT  /api/training/settings          … 週あたりの想定プレイ数（提示する曲数が変わる）
 *   POST /api/admin/training/benchmark/refresh … ティア別ベンチマークの再集計（同期）
 * </pre>
 *
 * <p>{@code userId} を付けると管理者は他ユーザーぶんを見られる（推薦精度の検証用）。
 * 既存の {@code /api/admin/fill-recommendation} と同じ運用にしてある。
 */
@RestController
public class PracticeMenuController {

    /** 週次メニューの生成・採点ロジック。 */
    private final PracticeMenuService practiceMenuService;
    /** ティア別ベンチマーク（登竜門譜面）の集計キャッシュ。 */
    private final TierBenchmarkCacheService tierBenchmarkCacheService;
    /** iidxId → User の解決と、userId 指定時の対象ユーザー取得。 */
    private final UserRepository userRepository;
    /** 管理者判定。 */
    private final AdminAuthService adminAuthService;

    public PracticeMenuController(PracticeMenuService practiceMenuService,
                                  TierBenchmarkCacheService tierBenchmarkCacheService,
                                  UserRepository userRepository,
                                  AdminAuthService adminAuthService) {
        this.practiceMenuService = practiceMenuService;
        this.tierBenchmarkCacheService = tierBenchmarkCacheService;
        this.userRepository = userRepository;
        this.adminAuthService = adminAuthService;
    }

    /**
     * 【メソッドの役割】 今週の練習メニューを返す。無ければその場で生成する。
     *
     * 開いた時点で採点し直すので、アップロード後にこの API を叩けば結果が反映される。
     *
     * GET /api/training/menu[?userId=...]
     */
    @GetMapping("/api/training/menu")
    public ResponseEntity<Map<String, Object>> menu(Authentication auth,
                                                    @RequestParam(required = false) Long userId) {
        User target = resolveTarget(auth, userId);
        if (target == null) return forbidden();
        return ResponseEntity.ok(practiceMenuService.getOrCreateMenu(target));
    }

    /**
     * 【メソッドの役割】 今週のメニューを組み直す。
     *
     * POST /api/training/menu/regenerate[?userId=...]
     *
     * @return 組み直し後のメニュー。上限到達時は {@code error} を含む 429
     */
    @PostMapping("/api/training/menu/regenerate")
    public ResponseEntity<Map<String, Object>> regenerate(Authentication auth,
                                                          @RequestParam(required = false) Long userId) {
        User target = resolveTarget(auth, userId);
        if (target == null) return forbidden();
        Map<String, Object> result = practiceMenuService.regenerate(target);
        if (result.containsKey("error")) {
            return ResponseEntity.status(429).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 8 軸の弱点レーダーを返す。
     *
     * GET /api/training/radar[?userId=...]
     */
    @GetMapping("/api/training/radar")
    public ResponseEntity<Map<String, Object>> radar(Authentication auth,
                                                     @RequestParam(required = false) Long userId) {
        User target = resolveTarget(auth, userId);
        if (target == null) return forbidden();
        return ResponseEntity.ok(practiceMenuService.getRadar(target));
    }

    /**
     * 【メソッドの役割】 締め済みの週の振り返りを返す。
     *
     * GET /api/training/review[?week=2026-08-31][?userId=...]
     *
     * @param week 対象週の月曜（ISO 日付）。省略時は直近の締め済み週
     */
    @GetMapping("/api/training/review")
    public ResponseEntity<Map<String, Object>> review(Authentication auth,
                                                      @RequestParam(required = false) String week,
                                                      @RequestParam(required = false) Long userId) {
        User target = resolveTarget(auth, userId);
        if (target == null) return forbidden();
        LocalDate weekStart = null;
        if (week != null && !week.isBlank()) {
            try {
                weekStart = LocalDate.parse(week.trim());
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "week は YYYY-MM-DD 形式で指定してください"));
            }
        }
        return ResponseEntity.ok(practiceMenuService.getReview(target, weekStart));
    }

    /**
     * 【メソッドの役割】 全ティアの到達 pt と、そのティアを分ける登竜門譜面を返す。
     *
     * ログイン中のユーザー（または userId 指定のユーザー）の現在ティアに印を付ける。
     *
     * GET /api/training/ladder[?userId=...]
     */
    @GetMapping("/api/training/ladder")
    public ResponseEntity<Map<String, Object>> ladder(Authentication auth,
                                                      @RequestParam(required = false) Long userId) {
        User target = resolveTarget(auth, userId);
        if (target == null) return forbidden();

        double totalBeatPt = target.getTotalBeatPt() == null ? 0.0 : target.getTotalBeatPt();
        String currentTier = BeatTierScale.tierOf(totalBeatPt);

        List<Map<String, Object>> tiers = new ArrayList<>();
        for (BeatTierScale.Tier tier : BeatTierScale.TIERS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", tier.name());
            row.put("minPoints", tier.minPoints());
            row.put("isCurrent", tier.name().equals(currentTier));

            List<Map<String, Object>> gates = new ArrayList<>();
            for (TierBenchmarkCacheService.Gate gate : tierBenchmarkCacheService.getGates(tier.name())) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("title", gate.title());
                g.put("difficultyName", gate.difficultyName());
                g.put("borderLabel", gate.borderLabel());
                g.put("discrimination", gate.discrimination());
                g.put("upperReachRate", gate.upperReachRate());
                g.put("lowerReachRate", gate.lowerReachRate());
                g.put("standardRate", gate.standardRate());
                g.put("upperUserCount", gate.upperUserCount());
                gates.add(g);
            }
            // 「このティアから 1 つ上へ上がるための」登竜門譜面。最上位には存在しない。
            row.put("gates", gates);
            tiers.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentTier", currentTier);
        result.put("totalBeatPt", totalBeatPt);
        result.put("benchmarkReady", tierBenchmarkCacheService.isReady());
        result.put("benchmarkUpdatedAt", tierBenchmarkCacheService.getLastRefreshedAt());
        result.put("tiers", tiers);
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 週あたりの想定プレイ数を設定し、その場でメニューを組み直す。
     *
     * 提示する曲数はこの値に比例して増減する（週 20 プレイで 計測 2 / 課題 6 / 埋め 4）。
     * 設定変更による組み直しは「組み直す」の回数を消費しない。
     *
     * PUT /api/training/settings?weeklyPlays=30[&userId=...]
     *
     * @return 組み直し後のメニュー（{@link #menu} と同形）
     */
    @PutMapping("/api/training/settings")
    public ResponseEntity<Map<String, Object>> updateSettings(
            Authentication auth,
            @RequestParam Integer weeklyPlays,
            @RequestParam(required = false) Long userId) {

        User target = resolveTarget(auth, userId);
        if (target == null) return forbidden();
        if (weeklyPlays == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "weeklyPlays を指定してください"));
        }
        return ResponseEntity.ok(practiceMenuService.updateWeeklyPlays(target, weeklyPlays));
    }

    /**
     * 【メソッドの役割】 ティア別ベンチマークを即時に再集計する（管理者運用用）。
     *
     * 通常は日次バッチで走るが、初回投入直後や難易度表の改定直後に待ちたくない場合に叩く。
     * 数十秒かかる同期処理。
     *
     * POST /api/admin/training/benchmark/refresh
     */
    @PostMapping("/api/admin/training/benchmark/refresh")
    public ResponseEntity<Map<String, Object>> refreshBenchmark(Authentication auth) {
        if (resolveTarget(auth, null) == null) return forbidden();
        tierBenchmarkCacheService.refresh();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", tierBenchmarkCacheService.isReady());
        result.put("updatedAt", tierBenchmarkCacheService.getLastRefreshedAt());
        return ResponseEntity.ok(result);
    }

    // ── ヘルパー ─────────────────────────────────────────────────

    /**
     * 【メソッドの役割】 管理者判定を行い、操作対象のユーザーを返す。
     *
     * 練習メニューは検証段階のため管理者専用。管理者でなければ null を返し、
     * 呼び出し側が 403 に変換する。
     *
     * @param userId 指定があればそのユーザー、無ければ認証ユーザー自身
     * @return 対象ユーザー。権限が無い / 見つからない場合は null
     */
    private User resolveTarget(Authentication auth, Long userId) {
        if (auth == null || !auth.isAuthenticated()) return null;
        String iidxId = (String) auth.getPrincipal();
        User me = userRepository.findByIidxId(iidxId).orElse(null);
        if (me == null || !adminAuthService.isAdmin(me)) return null;
        if (userId == null) return me;
        return userRepository.findById(userId).orElse(null);
    }

    /** 権限が無い場合の共通応答。 */
    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "この機能は現在管理者のみ利用できます"));
    }
}
