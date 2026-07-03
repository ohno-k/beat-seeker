<template>
  <Teleport to="body">
    <div v-if="isOpen" class="fixed inset-0 z-[110] bg-slate-900/60 dark:bg-slate-950/80 backdrop-blur-sm flex items-center justify-center p-4 sm:p-6 animate-fade-in" @click.self="close">
      <div id="report-container" ref="reportContent" class="bg-white dark:bg-slate-900 w-full max-w-3xl rounded-md shadow-xl flex flex-col overflow-hidden max-h-[90vh] animate-slide-up border border-slate-200 dark:border-slate-800">
        
        <!-- ヘッダー: グラデーションのヒーロー領域 + 閉じるボタン -->
        <div class="relative bg-indigo-600 px-6 py-4 overflow-hidden shrink-0">

          <button id="modal-close-btn" @click="close" class="absolute top-2 right-2 p-2 group z-50">
            <div class="text-white/60 group-hover:text-white bg-white/10 group-hover:bg-white/20 rounded-full w-7 h-7 flex items-center justify-center transition-colors">
              <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </div>
          </button>

          <div class="relative z-10 flex items-center gap-3">
            <div class="bg-white/20 p-2 rounded-lg shrink-0">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>
            <div>
              <h2 class="text-lg font-bold text-white tracking-tight leading-tight">{{ t('report.title') }}</h2>
              <p class="text-indigo-200 font-medium text-xs">{{ t('report.subtitle') }}</p>
            </div>
          </div>
        </div>

        <!-- スクロール可能な本文領域（ティア/PT/フォルダ/更新曲） -->
        <div id="report-body" class="flex-1 overflow-y-auto p-6 sm:p-8 bg-slate-50 dark:bg-slate-900">
          <div v-if="diffData" class="space-y-8 max-w-2xl mx-auto">
            
            <!-- ティア UP 通知（旧ティア minPoints < 新ティア minPoints のときのみ表示） -->
            <div v-if="diffData.oldTier && diffData.newTier && diffData.oldTier.minPoints < diffData.newTier.minPoints" class="bg-amber-100 dark:bg-amber-900/40 border border-amber-300 dark:border-amber-700/50 p-6 rounded-md text-center relative overflow-hidden">
              <p class="text-amber-700 dark:text-amber-400 font-bold text-sm mb-2 relative z-10">{{ t('report.tierUp') }}</p>
              <div class="flex items-center justify-center gap-4 relative z-10">
                <span class="text-2xl font-bold text-slate-500 dark:text-slate-400 line-through decoration-amber-400/50">{{ diffData.oldTier.name }}{{ diffData.oldTier.tier ? ' ' + diffData.oldTier.tier : '' }}</span>
                <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-amber-500 dark:text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="3">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
                </svg>
                <span class="text-3xl font-bold text-amber-600 dark:text-amber-300">{{ diffData.newTier.name }}{{ diffData.newTier.tier ? ' ' + diffData.newTier.tier : '' }}</span>
              </div>
            </div>

            <!-- Beat-Tier 総合ポイント変動（旧ティア → 新ティア + 増分 PT） -->
            <div class="flex flex-col items-center bg-white dark:bg-slate-800 p-8 rounded-md border border-slate-200 dark:border-slate-700 relative overflow-hidden">

              <p class="text-sm font-bold text-slate-500 dark:text-slate-400 mb-4 relative z-10">{{ t('report.beatTierChange') }}</p>
              
              <!-- ティア名の比較表示（左: Previous / 右: Current） -->
              <div class="flex items-center justify-center gap-3 sm:gap-6 w-full mb-6 relative z-10">
                <div class="flex flex-col items-center flex-1">
                  <span class="text-xs font-bold text-slate-400 mb-1">{{ t('report.previous') }} ({{ diffData.oldTotalBeatPt.toFixed(1) }})</span>
                  <span class="text-lg sm:text-xl font-bold text-slate-600 dark:text-slate-300">{{ diffData.oldTier?.name || '---' }}{{ diffData.oldTier?.tier ? ' ' + diffData.oldTier.tier : '' }}</span>
                </div>
                
                <div class="shrink-0 flex flex-col items-center justify-center pt-4">
                   <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-indigo-400 dark:text-indigo-500 animate-pulse" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
                  </svg>
                </div>

                <div class="flex flex-col items-center flex-1">
                  <span class="text-xs font-bold text-indigo-400 mb-1">{{ t('report.current') }} ({{ diffData.newTotalBeatPt.toFixed(1) }})</span>
                  <span class="text-lg sm:text-xl font-bold whitespace-nowrap" :class="diffData.newTier?.color || 'text-slate-600'">{{ diffData.newTier?.name || '---' }}{{ diffData.newTier?.tier ? ' ' + diffData.newTier.tier : '' }}</span>
                </div>
              </div>
              
              <!-- 増分 PT（+3.5 pt のような大きな数字） -->
              <div class="flex items-baseline gap-2 bg-slate-50 dark:bg-slate-900/50 px-6 py-3 rounded-md border border-slate-100 dark:border-slate-800 relative z-10">
                <span class="text-sm font-bold text-slate-500 dark:text-slate-400 mr-2">{{ t('report.increase') }}:</span>
                <span class="text-4xl font-bold tracking-tight" :class="diffData.totalBeatPtIncrease > 0 ? 'text-indigo-600 dark:text-indigo-400' : 'text-slate-400 dark:text-slate-500'">
                  {{ diffData.totalBeatPtIncrease > 0 ? '+' : '' }}{{ diffData.totalBeatPtIncrease.toFixed(1) }}
                </span>
                <span class="text-lg font-bold text-indigo-500">{{ t('common.pt') }}</span>
              </div>
            </div>

            <!-- Rate-Tier 変動（Rate 表示が ON かつ +0.1 以上増えた場合のみ） -->
            <div v-if="showRateTier && diffData.newTotalRatePt > 0 && (diffData.newTotalRatePt - diffData.oldTotalRatePt) >= 0.1" class="flex flex-col items-center bg-white dark:bg-slate-800 p-8 rounded-md border border-slate-200 dark:border-slate-700 relative overflow-hidden">

              <p class="text-sm font-bold text-slate-500 dark:text-slate-400 mb-4 relative z-10">{{ t('report.rateTierChange') }}</p>

              <div class="flex items-center justify-center gap-3 sm:gap-6 w-full mb-6 relative z-10">
                <div class="flex flex-col items-center flex-1">
                  <span class="text-xs font-bold text-slate-400 mb-1">{{ t('report.previous') }} ({{ diffData.oldTotalRatePt.toFixed(1) }})</span>
                  <span class="text-lg sm:text-xl font-bold text-slate-600 dark:text-slate-300">{{ diffData.oldRateTier?.name || '---' }}{{ diffData.oldRateTier?.tier ? ' ' + diffData.oldRateTier.tier : '' }}</span>
                </div>
                <div class="shrink-0 flex flex-col items-center justify-center pt-4">
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-emerald-400 dark:text-emerald-500 animate-pulse" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
                  </svg>
                </div>
                <div class="flex flex-col items-center flex-1">
                  <span class="text-xs font-bold text-emerald-400 mb-1">{{ t('report.current') }} ({{ diffData.newTotalRatePt.toFixed(1) }})</span>
                  <span class="text-lg sm:text-xl font-bold whitespace-nowrap" :class="diffData.newRateTier?.color || 'text-slate-600'">{{ diffData.newRateTier?.name || '---' }}{{ diffData.newRateTier?.tier ? ' ' + diffData.newRateTier.tier : '' }}</span>
                </div>
              </div>

              <div class="flex items-baseline gap-2 bg-slate-50 dark:bg-slate-900/50 px-6 py-3 rounded-md border border-slate-100 dark:border-slate-800 relative z-10">
                <span class="text-sm font-bold text-slate-500 dark:text-slate-400 mr-2">{{ t('report.increase') }}:</span>
                <span class="text-4xl font-bold tracking-tight" :class="diffData.newTotalRatePt - diffData.oldTotalRatePt > 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400 dark:text-slate-500'">
                  {{ diffData.newTotalRatePt - diffData.oldTotalRatePt > 0 ? '+' : '' }}{{ (diffData.newTotalRatePt - diffData.oldTotalRatePt).toFixed(1) }}
                </span>
                <span class="text-lg font-bold text-emerald-500">{{ t('common.pt') }}</span>
              </div>
            </div>

            <!-- フォルダアナウンス（☆11/☆12 フォルダのランクアサイン、ランクアップ、残り数を通知） -->
            <div v-if="diffData.folderAnnouncements && diffData.folderAnnouncements.length > 0" class="space-y-2">
              <h3 class="flex items-center gap-2 text-lg font-bold text-slate-800 dark:text-slate-200 mb-3 px-2">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-indigo-500" viewBox="0 0 20 20" fill="currentColor">
                  <path d="M5 4a1 1 0 00-2 0v7.268a2 2 0 000 3.464V16a1 1 0 102 0v-1.268a2 2 0 000-3.464V4zM11 4a1 1 0 10-2 0v1.268a2 2 0 000 3.464V16a1 1 0 102 0V8.732a2 2 0 000-3.464V4zM16 3a1 1 0 011 1v7.268a2 2 0 010 3.464V16a1 1 0 11-2 0v-1.268a2 2 0 010-3.464V4a1 1 0 011-1z" />
                </svg>
                {{ t('report.folderNews') }}
              </h3>
              <div v-for="ann in diffData.folderAnnouncements" :key="ann.folder + ann.type"
                class="px-4 py-3 rounded-md border transition-colors"
                :class="ann.type === 'rank_assigned'
                  ? 'bg-amber-50 dark:bg-amber-900/20 border-amber-200 dark:border-amber-800/50'
                  : ann.type === 'rank_up'
                    ? 'bg-violet-50 dark:bg-violet-900/20 border-violet-200 dark:border-violet-800/50'
                    : 'bg-slate-50 dark:bg-slate-800 border-slate-200 dark:border-slate-700'"
              >
                <template v-if="ann.type === 'rank_assigned'">
                  <div class="flex items-center gap-2">
                    <span class="text-xs font-bold text-amber-600 dark:text-amber-400">☆{{ ann.folder }}</span>
                    <span class="text-sm font-bold text-amber-700 dark:text-amber-300">{{ t('report.folderRankAssigned', { rank: ann.newRankName }) }}</span>
                  </div>
                </template>
                <template v-else-if="ann.type === 'rank_up'">
                  <div class="flex items-center gap-2">
                    <span class="text-xs font-bold text-violet-600 dark:text-violet-400">☆{{ ann.folder }}</span>
                    <span class="text-sm font-bold text-violet-700 dark:text-violet-300">{{ t('report.folderRankUp', { oldRank: ann.oldRankName, newRank: ann.newRankName }) }}</span>
                  </div>
                </template>
                <template v-else-if="ann.type === 'remaining'">
                  <div class="flex items-center gap-2">
                    <span class="text-xs font-bold text-slate-500 dark:text-slate-400">☆{{ ann.folder }}</span>
                    <span class="text-sm font-bold text-slate-600 dark:text-slate-300">{{ t('report.folderRemaining', { n: ann.remaining }) }}</span>
                  </div>
                </template>
              </div>
            </div>

            <!-- 更新曲リスト（Beat-PT 降順。各行: 難易度バッジ / ランキング / LAMP UP / DJ LEVEL / 投票） -->
            <div>
              <h3 class="flex items-center gap-2 text-lg font-bold text-slate-800 dark:text-slate-200 mb-4 px-2">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-emerald-500" viewBox="0 0 20 20" fill="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                </svg>
                {{ t('report.updatedSongs') }} <span class="bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300 text-xs font-bold px-2 py-0.5 rounded ml-1">{{ t('report.otherUpdates', { n: diffData.updatedSongs.length }) }}</span>
              </h3>
              
              <div v-if="diffData.updatedSongs.length === 0" class="text-center p-8 bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 border-dashed">
                <p class="text-slate-500 dark:text-slate-400 font-medium">{{ t('report.noUpdates') }}</p>
              </div>
              
              <div v-else class="space-y-2">
                <div v-for="song in sortedUpdatedSongs" :key="song.title + song.difficulty" class="bg-white dark:bg-slate-800 px-4 py-3 rounded-md border border-slate-200 dark:border-slate-700 transition-colors">

                  <!-- 行 1: 難易度バッジ / 単曲ティア / ソングランク / LAMP UP バッジ / DJ LEVEL 情報 / RATE TOP100 -->
                  <div class="flex items-center gap-1.5 mb-1 flex-wrap">
                    <span class="px-1.5 py-0.5 rounded text-[9px] font-bold border shrink-0" :class="getDifficultyColorClass(song.difficulty)">
                      {{ song.difficulty }}
                    </span>
                    <template v-for="rankNum in [getNumericRank(song.informalRank)]" :key="'rank'">
                      <span v-if="rankNum" class="px-1.5 py-0.5 rounded text-[9px] font-bold border shrink-0 bg-slate-100 text-slate-600 border-slate-300 dark:bg-slate-700 dark:text-slate-300 dark:border-slate-600">☆{{ rankNum }}</span>
                    </template>
                    <span v-if="song.songRank" class="px-1.5 py-0.5 rounded text-[9px] font-bold border shrink-0"
                      :class="song.songRank === 1 ? 'bg-amber-100 text-amber-700 border-amber-300 dark:bg-amber-900/30 dark:text-amber-400 dark:border-amber-700' : 'bg-slate-100 text-slate-600 border-slate-300 dark:bg-slate-700 dark:text-slate-300 dark:border-slate-600'"
                    >#{{ song.songRank }}<span class="font-medium">/{{ song.songRankTotal }}</span></span>
                    <span v-if="song.clearTypeImproved" class="px-1.5 py-0.5 bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400 text-[9px] font-bold rounded border border-emerald-200 dark:border-emerald-800/50">{{ t('report.lampUp') }}</span>
                    <template v-if="song.maxScore > 0">
                      <template v-for="info in [getScoreGradeInfo(song.newScore, song.maxScore)]" :key="'grade'">
                        <span class="text-[9px] font-bold tabular-nums px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300">
                          {{ info.fromMax }}
                        </span>
                        <span class="text-[9px] font-bold tabular-nums px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-700" :class="info.gradeColor">
                          {{ info.grade }}
                        </span>
                        <span v-if="info.nextGrade" class="text-[9px] font-bold tabular-nums px-1.5 py-0.5 rounded bg-slate-50 dark:bg-slate-700/50 text-slate-400 dark:text-slate-500 border border-slate-200 dark:border-slate-600">
                          {{ info.nextGrade.name }}{{ info.nextGrade.gap }}
                        </span>
                      </template>
                    </template>
                    <span v-if="song.isInRateTop100 && song.newRatePt > 0" class="text-[9px] font-bold px-1.5 py-0.5 rounded bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 border border-emerald-200 dark:border-emerald-800/50">RATE TOP100</span>
                  </div>

                  <!-- 行 2: タイトル + 単曲ティアアイコン + 新スコア(+増分) + 新 Beat-PT -->
                  <div class="flex items-baseline justify-between gap-2">
                    <h4 class="font-bold text-slate-800 dark:text-slate-100 text-base leading-tight" :title="song.title">{{ song.title }}</h4>
                    <div class="flex items-center gap-2 shrink-0 text-right">
                      <template v-for="t in [getSongTierTransition(song)]" :key="'tier-icon'">
                        <template v-if="t.newTier">
                          <RankIcon
                            v-if="t.oldTier"
                            :rank-name="t.oldTier.name"
                            :tier="t.oldTier.tier"
                            size="xs"
                            disable-party
                            class="opacity-60"
                          />
                          <svg v-if="t.oldTier" xmlns="http://www.w3.org/2000/svg" class="h-2.5 w-2.5 text-slate-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M14 5l7 7m0 0l-7 7m7-7H3" /></svg>
                          <RankIcon
                            :rank-name="t.newTier.name"
                            :tier="t.newTier.tier"
                            size="xs"
                            disable-party
                          />
                        </template>
                      </template>
                      <span v-if="song.scoreIncrease > 0" class="text-xs font-bold text-slate-600 dark:text-slate-300 whitespace-nowrap">{{ song.newScore }}<span class="text-blue-500 dark:text-blue-400">(+{{ song.scoreIncrease }})</span></span>
                      <span v-if="song.beatPtIncrease > 0" class="text-xs font-bold whitespace-nowrap" :class="song.isInTop100 ? 'text-amber-500 dark:text-amber-400' : 'text-indigo-400 dark:text-indigo-500'">+{{ song.newBeatPt.toFixed(1) }}pt</span>
                    </div>
                  </div>

                  <!-- 行 3: LAMP 変化（改善した場合のみ、旧 → 新） -->
                  <div v-if="song.clearTypeImproved" class="flex items-center gap-1.5 mt-0.5">
                    <span class="text-[10px] font-bold text-slate-400 line-through">{{ song.oldClearType }}</span>
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-2.5 w-2.5 text-slate-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M14 5l7 7m0 0l-7 7m7-7H3" /></svg>
                    <span class="text-[10px] font-bold" :class="getClearTypeColor(song.newClearType)">{{ song.newClearType }}</span>
                  </div>

                  <!-- 行 4: オプション投票ボタン（正規/MIRROR/RANDOM/R-RAN/S-RAN） -->
                  <div class="flex flex-wrap gap-1 mt-2 pt-2 border-t border-slate-100 dark:border-slate-700/50">
                    <button
                      v-for="opt in optionTypes"
                      :key="opt.value"
                      @click="castVote(song.title, song.difficulty, opt.value)"
                      :disabled="votingKey === (song.title + song.difficulty)"
                      class="px-2 py-1 rounded-md text-[10px] font-bold border transition-all disabled:opacity-50 flex items-center gap-0.5"
                      :class="getVoteClass(song.title, song.difficulty, opt.value, opt)"
                    >
                      {{ opt.icon }} {{ opt.label }}<span v-if="songVotes[song.title + song.difficulty]?.myVotes?.includes(opt.value)"> ✔</span>
                    </button>
                  </div>

                </div>
              </div>
            </div>
          </div>
        </div>
        
        <!-- フッター: X シェアボタン + 閉じるボタン -->
        <div id="modal-footer" class="p-4 sm:p-6 border-t border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 shrink-0 flex flex-col sm:flex-row gap-3">
          <button @click="openShareOptions" :disabled="isSharing" class="flex-1 py-3.5 sm:py-4 bg-black hover:bg-slate-800 text-white font-bold rounded-md transition-colors flex items-center justify-center gap-2 disabled:opacity-50 text-sm sm:text-base">
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
          <button @click="close" class="flex-1 py-3.5 sm:py-4 bg-slate-100 dark:bg-slate-800 hover:bg-slate-200 dark:hover:bg-slate-700 text-slate-800 dark:text-white font-bold rounded-md transition-colors flex items-center justify-center gap-2 text-sm sm:text-base">
            {{ t('report.backToDashboard') }}
          </button>
        </div>

        <!-- 画像出力オプション（Beat-PT 順 / Rate-PT 順の選択） -->
        <div v-if="isShareOptionsOpen" class="absolute inset-0 z-50 bg-slate-900/70 backdrop-blur-sm flex items-end sm:items-center justify-center p-4 rounded-md" @click.self="isShareOptionsOpen = false">
          <div class="bg-white dark:bg-slate-800 w-full max-w-sm rounded-md shadow-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
            <div class="px-6 py-4 border-b border-slate-100 dark:border-slate-700">
              <h3 class="font-bold text-slate-800 dark:text-slate-100 text-base">{{ t('report.outputOptions') }}</h3>
              <p class="text-xs text-slate-500 dark:text-slate-400 mt-0.5">{{ t('report.outputOptionsSub') }}</p>
            </div>
            <div class="p-4 space-y-2">
              <button
                @click="shareSortMode = 'beat'"
                class="w-full flex items-center gap-3 px-4 py-3 rounded-md border-2 transition-all text-left"
                :class="shareSortMode === 'beat' ? 'border-indigo-500 bg-indigo-50 dark:bg-indigo-900/30' : 'border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700/50'"
              >
                <div class="w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0" :class="shareSortMode === 'beat' ? 'border-indigo-500' : 'border-slate-300 dark:border-slate-600'">
                  <div v-if="shareSortMode === 'beat'" class="w-2.5 h-2.5 rounded-full bg-indigo-500"></div>
                </div>
                <div>
                  <p class="font-bold text-sm" :class="shareSortMode === 'beat' ? 'text-indigo-700 dark:text-indigo-300' : 'text-slate-700 dark:text-slate-200'">{{ t('report.sortByBeatPt') }}</p>
                  <p class="text-[11px] text-slate-500 dark:text-slate-400">{{ t('report.sortByBeatPtDesc') }}</p>
                </div>
              </button>
              <button
                @click="shareSortMode = 'rate'"
                class="w-full flex items-center gap-3 px-4 py-3 rounded-md border-2 transition-all text-left"
                :class="shareSortMode === 'rate' ? 'border-emerald-500 bg-emerald-50 dark:bg-emerald-900/30' : 'border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700/50'"
              >
                <div class="w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0" :class="shareSortMode === 'rate' ? 'border-emerald-500' : 'border-slate-300 dark:border-slate-600'">
                  <div v-if="shareSortMode === 'rate'" class="w-2.5 h-2.5 rounded-full bg-emerald-500"></div>
                </div>
                <div>
                  <p class="font-bold text-sm" :class="shareSortMode === 'rate' ? 'text-emerald-700 dark:text-emerald-300' : 'text-slate-700 dark:text-slate-200'">{{ t('report.sortByRatePt') }}</p>
                  <p class="text-[11px] text-slate-500 dark:text-slate-400">{{ t('report.sortByRatePtDesc') }}</p>
                </div>
              </button>
            </div>
            <div class="px-4 pb-4 flex gap-2">
              <button @click="isShareOptionsOpen = false" class="flex-1 py-2.5 bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 font-bold rounded-md text-sm transition-colors">
                {{ t('common.cancel') }}
              </button>
              <button @click="confirmShare" class="flex-1 py-2.5 bg-black hover:bg-slate-800 text-white font-bold rounded-md text-sm transition-colors flex items-center justify-center gap-1.5">
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

    <!-- X シェア用オフスクリーン領域（1080x1920 / 9:16 固定、画面外に配置して html2canvas でキャプチャ） -->
    <div 
      ref="shareContainer"
      class="fixed -top-[2000px] -left-[2000px] bg-slate-50 dark:bg-slate-900 w-[1080px] h-[1920px] flex flex-col z-[-1] border-none overflow-hidden pointer-events-none"
    >
      <div class="bg-indigo-600 p-12 text-center shrink-0">
        <h2 class="text-6xl font-bold text-white tracking-tight mb-4 drop-shadow-md leading-tight">
          {{ t('report.title') }}
        </h2>
        <p class="text-indigo-100 font-medium text-2xl">
          {{ t('report.newRecord') }}
        </p>
      </div>
      
      <div class="p-12 flex-1 flex flex-col bg-slate-50 dark:bg-slate-900" v-if="diffData">
        <!-- ティアサマリー行（Rate 表示 ON なら 2 列、OFF なら 1 列中央寄せ） -->
        <div class="grid gap-8 mb-10 shrink-0" :class="showRateTier ? 'grid-cols-2' : 'grid-cols-1 max-w-sm mx-auto w-full'">
          <!-- シェア画像: Beat-Tier カード -->
          <div class="border-2 border-indigo-100 dark:border-indigo-900/50 rounded-md p-8 bg-white dark:bg-slate-800 relative overflow-hidden">
            <div class="relative z-10 mb-6">
              <p class="text-xl font-bold text-slate-500 dark:text-slate-400 mb-2">{{ t('common.beatPt') }}</p>
              <div class="flex items-baseline gap-3">
                <span class="text-5xl font-bold text-slate-800 dark:text-slate-100 leading-tight">{{ diffData.newTotalBeatPt.toFixed(1) }}</span>
                <span class="text-2xl font-bold text-indigo-500">+{{ diffData.totalBeatPtIncrease.toFixed(1) }}</span>
              </div>
            </div>
            <div class="relative z-10 mb-6">
              <p class="text-xl font-bold text-slate-500 dark:text-slate-400 mb-1">BEAT-TIER</p>
              <p class="text-4xl font-bold leading-tight" :class="diffData.newTier?.color">{{ diffData.newTier?.name }} {{ diffData.newTier?.tier || '' }}</p>
            </div>
            <div v-if="nextRankData && nextRankData.nextRank" class="relative z-10">
              <div class="flex justify-between items-end mb-2">
                <span class="text-base font-bold text-slate-500">NEXT</span>
                <span class="text-xl font-bold" :class="nextRankData.nextRank.color">{{ nextRankData.nextRank.name }} {{ nextRankData.nextRank.tier || '' }}</span>
              </div>
              <div class="w-full h-4 bg-slate-100 dark:bg-slate-700/50 rounded-full overflow-hidden border border-slate-200 dark:border-slate-600">
                <div class="h-full bg-indigo-500" :style="{ width: `${nextRankData.progress}%` }"></div>
              </div>
            </div>
          </div>
          <!-- シェア画像: Rate-Tier カード -->
          <div v-if="showRateTier" class="border-2 border-emerald-100 dark:border-emerald-900/50 rounded-md p-8 bg-white dark:bg-slate-800 relative overflow-hidden">
            <div class="relative z-10 mb-6">
              <p class="text-xl font-bold text-slate-500 dark:text-slate-400 mb-2">{{ t('common.ratePt') }}</p>
              <div class="flex items-baseline gap-3">
                <span class="text-5xl font-bold text-slate-800 dark:text-slate-100 leading-tight">{{ (diffData.newTotalRatePt ?? 0).toFixed(1) }}</span>
                <span class="text-2xl font-bold text-emerald-500">{{ (diffData.newTotalRatePt ?? 0) - (diffData.oldTotalRatePt ?? 0) > 0 ? '+' : '' }}{{ ((diffData.newTotalRatePt ?? 0) - (diffData.oldTotalRatePt ?? 0)).toFixed(1) }}</span>
              </div>
            </div>
            <div class="relative z-10 mb-6">
              <p class="text-xl font-bold text-slate-500 dark:text-slate-400 mb-1">RATE-TIER</p>
              <p class="text-4xl font-bold leading-tight" :class="diffData.newRateTier?.color">{{ diffData.newRateTier?.name }} {{ diffData.newRateTier?.tier || '' }}</p>
            </div>
            <div v-if="nextRateTierData && nextRateTierData.nextRank" class="relative z-10">
              <div class="flex justify-between items-end mb-2">
                <span class="text-base font-bold text-slate-500">NEXT</span>
                <span class="text-xl font-bold" :class="nextRateTierData.nextRank.color">{{ nextRateTierData.nextRank.name }} {{ nextRateTierData.nextRank.tier || '' }}</span>
              </div>
              <div class="w-full h-4 bg-slate-100 dark:bg-slate-700/50 rounded-full overflow-hidden border border-slate-200 dark:border-slate-600">
                <div class="h-full bg-emerald-500" :style="{ width: `${nextRateTierData.progress}%` }"></div>
              </div>
            </div>
          </div>
        </div>

        <!-- シェア画像: 更新曲 TOP10（Beat-PT または Rate-PT 降順） -->
        <div v-if="diffData.updatedSongs.length > 0" class="flex-1 flex flex-col">
           <h3 class="text-3xl font-bold text-slate-800 dark:text-slate-200 mb-6 flex items-center gap-3 shrink-0 leading-tight">
              <span class="w-2.5 h-8 bg-emerald-500 rounded-full"></span>
              {{ t('report.top10') }}
           </h3>
           <div class="space-y-4 flex-1">
             <div v-for="song in imageSortedSongs.slice(0, 10)" :key="song.title + song.difficulty" class="flex items-center justify-between bg-white dark:bg-slate-800 p-5 rounded-md border border-slate-200 dark:border-slate-700">
               <div class="flex-1 min-w-0 pr-4">
                 <div class="flex items-center gap-2 mb-2 flex-wrap">
                    <span class="px-3 py-1 rounded text-sm font-bold border shrink-0" :class="getDifficultyColorClass(song.difficulty)">{{ song.difficulty }}</span>
                    <template v-for="rankNumImg in [getNumericRank(song.informalRank)]" :key="'rank-img'">
                      <span v-if="rankNumImg" class="px-2 py-1 rounded text-sm font-bold border shrink-0 bg-slate-100 text-slate-600 border-slate-300 dark:bg-slate-700 dark:text-slate-300 dark:border-slate-600">☆{{ rankNumImg }}</span>
                    </template>
                    <template v-for="tierImg in [getSongTierInfo(song)]" :key="'tier-img'">
                      <span v-if="tierImg" class="px-2 py-1 rounded text-sm font-bold border shrink-0 bg-white dark:bg-slate-900/50 border-slate-300 dark:border-slate-600" :class="tierImg.color">
                        {{ tierImg.name }}{{ tierImg.tier ? ' ' + tierImg.tier : '' }}
                      </span>
                    </template>
                    <span v-if="song.songRank" class="px-2 py-0.5 rounded text-sm font-bold border shrink-0"
                      :class="song.songRank === 1 ? 'bg-amber-100 text-amber-700 border-amber-300' : 'bg-slate-100 text-slate-600 border-slate-300'"
                    >#{{ song.songRank }}/{{ song.songRankTotal }}</span>
                    <span v-if="song.clearTypeImproved" class="text-sm font-bold text-emerald-600 dark:text-emerald-400 bg-emerald-100 dark:bg-emerald-900/30 px-3 py-1 rounded border border-emerald-200 dark:border-emerald-800/50">LAMP UP!</span>
                    <template v-if="song.maxScore > 0">
                      <template v-for="info2 in [getScoreGradeInfo(song.newScore, song.maxScore)]" :key="'grade2'">
                        <span class="text-sm font-bold tabular-nums px-2 py-0.5 rounded bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300">{{ info2.fromMax }}</span>
                        <span class="text-sm font-bold tabular-nums px-2 py-0.5 rounded bg-slate-100 dark:bg-slate-700" :class="info2.gradeColor">{{ info2.grade }}</span>
                        <span v-if="info2.nextGrade" class="text-sm font-bold tabular-nums px-2 py-0.5 rounded bg-slate-50 dark:bg-slate-700/50 text-slate-400 dark:text-slate-500 border border-slate-200 dark:border-slate-600">{{ info2.nextGrade.name }}{{ info2.nextGrade.gap }}</span>
                      </template>
                    </template>
                    <span v-if="song.isInRateTop100 && song.newRatePt > 0" class="text-sm font-bold px-2 py-0.5 rounded bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400">RATE TOP100</span>
                 </div>
                 <p class="font-bold text-2xl text-slate-800 dark:text-slate-100 truncate leading-snug pb-1">{{ song.title }}</p>
                 <div v-if="song.clearTypeImproved" class="flex items-center gap-3 mt-1.5">
                   <span class="text-sm font-bold text-slate-500 dark:text-slate-400 line-through">{{ song.oldClearType }}</span>
                   <span class="text-sm font-bold" :class="getClearTypeColor(song.newClearType)">→ {{ song.newClearType }}</span>
                 </div>
               </div>
               <div class="text-right flex items-center justify-end gap-4 shrink-0">
                 <template v-for="tierIconImg in [getSongTierInfo(song)]" :key="'tier-icon-img'">
                   <RankIcon
                     v-if="tierIconImg"
                     :rank-name="tierIconImg.name"
                     :tier="tierIconImg.tier"
                     size="md"
                     disable-party
                   />
                 </template>
                 <div v-if="song.scoreIncrease > 0" class="text-right">
                   <p class="text-sm font-bold text-slate-500 mb-1">EX SCORE</p>
                   <p class="font-bold text-3xl text-slate-700 dark:text-slate-300">{{ song.newScore }} <span class="text-lg font-bold text-blue-500">(+{{ song.scoreIncrease }})</span></p>
                 </div>
                 <div v-if="song.beatPtIncrease > 0" class="text-right">
                   <div class="flex items-center justify-end gap-2 mb-1">
                     <p class="text-sm font-bold" :class="song.isInTop100 ? 'text-amber-500' : 'text-slate-500'">BEAT-PT</p>
                     <span v-if="song.isInTop100 !== undefined" class="text-xs font-bold px-1.5 py-0.5 rounded" :class="song.isInTop100 ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-500'">{{ song.isInTop100 ? 'TOP100' : '圏外' }}</span>
                   </div>
                   <p class="font-bold text-3xl" :class="song.isInTop100 ? 'text-amber-500' : 'text-indigo-400'">+{{ song.newBeatPt.toFixed(1) }} <span class="text-lg font-bold text-slate-400">(+{{ song.beatPtIncrease.toFixed(1) }})</span></p>
                 </div>
               </div>
             </div>
           </div>
           <div v-if="diffData.updatedSongs.length > 10" class="text-center mt-6 shrink-0">
             <span class="inline-block px-4 py-2 bg-slate-200 dark:bg-slate-700 rounded text-base font-bold text-slate-500 dark:text-slate-300">
               {{ t('report.otherUpdates', { n: diffData.updatedSongs.length - 10 }) }}
             </span>
           </div>
        </div>
        <div v-else class="text-center py-12 text-slate-500 font-bold border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-md text-xl shrink-0">
          {{ t('report.noUpdates') }}
        </div>
      </div>
      
      <div class="bg-indigo-600 p-6 text-center text-white/90 font-bold text-xl shrink-0">
        beat-seeker - IIDX Score Tracker
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 アップロード結果 / 履歴差分を表示するモーダル。
 *
 * 機能:
 *  - ティア UP / Beat-PT 増減 / Rate-PT 増減 / フォルダアナウンス / 更新曲リストを表示
 *  - X (Twitter) 共有用の 1080x1920 縦長画像を html2canvas でオフスクリーン生成
 *  - Web Share API でファイル共有、不可ならクリップボードコピー + ツイート画面オープン（iOS 対応）
 *  - 更新曲ごとに「正規 / MIRROR / RANDOM / R-RAN / S-RAN」のオプション投票も可能
 *
 * props:
 *  - isOpen: モーダル開閉
 *  - diffData: 差分情報（UploadDiffResult 型）
 * emits:
 *  - close: 閉じる
 *  - navigate: 他タブへのナビゲーション要求
 */
