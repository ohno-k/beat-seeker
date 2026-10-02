<script setup lang="ts">
/**
 * ChartPlayer.vue
 *
 * 【コンポーネントの役割】
 * 譜面分析ページの「譜面再生」。textage の譜面をゲーム画面のように上から降らせて再生する（音源は無し、打鍵音のみ）。
 *
 * 【データ】
 * GET /api/analysis/chart-playback?textage=... が返す tick 単位（4/4 の 1 小節 = 384）のノーツ・CN・BPM 変化・小節線。
 * 秒への換算は 1 tick = 5 / (8 × BPM) 秒（textage の bms2jsh.js と同じ）。
 * データは表示したときに取りに行き、下の RANDOM カード（判別・配置評価）はすぐ計算した状態で出す
 * （サーバーが textage のページを DB に保存しているので、textage へは取りに行かないことが多い）。
 * 譜面再生のカードは「譜面を再生する」か、配置評価の「この並びで再生」を押したときに開く。後者は並びを当てて再生し、画面を譜面再生に合わせる。
 *
 * 【表示】
 * - スクロールは「ソフラン再現」（拍基準。BPM が上がると速く流れる＝実機と同じ）と「一定速度」（時間基準）の切り替え
 * - 表示時間（主 BPM でノーツが画面上端から判定ラインまで落ちる秒数。緑数字の目安も併記）・再生速度・1P/2P・打鍵音
 * - 描画は canvas。requestAnimationFrame の間だけ動き、タブが隠れたら一時停止する
 * - モバイル: 幅は親に合わせ、高さは画面の約 6 割。canvas のタップで再生/一時停止（縦スクロールは妨げない）。
 *   マウスは canvas の上下ドラッグで前後に送れる
 */
import { ref, shallowRef, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { mdiFastForward, mdiPause, mdiPlay, mdiRewind, mdiSkipPrevious } from '@mdi/js';
import { API_BASE } from '../composables/useAuth';
import {
  formatBpmLabel, buildChartTimeline, assignLanes, randomPattern, rRandomPattern, isValidPattern, MIRROR_PATTERN, OFF_PATTERN,
  type ChartPlaybackData, type ChartTimeline, type ChartOption,
} from '../utils/chartPlayback';
import PatternChips from './PatternChips.vue';
import RandomPanel from './RandomPanel.vue';

const props = defineProps<{
  textage: string;
}>();

// ── 読み込み ──────────────────────────────────────────────
const opened = ref(false);    // 譜面再生のカードを開いたか（RANDOM の評価はデータだけあれば出す）
const loading = ref(false);
const error = ref('');
const timeline = shallowRef<ChartTimeline | null>(null);
let loadPromise: Promise<void> | null = null;

/** 再生データを取得する（RANDOM の評価のため、表示したらすぐ呼ぶ。2 回目以降は同じ取得を待つだけ） */
function loadData(): Promise<void> {
  loadPromise ??= (async () => {
    loading.value = true;
    error.value = '';
    try {
      const res = await fetch(`${API_BASE}/api/analysis/chart-playback?textage=${encodeURIComponent(props.textage)}`);
      const data = await res.json().catch(() => ({}));
      if (!res.ok || data.error) {
        error.value = data.error ?? `譜面データを読み込めませんでした（${res.status}）`;
        loadPromise = null; // 開き直したときに取り直せるように
        return;
      }
      timeline.value = buildChartTimeline(data as ChartPlaybackData);
      loopA.value = 0;
      loopB.value = Math.min(3, timeline.value.measureTicks.length - 1);
    } catch {
      error.value = '通信エラーで譜面データを読み込めませんでした';
      loadPromise = null;
    } finally {
      loading.value = false;
    }
  })();
  return loadPromise;
}

/** 譜面再生のカードを開く（初回は canvas を用意して曲頭へ）。 */
async function open() {
  if (opened.value && timeline.value) return;
  opened.value = true;
  await loadData();
  if (!timeline.value) return;
  await nextTick(); // canvas を表示してから大きさを測る
  setupCanvas();
  seek(LEAD_IN);
}

const playerCardRef = ref<HTMLDivElement | null>(null);

/** RANDOM の評価から: 並びを当てて譜面再生を開き、再生して画面を譜面再生に合わせる。 */
async function applyAndPlay(p: string) {
  applyPattern(p);
  await open();
  if (!timeline.value) return;
  playerCardRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  play();
}

onMounted(loadData);

// ── 設定 ──────────────────────────────────────────────────
const SETTINGS_KEY = 'chartPlayer.settings';
interface Settings {
  visibleSec: number; rate: number; mode: 'beat' | 'time'; side: 1 | 2; sound: boolean;
  /** ノーツの太さ（縦の厚み、CSS px） */
  noteSize: number;
  /** ノーツの色: lane = 落ちてくるレーンの色（実機と同じ）、key = 元の鍵盤の色（RANDOM で元の白鍵がどこに来たか見える） */
  noteColor: 'lane' | 'key';
  /** 譜面オプションと、RANDOM / R-RANDOM の鍵盤の並び（左のレーンから元の鍵盤番号） */
  option: ChartOption; pattern: string;
}
const DEFAULTS: Settings = {
  visibleSec: 1.2, rate: 1, mode: 'beat', side: 1, sound: false, noteSize: 7, noteColor: 'lane', option: 'off', pattern: OFF_PATTERN,
};
function loadSettings(): Settings {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY);
    if (raw) return { ...DEFAULTS, ...JSON.parse(raw) };
  } catch { /* 読めなければ既定値 */ }
  return { ...DEFAULTS };
}
const settings = ref<Settings>(loadSettings());
// 譜面オプションは譜面ごとに選び直すものなので、新しい譜面を開くたびに正規に戻す（ほかの設定は引き継ぐ）。
// 親は :key="textage" で譜面ごとにこのコンポーネントを作り直すので、ここは譜面を開くたびに通る
settings.value.option = 'off';
watch(settings, (s) => {
  try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(s)); } catch { /* 保存できなくても動作は続ける */ }
  draw();
}, { deep: true });

