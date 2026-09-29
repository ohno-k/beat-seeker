package com.beatseeker.backend.service;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptableObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【クラスの役割】 textage.cc の譜面ページ（例: {@code score/33/undonigt.html}）に埋め込まれた JavaScript を
 * 難易度ごとのフラグ付きで実行し、SP 譜面のデータ（{@code sp[]} / {@code ln[]} / {@code c1[]} / {@code notes}）を取り出す。
 *
 * textage のページは次のような JS で、閲覧用スクリプト bms2jsh.js が URL の難易度文字から立てたフラグで分岐して
 * 譜面データを組み立て、最後に描画関数（hd / b / w / ft）を呼ぶ:
 * <pre>
 *   genre="TECHNO";title="22DUNK";bpm="135";measure=39;gap--;ln[39]=24;
 *   if(k){notes=323; sp=[,,,"#R1Xg...",...]; sp[4]=sp[3];
 *     if(a){notes=329; sp=[...];}      // ANOTHER は a=1
 *     if(l){notes=265; sp[3]="...";}   // NORMAL は l=1（HYPER の上書き）
 *   }else{ ...DP... }
 *   hd();w("...");b(3,6);ft();
 * </pre>
 * 旧実装（tools/analyze_chart.py）はこれを正規表現で追っていたため、ブロック構造の取り違えで
 * ノーツが欠ける譜面が多かった（キャッシュ済み 6,033 譜面で公式ノーツ数と一致したのは 558 譜面）。
 * 実行方式ではページ自身の {@code notes=} が公式ノーツ数と 96% の譜面で一致し、sp[] も正しい分岐から取れる。
 *
 * フラグは bms2jsh.js（tools/bms2jsh_raw.js）の URL 解釈どおり:
 * <pre>
 *   B(BEGINNER)   l=1, g=1       N(NORMAL)  l=1, hps=1      H(HYPER)  hps=1
 *   A(ANOTHER)    a=1            X(LEGGENDARIA) a=1, kuro=1  いずれも SP なので k=1
 * </pre>
 *
 * 安全性: ページの JS は外部から取得したコードなので、Java のクラスが一切見えないスコープ
 * （{@link Context#initSafeStandardObjects()} + 全拒否の ClassShutter）で、インタプリタモード・命令数の上限つきで実行する。
 * 描画関数など bms2jsh.js 側の関数は空の関数として用意する（2026-09 時点の全ページで使われているのは w / b / hd / ft / im のみ）。
 *
 * Spring にも DB にも依存しない（ユニットテストから直接呼ぶ）。スレッドセーフ。
 */
public final class TextagePageRunner {

    /** 1 回の実行で許す命令数（全ページの最大の数十倍。無限ループ対策）。 */
    private static final int INSTRUCTION_BUDGET = 20_000_000;
    private static final int OBSERVE_EVERY = 10_000;

    private static final Pattern SCRIPT = Pattern.compile("(?is)<script([^>]*)>(.*?)</script>");

    /**
     * bms2jsh.js が定義するグローバル変数の初期値（bms2jsh_raw.js 冒頭と同じ）と、ページが呼ぶ描画関数の空実装。
     * ページの JS はこれらが定義済みである前提で {@code gap--} や {@code if(pty)} を書いている。
     */
    private static final String PRELUDE = String.join("\n",
            "var LNDEF=384;",
            "var ln=[],sp=[],dp=[],tc=[],c1=[],c2=[],cn=[],sc32=[],sc32base=[],sc32loop=[];",
            "var genre,title,artist,bpm,opt,lnse,lnhs; genre=title=artist=bpm=opt=lnse=lnhs=\"\";",
            "var key,ky,back; key=ky=back=7;",
            "var hs,gap,ty,k; hs=gap=ty=k=1;",
            "var cncnt,bsscnt,legacy,prt,pty; cncnt=bsscnt=legacy=prt=pty=0;",
            "var soflan,level,notes,measure,a,l,m,g,db,p1o,hps,flp,off,lnln,lnst,lned,alls,hids,sran,kuro,sftkey,os,hcn,ttl;",
            "soflan=level=notes=measure=a=l=m=g=db=p1o=hps=flp=off=lnln=lnst=lned=alls=hids=sran=kuro=sftkey=os=hcn=ttl=0;",
            "function w(){} function b(){} function hd(){} function ft(){} function im(){} function bars_(){}");

    /**
     * SP の難易度。{@code code} は楽曲マスタの難易度コード、{@code urlChar} は textage URL の 2 文字目、
     * {@code flags} は bms2jsh.js がその文字で立てる変数。
     */
    public enum Difficulty {
        BEGINNER("1", 'B', "l=1;g=1;"),
        NORMAL("2", 'N', "l=1;hps=1;"),
        HYPER("3", 'H', "hps=1;"),
        ANOTHER("4", 'A', "a=1;"),
        LEGGENDARIA("10", 'X', "a=1;kuro=1;");

        public final String code;
        public final char urlChar;
        final String flags;

        Difficulty(String code, char urlChar, String flags) {
            this.code = code;
            this.urlChar = urlChar;
            this.flags = flags;
        }

        /** 楽曲マスタの難易度コード（1/2/3/4/10）から引く。該当なしは null。 */
        public static Difficulty ofCode(String code) {
            for (Difficulty d : values()) if (d.code.equals(code)) return d;
            return null;
        }
    }

    /**
     * 1 譜面分の実行結果。
     *
     * @param sp    小節番号 → 譜面文字列（空でないものだけ）
     * @param ln    小節番号 → その小節の分割数（{@code ln[N]=M} で上書きされた小節だけ）
     * @param lndef 分割数の既定値（{@code LNDEF}）
     * @param notes ページが宣言しているノーツ数（{@code notes=}。未記載なら 0）。公式ノーツ数との照合に使う
     * @param c1    小節番号 → CN（チャージノート）の定義 {@code [[キー, 位置, 長さ?], ...]}（4 つ目以降は切り捨て）
     */
    public record PageChart(Map<Integer, String> sp, Map<Integer, Integer> ln, int lndef, int notes,
                            Map<Integer, List<double[]>> c1) {}

    private static final ContextFactory FACTORY = new ContextFactory() {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setInterpretedMode(true); // 命令数の監視はインタプリタモードでのみ効く
            cx.setInstructionObserverThreshold(OBSERVE_EVERY);
            cx.setClassShutter(className -> false);
            cx.setMaximumInterpreterStackDepth(1000);
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            int[] used = (int[]) cx.getThreadLocal("budget");
            if (used == null) return;
            used[0] += instructionCount;
            if (used[0] > INSTRUCTION_BUDGET) {
                throw new Error("textage ページの実行が命令数の上限を超えました");
            }
        }
    };

    /**
     * 【メソッドの役割】 ページ HTML から、実行対象のインライン script（src 属性なし）を連結して取り出す。
     * {@code <!--} / {@code //-->} の HTML コメント囲みは取り除く。
     */
    public static String extractScript(String html) {
        StringBuilder sb = new StringBuilder();
        Matcher m = SCRIPT.matcher(html);
        while (m.find()) {
            if (m.group(1).toLowerCase().contains("src=")) continue;
            String body = m.group(2).replace("<!--", "").replace("//-->", "");
            sb.append(body).append('\n');
        }
        return sb.toString();
    }

    /**
     * 【メソッドの役割】 ページのスクリプトを指定難易度のフラグで実行し、SP 譜面データを取り出す。
     *
     * @param script     {@link #extractScript} の結果
     * @param difficulty 難易度
     * @return 実行結果（その難易度がページに無い場合は、ページの JS が既定で選ぶ譜面になる。
     *         呼び出し側で {@link PageChart#notes()} を公式ノーツ数と照合して判定する）
     * @throws IllegalStateException スクリプトの実行に失敗した（未知の関数呼び出し・構文エラー・命令数超過）
     */
    public PageChart run(String script, Difficulty difficulty) {
        Context cx = FACTORY.enterContext();
        try {
            cx.putThreadLocal("budget", new int[1]);
            ScriptableObject scope = cx.initSafeStandardObjects();
            cx.evaluateString(scope, PRELUDE, "prelude", 1, null);
            cx.evaluateString(scope, "var s=\"?1" + difficulty.urlChar + "C00\";" + difficulty.flags, "flags", 1, null);
            cx.evaluateString(scope, script, "textage", 1, null);
            return new PageChart(stringArray(scope.get("sp", scope)), intArray(scope.get("ln", scope)),
                    toInt(scope.get("LNDEF", scope), 384), toInt(scope.get("notes", scope), 0),
                    cnArray(scope.get("c1", scope)));
        } catch (RhinoException | Error e) {
            throw new IllegalStateException("textage ページの JS 実行に失敗: " + e.getMessage(), e);
        } finally {
            cx.removeThreadLocal("budget");
            Context.exit();
        }
    }

    private static Map<Integer, String> stringArray(Object o) {
        Map<Integer, String> out = new TreeMap<>();
        if (!(o instanceof NativeArray arr)) return out;
        for (Object id : arr.getIds()) {
            if (!(id instanceof Integer i)) continue;
            Object v = arr.get(i, arr);
            if (v instanceof CharSequence cs && !cs.isEmpty()) out.put(i, cs.toString());
        }
        return out;
    }

    private static Map<Integer, Integer> intArray(Object o) {
        Map<Integer, Integer> out = new TreeMap<>();
        if (!(o instanceof NativeArray arr)) return out;
        for (Object id : arr.getIds()) {
            if (!(id instanceof Integer i)) continue;
            Object v = arr.get(i, arr);
            if (v instanceof Number n) out.put(i, n.intValue());
        }
        return out;
    }

    private static Map<Integer, List<double[]>> cnArray(Object o) {
        Map<Integer, List<double[]>> out = new TreeMap<>();
        if (!(o instanceof NativeArray arr)) return out;
        for (Object id : arr.getIds()) {
            if (!(id instanceof Integer mes)) continue;
            if (!(arr.get(mes, arr) instanceof NativeArray entries)) continue;
            List<double[]> list = new ArrayList<>();
            for (Object eid : entries.getIds()) {
                if (!(eid instanceof Integer ei)) continue;
                if (!(entries.get(ei, entries) instanceof NativeArray entry)) continue;
                // 使うのは [キー, 位置, 長さ] まで（4 つ目以降のフラグは見ない）
                double[] vals = new double[(int) Math.min(entry.getLength(), 3)];
                boolean ok = vals.length >= 2;
                for (int j = 0; ok && j < vals.length; j++) {
                    Object v = entry.get(j, entry);
                    if (v instanceof Number n) vals[j] = n.doubleValue();
                    else ok = false;
                }
                if (ok) list.add(vals);
            }
            out.put(mes, Collections.unmodifiableList(list));
        }
        return out;
    }

    private static int toInt(Object o, int fallback) {
        if (o instanceof Number n && !Double.isNaN(n.doubleValue())) return n.intValue();
        return fallback;
    }
}
