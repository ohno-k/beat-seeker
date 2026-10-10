/**
 * 【ファイルの役割】 配信オーバーレイのリザルト表示を「いつ消すか」の判定。
 *
 * 流れ: プレー → 暗転（ここで表示）→ リザルト画面 → 暗転（選曲画面へ戻る。ここで消す）。
 *
 * 表示直後の暗転は数秒続くことがあるので、「表示から何秒」で判定すると暗転の途中で消えてしまう。
 * そこで「表示してから明るい画面（= リザルト画面）が合計 {@link RESULT_MIN_BRIGHT_MS} 以上映った後の暗転」で消す。
 * DOM に依存しない純粋な関数なので、Node から輝度の並びを流して検証できる。
 */

/** リザルト画面がこれだけ映るまでは、暗転しても消さない。 */
export const RESULT_MIN_BRIGHT_MS = 3000;
/** どんな場合でもこれを過ぎたら消す。 */
export const RESULT_MAX_MS = 120000;

export interface ResultTimerState {
  shownAt: number;
  /** 表示してから明るい画面が映っていた時間の合計。 */
  brightMs: number;
  lastAt: number;
}

export function startResultTimer(now: number): ResultTimerState {
  return { shownAt: now, brightMs: 0, lastAt: now };
}

/**
 * 【関数の役割】 1 フレーム分進め、表示を消すべきなら true を返す。
 * @param dark このフレームが暗転中か
 */
export function stepResultTimer(state: ResultTimerState, now: number, dark: boolean): boolean {
  const dt = Math.max(0, now - state.lastAt);
  state.lastAt = now;
  if (!dark) state.brightMs += dt;
  if (now - state.shownAt > RESULT_MAX_MS) return true;
  return dark && state.brightMs >= RESULT_MIN_BRIGHT_MS;
}
