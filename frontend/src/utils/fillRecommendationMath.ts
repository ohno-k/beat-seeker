/**
 * 【ユーティリティの役割】 コスパ埋めレコメンドの根拠を図示するための数値計算。
 *
 * バックエンド `FillRecommendationService` と同じ前提（S は logit 空間の正規分布、
 * 増分 = max(0, pt(s) − baseline)）で、分布の密度・達成確率・期待値曲線をフロント側で再現する。
 * 表示専用で、候補の採否や数値の正本はあくまでバックエンドのレスポンス。
 */
import { calculatePoints } from './beatTier';

/** logit 変換のクランプ。バックエンド `PairRegressionService.LOGIT_RATE_CLAMP` と同じ。 */
const LOGIT_RATE_CLAMP = 1e-4;

/** バックエンドの BORDER_RATES / BORDER_LABELS と同じ。ボーナスは「超えて」初めて付く。 */
export const PT_BORDERS: { label: 'AA' | 'AAA' | 'MAX-'; rate: number }[] = [
  { label: 'AA', rate: 77.77 },
  { label: 'AAA', rate: 88.88 },
  { label: 'MAX-', rate: 94.44 },
];

/** スコアレート（0〜1）→ logit。 */
export function rateToLogit(rate: number): number {
  const c = Math.max(LOGIT_RATE_CLAMP, Math.min(1 - LOGIT_RATE_CLAMP, rate));
  return Math.log(c / (1 - c));
}

/** logit → スコアレート（0〜1）。 */
export function logitToRate(logit: number): number {
  return 1 / (1 + Math.exp(-logit));
}

/**
 * 標準正規分布の累積分布関数。Abramowitz & Stegun 26.2.17（バックエンド `normalCdf` と同じ近似）。
 */
export function normalCdf(z: number): number {
  if (z < -8) return 0;
  if (z > 8) return 1;
  const t = 1 / (1 + 0.2316419 * Math.abs(z));
  const poly = t * (0.319381530
    + t * (-0.356563782
    + t * (1.781477937
    + t * (-1.821255978
    + t * 1.330274429))));
  const phi = Math.exp(-0.5 * z * z) / Math.sqrt(2 * Math.PI);
  const upper = phi * poly;
  return z >= 0 ? 1 - upper : upper;
}

/** 標準正規分布の密度。 */
function normalPdf(z: number): number {
  return Math.exp(-0.5 * z * z) / Math.sqrt(2 * Math.PI);
}

/** 【関数の役割】 P(S ≥ score)。バックエンド `tailProbability` と同じ。 */
export function tailProbability(score: number, maxScore: number, mu: number, sigma: number): number {
  if (score <= 0) return 1;
  if (score > maxScore) return 0;
  const z = (rateToLogit(score / maxScore) - mu) / sigma;
  return 1 - normalCdf(z);
}

/**
 * 【関数の役割】 スコア空間での S の確率密度（相対値）。
 * logit 空間の正規密度にヤコビアン d logit / d score = 1 / (max · p(1−p)) を掛けたもの。
 * 図の高さを決めるだけなので絶対スケールは問わない。
 */
export function scoreDensity(score: number, maxScore: number, mu: number, sigma: number): number {
  if (score <= 0 || score >= maxScore) return 0;
  const p = score / maxScore;
  const z = (rateToLogit(p) - mu) / sigma;
  return normalPdf(z) / sigma / (maxScore * p * (1 - p));
}

/** 【関数の役割】 logit 空間の位置 μ + kσ をスコアに直す。 */
export function scoreAtSigma(maxScore: number, mu: number, sigma: number, k: number): number {
  return Math.round(logitToRate(mu + sigma * k) * maxScore);
}

/**
 * 【関数の役割】 目標 s に届いたときの合計 BEAT-PT の増分 = max(0, pt(s) − baseline)。
 * 自己ベスト以下は更新にならないので 0（バックエンド `pickTarget` の探索下限 current + 1 と同じ扱い）。
 */
export function gainAt(score: number, maxScore: number, informalRank: string, baseline: number, currentScore: number): number {
  if (score <= currentScore) return 0;
  return Math.max(0, calculatePoints(score * 100 / maxScore, informalRank) - baseline);
}

/** 【関数の役割】 ボーダーレート（%）を「超える」のに必要な最小スコア。バックエンド `borderScore` と同じ。 */
export function borderScore(maxScore: number, borderRate: number): number {
  let need = Math.floor(maxScore * borderRate / 100) + 1;
  while (need * 100 / maxScore <= borderRate) need++;
  return need;
}
