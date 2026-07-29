package com.beatseeker.backend.service;

import com.beatseeker.backend.service.GunjinRules.Cell;
import com.beatseeker.backend.service.GunjinRules.Outcome;
import com.beatseeker.backend.service.GunjinRules.Piece;
import com.beatseeker.backend.service.GunjinRules.PieceType;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.beatseeker.backend.service.GunjinRules.PieceType.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 【テストの役割】 軍人将棋のルール実装（{@link GunjinRules}）が
 * 伝統的な 23 枚型のルールどおりかを検証する。
 *
 * 特に検証したいのは次の 3 点:
 *  - 盤の形（自陣がちょうど 23 マス、河は突入口しか通れない、総司令部が 2 マス幅）
 *  - 駒ごとの動き（工兵の縦横無制限、ヒコーキの河無視、タンク/騎兵の前方 2 マス）
 *  - 勝敗表（特殊駒の相性と、地雷の「相打ち」優先）
 */
class GunjinRulesTest {

    /** 駒 1 個だけを置いた盤を作る。 */
    private static List<Piece> board(Object... ownerTypeCell) {
        List<Piece> pieces = new ArrayList<>();
        for (int i = 0; i < ownerTypeCell.length; i += 3) {
            pieces.add(new Piece(i / 3 + 1,
                    (Integer) ownerTypeCell[i],
                    (PieceType) ownerTypeCell[i + 1],
                    (String) ownerTypeCell[i + 2]));
        }
        return pieces;
    }

    /** 指定した駒の移動先マス ID を集合で返す。 */
    private static Set<String> dests(List<Piece> pieces, String fromCell) {
        Map<String, Piece> occupied = GunjinRules.byCell(pieces);
        Piece p = occupied.get(fromCell);
        assertNotNull(p, "駒が " + fromCell + " に居ない");
        Set<String> out = new HashSet<>();
        for (Cell c : GunjinRules.destinations(p, occupied)) {
            out.add(c.id());
        }
        return out;
    }

    // ============================================================
    // 盤の形
    // ============================================================

    @Test
    void 自陣は駒数と同じ23マスで駒がちょうど埋まる() {
        assertEquals(23, GunjinRules.ARMY_SIZE, "23 枚型なので駒は 23 枚");
        assertEquals(23, GunjinRules.campCells(1).size(), "先手陣は 23 マス");
        assertEquals(23, GunjinRules.campCells(2).size(), "後手陣は 23 マス");
    }

    @Test
    void 盤は自陣23x2と突入口4マスでできている() {
        assertEquals(23 + 23 + 4, GunjinRules.allCells().size());
        long gates = GunjinRules.allCells().stream().filter(Cell::isGate).count();
        assertEquals(4, gates, "突入口（河の上のマス）は 2 本橋 × 2 段 = 4 マス");
    }

    @Test
    void 総司令部は最後列中央の2マス幅() {
        Cell p1Hq = GunjinRules.hqCell(1);
        Cell p2Hq = GunjinRules.hqCell(2);
        assertEquals("r9c2", p1Hq.id());
        assertEquals("r0c2", p2Hq.id());
        assertEquals(2, p1Hq.span());
        assertEquals(2, p2Hq.span());
        assertNull(GunjinRules.cell("r9c3"), "総司令部が c2-c3 を占めるので c3 単独のマスは無い");
        assertNull(GunjinRules.cell("r0c3"));
    }

    @Test
    void 河は突入口の2列しか存在しない() {
        for (int row : new int[] { 4, 5 }) {
            assertNotNull(GunjinRules.cell("r" + row + "c1"), "突入口 c1 は存在する");
            assertNotNull(GunjinRules.cell("r" + row + "c4"), "突入口 c4 は存在する");
            for (int col : new int[] { 0, 2, 3, 5 }) {
                assertNull(GunjinRules.cell("r" + row + "c" + col), "河の c" + col + " は存在しない");
            }
        }
    }

    // ============================================================
    // 駒の動き
    // ============================================================

    @Test
    void 階級駒は前後左右1マスだけ動ける() {
        List<Piece> pieces = board(1, SHOI, "r7c2");
        assertEquals(Set.of("r6c2", "r8c2", "r7c1", "r7c3"), dests(pieces, "r7c2"));
    }

    @Test
    void 突入口を通らないと敵陣へ渡れない() {
        // 自陣最前列 c0 は正面が河（マスが無い）なので前進できない。
        assertEquals(Set.of("r7c0", "r6c1"), dests(board(1, SHOI, "r6c0"), "r6c0"));
        // 突入口の列（c1）なら前進できる。
        assertTrue(dests(board(1, SHOI, "r6c1"), "r6c1").contains("r5c1"), "c1 は橋なので渡れる");
    }

