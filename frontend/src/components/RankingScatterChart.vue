<script setup lang="ts">
/**
 * 【コンポーネントの役割】 BEAT-PT × RATE-PT の二次元散布図を描画する。
 *
 * - X 軸: BEAT-PT（線形スケール）
 * - Y 軸: RATE-PT（対数スケール、指数的に伸びるテーブルに合わせて）
 * - グリッド線は両軸とも「大ティア境界」（Beginner/Novice/.../Legend）に配置
 * - ホバーで displayName / BEAT-Tier / RATE-Tier を表示
 * - ログイン中ユーザー本人の点は強調
 */
import { computed } from 'vue';
import {
  Chart as ChartJS, LinearScale, LogarithmicScale,
  PointElement, Tooltip, Legend,
} from 'chart.js';
import { Scatter } from 'vue-chartjs';
import {
  getRankInfo, getRateTierRankInfo,
  RANKS, RATE_TIER_RANKS,
} from '../utils/beatTier';
import { useDarkMode } from '../composables/useDarkMode';
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();

ChartJS.register(LinearScale, LogarithmicScale, PointElement, Tooltip, Legend);

export interface ScatterPoint {
  iidxId: string;
  displayName: string;
  beatPt: number;
  ratePt: number;
  isMe: boolean;
  isTopRanker: boolean;
}

const props = defineProps<{ points: ScatterPoint[] }>();
const { isDarkMode } = useDarkMode();

const ROMAN = ['', 'I', 'II', 'III', 'IV', 'V'];
function tierLabel(rank: { name: string; tier?: number }): string {
  if (!rank.tier) return rank.name;
  return `${rank.name} ${ROMAN[rank.tier] ?? rank.tier}`;
}

// 大ティア境界（各ランク名の最低 minPoints）→ ランク名 のマップ。
// 目盛りラベルとして「数値」より「ランク名」を表示することで意味が伝わるようにする。
function majorTierBoundaries(table: typeof RANKS): { value: number; name: string }[] {
  const minByName = new Map<string, number>();
  for (const r of table) {
    const cur = minByName.get(r.name);
    if (cur === undefined || r.minPoints < cur) minByName.set(r.name, r.minPoints);
  }
  return [...minByName.entries()]
    .map(([name, value]) => ({ value, name }))
    .sort((a, b) => a.value - b.value);
}
const BEAT_BOUNDARIES = majorTierBoundaries(RANKS);
// 対数軸のため 0 は除外（Beginner=0 は軸に乗らない）
const RATE_BOUNDARIES = majorTierBoundaries(RATE_TIER_RANKS).filter(b => b.value > 0);

// X 軸の下限。Beginner（0〜10000）の散らばりを切り捨てて主要レンジに集中する。
const BEAT_X_MIN = 10000;
const BEAT_TICKS = BEAT_BOUNDARIES.filter(b => b.value >= BEAT_X_MIN);
const BEAT_LABEL = new Map(BEAT_TICKS.map(b => [b.value, b.name]));
const RATE_LABEL = new Map(RATE_BOUNDARIES.map(b => [b.value, b.name]));

// ティア名 → 背景バンド色（Tailwind のティアカラーと整合させた極薄塗り）。
const BAND_COLORS_LIGHT: Record<string, string> = {
  Legend: 'rgba(245,158,11,0.10)',
  Mythic: 'rgba(168,85,247,0.08)',
  Ancient: 'rgba(99,102,241,0.08)',
  Master: 'rgba(239,68,68,0.07)',
  Elite: 'rgba(249,115,22,0.07)',
  Commander: 'rgba(234,179,8,0.07)',
  Veteran: 'rgba(16,185,129,0.07)',
  Expert: 'rgba(20,184,166,0.06)',
  Advanced: 'rgba(6,182,212,0.06)',
  Intermediate: 'rgba(59,130,246,0.06)',
  Novice: 'rgba(100,116,139,0.06)',
};
const BAND_COLORS_DARK: Record<string, string> = {
  Legend: 'rgba(245,158,11,0.14)',
  Mythic: 'rgba(168,85,247,0.13)',
  Ancient: 'rgba(99,102,241,0.13)',
  Master: 'rgba(239,68,68,0.12)',
  Elite: 'rgba(249,115,22,0.12)',
  Commander: 'rgba(234,179,8,0.12)',
  Veteran: 'rgba(16,185,129,0.12)',
  Expert: 'rgba(20,184,166,0.11)',
  Advanced: 'rgba(6,182,212,0.11)',
  Intermediate: 'rgba(59,130,246,0.11)',
  Novice: 'rgba(100,116,139,0.10)',
};

