package com.beatseeker.backend.service;

import com.beatseeker.backend.service.BemaniwikiSongListParser.Chart;
import com.beatseeker.backend.service.BemaniwikiSongListParser.NotesPage;
import com.beatseeker.backend.service.BemaniwikiSongListParser.Result;
import com.beatseeker.backend.service.BemaniwikiSongListParser.Song;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【テストの目的】 BEMANIWiki「beatmania IIDX 34 ZINRAI/旧曲リスト」と「旧曲総ノーツ数リスト」（2026-09-17 取得）を
 * 固定入力にして、レベル表とノーツ表が別ページに分かれていても結合できること、旧曲にだけ現れる表記
 * （譜面別の注記が並ぶ複数行の TITLE/GENRE/ARTIST、作品名の区切り行、金太字の解禁曲、BPM「※」）を
 * 意図どおり読むことを検証する。
 *
 * 固定 HTML は実ページ（各 2MB 前後）から楽曲リスト表の行を 31 曲分だけ残したもの。行の HTML は原文のまま。
 * 期待値は同じ HTML を別実装（Node の正規表現ベース）で読んだ結果と突き合わせて確定したもの。
 */
class BemaniwikiOldSongListParserTest {

    static final String LIST_FIXTURE = "/bemaniwiki/zinrai_old_songs_2026-09-17_excerpt.html";
    static final String NOTES_FIXTURE = "/bemaniwiki/zinrai_old_songs_notes_2026-09-17_excerpt.html";

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);

    private static Result result;

    static String read(String resource) throws IOException {
        try (InputStream in = BemaniwikiOldSongListParserTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("固定 HTML が test/resources にある: " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 2 ページの固定 HTML を本番と同じ手順（ノーツ → レベル）で読む。他のテストからも使う。 */
    static Result parseFixtures() throws IOException {
        BemaniwikiSongListParser parser = new BemaniwikiSongListParser();
        NotesPage notes = parser.parseNotesPage(read(NOTES_FIXTURE));
        return parser.parse(read(LIST_FIXTURE), notes);
    }

    /** {@link #parseFixtures()} と同じだが、指定した曲だけノーツ表に載っていない状態にして読む。 */
    static Result parseFixturesWithoutNotesFor(String... titles) throws IOException {
        BemaniwikiSongListParser parser = new BemaniwikiSongListParser();
        Map<String, Map<String, Integer>> notes = new LinkedHashMap<>(parser.parseNotesPage(read(NOTES_FIXTURE)).notesByTitle());
        for (String t : titles) assertThat(notes.remove(t)).as("ノーツ表にある曲: " + t).isNotNull();
        return parser.parse(read(LIST_FIXTURE), new NotesPage(notes, List.of()));
    }

    @BeforeAll
    static void parse() throws IOException {
        result = parseFixtures();
    }

    private static Song song(String title) {
        return result.songs().stream().filter(s -> s.title().equals(title)).findFirst()
                .orElseThrow(() -> new AssertionError("曲が見つからない: " + title));
    }

    @Test
    void 別ページのノーツ表をTITLEで結合して読む() {
        // バージョン一覧表・「譜面によりBPMが異なる楽曲」表は楽曲リストとして読まない
        assertThat(result.songs()).hasSize(31);

        Song s = song("Dr.LOVE");
        assertThat(s.genre()).isEqualTo("DANCE POP");
        assertThat(s.artist()).isEqualTo("baby weapon feat.Asuka.M");
        assertThat(s.bpm()).isEqualTo("123");
        assertThat(s.charts().keySet()).containsExactly("2", "3", "4");
        assertThat(s.charts().get("2").level()).isEqualTo(2);
        assertThat(s.charts().get("2").notes()).isEqualTo(204);
        assertThat(s.charts().get("3").level()).isEqualTo(3);
        assertThat(s.charts().get("3").notes()).isEqualTo(261);
        assertThat(s.charts().get("4").level()).isEqualTo(4);
        assertThat(s.charts().get("4").notes()).isEqualTo(290);
        assertThat(s.charts().values()).allMatch(Chart::isImportable);

        Song v = song("V");
        assertThat(v.charts().keySet()).containsExactly("1", "2", "3", "4");
        assertThat(v.charts().get("1").notes()).isEqualTo(173);
        assertThat(v.charts().get("4").level()).isEqualTo(12);
        assertThat(v.charts().get("4").notes()).isEqualTo(1519);
    }

    @Test
    void 作品名の区切り行は配信済みとして扱い記号を除く() {
        assertThat(result.songs()).allMatch(s -> s.isReleased(TODAY));
        assertThat(song("Dr.LOVE").section().label()).isEqualTo("beatmania IIDX");
        assertThat(song("焱影").section().label()).isEqualTo("beatmania IIDX 24 SINOBUZ");
        assertThat(song("焱影").section().releaseDate()).isNull();
        assertThat(song("焱影").section().scheduled()).isFalse();
    }

    @Test
    void 譜面別の注記が並ぶ複数行セルは1行目を曲の値として読む() {
        // TITLE: "Evans" ⏎ "[B][N][H] Evans -prototype-" ⏎ "[A]Evans"
        Song evans = song("Evans");
        assertThat(evans.artist()).isEqualTo("DJ YOSHITAKA");
        assertThat(evans.charts().get("4").level()).isEqualTo(12);
        assertThat(evans.charts().get("4").notes()).as("ノーツ表の TITLE は 'Evans' なので結合できる").isEqualTo(1440);

        // GENRE は 1 行目から注記（"[N]ANTHEM"）→ マーカーを外した 1 行目
        Song crew = song("crew");
        assertThat(crew.genre()).isEqualTo("ANTHEM");
        assertThat(crew.artist()).isEqualTo("beatnation Records");
        assertThat(crew.bpm()).isEqualTo("※");
        assertThat(crew.charts().keySet()).containsExactly("2", "3", "4");
        assertThat(crew.charts().get("4").notes()).isEqualTo(1480);

        Song denim = song("DENIM");
        assertThat(denim.artist()).isEqualTo("SLAKE");
        assertThat(denim.charts().get("2").notes()).isEqualTo(304);

        assertThat(song("BEAUTIFUL ANGEL").artist()).isEqualTo("DJ SWAN");
        // 角括弧を含んでも難易度マーカーでなければ触らない
        assertThat(song("MAX 360").artist()).isEqualTo("BEMANI Sound Team \"[𝑥]\"");
        assertThat(song("Friction[!]Function").charts().get("2").notes()).isEqualTo(560);
        assertThat(song("[ ]DENTITY").charts().get("2").notes()).isEqualTo(590);
    }

    @Test
    void ページ間で表記が違う曲名は正規化で結合し警告に残す() {
        // 旧曲リストは "never…"、総ノーツ数リストは "never..."
        Song s = song("never…");
        assertThat(s.charts().get("2").notes()).isEqualTo(414);
        assertThat(s.charts().get("4").notes()).isEqualTo(915);
        assertThat(result.warnings()).anyMatch(w -> w.contains("ノーツ表とは TITLE の表記が異なります") && w.contains("never"));
    }

    @Test
    void 上の行のrowspanでセルが少ない行も列をずらさず読む() {
        // 総ノーツ数リストでは "Timepiece phase II" の MOVIE / LAYER が rowspan=2 で、下の CN Ver. の行は td が 11 個しかない
        Song cn = song("Timepiece phase II (CN Ver.)");
        assertThat(cn.charts().get("2").notes()).isEqualTo(501);
        assertThat(cn.charts().get("3").notes()).isEqualTo(1008);
        assertThat(cn.charts().get("4").level()).isEqualTo(12);
        assertThat(cn.charts().get("4").notes()).isEqualTo(1546);
        assertThat(song("Timepiece phase II").charts().get("4").notes()).isEqualTo(1665);
        assertThat(result.warnings()).noneMatch(w -> w.contains("列数が想定と異なる"));
    }

    @Test
    void ノーツ表に無い曲はノーツ数未記載として読む() throws IOException {
        Result r = parseFixturesWithoutNotesFor("Dr.LOVE");

        Song s = r.songs().stream().filter(x -> x.title().equals("Dr.LOVE")).findFirst().orElseThrow();
        assertThat(s.charts().get("4").level()).isEqualTo(4);
        assertThat(s.charts().get("4").notes()).isNull();
        assertThat(s.charts().get("4").holdReason()).isEqualTo("ノーツ数未記載");
        assertThat(r.warnings()).contains("ノーツ表に見当たらない曲: Dr.LOVE");
        assertThat(r.contentHash()).isNotEqualTo(result.contentHash());
    }

    @Test
    void 金太字や色付きのレベルは未解禁扱いにしない() {
        // 無条件解禁されていない譜面（金太字）。灰色ではないので取り込む
        Chart legg = song("Catch Me").charts().get("10");
        assertThat(legg.level()).isEqualTo(11);
        assertThat(legg.notes()).isEqualTo(1106);
        assertThat(legg.hidden()).isFalse();
        assertThat(legg.isImportable()).isTrue();
        // 今作でレベルが変わった譜面（赤字）・追加された譜面（緑字）
        assertThat(song("Time to Empress").charts().get("10").level()).isEqualTo(12);
        assertThat(song("Medicine of love").charts().get("10").level()).isEqualTo(11);
        assertThat(song("Medicine of love").charts().get("10").notes()).isEqualTo(1273);
        assertThat(result.songs().stream().flatMap(x -> x.charts().values().stream())).noneMatch(Chart::hidden);
    }

    @Test
    void 特殊文字の曲名とBPMの印をそのまま読む() {
        Song aether = song("ÆTHER");
        assertThat(aether.bpm()).isEqualTo("※");
        assertThat(aether.artist()).isEqualTo("TAG underground");
        assertThat(aether.charts().get("10").notes()).isEqualTo(1640);
        assertThat(song("焱影").charts().get("4").notes()).isEqualTo(1437);
        assertThat(song("LOVE♡SHINE").charts().get("1").notes()).isEqualTo(270);
    }

    @Test
    void 総ノーツ数表が無いページは例外にする() throws IOException {
        // 旧曲リスト（レベル表のページ）には総ノーツ数表が無い
        assertThatThrownBy(() -> new BemaniwikiSongListParser().parseNotesPage(read(LIST_FIXTURE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("総ノーツ数表");
    }
}
