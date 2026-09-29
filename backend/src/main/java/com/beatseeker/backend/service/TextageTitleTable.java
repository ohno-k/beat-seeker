package com.beatseeker.backend.service;

import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【クラスの役割】 textage の曲一覧 {@code https://textage.cc/score/titletbl.js} を読み、
 * 楽曲マスタの曲から textage の譜面ページ（{@code {版}/{キー}.html}）を探す。
 *
 * titletbl.js の 1 行（Shift_JIS）:
 * <pre>
 *   'showtime'	:[33,3372,0,"HYBRID JERSEY CLUB","MK&amp;Kanae Asaba","Show Time".fontcolor("#00a060")],
 *   'backfwfv'	:[0,3909,1,"TRANCE","Sota Fujimori","Back Into The Light","&lt;br&gt;-Feeling Won't Fade Vocal Mix-"],
 *   'genom'	:[SS,104,1,"TRANCE","L.E.D.LIGHT","GENOM SCREAMS"],
 * </pre>
 * 並びは [版, ID, OPT, GENRE, ARTIST, TITLE, SUBTITLE?]。版が 0 の行は家庭用のみの曲、99 はダミーなので除外する。
 * 版の {@code SS} はファイル冒頭の {@code SS=35;} で定義される substream で、ページのフォルダは {@code s}。
 * TITLE の {@code .fontcolor(...)} は色付け、文字列中の {@code &#9835;} などは HTML 実体参照。
 *
 * Spring にも DB にも依存しない。
 */
public final class TextageTitleTable {

    private static final Pattern ROW = Pattern.compile("^\\s*'([^']+)'\\s*:\\s*\\[(.*)\\]\\s*,?\\s*$");
    private static final Pattern CONST = Pattern.compile("^\\s*([A-Z_]+)\\s*=\\s*(\\d+)\\s*;");
    private static final Pattern STYLE_CALL = Pattern.compile("\\.(?:fontcolor|link|bold|italics|big|small|sup|sub|fixed|strike)\\((?:\"(?:[^\"\\\\]|\\\\.)*\")?\\)");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    /**
     * 1 曲分のエントリ。
     *
     * @param key      ページ名（{@code titletbl} のキー）
     * @param version  版（substream は {@code SS} の値）
     * @param dir      ページのフォルダ（版の数字。substream は "s"）
     * @param genre    GENRE（実体参照を戻し、タグを除いたもの）
     * @param artist   ARTIST
     * @param title    TITLE
     * @param subtitle SUBTITLE（無ければ空文字）
     */
    public record Entry(String key, int version, String dir, String genre, String artist, String title, String subtitle) {
        /** ページのパス（{@code 33/showtime.html}）。 */
        public String pagePath() {
            return dir + "/" + key + ".html";
        }

        /** 表示用の曲名（TITLE + SUBTITLE）。 */
        public String fullTitle() {
            return subtitle.isEmpty() ? title : title + subtitle;
        }
    }

    private final List<Entry> entries;

    private TextageTitleTable(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> entries() {
        return entries;
    }

    /**
     * 【メソッドの役割】 titletbl.js の本文を解析する。
     *
     * @param js 本文（Shift_JIS からデコード済み）
     * @return 解析結果（家庭用のみ・ダミーの行は含まない）
     * @throws IllegalStateException 曲の行が 1 つも読めなかった（ファイル形式が変わった）
     */
    public static TextageTitleTable parse(String js) {
        Map<String, Integer> consts = new HashMap<>();
        List<Entry> out = new ArrayList<>();
        for (String line : js.split("\\r?\\n")) {
            Matcher cm = CONST.matcher(line);
            if (cm.find()) {
                consts.put(cm.group(1), Integer.parseInt(cm.group(2)));
                continue;
            }
            Matcher m = ROW.matcher(line);
            if (!m.matches()) continue;
            List<String> fields = splitFields(STYLE_CALL.matcher(m.group(2)).replaceAll(""));
            if (fields.size() < 6) continue;
            String verField = fields.get(0).trim();
            int version;
            String dir;
            if (verField.matches("\\d+")) {
                version = Integer.parseInt(verField);
                dir = verField;
            } else if (consts.containsKey(verField)) {
                version = consts.get(verField);
                dir = "SS".equals(verField) ? "s" : String.valueOf(version);
            } else {
                continue;
            }
            if (version == 0 || version >= 99) continue;
            out.add(new Entry(m.group(1), version, dir, text(fields.get(3)), text(fields.get(4)), text(fields.get(5)),
                    fields.size() > 6 ? text(fields.get(6)) : ""));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("titletbl.js から曲を読めませんでした（形式が変わった可能性があります）");
        }
        return new TextageTitleTable(out);
    }

    /** 照合の結果。 */
    public enum MatchKind { TITLE, ARTIST_GENRE }

    public record Candidate(Entry entry, MatchKind kind) {}

    /**
     * 【メソッドの役割】 楽曲マスタの曲に対応しそうなページを、確からしい順に返す（最終判断はページのノーツ数照合で行う）。
     *
     * 手順:
     *  1. 曲名の正規化一致（TITLE 単体、または TITLE+SUBTITLE）。複数あれば ARTIST・GENRE が一致するものを優先し、版の新しい順
     *  2. 1 が無ければ ARTIST と GENRE の正規化一致（公式 CSV が特殊文字を置き換えた曲名の救済。{@code excludedPages} は除く）
     *
     * @param title         楽曲マスタの曲名
     * @param artist        楽曲マスタの ARTIST
     * @param genre         楽曲マスタの GENRE
     * @param excludedPages 他の曲に使われているページ（2 の候補から除く）
     * @return 候補（最大 3 件）
     */
    public List<Candidate> candidates(String title, String artist, String genre, Collection<String> excludedPages) {
        String nt = norm(title);
        String na = norm(artist);
        String ng = norm(genre);
        List<Entry> byTitle = new ArrayList<>();
        for (Entry e : entries) {
            if (nt.equals(norm(e.title())) || nt.equals(norm(e.fullTitle()))) byTitle.add(e);
        }
        List<Candidate> out = new ArrayList<>();
        if (!byTitle.isEmpty()) {
            byTitle.sort(Comparator.<Entry>comparingInt(e -> (na.equals(norm(e.artist())) ? 0 : 1) + (ng.equals(norm(e.genre())) ? 0 : 1))
                    .thenComparing(Comparator.comparingInt(Entry::version).reversed()));
            for (Entry e : byTitle) {
                if (out.size() >= 3) break;
                out.add(new Candidate(e, MatchKind.TITLE));
            }
            return out;
        }
        if (na.isEmpty() || ng.isEmpty()) return out;
        for (Entry e : entries) {
            if (excludedPages.contains(e.pagePath())) continue;
            if (na.equals(norm(e.artist())) && ng.equals(norm(e.genre()))) out.add(new Candidate(e, MatchKind.ARTIST_GENRE));
        }
        out.sort(Comparator.comparingInt((Candidate c) -> c.entry().version()).reversed());
        return out.size() > 3 ? new ArrayList<>(out.subList(0, 3)) : out;
    }

    /** 曲名・ARTIST・GENRE の比較用の正規化（bemaniwiki 同期と同じ規則 + 記号の揺れを少し吸収）。 */
    static String norm(String s) {
        if (s == null) return "";
        return BemaniwikiSongListParser.normalizeTitle(s)
                .replace('’', '\'').replace('‘', '\'')
                .replace('“', '"').replace('”', '"');
    }

    /** JS 文字列リテラルを取り出し、エスケープ・HTML 実体参照・タグを戻す。 */
    private static String text(String field) {
        String f = field.trim();
        if (f.length() >= 2 && f.startsWith("\"") && f.endsWith("\"")) f = f.substring(1, f.length() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.length(); i++) {
            char c = f.charAt(i);
            if (c == '\\' && i + 1 < f.length()) {
                sb.append(f.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        String unescaped = Parser.unescapeEntities(sb.toString(), false);
        return TAG.matcher(unescaped).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    /** 配列リテラルの中身を、文字列の外にあるカンマで分ける。 */
    private static List<String> splitFields(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inStr = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inStr) {
                cur.append(c);
                if (c == '\\' && i + 1 < body.length()) {
                    cur.append(body.charAt(++i));
                } else if (c == '"') {
                    inStr = false;
                }
            } else if (c == '"') {
                inStr = true;
                cur.append(c);
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) out.add(cur.toString());
        return out;
    }
}
