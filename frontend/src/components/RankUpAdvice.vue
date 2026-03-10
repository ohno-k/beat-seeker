<template>
  <!-- Rank up suggestion panel -->
  <div
    v-if="nextRankGap > 0"
    class="bg-white dark:bg-slate-800 p-4 sm:p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors duration-200"
  >
    <div class="flex items-center justify-between mb-1">
      <h3 class="text-sm sm:text-base font-bold text-slate-800 dark:text-slate-100 flex items-center gap-2">
        <svg class="w-4 h-4 text-blue-500 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
        </svg>
        ランクアップへの道
      </h3>
      <button
        @click="regenerate"
        class="flex items-center gap-1 px-3 py-1.5 bg-blue-50 dark:bg-blue-900/30 hover:bg-blue-100 dark:hover:bg-blue-900/50 text-blue-600 dark:text-blue-400 text-xs font-bold rounded-lg border border-blue-200 dark:border-blue-800 transition-colors"
      >
        <svg class="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
        </svg>
        変更
      </button>
    </div>
    <p class="text-xs text-slate-400 dark:text-slate-500 mb-4">
      次のランクまで あと <span class="font-black text-blue-600 dark:text-blue-400">{{ nextRankGap.toFixed(1) }} pt</span> 必要
    </p>

    <div v-if="suggestions.length === 0" class="text-center py-6 text-slate-400 dark:text-slate-500 text-sm">
      スコア+50点以内で伸ばせる曲がありません
    </div>
    <div v-else class="space-y-2">
      <div
        v-for="(sug, i) in suggestions"
        :key="i"
        class="flex items-center gap-2 p-2 sm:p-3 rounded-xl bg-blue-50/50 dark:bg-blue-900/10 border border-blue-100 dark:border-blue-900/50"
      >
        <span class="text-[10px] font-black text-blue-400 dark:text-blue-500 shrink-0 w-4 text-right">{{ i + 1 }}</span>
        <div class="flex-1 min-w-0">
          <p class="font-bold text-slate-800 dark:text-slate-200 text-xs sm:text-sm truncate">{{ sug.song.title }}</p>
          <p class="text-[10px] text-slate-500 dark:text-slate-400">
            {{ sug.song.difficultyName }} / 現在 {{ sug.song.beatTierPoints.toFixed(1) }} pt
          </p>
        </div>
        <div class="text-right shrink-0">
          <p class="text-[10px] font-bold text-slate-500 dark:text-slate-400">{{ sug.targetLabel }}</p>
          <p class="text-xs font-black text-blue-600 dark:text-blue-400">
            スコア +{{ sug.scoreIncrease.toLocaleString() }}点
          </p>
          <p class="text-[10px] font-bold text-emerald-600 dark:text-emerald-400">+{{ sug.ptGain.toFixed(1) }} pt</p>
        </div>
      </div>
    </div>

    <!-- Total summary -->
    <div
      v-if="suggestions.length > 0"
      class="mt-4 p-3 rounded-xl border-2 flex items-center justify-between"
      :class="totalSuggestionGain >= nextRankGap
        ? 'bg-emerald-50 dark:bg-emerald-900/20 border-emerald-300 dark:border-emerald-700'
        : 'bg-amber-50 dark:bg-amber-900/20 border-amber-300 dark:border-amber-700'"
    >
      <div>
        <p class="text-[10px] font-bold uppercase tracking-wider"
          :class="totalSuggestionGain >= nextRankGap ? 'text-emerald-600 dark:text-emerald-400' : 'text-amber-600 dark:text-amber-400'">
          {{ totalSuggestionGain >= nextRankGap ? '達成可能！' : '不足' }}
        </p>
        <p class="text-lg font-black"
          :class="totalSuggestionGain >= nextRankGap ? 'text-emerald-700 dark:text-emerald-300' : 'text-amber-700 dark:text-amber-300'">
          合計 +{{ totalSuggestionGain.toFixed(1) }} pt
        </p>
      </div>
      <div class="text-right text-xs font-bold text-slate-500 dark:text-slate-400">
        <p>目標</p>
        <p class="text-sm font-black text-slate-700 dark:text-slate-200">+{{ nextRankGap.toFixed(1) }} pt</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, onMounted } from 'vue';
