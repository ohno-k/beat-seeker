/**
 * youtube.ts
 *
 * 【役割】 YouTube IFrame Player API の読み込みと、使う分だけの型。
 * スクリプト（https://www.youtube.com/iframe_api）は初めて使うときに 1 回だけ読み込む。
 */

/** プレーヤーの状態（YT.PlayerState と同じ値）。 */
export const YT_STATE = { UNSTARTED: -1, ENDED: 0, PLAYING: 1, PAUSED: 2, BUFFERING: 3, CUED: 5 } as const;

export interface YTPlayer {
  playVideo(): void;
  pauseVideo(): void;
  seekTo(seconds: number, allowSeekAhead: boolean): void;
  getCurrentTime(): number;
  getPlayerState(): number;
  setPlaybackRate(rate: number): void;
  destroy(): void;
}

export interface YTPlayerOptions {
  videoId: string;
  width?: string | number;
  height?: string | number;
  playerVars?: Record<string, string | number>;
  events?: {
    onReady?: (e: { target: YTPlayer }) => void;
    onStateChange?: (e: { data: number; target: YTPlayer }) => void;
    /** 2 = 不正な ID、5 = HTML5 で再生できない、100 = 削除・非公開、101 / 150 = 埋め込み不可 */
    onError?: (e: { data: number }) => void;
  };
}

interface YTNamespace {
  Player: new (el: HTMLElement, opts: YTPlayerOptions) => YTPlayer;
}

let apiPromise: Promise<YTNamespace> | null = null;

/** IFrame Player API を読み込む（2 回目以降は同じ Promise）。 */
export function loadYouTubeApi(): Promise<YTNamespace> {
  apiPromise ??= new Promise<YTNamespace>((resolve, reject) => {
    const w = window as any;
    if (w.YT?.Player) {
      resolve(w.YT);
      return;
    }
    const prev = w.onYouTubeIframeAPIReady;
    w.onYouTubeIframeAPIReady = () => {
      if (typeof prev === 'function') prev();
      resolve(w.YT);
    };
    const s = document.createElement('script');
    s.src = 'https://www.youtube.com/iframe_api';
    s.async = true;
    s.onerror = () => {
      apiPromise = null;
      reject(new Error('YouTube のプレーヤーを読み込めませんでした'));
    };
    document.head.appendChild(s);
  });
  return apiPromise;
}
