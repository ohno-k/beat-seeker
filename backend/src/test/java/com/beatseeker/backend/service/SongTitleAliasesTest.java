package com.beatseeker.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 作品をまたいで表記が変わった曲名が、現行表記へ片方向に寄せられることを検証する。
 *
 * 31 EPOLIS 期の公式 CSV に現れる "VØID"（U+00D8）は、現行作の CSV と曲マスタでは "VOID"。
 * 過去作スコアと現行スコアは曲名で突き合わせるため、この変換が無いと歴代ベストから漏れる。
 */
class SongTitleAliasesTest {

    @Test
    void 過去作表記のVØIDは現行表記のVOIDに寄せる() {
        assertThat(SongTitleAliases.canonical("VØID")).isEqualTo("VOID");
        // ソースの文字コードに依存しない形でも確認する（U+00D8 LATIN CAPITAL LETTER O WITH STROKE）。
        assertThat(SongTitleAliases.canonical("VØID")).isEqualTo("VOID");
    }

    @Test
    void 現行表記はそのまま返す() {
        assertThat(SongTitleAliases.canonical("VOID")).isEqualTo("VOID");
    }

    @Test
    void 対応表に無い曲名はそのまま返す() {
        assertThat(SongTitleAliases.canonical("冥")).isEqualTo("冥");
        assertThat(SongTitleAliases.canonical("")).isEmpty();
    }

    @Test
    void nullはnullのまま() {
        assertThat(SongTitleAliases.canonical(null)).isNull();
    }

    @Test
    void 対応表の値は必ず現行表記でありキーと異なる() {
        // 「現行表記 → 別表記」の逆向きエントリを誤って足すと、正しい CSV まで書き換えてしまう。
        SongTitleAliases.all().forEach((legacy, canonical) -> {
            assertThat(canonical).isNotEqualTo(legacy);
            assertThat(SongTitleAliases.canonical(canonical))
                    .as("現行表記 '%s' はそれ以上変換されない", canonical)
                    .isEqualTo(canonical);
        });
    }
}
