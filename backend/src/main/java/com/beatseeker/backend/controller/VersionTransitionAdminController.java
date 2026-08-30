package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.SystemTaskRun;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.SystemTaskRunRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.AdminAuthService;
import com.beatseeker.backend.service.VersionTransitionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【Controller の役割】 世代切り替え（新作稼働時の移行）の準備状況を管理者が確認するための読み取り API。
 *
 * 利用者向けの画面には一切現れない。管理者だけがアクセスでき、副作用のある操作は持たない
 * （実行は {@link com.beatseeker.backend.service.VersionTransitionScheduler} のタイマーが担う）。
 *
 * 既存の {@link AdminController} に足さず別クラスにしてあるのは、稼働中の管理機能に手を入れずに
 * 追加できるようにするため。管理者判定のやり方は他の管理系 Controller と同じ。
 *
 * エンドポイント:
 *  - GET /api/admin/version-transition/status … 件数と各手順の実行記録を返す
 */
@RestController
@RequestMapping("/api")
public class VersionTransitionAdminController {

    private final VersionTransitionService transitionService;
    private final SystemTaskRunRepository taskRunRepository;
    private final UserRepository userRepository;
    private final AdminAuthService adminAuthService;

    public VersionTransitionAdminController(VersionTransitionService transitionService,
                                            SystemTaskRunRepository taskRunRepository,
                                            UserRepository userRepository,
                                            AdminAuthService adminAuthService) {
        this.transitionService = transitionService;
        this.taskRunRepository = taskRunRepository;
        this.userRepository = userRepository;
        this.adminAuthService = adminAuthService;
    }

    /**
     * 【メソッドの役割】 移行の準備状況を返す。
     *
     * 返す内容:
     *  - counts … スナップショット件数 / 過去作スコア件数 / 現行スコア件数 / 履歴を持つユーザー数 /
     *             派生データ（譜面順位・比較集計）の残量 / 世代リセット行の数 /
     *             未適用の難易度表 draft の有無
     *  - runs   … 各手順（snapshot / copy-scores / apply-difficulty / reset-scores）の実行記録
     *
     * @param auth    管理者認証
     * @param version 対象の作品バージョン（既定 33）
     */
    @GetMapping("/admin/version-transition/status")
    public ResponseEntity<Map<String, Object>> status(Authentication auth,
                                                      @RequestParam(defaultValue = "33") int version) {
        checkAdminAccess(auth);

        List<Map<String, Object>> runs = new ArrayList<>();
        for (SystemTaskRun run : taskRunRepository.findByTaskKeyStartingWithOrderByStartedAtAsc("version-transition:")) {
            Map<String, Object> row = new HashMap<>();
            row.put("taskKey", run.getTaskKey());
            row.put("status", run.getStatus().name());
            row.put("startedAt", run.getStartedAt());
            row.put("finishedAt", run.getFinishedAt());
            row.put("detail", run.getDetail());
            runs.add(row);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("counts", transitionService.describe(version));
        body.put("runs", runs);
        return ResponseEntity.ok(body);
    }

    // ── 管理者チェック ───────────────────────────────────────

    /**
     * 管理者権限チェック。他の管理系 Controller と同じく {@link AdminAuthService} に判定を委ねる。
     *
     * @param auth 認証情報
     * @throws RuntimeException 未認証・ユーザー不明・管理者でない場合
     */
    private void checkAdminAccess(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        User user = userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (!adminAuthService.isAdmin(user)) {
            throw new RuntimeException("Access Denied: Admin privileges required.");
        }
    }
}
