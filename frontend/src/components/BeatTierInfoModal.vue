<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[110] bg-slate-50 dark:bg-slate-900 flex flex-col animate-fade-in transition-colors duration-200">
      <!-- Header -->
      <div class="px-8 py-6 border-b border-slate-100 dark:border-slate-700/50 flex justify-between items-center bg-white dark:bg-slate-800 sticky top-0 z-10 transition-colors duration-200">
        <div>
          <h3 class="text-2xl font-black text-slate-800 dark:text-slate-100">{{ t('beatTierInfo.title') }}</h3>
          <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500 mt-0.5 uppercase tracking-widest">{{ t('beatTierInfo.subtitle') }}</p>
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
              :class="['pb-4 text-sm font-black transition-all relative', activeTab === 'about' ? 'text-blue-600 dark:text-blue-400' : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300']"
            >
              {{ t('beatTierInfo.tabAbout') }}
              <div v-if="activeTab === 'about'" class="absolute bottom-0 left-0 right-0 h-1 bg-blue-600 dark:bg-blue-500 rounded-full"></div>
            </button>
            <button 
              @click="activeTab = 'songs'" 
              :class="['pb-4 text-sm font-black transition-all relative', activeTab === 'songs' ? 'text-blue-600 dark:text-blue-400' : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300']"
            >
              {{ t('beatTierInfo.tabSongs') }}
              <div v-if="activeTab === 'songs'" class="absolute bottom-0 left-0 right-0 h-1 bg-blue-600 dark:bg-blue-500 rounded-full"></div>
            </button>
          </div>
        </div>

        <div class="p-4 sm:p-8">
          <!-- About Tab -->
          <div v-if="activeTab === 'about'" class="space-y-8 animate-fade-in">
            <section>
              <h4 class="text-lg font-black text-slate-800 dark:text-slate-100 mb-3 flex items-center gap-2">
                <span class="w-1.5 h-6 bg-blue-600 dark:bg-blue-500 rounded-full"></span>
                {{ t('beatTierInfo.whatIsTitle') }}
              </h4>
              <p class="text-slate-600 dark:text-slate-300 leading-relaxed text-sm font-medium" v-html="t('beatTierInfo.whatIsDesc')"></p>
            </section>

            <section class="bg-slate-900 dark:bg-slate-950 rounded-3xl p-8 text-white shadow-2xl relative overflow-hidden border border-slate-700 dark:border-slate-800 transition-colors duration-200">
              <div class="absolute top-0 right-0 w-64 h-64 bg-blue-500/10 dark:bg-blue-400/5 rounded-full -translate-y-1/2 translate-x-1/2 blur-3xl"></div>
              <h4 class="text-[10px] font-black uppercase tracking-[0.2em] mb-6 text-slate-400 dark:text-slate-500">Calculation Formula</h4>
              <div class="flex flex-col md:flex-row items-center justify-between gap-8 relative z-10">
                <div class="flex-1 text-center md:text-left">
                  <p class="text-4xl font-black mb-2 tracking-tight text-blue-400 dark:text-blue-300">Beat-PT = Rate%^1.3 × Weight + Bonus</p>
                  <p class="text-xs font-bold text-slate-400 dark:text-slate-500 leading-relaxed" v-html="t('beatTierInfo.formulaDesc')"></p>
                </div>
                <div class="h-px md:h-20 w-full md:w-px bg-slate-700 dark:bg-slate-800"></div>
                <div class="flex-1 text-sm font-bold text-slate-300 dark:text-slate-400 leading-relaxed">
                  <p>• {{ t('beatTierInfo.weightDesc') }}</p>
                  <p class="text-blue-400/80 dark:text-blue-300/80 mt-1">{{ t('beatTierInfo.finalPointsDesc') }}</p>
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
                  
                  <!-- Legend & Special Ranks -->
                  <div class="flex items-center justify-center gap-12 border-b border-slate-200 dark:border-slate-800/50 pb-12">
                    <div v-if="groupedRanks['Legend']" class="flex flex-col items-center group">
                      <RankIcon :rank-name="'Legend'" size="lg" />
                      <div class="mt-6 text-center">
                        <p class="text-base font-black text-amber-500 uppercase tracking-widest mb-1">Legend</p>
                        <p class="text-sm font-bold text-slate-700 dark:text-slate-300 bg-slate-100 dark:bg-slate-800/50 px-3 py-1 rounded-full border border-slate-200 dark:border-slate-700">{{ groupedRanks['Legend'][0].minPoints.toLocaleString() }} pt</p>
                      </div>
                    </div>
                  </div>

                  <!-- Main Grid (Novice to Mythic) -->
                  <!-- On Mobile: Vertical List. On xl (1280px+): Horizontal Row -->
                  <div class="flex flex-col xl:flex-row justify-between gap-8 xl:gap-4 overflow-x-auto custom-scrollbar xl:overflow-visible pb-4 xl:pb-0">
                    <div v-for="name in rankNames" :key="name" class="flex flex-col xl:items-center bg-slate-50 dark:bg-slate-900/50 xl:bg-transparent rounded-2xl p-4 xl:p-0">
                      
                      <!-- Header Row for this Rank on Mobile, Top Header on Desktop -->
                      <div class="flex items-center xl:flex-col xl:space-y-6 mb-4 xl:mb-0">
                        <div class="hidden xl:block w-px h-12 bg-gradient-to-b from-transparent to-slate-200 dark:to-slate-800 xl:mb-6"></div>
                        <p class="text-base xl:text-xs font-black text-slate-700 dark:text-slate-300 xl:text-slate-500 xl:dark:text-slate-400 uppercase tracking-widest flex-1 xl:flex-none xl:h-4 xl:mb-4">{{ name }}</p>
                      </div>
                      
                      <!-- Tiers Flow: Horizontal on mobile, vertical on desktop -->
                      <div class="flex flex-row xl:flex-col items-center gap-4 xl:gap-0 xl:space-y-0 w-full overflow-x-auto xl:overflow-visible py-2 xl:py-0 custom-scrollbar">
                        <!-- Tiers 5 to 1 (Descending) -->
                        <div v-for="tier in 5" :key="tier" class="relative group shrink-0 xl:w-full">
                          <div v-if="getRankForTier(name, 6 - tier)" class="flex flex-row xl:flex-col items-center">
                             <!-- Small connecting lines -->
                            <div v-if="tier > 1" class="hidden xl:block w-px h-6 bg-slate-200 dark:bg-slate-800/50 mb-2"></div>
                            <div v-if="tier > 1" class="xl:hidden w-4 h-px bg-slate-200 dark:bg-slate-800/50 mr-4"></div>
                            
                            <div class="relative transition-all duration-300 transform group-hover:scale-110 group-hover:-translate-y-2">
                              <RankIcon :rank-name="name" :tier="6 - tier" size="md" />
                              <!-- Hover Tooltip -->
                              <div class="absolute bottom-full left-1/2 -translate-x-1/2 mb-3 opacity-0 group-hover:opacity-100 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 text-slate-800 dark:text-white p-3 rounded-xl text-xs whitespace-nowrap z-30 pointer-events-none transition-all shadow-xl dark:shadow-2xl">
                                <div class="flex flex-col items-center gap-1">
                                  <span class="font-black text-blue-600 dark:text-blue-400 text-sm">{{ name }} {{ 6 - tier }}</span>
                                  <span class="font-bold text-slate-600 dark:text-slate-300">{{ getRankForTier(name, 6 - tier)?.minPoints.toLocaleString() }} pt</span>
                                </div>
                                <!-- Tooltip Arrow -->
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

          <!-- Songs Tab -->
          <div v-else class="space-y-6 animate-fade-in h-full flex flex-col">
            <div class="flex flex-col sm:flex-row gap-4">
              <div class="relative flex-1">
                <input 
                  v-model="songSearch" 
                  type="text" 
                  :placeholder="t('beatTierInfo.songSearchPlaceholder')"
                  class="w-full pl-10 pr-4 py-2.5 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl text-sm font-medium focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400 focus:border-transparent outline-none transition-all shadow-sm text-slate-800 dark:text-slate-100 placeholder-slate-400 dark:placeholder-slate-500"
                />
                <svg class="w-5 h-5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 dark:text-slate-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                </svg>
              </div>
            </div>

            <div class="space-y-4">
              <div v-for="group in filteredSongGroups" :key="group.rank" class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden shadow-sm transition-colors duration-200">
                <div class="px-5 py-3 bg-slate-50 dark:bg-slate-700/50 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between transition-colors duration-200">
                  <div class="flex items-center gap-2">
                    <span class="text-xs font-black text-slate-500 dark:text-slate-400 uppercase tracking-widest">{{ t('beatTierInfo.unofficialDifficulty') }}</span>
                    <span class="text-lg font-black text-slate-800 dark:text-slate-100">{{ group.rank }}</span>
                  </div>
                  <div class="flex items-center gap-2">
                    <span class="text-xs font-bold text-slate-400 dark:text-slate-500">{{ t('beatTierInfo.weight') }}</span>
                    <span class="text-sm font-black text-blue-600 dark:text-blue-400">{{ group.weight }} pt</span>
                  </div>
                </div>
                <div class="p-4 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-x-6 gap-y-3 lg:gap-y-4">
                  <div 
                    v-for="song in group.songs" 
                    :key="song"
                    class="text-sm font-bold text-slate-700 dark:text-slate-300 flex items-center gap-2 truncate py-1"
                    :title="song"
                  >
                    <div class="w-1.5 h-1.5 shrink-0 rounded-full bg-slate-300 dark:bg-slate-600"></div>
                    {{ song }}
                  </div>
                </div>
              </div>
              <div v-if="filteredSongGroups.length === 0" class="py-20 text-center text-slate-400 dark:text-slate-500 font-bold">
                {{ t('beatTierInfo.noSongsFound') }}
              </div>
            </div>
          </div>
        </div>
      </div>
      
      <!-- Footer -->
      <div class="px-8 py-5 bg-slate-50 dark:bg-slate-800/80 border-t border-slate-100 dark:border-slate-700/50 text-center transition-colors duration-200">
        <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500 uppercase tracking-widest">
          {{ t('beatTierInfo.footerDesc') }} • {{ new Date().toLocaleDateString() }}
        </p>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { useI18n } from '../composables/useI18n';
