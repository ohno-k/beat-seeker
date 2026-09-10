<script setup lang="ts">
/**
 * 【コンポーネントの役割】 ランクアップ・アドバイス 1 件の「伸びしろ（期待 +x pt）」の根拠を図示するモーダル。
 *
 * 一覧の行はアイコンだけを置き、押すとこのモーダルで
 *  1. 式の分解 … 達成率 × 達成時の増分 = 期待 pt（バックエンドの値をそのまま）
 *  2. 図 … 上段: 推定能力から見た「出せるスコアの分布」と現在 / 損益分岐 / 中央値 / 目標 / 上限の位置。
 *          下段: 目標候補ごとの「達成時の増分」と「達成率 × 増分」の曲線。後者の最大点が目標
 *  3. 手順 … 実力の推定 → 増分の基準（TOP100 押し出し / 上積み）→ 目標の選択
 *  4. 推定に使った譜面 … ペア回帰（HIGH / LOW）のときだけ、参照譜面の上位を寄与順に並べる
 * を見せる。
 *
 * 図の曲線は `utils/fillRecommendationMath.ts` でバックエンドと同じ前提から再計算している
 * （logit 空間の正規分布・BEAT-PT 式）。数値の正本はレスポンス側で、図は「なぜその目標か」を目で追うためのもの。
 *
 * @prop item       一覧の 1 件（挑戦済みも同じ形）。押し出しライン（100 位の pt）は item.baselinePt に入っている。
 * @prop viewUserId 管理者が閲覧中の相手のユーザー ID。参照譜面の取得先を切り替える。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { API_BASE, useAuth } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';
import { gradeLabel, gradeColorClass, scoreGrade } from '../utils/scoreGrade';
import {
  PT_BORDERS, borderScore, gainAt, scoreAtSigma, scoreDensity, tailProbability,
} from '../utils/fillRecommendationMath';
import type { FillRecommendationItem } from '../types/fillRecommendation';
import { isRoughAccuracy } from '../types/fillRecommendation';
import InformalRankBadge from './InformalRankBadge.vue';

const props = withDefaults(defineProps<{
  item: FillRecommendationItem;
  viewUserId?: number | null;
}>(), {
  viewUserId: null,
});

const emit = defineEmits<{ close: [] }>();

const { t } = useI18n();
const { authHeaders } = useAuth();

// ── 表示ヘルパー ─────────────────────────────────────────

/** beat-seeker 記法（AAA+15 / MAX-30）。表記できないスコアは素の値。 */
function label(score: number): string {
  return gradeLabel(score, props.item.maxScore) || score.toLocaleString();
}

/** 記法の色（MAX-=紫 / AAA+=琥珀）。 */
function labelColor(score: number): string {
  return gradeColorClass(scoreGrade(score, props.item.maxScore), 'text-slate-700 dark:text-slate-200');
}

function rateOf(score: number): string {
  return (score * 100 / props.item.maxScore).toFixed(2);
}

function pct(p: number): number {
  return Math.round(p * 100);
}

function diffShort(name: string): string {
  return name === 'LEGGENDARIA' ? 'LEG' : name === 'ANOTHER' ? 'ANO' : name;
}

function accuracyLabel(): string {
  switch (props.item.accuracy) {
    case 'HIGH': return t('advice.accuracyHigh');
    case 'LOW': return t('advice.accuracyLow');
    case 'BASE': return t('advice.accuracyBase');
    case 'RANK': return t('advice.accuracyRank');
    default: return String(props.item.accuracy);
  }
}

const isRough = computed(() => isRoughAccuracy(props.item.accuracy));

/** 予測中央値（μ）のスコア。レスポンスの predictedScore は上限で丸められているので分布の中心はこちら。 */
const medianScore = computed(() => scoreAtSigma(props.item.maxScore, props.item.muLogit, props.item.sigmaLogit, 0));
const sigmaLowScore = computed(() => scoreAtSigma(props.item.maxScore, props.item.muLogit, props.item.sigmaLogit, -1));
const sigmaHighScore = computed(() => scoreAtSigma(props.item.maxScore, props.item.muLogit, props.item.sigmaLogit, 1));

/** 上限がコミュニティ最高で頭打ちになっているか（理論値そのものなら「上限」は図の端と同じ）。 */
const capBelowMax = computed(() => props.item.scoreCap < props.item.maxScore);

// ── 図の計算 ─────────────────────────────────────────────

/**
 * SVG の座標系。幅はコンテナの実ピクセル幅に合わせる（viewBox = 表示幅）ので、
 * 文字サイズがモバイルでも縮まない。高さは固定。
 */
