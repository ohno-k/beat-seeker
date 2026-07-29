package com.beatseeker.backend.service;

import java.util.*;

/**
 * 【クラスの役割】 軍人将棋（伝統的な 23 枚型・2 本橋の盤）のルールそのものを実装した純粋ロジック。
 *
 * DB もフレームワークも触らない静的ユーティリティ。盤の形・駒の動き・勝敗表・勝利条件だけを持つ。
 * オンライン対局における「審判」の判断はすべてここに集約されており、
 * {@link GunjinGameService} は盤の状態を出し入れするだけで判定そのものは行わない。
 *
 * <h2>盤の形（2 本橋）</h2>
 * <pre>
 *      c0 c1 c2 c3 c4 c5
 * r0  [  ][  ][= 総司令部 =][  ][  ]   ← 後手(P2) 最後列。総司令部は c2-c3 の 2 マス分を占める 1 マス
 * r1  [  ][  ][  ][  ][  ][  ]
 * r2  [  ][  ][  ][  ][  ][  ]
 * r3  [  ][  ][  ][  ][  ][  ]        ← 後手陣 最前列
 * r4      [突]        [突]             ← 河。突入口だけが存在する (c1 / c4)
 * r5      [突]        [突]             ← 河
 * r6  [  ][  ][  ][  ][  ][  ]        ← 先手陣 最前列
 * r7  [  ][  ][  ][  ][  ][  ]
 * r8  [  ][  ][  ][  ][  ][  ]
 * r9  [  ][  ][= 総司令部 =][  ][  ]   ← 先手(P1) 最後列
 * </pre>
 * 自陣は 4 段 × 6 列だが総司令部が 2 マスを結合して 1 マスになるため、置けるマスは
 * ちょうど 23 マス =駒数と一致する。つまり布陣では自陣が隙間なく埋まる。
 *
 * <h2>勝敗表の解釈について</h2>
 * 出典によって細部の記述が食い違う箇所（例: タンク対地雷を「タンクの負け」と書く資料と
 * 「地雷は勝っても爆発するので相打ち」と書く資料）がある。本実装では
 * <b>「地雷は飛行機・工兵にのみ負け、それ以外とは相打ち（踏んだ駒を道連れにして自分も爆発）」</b>
 * を優先し、地雷の項を他の駒の項より上位に適用する。判定順は {@link #resolve} の実装順のとおり。
 */
public final class GunjinRules {

    private GunjinRules() {
    }

    // ============================================================
    // 駒
    // ============================================================

    /** 駒の大分類。勝敗表で「将官には負ける」のようにまとめて参照するために使う。 */
    public enum Category {
        /** 将官（大将・中将・少将）。 */
        SHOKAN,
        /** 佐官（大佐・中佐・少佐）。 */
        SAKAN,
        /** 尉官（大尉・中尉・少尉）。 */
        IKAN,
        /** 特殊駒（飛行機・タンク・騎兵・工兵・地雷・軍旗・スパイ）。 */
        SPECIAL
    }

