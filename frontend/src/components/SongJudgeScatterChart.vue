<script setup lang="ts">
/**
 * 【コンポーネントの役割】 譜面ごとの EXSCORE × (PGREAT+GREAT)/NOTES 散布図を描画する。
 *
 * - X 軸: EXSCORE（左端 = 単曲ティア Novice I の必要スコア、右端 = 理論値）。
 *        Novice I に届かない（Beginner 帯の）点は枠外。
 * - Y 軸: (PGREAT+GREAT)/NOTES（%）。下限 = 回帰線の左端の値（回帰線が左下の角から伸びるようにズーム）。上限は 100%。
 * - 背景: 単曲ティア（Novice I〜Legend）の EXSCORE 帯を、ティア別棒グラフと同じ色（I=淡 → V=濃）で塗る。
 * - 点: 呼び出し側が可視範囲に絞った実ユーザー（自分 / フレンド / その他）
 * - 回帰線: 呼び出し側が全ユーザー（非公開含む・匿名）で求めた最小二乗直線
 * - 「自分の周辺」ボタン: 自分の点の前後 2 ティアずつに X 軸を絞り、Y 軸はその範囲の点に合わせる。
 */
import { computed, ref } from 'vue';
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

/** 単曲ティア 1 段ぶんの EXSCORE 帯 [start, end)。Legend は end = 理論値。低い順に並べて渡す。 */
export interface JudgeTierBand {
  start: number;
  end: number;
  name: string;
  tier?: number;
}

const props = defineProps<{
  points: JudgeScatterPoint[];
  regression: JudgeRegression | null;
  maxScore: number;
  /** X 軸の左端（単曲ティア Novice I の必要 EXSCORE）。0 ならデータの範囲に合わせて詰める。 */
  minScore: number;
  /** 背景に塗る単曲ティア帯（低い順）。空なら塗らない。 */
  tierBands: JudgeTierBand[];
}>();

const { t } = useI18n();
const { isDarkMode } = useDarkMode();

// ティア別棒グラフ（ScoreSummary の songTierDist）と同じブロック色。
const BLOCK_COLORS: Record<string, string> = {
  Novice: '#475569',
  Intermediate: '#2563eb',
  Advanced: '#0891b2',
  Expert: '#0d9488',
  Veteran: '#059669',
  Commander: '#a16207',
  Elite: '#ea580c',
  Master: '#dc2626',
  Ancient: '#4f46e5',
  Mythic: '#9333ea',
  Legend: '#f59e0b',
};
const ROMAN = ['', 'I', 'II', 'III', 'IV', 'V'];

