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

// 大ティア境界（各ランク名の最低 minPoints のみ）を抽出してグリッド／目盛に使う。
// サブティア（5 段階）まで全部出すと密集してしまうため、ランク単位で間引く。
function majorTickValues(table: typeof RANKS): number[] {
  const minByName = new Map<string, number>();
  for (const r of table) {
    const cur = minByName.get(r.name);
    if (cur === undefined || r.minPoints < cur) minByName.set(r.name, r.minPoints);
  }
  return [...minByName.values()].sort((a, b) => a - b);
}
const BEAT_TICKS = majorTickValues(RANKS);
// 対数軸のため 0 は除外（Beginner=0 は軸に乗らない）
const RATE_TICKS = majorTickValues(RATE_TIER_RANKS).filter(v => v > 0);

const chartData = computed(() => {
  const userPts = props.points.filter(p => !p.isTopRanker && !p.isMe);
  const myPts   = props.points.filter(p => p.isMe);
  const topPts  = props.points.filter(p => p.isTopRanker);

  const datasets: any[] = [
    {
      label: 'プレイヤー',
      data: userPts.map(p => ({ x: p.beatPt, y: p.ratePt, _meta: p })),
      backgroundColor: isDarkMode.value ? 'rgba(96,165,250,0.55)' : 'rgba(59,130,246,0.55)',
      borderColor: isDarkMode.value ? 'rgba(96,165,250,0.9)' : 'rgba(59,130,246,0.9)',
      pointRadius: 4,
      pointHoverRadius: 7,
    },
  ];
  if (topPts.length > 0) {
    datasets.push({
      label: 'TOPランカー',
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
      label: 'あなた',
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
        title: {
          display: true,
          text: 'BEAT-PT',
          color: titleColor,
          font: { weight: 'bold' as const, size: 12 },
        },
        grid: { color: gridColor },
        border: { color: gridColor },
        ticks: {
          color: tickColor,
          autoSkip: false,
          maxRotation: 0,
          callback: (val: number | string) => Number(val).toLocaleString(),
        },
        afterBuildTicks: (axis: any) => {
          axis.ticks = BEAT_TICKS.map(v => ({ value: v }));
        },
      },
      y: {
        type: 'logarithmic' as const,
        title: {
          display: true,
          text: 'RATE-PT',
          color: titleColor,
          font: { weight: 'bold' as const, size: 12 },
        },
        grid: { color: gridColor },
        border: { color: gridColor },
        min: RATE_TICKS[0],
        max: RATE_TICKS[RATE_TICKS.length - 1],
        ticks: {
          color: tickColor,
          autoSkip: false,
          callback: (val: number | string) => Number(val).toLocaleString(),
        },
        afterBuildTicks: (axis: any) => {
          axis.ticks = RATE_TICKS.map(v => ({ value: v }));
        },
      },
    },
    plugins: {
      legend: {
        position: 'top' as const,
        labels: { color: titleColor, usePointStyle: true, boxWidth: 8 },
      },
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
  <div class="w-full h-72 sm:h-80 md:h-96">
    <Scatter :data="chartData" :options="chartOptions" />
  </div>
</template>