    /**
     * 駒種。23 枚型の内訳をそのまま持つ。
     *
     * {@code rank} は階級駒の強弱比較用の数値（大将 9 〜 少尉 1）。特殊駒は 0。
     */
    public enum PieceType {
        /** 大将。最強の階級駒だが、スパイにだけ討たれる。 */
        TAISHO("大将", 1, Category.SHOKAN, 9),
        /** 中将。 */
        CHUJO("中将", 1, Category.SHOKAN, 8),
        /** 少将。 */
        SHOSHO("少将", 1, Category.SHOKAN, 7),
        /** 大佐。 */
        TAISA("大佐", 1, Category.SAKAN, 6),
        /** 中佐。 */
        CHUSA("中佐", 1, Category.SAKAN, 5),
        /** 少佐。総司令部を占領できる最下位の駒。 */
        SHOSA("少佐", 1, Category.SAKAN, 4),
        /** 大尉。 */
        TAII("大尉", 2, Category.IKAN, 3),
        /** 中尉。 */
        CHUI("中尉", 2, Category.IKAN, 2),
        /** 少尉。 */
        SHOI("少尉", 2, Category.IKAN, 1),
        /** ヒコーキ（飛行機）。将官にのみ負ける。縦は何マスでも飛び、河（突入口）を無視する。 */
        HIKOKI("ヒコーキ", 2, Category.SPECIAL, 0),
        /** タンク。将官・飛行機・工兵に負け、地雷とは相打ち。前方 2 マスまで進める。 */
        TANK("タンク", 2, Category.SPECIAL, 0),
        /** 騎兵。スパイと工兵にのみ勝つ。前方 2 マスまで進める。 */
        KIHEI("騎兵", 1, Category.SPECIAL, 0),
        /** 工兵。地雷を除去できる唯一の地上駒。縦横に何マスでも進める（飛び越しは不可）。 */
        KOHEI("工兵", 2, Category.SPECIAL, 0),
        /** 地雷。動けない。飛行機・工兵にのみ負け、それ以外は相打ち。 */
        JIRAI("地雷", 2, Category.SPECIAL, 0),
        /** 軍旗。動けない。すぐ後ろ（自陣側）の味方駒と同じ強さになる。 */
        GUNKI("軍旗", 1, Category.SPECIAL, 0),
        /** スパイ。大将にのみ勝ち、他には全敗。 */
        SPY("スパイ", 1, Category.SPECIAL, 0);

        /** 日本語表記（UI 表示用）。 */
        public final String label;
        /** 片陣営あたりの枚数。総和は 23。 */
        public final int count;
        /** 大分類。 */
        public final Category category;
        /** 階級駒の強弱値（大将 9 〜 少尉 1、特殊駒は 0）。 */
        public final int rank;

        PieceType(String label, int count, Category category, int rank) {
            this.label = label;
            this.count = count;
            this.category = category;
            this.rank = rank;
        }

        /** 階級駒（将官・佐官・尉官）か。 */
        public boolean isRanked() {
            return rank > 0;
        }

        /** その場から動けない駒（地雷・軍旗）か。 */
        public boolean isImmobile() {
            return this == JIRAI || this == GUNKI;
        }

        /** 敵の総司令部を占領して勝てる駒（大将〜少佐の 6 種）か。 */
        public boolean canCaptureHq() {
            return category == Category.SHOKAN || category == Category.SAKAN;
        }
    }

    /** 片陣営の駒 23 枚（駒種 → 枚数）。布陣の検証に使う。 */
    public static final Map<PieceType, Integer> ARMY;
    /** 片陣営の駒の総数（= 自陣のマス数）。 */
    public static final int ARMY_SIZE;

    static {
        Map<PieceType, Integer> army = new EnumMap<>(PieceType.class);
        int total = 0;
        for (PieceType t : PieceType.values()) {
            army.put(t, t.count);
            total += t.count;
        }
        ARMY = Collections.unmodifiableMap(army);
        ARMY_SIZE = total;
    }

    // ============================================================
    // 盤
    // ============================================================

    /** 盤の列数。 */
    public static final int COLS = 6;
    /** 盤の段数（後手陣 4 + 河 2 + 先手陣 4）。 */
    public static final int ROWS = 10;
    /** 突入口が置かれる列（河の段に存在する 2 本の橋）。 */
    private static final Set<Integer> GATE_COLS = Set.of(1, 4);
    /** 総司令部が置かれる列（span 2 なので c2-c3 を占める）。 */
    private static final int HQ_COL = 2;

    /**
     * 盤の 1 マス。
     *
     * @param id    マス ID（{@code "r7c1"} 形式。総司令部は {@code "r9c2"} / {@code "r0c2"}）
     * @param row   段（0 = 後手最後列 〜 9 = 先手最後列）
     * @param col   列の左端
     * @param span  横幅（総司令部だけ 2、他は 1）
     * @param zone  {@code 1} = 先手陣 / {@code 2} = 後手陣 / {@code 0} = 河（突入口）
     * @param hqOf  このマスが総司令部ならその所有者（1 or 2）、そうでなければ {@code 0}
     */
    public record Cell(String id, int row, int col, int span, int zone, int hqOf) {
        /** 総司令部マスか。 */
        public boolean isHq() {
            return hqOf != 0;
        }

