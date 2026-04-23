package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.AdminAuthService;
import com.beatseeker.backend.service.GameDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【クラスの役割】 ゲーム基礎データ（楽曲メタデータ・難易度表）の公開／管理 API を束ねるコントローラ。
 *
 * 本アプリでは「公開中の楽曲／難易度表」と「管理者が編集中のドラフト」の
 * 2 系統を持ち、管理者がドラフトを整えて {@code apply} することで
 * 公開データに昇格させる運用をしている。ドラフト適用時は全ユーザーの
 * スコアポイントを再計算する重い処理をバックグラウンドで走らせる。
 *
 * エンドポイント分類:
 *  - 公開: {@code /api/game-data/*} … 誰でも取得可能（楽曲一覧、難易度表）
 *  - 管理: {@code /api/admin/game-data/*} … 管理者のみ操作可能
 *
 * 注意: 「管理者判定」は {@link AdminAuthService} に集約されており、
 *   設定 {@code admin.user-id} / {@code admin.iidx-id} で上書きできる。
 *   将来的にロールベース認可に置き換える前提の暫定実装。
 */
@RestController
@RequestMapping("/api")
public class GameDataController {

    /** 楽曲・難易度表の取得／更新ロジックを委譲する Service。 */
    private final GameDataService gameDataService;
    /** 管理者判定のため、iidxId → User を引く Repository。 */
    private final UserRepository userRepository;
    /** 管理者判定のロジックを共通化した Service。 */
    private final AdminAuthService adminAuthService;

    /**
     * 【コンストラクタ】 Spring が Service/Repository を DI で注入する。
     */
    public GameDataController(GameDataService gameDataService,
                              UserRepository userRepository,
                              AdminAuthService adminAuthService) {
        this.gameDataService = gameDataService;
        this.userRepository = userRepository;
        this.adminAuthService = adminAuthService;
    }

    // ── 公開エンドポイント ──────────────────────────────
    //   認証不要。フロントや外部スクリプト（ブックマークレット）から利用。

    /**
     * 【メソッドの役割】 現在公開中の楽曲メタデータを JSON 文字列で返す。
     *
     * Service 側でキャッシュ or DB から JSON を組み立てて返すため、
     * ここでは Content-Type を明示的に {@code application/json} に設定して透過的に返すだけ。
     *
     * @return 楽曲メタデータ JSON（そのまま JavaScript から fetch してパースできる）
     */
    @GetMapping("/game-data/songs")
    public ResponseEntity<String> getActiveSongs() {
        try {
            String json = gameDataService.getActiveSongDataJson();
            return ResponseEntity.ok().header("Content-Type", "application/json").body(json);
        } catch (Exception e) {
            // 失敗時は JSON 形式でエラーメッセージを返す（フロントの fetch が扱いやすい）
            return ResponseEntity.status(500).body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * 【メソッドの役割】 現在公開中の難易度表（tier 配置情報）を JSON で返す。
     *
     * @return 難易度表 JSON
     */
    @GetMapping("/game-data/difficulty-table")
    public ResponseEntity<String> getActiveDifficultyTable() {
        try {
            String json = gameDataService.getActiveDifficultyTableJson();
            return ResponseEntity.ok().header("Content-Type", "application/json").body(json);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // ── 管理者エンドポイント ─────────────────────────────
    //   {@link #checkAdminAccess} を必ず最初に呼ぶこと。

    /**
     * 【メソッドの役割】 管理者向けに、ドラフト（編集中）状態の楽曲一覧を返す。
     *
     * @param auth 認証情報（管理者であることを要求）
     * @return ドラフト中の SongDefinition リスト
     */
    @GetMapping("/admin/game-data/songs/draft")
    public ResponseEntity<List<SongDefinition>> getDraftSongs(Authentication auth) {
        checkAdminAccess(auth);
        return ResponseEntity.ok(gameDataService.getDraftSongs());
    }

    /**
     * 【メソッドの役割】 ドラフトに楽曲を追加する。
     *
     * 楽曲は譜面ごとに 1 レコードになるため、フロントから送られた 1 つの楽曲フォームから
     * 複数の SongDefinition が作成されうる（ノーマル/ハイパー/アナザー等）。
     *
     * @param auth     管理者認証
     * @param songForm フロントから送られる楽曲フォーム（title, difficulty 情報等を含む）
     * @return 追加件数と作成された SongDefinition 一覧
     */
    @PostMapping("/admin/game-data/songs/draft")
    public ResponseEntity<Map<String, Object>> addDraftSong(
            Authentication auth,
            @RequestBody Map<String, Object> songForm) {
        checkAdminAccess(auth);
        try {
            List<SongDefinition> created = gameDataService.addDraftSong(songForm);
            Map<String, Object> result = new HashMap<>();
            result.put("message", created.size() + " 件のレコードをドラフトに追加しました");
            result.put("created", created);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            // 入力不正など想定される失敗は 400 で返す
            return ResponseEntity.status(400).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 ドラフト楽曲を 1 件削除する。
     *
     * @param auth 管理者認証
     * @param id   削除対象の SongDefinition ID
     */
    @DeleteMapping("/admin/game-data/songs/draft/{id}")
    public ResponseEntity<Map<String, Object>> deleteDraftSong(
            Authentication auth,
            @PathVariable Long id) {
        checkAdminAccess(auth);
        try {
            gameDataService.deleteDraftSong(id);
            return ResponseEntity.ok(Map.of("message", "ドラフト楽曲を削除しました"));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 ドラフト状態の難易度表 JSON を取得する。
     *
     * @param auth 管理者認証
     * @return ドラフト難易度表 JSON
     */
    @GetMapping("/admin/game-data/difficulty-table/draft")
    public ResponseEntity<String> getDraftDifficultyTable(Authentication auth) {
        checkAdminAccess(auth);
        try {
            String json = gameDataService.getDraftDifficultyTableJson();
            return ResponseEntity.ok().header("Content-Type", "application/json").body(json);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * 【メソッドの役割】 ドラフト難易度表を丸ごと置き換える。
     *
     * @param auth 管理者認証
     * @param json 置き換え後の難易度表 JSON（RequestBody として生文字列を受け取る）
     */
    @PutMapping("/admin/game-data/difficulty-table/draft")
    public ResponseEntity<Map<String, Object>> saveDraftDifficultyTable(
            Authentication auth,
            @RequestBody String json) {
        checkAdminAccess(auth);
        try {
            gameDataService.saveDraftDifficultyTable(json);
            return ResponseEntity.ok(Map.of("message", "難易度表のドラフトを保存しました"));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 楽曲ドラフトのみを「公開データ」として適用する。
     *
     * Service 側で重い処理（全ユーザースコアのポイント再計算）をバックグラウンド実行するため、
     * レスポンスは 202 Accepted（受け付け完了、処理は非同期）で返す。
     *
     * @param auth 管理者認証
     */
    @PostMapping("/admin/game-data/apply/songs")
    public ResponseEntity<Map<String, Object>> applyDraftSongs(Authentication auth) {
        checkAdminAccess(auth);
        try {
            gameDataService.applyDraftSongs();
            return ResponseEntity.ok(Map.of("message", "楽曲ドラフトを適用しました（Lv11/12 譜面は Uncategorized に追加。BEAT-PT 再計算なし）"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message", "適用エラー: " + e.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 難易度表ドラフトのみを「公開データ」として適用する。
     *
     * Service 側で重い処理（全ユーザースコアのポイント再計算）をバックグラウンド実行するため、
     * レスポンスは 202 Accepted（受け付け完了、処理は非同期）で返す。
     *
     * @param auth 管理者認証
     */
    @PostMapping("/admin/game-data/apply/difficulty")
    public ResponseEntity<Map<String, Object>> applyDraftDifficultyTable(Authentication auth) {
        checkAdminAccess(auth);
        try {
            gameDataService.applyDraftDifficultyTable();
            return ResponseEntity.accepted().body(Map.of("message", "難易度表ドラフトを適用しました。バックグラウンドでポイント再計算を開始します。"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message", "適用エラー: " + e.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 ドラフトに未適用の変更があるかを返す。管理画面のバナー表示に使う。
     *
     * @param auth 管理者認証
     * @return {@code {"hasDraftSongs": bool, "hasDraftDifficultyTable": bool}}
     */
    @GetMapping("/admin/game-data/status")
    public ResponseEntity<Map<String, Object>> getDraftStatus(Authentication auth) {
        checkAdminAccess(auth);
        Map<String, Object> status = new HashMap<>();
        status.put("hasDraftSongs", gameDataService.hasDraftSongs());
        status.put("hasDraftDifficultyTable", gameDataService.hasDraftDifficultyTable());
        return ResponseEntity.ok(status);
    }

    // ── 管理者チェック ───────────────────────────────────────

    /**
     * 管理者権限チェック。認証が通っていて、かつ {@link AdminAuthService} が
     * 管理者と判定することを確認する。
     *
     * TODO: ロールベース認可へ置き換える（現状は運営者アカウント 1 つを設定値で指定）。
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
        // 管理者判定は AdminAuthService に集約（admin.user-id / admin.iidx-id で上書き可）。
        if (!adminAuthService.isAdmin(user)) {
            throw new RuntimeException("Access Denied: Admin privileges required.");
        }
    }
}
