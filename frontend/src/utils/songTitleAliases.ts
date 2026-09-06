/**
 * 【モジュールの役割】 作品をまたいで公式 CSV 上の表記が変わった曲名を、
 * 現行作の CSV および曲マスタ（song_data.json）の表記へ寄せる対応表。
 *
 * 例: 31 EPOLIS 期の CSV は "VØID"、33 Sparkle Shower の CSV と曲マスタは "VOID"。
 * 現行スコアと過去作スコアは「曲名 + 難易度名」で突き合わせる（usePastScores の chartKey）ため、
 * 表記が違うと同一譜面が別譜面として扱われ、歴代ベストから漏れる。
 *
 * 方針:
 *  - 曲マスタ側（現行表記）を正とし、過去表記 → 現行表記 の片方向でしか変換しない。
 *  - 載せるのは「同一曲と断定できる、文字単位の表記差」だけ。songTitleMatch.ts のような
 *    曖昧な畳み込みは別曲を同一視し得るので、スコアの取り込みでは使わない。
 *  - 適用箇所は CSV パース直後（csvParser.ts）と、サーバから受け取った過去作スコア（usePastScores.ts）。
 *
 * バックエンド側の対応表は backend/.../service/SongTitleAliases.java。追加するときは両方を更新すること。
 */

/** 過去表記 → 現行表記。キーは公式 CSV に実際に現れた文字列そのもの。 */
export const SONG_TITLE_ALIASES: ReadonlyMap<string, string> = new Map<string, string>([
  // "VØID"（U+00D8 LATIN CAPITAL LETTER O WITH STROKE）は 31 EPOLIS 期の CSV 表記。
  ['VØID', 'VOID'],
]);

/**
 * 【関数の役割】 曲名を現行表記に寄せる。対応表に無ければ入力をそのまま返す。
 *
 * @param title CSV やサーバ応答に含まれる曲名
 * @returns 現行表記の曲名
 */
export const canonicalSongTitle = (title: string): string => SONG_TITLE_ALIASES.get(title) ?? title;
