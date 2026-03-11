<template>
  <Teleport to="body">
    <div v-if="isOpen" class="fixed inset-0 z-[110] bg-slate-900/60 dark:bg-slate-950/80 backdrop-blur-sm flex items-center justify-center p-4 sm:p-6 animate-fade-in" @click.self="close">
      <div id="report-container" ref="reportContent" class="bg-white dark:bg-slate-900 w-full max-w-3xl rounded-3xl shadow-2xl flex flex-col overflow-hidden max-h-[90vh] animate-slide-up border border-slate-200 dark:border-slate-800">
        
        <!-- Header: Hero Section -->
        <div class="relative bg-gradient-to-r from-indigo-600 to-blue-600 px-6 py-4 overflow-hidden shrink-0">
          <div class="absolute inset-0 opacity-10 bg-[radial-gradient(ellipse_at_top_right,_var(--tw-gradient-stops))] from-white via-transparent to-transparent"></div>

          <button id="modal-close-btn" @click="close" class="absolute top-2 right-2 p-2 group z-50">
            <div class="text-white/60 group-hover:text-white bg-white/10 group-hover:bg-white/20 rounded-full w-7 h-7 flex items-center justify-center transition-colors">
              <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </div>
          </button>

          <div class="relative z-10 flex items-center gap-3">
            <div class="bg-white/20 p-2 rounded-lg backdrop-blur-md shrink-0">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>
            <div>
              <h2 class="text-lg font-black text-white tracking-tight leading-tight">プレイ成果レポート</h2>
              <p class="text-indigo-200 font-medium text-xs">CSVのアップロードが完了し、あなたの成長が記録されました！</p>
            </div>
          </div>
        </div>

        <!-- Scrollable Body -->
        <div id="report-body" class="flex-1 overflow-y-auto p-6 sm:p-8 bg-slate-50 dark:bg-slate-900">
          <div v-if="diffData" class="space-y-8 max-w-2xl mx-auto">
            
            <!-- Tier Up Notification -->
            <div v-if="diffData.oldTier && diffData.newTier && diffData.oldTier.minPoints < diffData.newTier.minPoints" class="bg-gradient-to-r from-amber-100 to-yellow-100 dark:from-yellow-900/40 dark:to-amber-800/40 border border-amber-300 dark:border-amber-700/50 p-6 rounded-2xl shadow-sm text-center transform hover:scale-[1.02] transition-transform duration-300 relative overflow-hidden">
              <div class="absolute inset-0 bg-yellow-200/50 dark:bg-yellow-500/10 mix-blend-overlay animate-pulse"></div>
              <p class="text-amber-700 dark:text-amber-400 font-bold uppercase tracking-widest text-sm mb-2 relative z-10">BEAT-TIER 昇格！</p>
              <div class="flex items-center justify-center gap-4 relative z-10">
                <span class="text-2xl font-black text-slate-500 dark:text-slate-400 line-through decoration-amber-400/50">{{ diffData.oldTier.name }}{{ diffData.oldTier.tier ? ' ' + diffData.oldTier.tier : '' }}</span>
                <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-amber-500 dark:text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="3">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
                </svg>
                <span class="text-3xl font-black text-amber-600 dark:text-amber-300">{{ diffData.newTier.name }}{{ diffData.newTier.tier ? ' ' + diffData.newTier.tier : '' }}</span>
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
                  <span class="text-lg sm:text-xl font-black text-slate-600 dark:text-slate-300">{{ diffData.oldTier?.name || '---' }}{{ diffData.oldTier?.tier ? ' ' + diffData.oldTier.tier : '' }}</span>
                </div>
                
                <div class="shrink-0 flex flex-col items-center justify-center pt-4">
                   <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-indigo-400 dark:text-indigo-500 animate-pulse" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
                  </svg>
                </div>

                <div class="flex flex-col items-center flex-1">
                  <span class="text-xs font-bold text-indigo-400 mb-1">今回 ({{ diffData.newTotalBeatPt.toFixed(1) }})</span>
                  <span class="text-lg sm:text-xl font-black whitespace-nowrap" :class="diffData.newTier?.color || 'text-slate-600'">{{ diffData.newTier?.name || '---' }}{{ diffData.newTier?.tier ? ' ' + diffData.newTier.tier : '' }}</span>
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
                      <div class="flex items-center gap-1 mb-0.5">
                        <span class="text-[10px] font-bold uppercase" :class="song.isInTop100 ? 'text-amber-500 dark:text-amber-400' : 'text-slate-400 dark:text-slate-500'">BEAT-PT</span>
                        <span v-if="song.isInTop100 !== undefined" class="text-[9px] font-black px-1 rounded" :class="song.isInTop100 ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400' : 'bg-slate-100 text-slate-500 dark:bg-slate-700/50 dark:text-slate-400'">
                          {{ song.isInTop100 ? 'TOP100' : '圏外' }}
                        </span>
                      </div>
                      <div class="flex items-baseline gap-1">
                        <span class="font-black text-lg" :class="song.isInTop100 ? 'text-amber-500 dark:text-amber-400' : 'text-indigo-400 dark:text-indigo-500'">+{{ song.newBeatPt.toFixed(1) }}</span>
                        <span class="text-xs font-bold" :class="song.isInTop100 ? 'text-amber-400 dark:text-amber-500' : 'text-indigo-400 dark:text-indigo-500'">pt</span>
                        <span class="text-xs font-bold text-slate-400 dark:text-slate-500">(+{{ song.beatPtIncrease.toFixed(1) }})</span>
                      </div>
                    </div>
                  </div>
                  
                </div>
              </div>
            </div>
          </div>
        </div>
        
        <!-- Footer -->
        <div id="modal-footer" class="p-4 sm:p-6 border-t border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 shrink-0 flex flex-col sm:flex-row gap-3">
          <button @click="shareOnX" :disabled="isSharing" class="flex-1 py-3.5 sm:py-4 bg-black hover:bg-slate-800 text-white font-bold rounded-xl transition-colors shadow-md flex items-center justify-center gap-2 disabled:opacity-50 text-sm sm:text-base">
            <template v-if="!isSharing">
              <svg class="w-4 h-4 sm:w-5 sm:h-5 fill-current" viewBox="0 0 24 24">
                <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 22.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.005 4.09H5.078z"/>
              </svg>
              画像付きでXにポスト
            </template>
            <template v-else>
              <svg class="animate-spin -ml-1 mr-2 h-4 w-4 sm:h-5 sm:w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
              </svg>
              画像生成中...
            </template>
          </button>
          <button @click="close" class="flex-1 py-3.5 sm:py-4 bg-slate-100 dark:bg-slate-800 hover:bg-slate-200 dark:hover:bg-slate-700 text-slate-800 dark:text-white font-bold rounded-xl transition-colors shadow-sm flex items-center justify-center gap-2 text-sm sm:text-base">
            ダッシュボードへ戻る
          </button>
        </div>
        
      </div>
    </div>

    <!-- Offscreen container for X image sharing (1080x1920px fixed size 9:16) -->
    <div 
      ref="shareContainer"
      class="fixed top-[-9999px] left-[0] bg-slate-50 dark:bg-slate-900 w-[1080px] h-[1920px] flex flex-col z-[-1] border-none overflow-hidden"
    >
      <div class="bg-gradient-to-br from-indigo-500 via-blue-600 to-indigo-700 p-12 text-center shrink-0">
        <h2 class="text-6xl font-black text-white tracking-tight mb-4 drop-shadow-md">
          プレイ成果レポート
        </h2>
        <p class="text-indigo-100 font-medium text-2xl">
          beat-seeker で新記録を達成しました！
        </p>
      </div>
      
      <div class="p-12 flex-1 flex flex-col bg-slate-50 dark:bg-slate-900" v-if="diffData">
        <!-- Total Points and Progress-->
        <div class="border-2 border-indigo-100 dark:border-indigo-900/50 rounded-3xl p-10 mb-10 bg-white dark:bg-slate-800 shadow-xl relative overflow-hidden shrink-0">
          <div class="absolute inset-0 bg-indigo-50/50 dark:bg-indigo-900/10 mix-blend-overlay"></div>
          
          <div class="flex items-center justify-between relative z-10 mb-8">
            <div>
              <p class="text-xl font-bold text-slate-500 dark:text-slate-400 mb-2">総BEAT-PT</p>
              <div class="flex items-baseline gap-3">
                <span class="text-7xl font-black text-slate-800 dark:text-slate-100">{{ diffData.newTotalBeatPt.toFixed(1) }}</span>
                <span class="text-3xl font-bold text-indigo-500">+{{ diffData.totalBeatPtIncrease.toFixed(1) }}</span>
              </div>
            </div>
            <div class="text-right">
              <p class="text-xl font-bold text-slate-500 dark:text-slate-400 mb-2">BEAT-TIER</p>
              <p class="text-6xl font-black" :class="diffData.newTier?.color">{{ diffData.newTier?.name }} {{ diffData.newTier?.tier || '' }}</p>
            </div>
          </div>
          
          <!-- Progress Bar -->
          <div v-if="nextRankData && nextRankData.nextRank" class="relative z-10">
            <div class="flex justify-between items-end mb-3">
              <span class="text-lg font-bold text-slate-500 dark:text-slate-400">NEXT TIER</span>
              <div class="flex items-baseline gap-2">
                <span class="text-3xl font-black" :class="nextRankData.nextRank.color">{{ nextRankData.nextRank.name }} {{ nextRankData.nextRank.tier || '' }}</span>
                <span class="text-lg font-bold text-slate-400">まであと <span class="text-indigo-500">{{ (nextRankData.nextRank.minPoints - diffData.newTotalBeatPt).toFixed(1) }}</span> pt</span>
              </div>
            </div>
            <div class="w-full h-6 bg-slate-100 dark:bg-slate-700/50 rounded-full overflow-hidden border border-slate-200 dark:border-slate-600">
              <div class="h-full bg-gradient-to-r from-indigo-500 to-blue-500 transition-all" :style="{ width: `${nextRankData.progress}%` }"></div>
            </div>
          </div>
        </div>

        <!-- Top Updated Songs (max 10) -->
        <div v-if="diffData.updatedSongs.length > 0" class="flex-1 flex flex-col">
           <h3 class="text-3xl font-black text-slate-800 dark:text-slate-200 mb-6 flex items-center gap-3 shrink-0">
              <span class="w-2.5 h-8 bg-emerald-500 rounded-full"></span>
              更新楽曲 (Top 10)
           </h3>
           <div class="space-y-4 flex-1">
             <div v-for="song in diffData.updatedSongs.slice(0, 10)" :key="song.title + song.difficulty" class="flex items-center justify-between bg-white dark:bg-slate-800 p-5 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm">
               <div>
                 <div class="flex items-center gap-2 mb-2">
                    <span class="px-3 py-1 rounded text-sm font-black border" :class="getDifficultyColorClass(song.difficulty)">{{ song.difficulty }}</span>
                    <span v-if="song.clearTypeImproved" class="text-sm font-black text-emerald-600 dark:text-emerald-400 bg-emerald-100 dark:bg-emerald-900/30 px-3 py-1 rounded border border-emerald-200 dark:border-emerald-800/50">LAMP UP!</span>
                 </div>
                 <p class="font-black text-2xl text-slate-800 dark:text-slate-100">{{ song.title }}</p>
                 <div v-if="song.clearTypeImproved" class="flex items-center gap-3 mt-1.5">
                   <span class="text-sm font-bold text-slate-500 dark:text-slate-400 line-through">{{ song.oldClearType }}</span>
                   <span class="text-sm font-black" :class="getClearTypeColor(song.newClearType)">→ {{ song.newClearType }}</span>
                 </div>
               </div>
               <div class="text-right flex justify-end gap-10 w-1/2">
                 <div v-if="song.scoreIncrease > 0" class="text-right">
                   <p class="text-sm font-bold text-slate-500 mb-1">EX SCORE</p>
                   <p class="font-black text-3xl text-slate-700 dark:text-slate-300">{{ song.newScore }} <span class="text-lg font-bold text-blue-500">(+{{ song.scoreIncrease }})</span></p>
                 </div>
                 <div v-if="song.beatPtIncrease > 0" class="text-right">
                   <div class="flex items-center justify-end gap-2 mb-1">
                     <p class="text-sm font-bold" :class="song.isInTop100 ? 'text-amber-500' : 'text-slate-500'">BEAT-PT</p>
                     <span v-if="song.isInTop100 !== undefined" class="text-xs font-black px-1.5 py-0.5 rounded" :class="song.isInTop100 ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-500'">{{ song.isInTop100 ? 'TOP100' : '圏外' }}</span>
                   </div>
                   <p class="font-black text-3xl" :class="song.isInTop100 ? 'text-amber-500' : 'text-indigo-400'">+{{ song.newBeatPt.toFixed(1) }} <span class="text-lg font-bold text-slate-400">(+{{ song.beatPtIncrease.toFixed(1) }})</span></p>
                 </div>
               </div>
             </div>
           </div>
           <div v-if="diffData.updatedSongs.length > 10" class="text-center mt-6 shrink-0">
             <span class="inline-block px-4 py-2 bg-slate-200 dark:bg-slate-700 rounded-full text-base font-bold text-slate-500 dark:text-slate-300">
               ...他 {{ diffData.updatedSongs.length - 10 }} 件の更新
             </span>
           </div>
        </div>
        <div v-else class="text-center py-12 text-slate-500 font-bold border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-2xl text-xl shrink-0">
          自己ベストの更新はありませんでした。
        </div>
      </div>
      
      <div class="bg-indigo-600 p-6 text-center text-white/90 font-black text-xl tracking-widest shrink-0">
        beat-seeker - IIDX Score Tracker
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import type { UploadDiffResult } from './../types/UploadDiff';
import { getNextRankInfo } from '../utils/beatTier';
import html2canvas from 'html2canvas';

