<script setup lang="ts">
/**
 * 【コンポーネントの役割】 非公式難易度（例: ☆12.1）単位の Beat-PT 推移を折れ線グラフで表示する。
 *
 * - X 軸: 日付（アップロード日時）
 * - Y 軸: その難易度内の累計 Beat-PT（実数）。目盛ラベルは「フォルダティアの大ブロック名」
 *   （Legend / Mythic / Ancient / ... / Novice）で、対応する PT 閾値の位置にプロットされる。
 * - 各ティア領域は薄い色で塗り分け（ランキング散布図と同じパレット）。
 * - データ源: /api/scores/history の各 ScoreHistoryLog.diffJson を時系列で walk-forward して構築。
 *   - 各更新曲の `beatPtIncrease` を、その曲が属する非公式ランクに加算する
 *   - 難易度改訂 (updatedCount=0) は本グラフでは反映しない（diffJson が空のため）
 * - 現在の合計 PT (`currentTotalBeatPoints`) を最終アンカーとして使用し、差し引いた誤差は最終点に集約
 */
import { ref, computed, onMounted, watch } from 'vue';
import {
  Chart as ChartJS, LinearScale, CategoryScale, TimeScale,
  PointElement, LineElement, Filler, Tooltip, Legend,
} from 'chart.js';
import { Line } from 'vue-chartjs';
import {
  FOLDER_RANK_DEFS, getFolderLegendRate, getFolderRankOffsetMax, calculatePoints,
} from '../utils/beatTier';
import { diffTable as diffTableRanksRef } from '../composables/useGameData';
import { useAuth, API_BASE } from '../composables/useAuth';
import { useDarkMode } from '../composables/useDarkMode';
import { useI18n } from '../composables/useI18n';

ChartJS.register(LinearScale, CategoryScale, TimeScale, PointElement, LineElement, Filler, Tooltip, Legend);

const props = defineProps<{
  rank: string;
  songCount: number;
  currentTotalBeatPoints: number;
}>();
const emit = defineEmits<{ (e: 'close'): void }>();

const { t, currentLang } = useI18n();
const { authHeaders, isLoggedIn } = useAuth();
const { isDarkMode } = useDarkMode();

// ランキング散布図と揃えたティア背景バンドカラー。
const BAND_COLORS_LIGHT: Record<string, string> = {
  Legend: 'rgba(245,158,11,0.14)',
  Mythic: 'rgba(168,85,247,0.12)',
  Ancient: 'rgba(99,102,241,0.12)',
  Master: 'rgba(239,68,68,0.10)',
  Elite: 'rgba(249,115,22,0.10)',
  Commander: 'rgba(234,179,8,0.10)',
  Veteran: 'rgba(16,185,129,0.10)',
  Expert: 'rgba(20,184,166,0.09)',
  Advanced: 'rgba(6,182,212,0.09)',
  Intermediate: 'rgba(59,130,246,0.09)',
  Novice: 'rgba(100,116,139,0.09)',
};
const BAND_COLORS_DARK: Record<string, string> = {
  Legend: 'rgba(245,158,11,0.20)',
  Mythic: 'rgba(168,85,247,0.18)',
  Ancient: 'rgba(99,102,241,0.18)',
  Master: 'rgba(239,68,68,0.16)',
  Elite: 'rgba(249,115,22,0.16)',
  Commander: 'rgba(234,179,8,0.16)',
  Veteran: 'rgba(16,185,129,0.16)',
  Expert: 'rgba(20,184,166,0.14)',
  Advanced: 'rgba(6,182,212,0.14)',
  Intermediate: 'rgba(59,130,246,0.14)',
  Novice: 'rgba(100,116,139,0.14)',
};

const isLoading = ref(false);
const errorMsg = ref('');
type TimePoint = { ts: number; pt: number };
const series = ref<TimePoint[]>([]);

/** 楽曲タイトル+difficulty → 所属する非公式ランク のルックアップ Map。 */
const songToRank = computed(() => {
  const m = new Map<string, string>();
  (diffTableRanksRef.value || []).forEach((r: any) => {
    r.songs.forEach((songTitle: string) => {
      const isLegg = songTitle.endsWith('[L]');
      const baseTitle = isLegg ? songTitle.slice(0, -3) : songTitle;
      const diffName = isLegg ? 'LEGGENDARIA' : 'ANOTHER';
      m.set(`${baseTitle}_${diffName}`, r.rank);
    });
  });
  return m;
});

