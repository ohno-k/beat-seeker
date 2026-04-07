<template>
  <div class="w-full space-y-6 animate-fade-in">
    <!-- Email Registration Prompt (未登録の場合のみ表示) -->
    <div v-if="user && !user.email" class="w-full bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-700/50 rounded-2xl p-4 flex flex-col sm:flex-row items-start sm:items-center gap-3">
      <div class="flex items-center gap-3 flex-1">
        <div class="w-9 h-9 bg-amber-100 dark:bg-amber-900/50 rounded-xl flex items-center justify-center shrink-0">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
          </svg>
        </div>
        <div>
          <p class="text-sm font-bold text-amber-800 dark:text-amber-300">{{ t('dashboard.emailNotRegistered') }}</p>
          <p class="text-xs text-amber-600 dark:text-amber-400">{{ t('dashboard.emailHint') }}</p>
        </div>
      </div>
      <button @click="$emit('open-profile-edit')" class="shrink-0 px-4 py-2 bg-amber-500 hover:bg-amber-400 text-white text-sm font-bold rounded-xl transition-colors shadow-sm">
        {{ t('dashboard.registerNow') }}
      </button>
    </div>

    <!-- Tier Cards Row -->
    <div class="grid grid-cols-1 gap-6" :class="{ 'sm:grid-cols-2': showRateTier }">
      <!-- Beat-Tier (Lv11/12) -->
      <div class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 flex flex-col items-center justify-center relative overflow-hidden transition-colors duration-200">
        <div class="absolute inset-0 bg-gradient-to-br from-blue-50/50 dark:from-blue-900/20 to-transparent pointer-events-none"></div>
        <div class="absolute top-4 right-4 z-20">
          <button
            @click="showInfoModal = true"
            class="group flex items-center gap-1.5 text-blue-500 dark:text-blue-400 hover:text-blue-700 dark:hover:text-blue-300 transition-all font-bold"
          >
            <span class="text-[10px] uppercase tracking-wider opacity-0 group-hover:opacity-100 transition-opacity">{{ t('dashboard.whatIsBeatTier') }}</span>
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          </button>
        </div>
        <p class="text-sm font-medium text-slate-500 dark:text-slate-400 mb-1 z-10 font-bold uppercase tracking-widest">Beat-Tier</p>
        <div class="flex flex-col items-center z-10 text-center">
          <RankIcon :rank-name="rankInfo.name" :tier="rankInfo.tier" size="lg" class="mb-2" :is-supporter="user?.isSupporter && user?.showSupporterBorder" />
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
          {{ t('dashboard.remaining') }} ({{ nextRankInfo.nextRank.minPoints - totalPoints > 0 ? (nextRankInfo.nextRank.minPoints - totalPoints).toFixed(1) : 0 }} pt)
        </p>
      </div>

      <!-- Rate-Tier (全難度 ANOTHER/LEGGENDARIA) -->
      <div v-if="showRateTier" class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 flex flex-col items-center justify-center relative overflow-hidden transition-colors duration-200">
        <div class="absolute inset-0 bg-gradient-to-br from-emerald-50/50 dark:from-emerald-900/20 to-transparent pointer-events-none"></div>
        <div class="absolute top-4 right-4 z-20">
          <button
            @click="showRateInfoModal = true"
            class="group flex items-center gap-1.5 text-emerald-500 dark:text-emerald-400 hover:text-emerald-700 dark:hover:text-emerald-300 transition-all font-bold"
          >
            <span class="text-[10px] uppercase tracking-wider opacity-0 group-hover:opacity-100 transition-opacity">{{ t('dashboard.whatIsRateTier') }}</span>
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          </button>
        </div>
        <p class="text-sm font-medium text-slate-500 dark:text-slate-400 mb-1 z-10 font-bold uppercase tracking-widest">Rate-Tier</p>
        <div class="flex flex-col items-center z-10 text-center">
          <RankIcon :rank-name="rateTierRankInfo.name" :tier="rateTierRankInfo.tier" size="lg" class="mb-2" :is-supporter="user?.isSupporter && user?.showSupporterBorder" />
          <h3 class="text-2xl sm:text-3xl font-black mb-1 line-clamp-1" :class="rateTierRankInfo.color">
            {{ rateTierRankInfo.name }} {{ rateTierRankInfo.tier || '' }}
          </h3>
          <p class="text-xs sm:text-sm font-bold text-slate-400 dark:text-slate-500">{{ rateTierPoints.toFixed(1) }} pt</p>
        </div>
        <!-- Progress Bar -->
        <div class="w-full mt-4 bg-slate-100 dark:bg-slate-700 h-1.5 rounded-full overflow-hidden z-10">
          <div
            class="h-full bg-emerald-500 dark:bg-emerald-400 rounded-full transition-all duration-1000"
            :style="{ width: `${rateTierNextRankInfo.progress}%` }"
          ></div>
        </div>
        <p v-if="rateTierNextRankInfo.nextRank" class="text-[10px] font-bold text-slate-400 dark:text-slate-500 mt-2 z-10 uppercase tracking-widest text-center">
          Next: {{ rateTierNextRankInfo.nextRank.name }} {{ rateTierNextRankInfo.nextRank.tier || '' }}<br/>
          {{ t('dashboard.remaining') }} ({{ rateTierNextRankInfo.nextRank.minPoints - rateTierPoints > 0 ? (rateTierNextRankInfo.nextRank.minPoints - rateTierPoints).toFixed(1) : 0 }} pt)
        </p>
      </div>
    </div>

    <!-- Ranking + Lv12 Stats Row -->
    <div class="bg-white dark:bg-slate-800 p-4 sm:p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 flex flex-col justify-between transition-colors duration-200">
        <!-- Ranking Position -->
        <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 mb-5 pb-5 border-b border-slate-100 dark:border-slate-700">
          <div>
            <p class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">{{ t('dashboard.currentRank') }}</p>
            <div class="flex items-end gap-3">
              <span v-if="myRankingPosition" class="text-5xl font-black text-slate-800 dark:text-slate-100 tabular-nums leading-none"># {{ myRankingPosition.position }}</span>
              <span v-else class="text-3xl font-black text-slate-300 dark:text-slate-600 leading-none">-</span>
              <div v-if="myRankingPosition" class="flex flex-col pb-1 gap-0.5">
                <span class="text-sm font-bold text-slate-400">/ {{ myRankingPosition.total }} {{ t('dashboard.outOf') }}</span>
                <span v-if="myRankingPosition.rankChange === null" class="text-[11px] font-bold text-blue-500 uppercase">{{ t('dashboard.newEntry') }}</span>
                <span v-else-if="myRankingPosition.rankChange > 0" class="text-[11px] font-bold text-emerald-500">{{ t('dashboard.rankUp', { n: myRankingPosition.rankChange }) }}</span>
                <span v-else-if="myRankingPosition.rankChange < 0" class="text-[11px] font-bold text-red-500">{{ t('dashboard.rankDown', { n: Math.abs(myRankingPosition.rankChange) }) }}</span>
                <span v-else class="text-[11px] font-bold text-slate-400 dark:text-slate-500">{{ t('dashboard.rankNoChange') }}</span>
              </div>
            </div>
          </div>
          <div v-if="myRankingPosition" class="flex items-center gap-2 self-end sm:self-auto">
            <div class="text-right">
              <p class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-0.5">{{ t('dashboard.neighbors') }}</p>
              <div class="space-y-0.5">
                <p v-if="rankingNeighbors.above" class="text-xs text-slate-500 dark:text-slate-400 tabular-nums">
                  ▲ {{ rankingNeighbors.above.displayName }} ({{ rankingNeighbors.above.totalBeatPt.toFixed(1) }} pt)
                </p>
                <p class="text-xs font-bold text-blue-600 dark:text-blue-400 tabular-nums">
                  ▶ {{ t('dashboard.you') }} ({{ totalPoints.toFixed(1) }} pt)
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
            <p class="text-[9px] font-bold text-slate-400 uppercase tracking-widest mb-1">{{ t('dashboard.lv12Total') }}</p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-slate-700 dark:text-slate-200">{{ lv12Total }}</h3>
          </div>
          <div class="flex flex-col items-center justify-center p-3 rounded-xl bg-blue-50/30 dark:bg-blue-900/10 border border-blue-100/50 dark:border-blue-800/50 border-t-2 border-t-blue-400 dark:border-t-blue-500">
            <p class="text-[9px] font-bold text-blue-400 dark:text-blue-500 uppercase tracking-widest mb-1">{{ t('dashboard.lv12ClearRate') }}</p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-blue-600 dark:text-blue-400 flex items-baseline gap-0.5">
              {{ lv12ClearRate }}<span class="text-sm font-bold opacity-70">%</span>
            </h3>
          </div>
          <div class="flex flex-col items-center justify-center p-3 rounded-xl bg-amber-50/30 dark:bg-amber-900/10 border border-amber-100/50 dark:border-amber-800/50 border-t-2 border-t-amber-400 dark:border-t-amber-500">
            <p class="text-[9px] font-bold text-amber-500 uppercase tracking-widest mb-1">{{ t('dashboard.lv12AaaRate') }}</p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-amber-500 flex items-baseline gap-0.5">
              {{ lv12AaaRate }}<span class="text-sm font-bold opacity-70">%</span>
            </h3>
          </div>
          <div class="flex flex-col items-center justify-center p-3 rounded-xl bg-purple-50/30 dark:bg-purple-900/10 border border-purple-100/50 dark:border-purple-800/50 border-t-2 border-t-purple-400 dark:border-t-purple-500">
            <p class="text-[9px] font-bold text-purple-400 dark:text-purple-500 uppercase tracking-widest mb-1" :title="t('dashboard.maxMinusHint')">{{ t('dashboard.lv12MaxMinusRate') }}</p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-purple-600 dark:text-purple-400 flex items-baseline gap-0.5">
              {{ lv12MaxMinusRate }}<span class="text-sm font-bold opacity-70">%</span>
            </h3>
          </div>
        </div>
    </div>

    <!-- Unofficial Difficulty Table -->
    <UnofficialDifficultyTable :scores="allFlattenedScores" />

    <!-- Rank Up Advice -->
    <RankUpAdvice :flat-scores="allFlattenedScores" :total-points="props.totalPoints" />

    <!-- Activity Feed (全体ニュース) -->
    <ActivityFeed />

    <!-- Info Modal -->
    <BeatTierInfoModal v-if="showInfoModal" @close="showInfoModal = false" />
    <RateTierInfoModal v-if="showRateInfoModal" @close="showRateInfoModal = false" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue';
