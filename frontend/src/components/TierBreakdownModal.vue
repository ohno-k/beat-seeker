<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[110] bg-slate-50 dark:bg-slate-900 flex flex-col animate-fade-in transition-colors duration-200">
      <!-- ヘッダー -->
      <div class="px-6 sm:px-8 py-5 border-b border-slate-100 dark:border-slate-700/50 flex justify-between items-center bg-white dark:bg-slate-800 sticky top-0 z-10 transition-colors duration-200">
        <div>
          <h3 class="text-xl sm:text-2xl font-bold text-slate-800 dark:text-slate-100">
            {{ side === 'kenban' ? 'KENBAN-TIER' : 'SARA-TIER' }} 貢献度
          </h3>
          <p class="text-[10px] font-bold mt-0.5" :class="side === 'kenban' ? 'text-cyan-500 dark:text-cyan-400' : 'text-orange-500 dark:text-orange-400'">
            全譜面から{{ side === 'kenban' ? '鍵盤' : '皿' }}側貢献の上位 100 譜面
          </p>
        </div>
        <button @click="$emit('close')" class="p-2 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-full transition-all">
          <svg class="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <!-- 本体 -->
      <div class="flex-1 overflow-y-auto custom-scrollbar bg-slate-50/50 dark:bg-slate-900/50 transition-colors duration-200 p-4 sm:p-8">
        <!-- サマリ・統計 -->
        <div class="grid grid-cols-2 sm:grid-cols-4 gap-3 sm:gap-4 mb-6">
          <div class="bg-white dark:bg-slate-800 rounded-md p-4 border border-slate-200 dark:border-slate-700">
            <p class="text-[10px] font-bold text-slate-400 mb-1">合計 pt</p>
            <p class="text-xl sm:text-2xl font-bold tabular-nums" :class="side === 'kenban' ? 'text-cyan-600 dark:text-cyan-300' : 'text-orange-600 dark:text-orange-300'">
              {{ totalPoints.toFixed(1) }}
            </p>
          </div>
          <div class="bg-white dark:bg-slate-800 rounded-md p-4 border border-slate-200 dark:border-slate-700">
            <p class="text-[10px] font-bold text-slate-400 mb-1">対 BEAT 比</p>
            <p class="text-xl sm:text-2xl font-bold tabular-nums text-slate-700 dark:text-slate-200">
              {{ (totalPoints / (beatTotalPoints || 1) * 100).toFixed(1) }}<span class="text-sm font-bold text-slate-400 ml-0.5">%</span>
            </p>
          </div>
          <div class="bg-white dark:bg-slate-800 rounded-md p-4 border border-slate-200 dark:border-slate-700">
            <p class="text-[10px] font-bold text-slate-400 mb-1">対象譜面</p>
            <p class="text-xl sm:text-2xl font-bold tabular-nums text-slate-700 dark:text-slate-200">
              {{ contributions.length }}<span class="text-sm font-bold text-slate-400 ml-0.5">譜面</span>
            </p>
          </div>
          <div class="bg-white dark:bg-slate-800 rounded-md p-4 border border-slate-200 dark:border-slate-700">
            <p class="text-[10px] font-bold text-slate-400 mb-1">平均皿率</p>
            <p class="text-xl sm:text-2xl font-bold tabular-nums text-slate-700 dark:text-slate-200">
              {{ avgScratchPct.toFixed(1) }}<span class="text-sm font-bold text-slate-400 ml-0.5">%</span>
            </p>
          </div>
        </div>

        <!-- 解説 -->
        <div class="bg-slate-100 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700 rounded-md p-4 mb-6 text-xs text-slate-600 dark:text-slate-300 leading-relaxed">
          <p class="font-bold mb-1 text-slate-700 dark:text-slate-200">計算方法</p>
          <ul class="list-disc list-inside space-y-0.5">
            <li>全プレイ譜面それぞれの BEAT-PT に「{{ side === 'kenban' ? `鍵盤側重み = 1 − 皿率/${threshold}%` : `皿側重み = 皿率/${threshold}%` }}」を掛けた値が <span class="font-bold">貢献 pt</span></li>
            <li>その貢献 pt が高い順に上位 100 譜面を合計し、BEAT-PT 同等スケールに揃える補正倍率 (×{{ multiplier.toFixed(3) }}) を掛けたものが {{ side === 'kenban' ? 'KENBAN' : 'SARA' }}-TIER の合計 pt</li>
            <li>皿率 0%〜{{ threshold }}% の範囲で線形按分。{{ threshold }}% を超える譜面は{{ side === 'kenban' ? '鍵盤側 0' : '皿側 100%' }}扱い</li>
            <li>傾向データ未生成の譜面は重み 0（対象から除外）</li>
          </ul>
        </div>

        <!-- ランクアップ候補（次に伸ばすべき譜面）-->
        <div v-if="suggestions.length > 0" class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 overflow-hidden mb-6">
          <div class="px-4 py-3 border-b border-slate-100 dark:border-slate-700/50 flex items-center justify-between flex-wrap gap-2"
               :class="side === 'kenban' ? 'bg-cyan-50/50 dark:bg-cyan-900/10' : 'bg-orange-50/50 dark:bg-orange-900/10'">
            <div>
              <p class="text-xs sm:text-sm font-bold"
                 :class="side === 'kenban' ? 'text-cyan-700 dark:text-cyan-300' : 'text-orange-700 dark:text-orange-300'">
                次に伸ばすと {{ side === 'kenban' ? 'KENBAN' : 'SARA' }}-TIER が上がる譜面
              </p>
              <p class="text-[10px] text-slate-500 dark:text-slate-400 mt-0.5">
                各譜面を AAA / MAX- / MAX のいずれかまで上げた場合の {{ side === 'kenban' ? 'KENBAN' : 'SARA' }} 合計 pt 増分（上位 100 押し出しも考慮）
              </p>
            </div>
          </div>
          <div class="overflow-x-auto">
            <table class="w-full text-xs sm:text-sm">
              <thead class="bg-slate-50 dark:bg-slate-900/50 border-b border-slate-200 dark:border-slate-700 text-[10px] text-slate-400 font-bold">
                <tr>
                  <th class="px-3 py-2 text-left w-8">#</th>
                  <th class="px-3 py-2 text-left">曲名</th>
                  <th class="px-3 py-2 text-center w-20">難易度</th>
                  <th class="px-3 py-2 text-center w-16">タイプ</th>
                  <th class="px-3 py-2 text-right w-20">現スコア率</th>
                  <th class="px-3 py-2 text-right w-20">目標</th>
                  <th class="px-3 py-2 text-right w-24">+ 期待 pt</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100 dark:divide-slate-700">
                <tr v-for="(s, idx) in suggestions" :key="`sug-${s.title}|${s.difficultyName}`"
                    class="hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors">
                  <td class="px-3 py-2 text-slate-400 tabular-nums">{{ idx + 1 }}</td>
                  <td class="px-3 py-2 font-bold text-slate-700 dark:text-slate-200 truncate max-w-[14rem] sm:max-w-none">{{ s.title }}</td>
                  <td class="px-3 py-2 text-center">
                    <span class="text-[10px] font-bold px-2 py-0.5 rounded" :class="diffBadge(s.difficultyName)">
                      {{ s.difficultyName === 'LEGGENDARIA' ? 'LEG' : 'ANO' }}
                    </span>
                    <span v-if="s.informalRank" class="text-[10px] text-slate-400 ml-1 tabular-nums">☆{{ s.informalRank }}</span>
                  </td>
                  <td class="px-3 py-2 text-center">
                    <span class="text-[10px] font-bold px-2 py-0.5 rounded" :class="typeBadge(s.chartType)">
                      {{ typeLabel(s.chartType) }}
                    </span>
                  </td>
                  <td class="px-3 py-2 text-right tabular-nums text-slate-500 dark:text-slate-400">
                    {{ s.currentScoreRate.toFixed(1) }}%
                  </td>
                  <td class="px-3 py-2 text-right tabular-nums">
                    <span class="text-[10px] font-bold text-slate-500 dark:text-slate-400">{{ s.targetLabel }}</span>
                    <span class="text-[10px] text-slate-400 dark:text-slate-500 ml-1">({{ s.targetScoreRate.toFixed(1) }}%)</span>
                  </td>
                  <td class="px-3 py-2 text-right tabular-nums font-bold"
                      :class="side === 'kenban' ? 'text-cyan-600 dark:text-cyan-300' : 'text-orange-600 dark:text-orange-300'">
                    +{{ s.tierGain.toFixed(1) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ソート切替 -->
        <div class="flex items-center gap-2 mb-3 flex-wrap">
          <span class="text-[11px] font-bold text-slate-400">並び順:</span>
          <button v-for="opt in sortOptions" :key="opt.key"
                  @click="sortKey = opt.key"
                  :class="['px-3 py-1 text-xs font-bold rounded transition-colors',
                           sortKey === opt.key
                             ? (side === 'kenban' ? 'bg-cyan-500 text-white' : 'bg-orange-500 text-white')
                             : 'bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700']">
            {{ opt.label }}
          </button>
        </div>

        <!-- リスト -->
        <div class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 overflow-hidden">
          <div class="overflow-x-auto">
            <table class="w-full text-xs sm:text-sm">
              <thead class="bg-slate-50 dark:bg-slate-900/50 border-b border-slate-200 dark:border-slate-700 text-[10px] text-slate-400 font-bold">
                <tr>
                  <th class="px-3 py-2 text-left w-10">#</th>
                  <th class="px-3 py-2 text-left">曲名</th>
                  <th class="px-3 py-2 text-center w-20">難易度</th>
                  <th class="px-3 py-2 text-center w-16">タイプ</th>
                  <th class="px-3 py-2 text-right w-16">皿率</th>
                  <th class="px-3 py-2 text-right w-16">重み</th>
                  <th class="px-3 py-2 text-right w-20">BEAT pt</th>
                  <th class="px-3 py-2 text-right w-24">貢献 pt</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100 dark:divide-slate-700">
                <tr v-for="(row, idx) in sortedContributions" :key="`${row.title}|${row.difficultyName}`"
                    class="hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors">
                  <td class="px-3 py-2 text-slate-400 tabular-nums">{{ idx + 1 }}</td>
                  <td class="px-3 py-2 font-bold text-slate-700 dark:text-slate-200 truncate max-w-[14rem] sm:max-w-none">{{ row.title }}</td>
                  <td class="px-3 py-2 text-center">
                    <span class="text-[10px] font-bold px-2 py-0.5 rounded" :class="diffBadge(row.difficultyName)">
                      {{ row.difficultyName === 'LEGGENDARIA' ? 'LEG' : 'ANO' }}
                    </span>
                    <span v-if="row.informalRank" class="text-[10px] text-slate-400 ml-1 tabular-nums">☆{{ row.informalRank }}</span>
                  </td>
                  <td class="px-3 py-2 text-center">
                    <span class="text-[10px] font-bold px-2 py-0.5 rounded" :class="typeBadge(getChartType(row.scratchPct))">
                      {{ typeLabel(getChartType(row.scratchPct)) }}
                    </span>
                  </td>
                  <td class="px-3 py-2 text-right tabular-nums" :class="row.scratchPct == null ? 'text-slate-300 dark:text-slate-600' : 'text-slate-600 dark:text-slate-300'">
                    {{ row.scratchPct == null ? '—' : row.scratchPct.toFixed(1) + '%' }}
                  </td>
                  <td class="px-3 py-2 text-right tabular-nums" :class="row.weight === 0 ? 'text-slate-300 dark:text-slate-600' : 'text-slate-600 dark:text-slate-300'">
                    {{ row.weight.toFixed(2) }}
                  </td>
                  <td class="px-3 py-2 text-right tabular-nums text-slate-500 dark:text-slate-400">{{ row.beatTierPoints.toFixed(1) }}</td>
                  <td class="px-3 py-2 text-right tabular-nums font-bold"
                      :class="side === 'kenban' ? 'text-cyan-600 dark:text-cyan-300' : 'text-orange-600 dark:text-orange-300'">
                    {{ row.contribution.toFixed(1) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 KENBAN-TIER / SARA-TIER の内訳（貢献度上位 100 譜面）+ 伸ばすべき譜面候補を全画面モーダル表示する。
 *
 * 提供セクション:
 *  1. 合計・対 BEAT 比・対象譜面数・平均皿率のサマリ
 *  2. 計算方法の解説
 *  3. 「次に伸ばすと {KENBAN/SARA}-TIER が上がる譜面」候補リスト（上位 20 件）
 *  4. 上位 100 譜面のフル貢献表（並び替え可）
 *
 * Props:
 *  - side: 'kenban' | 'sara'
 *  - contributions: SideContribution[]  既ソート済みの上位 100 譜面（親で算出）
 *  - flatScores: ScoreRecord[]          全プレイ譜面（候補リスト計算用）
 *  - scratchMap: Map<string, number>    `(title|diffName) → scratchPct(%)`
 *  - totalPoints / beatTotalPoints      ヘッダー・サマリ表示用
 */
import { computed, ref } from 'vue';
import type { SideContribution } from '../utils/beatTier';
import type { ScoreRecord } from '../utils/scoreData';
import type { ChartType } from '../utils/beatTier';
import {
  SCRATCH_FULL_THRESHOLD_PCT, KENBAN_PT_MULTIPLIER, SARA_PT_MULTIPLIER,
  getKenbanWeight, getSaraWeight, getChartType,
  calculatePoints,
} from '../utils/beatTier';

const props = defineProps<{
  side: 'kenban' | 'sara';
  contributions: SideContribution[];
  flatScores: ScoreRecord[];
  scratchMap: Map<string, number>;
  totalPoints: number;
  beatTotalPoints: number;
}>();
defineEmits(['close']);

const threshold = SCRATCH_FULL_THRESHOLD_PCT;
const multiplier = computed(() => props.side === 'kenban' ? KENBAN_PT_MULTIPLIER : SARA_PT_MULTIPLIER);

const sortKey = ref<'contribution' | 'beat' | 'scratch'>('contribution');

const sortOptions = [
  { key: 'contribution' as const, label: '貢献 pt 降順' },
  { key: 'beat'         as const, label: 'BEAT pt 降順' },
  { key: 'scratch'      as const, label: '皿率 降順' },
];

const sortedContributions = computed(() => {
  const arr = [...props.contributions];
  if (sortKey.value === 'beat') {
    arr.sort((a, b) => b.beatTierPoints - a.beatTierPoints);
  } else if (sortKey.value === 'scratch') {
    arr.sort((a, b) => (b.scratchPct ?? -1) - (a.scratchPct ?? -1));
  }
  return arr;
});

const avgScratchPct = computed(() => {
  const known = props.contributions.filter(r => r.scratchPct != null) as (SideContribution & { scratchPct: number })[];
  if (known.length === 0) return 0;
  return known.reduce((s, r) => s + r.scratchPct, 0) / known.length;
});

// ── 「次に伸ばすべき譜面」候補の計算 ──
/** 候補リストの目標スコア率（高い順に試行、cap で届けば採用）。 */
const TARGET_RATES: { label: string; rate: number }[] = [
  { label: 'MAX',    rate: 100 },
  { label: 'MAX-',   rate: 94.44 },
  { label: 'AAA',    rate: 88.89 },
];

/** 上位 100 圏内かどうかの判定用に SideContribution の key Set を持つ。 */
const top100KeySet = computed(() => new Set(props.contributions.map(c => `${c.title}|${c.difficultyName}`)));

/** 上位 100 の「最下位 (= 100 番目)」の貢献 pt。0 か未満なら 0。 */
const top100Threshold = computed(() => {
  const arr = props.contributions;
  return arr.length >= 1 ? arr[arr.length - 1].contribution : 0;
});

/** 1 譜面分の候補情報。 */
interface Suggestion {
  title: string;
  difficultyName: string;
  informalRank: string | undefined;
  chartType: ChartType;
  currentScoreRate: number;
  targetLabel: string;
  targetScoreRate: number;
  tierGain: number;
}

/** 「次に伸ばすと TIER が上がる」候補（上位 20 件まで）。 */
const suggestions = computed<Suggestion[]>(() => {
  const getWeightFn = props.side === 'kenban' ? getKenbanWeight : getSaraWeight;
  const mult = multiplier.value;
  const inTop100 = top100KeySet.value;
  const threshContrib = top100Threshold.value;

  const candidates: Suggestion[] = [];
  for (const s of props.flatScores) {
    if (s.maxScore <= 0 || !s.informalRank) continue;
    if (s.score >= s.maxScore) continue;
    if (s.difficultyName !== 'ANOTHER' && s.difficultyName !== 'LEGGENDARIA') continue;
    const pct = props.scratchMap.get(`${s.title}|${s.difficultyName}`);
    const weight = getWeightFn(pct);
    if (weight <= 0) continue; // 傾向不明 or 反対側 100% は対象外

    // 届く一番高いボーダー (MAX → MAX- → AAA) を試す。届かないものは飛ばす。
    let targetLabel = '';
    let targetScoreRate = 0;
    let targetBP = 0;
    for (const t of TARGET_RATES) {
      if (s.scoreRate >= t.rate - 0.01) continue;
      const newBP = calculatePoints(t.rate, s.informalRank);
      if (newBP <= s.beatTierPoints) continue;
      targetLabel = t.label;
      targetScoreRate = t.rate;
      targetBP = newBP;
      break;
    }
    if (targetBP <= 0) continue;

    const newContrib = targetBP * weight;
    const oldContrib = s.beatTierPoints * weight;
    let rawGain: number;
    if (inTop100.has(`${s.title}|${s.difficultyName}`)) {
      // 既に top 100 → 増分そのまま反映。
      rawGain = newContrib - oldContrib;
    } else {
      // 圏外 → 新貢献が 100 位を超えれば、超えた分だけが反映される。
      rawGain = newContrib - threshContrib;
    }
    if (rawGain <= 0) continue;
    const tierGain = rawGain * mult;
    if (tierGain < 0.1) continue; // 表示上 0.0 になる候補は捨てる

    candidates.push({
      title: s.title,
      difficultyName: s.difficultyName,
      informalRank: s.informalRank,
      chartType: getChartType(pct),
      currentScoreRate: s.scoreRate,
      targetLabel,
      targetScoreRate,
      tierGain,
    });
  }

  candidates.sort((a, b) => b.tierGain - a.tierGain);
  return candidates.slice(0, 20);
});

// ── バッジ用ヘルパー ──
function diffBadge(diff: string): string {
  return diff === 'LEGGENDARIA'
    ? 'bg-purple-100 dark:bg-purple-900/40 text-purple-700 dark:text-purple-300'
    : 'bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-300';
}

function typeBadge(type: ChartType): string {
  switch (type) {
    case 'kenban':  return 'bg-cyan-100 dark:bg-cyan-900/40 text-cyan-700 dark:text-cyan-300';
    case 'sara':    return 'bg-orange-100 dark:bg-orange-900/40 text-orange-700 dark:text-orange-300';
    case 'balance': return 'bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-300';
    default:        return 'bg-slate-100 dark:bg-slate-800 text-slate-400 dark:text-slate-500';
  }
}
function typeLabel(type: ChartType): string {
  switch (type) {
    case 'kenban':  return '鍵盤';
    case 'sara':    return '皿';
    case 'balance': return 'バランス';
    default:        return '—';
  }
}
</script>
