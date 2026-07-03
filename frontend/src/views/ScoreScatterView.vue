<script setup lang="ts">
/**
 * 【View の役割】 2 譜面の (スコアA, スコアB) 散布図を表示する実験的ページ。
 *
 * - ユーザーは譜面 A と譜面 B をそれぞれ検索して選ぶ
 * - 両方を選んだ時点で API `/api/analysis/score-pair-scatter` を叩き、
 *   両譜面をプレイしているユーザー全員の (scoreA, scoreB) を取得して散布図化する
 * - n（両譜面プレイ済みユーザー数）を上部に大きく表示
 * - ホバーで displayName / 各譜面の score / score_rate(%) を表示
 *
 * MVP 段階のためフィルタ等は最小限。プレイヤー類型・予測精度の検証用。
 */
import { ref, reactive, computed, watch } from 'vue';
import {
  Chart as ChartJS, LinearScale, PointElement, LineElement, Tooltip, Legend,
} from 'chart.js';
import { Scatter } from 'vue-chartjs';
import { API_BASE } from '../composables/useAuth';
import { useGameData, getDifficultyCode, type SongDataEntry } from '../composables/useGameData';
import { useDarkMode } from '../composables/useDarkMode';
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();

ChartJS.register(LinearScale, PointElement, LineElement, Tooltip, Legend);

// DJ LEVEL 境界（score rate %）。A 未満は外れ値として除外、AA/AAA/MAX- がグリッド線になる。
const DJ_LEVEL_GRID: { value: number; label: string }[] = [
  { value: 200 / 3,    label: 'A' },     // 66.67%
  { value: 700 / 9,    label: 'AA' },    // 77.78%
  { value: 800 / 9,    label: 'AAA' },   // 88.89%
  { value: 1700 / 18,  label: 'MAX-' },  // 94.44%
  { value: 100,        label: 'MAX' },
];
const A_THRESHOLD = DJ_LEVEL_GRID[0].value;
const GRID_LABELS = new Map(DJ_LEVEL_GRID.map(g => [g.value, g.label]));

/**
 * 単純な線形回帰と相関係数を計算する。
 * @returns null（n<2 または分散 0）または { slope, intercept, r, n }
 */
function linearRegression(pts: { x: number; y: number }[]): { slope: number; intercept: number; r: number; n: number } | null {
  const n = pts.length;
  if (n < 2) return null;
  let sx = 0, sy = 0;
  for (const p of pts) { sx += p.x; sy += p.y; }
  const mx = sx / n, my = sy / n;
  let sxx = 0, syy = 0, sxy = 0;
  for (const p of pts) {
    const dx = p.x - mx, dy = p.y - my;
    sxx += dx * dx; syy += dy * dy; sxy += dx * dy;
  }
  if (sxx === 0 || syy === 0) return null;
  const slope = sxy / sxx;
  const intercept = my - slope * mx;
  const r = sxy / Math.sqrt(sxx * syy);
  return { slope, intercept, r, n };
}

const { songDataBody } = useGameData();
const { isDarkMode } = useDarkMode();

type Slot = 'A' | 'B';

// ── 譜面選択 ──────────────────────────────────────────────
// v-model に三項演算子は使えないため、slot をキーとする reactive オブジェクトに統一する。
const queries = reactive<Record<Slot, string>>({ A: '', B: '' });
const selected = reactive<Record<Slot, SongDataEntry | null>>({ A: null, B: null });

/**
 * 【computed の役割】 選択候補となる譜面（ANOTHER / LEGGENDARIA のみ、textage 必須）。
 * BEGINNER/NORMAL/HYPER は予測対象として意味が薄いので除外。
 */
const targetEntries = computed((): SongDataEntry[] =>
  songDataBody.value.filter(s => (s.difficulty === '4' || s.difficulty === '10') && !!s.textage)
);

function diffLabel(d: string): string {
  return d === '10' ? 'L' : d === '4' ? 'A' : d === '3' ? 'H' : d === '2' ? 'N' : 'B';
}
function diffName(d: string): string {
  return d === '10' ? 'LEGGENDARIA' : 'ANOTHER';
}
function diffBadgeClass(d: string): string {
  return d === '10'
    ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300'
    : 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300';
}