        /** 突入口（河の上のマス）か。 */
        public boolean isGate() {
            return zone == 0;
        }

        /** このマスが物理的に占める列の右端。 */
        public int colEnd() {
            return col + span - 1;
        }
    }

    /** 全マス（ID → マス）。挿入順は r0 から r9 の並び順。 */
    private static final Map<String, Cell> CELLS = new LinkedHashMap<>();
    /** 段ごとのマス一覧（列の昇順）。 */
    private static final Map<Integer, List<Cell>> ROW_CELLS = new HashMap<>();

    static {
        for (int row = 0; row < ROWS; row++) {
            List<Cell> rowCells = new ArrayList<>();
            for (int col = 0; col < COLS; col++) {
                if (!cellExists(row, col)) {
                    continue;
                }
                int span = isHqRow(row) && col == HQ_COL ? 2 : 1;
                int zone = row <= 3 ? 2 : row >= 6 ? 1 : 0;
                int hqOf = span == 2 ? (row == 0 ? 2 : 1) : 0;
                Cell cell = new Cell(cellId(row, col), row, col, span, zone, hqOf);
                CELLS.put(cell.id(), cell);
                rowCells.add(cell);
            }
            ROW_CELLS.put(row, List.copyOf(rowCells));
        }
    }

    /** 総司令部がある段（最後列）か。 */
    private static boolean isHqRow(int row) {
        return row == 0 || row == ROWS - 1;
    }

    /**
     * {@code (row, col)} に「マスの左端として」マスが存在するか。
     *
     * 河の段は突入口の 2 列だけ、最後列は c3 が総司令部（c2 始まり）に吸収されるため存在しない。
     */
    private static boolean cellExists(int row, int col) {
        if (col < 0 || col >= COLS) {
            return false;
        }
        if (row < 0 || row >= ROWS) {
            return false;
        }
        if (row == 4 || row == 5) {
            return GATE_COLS.contains(col);
        }
        if (isHqRow(row)) {
            return col != HQ_COL + 1;
        }
        return true;
    }

    /** マス ID を組み立てる。 */
    private static String cellId(int row, int col) {
        return "r" + row + "c" + col;
    }

    /** 全マスを盤面の並び順で返す（フロントの描画データ用）。 */
    public static Collection<Cell> allCells() {
        return CELLS.values();
    }

    /** ID からマスを引く。存在しなければ null。 */
    public static Cell cell(String id) {
        return id == null ? null : CELLS.get(id);
    }

    /** 指定プレイヤーの陣地のマス一覧（布陣で駒を置ける 23 マス）。 */
    public static List<Cell> campCells(int player) {
        return CELLS.values().stream().filter(c -> c.zone() == player).toList();
    }

    /** 指定プレイヤーの総司令部マス。 */
    public static Cell hqCell(int player) {
        return CELLS.values().stream().filter(c -> c.hqOf() == player).findFirst().orElseThrow();
    }

    /**
     * 段 {@code row} で列 {@code col} を物理的に含むマスを返す（総司令部の c3 側もヒットする）。
     * 存在しなければ null。
     */
    private static Cell cellCovering(int row, int col) {
        List<Cell> cells = ROW_CELLS.get(row);
        if (cells == null) {
            return null;
        }
        for (Cell c : cells) {
            if (col >= c.col() && col <= c.colEnd()) {
                return c;
            }
        }
        return null;
    }

    /** 2 つのマスの列範囲が重なるか（総司令部は 2 列分あるため範囲で判定する）。 */
    private static boolean colsOverlap(Cell a, Cell b) {
        return a.col() <= b.colEnd() && b.col() <= a.colEnd();
    }

