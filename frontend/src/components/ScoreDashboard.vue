<template>
  <div class="w-full space-y-6 animate-fade-in">
    <!-- Dashboard Stats Header -->
    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
      <div class="bg-white p-6 rounded-2xl shadow-sm border border-slate-200 flex flex-col items-center justify-center relative overflow-hidden">
        <div class="absolute inset-0 bg-gradient-to-br from-blue-50/50 to-transparent pointer-events-none"></div>
        <div class="absolute top-4 right-4 z-20">
          <button 
            @click="showInfoModal = true"
            class="group flex items-center gap-1.5 text-blue-500 hover:text-blue-700 transition-all font-bold"
          >
            <span class="text-[10px] uppercase tracking-wider opacity-0 group-hover:opacity-100 transition-opacity">Beat-Tierとは？</span>
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          </button>
        </div>
        <p class="text-sm font-medium text-slate-500 mb-1 z-10 font-bold uppercase tracking-widest">Beat-Tier</p>
        <div class="flex flex-col items-center z-10 text-center">
          <h3 class="text-2xl sm:text-3xl font-black mb-1 line-clamp-1" :class="rankInfo.color">
            {{ rankInfo.name }} {{ rankInfo.tier || '' }}
          </h3>
          <p class="text-xs sm:text-sm font-bold text-slate-400">{{ totalPoints.toFixed(1) }} pt</p>
        </div>
        <!-- Progress Bar -->
        <div class="w-full mt-4 bg-slate-100 h-1.5 rounded-full overflow-hidden z-10">
          <div 
            class="h-full bg-blue-500 rounded-full transition-all duration-1000"
            :style="{ width: `${nextRankInfo.progress}%` }"
          ></div>
        </div>
        <p v-if="nextRankInfo.nextRank" class="text-[10px] font-bold text-slate-400 mt-2 z-10 uppercase tracking-widest text-center">
          Next: {{ nextRankInfo.nextRank.name }} {{ nextRankInfo.nextRank.tier || '' }}<br/>
          残り ({{ nextRankInfo.nextRank.minPoints - totalPoints > 0 ? (nextRankInfo.nextRank.minPoints - totalPoints).toFixed(1) : 0 }} pt)
        </p>
      </div>
      <div class="bg-white p-4 sm:p-6 rounded-2xl shadow-sm border border-slate-200 flex flex-col justify-between col-span-1 md:col-span-1 lg:col-span-3">
        <!-- Dashboard Settings Panel -->
        <div class="flex flex-col sm:flex-row flex-wrap items-start sm:items-center justify-between gap-4 mb-6 pb-4 border-b border-slate-100">
          <div class="flex items-center gap-3">
            <h4 class="font-black text-slate-800 text-sm flex items-center gap-2">
              <svg class="w-4 h-4 text-blue-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4" />
              </svg>
              ダッシュボード設定
            </h4>
          </div>
          <div class="flex flex-wrap items-center gap-3 sm:gap-6 text-sm">
            <!-- Level Filter -->
            <div class="flex items-center gap-2">
              <label class="font-bold text-slate-500 text-xs">対象レベル</label>
              <select v-model="selectedLevel" class="bg-slate-50 border border-slate-200 text-slate-700 font-bold rounded-lg px-2 py-1 outline-none focus:ring-2 ring-blue-500 transition-all cursor-pointer">
                <option value="ALL">すべて</option>
                <option value="12">☆12</option>
                <option value="11">☆11</option>
                <option value="10">☆10</option>
                <option value="9">☆9</option>
                <option value="8">☆8</option>
              </select>
            </div>
            
            <!-- Exclude 0 Score -->
            <label class="flex items-center gap-2 cursor-pointer group">
              <div class="relative">
                <input type="checkbox" v-model="excludeZeroScore" class="sr-only" />
                <div class="block w-8 h-5 rounded-full transition-colors" :class="excludeZeroScore ? 'bg-blue-500' : 'bg-slate-300'"></div>
                <div class="dot absolute left-1 top-1 bg-white w-3 h-3 rounded-full transition-transform" :class="excludeZeroScore ? 'transform translate-x-3' : ''"></div>
              </div>
              <span class="font-bold text-xs text-slate-600 group-hover:text-slate-800 transition-colors">未プレイ(0pt)を除外</span>
            </label>

            <!-- Display Mode -->
            <div class="flex items-center bg-slate-100 p-0.5 rounded-lg border border-slate-200">
              <button 
                @click="displayMode = 'rate'"
                class="px-3 py-1 text-xs font-bold rounded-md transition-all"
                :class="displayMode === 'rate' ? 'bg-white text-blue-600 shadow-sm' : 'text-slate-500 hover:text-slate-700'"
              >
                達成率 (%)
              </button>
              <button 
                @click="displayMode = 'count'"
                class="px-3 py-1 text-xs font-bold rounded-md transition-all"
                :class="displayMode === 'count' ? 'bg-white text-blue-600 shadow-sm' : 'text-slate-500 hover:text-slate-700'"
              >
                個数 (曲)
              </button>
            </div>
          </div>
        </div>

        <!-- Stats Grid -->
        <div class="grid grid-cols-2 md:grid-cols-4 gap-4 sm:gap-6 mt-auto">
          <div class="flex flex-col items-center justify-center p-3 rounded-xl bg-slate-50/50 border border-slate-100 transition-all hover:bg-slate-50">
            <p class="text-[9px] sm:text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1 flex items-center gap-1">
              <span>対象曲数</span>
              <span v-if="excludeZeroScore" class="text-[8px] text-blue-400 bg-blue-50 px-1 py-0.5 rounded leading-none">厳選</span>
            </p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-slate-700">{{ totalFilteredSongs }}</h3>
          </div>
          <div class="flex flex-col items-center justify-center p-3 rounded-xl bg-blue-50/30 border border-blue-100/50 transition-all hover:bg-blue-50 border-t-2 border-t-blue-400">
            <p class="text-[9px] sm:text-[10px] font-bold text-blue-400 uppercase tracking-widest mb-1">クリア{{ displayMode === 'rate' ? '率' : '数' }}</p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-blue-600 flex items-baseline gap-1">
              {{ displayMode === 'rate' ? clearRate : clearCount }}<span class="text-sm font-bold opacity-70">{{ displayMode === 'rate' ? '%' : '' }}</span>
            </h3>
          </div>
          <div class="flex flex-col items-center justify-center p-3 rounded-xl bg-amber-50/30 border border-amber-100/50 transition-all hover:bg-amber-50 border-t-2 border-t-amber-400">
            <p class="text-[9px] sm:text-[10px] font-bold text-amber-500 uppercase tracking-widest mb-1">AAA達成{{ displayMode === 'rate' ? '率' : '数' }}</p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-amber-500 flex items-baseline gap-1">
              {{ displayMode === 'rate' ? aaaRate : aaaCount }}<span class="text-sm font-bold opacity-70">{{ displayMode === 'rate' ? '%' : '' }}</span>
            </h3>
          </div>
          <div class="flex flex-col items-center justify-center p-3 rounded-xl bg-purple-50/30 border border-purple-100/50 transition-all hover:bg-purple-50 border-t-2 border-t-purple-400">
            <p class="text-[9px] sm:text-[10px] font-bold text-purple-400 uppercase tracking-widest mb-1" title="スコアレート 94.45% 以上">MAX- 達成{{ displayMode === 'rate' ? '率' : '数' }}</p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-purple-600 flex items-baseline gap-1">
              {{ displayMode === 'rate' ? maxMinusRate : maxMinusCount }}<span class="text-sm font-bold opacity-70">{{ displayMode === 'rate' ? '%' : '' }}</span>
            </h3>
          </div>
        </div>
      </div>
    </div>

    <!-- Charts Row -->
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-4 sm:gap-6">
      <!-- Clear Types -->
      <div class="bg-white p-4 sm:p-6 rounded-2xl shadow-sm border border-slate-200 flex flex-col">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-base sm:text-lg font-bold text-slate-800">クリアタイプ分布</h3>
          <span class="text-[10px] font-bold px-2 py-1 bg-slate-100 text-slate-500 rounded">{{ selectedLevel === 'ALL' ? '全レベル' : `☆${selectedLevel}` }}</span>
        </div>
        <div class="h-48 sm:h-64 flex items-center justify-center">
          <Doughnut v-if="clearTypeData" :data="clearTypeData" :options="pieOptions" />
        </div>
      </div>
  
      <!-- DJ Levels -->
      <div class="bg-white p-4 sm:p-6 rounded-2xl shadow-sm border border-slate-200 flex flex-col">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-base sm:text-lg font-bold text-slate-800">DJレベル分布</h3>
          <span class="text-[10px] font-bold px-2 py-1 bg-slate-100 text-slate-500 rounded">{{ selectedLevel === 'ALL' ? '全レベル' : `☆${selectedLevel}` }}</span>
        </div>
        <div class="h-48 sm:h-64 flex items-center justify-center">
          <Bar v-if="djLevelData" :data="djLevelData" :options="barOptions" />
        </div>
      </div>
    </div>

    <!-- Info Modal -->
    <BeatTierInfoModal v-if="showInfoModal" @close="showInfoModal = false" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { ScoreData } from '../types/ScoreData';