const RATES = [0.25, 0.5, 0.75, 1, 1.25, 1.5];
// 緑数字 ≒ 表示時間(ms) × 0.6（60fps 基準の目安）
const greenNumber = computed(() => Math.round(settings.value.visibleSec * 600));

// ── 譜面オプション ─────────────────────────────────────────
const OPTIONS: { value: ChartOption; label: string; title: string }[] = [
  { value: 'off', label: '正規', title: 'OFF（正規）' },
  { value: 'mirror', label: 'MIR', title: 'MIRROR' },
  { value: 'random', label: 'RAN', title: 'RANDOM' },
  { value: 'rrandom', label: 'R-RAN', title: 'R-RANDOM' },
  { value: 'srandom', label: 'S-RAN', title: 'S-RANDOM' },
];
/** S-RANDOM の乱数の種（「引き直す」で変わる。保存はしない）。 */
const sRandomSeed = ref(Math.floor(Math.random() * 2 ** 31));
/** 並びの入力欄（RANDOM のときは直接打ち込める。正しい 7 文字になったら反映） */
const patternInput = ref(settings.value.pattern);

function setOption(o: ChartOption) {
  const s = settings.value;
  if (o === 'random' && s.option !== 'random') s.pattern = randomPattern();
  if (o === 'rrandom' && s.option !== 'rrandom') s.pattern = rRandomPattern();
  s.option = o;
  patternInput.value = shownPattern.value;
}

/** 同じオプションのまま引き直す。 */
function reroll() {
  const s = settings.value;
  if (s.option === 'random') s.pattern = randomPattern();
  else if (s.option === 'rrandom') s.pattern = rRandomPattern();
  else if (s.option === 'srandom') sRandomSeed.value = Math.floor(Math.random() * 2 ** 31);
  patternInput.value = shownPattern.value;
}

/** 配置評価で選んだ並びを RANDOM として当てる。 */
function applyPattern(p: string) {
  settings.value.option = 'random';
  settings.value.pattern = p;
  patternInput.value = p;
}

function onPatternInput(e: Event) {
  const v = (e.target as HTMLInputElement).value.replace(/[^1-7]/g, '').slice(0, 7);
  patternInput.value = v;
  if (isValidPattern(v)) settings.value.pattern = v;
}

/** 今の鍵盤の並び（S-RANDOM は並びが無いので空） */
const shownPattern = computed(() => {
  const s = settings.value;
  if (s.option === 'off') return OFF_PATTERN;
  if (s.option === 'mirror') return MIRROR_PATTERN;
  if (s.option === 'srandom') return '';
  return isValidPattern(s.pattern) ? s.pattern : OFF_PATTERN;
});
const patternInvalid = computed(() => settings.value.option === 'random' && !isValidPattern(patternInput.value));

/** オプションを当てたレーン。描画・打鍵処理はこれを引く */
const lanes = computed(() => {
  const tl = timeline.value;
  if (!tl) return null;
  return assignLanes(tl, settings.value.option, settings.value.pattern, sRandomSeed.value);
});
watch(lanes, () => {
  laneHit.fill(-99);
  draw();
});

/** BSS（皿の CN）の終点の時刻。昇順。終点も皿を回すので打鍵音を鳴らす（判定ありの終端だけ） */
const bssEndTimes = computed(() => {
  const tl = timeline.value;
  if (!tl) return new Float64Array(0);
  const ends: number[] = [];
  for (let i = 0; i < tl.cnKeys.length; i++) {
    if (tl.cnKeys[i] === 0 && (tl.cnFlags[i] & 2)) ends.push(tl.cnEndTimes[i]);
  }
  return Float64Array.from(ends.sort((a, b) => a - b));
});

// ── 再生状態 ──────────────────────────────────────────────
/** 曲頭の前に空ける秒数（最初のノーツが上から落ちてくるように）。 */
const LEAD_IN = -1.5;
let curTime = LEAD_IN;       // 譜面上の現在時刻（秒）。描画の毎フレームで使うのでリアクティブにしない
let lastFrame = 0;
let rafId = 0;
let hitIdx = 0;               // 次に判定ラインへ届く打鍵イベント
let bssEndIdx = 0;            // 次に判定ラインへ届く BSS の終点
const laneHit = new Float64Array(8).fill(-99);
const playing = ref(false);
const uiTime = ref(LEAD_IN);  // シークバーと時刻表示用（間引いて更新）
let uiUpdatedAt = 0;

const totalTime = computed(() => timeline.value?.totalTime ?? 0);

