import { ref, computed } from 'vue';

/**
 * 【Composable の役割】 演出 SE (Sound Effect) の再生制御。
 *
 * SongRevealView / StrategyCardView の演出にタイミングを合わせて短尺 SE を鳴らす
 * 専用ストア。ブラウザの autoplay 制約に対応するため、初回ユーザー操作で
 * `unlock()` を 1 回呼ぶ必要がある (各 View 側で REVEAL/抽選ボタンに紐づけ)。
 *
 * 連打再生のため `play()` は事前ロード済み Audio を `cloneNode()` してから鳴らす。
 * クローン側は再生終了で GC 任せ (DOM に attach しないので解放される)。
 *
 * 音量とミュート設定は localStorage で永続化し、別画面でも同じ設定を引き継ぐ。
 */

export type SeKey = 'impact' | 'ui' | 'ui2' | 'whoosh' | 'whoosh2' | 'whoosh3' | 'whoosh4';

const SE_FILES: Record<SeKey, string> = {
  impact:  '/se/se_impact.wav',
  ui:      '/se/se_ui.wav',
  ui2:     '/se/se_ui2.wav',
  whoosh:  '/se/se_whoosh.wav',
  whoosh2: '/se/se_whoosh2.wav',
  whoosh3: '/se/se_whoosh3.wav',
  whoosh4: '/se/se_whoosh4.wav',
};

// 元音源のピーク振幅にばらつきがあるため、聴感を揃えるための個別ゲイン。
// (0.0-1.0 の相対値、最終音量は `volume * gain`)
const SE_GAIN: Record<SeKey, number> = {
  impact:  1.0,
  ui:      1.0,
  ui2:     1.0,
  whoosh:  0.9,
  whoosh2: 0.9,
  whoosh3: 0.9,
  whoosh4: 0.9,
};

const LS_VOLUME_KEY = 'beatseeker.se.volume';
const LS_MUTED_KEY = 'beatseeker.se.muted';

const readVolume = (): number => {
  const raw = localStorage.getItem(LS_VOLUME_KEY);
  if (raw === null) return 0.7;
  const n = Number(raw);
  if (!Number.isFinite(n)) return 0.7;
  return Math.min(1, Math.max(0, n));
};
const readMuted = (): boolean => localStorage.getItem(LS_MUTED_KEY) === '1';

const volume = ref<number>(readVolume());
const muted = ref<boolean>(readMuted());

// 事前ロード用テンプレ。本体は再生せず、`cloneNode()` してから play する。
const templates: Partial<Record<SeKey, HTMLAudioElement>> = {};
let preloaded = false;
let unlocked = false;

/** 全 SE を Audio として生成し、メタデータ取得を促す。複数回呼んでも 1 回しか走らない。 */
const preload = () => {
  if (preloaded) return;
  preloaded = true;
  for (const key of Object.keys(SE_FILES) as SeKey[]) {
    const a = new Audio(SE_FILES[key]);
    a.preload = 'auto';
    templates[key] = a;
  }
};

/**
 * ブラウザの autoplay ポリシー解除用。ユーザー操作起源のイベントハンドラ内で
 * 一度だけ呼ぶ。各テンプレを無音 play → 即 pause することで、以降の clone 再生も
 * 同一ユーザージェスチャの延長として許可される。
 */
const unlock = () => {
  if (unlocked) return;
  unlocked = true;
  preload();
  for (const key of Object.keys(templates) as SeKey[]) {
    const a = templates[key];
    if (!a) continue;
    const originalVolume = a.volume;
    a.volume = 0;
    a.play().then(() => {
      a.pause();
      a.currentTime = 0;
      a.volume = originalVolume;
    }).catch(() => {
      a.volume = originalVolume;
    });
  }
};

/** SE を 1 発再生する。連打しても重ねて鳴る。ミュート中は no-op。 */
const play = (key: SeKey) => {
  if (muted.value) return;
  preload();
  const tpl = templates[key];
  if (!tpl) return;
  const node = tpl.cloneNode() as HTMLAudioElement;
  node.volume = Math.min(1, Math.max(0, volume.value * SE_GAIN[key]));
  node.play().catch(() => { /* autoplay 拒否時はサイレントに無視 */ });
};

/** 指定 ms 後に再生 (タイマー ID を返すので呼び出し側でクリーンアップ可能)。 */
const playAfter = (key: SeKey, delayMs: number): number => {
  return window.setTimeout(() => play(key), delayMs);
};

const setVolume = (v: number) => {
  const clamped = Math.min(1, Math.max(0, v));
  volume.value = clamped;
  localStorage.setItem(LS_VOLUME_KEY, String(clamped));
};

const setMuted = (m: boolean) => {
  muted.value = m;
  localStorage.setItem(LS_MUTED_KEY, m ? '1' : '0');
};

const toggleMuted = () => setMuted(!muted.value);

export function useSe() {
  return {
    volume: computed(() => volume.value),
    muted: computed(() => muted.value),
    preload,
    unlock,
    play,
    playAfter,
    setVolume,
    setMuted,
    toggleMuted,
  };
}