const chartWrap = ref<HTMLElement | null>(null);
const W = ref(640);
const PAD_L = 44;
const PAD_R = 44;
const TOP = { y0: 22, y1: 122 };   // 上段: 分布
const BOT = { y0: 160, y1: 272 };  // 下段: 増分 / 期待値
const AXIS_Y = BOT.y1;
const H = 300;
const SAMPLES = 220;
let resizeObserver: ResizeObserver | null = null;

function syncWidth() {
  const w = chartWrap.value?.clientWidth ?? 0;
  if (w > 0) W.value = Math.max(320, Math.round(w));
}

/** 図に出すスコア範囲。分布の ±3.2σ と、現在 / 損益分岐 / 目標 / 上限を必ず含める。 */
const domain = computed(() => {
  const it = props.item;
  const span = 3.2;
  const loCands = [it.breakEvenScore, scoreAtSigma(it.maxScore, it.muLogit, it.sigmaLogit, -span)];
  if (!it.unplayed) loCands.push(it.currentScore);
  const hiCands = [it.scoreCap, it.targetScore, scoreAtSigma(it.maxScore, it.muLogit, it.sigmaLogit, span)];
  let lo = Math.max(0, Math.min(...loCands));
  let hi = Math.min(it.maxScore, Math.max(...hiCands));
  if (hi - lo < 40) { lo = Math.max(0, lo - 20); hi = Math.min(it.maxScore, hi + 20); }
  // 端の線が枠と重ならないよう少しだけ余白を取る（理論値は超えない）。
  const padScore = Math.max(1, Math.round((hi - lo) * 0.04));
  lo = Math.max(0, lo - padScore);
  hi = Math.min(it.maxScore, hi + padScore);
  return { lo, hi };
});

function xOf(score: number): number {
  const { lo, hi } = domain.value;
  const r = hi > lo ? (score - lo) / (hi - lo) : 0;
  return PAD_L + Math.max(0, Math.min(1, r)) * (W.value - PAD_L - PAD_R);
}

/** サンプル点（整数スコア）。 */
const sampleScores = computed(() => {
  const { lo, hi } = domain.value;
  const out: number[] = [];
  for (let i = 0; i <= SAMPLES; i++) out.push(Math.round(lo + (hi - lo) * (i / SAMPLES)));
  return out;
});

/** 上段の密度の正規化定数（範囲内の最大値）。曲線の高さを 0〜1 に揃える。 */
const densityNorm = computed(() => {
  const it = props.item;
  return Math.max(...sampleScores.value.map(s => scoreDensity(s, it.maxScore, it.muLogit, it.sigmaLogit)), 1e-12);
});

/** 密度（相対値・最大 1）。 */
function densityAt(score: number): number {
  const it = props.item;
  return scoreDensity(score, it.maxScore, it.muLogit, it.sigmaLogit) / densityNorm.value;
}

/** 上段: 密度（相対値・最大 1）。 */
const densitySeries = computed(() => sampleScores.value.map(densityAt));

function densityY(v: number): number {
  return TOP.y1 - v * (TOP.y1 - TOP.y0);
}

/** 折れ線 → SVG path。 */
function linePath(points: { x: number; y: number }[]): string {
  return points.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');
}

/** 折れ線の下を底辺 baseY まで塗る path。 */
function areaPath(points: { x: number; y: number }[], baseY: number): string {
  if (points.length === 0) return '';
  const first = points[0];
  const last = points[points.length - 1];
  return `${linePath(points)} L${last.x.toFixed(1)},${baseY} L${first.x.toFixed(1)},${baseY} Z`;
}

const densityPoints = computed(() =>
  sampleScores.value.map((s, i) => ({ x: xOf(s), y: densityY(densitySeries.value[i]) })));

const densityAreaPath = computed(() => areaPath(densityPoints.value, TOP.y1));
const densityLinePath = computed(() => linePath(densityPoints.value));

/** 目標以上の部分（達成率に相当する面積）。目標の x で曲線を切り出す。 */
const achieveAreaPath = computed(() => {
  const target = props.item.targetScore;
  const pts = densityPoints.value.filter((_, i) => sampleScores.value[i] >= target);
  const head = { x: xOf(target), y: densityY(densityAt(target)) };
  return areaPath([head, ...pts], TOP.y1);
});

/**
 * 下段: 達成時の増分 gain(s) と 期待値 v(s) = P(S ≥ s) × gain(s)。
 * gain は上限まで単調に伸びて v より桁が大きくなりがちなので、左軸 = v、右軸 = gain と別スケールにする
 * （同じ軸に載せると v の山が潰れて「どこが最大か」が見えない）。
 */
