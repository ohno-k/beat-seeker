<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[110] bg-slate-50 dark:bg-slate-900 flex flex-col animate-fade-in transition-colors duration-200">
      <!-- Header -->
      <div class="px-8 py-6 border-b border-slate-100 dark:border-slate-700/50 flex justify-between items-center bg-white dark:bg-slate-800 sticky top-0 z-10 transition-colors duration-200">
        <div>
          <h3 class="text-2xl font-black text-slate-800 dark:text-slate-100">{{ t('rateTierInfo.title') }}</h3>
          <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500 mt-0.5 uppercase tracking-widest">{{ t('rateTierInfo.subtitle') }}</p>
        </div>
        <button @click="$emit('close')" class="p-2 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-full transition-all">
          <svg class="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <!-- Content -->
      <div class="flex-1 overflow-y-auto custom-scrollbar bg-slate-50/50 dark:bg-slate-900/50 transition-colors duration-200">
        <!-- Tabs -->
        <div class="px-8 pt-6 sticky top-0 bg-white/80 dark:bg-slate-800/80 backdrop-blur-md z-10 transition-colors duration-200">
          <div class="flex border-b border-slate-200 dark:border-slate-700 gap-8">
            <button
              @click="activeTab = 'about'"
              :class="['pb-4 text-sm font-black transition-all relative', activeTab === 'about' ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300']"
            >
              {{ t('beatTierInfo.tabAbout') }}
              <div v-if="activeTab === 'about'" class="absolute bottom-0 left-0 right-0 h-1 bg-emerald-600 dark:bg-emerald-500 rounded-full"></div>
            </button>
            <button
              @click="activeTab = 'table'"
              :class="['pb-4 text-sm font-black transition-all relative', activeTab === 'table' ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300']"
            >
              {{ t('rateTierInfo.tabTable') }}
              <div v-if="activeTab === 'table'" class="absolute bottom-0 left-0 right-0 h-1 bg-emerald-600 dark:bg-emerald-500 rounded-full"></div>
            </button>
          </div>
        </div>

        <div class="p-4 sm:p-8">
          <!-- About Tab -->
          <div v-if="activeTab === 'about'" class="space-y-8 animate-fade-in">
            <section>
              <h4 class="text-lg font-black text-slate-800 dark:text-slate-100 mb-3 flex items-center gap-2">
                <span class="w-1.5 h-6 bg-emerald-600 dark:bg-emerald-500 rounded-full"></span>
                {{ t('rateTierInfo.whatIsTitle') }}
              </h4>
              <p class="text-slate-600 dark:text-slate-300 leading-relaxed text-sm font-medium" v-html="t('rateTierInfo.whatIsDesc')"></p>
            </section>

            <section class="bg-slate-900 dark:bg-slate-950 rounded-3xl p-8 text-white shadow-2xl relative overflow-hidden border border-slate-700 dark:border-slate-800 transition-colors duration-200">
              <div class="absolute top-0 right-0 w-64 h-64 bg-emerald-500/10 dark:bg-emerald-400/5 rounded-full -translate-y-1/2 translate-x-1/2 blur-3xl"></div>
              <h4 class="text-[10px] font-black uppercase tracking-[0.2em] mb-6 text-slate-400 dark:text-slate-500">Calculation Formula</h4>
              <div class="flex flex-col md:flex-row items-center justify-between gap-8 relative z-10">
                <div class="flex-1 text-center md:text-left">
                  <p class="text-4xl font-black mb-2 tracking-tight text-emerald-400 dark:text-emerald-300">{{ t('rateTierInfo.formulaTitle') }}</p>
                  <p class="text-xs font-bold text-slate-400 dark:text-slate-500 leading-relaxed" v-html="t('rateTierInfo.formulaDesc')"></p>
                </div>
                <div class="h-px md:h-20 w-full md:w-px bg-slate-700 dark:bg-slate-800"></div>
                <div class="flex-1 text-sm font-bold text-slate-300 dark:text-slate-400 leading-relaxed">
                  <p>• {{ t('rateTierInfo.weightDesc') }}</p>
                  <p class="text-emerald-400/80 dark:text-emerald-300/80 mt-1">{{ t('rateTierInfo.finalPointsDesc') }}</p>
                </div>
              </div>
            </section>

            <!-- Rank Board -->
            <section class="space-y-8">
              <div class="flex items-center justify-between">
                <h4 class="text-lg font-black text-slate-800 dark:text-slate-100 flex items-center gap-2">
                  <span class="w-1.5 h-6 bg-purple-600 dark:bg-purple-500 rounded-full"></span>
                  {{ t('beatTierInfo.rankBoardTitle') }}
                </h4>
                <div class="text-[10px] font-bold text-slate-400 dark:text-slate-500 uppercase tracking-widest bg-slate-100 dark:bg-slate-800 px-3 py-1 rounded-full transition-colors duration-200">Hierarchy</div>
              </div>

              <!-- Premium Dark/Light Grid for Ranks -->
              <div class="bg-white dark:bg-slate-950 rounded-[2rem] p-4 sm:p-10 border border-slate-200 dark:border-slate-800 shadow-xl dark:shadow-2xl">
                <div class="w-full space-y-12">

                  <!-- Legend -->
                  <div class="flex items-center justify-center gap-12 border-b border-slate-200 dark:border-slate-800/50 pb-12">
                    <div v-if="groupedRanks['Legend']" class="flex flex-col items-center group">
                      <RankIcon :rank-name="'Legend'" size="lg" />
                      <div class="mt-6 text-center">
                        <p class="text-base font-black text-amber-500 uppercase tracking-widest mb-1">Legend</p>
                        <p class="text-sm font-bold text-slate-700 dark:text-slate-300 bg-slate-100 dark:bg-slate-800/50 px-3 py-1 rounded-full border border-slate-200 dark:border-slate-700">{{ groupedRanks['Legend'][0].minPoints.toLocaleString() }} pt</p>
                      </div>
                    </div>
                  </div>

                  <!-- Main Grid -->
                  <div class="flex flex-col xl:flex-row justify-between gap-8 xl:gap-4 overflow-x-auto custom-scrollbar xl:overflow-visible pb-4 xl:pb-0">
                    <div v-for="name in rankNames" :key="name" class="flex flex-col xl:items-center bg-slate-50 dark:bg-slate-900/50 xl:bg-transparent rounded-2xl p-4 xl:p-0">

                      <div class="flex items-center xl:flex-col xl:space-y-6 mb-4 xl:mb-0">
                        <div class="hidden xl:block w-px h-12 bg-gradient-to-b from-transparent to-slate-200 dark:to-slate-800 xl:mb-6"></div>
                        <p class="text-base xl:text-xs font-black text-slate-700 dark:text-slate-300 xl:text-slate-500 xl:dark:text-slate-400 uppercase tracking-widest flex-1 xl:flex-none xl:h-4 xl:mb-4">{{ name }}</p>
                      </div>

                      <div class="flex flex-row xl:flex-col items-center gap-4 xl:gap-0 xl:space-y-0 w-full overflow-x-auto xl:overflow-visible py-2 xl:py-0 custom-scrollbar">
                        <div v-for="tier in 5" :key="tier" class="relative group shrink-0 xl:w-full">
                          <div v-if="getRankForTier(name, 6 - tier)" class="flex flex-row xl:flex-col items-center">
                            <div v-if="tier > 1" class="hidden xl:block w-px h-6 bg-slate-200 dark:bg-slate-800/50 mb-2"></div>
                            <div v-if="tier > 1" class="xl:hidden w-4 h-px bg-slate-200 dark:bg-slate-800/50 mr-4"></div>

                            <div class="relative transition-all duration-300 transform group-hover:scale-110 group-hover:-translate-y-2">
                              <RankIcon :rank-name="name" :tier="6 - tier" size="md" />
                              <!-- Hover Tooltip -->
                              <div class="absolute bottom-full left-1/2 -translate-x-1/2 mb-3 opacity-0 group-hover:opacity-100 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 text-slate-800 dark:text-white p-3 rounded-xl text-xs whitespace-nowrap z-30 pointer-events-none transition-all shadow-xl dark:shadow-2xl">
                                <div class="flex flex-col items-center gap-1">
                                  <span class="font-black text-emerald-600 dark:text-emerald-400 text-sm">{{ name }} {{ 6 - tier }}</span>
                                  <span class="font-bold text-slate-600 dark:text-slate-300">{{ getRankForTier(name, 6 - tier)?.minPoints.toLocaleString() }} pt</span>
                                </div>
                                <div class="absolute top-full left-1/2 -translate-x-1/2 -mt-1 w-2 h-2 bg-white dark:bg-slate-900 border-r border-b border-slate-200 dark:border-slate-700 rotate-45"></div>
                              </div>
                            </div>
                            <div class="mt-3 xl:mt-3 ml-0 xl:ml-0 flex flex-col items-center gap-1 min-w-[3rem]">
                              <p class="text-xs font-black text-slate-700 dark:text-slate-300">{{ 6 - tier }}</p>
                              <p class="text-[10px] font-bold text-slate-500 tracking-tight">{{ (getRankForTier(name, 6 - tier)?.minPoints || 0) / 1000 }}k</p>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>

                  <!-- Beginner (Base) -->
                  <div class="flex items-center justify-center pt-8 border-t border-slate-200 dark:border-slate-800/50">
                    <div class="flex flex-col items-center opacity-70 hover:opacity-100 transition-all duration-300">
                      <RankIcon :rank-name="'Beginner'" size="sm" />
                      <p class="text-xs font-black text-slate-500 dark:text-slate-400 uppercase tracking-widest mt-3">Beginner (0 pt)</p>
                    </div>
                  </div>

                </div>
              </div>
            </section>
          </div>

          <!-- Score Rate Table Tab -->
          <div v-else class="space-y-6 animate-fade-in">
            <section>
              <h4 class="text-lg font-black text-slate-800 dark:text-slate-100 mb-3 flex items-center gap-2">
                <span class="w-1.5 h-6 bg-emerald-600 dark:bg-emerald-500 rounded-full"></span>
                {{ t('rateTierInfo.tableTitle') }}
              </h4>
              <p class="text-slate-500 dark:text-slate-400 text-sm font-medium mb-6">
                {{ t('rateTierInfo.tableDesc') }}
              </p>
            </section>

            <div class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden shadow-sm">
              <div class="px-5 py-3 bg-slate-50 dark:bg-slate-700/50 border-b border-slate-200 dark:border-slate-700 grid grid-cols-3 gap-4">
                <span class="text-xs font-black text-slate-500 dark:text-slate-400 uppercase tracking-widest">{{ t('table.colRate') }}</span>
                <span class="text-xs font-black text-slate-500 dark:text-slate-400 uppercase tracking-widest text-right">{{ t('table.colPoints') }}</span>
                <span class="text-xs font-black text-slate-500 dark:text-slate-400 uppercase tracking-widest text-right">{{ t('rateTierInfo.scoreExampleTitle') }}</span>
              </div>
              <div class="divide-y divide-slate-100 dark:divide-slate-700">
                <div
                  v-for="(threshold, i) in SCORE_RATE_THRESHOLDS"
                  :key="i"
                  class="px-5 py-4 grid grid-cols-3 gap-4 hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors"
                >
                  <div class="flex items-center gap-2">
                    <div class="w-2 h-2 rounded-full shrink-0" :class="thresholdColor(threshold.points)"></div>
                    <span class="text-sm font-black text-slate-800 dark:text-slate-100 tabular-nums">{{ threshold.rate.toFixed(2) }}%</span>
                    <span v-if="threshold.rate === 100" class="text-[9px] font-black uppercase tracking-wider px-1.5 py-0.5 rounded bg-amber-500 text-white">PERFECT</span>
                  </div>
                  <div class="text-right">
                    <span class="text-sm font-black tabular-nums" :class="thresholdTextColor(threshold.points)">{{ threshold.points }} pt</span>
                  </div>
                  <div class="text-right">
                    <span class="text-xs font-bold text-slate-500 dark:text-slate-400 tabular-nums">{{ Math.round(threshold.rate / 100 * 3000).toLocaleString() }} / 3000</span>
                  </div>
                </div>
              </div>
            </div>

            <p class="text-[11px] font-bold text-slate-400 dark:text-slate-500 text-center">
              {{ t('rateTierInfo.notes1500') }}
            </p>
          </div>
        </div>
      </div>

      <!-- Footer -->
      <div class="px-8 py-5 bg-slate-50 dark:bg-slate-800/80 border-t border-slate-100 dark:border-slate-700/50 text-center transition-colors duration-200">
        <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500 uppercase tracking-widest">
          {{ t('rateTierInfo.footerDesc') }} • {{ new Date().toLocaleDateString() }}
        </p>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { useI18n } from '../composables/useI18n';
