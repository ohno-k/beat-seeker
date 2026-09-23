<script setup lang="ts">
/**
 * 【Viewの役割】 譜面別スコア分布（スペクトラム）。管理者（ID18）専用の検証ページ。
 *
 * 曲別平均スコアレートページは MAX- 率・AAA 率の 2 点で譜面の難しさを見ていたが、
 * 「MAX- 率は同じでも AAA は取りやすい」など分布の形の違いが見えない。
 * このページは各譜面のスコアレート分布そのものを描く。
 *
 * 機能:
 *  - 表: 各行に分布帯（理論値の 1/180 刻みの人数密度を青の濃淡で並べた帯）と、
 *    AAA / MAX- / 任意の到達ライン / 中央値 / 上位10% ライン / MAX-÷AAA を表示。どの列でもソート可。
 *  - 到達ラインスライダー: MAX-・AAA の 2 点を一般化し、任意のスコアレート以上の到達率で並べ替える。
 *  - 比較チャート: 行のチェックで最大 5 譜面を重ね、到達率カーブ（x 以上を取った人の割合）
 *    または分布密度を描く。ホバーで各譜面の値を表示。
 *
 * データ:
 *  - API `/api/scores/song-score-spectrum`（管理者専用）。hist[b] = 理論値の b/180 以上 (b+1)/180 未満の人数。
 *    DJ LEVEL 境界は k/18 なので b=170 が MAX-、160 が AAA、140 が AA と桶の境目に一致する。
 *  - 集計はサーバーがバックグラウンドで行う。未計算時は ready=false が返るのでポーリングする。
 */
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue';
import { useGameData } from '../composables/useGameData';
import { useAuth } from '../composables/useAuth';
import { useDarkMode } from '../composables/useDarkMode';
import { formatJstDateTime } from '../utils/jstTime';

const { diffTableRanks } = useGameData();
const { authHeaders } = useAuth();
const { isDarkMode } = useDarkMode();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

/** 桶の数 - 1（理論値 = 180）。 */
const MAX_B = 180;
/** DJ LEVEL 境界（桶番号）。 */
const GRADE_LINES = [
  { b: 120, label: 'A' },
  { b: 140, label: 'AA' },
  { b: 160, label: 'AAA' },
  { b: 170, label: 'MAX-' },
];
/** 分布帯・チャートの x 範囲（桶番号）。A(66.7%) 〜 理論値。 */
const X_MIN = 120;
const X_MAX = MAX_B;

interface ChartRow {
  title: string;
  difficultyName: string;
  level: number;
  notes: number;
  playerCount: number;
  avgScoreRate: number;
  hist: number[];
}

/** 画面用に派生値を載せた行。 */
interface Row extends ChartRow {
  key: string;
  rank: string;
  /** atLeast[b] = b 以上の人数（累積）。 */
  atLeast: number[];
  aaaRate: number;
  maxMinusRate: number;
  median: number;
  top10: number;
  mmPerAaa: number;
}

const isLoading = ref(true);
const errorMsg = ref('');
const charts = ref<ChartRow[]>([]);
const computedAt = ref<string | null>(null);
const serverRefreshing = ref(false);
let pollTimer: ReturnType<typeof setTimeout> | null = null;

/** 【関数の役割】 API を叩き、未計算ならポーリングを続ける。 */
async function load(force = false) {
  if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
  try {
    const res = await fetch(`${API_BASE}/api/scores/song-score-spectrum${force ? '?refresh=true' : ''}`, {
      headers: authHeaders(),
    });
    if (!res.ok) {
      errorMsg.value = res.status === 403 ? '管理者専用のページです' : `APIエラー: ${res.status}`;
      isLoading.value = false;
      return;
    }
    const data = await res.json();
    serverRefreshing.value = !!data.refreshing;
    if (data.error) errorMsg.value = `集計エラー: ${data.error}`;
    if (data.ready) {
      charts.value = data.charts;
      computedAt.value = data.computedAt;
      isLoading.value = false;
    }
    // 計算中は 5 秒おきに再取得（初回・再計算とも）。
    if (data.refreshing) pollTimer = setTimeout(() => load(), 5000);
    else if (!data.ready) isLoading.value = false;
  } catch (e: any) {
    errorMsg.value = `通信エラー: ${e.message}`;
    isLoading.value = false;
  }
}