// ── 区間リピート（A〜B 小節を繰り返す） ─────────────────────────
/** 繰り返しの頭に戻るとき、A の小節頭より何秒前から流すか（ノーツが上から落ちてくるように） */
const LOOP_PREROLL = 1.0;
const loopOn = ref(false);
const loopA = ref(0);   // 小節の添字（measureTicks の何番目か）
const loopB = ref(0);
const measureOptions = computed(() => {
  const tl = timeline.value;
  if (!tl) return [];
  return Array.from(tl.measureTicks, (_, i) => ({ index: i, label: tl.firstMeasure + i }));
});
/** 区間の [開始, 終了) 秒 */
function loopRange(tl: ChartTimeline): [number, number] {
  const a = Math.min(loopA.value, loopB.value);
  const b = Math.max(loopA.value, loopB.value);
  const end = b + 1 < tl.measureTimes.length ? tl.measureTimes[b + 1] : tl.totalTime;
  return [tl.measureTimes[a], end];
}
function seekLoopStart() {
  const tl = timeline.value;
  if (!tl) return;
  seek(loopRange(tl)[0] - LOOP_PREROLL);
}
/** 今いる小節の添字 */
function currentMeasureIndex(): number {
  const tl = timeline.value;
  if (!tl) return 0;
  return Math.max(upperBound(tl.measureTimes, curTime + 1e-6) - 1, 0);
}
function setLoopPoint(which: 'a' | 'b') {
  const i = currentMeasureIndex();
  if (which === 'a') { loopA.value = i; if (loopB.value < i) loopB.value = i; }
  else { loopB.value = i; if (loopA.value > i) loopA.value = i; }
}
function setLoopOn(on: boolean) {
  loopOn.value = on;
  const tl = timeline.value;
  if (on && tl) {
    const [start, end] = loopRange(tl);
    if (curTime < start - LOOP_PREROLL || curTime >= end) seekLoopStart();
  }
}
watch([loopA, loopB], () => { if (loopA.value > loopB.value) loopB.value = loopA.value; });

function play() {
  const tl = timeline.value;
  if (!tl) return;
  if (curTime >= tl.totalTime) seek(LEAD_IN);
  if (loopOn.value) {
    const [start, end] = loopRange(tl);
    if (curTime < start - LOOP_PREROLL || curTime >= end) seekLoopStart();
  }
  ensureAudio();
  playing.value = true;
  lastFrame = performance.now();
  cancelAnimationFrame(rafId);
  rafId = requestAnimationFrame(frame);
}

function pause() {
  playing.value = false;
  cancelAnimationFrame(rafId);
  uiTime.value = curTime;
  draw();
}

function togglePlay() {
  if (playing.value) pause();
  else play();
}

function seek(t: number) {
  const tl = timeline.value;
  if (!tl) return;
  curTime = Math.min(Math.max(t, LEAD_IN), tl.totalTime);
  hitIdx = lowerBound(tl.hitTimes, curTime);
  bssEndIdx = lowerBound(bssEndTimes.value, curTime);
  laneHit.fill(-99);
  uiTime.value = curTime;
  draw();
}

/** 小節単位で前後に送る（dir = -1 / +1）。再生中の「前へ」は今の小節の頭が近ければ 1 つ前へ。 */
function stepMeasure(dir: -1 | 1) {
  const tl = timeline.value;
  if (!tl) return;
  const i = upperBound(tl.measureTimes, curTime + 1e-6) - 1;
  if (dir > 0) {
    seek(tl.measureTimes[Math.min(i + 1, tl.measureTimes.length - 1)] ?? tl.totalTime);
  } else {
    const head = tl.measureTimes[Math.max(i, 0)] ?? 0;
    const target = curTime - head < 0.3 ? tl.measureTimes[Math.max(i - 1, 0)] : head;
    seek(target ?? 0);
  }
}

function frame(now: number) {
  const tl = timeline.value;
  if (!tl || !playing.value) return;
  // タブ復帰直後などの大きな飛びは詰める
  const dt = Math.min((now - lastFrame) / 1000, 0.1);
  lastFrame = now;
  curTime += dt * settings.value.rate;
  if (loopOn.value && curTime >= loopRange(tl)[1]) seekLoopStart();
  processHits(tl);
  if (curTime >= tl.totalTime) {
    curTime = tl.totalTime;
    draw();
    pause();
    return;
  }
  if (now - uiUpdatedAt > 100) {
    uiTime.value = curTime;
    uiUpdatedAt = now;
  }
  draw();
  rafId = requestAnimationFrame(frame);
}

/**
 * 判定ラインを越えた打鍵イベントを処理（レーンを光らせ、打鍵音を鳴らす）。
 * 皿と鍵盤が同時なら皿の音と鍵盤の音を両方鳴らす。BSS の終点も皿の音を鳴らす。
 */
function processHits(tl: ChartTimeline) {
  let keyClicks = 0;
  let scratchClicks = 0;
  const la = lanes.value;
  while (hitIdx < tl.hitTimes.length && tl.hitTimes[hitIdx] <= curTime) {
    const key = la ? la.hitLanes[hitIdx] : tl.hitKeys[hitIdx];
    laneHit[key] = tl.hitTimes[hitIdx];
    if (curTime - tl.hitTimes[hitIdx] < 0.08) {
      if (key === 0) scratchClicks++;
      else keyClicks++;
    }
    hitIdx++;
  }
  const bssEnds = bssEndTimes.value;
  while (bssEndIdx < bssEnds.length && bssEnds[bssEndIdx] <= curTime) {
    laneHit[0] = bssEnds[bssEndIdx];
    if (curTime - bssEnds[bssEndIdx] < 0.08) scratchClicks++;
    bssEndIdx++;
  }
  if (!settings.value.sound) return;
  if (keyClicks > 0) playClick(keyClicks, false);
  if (scratchClicks > 0) playClick(scratchClicks, true);
}