const props = defineProps<{
  isOpen: boolean;
  diffData: UploadDiffResult | null;
}>();

const nextRankData = computed(() => {
  if (!props.diffData) return null;
  return getNextRankInfo(props.diffData.newTotalBeatPt);
});

const emit = defineEmits<{
  (e: 'close'): void;
}>();

const close = () => {
  emit('close');
};

const shareContainer = ref<HTMLElement | null>(null);
const isSharing = ref(false);

const shareOnX = async () => {
  if (!shareContainer.value || isSharing.value) return;
  isSharing.value = true;
  
  // Wait for Vue to render the loading state
  await new Promise(resolve => setTimeout(resolve, 50));
  
  try {
    const canvas = await html2canvas(shareContainer.value, {
      scale: 2,
      backgroundColor: document.documentElement.classList.contains('dark') ? '#0f172a' : '#ffffff', // slate-900 or white
      logging: false
    });

    canvas.toBlob(async (blob) => {
      if (!blob) throw new Error('Blob is null');
      
      const file = new File([blob], 'beat-seeker-report.png', { type: 'image/png' });
      const textParam = encodeURIComponent("beat-seekerでスコアを更新しました！\nhttps://beat-seeker-1.onrender.com \n#BeatSeeker");
      
      // Try Web Share API first (supported Safari/Mobile/Newer Windows Chrome)
      if (navigator.share && navigator.canShare && navigator.canShare({ files: [file] })) {
        try {
          await navigator.share({
            title: 'beat-seeker Report',
            text: "beat-seekerでスコアを更新しました！\nhttps://beat-seeker-1.onrender.com \n#BeatSeeker",
            files: [file]
          });
          isSharing.value = false;
          return; // Success via native share!
        } catch (e) {
          console.log('Share canceled or failed', e);
          // Fallback to clipboard if it fails without user cancellation
          if ((e as Error).name !== 'AbortError') {
              // Proceed to fallback
          } else {
              isSharing.value = false;
              return;
          }
        }
      }
      
      // Fallback: Clipboard Web API + Window Open
      try {
        await navigator.clipboard.write([
          new ClipboardItem({ 'image/png': blob })
        ]);
        alert("画像をクリップボードにコピーしました！\nX（Twitter）の投稿画面が開くので、そのまま画像を「貼り付け（Ctrl+V / Cmd+V）」してください。");
        window.open(`https://twitter.com/intent/tweet?text=${textParam}`, '_blank');
      } catch (e) {
        console.error('Clipboard copy failed:', e);
        // Deep fallback, create a download link so user can manually attach
        const downloadUrl = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = downloadUrl;
        a.download = 'beat-seeker-report.png';
        a.click();
        URL.revokeObjectURL(downloadUrl);
        alert("画像のコピーに失敗したため、画像をダウンロードしました。\nX（Twitter）の投稿画面が開きますので、ダウンロードした画像を添付してください。");
        window.open(`https://twitter.com/intent/tweet?text=${textParam}`, '_blank');
      }
      
      isSharing.value = false;
    }, 'image/png');
    
  } catch (error) {
    console.error('Share failed:', error);
    alert("画像の生成に失敗しました。");
    isSharing.value = false;
  }
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
