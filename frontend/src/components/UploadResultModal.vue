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
              <h2 class="text-lg font-black text-white tracking-tight leading-tight">{{ t('report.title') }}</h2>
              <p class="text-indigo-200 font-medium text-xs">{{ t('report.subtitle') }}</p>
            </div>
          </div>
        </div>

        <!-- Scrollable Body -->
        <div id="report-body" class="flex-1 overflow-y-auto p-6 sm:p-8 bg-slate-50 dark:bg-slate-900">
          <div v-if="diffData" class="space-y-8 max-w-2xl mx-auto">
            
            <!-- Tier Up Notification -->
            <div v-if="diffData.oldTier && diffData.newTier && diffData.oldTier.minPoints < diffData.newTier.minPoints" class="bg-gradient-to-r from-amber-100 to-yellow-100 dark:from-yellow-900/40 dark:to-amber-800/40 border border-amber-300 dark:border-amber-700/50 p-6 rounded-2xl shadow-sm text-center transform hover:scale-[1.02] transition-transform duration-300 relative overflow-hidden">
              <div class="absolute inset-0 bg-yellow-200/50 dark:bg-yellow-500/10 mix-blend-overlay animate-pulse"></div>
              <p class="text-amber-700 dark:text-amber-400 font-bold uppercase tracking-widest text-sm mb-2 relative z-10">{{ t('report.tierUp') }}</p>
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

              <p class="text-sm font-bold text-slate-500 dark:text-slate-400 uppercase tracking-widest mb-4 relative z-10">{{ t('report.beatTierChange') }}</p>
              
              <!-- Tier Context -->
              <div class="flex items-center justify-center gap-3 sm:gap-6 w-full mb-6 relative z-10">
                <div class="flex flex-col items-center flex-1">
                  <span class="text-xs font-bold text-slate-400 mb-1">{{ t('report.previous') }} ({{ diffData.oldTotalBeatPt.toFixed(1) }})</span>
                  <span class="text-lg sm:text-xl font-black text-slate-600 dark:text-slate-300">{{ diffData.oldTier?.name || '---' }}{{ diffData.oldTier?.tier ? ' ' + diffData.oldTier.tier : '' }}</span>
                </div>
                
                <div class="shrink-0 flex flex-col items-center justify-center pt-4">
                   <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-indigo-400 dark:text-indigo-500 animate-pulse" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
                  </svg>
                </div>

                <div class="flex flex-col items-center flex-1">
                  <span class="text-xs font-bold text-indigo-400 mb-1">{{ t('report.current') }} ({{ diffData.newTotalBeatPt.toFixed(1) }})</span>
                  <span class="text-lg sm:text-xl font-black whitespace-nowrap" :class="diffData.newTier?.color || 'text-slate-600'">{{ diffData.newTier?.name || '---' }}{{ diffData.newTier?.tier ? ' ' + diffData.newTier.tier : '' }}</span>
                </div>
              </div>
              
              <!-- PT Increase -->
              <div class="flex items-baseline gap-2 bg-slate-50 dark:bg-slate-900/50 px-6 py-3 rounded-xl border border-slate-100 dark:border-slate-800 relative z-10">
                <span class="text-sm font-bold text-slate-500 dark:text-slate-400 uppercase mr-2">{{ t('report.increase') }}:</span>
                <span class="text-4xl font-black tracking-tight" :class="diffData.totalBeatPtIncrease > 0 ? 'text-indigo-600 dark:text-indigo-400' : 'text-slate-400 dark:text-slate-500'">
                  {{ diffData.totalBeatPtIncrease > 0 ? '+' : '' }}{{ diffData.totalBeatPtIncrease.toFixed(1) }}
                </span>
                <span class="text-lg font-bold text-indigo-500">{{ t('common.pt') }}</span>
              </div>
            </div>

            <!-- Rate-Tier Change -->
            <div v-if="showRateTier && diffData.newTotalRatePt > 0 && (diffData.newTotalRatePt - diffData.oldTotalRatePt) >= 0.1" class="flex flex-col items-center bg-white dark:bg-slate-800 p-8 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 relative overflow-hidden">
              <div class="absolute inset-0 bg-emerald-50/50 dark:bg-emerald-900/10 mix-blend-overlay"></div>

              <p class="text-sm font-bold text-slate-500 dark:text-slate-400 uppercase tracking-widest mb-4 relative z-10">{{ t('report.rateTierChange') }}</p>

              <div class="flex items-center justify-center gap-3 sm:gap-6 w-full mb-6 relative z-10">
                <div class="flex flex-col items-center flex-1">
                  <span class="text-xs font-bold text-slate-400 mb-1">{{ t('report.previous') }} ({{ diffData.oldTotalRatePt.toFixed(1) }})</span>
                  <span class="text-lg sm:text-xl font-black text-slate-600 dark:text-slate-300">{{ diffData.oldRateTier?.name || '---' }}{{ diffData.oldRateTier?.tier ? ' ' + diffData.oldRateTier.tier : '' }}</span>
                </div>
                <div class="shrink-0 flex flex-col items-center justify-center pt-4">
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-emerald-400 dark:text-emerald-500 animate-pulse" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
                  </svg>
                </div>
                <div class="flex flex-col items-center flex-1">
                  <span class="text-xs font-bold text-emerald-400 mb-1">{{ t('report.current') }} ({{ diffData.newTotalRatePt.toFixed(1) }})</span>
                  <span class="text-lg sm:text-xl font-black whitespace-nowrap" :class="diffData.newRateTier?.color || 'text-slate-600'">{{ diffData.newRateTier?.name || '---' }}{{ diffData.newRateTier?.tier ? ' ' + diffData.newRateTier.tier : '' }}</span>
                </div>
              </div>

              <div class="flex items-baseline gap-2 bg-slate-50 dark:bg-slate-900/50 px-6 py-3 rounded-xl border border-slate-100 dark:border-slate-800 relative z-10">
                <span class="text-sm font-bold text-slate-500 dark:text-slate-400 uppercase mr-2">{{ t('report.increase') }}:</span>
                <span class="text-4xl font-black tracking-tight" :class="diffData.newTotalRatePt - diffData.oldTotalRatePt > 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400 dark:text-slate-500'">
                  {{ diffData.newTotalRatePt - diffData.oldTotalRatePt > 0 ? '+' : '' }}{{ (diffData.newTotalRatePt - diffData.oldTotalRatePt).toFixed(1) }}
                </span>
                <span class="text-lg font-bold text-emerald-500">{{ t('common.pt') }}</span>
              </div>
            </div>

            <!-- Updated Songs List -->
            <div>
              <h3 class="flex items-center gap-2 text-lg font-black text-slate-800 dark:text-slate-200 mb-4 px-2">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-emerald-500" viewBox="0 0 20 20" fill="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                </svg>
                {{ t('report.updatedSongs') }} <span class="bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300 text-xs font-bold px-2 py-0.5 rounded-full ml-1">{{ t('report.otherUpdates', { n: diffData.updatedSongs.length }) }}</span>
              </h3>
              
              <div v-if="diffData.updatedSongs.length === 0" class="text-center p-8 bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 border-dashed">
                <p class="text-slate-500 dark:text-slate-400 font-medium">{{ t('report.noUpdates') }}</p>
              </div>
              
              <div v-else class="space-y-2">
                <div v-for="song in sortedUpdatedSongs" :key="song.title + song.difficulty" class="bg-white dark:bg-slate-800 px-4 py-3 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors">

                  <!-- Row 1: Difficulty badge + LAMP UP + grade labels -->
                  <div class="flex items-center gap-1.5 mb-1 flex-wrap">
                    <span class="px-1.5 py-0.5 rounded text-[9px] font-black tracking-wider border shrink-0" :class="getDifficultyColorClass(song.difficulty)">
                      {{ song.difficulty }}
                    </span>
                    <span v-if="song.songRank" class="px-1.5 py-0.5 rounded text-[9px] font-black border shrink-0"
                      :class="song.songRank === 1 ? 'bg-amber-100 text-amber-700 border-amber-300 dark:bg-amber-900/30 dark:text-amber-400 dark:border-amber-700' : 'bg-slate-100 text-slate-600 border-slate-300 dark:bg-slate-700 dark:text-slate-300 dark:border-slate-600'"
                    >#{{ song.songRank }}<span class="font-medium">/{{ song.songRankTotal }}</span></span>
                    <span v-if="song.clearTypeImproved" class="px-1.5 py-0.5 bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400 text-[9px] font-black rounded border border-emerald-200 dark:border-emerald-800/50">{{ t('report.lampUp') }}</span>
                    <template v-if="song.maxScore > 0">
                      <template v-for="info in [getScoreGradeInfo(song.newScore, song.maxScore)]" :key="'grade'">
                        <span class="text-[9px] font-black tabular-nums px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300">
                          {{ info.fromMax }}
                        </span>
                        <span class="text-[9px] font-black tabular-nums px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-700" :class="info.gradeColor">
                          {{ info.grade }}
                        </span>
                        <span v-if="info.nextGrade" class="text-[9px] font-black tabular-nums px-1.5 py-0.5 rounded bg-slate-50 dark:bg-slate-700/50 text-slate-400 dark:text-slate-500 border border-slate-200 dark:border-slate-600">
                          {{ info.nextGrade.name }}{{ info.nextGrade.gap }}
                        </span>
                      </template>
                    </template>
                    <span v-if="song.isInRateTop100 && song.newRatePt > 0" class="text-[9px] font-black px-1.5 py-0.5 rounded bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 border border-emerald-200 dark:border-emerald-800/50">RATE TOP100</span>
                  </div>

                  <!-- Row 2: Title + scores -->
                  <div class="flex items-baseline justify-between gap-2">
                    <h4 class="font-black text-slate-800 dark:text-slate-100 text-base leading-tight" :title="song.title">{{ song.title }}</h4>
                    <div class="flex items-baseline gap-2 shrink-0 text-right">
                      <span v-if="song.scoreIncrease > 0" class="text-xs font-bold text-slate-600 dark:text-slate-300 whitespace-nowrap">{{ song.newScore }}<span class="text-blue-500 dark:text-blue-400">(+{{ song.scoreIncrease }})</span></span>
                      <span v-if="song.beatPtIncrease > 0" class="text-xs font-black whitespace-nowrap" :class="song.isInTop100 ? 'text-amber-500 dark:text-amber-400' : 'text-indigo-400 dark:text-indigo-500'">+{{ song.newBeatPt.toFixed(1) }}pt</span>
                    </div>
                  </div>

                  <!-- Row 3: LAMP change (if any) -->
                  <div v-if="song.clearTypeImproved" class="flex items-center gap-1.5 mt-0.5">
                    <span class="text-[10px] font-bold text-slate-400 line-through">{{ song.oldClearType }}</span>
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-2.5 w-2.5 text-slate-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M14 5l7 7m0 0l-7 7m7-7H3" /></svg>
                    <span class="text-[10px] font-black" :class="getClearTypeColor(song.newClearType)">{{ song.newClearType }}</span>
                  </div>

                  <!-- Row 4: Vote buttons -->
                  <div class="flex flex-wrap gap-1 mt-2 pt-2 border-t border-slate-100 dark:border-slate-700/50">
                    <button
                      v-for="opt in optionTypes"
                      :key="opt.value"
                      @click="castVote(song.title, song.difficulty, opt.value)"
                      :disabled="votingKey === (song.title + song.difficulty)"
                      class="px-2 py-1 rounded-md text-[10px] font-bold border transition-all disabled:opacity-50 flex items-center gap-0.5"
                      :class="getVoteClass(song.title, song.difficulty, opt.value, opt)"
                    >
                      {{ opt.icon }} {{ opt.label }}<span v-if="songVotes[song.title + song.difficulty]?.myVote === opt.value"> ✔</span>
                    </button>
                  </div>

                </div>
              </div>
            </div>
          </div>
        </div>
        
        <!-- Footer -->
        <div id="modal-footer" class="p-4 sm:p-6 border-t border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 shrink-0 flex flex-col sm:flex-row gap-3">
          <button @click="openShareOptions" :disabled="isSharing" class="flex-1 py-3.5 sm:py-4 bg-black hover:bg-slate-800 text-white font-bold rounded-xl transition-colors shadow-md flex items-center justify-center gap-2 disabled:opacity-50 text-sm sm:text-base">
            <template v-if="!isSharing">
              <svg class="w-4 h-4 sm:w-5 sm:h-5 fill-current" viewBox="0 0 24 24">
                <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 22.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.005 4.09H5.078z"/>
              </svg>
              {{ t('report.shareX') }}
            </template>
            <template v-else>
              <svg class="animate-spin -ml-1 mr-2 h-4 w-4 sm:h-5 sm:w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
              </svg>
              {{ t('report.generatingImage') }}
            </template>
          </button>
          <button @click="close" class="flex-1 py-3.5 sm:py-4 bg-slate-100 dark:bg-slate-800 hover:bg-slate-200 dark:hover:bg-slate-700 text-slate-800 dark:text-white font-bold rounded-xl transition-colors shadow-sm flex items-center justify-center gap-2 text-sm sm:text-base">
            {{ t('report.backToDashboard') }}
          </button>
        </div>

        <!-- Image Output Options Modal -->
        <div v-if="isShareOptionsOpen" class="absolute inset-0 z-50 bg-slate-900/70 backdrop-blur-sm flex items-end sm:items-center justify-center p-4 rounded-3xl" @click.self="isShareOptionsOpen = false">
          <div class="bg-white dark:bg-slate-800 w-full max-w-sm rounded-2xl shadow-2xl border border-slate-200 dark:border-slate-700 overflow-hidden">
            <div class="px-6 py-4 border-b border-slate-100 dark:border-slate-700">
              <h3 class="font-black text-slate-800 dark:text-slate-100 text-base">{{ t('report.outputOptions') }}</h3>
              <p class="text-xs text-slate-500 dark:text-slate-400 mt-0.5">{{ t('report.outputOptionsSub') }}</p>
            </div>
            <div class="p-4 space-y-2">
              <button
                @click="shareSortMode = 'beat'"
                class="w-full flex items-center gap-3 px-4 py-3 rounded-xl border-2 transition-all text-left"
                :class="shareSortMode === 'beat' ? 'border-indigo-500 bg-indigo-50 dark:bg-indigo-900/30' : 'border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700/50'"
              >
                <div class="w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0" :class="shareSortMode === 'beat' ? 'border-indigo-500' : 'border-slate-300 dark:border-slate-600'">
                  <div v-if="shareSortMode === 'beat'" class="w-2.5 h-2.5 rounded-full bg-indigo-500"></div>
                </div>
                <div>
                  <p class="font-black text-sm" :class="shareSortMode === 'beat' ? 'text-indigo-700 dark:text-indigo-300' : 'text-slate-700 dark:text-slate-200'">{{ t('report.sortByBeatPt') }}</p>
                  <p class="text-[11px] text-slate-500 dark:text-slate-400">{{ t('report.sortByBeatPtDesc') }}</p>
                </div>
              </button>
              <button
                @click="shareSortMode = 'rate'"
                class="w-full flex items-center gap-3 px-4 py-3 rounded-xl border-2 transition-all text-left"
                :class="shareSortMode === 'rate' ? 'border-emerald-500 bg-emerald-50 dark:bg-emerald-900/30' : 'border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700/50'"
              >
                <div class="w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0" :class="shareSortMode === 'rate' ? 'border-emerald-500' : 'border-slate-300 dark:border-slate-600'">
                  <div v-if="shareSortMode === 'rate'" class="w-2.5 h-2.5 rounded-full bg-emerald-500"></div>
                </div>
                <div>
                  <p class="font-black text-sm" :class="shareSortMode === 'rate' ? 'text-emerald-700 dark:text-emerald-300' : 'text-slate-700 dark:text-slate-200'">{{ t('report.sortByRatePt') }}</p>
                  <p class="text-[11px] text-slate-500 dark:text-slate-400">{{ t('report.sortByRatePtDesc') }}</p>
                </div>
              </button>
            </div>
            <div class="px-4 pb-4 flex gap-2">
              <button @click="isShareOptionsOpen = false" class="flex-1 py-2.5 bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 font-bold rounded-xl text-sm transition-colors">
                {{ t('common.cancel') }}
              </button>
              <button @click="confirmShare" class="flex-1 py-2.5 bg-black hover:bg-slate-800 text-white font-bold rounded-xl text-sm transition-colors flex items-center justify-center gap-1.5">
                <svg class="w-4 h-4 fill-current" viewBox="0 0 24 24">
                  <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 22.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.005 4.09H5.078z"/>
                </svg>
                {{ t('report.generateAndShare') }}
              </button>
            </div>
          </div>
        </div>
        
      </div>
    </div>

    <!-- Offscreen container for X image sharing (1080x1920px fixed size 9:16) -->
    <div 
      ref="shareContainer"
      class="fixed -top-[2000px] -left-[2000px] bg-slate-50 dark:bg-slate-900 w-[1080px] h-[1920px] flex flex-col z-[-1] border-none overflow-hidden pointer-events-none"
    >
      <div class="bg-gradient-to-br from-indigo-500 via-blue-600 to-indigo-700 p-12 text-center shrink-0">
        <h2 class="text-6xl font-black text-white tracking-tight mb-4 drop-shadow-md leading-tight">
          {{ t('report.title') }}
        </h2>
        <p class="text-indigo-100 font-medium text-2xl">
          {{ t('report.newRecord') }}
        </p>
      </div>
      
      <div class="p-12 flex-1 flex flex-col bg-slate-50 dark:bg-slate-900" v-if="diffData">
        <!-- Beat-Tier + Rate-Tier summary row -->
        <div class="grid gap-8 mb-10 shrink-0" :class="showRateTier ? 'grid-cols-2' : 'grid-cols-1 max-w-sm mx-auto w-full'">
          <!-- BEAT-TIER -->
          <div class="border-2 border-indigo-100 dark:border-indigo-900/50 rounded-3xl p-8 bg-white dark:bg-slate-800 shadow-xl relative overflow-hidden">
            <div class="absolute inset-0 bg-indigo-50/50 dark:bg-indigo-900/10 mix-blend-overlay"></div>
            <div class="relative z-10 mb-6">
              <p class="text-xl font-bold text-slate-500 dark:text-slate-400 mb-2">{{ t('common.beatPt') }}</p>
              <div class="flex items-baseline gap-3">
                <span class="text-5xl font-black text-slate-800 dark:text-slate-100 leading-tight">{{ diffData.newTotalBeatPt.toFixed(1) }}</span>
                <span class="text-2xl font-bold text-indigo-500">+{{ diffData.totalBeatPtIncrease.toFixed(1) }}</span>
              </div>
            </div>
            <div class="relative z-10 mb-6">
              <p class="text-xl font-bold text-slate-500 dark:text-slate-400 mb-1">BEAT-TIER</p>
              <p class="text-4xl font-black leading-tight" :class="diffData.newTier?.color">{{ diffData.newTier?.name }} {{ diffData.newTier?.tier || '' }}</p>
            </div>
            <div v-if="nextRankData && nextRankData.nextRank" class="relative z-10">
              <div class="flex justify-between items-end mb-2">
                <span class="text-base font-bold text-slate-500">NEXT</span>
                <span class="text-xl font-black" :class="nextRankData.nextRank.color">{{ nextRankData.nextRank.name }} {{ nextRankData.nextRank.tier || '' }}</span>
              </div>
              <div class="w-full h-4 bg-slate-100 dark:bg-slate-700/50 rounded-full overflow-hidden border border-slate-200 dark:border-slate-600">
                <div class="h-full bg-gradient-to-r from-indigo-500 to-blue-500" :style="{ width: `${nextRankData.progress}%` }"></div>
              </div>
            </div>
          </div>
          <!-- RATE-TIER -->
          <div v-if="showRateTier" class="border-2 border-emerald-100 dark:border-emerald-900/50 rounded-3xl p-8 bg-white dark:bg-slate-800 shadow-xl relative overflow-hidden">
            <div class="absolute inset-0 bg-emerald-50/50 dark:bg-emerald-900/10 mix-blend-overlay"></div>
            <div class="relative z-10 mb-6">
              <p class="text-xl font-bold text-slate-500 dark:text-slate-400 mb-2">{{ t('common.ratePt') }}</p>
              <div class="flex items-baseline gap-3">
                <span class="text-5xl font-black text-slate-800 dark:text-slate-100 leading-tight">{{ (diffData.newTotalRatePt ?? 0).toFixed(1) }}</span>
                <span class="text-2xl font-bold text-emerald-500">{{ (diffData.newTotalRatePt ?? 0) - (diffData.oldTotalRatePt ?? 0) > 0 ? '+' : '' }}{{ ((diffData.newTotalRatePt ?? 0) - (diffData.oldTotalRatePt ?? 0)).toFixed(1) }}</span>
              </div>
            </div>
            <div class="relative z-10 mb-6">
              <p class="text-xl font-bold text-slate-500 dark:text-slate-400 mb-1">RATE-TIER</p>
              <p class="text-4xl font-black leading-tight" :class="diffData.newRateTier?.color">{{ diffData.newRateTier?.name }} {{ diffData.newRateTier?.tier || '' }}</p>
            </div>
            <div v-if="nextRateTierData && nextRateTierData.nextRank" class="relative z-10">
              <div class="flex justify-between items-end mb-2">
                <span class="text-base font-bold text-slate-500">NEXT</span>
                <span class="text-xl font-black" :class="nextRateTierData.nextRank.color">{{ nextRateTierData.nextRank.name }} {{ nextRateTierData.nextRank.tier || '' }}</span>
              </div>
              <div class="w-full h-4 bg-slate-100 dark:bg-slate-700/50 rounded-full overflow-hidden border border-slate-200 dark:border-slate-600">
                <div class="h-full bg-gradient-to-r from-emerald-500 to-teal-500" :style="{ width: `${nextRateTierData.progress}%` }"></div>
              </div>
            </div>
          </div>
        </div>

        <!-- Top Updated Songs (max 10) -->
        <div v-if="diffData.updatedSongs.length > 0" class="flex-1 flex flex-col">
           <h3 class="text-3xl font-black text-slate-800 dark:text-slate-200 mb-6 flex items-center gap-3 shrink-0 leading-tight">
              <span class="w-2.5 h-8 bg-emerald-500 rounded-full"></span>
              {{ t('report.top10') }}
           </h3>
           <div class="space-y-4 flex-1">
             <div v-for="song in imageSortedSongs.slice(0, 10)" :key="song.title + song.difficulty" class="flex items-center justify-between bg-white dark:bg-slate-800 p-5 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm">
               <div class="flex-1 min-w-0 pr-4">
                 <div class="flex items-center gap-2 mb-2 flex-wrap">
                    <span class="px-3 py-1 rounded text-sm font-black border shrink-0" :class="getDifficultyColorClass(song.difficulty)">{{ song.difficulty }}</span>
                    <span v-if="song.songRank" class="px-2 py-0.5 rounded text-sm font-black border shrink-0"
                      :class="song.songRank === 1 ? 'bg-amber-100 text-amber-700 border-amber-300' : 'bg-slate-100 text-slate-600 border-slate-300'"
                    >#{{ song.songRank }}/{{ song.songRankTotal }}</span>
                    <span v-if="song.clearTypeImproved" class="text-sm font-black text-emerald-600 dark:text-emerald-400 bg-emerald-100 dark:bg-emerald-900/30 px-3 py-1 rounded border border-emerald-200 dark:border-emerald-800/50">LAMP UP!</span>
                    <template v-if="song.maxScore > 0">
                      <template v-for="info2 in [getScoreGradeInfo(song.newScore, song.maxScore)]" :key="'grade2'">
                        <span class="text-sm font-black tabular-nums px-2 py-0.5 rounded bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300">{{ info2.fromMax }}</span>
                        <span class="text-sm font-black tabular-nums px-2 py-0.5 rounded bg-slate-100 dark:bg-slate-700" :class="info2.gradeColor">{{ info2.grade }}</span>
                        <span v-if="info2.nextGrade" class="text-sm font-black tabular-nums px-2 py-0.5 rounded bg-slate-50 dark:bg-slate-700/50 text-slate-400 dark:text-slate-500 border border-slate-200 dark:border-slate-600">{{ info2.nextGrade.name }}{{ info2.nextGrade.gap }}</span>
                      </template>
                    </template>
                    <span v-if="song.isInRateTop100 && song.newRatePt > 0" class="text-sm font-black px-2 py-0.5 rounded bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400">RATE TOP100</span>
                 </div>
                 <p class="font-black text-2xl text-slate-800 dark:text-slate-100 truncate leading-snug pb-1">{{ song.title }}</p>
                 <div v-if="song.clearTypeImproved" class="flex items-center gap-3 mt-1.5">
                   <span class="text-sm font-bold text-slate-500 dark:text-slate-400 line-through">{{ song.oldClearType }}</span>
                   <span class="text-sm font-black" :class="getClearTypeColor(song.newClearType)">→ {{ song.newClearType }}</span>
                 </div>
               </div>
               <div class="text-right flex justify-end gap-6 shrink-0">
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
               {{ t('report.otherUpdates', { n: diffData.updatedSongs.length - 10 }) }}
             </span>
           </div>
        </div>
        <div v-else class="text-center py-12 text-slate-500 font-bold border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-2xl text-xl shrink-0">
          {{ t('report.noUpdates') }}
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
import { getNextRankInfo, getNextRateTierRankInfo } from '../utils/beatTier';
import { useAuth, API_BASE } from '../composables/useAuth';
import { useRateTierVisibility } from '../composables/useRateTierVisibility';
import { useI18n } from '../composables/useI18n';
import html2canvas from 'html2canvas';