/**
 * 【関数】 検索文字列で譜面候補を絞り込む（最大 60 件）。
 * 空クエリのときは先頭 40 件を表示してすぐ選びやすくする。
 */
function filteredEntries(query: string): SongDataEntry[] {
  const q = query.trim().toLowerCase();
  if (!q) return targetEntries.value.slice(0, 40);
  return targetEntries.value
    .filter(s => s.title.toLowerCase().includes(q) || s.artist.toLowerCase().includes(q))
    .slice(0, 60);
}
/**
 * 【関数】 Bピッカーの並び替え。
 * 譜面 A 選択後に取得した相関係数マップが populated なら |r| 降順、未取得ならアルファベット順のまま。
 * 検索フィルタはその上に乗る形（呼び出し側で filteredEntries 済みリストを渡す）。
 */
function sortByCorrelation(entries: SongDataEntry[]): SongDataEntry[] {
  if (correlations.size === 0) return entries;
  return [...entries].sort((a, b) => {
    const ka = `${a.title}\0${a.difficulty}`;
    const kb = `${b.title}\0${b.difficulty}`;
    // |r| 降順、未掲載は末尾
    const ra = correlations.get(ka);
    const rb = correlations.get(kb);
    const va = ra ? Math.abs(ra.r) : -Infinity;
    const vb = rb ? Math.abs(rb.r) : -Infinity;
    return vb - va;
  });
}

const filtered = computed(() => ({
  A: filteredEntries(queries.A),
  B: sortByCorrelation(filteredEntries(queries.B)),
}));

function pick(slot: Slot, entry: SongDataEntry) {
  selected[slot] = entry;
  if (slot === 'B') {
    // 手動 B 選択: 自動選択ではないことをマーク（バッジを消す）
    autoPickedB.value = false;
    // 自動選択ロジックの非同期処理が走っていても、ユーザーの選択を尊重する
    if (selected.A) fetchScatter();
  }
  // slot === 'A' は selected.A の watcher が autoSelectB → fetchScatter を起動する
}

// ── 譜面 B の自動選択（A と最も相関の高い譜面） ──────────────
const autoPickedB = ref(false);
const isAutoPicking = ref(false);

interface CorrelationCandidate {
  title: string;
  difficultyName: string;
  n: number;
  r: number;
  stddevB?: number;
}

// 譜面 A 選択時の相関候補マップ（key = `${title}\0${difficultyCodeStr}`）。
// B ピッカーの並び替え＆ r バッジ表示に使う。reactive にせず ref にして上書きで通知。
const correlations = reactive(new Map<string, { r: number; n: number }>());

/**
 * 【関数】 A の選択時に、最も相関係数の絶対値が高い譜面を B にセットする。
 * 候補が見つからなければ B はそのまま（あるいは null）にする。
 */
async function autoSelectB(a: SongDataEntry) {
  isAutoPicking.value = true;
  autoPickedB.value = false;
  // 既存の相関情報をクリア（A が切り替わったため）
  correlations.clear();
  try {
    const codeA = getDifficultyCode(diffName(a.difficulty));
    if (codeA == null) return;
    const params = new URLSearchParams({
      title: a.title,
      difficulty: diffName(a.difficulty),
      difficultyCode: String(codeA),
      // ピッカーの並べ替えに使うため上限を大きめに
      limit: '500',
      minN: '30',
    });
    const res = await fetch(`${API_BASE}/api/analysis/top-correlated?${params}`);
    if (!res.ok) return;
    const data = await res.json() as { candidates: CorrelationCandidate[] };
    // 取得した候補を全件マップ化（ピッカーの並び替え＆ r バッジ表示用）
    for (const c of data.candidates) {
      const codeStr = String(getDifficultyCode(c.difficultyName) ?? '');
      correlations.set(`${c.title}\0${codeStr}`, { r: c.r, n: c.n });
    }
    const top = data.candidates?.[0];
    if (!top) return;
    // candidate の (title, difficultyName) と一致する SongDataEntry を探す
    const codeStr = String(getDifficultyCode(top.difficultyName) ?? '');
    const match = targetEntries.value.find(s => s.title === top.title && s.difficulty === codeStr);
    if (match) {
      selected.B = match;
      autoPickedB.value = true;
    }
  } catch (e) {
    console.error('autoSelectB failed', e);
  } finally {
    isAutoPicking.value = false;
  }
}

