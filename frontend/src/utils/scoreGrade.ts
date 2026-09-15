/**
 * 【ユーティリティの役割】 EX スコアの beat-seeker 記法（MAX / MAX-n / AAA+n / AAA-n / AA+n / AA-n / A+n / A-n）。
 *
 * 「AAA+15」は AAA 境界を 15 点超えている、「MAX-30」は理論値まで 30 点、という読み方。
 * どのグレードで表すかは隣接グレードの中間点で切り替える（AAA+ と MAX- の境は 94.45% など）。
 * 閾値と差分の定義は ArenaView の scoreGrade / gradeDelta に準拠している
 * （同画面はローカル実装のまま。新規箇所はこちらを使う）。
 *
 * AAA / AA / A の境界は IIDX 公式仕様どおり 8/9・7/9・6/9 を切り上げた整数スコア。
 */

export type ScoreGrade = 'MAX' | 'MAX-' | 'AAA+' | 'AAA-' | 'AA+' | 'AA-' | 'A+' | 'A-' | '';

/** グレード境界スコア（切り上げ）。 */
function border(maxScore: number, numerator: number): number {
  return Math.ceil(maxScore * numerator / 9);
}

/**
 * 【関数の役割】 スコアが属するグレード帯を返す。A- 未満（61.12% 未満）や不正値は空文字。
 */
export function scoreGrade(score: number, maxScore: number): ScoreGrade {
  if (maxScore <= 0 || score <= 0) return '';
  if (score >= maxScore) return 'MAX';
  const pct = score / maxScore;
  if (pct >= 0.9445) return 'MAX-';
  if (pct >= 8 / 9) return 'AAA+';
  if (pct >= 0.8334) return 'AAA-';
  if (pct >= 7 / 9) return 'AA+';
  if (pct >= 0.7223) return 'AA-';
  if (pct >= 6 / 9) return 'A+';
  if (pct >= 0.6112) return 'A-';
  return '';
}

/**
 * 【関数の役割】 グレード基準値からの差分（EX 点）。「+」側は基準超え分、「-」側は基準到達までの残り。
 * MAX- は理論値までの残り。該当グレードが無ければ 0。
 */
export function gradeDelta(score: number, maxScore: number): number {
  switch (scoreGrade(score, maxScore)) {
    case 'MAX-': return maxScore - score;
    case 'AAA+': return score - border(maxScore, 8);
    case 'AAA-': return border(maxScore, 8) - score;
    case 'AA+':  return score - border(maxScore, 7);
    case 'AA-':  return border(maxScore, 7) - score;
    case 'A+':   return score - border(maxScore, 6);
    case 'A-':   return border(maxScore, 6) - score;
    default:     return 0;
  }
}

/**
 * 【関数の役割】 「グレード + 差分」の表記（例: AAA+100 / AAA-50 / MAX-30）。MAX は数値なし。
 * 表記できないスコア（A- 未満など）は空文字を返すので、呼び出し側は素のスコアに落とすこと。
 */
export function gradeLabel(score: number, maxScore: number): string {
  const g = scoreGrade(score, maxScore);
  if (!g) return '';
  if (g === 'MAX') return 'MAX';
  return `${g}${gradeDelta(score, maxScore)}`;
}

/**
 * 【関数の役割】 グレードラベルの文字色（MAX=金 / MAX-=紫 / AAA+=琥珀）。
 * それ以外は {@code fallback}（既定は灰）。
 */
export function gradeColorClass(grade: ScoreGrade | string, fallback = 'text-slate-400 dark:text-slate-500'): string {
  if (grade === 'MAX') return 'text-yellow-500 dark:text-yellow-400';
  if (grade === 'MAX-') return 'text-purple-500 dark:text-purple-400';
  if (grade === 'AAA+') return 'text-amber-500 dark:text-amber-400';
  return fallback;
}