// ── 打鍵音（WebAudio） ──────────────────────────────────────
let audio: AudioContext | null = null;
function ensureAudio() {
  if (!settings.value.sound) return;
  try {
    if (!audio) audio = new (window.AudioContext || (window as any).webkitAudioContext)();
    if (audio.state === 'suspended') audio.resume();
  } catch { audio = null; }
}
watch(() => settings.value.sound, (on) => { if (on) ensureAudio(); });

function playClick(count: number, scratch: boolean) {
  if (!audio) return;
  const t = audio.currentTime;
  const osc = audio.createOscillator();
  const gain = audio.createGain();
  osc.type = scratch ? 'triangle' : 'square';
  osc.frequency.value = scratch ? 420 : 1800;
  const vol = Math.min(0.05 + 0.02 * (count - 1), 0.12);
  gain.gain.setValueAtTime(vol, t);
  gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.045);
  osc.connect(gain).connect(audio.destination);
  osc.start(t);
  osc.stop(t + 0.05);
}

// ── canvas ────────────────────────────────────────────────
const wrapRef = ref<HTMLDivElement | null>(null);
const canvasRef = ref<HTMLCanvasElement | null>(null);
let ctx2d: CanvasRenderingContext2D | null = null;
let cssW = 0;
let cssH = 0;
let resizeObs: ResizeObserver | null = null;

function setupCanvas() {
  const canvas = canvasRef.value;
  const wrap = wrapRef.value;
  if (!canvas || !wrap) return;
  ctx2d = canvas.getContext('2d');
  resizeObs?.disconnect();
  resizeObs = new ResizeObserver(() => resize());
  resizeObs.observe(wrap);
  resize();
}

function resize() {
  const canvas = canvasRef.value;
  const wrap = wrapRef.value;
  if (!canvas || !wrap) return;
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  cssW = wrap.clientWidth;
  cssH = Math.round(Math.max(340, Math.min(window.innerHeight * 0.62, 620)));
  canvas.style.height = `${cssH}px`;
  canvas.width = Math.round(cssW * dpr);
  canvas.height = Math.round(cssH * dpr);
  ctx2d?.setTransform(dpr, 0, 0, dpr, 0, 0);
  draw();
}

// レーン: 0 = 皿、1〜7 = 鍵盤。幅の比は皿 1.7 / 白鍵 1.0 / 黒鍵 0.8
const LANE_WEIGHT = [1.7, 1, 0.8, 1, 0.8, 1, 0.8, 1];
const NOTE_COLOR = ['#f43f5e', '#e5e7eb', '#60a5fa', '#e5e7eb', '#60a5fa', '#e5e7eb', '#60a5fa', '#e5e7eb'];
const CN_BODY = ['rgba(244,63,94,0.45)', 'rgba(229,231,235,0.4)', 'rgba(96,165,250,0.45)', 'rgba(229,231,235,0.4)',
  'rgba(96,165,250,0.45)', 'rgba(229,231,235,0.4)', 'rgba(96,165,250,0.45)', 'rgba(229,231,235,0.4)'];

/** レーンの左端 x と幅（side: 1P は皿が左、2P は皿が右）。 */
function laneLayout(width: number, side: 1 | 2) {
  const laneW = Math.min(width - 16, 380);
  const left = (width - laneW) / 2;
  const total = LANE_WEIGHT.reduce((a, b) => a + b, 0);
  const order = side === 1 ? [0, 1, 2, 3, 4, 5, 6, 7] : [1, 2, 3, 4, 5, 6, 7, 0];
  const xs = new Array<number>(8);
  const ws = new Array<number>(8);
  let x = left;
  for (const lane of order) {
    const w = (LANE_WEIGHT[lane] / total) * laneW;
    xs[lane] = x;
    ws[lane] = w;
    x += w;
  }
  return { xs, ws, left, laneW };
}

