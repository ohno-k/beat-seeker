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
import zoomPlugin from 'chartjs-plugin-zoom';
import {
  FOLDER_RANK_DEFS, getFolderLegendRate, getFolderRankOffsetMax, calculatePoints,
} from '../utils/beatTier';
import { diffTable as diffTableRanksRef } from '../composables/useGameData';
import { useAuth, API_BASE } from '../composables/useAuth';
import { useDarkMode } from '../composables/useDarkMode';
import { useI18n } from '../composables/useI18n';

ChartJS.register(LinearScale, CategoryScale, TimeScale, PointElement, LineElement, Filler, Tooltip, Legend, zoomPlugin);

const props = defineProps<{
  rank: string;
  songCount: number;
  currentTotalBeatPoints: number;
}>();
const emit = defineEmits<{ (e: 'close'): void }>();

const { t, currentLang } = useI18n();
const { authHeaders, isLoggedIn } = useAuth();
const { isDarkMode } = useDarkMode();

// ランキング散布図と揃えたティアごとの基本 RGB。サブティア内でアルファだけ変える。
const TIER_RGB: Record<string, string> = {
  Legend: '245,158,11',
  Mythic: '168,85,247',
  Ancient: '99,102,241',
  Master: '239,68,68',
  Elite: '249,115,22',
  Commander: '234,179,8',
  Veteran: '16,185,129',
  Expert: '20,184,166',
  Advanced: '6,182,212',
  Intermediate: '59,130,246',
  Novice: '100,116,139',
};

/**
 * サブティア tier（1〜5）に対する背景塗りの不透明度。上位ティアほど濃く（V > IV > ... > I）。
 * Legend (tier=undefined) は最も濃い固定値。
 */
function bandAlpha(tier: number | undefined, isDark: boolean): number {
  if (!tier) return isDark ? 0.26 : 0.18; // Legend
  // tier 1..5 を 0.06..0.18 (light) / 0.08..0.24 (dark) に線形マップ
  const t = (tier - 1) / 4;
  if (isDark) return 0.08 + t * 0.16;
  return 0.06 + t * 0.12;
}

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

type SubTierBoundary = { name: string; label: string; pt: number; tier: number | undefined };

/**
 * サブティアまで含む全境界（Y 軸ラベル用 & 背景塗り分け用）。Legend / Mythic V / Mythic IV / ... / Novice I。
 * 結果は PT 昇順。
 */
