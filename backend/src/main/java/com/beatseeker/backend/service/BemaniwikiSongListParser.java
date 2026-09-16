package com.beatseeker.backend.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【クラスの役割】 BEMANIWiki 2nd の「beatmania IIDX 34 ZINRAI/新曲リスト」ページ（PukiWiki の HTML）を解析し、
 * 曲ごとの GENRE / TITLE / ARTIST / BPM と、SP 5 難易度（B/N/H/A/L）のレベル・ノーツ数を取り出す純粋なパーサ。
 *
 * 現実世界の概念: 新曲リストページには 2 つの表がある。
 *  - 「楽曲リスト」表: SP(B/N/H/A/L) と DP(N/H/A/L) のレベル、BPM、GENRE、TITLE、ARTIST
 *  - 「総ノーツ数, 演奏時間, ムービー, レイヤー」表: TITLE と NOTE(SP)(B/N/H/A/L)、NOTE(DP)、TIME、MOVIE、LAYER
 * 2 つの表は曲の並び順が一致しない（編集者が片方だけ並べ替えることがある）ので、TITLE で突き合わせる。
 *
 * 表記の解釈（ページ冒頭「表記について」と実データから確定したもの）:
 *  - セル "-" = その難易度の譜面が無い。空欄 = まだ判明していない（後日埋まる）。
 *  - "[CN]" "[BSS]" "[HCN]" "[CN?]" などの角括弧はギミックの注記で、レベル数字の前に付く。取り除いてから数字を読む。
 *  - 数字の後ろの "?" は未確定（例: "12?"）。確定するまで取り込まない。
 *  - 灰色文字（style の color が灰色）のレベルは未解禁の隠し譜面（ノーツ数だけ解析で判明している）。解禁されて
 *    通常表記に戻るまで取り込まない。
 *  - 表の中の「デフォルト曲」「2026/09/16配信(稼働初期)」「後日登場予定」のような 1 セルだけの行は区切り
 *    （セクション）。日付があればその日以降に配信済み、日付が無く「予定」「後日」を含めば未配信として扱う。
 *
 * 依存: Jsoup（HTML 解析）のみ。Spring や DB には依存しない（ユニットテストで固定 HTML を食わせて検証する）。
 *
 * 主要ロジックの概観:
 *  - 見出し 2 行（"SP" colspan=5 の下に B/N/H/A/L …）を平坦化して「列名 → 列番号」表を作り、列の並びに依存しない
 *  - 楽曲リスト表の行を上から読み、セクション行で現在のセクションを更新、通常行を {@link Song} にする
 *  - 総ノーツ数表を TITLE → 難易度別ノーツ数に読み、TITLE（完全一致 → 正規化一致）で結合する
 *  - 解析結果全体の SHA-256 を {@link Result#contentHash()} に載せ、前回実行からページが変わったかの判定に使う
 */
public final class BemaniwikiSongListParser {

    /** 難易度コード（{@code SongDefinition.difficulty} と同じ）。 */
    public static final String BEGINNER = "1";
    public static final String NORMAL = "2";
    public static final String HYPER = "3";
    public static final String ANOTHER = "4";
    public static final String LEGGENDARIA = "10";

    /** 見出しの略号（SP 列の並び）→ 難易度コード。 */
    private static final Map<String, String> SP_COLUMNS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("B", BEGINNER);
        m.put("N", NORMAL);
        m.put("H", HYPER);
        m.put("A", ANOTHER);
        m.put("L", LEGGENDARIA);
        SP_COLUMNS = Collections.unmodifiableMap(m);
    }

    /** "[CN]" "[BSS]" "[CN?]" のようなギミック注記。 */
    private static final Pattern MARKER = Pattern.compile("\\[[^\\]]*\\]");
    /** セクション行の配信日 "2026/09/16"。 */
    private static final Pattern DATE = Pattern.compile("(\\d{4})/(\\d{1,2})/(\\d{1,2})");
    /** style 属性の文字色（background-color は除外）。 */
    private static final Pattern TEXT_COLOR = Pattern.compile("(?<![-\\w])color\\s*:\\s*([^;\"]+)", Pattern.CASE_INSENSITIVE);
    /** 全角・半角の "?"（未確定マーク）。 */
    private static final Pattern UNCERTAIN_SUFFIX = Pattern.compile("[?？]\\s*$");

    /**
     * 表の区切り行。配信日が分かればその日以降に配信済み、日付が無い「後日登場予定」などは未配信。
     *
     * @param label       行の文言そのまま（例: "2026/09/16配信(稼働初期)"、"後日登場予定"）
     * @param releaseDate 文言から読めた配信日。無ければ null
     * @param scheduled   日付が無く、かつ「予定」「後日」「未定」を含む（= 未配信）
     */
    public record Section(String label, LocalDate releaseDate, boolean scheduled) {
        /** 【メソッドの役割】 基準日時点で配信済みか。日付があれば日付で、無ければ「予定」表記の有無で決める。 */
        public boolean isReleased(LocalDate today) {
            if (releaseDate != null) return !releaseDate.isAfter(today);
            return !scheduled;
        }
    }

    /**
     * 1 譜面分の読み取り結果。レベル表に "-" だった難易度は {@link Song#charts()} に含めない。
     *
     * @param difficulty 難易度コード（1/2/3/4/10）
     * @param level      レベル（★）。未記載・未確定なら null
     * @param notes      ノーツ数。未記載なら null
     * @param hidden     灰色表記（未解禁の隠し譜面）
     * @param uncertain  レベルに "?" が付いている（未確定）
     */
    public record Chart(String difficulty, Integer level, Integer notes, boolean hidden, boolean uncertain) {
        /** 【メソッドの役割】 beat-seeker に取り込める状態か（レベルとノーツ数が確定していて、未解禁でない）。 */
        public boolean isImportable() {
            return holdReason() == null;
        }

        /** 【メソッドの役割】 取り込めない理由（表示用）。取り込める場合は null。 */
        public String holdReason() {
            if (hidden) return "未解禁（灰色表記）";
            if (uncertain) return "レベル未確定（?付き）";
            if (level == null || level <= 0) return "レベル未記載";
            if (notes == null || notes <= 0) return "ノーツ数未記載";
            return null;
        }
    }

    /**
     * 1 曲分の読み取り結果。
     *
     * @param title   TITLE 列（注記リンクを除いた表示文字列そのまま）
     * @param genre   GENRE 列。空欄なら null
     * @param artist  ARTIST 列。空欄なら null
     * @param bpm     BPM 列（"172" や "132-220"）。空欄なら null
     * @param section この曲が属する区切り。表の先頭に区切りが無ければ null（配信済み扱い）
     * @param charts  難易度コード → 譜面。譜面が存在しない難易度は含まない
     */
    public record Song(String title, String genre, String artist, String bpm, Section section,
                       Map<String, Chart> charts) {
        /** 【メソッドの役割】 基準日時点で配信済みの曲か。 */
        public boolean isReleased(LocalDate today) {
            return section == null || section.isReleased(today);
        }
    }

    /**
     * ページ全体の解析結果。
     *
     * @param songs       表の並び順どおりの曲
     * @param contentHash 解析結果（曲・譜面の値すべて）の SHA-256。前回と同じならページは実質変わっていない
     * @param warnings    人が確認したほうがよい点（ノーツ表に無い曲、列数が合わない行など）
     */
    public record Result(List<Song> songs, String contentHash, List<String> warnings) {}

    /** 【メソッドの役割】 難易度コードを表示名にする（ログ・通知用）。 */
    public static String difficultyName(String code) {
        return switch (code) {
            case BEGINNER -> "BEGINNER";
            case NORMAL -> "NORMAL";
            case HYPER -> "HYPER";
            case ANOTHER -> "ANOTHER";
            case LEGGENDARIA -> "LEGGENDARIA";
            default -> code;
        };
    }

    /**
     * 【メソッドの役割】 曲名の表記ゆれを吸収した比較キーを作る。
     *
     * NFKC 正規化（全角英数・全角スペース → 半角）、小文字化、空白除去、波ダッシュ類の統一。
     * 既存の曲マスタと wiki で「同じ曲なのに文字が少し違う」ときに二重登録しないための突き合わせ専用で、
     * この値を保存には使わない（保存する曲名は常に元の表記）。
     */
    public static String normalizeTitle(String title) {
        if (title == null) return "";
        String s = Normalizer.normalize(title, Normalizer.Form.NFKC);
        s = s.toLowerCase(Locale.ROOT);
        s = s.replace('〜', '~')   // 〜 WAVE DASH
             .replace('～', '~')   // ～ FULLWIDTH TILDE（NFKC で ~ になるが念のため）
             .replace('∼', '~');  // ∼ TILDE OPERATOR
        return s.replaceAll("\\s+", "");
    }

    /**
     * 【メソッドの役割】 ページの HTML を解析して曲一覧を返す。
     *
     * 処理の流れ:
     *  - 手順1: ページ本文（div#body）内の全 table について見出しを平坦化し、「楽曲リスト表」と「総ノーツ数表」を特定
     *  - 手順2: 総ノーツ数表を TITLE → {難易度コード → ノーツ数} に読む
     *  - 手順3: 楽曲リスト表を上から読み、セクション行と通常行を処理。通常行は TITLE でノーツを結合
     *  - 手順4: 結果全体のハッシュを計算
     *
     * @param html ページの HTML（UTF-8 で読み込み済みの文字列）
     * @return 解析結果
     * @throws IllegalStateException 楽曲リスト表が見つからない（ページ構成が変わった）場合
     */
    public Result parse(String html) {
        Document doc = Jsoup.parse(html);
        Elements tables = doc.select("div#body table");
        if (tables.isEmpty()) tables = doc.select("table");

        Element levelTable = null;
        Map<String, Integer> levelIdx = null;
        Element notesTable = null;
        Map<String, Integer> notesIdx = null;
        for (Element t : tables) {
            Map<String, Integer> idx = headerIndex(t);
            if (levelTable == null && idx.containsKey("TITLE") && idx.containsKey("GENRE") && idx.containsKey("SP:A")) {
                levelTable = t;
                levelIdx = idx;
            } else if (notesTable == null && idx.containsKey("TITLE") && idx.containsKey("NOTE(SP):A")) {
                notesTable = t;
                notesIdx = idx;
            }
        }
        if (levelTable == null) {
            throw new IllegalStateException("楽曲リスト表（SP/DP/GENRE/TITLE/ARTIST）が見つかりません。ページ構成が変わった可能性があります");
        }

        List<String> warnings = new ArrayList<>();
        Map<String, Map<String, Integer>> notesByTitle;
        if (notesTable == null) {
            warnings.add("総ノーツ数の表が見つかりません。ノーツ数は未記載として扱います");
            notesByTitle = Map.of();
        } else {
            notesByTitle = parseNotesTable(notesTable, notesIdx, warnings);
        }
        Map<String, String> notesTitleByNorm = new LinkedHashMap<>();
        for (String t : notesByTitle.keySet()) notesTitleByNorm.putIfAbsent(normalizeTitle(t), t);

        List<Song> songs = new ArrayList<>();
        Map<String, Integer> seenTitles = new LinkedHashMap<>();
        Section current = null;
        int columnCount = levelIdx.size();
        List<Element> rows = levelTable.select("tr");
        for (int r = headerRowCount(rows); r < rows.size(); r++) {
            List<Element> cells = cells(rows.get(r));
            if (cells.isEmpty()) continue;
            if (isSectionRow(cells, columnCount)) {
                current = parseSection(cellText(cells.get(0)));
                continue;
            }
            if (cells.size() < columnCount) {
                warnings.add("列数が想定と異なる行をスキップ: " + cellText(cells.get(0)));
                continue;
            }
            String title = cellText(cells.get(levelIdx.get("TITLE")));
            if (title.isEmpty()) {
                warnings.add("TITLE が空の行をスキップ（" + (r + 1) + " 行目）");
                continue;
            }
            if (seenTitles.merge(title, 1, Integer::sum) > 1) {
                warnings.add("同じ TITLE が複数行あります: " + title);
            }
            String genre = blankToNull(cellText(cells.get(levelIdx.get("GENRE"))));
            String artist = levelIdx.containsKey("ARTIST") ? blankToNull(cellText(cells.get(levelIdx.get("ARTIST")))) : null;
            String bpm = levelIdx.containsKey("BPM") ? blankToNull(cellText(cells.get(levelIdx.get("BPM")))) : null;

            Map<String, Integer> notes = notesByTitle.get(title);
            if (notes == null) {
                String alt = notesTitleByNorm.get(normalizeTitle(title));
                if (alt != null) {
                    notes = notesByTitle.get(alt);
                    warnings.add("ノーツ表とは TITLE の表記が異なります: 楽曲リスト『" + title + "』 / ノーツ表『" + alt + "』");
                } else if (notesTable != null) {
                    warnings.add("ノーツ表に見当たらない曲: " + title);
                }
            }

            Map<String, Chart> charts = new LinkedHashMap<>();
            for (Map.Entry<String, String> col : SP_COLUMNS.entrySet()) {
                Integer ci = levelIdx.get("SP:" + col.getKey());
                if (ci == null) continue;
                String code = col.getValue();
                Chart ch = parseLevelCell(cells.get(ci), code);
                if (ch == null) continue; // "-" = 譜面なし
                Integer n = notes != null ? notes.get(code) : null;
                charts.put(code, new Chart(code, ch.level(), n, ch.hidden(), ch.uncertain()));
            }
            songs.add(new Song(title, genre, artist, bpm, current, Collections.unmodifiableMap(charts)));
        }

        return new Result(Collections.unmodifiableList(songs), contentHash(songs), Collections.unmodifiableList(warnings));
    }

    // ── 表の見出し ─────────────────────────────────────

    /**
     * 【メソッドの役割】 見出し 2 行を「列名 → 列番号」に平坦化する。
     *
     * 1 行目の colspan 付きセル（"SP" ×5, "DP" ×4, "NOTE(SP)" ×5 …）は 2 行目の同数のセル（B/N/H/A/L）と
     * 組み合わせて "SP:B" のような名前にし、rowspan 付きセル（BPM/GENRE/TITLE/ARTIST）はそのままの名前にする。
     * 見出しが 1 行しか無い表は 1 行目の名前だけを使う。
     */
    private Map<String, Integer> headerIndex(Element table) {
        List<Element> rows = table.select("tr");
        if (rows.isEmpty()) return Map.of();
        List<Element> row0 = cells(rows.get(0));
        List<Element> row1 = rows.size() > 1 ? cells(rows.get(1)) : List.of();
        Map<String, Integer> idx = new LinkedHashMap<>();
        int col = 0;
        int subPos = 0;
        for (Element c : row0) {
            String name = headerText(c);
            int colspan = intAttr(c, "colspan", 1);
            if (colspan > 1) {
                for (int i = 0; i < colspan; i++) {
                    String sub = subPos < row1.size() ? headerText(row1.get(subPos++)) : String.valueOf(i);
                    idx.put(name + ":" + sub, col++);
                }
            } else {
                idx.put(name, col++);
            }
        }
        return idx;
    }

    /** 見出しが colspan を使っていれば 2 行、そうでなければ 1 行を見出しとして読み飛ばす。 */
    private int headerRowCount(List<Element> rows) {
        if (rows.isEmpty()) return 0;
        for (Element c : cells(rows.get(0))) {
            if (intAttr(c, "colspan", 1) > 1) return 2;
        }
        return 1;
    }

    /** 見出し文字列（大文字・空白除去。"NOTE(SP)" のように括弧は残す）。 */
    private String headerText(Element cell) {
        return cell.text().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    // ── 総ノーツ数表 ─────────────────────────────────────

    /**
     * 【メソッドの役割】 総ノーツ数表を TITLE → {難易度コード → ノーツ数} に読む。
     * "-"（譜面なし）や空欄（未記載）はどちらも値なし（Map に入れない）。
     */
    private Map<String, Map<String, Integer>> parseNotesTable(Element table, Map<String, Integer> idx, List<String> warnings) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        int columnCount = idx.size();
        List<Element> rows = table.select("tr");
        for (int r = headerRowCount(rows); r < rows.size(); r++) {
            List<Element> cells = cells(rows.get(r));
            if (cells.isEmpty() || isSectionRow(cells, columnCount)) continue;
            if (cells.size() < columnCount) {
                warnings.add("ノーツ表: 列数が想定と異なる行をスキップ: " + cellText(cells.get(0)));
                continue;
            }
            String title = cellText(cells.get(idx.get("TITLE")));
            if (title.isEmpty()) continue;
            Map<String, Integer> perDiff = new LinkedHashMap<>();
            for (Map.Entry<String, String> col : SP_COLUMNS.entrySet()) {
                Integer ci = idx.get("NOTE(SP):" + col.getKey());
                if (ci == null) continue;
                Integer n = parseNumberCell(cells.get(ci));
                if (n != null) perDiff.put(col.getValue(), n);
            }
            if (result.containsKey(title)) {
                warnings.add("ノーツ表に同じ TITLE が複数行あります: " + title);
            }
            result.put(title, perDiff);
        }
        return result;
    }

    // ── セル ─────────────────────────────────────────────

    /**
     * 【メソッドの役割】 レベルセルを読む。
     *
     * @return "-" なら null（譜面なし）。それ以外はレベル（未記載・未確定なら level=null）と灰色・未確定フラグ
     */
    private Chart parseLevelCell(Element cell, String code) {
        boolean hidden = isGrayText(cell);
        String t = stripMarkers(cellText(cell));
        if (isNoChart(t)) return null;
        if (t.isEmpty()) return new Chart(code, null, null, hidden, false);
        boolean uncertain = UNCERTAIN_SUFFIX.matcher(t).find();
        String digits = t.replaceAll("[^0-9]", "");
        Integer level = digits.isEmpty() ? null : Integer.valueOf(digits);
        return new Chart(code, level, null, hidden, uncertain);
    }

    /** ノーツ数セル。"-"・空欄・"?" 付きは null。 */
    private Integer parseNumberCell(Element cell) {
        String t = stripMarkers(cellText(cell));
        if (t.isEmpty() || isNoChart(t)) return null;
        if (UNCERTAIN_SUFFIX.matcher(t).find()) return null;
        String digits = t.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : Integer.valueOf(digits);
    }

    private static boolean isNoChart(String t) {
        return t.equals("-") || t.equals("－") || t.equals("—") || t.equals("―");
    }

    private static String stripMarkers(String s) {
        return MARKER.matcher(s).replaceAll("").trim();
    }

    /**
     * 【メソッドの役割】 セルが灰色文字（未解禁の隠し譜面）か。
     * td 自身の style、または数字を包む span の style の文字色を見る。
     */
    private boolean isGrayText(Element cell) {
        if (isGrayColor(cell.attr("style"))) return true;
        for (Element span : cell.select("span[style]")) {
            String inner = stripMarkers(span.text());
            if (!inner.isEmpty() && inner.chars().anyMatch(Character::isDigit) && isGrayColor(span.attr("style"))) {
                return true;
            }
        }
        return false;
    }

    /** style 文字列の文字色が灰色系か（"#aaa" "#999999" "gray" など）。 */
    static boolean isGrayColor(String style) {
        if (style == null || style.isEmpty()) return false;
        Matcher m = TEXT_COLOR.matcher(style);
        if (!m.find()) return false;
        String value = m.group(1).trim().toLowerCase(Locale.ROOT);
        if (value.matches("(dark|dim|light)?gr[ae]y|silver|gainsboro")) return true;
        if (value.startsWith("#")) {
            String hex = value.substring(1);
            if (hex.length() == 3) {
                hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
            }
            if (!hex.matches("[0-9a-f]{6}")) return false;
            int rr = Integer.parseInt(hex.substring(0, 2), 16);
            int gg = Integer.parseInt(hex.substring(2, 4), 16);
            int bb = Integer.parseInt(hex.substring(4, 6), 16);
            int max = Math.max(rr, Math.max(gg, bb));
            int min = Math.min(rr, Math.min(gg, bb));
            int avg = (rr + gg + bb) / 3;
            // ほぼ無彩色で、黒でも白でもない明るさ
            return (max - min) <= 0x20 && avg >= 0x50 && avg <= 0xd8;
        }
        return false;
    }

    /** セルの表示文字列。脚注リンク（PukiWiki の note_super）は除き、改行や連続空白は 1 つの空白にする。 */
    private String cellText(Element cell) {
        Element copy = cell.clone();
        copy.select("a.note_super, .note_super").remove();
        return copy.text().replaceAll("\\s+", " ").trim();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private List<Element> cells(Element tr) {
        List<Element> out = new ArrayList<>();
        for (Element c : tr.children()) {
            String tag = c.tagName();
            if ("td".equals(tag) || "th".equals(tag)) out.add(c);
        }
        return out;
    }

    private static int intAttr(Element e, String name, int dflt) {
        String v = e.attr(name);
        if (v == null || v.isBlank()) return dflt;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return dflt;
        }
    }

    // ── セクション行 ─────────────────────────────────────

    /** 1 セルが複数列にまたがる（colspan）行を区切り行とみなす。 */
    private boolean isSectionRow(List<Element> cells, int columnCount) {
        if (cells.size() >= columnCount) return false;
        return intAttr(cells.get(0), "colspan", 1) > 1;
    }

    /** 区切り行の文言から配信日・予定フラグを読む。 */
    static Section parseSection(String label) {
        LocalDate date = null;
        Matcher m = DATE.matcher(label);
        if (m.find()) {
            try {
                date = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            } catch (Exception ignore) {
                date = null;
            }
        }
        boolean scheduled = date == null && (label.contains("予定") || label.contains("後日") || label.contains("未定"));
        return new Section(label, date, scheduled);
    }

    // ── ハッシュ ─────────────────────────────────────────

    /** 解析結果全体を決まった書式の文字列にして SHA-256 を取る（曲の並び順には依存させない）。 */
    private String contentHash(List<Song> songs) {
        List<String> lines = new ArrayList<>();
        for (Song s : songs) {
            StringBuilder sb = new StringBuilder();
            sb.append(s.title()).append('\t').append(s.genre()).append('\t').append(s.artist()).append('\t').append(s.bpm());
            if (s.section() != null) {
                sb.append('\t').append(s.section().label()).append('\t').append(s.section().releaseDate()).append('\t').append(s.section().scheduled());
            }
            for (Map.Entry<String, Chart> e : s.charts().entrySet()) {
                Chart c = e.getValue();
                sb.append('\t').append(e.getKey()).append('=').append(c.level()).append('/').append(c.notes())
                  .append(c.hidden() ? "H" : "").append(c.uncertain() ? "?" : "");
            }
            lines.add(sb.toString());
        }
        Collections.sort(lines);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