function draw() {
  const g = ctx2d;
  const tl = timeline.value;
  if (!g || !tl || cssW === 0) return;
  const s = settings.value;
  const la = lanes.value;
  const W = cssW;
  const H = cssH;
  const judgeY = H - 56;
  const { xs, ws, left, laneW } = laneLayout(W, s.side);

  // 位置の座標系: ソフラン再現 = tick、一定速度 = 秒
  const beat = s.mode === 'beat';
  const nowTick = tl.timeToTick(curTime);
  const posNow = beat ? nowTick : curTime;
  const unitPx = beat
    ? judgeY / (s.visibleSec / (5 / (8 * tl.mainBpm)))   // 主 BPM で visibleSec 秒かけて落ちる
    : judgeY / s.visibleSec;
  const posTop = posNow + judgeY / unitPx;
  const yOf = (pos: number) => judgeY - (pos - posNow) * unitPx;

  // 背景
  g.fillStyle = '#05070d';
  g.fillRect(0, 0, W, H);
  for (let lane = 0; lane < 8; lane++) {
    g.fillStyle = lane === 0 ? '#0d1220' : (lane % 2 === 0 ? '#0a0e1a' : '#111827');
    g.fillRect(xs[lane], 0, ws[lane], H);
  }
  // レーンの光（判定ラインを越えた直後）
  for (let lane = 0; lane < 8; lane++) {
    const age = curTime - laneHit[lane];
    if (age < 0 || age > 0.15) continue;
    const alpha = 0.35 * (1 - age / 0.15);
    const grad = g.createLinearGradient(0, judgeY, 0, judgeY - 160);
    grad.addColorStop(0, lane === 0 ? `rgba(244,63,94,${alpha})` : `rgba(147,197,253,${alpha})`);
    grad.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = grad;
    g.fillRect(xs[lane], judgeY - 160, ws[lane], 160);
  }
  g.fillStyle = '#1f2937';
  for (let lane = 0; lane < 8; lane++) g.fillRect(xs[lane], 0, 1, judgeY);
  g.fillRect(left + laneW - 1, 0, 1, judgeY);

  // 小節線
  const mPos = beat ? tl.measureTicks : tl.measureTimes;
  g.fillStyle = '#4b5563';
  for (let i = lowerBound(mPos, posNow); i < mPos.length && mPos[i] <= posTop; i++) {
    g.fillRect(left, Math.round(yOf(mPos[i])), laneW, 1);
  }
  // BPM 変化
  const bPos = beat ? tl.bpmTicks : tl.bpmTimes;
  g.font = '600 10px ui-sans-serif, system-ui, sans-serif';
  g.textBaseline = 'bottom';
  for (let i = Math.max(lowerBound(bPos, posNow), 1); i < bPos.length && bPos[i] <= posTop; i++) {
    const y = Math.round(yOf(bPos[i]));
    g.fillStyle = '#22c55e';
    g.fillRect(left, y, laneW, 2);
    // レーンの右に余白があればその外、無ければ右端の鍵盤の上に書く（左端は皿のノーツと重なるので避ける）
    const label = formatBpmLabel(tl.bpmValues[i]);
    const outside = left + laneW + 3 + g.measureText(label).width <= W - 2;
    g.textAlign = outside ? 'left' : 'right';
    g.fillText(label, outside ? left + laneW + 3 : left + laneW - 3, y - 1);
    g.textAlign = 'left';
  }

  const noteH = s.noteSize;
  // CN（本体 → 先頭・終端）
  const cStart = beat ? tl.cnStartTicks : tl.cnStartTimes;
  const cEnd = beat ? tl.cnEndTicks : tl.cnEndTimes;
  const maxLen = beat ? tl.cnMaxLenTicks : tl.cnMaxLenTimes;
  for (let i = lowerBound(cStart, posNow - maxLen); i < cStart.length && cStart[i] <= posTop; i++) {
    if (cEnd[i] < posNow) continue;
    const lane = la ? la.cnLanes[i] : tl.cnKeys[i];
    const color = s.noteColor === 'key' ? tl.cnKeys[i] : lane;
    const flags = tl.cnFlags[i];
    const yTop = yOf(Math.min(cEnd[i], posTop + 1));
    const yBottom = yOf(Math.max(cStart[i], posNow));
    const inset = ws[lane] * 0.18;
    g.fillStyle = CN_BODY[color];
    g.fillRect(xs[lane] + inset, yTop, ws[lane] - inset * 2, yBottom - yTop);
    g.fillStyle = NOTE_COLOR[color];
    if (flags & 1 && cStart[i] >= posNow) g.fillRect(xs[lane] + 1, yOf(cStart[i]) - noteH, ws[lane] - 2, noteH);
    if (flags & 2 && cEnd[i] <= posTop) g.fillRect(xs[lane] + 1, yOf(cEnd[i]) - noteH, ws[lane] - 2, noteH);
    // 押している最中の CN はレーンを光らせ続ける
    if (cStart[i] < posNow && cEnd[i] >= posNow) laneHit[lane] = curTime;
  }

  // 通常ノーツ
  const nPos = beat ? tl.noteTicks : tl.noteTimes;
  for (let i = lowerBound(nPos, posNow); i < nPos.length && nPos[i] <= posTop; i++) {
    const lane = la ? la.noteLanes[i] : tl.noteKeys[i];
    g.fillStyle = NOTE_COLOR[s.noteColor === 'key' ? tl.noteKeys[i] : lane];
    g.fillRect(xs[lane] + 1, Math.round(yOf(nPos[i])) - noteH, ws[lane] - 2, noteH);
  }

  // 判定ライン・鍵盤
  g.fillStyle = '#f43f5e';
  g.fillRect(left, judgeY, laneW, 3);
  for (let lane = 0; lane < 8; lane++) {
    const lit = curTime - laneHit[lane] >= 0 && curTime - laneHit[lane] < 0.1;
    g.fillStyle = lane === 0
      ? (lit ? '#fb7185' : '#3f1d27')
      : lane % 2 === 0 ? (lit ? '#93c5fd' : '#1e293b') : (lit ? '#f8fafc' : '#334155');
    if (lane === 0) {
      const cx = xs[0] + ws[0] / 2;
      const r = Math.min(ws[0] * 0.42, 22);
      g.beginPath();
      g.arc(cx, judgeY + 28, r, 0, Math.PI * 2);
      g.fill();
    } else {
      const top = lane % 2 === 0 ? judgeY + 8 : judgeY + 20;
      g.fillRect(xs[lane] + 2, top, ws[lane] - 4, 26);
    }
  }
}

// 譜面の上に出す現在の BPM・小節・ノーツ数（uiTime と同じ間引きで更新。canvas に書くとレーンの上端を隠すため）
const hud = computed(() => {
  const tl = timeline.value;
  if (!tl) return null;
  const tick = tl.timeToTick(uiTime.value);
  return {
    bpm: formatBpmLabel(tl.bpmAt(tick)),
    measure: tl.firstMeasure + Math.max(upperBound(tl.measureTicks, tick) - 1, 0),
    lastMeasure: tl.firstMeasure + tl.measureTicks.length - 1,
    passed: upperBound(tl.judgeTimes, uiTime.value),
    total: tl.judgeTimes.length,
  };
});

