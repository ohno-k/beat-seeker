package com.beatseeker.backend.service;

import org.junit.jupiter.api.Test;

import static com.beatseeker.backend.service.LeagueStandingsService.incompletePenalty;
import static com.beatseeker.backend.service.LeagueStandingsService.weeklyDeltas;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 「課題曲を全曲プレーした人だけで PT をやり取りする」規則を検証する。
 *
 * 1 曲でも遊ばなかった人はやり取りから外れ、グループ人数に応じた固定マイナス
 * （8 人卓なら -4）を受ける。残った人は<b>その人数</b>で順位を数え直して増減幅が決まるので、
 * 放置が多い週でも「遊んだ人が全員プラス」にはならない。
 */
class LeagueIncompletePlayTest {

    /** 全員が同着でない（＝表示順位が 1, 2, 3, ... と並ぶ）場合の順位配列。 */
    private static int[] ranks(int size) {
        int[] r = new int[size];
        for (int i = 0; i < size; i++) r[i] = i + 1;
        return r;
    }

    /** すべて true の配列（全員が全曲プレー / 全員が有効曲あり）。 */
    private static boolean[] all(int size, boolean value) {
        boolean[] b = new boolean[size];
        java.util.Arrays.fill(b, value);
        return b;
    }

    @Test
    void 固定マイナスはその人数の最大マイナス() {
        assertThat(incompletePenalty(8)).isEqualTo(-4);
        assertThat(incompletePenalty(7)).isEqualTo(-3);
        assertThat(incompletePenalty(6)).isEqualTo(-3);
        assertThat(incompletePenalty(2)).isEqualTo(-1);
        assertThat(incompletePenalty(1)).isZero(); // 1 人卓はそもそも増減なし
    }

    @Test
    void 全員が全曲プレーなら従来どおりの増減() {
        int[] deltas = weeklyDeltas(all(8, true), all(8, true), ranks(8));

        assertThat(deltas).containsExactly(4, 3, 2, 1, -1, -2, -3, -4);
    }

    @Test
    void 全曲プレーした人だけで残り人数ぶんの増減をやり取りする() {
        // 8 人卓で上位 3 人だけが 3 曲すべてをプレー。残る 5 人は 1 曲でも未プレー。
        boolean[] playedAll = { true, true, true, false, false, false, false, false };

        int[] deltas = weeklyDeltas(playedAll, all(8, true), ranks(8));

        // 3 人でのやり取りなので +1 / 0 / -1（8 人基準の +4 / +3 / +2 にはならない）
        assertThat(deltas[0]).isEqualTo(1);
        assertThat(deltas[1]).isZero();
        assertThat(deltas[2]).isEqualTo(-1);
        // 未プレーのある 5 人は順位に関わらず一律 -4
        assertThat(deltas[3]).isEqualTo(-4);
        assertThat(deltas[7]).isEqualTo(-4);
    }

    @Test
    void 順位が飛んでいてもやり取りは残った人の中で数え直す() {
        // 全曲プレーしたのは表示順位 1 位・4 位・8 位の 3 人（間に未プレーの人が挟まる）。
        boolean[] playedAll = { true, false, false, true, false, false, false, true };

        int[] deltas = weeklyDeltas(playedAll, all(8, true), ranks(8));

        assertThat(deltas[0]).isEqualTo(1);  // 残った 3 人の中では 1 位
        assertThat(deltas[3]).isZero();      // 同 2 位
        assertThat(deltas[7]).isEqualTo(-1); // 同 3 位
        assertThat(deltas[1]).isEqualTo(-4);
        assertThat(deltas[6]).isEqualTo(-4);
    }

    @Test
    void 全員が全曲プレーしていなければ全員が固定マイナス() {
        int[] deltas = weeklyDeltas(all(8, false), all(8, true), ranks(8));

        assertThat(deltas).containsOnly(-4);
    }

    @Test
    void 全曲プレーが1人だけならその人は増減なし() {
        boolean[] playedAll = { false, true, false, false, false, false, false, false };

        int[] deltas = weeklyDeltas(playedAll, all(8, true), ranks(8));

        assertThat(deltas[1]).isZero(); // やり取りの相手が居ない
        assertThat(deltas[0]).isEqualTo(-4);
    }

    @Test
    void 残った人の中の同着は順位帯を等分する() {
        // 6 人卓で 4 人が全曲プレー。そのうち上位 2 人が同着（表示順位 1, 1, 3, 4, ...）。
        boolean[] playedAll = { true, true, true, true, false, false };
        int[] displayRank = { 1, 1, 3, 4, 5, 6 };

        int[] deltas = weeklyDeltas(playedAll, all(6, true), displayRank);

        // 4 人でのやり取り: 1〜2 位が同着なら (+2, +1) の平均 1.5 → 切り上げて +2
        assertThat(deltas[0]).isEqualTo(2);
        assertThat(deltas[1]).isEqualTo(2);
        assertThat(deltas[2]).isEqualTo(-1); // 3 位
        assertThat(deltas[3]).isEqualTo(-2); // 4 位
        assertThat(deltas[4]).isEqualTo(-3); // 6 人卓の固定マイナス
        assertThat(deltas[5]).isEqualTo(-3);
    }

    @Test
    void 全曲プレーでも有効曲が0ならプラスは受け取れない() {
        // 3 曲すべて遊んだが 1 曲もラインを超えられなかった人がやり取りの 1 位に来た場合。
        boolean[] playedAll = { true, true, true, false, false, false, false, false };
        boolean[] anyValid = { false, true, true, true, true, true, true, true };

        int[] deltas = weeklyDeltas(playedAll, anyValid, ranks(8));

        assertThat(deltas[0]).isZero();      // +1 のはずがプラスを没収されて ±0
        assertThat(deltas[1]).isZero();      // 2 位は元から ±0
        assertThat(deltas[2]).isEqualTo(-1);
    }
}
