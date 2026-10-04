<script lang="ts">
/**
 * 管理者の埋め作業で「次へ」を押して開いた譜面は、譜面再生を開いて動画と一緒に流し始める。
 * 親は譜面ごとにこのコンポーネントを作り直すので、インスタンスをまたいでモジュールの変数で渡す。
 */
let autoStartNext = false;
</script>

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
 * - 表示時間（主 BPM でノーツが画面上端から判定ラインまで落ちる秒数。緑数字の目安も併記）・再生速度・打鍵音
 * - プレイサイド: ログイン中はプロフィールの設定（usePlaySide）。未ログインのときだけ設定欄で 1P/2P を切り替える
 * - 描画は canvas。requestAnimationFrame の間だけ動き、タブが隠れたら一時停止する
 * - モバイル: 幅は親に合わせ、高さは画面の約 6 割。canvas のタップで再生/一時停止（縦スクロールは妨げない）。
 *   マウスは canvas の上下ドラッグで前後に送れる
 */
import { ref, shallowRef, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { API_BASE, useAuth } from '../composables/useAuth';
import { useAdmin } from '../composables/useAdmin';
import { usePlaySide } from '../composables/usePlaySide';
import { loadYouTubeApi, YT_STATE, type YTPlayer } from '../utils/youtube';
import { tapResidual, median, MIN_TAPS } from '../utils/videoSync';
import {
  formatBpmLabel, buildChartTimeline, assignLanes, randomPattern, rRandomPattern, isValidPattern, MIRROR_PATTERN, OFF_PATTERN,
  type ChartPlaybackData, type ChartTimeline, type ChartOption,
} from '../utils/chartPlayback';
import { explainPattern, PENALTY_KEYS, PENALTY_LABELS, type PenaltyKey, type NotePenalty } from '../utils/randomEval';
import PatternChips from './PatternChips.vue';
import RandomPanel from './RandomPanel.vue';

const props = defineProps<{
  textage: string;
  /** 譜面再生を始める並び（当たり配置ランキングから開いたとき。空なら正規） */
  initialPattern?: string;
}>();
/** go = 管理者の埋め作業で次の譜面へ移る（親がその譜面を選ぶ） */
const emit = defineEmits<{ (e: 'go', textage: string): void }>();

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
  if (settings.value.video) {
    loadVideo();
    loadQueue();
  }
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
  /** 原曲の動画（YouTube）を一緒に流すか。ON のままなら次の譜面でも自動で動画を用意する */
  video: boolean;
}
const DEFAULTS: Settings = {
  visibleSec: 1.2, rate: 1, mode: 'beat', side: 1, sound: false, noteSize: 7, noteColor: 'lane', option: 'off', pattern: OFF_PATTERN,
  video: false,
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

// プレイサイド: ログイン中はプロフィールの設定（usePlaySide）、未ログインは設定欄の切り替え（settings.side）
const { profileSide } = usePlaySide();
const side = computed<1 | 2>(() => profileSide.value ?? settings.value.side);
watch(side, () => draw());

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

/** 並びから譜面オプションを決めて当てる（1234567 = 正規、7654321 = MIRROR、その回転 = R-RANDOM、ほか = RANDOM） */
function applyStartPattern(p: string | undefined) {
  if (!p || !isValidPattern(p)) return;
  const s = settings.value;
  if (p === OFF_PATTERN) s.option = 'off';
  else if (p === MIRROR_PATTERN) s.option = 'mirror';
  else if ((OFF_PATTERN + OFF_PATTERN).includes(p) || (MIRROR_PATTERN + MIRROR_PATTERN).includes(p)) { s.option = 'rrandom'; s.pattern = p; }
  else { s.option = 'random'; s.pattern = p; }
  patternInput.value = shownPattern.value;
}
// 当たり配置ランキングから開いたときはその並びで始める（同じ譜面を開き直したときも当て直す）
watch(() => props.initialPattern, applyStartPattern);

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
// 譜面ごとに作り直されるので、開いた時点の並びはここで当てる（shownPattern の定義より後で呼ぶ）
applyStartPattern(props.initialPattern);

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

/** @param fromVideo 動画側の再生ボタンから始まった（動画はもう流れているので動かさない） */
function play(fromVideo = false) {
  const tl = timeline.value;
  if (!tl) return;
  if (curTime >= tl.totalTime) seek(LEAD_IN, false);
  if (loopOn.value) {
    const [start, end] = loopRange(tl);
    if (curTime < start - LOOP_PREROLL || curTime >= end) seek(loopRange(tl)[0] - LOOP_PREROLL, false);
  }
  ensureAudio();
  playing.value = true;
  if (!fromVideo) startVideoFromChart();
  lastFrame = performance.now();
  cancelAnimationFrame(rafId);
  rafId = requestAnimationFrame(frame);
}

/** @param fromVideo 動画側で止められた（動画は止まっているので動かさない） */
function pause(fromVideo = false) {
  playing.value = false;
  cancelAnimationFrame(rafId);
  if (!fromVideo) videoPause();
  uiTime.value = curTime;
  draw();
}

function togglePlay() {
  if (playing.value) pause();
  else play();
}

/** @param followVideo 動画も同じ位置へ動かすか */
function seek(t: number, followVideo = true) {
  const tl = timeline.value;
  if (!tl) return;
  curTime = Math.min(Math.max(t, LEAD_IN), tl.totalTime);
  hitIdx = lowerBound(tl.hitTimes, curTime);
  bssEndIdx = lowerBound(bssEndTimes.value, curTime);
  laneHit.fill(-99);
  uiTime.value = curTime;
  if (followVideo) videoFollowSeek();
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
  const prev = curTime;
  curTime = advanceWithVideo(curTime, dt * settings.value.rate, now);
  // 動画に合わせて後ろへ戻ったときは打鍵の位置も戻す（前へは processHits が進める）
  if (curTime < prev) {
    hitIdx = lowerBound(tl.hitTimes, curTime);
    bssEndIdx = lowerBound(bssEndTimes.value, curTime);
  }
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

// ── 原曲の動画（YouTube） ────────────────────────────────────
// 動画を流している間は動画の再生位置が時計になり、譜面は「動画の位置 − ずれ」に追従する。
// ずれ（offset）: 動画の位置（秒）= 譜面の時刻（秒、最初の小節の頭が 0）+ offset。譜面ごとにサーバーに保存し、全員で共有する。
interface VideoInfo {
  status: 'ok' | 'notFound' | 'disabled' | 'none';
  videoId?: string; videoTitle?: string; channelTitle?: string; durationSec?: number;
  candidateIndex?: number; candidateCount?: number;
  offsetSec?: number | null;
  /** chart = この譜面で保存されたずれ、song = 同じ曲の別譜面のずれを借りている */
  offsetSource?: 'chart' | 'song' | null;
  /** ずれが無いとき、同じチャンネルの動画で保存されたずれの中央値（初期値の目安）と、その数 */
  offsetEstimate?: number;
  estimateSamples?: number;
}
const { isLoggedIn, authHeaders } = useAuth();
const { isAdmin } = useAdmin();

// ── 減点の色付け（管理者専用） ─────────────────────────────────
// RANDOM の配置評価で今の並びが減点された鍵盤ノーツをオレンジで塗る（減点の無いノーツと皿はいつもの色＝白鍵は白・黒鍵は青。
// 形による色分けはしない＝ユーザー判断）。凡例で隠した形だけに当たるノーツはいつもの色。
// 減点が重いほど彩度を下げる（2026-10-05 ユーザー判断）: そのノーツの減点の量（形の合計、難所の重みつき）が
// 0 に近いほど鮮やかなオレンジ、PENALTY_HEAVY 以上で彩度 PENALTY_SAT_MIN まで下げる
const PENALTY_HEAVY = 1;
const PENALTY_SAT_MAX = 95;
const PENALTY_SAT_MIN = 20;
function penaltyColor(amount: number): string {
  const t = Math.min(1, amount / PENALTY_HEAVY);
  return `hsl(25 ${Math.round(PENALTY_SAT_MAX - (PENALTY_SAT_MAX - PENALTY_SAT_MIN) * t)}% 53%)`;
}
const penaltyView = ref(false);
/** 凡例で隠した減点の形 */
const penaltyHidden = ref<PenaltyKey[]>([]);
function togglePenaltyKey(k: PenaltyKey) {
  penaltyHidden.value = penaltyHidden.value.includes(k) ? penaltyHidden.value.filter(x => x !== k) : [...penaltyHidden.value, k];
}
const penaltyMarks = computed(() => {
  const tl = timeline.value;
  const p = shownPattern.value;
  if (!isAdmin.value || !penaltyView.value || !tl || !p) return null;
  return explainPattern(tl, p, side.value);
});
/** 描画用: 減点されたノーツ・CN の添字 → 色と、形ごとの打鍵数 */
const penaltyPaint = computed(() => {
  const marks = penaltyMarks.value;
  if (!marks) return null;
  const hidden = new Set(penaltyHidden.value);
  const counts = Object.fromEntries(PENALTY_KEYS.map(k => [k, 0])) as Record<PenaltyKey, number>;
  const toPaint = (m: Map<number, NotePenalty>) => {
    const out = new Map<number, string>();
    for (const [i, r] of m) {
      let total = 0;
      for (const k of PENALTY_KEYS) {
        const v = r[k] ?? 0;
        if (!(v > 0)) continue;
        counts[k]++;
        if (!hidden.has(k)) total += v;
      }
      if (total > 0) out.set(i, penaltyColor(total));
    }
    return out;
  };
  return { notes: toPaint(marks.notes), cns: toPaint(marks.cns), counts };
});
watch(penaltyPaint, () => draw());
const videoInfo = ref<VideoInfo | null>(null);
const videoLoading = ref(false);
const videoError = ref('');
const videoReady = ref(false);
const videoBoxRef = ref<HTMLDivElement | null>(null);
let yt: YTPlayer | null = null;
/** 今使っているずれ（秒） */
const offset = ref(0);
/** ±ボタンや合わせ操作でずれを変えたか（保存ボタンを出す） */
const offsetTouched = ref(false);
/** ずれが未保存で、同じチャンネルからの推定値を初期値にしている */
const usingEstimate = ref(false);
const offsetSaving = ref(false);
const offsetMessage = ref('');
/** 合わせ済みの譜面でも、ずれ合わせの操作を開いて見る */
const syncOpen = ref(false);
/** 「リズムに合わせて叩く」の打鍵の差（秒） */
const taps = ref<number[]>([]);
const manualUrl = ref('');
/** 譜面が動画の 0 秒より前（曲頭前の空き・動画より早く始まる譜面）。動画の 0 秒に届いたら流し始める */
let videoPending = false;
/** こちらから止めたままシークした直後に、動画が勝手に再生を始めたら止め直す（未再生の動画はシークで再生が始まる） */
let ignoreVideoPlayUntil = 0;
let lastVideoSeekAt = 0;
/** 動画の読み込み待ちで譜面を止め始めた時刻（自動再生が許可されない端末で止まり続けないよう、一定時間で諦める） */
let heldSince = 0;
const VIDEO_HOLD_LIMIT_MS = 3000;

function videoActive(): boolean {
  return !!yt && videoReady.value;
}

/** 動画とずれを取得する（動画がまだ無ければサーバーが YouTube で探す） */
async function loadVideo() {
  if (videoLoading.value) return;
  videoLoading.value = true;
  videoError.value = '';
  try {
    const res = await fetch(`${API_BASE}/api/analysis/chart-video?search=true&textage=${encodeURIComponent(props.textage)}`);
    const data = await res.json().catch(() => ({}));
    if (!res.ok || data.error) {
      videoError.value = data.error ?? `動画を読み込めませんでした（${res.status}）`;
      return;
    }
    await applyVideoInfo(data as VideoInfo);
  } catch {
    videoError.value = '通信エラーで動画を読み込めませんでした';
  } finally {
    videoLoading.value = false;
  }
}

async function applyVideoInfo(data: VideoInfo) {
  const prevId = videoInfo.value?.videoId;
  videoInfo.value = data;
  usingEstimate.value = data.offsetSec == null && data.offsetEstimate != null;
  offset.value = data.offsetSec ?? data.offsetEstimate ?? 0;
  offsetTouched.value = false;
  offsetMessage.value = '';
  taps.value = [];
  syncOpen.value = false;
  if (data.status !== 'ok' || !data.videoId) {
    destroyVideo();
    return;
  }
  if (prevId === data.videoId && yt) return;
  await createPlayer(data.videoId);
}

async function createPlayer(videoId: string) {
  destroyVideo();
  videoError.value = '';
  let api;
  try {
    api = await loadYouTubeApi();
  } catch (e) {
    videoError.value = (e as Error).message;
    return;
  }
  await nextTick();
  const box = videoBoxRef.value;
  if (!box || !settings.value.video) return;
  const el = document.createElement('div');
  box.appendChild(el);
  yt = new api.Player(el, {
    videoId,
    width: '100%',
    height: '100%',
    playerVars: { playsinline: 1, rel: 0 },
    events: {
      onReady: () => {
        videoReady.value = true;
        yt?.setPlaybackRate(settings.value.rate);
        if (playing.value) startVideoFromChart();
        else videoFollowSeek(true);
      },
      onStateChange: (e) => onVideoState(e.data),
      onError: (e) => {
        videoError.value = e.data === 101 || e.data === 150
          ? 'この動画は埋め込み再生が許可されていません。「別の動画」を試してください'
          : '動画を再生できません。「別の動画」を試してください';
      },
    },
  });
}

function destroyVideo() {
  videoReady.value = false;
  videoPending = false;
  try { yt?.destroy(); } catch { /* 既に壊れていても続ける */ }
  yt = null;
  if (videoBoxRef.value) videoBoxRef.value.innerHTML = '';
}

function setVideoOn(on: boolean) {
  settings.value.video = on;
  if (on) {
    loadVideo();
    loadQueue();
  } else {
    destroyVideo();
    videoInfo.value = null;
    videoError.value = '';
  }
}

/** 譜面の今の位置から動画を流す */
function startVideoFromChart() {
  if (!videoActive()) return;
  const vt = curTime + offset.value;
  yt!.setPlaybackRate(settings.value.rate);
  heldSince = 0;
  if (vt >= 0) {
    videoPending = false;
    yt!.seekTo(vt, true);
    yt!.playVideo();
  } else {
    videoPending = true;
    ignoreVideoPlayUntil = performance.now() + 1000;
    yt!.seekTo(0, true);
    yt!.pauseVideo();
  }
}

function videoPause() {
  videoPending = false;
  if (videoActive()) yt!.pauseVideo();
}

/** 譜面を動かしたとき動画も同じ位置へ（ドラッグ中は間引き、離したときに force で合わせる） */
function videoFollowSeek(force = false) {
  if (!videoActive()) return;
  const now = performance.now();
  if (!force && now - lastVideoSeekAt < 200) return;
  lastVideoSeekAt = now;
  if (playing.value) {
    startVideoFromChart();
    return;
  }
  ignoreVideoPlayUntil = now + 1000;
  yt!.seekTo(Math.max(0, curTime + offset.value), true);
}

/**
 * 1 フレーム分進めた譜面の時刻。動画が流れていれば動画の位置に寄せる（0.3 秒以上ずれたら飛ぶ、それ未満は少しずつ）。
 * 動画の読み込み中は譜面を止めて待つ。
 */
function advanceWithVideo(t: number, step: number, now: number): number {
  if (!videoActive() || !playing.value) return t + step;
  const next = t + step;
  if (videoPending) {
    if (next + offset.value >= 0) {
      videoPending = false;
      yt!.playVideo();
    }
    return next;
  }
  const state = yt!.getPlayerState();
  if (state === YT_STATE.BUFFERING || state === YT_STATE.UNSTARTED || state === YT_STATE.CUED) {
    heldSince ||= now;
    if (now - heldSince < VIDEO_HOLD_LIMIT_MS) return t;
    return next;
  }
  heldSince = 0;
  if (state !== YT_STATE.PLAYING) return next;
  const target = yt!.getCurrentTime() - offset.value;
  const drift = target - next;
  if (Math.abs(drift) > 0.3) return target;
  return next + drift * 0.08;
}

/** 動画側の再生・一時停止ボタンに譜面を合わせる */
function onVideoState(s: number) {
  if (!yt) return;
  if (s === YT_STATE.PLAYING && !playing.value) {
    if (performance.now() < ignoreVideoPlayUntil) {
      yt.pauseVideo();
      return;
    }
    seek(yt.getCurrentTime() - offset.value, false);
    play(true);
  } else if (s === YT_STATE.PAUSED && playing.value && !videoPending) {
    pause(true);
  }
}

watch(() => settings.value.rate, (r) => { if (videoActive()) yt!.setPlaybackRate(r); });

// ── ずれ合わせ ──
function round3(x: number) {
  return Math.round(x * 1000) / 1000;
}

/** 最初のノーツの音が鳴った瞬間に押す: ずれ = 動画の位置 − 最初のノーツの時刻 */
function markFirstNote() {
  const tl = timeline.value;
  if (!tl || !videoActive() || tl.hitTimes.length === 0) return;
  offset.value = round3(yt!.getCurrentTime() - tl.hitTimes[0]);
  offsetTouched.value = true;
  taps.value = [];
  if (!playing.value) seek(tl.hitTimes[0], false);
}

/** 再生中に、聞こえるノーツの音に合わせて叩く。最寄りのノーツとの差を集め、中央値でずれを直す */
function tapRhythm() {
  const tl = timeline.value;
  if (!tl || !playing.value || !videoActive()) return;
  const r = tapResidual(tl.hitTimes, curTime);
  if (r == null) return;
  taps.value = [...taps.value.slice(-15), r];
}
const tapSuggestion = computed(() => (taps.value.length >= MIN_TAPS ? median(taps.value) : null));
function applyTaps() {
  const m = tapSuggestion.value;
  if (m == null) return;
  offset.value = round3(offset.value + m);
  offsetTouched.value = true;
  taps.value = [];
}

function nudge(d: number) {
  offset.value = round3(offset.value + d);
  offsetTouched.value = true;
  taps.value = [];
  if (!playing.value) videoFollowSeek(true);
}

const offsetStatus = computed(() => {
  const v = videoInfo.value;
  if (offsetTouched.value) return '調整中（未保存）';
  if (v?.offsetSource === 'chart') return '合わせ済み';
  if (v?.offsetSource === 'song') return '同じ曲の別譜面のずれを使用中';
  if (usingEstimate.value) return `推定値（同じチャンネルの ${v?.estimateSamples} 譜面から。要確認）`;
  return '未調整';
});
/** 保存ボタンを出すか（調整した、または推定値のまま確定したい） */
const canSaveOffset = computed(() => offsetTouched.value || usingEstimate.value);
/** ずれが合わせ済みなら操作を畳んでおく */
const syncCollapsible = computed(() => videoInfo.value?.offsetSource === 'chart' && !offsetTouched.value && !offsetMessage.value);
const syncCollapsed = computed(() => syncCollapsible.value && !syncOpen.value);

async function postVideo(path: string, body: object): Promise<VideoInfo | null> {
  const res = await fetch(`${API_BASE}/api/analysis/chart-video/${path}`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.error) throw new Error(data.error ?? `失敗しました（${res.status}）`);
  return data as VideoInfo;
}

/** @returns 保存できたか */
async function saveOffset(): Promise<boolean> {
  const v = videoInfo.value;
  if (!v?.videoId) return false;
  offsetSaving.value = true;
  offsetMessage.value = '';
  try {
    const data = await postVideo('offset', { textage: props.textage, videoId: v.videoId, offsetSec: offset.value });
    if (data) videoInfo.value = data;
    offsetTouched.value = false;
    usingEstimate.value = false;
    offsetMessage.value = '保存しました。この譜面を開く全員の再生に使われます';
    loadQueue();
    return true;
  } catch (e) {
    offsetMessage.value = (e as Error).message;
    return false;
  } finally {
    offsetSaving.value = false;
  }
}

// ── 管理者の埋め作業（ずれ合わせの進み具合と、次の未調整の譜面へ） ──
interface QueueInfo {
  total: number; done: number; borrowed: number; waiting: number; unsearched: number; notFound: number;
  /** 「保存せず次へ」で飛ばした譜面（一覧に出さない。曲の動画が替わると戻る） */
  skipped: number;
  /** 次に合わせる譜面（動画あり・ずれ無し → 動画未検索の順） */
  next: string[];
}
const queueInfo = ref<QueueInfo | null>(null);
const nextTextage = computed(() => queueInfo.value?.next.find(t => t !== props.textage) ?? null);
const queueProgress = computed(() => {
  const q = queueInfo.value;
  return q && q.total > 0 ? Math.round(((q.done + q.borrowed) / q.total) * 1000) / 10 : 0;
});

async function loadQueue() {
  if (!isAdmin.value) return;
  try {
    const res = await fetch(`${API_BASE}/api/analysis/chart-video/queue`, { headers: authHeaders() });
    if (res.ok) queueInfo.value = await res.json();
  } catch { /* 進み具合が出ないだけ */ }
}

/** 次の未調整の譜面を開き、動画と一緒に流し始める */
function goNext() {
  const t = nextTextage.value;
  if (!t) return;
  pause();
  autoStartNext = true;
  emit('go', t);
}

async function saveAndNext() {
  if (await saveOffset()) goNext();
}

/** 今の譜面を飛ばしたことをサーバーに記録してから次へ（記録しないと次の一覧にまた出てくる） */
async function skipAndNext() {
  if (!nextTextage.value) return;
  try {
    await fetch(`${API_BASE}/api/analysis/chart-video/skip`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ textage: props.textage }),
    });
  } catch { /* 記録できなくても次へは進む */ }
  goNext();
}