    @Test
    void 突入口の上では左右に動けない() {
        // 河の上は 2 本の橋が離れているので横移動先が無い。
        assertEquals(Set.of("r4c1", "r6c1"), dests(board(1, SHOI, "r5c1"), "r5c1"));
    }

    @Test
    void 総司令部からは幅2マス分の前方どちらにも出られる() {
        // 総司令部は c2-c3 の 2 マス幅なので、前に出る手が 2 通りに分岐する。
        Set<String> d = dests(board(1, TAISHO, "r9c2"), "r9c2");
        assertTrue(d.containsAll(Set.of("r8c2", "r8c3")), "総司令部の前は 2 マスある: " + d);
        assertTrue(d.containsAll(Set.of("r9c1", "r9c4")), "左右へも動ける: " + d);
        assertEquals(4, d.size(), "後ろは盤外なので 4 手: " + d);
    }

    @Test
    void 地雷と軍旗は動けない() {
        assertTrue(dests(board(1, JIRAI, "r7c2"), "r7c2").isEmpty());
        assertTrue(dests(board(1, GUNKI, "r7c2"), "r7c2").isEmpty());
    }

    @Test
    void 工兵は縦横に何マスでも動けるが駒は飛び越せない() {
        List<Piece> pieces = board(1, KOHEI, "r7c1", 1, SHOI, "r7c4");
        Set<String> d = dests(pieces, "r7c1");
        // 横: 味方の r7c4 の手前 c3 まで。c4 は味方なので不可、c5 は越えられない。
        assertTrue(d.containsAll(Set.of("r7c0", "r7c2", "r7c3")));
        assertFalse(d.contains("r7c4"), "味方の駒には重ねられない");
        assertFalse(d.contains("r7c5"), "味方の駒を飛び越せない");
        // 縦: 橋のある c1 を通って敵陣まで届く。
        assertTrue(d.containsAll(Set.of("r6c1", "r5c1", "r4c1", "r3c1", "r2c1", "r1c1")));
        assertTrue(d.contains("r8c1") && d.contains("r9c1"), "後方にも動ける");
    }

    @Test
    void 工兵は河を飛び越えられない() {
        // c0 は橋が無いので、縦に進んでも r6c0 で行き止まり。
        Set<String> d = dests(board(1, KOHEI, "r8c0"), "r8c0");
        assertTrue(d.contains("r6c0"));
        assertFalse(d.contains("r3c0"), "橋の無い列では敵陣へ渡れない");
    }

    @Test
    void ヒコーキは河を無視して同じ列の敵陣まで飛べる() {
        // 突入口の無い c0 でも、縦は何マスでも飛べて敵陣に直接入れる。
        Set<String> d = dests(board(1, HIKOKI, "r8c0"), "r8c0");
        assertTrue(d.containsAll(Set.of("r9c0", "r7c0", "r6c0", "r3c0", "r2c0", "r1c0", "r0c0")),
                "同じ列のすべての段へ飛べる: " + d);
        assertTrue(d.contains("r8c1"), "横は 1 マス");
        assertFalse(d.contains("r8c2"), "横は 1 マスだけ");
    }

    @Test
    void ヒコーキは途中の駒を飛び越せる() {
        List<Piece> pieces = board(1, HIKOKI, "r8c0", 1, SHOI, "r7c0", 2, SHOI, "r2c0");
        Set<String> d = dests(pieces, "r8c0");
        assertFalse(d.contains("r7c0"), "味方の駒には重ねられない");
        assertTrue(d.contains("r6c0"), "味方の駒を飛び越して先へ行ける");
        assertTrue(d.contains("r2c0"), "敵の駒には交戦できる");
        assertTrue(d.contains("r1c0"), "敵の駒も飛び越せる");
    }

    @Test
    void タンクと騎兵は前方2マスまで進める() {
        for (PieceType type : new PieceType[] { TANK, KIHEI }) {
            Set<String> d = dests(board(1, type, "r8c2"), "r8c2");
            assertTrue(d.containsAll(Set.of("r7c2", "r6c2")), type.label + " は前方 2 マスまで: " + d);
            assertTrue(d.contains("r9c2"), type.label + " は後ろへ 1 マス");
            assertTrue(d.containsAll(Set.of("r8c1", "r8c3")), type.label + " は左右へ 1 マス");
            assertFalse(d.contains("r8c0"), type.label + " の横は 1 マスだけ");
        }
    }

