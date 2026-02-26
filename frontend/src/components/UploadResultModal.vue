<template>
  <Teleport to="body">
    <div v-if="isOpen" class="fixed inset-0 z-[110] bg-slate-900/60 dark:bg-slate-950/80 backdrop-blur-sm flex items-center justify-center p-4 sm:p-6 animate-fade-in" @click.self="close">
      <div class="bg-white dark:bg-slate-900 w-full max-w-3xl rounded-3xl shadow-2xl flex flex-col overflow-hidden max-h-[90vh] animate-slide-up border border-slate-200 dark:border-slate-800">
        
        <!-- Header: Hero Section -->
        <div class="relative bg-gradient-to-br from-indigo-500 via-blue-600 to-indigo-700 p-8 text-center overflow-hidden shrink-0">
          <div class="absolute inset-0 opacity-20 bg-[radial-gradient(ellipse_at_top_right,_var(--tw-gradient-stops))] from-white via-transparent to-transparent"></div>
          
          <button @click="close" class="absolute top-4 right-4 text-white/70 hover:text-white bg-black/10 hover:bg-black/20 rounded-full p-2 transition-colors">
            <svg class="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
          
          <div class="relative z-10 flex flex-col items-center">
            <div class="bg-white/20 p-3 rounded-full mb-4 backdrop-blur-md">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>
            <h2 class="text-3xl sm:text-4xl font-black text-white tracking-tight mb-2 drop-shadow-md">
              プレイ成果レポート
            </h2>
            <p class="text-indigo-100 font-medium text-sm sm:text-base">
              CSVのアップロードが完了し、あなたの成長が記録されました！
            </p>
          </div>
        </div>

        <!-- Scrollable Body -->
        <div class="flex-1 overflow-y-auto p-6 sm:p-8 bg-slate-50 dark:bg-slate-900">
          <div v-if="diffData" class="space-y-8 max-w-2xl mx-auto">
            
            <!-- Tier Up Notification -->
            <div v-if="diffData.oldTier && diffData.newTier && diffData.oldTier.tier < diffData.newTier.tier" class="bg-gradient-to-r from-amber-100 to-yellow-100 dark:from-yellow-900/40 dark:to-amber-800/40 border border-amber-300 dark:border-amber-700/50 p-6 rounded-2xl shadow-sm text-center transform hover:scale-[1.02] transition-transform duration-300 relative overflow-hidden">
              <div class="absolute inset-0 bg-yellow-200/50 dark:bg-yellow-500/10 mix-blend-overlay animate-pulse"></div>
              <p class="text-amber-700 dark:text-amber-400 font-bold uppercase tracking-widest text-sm mb-2 relative z-10">BEAT-TIER 昇格！</p>
              <div class="flex items-center justify-center gap-4 relative z-10">
                <span class="text-2xl font-black text-slate-500 dark:text-slate-400 line-through decoration-amber-400/50">{{ diffData.oldTier.name }}</span>
                <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-amber-500 dark:text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="3">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
                </svg>
                <span class="text-3xl font-black text-amber-600 dark:text-amber-300">{{ diffData.newTier.name }}</span>
              </div>
            </div>

            <!-- Total Points Change Details -->
            <div class="flex flex-col items-center bg-white dark:bg-slate-800 p-8 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 relative overflow-hidden">
              <div class="absolute inset-0 bg-indigo-50/50 dark:bg-indigo-900/10 mix-blend-overlay"></div>
              
              <p class="text-sm font-bold text-slate-500 dark:text-slate-400 uppercase tracking-widest mb-4 relative z-10">総合 BEAT-TIER の変化</p>
              
              <!-- Tier Context -->
              <div class="flex items-center justify-center gap-3 sm:gap-6 w-full mb-6 relative z-10">
                <div class="flex flex-col items-center flex-1">
                  <span class="text-xs font-bold text-slate-400 mb-1">前回 ({{ diffData.oldTotalBeatPt.toFixed(1) }})</span>
                  <span class="text-lg sm:text-xl font-black text-slate-600 dark:text-slate-300">{{ diffData.oldTier?.name || '---' }}</span>
                </div>
                
                <div class="shrink-0 flex flex-col items-center justify-center pt-4">
                   <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-indigo-400 dark:text-indigo-500 animate-pulse" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
                  </svg>
                </div>

                <div class="flex flex-col items-center flex-1">
                  <span class="text-xs font-bold text-indigo-400 mb-1">今回 ({{ diffData.newTotalBeatPt.toFixed(1) }})</span>
                  <span class="text-lg sm:text-xl font-black whitespace-nowrap" :class="diffData.newTier?.color || 'text-slate-600'">{{ diffData.newTier?.name || '---' }}</span>
                </div>
              </div>
              
              <!-- PT Increase -->
              <div class="flex items-baseline gap-2 bg-slate-50 dark:bg-slate-900/50 px-6 py-3 rounded-xl border border-slate-100 dark:border-slate-800 relative z-10">
                <span class="text-sm font-bold text-slate-500 dark:text-slate-400 uppercase mr-2">増加量:</span>
                <span class="text-4xl font-black tracking-tight" :class="diffData.totalBeatPtIncrease > 0 ? 'text-indigo-600 dark:text-indigo-400' : 'text-slate-400 dark:text-slate-500'">
                  {{ diffData.totalBeatPtIncrease > 0 ? '+' : '' }}{{ diffData.totalBeatPtIncrease.toFixed(1) }}
                </span>
                <span class="text-lg font-bold text-indigo-500">pt</span>
              </div>
            </div>

            <!-- Updated Songs List -->
            <div>
              <h3 class="flex items-center gap-2 text-lg font-black text-slate-800 dark:text-slate-200 mb-4 px-2">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-emerald-500" viewBox="0 0 20 20" fill="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                </svg>
                更新された楽曲 <span class="bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300 text-xs font-bold px-2 py-0.5 rounded-full ml-1">{{ diffData.updatedSongs.length }}件</span>
              </h3>
              
              <div v-if="diffData.updatedSongs.length === 0" class="text-center p-8 bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 border-dashed">
                <p class="text-slate-500 dark:text-slate-400 font-medium">自己ベストの更新はありませんでした。</p>
              </div>
              
              <div v-else class="space-y-3">
                <div v-for="song in diffData.updatedSongs" :key="song.title + song.difficulty" class="bg-white dark:bg-slate-800 p-4 sm:p-5 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 flex flex-col sm:flex-row sm:items-center justify-between gap-4 transition-colors">
                  
                  <div class="flex flex-col gap-1 overflow-hidden">
                    <div class="flex items-center gap-2 flex-wrap">
                      <span class="px-2 py-0.5 rounded text-[10px] font-black tracking-wider border" :class="getDifficultyColorClass(song.difficulty)">
                        {{ song.difficulty }}
                      </span>
                      <span v-if="song.clearTypeImproved" class="px-2 py-0.5 bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400 text-[10px] font-black tracking-wider rounded border border-emerald-200 dark:border-emerald-800/50">LAMP UP!</span>
                    </div>
                    <h4 class="font-black text-slate-800 dark:text-slate-100 text-lg truncate" :title="song.title">{{ song.title }}</h4>
                    
                    <div v-if="song.clearTypeImproved" class="flex items-center gap-2 mt-1">
                      <span class="text-xs font-bold text-slate-500 dark:text-slate-400 line-through">{{ song.oldClearType }}</span>
                      <svg xmlns="http://www.w3.org/2000/svg" class="h-3 w-3 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M14 5l7 7m0 0l-7 7m7-7H3" /></svg>
                      <span class="text-xs font-black" :class="getClearTypeColor(song.newClearType)">{{ song.newClearType }}</span>
                    </div>
                  </div>
                  
                  <div class="flex items-end sm:items-center justify-between sm:justify-end gap-6 shrink-0 bg-slate-50 dark:bg-slate-900/50 p-3 sm:p-0 sm:bg-transparent sm:dark:bg-transparent border border-slate-100 dark:border-slate-800 sm:border-0 rounded-xl">
                    <div v-if="song.scoreIncrease > 0" class="flex flex-col items-start sm:items-end">
                      <span class="text-[10px] font-bold text-slate-400 dark:text-slate-500 uppercase">EX SCORE</span>
                      <div class="flex items-baseline gap-1">
                        <span class="font-black text-slate-700 dark:text-slate-200 text-lg">{{ song.newScore }}</span>
                        <span class="text-xs font-bold text-blue-500 dark:text-blue-400">(+{{ song.scoreIncrease }})</span>
                      </div>
                    </div>
                    
                    <div v-if="song.beatPtIncrease > 0" class="flex flex-col items-end">
                      <span class="text-[10px] font-bold text-slate-400 dark:text-slate-500 uppercase">BEAT-PT</span>
                      <div class="flex items-baseline gap-1">
                        <span class="font-black text-indigo-600 dark:text-indigo-400 text-lg">+{{ song.beatPtIncrease.toFixed(1) }}</span>
                        <span class="text-xs font-bold text-indigo-400 dark:text-indigo-500">pt</span>
                      </div>
                    </div>
                  </div>
                  
                </div>
              </div>
            </div>
          </div>
        </div>
        
        <!-- Footer -->
        <div class="p-6 border-t border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 shrink-0">
          <button @click="close" class="w-full py-4 bg-slate-800 dark:bg-slate-700 hover:bg-slate-900 dark:hover:bg-slate-600 text-white font-bold rounded-xl transition-colors shadow-md flex items-center justify-center gap-2">
            ダッシュボードへ戻る
          </button>
        </div>
        
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import type { UploadDiffResult } from './../types/UploadDiff';