onMounted(async () => {
  if (!autoStartNext) return;
  autoStartNext = false;
  settings.value.video = true;
  await open();
  if (!timeline.value) return;
  playerCardRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  play();
});

async function nextVideo() {
  pause();
  try {
    const data = await postVideo('next', { textage: props.textage });
    if (data) await applyVideoInfo(data);
  } catch (e) {
    videoError.value = (e as Error).message;
  }
}

/** 管理者: その曲の動画を検索し直す（候補が全部外れているとき。検索 1 回分の割り当てを使う） */
async function researchVideo() {
  if (!confirm('この曲の動画を検索し直します（今日の検索回数を 1 回使います）。よろしいですか？')) return;
  pause();
  try {
    const data = await postVideo('research', { textage: props.textage });
    if (data) await applyVideoInfo(data);
    loadQueue();
  } catch (e) {
    videoError.value = (e as Error).message;
  }
}

async function setManualVideo() {
  if (!manualUrl.value.trim()) return;
  pause();
  try {
    const data = await postVideo('manual', { textage: props.textage, url: manualUrl.value.trim() });
    if (data) await applyVideoInfo(data);
    manualUrl.value = '';
  } catch (e) {
    videoError.value = (e as Error).message;
  }
}

function fmtOffset(x: number): string {
  return `${x >= 0 ? '+' : ''}${x.toFixed(3)}`;
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
  const { xs, ws, left, laneW } = laneLayout(W, side.value);

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
  const paint = penaltyPaint.value;
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
    // 減点の色付け中: 減点された鍵盤の CN の先頭はオレンジ
    if (flags & 1 && cStart[i] >= posNow) {
      const pc = paint && tl.cnKeys[i] !== 0 ? paint.cns.get(i) : undefined;
      if (pc) g.fillStyle = pc;
      g.fillRect(xs[lane] + 1, yOf(cStart[i]) - noteH, ws[lane] - 2, noteH);
      g.fillStyle = NOTE_COLOR[color];
    }
    if (flags & 2 && cEnd[i] <= posTop) g.fillRect(xs[lane] + 1, yOf(cEnd[i]) - noteH, ws[lane] - 2, noteH);
    // 押している最中の CN はレーンを光らせ続ける
    if (cStart[i] < posNow && cEnd[i] >= posNow) laneHit[lane] = curTime;
  }

  // 通常ノーツ
  const nPos = beat ? tl.noteTicks : tl.noteTimes;
  for (let i = lowerBound(nPos, posNow); i < nPos.length && nPos[i] <= posTop; i++) {
    const lane = la ? la.noteLanes[i] : tl.noteKeys[i];
    g.fillStyle = NOTE_COLOR[s.noteColor === 'key' ? tl.noteKeys[i] : lane];
    const pc = paint && tl.noteKeys[i] !== 0 ? paint.notes.get(i) : undefined;
    if (pc) g.fillStyle = pc;
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
  else if (drag?.moved) videoFollowSeek(true); // ドラッグ中は間引いていたので、離した位置に動画を合わせる
  drag = null;
}