onMounted(() => load());
onBeforeUnmount(() => { if (pollTimer) clearTimeout(pollTimer); });

/** 難易度表の「曲名→ランク」逆引き（LEGGENDARIA は `曲名[L]`）。 */
const rankMap = computed(() => {
  const map = new Map<string, string>();
  for (const r of diffTableRanks.value) for (const s of r.songs) map.set(s, r.rank);
  return map;
});

/** 桶番号 → スコアレート（%）。 */
const bToRate = (b: number) => (b * 100) / MAX_B;

/** 【関数の役割】 累積人数から「上位 p 割が到達している最高の桶」を返す。 */
function topLine(atLeast: number[], total: number, p: number): number {
  for (let b = MAX_B; b >= 0; b--) if (atLeast[b] >= total * p) return b;
  return 0;
}

const rows = computed<Row[]>(() => charts.value.map((c) => {
  const atLeast = new Array(MAX_B + 2).fill(0);
  for (let b = MAX_B; b >= 0; b--) atLeast[b] = atLeast[b + 1] + (c.hist[b] ?? 0);
  const n = c.playerCount;
  const aaa = (atLeast[160] * 100) / n;
  const mm = (atLeast[170] * 100) / n;
  return {
    ...c,
    key: `${c.title}_${c.difficultyName}`,
    rank: rankMap.value.get(c.difficultyName === 'LEGGENDARIA' ? `${c.title}[L]` : c.title) ?? '-',
    atLeast,
    aaaRate: aaa,
    maxMinusRate: mm,
    median: bToRate(topLine(atLeast, n, 0.5)),
    top10: bToRate(topLine(atLeast, n, 0.1)),
    mmPerAaa: atLeast[160] > 0 ? (atLeast[170] * 100) / atLeast[160] : 0,
  };
}));

// ===== フィルタ・ソート =====
const search = ref('');
const levelFilter = ref<'all' | '11' | '12'>('12');
const minPlayers = ref(30);
/** 到達ライン（桶番号）。既定は MAX-。 */
const threshold = ref(170);
const thresholdRate = (r: Row) => (r.atLeast[threshold.value] * 100) / r.playerCount;

type SortKey = 'title' | 'rank' | 'playerCount' | 'avgScoreRate' | 'aaaRate' | 'maxMinusRate' | 'threshold' | 'median' | 'top10' | 'mmPerAaa';
const sortKey = ref<SortKey>('threshold');
const sortDir = ref<'asc' | 'desc'>('asc');

function sortValue(r: Row, k: SortKey): number | string {
  switch (k) {
    case 'title': return r.title;
    case 'rank': return parseFloat(r.rank) || 0;
    case 'threshold': return thresholdRate(r);
    default: return r[k];
  }
}

function toggleSort(k: SortKey) {
  if (sortKey.value === k) sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc';
  else { sortKey.value = k; sortDir.value = 'asc'; }
}
const sortIcon = (k: SortKey) => (sortKey.value !== k ? '↕' : sortDir.value === 'asc' ? '↑' : '↓');

const filtered = computed(() => {
  const q = search.value.trim().toLowerCase();
  const dir = sortDir.value === 'asc' ? 1 : -1;
  return rows.value
    .filter((r) => levelFilter.value === 'all' || String(r.level) === levelFilter.value)
    .filter((r) => r.playerCount >= minPlayers.value)
    .filter((r) => !q || r.title.toLowerCase().includes(q))
    .sort((a, b) => {
      const va = sortValue(a, sortKey.value);
      const vb = sortValue(b, sortKey.value);
      const primary = typeof va === 'string' ? va.localeCompare(vb as string) : (va as number) - (vb as number);
      return primary * dir || a.maxMinusRate - b.maxMinusRate;
    });
});