import { useI18n } from '../composables/useI18n';
import type { ScoreData } from '../types/ScoreData';
import { getRankInfo, getNextRankInfo, getRateTierRankInfo, getNextRateTierRankInfo, calculateScoreRateTierPoints } from '../utils/beatTier';
import BeatTierInfoModal from './BeatTierInfoModal.vue';
import RateTierInfoModal from './RateTierInfoModal.vue';
import RankIcon from './RankIcon.vue';
import UnofficialDifficultyTable from './UnofficialDifficultyTable.vue';
import RankUpAdvice from './RankUpAdvice.vue';
import ActivityFeed from './ActivityFeed.vue';
import { useAuth } from '../composables/useAuth';
import { flattenScores } from '../utils/scoreData';
import { useRateTierVisibility } from '../composables/useRateTierVisibility';

const { showRateTier } = useRateTierVisibility();
const { t } = useI18n();
const { user } = useAuth();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

const emit = defineEmits<{ (e: 'open-profile-edit'): void }>();

const props = defineProps<{
  scores: ScoreData[];
  totalPoints: number;
}>();

const showInfoModal = ref(false);
const showRateInfoModal = ref(false);

// Beat-Tier Calculations
const rankInfo = computed(() => getRankInfo(props.totalPoints));
const nextRankInfo = computed(() => getNextRankInfo(props.totalPoints));

// Flat Scores processing
const allFlattenedScores = computed(() => flattenScores(props.scores));

// Rate-Tier: top 100 ANOTHER/LEGGENDARIA songs across all levels
const rateTierPoints = computed(() => {
  const top100 = allFlattenedScores.value
    .filter(s => ['ANOTHER', 'LEGGENDARIA'].includes(s.difficultyName) && s.scoreRate > 0)
    .map(s => calculateScoreRateTierPoints(s.scoreRate))
    .filter(pt => pt > 0)
    .sort((a, b) => b - a)
    .slice(0, 100);
  const sum = top100.reduce((acc, pt) => acc + pt, 0);
  return Math.round(sum * 10) / 10;
});
const rateTierRankInfo = computed(() => getRateTierRankInfo(rateTierPoints.value));
const rateTierNextRankInfo = computed(() => getNextRateTierRankInfo(rateTierPoints.value));

// Lv12 quick stats (no settings needed)
const lv12All = computed(() => allFlattenedScores.value.filter(s => s.difficultyLevel === 12));
const lv12Total = computed(() => lv12All.value.length);
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
