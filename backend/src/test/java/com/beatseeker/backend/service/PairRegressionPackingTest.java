package com.beatseeker.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 ペア回帰キャッシュ構築時の packed スコア処理を検証する。
 *
 *  - packed = (chartIdx << 32) | score の重複行（source 違い）が最大スコア 1 本に潰れること
 *  - 可変長 long 配列（{@link PairRegressionService.LongList}）が正しく伸びること
 */
class PairRegressionPackingTest {

    private static long pack(int chartIdx, int score) {
        return ((long) chartIdx << 32) | (score & 0xFFFFFFFFL);
    }

    @Test
    void 同じ譜面の重複行は最大スコアだけが残る() {
        long[] packed = {
                pack(5, 3000), pack(2, 1800), pack(5, 3120), // 譜面 5 は arcade / INFINITAS の 2 行
                pack(9, 2500), pack(2, 1750),                 // 譜面 2 も 2 行（小さい方が後ろに来る）
        };

        long[] out = PairRegressionService.dedupeMaxByChart(packed);

        assertThat(out).containsExactly(pack(2, 1800), pack(5, 3120), pack(9, 2500));
    }

    @Test
    void 重複が無ければ並び替えだけで件数は変わらない() {
        long[] packed = {pack(3, 100), pack(1, 200), pack(2, 300)};

        long[] out = PairRegressionService.dedupeMaxByChart(packed);

        assertThat(out).containsExactly(pack(1, 200), pack(2, 300), pack(3, 100));
        assertThat(PairRegressionService.dedupeMaxByChart(new long[0])).isEmpty();
    }

    @Test
    void 可変長配列は初期容量を超えても要素を失わない() {
        PairRegressionService.LongList list = new PairRegressionService.LongList();
        for (int i = 0; i < 1000; i++) list.add(pack(i, i * 2));

        long[] arr = list.toArray();

        assertThat(arr).hasSize(1000);
        assertThat(arr[0]).isEqualTo(pack(0, 0));
        assertThat(arr[999]).isEqualTo(pack(999, 1998));
    }
}