const subTierBoundaries = computed(() => {
  const legendRate = getFolderLegendRate(props.rank);
  if (legendRate <= 0) return [] as SubTierBoundary[];
  const offsetScale = getFolderRankOffsetMax(props.rank);

  const out: SubTierBoundary[] = [];
  for (const def of FOLDER_RANK_DEFS) {
    const thresholdRate = legendRate - def.offset * offsetScale;
    if (thresholdRate <= 66.666) continue;
    out.push({
      name: def.name,
      label: subTierLabel(def),
      pt: calculatePoints(thresholdRate, props.rank) * props.songCount,
      tier: def.tier,
    });
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
 * Y 軸範囲。データ範囲にタイトにズームする。
 * - データ ± 8% パディング
 * - データ上端の直上 / 直下にサブティア境界が「ごく近く」にある場合のみスナップ
 * - 表示ラベルは Y 軸スパンの 5% 以下の間隔で重なる場合は上位優先で間引く
 *
 * これにより、サブティア境界が PT 上で密集している低 difficulty 帯でも
 * ラベルが互いに重なって読めなくなることを避けつつ、データを最大限拡大表示できる。
 */
const yMinMax = computed(() => {
  const ptsInSeries = series.value.map(p => p.pt);
  const dataMax = Math.max(props.currentTotalBeatPoints, ptsInSeries.length > 0 ? Math.max(...ptsInSeries) : 0);
  const dataMin = ptsInSeries.length > 0 ? Math.min(...ptsInSeries) : 0;

  const sorted = [...subTierBoundaries.value].sort((a, b) => a.pt - b.pt);
  const range = Math.max(dataMax - dataMin, 1);
  const pad = range * 0.08;

  let max = dataMax + pad;
  let min = Math.max(0, dataMin - pad);

  // 直近にサブティア境界がパディング 2 倍以内にあるならスナップする（軸端の見栄えを揃える）。
  const above = sorted.find(b => b.pt > dataMax && b.pt <= dataMax + pad * 2);
  if (above) max = above.pt;
  const belowList = sorted.filter(b => b.pt < dataMin && b.pt >= dataMin - pad * 2);
  if (belowList.length > 0) min = belowList[belowList.length - 1].pt;

  if (max - min < 1) max = min + 1;

  // 範囲内のサブティア境界をリストアップし、上位優先で 5% 以下の間隔を間引く。
  const inRangeDesc = subTierBoundaries.value
    .filter(t => t.pt >= min && t.pt <= max)
    .sort((a, b) => b.pt - a.pt);
  const minSpacing = (max - min) * 0.05;
  const spaced: typeof inRangeDesc = [];
  for (const t of inRangeDesc) {
    if (spaced.length === 0 || (spaced[spaced.length - 1].pt - t.pt) >= minSpacing) {
      spaced.push(t);
    }
  }
  const visibleTicks = spaced.sort((a, b) => a.pt - b.pt);
  return { min, max, visibleTicks };
});

/**
 * Chart.js プラグイン: サブティア領域を Y 軸方向に塗り分ける。
 * - 各サブティア境界 (Novice I → ... → Mythic V → Legend) を下端として帯を構築
 * - 同じ大ティア内では上位サブティアほど不透明度を高くしてグラデーションを表現
 * - リアクティブな `subTierBoundaries` / `isDarkMode` は描画時に参照する。
 */
const tierBandPlugin = {
  id: 'rankGrowthTierBands',
  beforeDatasetsDraw(chart: any) {
    const { ctx, chartArea, scales } = chart;
    if (!chartArea || !scales?.y) return;
    const yScale = scales.y;
    const sorted = [...subTierBoundaries.value].sort((a, b) => a.pt - b.pt);
    if (sorted.length === 0) return;
    const isDark = isDarkMode.value;

    ctx.save();
    for (let i = 0; i < sorted.length; i++) {
      const bandStart = sorted[i].pt;
      const bandEnd = i + 1 < sorted.length ? sorted[i + 1].pt : yScale.max;
      const { name, tier } = sorted[i];
      const yTop = yScale.getPixelForValue(bandEnd);
      const yBot = yScale.getPixelForValue(bandStart);
      const top = Math.max(yTop, chartArea.top);
      const bot = Math.min(yBot, chartArea.bottom);
      if (bot <= top) continue;
      const rgb = TIER_RGB[name] ?? '148,163,184';
      const alpha = bandAlpha(tier, isDark);
      ctx.fillStyle = `rgba(${rgb},${alpha})`;
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
      // マウスホイール・ピンチで両軸ズーム、ドラッグでパン。
      zoom: {
        pan: {
          enabled: true,
          mode: 'xy' as const,
          modifierKey: null,
        },
        zoom: {
          wheel: { enabled: true, speed: 0.1 },
          pinch: { enabled: true },
          mode: 'xy' as const,
        },
        limits: {
          // Y 軸は元範囲の倍くらいまで／半分くらいまでズームできる程度に制限
          y: { min: 'original' as any, max: 'original' as any, minRange: 1 },
          x: { minRange: 1 },
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

const chartRef = ref<any>(null);
function resetZoom() {
  const c = chartRef.value?.chart;
  if (c && typeof c.resetZoom === 'function') c.resetZoom();
}

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

    // ランクに属する全曲キーのリスト（現在の難易度表ベース）。
    const rankSongKeys: string[] = [];
    (diffTableRanksRef.value || []).forEach((r: any) => {
      if (r.rank !== props.rank) return;
      r.songs.forEach((songTitle: string) => {
        const isLegg = songTitle.endsWith('[L]');
        const baseTitle = isLegg ? songTitle.slice(0, -3) : songTitle;
        const diffName = isLegg ? 'LEGGENDARIA' : 'ANOTHER';
        rankSongKeys.push(`${baseTitle}_${diffName}`);
      });
    });
    const requiredCount = rankSongKeys.length;

    // 履歴中で「いつ初登場したか / そのときの oldScore」を曲別に記録。
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

    // 履歴前から既プレイの曲（履歴中に一切登場しない曲は currently fully filled の前提でこのカテゴリに分類）。
    const preHistoryPlayed = new Set<string>();
    for (const key of rankSongKeys) {
      if (!firstAppear.has(key)) preHistoryPlayed.add(key);
    }

    // 走査しながら playedSet を成長させ、全曲埋まった瞬間の ts を fullFilledTs として記録する。
    const playedSet = new Set<string>(preHistoryPlayed);
    let fullFilledTs = -Infinity;
    // preHistory のみで既に全曲揃っているなら、最初の history エントリから「埋まり済み」とみなす。
    if (playedSet.size >= requiredCount && asc.length > 0) {
      fullFilledTs = new Date(asc[0].date.endsWith('Z') ? asc[0].date : `${asc[0].date}Z`).getTime();
    }

    let cum = 0;
    type Snapshot = { ts: number; cum: number };
    const snapshots: Snapshot[] = [];
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
              playedSet.add(key);
            }
          }
        }
      } catch (_) { /* ignore */ }
      cum += diff;
      // 全曲が初めて揃った瞬間を fullFilledTs として記録（一度しか上書きしない）。
      if (fullFilledTs === -Infinity && playedSet.size >= requiredCount) {
        fullFilledTs = ts;
      }
      // 全曲埋まり以降のエントリだけスナップショットを残す。
      if (fullFilledTs !== -Infinity && ts >= fullFilledTs && (diff !== 0 || snapshots.length === 0)) {
        snapshots.push({ ts, cum });
      }
    }

    // cum は diff_json に基づく「履歴中の増分の合計」。履歴前から既プレイの曲の寄与は含まれない。
    // 現在の合計 PT と「cum の最終値」の差を base として全スナップショットに加算することで、
    // 履歴前寄与分を反映した累計 PT に整える。
    const finalCum = snapshots.length > 0 ? snapshots[snapshots.length - 1].cum : cum;
    const base = Math.max(0, props.currentTotalBeatPoints - finalCum);
    const points: TimePoint[] = snapshots.map(s => ({ ts: s.ts, pt: Math.max(0, s.cum + base) }));

    if (points.length > 0) {
      // 微小な丸め誤差で現在値とずれないよう、最終点だけは現在値そのものに合わせる。
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
      <div class="relative z-10 bg-white dark:bg-slate-800 rounded-md shadow-xl border border-slate-200 dark:border-slate-700 w-full max-w-3xl max-h-[90vh] flex flex-col">
        <div class="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700 shrink-0">
          <h3 class="font-bold text-sm sm:text-base text-slate-800 dark:text-slate-100">
            ☆{{ rank }} {{ t('table.growthChartTitle') }}
          </h3>
          <div class="flex items-center gap-2">
            <button
              v-if="series.length > 0"
              type="button"
              @click="resetZoom"
              :title="t('table.resetZoom')"
              :aria-label="t('table.resetZoom')"
              class="px-2 h-7 rounded-lg bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-600 dark:text-slate-300 text-xs font-bold flex items-center gap-1 transition-colors"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M4 4v6h6M20 20v-6h-6M20 4l-7 7M4 20l7-7" />
              </svg>
              {{ t('table.resetZoom') }}
            </button>
            <button @click="emit('close')" :aria-label="t('common.back')" class="w-7 h-7 rounded-full bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-500 dark:text-slate-400 font-bold text-sm flex items-center justify-center transition-colors">×</button>
          </div>
        </div>
        <div class="flex-1 overflow-auto p-4">
          <div v-if="isLoading" class="py-12 flex justify-center">
            <div class="w-8 h-8 border-4 border-slate-200 dark:border-slate-700 border-t-indigo-600 dark:border-t-indigo-500 rounded-full animate-spin"></div>
          </div>
          <div v-else-if="errorMsg" class="py-8 text-center text-red-500 dark:text-red-400">{{ errorMsg }}</div>
          <div v-else-if="series.length === 0" class="py-12 text-center text-slate-500 dark:text-slate-400">{{ t('history.empty') }}</div>
          <div v-else class="w-full" style="height: clamp(260px, 60vh, 480px);">
            <Line ref="chartRef" :data="chartData" :options="chartOptions" :plugins="[tierBandPlugin]" />
          </div>
          <p v-if="series.length > 0" class="mt-2 text-[10px] sm:text-xs text-slate-400 dark:text-slate-500 text-center">
            {{ t('table.zoomHint') }}
          </p>
        </div>
      </div>
    </div>
  </Teleport>
</template>
