package com.beatseeker.backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * textage のテスト用フィクスチャ（src/test/resources/textage/）の読み込み。
 *
 * pages/ は 2026-04 に取得した実ページ（chart_cache/html/ から UTF-8 にそろえて複製）。
 * titletbl_excerpt.js は実物の titletbl.js から行を間引いたもので、実物と同じ Shift_JIS。
 * expected_profiles.json は Python 版（tools/analyze_chart.py の profile_from_sp）に、
 * ページの JS を実行して取り出した sp[] / c1[] を渡して計算した値。
 */
final class TextageFixtures {

    private TextageFixtures() {}

    static String page(String path) throws IOException {
        return read("/textage/pages/" + path, StandardCharsets.UTF_8);
    }

    static String titleTable() throws IOException {
        return read("/textage/titletbl_excerpt.js", Charset.forName("windows-31j"));
    }

    static String read(String resource, Charset cs) throws IOException {
        try (InputStream in = TextageFixtures.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("not found: " + resource);
            return new String(in.readAllBytes(), cs);
        }
    }
}