function onKeydown(e: KeyboardEvent) {
  if (!timeline.value) return;
  if (e.key === 't' || e.key === 'T') { e.preventDefault(); tapRhythm(); }
  else if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); togglePlay(); }
  else if (e.key === 'ArrowLeft') { e.preventDefault(); stepMeasure(-1); }
  else if (e.key === 'ArrowRight') { e.preventDefault(); stepMeasure(1); }
}

function onSeekInput(e: Event) {
  seek(Number((e.target as HTMLInputElement).value));
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
  destroyVideo();
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
  <div ref="playerCardRef" class="player-card rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-4">
    <div class="player-head">
      <div class="text-xs font-medium text-slate-400 dark:text-slate-500">譜面再生</div>
      <button v-if="!opened"
        type="button"
        class="play-open inline-flex items-center gap-1.5 rounded-md bg-blue-600 hover:bg-blue-500 text-white text-xs font-bold px-3 py-2"
        @click="open">
        <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z" /></svg>
        譜面を再生する
      </button>
    </div>

    <template v-if="opened">
      <div v-if="loading" class="mt-3 flex items-center justify-center py-10 text-xs text-slate-400 dark:text-slate-500">
        <div class="w-5 h-5 mr-2 border-2 border-blue-200 border-t-blue-500 rounded-full animate-spin"></div>
        譜面データを読み込み中…
      </div>
      <p v-else-if="error" class="mt-3 text-xs text-red-600 dark:text-red-400">{{ error }}</p>

      <div v-show="timeline && !loading" class="mt-3">
        <!-- 原曲の動画（YouTube）。動画の再生位置に譜面が追従する -->
        <div class="video-area">
          <button v-if="!settings.video" type="button" class="video-open" @click="setVideoOn(true)">
            <svg class="h-4 w-4" viewBox="0 0 24 24" fill="currentColor"><path d="M10 15.5v-7l6 3.5zM21.6 7.2a2.5 2.5 0 0 0-1.8-1.8C18.2 5 12 5 12 5s-6.2 0-7.8.4a2.5 2.5 0 0 0-1.8 1.8C2 8.8 2 12 2 12s0 3.2.4 4.8a2.5 2.5 0 0 0 1.8 1.8c1.6.4 7.8.4 7.8.4s6.2 0 7.8-.4a2.5 2.5 0 0 0 1.8-1.8c.4-1.6.4-4.8.4-4.8s0-3.2-.4-4.8" /></svg>
            原曲の動画（YouTube）と一緒に再生する
          </button>
          <template v-else>
            <div v-if="videoLoading" class="flex items-center justify-center py-4 text-xs text-slate-400 dark:text-slate-500">
              <div class="w-4 h-4 mr-2 border-2 border-blue-200 border-t-blue-500 rounded-full animate-spin"></div>
              原曲の動画を探しています…
            </div>
            <p v-else-if="videoInfo?.status === 'disabled'" class="video-note">動画の検索が未設定です（YouTube API キーの設定待ち）。</p>
            <p v-else-if="videoInfo?.status === 'notFound'" class="video-note">この曲の動画が見つかりませんでした。</p>
            <p v-if="videoError" class="video-note text-red-600 dark:text-red-400">{{ videoError }}</p>

            <div v-show="videoInfo?.status === 'ok'" ref="videoBoxRef" class="video-frame"></div>

            <template v-if="videoInfo?.status === 'ok'">
              <div class="video-meta">
                <a :href="`https://www.youtube.com/watch?v=${videoInfo.videoId}`" target="_blank" rel="noopener noreferrer"
                  class="video-title" :title="videoInfo.videoTitle">{{ videoInfo.videoTitle || videoInfo.videoId }}</a>
                <span v-if="videoInfo.channelTitle" class="text-slate-400 dark:text-slate-500">{{ videoInfo.channelTitle }}</span>
                <button v-if="isLoggedIn && (videoInfo.candidateCount ?? 0) > 1" type="button" class="video-btn ml-auto"
                  @click="nextVideo">
                  別の動画<span v-if="(videoInfo.candidateIndex ?? -1) >= 0">（{{ (videoInfo.candidateIndex ?? 0) + 1 }}/{{ videoInfo.candidateCount }}）</span>
                </button>
              </div>

              <!-- ずれ合わせ -->
              <div class="sync-panel">
                <div class="sync-row">
                  <span class="setting-label">ずれ</span>
                  <span class="font-bold tabular-nums">{{ fmtOffset(offset) }} 秒</span>
                  <span class="sync-status" :class="{ warn: !offsetTouched && !videoInfo.offsetSource }">{{ offsetStatus }}</span>
                  <button v-if="syncCollapsible" type="button" class="sync-toggle" :aria-expanded="!syncCollapsed"
                    @click="syncOpen = !syncOpen">{{ syncCollapsed ? '調整する ▾' : '閉じる ▴' }}</button>
                </div>
                <template v-if="!syncCollapsed">
                <div class="sync-row">
                  <div class="seg nudge">
                    <button type="button" title="譜面を早める" @click="nudge(-0.1)">−0.1</button>
                    <button type="button" title="譜面を早める" @click="nudge(-0.01)">−0.01</button>
                    <button type="button" title="譜面を遅らせる" @click="nudge(0.01)">+0.01</button>
                    <button type="button" title="譜面を遅らせる" @click="nudge(0.1)">+0.1</button>
                  </div>
                </div>
                <div class="sync-row">
                  <button type="button" class="video-btn" :disabled="!videoReady" @click="markFirstNote">最初のノーツの音で押す</button>
                  <button type="button" class="video-btn" :disabled="!playing" @click="tapRhythm">リズムに合わせて叩く<span class="pc-only">（T）</span></button>
                  <span v-if="taps.length && tapSuggestion == null" class="text-slate-400 dark:text-slate-500 tabular-nums">{{ taps.length }}/{{ MIN_TAPS }}</span>
                  <button v-if="tapSuggestion != null" type="button" class="video-btn primary" @click="applyTaps">
                    {{ fmtOffset(tapSuggestion) }} 秒ずらす
                  </button>
                </div>
                <div v-if="canSaveOffset || offsetMessage" class="sync-row">
                  <button v-if="canSaveOffset && isLoggedIn" type="button" class="video-btn primary" :disabled="offsetSaving"
                    @click="saveOffset">このずれを保存（全員に共有）</button>
                  <span v-else-if="canSaveOffset" class="text-slate-400 dark:text-slate-500">ログインすると保存して全員に共有できます</span>
                  <span v-if="offsetMessage" class="text-slate-500 dark:text-slate-400">{{ offsetMessage }}</span>
                </div>
                <p class="text-[11px] text-slate-400 dark:text-slate-500">
                  合わせ方: 再生して最初のノーツの音が鳴った瞬間に「最初のノーツの音で押す」。続けて再生中に聞こえるノーツの音に合わせて
                  「リズムに合わせて叩く」を {{ MIN_TAPS }} 回以上押すと細かく直せます。±ボタンでも調整できます。
                </p>
                </template>
              </div>
            </template>

            <!-- 管理者の埋め作業 -->
            <div v-if="isAdmin" class="admin-box">
              <div class="sync-row">
                <span class="font-bold">埋め作業（管理者）</span>
                <span v-if="queueInfo" class="tabular-nums">
                  {{ queueInfo.done + queueInfo.borrowed }} / {{ queueInfo.total }} 譜面（{{ queueProgress }}%）
                </span>
              </div>
              <div v-if="queueInfo" class="queue-bar"><div :style="{ width: `${queueProgress}%` }"></div></div>
              <div v-if="queueInfo" class="text-slate-400 dark:text-slate-500 tabular-nums">
                合わせ済み {{ queueInfo.done }}・別譜面のずれを使用 {{ queueInfo.borrowed }}・未調整 {{ queueInfo.waiting }}・
                動画未検索 {{ queueInfo.unsearched }}・動画なし {{ queueInfo.notFound }}・飛ばした {{ queueInfo.skipped }}
              </div>
              <div class="sync-row">
                <button v-if="videoInfo?.status === 'ok'" type="button" class="video-btn primary"
                  :disabled="offsetSaving || !canSaveOffset || !nextTextage" @click="saveAndNext">保存して次へ</button>
                <button type="button" class="video-btn" :disabled="!nextTextage" @click="skipAndNext">保存せず次の未調整へ</button>
              </div>
              <p class="text-[11px] text-slate-400 dark:text-slate-500">
                次は「動画あり・ずれ未調整」の譜面から、レベルの高い順に開いて自動で再生します。それが尽きると動画未検索の譜面に進み、開くたびに検索します（1 日 90 回まで）。
                飛ばした譜面は一覧に戻りません（「別の動画」などで曲の動画が替わると戻ります。直接開けば合わせて保存できます）。
              </p>
              <div class="sync-row">
                <input v-model="manualUrl" type="text" placeholder="YouTube の URL（この曲の動画を指定）"
                  class="flex-1 min-w-0 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-700 px-2 py-1.5" />
                <button type="button" class="video-btn" @click="setManualVideo">指定</button>
                <button type="button" class="video-btn" title="候補が全部外れているとき" @click="researchVideo">再検索</button>
              </div>
            </div>
          </template>
        </div>

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
        <!-- 減点の色付けの凡例（管理者）。押すとその形の色付けを隠す／戻す -->
        <div v-if="isAdmin && penaltyView" class="penalty-legend">
          <template v-if="penaltyPaint">
            <button v-for="k in PENALTY_KEYS" :key="k" type="button" class="penalty-chip"
              :class="{ off: penaltyHidden.includes(k) }" :title="penaltyHidden.includes(k) ? '色付けを戻す' : '色付けを隠す'"
              @click="togglePenaltyKey(k)">
              {{ PENALTY_LABELS[k] }}<span class="tabular-nums text-slate-400 dark:text-slate-500">{{ penaltyPaint.counts[k] }}</span>
            </button>
          </template>
          <span v-else class="text-slate-400 dark:text-slate-500">S-RANDOM は並びが決まらないので色付けできません</span>
          <p class="w-full text-[11px] text-slate-400 dark:text-slate-500">
            今の並びで減点された鍵盤ノーツを<span class="font-bold text-orange-500">オレンジ</span>で塗ります（減点が重いほどくすんだ色、軽いほど鮮やかな色。減点の無いノーツと皿はいつもの色）。数字はその形に当たったノーツ数で、押すとその形をオレンジにしないようにできます。
          </p>
        </div>

        <!-- 再生操作 -->
        <div class="player-controls mt-3">
          <div class="player-buttons">
            <button type="button" class="ctrl-btn" title="先頭へ" aria-label="先頭へ" @click="seek(LEAD_IN)">
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6 8.5 6V6z" /></svg>
            </button>
            <button type="button" class="ctrl-btn" title="前の小節 (←)" aria-label="前の小節" @click="stepMeasure(-1)">
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M11 18V6l-8.5 6zm.5-6 8.5 6V6z" /></svg>
            </button>
            <button type="button" class="ctrl-btn ctrl-main" :title="playing ? '一時停止 (Space)' : '再生 (Space)'"
              :aria-label="playing ? '一時停止' : '再生'" @click="togglePlay">
              <svg v-if="!playing" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z" /></svg>
              <svg v-else viewBox="0 0 24 24" fill="currentColor"><path d="M6 19h4V5H6zm8-14v14h4V5z" /></svg>
            </button>
            <button type="button" class="ctrl-btn" title="次の小節 (→)" aria-label="次の小節" @click="stepMeasure(1)">
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 18l8.5-6L4 6zm9-12v12l8.5-6z" /></svg>
            </button>
            <span class="ml-auto text-xs tabular-nums text-slate-500 dark:text-slate-400">
              {{ fmtTime(uiTime) }} / {{ fmtTime(totalTime) }}
            </span>
          </div>
          <input type="range"
            class="player-seek mt-2 w-full accent-blue-600"
            :min="LEAD_IN" :max="totalTime" step="0.01"
            :value="uiTime"
            aria-label="再生位置"
            @input="onSeekInput" />
        </div>

        <!-- 設定 -->
        <div class="player-settings mt-3 text-xs text-slate-600 dark:text-slate-300">
          <label class="setting setting-slider setting-wide">
            <span class="setting-label">表示時間</span>
            <input v-model.number="settings.visibleSec" type="range" min="0.4" max="3" step="0.05" class="setting-range accent-blue-600" />
            <span class="slider-value tabular-nums whitespace-nowrap">{{ settings.visibleSec.toFixed(2) }}秒<span class="text-slate-400 dark:text-slate-500">（緑数字 約{{ greenNumber }}）</span></span>
          </label>
          <label class="setting setting-slider setting-wide">
            <span class="setting-label">ノーツの太さ</span>
            <input v-model.number="settings.noteSize" type="range" min="3" max="16" step="1" class="setting-range accent-blue-600" />
            <span class="slider-value tabular-nums whitespace-nowrap">{{ settings.noteSize }}px</span>
          </label>
          <label class="setting">
            <span class="setting-label">再生速度</span>
            <select v-model.number="settings.rate"
              class="rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-700 px-2 py-1.5">
              <option v-for="r in RATES" :key="r" :value="r">×{{ r }}</option>
            </select>
          </label>
          <div class="setting">
            <span class="setting-label">スクロール</span>
            <div class="seg">
              <button type="button" :class="{ on: settings.mode === 'beat' }" @click="settings.mode = 'beat'">ソフラン再現</button>
              <button type="button" :class="{ on: settings.mode === 'time' }" @click="settings.mode = 'time'">一定速度</button>
            </div>
          </div>
          <div class="setting setting-wide setting-option">
            <span class="setting-label">譜面</span>
            <div class="option-body">
              <div class="seg">
                <button v-for="o in OPTIONS" :key="o.value" type="button" :title="o.title"
                  :class="{ on: settings.option === o.value }" @click="setOption(o.value)">{{ o.label }}</button>
              </div>
              <div v-if="settings.option !== 'off'" class="option-detail">
                <template v-if="settings.option === 'random'">
                  <input :value="patternInput" type="text" inputmode="numeric" maxlength="7" aria-label="鍵盤の並び"
                    class="pattern-input tabular-nums rounded border bg-white dark:bg-slate-700 px-2 py-1.5"
                    :class="patternInvalid ? 'border-red-400 dark:border-red-500' : 'border-slate-300 dark:border-slate-600'"
                    @input="onPatternInput" />
                  <PatternChips v-if="!patternInvalid" :pattern="shownPattern" />
                </template>
                <PatternChips v-else-if="shownPattern" :pattern="shownPattern" />
                <span v-else class="text-slate-400 dark:text-slate-500">ノーツごとにランダム</span>
                <button v-if="settings.option !== 'mirror'" type="button" class="reroll" @click="reroll">引き直す</button>
                <span v-if="patternInvalid" class="text-red-500 dark:text-red-400">1〜7 を 1 回ずつ</span>
              </div>
            </div>
          </div>
          <div class="setting">
            <span class="setting-label">ノーツの色</span>
            <div class="seg">
              <button type="button" :class="{ on: settings.noteColor === 'lane' }" @click="settings.noteColor = 'lane'">レーン</button>
              <button type="button" :class="{ on: settings.noteColor === 'key' }" title="元の白鍵を白、元の黒鍵を青で塗る"
                @click="settings.noteColor = 'key'">元の鍵盤</button>
            </div>
          </div>
          <div class="setting setting-wide setting-loop">
            <span class="setting-label">区間リピート</span>
            <div class="loop-body">
              <label class="loop-point">
                <span class="loop-tag">A</span>
                <select v-model.number="loopA" aria-label="区間の開始小節"
                  class="rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-700 px-1.5 py-1.5">
                  <option v-for="m in measureOptions" :key="m.index" :value="m.index">小節 {{ m.label }}</option>
                </select>
                <button type="button" class="loop-here" title="今の小節を A にする" @click="setLoopPoint('a')">今</button>
              </label>
              <label class="loop-point">
                <span class="loop-tag">B</span>
                <select v-model.number="loopB" aria-label="区間の終了小節"
                  class="rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-700 px-1.5 py-1.5">
                  <option v-for="m in measureOptions" :key="m.index" :value="m.index" :disabled="m.index < loopA">小節 {{ m.label }}</option>
                </select>
                <button type="button" class="loop-here" title="今の小節を B にする" @click="setLoopPoint('b')">今</button>
              </label>
              <div class="seg">
                <button type="button" :class="{ on: loopOn }" @click="setLoopOn(true)">ON</button>
                <button type="button" :class="{ on: !loopOn }" @click="setLoopOn(false)">OFF</button>
              </div>
            </div>
          </div>
          <div class="setting">
            <span class="setting-label">サイド</span>
            <!-- ログイン中はプロフィールのプレイサイドに従う（変更はプロフィール編集から） -->
            <span v-if="profileSide" class="font-semibold">{{ profileSide }}P<span class="ml-1 font-normal text-slate-400 dark:text-slate-500">（プロフィールの設定）</span></span>
            <div v-else class="seg">
              <button type="button" :class="{ on: settings.side === 1 }" @click="settings.side = 1">1P</button>
              <button type="button" :class="{ on: settings.side === 2 }" @click="settings.side = 2">2P</button>
            </div>
          </div>
          <div class="setting">
            <span class="setting-label">原曲動画</span>
            <div class="seg">
              <button type="button" :class="{ on: settings.video }" @click="setVideoOn(true)">ON</button>
              <button type="button" :class="{ on: !settings.video }" @click="setVideoOn(false)">OFF</button>
            </div>
          </div>
          <div v-if="isAdmin" class="setting">
            <span class="setting-label">減点の色</span>
            <div class="seg">
              <button type="button" :class="{ on: penaltyView }" @click="penaltyView = true">ON</button>
              <button type="button" :class="{ on: !penaltyView }" @click="penaltyView = false">OFF</button>
            </div>
          </div>
          <div class="setting">
            <span class="setting-label">打鍵音</span>
            <div class="seg">
              <button type="button" :class="{ on: settings.sound }" @click="settings.sound = true">ON</button>
              <button type="button" :class="{ on: !settings.sound }" @click="settings.sound = false">OFF</button>
            </div>
          </div>
        </div>
        <p class="mt-2 text-[11px] text-slate-400 dark:text-slate-500">
          原曲動画を ON にすると、YouTube の動画の音に合わせて譜面が流れます（動画は検索で自動的に選びます）。譜面をタップ（クリック）で再生／一時停止<span class="pc-only">、マウスの上下ドラッグで前後に移動、←→キーで小節送り</span>できます。
          区間リピートは B の小節の終わりまで流すと、A の 1 秒前に戻ります。
        </p>
      </div>
    </template>
  </div>

  <!-- RANDOM の判別・配置評価（譜面再生とは別のカード。データは表示時に読み込み、「この並びで再生」で譜面再生を開いて再生する） -->
  <div class="rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-4">
    <div class="text-xs font-medium text-slate-400 dark:text-slate-500">RANDOM</div>
    <div v-if="loading" class="mt-3 flex items-center justify-center py-6 text-xs text-slate-400 dark:text-slate-500">
      <div class="w-5 h-5 mr-2 border-2 border-blue-200 border-t-blue-500 rounded-full animate-spin"></div>
      譜面データを読み込み中…
    </div>
    <p v-else-if="error" class="mt-3 text-xs text-red-600 dark:text-red-400">{{ error }}</p>
    <RandomPanel v-if="timeline && !loading" :timeline="timeline" :side="side" :current-pattern="shownPattern"
      @apply="applyAndPlay" />
  </div>
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
.ctrl-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2.5rem;
  height: 2.5rem;
  border-radius: 9999px;
  color: rgb(71 85 105);
  background: rgb(241 245 249);
}
.ctrl-btn:hover { background: rgb(226 232 240); }
.ctrl-btn svg { width: 1.1rem; height: 1.1rem; }
.ctrl-main {
  width: 3rem;
  height: 3rem;
  color: white;
  background: rgb(37 99 235);
}
.ctrl-main:hover { background: rgb(59 130 246); }
.ctrl-main svg { width: 1.4rem; height: 1.4rem; }
.dark .ctrl-btn:not(.ctrl-main) { color: rgb(203 213 225); background: rgb(51 65 85); }
.dark .ctrl-btn:not(.ctrl-main):hover { background: rgb(71 85 105); }
.player-seek { height: 1.5rem; }

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
.setting-range { flex: 1; min-width: 0; height: 1.5rem; }
/* スマホ幅では値の表示（「1.20秒（緑数字 約720）」）を次の行へ送り、スライダーに行の幅を全部使わせる */
.setting-slider { flex-wrap: wrap; row-gap: 0.1rem; }
/* 幅の狭いスマホでは譜面オプションの見出しを上に置き、5 つのボタンに行の幅を全部使わせる */
@media (max-width: 419px) {
  .setting-option { flex-wrap: wrap; row-gap: 0.4rem; }
  .setting-option .option-body { flex-basis: 100%; }
  .setting-option .option-body .seg { max-width: none; }
}
@media (max-width: 639px) {
  .setting-slider .setting-range { flex-basis: calc(100% - 5.6rem); height: 2rem; }
  .setting-slider .slider-value { flex-basis: 100%; padding-left: 5.6rem; }
}
.seg {
  display: inline-flex;
  border-radius: 0.375rem;
  overflow: hidden;
  border: 1px solid rgb(203 213 225);
}
.seg button {
  padding: 0.4rem 0.75rem;
  font-weight: 600;
  color: rgb(71 85 105);
  background: white;
}
.seg button + button { border-left: 1px solid rgb(203 213 225); }
.seg button.on { color: white; background: rgb(37 99 235); }
.dark .seg { border-color: rgb(71 85 105); }
.dark .seg button { color: rgb(203 213 225); background: rgb(51 65 85); }
.dark .seg button + button { border-left-color: rgb(71 85 105); }
.dark .seg button.on { color: white; background: rgb(37 99 235); }