import type { ScoreRecord } from '../utils/scoreData';
import { calculatePoints, getNextRankInfo } from '../utils/beatTier';

const props = defineProps<{
  flatScores: ScoreRecord[];
  totalPoints: number;
}>();

// ── Top-100 state ────────────────────────────────────────────────────────

const sortedScored = computed(() =>
  props.flatScores
    .filter(s => s.beatTierPoints > 0)
    .sort((a, b) => b.beatTierPoints - a.beatTierPoints)
);

const threshold = computed(() => {
  const s = sortedScored.value;
  return s.length >= 100 ? s[99].beatTierPoints : 0;
});

const top100Set = computed(() =>
  new Set(sortedScored.value.slice(0, 100).map(s => `${s.title}|${s.difficultyName}`))
);

// ── Rank-up gap ──────────────────────────────────────────────────────────

const nextRankGap = computed(() => {
  const { nextRank } = getNextRankInfo(props.totalPoints);
  if (!nextRank) return 0;
  return Math.max(0, nextRank.minPoints - props.totalPoints);
});

// ── Improvement thresholds (AA / AAA / MAX- give bonus points) ───────────
// Each threshold slightly above the bonus trigger point so the bonus is included.
const IMPROVEMENT_THRESHOLDS = [
  { rate: 66.67, label: 'Beat-PT獲得ライン突破' },
  { rate: 77.78, label: 'AA達成' },
  { rate: 88.89, label: 'AAA達成' },
  { rate: 94.45, label: 'MAX-達成' },
];

interface Suggestion {
  song: ScoreRecord;
  targetLabel: string;
  scoreIncrease: number; // raw score points needed (≤ 50)
  ptGain: number;        // net Beat-PT gain
}

function buildCandidates(): Suggestion[] {
  const candidates: Suggestion[] = [];
  const th = threshold.value;

  for (const song of props.flatScores) {
    if (song.maxScore <= 0 || !song.informalRank || song.scoreRate < 0) continue;

    for (const thr of IMPROVEMENT_THRESHOLDS) {
      if (song.scoreRate >= thr.rate - 0.01) continue; // already past this threshold

      const targetScore = Math.ceil(song.maxScore * thr.rate / 100);
      const scoreIncrease = targetScore - song.score;
      if (scoreIncrease <= 0 || scoreIncrease > 50) continue; // ≤50点 制限

      const newBeatPT = calculatePoints(thr.rate, song.informalRank);
      const rawGain = newBeatPT - song.beatTierPoints;
      if (rawGain <= 0) continue;

      const inTop100 = top100Set.value.has(`${song.title}|${song.difficultyName}`);
      let netGain: number;
      if (inTop100) {
        netGain = rawGain;
      } else if (th > 0) {
        netGain = newBeatPT - th;
      } else {
        netGain = rawGain;
      }

      if (netGain <= 0) continue;

      candidates.push({ song, targetLabel: thr.label, scoreIncrease, ptGain: netGain });
      break; // use the first (lowest) reachable threshold per song
    }
  }

  return candidates;
}

function pickRandomSuggestions(): Suggestion[] {
  const gap = nextRankGap.value;
  if (gap <= 0) return [];

  const candidates = buildCandidates();
  if (candidates.length === 0) return [];

  // Shuffle
  const shuffled = [...candidates].sort(() => Math.random() - 0.5);

  // Pick greedily until gap is covered or 20 songs reached
  const result: Suggestion[] = [];
  let accumulated = 0;
  for (const c of shuffled) {
    if (result.length >= 20) break;
    result.push(c);
    accumulated += c.ptGain;
    if (accumulated >= gap) break;
  }
  return result;
}

const suggestions = ref<Suggestion[]>([]);

onMounted(() => { suggestions.value = pickRandomSuggestions(); });
watch(() => [props.flatScores, props.totalPoints], () => {
  suggestions.value = pickRandomSuggestions();
}, { deep: false });

function regenerate() {
  suggestions.value = pickRandomSuggestions();
}

const totalSuggestionGain = computed(() =>
  suggestions.value.reduce((acc, s) => acc + s.ptGain, 0)
);
</script>
