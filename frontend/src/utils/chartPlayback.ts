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
  /** 打鍵イベントの出どころ（通常ノーツ i → i、CN j → -(j + 1)）。譜面オプションで置き換えたレーンを引くのに使う */
  hitSources: Int32Array;
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
  // [時刻, 元のキー, 出どころ（通常ノーツ i → i、CN j → -(j + 1)）]
  const hits: [number, number, number][] = [];
  const judges: number[] = [];
  for (let i = 0; i < noteTimes.length; i++) {
    hits.push([noteTimes[i], noteKeys[i], i]);
    judges.push(noteTimes[i]);
  }
  for (let i = 0; i < cnStartTimes.length; i++) {
    if (cnFlags[i] & 1) { hits.push([cnStartTimes[i], cnKeys[i], -(i + 1)]); judges.push(cnStartTimes[i]); }
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
    hitSources: Int32Array.from(hits, h => h[2]),
    judgeTimes: Float64Array.from(judges),
    tickToTime,
    timeToTick,
    bpmAt,
  };
}

// ── 譜面オプション ─────────────────────────────────────────

/** 譜面オプション（皿は動かさない。鍵盤 1〜7 の並べ替え）。 */
export type ChartOption = 'off' | 'mirror' | 'random' | 'rrandom' | 'srandom';

/**
 * 鍵盤の並び。左のレーンから順に「元の譜面の何番の鍵盤か」を書いた 7 文字（実機のランダム表記と同じ）。
 * 正規 = "1234567"、MIRROR = "7654321"。
 */
export const OFF_PATTERN = '1234567';
export const MIRROR_PATTERN = '7654321';

/** 1〜7 を 1 回ずつ使った 7 文字か。 */
export function isValidPattern(p: string): boolean {
  return /^[1-7]{7}$/.test(p) && new Set(p).size === 7;
}

