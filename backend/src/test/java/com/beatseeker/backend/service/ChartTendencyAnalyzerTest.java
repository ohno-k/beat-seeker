package com.beatseeker.backend.service;

import com.beatseeker.backend.service.TextagePageRunner.Difficulty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 譜面傾向の計算（{@link ChartTendencyAnalyzer}）が Python 版（tools/analyze_chart.py）と同じ値を出すことを検証する。
 * 期待値は同じ sp[] / c1[] を Python 版に渡して計算したもの（expected_profiles.json）。
 * 2026-09 にキャッシュ済みの全 9,565 譜面（chart_cache/html/ × 5 難易度）でも一致を確認している。
 */
class ChartTendencyAnalyzerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
            "1/22dunk.html, HYPER, 1/22dunk#k",          // CN なし・小節長の上書きあり
            "19/yheadjoe.html, LEGGENDARIA, 19/yheadjoe#l", // CN あり
            "20/_decoher.html, ANOTHER, 20/_decoher#a",
    })
    void matchesPythonImplementation(String page, Difficulty difficulty, String key) throws Exception {
        JsonNode expected = MAPPER.readTree(TextageFixtures.read("/textage/expected_profiles.json", StandardCharsets.UTF_8)).get(key);
        TextagePageRunner.PageChart chart = new TextagePageRunner()
                .run(TextagePageRunner.extractScript(TextageFixtures.page(page)), difficulty);

        JsonNode actual = MAPPER.valueToTree(ChartTendencyAnalyzer.profile(chart, expected.get("bpm").asText()));

        assertThat(actual).isEqualTo(expected.get("profile"));
    }

    @Test
    void decodesRunLengthAndHexFormats() {
        // "#R1": 4 分ごとに鍵盤 1（384 分割で 4 個）
        assertThat(ChartTendencyAnalyzer.decodeMeasure("#R1", 384)).hasSize(4)
                .allSatisfy(n -> assertThat(n.key()).isEqualTo(1));
        // 16 進形式: 2 文字ごとにビット列。"03" = 皿 + 鍵盤 1、"00" = 休符
        assertThat(ChartTendencyAnalyzer.decodeMeasure("0300", 384))
                .containsExactly(new ChartTendencyAnalyzer.Note(0, 0), new ChartTendencyAnalyzer.Note(0, 1));
    }

    @Test
    void roundsLikePython() {
        assertThat(ChartTendencyAnalyzer.round(0.125, 2)).isEqualTo(0.12);  // 偶数丸め
        assertThat(ChartTendencyAnalyzer.round(2.675, 2)).isEqualTo(2.67);  // 2 進で 2.67499… なので切り捨て
        assertThat(ChartTendencyAnalyzer.round(1.25, 1)).isEqualTo(1.2);
    }
}
