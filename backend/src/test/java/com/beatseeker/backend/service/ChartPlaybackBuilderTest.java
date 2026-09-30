package com.beatseeker.backend.service;

import com.beatseeker.backend.service.ChartPlaybackBuilder.Playback;
import com.beatseeker.backend.service.TextagePageRunner.Difficulty;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 譜面再生用のデータ（{@link ChartPlaybackBuilder}）が textage の表示（bms2jsh.js）と同じノーツを
 * 同じ位置に並べることを検証する。
 *
 * キャッシュ済みの全ページ（chart_cache/html、9,381 譜面）で、通常ノーツ + CN の先頭・終端の数がページの宣言するノーツ数と
 * 一致したのは 9,364 譜面（残りは textage 側が同じ小節を 2 回描くなどの表示上の都合）。
 */
class ChartPlaybackBuilderTest {

    private final TextagePageRunner runner = new TextagePageRunner();

    @Test
    void objectsMatchDeclaredNotes_andMeasuresFollowLn() throws Exception {
        String script = TextagePageRunner.extractScript(TextageFixtures.page("1/22dunk.html"));
        Playback p = ChartPlaybackBuilder.build(runner.runWithObjects(script, Difficulty.ANOTHER));

        assertThat(p.objectCount()).isEqualTo(329);
        assertThat(p.firstMeasure()).isEqualTo(1);
        assertThat(p.measures()).hasSize(39).startsWith(0, 384, 768);
        assertThat(p.endTick()).isEqualTo(38 * 384 + 24); // 最終小節は ln[39]=24
        assertThat(p.bpmChanges()).hasSize(1);
        assertThat(p.bpmChanges().get(0)).containsExactly(0, 135);
        // tick の昇順で、鍵盤は 0〜7
        for (int i = 1; i < p.notes().size(); i++) {
            assertThat(p.notes().get(i)[0]).isGreaterThanOrEqualTo(p.notes().get(i - 1)[0]);
            assertThat(p.notes().get(i)[1]).isBetween(0, 7);
        }
    }

    @Test
    void chargeNotesCountHeadAndTail() throws Exception {
        String script = TextagePageRunner.extractScript(TextageFixtures.page("19/yheadjoe.html"));
        Playback p = ChartPlaybackBuilder.build(runner.runWithObjects(script, Difficulty.LEGGENDARIA));

        assertThat(p.charges()).isNotEmpty();
        assertThat(p.objectCount()).isEqualTo(1596);
        for (int[] c : p.charges()) assertThat(c[1]).isGreaterThanOrEqualTo(c[0]);
    }

    @Test
    void decodesLikeBms2jsh_afterScratchModeMarker() {
        // "-" の後の "C" は 1 文字しか読み進めない（Python 版の移植は 2 文字進めて "R" を読み飛ばしていた）。
        // C: 2 分ごと（0, 192）、R: 4 分ごと（0, 96, 192, 288）の皿。bms2jsh.js は重なりもそのまま描く
        Playback p = build("gap--;measure=1;sp[1]=\"#-CR\";");

        assertThat(p.notes()).extracting(n -> n[0]).containsExactly(0, 0, 96, 192, 192, 288);
        assertThat(p.notes()).allSatisfy(n -> assertThat(n[1]).isZero());
    }

    @Test
    void chargeWithOmittedLengthUsesDefault_andFlagsMarkHeadAndTail() {
        // [キー, 位置, 長さ(省略=30), フラグ(1=先頭, 2=終端)]。小節をまたぐ CN は小節ごとに区切られる
        Playback p = build("gap--;measure=2;c1[1]=[[5,96,96,1]];c1[2]=[[5,0,,2],[13,0]];");

        assertThat(p.charges()).hasSize(4);
        assertThat(p.charges().get(0)).containsExactly(96 * 3, 96 * 3 + 288, 5, 1);
        assertThat(p.charges()).anySatisfy(c -> assertThat(c).containsExactly(384, 384 + 90, 5, 2));
        // 13 は 3 鍵と 1 鍵の同時 CN
        assertThat(p.charges()).anySatisfy(c -> assertThat(c).containsExactly(384, 384 + 90, 3, 3));
        assertThat(p.charges()).anySatisfy(c -> assertThat(c).containsExactly(384, 384 + 90, 1, 3));
        assertThat(p.objectCount()).isEqualTo(1 + 1 + 2 + 2);
    }

    @Test
    void soflanTakesInitialBpmFromTc_andPlacesChangesInTicks() {
        // BPM 表記がソフラン（"100〜200"）なら曲頭は tc[] の指定。"20096" = BPM 200 を位置 96（×3 = 288 tick）で
        Playback p = build("gap--;measure=3;bpm=\"100〜200\";tc[1]=[\"1000\"];tc[2]=[\"20096\"];tc[3]=[\" 900\"];"
                + "ln[2]=576;sp[1]=\"#R1\";");

        assertThat(p.bpmChanges()).hasSize(3);
        assertThat(p.bpmChanges().get(0)).containsExactly(0, 100);
        assertThat(p.bpmChanges().get(1)).containsExactly(384 + 288, 200);
        assertThat(p.bpmChanges().get(2)).containsExactly(384 + 576, 90); // 先頭の空白は BPM 2 桁の桁埋め
        assertThat(p.endTick()).isEqualTo(384 + 576 + 384);
    }

    @Test
    void measuresBeforeOneMinusGapAreKept() {
        // gap が負のページ（前奏が長い曲）は 1 - gap より前の小節も描かれる
        Playback p = build("gap=-2;measure=3;sp[1]=\"#R1\";sp[3]=\"#R1\";");

        assertThat(p.firstMeasure()).isEqualTo(-1);
        assertThat(p.measures()).containsExactly(0, 384, 768);
        assertThat(p.notes()).hasSize(8);
    }

    private Playback build(String script) {
        return ChartPlaybackBuilder.build(runner.runWithObjects(script, Difficulty.ANOTHER));
    }
}
