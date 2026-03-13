<template>
  <div class="w-full space-y-6 animate-fade-in">
    <!-- Dashboard Stats Header -->
    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
      <div class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 flex flex-col items-center justify-center relative overflow-hidden transition-colors duration-200">
        <div class="absolute inset-0 bg-gradient-to-br from-blue-50/50 dark:from-blue-900/20 to-transparent pointer-events-none"></div>
        <div class="absolute top-4 right-4 z-20">
          <button 
            @click="showInfoModal = true"
            class="group flex items-center gap-1.5 text-blue-500 dark:text-blue-400 hover:text-blue-700 dark:hover:text-blue-300 transition-all font-bold"
          >
            <span class="text-[10px] uppercase tracking-wider opacity-0 group-hover:opacity-100 transition-opacity">Beat-Tierとは？</span>
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          </button>
        </div>
        <p class="text-sm font-medium text-slate-500 dark:text-slate-400 mb-1 z-10 font-bold uppercase tracking-widest">Beat-Tier</p>
        <div class="flex flex-col items-center z-10 text-center">
          <RankIcon :rank-name="rankInfo.name" :tier="rankInfo.tier" size="lg" class="mb-2" />
          <h3 class="text-2xl sm:text-3xl font-black mb-1 line-clamp-1" :class="rankInfo.color">
            {{ rankInfo.name }} {{ rankInfo.tier || '' }}
          </h3>
          <p class="text-xs sm:text-sm font-bold text-slate-400 dark:text-slate-500">{{ totalPoints.toFixed(1) }} pt</p>
        </div>
        <!-- Progress Bar -->
        <div class="w-full mt-4 bg-slate-100 dark:bg-slate-700 h-1.5 rounded-full overflow-hidden z-10">
          <div 
            class="h-full bg-blue-500 dark:bg-blue-400 rounded-full transition-all duration-1000"
            :style="{ width: `${nextRankInfo.progress}%` }"
          ></div>
        </div>
        <p v-if="nextRankInfo.nextRank" class="text-[10px] font-bold text-slate-400 dark:text-slate-500 mt-2 z-10 uppercase tracking-widest text-center">
          Next: {{ nextRankInfo.nextRank.name }} {{ nextRankInfo.nextRank.tier || '' }}<br/>
          残り ({{ nextRankInfo.nextRank.minPoints - totalPoints > 0 ? (nextRankInfo.nextRank.minPoints - totalPoints).toFixed(1) : 0 }} pt)
        </p>
      </div>
      <div class="bg-white dark:bg-slate-800 p-4 sm:p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 flex flex-col justify-between col-span-1 md:col-span-1 lg:col-span-3 transition-colors duration-200">
        <!-- Ranking Position -->
        <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 mb-5 pb-5 border-b border-slate-100 dark:border-slate-700">
          <div>
            <p class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">現在のランキング順位</p>
            <div class="flex items-end gap-3">
              <span v-if="myRankingPosition" class="text-5xl font-black text-slate-800 dark:text-slate-100 tabular-nums leading-none"># {{ myRankingPosition.position }}</span>
              <span v-else class="text-3xl font-black text-slate-300 dark:text-slate-600 leading-none">-</span>
              <div v-if="myRankingPosition" class="flex flex-col pb-1 gap-0.5">
                <span class="text-sm font-bold text-slate-400">/ {{ myRankingPosition.total }} 人中</span>
                <span v-if="myRankingPosition.rankChange === null" class="text-[11px] font-bold text-blue-500">NEW ENTRY</span>
                <span v-else-if="myRankingPosition.rankChange > 0" class="text-[11px] font-bold text-emerald-500">▲ {{ myRankingPosition.rankChange }} 位上昇</span>
                <span v-else-if="myRankingPosition.rankChange < 0" class="text-[11px] font-bold text-red-500">▼ {{ Math.abs(myRankingPosition.rankChange) }} 位下降</span>
                <span v-else class="text-[11px] font-bold text-slate-400 dark:text-slate-500">順位変動なし</span>
              </div>
            </div>
          </div>
          <div v-if="myRankingPosition" class="flex items-center gap-2 self-end sm:self-auto">
            <div class="text-right">
              <p class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-0.5">前後のプレイヤー</p>
              <div class="space-y-0.5">
                <p v-if="rankingNeighbors.above" class="text-xs text-slate-500 dark:text-slate-400 tabular-nums">
                  ▲ {{ rankingNeighbors.above.displayName }} ({{ rankingNeighbors.above.totalBeatPt.toFixed(1) }} pt)
                </p>
                <p class="text-xs font-bold text-blue-600 dark:text-blue-400 tabular-nums">
                  ▶ あなた ({{ totalPoints.toFixed(1) }} pt)
                </p>
                <p v-if="rankingNeighbors.below" class="text-xs text-slate-500 dark:text-slate-400 tabular-nums">
                  ▼ {{ rankingNeighbors.below.displayName }} ({{ rankingNeighbors.below.totalBeatPt.toFixed(1) }} pt)
                </p>
              </div>
            </div>
          </div>
        </div>

        <!-- Lv12 Quick Stats (fixed, no settings needed) -->
        <div class="grid grid-cols-2 md:grid-cols-4 gap-3 sm:gap-4 mt-auto">
          <div class="flex flex-col items-center justify-center p-3 rounded-xl bg-slate-50/50 dark:bg-slate-700/30 border border-slate-100 dark:border-slate-700">
            <p class="text-[9px] font-bold text-slate-400 uppercase tracking-widest mb-1">☆12 総数</p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-slate-700 dark:text-slate-200">{{ lv12Total }}</h3>
          </div>
          <div class="flex flex-col items-center justify-center p-3 rounded-xl bg-blue-50/30 dark:bg-blue-900/10 border border-blue-100/50 dark:border-blue-800/50 border-t-2 border-t-blue-400 dark:border-t-blue-500">
            <p class="text-[9px] font-bold text-blue-400 dark:text-blue-500 uppercase tracking-widest mb-1">☆12 クリア率</p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-blue-600 dark:text-blue-400 flex items-baseline gap-0.5">
              {{ lv12ClearRate }}<span class="text-sm font-bold opacity-70">%</span>
            </h3>
          </div>
          <div class="flex flex-col items-center justify-center p-3 rounded-xl bg-amber-50/30 dark:bg-amber-900/10 border border-amber-100/50 dark:border-amber-800/50 border-t-2 border-t-amber-400 dark:border-t-amber-500">
            <p class="text-[9px] font-bold text-amber-500 uppercase tracking-widest mb-1">☆12 AAA率</p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-amber-500 flex items-baseline gap-0.5">
              {{ lv12AaaRate }}<span class="text-sm font-bold opacity-70">%</span>
            </h3>
          </div>
          <div class="flex flex-col items-center justify-center p-3 rounded-xl bg-purple-50/30 dark:bg-purple-900/10 border border-purple-100/50 dark:border-purple-800/50 border-t-2 border-t-purple-400 dark:border-t-purple-500">
            <p class="text-[9px] font-bold text-purple-400 dark:text-purple-500 uppercase tracking-widest mb-1" title="スコアレート 94.45% 以上">☆12 MAX-率</p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-purple-600 dark:text-purple-400 flex items-baseline gap-0.5">
              {{ lv12MaxMinusRate }}<span class="text-sm font-bold opacity-70">%</span>
            </h3>
          </div>
        </div>
      </div>
    </div>

    <!-- Unofficial Difficulty Table -->
    <UnofficialDifficultyTable :scores="allFlattenedScores" />

    <!-- Rank Up Advice -->
    <RankUpAdvice :flat-scores="allFlattenedScores" :total-points="props.totalPoints" />

    <!-- Info Modal -->
    <BeatTierInfoModal v-if="showInfoModal" @close="showInfoModal = false" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue';