/**
 * ティア境界配列から「[start, end, name]」のバンド情報を作る。
 * 末尾（Legend など）は対応軸の最大値まで伸びる。
 */
function makeBands(boundaries: { value: number; name: string }[], axisMax: number) {
  const bands: { start: number; end: number; name: string }[] = [];
  for (let i = 0; i < boundaries.length; i++) {
    const start = boundaries[i].value;
    const end = i + 1 < boundaries.length ? boundaries[i + 1].value : axisMax;
    bands.push({ start, end, name: boundaries[i].name });
  }
  return bands;
}

// プラグイン: BEAT-Tier 縦バンド（背景）を描画。
const tierBandPlugin = {
  id: 'beatTierBands',
  beforeDatasetsDraw(chart: any) {
    const { ctx, chartArea, scales } = chart;
    if (!chartArea || !scales?.x) return;
    const xScale = scales.x;
    const xMax = xScale.max ?? 18000;
    const isDark = chart.options?.plugins?.beatTierBands?.dark ?? false;
    const palette = isDark ? BAND_COLORS_DARK : BAND_COLORS_LIGHT;
    const beatBands = makeBands(BEAT_TICKS, Math.max(xMax, 18000));

    ctx.save();
    for (const band of beatBands) {
      const xStart = xScale.getPixelForValue(band.start);
      const xEnd = xScale.getPixelForValue(band.end);
      const left = Math.max(xStart, chartArea.left);
      const right = Math.min(xEnd, chartArea.right);
      if (right <= left) continue;
      ctx.fillStyle = palette[band.name] ?? 'rgba(148,163,184,0.05)';
      ctx.fillRect(left, chartArea.top, right - left, chartArea.bottom - chartArea.top);
    }
    ctx.restore();
  },
};

const chartData = computed(() => {
  // X 軸下限未満は表示対象外（Beginner レンジを切り捨てて主要分布に集中）
  const visible = props.points.filter(p => p.beatPt >= BEAT_X_MIN);
  const userPts = visible.filter(p => !p.isTopRanker && !p.isMe);
  const myPts   = visible.filter(p => p.isMe);
  const topPts  = visible.filter(p => p.isTopRanker);

  const datasets: any[] = [
    {
      label: t('scatter.player'),
      data: userPts.map(p => ({ x: p.beatPt, y: p.ratePt, _meta: p })),
      backgroundColor: isDarkMode.value ? 'rgba(96,165,250,0.55)' : 'rgba(59,130,246,0.55)',
      borderColor: isDarkMode.value ? 'rgba(96,165,250,0.9)' : 'rgba(59,130,246,0.9)',
      pointRadius: 4,
      pointHoverRadius: 7,
    },
  ];
  if (topPts.length > 0) {
    datasets.push({
      label: t('scatter.topRanker'),
      data: topPts.map(p => ({ x: p.beatPt, y: p.ratePt, _meta: p })),
      backgroundColor: isDarkMode.value ? 'rgba(251,191,36,0.45)' : 'rgba(245,158,11,0.55)',
      borderColor: isDarkMode.value ? 'rgba(251,191,36,0.85)' : 'rgba(245,158,11,0.9)',
      pointStyle: 'triangle',
      pointRadius: 4,
      pointHoverRadius: 7,
    });
  }
  if (myPts.length > 0) {
    datasets.push({
      label: t('scatter.you'),
      data: myPts.map(p => ({ x: p.beatPt, y: p.ratePt, _meta: p })),
      backgroundColor: 'rgba(16,185,129,1)',
      borderColor: isDarkMode.value ? '#fff' : '#0f172a',
      borderWidth: 2,
      pointRadius: 8,
      pointHoverRadius: 11,
      order: -1,
    });
  }
  return { datasets };
});

