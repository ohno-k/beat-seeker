/**
 * 【型の役割】 コスパ埋めレコメンド（`/api/analysis/fill-recommendation`）のレスポンス型。
 *
 * ランクアップ・アドバイスの一覧（RankUpAdvice）と根拠モーダル（RankUpAdviceReasonModal）で共有する。
 * 各値の定義は docs/コスパ埋めレコメンド.md を参照。
 */

/** 予測の出どころ。HIGH / LOW はペア回帰、BASE / RANK は加法モデルによる概算。 */
export type FillAccuracy = 'HIGH' | 'LOW' | 'BASE' | 'RANK';

/** `/api/analysis/fill-recommendation` の items 1 件ぶん。 */
export interface FillRecommendationItem {
  title: string;
  difficultyName: string;
  informalRank: string;
  difficultyLevel: number;
  /** 未プレイ譜面（＝純粋な「埋め」候補）なら true。 */
  unplayed: boolean;
  currentScore: number;
  currentRate: number;
  currentBeatPt: number;
  inTop100: boolean;
  maxScore: number;
  predictedScore: number;
  predictedRate: number;
  /** 予測のばらつき（スコアレート % 換算の 1σ）。 */
  sigmaRate: number;
  /** 推定能力の中央値（logit 空間）。分布を描くのに使う。 */
  muLogit: number;
  /** 推定能力のばらつき（logit 空間の 1σ）。 */
  sigmaLogit: number;
  /** 増分の比較対象 pt。TOP100 圏内なら現在 pt、圏外・未プレイなら 100 位の pt。 */
  baselinePt: number;
  /** 目標の上限スコア（コミュニティ実測最高。誰も出していないスコアは提示しない）。 */
  scoreCap: number;
  /** 損益分岐スコア。ここを超えて初めて総合 BEAT-PT が増える。 */
  breakEvenScore: number;
  /** P(損益分岐スコア以上を出せる | 推定能力)。「そもそも合計に効く可能性」。 */
  breakEvenProbability: number;
  /** 目標スコアに届く確率 P(S ≥ targetScore)。「達成率」として表示する。 */
  achieveProbability: number;
  /** 目標スコア。P(届く) × 達成時の増分 が最大になる点。 */
  targetScore: number;
  targetRate: number;
  /** 'AA' / 'AAA' / 'MAX-'。目標がボーダーちょうどでなければ空文字。 */
  targetLabel: string;
  /** achieveProbability と同値（互換用）。 */
  targetProbability: number;
  /** 目標に届いたときの総合 BEAT-PT の増分。 */
  targetGain: number;
  /** 期待獲得 pt = achieveProbability × targetGain。 */
  expectedGain: number;
  supportCount: number;
  accuracy: FillAccuracy;
}

/** 挑戦済み（直近に更新したが目標未達）の 1 件。items と同じ形に更新前後のスコアと日時が付く。 */
export interface AttemptedItem extends FillRecommendationItem {
  /** 冷却期間内で最初に更新したときの更新前スコア。 */
  attemptOldScore: number;
  /** 冷却期間内で最後に更新したときの更新後スコア。 */
  attemptNewScore: number;
  /** 最後に更新したアップロードの日時（サーバーの LocalDateTime 文字列）。 */
  lastAttemptAt: string;
}

/** 概算系（ペア回帰の参照が無い）かどうか。バッジ表示の判定に使う。 */
export function isRoughAccuracy(acc: FillAccuracy): boolean {
  return acc === 'BASE' || acc === 'RANK';
}