// ── 入力 ─────────────────────────────────────────────────
// タップ/クリック = 再生・一時停止。マウスの上下ドラッグ = 前後に送る（タッチは縦スクロールを優先するので送らない）
let drag: { y: number; t: number; moved: boolean; mouse: boolean; wasPlaying: boolean } | null = null;
function onPointerDown(e: PointerEvent) {
  drag = { y: e.clientY, t: curTime, moved: false, mouse: e.pointerType === 'mouse', wasPlaying: playing.value };
}
function onPointerMove(e: PointerEvent) {
  const tl = timeline.value;
  if (!drag || !drag.mouse || !tl || (e.buttons & 1) === 0) return;
  const dy = e.clientY - drag.y;
  if (!drag.moved && Math.abs(dy) < 6) return;
  if (!drag.moved) {
    drag.moved = true;
    if (playing.value) pause();
  }
  // 下へドラッグ = 先へ（ノーツを引き下ろす感覚）。画面の高さで表示時間ぶん動く
  seek(drag.t + (dy / Math.max(cssH - 56, 1)) * settings.value.visibleSec);
}
function onPointerUp() {
  if (drag && !drag.moved) togglePlay();
  else if (drag?.wasPlaying) play();
  drag = null;
}

function onKeydown(e: KeyboardEvent) {
  if (!timeline.value) return;
  if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); togglePlay(); }
  else if (e.key === 'ArrowLeft') { e.preventDefault(); stepMeasure(-1); }
  else if (e.key === 'ArrowRight') { e.preventDefault(); stepMeasure(1); }
}

/** シークバー（v-slider）の値が変わったとき。値は秒。 */
function onSeekInput(value: number) {
  seek(Number(value));
}

function onVisibility() {
  if (document.hidden && playing.value) pause();
}

onMounted(() => document.addEventListener('visibilitychange', onVisibility));
onBeforeUnmount(() => {
  cancelAnimationFrame(rafId);
  resizeObs?.disconnect();
  document.removeEventListener('visibilitychange', onVisibility);
  audio?.close().catch(() => {});
});

