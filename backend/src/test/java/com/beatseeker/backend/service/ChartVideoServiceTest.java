package com.beatseeker.backend.service;

import com.beatseeker.backend.service.ChartVideoService.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 原曲動画の候補の並べ方と、URL・長さ・譜面の秒数の読み取りを検証する。
 */
class ChartVideoServiceTest {

    @Test
    void rank_prefersTitleMatchAndFittingLength_dropsShortsAndCovers() {
        List<Candidate> searched = List.of(
                new Candidate("short000001", "廿 #shorts", "a", 45),                        // 短すぎる → 除く
                new Candidate("cover000001", "廿 / DJ TECHNORCH ピアノで弾いてみた", "b", 130),  // 原曲でない語
                new Candidate("other000001", "beatmania IIDX 別の曲 SPA", "c", 140),          // 曲名なし
                new Candidate("play0000001", "【beatmania IIDX】廿 (SPL) 正規", "d", 150),      // 本命（選曲・リザルト込み）
                new Candidate("long0000001", "IIDX 廿 作業用 1 hour", "e", 3600));            // 長すぎる → 除く

        List<Candidate> ranked = ChartVideoService.rank(searched, "廿", 125.0);

        assertThat(ranked).extracting(Candidate::id).containsExactly("play0000001", "other000001", "cover000001");
    }

    @Test
    void searchQuery_dropsYouTubeOperators() {
        // 「-rebuild-」のままだと rebuild を含む動画が除外される（2026-10-03 AA -rebuild- が無印 AA だらけになった）
        assertThat(ChartVideoService.searchQuery("AA -rebuild-", "D.J.Amuro")).isEqualTo("AA rebuild D.J.Amuro beatmania IIDX");
        assertThat(ChartVideoService.searchQuery("《PL|RAYER》", "BlackLolita")).isEqualTo("《PL RAYER》 BlackLolita beatmania IIDX");
        assertThat(ChartVideoService.searchQuery("-65℃", null)).isEqualTo("65℃ beatmania IIDX");
    }

    @Test
    void rank_shorterTitle_dropsVideosOfTheLongerSong() {
        // 「AA」の検索結果（本番の候補）。「AA -rebuild-」の動画は別の曲なので下げる
        List<Candidate> searched = List.of(
                new Candidate("2rCcNpfmhK4", "Beatmania IIDX 11 IIDX RED - AA [ANOTHER]", "Ko Ramdeo", 126),
                new Candidate("xBpgjlG8FKg", "【beatmania IIDX】AA -rebuild- / D.J.Amuro", "zakuroiidx", 130),
                new Candidate("EF1yW1ne1FE", "【beatmania IIDX】AA / D.J.Amuro", "牙", 132));
        List<String> longer = ChartVideoService.longerTitles("AA", List.of("AA", "AA -rebuild-", "GENE"));
        assertThat(longer).containsExactly("aarebuild");

        List<Candidate> ranked = ChartVideoService.rank(searched, "AA", 120.0, longer);
        assertThat(ranked).extracting(Candidate::id).last().isEqualTo("xBpgjlG8FKg");

        // 「AA -rebuild-」側は曲名を含む動画が先頭
        List<Candidate> rebuild = ChartVideoService.rank(searched, "AA -rebuild-", 120.0, List.of());
        assertThat(rebuild.get(0).id()).isEqualTo("xBpgjlG8FKg");
    }

    @Test
    void rank_notOriginalWordInSongTitleIsNotPenalized() {
        List<Candidate> searched = List.of(
                new Candidate("other000001", "beatmania IIDX 別の曲", "x", 150),
                new Candidate("right000001", "【IIDX】Abyss -The Heavens Remix- SPA", "y", 150));
        List<Candidate> ranked = ChartVideoService.rank(searched, "Abyss -The Heavens Remix-", 140.0);
        assertThat(ranked.get(0).id()).isEqualTo("right000001");
    }

    @Test
    void rank_withoutChartLength_keepsAllWithDuration() {
        List<Candidate> searched = List.of(
                new Candidate("a0000000001", "GENE", "x", 120),
                new Candidate("b0000000001", "no duration", "x", null));
        assertThat(ChartVideoService.rank(searched, "GENE", null)).extracting(Candidate::id).containsExactly("a0000000001");
    }

    @Test
    void normalize_ignoresWidthCaseAndSymbols() {
        assertThat(ChartVideoService.normalize("ＧＥＮＥ")).isEqualTo("gene");
        assertThat(ChartVideoService.normalize("Close the World feat.a☆ru"))
                .isEqualTo(ChartVideoService.normalize("close the world feat. a☆ru"));
    }

    @Test
    void parseVideoId_acceptsUrlsAndBareIds() {
        assertThat(ChartVideoService.parseVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s")).isEqualTo("dQw4w9WgXcQ");
        assertThat(ChartVideoService.parseVideoId("https://youtu.be/dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ");
        assertThat(ChartVideoService.parseVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ");
        assertThat(ChartVideoService.parseVideoId(" dQw4w9WgXcQ ")).isEqualTo("dQw4w9WgXcQ");
        assertThat(ChartVideoService.parseVideoId("https://example.com/")).isNull();
    }

    @Test
    void parseDuration_iso8601() {
        assertThat(ChartVideoService.parseDuration("PT3M2S")).isEqualTo(182);
        assertThat(ChartVideoService.parseDuration("PT1H")).isEqualTo(3600);
        assertThat(ChartVideoService.parseDuration("bad")).isNull();
    }

    @Test
    void median_oddAndEven() {
        assertThat(ChartVideoService.median(List.of(6.4, 10.0, 6.1))).isEqualTo(6.4);
        assertThat(ChartVideoService.median(List.of(-1.03, 0.03))).isCloseTo(-0.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void chartSeconds_followsBpmChanges() {
        // 1 小節 = 384 tick。BPM150 で 1 小節 = 1.6 秒、BPM300 で 0.8 秒
        assertThat(ChartVideoService.chartSeconds(List.of(new double[]{0, 150}), 384 * 10)).isEqualTo(16.0);
        assertThat(ChartVideoService.chartSeconds(List.of(new double[]{0, 150}, new double[]{384, 300}), 384 * 2))
                .isCloseTo(2.4, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ChartVideoService.chartSeconds(List.of(), 384)).isNull();
    }
}