.option-body {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 0.75rem;
  flex: 1;
  min-width: 0;
}
.seg button { white-space: nowrap; }
/* 譜面オプションは 5 つ並ぶので、スマホ幅でも 1 行に収まるよう行いっぱいに等分する */
.option-body .seg { display: flex; flex: 1 1 16rem; max-width: 20rem; }
.option-body .seg button { flex: 1 1 0; padding-left: 0.2rem; padding-right: 0.2rem; }
.option-detail {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.pattern-input { width: 5.5rem; letter-spacing: 0.12em; font-weight: 700; }
.reroll {
  padding: 0.35rem 0.7rem;
  border-radius: 0.375rem;
  font-weight: 600;
  color: rgb(37 99 235);
  border: 1px solid rgb(147 197 253);
}
.reroll:hover { background: rgb(239 246 255); }
.dark .reroll { color: rgb(147 197 253); border-color: rgb(30 64 175); }
.dark .reroll:hover { background: rgb(30 41 59); }

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
.loop-here {
  padding: 0.3rem 0.5rem;
  border-radius: 0.375rem;
  font-weight: 600;
  color: rgb(37 99 235);
  border: 1px solid rgb(147 197 253);
}
.dark .loop-here { color: rgb(147 197 253); border-color: rgb(30 64 175); }

/* 原曲の動画 */
.video-area {
  max-width: 520px;
  margin: 0 auto 0.75rem;
  font-size: 0.75rem;
}
.video-open {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.4rem;
  width: 100%;
  padding: 0.55rem 0.75rem;
  border-radius: 0.375rem;
  font-weight: 700;
  color: rgb(220 38 38);
  border: 1px dashed rgb(252 165 165);
}
.video-open:hover { background: rgb(254 242 242); }
.dark .video-open { color: rgb(252 165 165); border-color: rgb(127 29 29); }
.dark .video-open:hover { background: rgb(30 41 59); }
.video-note { padding: 0.5rem 0; color: rgb(100 116 139); }
.video-frame {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
  border-radius: 0.375rem;
  overflow: hidden;
  background: black;
}
.video-frame :deep(iframe) { position: absolute; inset: 0; width: 100%; height: 100%; }
.video-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.25rem 0.6rem;
  margin-top: 0.4rem;
}
.video-title {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
  color: rgb(37 99 235);
}
.dark .video-title { color: rgb(147 197 253); }
.sync-panel {
  display: flex;
  flex-direction: column;
  gap: 0.45rem;
  margin-top: 0.5rem;
  padding: 0.6rem;
  border-radius: 0.375rem;
  background: rgb(248 250 252);
  color: rgb(71 85 105);
}
.dark .sync-panel { background: rgb(15 23 42 / 0.6); color: rgb(203 213 225); }
.sync-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.4rem 0.6rem;
}
.sync-status { color: rgb(100 116 139); }
.sync-status.warn { color: rgb(217 119 6); font-weight: 600; }
.sync-toggle { margin-left: auto; font-size: 0.75rem; color: rgb(37 99 235); }
.dark .sync-toggle { color: rgb(147 197 253); }
.seg.nudge button { padding-left: 0.6rem; padding-right: 0.6rem; font-variant-numeric: tabular-nums; }
.video-btn {
  padding: 0.35rem 0.7rem;
  border-radius: 0.375rem;
  font-weight: 600;
  color: rgb(37 99 235);
  border: 1px solid rgb(147 197 253);
  white-space: nowrap;
}
.video-btn:hover:not(:disabled) { background: rgb(239 246 255); }
.video-btn:disabled { opacity: 0.45; cursor: not-allowed; }
.video-btn.primary { color: white; background: rgb(37 99 235); border-color: rgb(37 99 235); }
.video-btn.primary:hover:not(:disabled) { background: rgb(59 130 246); }
.dark .video-btn:not(.primary) { color: rgb(147 197 253); border-color: rgb(30 64 175); }
.dark .video-btn:not(.primary):hover:not(:disabled) { background: rgb(30 41 59); }

