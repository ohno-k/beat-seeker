package com.beatseeker.backend.service;

import org.junit.jupiter.api.Test;

import static com.beatseeker.backend.service.LeagueStandingsService.POINT_CAP;
import static com.beatseeker.backend.service.LeagueStandingsService.POST_MOVE_POINTS;
import static com.beatseeker.backend.service.LeagueStandingsService.pointsAfterMovement;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 週の締めで保存される昇降格ポイントの引き継ぎを検証する。
 *
 * 昇降格した直後は 0 ではなく「移動先での立ち位置」から始める:
 *  - 昇格後は -{@code POST_MOVE_POINTS}（新 DIVISION では下位スタート）
 *  - 降格後は +{@code POST_MOVE_POINTS}（元の DIVISION へ戻りやすい上位スタート）
 * 移動が無い週は累積に増減を足し、±{@code POINT_CAP} にクランプして保持する。
 */
class LeaguePointCarryOverTest {

    @Test
    void 昇格後はマイナス側から再スタートする() {
        // +8 到達で昇格した週（累積 4 + 増減 4）。0 でも +8 でもなく -4 から始める
        assertThat(pointsAfterMovement("promote", 4, 4)).isEqualTo(-POST_MOVE_POINTS);
    }

    @Test
    void 降格後はプラス側から再スタートする() {
        // -8 到達で降格した週（累積 -4 + 増減 -4）。0 でも -8 でもなく +4 から始める
        assertThat(pointsAfterMovement("relegate", -4, -4)).isEqualTo(POST_MOVE_POINTS);
    }

    @Test
    void 移動なしの週は累積に増減を足す() {
        assertThat(pointsAfterMovement("stay", 2, 3)).isEqualTo(5);
        assertThat(pointsAfterMovement("stay", -2, -3)).isEqualTo(-5);
        assertThat(pointsAfterMovement(null, 0, 4)).isEqualTo(4);
    }

    @Test
    void 移動先が無い場合は上限にクランプされる() {
        // LEGEND のプラス超過・DIVISION 10 のマイナス超過（昇降格しないので movement は stay のまま）
        assertThat(pointsAfterMovement("stay", POINT_CAP, 4)).isEqualTo(POINT_CAP);
        assertThat(pointsAfterMovement("stay", -POINT_CAP, -4)).isEqualTo(-POINT_CAP);
    }

    @Test
    void 昇格直後は一週の増減で降格圏に届く() {
        // -4 スタート + 8 人グループ最下位（-4）= -8 で即降格。昇格した DIVISION で通用しなければ戻る
        int start = pointsAfterMovement("promote", 4, 4);
        assertThat(start + LeagueStandingsService.deltaForRank(8, 8)).isEqualTo(-POINT_CAP);
        // 逆に降格直後（+4）は 8 人グループ 1 位（+4）で即再昇格できる
        int afterRelegation = pointsAfterMovement("relegate", -4, -4);
        assertThat(afterRelegation + LeagueStandingsService.deltaForRank(8, 1)).isEqualTo(POINT_CAP);
    }
}