import type { ScoreData } from '../types/ScoreData';
import { getRankInfo, getNextRankInfo } from '../utils/beatTier';
import BeatTierInfoModal from './BeatTierInfoModal.vue';
import RankIcon from './RankIcon.vue';
import UnofficialDifficultyTable from './UnofficialDifficultyTable.vue';
import RankUpAdvice from './RankUpAdvice.vue';
import { useAuth } from '../composables/useAuth';
import { flattenScores } from '../utils/scoreData';

const { user } = useAuth();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

const props = defineProps<{
  scores: ScoreData[];
  totalPoints: number;
}>();

const showInfoModal = ref(false);

// Beat-Tier Calculations
const rankInfo = computed(() => getRankInfo(props.totalPoints));
const nextRankInfo = computed(() => getNextRankInfo(props.totalPoints));

// Flat Scores processing
const allFlattenedScores = computed(() => flattenScores(props.scores));

// Lv12 quick stats (no settings needed)
const lv12All = computed(() => allFlattenedScores.value.filter(s => s.difficultyLevel === 12));
const lv12Total = computed(() => lv12All.value.length);
const lv12Played = computed(() => lv12All.value.filter(s => s.score > 0));
const lv12ClearRate = computed(() => {
  if (!lv12Total.value) return 0;
  const cleared = lv12All.value.filter(s => !['FAILED', 'NO PLAY', '---'].includes(s.clearType)).length;
  return Math.round((cleared / lv12Total.value) * 100);
});
const lv12AaaRate = computed(() => {
  if (!lv12Total.value) return 0;
  const aaa = lv12All.value.filter(s => s.djLevel === 'AAA').length;
  return Math.round((aaa / lv12Total.value) * 100);
});
const lv12MaxMinusRate = computed(() => {
  if (!lv12Total.value) return 0;
  const mm = lv12All.value.filter(s => s.scoreRate >= 94.45).length;
  return Math.round((mm / lv12Total.value) * 100);
});

// Ranking
interface RankingEntry { displayName: string; iidxId: string; totalBeatPt: number; rankChange: number | null; }
const rankingData = ref<RankingEntry[]>([]);

onMounted(async () => {
  try {
    const res = await fetch(`${API_BASE}/api/scores/ranking`);
    if (res.ok) rankingData.value = await res.json();
  } catch {}
});

const myRankingPosition = computed(() => {
  if (!user.value || !rankingData.value.length) return null;
  const idx = rankingData.value.findIndex(r => r.iidxId === user.value!.iidxId);
  if (idx === -1) return null;
  return { position: idx + 1, total: rankingData.value.length, rankChange: rankingData.value[idx].rankChange };
});

const rankingNeighbors = computed(() => {
  if (!myRankingPosition.value) return { above: null, below: null };
  const pos = myRankingPosition.value.position;
  return {
    above: pos > 1 ? rankingData.value[pos - 2] : null,
    below: pos < rankingData.value.length ? rankingData.value[pos] : null,
  };
});
</script>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.5s ease-out forwards;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(15px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