.penalty-legend { display: flex; flex-wrap: wrap; align-items: center; gap: 0.3rem 0.4rem; margin-top: 0.5rem; font-size: 11px; }
.penalty-chip {
  display: inline-flex; align-items: center; gap: 0.3rem; padding: 0.15rem 0.45rem; border-radius: 9999px;
  border: 1px solid rgb(203 213 225); color: rgb(51 65 85); background: white;
}
.dark .penalty-chip { border-color: rgb(71 85 105); color: rgb(226 232 240); background: rgb(30 41 59); }
.penalty-chip.off { opacity: 0.4; text-decoration: line-through; }
.admin-box {
  display: flex;
  flex-direction: column;
  gap: 0.45rem;
  margin-top: 0.5rem;
  padding: 0.6rem;
  border-radius: 0.375rem;
  border: 1px solid rgb(253 230 138);
  background: rgb(255 251 235);
  color: rgb(71 85 105);
}
.dark .admin-box { border-color: rgb(120 53 15); background: rgb(41 37 36 / 0.6); color: rgb(203 213 225); }
.queue-bar { height: 0.35rem; border-radius: 9999px; background: rgb(226 232 240); overflow: hidden; }
.queue-bar > div { height: 100%; background: rgb(16 185 129); }
.dark .queue-bar { background: rgb(51 65 85); }

.pc-only { display: none; }
@media (hover: hover) and (pointer: fine) {
  .pc-only { display: inline; }
}
@media (min-width: 640px) {
  .player-settings { grid-template-columns: 1fr 1fr; }
  .setting:first-child, .setting-wide { grid-column: 1 / -1; }
}
</style>