import { ref, computed } from 'vue';
import type { UploadDiffResult } from './../types/UploadDiff';
import { getNextRankInfo, getNextRateTierRankInfo, getFolderRankInfoByRate } from '../utils/beatTier';
import type { RankInfo } from '../utils/beatTier';
import RankIcon from './RankIcon.vue';
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

/** 次ランクまでの進捗情報（Beat-Tier）。プログレスバー表示に使う。 */
const nextRankData = computed(() => {
  if (!props.diffData) return null;
  return getNextRankInfo(props.diffData.newTotalBeatPt);
});

/** 次ランクまでの進捗情報（Rate-Tier）。 */
const nextRateTierData = computed(() => {
  if (!props.diffData) return null;
  return getNextRateTierRankInfo(props.diffData.newTotalRatePt ?? 0);
});

/** 本文に表示する更新曲。Beat-PT 降順。 */
const sortedUpdatedSongs = computed(() => {
  if (!props.diffData) return [];
  return [...props.diffData.updatedSongs].sort((a, b) => b.newBeatPt - a.newBeatPt);
});

/** シェア画像（TOP10）に出す更新曲。シェアモード（beat/rate）で並び替えを切替。 */
const imageSortedSongs = computed(() => {
  if (!props.diffData) return [];
  if (shareSortMode.value === 'rate') {
    return [...props.diffData.updatedSongs].sort((a, b) => b.newRatePt - a.newRatePt);
  }
  return [...props.diffData.updatedSongs].sort((a, b) => b.newBeatPt - a.newBeatPt);
});