import { WEIGHTS, getGroupedRanks } from '../utils/beatTier';
import diffTableRaw from '../data/difficulty_table.json';
import RankIcon from './RankIcon.vue';

const { t } = useI18n();
defineEmits(['close']);

const activeTab = ref<'about' | 'songs'>('about');
const songSearch = ref('');

const groupedRanks = computed(() => getGroupedRanks());
const rankNames = ['Mythic', 'Ancient', 'Master', 'Elite', 'Commander', 'Veteran', 'Expert', 'Advanced', 'Intermediate', 'Novice'];

const getRankForTier = (name: string, tier: number) => {
  return groupedRanks.value[name]?.find(r => r.tier === tier);
};

const songGroups = computed(() => {
  return diffTableRaw.ranks.map(r => ({
    rank: r.rank,
    weight: WEIGHTS[r.rank] || 0,
    songs: r.songs
  })).filter(g => g.weight > 0);
});

const filteredSongGroups = computed(() => {
  if (!songSearch.value) return songGroups.value;
  
  return songGroups.value.map(group => {
    const matchedSongs = group.songs.filter(s => 
      s.toLowerCase().includes(songSearch.value.toLowerCase())
    );
    return { ...group, songs: matchedSongs };
  }).filter(group => group.songs.length > 0);
});
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

.transition-hover {
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
}
</style>
