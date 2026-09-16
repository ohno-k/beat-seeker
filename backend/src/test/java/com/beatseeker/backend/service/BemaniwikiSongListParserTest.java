package com.beatseeker.backend.service;

import com.beatseeker.backend.service.BemaniwikiSongListParser.Chart;
import com.beatseeker.backend.service.BemaniwikiSongListParser.Result;
import com.beatseeker.backend.service.BemaniwikiSongListParser.Section;
import com.beatseeker.backend.service.BemaniwikiSongListParser.Song;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【テストの目的】 BEMANIWiki「beatmania IIDX 34 ZINRAI/新曲リスト」の実 HTML（2026-09-16 取得）を固定入力にして、
 * レベル表とノーツ表の読み取り・結合、表記（"-" / 空欄 / [CN] / 灰色 / "?"）の解釈、セクション（配信日・後日登場予定）の
 * 判定が意図どおりであることを検証する。
 *
 * 期待値は同じ HTML を別実装（Node の正規表現ベース）で読んだ結果と突き合わせて確定したもの。
 */
class BemaniwikiSongListParserTest {

    private static final LocalDate LAUNCH_DAY = LocalDate.of(2026, 9, 16);

    private static Result result;

    @BeforeAll
    static void parseFixture() throws IOException {
        try (InputStream in = BemaniwikiSongListParserTest.class.getResourceAsStream("/bemaniwiki/zinrai_new_songs_2026-09-16.html")) {
            assertThat(in).as("固定 HTML が test/resources にある").isNotNull();
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            result = new BemaniwikiSongListParser().parse(html);
        }
    }

    private static Song song(String title) {
        return result.songs().stream().filter(s -> s.title().equals(title)).findFirst()
                .orElseThrow(() -> new AssertionError("曲が見つからない: " + title));
    }

    @Test
    void 楽曲リスト表の全曲を読み取り配信済みと後日登場予定を区別する() {
        assertThat(result.songs()).hasSize(44);
        List<Song> released = result.songs().stream().filter(s -> s.isReleased(LAUNCH_DAY)).toList();
        List<Song> scheduled = result.songs().stream().filter(s -> !s.isReleased(LAUNCH_DAY)).toList();
        assertThat(released).hasSize(37);
        assertThat(scheduled).hasSize(7);
        assertThat(scheduled).extracting(Song::title).contains(
                "Fly Above -20th Annivisary Mix-", "I'm so happy (20th Anniv.Mix)",
                "moffing 猫叉Master meets β2 黒パカミックス", "恋する☆宇宙戦争っ！！(恋する★ハードコアRemix)");
        // 稼働初期セクションの日付が読めている
        Section first = song("BENiZAKURA").section();
        assertThat(first.releaseDate()).isEqualTo(LAUNCH_DAY);
        assertThat(first.label()).contains("2026/09/16");
        // 稼働前日ならまだ配信前
        assertThat(song("BENiZAKURA").isReleased(LAUNCH_DAY.minusDays(1))).isFalse();
        // 後日登場予定は日付が無く scheduled
        Section later = song("I'm so happy (20th Anniv.Mix)").section();
        assertThat(later.releaseDate()).isNull();
        assertThat(later.scheduled()).isTrue();
    }

    @Test
    void 基本情報とSPレベルとノーツ数をTITLEで結合して読む() {
        Song s = song("BENiZAKURA");
        assertThat(s.genre()).isEqualTo("ENERGETIC JAPANESQUE");
        assertThat(s.artist()).isEqualTo("BlackY");
        assertThat(s.bpm()).isEqualTo("172");
        // B と L は "-"（譜面なし）なので含まれない
        assertThat(s.charts().keySet()).containsExactly("2", "3", "4");
        assertThat(s.charts().get("2").level()).isEqualTo(5);
        assertThat(s.charts().get("2").notes()).isEqualTo(525);
        assertThat(s.charts().get("3").level()).isEqualTo(8);
        assertThat(s.charts().get("3").notes()).as("HYPER のノーツ数は未記載").isNull();
        assertThat(s.charts().get("4").level()).isEqualTo(11);
        assertThat(s.charts().get("4").notes()).isEqualTo(1405);
        assertThat(s.charts().get("2").isImportable()).isTrue();
        assertThat(s.charts().get("3").isImportable()).isFalse();
        assertThat(s.charts().get("3").holdReason()).isEqualTo("ノーツ数未記載");
        assertThat(s.charts().get("4").isImportable()).isTrue();
    }