/**
 * 【関数の役割】 スコアを DJ LEVEL に変換し、MAX からの距離 / 現在グレード / 次グレードまでの距離を返す。
 * 返り値例:
 *  - fromMax: "MAX" または "MAX-n"
 *  - grade: "AAA" / "AA+123" / "A+0" など
 *  - nextGrade: 一つ上のグレードに到達するまでの不足 EX-SCORE
 * AAA=8/9, AA=7/9 ... の公式しきい値を使い、切り上げで判定する。
 */
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
  // E 未満の場合は次ターゲットを E に設定して F として返す。
  const eThresh = Math.ceil(maxScore * 2 / 9);
  return { fromMax, grade: 'F', gradeColor: 'text-slate-400', nextGrade: { name: 'E-', gap: eThresh - newScore } };
}

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'navigate', tab: string): void;
}>();

/** 【関数の役割】 モーダルを閉じる。 */
const close = () => {
  emit('close');
};

// オプション投票関連の状態（複数選択対応）
interface SongVoteState { myVotes: string[]; }
/** 曲+難易度キーに対する自分の投票一覧。再描画用に ref で保持。 */
const songVotes = ref<Record<string, SongVoteState>>({});
/** 投票中の行キー（ボタン無効化用）。 */
const votingKey = ref<string | null>(null);