import { RATE_TIER_RANKS, SCORE_RATE_THRESHOLDS, getGroupedRateTierRanks } from '../utils/beatTier';
import RankIcon from './RankIcon.vue';

const { t } = useI18n();
defineEmits(['close']);

const activeTab = ref<'about' | 'table'>('about');

const groupedRanks = computed(() => getGroupedRateTierRanks());
const rankNames = ['Mythic', 'Ancient', 'Master', 'Elite', 'Commander', 'Veteran', 'Expert', 'Advanced', 'Intermediate', 'Novice'];

const getRankForTier = (name: string, tier: number) => {
  return groupedRanks.value[name]?.find(r => r.tier === tier);
};

function thresholdColor(points: number): string {
  if (points >= 256) return 'bg-amber-400';
  if (points >= 64) return 'bg-emerald-500';
  if (points >= 8) return 'bg-teal-500';
  if (points >= 2) return 'bg-cyan-500';
  return 'bg-slate-300';
}

function thresholdTextColor(points: number): string {
  if (points >= 256) return 'text-amber-500 dark:text-amber-400';
  if (points >= 64) return 'text-emerald-600 dark:text-emerald-400';
  if (points >= 8) return 'text-teal-600 dark:text-teal-400';
  if (points >= 2) return 'text-cyan-600 dark:text-cyan-400';
  return 'text-slate-500 dark:text-slate-400';
}
</script>

<style scoped>
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
}
.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: #e2e8f0;
  border-radius: 10px;
}
.custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background: #cbd5e1;
}

.animate-fade-in {
  animation: fadeIn 0.2s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
