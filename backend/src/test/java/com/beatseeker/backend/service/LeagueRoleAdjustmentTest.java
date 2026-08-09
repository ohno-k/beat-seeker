package com.beatseeker.backend.service;

import org.junit.jupiter.api.Test;

import static com.beatseeker.backend.service.LeagueStandingsService.applyRole;
import static com.beatseeker.backend.service.LeagueStandingsService.deltaForRank;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 チャレンジ/ディフェンス補正（±2）が着順の勝ち負けを反転させないことを検証する。
 *
 * 補正は「同じ結果なら格上への挑戦を優遇する」ためのものなので、
 *  - 負け越した挑戦者がプラスになってはいけない（0 で止める）
 *  - 勝った防衛側がマイナスになってはいけない（0 で止める）
 * 3 人グループの着順デルタが +1 / 0 / -1 と小さいため、この反転が実際に起きていた。
 */
class LeagueRoleAdjustmentTest {

    @Test
    void 三人グループの着順デルタは前提どおり() {
        assertThat(deltaForRank(3, 1)).isEqualTo(1);
        assertThat(deltaForRank(3, 2)).isZero();
        assertThat(deltaForRank(3, 3)).isEqualTo(-1);
    }

    @Test
    void チャレンジで最下位なら増減なし() {
        // -1 + 2 = +1（最下位なのに加点）にはせず、0 で止める
        assertThat(applyRole(deltaForRank(3, 3), "challenge")).isZero();
    }

    @Test
    void ディフェンスで一位なら増減なし() {
        // +1 - 2 = -1（1 位なのに減点）にはせず、0 で止める
        assertThat(applyRole(deltaForRank(3, 1), "defense")).isZero();
    }

    @Test
    void 負け側は補正後もマイナスのまま残る() {
        // 8 人グループの 8 位 = -4。チャレンジでも -2 でマイナス側に留まる（0 には切り上げない）
        assertThat(applyRole(deltaForRank(8, 8), "challenge")).isEqualTo(-2);
        // 8 人グループの 1 位 = +4。ディフェンスでも +2 でプラス側に留まる
        assertThat(applyRole(deltaForRank(8, 1), "defense")).isEqualTo(2);
    }

    @Test
    void 勝ち負けが付いていない中央順位には従来どおり補正が乗る() {
        // 3 人の 2 位（±0）は勝ちでも負けでもないので、挑戦の優遇 +2 / 防衛の不利 -2 をそのまま適用する
        assertThat(applyRole(deltaForRank(3, 2), "challenge")).isEqualTo(2);
        assertThat(applyRole(deltaForRank(3, 2), "defense")).isEqualTo(-2);
    }

    @Test
    void normalは補正されない() {
        assertThat(applyRole(deltaForRank(3, 1), "normal")).isEqualTo(1);
        assertThat(applyRole(deltaForRank(3, 3), "normal")).isEqualTo(-1);
    }

    @Test
    void 補正後も週の増減幅にクランプされる() {
        assertThat(applyRole(4, "challenge")).isEqualTo(LeagueStandingsService.WEEKLY_DELTA_CAP);
        assertThat(applyRole(-4, "defense")).isEqualTo(-LeagueStandingsService.WEEKLY_DELTA_CAP);
    }
}
