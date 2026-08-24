package com.beatseeker.backend.service;

import org.junit.jupiter.api.Test;

import static com.beatseeker.backend.service.LeagueStandingsService.BAND_ABSENT;
import static com.beatseeker.backend.service.LeagueStandingsService.BAND_PLAYED;
import static com.beatseeker.backend.service.LeagueStandingsService.BAND_VALID;
import static com.beatseeker.backend.service.LeagueStandingsService.distributeSongPoints;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 曲別の着順ポイント配分が「有効 &gt; 参加 &gt; 不参加」の 3 段になっていることを検証する。
 *
 * 旧仕様は未有効者を一律 1 つの同着集団として扱っていたため、週内に課題曲を遊んでいない人も
 * 遊んだ人と同じ山分けを受け取れていた。新仕様では不参加者を 0 pt 固定にし、その順位帯ぶんの
 * 生ポイントは配らずに消す（参加した未到達者の取り分が上の順位帯へ繰り上がる）。
 */
class LeagueSongPointDistributionTest {

    /** レート不明（未有効）を表すソートキー。 */
    private static final double NONE = Double.NEGATIVE_INFINITY;

    @Test
    void 不参加者は0ptで参加した未到達者が上の順位帯を山分けする() {
        // 8 人卓: 有効 2 人（99.0 / 98.0）・参加未到達 3 人・不参加 3 人
        int[] band = { BAND_VALID, BAND_VALID, BAND_PLAYED, BAND_PLAYED, BAND_PLAYED, BAND_ABSENT, BAND_ABSENT, BAND_ABSENT };
        double[] rate = { 99.0, 98.0, NONE, NONE, NONE, NONE, NONE, NONE };

        double[] pts = distributeSongPoints(band, rate);

        assertThat(pts[0]).isEqualTo(8.0); // 1 位
        assertThat(pts[1]).isEqualTo(7.0); // 2 位
        // 参加した未到達者は 3〜5 位の帯を山分け: (6+5+4)/3
        assertThat(pts[2]).isEqualTo(5.0);
        assertThat(pts[3]).isEqualTo(5.0);
        assertThat(pts[4]).isEqualTo(5.0);
        // 不参加者は 6〜8 位の帯を占めるが受け取らない（3+2+1 = 6 pt は配られずに消える）
        assertThat(pts[5]).isEqualTo(0.0);
        assertThat(pts[6]).isEqualTo(0.0);
        assertThat(pts[7]).isEqualTo(0.0);
    }

    @Test
    void 全員が参加していれば従来どおりの配分になる() {
        // 不参加者が居ない卓では旧仕様と同じ結果になること（既存の週の成績が変わらない保証）。
        int[] band = { BAND_VALID, BAND_PLAYED, BAND_PLAYED, BAND_PLAYED };
        double[] rate = { 97.5, NONE, NONE, NONE };

        double[] pts = distributeSongPoints(band, rate);

        assertThat(pts[0]).isEqualTo(4.0);
        // 未有効 3 人が 2〜4 位の帯を山分け: (3+2+1)/3
        assertThat(pts[1]).isEqualTo(2.0);
        assertThat(pts[2]).isEqualTo(2.0);
        assertThat(pts[3]).isEqualTo(2.0);
    }

    @Test
    void 同レートの有効者は同着として順位帯を等分する() {
        int[] band = { BAND_VALID, BAND_VALID, BAND_PLAYED, BAND_ABSENT };
        double[] rate = { 98.0, 98.0, NONE, NONE };

        double[] pts = distributeSongPoints(band, rate);

        // 同着 2 人が 1〜2 位の帯を等分: (4+3)/2
        assertThat(pts[0]).isEqualTo(3.5);
        assertThat(pts[1]).isEqualTo(3.5);
        assertThat(pts[2]).isEqualTo(2.0); // 3 位
        assertThat(pts[3]).isEqualTo(0.0); // 4 位の 1 pt は配られない
    }

    @Test
    void 誰も遊んでいなければ全員0ptになる() {
        int[] band = { BAND_ABSENT, BAND_ABSENT, BAND_ABSENT };
        double[] rate = { NONE, NONE, NONE };

        assertThat(distributeSongPoints(band, rate)).containsExactly(0.0, 0.0, 0.0);
    }

    @Test
    void 段が先に効きレートは同じ段の中でしか比較されない() {
        // 配列の並び順（＝メンバーの登録順）に関わらず、段で先に切られること。
        int[] band = { BAND_ABSENT, BAND_PLAYED, BAND_VALID };
        double[] rate = { NONE, NONE, 96.0 };

        double[] pts = distributeSongPoints(band, rate);

        assertThat(pts[2]).isEqualTo(3.0); // 有効者が 1 位
        assertThat(pts[1]).isEqualTo(2.0); // 参加未到達が 2 位
        assertThat(pts[0]).isEqualTo(0.0); // 不参加は 3 位の 1 pt を受け取らない
    }
}