const ROMAN = ['', 'I', 'II', 'III', 'IV', 'V'];
const subTierLabel = (def: { name: string; tier?: number }) =>
  def.tier ? `${def.name} ${ROMAN[def.tier] ?? def.tier}` : def.name;

/**
 * サブティアまで含む全境界（Y 軸ラベル用）。Legend / Mythic V / Mythic IV / ... / Novice I。
 * 結果は PT 昇順。
 */
const subTierBoundaries = computed(() => {
  const legendRate = getFolderLegendRate(props.rank);
  if (legendRate <= 0) return [] as { name: string; label: string; pt: number }[];
  const offsetScale = getFolderRankOffsetMax(props.rank);

  const out: { name: string; label: string; pt: number }[] = [];
  for (const def of FOLDER_RANK_DEFS) {
    const thresholdRate = legendRate - def.offset * offsetScale;
    if (thresholdRate <= 66.666) continue;
    out.push({
      name: def.name,
      label: subTierLabel(def),
      pt: calculatePoints(thresholdRate, props.rank) * props.songCount,
    });
  }
  out.sort((a, b) => a.pt - b.pt);
  return out;
});

/**
 * 大ブロック単位の境界（背景色の塗り分け用）。各ブロック (tier=1) の入口 PT。Legend は単独。
 * 結果は PT 昇順。
 */
const tierBoundaries = computed(() => {
  const legendRate = getFolderLegendRate(props.rank);
  if (legendRate <= 0) return [] as { name: string; pt: number }[];
  const offsetScale = getFolderRankOffsetMax(props.rank);

  const out: { name: string; pt: number }[] = [];
  out.push({ name: 'Legend', pt: calculatePoints(legendRate, props.rank) * props.songCount });
  for (const def of FOLDER_RANK_DEFS) {
    if (def.tier !== 1) continue;
    const thresholdRate = legendRate - def.offset * offsetScale;
    if (thresholdRate <= 66.666) continue;
    out.push({ name: def.name, pt: calculatePoints(thresholdRate, props.rank) * props.songCount });
  }
  out.sort((a, b) => a.pt - b.pt);
  return out;
});

const formatDateLabel = (ts: number) => {
  const d = new Date(ts);
  const locale = currentLang.value === 'ko' ? 'ko-KR' : (currentLang.value === 'en' ? 'en-US' : 'ja-JP');
  return d.toLocaleDateString(locale, { timeZone: 'Asia/Tokyo', year: '2-digit', month: '2-digit', day: '2-digit' });
};

const chartData = computed(() => {
  const labels = series.value.map(p => formatDateLabel(p.ts));
  const data = series.value.map(p => p.pt);
  const lineColor = isDarkMode.value ? '#a78bfa' : '#7c3aed';
  return {
    labels,
    datasets: [
      {
        label: `☆${props.rank} ${t('table.colTotalPt')}`,
        data,
        borderColor: lineColor,
        backgroundColor: lineColor + '33',
        borderWidth: 2.5,
        pointRadius: 3,
        pointHoverRadius: 5,
        tension: 0.15,
        fill: false,
      },
    ],
  };
});

/**
 * Y 軸範囲。データ周辺のサブティア境界に合わせてズームイン。
 * - max: dataMax より上にある最初のサブティア PT（無ければ dataMax * 1.05）
 * - min: dataMin より下にある最後のサブティア PT（無ければ 0）
 *
 * サブティア表示が密集しないよう、軸ラベルは subTierBoundaries 全件を渡して
 * Chart.js 側の autoSkip 抑制と afterBuildTicks で位置を強制する。
 */
const yMinMax = computed(() => {
  const ptsInSeries = series.value.map(p => p.pt);
  const dataMax = Math.max(props.currentTotalBeatPoints, ptsInSeries.length > 0 ? Math.max(...ptsInSeries) : 0);
  const dataMin = ptsInSeries.length > 0 ? Math.min(...ptsInSeries) : 0;

  const sorted = [...subTierBoundaries.value].sort((a, b) => a.pt - b.pt);

  let max = dataMax * 1.05 + 1;
  let min = Math.max(0, dataMin - Math.max(1, (dataMax - dataMin) * 0.05));

  const above = sorted.find(b => b.pt > dataMax);
  if (above) max = above.pt;

  const belowList = sorted.filter(b => b.pt < dataMin);
  if (belowList.length > 0) min = belowList[belowList.length - 1].pt;
  else min = 0;

  if (max - min < 1) max = min + 1;

  // 軸ラベル: 範囲内の全サブティア境界を出す（ズーム済みなのでラベル密度は許容範囲）。
  const visibleTicks = subTierBoundaries.value.filter(t => t.pt >= min && t.pt <= max);
  return { min, max, visibleTicks };
});

