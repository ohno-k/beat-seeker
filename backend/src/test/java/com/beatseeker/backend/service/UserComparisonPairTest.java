package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.UserComparisonStat;
import com.beatseeker.backend.entity.UserComparisonStat.LevelCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.beatseeker.backend.service.UserComparisonStatsService.comparePair;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 ユーザー間スコア比較の突き合わせ（マージ結合）が正しく数えられるか検証する。
 *
 * 検証したいのは 3 点:
 *  - 両者プレイ済みの譜面だけが WIN / LOSS / DRAW になること
 *  - 片方だけプレイ済みの譜面が「片方のみ」に落ちること（勝敗には数えない）
 *  - レベル帯（Lv.10 以下 / Lv.11 / Lv.12）ごとに独立して数えられること
 *
 * 相手視点の行は勝敗を反転させて作るので、その反転もあわせて確認する。
 */
class UserComparisonPairTest {

    /** 譜面 0,1 が Lv.12、譜面 2,3 が Lv.11、譜面 4 が Lv.10 以下、という並び。 */
    private static final LevelCategory[] LEVELS = {
            LevelCategory.LV12,
            LevelCategory.LV12,
            LevelCategory.LV11,
            LevelCategory.LV11,
            LevelCategory.LV10MINUS
    };

    /** 譜面 ID とスコアを交互に並べた指定から譜面列を組み立てる（順不同で渡してよい）。 */
    private static UserComparisonStatsService.UserCharts charts(int... chartIdAndScore) {
        UserComparisonStatsService.ChartScoreBuilder builder = new UserComparisonStatsService.ChartScoreBuilder();
        for (int i = 0; i < chartIdAndScore.length; i += 2) {
            builder.add(chartIdAndScore[i], chartIdAndScore[i + 1]);
        }
        return builder.build(LEVELS);
    }

    private static int slot(LevelCategory category) {
        return category.ordinal();
    }

    @Test
    void 両者プレイ済みの譜面だけが勝敗になる() {
        // 譜面 0: A の勝ち / 譜面 1: B の勝ち / 譜面 2: 同点
        var a = charts(0, 1500, 1, 1000, 2, 1200);
        var b = charts(0, 1400, 1, 1100, 2, 1200);

        var result = comparePair(a, b, LEVELS);

        assertThat(result.win[slot(LevelCategory.LV12)]).isEqualTo(1);
        assertThat(result.loss[slot(LevelCategory.LV12)]).isEqualTo(1);
        assertThat(result.draw[slot(LevelCategory.LV12)]).isZero();
        assertThat(result.draw[slot(LevelCategory.LV11)]).isEqualTo(1);
        // 全譜面を両者プレイ済みなので「片方のみ」は 0。
        assertThat(result.onlySelf).containsOnly(0);
        assertThat(result.onlyOpponent).containsOnly(0);
    }

    @Test
    void 片方だけプレイ済みの譜面は勝敗に数えない() {
        // 譜面 0 は両者、譜面 1 は A のみ、譜面 3 は B のみ。
        var a = charts(0, 1500, 1, 900);
        var b = charts(0, 1600, 3, 800);

        var result = comparePair(a, b, LEVELS);

        assertThat(result.loss[slot(LevelCategory.LV12)]).isEqualTo(1);
        assertThat(result.win[slot(LevelCategory.LV12)]).isZero();
        // 譜面 1 は Lv.12、譜面 3 は Lv.11。
        assertThat(result.onlySelf[slot(LevelCategory.LV12)]).isEqualTo(1);
        assertThat(result.onlyOpponent[slot(LevelCategory.LV11)]).isEqualTo(1);
        assertThat(result.onlyOpponent[slot(LevelCategory.LV12)]).isZero();
    }

    @Test
    void レベル帯ごとに独立して数える() {
        // Lv.12 は A の 2 勝、Lv.11 は B の 2 勝、Lv.10 以下は同点。
        var a = charts(0, 1500, 1, 1500, 2, 1000, 3, 1000, 4, 700);
        var b = charts(0, 1400, 1, 1400, 2, 1100, 3, 1100, 4, 700);

        var result = comparePair(a, b, LEVELS);

        assertThat(result.win[slot(LevelCategory.LV12)]).isEqualTo(2);
        assertThat(result.loss[slot(LevelCategory.LV12)]).isZero();
        assertThat(result.loss[slot(LevelCategory.LV11)]).isEqualTo(2);
        assertThat(result.win[slot(LevelCategory.LV11)]).isZero();
        assertThat(result.draw[slot(LevelCategory.LV10MINUS)]).isEqualTo(1);
    }

    @Test
    void 相手から見た行は勝敗と片方のみが入れ替わる() {
        var a = charts(0, 1500, 1, 900);
        var b = charts(0, 1400, 3, 800);

        var result = comparePair(a, b, LEVELS);
        List<UserComparisonStat> rows = result.toBothRows(1L, 2L);

        var forA = pick(rows, 1L, LevelCategory.LV12);
        var forB = pick(rows, 2L, LevelCategory.LV12);

        assertThat(forA.getWin()).isEqualTo(1);
        assertThat(forA.getLoss()).isZero();
        assertThat(forA.getOnlySelf()).isEqualTo(1);
        assertThat(forA.getOnlyOpponent()).isZero();

        // B 視点は A 視点の鏡像。
        assertThat(forB.getWin()).isZero();
        assertThat(forB.getLoss()).isEqualTo(1);
        assertThat(forB.getOnlySelf()).isZero();
        assertThat(forB.getOnlyOpponent()).isEqualTo(1);
        assertThat(forB.getOpponentId()).isEqualTo(1L);
    }

    @Test
    void 数えるものが無いレベル帯は行を作らない() {
        // 両者とも Lv.12 の譜面 0 しか持っていないので、Lv.11 と Lv.10 以下の行は生まれない。
        var a = charts(0, 1500);
        var b = charts(0, 1400);

        List<UserComparisonStat> rows = comparePair(a, b, LEVELS).toBothRows(1L, 2L);

        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(r -> r.getLevelCategory() == LevelCategory.LV12);
    }

    @Test
    void スコアが片方も無ければ相手のプレイ済みが全部片方のみになる() {
        var a = charts();
        var b = charts(0, 1400, 2, 1000, 4, 700);

        var result = comparePair(a, b, LEVELS);

        assertThat(result.onlySelf).containsOnly(0);
        assertThat(result.onlyOpponent[slot(LevelCategory.LV12)]).isEqualTo(1);
        assertThat(result.onlyOpponent[slot(LevelCategory.LV11)]).isEqualTo(1);
        assertThat(result.onlyOpponent[slot(LevelCategory.LV10MINUS)]).isEqualTo(1);
        assertThat(result.win).containsOnly(0);
        assertThat(result.loss).containsOnly(0);
        assertThat(result.draw).containsOnly(0);
    }

    private static UserComparisonStat pick(List<UserComparisonStat> rows, Long userId, LevelCategory category) {
        return rows.stream()
                .filter(r -> r.getUserId().equals(userId) && r.getLevelCategory() == category)
                .findFirst()
                .orElseThrow();
    }
}