const props = defineProps<{
  isOpen: boolean;
  diffData: UploadDiffResult | null;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
}>();

const close = () => {
  emit('close');
};

const getDifficultyColorClass = (difficulty: string) => {
  switch (difficulty.toLowerCase()) {
    case 'beginner': return 'text-emerald-700 bg-emerald-100 border-emerald-300 dark:bg-emerald-900/30 dark:text-emerald-400 dark:border-emerald-800/50';
    case 'normal': return 'text-blue-700 bg-blue-100 border-blue-300 dark:bg-blue-900/30 dark:text-blue-400 dark:border-blue-800/50';
    case 'hyper': return 'text-amber-700 bg-amber-100 border-amber-300 dark:bg-amber-900/30 dark:text-amber-400 dark:border-amber-800/50';
    case 'another': return 'text-red-700 bg-red-100 border-red-300 dark:bg-red-900/30 dark:text-red-400 dark:border-red-800/50';
    case 'leggendaria': return 'text-purple-700 bg-purple-100 border-purple-300 dark:bg-purple-900/30 dark:text-purple-400 dark:border-purple-800/50';
    default: return 'text-slate-700 bg-slate-100 border-slate-300 dark:bg-slate-800 dark:text-slate-300 dark:border-slate-700';
  }
};

const getClearTypeColor = (type: string) => {
    switch (type) {
        case 'FULLCOMBO CLEAR': return 'text-cyan-500 dark:text-cyan-400';
        case 'EX HARD CLEAR': return 'text-yellow-500 dark:text-yellow-400';
        case 'HARD CLEAR': return 'text-red-500 dark:text-red-400';
        case 'CLEAR': return 'text-blue-500 dark:text-blue-400';
        case 'EASY CLEAR': return 'text-green-500 dark:text-green-400';
        case 'ASSIST CLEAR': return 'text-purple-500 dark:text-purple-400';
        case 'FAILED': return 'text-orange-500 dark:text-orange-400';
        default: return 'text-slate-500 dark:text-slate-400';
    }
};
</script>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.3s ease-out forwards;
}

.animate-slide-up {
  animation: slideUp 0.4s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes slideUp {
  from { opacity: 0; transform: translateY(20px) scale(0.98); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}
</style>
