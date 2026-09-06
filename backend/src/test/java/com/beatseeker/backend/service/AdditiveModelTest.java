package com.beatseeker.backend.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 【テストの目的】 加法モデル {@code logit = θ_u + δ_c + ε} の交互平均が、
 * 既知のパラメータから生成した疎なデータ（各ユーザーは一部の譜面しかプレイしない）を復元できることを確認する。
 *
 * θ と δ は定数ぶんの不定性があるので、和 θ_u + δ_c で比較する。
 */
class AdditiveModelTest {

    @Test
    void 疎なデータから実力と譜面効果の和を復元できる() {
        Random rnd = new Random(42);
        int numUsers = 200, numCharts = 60;
        double[] trueTheta = new double[numUsers];
        double[] trueDelta = new double[numCharts];
        for (int u = 0; u < numUsers; u++) trueTheta[u] = 1.0 + rnd.nextGaussian() * 0.8;
        for (int c = 0; c < numCharts; c++) trueDelta[c] = rnd.nextGaussian() * 0.5;

        List<int[]> idx = new ArrayList<>();
        List<double[]> logit = new ArrayList<>();
        for (int u = 0; u < numUsers; u++) {
            List<Integer> played = new ArrayList<>();
            for (int c = 0; c < numCharts; c++) if (rnd.nextDouble() < 0.3) played.add(c);
            if (played.isEmpty()) played.add(rnd.nextInt(numCharts));
            int[] a = new int[played.size()];
            double[] b = new double[played.size()];
            for (int i = 0; i < a.length; i++) {
                a[i] = played.get(i);
                b[i] = trueTheta[u] + trueDelta[a[i]] + rnd.nextGaussian() * 0.1;
            }
            idx.add(a);
            logit.add(b);
        }

        PairRegressionService.AdditiveModel.Result r =
                PairRegressionService.AdditiveModel.fit(idx, logit, numCharts, PairRegressionService.ADDITIVE_ITERATIONS);

        // 未プレイの (u, c) を含めて θ_u + δ_c が真値に近い（ノイズ 0.1 に対して誤差 0.1 未満が大半）
        double sumAbs = 0;
        int cnt = 0;
        for (int u = 0; u < numUsers; u++) {
            for (int c = 0; c < numCharts; c++) {
                double pred = r.theta[u] + r.delta[c];
                sumAbs += Math.abs(pred - (trueTheta[u] + trueDelta[c]));
                cnt++;
            }
        }
        assertThat(sumAbs / cnt).isLessThan(0.08);
        // 残差 sd はノイズ水準（0.1）に近い
        assertThat(r.pooledSd).isCloseTo(0.1, within(0.03));
        // 全譜面に人数が立つ
        for (int c = 0; c < numCharts; c++) assertThat(r.n[c]).isGreaterThan(0);
    }

    @Test
    void 誰もプレイしていない譜面は人数ゼロで効果ゼロになる() {
        List<int[]> idx = List.of(new int[]{0, 1}, new int[]{1});
        List<double[]> logit = List.of(new double[]{1.0, 2.0}, new double[]{2.5});

        PairRegressionService.AdditiveModel.Result r =
                PairRegressionService.AdditiveModel.fit(idx, logit, 3, 5);

        assertThat(r.n[2]).isZero();
        assertThat(r.delta[2]).isZero();
        assertThat(r.n[1]).isEqualTo(2);
        // 1 人しか居ない譜面の残差 sd は定義しない（NaN）
        assertThat(r.residSd[0]).isNaN();
    }
}
