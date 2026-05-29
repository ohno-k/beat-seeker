/**
 * 【役割】 INFINITAS リザルトの「自動取り込み可否の判定」と「ScoreData 組み立て」を
 * useInfinitasMonitor / InfinitasMonitor.vue の両方から使える形で提供する。
 *
 * 目的: 必要な情報がすべて揃って整合していれば、確認モーダルを出さずに自動登録する
 * （ユーザー理想の「全自動」）。ただし誤データのサイレント登録は避けたいので、
 * 確信度ゲートを通ったものだけ 'auto' とし、曖昧なものは 'manual'（モーダル）、
 * 演出中・カウントアップ中など未確定フレームは 'skip'（無視して監視継続）に分類する。
 */
import type { InfinitasResult } from './useInfinitasMonitor';
import type { ScoreData, DifficultyStats } from '../types/ScoreData';

/** 取り込み判定の結果。 */
export type ImportDecision =
  | 'auto'    // 全情報が揃って整合 → モーダル無しで自動登録
  | 'manual'  // 確定はしているが曖昧（曲未特定・DP・整合性 NG 等）→ 確認モーダル
  | 'skip';   // 未確定（演出中・カウントアップ中・スコア0等）→ 無視して監視継続

/** スコア取得元。 */
export type ScoreSource = 'current' | 'best';

/** 認識済みクリアタイプとして妥当か（現在プレイ列に NO PLAY はありえない）。 */
function isValidCurrentClear(clearType: string | null): boolean {
  return !!clearType && clearType !== 'NO PLAY';
}

/**
 * リザルトを 'auto' / 'manual' / 'skip' に分類する。
 *
 * skip（未確定・取り込まない）:
 *  - EX SCORE が null か 0（演出中・カウントアップ前）
 *  - NOTES 数が取れていない（曲特定不能 = 認識が安定していない）
 *  - EX SCORE が最大値(notes×2)を超える（ありえない＝カウントアップ途中 or 誤読）
 *  - PGREAT×2 > EX SCORE（great が負＝途中値 or 誤読）
 *  - 難易度が取れていない
 *
 * manual（確定はしたが要確認）:
 *  - 曲が一意特定できていない（songEntry が null）
 *  - クリアタイプが不正（null / NO PLAY）
 *  - JUDGE 整合性 NG（BAD+POOR ≠ MISS COUNT。両方読めている場合のみ判定）
 *  - DP プレー（beat-seeker は SP 前提のため、自動では入れず人手確認に回す）
 *
 * auto（全自動登録）: 上記いずれにも該当しない。
 */
export function classifyResult(result: InfinitasResult): ImportDecision {
  const sc = result.score;
  const notes = result.notesCount;

  // ── skip: 未確定フレーム ──
  if (sc == null || sc <= 0) return 'skip';
  if (notes == null || notes <= 0) return 'skip';
  if (sc > notes * 2) return 'skip';
  if (result.pgreat != null && result.pgreat * 2 > sc) return 'skip';
  if (!result.difficulty) return 'skip';

  // ── manual: 確定したが曖昧 ──
  if (!result.songEntry) return 'manual';
  if (!isValidCurrentClear(result.clearType)) return 'manual';
  if (result.playSide === 'DP') return 'manual';
  // JUDGE 整合性（BAD+POOR == MISS COUNT）。3 値とも読めている時だけ厳密判定。
  if (result.bad != null && result.poor != null && result.missCount != null) {
    if (result.bad + result.poor !== result.missCount) return 'manual';
  }

  return 'auto';
}

/**
 * 今回プレイと自己ベストのどちらを登録に使うかを決定する（モーダルの resolveScoreSource と同等）。
 *  - 自己ベスト未読 → current
 *  - 今回プレイ ≥ 自己ベスト → current
 *  - beat-seeker 上にスコアが無い & 自己ベストが今回より上 → best（取りこぼし防止）
 *  - それ以外 → current（サーバ側でベスト判定）
 */
export function resolveScoreSource(result: InfinitasResult, existingScores: ScoreData[]): ScoreSource {
  const current = result.score ?? 0;
  const best = result.bestScore ?? 0;
  if (best === 0) return 'current';
  if (current >= best) return 'current';
  const title = result.songEntry?.title;
  const diff = result.difficulty;
  if (title && diff) {
    const diffKey = diff.toLowerCase() as 'another' | 'leggendaria';
    const has = existingScores.some(s =>
      s.title === title &&
      s[diffKey] &&
      (s[diffKey] as DifficultyStats).clearType !== 'NO PLAY' &&
      (s[diffKey] as DifficultyStats).score > 0
    );
    if (!has) return 'best';
  }
  return 'current';
}

/** 空の DifficultyStats。 */
function emptyStats(): DifficultyStats {
  return { difficulty: null, score: 0, pgreat: 0, great: 0, missCount: null, clearType: 'NO PLAY', djLevel: '---' };
}

/**
 * 認識結果から 1 曲・1 譜面分の ScoreData を組み立てる（モーダルの confirm と同じ構造）。
 * source に応じて今回プレイ / 自己ベストの値を採用する。songEntry/difficulty 必須。
 */
export function buildScoreData(result: InfinitasResult, source: ScoreSource): ScoreData | null {
  const song = result.songEntry;
  const diff = result.difficulty;
  if (!song || !diff) return null;

  const stats: DifficultyStats = source === 'best'
    ? {
        difficulty: song.level ?? null,
        score: result.bestScore ?? 0,
        pgreat: 0,
        great: 0,
        missCount: result.bestMissCount,
        clearType: result.bestClearType || 'NO PLAY',
        djLevel: result.bestDjLevel || '---',
      }
    : {
        difficulty: song.level ?? null,
        score: result.score ?? 0,
        pgreat: result.pgreat ?? 0,
        great: result.great ?? 0,
        missCount: result.missCount,
        clearType: result.clearType || 'NO PLAY',
        djLevel: result.djLevel || '---',
      };

  return {
    version: 'INFINITAS',
    title: song.title,
    genre: song.genre,
    artist: song.artist,
    playCount: 1,
    beginner: emptyStats(),
    normal: emptyStats(),
    hyper: emptyStats(),
    another: diff === 'ANOTHER' ? stats : emptyStats(),
    leggendaria: diff === 'LEGGENDARIA' ? stats : emptyStats(),
    lastPlayTime: new Date().toISOString(),
  };
}