    @Test
    void BEGINNERを含む5難易度と括弧付き注記を正しく読む() {
        Song s = song("Ex-Otogibanashi");
        assertThat(s.genre()).isEqualTo("アニメ");
        assertThat(s.artist()).startsWith("ryo (supercell)");
        assertThat(s.bpm()).isEqualTo("190");
        Map<String, Chart> c = s.charts();
        assertThat(c.keySet()).containsExactly("1", "2", "3", "4");
        assertThat(c.get("1").level()).isEqualTo(1);
        assertThat(c.get("1").notes()).isEqualTo(87);
        assertThat(c.get("2").level()).as("[CN] を除いて 3").isEqualTo(3);
        assertThat(c.get("2").notes()).isEqualTo(234);
        assertThat(c.get("3").level()).isEqualTo(6);
        assertThat(c.get("3").notes()).isEqualTo(487);
        assertThat(c.get("4").level()).as("[CN][BSS] を除いて 10").isEqualTo(10);
        assertThat(c.get("4").notes()).isEqualTo(800);
        assertThat(c.values()).allMatch(Chart::isImportable);
    }

    @Test
    void 灰色表記の未解禁譜面は保留にする() {
        Song dead = song("DEADRABBIT!!");
        Chart another = dead.charts().get("4");
        assertThat(another.level()).isEqualTo(12);
        assertThat(another.notes()).as("解析で判明済みのノーツ数は読める").isEqualTo(1602);
        assertThat(another.hidden()).isTrue();
        assertThat(another.isImportable()).isFalse();
        assertThat(another.holdReason()).isEqualTo("未解禁（灰色表記）");
        // 同じ曲の通常表記の譜面は取り込める
        assertThat(dead.charts().get("2").level()).isEqualTo(6);
        assertThat(dead.charts().get("2").notes()).isEqualTo(535);
        assertThat(dead.charts().get("2").isImportable()).isTrue();
        assertThat(dead.charts().get("3").level()).isEqualTo(10);
        assertThat(dead.charts().get("3").notes()).isEqualTo(1042);

        Song citro = song("Citroforte");
        assertThat(citro.charts().get("4").hidden()).isTrue();
        assertThat(citro.charts().get("3").level()).as("空欄のレベルは未記載").isNull();
        assertThat(citro.charts().get("3").holdReason()).isEqualTo("レベル未記載");
        assertThat(citro.charts().values()).noneMatch(Chart::isImportable);
    }

    @Test
    void ノーツ表と楽曲リストの並び順が違っても曲名で結合する() {
        // 楽曲リストでは 9-1 → ΛИ0MΛLY の順、ノーツ表では逆順に載っている
        Song a = song("ΛИ0MΛLY");
        Song b = song("9-1");
        assertThat(a.charts().get("4").notes()).isNotNull();
        assertThat(b.charts().get("4").notes()).isNotNull();
        assertThat(a.charts().get("4").notes()).isNotEqualTo(b.charts().get("4").notes());
        assertThat(result.warnings()).noneMatch(w -> w.contains("ノーツ表に見当たらない曲"));
    }

    @Test
    void ノーツ数が未記載の曲は取り込み対象にならない() {
        Song s = song("NOMAD (feat.らっぷぴと)");
        assertThat(s.genre()).isEqualTo("HIPHOP");
        assertThat(s.artist()).isEqualTo("魂音泉");
        assertThat(s.charts().get("2").level()).isEqualTo(4);
        assertThat(s.charts().get("4").level()).isEqualTo(10);
        assertThat(s.charts().values()).noneMatch(Chart::isImportable);
    }

