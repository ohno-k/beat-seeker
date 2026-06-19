/**
 * 【ユーティリティの役割】 大会 (team5) のチーム名・ジャンルの配色を一元管理する。
 *
 * 観戦ビュー / 管理画面 / TL ページ / プレイヤーページで共通利用する。チーム名は大会ごとに
 * 変わるため、配色ルールはこの 1 ファイルだけ書き換えれば全画面に反映される。
 *
 * Tailwind の purge を避けるため、返すクラスはすべて完全なリテラル文字列で保持する。
 */

/**
 * チーム名 → 文字色クラス。識別キーの部分一致 (大文字小文字無視) で判定する。
 *  テクノワールド→水色 / ADX MAMY→紺(青) / Fantasista→うすだいだい / G-STAGE→ピンク / CYGameWorld→オレンジ
 */
const TEAM_COLOR_RULES: { match: string; cls: string }[] = [
  { match: 'テクノ', cls: 'text-sky-500 dark:text-sky-300' },          // テクノワールド → 水色
  { match: 'mamy', cls: 'text-blue-800 dark:text-blue-300' },          // ADX MAMY → 紺 (青)
  { match: 'fantasista', cls: 'text-orange-400 dark:text-orange-200' }, // Fantasista → うすだいだい
  { match: 'g-stage', cls: 'text-pink-500 dark:text-pink-300' },       // G-STAGE → ピンク
  { match: 'cygame', cls: 'text-orange-600 dark:text-orange-400' },    // CYGameWorld → オレンジ
];
const DEFAULT_TEAM_CLS = 'text-slate-700 dark:text-slate-200';

/** チーム名文字列から色クラスを引く。該当なしは標準色。 */
export function teamColorClass(name?: string | null): string {
  if (!name) return DEFAULT_TEAM_CLS;
  const lower = name.toLowerCase();
  const rule = TEAM_COLOR_RULES.find(r => lower.includes(r.match.toLowerCase()));
  return rule ? rule.cls : DEFAULT_TEAM_CLS;
}

/**
 * 指定ジャンル → バッジ色クラス (bg + text)。観戦/TL/プレイヤーのバッジ表示用。
 *  NOTES→ピンク / PEAK→オレンジ / CHORD→黄緑 / CHARGE→紫 / SCRATCH→赤 / SOF-LAN→青 / INSANE→中立
 */
const GENRE_BADGE: Record<string, string> = {
  NOTES: 'bg-pink-100 text-pink-700 dark:bg-pink-900/40 dark:text-pink-300',
  PEAK: 'bg-orange-100 text-orange-700 dark:bg-orange-900/40 dark:text-orange-300',
  CHORD: 'bg-lime-100 text-lime-700 dark:bg-lime-900/40 dark:text-lime-300',
  CHARGE: 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300',
  SCRATCH: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300',
  'SOF-LAN': 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300',
  INSANE: 'bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300',
};
const DEFAULT_GENRE_BADGE = 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300';

/** ジャンルのバッジ色 (bg+text)。未指定/不明は中立のエメラルド。 */
export function genreBadgeClass(g?: string | null): string {
  return (g ? GENRE_BADGE[g] : undefined) ?? DEFAULT_GENRE_BADGE;
}

/** ジャンルの文字色のみ (インラインのジャンル名強調用)。 */
const GENRE_TEXT: Record<string, string> = {
  NOTES: 'text-pink-600 dark:text-pink-300',
  PEAK: 'text-orange-600 dark:text-orange-300',
  CHORD: 'text-lime-600 dark:text-lime-300',
  CHARGE: 'text-purple-600 dark:text-purple-300',
  SCRATCH: 'text-red-600 dark:text-red-300',
  'SOF-LAN': 'text-blue-600 dark:text-blue-300',
  INSANE: 'text-slate-600 dark:text-slate-300',
};
const DEFAULT_GENRE_TEXT = 'text-emerald-600 dark:text-emerald-300';

/** ジャンルの文字色クラス。 */
export function genreTextClass(g?: string | null): string {
  return (g ? GENRE_TEXT[g] : undefined) ?? DEFAULT_GENRE_TEXT;
}

/**
 * 指定ジャンル → セレクタ用の文字色 + 枠線色クラス (管理画面のジャンルセレクタ用)。
 * 未指定時は標準の枠線色。
 */
const GENRE_SELECT_COLOR: Record<string, string> = {
  NOTES: 'text-pink-600 dark:text-pink-300 border-pink-300 dark:border-pink-700',
  PEAK: 'text-orange-600 dark:text-orange-300 border-orange-300 dark:border-orange-700',
  CHORD: 'text-lime-600 dark:text-lime-300 border-lime-300 dark:border-lime-700',
  CHARGE: 'text-purple-600 dark:text-purple-300 border-purple-300 dark:border-purple-700',
  SCRATCH: 'text-red-600 dark:text-red-300 border-red-300 dark:border-red-700',
  'SOF-LAN': 'text-blue-600 dark:text-blue-300 border-blue-300 dark:border-blue-700',
  INSANE: 'text-slate-600 dark:text-slate-300 border-slate-400 dark:border-slate-500',
};

/** ジャンルセレクタの色クラス (文字 + 枠線 + 太字)。未指定は標準枠線色。 */
export function genreSelectClass(g?: string | null): string {
  return (g ? GENRE_SELECT_COLOR[g] : undefined)
    ? `${GENRE_SELECT_COLOR[g as string]} font-bold`
    : 'border-slate-300 dark:border-slate-600';
}
