/**
 * 【ユーティリティの役割】 単曲 BPI（beatmania Power Indicator）の計算。
 *
 * 本家 BPIManager2 の `src/lib/bpi/index.ts`（BpiCalculator）の定義に準拠する。
 * 必要な入力は「自分の EX スコア・理論値(notes*2)・皆伝平均(avg)・世界記録(wr)・補正係数(coef)」で、
 * これらは song_data.json（= useGameData の songDataBody）の各譜面エントリに含まれている。
 *
 * 定義:
 *  - pgf(j, m) = 1 + (j/m - 0.5) / (1 - j/m)   （j=スコア, m=理論値。j==m のときは特異点回避で m*0.8）
 *  - BPI = ±100 * |ln(pgf(S)/pgf(avg)) / ln(pgf(wr)/pgf(avg))| ^ coef
 *      符号: スコア >= avg なら +、未満なら -
 *      係数: coef（未指定 / 0以下は既定 1.175）
 *      下限: -15 でクランプ
 */

/** 補正係数の既定値（song_data に coef が無い譜面はこれを使う）。 */
const DEFAULT_POW_COEF = 1.175;

/**
 * pgf（達成度関数）。スコア j・理論値 m から内部値を返す。
 * j==m（理論値=パーフェクト）のときは 1 - j/m = 0 となり発散するため、本家同様 m*0.8 を返して回避する。
 */
function pgf(j: number, m: number): number {
  if (j === m) return m * 0.8;
  return 1 + (j / m - 0.5) / (1 - j / m);
}

/**
 * 【関数の役割】 単曲 BPI を計算する。算出不能な入力（データ欠落・0点・理論値超過など）は null を返す。
 *
 * @param score EX スコア
 * @param max   理論値（notes * 2）
 * @param avg   皆伝平均スコア（song_data.avg）
 * @param wr    世界記録スコア（song_data.wr）
 * @param coef  補正係数（song_data.coef）。未指定 / 0以下なら 1.175 を使う。
 * @returns     小数第2位までに丸めた BPI。算出不能なら null。
 */
export function calcBpi(
  score: number,
  max: number,
  avg: number | null | undefined,
  wr: number | null | undefined,
  coef?: number | null,
): number | null {
  // データが揃っていなければ計算できない。
  if (!max || max <= 0) return null;
  if (avg == null || wr == null || avg <= 0 || wr <= 0) return null;
  if (score <= 0) return null;        // 未プレイ / 0点は対象外
  if (score > max) return null;       // 理論値超過は異常値として除外

  const ps = pgf(score, max);
  const pk = pgf(avg, max);   // kaiden（皆伝平均）
  const pz = pgf(wr, max);    // world record
  if (pk <= 0 || pz <= 0 || ps <= 0) return null;

  const denom = Math.log(pz / pk);
  // wr <= avg のような壊れたデータでは分母が 0 / 非有限になり計算不能。
  if (!isFinite(denom) || denom === 0) return null;

  const ratio = Math.log(ps / pk) / denom;
  if (!isFinite(ratio)) return null;

  const powCoef = coef && coef > 0 ? coef : DEFAULT_POW_COEF;
  const sign = score >= avg ? 1 : -1;
  let bpi = sign * 100 * Math.pow(Math.abs(ratio), powCoef);
  if (!isFinite(bpi)) return null;

  bpi = Math.round(bpi * 100) / 100;  // 小数第2位まで
  return Math.max(bpi, -15);          // 下限 -15
}
