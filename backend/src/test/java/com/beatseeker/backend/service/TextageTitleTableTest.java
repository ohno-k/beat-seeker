package com.beatseeker.backend.service;

import com.beatseeker.backend.service.TextageTitleTable.Candidate;
import com.beatseeker.backend.service.TextageTitleTable.Entry;
import com.beatseeker.backend.service.TextageTitleTable.MatchKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 【テストの目的】 textage の titletbl.js の解析と、楽曲マスタの曲からページ候補を引く規則を検証する。 */
class TextageTitleTableTest {

    private static TextageTitleTable table;

    @BeforeAll
    static void parse() throws Exception {
        table = TextageTitleTable.parse(TextageFixtures.titleTable());
    }

    @Test
    void skipsConsumerOnlyAndDummyRows_andResolvesSubstreamFolder() {
        assertThat(table.entries()).extracting(Entry::key)
                .doesNotContain("321star5", "backfwfv", "__dmy__")   // 版 0 = 家庭用のみ、99 = ダミー
                .contains("22dunk", "genom", "showtime");
        Entry genom = find("genom");
        assertThat(genom.version()).isEqualTo(35);
        assertThat(genom.pagePath()).isEqualTo("s/genom.html");
        assertThat(find("22dunk").pagePath()).isEqualTo("1/22dunk.html");
    }

    @Test
    void stripsStylingAndUnescapesEntities() {
        assertThat(find("showtime").title()).isEqualTo("Show Time");
        assertThat(find("showtime").artist()).isEqualTo("MK&Kanae Asaba");
        assertThat(find("_decohel").title()).isEqualTo("龍と少女とデコヒーレンス†");
        assertThat(find("_bengal").title()).isEqualTo("ベンガル vs. アムール Battle");
    }

    @Test
    void titleMatchIsPreferredAndLeggendariaOnlyPageIsNotConfusedWithMainPage() {
        List<Candidate> c = table.candidates("龍と少女とデコヒーレンス", "黒猫ダンジョン", "EPIC TECHNO", Set.of());
        assertThat(c).extracting(x -> x.entry().key()).containsExactly("_decoher");
        assertThat(c.get(0).kind()).isEqualTo(MatchKind.TITLE);

        // 全角・大文字小文字・空白の違いは吸収する
        assertThat(table.candidates("ＳＨＯＷ ＴＩＭＥ", "", "", Set.of())).extracting(x -> x.entry().key()).containsExactly("showtime");
    }

    @Test
    void fallsBackToArtistAndGenreForAsciiReplacedTitles() {
        // 公式 CSV が特殊文字を置き換えた曲名（Amor∞Fati → AmorFati のような表記）でも ARTIST・GENRE で拾う
        List<Candidate> c = table.candidates("Amor Fati", "BlackY", "PIANO DANCE APPASSIONATO", Set.of());
        assertThat(c).extracting(x -> x.entry().key()).containsExactly("amorfati");
        assertThat(c.get(0).kind()).isEqualTo(MatchKind.ARTIST_GENRE);
        // 他の曲に使われているページは除く
        assertThat(table.candidates("Amor Fati", "BlackY", "PIANO DANCE APPASSIONATO", Set.of("33/amorfati.html"))).isEmpty();
    }

    @Test
    void failsLoudlyWhenFormatChanges() {
        assertThatThrownBy(() -> TextageTitleTable.parse("var x = 1;")).isInstanceOf(IllegalStateException.class);
    }

    private static Entry find(String key) {
        return table.entries().stream().filter(e -> e.key().equals(key)).findFirst().orElseThrow();
    }
}