const { t } = useI18n();

const props = defineProps<{
  isOpen: boolean;
  diffData: UploadDiffResult | null;
}>();

const { authHeaders } = useAuth();
const { showRateTier } = useRateTierVisibility();

const nextRankData = computed(() => {
  if (!props.diffData) return null;
  return getNextRankInfo(props.diffData.newTotalBeatPt);
});

const nextRateTierData = computed(() => {
  if (!props.diffData) return null;
  return getNextRateTierRankInfo(props.diffData.newTotalRatePt ?? 0);
});

const sortedUpdatedSongs = computed(() => {
  if (!props.diffData) return [];
  return [...props.diffData.updatedSongs].sort((a, b) => b.newBeatPt - a.newBeatPt);
});

const imageSortedSongs = computed(() => {
  if (!props.diffData) return [];
  if (shareSortMode.value === 'rate') {
    return [...props.diffData.updatedSongs].sort((a, b) => b.newRatePt - a.newRatePt);
  }
  return [...props.diffData.updatedSongs].sort((a, b) => b.newBeatPt - a.newBeatPt);
});

// Score grade label: "MAX", "MAX-n", "AAA+n", "AAA", "AA+n", etc.
function getScoreGradeInfo(newScore: number, maxScore: number): { fromMax: string; grade: string; gradeColor: string; nextGrade: { name: string; gap: number } | null } {
  if (!maxScore || maxScore <= 0) return { fromMax: '', grade: '', gradeColor: '', nextGrade: null };
  const fromMaxN = maxScore - newScore;
  const fromMax = fromMaxN === 0 ? 'MAX' : `MAX-${fromMaxN}`;

  const grades = [
    { name: 'AAA', threshold: maxScore * 8 / 9, color: 'text-yellow-500' },
    { name: 'AA',  threshold: maxScore * 7 / 9, color: 'text-blue-400' },
    { name: 'A',   threshold: maxScore * 6 / 9, color: 'text-green-500' },
    { name: 'B',   threshold: maxScore * 5 / 9, color: 'text-slate-400' },
    { name: 'C',   threshold: maxScore * 4 / 9, color: 'text-slate-400' },
    { name: 'D',   threshold: maxScore * 3 / 9, color: 'text-slate-400' },
    { name: 'E',   threshold: maxScore * 2 / 9, color: 'text-slate-400' },
  ];

  for (let i = 0; i < grades.length; i++) {
    const g = grades[i];
    const thresh = Math.ceil(g.threshold);
    if (newScore >= thresh) {
      const above = newScore - thresh;
      const nextGrade = i > 0 ? { name: grades[i - 1].name + '-', gap: Math.ceil(grades[i - 1].threshold) - newScore } : null;
      return { fromMax, grade: above === 0 ? g.name : `${g.name}+${above}`, gradeColor: g.color, nextGrade };
    }
  }
  // Below E: next grade is E
  const eThresh = Math.ceil(maxScore * 2 / 9);
  return { fromMax, grade: 'F', gradeColor: 'text-slate-400', nextGrade: { name: 'E-', gap: eThresh - newScore } };
}

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'navigate', tab: string): void;
}>();