    /**
     * 縦に 1 段動いた先のマス一覧。
     *
     * 総司令部は 2 列分の幅があるため、そこから前に出る手は 2 マスに分岐し得る。
     * 逆に c2 / c3 のマスから最後列へ下がる手は同じ総司令部マスに収束する。
     */
    private static List<Cell> verticalNeighbors(Cell from, int dRow) {
        List<Cell> targets = ROW_CELLS.get(from.row() + dRow);
        if (targets == null) {
            return List.of();
        }
        List<Cell> out = new ArrayList<>(2);
        for (Cell c : targets) {
            if (colsOverlap(from, c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** 横に 1 マス動いた先（存在しなければ空）。 */
    private static List<Cell> horizontalNeighbors(Cell from, int dCol) {
        int targetCol = dCol > 0 ? from.colEnd() + 1 : from.col() - 1;
        Cell c = cellCovering(from.row(), targetCol);
        return c == null ? List.of() : List.of(c);
    }

    /** 指定方向へ 1 歩動いた先の候補。縦は分岐し得るのでリストで返す。 */
    private static List<Cell> step(Cell from, int dRow, int dCol) {
        if (dRow != 0) {
            return verticalNeighbors(from, dRow);
        }
        return horizontalNeighbors(from, dCol);
    }

    // ============================================================
    // 駒の配置と手
    // ============================================================

    /**
     * 盤上の 1 駒。
     *
     * @param id    駒の通し番号（1..46）。棋譜や UI のトラッキングに使う
     * @param owner 所有者（1 = 先手 / 2 = 後手）
     * @param type  駒種
     * @param cell  乗っているマス ID
     */
    public record Piece(int id, int owner, PieceType type, String cell) {
        /** マスだけを差し替えた複製を返す。 */
        public Piece movedTo(String newCell) {
            return new Piece(id, owner, type, newCell);
        }
    }

    /**
     * 1 つの合法手。
     *
     * @param from 動かす駒のマス ID
     * @param to   移動先のマス ID
     */
    public record Move(String from, String to) {
    }

    /** 駒がぶつかった時の審判の宣告。 */
    public enum Outcome {
        /** 攻撃側の勝ち（守備側の駒を除去し、攻撃側が進む）。 */
        ATTACKER_WINS,
        /** 守備側の勝ち（攻撃側の駒を除去し、盤は動かない）。 */
        DEFENDER_WINS,
        /** 相打ち（両方の駒を除去し、マスは空になる）。 */
        BOTH_LOSE
    }

    /** 「前」の向き（先手は段が減る方向、後手は増える方向）。 */
    private static int forward(int owner) {
        return owner == 1 ? -1 : 1;
    }

    // ============================================================
    // 合法手の生成
    // ============================================================

    /**
     * 【メソッドの役割】 指定プレイヤーの全合法手を列挙する。
     *
     * @param pieces 盤上の全生存駒
     * @param player 手番のプレイヤー（1 or 2）
     * @return 合法手のリスト（手が無ければ空。空 = そのプレイヤーの負け）
     */
    public static List<Move> legalMoves(Collection<Piece> pieces, int player) {
        Map<String, Piece> occupied = byCell(pieces);
        List<Move> moves = new ArrayList<>();
        for (Piece p : pieces) {
            if (p.owner() != player) {
                continue;
            }
            for (Cell dest : destinations(p, occupied)) {
                moves.add(new Move(p.cell(), dest.id()));
            }
        }
        return moves;
    }

    /**
     * 【メソッドの役割】 1 つの駒が動ける先のマスを列挙する。
     *
     * 味方の駒が乗っているマスは除外し、敵の駒が乗っているマス（=交戦）は含める。
     *
     * @param piece    動かす駒
     * @param occupied マス ID → 乗っている駒
     * @return 移動先マスの一覧
     */
    public static List<Cell> destinations(Piece piece, Map<String, Piece> occupied) {
        Cell from = cell(piece.cell());
        if (from == null || piece.type().isImmobile()) {
            return List.of();
        }
        PieceType type = piece.type();
        int fwd = forward(piece.owner());
        LinkedHashSet<Cell> raw = new LinkedHashSet<>();

        switch (type) {
            case HIKOKI -> {
                // 縦は同じ列を何マスでも。駒も河（突入口）も飛び越えるので、
                // 「列範囲が重なる別の段のマス」がすべて移動先になる。
                for (Cell c : CELLS.values()) {
                    if (c.row() != from.row() && colsOverlap(from, c)) {
                        raw.add(c);
                    }
                }
                // 横は 1 マスだけ。
                raw.addAll(walk(from, 0, -1, 1, false, occupied));
                raw.addAll(walk(from, 0, 1, 1, false, occupied));
            }
            case KOHEI -> {
                // 縦横に何マスでも。飛び越しは不可なので河は突入口しか通れない。
                raw.addAll(walk(from, -1, 0, Integer.MAX_VALUE, false, occupied));
                raw.addAll(walk(from, 1, 0, Integer.MAX_VALUE, false, occupied));
                raw.addAll(walk(from, 0, -1, Integer.MAX_VALUE, false, occupied));
                raw.addAll(walk(from, 0, 1, Integer.MAX_VALUE, false, occupied));
            }
            case TANK, KIHEI -> {
                // 前方は 2 マスまで（間に駒があれば止まる）、後ろと左右は 1 マス。
                raw.addAll(walk(from, fwd, 0, 2, false, occupied));
                raw.addAll(walk(from, -fwd, 0, 1, false, occupied));
                raw.addAll(walk(from, 0, -1, 1, false, occupied));
                raw.addAll(walk(from, 0, 1, 1, false, occupied));
            }
            default -> {
                // 将官・佐官・尉官・スパイ: 前後左右 1 マス。
                raw.addAll(walk(from, -1, 0, 1, false, occupied));
                raw.addAll(walk(from, 1, 0, 1, false, occupied));
                raw.addAll(walk(from, 0, -1, 1, false, occupied));
                raw.addAll(walk(from, 0, 1, 1, false, occupied));
            }
        }

        List<Cell> out = new ArrayList<>(raw.size());
        for (Cell c : raw) {
            Piece there = occupied.get(c.id());
            // 味方の駒には重ねられない。
            if (there != null && there.owner() == piece.owner()) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    /**
     * 【メソッドの役割】 一方向に最大 {@code maxSteps} 歩ぶん進み、通過・到達し得るマスを集める。
     *
     * 総司令部が 2 列幅を持つため経路が分岐し得る。そのため 1 歩ずつ「到達集合」を広げる形で歩く。
     * 飛び越し不可の駒では、駒が乗っているマスは移動先候補には入るがそこで進行が止まる。
     *
     * @param from      出発マス
     * @param dRow      段の増分（-1/0/1）
     * @param dCol      列の増分（-1/0/1）
     * @param maxSteps  最大歩数
     * @param canJump   途中の駒を飛び越せるか
     * @param occupied  マス ID → 乗っている駒
     * @return 到達し得るマスの集合
     */
    private static LinkedHashSet<Cell> walk(Cell from, int dRow, int dCol, int maxSteps,
                                            boolean canJump, Map<String, Piece> occupied) {
        LinkedHashSet<Cell> reached = new LinkedHashSet<>();
        List<Cell> frontier = List.of(from);
        for (int stepNo = 0; stepNo < maxSteps && !frontier.isEmpty(); stepNo++) {
            LinkedHashSet<Cell> next = new LinkedHashSet<>();
            for (Cell c : frontier) {
                next.addAll(step(c, dRow, dCol));
            }
            if (next.isEmpty()) {
                break;
            }
            reached.addAll(next);
            if (canJump) {
                frontier = new ArrayList<>(next);
            } else {
                // 駒が乗っているマスはそこで行き止まり（そのマス自体は交戦先として有効）。
                frontier = next.stream().filter(c -> !occupied.containsKey(c.id())).toList();
            }
        }
        return reached;
    }

    /** 駒のコレクションを「マス ID → 駒」に索引する。 */
    public static Map<String, Piece> byCell(Collection<Piece> pieces) {
        Map<String, Piece> map = new HashMap<>();
        for (Piece p : pieces) {
            map.put(p.cell(), p);
        }
        return map;
    }

    // ============================================================
    // 勝敗表（審判）
    // ============================================================

    /**
     * 【メソッドの役割】 ぶつかった 2 駒の勝敗を判定する（審判の宣告そのもの）。
     *
     * 判定順（上にあるものが優先される）:
     * <ol>
     *   <li>軍旗はすぐ後ろの味方駒の駒種に読み替える（後ろに駒が無ければ最弱扱い）</li>
     *   <li>地雷: 飛行機・工兵には負ける。それ以外の駒とは相打ち（道連れにして自分も爆発）</li>
     *   <li>同じ駒種同士は相打ち</li>
     *   <li>スパイ: 大将にのみ勝ち、他には全敗</li>
     *   <li>飛行機: 将官にのみ負け、他には勝つ</li>
     *   <li>タンク: 将官・工兵に負け、他には勝つ</li>
     *   <li>騎兵: 工兵にのみ勝ち、他には負ける</li>
     *   <li>工兵: 上記以外（将官・佐官・尉官）には負ける</li>
     *   <li>階級駒同士は階級の高い方が勝つ</li>
     * </ol>
     *
     * @param attacker 攻め込んだ駒
     * @param defender 受けた駒
     * @param pieces   盤上の全駒（軍旗の読み替えに必要）
     * @return 審判の宣告
     */
    public static Outcome resolve(Piece attacker, Piece defender, Collection<Piece> pieces) {
        PieceType a = effectiveType(attacker, pieces);
        PieceType d = effectiveType(defender, pieces);

        // 1. 軍旗の読み替え結果が「後ろに駒無し」の場合は最弱（無条件で負け）。
        boolean aBare = a == null;
        boolean dBare = d == null;
        if (aBare && dBare) {
            return Outcome.BOTH_LOSE;
        }
        if (aBare) {
            return Outcome.DEFENDER_WINS;
        }
        if (dBare) {
            return Outcome.ATTACKER_WINS;
        }

        // 2. 地雷。
        if (a == PieceType.JIRAI || d == PieceType.JIRAI) {
            PieceType other = a == PieceType.JIRAI ? d : a;
            boolean otherClears = other == PieceType.HIKOKI || other == PieceType.KOHEI;
            if (a == PieceType.JIRAI && d == PieceType.JIRAI) {
                return Outcome.BOTH_LOSE;
            }
            if (!otherClears) {
                // 踏んだ駒を道連れにして自分も爆発する。
                return Outcome.BOTH_LOSE;
            }
            return a == PieceType.JIRAI ? Outcome.DEFENDER_WINS : Outcome.ATTACKER_WINS;
        }

        // 3. 同駒種は相打ち。
        if (a == d) {
            return Outcome.BOTH_LOSE;
        }

        // 4. スパイ。
        if (a == PieceType.SPY) {
            return d == PieceType.TAISHO ? Outcome.ATTACKER_WINS : Outcome.DEFENDER_WINS;
        }
        if (d == PieceType.SPY) {
            return a == PieceType.TAISHO ? Outcome.DEFENDER_WINS : Outcome.ATTACKER_WINS;
        }

        // 5. 飛行機。
        if (a == PieceType.HIKOKI) {
            return d.category == Category.SHOKAN ? Outcome.DEFENDER_WINS : Outcome.ATTACKER_WINS;
        }
        if (d == PieceType.HIKOKI) {
            return a.category == Category.SHOKAN ? Outcome.ATTACKER_WINS : Outcome.DEFENDER_WINS;
        }

        // 6. タンク。
        if (a == PieceType.TANK) {
            boolean beatsTank = d.category == Category.SHOKAN || d == PieceType.KOHEI;
            return beatsTank ? Outcome.DEFENDER_WINS : Outcome.ATTACKER_WINS;
        }
        if (d == PieceType.TANK) {
            boolean beatsTank = a.category == Category.SHOKAN || a == PieceType.KOHEI;
            return beatsTank ? Outcome.ATTACKER_WINS : Outcome.DEFENDER_WINS;
        }

        // 7. 騎兵。
        if (a == PieceType.KIHEI) {
            return d == PieceType.KOHEI ? Outcome.ATTACKER_WINS : Outcome.DEFENDER_WINS;
        }
        if (d == PieceType.KIHEI) {
            return a == PieceType.KOHEI ? Outcome.DEFENDER_WINS : Outcome.ATTACKER_WINS;
        }

        // 8. 工兵（残る相手は階級駒だけなので工兵の負け）。
        if (a == PieceType.KOHEI) {
            return Outcome.DEFENDER_WINS;
        }
        if (d == PieceType.KOHEI) {
            return Outcome.ATTACKER_WINS;
        }

        // 9. 階級駒同士。
        return a.rank > d.rank ? Outcome.ATTACKER_WINS : Outcome.DEFENDER_WINS;
    }

    /**
     * 【メソッドの役割】 勝敗判定に使う「実効の駒種」を返す。
     *
     * 軍旗はすぐ後ろ（自陣の最後列に近い側）の味方駒と同じ強さになるため、その駒種に読み替える。
     * 後ろに味方駒が居なければ {@code null}（最弱＝無条件で負け）を返す。
     *
     * @param piece  対象の駒
     * @param pieces 盤上の全駒
     * @return 実効の駒種。軍旗で後ろが空なら null
     */
    public static PieceType effectiveType(Piece piece, Collection<Piece> pieces) {
        if (piece.type() != PieceType.GUNKI) {
            return piece.type();
        }
        Cell here = cell(piece.cell());
        if (here == null) {
            return null;
        }
        Map<String, Piece> occupied = byCell(pieces);
        // 「後ろ」= 前進方向の逆。総司令部の幅の都合で複数マスあり得るが、
        // 軍旗が置ける位置では実際には 1 マスに収束する。念のため最初に見つかった味方駒を採用する。
        for (Cell behind : verticalNeighbors(here, -forward(piece.owner()))) {
            Piece p = occupied.get(behind.id());
            if (p != null && p.owner() == piece.owner() && p.type() != PieceType.GUNKI) {
                return p.type();
            }
        }
        return null;
    }

    // ============================================================
    // 布陣の検証
    // ============================================================

    /**
     * 【メソッドの役割】 提出された布陣が正しいか検証する。
     *
     * 自陣は 23 マス・駒も 23 枚なので「自陣の全マスがちょうど 1 枚ずつで埋まり、
     * 駒種の内訳が {@link #ARMY} と一致する」ことを確認すればよい。
     *
     * @param player     布陣するプレイヤー（1 or 2）
     * @param placements マス ID → 駒種
     * @return エラーメッセージ。問題なければ null
     */
    public static String validateSetup(int player, Map<String, PieceType> placements) {
        if (placements == null || placements.size() != ARMY_SIZE) {
            return "布陣は自陣 " + ARMY_SIZE + " マスすべてを埋めてください";
        }
        Set<String> camp = new HashSet<>();
        for (Cell c : campCells(player)) {
            camp.add(c.id());
        }
        Map<PieceType, Integer> counted = new EnumMap<>(PieceType.class);
        for (Map.Entry<String, PieceType> e : placements.entrySet()) {
            if (!camp.contains(e.getKey())) {
                return "自陣の外のマスが指定されています: " + e.getKey();
            }
            if (e.getValue() == null) {
                return "駒種が指定されていないマスがあります: " + e.getKey();
            }
            counted.merge(e.getValue(), 1, Integer::sum);
        }
        for (Map.Entry<PieceType, Integer> e : ARMY.entrySet()) {
            int have = counted.getOrDefault(e.getKey(), 0);
            if (have != e.getValue()) {
                return e.getKey().label + " は " + e.getValue() + " 枚です（" + have + " 枚になっています）";
            }
        }
        return null;
    }

    /**
     * 【メソッドの役割】 自陣をランダムに埋めた布陣を作る（「おまかせ配置」用）。
     *
     * @param player プレイヤー（1 or 2）
     * @param rnd    乱数源
     * @return マス ID → 駒種（23 件）
     */
    public static Map<String, PieceType> randomSetup(int player, Random rnd) {
        List<PieceType> bag = new ArrayList<>(ARMY_SIZE);
        for (Map.Entry<PieceType, Integer> e : ARMY.entrySet()) {
            for (int i = 0; i < e.getValue(); i++) {
                bag.add(e.getKey());
            }
        }
        Collections.shuffle(bag, rnd);
        List<Cell> cells = campCells(player);
        Map<String, PieceType> out = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            out.put(cells.get(i).id(), bag.get(i));
        }
        return out;
    }
}
