package com.beatseeker.backend;

import com.beatseeker.backend.entity.PastScore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 過去作スコアの曲名を現行表記へ寄せる際、同じ譜面の行が既にある場合の
 * マージ規則（{@link DataInitializer#mergeBestInto}）を検証する。
 *
 * 例: 31 EPOLIS の CSV を表記変更の前後で 2 回取り込み、"VØID" と "VOID" の行が両方ある状態。
 * "VØID" 側を "VOID" に改名するとユニーク制約に衝突するので、良い値だけを "VOID" 側へ移して
 * "VØID" 側を消す。スコア・ランプ・BP は互いに独立して比較する（IIDX 実機のベスト記録と同じ）。
 */
class PastScoreTitleMergeTest {

    private static PastScore row(int score, String djLevel, int pgreat, int great,
                                 String clearType, Integer missCount, Integer playCount, String lastPlayedAt) {
        PastScore p = new PastScore();
        p.setTitle("VOID");
        p.setDifficultyName("ANOTHER");
        p.setVersion(31);
        p.setScore(score);
        p.setDjLevel(djLevel);
        p.setPgreat(pgreat);
        p.setGreat(great);
        p.setClearType(clearType);
        p.setMissCount(missCount);
        p.setPlayCount(playCount);
        p.setLastPlayedAt(lastPlayedAt);
        return p;
    }

    @Test
    void スコアが上回る側のDJレベルとPGREATとGREATをまとめて採用する() {
        PastScore target = row(3000, "AA", 1200, 600, "HARD CLEAR", 20, 5, "2024-01-01 10:00");
        PastScore source = row(3100, "AAA", 1300, 500, "CLEAR", 30, 3, "2023-12-01 10:00");

        DataInitializer.mergeBestInto(target, source);

        assertThat(target.getScore()).isEqualTo(3100);
        assertThat(target.getDjLevel()).isEqualTo("AAA");
        assertThat(target.getPgreat()).isEqualTo(1300);
        assertThat(target.getGreat()).isEqualTo(500);
        // ランプと BP は現行表記側のほうが良いのでそのまま
        assertThat(target.getClearType()).isEqualTo("HARD CLEAR");
        assertThat(target.getMissCount()).isEqualTo(20);
        // 累積値は大きいほう
        assertThat(target.getPlayCount()).isEqualTo(5);
        assertThat(target.getLastPlayedAt()).isEqualTo("2024-01-01 10:00");
    }

    @Test
    void ランプとBPはスコアと独立して良いほうを採る() {
        PastScore target = row(3100, "AAA", 1300, 500, "EASY CLEAR", 40, 2, "2023-11-01 10:00");
        PastScore source = row(2900, "AA", 1100, 700, "FULLCOMBO CLEAR", 0, 8, "2024-02-01 10:00");

        DataInitializer.mergeBestInto(target, source);

        // スコア群は現行表記側が上なので据え置き
        assertThat(target.getScore()).isEqualTo(3100);
        assertThat(target.getDjLevel()).isEqualTo("AAA");
        assertThat(target.getPgreat()).isEqualTo(1300);
        assertThat(target.getGreat()).isEqualTo(500);
        // ランプと BP は過去表記側が上
        assertThat(target.getClearType()).isEqualTo("FULLCOMBO CLEAR");
        assertThat(target.getMissCount()).isEqualTo(0);
        assertThat(target.getPlayCount()).isEqualTo(8);
        assertThat(target.getLastPlayedAt()).isEqualTo("2024-02-01 10:00");
    }

    @Test
    void 同点なら現行表記側を据え置く() {
        PastScore target = row(3000, "AA", 1200, 600, "HARD CLEAR", 20, 5, "2024-01-01 10:00");
        PastScore source = row(3000, "AA", 1250, 500, "HARD CLEAR", 20, 5, "2024-01-01 10:00");

        DataInitializer.mergeBestInto(target, source);

        assertThat(target.getPgreat()).isEqualTo(1200);
        assertThat(target.getGreat()).isEqualTo(600);
    }

    @Test
    void 未計測のBPやnull値は既存の値を壊さない() {
        PastScore target = row(3000, "AA", 1200, 600, "HARD CLEAR", null, null, null);
        PastScore source = row(2000, "A", 800, 400, "FAILED", 15, 1, "2023-10-01 10:00");

        DataInitializer.mergeBestInto(target, source);

        // BP は現行表記側が未計測なので過去表記側の値で埋まる
        assertThat(target.getMissCount()).isEqualTo(15);
        assertThat(target.getPlayCount()).isEqualTo(1);
        assertThat(target.getLastPlayedAt()).isEqualTo("2023-10-01 10:00");
        assertThat(target.getScore()).isEqualTo(3000);
        assertThat(target.getClearType()).isEqualTo("HARD CLEAR");

        // 逆向き: 過去表記側が null だらけなら現行表記側は一切変わらない
        PastScore target2 = row(3000, "AA", 1200, 600, "HARD CLEAR", 20, 5, "2024-01-01 10:00");
        PastScore source2 = new PastScore();
        DataInitializer.mergeBestInto(target2, source2);
        assertThat(target2.getScore()).isEqualTo(3000);
        assertThat(target2.getMissCount()).isEqualTo(20);
        assertThat(target2.getPlayCount()).isEqualTo(5);
        assertThat(target2.getLastPlayedAt()).isEqualTo("2024-01-01 10:00");
    }
}
