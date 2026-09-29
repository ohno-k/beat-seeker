<script setup lang="ts">
/**
 * 【コンポーネントの役割】 譜面ごとの EXSCORE × (PGREAT+GREAT)/NOTES 散布図を描画する。
 *
 * - X 軸: EXSCORE（0〜理論値。データの範囲に合わせて下限を詰める）
 * - Y 軸: (PGREAT+GREAT)/NOTES（%）
 * - 点: 呼び出し側が可視範囲に絞った実ユーザー（自分 / フレンド / その他）
 * - 回帰線: 呼び出し側が全ユーザー（非公開含む・匿名）で求めた最小二乗直線
 */
import { computed } from 'vue';
import {
  Chart as ChartJS, LinearScale, PointElement, LineElement, Tooltip, Legend,
} from 'chart.js';
import { Scatter } from 'vue-chartjs';
import { useDarkMode } from '../composables/useDarkMode';
import { useI18n } from '../composables/useI18n';

ChartJS.register(LinearScale, PointElement, LineElement, Tooltip, Legend);

export interface JudgeScatterPoint {
  key: string;
  displayName: string;
  /** EXSCORE */
  score: number;
  /** (PGREAT+GREAT)/NOTES × 100 */
  judgeRate: number;
  isSelf: boolean;
  isFriend: boolean;
}

/** 回帰直線 y = intercept + slope × x（x = EXSCORE, y = %）と、線を引く X の範囲。 */
export interface JudgeRegression {
  slope: number;
  intercept: number;
  xMin: number;
  xMax: number;
  n: number;
}

const props = defineProps<{
  points: JudgeScatterPoint[];
  regression: JudgeRegression | null;
  maxScore: number;
}>();

const { t } = useI18n();
const { isDarkMode } = useDarkMode();

/** X 軸下限: 表示中の点と回帰線の最小値から少し余白を取り、100 単位に丸める。 */
const xMin = computed(() => {
  const xs = props.points.map(p => p.score);
  if (props.regression) xs.push(props.regression.xMin);
  if (xs.length === 0) return 0;
  const lo = Math.min(...xs);
  const pad = Math.max(10, props.maxScore * 0.02);
  return Math.max(0, Math.floor((lo - pad) / 100) * 100);
});

/** Y 軸下限: 表示中の点と回帰線の最小値から少し余白を取り、5% 単位に丸める。 */
const yMin = computed(() => {
  const ys = props.points.map(p => p.judgeRate);
  const reg = props.regression;
  if (reg) ys.push(reg.intercept + reg.slope * reg.xMin, reg.intercept + reg.slope * reg.xMax);
  if (ys.length === 0) return 0;
  return Math.max(0, Math.floor((Math.min(...ys) - 2) / 5) * 5);
});

const chartData = computed(() => {
  const dark = isDarkMode.value;
  const toXY = (p: JudgeScatterPoint) => ({ x: p.score, y: p.judgeRate, _meta: p });
  const others = props.points.filter(p => !p.isSelf && !p.isFriend);
  const friends = props.points.filter(p => !p.isSelf && p.isFriend);
  const self = props.points.filter(p => p.isSelf);

  const datasets: any[] = [];
  const reg = props.regression;
  if (reg) {
    datasets.push({
      label: t('table.judgeScatterRegression', { n: reg.n }),
      data: [
        { x: reg.xMin, y: reg.intercept + reg.slope * reg.xMin },
        { x: reg.xMax, y: reg.intercept + reg.slope * reg.xMax },
      ],
      showLine: true,
      borderColor: dark ? 'rgba(148,163,184,0.9)' : 'rgba(71,85,105,0.85)',
      backgroundColor: 'transparent',
      borderWidth: 2,
      borderDash: [6, 4],
      pointRadius: 0,
      pointHoverRadius: 0,
      pointHitRadius: 0,
      order: 10,
    });
  }
  datasets.push({
    label: t('scatter.player'),
    data: others.map(toXY),
    backgroundColor: dark ? 'rgba(96,165,250,0.55)' : 'rgba(59,130,246,0.55)',
    borderColor: dark ? 'rgba(96,165,250,0.9)' : 'rgba(59,130,246,0.9)',
    pointRadius: 4,
    pointHoverRadius: 7,
  });
  if (friends.length > 0) {
    datasets.push({
      label: t('scatter.friend'),
      data: friends.map(toXY),
      backgroundColor: dark ? 'rgba(248,113,113,0.85)' : 'rgba(239,68,68,0.85)',
      borderColor: dark ? '#fff' : '#0f172a',
      borderWidth: 1.5,
      pointRadius: 6,
      pointHoverRadius: 9,
    });
  }
  if (self.length > 0) {
    datasets.push({
      label: t('scatter.you'),
      data: self.map(toXY),
      backgroundColor: 'rgba(16,185,129,1)',
      borderColor: dark ? '#fff' : '#0f172a',
      borderWidth: 2,
      pointRadius: 8,
      pointHoverRadius: 11,
      order: -1,
    });
  }
  return { datasets };
});

const chartOptions = computed(() => {
  const dark = isDarkMode.value;
  const gridColor = dark ? 'rgba(148,163,184,0.18)' : 'rgba(148,163,184,0.32)';
  const tickColor = dark ? '#cbd5e1' : '#475569';
  const titleColor = dark ? '#e2e8f0' : '#1e293b';
  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: false as const,
    // Retina の canvas メモリを抑える（RankingScatterChart と同じ理由）。
    devicePixelRatio: Math.min(typeof window !== 'undefined' ? (window.devicePixelRatio || 1) : 1, 2),
    scales: {
      x: {
        type: 'linear' as const,
        min: xMin.value,
        max: props.maxScore > 0 ? props.maxScore : undefined,
        title: { display: true, text: 'EXSCORE', color: titleColor, font: { weight: 'bold' as const, size: 12 } },
        grid: { color: gridColor },
        ticks: { color: tickColor, font: { size: 10 }, precision: 0 },
      },
      y: {
        type: 'linear' as const,
        min: yMin.value,
        max: 100,
        title: { display: true, text: '(PGREAT+GREAT) / NOTES', color: titleColor, font: { weight: 'bold' as const, size: 12 } },
        grid: { color: gridColor },
        ticks: { color: tickColor, font: { size: 10 }, callback: (v: number | string) => `${v}%` },
      },
    },
    plugins: {
      legend: {
        position: 'top' as const,
        labels: { color: titleColor, usePointStyle: true, boxWidth: 8 },
      },
      tooltip: {
        backgroundColor: dark ? 'rgba(15,23,42,0.95)' : 'rgba(255,255,255,0.97)',
        titleColor: dark ? '#f1f5f9' : '#0f172a',
        bodyColor: dark ? '#f1f5f9' : '#0f172a',
        borderColor: dark ? 'rgba(148,163,184,0.3)' : 'rgba(148,163,184,0.5)',
        borderWidth: 1,
        padding: 10,
        // 回帰線（_meta なし）はツールチップ対象外。
        filter: (item: any) => !!item.raw?._meta,
        callbacks: {
          title: () => '',
          label: (ctx: any) => {
            const p: JudgeScatterPoint = ctx.raw._meta;
            return [
              p.displayName || '(no name)',
              `EXSCORE: ${p.score.toLocaleString()}`,
              `(PG+GR)/NOTES: ${p.judgeRate.toFixed(2)}%`,
            ];
          },
        },
      },
    },
  };
});
</script>

<template>
  <div class="h-64 sm:h-72">
    <Scatter :data="chartData" :options="chartOptions" />
  </div>
</template>
