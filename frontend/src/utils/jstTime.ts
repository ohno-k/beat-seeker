/**
 * 【モジュールの役割】 画面に出す日時をすべて日本時間 (JST) で揃えるための共通ユーティリティ。
 *
 * 背景:
 *  - バックエンドは日時を `2026-09-20T15:30:00+09:00` のように **JST オフセット付き** で返す
 *    （`com.beatseeker.backend.util.JstTime` / `JacksonJstConfig`）。
 *  - それを `toLocaleString()` などで素のまま整形すると「閲覧端末のタイムゾーン」で表示される。
 *    海外在住のプレイヤーや、端末の TZ 設定がずれている環境では日本時間とは違う時刻が出てしまう。
 *
 * そこで、表示に関わる整形はすべてこのモジュール経由にして `timeZone: 'Asia/Tokyo'` を固定する。
 * 端末の TZ が何であっても、beat-seeker の画面に出る時刻は常に JST になる。
 *
 * 古いレスポンスやキャッシュがタイムゾーン無しの文字列 (`2026-09-20T15:30:00`) を返してきた場合は、
 * JST の壁時計時刻とみなして `+09:00` を補う（バックエンドが JST で返す前提に合わせる）。
 */

/** 表示に使うタイムゾーン。日本時間で固定する。 */
export const JST_TIME_ZONE = 'Asia/Tokyo';

/** 文字列末尾のタイムゾーン指定（`Z` または `+09:00` / `+0900`）を検出する。 */
const HAS_ZONE = /(Z|[+-]\d{2}:?\d{2})$/;

/** 日時として解釈できる入力。 */
export type JstTimeInput = string | number | Date | null | undefined;

/**
 * 【関数の役割】 サーバー日時文字列 / epoch ミリ秒 / Date を `Date` に変換する。
 *
 * タイムゾーン指定の無い文字列は JST（`+09:00`）として解釈する。
 *
 * @param value ISO 文字列・epoch ミリ秒・Date のいずれか
 * @returns 解釈できた `Date`。空・不正値なら null
 */
export function toJstDate(value: JstTimeInput): Date | null {
  if (value == null || value === '') return null;
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
  if (typeof value === 'number') {
    const d = new Date(value);
    return Number.isNaN(d.getTime()) ? null : d;
  }
  const s = value.trim();
  if (!s) return null;
  // "2026-09-20 15:30:00"（スペース区切り）も Safari で確実に読めるよう "T" に寄せる。
  const iso = s.includes(' ') && !s.includes('T') ? s.replace(' ', 'T') : s;
  const d = new Date(HAS_ZONE.test(iso) ? iso : `${iso}+09:00`);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** ロケール未指定時に使う既定ロケール。日本時間表記なので ja-JP。 */
const DEFAULT_LOCALE = 'ja-JP';

/**
 * 【関数の役割】 JST 固定で `Intl` 整形する。すべての表示用フォーマッタの土台。
 *
 * @param value 日時
 * @param options `Intl.DateTimeFormat` のオプション（`timeZone` は常に JST で上書きされる）
 * @param locale 表示ロケール（省略時 ja-JP）
 * @returns 整形済み文字列。日時として読めなければ空文字
 */
export function formatJst(
  value: JstTimeInput,
  options: Intl.DateTimeFormatOptions = {},
  locale: string = DEFAULT_LOCALE,
): string {
  const d = toJstDate(value);
  if (!d) return '';
  return new Intl.DateTimeFormat(locale, { ...options, timeZone: JST_TIME_ZONE }).format(d);
}

/**
 * 【関数の役割】 「2026/09/20」形式（JST）。
 *
 * @param value 日時
 * @param locale 表示ロケール（省略時 ja-JP）
 */
export function formatJstDate(value: JstTimeInput, locale: string = DEFAULT_LOCALE): string {
  return formatJst(value, { year: 'numeric', month: '2-digit', day: '2-digit' }, locale);
}

/**
 * 【関数の役割】 「2026/09/20 15:30」形式（JST・24 時間表記）。
 *
 * @param value 日時
 * @param locale 表示ロケール（省略時 ja-JP）
 */
export function formatJstDateTime(value: JstTimeInput, locale: string = DEFAULT_LOCALE): string {
  return formatJst(
    value,
    { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false },
    locale,
  );
}

/**
 * 【関数の役割】 「9/20 15:30」形式（JST・24 時間表記）。年を省いた短縮表示。
 *
 * @param value 日時
 * @param locale 表示ロケール（省略時 ja-JP）
 */
export function formatJstShortDateTime(value: JstTimeInput, locale: string = DEFAULT_LOCALE): string {
  return formatJst(
    value,
    { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false },
    locale,
  );
}

/**
 * 【関数の役割】 「15:30」形式（JST・24 時間表記）。
 *
 * @param value 日時
 * @param locale 表示ロケール（省略時 ja-JP）
 */
export function formatJstTime(value: JstTimeInput, locale: string = DEFAULT_LOCALE): string {
  return formatJst(value, { hour: '2-digit', minute: '2-digit', hour12: false }, locale);
}

/** {@link jstParts} が返す、JST に直した日時の各要素。 */
export interface JstParts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

/** `jstParts` 用のフォーマッタ。生成コストが高いので使い回す。 */
const PARTS_FORMATTER = new Intl.DateTimeFormat('en-US', {
  timeZone: JST_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

/**
 * 【関数の役割】 日時を JST の年・月・日・時・分・秒に分解する。
 *
 * `Date#getMonth()` / `getHours()` は端末のタイムゾーンで返ってくるため、
 * 「7/21 12:00」のような手組みの表示を作るときはこちらを使う。
 *
 * @param value 日時（省略時は現在時刻）
 * @returns JST での各要素。日時として読めなければ null
 */
export function jstParts(value: JstTimeInput = new Date()): JstParts | null {
  const d = toJstDate(value);
  if (!d) return null;
  const parts = PARTS_FORMATTER.formatToParts(d);
  const pick = (type: Intl.DateTimeFormatPartTypes) => Number(parts.find(p => p.type === type)?.value ?? NaN);
  const hour = pick('hour');
  return {
    year: pick('year'),
    month: pick('month'),
    day: pick('day'),
    // en-US の hour12:false は 24 時を返すことがあるので 0 に寄せる。
    hour: hour === 24 ? 0 : hour,
    minute: pick('minute'),
    second: pick('second'),
  };
}

/**
 * 【関数の役割】 現在の JST 日時の各要素。「今日は JST で何月何日か」を見たいとき用。
 */
export function nowJstParts(): JstParts {
  // 現在時刻は必ず解釈できるので null にはならない。
  return jstParts(new Date())!;
}

/**
 * 【関数の役割】 JST の「YYYY-MM-DD」文字列。日付でグルーピングするキーに使う。
 *
 * @param value 日時
 * @returns 例 `2026-09-20`。日時として読めなければ空文字
 */
export function toJstDateKey(value: JstTimeInput): string {
  const p = jstParts(value);
  if (!p) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${p.year}-${pad(p.month)}-${pad(p.day)}`;
}
