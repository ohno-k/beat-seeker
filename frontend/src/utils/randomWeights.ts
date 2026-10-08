/**
 * randomWeights.ts
 *
 * RANDOM の配置評価（randomEval.ts）で、減点の形ごとの該当ノーツ数に掛ける係数。
 * scripts/fit-random-weights.mts が配置アンケート「どっちが押しやすい？」の回答（サイドバーの配置アンケート）とオプション投票から学習して書き換える。
 * 手で直さない（直すなら学習をやり直す）。全部 1 なら係数無し（2026-10-03 以降の数え方）と同じ。
 */
import type { PenaltyKey } from './randomEval.ts';

export const RANDOM_WEIGHTS_INFO = {
  /** 学習した日時（JST）。null = まだ学習していない */
  fittedAt: null as string | null,
  /** 学習に使った回答の数（同じくらい・スキップを除く）と、オプション投票の弱い正解の数 */
  humanPairs: 0,
  weakPairs: 0,
  /** 交差検証での「どっちが押しやすい？」の正解率（係数無し → 学習後） */
  cvAccuracyBefore: null as number | null,
  cvAccuracyAfter: null as number | null,
};

export const RANDOM_WEIGHTS: Record<PenaltyKey, number> = {
  scratchSimulOff: 1,
  streamSameHand: 1,
  scratchNear: 1,
  scratch3: 1,
  unsplit16: 1,
  fastMove: 1,
  trill: 1,
  foldStair: 1,
  jackClash: 1,
  cnHold: 1,
  uneven: 1,
};
