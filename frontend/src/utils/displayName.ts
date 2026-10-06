/**
 * 表示名（DJ ネーム）の長さを「表示幅」で測るユーティリティ。
 * 全角 1 文字 = 2、半角 1 文字 = 1 で数え、上限は全角 12 文字 / 半角 24 文字。
 * バックエンドの DisplayNames.java と同じ規則（既存の長い名前は API 出力時に「…」で省略される）。
 */

/** 表示名の上限幅（全角 12 文字相当）。 */
export const DISPLAY_NAME_MAX_WIDTH = 24;

/** 文字列の表示幅。ASCII・Latin-1・半角カナは 1、それ以外は 2。 */
export function displayNameWidth(s: string): number {
  let w = 0;
  for (const ch of s) {
    const cp = ch.codePointAt(0)!;
    w += cp <= 0xff || (cp >= 0xff61 && cp <= 0xff9f) ? 1 : 2;
  }
  return w;
}

/** 上限幅に収まっているか。 */
export function displayNameFits(s: string): boolean {
  return displayNameWidth(s) <= DISPLAY_NAME_MAX_WIDTH;
}
