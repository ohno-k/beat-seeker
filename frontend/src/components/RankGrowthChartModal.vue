<script setup lang="ts">
/**
 * 【コンポーネントの役割】 非公式難易度（例: ☆12.1）単位の Beat-PT 推移を折れ線グラフで表示する。
 *
 * - X 軸: 日付（アップロード日時）
 * - Y 軸: その難易度内の累計 Beat-PT（実数）。目盛ラベルは「フォルダティア名」になっており、
 *   対応する PT 閾値の位置にプロットされる。
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

const ROMAN = ['', 'I', 'II', 'III', 'IV', 'V'];
const tierLabel = (def: typeof FOLDER_RANK_DEFS[number]) =>
  def.tier ? `${def.name} ${ROMAN[def.tier] ?? def.tier}` : def.name;

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

/** 各フォルダティア境界の PT 閾値（Legend → Novice I の順）。Y 軸ラベル用。 */
const tierThresholds = computed(() => {
  const legendRate = getFolderLegendRate(props.rank);
  if (legendRate <= 0) return [] as { label: string; pt: number; color: string }[];
  const offsetScale = getFolderRankOffsetMax(props.rank);
  const out: { label: string; pt: number; color: string }[] = [];
  for (const def of FOLDER_RANK_DEFS) {
    const thresholdRate = legendRate - def.offset * offsetScale;
    if (thresholdRate <= 66.666) break;
    const pt = calculatePoints(thresholdRate, props.rank) * props.songCount;
    out.push({ label: tierLabel(def), pt, color: def.color });
  }
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
        borderWidth: 2,
        pointRadius: 3,
        pointHoverRadius: 5,
        tension: 0.15,
        fill: false,
      },
    ],
  };
});

const yMinMax = computed(() => {
  const ptsInSeries = series.value.map(p => p.pt);
  const dataMin = ptsInSeries.length > 0 ? Math.min(...ptsInSeries) : 0;
  const dataMax = ptsInSeries.length > 0 ? Math.max(...ptsInSeries, props.currentTotalBeatPoints) : props.currentTotalBeatPoints;
  const pad = Math.max((dataMax - dataMin) * 0.15, 1);
  const min = Math.max(0, dataMin - pad);
  const max = dataMax + pad;
  // ティア閾値のうち軸範囲に含まれるものだけ抽出（極端な高ランク閾値で軸が潰れるのを防ぐ）
  const visibleTicks = tierThresholds.value.filter(t => t.pt >= min && t.pt <= max);
  return { min, max, visibleTicks };
});

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
      callback: (val: any) => {
        const v = Number(val);
        return tickLabels.get(v) ?? v.toFixed(0);
      },
      autoSkip: false,
    },
    grid: { color: grid },
  };
  // ティア閾値が取得できているときだけ Y 軸の目盛位置を強制差し替える。
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
    // 古い順に並べる
    const asc = [...data].sort((a: any, b: any) => new Date(a.date).getTime() - new Date(b.date).getTime());

    const lookup = songToRank.value;
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
      // 同じランク内に変化が無いアップロードでもライン上に点を残すと過密になるため、
      // 差分があった or 初回 or 最後尾は必ず記録、それ以外は前回値と同じならスキップ
      if (diff !== 0 || points.length === 0) {
        points.push({ ts, pt: Math.max(0, cum) });
      }
    }
    // 現在値が確定値なので、最終ポイントを差し替えて誤差を吸収。
    if (points.length > 0) {
      points[points.length - 1] = { ts: points[points.length - 1].ts, pt: props.currentTotalBeatPoints };
    } else {
      // 履歴ゼロでも現在値だけは描画する
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
            <Line :data="chartData" :options="chartOptions" />
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>
