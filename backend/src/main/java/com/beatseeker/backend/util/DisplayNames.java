package com.beatseeker.backend.util;

/**
 * 【クラスの役割】 表示名（DJ ネーム）の長さを「表示幅」で測り、上限を超えたら省略するユーティリティ。
 *
 * <p>表示幅は全角 1 文字 = 2、半角 1 文字 = 1 で数える（全角 12 文字 / 半角 24 文字まで）。
 * 2026-10 時点で本番には 80 文字超の表示名があり、ランキングやリーグの表が崩れていたため導入した。
 * 名前の入力自体は制限せず（短くするよう求めない）、API の出力時にだけ省略する
 * ({@code DisplayNameJacksonConfig})。
 */
public final class DisplayNames {

    /** 表示名の上限幅（全角 12 文字相当）。 */
    public static final int MAX_WIDTH = 24;

    private static final String ELLIPSIS = "…";

    private DisplayNames() {
    }

    /** 1 文字 (コードポイント) の表示幅。ASCII・Latin-1・半角カナは 1、それ以外は 2。 */
    private static int width(int cp) {
        if (cp <= 0xFF) return 1;
        if (cp >= 0xFF61 && cp <= 0xFF9F) return 1;
        return 2;
    }

    /** 文字列全体の表示幅。null は 0。 */
    public static int width(String s) {
        if (s == null) return 0;
        return s.codePoints().map(DisplayNames::width).sum();
    }

    /** 上限幅に収まっているか。 */
    public static boolean fits(String s) {
        return width(s) <= MAX_WIDTH;
    }

    /**
     * 上限幅を超える名前を、末尾に「…」を付けて上限幅に収まるよう切り詰める。
     * 収まっている名前（null 含む）はそのまま返す。
     */
    public static String truncate(String s) {
        if (s == null || fits(s)) return s;
        int budget = MAX_WIDTH - width(ELLIPSIS.codePointAt(0));
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int cp : s.codePoints().toArray()) {
            int w = width(cp);
            if (used + w > budget) break;
            sb.appendCodePoint(cp);
            used += w;
        }
        return sb.toString().stripTrailing() + ELLIPSIS;
    }
}
