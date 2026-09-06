package com.beatseeker.backend.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【ユーティリティの役割】 作品をまたいで公式 CSV 上の表記が変わった曲名を、
 * 現行作の CSV および曲マスタ（{@code song_data.json}）の表記へ寄せる対応表。
 *
 * 現実世界の概念: IIDX の公式スコア CSV は作品ごとに出力され、同じ曲でも作品によって
 * 「タイトル」列の文字が変わることがある（例: 31 EPOLIS 期の CSV は "VØID"、
 * 33 Sparkle Shower の CSV と曲マスタは "VOID"）。beat-seeker は現行スコア（{@code scores}）と
 * 過去作スコア（{@code past_scores}）を「曲名 + 難易度名」で突き合わせるため、表記が違うと
 * 同一譜面が別の譜面として扱われ、歴代ベスト・練習メニュー・リーグの歴代参照から漏れる。
 *
 * 方針:
 *  - 曲マスタ側（現行表記）を正とし、過去表記 → 現行表記 の片方向でしか変換しない。
 *  - ここに載せるのは「同一曲と断定できる、文字単位の表記差」だけ。発音区別符号の除去のような
 *    曖昧な畳み込みは別曲を同一視する危険があるので行わない。
 *  - 取り込み時（{@code ScoreController#uploadScores} / {@code PastScoreController#uploadPastScores}）と
 *    起動時の既存行是正（{@code DataInitializer}）の両方から参照する。
 *
 * フロント側の対応表は {@code frontend/src/utils/songTitleAliases.ts}。追加するときは両方を更新すること。
 */
public final class SongTitleAliases {

    /** 過去表記 → 現行表記。キーは公式 CSV に実際に現れた文字列そのもの。 */
    private static final Map<String, String> ALIASES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // "VØID"（U+00D8 LATIN CAPITAL LETTER O WITH STROKE）は 31 EPOLIS 期の CSV 表記。
        // 現行作の CSV と曲マスタは "VOID"。ソースの文字コード事故を避けるため Unicode エスケープで書く。
        m.put("VØID", "VOID");
        ALIASES = Collections.unmodifiableMap(m);
    }

    private SongTitleAliases() {
        // ユーティリティクラスのためインスタンス化しない
    }

    /**
     * 【メソッドの役割】 曲名を現行表記に寄せる。
     *
     * @param title CSV から届いた曲名（null 可）
     * @return 対応表に載っていれば現行表記、それ以外は入力そのまま（null は null）
     */
    public static String canonical(String title) {
        if (title == null) return null;
        String mapped = ALIASES.get(title);
        return mapped != null ? mapped : title;
    }

    /**
     * 【メソッドの役割】 対応表全体（過去表記 → 現行表記）を返す。
     * 起動時に既存行を是正する {@code DataInitializer} が走査に使う。
     *
     * @return 変更不可の Map
     */
    public static Map<String, String> all() {
        return ALIASES;
    }
}