import { Chart as ChartJS, ArcElement, Tooltip, Legend, CategoryScale, LinearScale, BarElement, Title } from 'chart.js';
import { Doughnut, Bar } from 'vue-chartjs';
import { ref } from 'vue';
import { getRankInfo, getNextRankInfo } from '../utils/beatTier';
import BeatTierInfoModal from './BeatTierInfoModal.vue';

import { flattenScores } from '../utils/scoreData';

ChartJS.register(ArcElement, Tooltip, Legend, CategoryScale, LinearScale, BarElement, Title);

const props = defineProps<{
  scores: ScoreData[];
  totalPoints: number;
}>();

const showInfoModal = ref(false);

// Dashboard Settings State
const selectedLevel = ref('ALL');
const excludeZeroScore = ref(false);
const displayMode = ref<'rate' | 'count'>('rate');

// Beat-Tier Calculations
const rankInfo = computed(() => getRankInfo(props.totalPoints));
const nextRankInfo = computed(() => getNextRankInfo(props.totalPoints));

// Flat Scores processing
const allFlattenedScores = computed(() => flattenScores(props.scores));

const filteredScores = computed(() => {
  return allFlattenedScores.value.filter(s => {
    // Level filter
    if (selectedLevel.value !== 'ALL' && s.difficultyLevel !== parseInt(selectedLevel.value)) {
      return false;
    }
    // Zero score exclusion
    if (excludeZeroScore.value && s.score === 0) {
      return false;
    }
    return true;
  });
});

