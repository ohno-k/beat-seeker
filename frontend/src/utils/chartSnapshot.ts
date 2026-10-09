/**
 * chartSnapshot.ts
 *
 * 譜面の 1 区間を、指定の並びで上から降らせて描く（配置アンケート「どっちが押しやすい？」用）。
 * 区間の外のノーツは描かない。時間に比例した一定速度（ソフランでも詰まらない）で、色とレーン幅は譜面再生（ChartPlayer.vue）と同じ。
 * 1 フレームの描画だけを受け持ち、時刻を進めて繰り返すのは呼び出し側（RandomPairSurvey.vue）。
 */
import { assignLanes, type ChartTimeline } from './chartPlayback.ts';

// レーン: 0 = 皿、1〜7 = 鍵盤。幅の比は皿 1.7 / 白鍵 1.0 / 黒鍵 0.8（ChartPlayer と同じ）
const LANE_WEIGHT = [1.7, 1, 0.8, 1, 0.8, 1, 0.8, 1];
const NOTE_COLOR = ['#f43f5e', '#e5e7eb', '#60a5fa', '#e5e7eb', '#60a5fa', '#e5e7eb', '#60a5fa', '#e5e7eb'];
const CN_BODY = ['rgba(244,63,94,0.45)', 'rgba(229,231,235,0.4)', 'rgba(96,165,250,0.45)', 'rgba(229,231,235,0.4)',
  'rgba(96,165,250,0.45)', 'rgba(229,231,235,0.4)', 'rgba(96,165,250,0.45)', 'rgba(229,231,235,0.4)'];

/** 緑数字 → 表示時間（秒）。緑数字 ≒ 表示時間(ms) × 0.6（60fps 基準の目安。ChartPlayer と同じ） */
export function greenToVisibleSec(green: number): number {
  return green / 600;
}

export interface FallingChart {
  /** canvas の表示サイズ（CSS px）を合わせる */
  resize(canvas: HTMLCanvasElement, width: number, height: number): void;
  /** 時刻 now（秒）の画面を描く。visibleSec = ノーツが上端から判定ラインまで落ちる秒数 */
  draw(canvas: HTMLCanvasElement, now: number, visibleSec: number): void;
}

/**
 * 【関数の役割】 区間 [t0, t1) を並び pattern で降らせる描画を作る（並びのレーンの割り当ては最初に 1 回だけ）。
 *
 * @param side  1P（皿が左）/ 2P（皿が右）
 */
export function makeFallingChart(tl: ChartTimeline, pattern: string, side: 1 | 2, t0: number, t1: number): FallingChart {
  const la = assignLanes(tl, 'random', pattern, 0);
  // 区間にかかるノーツだけを先に抜き出す
  const notes: { t: number; lane: number }[] = [];
  for (let i = 0; i < tl.noteKeys.length; i++) {
    const t = tl.noteTimes[i];
    if (t >= t0 && t < t1) notes.push({ t, lane: la.noteLanes[i] });
  }
  const cns: { s: number; e: number; lane: number; head: boolean; tail: boolean }[] = [];
  for (let i = 0; i < tl.cnKeys.length; i++) {
    const s = tl.cnStartTimes[i];
    const e = tl.cnEndTimes[i];
    if (e < t0 || s >= t1) continue;
    cns.push({ s: Math.max(s, t0), e: Math.min(e, t1), lane: la.cnLanes[i], head: (tl.cnFlags[i] & 1) !== 0 && s >= t0, tail: (tl.cnFlags[i] & 2) !== 0 && e < t1 });
  }
  const measures = Array.from(tl.measureTimes).filter(m => m >= t0 - 1e-6 && m <= t1 + 1e-6);

  let W = 0, H = 0;
  const xs = new Array<number>(8);
  const ws = new Array<number>(8);

  return {
    resize(canvas, width, height) {
      const dpr = window.devicePixelRatio || 1;
      W = width; H = height;
      canvas.style.width = `${width}px`;
      canvas.style.height = `${height}px`;
      canvas.width = Math.round(width * dpr);
      canvas.height = Math.round(height * dpr);
      canvas.getContext('2d')?.setTransform(dpr, 0, 0, dpr, 0, 0);
      const total = LANE_WEIGHT.reduce((a, b) => a + b, 0);
      const order = side === 1 ? [0, 1, 2, 3, 4, 5, 6, 7] : [1, 2, 3, 4, 5, 6, 7, 0];
      let x = 0;
      for (const lane of order) {
        ws[lane] = (LANE_WEIGHT[lane] / total) * width;
        xs[lane] = x;
        x += ws[lane];
      }
    },
    draw(canvas, now, visibleSec) {
      const g = canvas.getContext('2d');
      if (!g || W === 0) return;
      const judgeY = H - 24;
      const yOf = (t: number) => judgeY - ((t - now) / visibleSec) * judgeY;
      const top = now + visibleSec;

      g.fillStyle = '#05070d';
      g.fillRect(0, 0, W, H);
      for (let lane = 0; lane < 8; lane++) {
        g.fillStyle = lane === 0 ? '#0d1220' : (lane % 2 === 0 ? '#0a0e1a' : '#111827');
        g.fillRect(xs[lane], 0, ws[lane], judgeY);
        g.fillStyle = '#1f2937';
        g.fillRect(xs[lane], 0, 1, judgeY);
      }
      g.fillStyle = '#4b5563';
      for (const m of measures) if (m >= now && m <= top) g.fillRect(0, Math.round(yOf(m)), W, 1);

      const noteH = 5;
      for (const c of cns) {
        if (c.e < now || c.s > top) continue;
        const inset = ws[c.lane] * 0.18;
        const yTop = yOf(Math.min(c.e, top));
        const yBottom = yOf(Math.max(c.s, now));
        g.fillStyle = CN_BODY[c.lane];
        g.fillRect(xs[c.lane] + inset, yTop, ws[c.lane] - inset * 2, yBottom - yTop);
        g.fillStyle = NOTE_COLOR[c.lane];
        if (c.head && c.s >= now) g.fillRect(xs[c.lane] + 1, yOf(c.s) - noteH, ws[c.lane] - 2, noteH);
        if (c.tail && c.e <= top) g.fillRect(xs[c.lane] + 1, yOf(c.e) - noteH, ws[c.lane] - 2, noteH);
      }
      for (const n of notes) {
        if (n.t < now || n.t > top) continue;
        g.fillStyle = NOTE_COLOR[n.lane];
        g.fillRect(xs[n.lane] + 1, Math.round(yOf(n.t)) - noteH, ws[n.lane] - 2, noteH);
      }

      // 判定ラインと鍵盤（色だけ）
      g.fillStyle = '#f43f5e';
      g.fillRect(0, judgeY, W, 3);
      for (let lane = 0; lane < 8; lane++) {
        g.fillStyle = lane === 0 ? '#3f1d27' : lane % 2 === 0 ? '#1e293b' : '#334155';
        g.fillRect(xs[lane] + 1, judgeY + 6, ws[lane] - 2, H - judgeY - 8);
      }
    },
  };
}
