/**
 * randomPairWorker.ts
 *
 * 「どっちが押しやすい？」の 1 問を作る Web Worker（utils/randomPairQuestion.ts。5,040 通りの評価で 1〜2 秒かかるので画面を止めない）。
 * 受け取り: { id, data: 再生データ, side, measures } → 返し: { id, question }（作れなければ question = null）か { id, error }
 */
import { buildChartTimeline, type ChartPlaybackData } from '../utils/chartPlayback.ts';
import { makePairQuestion } from '../utils/randomPairQuestion.ts';

self.onmessage = (e: MessageEvent<{ id: number; data: ChartPlaybackData; side: 1 | 2; measures: number }>) => {
  const { id, data, side, measures } = e.data;
  try {
    const question = makePairQuestion(buildChartTimeline(data), side, measures);
    self.postMessage({ id, question });
  } catch (err) {
    self.postMessage({ id, error: String(err) });
  }
};