// Computed Stats based on filters
const totalFilteredSongs = computed(() => filteredScores.value.length);

const clearedSongs = computed(() => filteredScores.value.filter(s => s.clearType !== 'FAILED' && s.clearType !== 'NO PLAY' && s.clearType !== '---'));
const clearCount = computed(() => clearedSongs.value.length);
const clearRate = computed(() => totalFilteredSongs.value === 0 ? 0 : Math.round((clearCount.value / totalFilteredSongs.value) * 100));

const aaaSongs = computed(() => filteredScores.value.filter(s => s.djLevel === 'AAA'));
const aaaCount = computed(() => aaaSongs.value.length);
const aaaRate = computed(() => totalFilteredSongs.value === 0 ? 0 : Math.round((aaaCount.value / totalFilteredSongs.value) * 100));

const maxMinusSongs = computed(() => filteredScores.value.filter(s => s.scoreRate >= 94.45));
const maxMinusCount = computed(() => maxMinusSongs.value.length);
const maxMinusRate = computed(() => totalFilteredSongs.value === 0 ? 0 : Math.round((maxMinusCount.value / totalFilteredSongs.value) * 100));

// Clear Type Chart
const clearTypeColors: Record<string, string> = {
  'FULLCOMBO CLEAR': '#10b981', // green-500
  'EX HARD CLEAR': '#f59e0b',   // amber-500
  'HARD CLEAR': '#ef4444',      // red-500
  'CLEAR': '#3b82f6',           // blue-500
  'EASY CLEAR': '#22c55e',      // green-500 (alt)
  'ASSIST CLEAR': '#a855f7',    // purple-500
  'FAILED': '#64748b',          // slate-500
};

const clearTypeData = computed(() => {
  const counts: Record<string, number> = {};
  filteredScores.value.forEach(s => {
    // Only count actual plays or failed, ignore empty data
    if (s.clearType !== 'NO PLAY' && s.clearType !== '---') {
      counts[s.clearType] = (counts[s.clearType] || 0) + 1;
    }
  });

  const labels = Object.keys(counts).sort((a, b) => counts[b] - counts[a]);
  const data = labels.map(l => counts[l]);
  const bgColors = labels.map(l => clearTypeColors[l] || '#cbd5e1');

  return {
    labels,
    datasets: [
      {
        backgroundColor: bgColors,
        data,
        borderWidth: 0,
        hoverOffset: 4
      }
    ]
  };
});

// DJ Level Chart
const djLevelColors: Record<string, string> = {
  'AAA': '#fbbf24', // amber-400
  'AA': '#94a3b8',  // slate-400
  'A': '#22c55e',   // green-500
  'B': '#3b82f6',   // blue-500
  'C': '#8b5cf6',   // violet-500
  'D': '#d946ef',   // fuchsia-500
  'E': '#f43f5e',   // rose-500
  'F': '#64748b',   // slate-500
};

const djLevelData = computed(() => {
  const levels = ['AAA', 'AA', 'A', 'B', 'C', 'D', 'E', 'F'];
  const counts: Record<string, number> = levels.reduce((acc, lvl) => ({ ...acc, [lvl]: 0 }), {});
  
  filteredScores.value.forEach(s => {
    // Only count actual plays or fails
    if (s.clearType !== 'NO PLAY' && s.clearType !== '---') {
        const lvl = s.djLevel;
        if (counts[lvl] !== undefined) {
          counts[lvl]++;
        }
    }
  });

  const data = levels.map(l => counts[l]);
  const bgColors = levels.map(l => djLevelColors[l]);

  return {
    labels: levels,
    datasets: [
      {
        label: '楽曲数',
        backgroundColor: bgColors,
        data,
        borderRadius: 4,
      }
    ]
  };
});

// Chart Options
const pieOptions = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: {
      position: 'right' as const,
      labels: {
        usePointStyle: true,
        padding: 20,
        font: {
          family: "'Inter', sans-serif",
          size: 12
        }
      }
    }
  }
};

const barOptions = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: {
      display: false
    },
    tooltip: {
      callbacks: {
        title: () => ''
      }
    }
  },
  scales: {
    y: {
      beginAtZero: true,
      ticks: {
        stepSize: 1
      },
      grid: {
        color: '#f1f5f9'
      }
    },
    x: {
      grid: {
        display: false
      }
    }
  }
};
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
