package com.beatseeker.backend.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【クラスの役割】 textage の譜面データ（{@link TextagePageRunner.PlaybackChart}）から、譜面分析ページの譜面再生に使う
 * 「曲頭からの位置（tick）」付きのオブジェクト列を作る。
 *
 * 位置の単位は tick（4/4 の 1 小節 = 384、4 分音符 = 96）。秒への換算は BPM 変化（{@link Playback#bpmChanges}）を使って
 * フロント側で行う（1 tick = 5 / (8 × BPM) 秒。bms2jsh.js の stat_calc_time と同じ式）。
 *
 * textage のページの読み方は bms2jsh.js（tools/bms2jsh_raw.js）の bars_ / stat_insert に合わせている:
 * <ul>
 *   <li>描画する小節は {@code 1 - gap} 〜 {@code measure}。小節の長さは {@code ln[小節]}（既定 LNDEF = 384）</li>
 *   <li>通常ノーツは sp[] を bms2jsh.js と同じ手順でデコードしたもの（{@link TextagePageRunner#runWithObjects}。位置は小節頭からの tick）</li>
 *   <li>CN は c1[] の {@code [キー, 位置, 長さ(既定 30), フラグ(既定 3)]}（{@link TextagePageRunner.PlaybackChart#charges}）。位置と長さは ×3 で tick。
 *       キーが 10 以上なら 2 キー同時（1 の位と 10 の位）。フラグの 1 = 先頭がこの小節、2 = 終端がこの小節
 *       （小節をまたぐ CN は小節ごとに区切って書かれている）</li>
 *   <li>BPM 変化は tc[] の {@code "BBBppp"}（先頭 3 文字が BPM、残りが位置 ÷ 3）。曲頭の BPM は
 *       BPM 表記が数値ならその値、ソフラン曲（"160〜220"）は tc[] が曲頭で指定する</li>
 * </ul>
 *
 * Spring にも DB にも依存しない。
 */
public final class ChartPlaybackBuilder {

    /** 小節の既定の長さ（tick）。 */
    private static final int LNDEF = 384;
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private ChartPlaybackBuilder() {}

    /**
     * 再生用の譜面データ。
     *
     * @param notes      通常ノーツ {@code [tick, キー]}（キー: 0 = 皿、1〜7 = 鍵盤）。tick → キーの昇順
     * @param charges    CN の区間 {@code [開始 tick, 終了 tick, キー, フラグ]}（フラグ: 1 = 先頭あり、2 = 終端あり）。開始 tick の昇順
     * @param bpmChanges BPM 変化 {@code [tick, BPM]}。先頭は tick 0
     * @param measures   小節線の tick（各小節の頭）
     * @param firstMeasure 最初の小節の表示上の番号
     * @param endTick    最終小節の終わりの tick
     */
    public record Playback(List<int[]> notes, List<int[]> charges, List<double[]> bpmChanges, List<Integer> measures,
                           int firstMeasure, int endTick) {

        /** 通常ノーツと CN の先頭・終端の数（ページが宣言するノーツ数と照合できる）。 */
        public int objectCount() {
            int n = notes.size();
            for (int[] c : charges) {
                if ((c[3] & 1) != 0) n++;
                if ((c[3] & 2) != 0) n++;
            }
            return n;
        }
    }

    /**
     * 【メソッドの役割】 ページの実行結果から再生用の譜面データを作る。
     *
     * @param pc ページの実行結果と bms2jsh.js と同じ手順でデコードした通常ノーツ（{@link TextagePageRunner#runWithObjects}）
     */
    public static Playback build(TextagePageRunner.PlaybackChart pc) {
        TextagePageRunner.PageChart chart = pc.chart();
        int lndef = chart.lndef() > 0 ? chart.lndef() : LNDEF;

        // bms2jsh.js が描く小節（0 〜 measure。ページの b() が範囲を指定し、bars_ は範囲外を描かない）。
        // 曲頭は 1 - gap（gap が負のページは sp[] がそれより前から始まる）。measure が無いページは sp[] の最後まで
        int first = 1 - chart.gap();
        if (!chart.sp().isEmpty()) first = Math.min(first, Collections.min(chart.sp().keySet()));
        first = Math.max(first, 0);
        int last = chart.measure();
        if (last <= 0 && !chart.sp().isEmpty()) last = Collections.max(chart.sp().keySet());

        // 小節ごとの頭の tick
        Map<Integer, Integer> start = new TreeMap<>();
        List<Integer> measures = new ArrayList<>();
        int offset = 0;
        for (int m = first; m <= last; m++) {
            start.put(m, offset);
            measures.add(offset);
            offset += lengthOf(chart, m, lndef);
        }
        int endTick = offset;

        List<int[]> notes = new ArrayList<>();
        for (Map.Entry<Integer, double[]> e : pc.objects().entrySet()) {
            Integer base = start.get(e.getKey());
            if (base == null) continue;
            double[] v = e.getValue();
            for (int i = 0; i + 1 < v.length; i += 2) {
                if (Double.isNaN(v[i]) || Double.isNaN(v[i + 1])) continue;
                int key = (int) v[i + 1];
                if (key < 0 || key > 7) continue;
                notes.add(new int[]{base + (int) Math.round(v[i]), key});
            }
        }
        notes.sort(Comparator.<int[]>comparingInt(n -> n[0]).thenComparingInt(n -> n[1]));

        List<int[]> charges = new ArrayList<>();
        for (Map.Entry<Integer, double[]> e : pc.charges().entrySet()) {
            Integer base = start.get(e.getKey());
            if (base == null) continue;
            double[] v = e.getValue();
            for (int i = 0; i + 3 < v.length; i += 4) {
                if (Double.isNaN(v[i]) || Double.isNaN(v[i + 1]) || Double.isNaN(v[i + 2])) continue;
                int pos = (int) v[i];
                int len = (int) v[i + 1];
                int keys = (int) v[i + 2];
                int flags = Double.isNaN(v[i + 3]) ? 3 : (int) v[i + 3];
                int[] targets = keys < 10 ? new int[]{keys % 10} : new int[]{keys % 10, keys / 10};
                for (int k : targets) {
                    if (k < 0 || k > 7) continue;
                    charges.add(new int[]{base + pos, base + pos + len, k, flags & 3});
                }
            }
        }
        charges.sort(Comparator.<int[]>comparingInt(c -> c[0]).thenComparingInt(c -> c[2]));

        return new Playback(notes, charges, bpmChanges(chart, start), measures, first + chart.gap(), endTick);
    }

    /** BPM 変化を tick 順に並べる。同じ tick の指定は後のもの（bms2jsh.js の stat_insert と同じ）。 */
    private static List<double[]> bpmChanges(TextagePageRunner.PageChart chart, Map<Integer, Integer> start) {
        TreeMap<Integer, Double> byTick = new TreeMap<>();
        double initial = parseInitialBpm(chart.bpm());
        if (initial > 0) byTick.put(0, initial);
        int firstMeasure = start.isEmpty() ? 0 : start.keySet().iterator().next();
        for (Map.Entry<Integer, List<String>> e : chart.tc().entrySet()) {
            Integer base = start.get(e.getKey());
            boolean beforeFirst = base == null && e.getKey() < firstMeasure; // 描画範囲より前の指定は曲頭の BPM として扱う
            if (base == null && !beforeFirst) continue;
            for (String s : e.getValue()) {
                if (s == null || s.length() < 4) continue;
                double bpm = parseNumber(s.substring(0, 3));
                double pos = parseNumber(s.substring(3));
                if (bpm <= 0 || pos < 0) continue;
                byTick.put(beforeFirst ? 0 : base + (int) Math.round(pos * 3), bpm);
            }
        }
        // 曲頭の BPM が分からない（ソフラン曲で tc[] が曲頭を指定していない）ときは最初の指定を曲頭から使う
        if (!byTick.containsKey(0)) byTick.put(0, byTick.isEmpty() ? 120 : byTick.firstEntry().getValue());
        List<double[]> out = new ArrayList<>();
        Double prev = null;
        for (Map.Entry<Integer, Double> e : byTick.entrySet()) {
            if (prev != null && prev.equals(e.getValue())) continue; // 同じ BPM の指定し直しは省く
            out.add(new double[]{e.getKey(), e.getValue()});
            prev = e.getValue();
        }
        return out;
    }

    /** BPM 表記が数値だけならその値。ソフラン（"160〜220" など）や空は 0（tc[] の指定を待つ）。 */
    static double parseInitialBpm(String bpm) {
        if (bpm == null) return 0;
        String t = bpm.trim();
        return t.matches("\\d+(?:\\.\\d+)?") ? Double.parseDouble(t) : 0;
    }

    private static double parseNumber(String s) {
        Matcher m = NUMBER.matcher(s.trim());
        if (!m.lookingAt()) return -1;
        return Double.parseDouble(m.group());
    }

    private static int lengthOf(TextagePageRunner.PageChart chart, int measure, int lndef) {
        Integer len = chart.ln().get(measure);
        return len != null && len > 0 ? len : lndef;
    }
}