/**
 * Chart.js プラグイン: ティア領域を Y 軸方向に薄く塗り分ける。
 * 各ブロック (Novice → ... → Mythic → Legend) の下端境界をもとに、
 * 上端を「1 つ上の境界 PT」（Legend は yScale.max）として塗る。
 * リアクティブな `tierBoundaries` / `isDarkMode` は描画時に参照する。
 */
const tierBandPlugin = {
  id: 'rankGrowthTierBands',
  beforeDatasetsDraw(chart: any) {
    const { ctx, chartArea, scales } = chart;
    if (!chartArea || !scales?.y) return;
    const yScale = scales.y;
    const palette = isDarkMode.value ? BAND_COLORS_DARK : BAND_COLORS_LIGHT;
    const sorted = [...tierBoundaries.value].sort((a, b) => a.pt - b.pt);
    if (sorted.length === 0) return;

    ctx.save();
    for (let i = 0; i < sorted.length; i++) {
      const bandStart = sorted[i].pt;
      const bandEnd = i + 1 < sorted.length ? sorted[i + 1].pt : yScale.max;
      const name = sorted[i].name;
      const yTop = yScale.getPixelForValue(bandEnd);
      const yBot = yScale.getPixelForValue(bandStart);
      const top = Math.max(yTop, chartArea.top);
      const bot = Math.min(yBot, chartArea.bottom);
      if (bot <= top) continue;
      ctx.fillStyle = palette[name] ?? 'rgba(148,163,184,0.06)';
      ctx.fillRect(chartArea.left, top, chartArea.right - chartArea.left, bot - top);
    }
    ctx.restore();
  },
};

const chartOptions = computed(() => {
  const grid = isDarkMode.value ? 'rgba(148,163,184,0.18)' : 'rgba(148,163,184,0.25)';
  const tickColor = isDarkMode.value ? '#cbd5e1' : '#475569';
  const ticks = yMinMax.value.visibleTicks;
  const tickPositions = ticks.map(t => t.pt);
  const tickLabels = new Map(ticks.map(t => [t.pt, t.label]));

  const yScale: any = {
    min: yMinMax.value.min,
    max: yMinMax.value.max,
    ticks: {
      color: tickColor,
      font: { size: 11, weight: 'bold' as const },
      callback: (val: any) => {
        const v = Number(val);
        return tickLabels.get(v) ?? '';
      },
      autoSkip: false,
      padding: 6,
    },
    grid: { color: grid, drawTicks: false },
    border: { display: false },
  };
  if (tickPositions.length > 0) {
    yScale.afterBuildTicks = (axis: any) => {
      axis.ticks = tickPositions.map(v => ({ value: v }));
    };
  }

  return {
    responsive: true,
    maintainAspectRatio: false,
    interaction: { mode: 'nearest' as const, axis: 'x' as const, intersect: false },
    plugins: {
      legend: { display: false },
      tooltip: {
        callbacks: {
          label: (ctx: any) => `${ctx.parsed.y.toFixed(1)} pt`,
        },
      },
    },
    scales: {
      x: {
        ticks: { color: tickColor, maxRotation: 0, autoSkip: true, maxTicksLimit: 8 },
        grid: { color: grid, drawTicks: false },
      },
      y: yScale,
    },
  } as any;
});

