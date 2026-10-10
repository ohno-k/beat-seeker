/**
 * 【ファイルの役割】 プレー画面の曲名・アーティストの OCR 結果を、楽曲データの譜面に照合する。
 *
 * OCR は 1 文字単位の誤読（l↔I、0↔O、記号の欠落）が多いので、完全一致ではなく
 * 文字の 2-gram の重なり（Dice 係数）で近さを測る。候補は呼び出し側で
 * 「プレー画面の札から読んだ難易度の譜面」に絞っておく。
 */
import { foldTitleVariants } from './songTitleMatch';

/** 比較用に畳み込む（大文字小文字・全半角・記号・空白を無視）。 */
function fold(s: string): string {
  return foldTitleVariants(s)[0] ?? '';
}

function grams(s: string): Map<string, number> {
  const m = new Map<string, number>();
  const n = s.length < 2 ? 1 : 2;
  for (let i = 0; i + n <= s.length; i++) {
    const g = s.slice(i, i + n);
    m.set(g, (m.get(g) ?? 0) + 1);
  }
  return m;
}

/** 【関数の役割】 畳み込み済み文字列どうしの Dice 係数（0〜1）。 */
export function diceSimilarity(a: string, b: string): number {
  if (!a || !b) return 0;
  if (a === b) return 1;
  const ga = grams(a);
  const gb = grams(b);
  let inter = 0;
  let na = 0;
  let nb = 0;
  for (const v of ga.values()) na += v;
  for (const v of gb.values()) nb += v;
  for (const [g, v] of ga) inter += Math.min(v, gb.get(g) ?? 0);
  return (2 * inter) / (na + nb);
}

export interface OcrMatch<T> {
  chart: T;
  score: number;
}

/** 採用する最低スコアと、2 位との最低差。 */
const MIN_SCORE = 0.55;
const MIN_MARGIN = 0.08;
const CONFIDENT_SCORE = 0.85;

/**
 * 【関数の役割】 OCR の曲名・アーティストに最も近い譜面を返す。確信が持てなければ null。
 *
 * スコア = 曲名の近さ × 0.7 + アーティストの近さ × 0.3（アーティストが読めなかったときは曲名だけ）。
 * 1 位が {@link MIN_SCORE} 未満、または 2 位との差が {@link MIN_MARGIN} 未満で
 * {@link CONFIDENT_SCORE} にも届かない場合は、取り違えを避けて null を返す。
 */
export function matchChartByOcr<T extends { title: string; artist: string }>(
  charts: readonly T[],
  ocrTitle: string,
  ocrArtist: string,
): { best: OcrMatch<T> | null; ranked: OcrMatch<T>[] } {
  const qt = fold(ocrTitle);
  const qa = fold(ocrArtist);
  if (!qt) return { best: null, ranked: [] };
  const ranked = charts
    .map(chart => {
      const ts = diceSimilarity(qt, fold(chart.title));
      const as = qa ? diceSimilarity(qa, fold(chart.artist)) : 0;
      return { chart, score: qa ? ts * 0.7 + as * 0.3 : ts };
    })
    .sort((x, y) => y.score - x.score)
    .slice(0, 5);
  const [first, second] = ranked;
  if (!first || first.score < MIN_SCORE) return { best: null, ranked };
  const margin = first.score - (second?.score ?? 0);
  if (margin < MIN_MARGIN && first.score < CONFIDENT_SCORE) return { best: null, ranked };
  return { best: first, ranked };
}
