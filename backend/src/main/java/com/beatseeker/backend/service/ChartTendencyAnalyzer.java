package com.beatseeker.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【クラスの役割】 textage の譜面データ（{@link TextagePageRunner.PageChart}）から譜面傾向プロファイルを計算する。
 *
 * tools/analyze_chart.py の {@code decode_measure} / {@code profile_from_sp} / {@code _detect_patterns} の移植で、
 * 計算式・丸め・タグの閾値・JSON のキー名と並びは Python 版と同じ（キャッシュ済み全譜面で出力が一致することを確認済み）。
 * 出力は {@code chart_cache/profiles/*.json} と同じ形の Map なので、
 * {@link ChartTendencyService} の JSON → エンティティ変換にそのまま渡せる。
 *
 * Python 版から変えた点は入力だけ: CN は正規表現で拾った c1[] ではなく、ページの JS を実行した結果の c1[] を使う
 * （位置の換算式 {@code (小節-1)*384 + 位置*3} は同じ）。
 *
 * Spring にも DB にも依存しない。
 */
public final class ChartTendencyAnalyzer {

    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    /** CN 位置の換算に使う 1 小節の分割数（Python 版と同じく曲ごとの LNDEF ではなく固定値）。 */
    private static final int LNDEF = 384;
    /** textage の小節番号のずれ（ページの {@code gap--}）。 */
    private static final int GAP = -1;
    /** 配置パターン検出の最大間隔（16 分音符 = 24 分割）。 */
    private static final int MAX_INTERVAL = 24;

    private static final Map<Integer, String> INAMES = new LinkedHashMap<>();
    static {
        INAMES.put(384, "1measure");
        INAMES.put(192, "half");
        INAMES.put(96, "quarter");
        INAMES.put(64, "quarter-triplet");
        INAMES.put(48, "8th");
        INAMES.put(32, "12th(triplet)");
        INAMES.put(24, "16th");
        INAMES.put(16, "24th");
        INAMES.put(12, "32nd");
        INAMES.put(8, "48th");
        INAMES.put(6, "64th");
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** 1 ノーツ。key: 0 = 皿、1〜7 = 鍵盤。 */
    record Note(int pos, int key) {}

    private ChartTendencyAnalyzer() {}

    // ── 小節デコード ─────────────────────────────────────────────

    /**
     * 【メソッドの役割】 1 小節分の譜面文字列をノーツ列にする（bms2jsh.js のデコーダの移植）。
     *
     * @param sdd 譜面文字列（{@code "#R1Xg..."} 形式か 16 進形式）
     * @param lnN その小節の分割数
     * @return (小節内の位置, キー) の列。並びはデコード順
     */
    static List<Note> decodeMeasure(String sdd, int lnN) {
        List<Note> notes = new ArrayList<>();
        if (sdd == null || sdd.isEmpty()) return notes;
        int len = sdd.length();

        if (sdd.charAt(0) == '#') {
            int sft = 1;
            int v2c = 0;
            while (sft < len) {
                char c = sdd.charAt(sft);
                String v2o = "";
                int v2v = (v2c != 0 ? 1 : 3) * lnN / 6;
                int v2s;
                int v2p;
                int v2t;
                int v2b;
                switch (c) {
                    case 'C', 'c', 'R', 'r', 'P', 'p' -> {
                        v2s = switch (c) { case 'c' -> 96; case 'r' -> 48; case 'p' -> 24; default -> 0; };
                        v2p = switch (c) { case 'C', 'c' -> 192; case 'R', 'r' -> 96; default -> 48; };
                        v2t = 0;
                        v2o = (v2c == 0 && sft + 1 < len) ? String.valueOf(sdd.charAt(sft + 1)) : "";
                        sft += 2;
                    }
                    case 'B', 'b', 'Q', 'q', 'O', 'o', 'X', 'x', 'Z', 'S', 's', 'T', 't', 'U' -> {
                        v2p = switch (c) {
                            case 'B', 'b' -> 192;
                            case 'Q', 'q' -> 96;
                            case 'O', 'o' -> 48;
                            case 'X', 'x' -> 24;
                            case 'Z' -> 12;
                            case 'S', 's' -> 64;
                            case 'T', 't' -> 32;
                            default -> 16; // U
                        };
                        v2s = Character.isLowerCase(c) ? v2p / 2 : 0;
                        v2t = 1;
                        v2b = ceilDiv(v2v, v2p) + 1;
                        v2o = slice(sdd, sft + 1, sft + v2b);
                        sft += v2b;
                    }
                    case '1', '2', '3', '4', '5', '6', '7' -> {
                        String o = slice(sdd, sft, sft + 3);
                        sft += 3;
                        if (o.length() < 3) continue;
                        int ob2 = o.charAt(0) - '0';
                        int vh = b64i(o.charAt(1)) * 64 + b64i(o.charAt(2));
                        if (vh < lnN) notes.add(new Note(vh, ob2));
                        continue;
                    }
                    case '8', '9' -> {
                        int bits = sft + 1 < len ? b64i(sdd.charAt(sft + 1)) : 0;
                        String pos = slice(sdd, sft + 2, sft + 4);
                        StringBuilder abs = new StringBuilder();
                        if (c == '9') abs.append('1').append(pos);
                        for (int ii = 0; ii < 6; ii++) {
                            if ((bits & (1 << ii)) != 0) abs.append((char) ('2' + ii)).append(pos);
                        }
                        sft += 4;
                        for (int j = 0; j + 3 <= abs.length(); j += 3) {
                            char kc = abs.charAt(j);
                            int ob2 = (kc >= '0' && kc <= '9') ? kc - '0' : 0;
                            int vh = b64i(abs.charAt(j + 1)) * 64 + b64i(abs.charAt(j + 2));
                            if (vh < lnN) notes.add(new Note(vh, ob2));
                        }
                        continue;
                    }
                    case '-' -> {
                        v2c = 1;
                        sft += 1;
                        continue;
                    }
                    case '_' -> {
                        String rem = sft == len - 1 ? "AA" : sdd.substring(sft + 1);
                        for (int j = 0; j + 2 <= rem.length(); j += 2) {
                            int vh = b64i(rem.charAt(j)) * 64 + b64i(rem.charAt(j + 1));
                            if (vh < lnN) notes.add(new Note(vh, 0));
                        }
                        return notes;
                    }
                    default -> {
                        return notes;
                    }
                }

                // 並び（v2k）を作る
                StringBuilder v2k = new StringBuilder();
                if (v2t == 1) {
                    for (int i = 0; i < v2o.length(); i++) {
                        int vx = b64i(v2o.charAt(i));
                        if (v2c == 0) {
                            v2k.append(vx / 8).append(vx % 8);
                        } else {
                            for (int i3 = 5; i3 >= 0; i3--) v2k.append(((vx >> i3) & 1) != 0 ? '1' : '0');
                        }
                    }
                } else {
                    int steps = Math.floorDiv(lnN - v2s + v2p - 1, v2p);
                    String val = v2c != 0 ? "1" : (v2o.isEmpty() ? "0" : v2o);
                    if (steps > 0) v2k.append(val.repeat(steps));
                }

                // ノーツを置く
                int vi = 0;
                for (int i2 = v2s; i2 < lnN; i2 += v2p, vi++) {
                    if (vi >= v2k.length()) continue;
                    char ch = v2k.charAt(vi);
                    if (ch == '0') continue;
                    if (v2c == 1) {
                        notes.add(new Note(i2, 0));
                    } else {
                        int key = (ch >= '0' && ch <= '9') ? ch - '0' : 0;
                        if (key > 0) notes.add(new Note(i2, key));
                    }
                }
            }
            return notes;
        }

        // 16 進形式
        int lenVal;
        int sft;
        if (sdd.charAt(0) == 'x') {
            Integer parsed = pyHex(slice(sdd, 1, 4));
            lenVal = parsed != null ? parsed : len - 4;
            if (lenVal == 0) lenVal = 1;
            sft = 4;
        } else {
            lenVal = len;
            sft = 0;
        }
        int div = 0;
        while (sft < len) {
            while (sft < len && sdd.charAt(sft) == '@') {
                Integer d = pyHex(slice(sdd, sft + 1, sft + 3));
                if (d != null) div += d * 2;
                sft += 3;
            }
            if (sft + 2 > len) break;
            Integer y = pyHex(sdd.substring(sft, sft + 2));
            if (y == null) {
                sft += 2;
                div += 2;
                continue;
            }
            int pos = (int) Math.floorDiv((long) lnN * div, (long) lenVal);
            for (int j = 0; j < 8; j++) {
                if ((y & (1 << j)) != 0) notes.add(new Note(pos, j));
            }
            sft += 2;
            div += 2;
        }
        return notes;
    }

    // ── プロファイル ────────────────────────────────────────────

    /**
     * 【メソッドの役割】 譜面データから傾向プロファイルを計算する（Python 版 profile_from_sp と同じ出力）。
     *
     * @param chart  ページの実行結果
     * @param bpmRaw BPM 表記（"196" / "12-300"。ソフランは最大値を使う）
     * @return プロファイル（notes はデコードしたノーツ数。公式ノーツ数で上書きするのは呼び出し側）
     */
    public static Map<String, Object> profile(TextagePageRunner.PageChart chart, String bpmRaw) {
        Map<Integer, String> sp = chart.sp();
        int lndef = chart.lndef();
        Map<Integer, Integer> lnMap = chart.ln();
        if (bpmRaw == null) bpmRaw = "";

        List<Integer> measures = new ArrayList<>(new TreeSet<>(sp.keySet()));
        int minMes = measures.isEmpty() ? 0 : measures.get(0);
        int maxMes = measures.isEmpty() ? -1 : measures.get(measures.size() - 1);

        // 小節ごとの累積オフセット（可変長小節に対応）
        Map<Integer, Integer> cumOffset = new TreeMap<>();
        int offset = 0;
        for (int m = minMes; m <= maxMes; m++) {
            cumOffset.put(m, offset);
            offset += lnMap.getOrDefault(m, lndef);
        }

        List<Note> allNotes = new ArrayList<>();
        Map<Integer, Integer> measureNotes = new TreeMap<>();
        Map<Integer, Integer> measureKbd = new TreeMap<>();
        Map<Integer, Integer> measureScr = new TreeMap<>();
        for (int mes : measures) {
            String sdd = sp.get(mes);
            if (sdd == null || sdd.isEmpty()) continue;
            int lnN = lnMap.getOrDefault(mes, lndef);
            int base = cumOffset.getOrDefault(mes, (mes + GAP) * lndef);
            List<Note> decoded = decodeMeasure(sdd, lnN);
            measureNotes.put(mes, decoded.size());
            int kbd = 0;
            for (Note n : decoded) if (n.key() != 0) kbd++;
            measureKbd.put(mes, kbd);
            measureScr.put(mes, decoded.size() - kbd);
            for (Note n : decoded) allNotes.add(new Note(base + n.pos(), n.key()));
        }
        allNotes.sort(Comparator.comparingInt(Note::pos).thenComparingInt(Note::key));

        // 同時刻のノーツをまとめたイベント（位置 → 押すキーの数）
        Map<Integer, Integer> eventSizes = new TreeMap<>();
        for (Note n : allNotes) eventSizes.merge(n.pos(), 1, Integer::sum);
        List<Integer> eventPos = new ArrayList<>(eventSizes.keySet());
        List<Integer> pats = new ArrayList<>(eventSizes.values());

        List<Integer> intervals = new ArrayList<>();
        for (int i = 1; i < eventPos.size(); i++) intervals.add(eventPos.get(i) - eventPos.get(i - 1));
        Map<Integer, Integer> icounts = counter(intervals);
        int totalW = sumValues(icounts);
        if (totalW == 0) totalW = 1;

        int bpmMain = 0;
        Matcher dm = DIGITS.matcher(bpmRaw);
        while (dm.find()) bpmMain = Math.max(bpmMain, parseIntSafe(dm.group()));
        boolean isSoflan = bpmRaw.contains("-");

        int dominantInterval = icounts.isEmpty() ? 24 : mostCommon(icounts);
        double dominantEff16 = dominantInterval != 0 ? 24.0 * bpmMain / dominantInterval : 0;

        double weightedEff16 = 0.0;
        for (Map.Entry<Integer, Integer> e : icounts.entrySet()) {
            int d = e.getKey();
            if (d > 0) weightedEff16 += (24.0 * bpmMain / d) * ((double) e.getValue() / totalW);
        }

        Map<Integer, Integer> chordDist = new TreeMap<>(counter(pats));
        int totalEv = pats.size();
        int scratchCnt = 0;
        for (Note n : allNotes) if (n.key() == 0) scratchCnt++;
        double scratchPct = allNotes.isEmpty() ? 0 : (double) scratchCnt / allNotes.size() * 100;
        int singles = chordDist.getOrDefault(1, 0);
        double chordPct = totalEv != 0 ? (double) (totalEv - singles) / totalEv * 100 : 0;
        double singlePct = totalEv != 0 ? (double) singles / totalEv * 100 : 0;

        int ranuchi = 0;
        for (int i = 0; i < pats.size() - 3; i++) {
            if (pats.get(i) >= 2 && pats.get(i + 1) == 1 && pats.get(i + 2) >= 2 && pats.get(i + 3) == 1) ranuchi++;
        }

        Map<String, Object> intervalDetail = intervalDetail(icounts, totalW, bpmMain);

        List<String> tags = new ArrayList<>();
        if (scratchPct >= 15) tags.add("scratch_very_heavy");
        else if (scratchPct >= 8) tags.add("scratch_heavy");
        else if (scratchPct < 3) tags.add("scratch_low");
        if (chordPct >= 65) tags.add("chord_heavy");
        if (singlePct >= 60) tags.add("single_heavy");
        if (icounts.keySet().stream().anyMatch(d -> d <= 12)) tags.add("has_32nd");
        if (icounts.keySet().stream().anyMatch(d -> d >= 30 && d <= 34)) tags.add("has_triplet");
        if (isSoflan) tags.add("soflan");
        if (dominantEff16 >= 180) tags.add("high_effective_bpm");
        else if (dominantEff16 >= 120) tags.add("mid_effective_bpm");
        else tags.add("low_effective_bpm");

        // 鍵盤のみ / 皿のみのインターバル分布
        Map<String, Object> kbdProfile = new LinkedHashMap<>();
        List<Integer> kbdPositions = new ArrayList<>();
        List<Integer> scrPositions = new ArrayList<>();
        for (Note n : allNotes) (n.key() != 0 ? kbdPositions : scrPositions).add(n.pos());
        if (kbdPositions.size() >= 2) {
            Map<Integer, Integer> c = counter(diffs(kbdPositions));
            kbdProfile.put("kbd_interval_dist", intervalDetail(c, Math.max(sumValues(c), 1), bpmMain));
        }
        Map<String, Object> scrProfile = new LinkedHashMap<>();
        if (scrPositions.size() >= 2) {
            Map<Integer, Integer> c = counter(diffs(scrPositions));
            scrProfile.put("scr_interval_dist", intervalDetail(c, Math.max(sumValues(c), 1), bpmMain));
        }

        Map<String, Object> cnProfile = cnProfile(chart.c1(), allNotes, bpmMain);

        int[] patterns = detectPatterns(allNotes);
        int totalNotes = Math.max(allNotes.size(), 1);
        if (patterns[0] >= 5) tags.add("jack_heavy");
        else if (patterns[0] >= 2) tags.add("has_jack");
        if (patterns[2] >= 3) tags.add("trill_heavy");
        else if (patterns[2] >= 1) tags.add("has_trill");
        if (patterns[4] >= 5) tags.add("stairs_heavy");
        else if (patterns[4] >= 2) tags.add("has_stairs");
        if (patterns[6] >= 3) tags.add("dstairs_heavy");
        else if (patterns[6] >= 1) tags.add("has_dstairs");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("notes", allNotes.size());
        out.put("events", totalEv);
        out.put("bpm_main", bpmMain);
        out.put("is_soflan", isSoflan);
        out.put("dominant_interval", dominantInterval);
        out.put("dominant_eff16", dominantInterval != 0 ? (Object) round(dominantEff16, 1) : (Object) 0);
        out.put("weighted_eff16", round(weightedEff16, 1));
        out.put("scratch_pct", round(scratchPct, 2));
        out.put("chord_pct", round(chordPct, 2));
        out.put("single_pct", round(singlePct, 2));
        out.put("ranuchi", ranuchi);
        out.put("interval_dist", intervalDetail);
        Map<String, Integer> chordOut = new LinkedHashMap<>();
        chordDist.forEach((k, v) -> chordOut.put(String.valueOf(k), v));
        out.put("chord_dist", chordOut);
        String[] names = {"jack", "trill", "stairs", "dstairs"};
        for (int i = 0; i < names.length; i++) {
            out.put(names[i] + "_count", patterns[i * 2]);
            out.put(names[i] + "_notes", patterns[i * 2 + 1]);
            out.put(names[i] + "_pct", round((double) patterns[i * 2 + 1] / totalNotes * 100, 2));
        }
        out.put("tags", tags);
        out.put("measure_notes", measureRange(measureNotes, minMes, maxMes));
        out.put("measure_notes_kbd", measureRange(measureKbd, minMes, maxMes));
        out.put("measure_notes_scr", measureRange(measureScr, minMes, maxMes));
        out.putAll(kbdProfile);
        out.putAll(scrProfile);
        out.putAll(cnProfile);
        return out;
    }

    /** CN（チャージノート）の集計。c1 が空なら空 Map（Python 版と同じくキー自体を出さない）。 */
    private static Map<String, Object> cnProfile(Map<Integer, List<double[]>> c1, List<Note> allNotes, int bpmMain) {
        Map<String, Object> out = new LinkedHashMap<>();
        // (開始, 終了, キー)。2 キー同時の CN は同じ区間に 2 件
        List<double[]> events = new ArrayList<>();
        for (Map.Entry<Integer, List<double[]>> e : c1.entrySet()) {
            int mes = e.getKey();
            for (double[] entry : e.getValue()) {
                if (entry.length < 2) continue;
                double keyOrChord = entry[0];
                double duration = entry.length >= 3 ? entry[2] : 30;
                double start = (double) (mes + GAP) * LNDEF + entry[1] * 3;
                double end = (double) (mes + GAP) * LNDEF + (entry[1] + duration) * 3;
                if (keyOrChord < 10) {
                    events.add(new double[]{start, end, keyOrChord});
                } else {
                    events.add(new double[]{start, end, pyMod(keyOrChord, 10)});
                    events.add(new double[]{start, end, Math.floor(keyOrChord / 10)});
                }
            }
        }
        if (events.isEmpty()) return out;

        Set<List<Double>> objects = new HashSet<>();
        TreeSet<Double> starts = new TreeSet<>();
        int scratch = 0;
        List<double[]> kbdHolds = new ArrayList<>();
        for (double[] ev : events) {
            objects.add(List.of(ev[0], ev[1]));
            starts.add(ev[0]);
            if (ev[2] == 0) scratch++;
            else kbdHolds.add(ev);
        }
        List<Double> startList = new ArrayList<>(starts);
        List<Double> cnIntervals = new ArrayList<>();
        for (int i = 1; i < startList.size(); i++) cnIntervals.add(startList.get(i) - startList.get(i - 1));
        Map<Double, Integer> cnCounts = new LinkedHashMap<>();
        for (Double d : cnIntervals) cnCounts.merge(d, 1, Integer::sum);
        int cnTotal = Math.max(cnIntervals.size(), 1);
        Map<String, Object> cnDetail = new LinkedHashMap<>();
        cnCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(e -> {
                    double d = e.getKey();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", d == Math.rint(d) ? iname((int) d) : "(" + d + ")");
                    m.put("count", e.getValue());
                    m.put("pct", round((double) e.getValue() / cnTotal * 100, 1));
                    m.put("eff16", d > 0 ? (Object) round(24.0 * bpmMain / d, 1) : (Object) 0);
                    cnDetail.put(numberKey(d), m);
                });

        List<Integer> nonCnKbd = new ArrayList<>();
        for (Note n : allNotes) if (n.key() != 0) nonCnKbd.add(n.pos());
        double overlapPct = 0.0;
        if (!kbdHolds.isEmpty() && !nonCnKbd.isEmpty()) {
            int overlap = 0;
            for (int pos : nonCnKbd) {
                for (double[] h : kbdHolds) {
                    if (h[0] < pos && pos < h[1]) { overlap++; break; }
                }
            }
            overlapPct = round((double) overlap / nonCnKbd.size() * 100, 2);
        }

        out.put("cn_notes", objects.size());
        out.put("cn_scratch_pct", round((double) scratch / events.size() * 100, 2));
        out.put("cn_kbd_overlap_pct", overlapPct);
        out.put("cn_interval_dist", cnDetail);
        return out;
    }

    /**
     * 配置パターン（縦連・トリル・階段・二重階段）の検出（Python 版 _detect_patterns の移植）。
     *
     * @return {jack_count, jack_notes, trill_count, trill_notes, stairs_count, stairs_notes, dstairs_count, dstairs_notes}
     */
    private static int[] detectPatterns(List<Note> allNotes) {
        Map<Integer, List<Integer>> keyPositions = new TreeMap<>();
        Map<Integer, TreeSet<Integer>> posBtnKeys = new TreeMap<>();
        for (Note n : allNotes) {
            if (n.key() < 1) continue;
            keyPositions.computeIfAbsent(n.key(), k -> new ArrayList<>()).add(n.pos());
            posBtnKeys.computeIfAbsent(n.pos(), p -> new TreeSet<>()).add(n.key());
        }

        // 縦連打: 同一鍵が 16 分以下の間隔で 3 打以上
        int jackCount = 0;
        int jackNotes = 0;
        for (List<Integer> positions : keyPositions.values()) {
            int i = 0;
            while (i < positions.size()) {
                int run = 1;
                while (i + run < positions.size() && positions.get(i + run) - positions.get(i + run - 1) <= MAX_INTERVAL) run++;
                if (run >= 3) {
                    jackCount++;
                    jackNotes += run;
                }
                i += Math.max(run, 1);
            }
        }

        // トリル: 2 鍵が交互に 16 分以下の間隔で 6 ノーツ以上
        int trillCount = 0;
        int trillNotes = 0;
        List<Integer> buttonKeys = new ArrayList<>(keyPositions.keySet());
        Set<Long> trillUsed = new HashSet<>();
        for (int ia = 0; ia < buttonKeys.size(); ia++) {
            for (int ib = ia + 1; ib < buttonKeys.size(); ib++) {
                int keyA = buttonKeys.get(ia);
                int keyB = buttonKeys.get(ib);
                List<Note> merged = new ArrayList<>();
                for (int p : keyPositions.get(keyA)) merged.add(new Note(p, keyA));
                for (int p : keyPositions.get(keyB)) merged.add(new Note(p, keyB));
                merged.sort(Comparator.comparingInt(Note::pos).thenComparingInt(Note::key));
                int i = 0;
                while (i < merged.size() - 1) {
                    int run = 1;
                    int j = i + 1;
                    while (j < merged.size()) {
                        int gap = merged.get(j).pos() - merged.get(j - 1).pos();
                        if (gap > MAX_INTERVAL || gap <= 0 || merged.get(j).key() == merged.get(j - 1).key()) break;
                        run++;
                        j++;
                    }
                    if (run >= 6) {
                        Set<Long> inRun = new HashSet<>();
                        for (int k = i; k < i + run; k++) inRun.add(noteId(merged.get(k)));
                        int fresh = 0;
                        for (Long id : inRun) if (!trillUsed.contains(id)) fresh++;
                        if (fresh >= run * 0.5) {
                            trillCount++;
                            trillNotes += run;
                            trillUsed.addAll(inRun);
                        }
                        i += run;
                    } else {
                        i++;
                    }
                }
            }
        }

        // 階段: 単ノーツが鍵番号 ±1 ずつ、16 分以下の間隔で 4 ステップ以上
        List<int[]> singles = new ArrayList<>();
        List<int[][]> chord2 = new ArrayList<>();
        for (Map.Entry<Integer, TreeSet<Integer>> e : posBtnKeys.entrySet()) {
            if (e.getValue().size() == 1) singles.add(new int[]{e.getKey(), e.getValue().first()});
            if (e.getValue().size() == 2) {
                chord2.add(new int[][]{{e.getKey()}, {e.getValue().first(), e.getValue().last()}});
            }
        }
        int stairsCount = 0;
        int stairsNotes = 0;
        int i = 0;
        while (i < singles.size() - 1) {
            int bestRun = 1;
            for (int direction : new int[]{1, -1}) {
                int run = 1;
                int prevPos = singles.get(i)[0];
                int prevKey = singles.get(i)[1];
                int j = i + 1;
                while (j < singles.size()) {
                    int curPos = singles.get(j)[0];
                    int curKey = singles.get(j)[1];
                    int interval = curPos - prevPos;
                    if (interval > MAX_INTERVAL) break;
                    if (interval == 0) { j++; continue; }
                    if (curKey == prevKey + direction) {
                        run++;
                        prevPos = curPos;
                        prevKey = curKey;
                        j++;
                    } else {
                        break;
                    }
                }
                if (run > bestRun) bestRun = run;
            }
            if (bestRun >= 4) {
                stairsCount++;
                stairsNotes += bestRun;
                i += bestRun;
            } else {
                i++;
            }
        }

        // 二重階段: 2 鍵同時押しが ±1 ずつ平行移動、16 分以下で 4 ステップ以上
        int dstairsCount = 0;
        int dstairsNotes = 0;
        i = 0;
        while (i < chord2.size() - 1) {
            int bestRun = 1;
            for (int direction : new int[]{1, -1}) {
                int run = 1;
                int prevPos = chord2.get(i)[0][0];
                int[] prevKeys = chord2.get(i)[1];
                int j = i + 1;
                while (j < chord2.size()) {
                    int curPos = chord2.get(j)[0][0];
                    int[] curKeys = chord2.get(j)[1];
                    int interval = curPos - prevPos;
                    if (interval > MAX_INTERVAL || interval == 0) {
                        if (interval == 0) { j++; continue; }
                        break;
                    }
                    if (curKeys[0] - prevKeys[0] == direction && curKeys[1] - prevKeys[1] == direction) {
                        run++;
                        prevPos = curPos;
                        prevKeys = curKeys;
                        j++;
                    } else {
                        break;
                    }
                }
                if (run > bestRun) bestRun = run;
            }
            if (bestRun >= 4) {
                dstairsCount++;
                dstairsNotes += bestRun * 2;
                i += bestRun;
            } else {
                i++;
            }
        }

        return new int[]{jackCount, jackNotes, trillCount, trillNotes, stairsCount, stairsNotes, dstairsCount, dstairsNotes};
    }

    // ── 小道具 ───────────────────────────────────────────────

    /** 上位 10 件のインターバル内訳（件数の降順、同数は出現順。Python の sorted(key=-count) と同じ安定ソート）。 */
    private static Map<String, Object> intervalDetail(Map<Integer, Integer> counts, int total, int bpmMain) {
        Map<String, Object> out = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(e -> {
                    int d = e.getKey();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", iname(d));
                    m.put("count", e.getValue());
                    m.put("pct", round((double) e.getValue() / total * 100, 1));
                    m.put("eff16", d > 0 ? (Object) round(24.0 * bpmMain / d, 1) : (Object) 0);
                    out.put(String.valueOf(d), m);
                });
        return out;
    }

    static String iname(int d) {
        String exact = INAMES.get(d);
        if (exact != null) return exact;
        for (Map.Entry<Integer, String> e : INAMES.entrySet()) { // キーの降順
            if (Math.abs(d - e.getKey()) <= 1) return "~" + e.getValue();
        }
        return "(" + d + ")";
    }

    /** 出現順を保った件数集計（Python の Counter と同じ並び）。 */
    private static Map<Integer, Integer> counter(List<Integer> values) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (Integer v : values) out.merge(v, 1, Integer::sum);
        return out;
    }

    /** 最頻値。同数なら先に出たもの（Counter.most_common(1) と同じ）。 */
    private static int mostCommon(Map<Integer, Integer> counts) {
        int best = 0;
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static int sumValues(Map<Integer, Integer> m) {
        int s = 0;
        for (int v : m.values()) s += v;
        return s;
    }

    private static List<Integer> diffs(List<Integer> sortedPositions) {
        List<Integer> out = new ArrayList<>();
        for (int i = 1; i < sortedPositions.size(); i++) out.add(sortedPositions.get(i) - sortedPositions.get(i - 1));
        return out;
    }

    private static List<Integer> measureRange(Map<Integer, Integer> m, int minMes, int maxMes) {
        List<Integer> out = new ArrayList<>();
        for (int i = minMes; i <= maxMes; i++) out.add(m.getOrDefault(i, 0));
        return out;
    }

    private static long noteId(Note n) {
        return ((long) n.pos() << 8) | (n.key() & 0xff);
    }

    /** Python の round(x, n)（2 進の正確な値に対する偶数丸め）。 */
    static double round(double x, int digits) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return x;
        return new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    private static double pyMod(double a, double b) {
        double r = a % b;
        return (r != 0 && (r < 0) != (b < 0)) ? r + b : r;
    }

    /** 整数値なら "24"、そうでなければ "24.5"（Python の dict キーを JSON にしたときの表記）。 */
    private static String numberKey(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static int b64i(char c) {
        int i = B64.indexOf(c);
        return Math.max(i, 0);
    }

    private static int ceilDiv(int a, int b) {
        return -Math.floorDiv(-a, b);
    }

    /** Python のスライス s[a:b]（範囲外は切り詰め）。 */
    private static String slice(String s, int a, int b) {
        int len = s.length();
        a = Math.max(0, Math.min(a, len));
        b = Math.max(0, Math.min(b, len));
        return b > a ? s.substring(a, b) : "";
    }

    /** Python の int(s, 16)。前後の空白・符号・"0x"・数字間の "_" を許し、解釈できなければ null。 */
    static Integer pyHex(String s) {
        String t = s.strip();
        int sign = 1;
        if (!t.isEmpty() && (t.charAt(0) == '+' || t.charAt(0) == '-')) {
            if (t.charAt(0) == '-') sign = -1;
            t = t.substring(1);
        }
        if (t.length() >= 2 && t.charAt(0) == '0' && (t.charAt(1) == 'x' || t.charAt(1) == 'X')) {
            t = t.substring(2);
            if (t.startsWith("_")) t = t.substring(1);
        }
        if (t.isEmpty() || t.startsWith("_") || t.endsWith("_") || t.contains("__")) return null;
        t = t.replace("_", "");
        for (int i = 0; i < t.length(); i++) {
            if (Character.digit(t.charAt(i), 16) < 0) return null;
        }
        try {
            return sign * Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