// ── 表示ヘルパー ───────────────────────────────────────────
function fmtTime(sec: number): string {
  const s = Math.max(0, Math.floor(sec));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

function lowerBound(arr: ArrayLike<number>, x: number): number {
  let lo = 0;
  let hi = arr.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (arr[mid] < x) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}

function upperBound(arr: ArrayLike<number>, x: number): number {
  let lo = 0;
  let hi = arr.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (arr[mid] <= x) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}
</script>

<template>
  <div class="flex flex-col gap-4">
  <v-card>
  <div ref="playerCardRef" class="player-card p-4">
    <div class="player-head">
      <div class="text-xs font-medium text-slate-400 dark:text-slate-500">譜面再生</div>
      <v-btn v-if="!opened"
        type="button"
        color="primary"
        size="small"
        class="play-open"
        :prepend-icon="mdiPlay"
        @click="open">
        譜面を再生する
      </v-btn>
    </div>

    <template v-if="opened">
      <div v-if="loading" class="mt-3 flex items-center justify-center py-10 text-xs text-slate-400 dark:text-slate-500">
        <v-progress-circular indeterminate size="20" width="2" class="mr-2" />
        譜面データを読み込み中…
      </div>
      <v-alert v-else-if="error" type="error" density="compact" class="mt-3">{{ error }}</v-alert>

      <div v-show="timeline && !loading" class="mt-3">
        <div v-if="hud" class="player-hud text-xs tabular-nums text-slate-500 dark:text-slate-400">
          <span class="font-bold text-emerald-600 dark:text-emerald-400">BPM {{ hud.bpm }}</span>
          <span>小節 {{ hud.measure }}/{{ hud.lastMeasure }}</span>
          <span>{{ hud.passed }}/{{ hud.total }} notes</span>
        </div>
        <!-- 譜面（canvas）。タップで再生/一時停止 -->
        <div ref="wrapRef"
          class="player-stage rounded-md overflow-hidden bg-black focus:outline-none focus:ring-2 focus:ring-blue-500"
          tabindex="0"
          @keydown="onKeydown">
          <canvas ref="canvasRef"
            class="block w-full select-none cursor-pointer"
            @pointerdown="onPointerDown"
            @pointermove="onPointerMove"
            @pointerup="onPointerUp"
            @pointercancel="drag = null" />
        </div>

        <!-- 再生操作 -->
        <div class="player-controls mt-3">
          <div class="player-buttons">
            <v-btn type="button" icon variant="tonal" size="small" title="先頭へ" aria-label="先頭へ" @click="seek(LEAD_IN)">
              <v-icon :icon="mdiSkipPrevious" />
            </v-btn>
            <v-btn type="button" icon variant="tonal" size="small" title="前の小節 (←)" aria-label="前の小節" @click="stepMeasure(-1)">
              <v-icon :icon="mdiRewind" />
            </v-btn>
            <v-btn type="button" icon color="primary" :title="playing ? '一時停止 (Space)' : '再生 (Space)'"
              :aria-label="playing ? '一時停止' : '再生'" @click="togglePlay">
              <v-icon :icon="playing ? mdiPause : mdiPlay" />
            </v-btn>
            <v-btn type="button" icon variant="tonal" size="small" title="次の小節 (→)" aria-label="次の小節" @click="stepMeasure(1)">
              <v-icon :icon="mdiFastForward" />
            </v-btn>
            <span class="ml-auto text-xs tabular-nums text-slate-500 dark:text-slate-400">
              {{ fmtTime(uiTime) }} / {{ fmtTime(totalTime) }}
            </span>
          </div>
          <v-slider
            class="player-seek mt-2 w-full"
            color="primary"
            hide-details
            :min="LEAD_IN" :max="totalTime" :step="0.01"
            :model-value="uiTime"
            aria-label="再生位置"
            @update:model-value="onSeekInput" />
        </div>

        <!-- 設定 -->
        <div class="player-settings mt-3 text-xs text-slate-600 dark:text-slate-300">
          <div class="setting setting-slider setting-wide">
            <span class="setting-label">表示時間</span>
            <v-slider v-model="settings.visibleSec" :min="0.4" :max="3" :step="0.05" aria-label="表示時間" color="primary" hide-details class="setting-range" />
            <span class="slider-value tabular-nums whitespace-nowrap">{{ settings.visibleSec.toFixed(2) }}秒<span class="text-slate-400 dark:text-slate-500">（緑数字 約{{ greenNumber }}）</span></span>
          </div>
          <div class="setting setting-slider setting-wide">
            <span class="setting-label">ノーツの太さ</span>
            <v-slider v-model="settings.noteSize" :min="3" :max="16" :step="1" aria-label="ノーツの太さ" color="primary" hide-details class="setting-range" />
            <span class="slider-value tabular-nums whitespace-nowrap">{{ settings.noteSize }}px</span>
          </div>
          <div class="setting">
            <span class="setting-label">再生速度</span>
            <v-select v-model="settings.rate"
              :items="RATES.map((r) => ({ title: `×${r}`, value: r }))"
              aria-label="再生速度"
              density="compact"
              hide-details
              class="w-28 flex-none" />
          </div>
          <div class="setting">
            <span class="setting-label">スクロール</span>
            <v-btn-toggle v-model="settings.mode" mandatory color="primary" variant="outlined" divided density="compact">
              <v-btn type="button" value="beat" size="small">ソフラン再現</v-btn>
              <v-btn type="button" value="time" size="small">一定速度</v-btn>
            </v-btn-toggle>
          </div>
          <div class="setting setting-wide setting-option">
            <span class="setting-label">譜面</span>
            <div class="option-body">
              <v-btn-toggle :model-value="settings.option" mandatory color="primary" variant="outlined" divided density="compact" class="option-toggle">
                <v-btn v-for="o in OPTIONS" :key="o.value" :value="o.value" type="button" size="small" :title="o.title"
                  @click="setOption(o.value)">{{ o.label }}</v-btn>
              </v-btn-toggle>
              <div v-if="settings.option !== 'off'" class="option-detail">
                <template v-if="settings.option === 'random'">
                  <v-text-field :model-value="patternInput" type="text" inputmode="numeric" maxlength="7" aria-label="鍵盤の並び"
                    density="compact"
                    hide-details
                    class="pattern-input flex-none tabular-nums"
                    :error="patternInvalid"
                    @input="onPatternInput" />
                  <PatternChips v-if="!patternInvalid" :pattern="shownPattern" />
                </template>
                <PatternChips v-else-if="shownPattern" :pattern="shownPattern" />
                <span v-else class="text-slate-400 dark:text-slate-500">ノーツごとにランダム</span>
                <v-btn v-if="settings.option !== 'mirror'" type="button" variant="outlined" color="primary" size="small" @click="reroll">引き直す</v-btn>
                <span v-if="patternInvalid" class="text-red-500 dark:text-red-400">1〜7 を 1 回ずつ</span>
              </div>
            </div>
          </div>
          <div class="setting">
            <span class="setting-label">ノーツの色</span>
            <v-btn-toggle v-model="settings.noteColor" mandatory color="primary" variant="outlined" divided density="compact">
              <v-btn type="button" value="lane" size="small">レーン</v-btn>
              <v-btn type="button" value="key" size="small" title="元の白鍵を白、元の黒鍵を青で塗る">元の鍵盤</v-btn>
            </v-btn-toggle>
          </div>
          <div class="setting setting-wide setting-loop">
            <span class="setting-label">区間リピート</span>
            <div class="loop-body">
              <div class="loop-point">
                <span class="loop-tag">A</span>
                <v-select v-model="loopA" aria-label="区間の開始小節"
                  :items="measureOptions.map((m) => ({ title: `小節 ${m.label}`, value: m.index }))"
                  density="compact" hide-details class="loop-select flex-none" />
                <v-btn type="button" variant="outlined" color="primary" size="small" min-width="0" title="今の小節を A にする" @click="setLoopPoint('a')">今</v-btn>
              </div>
              <div class="loop-point">
                <span class="loop-tag">B</span>
                <v-select v-model="loopB" aria-label="区間の終了小節"
                  :items="measureOptions.map((m) => ({ title: `小節 ${m.label}`, value: m.index, props: { disabled: m.index < loopA } }))"
                  density="compact" hide-details class="loop-select flex-none" />
                <v-btn type="button" variant="outlined" color="primary" size="small" min-width="0" title="今の小節を B にする" @click="setLoopPoint('b')">今</v-btn>
              </div>
              <v-btn-toggle :model-value="loopOn ? 'on' : 'off'" mandatory color="primary" variant="outlined" divided density="compact">
                <v-btn type="button" value="on" size="small" @click="setLoopOn(true)">ON</v-btn>
                <v-btn type="button" value="off" size="small" @click="setLoopOn(false)">OFF</v-btn>
              </v-btn-toggle>
            </div>
          </div>
          <div class="setting">
            <span class="setting-label">サイド</span>
            <v-btn-toggle v-model="settings.side" mandatory color="primary" variant="outlined" divided density="compact">
              <v-btn type="button" :value="1" size="small">1P</v-btn>
              <v-btn type="button" :value="2" size="small">2P</v-btn>
            </v-btn-toggle>
          </div>
          <div class="setting">
            <span class="setting-label">打鍵音</span>
            <v-btn-toggle :model-value="settings.sound ? 'on' : 'off'" mandatory color="primary" variant="outlined" divided density="compact">
              <v-btn type="button" value="on" size="small" @click="settings.sound = true">ON</v-btn>
              <v-btn type="button" value="off" size="small" @click="settings.sound = false">OFF</v-btn>
            </v-btn-toggle>
          </div>
        </div>
        <p class="mt-2 text-[11px] text-slate-400 dark:text-slate-500">
          楽曲の音声は再生されません。譜面をタップ（クリック）で再生／一時停止<span class="pc-only">、マウスの上下ドラッグで前後に移動、←→キーで小節送り</span>できます。
          区間リピートは B の小節の終わりまで流すと、A の 1 秒前に戻ります。
        </p>
      </div>
    </template>
  </div>
  </v-card>

  <!-- RANDOM の判別・配置評価（譜面再生とは別のカード。データは表示時に読み込み、「この並びで再生」で譜面再生を開いて再生する） -->
  <v-card>
    <v-card-text>
    <div class="text-xs font-medium text-slate-400 dark:text-slate-500">RANDOM</div>
    <div v-if="loading" class="mt-3 flex items-center justify-center py-6 text-xs text-slate-400 dark:text-slate-500">
      <v-progress-circular indeterminate size="20" width="2" class="mr-2" />
      譜面データを読み込み中…
    </div>
    <v-alert v-else-if="error" type="error" density="compact" class="mt-3">{{ error }}</v-alert>
    <RandomPanel v-if="timeline && !loading" :timeline="timeline" :side="settings.side" :current-pattern="shownPattern"
      @apply="applyAndPlay" />
    </v-card-text>
  </v-card>
  </div>
</template>

<style scoped>
/* 「この並びで再生」で画面を合わせたとき、上部の固定ヘッダーに隠れないように */
.player-card { scroll-margin-top: 5rem; }
.player-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  min-height: 2rem;
}
.player-hud {
  display: flex;
  justify-content: space-between;
  gap: 0.75rem;
  max-width: 520px;
  margin: 0 auto 0.375rem;
}
.player-stage {
  /* PC の広い画面でもレーンの周りが間延びしないよう幅を抑えて中央に置く */
  max-width: 520px;
  margin: 0 auto;
  /* タップ時の青いハイライトを出さない。縦スクロール（pan-y）は妨げない */
  -webkit-tap-highlight-color: transparent;
  touch-action: pan-y;
}
.player-buttons {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.player-settings {
  display: grid;
  grid-template-columns: 1fr;
  gap: 0.6rem 1.25rem;
}
.setting {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  min-width: 0;
}
.setting-label {
  flex: none;
  width: 5rem;
  font-weight: 600;
  color: rgb(100 116 139);
}
.setting-range { flex: 1; min-width: 0; }
/* スマホ幅では値の表示（「1.20秒（緑数字 約720）」）を次の行へ送り、スライダーに行の幅を全部使わせる */
.setting-slider { flex-wrap: wrap; row-gap: 0.1rem; }
/* 幅の狭いスマホでは譜面オプションの見出しを上に置き、5 つのボタンに行の幅を全部使わせる */
@media (max-width: 419px) {
  .setting-option { flex-wrap: wrap; row-gap: 0.4rem; }
  .setting-option .option-body { flex-basis: 100%; }
  .setting-option .option-body .option-toggle { max-width: none; }
}
@media (max-width: 639px) {
  .setting-slider .setting-range { flex-basis: calc(100% - 5.6rem); }
  .setting-slider .slider-value { flex-basis: 100%; padding-left: 5.6rem; }
}

.option-body {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 0.75rem;
  flex: 1;
  min-width: 0;
}
/* 譜面オプションは 5 つ並ぶので、スマホ幅でも 1 行に収まるよう行いっぱいに等分する */
.option-body .option-toggle { display: flex; flex: 1 1 16rem; max-width: 20rem; }
.option-body .option-toggle .v-btn { flex: 1 1 0; min-width: 0; padding-left: 0.2rem; padding-right: 0.2rem; }
.option-detail {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.pattern-input { width: 7.5rem; }
.pattern-input :deep(input) { letter-spacing: 0.12em; font-weight: 700; }
.loop-select { width: 8.5rem; }

.loop-body {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 0.75rem;
  flex: 1;
  min-width: 0;
}
.loop-point { display: inline-flex; align-items: center; gap: 0.3rem; }
.loop-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.2rem;
  height: 1.2rem;
  border-radius: 9999px;
  font-weight: 700;
  color: white;
  background: rgb(100 116 139);
}

.pc-only { display: none; }
@media (hover: hover) and (pointer: fine) {
  .pc-only { display: inline; }
}
@media (min-width: 640px) {
  .player-settings { grid-template-columns: 1fr 1fr; }
  .setting:first-child, .setting-wide { grid-column: 1 / -1; }
}
</style>