const chartOptions = computed(() => {
  const gridColor = isDarkMode.value ? 'rgba(148,163,184,0.18)' : 'rgba(148,163,184,0.32)';
  const tickColor = isDarkMode.value ? '#cbd5e1' : '#475569';
  const titleColor = isDarkMode.value ? '#e2e8f0' : '#1e293b';
  const tooltipBg = isDarkMode.value ? 'rgba(15,23,42,0.95)' : 'rgba(255,255,255,0.97)';
  const tooltipText = isDarkMode.value ? '#f1f5f9' : '#0f172a';
  const tooltipBorder = isDarkMode.value ? 'rgba(148,163,184,0.3)' : 'rgba(148,163,184,0.5)';

  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: false as const,
    scales: {
      x: {
        type: 'linear' as const,
        min: BEAT_X_MIN,
        max: 18000,
        title: {
          display: true,
          text: 'BEAT-TIER',
          color: titleColor,
          font: { weight: 'bold' as const, size: 12 },
        },
        grid: { color: gridColor, drawTicks: false },
        border: { color: gridColor },
        ticks: {
          color: tickColor,
          autoSkip: false,
          maxRotation: 45,
          minRotation: 45,
          font: { size: 10 },
          padding: 4,
          // ティア境界の数値はそのまま、表示文字列だけランク名に差し替える。
          callback: (val: number | string) => BEAT_LABEL.get(Number(val)) ?? '',
        },
        afterBuildTicks: (axis: any) => {
          axis.ticks = BEAT_TICKS.map(b => ({ value: b.value }));
        },
      },
      y: {
        type: 'logarithmic' as const,
        title: {
          display: true,
          text: 'RATE-TIER',
          color: titleColor,
          font: { weight: 'bold' as const, size: 12 },
        },
        grid: { color: gridColor, drawTicks: false },
        border: { color: gridColor },
        min: RATE_BOUNDARIES[0].value,
        max: RATE_BOUNDARIES[RATE_BOUNDARIES.length - 1].value,
        ticks: {
          color: tickColor,
          autoSkip: false,
          font: { size: 10 },
          padding: 4,
          callback: (val: number | string) => RATE_LABEL.get(Number(val)) ?? '',
        },
        afterBuildTicks: (axis: any) => {
          axis.ticks = RATE_BOUNDARIES.map(b => ({ value: b.value }));
        },
      },
    },
    plugins: {
      legend: {
        position: 'top' as const,
        labels: { color: titleColor, usePointStyle: true, boxWidth: 8 },
      },
      // 自前プラグインへ「ダーク or ライト」を伝える（背景バンド色の切替用）。
      beatTierBands: { dark: isDarkMode.value },
      tooltip: {
        backgroundColor: tooltipBg,
        titleColor: tooltipText,
        bodyColor: tooltipText,
        borderColor: tooltipBorder,
        borderWidth: 1,
        padding: 10,
        callbacks: {
          title: () => '',
          label: (ctx: any) => {
            const p: ScatterPoint = ctx.raw._meta;
            const beatRank = getRankInfo(p.beatPt);
            const rateRank = getRateTierRankInfo(p.ratePt);
            return [
              p.displayName || '(no name)',
              `BEAT: ${tierLabel(beatRank)}  (${p.beatPt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 })} pt)`,
              `RATE: ${tierLabel(rateRank)}  (${p.ratePt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 })} pt)`,
            ];
          },
        },
      },
    },
  };
});
</script>

<template>
  <div class="w-full h-80 sm:h-96 md:h-[28rem]">
    <Scatter :data="chartData" :options="chartOptions" :plugins="[tierBandPlugin]" />
  </div>
</template>