    @Test
    void タンクは前方2マス目へ行く途中の駒を飛び越せない() {
        List<Piece> pieces = board(1, TANK, "r8c2", 2, SHOI, "r7c2");
        Set<String> d = dests(pieces, "r8c2");
        assertTrue(d.contains("r7c2"), "1 マス目の敵とは交戦できる");
        assertFalse(d.contains("r6c2"), "駒を飛び越して 2 マス目へは行けない");
    }

    // ============================================================
    // 勝敗表
    // ============================================================

    /** 攻撃側 a が守備側 d に攻め込んだ結果を返す（周囲に他の駒が無い状態）。 */
    private static Outcome fight(PieceType a, PieceType d) {
        List<Piece> pieces = board(1, a, "r6c1", 2, d, "r5c1");
        Map<String, Piece> occupied = GunjinRules.byCell(pieces);
        return GunjinRules.resolve(occupied.get("r6c1"), occupied.get("r5c1"), pieces);
    }

    /** a が d に勝つことを、攻守を入れ替えても成り立つ形で確認する。 */
    private static void beats(PieceType winner, PieceType loser) {
        assertEquals(Outcome.ATTACKER_WINS, fight(winner, loser),
                winner.label + " が攻めて " + loser.label + " に勝つ");
        assertEquals(Outcome.DEFENDER_WINS, fight(loser, winner),
                loser.label + " が攻めても " + winner.label + " に負ける");
    }

    /** a と d が相打ちになることを確認する。 */
    private static void mutual(PieceType a, PieceType d) {
        assertEquals(Outcome.BOTH_LOSE, fight(a, d), a.label + " と " + d.label + " は相打ち");
        assertEquals(Outcome.BOTH_LOSE, fight(d, a), d.label + " と " + a.label + " は相打ち");
    }

    @Test
    void 階級駒は階級の高い方が勝つ() {
        PieceType[] order = { TAISHO, CHUJO, SHOSHO, TAISA, CHUSA, SHOSA, TAII, CHUI, SHOI };
        for (int i = 0; i < order.length; i++) {
            for (int j = i + 1; j < order.length; j++) {
                beats(order[i], order[j]);
            }
        }
    }

    @Test
    void 同じ駒種同士は相打ち() {
        for (PieceType t : PieceType.values()) {
            if (t == GUNKI) {
                continue; // 軍旗は後ろの駒に読み替わるので別テストで扱う
            }
            assertEquals(Outcome.BOTH_LOSE, fight(t, t), t.label + " 同士は相打ち");
        }
    }

    @Test
    void スパイは大将にのみ勝ち他には全敗() {
        beats(SPY, TAISHO);
        for (PieceType t : PieceType.values()) {
            if (t == SPY || t == TAISHO || t == JIRAI || t == GUNKI) {
                continue; // 地雷は相打ち、軍旗は読み替え、同駒種は別テスト
            }
            beats(t, SPY);
        }
    }

    @Test
    void ヒコーキは将官にのみ負ける() {
        beats(TAISHO, HIKOKI);
        beats(CHUJO, HIKOKI);
        beats(SHOSHO, HIKOKI);
        for (PieceType t : new PieceType[] { TAISA, CHUSA, SHOSA, TAII, CHUI, SHOI, TANK, KIHEI, KOHEI, SPY }) {
            beats(HIKOKI, t);
        }
        beats(HIKOKI, JIRAI); // 地雷に勝てるのはヒコーキと工兵だけ
    }

    @Test
    void タンクは将官とヒコーキと工兵に負けそれ以外には勝つ() {
        beats(TAISHO, TANK);
        beats(CHUJO, TANK);
        beats(SHOSHO, TANK);
        beats(HIKOKI, TANK);
        beats(KOHEI, TANK);
        for (PieceType t : new PieceType[] { TAISA, CHUSA, SHOSA, TAII, CHUI, SHOI, KIHEI, SPY }) {
            beats(TANK, t);
        }
    }

    @Test
    void 騎兵はスパイと工兵にのみ勝つ() {
        beats(KIHEI, SPY);
        beats(KIHEI, KOHEI);
        for (PieceType t : new PieceType[] { TAISHO, CHUJO, SHOSHO, TAISA, CHUSA, SHOSA,
                TAII, CHUI, SHOI, HIKOKI, TANK }) {
            beats(t, KIHEI);
        }
    }

    @Test
    void 工兵は地雷とスパイとタンクに勝ち階級駒には負ける() {
        beats(KOHEI, JIRAI);
        beats(KOHEI, SPY);
        beats(KOHEI, TANK);
        for (PieceType t : new PieceType[] { TAISHO, CHUJO, SHOSHO, TAISA, CHUSA, SHOSA,
                TAII, CHUI, SHOI, HIKOKI, KIHEI }) {
            beats(t, KOHEI);
        }
    }

