/**
 * chartPlayback.ts
 *
 * 譜面再生（components/ChartPlayer.vue）用のデータ変換。
 * API（GET /api/analysis/chart-playback）の tick 単位のデータから、tick ⇔ 秒の換算と、
 * 描画で二分探索できるよう位置の昇順に並べた配列を作る。
 *
 * tick は 4/4 の 1 小節 = 384（4 分音符 = 96）。1 tick = 5 / (8 × BPM) 秒（= 60 / (96 × BPM)）。
 */

/** API のレスポンス。 */
export interface ChartPlaybackData {
  textage: string;
  title: string;
  difficulty: string;
  level: number | null;
  notes: number;
  bpm: string;
  firstMeasure: number;
  endTick: number;
  /** 各小節の頭の tick */
  measures: number[];
  /** [tick, BPM]。先頭は tick 0 */
  bpmChanges: [number, number][];
  /** 通常ノーツ [tick, キー]（0 = 皿、1〜7 = 鍵盤）。tick の昇順 */
  objects: [number, number][];
  /** CN [開始 tick, 終了 tick, キー, フラグ(1 = 先頭あり, 2 = 終端あり)]。開始 tick の昇順 */
  charges: [number, number, number, number][];
}

/** 再生・描画用に組み立てた譜面。位置の配列はすべて昇順。 */
export interface ChartTimeline {
  firstMeasure: number;
  totalTime: number;
  /** いちばん長く続く BPM（拍基準スクロールの速さの基準） */
  mainBpm: number;
  measureTicks: Float64Array;
  measureTimes: Float64Array;
  bpmTicks: Float64Array;
  bpmTimes: Float64Array;
  bpmValues: Float64Array;
  noteTicks: Float64Array;
  noteTimes: Float64Array;
  noteKeys: Uint8Array;
  cnStartTicks: Float64Array;
  cnStartTimes: Float64Array;
  cnEndTicks: Float64Array;
  cnEndTimes: Float64Array;
  cnKeys: Uint8Array;
  cnFlags: Uint8Array;
  /** CN の最大の長さ（開始位置から探すときの戻り幅） */
  cnMaxLenTicks: number;
  cnMaxLenTimes: number;
  /** 打鍵（通常ノーツと CN の先頭）の時刻とキー。時刻の昇順 */
  hitTimes: Float64Array;
  hitKeys: Uint8Array;
  /** ノーツ数に数えるもの（通常ノーツ・CN の先頭と終端）の時刻。昇順 */
  judgeTimes: Float64Array;
  tickToTime(tick: number): number;
  timeToTick(time: number): number;
  bpmAt(tick: number): number;
}

const secPerTick = (bpm: number) => 5 / (8 * bpm);

/** BPM の表示（整数ならそのまま、小数は 1 桁）。 */
export function formatBpmLabel(bpm: number): string {
  return Number.isInteger(bpm) ? String(bpm) : bpm.toFixed(1);
}

