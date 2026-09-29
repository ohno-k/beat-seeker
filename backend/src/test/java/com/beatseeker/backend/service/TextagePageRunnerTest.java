package com.beatseeker.backend.service;

import com.beatseeker.backend.service.TextagePageRunner.Difficulty;
import com.beatseeker.backend.service.TextagePageRunner.PageChart;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【テストの目的】 textage のページ JS を難易度フラグ付きで実行し、正しい分岐の譜面データが取れることを検証する。
 * 期待するノーツ数は公式（楽曲マスタ）の値。
 */
class TextagePageRunnerTest {

    private final TextagePageRunner runner = new TextagePageRunner();

    @Test
    void oldPage_normalIsTheLightOverrideInsideHyperBlock() throws Exception {
        // 1st style のページは if(k){ HYPER; if(a){ANOTHER} if(l){NORMAL の上書き} }else{ DP } の形。
        // 旧 Python 版は NORMAL を else 側（DP）から取っていた。
        String script = TextagePageRunner.extractScript(TextageFixtures.page("1/22dunk.html"));

        PageChart normal = runner.run(script, Difficulty.NORMAL);
        PageChart hyper = runner.run(script, Difficulty.HYPER);
        PageChart another = runner.run(script, Difficulty.ANOTHER);

        assertThat(normal.notes()).isEqualTo(265);
        assertThat(hyper.notes()).isEqualTo(323);
        assertThat(another.notes()).isEqualTo(329);
        // CN の無い譜面は sp[] をデコードしたノーツ数がページの宣言と一致する
        assertThat(decodedNotes(normal)).isEqualTo(265);
        assertThat(decodedNotes(hyper)).isEqualTo(323);
        assertThat(decodedNotes(another)).isEqualTo(329);
        // sp[4]=sp[3] のような参照も JS がそのまま解決する
        assertThat(hyper.sp().get(4)).isEqualTo(hyper.sp().get(3));
        assertThat(hyper.ln()).containsEntry(39, 24);
    }

    @Test
    void leggendariaUsesKuroFlagAndCarriesChargeNotes() throws Exception {
        String script = TextagePageRunner.extractScript(TextageFixtures.page("19/yheadjoe.html"));

        PageChart another = runner.run(script, Difficulty.ANOTHER);
        PageChart legg = runner.run(script, Difficulty.LEGGENDARIA);

        assertThat(another.notes()).isEqualTo(1152);
        assertThat(legg.notes()).isEqualTo(1596);
        assertThat(legg.c1()).isNotEmpty();
    }

    @Test
    void missingDifficultyFallsBackToAnotherChart_soNotesDoNotMatch() throws Exception {
        // 旧来の LEGGENDARIA 専用ページ（†）には NORMAL/HYPER が無く、ページの notes も宣言されない
        String script = TextagePageRunner.extractScript(TextageFixtures.page("20/_decohel.html"));

        assertThat(runner.run(script, Difficulty.NORMAL).notes()).isZero();
        assertThat(runner.run(script, Difficulty.LEGGENDARIA).notes()).isEqualTo(1713);
    }

    @Test
    void scriptErrorsAndEndlessLoopsAreReportedNotHung() {
        assertThatThrownBy(() -> runner.run("undefinedFunction();", Difficulty.ANOTHER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> runner.run("while(true){}", Difficulty.ANOTHER))
                .isInstanceOf(IllegalStateException.class);
        // Java のクラスには触れない
        assertThatThrownBy(() -> runner.run("java.lang.System.exit(1);", Difficulty.ANOTHER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void extractScriptSkipsExternalScripts() {
        String html = "<script type=\"text/javascript\" src=\"../bms2jsh.js\"></script>"
                + "<script><!--\nnotes=5;sp[3]=\"#R1\";\n//--></script>";
        String script = TextagePageRunner.extractScript(html);
        PageChart c = runner.run(script, Difficulty.ANOTHER);
        assertThat(c.notes()).isEqualTo(5);
        assertThat(c.sp()).containsEntry(3, "#R1");
    }

    private static int decodedNotes(PageChart c) {
        int n = 0;
        for (var e : c.sp().entrySet()) {
            n += ChartTendencyAnalyzer.decodeMeasure(e.getValue(), c.ln().getOrDefault(e.getKey(), c.lndef())).size();
        }
        return n;
    }
}