/** 投票できるオプションの定義。ボタンの色クラスもここに集約。 */
const optionTypes = [
  { value: 'REGULAR', label: '正規', icon: '▶', activeBg: 'bg-blue-50 dark:bg-blue-900/30', activeText: 'text-blue-700 dark:text-blue-400', activeBorder: 'border-blue-300 dark:border-blue-700' },
  { value: 'MIRROR', label: 'MIRROR', icon: '◀', activeBg: 'bg-purple-50 dark:bg-purple-900/30', activeText: 'text-purple-700 dark:text-purple-400', activeBorder: 'border-purple-300 dark:border-purple-700' },
  { value: 'RANDOM', label: 'RANDOM', icon: '🎲', activeBg: 'bg-emerald-50 dark:bg-emerald-900/30', activeText: 'text-emerald-700 dark:text-emerald-400', activeBorder: 'border-emerald-300 dark:border-emerald-700' },
  { value: 'R-RANDOM', label: 'R-RAN', icon: '🔀', activeBg: 'bg-amber-50 dark:bg-amber-900/30', activeText: 'text-amber-700 dark:text-amber-400', activeBorder: 'border-amber-300 dark:border-amber-700' },
  { value: 'S-RANDOM', label: 'S-RAN', icon: '🎰', activeBg: 'bg-rose-50 dark:bg-rose-900/30', activeText: 'text-rose-700 dark:text-rose-400', activeBorder: 'border-rose-300 dark:border-rose-700' },
];

