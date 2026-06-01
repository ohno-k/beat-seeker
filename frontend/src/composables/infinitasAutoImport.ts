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

/**
 * 認識結果の整合性警告。複数の独立した読み取り（EX SCORE / PGREAT / JUDGE GREAT 行）が
 * 矛盾していないか、また物理的に妥当な値かをチェックした結果。空配列なら問題なし。
 *
 * 確認モーダルで強調表示し、`classifyResult` では非空なら自動登録を避けて 'manual'（人手確認）に倒す。
 * 1 箇所の OCR 誤読が EX SCORE → great/djLevel と波及するため、独立読み取り同士の照合が誤読検出の要。
 */
export interface ConsistencyWarning {
  /** 警告種別。モーダルで i18n キーに対応付けて表示する。 */
  code: 'judge-mismatch' | 'over-max' | 'negative-great' | 'miss-over-notes';
  /** 強調表示すべき編集フィールド。 */
  fields: ReadonlyArray<'score' | 'pgreat' | 'great' | 'missCount'>;
  /** メッセージ補間用のパラメータ。 */
  params: Record<string, number>;
}

/**
 * 認識結果の整合性を検証して警告リストを返す。
 *
 * ① JUDGE クロスチェック（最重要）:
 *   IIDX のスコア定義 `EX = 2*PGREAT + GREAT` は厳密な恒等式。画面の EX SCORE 欄とは独立に
 *   JUDGE 内訳行から読んだ GREAT（{@link InfinitasResult.judgeGreat}）を使い、
 *   `2*pgreat + judgeGreat` が EX SCORE と一致するか照合する。一致しなければ EX SCORE か
 *   PGREAT か GREAT 行のいずれかが誤読されている（例: EX を 2241→2641 と読み違えるケースを検出）。
 *
 * ② 妥当性ガード:
 *   - EX SCORE が理論値（notes×2）を超える
 *   - PGREAT×2 が EX SCORE を超える（great が負になる）
 *   - MISS COUNT が NOTES を超える
 *
 * notes は曲特定できていればその notes を優先（より確実）、無ければ認識した notesCount を使う。
 */
export function getConsistencyWarnings(result: InfinitasResult): ConsistencyWarning[] {
  const warnings: ConsistencyWarning[] = [];
  const { score, pgreat, judgeGreat, missCount } = result;
  const notes = result.songEntry?.notes ?? result.notesCount ?? null;

  // ① JUDGE クロスチェック（独立読み取り同士の照合）
  if (score != null && pgreat != null && judgeGreat != null) {
    const judgeEx = pgreat * 2 + judgeGreat;
    if (judgeEx !== score) {
      warnings.push({ code: 'judge-mismatch', fields: ['score', 'pgreat', 'great'], params: { judgeEx, score } });
    }
  }

  // ② 妥当性ガード
  if (score != null && notes != null && notes > 0 && score > notes * 2) {
    warnings.push({ code: 'over-max', fields: ['score'], params: { max: notes * 2, score } });
  }
  if (score != null && pgreat != null && pgreat * 2 > score) {
    warnings.push({ code: 'negative-great', fields: ['score', 'pgreat'], params: { pgreat, score } });
  }
  if (missCount != null && notes != null && notes > 0 && missCount > notes) {
    warnings.push({ code: 'miss-over-notes', fields: ['missCount'], params: { missCount, notes } });
  }

  return warnings;
}

/** 認識済みクリアタイプとして妥当か（現在プレイ列に NO PLAY はありえない）。 */
function isValidCurrentClear(clearType: string | null): boolean {
  return !!clearType && clearType !== 'NO PLAY';
}