/** 表の表示件数（多いと描画が重いので段階表示）。 */
const visibleCount = ref(100);
watch([search, levelFilter, minPlayers, sortKey, sortDir], () => { visibleCount.value = 100; });
const visibleRows = computed(() => filtered.value.slice(0, visibleCount.value));

// ===== 色（dataviz 既定パレット。ライト/ダークで別ステップ） =====
const SERIES_LIGHT = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4'];
const SERIES_DARK = ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181'];
const seriesColors = computed(() => (isDarkMode.value ? SERIES_DARK : SERIES_LIGHT));
const ink = computed(() => isDarkMode.value
  ? { surface: '#1e293b', muted: '#898781', grid: '#334155', axis: '#475569', text: '#e2e8f0', ramp: [30, 41, 59, 134, 182, 239] }
  : { surface: '#ffffff', muted: '#898781', grid: '#e1e0d9', axis: '#c3c2b7', text: '#0b0b0b', ramp: [255, 255, 255, 24, 79, 149] });

/** 【関数の役割】 0〜1 の強さを、面の色 → 青の 1 色グラデーションに写す。 */
function rampColor(t: number): string {
  const [r0, g0, b0, r1, g1, b1] = ink.value.ramp;
  const k = Math.sqrt(Math.max(0, Math.min(1, t))); // 裾野も見えるよう平方根で持ち上げる
  const mix = (a: number, b: number) => Math.round(a + (b - a) * k);
  return `rgb(${mix(r0, r1)},${mix(g0, g1)},${mix(b0, b1)})`;
}

/**
 * 【関数の役割】 行の分布帯を CSS グラデーション 1 本で作る（1,000 行超でも DOM を増やさない）。
 * 各桶の人数をその行の最大桶で正規化して濃さにする。A 未満の人数は帯に出さない。
 */
function stripBackground(r: Row): string {
  let peak = 0;
  for (let b = X_MIN; b <= X_MAX; b++) peak = Math.max(peak, r.hist[b] ?? 0);
  const n = X_MAX - X_MIN + 1;
  const stops: string[] = [];
  for (let i = 0; i < n; i++) {
    const c = rampColor(peak ? (r.hist[X_MIN + i] ?? 0) / peak : 0);
    stops.push(`${c} ${(i / n) * 100}% ${((i + 1) / n) * 100}%`);
  }
  return `linear-gradient(to right, ${stops.join(', ')})`;
}
/** 桶番号 → 分布帯・チャート内の x 位置（%）。 */
const bToPct = (b: number) => ((b - X_MIN) / (X_MAX - X_MIN + 1)) * 100;

/** 分布帯ホバー中の情報（行キー・桶）。 */
const stripHover = ref<{ key: string; b: number; x: number } | null>(null);
function onStripMove(e: MouseEvent, r: Row) {
  const el = e.currentTarget as HTMLElement;
  const rect = el.getBoundingClientRect();
  const i = Math.floor(((e.clientX - rect.left) / rect.width) * (X_MAX - X_MIN + 1));
  const b = Math.max(X_MIN, Math.min(X_MAX, X_MIN + i));
  stripHover.value = { key: r.key, b, x: ((e.clientX - rect.left) / rect.width) * 100 };
}

// ===== 比較チャート =====
const MAX_COMPARE = 5;
const selectedKeys = ref<string[]>([]);
/** 初回ロード時だけ、現在の並びの先頭 3 譜面を選んでおく。 */
let initialSelectionDone = false;
watch(filtered, (list) => {
  if (initialSelectionDone || list.length === 0) return;
  initialSelectionDone = true;
  selectedKeys.value = list.slice(0, 3).map((r) => r.key);
}, { immediate: true });