const valueSeries = computed(() => {
  const it = props.item;
  const gains = sampleScores.value.map(s => gainAt(s, it.maxScore, it.informalRank, it.baselinePt, it.currentScore));
  const values = sampleScores.value.map((s, i) => gains[i] > 0
    ? tailProbability(s, it.maxScore, it.muLogit, it.sigmaLogit) * gains[i]
    : 0);
  const gainMax = Math.max(...gains, it.targetGain, 1e-9);
  const valueMax = Math.max(...values, it.expectedGain, 1e-9);
  return { gains, values, gainMax, valueMax };
});

function valueY(v: number): number {
  return BOT.y1 - (v / valueSeries.value.valueMax) * (BOT.y1 - BOT.y0);
}

function gainY(g: number): number {
  return BOT.y1 - (g / valueSeries.value.gainMax) * (BOT.y1 - BOT.y0);
}

const gainLinePath = computed(() =>
  linePath(sampleScores.value.map((s, i) => ({ x: xOf(s), y: gainY(valueSeries.value.gains[i]) }))));

const valueAreaPath = computed(() =>
  areaPath(sampleScores.value.map((s, i) => ({ x: xOf(s), y: valueY(valueSeries.value.values[i]) })), BOT.y1));

const valueLinePath = computed(() =>
  linePath(sampleScores.value.map((s, i) => ({ x: xOf(s), y: valueY(valueSeries.value.values[i]) }))));

/** 目標点（下段の最大点）。値はレスポンスの expectedGain をそのまま使う。 */
const targetPoint = computed(() => ({ x: xOf(props.item.targetScore), y: valueY(props.item.expectedGain) }));

/** 下段の目盛りに出す最大値ラベル（左: 期待値、右: 増分）。 */
const valueAxisMax = computed(() => valueSeries.value.valueMax);
const gainAxisMax = computed(() => valueSeries.value.gainMax);

/** X 軸のボーダー目盛り（範囲内のものだけ）。 */
const borderTicks = computed(() => {
  const { lo, hi } = domain.value;
  return PT_BORDERS
    .map(b => ({ label: b.label, score: borderScore(props.item.maxScore, b.rate) }))
    .filter(b => b.score >= lo && b.score <= hi)
    .map(b => ({ ...b, x: xOf(b.score) }));
});

/** 縦線マーカー。凡例と同じ順で描く。 */
interface Marker {
  key: 'current' | 'breakEven' | 'median' | 'target' | 'cap';
  score: number;
  x: number;
  stroke: string;
  dash: string;
  width: number;
  chip: string;
  dot: string;
}

const markers = computed<Marker[]>(() => {
  const it = props.item;
  const list: Marker[] = [];
  if (!it.unplayed) {
    list.push({
      key: 'current', score: it.currentScore, x: xOf(it.currentScore),
      stroke: 'stroke-slate-400 dark:stroke-slate-500', dash: '4 3', width: 1.5,
      chip: 'border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300',
      dot: 'bg-slate-400 dark:bg-slate-500',
    });
  }
  list.push({
    key: 'breakEven', score: it.breakEvenScore, x: xOf(it.breakEvenScore),
    stroke: 'stroke-rose-500 dark:stroke-rose-400', dash: '2 3', width: 1.5,
    chip: 'border-rose-200 dark:border-rose-800 text-rose-600 dark:text-rose-300',
    dot: 'bg-rose-500 dark:bg-rose-400',
  });
  list.push({
    key: 'median', score: medianScore.value, x: xOf(medianScore.value),
    stroke: 'stroke-sky-500 dark:stroke-sky-400', dash: '5 3', width: 1.5,
    chip: 'border-sky-200 dark:border-sky-800 text-sky-700 dark:text-sky-300',
    dot: 'bg-sky-500 dark:bg-sky-400',
  });
  list.push({
    key: 'target', score: it.targetScore, x: xOf(it.targetScore),
    stroke: 'stroke-emerald-500 dark:stroke-emerald-400', dash: '', width: 2.5,
    chip: 'border-emerald-300 dark:border-emerald-700 text-emerald-700 dark:text-emerald-300',
    dot: 'bg-emerald-500 dark:bg-emerald-400',
  });
  if (capBelowMax.value) {
    list.push({
      key: 'cap', score: it.scoreCap, x: xOf(it.scoreCap),
      stroke: 'stroke-violet-500 dark:stroke-violet-400', dash: '1.5 3', width: 1.5,
      chip: 'border-violet-200 dark:border-violet-800 text-violet-700 dark:text-violet-300',
      dot: 'bg-violet-500 dark:bg-violet-400',
    });
  }
  return list;
});