const close = () => {
  emit('close');
};

// Option vote state
interface SongVoteState { myVote: string | null; }
const songVotes = ref<Record<string, SongVoteState>>({});
const votingKey = ref<string | null>(null);

const optionTypes = [
  { value: 'REGULAR', label: '正規', icon: '▶', activeBg: 'bg-blue-50 dark:bg-blue-900/30', activeText: 'text-blue-700 dark:text-blue-400', activeBorder: 'border-blue-300 dark:border-blue-700' },
  { value: 'MIRROR', label: 'MIRROR', icon: '◀', activeBg: 'bg-purple-50 dark:bg-purple-900/30', activeText: 'text-purple-700 dark:text-purple-400', activeBorder: 'border-purple-300 dark:border-purple-700' },
  { value: 'RANDOM', label: 'RANDOM', icon: '🎲', activeBg: 'bg-emerald-50 dark:bg-emerald-900/30', activeText: 'text-emerald-700 dark:text-emerald-400', activeBorder: 'border-emerald-300 dark:border-emerald-700' },
  { value: 'R-RANDOM', label: 'R-RAN', icon: '🔀', activeBg: 'bg-amber-50 dark:bg-amber-900/30', activeText: 'text-amber-700 dark:text-amber-400', activeBorder: 'border-amber-300 dark:border-amber-700' },
  { value: 'S-RANDOM', label: 'S-RAN', icon: '🎰', activeBg: 'bg-rose-50 dark:bg-rose-900/30', activeText: 'text-rose-700 dark:text-rose-400', activeBorder: 'border-rose-300 dark:border-rose-700' },
];