function toggleSelect(r: Row) {
  const i = selectedKeys.value.indexOf(r.key);
  if (i >= 0) selectedKeys.value.splice(i, 1);
  else if (selectedKeys.value.length < MAX_COMPARE) selectedKeys.value.push(r.key);
}
const rowByKey = computed(() => new Map(rows.value.map((r) => [r.key, r])));
/** 色は選んだ順のスロットで固定（他の譜面を外しても色が変わらないよう、スロットを保持）。 */
const slotOf = ref(new Map<string, number>());
watch(selectedKeys, (keys) => {
  const next = new Map<string, number>();
  const used = new Set<number>();
  for (const k of keys) { const s = slotOf.value.get(k); if (s !== undefined) { next.set(k, s); used.add(s); } }
  for (const k of keys) {
    if (next.has(k)) continue;
    let s = 0; while (used.has(s)) s++;
    next.set(k, s); used.add(s);
  }
  slotOf.value = next;
}, { deep: true, immediate: true });
const selectedSeries = computed(() => selectedKeys.value
  .map((k) => rowByKey.value.get(k))
  .filter((r): r is Row => !!r)
  .map((r) => ({ row: r, color: seriesColors.value[slotOf.value.get(r.key) ?? 0] })));

const chartMode = ref<'survival' | 'density'>('survival');
const W = 800, H = 300, PAD_L = 44, PAD_R = 16, PAD_T = 16, PAD_B = 32;
const xOf = (b: number) => PAD_L + ((b - X_MIN) / (X_MAX - X_MIN)) * (W - PAD_L - PAD_R);

/** 【関数の役割】 モードに応じた桶 b の値（%）。密度は 3 桶移動平均で均す。 */
function seriesValue(r: Row, b: number): number {
  if (chartMode.value === 'survival') return (r.atLeast[b] * 100) / r.playerCount;
  let s = 0, n = 0;
  for (let d = -1; d <= 1; d++) { const bb = b + d; if (bb >= 0 && bb <= MAX_B) { s += r.hist[bb] ?? 0; n++; } }
  return (s / n) * 100 / r.playerCount;
}
const yMax = computed(() => {
  if (chartMode.value === 'survival') return 100;
  let m = 0;
  for (const { row } of selectedSeries.value) for (let b = X_MIN; b <= X_MAX; b++) m = Math.max(m, seriesValue(row, b));
  return Math.max(1, Math.ceil(m));
});
const yOf = (v: number) => PAD_T + (1 - v / yMax.value) * (H - PAD_T - PAD_B);
const yTicks = computed(() => {
  const step = chartMode.value === 'survival' ? 25 : Math.max(1, Math.ceil(yMax.value / 4));
  const t: number[] = [];
  for (let v = 0; v <= yMax.value + 1e-9; v += step) t.push(v);
  return t;
});
const xTicks = [70, 75, 80, 85, 90, 95, 100];
const rateToB = (rate: number) => (rate * MAX_B) / 100;

function pathFor(r: Row): string {
  let d = '';
  for (let b = X_MIN; b <= X_MAX; b++) d += `${d ? 'L' : 'M'}${xOf(b).toFixed(1)},${yOf(seriesValue(r, b)).toFixed(1)}`;
  return d;
}

/** チャートのホバー位置（桶番号）。 */
const hoverB = ref<number | null>(null);
function onChartMove(e: MouseEvent) {
  const svg = e.currentTarget as SVGSVGElement;
  const rect = svg.getBoundingClientRect();
  const x = ((e.clientX - rect.left) / rect.width) * W;
  const b = Math.round(X_MIN + ((x - PAD_L) / (W - PAD_L - PAD_R)) * (X_MAX - X_MIN));
  hoverB.value = b >= X_MIN && b <= X_MAX ? b : null;
}
const hoverTooltip = computed(() => {
  if (hoverB.value === null) return null;
  const b = hoverB.value;
  return {
    left: (xOf(b) / W) * 100,
    label: chartMode.value === 'survival' ? `${bToRate(b).toFixed(2)}% 以上` : `${bToRate(b).toFixed(2)}〜${bToRate(b + 1).toFixed(2)}%`,
    items: selectedSeries.value
      .map(({ row, color }) => ({ title: row.title, isL: row.difficultyName === 'LEGGENDARIA', color, v: seriesValue(row, b) }))
      .sort((a, c) => c.v - a.v),
  };
});