function hexToRgba(hex: string, alpha: number): string {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r},${g},${b},${alpha})`;
}

function tierLabel(band: { name: string; tier?: number }): string {
  return band.tier ? `${band.name} ${ROMAN[band.tier] ?? band.tier}` : band.name;
}

/** EXSCORE が属する単曲ティア帯の添字（Novice I 未満は -1）。Legend は理論値ちょうども含む。 */
function bandIndexOf(score: number): number {
  const bands = props.tierBands;
  for (let i = bands.length - 1; i >= 0; i--) {
    if (score >= bands[i].start) return i;
  }
  return -1;
}

/** 「自分の周辺」ズーム中か。 */
const zoomSelf = ref(false);

/** 自分の点（無ければ null）。 */
const selfPoint = computed(() => props.points.find(p => p.isSelf) ?? null);

/**
 * 全体表示の X 軸下限: Novice I の必要スコアが分かればそこを左端にする。
 * 分からない譜面（非公式ランク無し等）は表示中の点と回帰線の最小値から余白を取り、100 単位に丸める。
 */
const fullXMin = computed(() => {
  if (props.minScore > 0) return props.minScore;
  const xs = props.points.map(p => p.score);
  if (props.regression) xs.push(props.regression.xMin);
  if (xs.length === 0) return 0;
  const lo = Math.min(...xs);
  const pad = Math.max(10, props.maxScore * 0.02);
  return Math.max(0, Math.floor((lo - pad) / 100) * 100);
});
const fullXMax = computed(() => (props.maxScore > 0 ? props.maxScore : Math.max(0, ...props.points.map(p => p.score))));

/**
 * 「自分の周辺」ズーム時の X 範囲。自分のティア帯の前後 2 段ずつ（計 5 段）を映す。
 * ティア帯が無い・自分が Novice I 未満のときは理論値の ±3% を映す。
 */
const zoomXRange = computed<[number, number] | null>(() => {
  const me = selfPoint.value;
  if (!me) return null;
  const bands = props.tierBands;
  const idx = bandIndexOf(me.score);
  if (idx >= 0) {
    const lo = bands[Math.max(0, idx - 2)].start;
    const hi = bands[Math.min(bands.length - 1, idx + 2)].end;
    return [lo, Math.max(hi, lo + 1)];
  }
  const half = Math.max(10, props.maxScore * 0.03);
  return [Math.max(0, me.score - half), Math.min(fullXMax.value, me.score + half)];
});

/** ズーム表示中か（自分の点が無ければ常に全体表示）。 */
const isZoomed = computed(() => zoomSelf.value && !!zoomXRange.value);

const xRange = computed<[number, number]>(() =>
  (isZoomed.value ? zoomXRange.value! : [fullXMin.value, fullXMax.value]));

/** 描画する点。全体表示では X 軸下限未満（Beginner 帯）を除く。ズーム時は枠外の点を Chart.js 側で切る。 */
const drawnPoints = computed(() =>
  (isZoomed.value ? props.points : props.points.filter(p => p.score >= fullXMin.value)));

/**
 * 回帰線を描く両端。全体表示では X 軸の左端まで延ばし（Y 軸下限と合わせて左下の角から始まるように）、
 * 右端はデータの最大値（理論値を超えない）まで。ズーム時は X 範囲いっぱいに引く。
 */
const regressionSegment = computed(() => {
  const reg = props.regression;
  if (!reg) return null;
  const [x0, hi] = xRange.value;
  const x1 = isZoomed.value ? hi : Math.min(reg.xMax, hi);
  if (x1 <= x0) return null;
  return [
    { x: x0, y: reg.intercept + reg.slope * x0 },
    { x: x1, y: reg.intercept + reg.slope * x1 },
  ];
});

/**
 * Y 軸の範囲。
 * - 全体表示: 回帰線が右上がりなら下限 = その左端の値（線を左下の角から始める）。
 *   そうでなければ点と回帰線の最小値から余白を取り 5% 単位に丸める。上限は 100%。
 * - ズーム時: X 範囲内の点と回帰線の最小〜最大に余白を付ける（上限 100%）。
 */
const yRange = computed<[number, number]>(() => {
  const seg = regressionSegment.value;
  if (isZoomed.value) {
    const [lo, hi] = xRange.value;
    const ys = props.points.filter(p => p.score >= lo && p.score <= hi).map(p => p.judgeRate);
    if (seg) ys.push(seg[0].y, seg[1].y);
    const yLo = Math.min(...ys);
    const yHi = Math.max(...ys);
    const pad = Math.max(0.5, (yHi - yLo) * 0.1);
    return [Math.max(0, yLo - pad), Math.min(100, yHi + pad)];
  }
  if (seg && seg[1].y > seg[0].y && seg[0].y >= 0 && seg[0].y < 100) return [seg[0].y, 100];
  const ys = drawnPoints.value.map(p => p.judgeRate);
  if (seg) ys.push(seg[0].y, seg[1].y);
  if (ys.length === 0) return [0, 100];
  return [Math.max(0, Math.floor((Math.min(...ys) - 2) / 5) * 5), 100];
});

// プラグイン: 単曲ティア帯（背景）と帯ラベルを描画する。
// 見えている全帯にティア名（例: Master III）が収まるならティア名、収まらなければブロック名だけを帯の上端に出す。
const tierBandPlugin = {
  id: 'judgeTierBands',
  beforeDatasetsDraw(chart: any) {
    const { ctx, chartArea, scales } = chart;
    const opts = chart.options?.plugins?.judgeTierBands;
    const bands: JudgeTierBand[] = opts?.bands ?? [];
    if (!chartArea || !scales?.x || bands.length === 0) return;
    const dark = !!opts?.dark;
    const xScale = scales.x;
    const clamp = (px: number) => Math.min(Math.max(px, chartArea.left), chartArea.right);
    const pixels = bands
      .map(b => ({ band: b, left: clamp(xScale.getPixelForValue(b.start)), right: clamp(xScale.getPixelForValue(b.end)) }))
      .filter(p => p.right > p.left);

    ctx.save();
    // 帯の塗り（I=淡 → V=濃、Legend は最も濃い）
    for (const { band, left, right } of pixels) {
      const level = band.tier ?? 6;
      const alpha = (dark ? 0.08 : 0.05) + level * (dark ? 0.03 : 0.025);
      ctx.fillStyle = hexToRgba(BLOCK_COLORS[band.name] ?? '#94a3b8', alpha);
      ctx.fillRect(left, chartArea.top, right - left, chartArea.bottom - chartArea.top);
    }
    // ブロック境界（各ブロックの I と Legend の左端）に細い線
    ctx.strokeStyle = dark ? 'rgba(148,163,184,0.35)' : 'rgba(100,116,139,0.35)';
    ctx.lineWidth = 1;
    for (const { band, left } of pixels) {
      if ((band.tier ?? 1) !== 1 || left <= chartArea.left) continue;
      ctx.beginPath();
      ctx.moveTo(left + 0.5, chartArea.top);
      ctx.lineTo(left + 0.5, chartArea.bottom);
      ctx.stroke();
    }
    // ラベル
    ctx.font = 'bold 9px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    const labelColor = (name: string) => hexToRgba(BLOCK_COLORS[name] ?? '#64748b', dark ? 0.95 : 0.85);
    const allTierLabelsFit = pixels.every(p => ctx.measureText(tierLabel(p.band)).width + 4 <= p.right - p.left);
    if (allTierLabelsFit) {
      for (const { band, left, right } of pixels) {
        ctx.fillStyle = labelColor(band.name);
        ctx.fillText(tierLabel(band), (left + right) / 2, chartArea.top + 3);
      }
    } else {
      const blocks = new Map<string, { left: number; right: number }>();
      for (const { band, left, right } of pixels) {
        const cur = blocks.get(band.name);
        blocks.set(band.name, cur ? { left: Math.min(cur.left, left), right: Math.max(cur.right, right) } : { left, right });
      }
      for (const [name, { left, right }] of blocks) {
        const label = ctx.measureText(name).width + 4 <= right - left ? name : name.slice(0, 3);
        if (ctx.measureText(label).width + 2 > right - left) continue;
        ctx.fillStyle = labelColor(name);
        ctx.fillText(label, (left + right) / 2, chartArea.top + 3);
      }
    }
    ctx.restore();
  },
};

const chartData = computed(() => {
  const dark = isDarkMode.value;
  const toXY = (p: JudgeScatterPoint) => ({ x: p.score, y: p.judgeRate, _meta: p });
  const drawn = drawnPoints.value;
  const others = drawn.filter(p => !p.isSelf && !p.isFriend);
  const friends = drawn.filter(p => !p.isSelf && p.isFriend);
  const self = drawn.filter(p => p.isSelf);

  const datasets: any[] = [];
  const reg = props.regression;
  const seg = regressionSegment.value;
  if (reg && seg) {
    datasets.push({
      label: t('table.judgeScatterRegression', { n: reg.n }),
      data: seg,
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
  const [xLo, xHi] = xRange.value;
  const [yLo, yHi] = yRange.value;
  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: false as const,
    // Retina の canvas メモリを抑える（RankingScatterChart と同じ理由）。
    devicePixelRatio: Math.min(typeof window !== 'undefined' ? (window.devicePixelRatio || 1) : 1, 2),
    scales: {
      x: {
        type: 'linear' as const,
        min: xLo,
        max: xHi,
        title: { display: true, text: 'EXSCORE', color: titleColor, font: { weight: 'bold' as const, size: 12 } },
        grid: { color: gridColor },
        ticks: { color: tickColor, font: { size: 10 }, precision: 0 },
      },
      y: {
        type: 'linear' as const,
        min: yLo,
        max: yHi,
        title: { display: true, text: '(PGREAT+GREAT) / NOTES', color: titleColor, font: { weight: 'bold' as const, size: 12 } },
        grid: { color: gridColor },
        ticks: { color: tickColor, font: { size: 10 }, callback: (v: number | string) => `${Number(Number(v).toFixed(1))}%` },
      },
    },
    plugins: {
      legend: {
        position: 'top' as const,
        labels: { color: titleColor, usePointStyle: true, boxWidth: 8 },
      },
      // 自前プラグインへ帯とダーク/ライトを伝える。
      judgeTierBands: { bands: props.tierBands, dark },
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
            const idx = bandIndexOf(p.score);
            const lines = [
              p.displayName || '(no name)',
              `EXSCORE: ${p.score.toLocaleString()}`,
              `(PG+GR)/NOTES: ${p.judgeRate.toFixed(2)}%`,
            ];
            if (idx >= 0) lines.push(`${t('table.judgeScatterTier')}: ${tierLabel(props.tierBands[idx])}`);
            return lines;
          },
        },
      },
    },
  };
});
</script>

<template>
  <div>
    <div v-if="selfPoint" class="flex justify-end mb-1">
      <button
        type="button"
        @click="zoomSelf = !zoomSelf"
        class="flex items-center gap-1 px-2.5 py-1 text-[11px] font-bold rounded-md border transition-colors"
        :class="zoomSelf
          ? 'bg-emerald-600 border-emerald-600 text-white hover:bg-emerald-700'
          : 'bg-white dark:bg-slate-700 border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-600'"
      >
        <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path v-if="!zoomSelf" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0zM10 7v6m3-3H7" />
          <path v-else stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0zM13 10H7" />
        </svg>
        {{ zoomSelf ? t('table.judgeScatterZoomReset') : t('table.judgeScatterZoomSelf') }}
      </button>
    </div>
    <div class="h-64 sm:h-72">
      <Scatter :data="chartData" :options="chartOptions" :plugins="[tierBandPlugin]" />
    </div>
  </div>
</template>