// テンプレートで `correlations.get(...)` を直接呼び出すためのヘルパー
function correlationFor(entry: SongDataEntry): { r: number; n: number } | undefined {
  return correlations.get(`${entry.title}\0${entry.difficulty}`);
}

// ── API 呼び出し ──────────────────────────────────────────
interface ScatterRow {
  userId: number;
  displayName: string | null;
  privacyLevel: number | null;
  scoreA: number;
  scoreB: number;
  notesA: number | null;
  notesB: number | null;
}
interface ScatterResp {
  n: number;
  points: ScatterRow[];
}

const isLoading = ref(false);
const errorMsg = ref('');
const scatterData = ref<ScatterResp | null>(null);

async function fetchScatter() {
  if (!selected.A || !selected.B) return;
  const a = selected.A, b = selected.B;
  const codeA = getDifficultyCode(diffName(a.difficulty));
  const codeB = getDifficultyCode(diffName(b.difficulty));
  if (codeA == null || codeB == null) return;

  isLoading.value = true;
  errorMsg.value = '';
  scatterData.value = null;
  try {
    const params = new URLSearchParams({
      titleA: a.title, difficultyA: diffName(a.difficulty), difficultyCodeA: String(codeA),
      titleB: b.title, difficultyB: diffName(b.difficulty), difficultyCodeB: String(codeB),
    });
    const res = await fetch(`${API_BASE}/api/analysis/score-pair-scatter?${params}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    scatterData.value = await res.json();
  } catch (e: any) {
    errorMsg.value = e.message ?? '通信エラー';
  } finally {
    isLoading.value = false;
  }
}

// 譜面 A が変わったら最も相関の高い B を自動選択し、その後で散布図を取得する。
// 既存の B が手動選択されていても上書きする（仕様：「A 選択時に B を表示」）。
watch(() => selected.A, async (a) => {
  if (!a) return;
  await autoSelectB(a);
  if (selected.A && selected.B) fetchScatter();
});

// ── 散布図用のデータ整形 ────────────────────────────────
/**
 * notesA / notesB が取れた場合のみ「達成率」を計算してそちらを軸に使う。
 * ノーツ情報が無い古いレコードは max=score の 1.05 倍などフォールバックは入れず、
 * notes が NULL の点は素直に raw EX score 軸で扱う（情報不足を可視化する）。
 */
function maxScore(notes: number | null): number | null {
  return notes != null && notes > 0 ? notes * 2 : null;
}

/**
 * 【computed の役割】 API レスポンス → 散布図用の点列に変換し、A 未満（外れ値）を除外する。
 * notes が欠落しているレコードは率を出せないので併せて除外する。
 */
const visiblePoints = computed(() => {
  if (!scatterData.value) return [];
  return scatterData.value.points
    .map(p => {
      const maxA = maxScore(p.notesA);
      const maxB = maxScore(p.notesB);
      if (maxA == null || maxB == null) return null;
      return {
        x: (p.scoreA / maxA) * 100,
        y: (p.scoreB / maxB) * 100,
        _meta: { ...p, maxA, maxB },
      };
    })
    .filter((p): p is NonNullable<typeof p> => p !== null && p.x >= A_THRESHOLD && p.y >= A_THRESHOLD);
});

/** 線形回帰と相関係数。フィルタ後の点が 2 件未満なら null。 */
const regression = computed(() => linearRegression(visiblePoints.value));

const chartData = computed(() => {
  const pts = visiblePoints.value;
  const datasets: any[] = [
    {
      label: t('scatter.player'),
      data: pts,
      backgroundColor: isDarkMode.value ? 'rgba(96,165,250,0.55)' : 'rgba(59,130,246,0.55)',
      borderColor: isDarkMode.value ? 'rgba(96,165,250,0.9)' : 'rgba(59,130,246,0.9)',
      pointRadius: 4,
      pointHoverRadius: 7,
    },
  ];

  // 回帰直線: x = A_THRESHOLD と x = 100 の 2 点で線分を描く（chart.js が viewport にクリップ）
  const reg = regression.value;
  if (reg) {
    const x0 = A_THRESHOLD, x1 = 100;
    datasets.push({
      label: t('scatter.regression'),
      data: [
        { x: x0, y: reg.slope * x0 + reg.intercept },
        { x: x1, y: reg.slope * x1 + reg.intercept },
      ],
      showLine: true,
      borderColor: isDarkMode.value ? 'rgba(248,113,113,0.95)' : 'rgba(220,38,38,0.95)',
      backgroundColor: 'transparent',
      borderWidth: 2,
      borderDash: [6, 4],
      pointRadius: 0,
      pointHoverRadius: 0,
      // 散布図の点と重なってもツールチップを汚さないよう、回帰線の hover を無効化
      pointHitRadius: 0,
      order: -1,
    });
  }

  return { datasets };
});

const chartOptions = computed(() => {
  const grid = isDarkMode.value ? 'rgba(148,163,184,0.18)' : 'rgba(148,163,184,0.32)';
  const tickColor = isDarkMode.value ? '#cbd5e1' : '#475569';
  const titleColor = isDarkMode.value ? '#e2e8f0' : '#1e293b';
  const tipBg = isDarkMode.value ? 'rgba(15,23,42,0.95)' : 'rgba(255,255,255,0.97)';
  const tipText = isDarkMode.value ? '#f1f5f9' : '#0f172a';
  const tipBorder = isDarkMode.value ? 'rgba(148,163,184,0.3)' : 'rgba(148,163,184,0.5)';
  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: false as const,
    scales: {
      x: {
        type: 'linear' as const,
        min: A_THRESHOLD, max: 100,
        title: {
          display: true,
          text: selected.A ? `${selected.A.title} (${diffName(selected.A.difficulty)})` : t('scatter.chartASlot'),
          color: titleColor,
          font: { weight: 'bold' as const, size: 11 },
        },
        grid: { color: grid },
        ticks: {
          color: tickColor,
          autoSkip: false,
          callback: (v: number | string) => GRID_LABELS.get(Number(v)) ?? '',
        },
        afterBuildTicks: (axis: any) => {
          axis.ticks = DJ_LEVEL_GRID.map(g => ({ value: g.value }));
        },
      },
      y: {
        type: 'linear' as const,
        min: A_THRESHOLD, max: 100,
        title: {
          display: true,
          text: selected.B ? `${selected.B.title} (${diffName(selected.B.difficulty)})` : t('scatter.chartBSlot'),
          color: titleColor,
          font: { weight: 'bold' as const, size: 11 },
        },
        grid: { color: grid },
        ticks: {
          color: tickColor,
          autoSkip: false,
          callback: (v: number | string) => GRID_LABELS.get(Number(v)) ?? '',
        },
        afterBuildTicks: (axis: any) => {
          axis.ticks = DJ_LEVEL_GRID.map(g => ({ value: g.value }));
        },
      },
    },
    plugins: {
      legend: {
        position: 'top' as const,
        labels: { color: titleColor, usePointStyle: true, boxWidth: 8 },
      },
      tooltip: {
        backgroundColor: tipBg,
        titleColor: tipText,
        bodyColor: tipText,
        borderColor: tipBorder,
        borderWidth: 1,
        padding: 10,
        callbacks: {
          title: () => '',
          label: (ctx: any) => {
            const m = ctx.raw._meta as ScatterRow & { maxA: number; maxB: number };
            const isPrivate = (m.privacyLevel ?? 1) !== 0;
            const name = isPrivate ? t('scatter.privateUser') : (m.displayName || t('scatter.noName'));
            const rateA = (m.scoreA / m.maxA) * 100;
            const rateB = (m.scoreB / m.maxB) * 100;
            return [
              name,
              `A: ${m.scoreA.toLocaleString()}  (${rateA.toFixed(2)}%)`,
              `B: ${m.scoreB.toLocaleString()}  (${rateB.toFixed(2)}%)`,
            ];
          },
        },
      },
    },
  };
});

// 散布図に実際に乗った点の数（A 未満や notes 欠落で除外された点を引いた値）
const pointsRendered = computed(() => visiblePoints.value.length);
const droppedCount = computed(() => (scatterData.value?.n ?? 0) - pointsRendered.value);
</script>

<template>
  <div class="w-full max-w-6xl mx-auto space-y-6 animate-fade-in">
    <div class="bg-white dark:bg-slate-800 p-6 sm:p-8 rounded-md border border-slate-200 dark:border-slate-700">
      <!-- ヘッダー -->
      <div class="flex items-center gap-4 mb-6">
        <div class="p-3 rounded-md bg-cyan-100 dark:bg-cyan-900/30">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-7 w-7 text-cyan-600 dark:text-cyan-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <circle cx="6" cy="18" r="2" stroke-width="2"/>
            <circle cx="11" cy="9" r="2" stroke-width="2"/>
            <circle cx="17" cy="14" r="2" stroke-width="2"/>
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 21V3"/>
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 21h18"/>
          </svg>
        </div>
        <div>
          <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">{{ t('scatter.title') }}</h2>
          <p class="text-slate-500 dark:text-slate-400 text-sm">{{ t('scatter.subtitle') }}</p>
        </div>
      </div>

      <!-- 譜面ピッカー（A / B 並列）。
           B は譜面 A が選択されてから表示する。
           A 未選択時は A を全幅にしてレイアウトの偏りを防ぐ。 -->
      <div class="grid grid-cols-1 md:grid-cols-2 gap-4 mb-6">
        <div v-for="slot in (['A','B'] as const)" :key="slot"
          v-show="slot === 'A' || selected.A"
          class="rounded-md border border-slate-200 dark:border-slate-700 p-4 bg-slate-50 dark:bg-slate-900/40"
          :class="{ 'md:col-span-2': slot === 'A' && !selected.A }">
          <div class="flex items-center justify-between mb-2">
            <span class="text-xs font-bold text-slate-500 dark:text-slate-400">{{ slot === 'A' ? t('scatter.chartASlot') : t('scatter.chartBSlot') }}</span>
            <span v-if="slot === 'B' && isAutoPicking"
              class="text-[10px] font-bold text-cyan-600 dark:text-cyan-400 inline-flex items-center gap-1">
              <span class="w-3 h-3 border-2 border-cyan-200 dark:border-slate-700 border-t-cyan-500 rounded-full animate-spin"></span>
              {{ t('scatter.autoPicking') }}
            </span>
            <span v-else-if="slot === 'B' && autoPickedB && selected.B"
              class="text-[10px] font-bold text-cyan-600 dark:text-cyan-400">{{ t('scatter.autoPicked') }}</span>
            <span v-else-if="selected[slot]"
              class="text-[10px] font-bold text-emerald-600 dark:text-emerald-400">{{ t('scatter.selected') }}</span>
          </div>

          <!-- 選択中譜面の表示 -->
          <div v-if="selected[slot]" class="mb-3 flex items-center gap-2">
            <span class="px-2 py-0.5 rounded text-[10px] font-bold"
              :class="diffBadgeClass(selected[slot]!.difficulty)">
              {{ diffLabel(selected[slot]!.difficulty) }}{{ selected[slot]!.level }}
            </span>
            <span class="font-bold text-sm text-slate-800 dark:text-slate-100 truncate">
              {{ selected[slot]!.title }}
            </span>
          </div>

          <!-- 検索ボックス -->
          <input
            v-model="queries[slot]"
            type="text"
            :placeholder="t('scatter.searchPlaceholder')"
            class="w-full px-3 py-1.5 text-sm rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 mb-2"
          />

          <!-- 候補リスト。Bピッカーは A と相関の高い順に並ぶ -->
          <ul class="max-h-48 overflow-y-auto divide-y divide-slate-100 dark:divide-slate-700/50 rounded-lg border border-slate-100 dark:border-slate-700/50 bg-white dark:bg-slate-800">
            <li v-for="entry in filtered[slot]" :key="`${slot}-${entry.textage}`"
              @click="pick(slot, entry)"
              class="px-2 py-1.5 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-700/40 flex items-center gap-2">
              <span class="px-1.5 py-0.5 rounded text-[10px] font-bold shrink-0"
                :class="diffBadgeClass(entry.difficulty)">
                {{ diffLabel(entry.difficulty) }}{{ entry.level }}
              </span>
              <span class="text-xs text-slate-700 dark:text-slate-200 truncate flex-1">{{ entry.title }}</span>
              <!-- B ピッカーで相関係数があれば表示（自動並び替えの根拠） -->
              <span v-if="slot === 'B' && correlationFor(entry)"
                class="text-[10px] font-bold tabular-nums shrink-0"
                :class="Math.abs(correlationFor(entry)!.r) >= 0.7 ? 'text-emerald-600 dark:text-emerald-400'
                      : Math.abs(correlationFor(entry)!.r) >= 0.4 ? 'text-amber-600 dark:text-amber-400'
                      : 'text-slate-400'">
                r={{ correlationFor(entry)!.r.toFixed(2) }}
              </span>
            </li>
            <li v-if="filtered[slot].length === 0"
              class="px-2 py-3 text-center text-xs text-slate-400">{{ t('scatter.noMatches') }}</li>
          </ul>
        </div>
      </div>

      <!-- 結果エリア -->
      <div v-if="!selected.A || !selected.B"
        class="h-64 flex items-center justify-center text-slate-400 dark:text-slate-500 text-sm border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-md">
        {{ t('scatter.selectTwo') }}
      </div>

      <div v-else-if="isLoading"
        class="h-64 flex items-center justify-center text-slate-400 dark:text-slate-500">
        <div class="w-10 h-10 border-4 border-cyan-100 dark:border-slate-700 border-t-cyan-500 rounded-full animate-spin mr-3"></div>
        {{ t('scatter.loading') }}
      </div>

      <div v-else-if="errorMsg"
        class="p-6 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-md text-center font-bold">
        {{ errorMsg }}
      </div>

      <div v-else-if="scatterData">
        <!-- 統計サマリ: n / 相関係数 r / 回帰式 -->
        <div class="grid grid-cols-1 sm:grid-cols-3 gap-3 mb-4">
          <div class="rounded-md bg-slate-50 dark:bg-slate-900/40 border border-slate-200 dark:border-slate-700 p-4">
            <div class="text-[10px] font-bold text-slate-400">{{ t('scatter.nLabelAbove') }}</div>
            <div class="text-3xl font-bold text-cyan-600 dark:text-cyan-400 tabular-nums">
              {{ pointsRendered.toLocaleString() }}
              <span class="text-xs font-bold text-slate-400 ml-1">/ {{ scatterData.n.toLocaleString() }}</span>
            </div>
            <div v-if="droppedCount > 0" class="text-[10px] text-amber-500 mt-0.5">
              {{ t('scatter.excludedCount', { count: droppedCount }) }}
            </div>
          </div>
          <div class="rounded-md bg-slate-50 dark:bg-slate-900/40 border border-slate-200 dark:border-slate-700 p-4">
            <div class="text-[10px] font-bold text-slate-400">{{ t('scatter.correlation') }}</div>
            <div class="text-3xl font-bold tabular-nums"
              :class="regression ? (Math.abs(regression.r) >= 0.7 ? 'text-emerald-600 dark:text-emerald-400'
                                  : Math.abs(regression.r) >= 0.4 ? 'text-amber-600 dark:text-amber-400'
                                  : 'text-slate-500 dark:text-slate-400') : 'text-slate-400'">
              {{ regression ? regression.r.toFixed(3) : '—' }}
            </div>
            <div class="text-[10px] text-slate-400 mt-0.5">
              r² = {{ regression ? (regression.r * regression.r).toFixed(3) : '—' }}
            </div>
          </div>
          <div class="rounded-md bg-slate-50 dark:bg-slate-900/40 border border-slate-200 dark:border-slate-700 p-4">
            <div class="text-[10px] font-bold text-slate-400">{{ t('scatter.regressionEq') }}</div>
            <div class="text-base font-bold tabular-nums text-red-600 dark:text-red-400 mt-1">
              <template v-if="regression">
                y = {{ regression.slope.toFixed(3) }} x
                {{ regression.intercept >= 0 ? '+' : '−' }}
                {{ Math.abs(regression.intercept).toFixed(2) }}
              </template>
              <template v-else>—</template>
            </div>
            <div class="text-[10px] text-slate-400 mt-0.5">{{ t('scatter.regressionAxisHint') }}</div>
          </div>
        </div>

        <!-- 散布図 -->
        <div class="rounded-md border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/40 p-3 sm:p-5">
          <div v-if="pointsRendered === 0"
            class="h-72 flex items-center justify-center text-slate-400 text-sm">
            {{ t('scatter.noPointsInA') }}
          </div>
          <div v-else class="w-full h-80 sm:h-[28rem]">
            <Scatter :data="chartData" :options="chartOptions" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.4s ease-out forwards;
}
@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
