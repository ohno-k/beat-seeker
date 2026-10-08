/**
 * chartSnapshot.ts
 *
 * 譜面の 1 区間を、指定の並びで 1 枚の静止画として canvas に描く（「どっちが押しやすい？」の比較画面用）。
 * 下が区間の始まり・上が終わりで、時間に比例した一定速度（ソフランでも詰まらない）。色とレーン幅は譜面再生（ChartPlayer.vue）と同じ。
 */
import { assignLanes, type ChartTimeline } from './chartPlayback.ts';

// レーン: 0 = 皿、1〜7 = 鍵盤。幅の比は皿 1.7 / 白鍵 1.0 / 黒鍵 0.8（ChartPlayer と同じ）
const LANE_WEIGHT = [1.7, 1, 0.8, 1, 0.8, 1, 0.8, 1];
const NOTE_COLOR = ['#f43f5e', '#e5e7eb', '#60a5fa', '#e5e7eb', '#60a5fa', '#e5e7eb', '#60a5fa', '#e5e7eb'];
const CN_BODY = ['rgba(244,63,94,0.45)', 'rgba(229,231,235,0.4)', 'rgba(96,165,250,0.45)', 'rgba(229,231,235,0.4)',
  'rgba(96,165,250,0.45)', 'rgba(229,231,235,0.4)', 'rgba(96,165,250,0.45)', 'rgba(229,231,235,0.4)'];

/**
 * 【関数の役割】 区間 [t0, t1) を並び pattern で描く。canvas の表示サイズ（CSS px）は width × height にする。
 *
 * @param side  1P（皿が左）/ 2P（皿が右）
 */
export function drawChartSnapshot(
  canvas: HTMLCanvasElement, tl: ChartTimeline, pattern: string, side: 1 | 2,
  t0: number, t1: number, width: number, height: number,
): void {
  const dpr = window.devicePixelRatio || 1;
  canvas.style.width = `${width}px`;
  canvas.style.height = `${height}px`;
  canvas.width = Math.round(width * dpr);
  canvas.height = Math.round(height * dpr);
  const g = canvas.getContext('2d');
  if (!g) return;
  g.setTransform(dpr, 0, 0, dpr, 0, 0);

  const pad = 6; // 上下の余白（端のノーツが切れないように）
  const span = Math.max(t1 - t0, 1e-6);
  const yOf = (t: number) => height - pad - ((t - t0) / span) * (height - pad * 2);

  // レーンの位置
  const total = LANE_WEIGHT.reduce((a, b) => a + b, 0);
  const order = side === 1 ? [0, 1, 2, 3, 4, 5, 6, 7] : [1, 2, 3, 4, 5, 6, 7, 0];
  const xs = new Array<number>(8);
  const ws = new Array<number>(8);
  let x = 0;
  for (const lane of order) {
    ws[lane] = (LANE_WEIGHT[lane] / total) * width;
    xs[lane] = x;
    x += ws[lane];
  }

  g.fillStyle = '#05070d';
  g.fillRect(0, 0, width, height);
  for (let lane = 0; lane < 8; lane++) {
    g.fillStyle = lane === 0 ? '#0d1220' : (lane % 2 === 0 ? '#0a0e1a' : '#111827');
    g.fillRect(xs[lane], 0, ws[lane], height);
    g.fillStyle = '#1f2937';
    g.fillRect(xs[lane], 0, 1, height);
  }

  // 小節線
  g.fillStyle = '#4b5563';
  for (const mt of tl.measureTimes) {
    if (mt < t0 - 1e-6 || mt > t1 + 1e-6) continue;
    g.fillRect(0, Math.round(yOf(mt)), width, 1);
  }

  const la = assignLanes(tl, 'random', pattern, 0);
  const noteH = Math.max(3, Math.min(6, height / 120));

  // CN（区間にかかる分だけ。本体 → 先頭・終端）
  for (let i = 0; i < tl.cnKeys.length; i++) {
    const s = tl.cnStartTimes[i];
    const e = tl.cnEndTimes[i];
    if (e < t0 || s >= t1) continue;
    const lane = la.cnLanes[i];
    const inset = ws[lane] * 0.18;
    const yTop = yOf(Math.min(e, t1));
    const yBottom = yOf(Math.max(s, t0));
    g.fillStyle = CN_BODY[lane];
    g.fillRect(xs[lane] + inset, yTop, ws[lane] - inset * 2, yBottom - yTop);
    g.fillStyle = NOTE_COLOR[lane];
    if (tl.cnFlags[i] & 1 && s >= t0) g.fillRect(xs[lane] + 1, yOf(s) - noteH, ws[lane] - 2, noteH);
    if (tl.cnFlags[i] & 2 && e < t1) g.fillRect(xs[lane] + 1, yOf(e) - noteH, ws[lane] - 2, noteH);
  }
  // 通常ノーツ
  for (let i = 0; i < tl.noteKeys.length; i++) {
    const t = tl.noteTimes[i];
    if (t < t0 || t >= t1) continue;
    const lane = la.noteLanes[i];
    g.fillStyle = NOTE_COLOR[lane];
    g.fillRect(xs[lane] + 1, Math.round(yOf(t)) - noteH, ws[lane] - 2, noteH);
  }
}
