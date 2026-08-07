/**
 * Composable 層で共有される**定数モジュール**。
 *
 * ここに集約しているのは、各 composable（useAuth / useI18n / useRateTierVisibility など）から
 * 同じ値を import させ、キー名や URL 文字列の重複定義を防ぐため。
 */

/**
 * localStorage に JWT を保存する際のキー。
 *
 * 全 composable（useAuth / useI18n / useRateTierVisibility 等）が
 * この定数を参照するので、キー名変更はこの 1 箇所だけで済む。
 */
export const TOKEN_KEY = 'beat-seeker-token';

/**
 * バックエンド API のベース URL。
 *
 * Vite の環境変数 `VITE_API_BASE` があればそれを優先し、
 * 未設定ならローカル開発用の `http://localhost:8080` をフォールバックとする。
 */
export const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

/**
 * 段位プルダウンの選択肢（七級〜皆伝、19 段階）。
 *
 * `value` はバックエンドが期待する日本語段位、`labelKey` は i18n 表示用キー。
 * 登録モーダル / プロフィール編集モーダル等から参照される。
 */
export const DAN_RANK_OPTIONS: ReadonlyArray<{ value: string; labelKey: string }> = [
  { value: '七級', labelKey: 'dan.7kyu' },
  { value: '六級', labelKey: 'dan.6kyu' },
  { value: '五級', labelKey: 'dan.5kyu' },
  { value: '四級', labelKey: 'dan.4kyu' },
  { value: '三級', labelKey: 'dan.3kyu' },
  { value: '二級', labelKey: 'dan.2kyu' },
  { value: '一級', labelKey: 'dan.1kyu' },
  { value: '初段', labelKey: 'dan.shodan' },
  { value: '二段', labelKey: 'dan.2dan' },
  { value: '三段', labelKey: 'dan.3dan' },
  { value: '四段', labelKey: 'dan.4dan' },
  { value: '五段', labelKey: 'dan.5dan' },
  { value: '六段', labelKey: 'dan.6dan' },
  { value: '七段', labelKey: 'dan.7dan' },
  { value: '八段', labelKey: 'dan.8dan' },
  { value: '九段', labelKey: 'dan.9dan' },
  { value: '十段', labelKey: 'dan.10dan' },
  { value: '中伝', labelKey: 'dan.chuden' },
  { value: '皆伝', labelKey: 'dan.kaiden' },
] as const;

/** アリーナランクの選択肢（A1〜D5 の 20 段階）。 */
export const ARENA_RANKS: ReadonlyArray<string> = [
  'A1', 'A2', 'A3', 'A4', 'A5',
  'B1', 'B2', 'B3', 'B4', 'B5',
  'C1', 'C2', 'C3', 'C4', 'C5',
  'D1', 'D2', 'D3', 'D4', 'D5',
] as const;

/**
 * 譜面難易度（譜面種別）。
 *
 * IIDX のチャートカラム名と一致しており、フィルター UI / グラフラベル等で参照される。
 * `t('table.difficulty.<lower>')` で多言語ラベルに解決できる。
 */
export const DIFFICULTIES: ReadonlyArray<'NORMAL' | 'HYPER' | 'ANOTHER' | 'LEGGENDARIA'> = [
  'NORMAL', 'HYPER', 'ANOTHER', 'LEGGENDARIA',
] as const;

/**
 * DJ LEVEL（クリア時の判定ランク）。F が最低、AAA が最高。
 *
 * フィルター UI やランキングで参照される。`t('table.rank.<lower>')` で多言語ラベルに解決できる。
 */
export const DJ_LEVELS: ReadonlyArray<'AAA' | 'AA' | 'A' | 'B' | 'C' | 'D' | 'E' | 'F'> = [
  'AAA', 'AA', 'A', 'B', 'C', 'D', 'E', 'F',
] as const;

/**
 * クリアランプの強弱を数値化したテーブル。大きいほど上位ランプ。
 *
 * 「ランプが上がったか」の判定や並び替えの順序比較に使う。
 * バックエンドの `ScoreController.getClearTypeRank` / `LeagueChartNotation.clearTypeRank` と同一の値域。
 */
export const CLEAR_TYPE_RANK: Record<string, number> = {
  'FULLCOMBO CLEAR': 7,
  'EX HARD CLEAR': 6,
  'HARD CLEAR': 5,
  'CLEAR': 4,
  'EASY CLEAR': 3,
  'ASSIST CLEAR': 2,
  'FAILED': 1,
  'NO PLAY': 0,
  '---': 0,
};
