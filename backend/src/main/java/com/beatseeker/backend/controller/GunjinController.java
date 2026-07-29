package com.beatseeker.backend.controller;

import com.beatseeker.backend.service.GunjinGameService;
import com.beatseeker.backend.service.GunjinGameService.GunjinException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 【クラスの役割】 軍人将棋（隠しページ {@code /lounge}）の REST コントローラ。
 *
 * 認可方針: beat-seeker のログインを要求しない。アカウントを持たない友人と遊ぶための隠し機能で、
 * <b>入室コード + 入室時に発行されるトークン</b>が本人確認材料になる
 * （{@code /api/competition-access/**} と同じ考え方）。
 * トークンの照合と「相手の駒を伏せる」視界制限はすべて {@link GunjinGameService} 側で行う。
 *
 * パス名について: 隠し機能なので API パスも機能名を出さず {@code /api/lounge/**} にしている。
 *
 * 主なエンドポイント:
 *  - GET  /api/lounge/board          … 盤の形と駒の定義（静的データ）
 *  - POST /api/lounge/rooms          … 部屋を作る（先手として入室）
 *  - POST /api/lounge/rooms/join     … 入室コードで入る（後手として入室）
 *  - GET  /api/lounge/rooms/{code}   … 自分から見た盤の状態
 *  - GET  /api/lounge/rooms/{code}/suggest-setup … おまかせ布陣の生成
 *  - POST /api/lounge/rooms/{code}/setup   … 布陣の提出
 *  - POST /api/lounge/rooms/{code}/move    … 着手
 *  - POST /api/lounge/rooms/{code}/resign  … 投了
 */
@RestController
@RequestMapping("/api/lounge")
public class GunjinController {

    private final GunjinGameService service;

    /**
     * 【コンストラクタ】 Spring が対局サービスを注入して初期化する。
     *
     * @param service 対局進行サービス
     */
    public GunjinController(GunjinGameService service) {
        this.service = service;
    }

    /** 部屋作成・入室リクエスト。 */
    public record NameRequest(String name) {
    }

    /** 入室リクエスト（入室コード + 表示名）。 */
    public record JoinRequest(String roomCode, String name) {
    }

    /** 布陣の提出リクエスト。 */
    public record SetupRequest(Map<String, String> placements) {
    }

    /** 着手リクエスト。 */
    public record MoveRequest(String from, String to) {
    }

    /**
     * 【メソッドの役割】 盤の形（マス一覧）と駒の定義を返す。対局に入る前でも取得できる。
     *
     * 盤が長方形でない（河・2 マス幅の総司令部）ため、フロントはこの定義をそのまま描画に使う。
     *
     * @return 盤定義
     */
    @GetMapping("/board")
    public ResponseEntity<?> board() {
        return ResponseEntity.ok(service.boardDefinition());
    }

    /**
     * 【メソッドの役割】 新しい部屋を作り、先手として入室する。
     *
     * @param req 表示名
     * @return {@code {roomCode, token, player}}。token はクライアントが保管する
     */
    @PostMapping("/rooms")
    public ResponseEntity<?> create(@RequestBody(required = false) NameRequest req) {
        return ResponseEntity.ok(service.createRoom(req == null ? null : req.name()));
    }

    /**
     * 【メソッドの役割】 入室コードで部屋に後手として入る。
     *
     * @param req 入室コードと表示名
     * @return {@code {roomCode, token, player}}
     */
    @PostMapping("/rooms/join")
    public ResponseEntity<?> join(@RequestBody JoinRequest req) {
        if (req == null) {
            throw new GunjinException("入室コードを入力してください");
        }
        return ResponseEntity.ok(service.joinRoom(req.roomCode(), req.name()));
    }

    /**
     * 【メソッドの役割】 自分から見た盤の状態を返す（相手の駒は伏せられている）。
     *
     * フロントはこれを数秒間隔でポーリングし、{@code stateVersion} の変化で再描画を判断する。
     *
     * @param code  入室コード
     * @param token 本人証明トークン
     * @return 視界制限つきの盤の状態
     */
    @GetMapping("/rooms/{code}")
    public ResponseEntity<?> state(@PathVariable String code, @RequestParam String token) {
        return ResponseEntity.ok(service.view(code, token));
    }

    /**
     * 【メソッドの役割】「おまかせ配置」用のランダム布陣を返す（提出はしない）。
     *
     * @param code  入室コード
     * @param token 本人証明トークン
     * @return マス ID → 駒種名
     */
    @GetMapping("/rooms/{code}/suggest-setup")
    public ResponseEntity<?> suggestSetup(@PathVariable String code, @RequestParam String token) {
        return ResponseEntity.ok(service.suggestSetup(code, token));
    }

    /**
     * 【メソッドの役割】 自陣 23 マスの布陣を提出する。両者が出し終わると対局が始まる。
     *
     * @param code  入室コード
     * @param token 本人証明トークン
     * @param req   マス ID → 駒種名
     * @return 提出後の盤の状態
     */
    @PostMapping("/rooms/{code}/setup")
    public ResponseEntity<?> setup(@PathVariable String code, @RequestParam String token,
                                   @RequestBody SetupRequest req) {
        service.submitSetup(code, token, req == null ? Map.of() : req.placements());
        return ResponseEntity.ok(service.view(code, token));
    }

    /**
     * 【メソッドの役割】 1 手指す。交戦の判定はサーバ（審判）が確定させる。
     *
     * @param code  入室コード
     * @param token 本人証明トークン
     * @param req   移動元・移動先のマス ID
     * @return 着手後の盤の状態
     */
    @PostMapping("/rooms/{code}/move")
    public ResponseEntity<?> move(@PathVariable String code, @RequestParam String token,
                                  @RequestBody MoveRequest req) {
        if (req == null) {
            throw new GunjinException("動かす駒を指定してください");
        }
        service.move(code, token, req.from(), req.to());
        return ResponseEntity.ok(service.view(code, token));
    }

    /**
     * 【メソッドの役割】 投了する。
     *
     * @param code  入室コード
     * @param token 本人証明トークン
     * @return 投了後の盤の状態
     */
    @PostMapping("/rooms/{code}/resign")
    public ResponseEntity<?> resign(@PathVariable String code, @RequestParam String token) {
        service.resign(code, token);
        return ResponseEntity.ok(service.view(code, token));
    }

    /**
     * 【メソッドの役割】 ルール違反・不正なリクエストを 400 + メッセージに変換する。
     *
     * フロントはこのメッセージをそのままトーストに出せばよい。
     *
     * @param e サービス層が投げた例外
     * @return {@code 400 {"error": "..."}}
     */
    @ExceptionHandler(GunjinException.class)
    public ResponseEntity<?> handleGunjinException(GunjinException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /**
     * 【メソッドの役割】 楽観ロックの衝突を「もう一度どうぞ」に変換する。
     *
     * 両者が同じ瞬間に布陣を提出した場合など、同じ行を同時に更新すると発生する。
     * 盤は壊れていない（片方の更新だけが弾かれた）ので、押し直せば通る。
     *
     * @param e Hibernate の楽観ロック例外
     * @return {@code 409 {"error": "..."}}
     */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<?> handleConflict(org.springframework.orm.ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(409)
                .body(Map.of("error", "相手と操作が重なりました。もう一度お試しください"));
    }
}