    @Test
    void 配信済みで取り込める譜面の総数() {
        long importable = result.songs().stream()
                .filter(s -> s.isReleased(LAUNCH_DAY))
                .flatMap(s -> s.charts().values().stream())
                .filter(Chart::isImportable)
                .count();
        long held = result.songs().stream()
                .filter(s -> s.isReleased(LAUNCH_DAY))
                .flatMap(s -> s.charts().values().stream())
                .filter(c -> !c.isImportable())
                .count();
        // 配信済み 37 曲・119 譜面のうち、レベルとノーツ数が揃い未解禁でないもの。
        // 未解禁の ANOTHER ★12 は 10 譜面（td 自体が灰色 7 + 数字だけ span で灰色 3）。
        assertThat(importable).isEqualTo(57);
        assertThat(held).isEqualTo(62);
    }

    @Test
    void 数字だけをspanで灰色にした未解禁表記も読む() {
        for (String title : List.of("OTOROSHI", "ΛИ0MΛLY", "斎戒")) {
            Chart another = song(title).charts().get("4");
            assertThat(another.level()).as(title).isEqualTo(12);
            assertThat(another.hidden()).as(title + " は span で灰色").isTrue();
            assertThat(another.isImportable()).isFalse();
        }
        // 注記の span（橙色）は灰色扱いしない
        assertThat(song("Ex-Otogibanashi").charts().get("4").hidden()).isFalse();
        // ARTIST 内の改行（br）は空白として読む
        assertThat(song("Ex-Otogibanashi").artist()).isEqualTo("ryo (supercell) / かぐや(cv.夏吉ゆうこ) & 月見ヤチヨ(cv.早見沙織) from 超かぐや姫！");
    }

    @Test
    void ハッシュは同じ入力で安定し内容が変わると変わる() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/bemaniwiki/zinrai_new_songs_2026-09-16.html")) {
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Result again = new BemaniwikiSongListParser().parse(html);
            assertThat(again.contentHash()).isEqualTo(result.contentHash());
            Result edited = new BemaniwikiSongListParser().parse(html.replace(">BENiZAKURA<", ">BENiZAKURA2<"));
            assertThat(edited.contentHash()).isNotEqualTo(result.contentHash());
        }
    }

    @Test
    void セクション文言の解釈() {
        Section dated = BemaniwikiSongListParser.parseSection("2026/10/01配信予定");
        assertThat(dated.releaseDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(dated.isReleased(LocalDate.of(2026, 9, 30))).isFalse();
        assertThat(dated.isReleased(LocalDate.of(2026, 10, 1))).isTrue();

        assertThat(BemaniwikiSongListParser.parseSection("後日登場予定").isReleased(LAUNCH_DAY)).isFalse();
        assertThat(BemaniwikiSongListParser.parseSection("デフォルト曲").isReleased(LAUNCH_DAY)).isTrue();
    }

    @Test
    void 曲名の正規化は表記ゆれだけを吸収する() {
        assertThat(BemaniwikiSongListParser.normalizeTitle("デラむぅのでらっくす☆かるた ～NOTES・CHORD・PEAK編～"))
                .isEqualTo(BemaniwikiSongListParser.normalizeTitle("デラむぅのでらっくす☆かるた 〜notes・chord・peak編〜"));
        assertThat(BemaniwikiSongListParser.normalizeTitle("ＡＢＣ　ｄｅｆ")).isEqualTo("abcdef");
        assertThat(BemaniwikiSongListParser.normalizeTitle("VOID")).isNotEqualTo(BemaniwikiSongListParser.normalizeTitle("VØID"));
    }

    @Test
    void 灰色判定() {
        assertThat(BemaniwikiSongListParser.isGrayColor("color:#aaa; text-align:center; background-color:#fff0ec;")).isTrue();
        assertThat(BemaniwikiSongListParser.isGrayColor("text-align:center; background-color:#fff0ec;")).as("背景色は文字色ではない").isFalse();
        assertThat(BemaniwikiSongListParser.isGrayColor("color:#ff5900")).isFalse();
        assertThat(BemaniwikiSongListParser.isGrayColor("color:gray")).isTrue();
        assertThat(BemaniwikiSongListParser.isGrayColor("color:#000")).isFalse();
    }

    @Test
    void 楽曲リスト表が無いHTMLは例外にする() {
        assertThatThrownBy(() -> new BemaniwikiSongListParser().parse("<html><body><div id=\"body\"><p>nothing</p></div></body></html>"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("楽曲リスト表");
    }
}