/**
 * 【関数の役割】 投票ボタンに適用する Tailwind クラスを返す。
 * 自分が投票済みのオプションは色付きハイライトに、それ以外はグレー枠に。
 */
const getVoteClass = (title: string, difficulty: string, optValue: string, opt: typeof optionTypes[0]) => {
  const key = title + difficulty;
  const isVoted = songVotes.value[key]?.myVotes?.includes(optValue) ?? false;
  if (isVoted) return `${opt.activeBg} ${opt.activeText} ${opt.activeBorder}`;
  return 'bg-slate-50 dark:bg-slate-900 text-slate-600 dark:text-slate-400 border-slate-200 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800';
};

/**
 * 【関数の役割】 曲ごとのオプション投票を toggle する（複数選択対応）。
 * 既にそのオプションを投票済みなら DELETE で取消、そうでなければ POST で追加。
 * 他のオプションには影響しない。成功/失敗ともサイレント（UI はローカル state の切替のみ）。
 */
const castVote = async (title: string, difficultyName: string, optionType: string) => {
  const key = title + difficultyName;
  votingKey.value = key;
  try {
    const current = songVotes.value[key]?.myVotes ?? [];
    const alreadyVoted = current.includes(optionType);
    if (alreadyVoted) {
      const params = new URLSearchParams({ title, difficultyName, optionType });
      await fetch(`${API_BASE}/api/votes?${params}`, { method: 'DELETE', headers: authHeaders() });
      songVotes.value[key] = { myVotes: current.filter(v => v !== optionType) };
    } else {
      await fetch(`${API_BASE}/api/votes`, {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ title, difficultyName, optionType })
      });
      songVotes.value[key] = { myVotes: [...current, optionType] };
    }
  } catch {
    // silent
  } finally {
    votingKey.value = null;
  }
};

