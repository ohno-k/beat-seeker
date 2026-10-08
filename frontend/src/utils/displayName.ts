/**
 * 【ユーティリティの役割】 表示名（DJ ネーム）を表示幅で測り、上限を超えたら末尾に「…」を付けて省略する。
 *
 * サーバーの DisplayNames.truncate と同じ規則（全角 1 文字 = 2・半角 1 文字 = 1、上限 24 = 全角 12 文字）。
 * 通常の API はサーバーが出力時に省略するが、管理画面の API（/api/admin/**）は元の名前を返すので、
 * 管理画面で一覧に名前を並べるときはこちらで省略する。
 */

/** 表示名の上限幅（全角 12 文字相当）。 */
const MAX_WIDTH = 24;
const ELLIPSIS = '…';

/** 1 文字（コードポイント）の表示幅。ASCII・Latin-1・半角カナは 1、それ以外は 2。 */
function charWidth(cp: number): number {
  if (cp <= 0xff) return 1;
  if (cp >= 0xff61 && cp <= 0xff9f) return 1;
  return 2;
}

/** 上限幅を超える名前を「…」付きで上限幅に収める。収まっている名前はそのまま返す。 */
export function truncateDisplayName(name: string): string {
  const chars = [...name];
  const total = chars.reduce((w, c) => w + charWidth(c.codePointAt(0)!), 0);
  if (total <= MAX_WIDTH) return name;
  const budget = MAX_WIDTH - charWidth(ELLIPSIS.codePointAt(0)!);
  let out = '';
  let used = 0;
  for (const c of chars) {
    const w = charWidth(c.codePointAt(0)!);
    if (used + w > budget) break;
    out += c;
    used += w;
  }
  return out.trimEnd() + ELLIPSIS;
}