/**
 * リザルトを 'auto' / 'manual' / 'skip' に分類する。
 *
 * skip（未確定・取り込まない）:
 *  - EX SCORE が null か 0（演出中・カウントアップ前）
 *  - EX SCORE が最大値(notes×2)を超える（ありえない＝カウントアップ途中 or 誤読。NOTES が読めた時のみ判定）
 *  - PGREAT×2 > EX SCORE（great が負＝途中値 or 誤読）
 *  - 難易度が取れていない
 *
 * 曲名が検知できない（曲を一意特定できない＝songEntry が null）時の扱い【ユーザー要望】:
 *  - 候補が複数（NOTES は読めたが同 NOTES の別曲と区別できない）→ manual（選択モーダル）
 *  - 候補が 0 件（NOTES が読めない / INFINITAS 限定曲で song_data に無い）→ skip（黙って監視継続）
 *  以前は NOTES 不明や songEntry=null を一律 manual にしていたが、「曲名が検知できない時はモーダルで
 *  煩わせず、候補が複数の時だけ確認する」方針へ変更。曲が特定できなければスコアは組み立てられないため
 *  0 件は破棄する。なお EXSCORE 相違（JUDGE クロスチェック不一致）があれば候補複数モーダルで警告が出る。
 *
 * manual（曲は特定できたが要確認）:
 *  - クリアタイプが不正（null / NO PLAY）
 *  - JUDGE 整合性 NG（BAD+POOR が MISS COUNT を超過＝誤読。空POOR は POOR 欄に出ないため
 *    BAD+POOR <= MISS COUNT が正常。3 値とも読めている場合のみ判定）
 *  - DP プレー（beat-seeker は SP 前提のため、自動では入れず人手確認に回す）
 *  - 整合性チェック NG（EXSCORE 相違＝JUDGE クロスチェック不一致 / miss-over-notes）
 *
 * auto（全自動登録）: 上記いずれにも該当しない。
 */
export function classifyResult(result: InfinitasResult): ImportDecision {
  const sc = result.score;
  const notes = result.notesCount;

  // ── skip: 未確定フレーム ──
  if (sc == null || sc <= 0) return 'skip';
  // NOTES が読めている時だけ「最大値(notes×2)超え＝カウントアップ途中/誤読」を未確定として弾く。
  if (notes != null && notes > 0 && sc > notes * 2) return 'skip';
  if (result.pgreat != null && result.pgreat * 2 > sc) return 'skip';
  if (!result.difficulty) return 'skip';

  // ── 曲名が検知できない（曲を一意特定できない）時 ──
  // 候補が複数 → 選択モーダル（manual）。候補 0 件（NOTES 読めず / INFINITAS 限定曲）→ 黙ってスキップ。
  // 曲が特定できなければスコアは組み立てられないので、モーダルで煩わせずそのまま監視を続ける。
  if (!result.songEntry) {
    return result.candidates.length >= 2 ? 'manual' : 'skip';
  }

  // ── manual: 曲は特定できたが要確認 ──
  if (!isValidCurrentClear(result.clearType)) return 'manual';
  if (result.playSide === 'DP') return 'manual';
  // JUDGE 整合性。MISS COUNT = BAD + 見逃しPOOR + 空POOR だが、リザルト詳細の POOR 欄は
  // 空POOR（2度押し等でコンボ継続するもの）を含まないため bad + poor <= missCount が正常
  // （差分が空POOR）。よって「超過」した時だけ誤読とみなして弾く。3 値とも読めている時のみ判定。
  if (result.bad != null && result.poor != null && result.missCount != null) {
    if (result.bad + result.poor > result.missCount) return 'manual';
  }
  // 整合性チェック（① JUDGE クロスチェック / ② 妥当性）に引っかかったら自動登録しない。
  // over-max / negative-great は上の skip ゲートで既に弾かれているため、ここに残るのは
  // judge-mismatch（独立読み取りの不一致）/ miss-over-notes。いずれも誤読の可能性が高いので
  // サイレント登録せず確認モーダルへ回す。
  if (result.validationWarnings.length > 0) return 'manual';

  return 'auto';
}

/**
 * 登録に使うスコアソースを決定する。
 *
 * 【方針変更】 INFINITAS 取り込みでは「自己ベスト(bestScore)送信」を廃止し、**常に今回プレイ(current)** を登録する。
 *   リザルト画面の自己ベスト欄は取得元が曖昧で（アーケード等のベストが混ざり得る）、それを INFINITAS の
 *   スコアとして登録してしまうと別管理の前提が崩れるため。今回プレイした結果のみを記録する。
 *
 * 引数は将来の判定拡張・呼び出し側互換のため残置（現状は未使用）。
 */
export function resolveScoreSource(_result: InfinitasResult, _existingScores: ScoreData[]): ScoreSource {
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