/** シェア用オフスクリーン DOM への参照。html2canvas のキャプチャ対象。 */
const shareContainer = ref<HTMLElement | null>(null);
/** 画像生成 + シェア進行中フラグ（ボタンにスピナー表示）。 */
const isSharing = ref(false);
/** シェアオプション（ソート軸選択）モーダルの開閉。 */
const isShareOptionsOpen = ref(false);
/** シェア画像の楽曲ソート軸。'beat' なら Beat-PT、'rate' なら Rate-PT で降順。 */
const shareSortMode = ref<'beat' | 'rate'>('beat');

/** 【関数の役割】 シェアオプションを開く。ユーザーはソート軸（Beat or Rate）を選択できる。 */
const openShareOptions = () => {
  isShareOptionsOpen.value = true;
};

/** 【関数の役割】 シェアオプションを閉じて本体の共有処理を走らせる。 */
const confirmShare = () => {
  isShareOptionsOpen.value = false;
  shareOnX();
};

/**
 * 【関数の役割】 X 向けの 1080x1920 PNG を生成し、共有可能な経路で送り出す。
 * 優先順位:
 *  1. Web Share API（navigator.share）でファイルごと共有
 *  2. Clipboard.write で PNG をコピーしてから twitter.com/intent/tweet を開く
 *  3. 上記が失敗したら自動ダウンロード + ツイート画面オープン
 * iOS Safari での user-gesture チェーンを切らさないため、toBlob は Promise 化して await する。
 */