function markerName(key: Marker['key']): string {
  switch (key) {
    case 'current': return t('adviceReason.markerCurrent');
    case 'breakEven': return t('adviceReason.markerBreakEven');
    case 'median': return t('adviceReason.markerMedian');
    case 'target': return t('adviceReason.markerTarget');
    case 'cap': return t('adviceReason.markerCap');
  }
}

/** 達成率ラベルの位置。目標線の右に置き、右端に寄りすぎたら左側へ逃がす。 */
const achieveLabel = computed(() => {
  const x = xOf(props.item.targetScore);
  const right = x + 6 < W.value - PAD_R - 70;
  return { x: right ? x + 6 : x - 6, anchor: right ? 'start' : 'end' };
});

// ── 推定に使った譜面（ペア回帰のときだけ）────────────────

interface RefItem {
  title: string;
  difficultyName: string;
  difficultyLevel: number;
  actualA: number;
  notesA?: number;
  r: number;
  n: number;
  weight: number;
  predScore: number;
  isPrimary: boolean;
}

const refs = ref<RefItem[]>([]);
const refsLoading = ref(false);
const refsError = ref('');
/** 一覧に出す件数。全件はコンパクトさを損なうので上位だけ。 */
const REFS_SHOWN = 6;

async function fetchRefs() {
  if (isRough.value) return;
  refsLoading.value = true;
  refsError.value = '';
  refs.value = [];
  try {
    const params = new URLSearchParams({ title: props.item.title, difficultyName: props.item.difficultyName });
    const url = props.viewUserId == null
      ? `${API_BASE}/api/analysis/growth-potential-refs?${params}`
      : `${API_BASE}/api/admin/growth-potential-refs?userId=${props.viewUserId}&${params}`;
    const res = await fetch(url, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json() as { refs: RefItem[] };
    refs.value = data.refs ?? [];
  } catch (e: any) {
    refsError.value = e?.message ?? 'fetch failed';
  } finally {
    refsLoading.value = false;
  }
}

/**
 * 実際に採用された段の参照だけ（HIGH なら |r| ≧ 0.95 のみ、LOW なら全件）を寄与順に。
 * バックエンドの 2 段構えと同じ判定（HIGH が 3 件以上なら HIGH）。
 */
const visibleRefs = computed(() => {
  const primary = refs.value.filter(r => r.isPrimary);
  const used = primary.length >= 3 ? primary : refs.value;
  return [...used].sort((a, b) => b.weight - a.weight);
});
const totalRefWeight = computed(() => visibleRefs.value.reduce((s, r) => s + r.weight, 0));
const shownRefs = computed(() => visibleRefs.value.slice(0, REFS_SHOWN));
const hiddenRefCount = computed(() => Math.max(0, visibleRefs.value.length - REFS_SHOWN));

function contribution(r: RefItem): number {
  return totalRefWeight.value > 0 ? (r.weight / totalRefWeight.value) * 100 : 0;
}

function refScoreLabel(r: RefItem): string {
  const max = r.notesA ? r.notesA * 2 : 0;
  return (max > 0 && gradeLabel(r.actualA, max)) || r.actualA.toLocaleString();
}

// ── ライフサイクル ───────────────────────────────────────

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close');
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown);
  syncWidth();
  if (typeof ResizeObserver !== 'undefined' && chartWrap.value) {
    resizeObserver = new ResizeObserver(syncWidth);
    resizeObserver.observe(chartWrap.value);
  }
});
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown);
  resizeObserver?.disconnect();
});

watch(() => `${props.item.title}\0${props.item.difficultyName}\0${props.viewUserId ?? ''}`, fetchRefs, { immediate: true });
</script>