export function buildChartTimeline(data: ChartPlaybackData): ChartTimeline {
  // テンポの区間: [開始 tick, BPM, 開始時刻]
  const changes = data.bpmChanges.length > 0 ? data.bpmChanges : [[0, 120] as [number, number]];
  const segTick: number[] = [];
  const segBpm: number[] = [];
  const segTime: number[] = [];
  let time = 0;
  for (let i = 0; i < changes.length; i++) {
    const [tick, bpm] = changes[i];
    const b = bpm > 0 ? bpm : segBpm[segBpm.length - 1] ?? 120;
    if (i > 0) time += (tick - segTick[i - 1]) * secPerTick(segBpm[i - 1]);
    segTick.push(i === 0 ? 0 : tick);
    segBpm.push(b);
    segTime.push(time);
  }

  // 区間の探索（曲頭より前は最初の BPM で延長）
  const segOfTick = (tick: number) => {
    let lo = 0, hi = segTick.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (segTick[mid] <= tick) lo = mid; else hi = mid - 1;
    }
    return lo;
  };
  const segOfTime = (t: number) => {
    let lo = 0, hi = segTime.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (segTime[mid] <= t) lo = mid; else hi = mid - 1;
    }
    return lo;
  };
  const tickToTime = (tick: number) => {
    const i = segOfTick(tick);
    return segTime[i] + (tick - segTick[i]) * secPerTick(segBpm[i]);
  };
  const timeToTick = (t: number) => {
    const i = segOfTime(t);
    return segTick[i] + (t - segTime[i]) / secPerTick(segBpm[i]);
  };
  const bpmAt = (tick: number) => segBpm[segOfTick(tick)];

  const totalTime = tickToTime(data.endTick);

  // いちばん長く続く BPM
  const durByBpm = new Map<number, number>();
  for (let i = 0; i < segTick.length; i++) {
    const end = i + 1 < segTime.length ? segTime[i + 1] : totalTime;
    durByBpm.set(segBpm[i], (durByBpm.get(segBpm[i]) ?? 0) + Math.max(end - segTime[i], 0));
  }
  let mainBpm = segBpm[0];
  let best = -1;
  for (const [bpm, dur] of durByBpm) {
    if (dur > best) { best = dur; mainBpm = bpm; }
  }

  const toTimes = (ticks: ArrayLike<number>) => Float64Array.from(ticks as number[], tickToTime);

  const measureTicks = Float64Array.from(data.measures);
  const noteTicks = Float64Array.from(data.objects, o => o[0]);
  const noteKeys = Uint8Array.from(data.objects, o => o[1]);
  const cnStartTicks = Float64Array.from(data.charges, c => c[0]);
  const cnEndTicks = Float64Array.from(data.charges, c => c[1]);
  const cnKeys = Uint8Array.from(data.charges, c => c[2]);
  const cnFlags = Uint8Array.from(data.charges, c => c[3]);
  const cnStartTimes = toTimes(cnStartTicks);
  const cnEndTimes = toTimes(cnEndTicks);
  let cnMaxLenTicks = 0;
  let cnMaxLenTimes = 0;
  for (let i = 0; i < cnStartTicks.length; i++) {
    cnMaxLenTicks = Math.max(cnMaxLenTicks, cnEndTicks[i] - cnStartTicks[i]);
    cnMaxLenTimes = Math.max(cnMaxLenTimes, cnEndTimes[i] - cnStartTimes[i]);
  }
  const noteTimes = toTimes(noteTicks);

  // 打鍵イベント（通常ノーツ + CN の先頭）と、ノーツ数に数えるもの（+ CN の終端）
  const hits: [number, number][] = [];
  const judges: number[] = [];
  for (let i = 0; i < noteTimes.length; i++) {
    hits.push([noteTimes[i], noteKeys[i]]);
    judges.push(noteTimes[i]);
  }
  for (let i = 0; i < cnStartTimes.length; i++) {
    if (cnFlags[i] & 1) { hits.push([cnStartTimes[i], cnKeys[i]]); judges.push(cnStartTimes[i]); }
    if (cnFlags[i] & 2) judges.push(cnEndTimes[i]);
  }
  hits.sort((a, b) => a[0] - b[0]);
  judges.sort((a, b) => a - b);

  return {
    firstMeasure: data.firstMeasure,
    totalTime,
    mainBpm,
    measureTicks,
    measureTimes: toTimes(measureTicks),
    bpmTicks: Float64Array.from(segTick),
    bpmTimes: Float64Array.from(segTime),
    bpmValues: Float64Array.from(segBpm),
    noteTicks,
    noteTimes,
    noteKeys,
    cnStartTicks,
    cnStartTimes,
    cnEndTicks,
    cnEndTimes,
    cnKeys,
    cnFlags,
    cnMaxLenTicks,
    cnMaxLenTimes,
    hitTimes: Float64Array.from(hits, h => h[0]),
    hitKeys: Uint8Array.from(hits, h => h[1]),
    judgeTimes: Float64Array.from(judges),
    tickToTime,
    timeToTick,
    bpmAt,
  };
}