const thresholdLabel = computed(() => {
  const g = GRADE_LINES.find((x) => x.b === threshold.value);
  return `${bToRate(threshold.value).toFixed(2)}%${g ? `（${g.label}）` : ''}`;
});
</script>

<template>
  <div class="space-y-6">
    <div class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 p-6">
      <div class="flex flex-wrap items-start justify-between gap-3 mb-2">
        <h2 class="text-xl font-bold text-slate-900 dark:text-white">スコア分布</h2>
        <div class="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
          <span v-if="computedAt">集計: {{ formatJstDateTime(computedAt) }}</span>
          <span v-if="serverRefreshing" class="text-blue-600 dark:text-blue-400">再計算中…</span>
          <button
            class="px-2 py-1 rounded border border-slate-300 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-50"
            :disabled="serverRefreshing"
            @click="load(true)"
          >再計算</button>
        </div>
      </div>
      <p class="text-sm text-slate-500 dark:text-slate-400 mb-6">
        MAX- 率・AAA 率の 2 点ではなく、スコアレート分布そのものを譜面ごとに表示（ANOTHER+LEGGENDARIA、☆11+☆12、各プレイヤーの自己歴代ベストを 1 票）。
        分布は理論値の 1/180（≒0.56%）刻みで、A / AA / AAA / MAX- の境界と桶の境目が一致します。
      </p>

      <div v-if="isLoading" class="flex items-center justify-center py-12">
        <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
        <span class="ml-3 text-sm text-slate-500">{{ serverRefreshing ? 'サーバーで集計中です（1 分前後かかります）…' : '読み込み中...' }}</span>
      </div>
      <div v-else-if="errorMsg && charts.length === 0" class="text-red-500 text-sm py-4">{{ errorMsg }}</div>

      <template v-else>
        <p v-if="errorMsg" class="text-red-500 text-xs mb-2">{{ errorMsg }}（前回の集計を表示中）</p>

        <!-- 比較チャート -->
        <div class="rounded-md border border-slate-200 dark:border-slate-700 p-4 mb-6">
          <div class="flex flex-wrap items-center justify-between gap-2 mb-3">
            <div class="text-sm font-bold text-slate-700 dark:text-slate-200">
              {{ chartMode === 'survival' ? '到達率カーブ（そのスコアレート以上を取った人の割合）' : '分布密度（そのスコアレート帯にいる人の割合）' }}
            </div>
            <div class="inline-flex rounded border border-slate-300 dark:border-slate-600 overflow-hidden text-xs">
              <button class="px-2.5 py-1" :class="chartMode === 'survival' ? 'bg-slate-800 text-white dark:bg-slate-200 dark:text-slate-900' : 'text-slate-600 dark:text-slate-300'" @click="chartMode = 'survival'">到達率</button>
              <button class="px-2.5 py-1" :class="chartMode === 'density' ? 'bg-slate-800 text-white dark:bg-slate-200 dark:text-slate-900' : 'text-slate-600 dark:text-slate-300'" @click="chartMode = 'density'">密度</button>
            </div>
          </div>

          <!-- 凡例（選択中の譜面。× で外す） -->
          <div class="flex flex-wrap gap-x-4 gap-y-1 mb-2 text-xs">
            <span v-for="s in selectedSeries" :key="s.row.key" class="inline-flex items-center gap-1.5 text-slate-700 dark:text-slate-200">
              <span class="inline-block w-3 h-0.5 rounded" :style="{ background: s.color }"></span>
              {{ s.row.title }}<span v-if="s.row.difficultyName === 'LEGGENDARIA'" class="text-orange-500">[L]</span>
              <span class="text-slate-400">({{ s.row.rank }})</span>
              <button class="text-slate-400 hover:text-red-500" @click="toggleSelect(s.row)">×</button>
            </span>
            <span v-if="selectedSeries.length === 0" class="text-slate-400">下の表のチェックで譜面を選ぶと重ねて比較できます（最大 {{ MAX_COMPARE }} 譜面）</span>
          </div>

          <div class="relative">
            <svg :viewBox="`0 0 ${W} ${H}`" class="w-full h-auto select-none" @mousemove="onChartMove" @mouseleave="hoverB = null">
              <!-- グリッド・y 軸 -->
              <g v-for="v in yTicks" :key="`y${v}`">
                <line :x1="PAD_L" :x2="W - PAD_R" :y1="yOf(v)" :y2="yOf(v)" :stroke="v === 0 ? ink.axis : ink.grid" stroke-width="1" />
                <text :x="PAD_L - 6" :y="yOf(v) + 4" text-anchor="end" font-size="11" :fill="ink.muted" style="font-variant-numeric: tabular-nums">{{ v }}%</text>
              </g>
              <!-- x 軸目盛 -->
              <text v-for="r in xTicks" :key="`x${r}`" :x="xOf(rateToB(r))" :y="H - 12" text-anchor="middle" font-size="11" :fill="ink.muted">{{ r }}%</text>
              <!-- DJ LEVEL 境界 -->
              <g v-for="g in GRADE_LINES" :key="g.label">
                <line :x1="xOf(g.b)" :x2="xOf(g.b)" :y1="PAD_T" :y2="H - PAD_B" :stroke="ink.axis" stroke-width="1" stroke-dasharray="3 3" />
                <text :x="xOf(g.b) + 3" :y="PAD_T + 10" font-size="10" :fill="ink.muted">{{ g.label }}</text>
              </g>
              <!-- 系列 -->
              <path v-for="s in selectedSeries" :key="s.row.key" :d="pathFor(s.row)" fill="none" :stroke="s.color" stroke-width="2" stroke-linejoin="round" />
              <!-- ホバー: クロスヘア＋点 -->
              <g v-if="hoverB !== null">
                <line :x1="xOf(hoverB)" :x2="xOf(hoverB)" :y1="PAD_T" :y2="H - PAD_B" :stroke="ink.muted" stroke-width="1" />
                <circle v-for="s in selectedSeries" :key="`h${s.row.key}`" :cx="xOf(hoverB)" :cy="yOf(seriesValue(s.row, hoverB))" r="4" :fill="s.color" :stroke="ink.surface" stroke-width="2" />
              </g>
              <!-- 透明のヒット領域 -->
              <rect :x="PAD_L" :y="PAD_T" :width="W - PAD_L - PAD_R" :height="H - PAD_T - PAD_B" fill="transparent" />
            </svg>
            <div
              v-if="hoverTooltip && hoverTooltip.items.length"
              class="absolute top-2 pointer-events-none z-10 rounded-md border border-slate-200 dark:border-slate-600 bg-white/95 dark:bg-slate-900/95 shadow px-2.5 py-1.5 text-xs"
              :style="hoverTooltip.left > 60 ? { right: `${100 - hoverTooltip.left + 1}%` } : { left: `${hoverTooltip.left + 1}%` }"
            >
              <div class="font-bold text-slate-700 dark:text-slate-200 mb-1">{{ hoverTooltip.label }}</div>
              <div v-for="it in hoverTooltip.items" :key="it.title + it.isL" class="flex items-center gap-1.5 whitespace-nowrap">
                <span class="inline-block w-2 h-2 rounded-full" :style="{ background: it.color }"></span>
                <span class="text-slate-600 dark:text-slate-300 max-w-[14rem] truncate">{{ it.title }}{{ it.isL ? '[L]' : '' }}</span>
                <span class="ml-auto pl-3 font-mono font-bold text-slate-800 dark:text-slate-100">{{ it.v.toFixed(1) }}%</span>
              </div>
            </div>
          </div>
        </div>

        <!-- フィルタ・到達ライン -->
        <div class="flex flex-wrap items-center gap-3 mb-4 text-sm">
          <input v-model="search" type="text" placeholder="曲名で検索..." class="px-3 py-1.5 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 text-slate-800 dark:text-slate-100 w-48" />
          <select v-model="levelFilter" class="px-2 py-1.5 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 text-slate-800 dark:text-slate-100">
            <option value="12">☆12</option>
            <option value="11">☆11</option>
            <option value="all">☆11+12</option>
          </select>
          <label class="inline-flex items-center gap-1 text-slate-600 dark:text-slate-300">
            人数
            <input v-model.number="minPlayers" type="number" min="1" class="w-16 px-2 py-1.5 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900" />
            人以上
          </label>
          <label class="inline-flex items-center gap-2 text-slate-600 dark:text-slate-300 grow min-w-[16rem]">
            到達ライン
            <input v-model.number="threshold" type="range" :min="X_MIN" :max="X_MAX" step="1" class="grow accent-blue-600" />
            <span class="font-mono font-bold text-slate-800 dark:text-slate-100 w-32">{{ thresholdLabel }}</span>
          </label>
        </div>

        <p class="text-sm text-slate-500 mb-2">{{ filtered.length }}譜面</p>

        <div class="overflow-x-auto rounded-md border border-slate-200 dark:border-slate-700">
          <table class="w-full text-sm">
            <thead>
              <tr class="bg-slate-50 dark:bg-slate-700/50 border-b border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 whitespace-nowrap">
                <th class="px-2 py-2.5 w-8"></th>
                <th class="text-left px-2 py-2.5 font-bold cursor-pointer hover:text-blue-600" @click="toggleSort('title')">曲名 {{ sortIcon('title') }}</th>
                <th class="px-2 py-2.5 font-bold cursor-pointer hover:text-blue-600" @click="toggleSort('rank')">難易度 {{ sortIcon('rank') }}</th>
                <th class="px-2 py-2.5 font-bold cursor-pointer hover:text-blue-600" @click="toggleSort('playerCount')">人数 {{ sortIcon('playerCount') }}</th>
                <th class="px-2 py-2.5 font-bold cursor-pointer hover:text-blue-600" @click="toggleSort('avgScoreRate')">平均 {{ sortIcon('avgScoreRate') }}</th>
                <th class="px-2 py-2.5 font-bold cursor-pointer hover:text-blue-600" @click="toggleSort('aaaRate')">AAA {{ sortIcon('aaaRate') }}</th>
                <th class="px-2 py-2.5 font-bold cursor-pointer hover:text-blue-600" @click="toggleSort('maxMinusRate')">MAX- {{ sortIcon('maxMinusRate') }}</th>
                <th class="px-2 py-2.5 font-bold cursor-pointer hover:text-blue-600 bg-blue-50/60 dark:bg-blue-900/20" @click="toggleSort('threshold')" :title="`${thresholdLabel} 以上の割合`">≥{{ bToRate(threshold).toFixed(1) }}% {{ sortIcon('threshold') }}</th>
                <th class="px-2 py-2.5 font-bold cursor-pointer hover:text-blue-600" @click="toggleSort('median')" title="半数が到達しているスコアレート">中央値 {{ sortIcon('median') }}</th>
                <th class="px-2 py-2.5 font-bold cursor-pointer hover:text-blue-600" @click="toggleSort('top10')" title="上位10%が到達しているスコアレート">上位10% {{ sortIcon('top10') }}</th>
                <th class="px-2 py-2.5 font-bold cursor-pointer hover:text-blue-600" @click="toggleSort('mmPerAaa')" title="AAA 到達者のうち MAX- にも届いた割合。低いほど AAA→MAX- の壁が厚い">MAX-/AAA {{ sortIcon('mmPerAaa') }}</th>
                <th class="text-left px-2 py-2.5 font-bold min-w-[16rem]">
                  <div class="relative h-4 text-[10px] font-normal text-slate-400">
                    <span v-for="g in GRADE_LINES" :key="g.label" class="absolute -translate-x-1/2" :style="{ left: `${bToPct(g.b)}%` }">{{ g.label }}</span>
                  </div>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="r in visibleRows"
                :key="r.key"
                class="border-b border-slate-100 dark:border-slate-700/50 hover:bg-slate-50 dark:hover:bg-slate-700/30"
              >
                <td class="px-2 py-1.5 text-center">
                  <input
                    type="checkbox"
                    class="cursor-pointer"
                    :checked="selectedKeys.includes(r.key)"
                    :disabled="!selectedKeys.includes(r.key) && selectedKeys.length >= MAX_COMPARE"
                    :style="selectedKeys.includes(r.key) ? { accentColor: seriesColors[slotOf.get(r.key) ?? 0] } : {}"
                    @change="toggleSelect(r)"
                  />
                </td>
                <td class="px-2 py-1.5">
                  <span class="font-medium text-slate-800 dark:text-slate-200">{{ r.title }}</span>
                  <span v-if="r.difficultyName === 'LEGGENDARIA'" class="ml-1 text-[10px] font-bold text-orange-500 bg-orange-50 dark:bg-orange-900/30 px-1 rounded">[L]</span>
                </td>
                <td class="px-2 py-1.5 text-center font-mono text-slate-700 dark:text-slate-300">{{ r.rank }}</td>
                <td class="px-2 py-1.5 text-center text-slate-500 dark:text-slate-400">{{ r.playerCount }}</td>
                <td class="px-2 py-1.5 text-center font-mono text-slate-700 dark:text-slate-300">{{ r.avgScoreRate.toFixed(2) }}%</td>
                <td class="px-2 py-1.5 text-center font-mono text-slate-700 dark:text-slate-300">{{ r.aaaRate.toFixed(1) }}%</td>
                <td class="px-2 py-1.5 text-center font-mono text-slate-700 dark:text-slate-300">{{ r.maxMinusRate.toFixed(1) }}%</td>
                <td class="px-2 py-1.5 text-center font-mono font-bold text-slate-800 dark:text-slate-100 bg-blue-50/60 dark:bg-blue-900/20">{{ thresholdRate(r).toFixed(1) }}%</td>
                <td class="px-2 py-1.5 text-center font-mono text-slate-700 dark:text-slate-300">{{ r.median.toFixed(1) }}%</td>
                <td class="px-2 py-1.5 text-center font-mono text-slate-700 dark:text-slate-300">{{ r.top10.toFixed(1) }}%</td>
                <td class="px-2 py-1.5 text-center font-mono text-slate-700 dark:text-slate-300">{{ r.mmPerAaa.toFixed(1) }}%</td>
                <td class="px-2 py-1.5">
                  <!-- 分布帯: 濃い = その帯に人が多い。点線は DJ LEVEL 境界、縦棒は到達ライン -->
                  <div
                    class="relative h-5 rounded-sm cursor-crosshair"
                    :style="{ background: stripBackground(r) }"
                    @mousemove="onStripMove($event, r)"
                    @mouseleave="stripHover = null"
                  >
                    <span v-for="g in GRADE_LINES.slice(1)" :key="g.label" class="absolute top-0 bottom-0 border-l border-dashed border-slate-400/70" :style="{ left: `${bToPct(g.b)}%` }"></span>
                    <span class="absolute -top-0.5 -bottom-0.5 w-0.5 bg-red-500" :style="{ left: `${bToPct(threshold)}%` }"></span>
                    <div
                      v-if="stripHover && stripHover.key === r.key"
                      class="absolute bottom-full mb-1 z-10 pointer-events-none whitespace-nowrap rounded border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 shadow px-2 py-1 text-xs text-slate-700 dark:text-slate-200"
                      :class="stripHover.x > 60 ? '-translate-x-full' : ''"
                      :style="{ left: `${stripHover.x}%` }"
                    >
                      {{ bToRate(stripHover.b).toFixed(2) }}〜{{ bToRate(stripHover.b + 1).toFixed(2) }}%:
                      <b>{{ r.hist[stripHover.b] ?? 0 }}人</b>
                      ／以上 {{ ((r.atLeast[stripHover.b] * 100) / r.playerCount).toFixed(1) }}%
                    </div>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="visibleCount < filtered.length" class="text-center mt-3">
          <button class="px-4 py-1.5 text-sm rounded border border-slate-300 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-700 text-slate-600 dark:text-slate-300" @click="visibleCount += 200">
            さらに表示（残り {{ filtered.length - visibleCount }}）
          </button>
        </div>
      </template>
    </div>
  </div>
</template>