<template>
  <Teleport to="body">
    <div
      class="fixed inset-0 z-[110] bg-slate-900/60 dark:bg-slate-950/80 backdrop-blur-sm flex items-center justify-center p-0 sm:p-4 animate-fade-in"
      @click.self="emit('close')"
    >
      <div
        class="bg-white dark:bg-slate-900 w-full max-w-2xl rounded-none sm:rounded-md shadow-xl flex flex-col overflow-hidden h-full sm:h-auto sm:max-h-[92vh] border border-slate-200 dark:border-slate-800"
        role="dialog"
        aria-modal="true"
      >
        <!-- ヘッダー -->
        <div class="relative px-4 sm:px-6 py-3 sm:py-4 shrink-0 bg-blue-700 dark:bg-blue-600">
          <button
            type="button"
            @click="emit('close')"
            class="absolute top-2 right-2 p-2 group z-50"
            :aria-label="t('common.close')"
          >
            <div class="text-white/70 group-hover:text-white bg-white/10 group-hover:bg-white/20 rounded-full w-7 h-7 flex items-center justify-center transition-colors">
              <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </div>
          </button>
          <div class="pr-10">
            <p class="text-[10px] sm:text-xs font-bold text-white/80">{{ t('adviceReason.title') }}</p>
            <h2 class="text-base sm:text-lg font-bold text-white tracking-tight leading-tight truncate">{{ item.title }}</h2>
            <div class="flex flex-wrap items-center gap-1.5 mt-1.5">
              <span class="px-1.5 py-0.5 rounded text-[10px] font-bold bg-white/20 text-white">
                {{ diffShort(item.difficultyName) }} {{ item.difficultyLevel }}
              </span>
              <InformalRankBadge :rank="item.informalRank" size="xs" />
              <span v-if="item.unplayed" class="px-1.5 py-0.5 rounded text-[10px] font-bold bg-white text-blue-700">
                {{ t('advice.unplayedTag') }}
              </span>
              <span class="text-[10px] font-bold text-white/80">
                {{ t('advice.supportHint', { n: item.supportCount, acc: accuracyLabel() }) }}
              </span>
            </div>
          </div>
        </div>

        <!-- 本体 -->
        <div class="flex-1 overflow-y-auto bg-slate-50 dark:bg-slate-900 p-3 sm:p-4 space-y-3">

          <!-- 1. 式の分解 -->
          <section class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 p-3 sm:p-4">
            <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500 mb-2">{{ t('adviceReason.formulaLabel') }}</p>
            <div class="flex items-stretch justify-between gap-1 sm:gap-2 tabular-nums">
              <div class="flex-1 min-w-0 rounded-md bg-slate-50 dark:bg-slate-700/40 px-2 py-2 text-center">
                <p class="text-[10px] font-bold text-slate-500 dark:text-slate-400 truncate">{{ t('adviceReason.termProbability') }}</p>
                <p class="text-xl sm:text-2xl font-bold text-emerald-600 dark:text-emerald-400 leading-tight">{{ pct(item.achieveProbability) }}<span class="text-xs ml-0.5">%</span></p>
                <p class="text-[10px] text-slate-400 dark:text-slate-500 leading-tight break-words">P(S ≥ {{ item.targetScore.toLocaleString() }})</p>
              </div>
              <div class="self-center text-lg font-bold text-slate-400 dark:text-slate-500">×</div>
              <div class="flex-1 min-w-0 rounded-md bg-slate-50 dark:bg-slate-700/40 px-2 py-2 text-center">
                <p class="text-[10px] font-bold text-slate-500 dark:text-slate-400 truncate">{{ t('adviceReason.termGain') }}</p>
                <p class="text-xl sm:text-2xl font-bold text-amber-600 dark:text-amber-400 leading-tight">+{{ item.targetGain.toFixed(1) }}<span class="text-xs ml-0.5">pt</span></p>
                <p class="text-[10px] text-slate-400 dark:text-slate-500 leading-tight break-words">pt({{ label(item.targetScore) }}) − {{ item.baselinePt.toFixed(1) }}</p>
              </div>
              <div class="self-center text-lg font-bold text-slate-400 dark:text-slate-500">=</div>
              <div class="flex-1 min-w-0 rounded-md bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-200 dark:border-emerald-800 px-2 py-2 text-center">
                <p class="text-[10px] font-bold text-emerald-700 dark:text-emerald-300 truncate">{{ t('adviceReason.termExpected') }}</p>
                <p class="text-xl sm:text-2xl font-bold text-emerald-700 dark:text-emerald-300 leading-tight">+{{ item.expectedGain.toFixed(1) }}<span class="text-xs ml-0.5">pt</span></p>
                <p class="text-[10px] text-emerald-600/80 dark:text-emerald-400/80 leading-tight break-words">{{ t('adviceReason.termExpectedNote') }}</p>
              </div>
            </div>
            <p class="mt-2 text-[11px] text-slate-600 dark:text-slate-300 leading-snug">
              {{ t('adviceReason.targetSentence', { target: label(item.targetScore), score: item.targetScore.toLocaleString(), rate: rateOf(item.targetScore) }) }}
              <template v-if="item.targetLabel"> {{ t('adviceReason.targetIsBorder', { label: item.targetLabel }) }}</template>
            </p>
          </section>

          <!-- 2. 図 -->
          <section class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 p-3 sm:p-4">
            <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500 mb-1">{{ t('adviceReason.chartLabel') }}</p>
            <div ref="chartWrap" class="w-full">
            <svg :viewBox="`0 0 ${W} ${H}`" :width="W" :height="H" class="block max-w-full h-auto select-none" role="img" :aria-label="t('adviceReason.chartLabel')">
              <!-- 上段タイトル -->
              <text :x="PAD_L" :y="TOP.y0 - 10" class="fill-slate-500 dark:fill-slate-400 font-bold" font-size="11">{{ t('adviceReason.chartTopTitle') }}</text>
              <!-- 上段: 分布 -->
              <path :d="densityAreaPath" class="fill-sky-400/20 dark:fill-sky-300/15" />
              <path :d="achieveAreaPath" class="fill-emerald-500/45 dark:fill-emerald-400/40" />
              <path :d="densityLinePath" class="stroke-sky-500 dark:stroke-sky-400" fill="none" stroke-width="1.5" />
              <line :x1="PAD_L" :x2="W - PAD_R" :y1="TOP.y1" :y2="TOP.y1" class="stroke-slate-300 dark:stroke-slate-600" stroke-width="1" />
              <text
                :x="achieveLabel.x" :y="TOP.y0 + 12" :text-anchor="achieveLabel.anchor"
                class="fill-emerald-700 dark:fill-emerald-300 font-bold" font-size="11"
              >{{ t('advice.achieveProbability', { p: pct(item.achieveProbability) }) }}</text>

              <!-- 下段タイトル -->
              <text :x="PAD_L" :y="BOT.y0 - 10" class="fill-slate-500 dark:fill-slate-400 font-bold" font-size="11">{{ t('adviceReason.chartBottomTitle') }}</text>
              <!-- 下段: 増分と期待値 -->
              <path :d="valueAreaPath" class="fill-emerald-500/25 dark:fill-emerald-400/20" />
              <path :d="gainLinePath" class="stroke-amber-500 dark:stroke-amber-400" fill="none" stroke-width="1.5" stroke-dasharray="3 2" />
              <path :d="valueLinePath" class="stroke-emerald-600 dark:stroke-emerald-400" fill="none" stroke-width="2" />
              <line :x1="PAD_L" :x2="W - PAD_R" :y1="AXIS_Y" :y2="AXIS_Y" class="stroke-slate-300 dark:stroke-slate-600" stroke-width="1" />
              <!-- 下段 Y 目盛り: 左 = 達成率 × 増分、右 = 達成時の増分（別スケール） -->
              <text :x="PAD_L - 4" :y="BOT.y0 + 4" text-anchor="end" class="fill-emerald-600 dark:fill-emerald-400 font-bold" font-size="9">+{{ valueAxisMax.toFixed(1) }}</text>
              <text :x="PAD_L - 4" :y="BOT.y1 + 3" text-anchor="end" class="fill-slate-400 dark:fill-slate-500" font-size="9">0 pt</text>
              <text :x="W - PAD_R + 4" :y="BOT.y0 + 4" text-anchor="start" class="fill-amber-600 dark:fill-amber-400 font-bold" font-size="9">+{{ gainAxisMax.toFixed(1) }}</text>
              <text :x="W - PAD_R + 4" :y="BOT.y1 + 3" text-anchor="start" class="fill-slate-400 dark:fill-slate-500" font-size="9">0 pt</text>

              <!-- ボーダー目盛り（AA / AAA / MAX-） -->
              <g v-for="b in borderTicks" :key="b.label">
                <line :x1="b.x" :x2="b.x" :y1="TOP.y0" :y2="AXIS_Y" class="stroke-slate-200 dark:stroke-slate-700" stroke-width="1" />
                <text :x="b.x" :y="AXIS_Y + 12" text-anchor="middle" class="fill-slate-500 dark:fill-slate-400 font-bold" font-size="9">{{ b.label }}</text>
              </g>
              <!-- 範囲の両端 -->
              <text :x="PAD_L" :y="AXIS_Y + 24" text-anchor="start" class="fill-slate-400 dark:fill-slate-500" font-size="9">{{ domain.lo.toLocaleString() }}</text>
              <text :x="W - PAD_R" :y="AXIS_Y + 24" text-anchor="end" class="fill-slate-400 dark:fill-slate-500" font-size="9">{{ domain.hi.toLocaleString() }}</text>
              <text :x="(PAD_L + W - PAD_R) / 2" :y="AXIS_Y + 24" text-anchor="middle" class="fill-slate-400 dark:fill-slate-500" font-size="9">{{ t('adviceReason.axisScore') }}</text>

              <!-- 縦線マーカー -->
              <g v-for="m in markers" :key="m.key">
                <line :x1="m.x" :x2="m.x" :y1="TOP.y0" :y2="AXIS_Y" :class="m.stroke" :stroke-width="m.width" :stroke-dasharray="m.dash || undefined" />
              </g>
              <!-- 目標点 -->
              <circle :cx="targetPoint.x" :cy="targetPoint.y" r="4.5" class="fill-emerald-500 dark:fill-emerald-400 stroke-white dark:stroke-slate-800" stroke-width="2" />
            </svg>
            </div>

            <!-- 凡例（曲線 + マーカー） -->
            <div class="mt-2 flex flex-wrap gap-1.5">
              <span class="inline-flex items-center gap-1.5 px-1.5 py-0.5 rounded border border-emerald-200 dark:border-emerald-800 bg-white dark:bg-slate-800 text-[10px] font-bold text-emerald-700 dark:text-emerald-300">
                <span class="w-4 border-t-2 border-emerald-600 dark:border-emerald-400"></span>
                <span>{{ t('adviceReason.legendValue') }}</span>
              </span>
              <span class="inline-flex items-center gap-1.5 px-1.5 py-0.5 rounded border border-amber-200 dark:border-amber-800 bg-white dark:bg-slate-800 text-[10px] font-bold text-amber-700 dark:text-amber-300">
                <span class="w-4 border-t-2 border-dashed border-amber-500 dark:border-amber-400"></span>
                <span>{{ t('adviceReason.legendGain') }}</span>
              </span>
              <span
                v-for="m in markers"
                :key="m.key"
                class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded border bg-white dark:bg-slate-800 text-[10px] font-bold tabular-nums"
                :class="m.chip"
              >
                <span class="w-1.5 h-1.5 rounded-full" :class="m.dot"></span>
                <span>{{ markerName(m.key) }}</span>
                <span :class="labelColor(m.score)">{{ label(m.score) }}</span>
                <span class="font-normal opacity-70">({{ m.score.toLocaleString() }})</span>
              </span>
              <span
                v-if="!capBelowMax"
                class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded border border-violet-200 dark:border-violet-800 bg-white dark:bg-slate-800 text-[10px] font-bold text-violet-700 dark:text-violet-300 tabular-nums"
              >
                <span class="w-1.5 h-1.5 rounded-full bg-violet-500 dark:bg-violet-400"></span>
                <span>{{ t('adviceReason.markerCap') }}</span>
                <span>MAX</span>
                <span class="font-normal opacity-70">({{ item.maxScore.toLocaleString() }})</span>
              </span>
            </div>
            <p class="mt-2 text-[10px] text-slate-400 dark:text-slate-500 leading-snug">{{ t('adviceReason.chartHint') }}</p>
          </section>

          <!-- 3. 手順 -->
          <section class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 p-3 sm:p-4">
            <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500 mb-2">{{ t('adviceReason.stepsLabel') }}</p>
            <ol class="space-y-2.5">
              <!-- Step 1: 実力の推定 -->
              <li class="flex gap-2.5">
                <span class="shrink-0 w-5 h-5 rounded-full bg-sky-100 dark:bg-sky-900/40 text-sky-700 dark:text-sky-300 text-[10px] font-bold flex items-center justify-center">1</span>
                <div class="min-w-0 text-[11px] leading-snug text-slate-700 dark:text-slate-200">
                  <p class="font-bold text-slate-800 dark:text-slate-100">{{ t('adviceReason.step1Title') }}</p>
                  <p v-if="item.accuracy === 'HIGH' || item.accuracy === 'LOW'">
                    {{ t('adviceReason.step1Pair', { n: item.supportCount, acc: accuracyLabel() }) }}
                  </p>
                  <p v-else-if="item.accuracy === 'BASE'">{{ t('adviceReason.step1Base') }}</p>
                  <p v-else>{{ t('adviceReason.step1Rank') }}</p>
                  <p class="text-slate-500 dark:text-slate-400 tabular-nums">
                    {{ t('adviceReason.step1Range', {
                      median: label(medianScore), medianRate: rateOf(medianScore),
                      lo: label(sigmaLowScore), hi: label(sigmaHighScore),
                    }) }}
                  </p>
                </div>
              </li>
              <!-- Step 2: 増分の基準 -->
              <li class="flex gap-2.5">
                <span class="shrink-0 w-5 h-5 rounded-full bg-rose-100 dark:bg-rose-900/40 text-rose-700 dark:text-rose-300 text-[10px] font-bold flex items-center justify-center">2</span>
                <div class="min-w-0 text-[11px] leading-snug text-slate-700 dark:text-slate-200">
                  <p class="font-bold text-slate-800 dark:text-slate-100">{{ t('adviceReason.step2Title') }}</p>
                  <p v-if="item.inTop100">
                    {{ t('adviceReason.step2InTop100', { pt: item.currentBeatPt.toFixed(1), current: label(item.currentScore) }) }}
                  </p>
                  <p v-else>
                    {{ t(item.unplayed ? 'adviceReason.step2Unplayed' : 'adviceReason.step2Outside', { threshold: item.baselinePt.toFixed(1) }) }}
                  </p>
                  <p class="text-slate-500 dark:text-slate-400 tabular-nums">
                    {{ t('adviceReason.step2BreakEven', {
                      score: label(item.breakEvenScore), raw: item.breakEvenScore.toLocaleString(), p: pct(item.breakEvenProbability),
                    }) }}
                  </p>
                </div>
              </li>
              <!-- Step 3: 目標の選択 -->
              <li class="flex gap-2.5">
                <span class="shrink-0 w-5 h-5 rounded-full bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-300 text-[10px] font-bold flex items-center justify-center">3</span>
                <div class="min-w-0 text-[11px] leading-snug text-slate-700 dark:text-slate-200">
                  <p class="font-bold text-slate-800 dark:text-slate-100">{{ t('adviceReason.step3Title') }}</p>
                  <p>{{ t('adviceReason.step3Body', { cap: capBelowMax ? label(item.scoreCap) : 'MAX' }) }}</p>
                  <p v-if="item.targetLabel" class="text-slate-500 dark:text-slate-400">{{ t('adviceReason.step3Border', { label: item.targetLabel }) }}</p>
                  <p v-else-if="item.achieveProbability < 0.5" class="text-slate-500 dark:text-slate-400">{{ t('adviceReason.step3AboveMedian') }}</p>
                </div>
              </li>
            </ol>
          </section>

          <!-- 4. 推定に使った譜面 -->
          <section class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 p-3 sm:p-4">
            <div class="flex items-baseline justify-between mb-2">
              <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500">{{ t('adviceReason.refsLabel') }}</p>
              <span v-if="!isRough && !refsLoading && !refsError" class="text-[10px] font-bold text-slate-400 dark:text-slate-500">
                {{ t('potentialDetails.refsCount', { n: visibleRefs.length }) }}
              </span>
            </div>
            <p v-if="isRough" class="text-[11px] text-slate-600 dark:text-slate-300 leading-snug">
              {{ t(item.accuracy === 'BASE' ? 'adviceReason.refsRoughBase' : 'adviceReason.refsRoughRank') }}
            </p>
            <div v-else-if="refsLoading" class="text-center py-4 text-xs text-slate-400 dark:text-slate-500">
              {{ t('potentialDetails.loadingRefs') }}
            </div>
            <div v-else-if="refsError" class="text-center py-4 text-xs text-rose-500">{{ refsError }}</div>
            <div v-else-if="visibleRefs.length === 0" class="text-center py-4 text-xs text-slate-400 dark:text-slate-500">
              {{ t('potentialDetails.noRefs') }}
            </div>
            <ul v-else class="space-y-1">
              <li
                v-for="r in shownRefs"
                :key="`${r.title}|${r.difficultyName}`"
                class="flex items-center gap-2 text-[11px]"
              >
                <div class="flex-1 min-w-0">
                  <p class="font-bold text-slate-800 dark:text-slate-100 truncate">
                    {{ r.title }}
                    <span class="ml-1 font-normal text-slate-400 dark:text-slate-500">{{ diffShort(r.difficultyName) }}</span>
                  </p>
                  <div class="h-1 rounded-full bg-slate-100 dark:bg-slate-700 overflow-hidden mt-0.5">
                    <div class="h-full bg-sky-500 dark:bg-sky-400 rounded-full" :style="{ width: `${Math.max(2, contribution(r))}%` }"></div>
                  </div>
                </div>
                <div class="shrink-0 text-right tabular-nums leading-tight">
                  <p class="font-bold text-slate-700 dark:text-slate-200">{{ t('adviceReason.refYourScore') }} {{ refScoreLabel(r) }}</p>
                  <p class="text-[10px] text-slate-400 dark:text-slate-500">|r| {{ Math.abs(r.r).toFixed(2) }} · {{ contribution(r).toFixed(0) }}%</p>
                </div>
              </li>
              <li v-if="hiddenRefCount > 0" class="text-[10px] text-slate-400 dark:text-slate-500 pt-1">
                {{ t('adviceReason.refsMore', { n: hiddenRefCount }) }}
              </li>
            </ul>
          </section>

        </div>
      </div>
    </div>
  </Teleport>
</template>