const getVoteClass = (title: string, difficulty: string, optValue: string, opt: typeof optionTypes[0]) => {
  const key = title + difficulty;
  const isVoted = songVotes.value[key]?.myVote === optValue;
  if (isVoted) return `${opt.activeBg} ${opt.activeText} ${opt.activeBorder}`;
  return 'bg-slate-50 dark:bg-slate-900 text-slate-600 dark:text-slate-400 border-slate-200 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800';
};

const castVote = async (title: string, difficultyName: string, optionType: string) => {
  const key = title + difficultyName;
  votingKey.value = key;
  try {
    const currentVote = songVotes.value[key]?.myVote;
    if (currentVote === optionType) {
      const params = new URLSearchParams({ title, difficultyName });
      await fetch(`${API_BASE}/api/votes?${params}`, { method: 'DELETE', headers: authHeaders() });
      songVotes.value[key] = { myVote: null };
    } else {
      await fetch(`${API_BASE}/api/votes`, {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ title, difficultyName, optionType })
      });
      songVotes.value[key] = { myVote: optionType };
    }
  } catch {
    // silent
  } finally {
    votingKey.value = null;
  }
};

const shareContainer = ref<HTMLElement | null>(null);
const isSharing = ref(false);
const isShareOptionsOpen = ref(false);
const shareSortMode = ref<'beat' | 'rate'>('beat');

