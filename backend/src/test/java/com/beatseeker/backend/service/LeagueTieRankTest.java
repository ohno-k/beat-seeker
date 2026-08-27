package com.beatseeker.backend.service;

import org.junit.jupiter.api.Test;

import static com.beatseeker.backend.service.LeagueStandingsService.deltaForRank;
import static com.beatseeker.backend.service.LeagueStandingsService.sharedDelta;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 得点が並んだときの昇降格ポイント（PT）の配分を検証する。
 *
 * 同着（得点も有効曲数も同じ）の集団は、占める順位帯のデルタ合計の平均を等しく受け取り、
 * 端数は<b>多いほう（切り上げ）</b>へ丸める。得点が同じでも有効曲数が違えば同着にはならず、
 * それぞれの順位のデルタをそのまま受け取る（呼び出し側が tieCount = 1 で呼ぶ）。
 */
class LeagueTieRankTest {

    @Test
    void 単独順位ならその順位のデルタそのもの() {
        for (int rank = 1; rank <= 8; rank++) {
            assertThat(sharedDelta(8, rank, 1)).isEqualTo(deltaForRank(8, rank));
        }
    }

    @Test
    void 同着の端数は多いほうへ丸める() {
        // 8 人卓の 2〜3 位が同着: (+3, +2) の平均 2.5 → +3
        assertThat(sharedDelta(8, 2, 2)).isEqualTo(3);
        // 5〜6 位が同着: (-1, -2) の平均 -1.5 → -1（マイナス側も「多いほう」= 0 に近いほう）
        assertThat(sharedDelta(8, 5, 2)).isEqualTo(-1);
    }

    @Test
    void 割り切れる同着は平均そのもの() {
        // 8 人卓の 1〜3 位が同着: (+4, +3, +2) の平均 3.0 → +3
        assertThat(sharedDelta(8, 1, 3)).isEqualTo(3);
        // 3〜6 位が同着: (+2, +1, -1, -2) の平均 0 → ±0
        assertThat(sharedDelta(8, 3, 4)).isZero();
    }

    @Test
    void 全員同着なら増減なし() {
        // 8 人全員が同着: (+4..-4) の合計 0 → ±0
        assertThat(sharedDelta(8, 1, 8)).isZero();
    }

    @Test
    void 奇数人数の同着も切り上げになる() {
        // 7 人卓の 1〜2 位が同着: (+3, +2) の平均 2.5 → +3
        assertThat(sharedDelta(7, 1, 2)).isEqualTo(3);
        // 6〜7 位が同着: (-2, -3) の平均 -2.5 → -2
        assertThat(sharedDelta(7, 6, 2)).isEqualTo(-2);
    }

    @Test
    void 同着でも一週の増減幅は上限を超えない() {
        assertThat(sharedDelta(8, 1, 1)).isLessThanOrEqualTo(LeagueStandingsService.WEEKLY_DELTA_CAP);
        assertThat(sharedDelta(8, 8, 1)).isGreaterThanOrEqualTo(-LeagueStandingsService.WEEKLY_DELTA_CAP);
    }
}
