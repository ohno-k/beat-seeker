<script setup lang="ts">
/**
 * 【Component の役割】 選曲アシストの「○譜面から推定」内訳モーダル。
 *
 *  - 左カラム: 対象譜面 B の予測に寄与した参照譜面 A の一覧（|r| 降順）
 *  - 右カラム: 左で選んだ A について、A×B の散布図を描画
 *
 *  モバイル（< md）では縦積み。譜面 A をタップしたら散布図セクションへ自動スクロール。
 *  デスクトップ（≥ md）では左右並列で常時両方が見える。
 */
import { ref, computed, watch, nextTick } from 'vue';
import {
  Chart as ChartJS, LinearScale, PointElement, LineElement, Tooltip, Legend,
} from 'chart.js';
import { Scatter } from 'vue-chartjs';
import { API_BASE, useAuth } from '../composables/useAuth';
import { useDarkMode } from '../composables/useDarkMode';
import { useI18n } from '../composables/useI18n';
import { getDifficultyCode } from '../composables/useGameData';

ChartJS.register(LinearScale, PointElement, LineElement, Tooltip, Legend);

const { t } = useI18n();
const { authHeaders, user: authUser } = useAuth();
const { isDarkMode } = useDarkMode();

interface Props {
  /** 対象譜面 B の曲名 */
  targetTitle: string;
  /** 対象譜面 B の難易度名（"ANOTHER" / "LEGGENDARIA"） */
  targetDifficultyName: string;
  /** 対象譜面 B の難易度レベル（11 / 12） */
  targetDifficultyLevel: number;
  /** 対象譜面 B の難易度バッジ用カラークラス */
  targetDifficultyColor?: string;
  /** 対象譜面 B の現在スコア */
  targetCurrentScore: number;
  /** 対象譜面 B の予測スコア */
  targetPredictedScore: number;
  /** 対象譜面 B の現在スコアレート (%) */
  targetCurrentRate?: number;
  /** 対象譜面 B の予測スコアレート (%) */
  targetPredictedRate?: number;
  /** 対象譜面 B の最大スコア */
  targetMaxScore?: number;
  /** 推定に使われた譜面数（subPotential の件数） */
  targetSupportCount?: number;
  /** 'potential' or 'strength': アクセントカラーの切替に使う */
  mode: 'potential' | 'strength';
  /** 管理者検証時の対象ユーザー ID。null ならログイン中ユーザーを対象にする。 */
  viewUserId?: number | null;
}

const props = withDefaults(defineProps<Props>(), {
  targetCurrentRate: undefined,
  targetPredictedRate: undefined,
  targetMaxScore: undefined,
  targetSupportCount: undefined,
  targetDifficultyColor: undefined,
  viewUserId: null,
});

const emit = defineEmits<{ close: [] }>();

// ── 参照譜面 A 一覧 ──────────────────────────────────────
interface RefItem {
  title: string;
  difficultyName: string;
  difficultyLevel: number;
  actualA: number;
  notesA?: number;
  notesB?: number;
  r: number;
  n: number;
  slope: number;
  intercept: number;
  weight: number;
  predScore: number;
  isPrimary: boolean;
}

const refsList = ref<RefItem[]>([]);
const isLoadingRefs = ref(false);
const refsError = ref('');

/** バックエンドの 2段階予測ロジックに合わせて、いま実際に採用されているのが HIGH か LOW か。 */
const usedPrimary = computed(() => refsList.value.filter(r => r.isPrimary).length >= 3);

/**
 * 実際の予測に寄与した（貢献度 > 0%）参照譜面のみ。
 * HIGH 採用時は |r| ≧ 0.95 のもの、LOW 採用時は全件（|r| ≧ 0.90）。
 * モーダル一覧と件数表示の両方でこの配列を使う。
 */
const visibleRefs = computed(() =>
  usedPrimary.value ? refsList.value.filter(r => r.isPrimary) : refsList.value
);

/** 寄与重みの合計。visibleRefs ベースで計算 → 内訳 % が常に合計 100% になる。 */
const totalVisibleWeight = computed(() =>
  visibleRefs.value.reduce((sum, r) => sum + r.weight, 0)
);