    @Test
    void 地雷はヒコーキと工兵にのみ負けそれ以外とは相打ち() {
        beats(HIKOKI, JIRAI);
        beats(KOHEI, JIRAI);
        for (PieceType t : new PieceType[] { TAISHO, CHUJO, SHOSHO, TAISA, CHUSA, SHOSA,
                TAII, CHUI, SHOI, TANK, KIHEI, SPY }) {
            mutual(t, JIRAI);
        }
    }

    // ============================================================
    // 軍旗
    // ============================================================

    @Test
    void 軍旗はすぐ後ろの味方駒と同じ強さになる() {
        // 先手の軍旗 r8c2、その後ろ（自陣側）r9c2 に大将 → 軍旗は大将として戦う。
        List<Piece> pieces = board(1, GUNKI, "r8c2", 1, TAISHO, "r9c2", 2, TAII, "r7c2");
        Map<String, Piece> occupied = GunjinRules.byCell(pieces);
        assertEquals(TAISHO, GunjinRules.effectiveType(occupied.get("r8c2"), pieces));
        assertEquals(Outcome.DEFENDER_WINS,
                GunjinRules.resolve(occupied.get("r7c2"), occupied.get("r8c2"), pieces),
                "大将の強さを持つ軍旗は大尉の攻撃を退ける");
    }

    @Test
    void 後ろに味方駒が無い軍旗は無条件で負ける() {
        List<Piece> pieces = board(1, GUNKI, "r9c2", 2, SHOI, "r8c2");
        Map<String, Piece> occupied = GunjinRules.byCell(pieces);
        assertNull(GunjinRules.effectiveType(occupied.get("r9c2"), pieces));
        assertEquals(Outcome.ATTACKER_WINS,
                GunjinRules.resolve(occupied.get("r8c2"), occupied.get("r9c2"), pieces));
    }

    @Test
    void 後ろが地雷の軍旗は地雷として相打ちになる() {
        List<Piece> pieces = board(1, GUNKI, "r8c2", 1, JIRAI, "r9c2", 2, TAISHO, "r7c2");
        Map<String, Piece> occupied = GunjinRules.byCell(pieces);
        assertEquals(Outcome.BOTH_LOSE,
                GunjinRules.resolve(occupied.get("r7c2"), occupied.get("r8c2"), pieces));
    }

    // ============================================================
    // 布陣の検証と勝利条件
    // ============================================================

    @Test
    void おまかせ布陣は検証を通る() {
        for (int player : new int[] { 1, 2 }) {
            Map<String, PieceType> setup = GunjinRules.randomSetup(player, new Random(42));
            assertEquals(23, setup.size());
            assertNull(GunjinRules.validateSetup(player, setup));
        }
    }

    @Test
    void 駒数の内訳が違う布陣は弾かれる() {
        Map<String, PieceType> setup = new LinkedHashMap<>(GunjinRules.randomSetup(1, new Random(1)));
        // どこか 1 マスを大将に書き換えると大将 2 枚になり不正。
        String cell = setup.keySet().stream().filter(k -> setup.get(k) != TAISHO).findFirst().orElseThrow();
        setup.put(cell, TAISHO);
        assertNotNull(GunjinRules.validateSetup(1, setup));
    }

    @Test
    void 自陣の外を含む布陣は弾かれる() {
        Map<String, PieceType> setup = new LinkedHashMap<>(GunjinRules.randomSetup(1, new Random(1)));
        String first = setup.keySet().iterator().next();
        PieceType type = setup.remove(first);
        setup.put("r0c0", type); // 敵陣のマス
        assertNotNull(GunjinRules.validateSetup(1, setup));
    }

    @Test
    void 総司令部を占領できるのは大将から少佐までの6種() {
        List<PieceType> canCapture = Arrays.stream(PieceType.values())
                .filter(PieceType::canCaptureHq).toList();
        assertEquals(List.of(TAISHO, CHUJO, SHOSHO, TAISA, CHUSA, SHOSA), canCapture);
    }

    @Test
    void 動かせる駒が地雷と軍旗だけになると合法手が無い() {
        List<Piece> pieces = board(1, JIRAI, "r9c2", 1, GUNKI, "r8c2", 2, TAISHO, "r0c2");
        assertTrue(GunjinRules.legalMoves(pieces, 1).isEmpty(), "地雷と軍旗しか無い側は指せない");
        assertFalse(GunjinRules.legalMoves(pieces, 2).isEmpty());
    }
}