const shareOnX = async () => {
  if (!shareContainer.value || isSharing.value) return;
  isSharing.value = true;

  const textParam = encodeURIComponent(`${t('report.shareText')}\nhttps://beat-seeker.com \n#BeatSeeker`);

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

    // iOS Safari での user-gesture を切らさないためにコールバックではなく await で Blob 化する。
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'));
    if (!blob) throw new Error('Blob is null');

    const file = new File([blob], 'beat-seeker-report.png', { type: 'image/png' });

    // まず Web Share API を試す（iOS Safari / Android / 最新デスクトップ Chrome）。
    if (navigator.share && navigator.canShare && navigator.canShare({ files: [file] })) {
      try {
        await navigator.share({
          title: 'beat-seeker Report',
          text: `${t('report.shareText')}\nhttps://beat-seeker.com \n#BeatSeeker`,
          files: [file]
        });
        isSharing.value = false;
        return;
      } catch (e) {
        if ((e as Error).name === 'AbortError') {
          isSharing.value = false;
          return;
        }
        // AbortError 以外はクリップボード経路にフォールバック。
      }
    }

    // フォールバック: Clipboard API で画像コピー → Twitter 投稿画面を開く。
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

/**
 * 【関数の役割】 informalRank 文字列から数値部分（例: "12.0"）だけを抽出。
 * "Uncategorized(other)" 等の非数値ランクは null を返す（バッジ表示の判定に使う）。
 */
const getNumericRank = (informalRank: string | undefined): string | null => {
  if (!informalRank) return null;
  const m = informalRank.match(/(\d+\.\d+)/);
  return m ? m[1] : null;
};

/**
 * 【関数の役割】 1 譜面の score rate と informalRank から、単曲ティア（フォルダランクと同基準）を返す。
 * informalRank（☆11.0〜13.0）が無い・スコアが 0 以下・非数値ランクの場合は null。
 */
const getSongTierInfoByRate = (rate: number | undefined, informalRank: string | undefined): RankInfo | null => {
  const rank = getNumericRank(informalRank);
  if (!rank || !rate || rate <= 0) return null;
  const info = getFolderRankInfoByRate(rate, rank);
  if (!info || info.name === 'Beginner') return null;
  return info;
};

const getSongTierInfo = (song: { scoreRate?: number; informalRank?: string }): RankInfo | null => {
  return getSongTierInfoByRate(song.scoreRate, song.informalRank);
};

/**
 * 【関数の役割】 旧スコアでの単曲ティアと新スコアでの単曲ティアを返す。
 * スコア更新でティアが変動した場合のみ `oldTier` を非 null で返す（変動なしなら null）。
 * 旧スコア = 0 や maxScore 不明の場合は oldTier は出さない（=「初プレイで上がった」扱い）。
 */
const getSongTierTransition = (song: { scoreRate?: number; oldScore?: number; maxScore?: number; informalRank?: string }): { oldTier: RankInfo | null; newTier: RankInfo | null } => {
  const newTier = getSongTierInfoByRate(song.scoreRate, song.informalRank);
  if (!newTier) return { oldTier: null, newTier: null };
  const maxScore = song.maxScore ?? 0;
  const oldScore = song.oldScore ?? 0;
  if (maxScore <= 0 || oldScore <= 0) return { oldTier: null, newTier };
  const oldRate = (oldScore / maxScore) * 100;
  const oldTier = getSongTierInfoByRate(oldRate, song.informalRank);
  if (!oldTier) return { oldTier: null, newTier };
  const same = oldTier.name === newTier.name && (oldTier.tier ?? '') === (newTier.tier ?? '');
  return { oldTier: same ? null : oldTier, newTier };
};

/**
 * 【関数の役割】 難易度名を受け取って、BEG/NOR/HYP/ANO/LEG の IIDX 慣用色（Tailwind クラス）を返す。
 */
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

/**
 * 【関数の役割】 CLEAR TYPE（FULLCOMBO / EX HARD / HARD / CLEAR / EASY / ASSIST / FAILED）に応じた色を返す。
 * 未対応タイプはグレー。
 */
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