const openShareOptions = () => {
  isShareOptionsOpen.value = true;
};

const confirmShare = () => {
  isShareOptionsOpen.value = false;
  shareOnX();
};

const shareOnX = async () => {
  if (!shareContainer.value || isSharing.value) return;
  isSharing.value = true;

  const textParam = encodeURIComponent(`${t('report.shareText')}\nhttps://beat-seeker-1.onrender.com \n#BeatSeeker`);

  try {
    const canvas = await html2canvas(shareContainer.value, {
      scale: 2,
      backgroundColor: document.documentElement.classList.contains('dark') ? '#0f172a' : '#ffffff',
      logging: false,
      windowWidth: 1080,
      windowHeight: 1920,
      scrollX: 0,
      scrollY: 0,
    });

    // Use await+Promise instead of callback to preserve the user gesture chain on iOS Safari
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'));
    if (!blob) throw new Error('Blob is null');

    const file = new File([blob], 'beat-seeker-report.png', { type: 'image/png' });

    // Try Web Share API first (iOS Safari / Android / newer desktop Chrome)
    if (navigator.share && navigator.canShare && navigator.canShare({ files: [file] })) {
      try {
        await navigator.share({
          title: 'beat-seeker Report',
          text: `${t('report.shareText')}\nhttps://beat-seeker-1.onrender.com \n#BeatSeeker`,
          files: [file]
        });
        isSharing.value = false;
        return;
      } catch (e) {
        if ((e as Error).name === 'AbortError') {
          isSharing.value = false;
          return;
        }
        // Non-abort error → fall through to clipboard
      }
    }

    // Fallback: Clipboard Web API + Window Open
    try {
      await navigator.clipboard.write([
        new ClipboardItem({ 'image/png': blob })
      ]);
      alert(t('report.copySuccess'));
      window.open(`https://twitter.com/intent/tweet?text=${textParam}`, '_blank');
    } catch (e) {
      console.error('Clipboard copy failed:', e);
      const downloadUrl = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = downloadUrl;
      a.download = 'beat-seeker-report.png';
      a.click();
      URL.revokeObjectURL(downloadUrl);
      alert(t('report.copyError'));
      window.open(`https://twitter.com/intent/tweet?text=${textParam}`, '_blank');
    }
  } catch (error) {
    console.error('Share failed:', error);
    alert(t('report.generateError'));
  }

  isSharing.value = false;
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
