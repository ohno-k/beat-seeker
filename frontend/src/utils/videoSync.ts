/**
 * videoSync.ts
 *
 * 【役割】 譜面再生と原曲動画のずれ合わせの計算。
 *
 * ずれ（offset）の定義: 動画の再生位置（秒）= 譜面の時刻（秒、最初の小節の頭が 0）+ offset。
 */

/** 「リズムに合わせて叩く」で、叩いた時刻と最寄りのノーツの差として数える範囲（秒）。これより外れた打鍵は捨てる */
export const TAP_WINDOW = 0.15;
/** 補正を出すのに必要な打鍵数 */
export const MIN_TAPS = 6;

/** 昇順の配列で x に最も近い値。 */
export function nearest(sorted: ArrayLike<number>, x: number): number | null {
  if (sorted.length === 0) return null;
  let lo = 0;
  let hi = sorted.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (sorted[mid] < x) lo = mid + 1;
    else hi = mid;
  }
  const a = lo > 0 ? sorted[lo - 1] : null;
  const b = lo < sorted.length ? sorted[lo] : null;
  if (a == null) return b;
  if (b == null) return a;
  return x - a <= b - x ? a : b;
}

/**
 * 打鍵の差（叩いた譜面上の時刻 − 最寄りのノーツの時刻）。範囲外は null。
 * 今のずれが本当のずれより d 秒小さいと、音が鳴った瞬間の譜面の時刻はノーツより d 秒先にあるので差は +d になる
 * → 新しいずれ = 今のずれ + 差の中央値。
 */
export function tapResidual(noteTimes: ArrayLike<number>, tapChartTime: number): number | null {
  const n = nearest(noteTimes, tapChartTime);
  if (n == null) return null;
  const r = tapChartTime - n;
  return Math.abs(r) <= TAP_WINDOW ? r : null;
}

/** 中央値（空なら null）。 */
export function median(xs: readonly number[]): number | null {
  if (xs.length === 0) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}