async function loadHistory() {
  if (!isLoggedIn.value) {
    errorMsg.value = t('history.empty');
    return;
  }
  isLoading.value = true;
  errorMsg.value = '';
  try {
    const res = await fetch(`${API_BASE}/api/scores/history`, { headers: authHeaders() });
    if (!res.ok) throw new Error(t('history.error'));
    const data = await res.json();
    const asc = [...data].sort((a: any, b: any) => new Date(a.date).getTime() - new Date(b.date).getTime());

    const lookup = songToRank.value;

    // Pre-pass: 各曲の初回登場 ts と oldScore を記録する。
    // - oldScore === 0 で初登場 → そのタイミングで「初プレイ」
    // - oldScore > 0 で初登場 → 履歴より前から既プレイ（時刻不明 → -∞ 扱い）
    // - 履歴に一切登場しない曲 → 全プレイ済み行に来ている前提なので履歴より前から既プレイ扱い
    const firstAppear = new Map<string, { ts: number; oldScore: number }>();
    for (const entry of asc) {
      if (!entry.diffJson || entry.diffJson === '[]') continue;
      const ts = new Date(entry.date.endsWith('Z') ? entry.date : `${entry.date}Z`).getTime();
      try {
        const songs = JSON.parse(entry.diffJson) as any[];
        for (const s of songs) {
          const key = `${s.title}_${s.difficulty || s.difficultyName}`;
          if (lookup.get(key) !== props.rank) continue;
          if (!firstAppear.has(key)) {
            firstAppear.set(key, { ts, oldScore: Number(s.oldScore ?? 0) });
          }
        }
      } catch (_) { /* ignore */ }
    }

    // 全曲埋まった ts = 履歴中で初プレイされた曲の最終タイムスタンプ。
    // 履歴内で初プレイが 1 件も無ければ -∞（= 履歴開始時点で既に全埋まり）。
    let fullFilledTs = -Infinity;
    for (const { ts, oldScore } of firstAppear.values()) {
      if (oldScore === 0) {
        if (ts > fullFilledTs) fullFilledTs = ts;
      }
    }

    let cum = 0;
    const points: TimePoint[] = [];
    for (const entry of asc) {
      const ts = new Date(entry.date.endsWith('Z') ? entry.date : `${entry.date}Z`).getTime();
      let diff = 0;
      try {
        if (entry.diffJson && entry.diffJson !== '[]') {
          const songs = JSON.parse(entry.diffJson) as any[];
          for (const s of songs) {
            const key = `${s.title}_${s.difficulty || s.difficultyName}`;
            if (lookup.get(key) === props.rank) {
              const inc = Number(s.beatPtIncrease ?? (Number(s.newBeatPt ?? 0) - Number(s.oldBeatPt ?? 0)));
              if (Number.isFinite(inc)) diff += inc;
            }
          }
        }
      } catch (_) { /* ignore parse failure */ }
      cum += diff;
      // 全曲埋まった ts 以降だけプロットする。
      if (ts >= fullFilledTs && (diff !== 0 || points.length === 0)) {
        points.push({ ts, pt: Math.max(0, cum) });
      }
    }
    if (points.length > 0) {
      points[points.length - 1] = { ts: points[points.length - 1].ts, pt: props.currentTotalBeatPoints };
    } else {
      points.push({ ts: Date.now(), pt: props.currentTotalBeatPoints });
    }
    series.value = points;
  } catch (err: any) {
    errorMsg.value = err?.message || 'failed';
  } finally {
    isLoading.value = false;
  }
}

onMounted(loadHistory);
watch(() => props.rank, loadHistory);
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50 flex items-center justify-center p-2 sm:p-4">
      <div class="fixed inset-0 bg-black/50 backdrop-blur-sm" @click="emit('close')"></div>
      <div class="relative z-10 bg-white dark:bg-slate-800 rounded-xl sm:rounded-2xl shadow-2xl border border-slate-200 dark:border-slate-700 w-full max-w-3xl max-h-[90vh] flex flex-col">
        <div class="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700 shrink-0">
          <h3 class="font-bold text-sm sm:text-base text-slate-800 dark:text-slate-100">
            ☆{{ rank }} {{ t('table.growthChartTitle') }}
          </h3>
          <button @click="emit('close')" :aria-label="t('common.back')" class="w-7 h-7 rounded-full bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-500 dark:text-slate-400 font-bold text-sm flex items-center justify-center transition-colors">×</button>
        </div>
        <div class="flex-1 overflow-auto p-4">
          <div v-if="isLoading" class="py-12 flex justify-center">
            <div class="w-8 h-8 border-4 border-slate-200 dark:border-slate-700 border-t-indigo-600 dark:border-t-indigo-500 rounded-full animate-spin"></div>
          </div>
          <div v-else-if="errorMsg" class="py-8 text-center text-red-500 dark:text-red-400">{{ errorMsg }}</div>
          <div v-else-if="series.length === 0" class="py-12 text-center text-slate-500 dark:text-slate-400">{{ t('history.empty') }}</div>
          <div v-else class="w-full" style="height: clamp(260px, 60vh, 480px);">
            <Line :data="chartData" :options="chartOptions" :plugins="[tierBandPlugin]" />
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>
