package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.GunjinGame;
import com.beatseeker.backend.repository.GunjinGameRepository;
import com.beatseeker.backend.service.GunjinRules.Cell;
import com.beatseeker.backend.service.GunjinRules.Outcome;
import com.beatseeker.backend.service.GunjinRules.Piece;
import com.beatseeker.backend.service.GunjinRules.PieceType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 【クラスの役割】 軍人将棋の対局進行を司るサービス。オンライン対局における「審判」の身体。
 *
 * 判定そのものは {@link GunjinRules} が持ち、このクラスは
 * 「部屋の作成・入室 → 布陣の提出 → 交互の着手 → 決着」というライフサイクルと、
 * <b>各プレイヤーへ返す情報の絞り込み（視界制限）</b>を担当する。
 *
 * 視界制限が本質: 盤の真の状態は DB の 1 行にだけ存在し、
 * {@link #view} は閲覧者のトークンから陣営を特定して
 * 「自分の駒＝駒種まで見える / 相手の駒＝伏せ札として位置だけ見える」射影を作る。
 * 棋譜にも駒種を書かないため、API 応答を覗いても相手の布陣は分からない。
 *
 * 依存:
 *  - {@link GunjinGameRepository} … 対局の永続化
 *  - {@link ObjectMapper} … 盤・棋譜の JSON 変換
 */
@Service
public class GunjinGameService {

    /** 入室コードに使う文字集合。0/O/1/I/L など読み間違えやすい文字を除いている。 */
    private static final String CODE_CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    /** 入室コードの長さ。口頭で伝えられる短さにする。 */
    private static final int CODE_LENGTH = 4;
    /** 本人証明トークンの長さ（Base16 相当の文字数）。 */
    private static final int TOKEN_LENGTH = 32;
    /** この日数だけ更新が無い部屋は掃除対象にする。 */
    private static final int STALE_DAYS = 7;

    private final GunjinGameRepository repository;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    /**
     * 【コンストラクタ】 Spring がリポジトリと ObjectMapper を注入して初期化する。
     *
     * @param repository   対局リポジトリ
     * @param objectMapper 盤・棋譜の JSON 変換に使う Jackson
     */
    public GunjinGameService(GunjinGameRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** ルール違反・不正なリクエストを表す例外。Controller が 400 に変換する。 */
    public static class GunjinException extends RuntimeException {
        /**
         * @param message ユーザーに見せるエラーメッセージ（日本語）
         */
        public GunjinException(String message) {
            super(message);
        }
    }

    // ============================================================
    // 部屋の作成・入室
    // ============================================================

    /**
     * 【メソッドの役割】 新しい部屋を作り、先手として入室する。
     *
     * @param name 先手の表示名（空なら "先手"）
     * @return {@code {roomCode, token, player}}。token は呼び出し元が保管し、以降の全操作で提示する
     */
    @Transactional
    public Map<String, Object> createRoom(String name) {
        purgeStaleRooms();
        GunjinGame game = new GunjinGame();
        game.setRoomCode(generateRoomCode());
        game.setP1Token(generateToken());
        game.setP1Name(normalizeName(name, "先手"));
        game.setStatus(GunjinGame.Status.WAITING);
        game.setPiecesJson("[]");
        game.setLogJson("[]");
        game.setDeadJson("[]");
        touch(game);
        repository.save(game);
        return Map.of("roomCode", game.getRoomCode(), "token", game.getP1Token(), "player", 1);
    }

    /**
     * 【メソッドの役割】 入室コードで部屋に後手として入る。
     *
     * 既に後手が埋まっている部屋には入れない（観戦機能は持たない。
     * 軍人将棋は伏せ札のゲームなので、第三者に盤を見せる口を作らない方針）。
     *
     * @param roomCode 入室コード（大文字小文字は問わない）
     * @param name     後手の表示名（空なら "後手"）
     * @return {@code {roomCode, token, player}}
     */
    @Transactional
    public Map<String, Object> joinRoom(String roomCode, String name) {
        GunjinGame game = requireGame(roomCode);
        if (game.getP2Token() != null) {
            throw new GunjinException("この部屋はもう 2 人揃っています");
        }
        game.setP2Token(generateToken());
        game.setP2Name(normalizeName(name, "後手"));
        game.setStatus(GunjinGame.Status.SETUP);
        bumpVersion(game);
        touch(game);
        repository.save(game);
        return Map.of("roomCode", game.getRoomCode(), "token", game.getP2Token(), "player", 2);
    }

    // ============================================================
    // 布陣
    // ============================================================

    /**
     * 【メソッドの役割】 自陣 23 マスの布陣を提出する。
     *
     * 両者が提出した時点で {@code PLAYING} に移り、先手の手番から始まる。
     * 提出後の差し替えは不可（相手を待たせている間に組み替えられると不公平なため）。
     *
     * @param roomCode   入室コード
     * @param token      本人証明トークン
     * @param placements マス ID → 駒種名（例 {@code {"r9c2": "TAISHO", ...}}）
     */
    @Transactional
    public void submitSetup(String roomCode, String token, Map<String, String> placements) {
        GunjinGame game = requireGame(roomCode);
        int me = requirePlayer(game, token);
        if (game.getStatus() != GunjinGame.Status.SETUP) {
            throw new GunjinException(game.getStatus() == GunjinGame.Status.WAITING
                    ? "対戦相手の入室を待っています"
                    : "布陣の提出はもう締め切られています");
        }
        if (isReady(game, me)) {
            throw new GunjinException("すでに布陣を提出しています");
        }

        Map<String, PieceType> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : placements.entrySet()) {
            parsed.put(e.getKey(), parsePieceType(e.getValue()));
        }
        String error = GunjinRules.validateSetup(me, parsed);
        if (error != null) {
            throw new GunjinException(error);
        }

        // 相手の駒はそのまま残し、自分の駒だけを足す。
        List<Piece> pieces = new ArrayList<>(readPieces(game));
        pieces.removeIf(p -> p.owner() == me);
        int nextId = pieces.stream().mapToInt(Piece::id).max().orElse(0) + 1;
        for (Map.Entry<String, PieceType> e : parsed.entrySet()) {
            pieces.add(new Piece(nextId++, me, e.getValue(), e.getKey()));
        }
        writePieces(game, pieces);

        if (me == 1) {
            game.setP1Ready(true);
        } else {
            game.setP2Ready(true);
        }
        if (game.isP1Ready() && game.isP2Ready()) {
            game.setStatus(GunjinGame.Status.PLAYING);
            game.setTurn(1);
        }
        bumpVersion(game);
        touch(game);
        repository.save(game);
    }

    /**
     * 【メソッドの役割】「おまかせ配置」用のランダム布陣を作って返す（提出はしない）。
     *
     * @param roomCode 入室コード
     * @param token    本人証明トークン
     * @return マス ID → 駒種名
     */
    public Map<String, String> suggestSetup(String roomCode, String token) {
        GunjinGame game = requireGame(roomCode);
        int me = requirePlayer(game, token);
        Map<String, String> out = new LinkedHashMap<>();
        GunjinRules.randomSetup(me, random).forEach((cell, type) -> out.put(cell, type.name()));
        return out;
    }

    // ============================================================
    // 着手
    // ============================================================

    /**
     * 【メソッドの役割】 1 手指す。交戦が起きた場合の判定もここで確定させる。
     *
     * @param roomCode 入室コード
     * @param token    本人証明トークン
     * @param from     動かす駒のマス ID
     * @param to       移動先のマス ID
     */
    @Transactional
    public void move(String roomCode, String token, String from, String to) {
        GunjinGame game = requireGame(roomCode);
        int me = requirePlayer(game, token);
        if (game.getStatus() != GunjinGame.Status.PLAYING) {
            throw new GunjinException("対局中ではありません");
        }
        if (game.getTurn() != me) {
            throw new GunjinException("相手の手番です");
        }

        List<Piece> pieces = new ArrayList<>(readPieces(game));
        Map<String, Piece> occupied = GunjinRules.byCell(pieces);
        Piece mover = occupied.get(from);
        if (mover == null || mover.owner() != me) {
            throw new GunjinException("そのマスに自分の駒がありません");
        }
        boolean legal = GunjinRules.destinations(mover, occupied).stream()
                .anyMatch(c -> c.id().equals(to));
        if (!legal) {
            throw new GunjinException("その駒はそのマスへ動けません");
        }

        Piece target = occupied.get(to);
        String result;
        Piece landed = null;
        List<Map<String, Object>> dead = new ArrayList<>(readDead(game));
        int moveNo = game.getMoveCount() + 1;

        if (target == null) {
            // 空きマスへの前進。
            pieces.remove(mover);
            landed = mover.movedTo(to);
            pieces.add(landed);
            result = "MOVE";
        } else {
            // 交戦。審判（GunjinRules）の宣告に従って盤を書き換える。
            Outcome outcome = GunjinRules.resolve(mover, target, pieces);
            result = outcome.name();
            switch (outcome) {
                case ATTACKER_WINS -> {
                    pieces.remove(target);
                    pieces.remove(mover);
                    landed = mover.movedTo(to);
                    pieces.add(landed);
                    dead.add(deadEntry(target, moveNo));
                }
                case DEFENDER_WINS -> {
                    pieces.remove(mover);
                    dead.add(deadEntry(mover, moveNo));
                }
                case BOTH_LOSE -> {
                    pieces.remove(mover);
                    pieces.remove(target);
                    dead.add(deadEntry(mover, moveNo));
                    dead.add(deadEntry(target, moveNo));
                }
            }
        }

        writePieces(game, pieces);
        writeDead(game, dead);
        game.setMoveCount(moveNo);
        appendLog(game, moveNo, me, from, to, result);

        // 勝利条件 1: 敵の総司令部を大将〜少佐で占領した。
        Cell enemyHq = GunjinRules.hqCell(opponent(me));
        if (landed != null && landed.cell().equals(enemyHq.id()) && landed.type().canCaptureHq()) {
            finish(game, me, "HQ");
        } else {
            // 勝利条件 2: 相手に動かせる駒が残っていない（地雷・軍旗だけ、または全滅）。
            int next = opponent(me);
            if (GunjinRules.legalMoves(pieces, next).isEmpty()) {
                finish(game, me, "ANNIHILATED");
            } else {
                game.setTurn(next);
            }
        }

        bumpVersion(game);
        touch(game);
        repository.save(game);
    }

    /**
     * 【メソッドの役割】 投了する。相手の勝ちで対局を終える。
     *
     * @param roomCode 入室コード
     * @param token    本人証明トークン
     */
    @Transactional
    public void resign(String roomCode, String token) {
        GunjinGame game = requireGame(roomCode);
        int me = requirePlayer(game, token);
        if (game.getStatus() == GunjinGame.Status.FINISHED) {
            throw new GunjinException("この対局は既に終わっています");
        }
        finish(game, opponent(me), "RESIGNED");
        bumpVersion(game);
        touch(game);
        repository.save(game);
    }

    /** 対局を決着させる。 */
    private void finish(GunjinGame game, int winner, String reason) {
        game.setStatus(GunjinGame.Status.FINISHED);
        game.setWinner(winner);
        game.setWinReason(reason);
    }

    // ============================================================
    // 視界制限つきの盤の射影
    // ============================================================

    /**
     * 【メソッドの役割】 閲覧者の陣営から見た盤の状態を組み立てる。
     *
     * ここが「伏せ札」を実装している唯一の場所。
     *  - 自分の駒: 駒種・動かせる先まで返す
     *  - 相手の駒: マス ID だけ返し、駒種は返さない
     *  - 決着後: 双方の駒種をすべて開示する（感想戦のため）
     *  - 棋譜: 駒種を含まず、審判の宣告（勝ち／負け／相打ち）だけを含む
     *
     * @param roomCode 入室コード
     * @param token    本人証明トークン
     * @return フロントへ返す状態マップ
     */
    @Transactional(readOnly = true)
    public Map<String, Object> view(String roomCode, String token) {
        GunjinGame game = requireGame(roomCode);
        int me = requirePlayer(game, token);
        List<Piece> pieces = readPieces(game);
        boolean revealAll = game.getStatus() == GunjinGame.Status.FINISHED;
        Map<String, Piece> occupied = GunjinRules.byCell(pieces);

        List<Map<String, Object>> mine = new ArrayList<>();
        List<Map<String, Object>> theirs = new ArrayList<>();
        for (Piece p : pieces) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", p.id());
            entry.put("cell", p.cell());
            if (p.owner() == me) {
                entry.put("type", p.type().name());
                // 手番のときだけ、その駒が動ける先を添える（UI のハイライト用）。
                if (game.getStatus() == GunjinGame.Status.PLAYING && game.getTurn() == me) {
                    entry.put("moves", GunjinRules.destinations(p, occupied).stream().map(Cell::id).toList());
                }
                // 軍旗の実効強さ（後ろの駒に依存する）は本人にだけ見せる。
                if (p.type() == PieceType.GUNKI) {
                    PieceType eff = GunjinRules.effectiveType(p, pieces);
                    entry.put("flagStrength", eff == null ? null : eff.name());
                }
                mine.add(entry);
            } else {
                if (revealAll) {
                    entry.put("type", p.type().name());
                }
                theirs.add(entry);
            }
        }

        // 墓場は自分の分だけ（決着後は相手の分も開示）。
        List<Map<String, Object>> myDead = new ArrayList<>();
        List<Map<String, Object>> theirDead = new ArrayList<>();
        for (Map<String, Object> d : readDead(game)) {
            int owner = ((Number) d.getOrDefault("o", 0)).intValue();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("moveNo", d.get("n"));
            if (owner == me) {
                entry.put("type", d.get("t"));
                myDead.add(entry);
            } else {
                if (revealAll) {
                    entry.put("type", d.get("t"));
                }
                theirDead.add(entry);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roomCode", game.getRoomCode());
        out.put("status", game.getStatus().name());
        out.put("me", me);
        out.put("myName", me == 1 ? game.getP1Name() : game.getP2Name());
        out.put("opponentName", me == 1 ? game.getP2Name() : game.getP1Name());
        out.put("turn", game.getTurn());
        out.put("myTurn", game.getStatus() == GunjinGame.Status.PLAYING && game.getTurn() == me);
        out.put("iAmReady", isReady(game, me));
        out.put("opponentReady", isReady(game, opponent(me)));
        out.put("moveCount", game.getMoveCount());
        out.put("stateVersion", game.getStateVersion());
        out.put("winner", game.getWinner());
        out.put("winReason", game.getWinReason());
        out.put("myPieces", mine);
        out.put("opponentPieces", theirs);
        out.put("myDead", myDead);
        out.put("opponentDead", theirDead);
        out.put("log", readLog(game));
        return out;
    }

    /**
     * 【メソッドの役割】 盤の形（マスの一覧）を返す。ゲーム開始前でも取得できる静的データ。
     *
     * 盤が長方形でない（河・2 マス幅の総司令部がある）ため、
     * フロントで形を再定義せず、サーバのマス定義をそのまま描画させる。
     *
     * @return {@code {cols, rows, armySize, cells: [...], pieceTypes: [...]}}
     */
    public Map<String, Object> boardDefinition() {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (Cell c : GunjinRules.allCells()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("row", c.row());
            m.put("col", c.col());
            m.put("span", c.span());
            m.put("zone", c.zone());
            m.put("hqOf", c.hqOf());
            m.put("gate", c.isGate());
            cells.add(m);
        }
        List<Map<String, Object>> types = new ArrayList<>();
        for (PieceType t : PieceType.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("label", t.label);
            m.put("count", t.count);
            m.put("category", t.category.name());
            m.put("rank", t.rank);
            m.put("immobile", t.isImmobile());
            m.put("canCaptureHq", t.canCaptureHq());
            types.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cols", GunjinRules.COLS);
        out.put("rows", GunjinRules.ROWS);
        out.put("armySize", GunjinRules.ARMY_SIZE);
        out.put("cells", cells);
        out.put("pieceTypes", types);
        return out;
    }

    // ============================================================
    // 内部ヘルパ
    // ============================================================

    /** 入室コードで対局を引く（無ければ例外）。 */
    private GunjinGame requireGame(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            throw new GunjinException("入室コードを入力してください");
        }
        return repository.findByRoomCode(roomCode.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new GunjinException("その入室コードの部屋は見つかりません"));
    }

    /** トークンから陣営（1 or 2）を特定する（一致しなければ例外）。 */
    private int requirePlayer(GunjinGame game, String token) {
        if (token != null && !token.isBlank()) {
            if (token.equals(game.getP1Token())) {
                return 1;
            }
            if (token.equals(game.getP2Token())) {
                return 2;
            }
        }
        throw new GunjinException("この部屋の対局者として確認できません");
    }

    /** 相手の陣営番号。 */
    private int opponent(int player) {
        return player == 1 ? 2 : 1;
    }

    /** 指定プレイヤーが布陣を提出済みか。 */
    private boolean isReady(GunjinGame game, int player) {
        return player == 1 ? game.isP1Ready() : game.isP2Ready();
    }

    /** 表示名を整える（空なら既定名、長すぎれば切り詰め）。 */
    private String normalizeName(String name, String fallback) {
        if (name == null || name.trim().isEmpty()) {
            return fallback;
        }
        String trimmed = name.trim();
        return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;
    }

    /** 駒種名をパースする（未知の値は例外）。 */
    private PieceType parsePieceType(String name) {
        try {
            return PieceType.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GunjinException("知らない駒が含まれています: " + name);
        }
    }

    /** 盤が変化したことを表すカウンタを進める。 */
    private void bumpVersion(GunjinGame game) {
        game.setStateVersion(game.getStateVersion() + 1);
    }

    /** 最終更新日時を現在時刻にする。 */
    private void touch(GunjinGame game) {
        game.setUpdatedAt(LocalDateTime.now());
    }

    /** 墓場に積む 1 件を作る。 */
    private Map<String, Object> deadEntry(Piece piece, int moveNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("o", piece.owner());
        m.put("t", piece.type().name());
        m.put("n", moveNo);
        return m;
    }

    /** 重複しない入室コードを生成する。 */
    private String generateRoomCode() {
        for (int attempt = 0; attempt < 40; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            }
            String code = sb.toString();
            if (!repository.existsByRoomCode(code)) {
                return code;
            }
        }
        throw new GunjinException("部屋が混み合っています。少し待ってからもう一度お試しください");
    }

    /** 本人証明トークンを生成する。 */
    private String generateToken() {
        byte[] buf = new byte[TOKEN_LENGTH / 2];
        random.nextBytes(buf);
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (byte b : buf) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 長期間更新の無い部屋を削除する。
     * 対局は使い捨てなので専用のスケジューラは持たず、部屋作成のついでに掃除する。
     */
    private void purgeStaleRooms() {
        try {
            List<GunjinGame> stale = repository.findByUpdatedAtBefore(LocalDateTime.now().minusDays(STALE_DAYS));
            if (!stale.isEmpty()) {
                repository.deleteAll(stale);
            }
        } catch (Exception ignored) {
            // 掃除の失敗で部屋作成を止める理由は無い。
        }
    }

    // ---- JSON の出し入れ ----

    /** 盤の駒配置を読む。 */
    private List<Piece> readPieces(GunjinGame game) {
        List<Map<String, Object>> raw = readList(game.getPiecesJson());
        List<Piece> out = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            out.add(new Piece(
                    ((Number) m.get("i")).intValue(),
                    ((Number) m.get("o")).intValue(),
                    PieceType.valueOf((String) m.get("t")),
                    (String) m.get("c")));
        }
        return out;
    }

    /** 盤の駒配置を書く。 */
    private void writePieces(GunjinGame game, List<Piece> pieces) {
        List<Map<String, Object>> raw = new ArrayList<>(pieces.size());
        for (Piece p : pieces) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("i", p.id());
            m.put("o", p.owner());
            m.put("t", p.type().name());
            m.put("c", p.cell());
            raw.add(m);
        }
        game.setPiecesJson(writeJson(raw));
    }

    /** 墓場を読む。 */
    private List<Map<String, Object>> readDead(GunjinGame game) {
        return readList(game.getDeadJson());
    }

    /** 墓場を書く。 */
    private void writeDead(GunjinGame game, List<Map<String, Object>> dead) {
        game.setDeadJson(writeJson(dead));
    }

    /** 棋譜を読む。 */
    private List<Map<String, Object>> readLog(GunjinGame game) {
        return readList(game.getLogJson());
    }

    /** 棋譜に 1 手追記する（駒種は決して書かない）。 */
    private void appendLog(GunjinGame game, int moveNo, int owner, String from, String to, String result) {
        List<Map<String, Object>> log = new ArrayList<>(readLog(game));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("n", moveNo);
        entry.put("o", owner);
        entry.put("from", from);
        entry.put("to", to);
        entry.put("r", result);
        log.add(entry);
        game.setLogJson(writeJson(log));
    }

    /** JSON 文字列をマップのリストとして読む（null／壊れていれば空リスト）。 */
    private List<Map<String, Object>> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** オブジェクトを JSON 文字列にする。 */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new GunjinException("盤の保存に失敗しました");
        }
    }
}
