package com.beatseeker.backend.service;

import org.junit.jupiter.api.Test;

import static com.beatseeker.backend.service.LeagueStandingsService.BAND_ABSENT;
import static com.beatseeker.backend.service.LeagueStandingsService.BAND_PLAYED;
import static com.beatseeker.backend.service.LeagueStandingsService.BAND_VALID;
import static com.beatseeker.backend.service.LeagueStandingsService.distributeSongPoints;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 曲別の着順ポイント配分を検証する。
 *
 * 有効ラインに届かなかった人は、遊んだかどうかに関わらず<b>まず全員で</b>その順位帯を山分けし、
 * そのあと週内に遊んだ形跡が無い人（不参加）だけが 0 pt に落ちる。落ちたぶんの生ポイントは
 * 誰にも配られずに消えるので、参加した未到達者の取り分は不参加者の人数に左右されない。
 */
class LeagueSongPointDistributionTest {

    /** レート不明（未有効）を表すソートキー。 */
    private static final double NONE = Double.NEGATIVE_INFINITY;

    @Test
    void 未到達者は全員で山分けしたあと不参加者だけが0ptになる() {
        // 8 人卓: 有効 2 人（99.0 / 98.0）・参加未到達 3 人・不参加 3 人
        int[] band = { BAND_VALID, BAND_VALID, BAND_PLAYED, BAND_PLAYED, BAND_PLAYED, BAND_ABSENT, BAND_ABSENT, BAND_ABSENT };
        double[] rate = { 99.0, 98.0, NONE, NONE, NONE, NONE, NONE, NONE };

        double[] pts = distributeSongPoints(band, rate);

        assertThat(pts[0]).isEqualTo(8.0); // 1 位
        assertThat(pts[1]).isEqualTo(7.0); // 2 位
        // 未到達の 6 人が 3〜8 位の帯を山分け: (6+5+4+3+2+1)/6 = 3.5
        assertThat(pts[2]).isEqualTo(3.5);
        assertThat(pts[3]).isEqualTo(3.5);
        assertThat(pts[4]).isEqualTo(3.5);
        // 不参加者は山分けの取り分を受け取らず 0 pt（消えた 10.5 pt は誰にも配られない）
        assertThat(pts[5]).isEqualTo(0.0);
        assertThat(pts[6]).isEqualTo(0.0);
        assertThat(pts[7]).isEqualTo(0.0);
    }

    @Test
    void 不参加者の人数は参加した未到達者の取り分を変えない() {
        // 上のケースと同じ 8 人卓で、不参加 3 人を参加未到達に入れ替えても取り分は 3.5 のまま。
        int[] band = { BAND_VALID, BAND_VALID, BAND_PLAYED, BAND_PLAYED, BAND_PLAYED, BAND_PLAYED, BAND_PLAYED, BAND_PLAYED };
        double[] rate = { 99.0, 98.0, NONE, NONE, NONE, NONE, NONE, NONE };

        double[] pts = distributeSongPoints(band, rate);

        assertThat(pts[0]).isEqualTo(8.0);
        assertThat(pts[1]).isEqualTo(7.0);
        for (int i = 2; i < 8; i++) {
            assertThat(pts[i]).isEqualTo(3.5);
        }
    }

    @Test
    void 全員が参加していれば従来どおりの配分になる() {
        // 不参加者が居ない卓では従来と同じ結果になること。
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
        // 未到達の 2 人が 3〜4 位の帯を山分け: (2+1)/2 = 1.5。不参加の 1 人は 0 pt。
        assertThat(pts[2]).isEqualTo(1.5);
        assertThat(pts[3]).isEqualTo(0.0);
    }

    @Test
    void 誰も遊んでいなければ全員0ptになる() {
        int[] band = { BAND_ABSENT, BAND_ABSENT, BAND_ABSENT };
        double[] rate = { NONE, NONE, NONE };

        assertThat(distributeSongPoints(band, rate)).containsExactly(0.0, 0.0, 0.0);
    }

    @Test
    void 段が先に効きレートは同じ段の中でしか比較されない() {
        // 配列の並び順（＝メンバーの登録順）に関わらず、有効／未有効で先に切られること。
        int[] band = { BAND_ABSENT, BAND_PLAYED, BAND_VALID };
        double[] rate = { NONE, NONE, 96.0 };

        double[] pts = distributeSongPoints(band, rate);

        assertThat(pts[2]).isEqualTo(3.0); // 有効者が 1 位
        assertThat(pts[1]).isEqualTo(1.5); // 未到達 2 人が 2〜3 位の帯を山分け: (2+1)/2
        assertThat(pts[0]).isEqualTo(0.0); // 不参加は 0 pt
    }
}