/** 再現できる乱数（mulberry32）。S-RANDOM の「引き直し」を種で持つため。 */
export function seededRandom(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** RANDOM の並びを引く（7! 通りから一様に）。 */
export function randomPattern(rand: () => number = Math.random): string {
  const keys = ['1', '2', '3', '4', '5', '6', '7'];
  for (let i = keys.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [keys[i], keys[j]] = [keys[j], keys[i]];
  }
  return keys.join('');
}

/** R-RANDOM の並びを引く（正規またはミラーを 1〜6 レーンずらしたもの。ずらし 0 は出さない）。 */
export function rRandomPattern(rand: () => number = Math.random): string {
  const base = rand() < 0.5 ? OFF_PATTERN : MIRROR_PATTERN;
  const shift = 1 + Math.floor(rand() * 6);
  return base.slice(shift) + base.slice(0, shift);
}

/** オプションを当てたあとの各オブジェクトのレーン（0 = 皿、1〜7 = 鍵盤。左から数える）。 */
export interface LaneAssignment {
  noteLanes: Uint8Array;
  cnLanes: Uint8Array;
  /** {@link ChartTimeline.hitTimes} と同じ並び */
  hitLanes: Uint8Array;
}

/**
 * 【関数の役割】 譜面オプションを当てたレーンを決める。
 *
 * - 正規・MIRROR・RANDOM・R-RANDOM: 鍵盤の並び（pattern）どおりに置き換える
 * - S-RANDOM: ノーツごとに鍵盤を選び直す。同じタイミングのノーツどうし・押している最中の CN とは重ねない。
 *   CN は小節ごとに区切られているので、先頭から終端までを 1 本にまとめて同じレーンに置く
 *
 * @param pattern 鍵盤の並び（S-RANDOM では使わない）
 * @param seed    S-RANDOM の乱数の種
 */
export function assignLanes(tl: ChartTimeline, option: ChartOption, pattern: string, seed: number): LaneAssignment {
  const noteLanes = new Uint8Array(tl.noteKeys.length);
  const cnLanes = new Uint8Array(tl.cnKeys.length);

  if (option !== 'srandom') {
    const p = option === 'off' ? OFF_PATTERN : option === 'mirror' ? MIRROR_PATTERN : (isValidPattern(pattern) ? pattern : OFF_PATTERN);
    // 元の鍵盤 → レーン
    const laneOf = new Uint8Array(8);
    for (let lane = 1; lane <= 7; lane++) laneOf[Number(p[lane - 1])] = lane;
    for (let i = 0; i < noteLanes.length; i++) noteLanes[i] = tl.noteKeys[i] === 0 ? 0 : laneOf[tl.noteKeys[i]];
    for (let i = 0; i < cnLanes.length; i++) cnLanes[i] = tl.cnKeys[i] === 0 ? 0 : laneOf[tl.cnKeys[i]];
  } else {
    assignSRandom(tl, seededRandom(seed), noteLanes, cnLanes);
  }

  const hitLanes = Uint8Array.from(tl.hitSources, src => (src >= 0 ? noteLanes[src] : cnLanes[-src - 1]));
  return { noteLanes, cnLanes, hitLanes };
}

/**
 * textage の CN の長さは実際より 2 単位（6 tick）短く書かれている（128 → 126、48 → 46）。
 * 離すタイミングは終端 + 6 tick のグリッド上なので、そこまで同じレーンに他のノーツを置かない。
 */
export const CN_RELEASE_PAD = 6;

/**
 * CN の区間（小節ごとに区切られている）を 1 本ずつにまとめる。
 * 同じ鍵盤で、前の区間の終わりから続き、前の区間に終端が無いものをつなぐ。
 * groupOf は区間 → まとまりの添字、groups はまとまりの開始・終了 tick と鍵盤。
 */
export function groupCharges(tl: ChartTimeline): { groupOf: Int32Array; groups: { start: number; end: number; key: number }[] } {
  const groupOf = new Int32Array(tl.cnKeys.length).fill(-1);
  const groups: { start: number; end: number; key: number }[] = [];
  const open = new Map<number, number>(); // 元の鍵盤 → まだ終端の来ていない CN のまとまり
  for (let i = 0; i < tl.cnKeys.length; i++) {
    const key = tl.cnKeys[i];
    const g = open.get(key);
    if (g !== undefined && !(tl.cnFlags[i] & 1) && Math.abs(groups[g].end - tl.cnStartTicks[i]) < 1e-6) {
      groupOf[i] = g;
      groups[g].end = tl.cnEndTicks[i];
    } else {
      groupOf[i] = groups.length;
      groups.push({ start: tl.cnStartTicks[i], end: tl.cnEndTicks[i], key });
    }
    if (tl.cnFlags[i] & 2) open.delete(key);
    else open.set(key, groupOf[i]);
  }
  return { groupOf, groups };
}

function assignSRandom(tl: ChartTimeline, rand: () => number, noteLanes: Uint8Array, cnLanes: Uint8Array) {
  const { groupOf, groups } = groupCharges(tl);

  // タイミング順にレーンを決める（同じ tick では CN を先に置く）
  type Ev = { tick: number; kind: 0 | 1; idx: number };
  const evs: Ev[] = [];
  groups.forEach((g, idx) => { if (g.key !== 0) evs.push({ tick: g.start, kind: 0, idx }); });
  for (let i = 0; i < tl.noteKeys.length; i++) if (tl.noteKeys[i] !== 0) evs.push({ tick: tl.noteTicks[i], kind: 1, idx: i });
  evs.sort((a, b) => a.tick - b.tick || a.kind - b.kind);

  const heldUntil = new Float64Array(8).fill(-Infinity); // レーン → CN の終わり
  const groupLane = new Uint8Array(groups.length);
  let curTick = NaN;
  let usedAtTick = new Set<number>();
  for (const ev of evs) {
    if (ev.tick !== curTick) { curTick = ev.tick; usedAtTick = new Set(); }
    const free: number[] = [];
    for (let lane = 1; lane <= 7; lane++) {
      if (!usedAtTick.has(lane) && heldUntil[lane] < ev.tick) free.push(lane);
    }
    // 空きが無い（譜面側で 7 鍵を超える重なり）ときは重なりを許して選ぶ
    const pool = free.length > 0 ? free : [1, 2, 3, 4, 5, 6, 7];
    const lane = pool[Math.floor(rand() * pool.length)];
    usedAtTick.add(lane);
    if (ev.kind === 0) {
      groupLane[ev.idx] = lane;
      heldUntil[lane] = groups[ev.idx].end + CN_RELEASE_PAD;
    } else {
      noteLanes[ev.idx] = lane;
    }
  }
  for (let i = 0; i < cnLanes.length; i++) {
    const g = groupOf[i];
    cnLanes[i] = groups[g].key === 0 ? 0 : groupLane[g];
  }
  // 皿はそのまま（noteLanes は 0 で初期化済み）
}