// ── 散布図 ───────────────────────────────────────────────
interface ScatterRow {
  userId: number;
  displayName: string | null;
  privacyLevel: number | null;
  scoreA: number;
  scoreB: number;
  notesA: number | null;
  notesB: number | null;
}
interface ScatterResp { n: number; points: ScatterRow[]; }

const selectedRef = ref<RefItem | null>(null);
const scatterData = ref<ScatterResp | null>(null);
const isLoadingScatter = ref(false);
const scatterError = ref('');
const scatterSectionRef = ref<HTMLElement | null>(null);

async function fetchRefs() {
  isLoadingRefs.value = true;
  refsError.value = '';
  refsList.value = [];
  try {
    const params = new URLSearchParams({
      title: props.targetTitle,
      difficultyName: props.targetDifficultyName,
    });
    const url = props.viewUserId == null
      ? `${API_BASE}/api/analysis/growth-potential-refs?${params}`
      : `${API_BASE}/api/admin/growth-potential-refs?userId=${props.viewUserId}&${params}`;
    const res = await fetch(url, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json() as { refs: RefItem[] };
    refsList.value = data.refs ?? [];
  } catch (e: any) {
    refsError.value = e?.message ?? '通信エラー';
  } finally {
    isLoadingRefs.value = false;
  }
}

// 対象譜面が変わったら（モーダルが開いた瞬間も含む）状態を初期化して再取得
watch(() => `${props.targetTitle}\0${props.targetDifficultyName}\0${props.viewUserId ?? ''}`,
  () => { selectedRef.value = null; scatterData.value = null; fetchRefs(); },
  { immediate: true });

// DJ LEVEL 境界（score rate %）。A 未満は外れ値として除外、AA/AAA/MAX- がグリッド線になる。
const DJ_LEVEL_GRID: { value: number; label: string }[] = [
  { value: 200 / 3,   label: 'A' },
  { value: 700 / 9,   label: 'AA' },
  { value: 800 / 9,   label: 'AAA' },
  { value: 1700 / 18, label: 'MAX-' },
  { value: 100,       label: 'MAX' },
];
const A_THRESHOLD = DJ_LEVEL_GRID[0].value;
const GRID_LABELS = new Map(DJ_LEVEL_GRID.map(g => [g.value, g.label]));

/**
 * バックエンドの logit 空間の slope/intercept を逆変換するためのヘルパー。
 * バックエンドは scoreRate (0〜1) → logit → 線形回帰 → sigmoid という流れで予測しているので、
 * 描画するときも同じ写像を通せば「実際にモデルが使っている曲線」が散布図に重なる。
 */
const LOGIT_RATE_CLAMP = 1e-4;
function scoreRateToLogit(rate: number): number {
  const clamped = Math.max(LOGIT_RATE_CLAMP, Math.min(1 - LOGIT_RATE_CLAMP, rate));
  return Math.log(clamped / (1 - clamped));
}
function logitToScoreRate(logit: number): number {
  return 1 / (1 + Math.exp(-logit));
}

async function pickRef(r: RefItem) {
  selectedRef.value = r;
  await fetchScatter(r);
  // モバイル: 散布図セクションへスクロール（デスクトップは横並びで既に可視なのでノーオペ）
  await nextTick();
  if (window.innerWidth < 768 && scatterSectionRef.value) {
    scatterSectionRef.value.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}

async function fetchScatter(r: RefItem) {
  isLoadingScatter.value = true;
  scatterError.value = '';
  scatterData.value = null;
  try {
    const codeA = getDifficultyCode(r.difficultyName);
    const codeB = getDifficultyCode(props.targetDifficultyName);
    if (codeA == null || codeB == null) throw new Error('Unknown difficulty');
    const params = new URLSearchParams({
      titleA: r.title, difficultyA: r.difficultyName, difficultyCodeA: String(codeA),
      titleB: props.targetTitle, difficultyB: props.targetDifficultyName, difficultyCodeB: String(codeB),
    });
    const res = await fetch(`${API_BASE}/api/analysis/score-pair-scatter?${params}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    scatterData.value = await res.json();
  } catch (e: any) {
    scatterError.value = e?.message ?? '通信エラー';
  } finally {
    isLoadingScatter.value = false;
  }
}

/** 強調表示するユーザー ID。管理者検証時は viewUserId、それ以外はログイン中ユーザー。 */
const highlightUserId = computed(() => props.viewUserId ?? authUser.value?.id ?? null);

function maxScore(notes: number | null): number | null {
  return notes != null && notes > 0 ? notes * 2 : null;
}

interface PlottedPoint { x: number; y: number; _meta: ScatterRow & { maxA: number; maxB: number } }
const visiblePoints = computed<PlottedPoint[]>(() => {
  if (!scatterData.value) return [];
  return scatterData.value.points
    .map<PlottedPoint | null>(p => {
      const maxA = maxScore(p.notesA);
      const maxB = maxScore(p.notesB);
      if (maxA == null || maxB == null) return null;
      return {
        x: (p.scoreA / maxA) * 100,
        y: (p.scoreB / maxB) * 100,
        _meta: { ...p, maxA, maxB },
      };
    })
    .filter((p): p is PlottedPoint => p !== null && p.x >= A_THRESHOLD && p.y >= A_THRESHOLD);
});

const chartData = computed(() => {
  const pts = visiblePoints.value;
  // 自分（or 検証対象）の点だけ別データセットに分けてアクセントカラーで強調
  const youPoints: PlottedPoint[] = [];
  const otherPoints: PlottedPoint[] = [];
  for (const p of pts) {
    if (highlightUserId.value != null && p._meta.userId === highlightUserId.value) {
      youPoints.push(p);
    } else {
      otherPoints.push(p);
    }
  }
  const accentBg = props.mode === 'strength'
    ? (isDarkMode.value ? 'rgba(52,211,153,0.95)' : 'rgba(5,150,105,0.95)')
    : (isDarkMode.value ? 'rgba(34,211,238,0.95)' : 'rgba(8,145,178,0.95)');

  const datasets: any[] = [
    {
      label: t('scatter.player'),
      data: otherPoints,
      backgroundColor: isDarkMode.value ? 'rgba(148,163,184,0.5)' : 'rgba(100,116,139,0.5)',
      borderColor: isDarkMode.value ? 'rgba(148,163,184,0.8)' : 'rgba(100,116,139,0.8)',
      pointRadius: 3,
      pointHoverRadius: 6,
    },
  ];
  if (youPoints.length > 0) {
    datasets.push({
      label: t('scatter.you'),
      data: youPoints,
      backgroundColor: accentBg,
      borderColor: '#ffffff',
      borderWidth: 2,
      pointRadius: 7,
      pointHoverRadius: 10,
      order: -2,
    });
  }
  // 回帰曲線: バックエンドが logit 空間でフィットした slope/intercept をそのまま使い、
  // x を細かくサンプリング → 軸は %（生レート）のまま、生レート空間に戻すと
  // sigmoid 由来のなめらかな S 字カーブとして見える。これがモデルが実際に予測に使っている曲線。
  //
  // y が描画範囲（A_THRESHOLD..100）を外れる先頭・末尾サンプルは事前に切り落とす。
  // Chart.js のクリップ + 破線 + 多点接続の組み合わせで線が描画されない事象があるため。
  const sel = selectedRef.value;
  if (sel != null) {
    const samples: { x: number; y: number }[] = [];
    const STEPS = 80;
    for (let i = 0; i <= STEPS; i++) {
      const xPct = A_THRESHOLD + (100 - A_THRESHOLD) * (i / STEPS);
      const xLogit = scoreRateToLogit(xPct / 100);
      const yLogit = sel.slope * xLogit + sel.intercept;
      const yPct = logitToScoreRate(yLogit) * 100;
      // 描画範囲内の点のみ採用（範囲外を含めると一部の Chart.js バージョンで線がまるごと消える）
      if (yPct >= A_THRESHOLD && yPct <= 100) {
        samples.push({ x: xPct, y: yPct });
      }
    }
    if (samples.length >= 2) {
      datasets.push({
        label: t('scatter.regression'),
        data: samples,
        showLine: true,
        borderColor: isDarkMode.value ? 'rgba(248,113,113,0.95)' : 'rgba(220,38,38,0.95)',
        backgroundColor: 'transparent',
        borderWidth: 2,
        borderDash: [6, 4],
        pointRadius: 0,
        pointHoverRadius: 0,
        pointHitRadius: 0,
        order: -1,
        // tension は使わない。サンプル間隔を細かくしているので直線セグメントの集合でも十分なめらか。
      });
    }
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
          text: selectedRef.value
            ? `${selectedRef.value.title} (${diffShort(selectedRef.value.difficultyName)})`
            : '',
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
          text: `${props.targetTitle} (${diffShort(props.targetDifficultyName)})`,
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
        labels: { color: titleColor, usePointStyle: true, boxWidth: 8, font: { size: 10 } },
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
            const m = ctx.raw._meta as (ScatterRow & { maxA: number; maxB: number }) | undefined;
            if (!m) return '';
            const isPrivate = (m.privacyLevel ?? 1) !== 0;
            const name = isPrivate ? t('scatter.privateUser') : (m.displayName || t('scatter.noName'));
            const rateA = (m.scoreA / m.maxA) * 100;
            const rateB = (m.scoreB / m.maxB) * 100;
            return [
              name,
              `A: ${m.scoreA.toLocaleString()} (${rateA.toFixed(2)}%)`,
              `B: ${m.scoreB.toLocaleString()} (${rateB.toFixed(2)}%)`,
            ];
          },
        },
      },
    },
  };
});

// ── 表示ヘルパー ─────────────────────────────────────────
function diffShort(name: string): string {
  return name === 'LEGGENDARIA' ? 'LEG' : name === 'ANOTHER' ? 'ANO' : name.slice(0, 3).toUpperCase();
}
function diffBadgeClass(name: string): string {
  return name === 'LEGGENDARIA'
    ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300'
    : 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300';
}

/** 各 A の貢献度 (%): visibleRefs に含まれるものだけ意味を持つ（合計 100% になる）。 */
function contributionPct(r: RefItem): number {
  return totalVisibleWeight.value > 0 ? (r.weight / totalVisibleWeight.value) * 100 : 0;
}

/** モードに応じたアクセントカラー（cyan/emerald）の Tailwind クラス断片。 */
const accentClasses = computed(() => props.mode === 'strength'
  ? {
      btnBg: 'bg-emerald-600',
      headerBg: 'bg-emerald-600',
      ring: 'ring-emerald-500',
      activeBg: 'bg-emerald-50 dark:bg-emerald-900/30 border-emerald-500',
      pillBg: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300',
      spinnerBorder: 'border-emerald-100 border-t-emerald-600',
    }
  : {
      btnBg: 'bg-cyan-600',
      headerBg: 'bg-cyan-600',
      ring: 'ring-cyan-500',
      activeBg: 'bg-cyan-50 dark:bg-cyan-900/30 border-cyan-500',
      pillBg: 'bg-cyan-100 text-cyan-700 dark:bg-cyan-900/40 dark:text-cyan-300',
      spinnerBorder: 'border-cyan-100 border-t-cyan-600',
    });
</script>

<template>
  <Teleport to="body">
    <div
      class="fixed inset-0 z-[110] bg-slate-900/60 dark:bg-slate-950/80 backdrop-blur-sm flex items-center justify-center p-0 sm:p-4 animate-fade-in"
      @click.self="emit('close')"
    >
      <div class="bg-white dark:bg-slate-900 w-full max-w-5xl rounded-none sm:rounded-md shadow-xl flex flex-col overflow-hidden h-full sm:h-auto sm:max-h-[92vh] border border-slate-200 dark:border-slate-800">

        <!-- ヘッダー -->
        <div class="relative px-4 sm:px-6 py-3 sm:py-4 shrink-0" :class="accentClasses.headerBg">
          <button
            @click="emit('close')"
            class="absolute top-2 right-2 p-2 group z-50"
            aria-label="Close"
          >
            <div class="text-white/70 group-hover:text-white bg-white/10 group-hover:bg-white/20 rounded-full w-7 h-7 flex items-center justify-center transition-colors">
              <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </div>
          </button>
          <div class="pr-10">
            <p class="text-[10px] sm:text-xs font-bold text-white/80">{{ t('potentialDetails.title') }}</p>
            <h2 class="text-base sm:text-lg font-bold text-white tracking-tight leading-tight truncate">{{ targetTitle }}</h2>
            <div class="flex flex-wrap items-center gap-1.5 mt-1.5">
              <span class="px-1.5 py-0.5 rounded text-[10px] font-bold bg-white/20 text-white">
                {{ diffShort(targetDifficultyName) }} {{ targetDifficultyLevel }}
              </span>
              <span v-if="targetSupportCount != null" class="text-[10px] font-bold text-white/80">
                {{ t('potentialDetails.supportCountLabel', { count: targetSupportCount }) }}
              </span>
            </div>
            <!-- スコア／予測の対比 -->
            <div class="flex items-baseline gap-2 mt-1 text-[11px] sm:text-xs font-bold text-white/90">
              <span>{{ targetCurrentScore.toLocaleString() }}</span>
              <span class="text-white/60">→</span>
              <span>{{ Math.round(targetPredictedScore).toLocaleString() }}</span>
              <span v-if="targetCurrentRate != null && targetPredictedRate != null" class="text-white/70">
                ({{ targetCurrentRate.toFixed(2) }}% → {{ targetPredictedRate.toFixed(2) }}%)
              </span>
            </div>
          </div>
        </div>

        <!-- 本体: モバイル縦積み / デスクトップ横並び -->
        <div class="flex-1 overflow-y-auto bg-slate-50 dark:bg-slate-900">
          <div class="grid grid-cols-1 md:grid-cols-2 md:divide-x md:divide-slate-200 dark:md:divide-slate-700 md:h-full">

            <!-- 左カラム: 参照譜面リスト -->
            <div class="p-3 sm:p-4 md:overflow-y-auto md:max-h-[calc(92vh-120px)]">
              <div class="flex items-baseline justify-between mb-3">
                <p class="text-xs font-bold text-slate-500 dark:text-slate-400">
                  {{ t('potentialDetails.refsListLabel') }}
                </p>
                <span v-if="!isLoadingRefs" class="text-[10px] font-bold text-slate-400">
                  {{ t('potentialDetails.refsCount', { n: visibleRefs.length }) }}
                </span>
              </div>

              <div v-if="isLoadingRefs" class="flex flex-col items-center justify-center py-12 gap-3">
                <div class="w-8 h-8 border-4 rounded-full animate-spin" :class="accentClasses.spinnerBorder"></div>
                <p class="text-xs font-bold text-slate-500">{{ t('potentialDetails.loadingRefs') }}</p>
              </div>

              <div v-else-if="refsError" class="text-center py-12">
                <p class="text-3xl mb-2">⚠️</p>
                <p class="text-xs font-bold text-red-600 dark:text-red-400">{{ refsError }}</p>
              </div>

              <div v-else-if="visibleRefs.length === 0" class="text-center py-12">
                <p class="text-3xl mb-2">🤔</p>
                <p class="text-xs font-bold text-slate-500">{{ t('potentialDetails.noRefs') }}</p>
              </div>

              <ul v-else class="space-y-1.5">
                <li
                  v-for="r in visibleRefs"
                  :key="`${r.title}_${r.difficultyName}`"
                >
                  <button
                    type="button"
                    @click="pickRef(r)"
                    class="w-full text-left p-2.5 rounded-md border-2 transition-colors active:scale-[0.99]"
                    :class="selectedRef && selectedRef.title === r.title && selectedRef.difficultyName === r.difficultyName
                      ? accentClasses.activeBg
                      : 'bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700 hover:border-slate-300 dark:hover:border-slate-600'"
                  >
                    <p class="font-bold text-slate-900 dark:text-white text-sm leading-tight truncate">{{ r.title }}</p>
                    <div class="flex flex-wrap items-center gap-1.5 mt-1">
                      <span class="px-1.5 py-0.5 rounded text-[10px] font-bold" :class="diffBadgeClass(r.difficultyName)">
                        {{ diffShort(r.difficultyName) }} {{ r.difficultyLevel }}
                      </span>
                      <span class="px-1.5 py-0.5 rounded text-[10px] font-bold" :class="accentClasses.pillBg">
                        |r| {{ Math.abs(r.r).toFixed(3) }}
                      </span>
                      <span class="px-1.5 py-0.5 rounded text-[10px] font-bold bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300">
                        n={{ r.n }}
                      </span>
                    </div>
                    <div class="flex items-center justify-between gap-2 mt-1.5">
                      <span class="text-[10px] font-bold text-slate-500 dark:text-slate-400">
                        {{ t('potentialDetails.actualA') }}: {{ r.actualA.toLocaleString() }}
                        <template v-if="r.notesA"> ({{ ((r.actualA / (r.notesA * 2)) * 100).toFixed(2) }}%)</template>
                      </span>
                      <span class="text-[10px] font-bold text-slate-700 dark:text-slate-200">
                        {{ t('potentialDetails.contribution') }} {{ contributionPct(r).toFixed(2) }}%
                      </span>
                    </div>
                    <p class="text-[10px] text-slate-400 dark:text-slate-500 font-bold mt-0.5">
                      {{ t('potentialDetails.singlePred') }}: {{ Math.round(r.predScore).toLocaleString() }}
                      <template v-if="r.notesB"> ({{ ((r.predScore / (r.notesB * 2)) * 100).toFixed(2) }}%)</template>
                    </p>
                  </button>
                </li>
              </ul>
            </div>

            <!-- 右カラム: 散布図 -->
            <div ref="scatterSectionRef" class="p-3 sm:p-4 md:overflow-y-auto md:max-h-[calc(92vh-120px)]">
              <p class="text-xs font-bold text-slate-500 dark:text-slate-400 mb-3">
                {{ t('potentialDetails.scatterLabel') }}
              </p>

              <div v-if="!selectedRef" class="text-center py-16 px-4">
                <p class="text-4xl mb-3">👈</p>
                <p class="text-xs font-bold text-slate-500 dark:text-slate-400">{{ t('potentialDetails.pickRefHint') }}</p>
              </div>

              <template v-else>
                <div class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 p-3 sm:p-4">
                  <!-- メタ情報: 選択中の A と相関係数 -->
                  <div class="mb-3">
                    <p class="font-bold text-slate-900 dark:text-white text-sm leading-tight truncate">{{ selectedRef.title }}</p>
                    <div class="flex flex-wrap items-center gap-1.5 mt-1">
                      <span class="px-1.5 py-0.5 rounded text-[10px] font-bold" :class="diffBadgeClass(selectedRef.difficultyName)">
                        {{ diffShort(selectedRef.difficultyName) }} {{ selectedRef.difficultyLevel }}
                      </span>
                      <span class="px-1.5 py-0.5 rounded text-[10px] font-bold" :class="accentClasses.pillBg">
                        r = {{ selectedRef.r.toFixed(3) }}
                      </span>
                      <span class="px-1.5 py-0.5 rounded text-[10px] font-bold bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300">
                        n = {{ selectedRef.n }}
                      </span>
                    </div>
                  </div>

                  <!-- 散布図本体 / ローディング / エラー -->
                  <div v-if="isLoadingScatter" class="flex flex-col items-center justify-center py-12 gap-3">
                    <div class="w-8 h-8 border-4 rounded-full animate-spin" :class="accentClasses.spinnerBorder"></div>
                    <p class="text-xs font-bold text-slate-500">{{ t('potentialDetails.loadingScatter') }}</p>
                  </div>
                  <div v-else-if="scatterError" class="text-center py-12">
                    <p class="text-3xl mb-2">⚠️</p>
                    <p class="text-xs font-bold text-red-600 dark:text-red-400">{{ scatterError }}</p>
                  </div>
                  <template v-else-if="scatterData">
                    <div class="text-[10px] font-bold text-slate-500 dark:text-slate-400 mb-2">
                      {{ t('potentialDetails.bothPlayedCount', { n: visiblePoints.length }) }}
                    </div>
                    <div class="h-64 sm:h-80">
                      <Scatter :data="chartData" :options="chartOptions" />
                    </div>
                  </template>
                </div>
              </template>
            </div>

          </div>
        </div>

      </div>
    </div>
  </Teleport>
</template>
