<template>
  <div class="w-full space-y-6 animate-fade-in">
    <!-- Email Registration Prompt (未登録の場合のみ表示) -->
    <div v-if="user && !user.email" class="w-full bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-700/50 rounded-md p-4 flex flex-col sm:flex-row items-start sm:items-center gap-3">
      <div class="flex items-center gap-3 flex-1">
        <div class="w-9 h-9 bg-amber-100 dark:bg-amber-900/50 rounded-md flex items-center justify-center shrink-0">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
          </svg>
        </div>
        <div>
          <p class="text-sm font-bold text-amber-800 dark:text-amber-300">{{ t('dashboard.emailNotRegistered') }}</p>
          <p class="text-xs text-amber-600 dark:text-amber-400">{{ t('dashboard.emailHint') }}</p>
        </div>
      </div>
      <button @click="$emit('open-profile-edit')" class="shrink-0 px-4 py-2 bg-amber-500 hover:bg-amber-400 text-white text-sm font-bold rounded-md transition-colors shadow-sm">
        {{ t('dashboard.registerNow') }}
      </button>
    </div>

    <!-- ツールバー: 歴代ベスト反映トグル（過去作を取り込み済みの本人のみ）／簡易表示・カスタマイズ（自分のダッシュボードのみ） -->
    <div v-if="(canUseAllTime && hasPastImports) || canCustomize" class="flex flex-wrap items-center gap-3">
      <label v-if="canUseAllTime && hasPastImports" class="flex items-center gap-2 cursor-pointer group whitespace-nowrap" :title="t('past.toggleHint')">
        <div class="relative inline-flex items-center">
          <input type="checkbox" :checked="showAllTime" @change="toggleAllTime" class="sr-only peer">
          <div class="w-9 h-5 bg-slate-200 dark:bg-slate-700 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-amber-300 dark:peer-focus:ring-amber-800 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white dark:peer-checked:after:border-slate-800 after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white dark:after:bg-slate-800 after:border-slate-300 dark:after:border-slate-600 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-amber-500"></div>
        </div>
        <span
          class="text-xs sm:text-sm font-bold transition-colors"
          :class="showAllTime ? 'text-amber-600 dark:text-amber-400' : 'text-slate-600 dark:text-slate-400 group-hover:text-slate-800 dark:group-hover:text-slate-200'"
        >{{ t('past.toggle') }}</span>
        <span v-if="isLoadingPast" class="w-3 h-3 border-2 border-amber-500/30 border-t-amber-500 rounded-full animate-spin"></span>
      </label>
      <span v-if="canUseAllTime && hasPastImports && showAllTime" class="text-xs text-amber-600 dark:text-amber-400">{{ t('past.tierNote') }}</span>

      <!-- 表示モード（通常／簡易／カスタマイズ。言語設定と同じく選んだ瞬間に保存）と、カスタマイズ項目の編集 -->
      <div v-if="canCustomize" class="ml-auto flex items-center gap-2">
        <DashboardModeSelect />
        <button
          type="button"
          @click="showCustomizeModal = true"
          :title="t('dashboard.layout.edit')"
          :aria-label="t('dashboard.layout.edit')"
          class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 text-xs font-bold text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
        >
          <svg class="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
            <path stroke-linecap="round" stroke-linejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
          <span class="max-sm:hidden">{{ t('dashboard.layout.edit') }}</span>
        </button>
      </div>
    </div>

    <!-- ウィジェットを設定の並び順で描画する（自分のダッシュボード以外は初期配置。閲覧モードごとの出せる/出せないは visibleWidgets 側で絞る） -->
    <template v-for="w in visibleWidgets" :key="w">
      <!-- ティアカード（簡易表示: 2 つのティアを 1 枚のカードに横並び・小アイコンで） -->
      <div
        v-if="w === 'tier' && isSimpleView"
        class="bg-white dark:bg-slate-800 p-4 rounded-md border border-slate-200 dark:border-slate-700 grid grid-cols-1 gap-4 transition-colors duration-200"
        :class="{ 'sm:grid-cols-2': showRateTier }"
      >
        <div class="flex items-center gap-3 min-w-0">
          <RankIcon :rank-name="rankInfo.name" :tier="rankInfo.tier" size="md" :is-supporter="iconGloss" v-bind="beatFrame" />
          <div class="flex-1 min-w-0">
            <div class="flex items-center justify-between gap-2">
              <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500">Beat-Tier<span v-if="showAllTime" class="ml-1.5 px-1.5 py-0.5 text-[9px] rounded bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300">{{ t('past.tierBadge') }}</span></p>
              <button type="button" @click="showInfoModal = true" :title="t('dashboard.whatIsBeatTier')" :aria-label="t('dashboard.whatIsBeatTier')" class="text-blue-500 dark:text-blue-400 hover:text-blue-700 dark:hover:text-blue-300 transition-colors shrink-0">
                <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
              </button>
            </div>
            <p class="text-lg font-bold leading-tight truncate" :class="rankInfo.color">{{ rankInfo.name }} {{ rankInfo.tier || '' }}</p>
            <p class="text-[11px] font-bold text-slate-400 dark:text-slate-500 tabular-nums truncate">
              {{ displayBeatPt.toFixed(1) }} pt<template v-if="nextRankInfo.nextRank"> · Next: {{ nextRankInfo.nextRank.name }} {{ nextRankInfo.nextRank.tier || '' }} ({{ t('dashboard.remaining') }} {{ remainingPt(nextRankInfo.nextRank.minPoints, displayBeatPt) }} pt)</template>
            </p>
            <div class="w-full mt-2 bg-slate-100 dark:bg-slate-700 h-1 rounded-full overflow-hidden">
              <div class="h-full bg-blue-500 dark:bg-blue-400 rounded-full transition-all duration-1000" :style="{ width: `${nextRankInfo.progress}%` }"></div>
            </div>
          </div>
        </div>
        <div v-if="showRateTier" class="flex items-center gap-3 min-w-0">
          <RankIcon :rank-name="rateTierRankInfo.name" :tier="rateTierRankInfo.tier" size="md" :is-supporter="iconGloss" v-bind="rateFrame" />
          <div class="flex-1 min-w-0">
            <div class="flex items-center justify-between gap-2">
              <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500">Rate-Tier<span v-if="showAllTime" class="ml-1.5 px-1.5 py-0.5 text-[9px] rounded bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300">{{ t('past.tierBadge') }}</span></p>
              <button type="button" @click="showRateInfoModal = true" :title="t('dashboard.whatIsRateTier')" :aria-label="t('dashboard.whatIsRateTier')" class="text-emerald-500 dark:text-emerald-400 hover:text-emerald-700 dark:hover:text-emerald-300 transition-colors shrink-0">
                <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
              </button>
            </div>
            <p class="text-lg font-bold leading-tight truncate" :class="rateTierRankInfo.color">{{ rateTierRankInfo.name }} {{ rateTierRankInfo.tier || '' }}</p>
            <p class="text-[11px] font-bold text-slate-400 dark:text-slate-500 tabular-nums truncate">
              {{ displayRatePt.toFixed(1) }} pt<template v-if="rateTierNextRankInfo.nextRank"> · Next: {{ rateTierNextRankInfo.nextRank.name }} {{ rateTierNextRankInfo.nextRank.tier || '' }} ({{ t('dashboard.remaining') }} {{ remainingPt(rateTierNextRankInfo.nextRank.minPoints, displayRatePt) }} pt)</template>
            </p>
            <div class="w-full mt-2 bg-slate-100 dark:bg-slate-700 h-1 rounded-full overflow-hidden">
              <div class="h-full bg-emerald-500 dark:bg-emerald-400 rounded-full transition-all duration-1000" :style="{ width: `${rateTierNextRankInfo.progress}%` }"></div>
            </div>
          </div>
        </div>
      </div>

      <!-- ティアカード（通常表示） -->
      <div v-else-if="w === 'tier'" class="grid grid-cols-1 gap-6" :class="{ 'sm:grid-cols-2': showRateTier }">
      <!-- Beat-Tier (Lv11/12) -->
      <div class="bg-white dark:bg-slate-800 p-6 rounded-md border border-slate-200 dark:border-slate-700 flex flex-col items-center justify-center relative overflow-hidden transition-colors duration-200">
        <div class="absolute top-4 right-4 z-20">
          <button
            @click="showInfoModal = true"
            class="group flex items-center gap-1.5 text-blue-500 dark:text-blue-400 hover:text-blue-700 dark:hover:text-blue-300 transition-all font-bold"
          >
            <span class="text-[10px] opacity-0 group-hover:opacity-100 transition-opacity">{{ t('dashboard.whatIsBeatTier') }}</span>
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          </button>
        </div>
        <p class="text-sm font-medium text-slate-500 dark:text-slate-400 mb-1 z-10 font-bold">Beat-Tier<span v-if="showAllTime" class="ml-1.5 px-1.5 py-0.5 text-[9px] rounded bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300">{{ t('past.tierBadge') }}</span></p>
        <div class="flex flex-col items-center z-10 text-center">
          <RankIcon :rank-name="rankInfo.name" :tier="rankInfo.tier" size="lg" class="mb-2" :is-supporter="iconGloss" v-bind="beatFrame" />
          <h3 class="text-2xl sm:text-3xl font-bold mb-1 line-clamp-1" :class="rankInfo.color">
            {{ rankInfo.name }} {{ rankInfo.tier || '' }}
          </h3>
          <p class="text-xs sm:text-sm font-bold text-slate-400 dark:text-slate-500">{{ displayBeatPt.toFixed(1) }} pt</p>
        </div>
        <!-- Progress Bar -->
        <div class="w-full mt-4 bg-slate-100 dark:bg-slate-700 h-1.5 rounded-full overflow-hidden z-10">
          <div
            class="h-full bg-blue-500 dark:bg-blue-400 rounded-full transition-all duration-1000"
            :style="{ width: `${nextRankInfo.progress}%` }"
          ></div>
        </div>
        <p v-if="nextRankInfo.nextRank" class="text-[10px] font-bold text-slate-400 dark:text-slate-500 mt-2 z-10 text-center">
          Next: {{ nextRankInfo.nextRank.name }} {{ nextRankInfo.nextRank.tier || '' }}<br/>
          {{ t('dashboard.remaining') }} ({{ remainingPt(nextRankInfo.nextRank.minPoints, displayBeatPt) }} pt)
        </p>
      </div>

      <!-- Rate-Tier (全難度 ANOTHER/LEGGENDARIA) -->
      <div v-if="showRateTier" class="bg-white dark:bg-slate-800 p-6 rounded-md border border-slate-200 dark:border-slate-700 flex flex-col items-center justify-center relative overflow-hidden transition-colors duration-200">
        <div class="absolute top-4 right-4 z-20">
          <button
            @click="showRateInfoModal = true"
            class="group flex items-center gap-1.5 text-emerald-500 dark:text-emerald-400 hover:text-emerald-700 dark:hover:text-emerald-300 transition-all font-bold"
          >
            <span class="text-[10px] opacity-0 group-hover:opacity-100 transition-opacity">{{ t('dashboard.whatIsRateTier') }}</span>
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          </button>
        </div>
        <p class="text-sm font-medium text-slate-500 dark:text-slate-400 mb-1 z-10 font-bold">Rate-Tier<span v-if="showAllTime" class="ml-1.5 px-1.5 py-0.5 text-[9px] rounded bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300">{{ t('past.tierBadge') }}</span></p>
        <div class="flex flex-col items-center z-10 text-center">
          <RankIcon :rank-name="rateTierRankInfo.name" :tier="rateTierRankInfo.tier" size="lg" class="mb-2" :is-supporter="iconGloss" v-bind="rateFrame" />
          <h3 class="text-2xl sm:text-3xl font-bold mb-1 line-clamp-1" :class="rateTierRankInfo.color">
            {{ rateTierRankInfo.name }} {{ rateTierRankInfo.tier || '' }}
          </h3>
          <p class="text-xs sm:text-sm font-bold text-slate-400 dark:text-slate-500">{{ displayRatePt.toFixed(1) }} pt</p>
        </div>
        <!-- Progress Bar -->
        <div class="w-full mt-4 bg-slate-100 dark:bg-slate-700 h-1.5 rounded-full overflow-hidden z-10">
          <div
            class="h-full bg-emerald-500 dark:bg-emerald-400 rounded-full transition-all duration-1000"
            :style="{ width: `${rateTierNextRankInfo.progress}%` }"
          ></div>
        </div>
        <p v-if="rateTierNextRankInfo.nextRank" class="text-[10px] font-bold text-slate-400 dark:text-slate-500 mt-2 z-10 text-center">
          Next: {{ rateTierNextRankInfo.nextRank.name }} {{ rateTierNextRankInfo.nextRank.tier || '' }}<br/>
          {{ t('dashboard.remaining') }} ({{ remainingPt(rateTierNextRankInfo.nextRank.minPoints, displayRatePt) }} pt)
        </p>
        <!-- DJ Name pie (topRanker only) -->
        <div v-if="isTopRankerView && rateTierDjPie.length > 0" class="w-full mt-5 pt-4 border-t border-slate-100 dark:border-slate-700 z-10 flex flex-col sm:flex-row items-center gap-4">
          <svg viewBox="0 0 100 100" class="w-24 h-24 shrink-0" role="img" aria-label="DJNAME別 対象入り曲 内訳">
            <path v-for="(slice, i) in rateTierDjPie" :key="i" :d="slice.path" :fill="slice.color" stroke="white" stroke-width="0.5" />
          </svg>
          <ul class="flex-1 w-full grid grid-cols-2 sm:grid-cols-1 gap-x-3 gap-y-1 max-h-[28rem] overflow-y-auto text-[10px] sm:text-[11px]">
            <li v-for="(slice, i) in rateTierDjPie" :key="i" class="flex items-center gap-1.5 min-w-0">
              <span class="w-2.5 h-2.5 rounded-sm shrink-0" :style="{ backgroundColor: slice.color }"></span>
              <span class="truncate font-bold text-slate-700 dark:text-slate-200" :title="slice.name">{{ slice.name }}</span>
              <span class="ml-auto tabular-nums text-slate-500 dark:text-slate-400 shrink-0">{{ slice.count }}曲 ({{ slice.pct.toFixed(0) }}%)</span>
            </li>
          </ul>
        </div>
      </div>
      </div>

      <!-- 現在の DIVISION（リーグ参加中・自分のダッシュボードのみ。未参加なら何も描かない） -->
      <LeagueDivisionPanel v-else-if="w === 'league'" @open-league="emit('open-league')" />

      <!-- ランキング順位・ロードマップ レベル・前後のプレイヤー -->
      <div v-else-if="w === 'ranking'" class="bg-white dark:bg-slate-800 p-4 sm:p-6 rounded-md border border-slate-200 dark:border-slate-700 transition-colors duration-200">
        <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
          <!-- 順位とロードマップレベルを横並び（狭い画面では折り返す。flex-col/sm: は output.css に負けるので使わない） -->
          <div class="flex flex-wrap items-end gap-x-10 gap-y-4">
          <div>
            <p class="text-[10px] font-bold text-slate-400 mb-1">{{ t('dashboard.currentRank') }}</p>
            <div class="flex items-end gap-3">
              <span v-if="myRankingPosition" class="text-5xl font-bold text-slate-800 dark:text-slate-100 tabular-nums leading-none"># {{ myRankingPosition.position }}</span>
              <span v-else class="text-3xl font-bold text-slate-300 dark:text-slate-600 leading-none">-</span>
              <div v-if="myRankingPosition" class="flex flex-col pb-1 gap-0.5">
                <span class="text-sm font-bold text-slate-400">/ {{ myRankingPosition.total }} {{ t('dashboard.outOf') }}</span>
                <span v-if="myRankingPosition.rankChange === null" class="text-[11px] font-bold text-blue-500 uppercase">{{ t('dashboard.newEntry') }}</span>
                <span v-else-if="myRankingPosition.rankChange > 0" class="text-[11px] font-bold text-emerald-500">{{ t('dashboard.rankUp', { n: myRankingPosition.rankChange }) }}</span>
                <span v-else-if="myRankingPosition.rankChange < 0" class="text-[11px] font-bold text-red-500">{{ t('dashboard.rankDown', { n: Math.abs(myRankingPosition.rankChange) }) }}</span>
                <span v-else class="text-[11px] font-bold text-slate-400 dark:text-slate-500">{{ t('dashboard.rankNoChange') }}</span>
              </div>
            </div>
          </div>
          <!-- スコアロードマップのレベル（自分の分は押すとロードマップを開く） -->
          <component
            :is="roadmapTarget === 'self' ? 'button' : 'div'"
            v-if="roadmapLevel"
            class="text-left rounded-md"
            :class="roadmapTarget === 'self' ? 'group cursor-pointer' : ''"
            :title="roadmapTarget === 'self' ? t('dashboard.roadmapOpen') : undefined"
            @click="roadmapTarget === 'self' && emit('open-roadmap')"
          >
            <p class="text-[10px] font-bold text-slate-400 mb-1">{{ t('dashboard.roadmapLevel') }}</p>
            <div class="flex items-end gap-2">
              <span class="text-5xl font-bold text-blue-700 dark:text-blue-300 tabular-nums leading-none group-hover:underline">{{ formatRoadmapLevel(roadmapLevel.level) }}</span>
              <span class="text-sm font-bold text-slate-400 pb-1">/ {{ roadmapLevel.maxLevel }}</span>
            </div>
          </component>
          </div>
          <div v-if="myRankingPosition" class="flex items-center gap-2 self-end sm:self-auto">
            <div class="text-right">
              <p class="text-[10px] font-bold text-slate-400 mb-0.5">{{ t('dashboard.neighbors') }}</p>
              <div class="space-y-0.5">
                <p v-if="rankingNeighbors.above" class="text-xs text-slate-500 dark:text-slate-400 tabular-nums">
                  ▲ {{ rankingNeighbors.above.displayName }} ({{ rankingNeighbors.above.totalBeatPt.toFixed(1) }} pt)
                </p>
                <p class="text-xs font-bold text-blue-600 dark:text-blue-400 tabular-nums">
                  ▶ {{ isViewingOther ? (viewingDisplayName || t('dashboard.you')) : t('dashboard.you') }} ({{ totalPoints.toFixed(1) }} pt)
                </p>
                <p v-if="rankingNeighbors.below" class="text-xs text-slate-500 dark:text-slate-400 tabular-nums">
                  ▼ {{ rankingNeighbors.below.displayName }} ({{ rankingNeighbors.below.totalBeatPt.toFixed(1) }} pt)
                </p>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- ☆12 クイック統計 -->
      <div v-else-if="w === 'lv12'" class="bg-white dark:bg-slate-800 p-4 sm:p-6 rounded-md border border-slate-200 dark:border-slate-700 transition-colors duration-200">
        <div class="grid grid-cols-2 md:grid-cols-4 gap-3 sm:gap-4">
          <div class="flex flex-col items-center justify-center p-3 rounded-md bg-slate-50/50 dark:bg-slate-700/30 border border-slate-100 dark:border-slate-700">
            <p class="text-[9px] font-bold text-slate-400 mb-1">{{ t('dashboard.lv12Total') }}</p>
            <h3 class="text-2xl sm:text-3xl font-bold tabular-nums text-slate-700 dark:text-slate-200">{{ lv12Total }}</h3>
          </div>
          <div class="flex flex-col items-center justify-center p-3 rounded-md bg-blue-50/30 dark:bg-blue-900/10 border border-blue-100/50 dark:border-blue-800/50 border-t-2 border-t-blue-400 dark:border-t-blue-500">
            <p class="text-[9px] font-bold text-blue-400 dark:text-blue-500 mb-1">{{ t('dashboard.lv12ClearRate') }}</p>
            <h3 class="text-2xl sm:text-3xl font-bold tabular-nums text-blue-600 dark:text-blue-400 flex items-baseline gap-0.5">
              {{ lv12ClearRate }}<span class="text-sm font-bold opacity-70">%</span>
            </h3>
          </div>
          <div class="flex flex-col items-center justify-center p-3 rounded-md bg-amber-50/30 dark:bg-amber-900/10 border border-amber-100/50 dark:border-amber-800/50 border-t-2 border-t-amber-400 dark:border-t-amber-500">
            <p class="text-[9px] font-bold text-amber-500 mb-1">{{ t('dashboard.lv12AaaRate') }}</p>
            <h3 class="text-2xl sm:text-3xl font-bold tabular-nums text-amber-500 flex items-baseline gap-0.5">
              {{ lv12AaaRate }}<span class="text-sm font-bold opacity-70">%</span>
            </h3>
          </div>
          <div class="flex flex-col items-center justify-center p-3 rounded-md bg-purple-50/30 dark:bg-purple-900/10 border border-purple-100/50 dark:border-purple-800/50 border-t-2 border-t-purple-400 dark:border-t-purple-500">
            <p class="text-[9px] font-bold text-purple-400 dark:text-purple-500 mb-1" :title="t('dashboard.maxMinusHint')">{{ t('dashboard.lv12MaxMinusRate') }}</p>
            <h3 class="text-2xl sm:text-3xl font-bold tabular-nums text-purple-600 dark:text-purple-400 flex items-baseline gap-0.5">
              {{ lv12MaxMinusRate }}<span class="text-sm font-bold opacity-70">%</span>
            </h3>
          </div>
        </div>
      </div>

      <!-- 非公式難易度表（歴代トグル ON なら歴代ベストで集計。成長記録だけは現行作基準） -->
      <UnofficialDifficultyTable
        v-else-if="w === 'diffTable'"
        :scores="displayScores"
        :history-scores="allFlattenedScores"
        :current-all-time-best="currentAllTimeBest"
        @folder-open="tableFolderOpened = true"
      />

      <!-- ランクアップアドバイス: 自分のダッシュボードと、管理者が他ユーザーを閲覧しているときだけ出す（widgetAvailable）。
           管理者閲覧では相手のユーザー ID を渡し、相手の推薦を管理者用 API から引く。
           フレンド閲覧などは相手の推薦を引く API が無く、自分の推薦を相手の残り pt と並べても意味が無いので出さない。 -->
      <RankUpAdvice
        v-else-if="w === 'advice'"
        :total-points="props.totalPoints"
        :viewing-user-id="viewingMode === 'admin' ? (viewingUserId ?? null) : null"
        :viewing-display-name="viewingMode === 'admin' ? viewingDisplayName : undefined"
      />

      <!-- 全体ニュース -->
      <ActivityFeed v-else-if="w === 'activity'" />

      <!-- 最近の更新（最後に CSV を取り込んだ日に更新された譜面。同日分はまとめる） -->
      <RecentPlayUpdates
        v-else-if="w === 'recentPlays'"
        :viewing-user-id="viewingMode === 'admin' ? (viewingUserId ?? null) : null"
        :scores="allFlattenedScores"
        @open-history="emit('open-history')"
      />

      <!-- プロフィールの小見出し（成長サマリー／時系列推移／クリア状況など）。初期設定では非表示で、カスタマイズで足す -->
      <ProfileDashboard v-else-if="isProfileWidget(w)" :sections="PROFILE_WIDGET_SECTIONS[w]" :reload-key="scoresVersion" />
    </template>

    <!-- Private user notice: hide per-song breakdowns since scores are unavailable -->
    <div v-if="isPrivateView" class="bg-amber-50 dark:bg-amber-900/20 p-5 rounded-md border border-amber-200 dark:border-amber-800 text-center">
      <div class="flex items-center justify-center gap-2 text-amber-700 dark:text-amber-300 font-bold">
        <span>🔒</span>
        <span>{{ t('dashboard.privateUserNotice') }}</span>
      </div>
    </div>

    <!-- Info Modal -->
    <BeatTierInfoModal v-if="showInfoModal" @close="showInfoModal = false" />
    <RateTierInfoModal v-if="showRateInfoModal" @close="showRateInfoModal = false" />
    <DashboardCustomizeModal v-if="showCustomizeModal" @close="showCustomizeModal = false" />
  </div>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 メインダッシュボードのスコアカード一式。
 * - Beat-Tier（Lv11/12 ANOTHER 中心）と Rate-Tier（全曲対象）の 2 系統ランクを並置
 * - 次ランクまでの差分、進捗バー、ランクアップに効く推奨曲（RankUpAdvice）を表示
 * - 非公式難易度表のランク別集計（UnofficialDifficultyTable）も埋込み
 * - 閲覧モード (admin/friend/public/topRanker/private) によって利用可能な情報範囲を切替
 * - TOP ランカー閲覧時はバーチャルプロフィール用に DJ 名ごとの TOP100 譜面を円グラフ化する特殊表示
 * - 各セクションは「ウィジェット」（useDashboardLayout の DashboardWidgetId）として、設定の並び順で描画する。
 *   自分のダッシュボードではツールバーから簡易表示モードの切替とカスタマイズ（表示/非表示・並び順）ができ、
 *   他人の閲覧・共有ページでは常に初期配置。閲覧モードごとの「出せるか」は widgetAvailable で別に絞る。
 *
 * @prop scores 階層化されたスコアデータ。
 * @prop totalPoints 現在の Beat-PT 合計。
 * @prop viewingIidxId 他人を閲覧中なら IIDX ID が入る（自分のダッシュボード表示時は undefined）。
 * @prop viewingMode 閲覧モード。UI の「編集ボタン」表示・API 呼出先・機能制限の分岐に使用。
 * @prop rateTierPointsOverride TOP ランカー等、サーバ算出済み Rate-PT が優先される場合に使用する値。
 * @emits open-profile-edit メール未登録等の促しバナーからプロフィール編集を要求。
 * @emits open-roadmap ロードマップ レベルの押下でスコアロードマップ画面へ。
 * @emits open-league 現在の DIVISION パネルの押下でリーグ画面へ。
 */
import { computed, ref, watch, onMounted } from 'vue';
import { useI18n } from '../composables/useI18n';
import type { ScoreData } from '../types/ScoreData';
import {
  getRankInfo, getNextRankInfo,
  getRateTierRankInfo, getNextRateTierRankInfo,
  calculateScoreRateTierPoints, calculateTotalPoints,
  previousTierFrame,
} from '../utils/beatTier';
import { usePastScores, chartKey } from '../composables/usePastScores';
import type { PastBest } from '../composables/usePastScores';
import BeatTierInfoModal from './BeatTierInfoModal.vue';
import RateTierInfoModal from './RateTierInfoModal.vue';
import RankIcon from './RankIcon.vue';
import UnofficialDifficultyTable from './UnofficialDifficultyTable.vue';
import RankUpAdvice from './RankUpAdvice.vue';
import ActivityFeed from './ActivityFeed.vue';
import LeagueDivisionPanel from './LeagueDivisionPanel.vue';
import DashboardCustomizeModal from './DashboardCustomizeModal.vue';
import DashboardModeSelect from './DashboardModeSelect.vue';
import RecentPlayUpdates from './RecentPlayUpdates.vue';
import ProfileDashboard from './ProfileDashboard.vue';
import { useAuth } from '../composables/useAuth';
import { flattenScores } from '../utils/scoreData';
import { formatRoadmapLevel } from '../utils/roadmapLevels';
import { useRateTierVisibility } from '../composables/useRateTierVisibility';
import { useDashboardLayout, NORMAL_WIDGETS, SIMPLE_WIDGETS, type DashboardWidgetId } from '../composables/useDashboardLayout';

const { showRateTier } = useRateTierVisibility();
const { t } = useI18n();
const { user, authHeaders } = useAuth();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

const emit = defineEmits<{
  (e: 'open-profile-edit'): void;
  (e: 'open-roadmap'): void;
  (e: 'open-league'): void;
  (e: 'open-history'): void;
}>();

/** プロフィール由来のウィジェット ID → ProfileDashboard に渡す小見出し（sections prop）。 */
const PROFILE_WIDGET_SECTIONS = {
  profileSummary: ['growthSummary'],
  profileTrends: ['growthTrends'],
  profileClearStatus: ['clearStatus'],
  profileInformalClear: ['informalClear'],
  profileTop100Dist: ['top100Dist'],
  profileTop10: ['top10'],
} as const;
type ProfileWidgetId = keyof typeof PROFILE_WIDGET_SECTIONS;
const isProfileWidget = (w: DashboardWidgetId): w is ProfileWidgetId => w in PROFILE_WIDGET_SECTIONS;

const props = defineProps<{
  scores: ScoreData[];
  totalPoints: number;
  /** 閲覧対象ユーザーの DB 主キー。管理者閲覧時にランクアップアドバイスを相手のものに切り替えるために使う。 */
  viewingUserId?: number | null;
  viewingIidxId?: string;
  viewingDisplayName?: string;
  viewingMode?: 'admin' | 'friend' | 'public' | 'topRanker' | 'arenaTopRanker' | 'private' | null;
  rateTierPointsOverride?: number | null;
  /** 閲覧対象ユーザーの前作 BEAT-PT / RATE-PT（ティアアイコンの外枠用）。他人閲覧時のみ使う。 */
  previousBeatPt?: number | null;
  previousRatePt?: number | null;
  /** 閲覧対象ユーザーがサポーターか（ティアアイコンの光沢用）。他人閲覧時のみ使う。 */
  viewingIsSupporter?: boolean | null;
}>();

/** 【computed の役割】 他ユーザーを閲覧中かどうか（viewingIidxId が存在する）。 */
const isViewingOther = computed(() => !!props.viewingIidxId);

/**
 * スコアが入れ替わった回数。プロフィール系ウィジェットは履歴・スコアを自分で API から引くので、
 * アップロード後などに props.scores が変わったらこれを合図に取り直させる（reloadKey）。
 * （props の定義より後に置くこと。watch の getter は登録時に即実行される）
 */
const scoresVersion = ref(0);
watch(() => props.scores, () => { scoresVersion.value++; });

/** 【computed の役割】 BEAT-TIER アイコンの外枠（前作ティア）。自分なら /me の値、他人閲覧なら props。 */
const beatFrame = computed(() =>
  previousTierFrame(isViewingOther.value ? props.previousBeatPt : user.value?.previousBeatPt, 'beat'));
/** 【computed の役割】 RATE-TIER アイコンの外枠（前作 RATE-TIER）。 */
const rateFrame = computed(() =>
  previousTierFrame(isViewingOther.value ? props.previousRatePt : user.value?.previousRatePt, 'rate'));
/** 【computed の役割】 アイコンの光沢（サポーター特典）。閲覧対象本人の属性を見る。 */
const iconGloss = computed(() => (isViewingOther.value ? !!props.viewingIsSupporter : !!user.value?.isSupporter));
/** 【computed の役割】 都道府県 TOP ランカー（バーチャル）の閲覧中かどうか。 */
const isTopRankerView = computed(() => props.viewingMode === 'topRanker');
/** 【computed の役割】 プライベート設定ユーザーの閲覧中かどうか（詳細非表示モード）。 */
const isPrivateView = computed(() => props.viewingMode === 'private');

// ---- ダッシュボードの表示設定（簡易表示モード／ウィジェットの表示と並び順）----
const { mode: layoutMode, isSimple, customOrder } = useDashboardLayout();
const showCustomizeModal = ref(false);

/**
 * 【computed の役割】 自分のダッシュボードか（ログイン中・他人の閲覧でない・閲覧モード無し）。
 * 表示設定（簡易表示・カスタマイズ）と歴代ベストのトグルはここでだけ有効。
 */
const isOwnDashboard = computed(() => !!user.value && !isViewingOther.value && !props.viewingMode);
const canCustomize = isOwnDashboard;
/** 【computed の役割】 簡易表示で描くか。他人の閲覧では自分の設定を持ち込まない。 */
const isSimpleView = computed(() => isOwnDashboard.value && isSimple.value);

/**
 * ウィジェットごとの「この閲覧状況で出せるか」。ユーザーの表示設定とは独立した制約で、
 * 従来の各セクションの v-if をそのまま移したもの。
 */
const widgetAvailable: Record<DashboardWidgetId, () => boolean> = {
  tier: () => true,
  league: () => isOwnDashboard.value,
  ranking: () => !isTopRankerView.value && !isPrivateView.value,
  lv12: () => !isTopRankerView.value && !isPrivateView.value,
  // 取り込み履歴は本人か管理者しか引けない（ランクアップアドバイスと同じ条件）
  recentPlays: () => isOwnDashboard.value || (props.viewingMode === 'admin' && !!props.viewingUserId),
  diffTable: () => !isPrivateView.value,
  advice: () => !isPrivateView.value && (!props.viewingMode || props.viewingMode === 'admin'),
  activity: () => !isPrivateView.value,
  // プロフィールの内容は本人の履歴・スコアを API から引くので自分のダッシュボードだけ
  profileSummary: () => isOwnDashboard.value,
  profileTrends: () => isOwnDashboard.value,
  profileClearStatus: () => isOwnDashboard.value,
  profileInformalClear: () => isOwnDashboard.value,
  profileTop100Dist: () => isOwnDashboard.value,
  profileTop10: () => isOwnDashboard.value,
};

/**
 * 【computed の役割】 実際に描画するウィジェットの並び。
 * 自分のダッシュボード: 表示モードに従う（通常=初期配置 / 簡易=固定の最小セット / カスタマイズ=設定の並び）。
 * それ以外（他人の閲覧・共有ページ）: 初期配置。いずれも閲覧状況で出せないものは落とす。
 */
const visibleWidgets = computed<DashboardWidgetId[]>(() => {
  const order: readonly DashboardWidgetId[] = !isOwnDashboard.value
    ? NORMAL_WIDGETS
    : layoutMode.value === 'simple' ? SIMPLE_WIDGETS
    : layoutMode.value === 'custom' ? customOrder.value
    : NORMAL_WIDGETS;
  return order.filter(w => widgetAvailable[w]());
});

/** 【関数の役割】 次ティアまでの残り pt を表示用に整形する（到達済みなら "0"）。 */
const remainingPt = (minPoints: number, current: number) =>
  minPoints - current > 0 ? (minPoints - current).toFixed(1) : '0';

// DJ palette for pie slices
const DJ_PALETTE = [
  '#3b82f6', '#ef4444', '#10b981', '#f59e0b', '#8b5cf6',
  '#ec4899', '#14b8a6', '#f97316', '#6366f1', '#84cc16',
  '#06b6d4', '#a855f7', '#eab308', '#22c55e', '#f43f5e'
];

/**
 * 【関数の役割】 円グラフのスライス用 SVG path 配列を生成する。
 * 入力 { name, count } から比率を計算し、-90°（真上）開始で時計回りに描画する。
 * 単一スライスの場合は SVG A 要素が動作しないため円全体のフォールバック path を使う。
 */
function buildPieSlices(entries: { name: string; count: number }[]) {
  const total = entries.reduce((s, e) => s + e.count, 0);
  if (total === 0) return [] as Array<{ name: string; count: number; pct: number; color: string; path: string }>;
  let angle = -Math.PI / 2;
  return entries.map((e, i) => {
    const sweep = (e.count / total) * 2 * Math.PI;
    const x1 = 50 + 40 * Math.cos(angle);
    const y1 = 50 + 40 * Math.sin(angle);
    const endAngle = angle + sweep;
    const x2 = 50 + 40 * Math.cos(endAngle);
    const y2 = 50 + 40 * Math.sin(endAngle);
    const largeArc = sweep > Math.PI ? 1 : 0;
    let path: string;
    if (entries.length === 1) {
      path = 'M10,50 A40,40 0 1,1 90,50 A40,40 0 1,1 10,50 Z';
    } else {
      path = `M50,50 L${x1.toFixed(3)},${y1.toFixed(3)} A40,40 0 ${largeArc},1 ${x2.toFixed(3)},${y2.toFixed(3)} Z`;
    }
    angle = endAngle;
    return {
      name: e.name,
      count: e.count,
      pct: (e.count / total) * 100,
      color: DJ_PALETTE[i % DJ_PALETTE.length],
      path
    };
  });
}

/**
 * 【computed の役割】 TOP ランカー閲覧時の Rate-Tier DJ 名円グラフ。
 * ANOTHER / LEGGENDARIA に絞って scoreRate から Rate-PT を再計算し、上位 100 件を DJ 名で集計する。
 */
const rateTierDjPie = computed(() => {
  if (!isTopRankerView.value) return [];
  const top100 = allFlattenedScores.value
    .filter(s => ['ANOTHER', 'LEGGENDARIA'].includes(s.difficultyName) && s.scoreRate > 0)
    .map(s => ({ djName: (s.djName && s.djName.trim()) || '(不明)', pt: calculateScoreRateTierPoints(s.scoreRate) }))
    .filter(e => e.pt > 0)
    .sort((a, b) => b.pt - a.pt)
    .slice(0, 100);
  const counts = new Map<string, number>();
  for (const e of top100) {
    counts.set(e.djName, (counts.get(e.djName) || 0) + 1);
  }
  const entries = Array.from(counts.entries())
    .map(([name, count]) => ({ name, count }))
    .sort((a, b) => b.count - a.count);
  return buildPieSlices(entries);
});

// Beat-Tier / Rate-Tier の情報モーダル表示フラグ。
const showInfoModal = ref(false);
const showRateInfoModal = ref(false);

// ---- 歴代ベスト（過去作反映）----
const {
  fetchPastBest,
  fetchSummary: fetchPastSummary,
  applyAllTimeBest,
  pastBestByChart,
  hasPastImports,
  isLoaded: isPastLoaded,
  isLoading: isLoadingPast,
} = usePastScores();

/**
 * 「歴代ベストを反映」トグルの状態。
 * ON にすると Beat-Tier / Rate-Tier のカードが、過去作を含めた歴代ベストで再計算した
 * PT とティアを表示する。
 *
 * 重要: これは表示だけの切り替えで、サーバーに保存される値には一切影響しない。
 * 履歴ログ（成長記録）とランキングは現行作のスコアだけで算出され続ける。
 */
const showAllTime = ref(false);

/** 歴代ベストを扱えるか。過去作は本人のデータなので、他ユーザー閲覧中は出さない（= 自分のダッシュボードのみ）。 */
const canUseAllTime = isOwnDashboard;

/**
 * 【関数の役割】 トグルの切り替え。ON にする瞬間だけ過去作スコアを取得する
 * （数千件になり得るので、必要になるまで取りに行かない）。
 */
const toggleAllTime = async () => {
  if (showAllTime.value) {
    showAllTime.value = false;
    return;
  }
  try {
    await fetchPastBest();
    showAllTime.value = true;
  } catch {
    // 取得失敗時は OFF のまま据え置く
  }
};

// トグルの表示可否判定にだけ使う軽量なサマリを先に取得しておく。
onMounted(() => {
  if (!canUseAllTime.value) return;
  fetchPastSummary().catch(() => { /* 握り潰し: トグルが出ないだけ */ });
});

/** 【computed の役割】 階層化スコアをフラット配列に変換（曲 × 難易度 1 レコード）。 */
const allFlattenedScores = computed(() => flattenScores(props.scores));

/**
 * 非公式難易度表のフォルダが一度でも開かれたか。
 * 表の中の「今作で歴代自己ベスト」強調には過去作スコア（数千件になり得る）が要るが、
 * 強調が見えるのは開いたフォルダの中だけなので、開かれるまで取りに行かない。
 * サマリ取得より先に開かれることもあるので、条件が揃った時点で取得する。
 */
const tableFolderOpened = ref(false);
watch([tableFolderOpened, hasPastImports, canUseAllTime], ([opened, hasPast, canUse]) => {
  if (opened && hasPast && canUse) fetchPastBest().catch(() => { /* 握り潰し: 強調が出ないだけ */ });
});

/**
 * 【computed の役割】 今作のスコアが歴代自己ベストの譜面（非公式難易度表の行強調用）。
 * 譜面キー → 並んだ／超えた過去作ベスト。過去作にスコアが無い譜面（新曲の初スコアなど）は値が null。
 *
 * 「過去作が上回るときだけ過去作、同点・過去作なしは今作」は {@link applyAllTimeBest} や
 * スコア一覧の歴代ベスト作品フィルタと同じ規則。現行作のレコードから作るので歴代反映トグルには依存しない。
 * 過去作は本人のデータなので、他ユーザー閲覧中・未取得の間は null（強調なし）。
 * 過去作スコアが 1 件も無いときも null にする（比べる相手が無いのに全行が光ってしまうため）。
 */
const currentAllTimeBest = computed<Map<string, PastBest | null> | null>(() => {
  if (!canUseAllTime.value || !isPastLoaded.value) return null;
  const bestByChart = pastBestByChart();
  if (bestByChart.size === 0) return null;
  const map = new Map<string, PastBest | null>();
  allFlattenedScores.value.forEach(rec => {
    if (rec.score <= 0) return;
    const key = chartKey(rec.title, rec.difficultyName);
    const past = bestByChart.get(key);
    if (!past || past.score <= 0) map.set(key, null);
    else if (rec.score >= past.score) map.set(key, past);
  });
  return map;
});

/** 【computed の役割】 過去作のベストを重ねたレコード。歴代 PT の算出元。 */
const allTimeRecords = computed(() => applyAllTimeBest(allFlattenedScores.value));

/**
 * 【computed の役割】 一覧系コンポーネントへ渡す表示用レコード。
 * 「歴代ベストを反映」トグル ON のときは歴代ベストで上書きしたレコードを使う。
 */
const displayScores = computed(() => (showAllTime.value ? allTimeRecords.value : allFlattenedScores.value));

/**
 * 【computed の役割】 歴代ベースの Beat-PT。
 * App.vue が現行作について行っているのと同じ計算（上位 100 譜面の合計）を、
 * 歴代ベストで上書きしたレコードに対して適用する。
 */
const allTimeBeatPt = computed(() => calculateTotalPoints(allTimeRecords.value));

/** 【computed の役割】 実際にカードへ表示する Beat-PT。トグルに応じて切り替える。 */
const displayBeatPt = computed(() => (showAllTime.value ? allTimeBeatPt.value : props.totalPoints));

// ---- Beat-Tier 算出 ----
/** 【computed の役割】 表示中の Beat-PT に対応するティア情報（名称、色、アイコン等）。 */
const rankInfo = computed(() => getRankInfo(displayBeatPt.value));
/** 【computed の役割】 次のティアまでの差分情報（必要 pt、ティア名）。 */
const nextRankInfo = computed(() => getNextRankInfo(displayBeatPt.value));

/**
 * 【computed の役割】 Rate-Tier ポイント算出。
 *   - ANOTHER/LEGGENDARIA の 上位 100 曲分の Rate-PT を合算
 *   - 100% 達成曲が 100 件を超えた場合、超過分 1 件あたり +1 pt のオーバーフローボーナスを加算
 *     （バックエンド ScoreRecalculationService と一致する挙動）
 *   - rateTierPointsOverride（TOP ランカー等の既計算値）が与えられていればそれを優先
 */
const calcRateTierPoints = (records: ReturnType<typeof flattenScores>): number => {
  const eligible = records
    .filter(s => ['ANOTHER', 'LEGGENDARIA'].includes(s.difficultyName) && s.scoreRate > 0);
  const pts = eligible
    .map(s => calculateScoreRateTierPoints(s.scoreRate))
    .filter(pt => pt > 0)
    .sort((a, b) => b - a);
  const top100 = pts.slice(0, 100);
  let sum = top100.reduce((acc, pt) => acc + pt, 0);
  const perfectRateCount = eligible.filter(s => s.scoreRate >= 100.0).length;
  if (perfectRateCount > 100) sum += (perfectRateCount - 100);
  return Math.round(sum * 10) / 10;
};

const rateTierPoints = computed(() => {
  if (props.rateTierPointsOverride != null) return props.rateTierPointsOverride;
  return calcRateTierPoints(allFlattenedScores.value);
});

/** 【computed の役割】 歴代ベースの Rate-PT。現行と同じ計算式を歴代ベストのレコードに適用する。 */
const allTimeRatePt = computed(() => calcRateTierPoints(allTimeRecords.value));

/** 【computed の役割】 実際にカードへ表示する Rate-PT。トグルに応じて切り替える。 */
const displayRatePt = computed(() => (showAllTime.value ? allTimeRatePt.value : rateTierPoints.value));

/** 【computed の役割】 Rate-Tier の現在ティア情報。 */
const rateTierRankInfo = computed(() => getRateTierRankInfo(displayRatePt.value));
/** 【computed の役割】 Rate-Tier の次ティア情報。 */
const rateTierNextRankInfo = computed(() => getNextRateTierRankInfo(displayRatePt.value));

// ---- Lv12 クイック統計（UI サマリーカードで使う軽量集計）----
/** 【computed の役割】 Lv12 全譜面の配列。 */
const lv12All = computed(() => allFlattenedScores.value.filter(s => s.difficultyLevel === 12));
/** 【computed の役割】 Lv12 譜面総数。 */
const lv12Total = computed(() => lv12All.value.length);
/** 【computed の役割】 Lv12 クリア率（FAILED/NO PLAY/--- 以外を「クリア済」とする）。 */
const lv12ClearRate = computed(() => {
  if (!lv12Total.value) return 0;
  const cleared = lv12All.value.filter(s => !['FAILED', 'NO PLAY', '---'].includes(s.clearType)).length;
  return Math.round((cleared / lv12Total.value) * 100);
});
/** 【computed の役割】 Lv12 AAA 達成率。 */
const lv12AaaRate = computed(() => {
  if (!lv12Total.value) return 0;
  const aaa = lv12All.value.filter(s => s.djLevel === 'AAA').length;
  return Math.round((aaa / lv12Total.value) * 100);
});
/** 【computed の役割】 Lv12 MAX-（94.45%）以上達成率。 */
const lv12MaxMinusRate = computed(() => {
  if (!lv12Total.value) return 0;
  const mm = lv12All.value.filter(s => s.scoreRate >= 94.45).length;
  return Math.round((mm / lv12Total.value) * 100);
});

// ---- ランキング（自分の近隣を表示するため全体ランキングを取得）----
interface RankingEntry { displayName: string; iidxId: string; totalBeatPt: number; rankChange: number | null; }
const rankingData = ref<RankingEntry[]>([]);

// マウント時に全体ランキングを取得（失敗しても UI は継続表示）。
onMounted(async () => {
  try {
    const res = await fetch(`${API_BASE}/api/scores/ranking`);
    if (res.ok) rankingData.value = await res.json();
  } catch {}
});

/**
 * 【computed の役割】 対象ユーザーの全体順位と前回差分を返す。
 * 閲覧対象が他人（viewingIidxId あり）ならその人、そうでなければ自分の iidxId で検索。
 */
const myRankingPosition = computed(() => {
  if (!rankingData.value.length) return null;
  // フレンド/管理者閲覧中は対象ユーザーの iidxId を、それ以外は自分の iidxId を使う。
  const targetIidxId = props.viewingIidxId || user.value?.iidxId;
  if (!targetIidxId) return null;
  const idx = rankingData.value.findIndex(r => r.iidxId === targetIidxId);
  if (idx === -1) return null;
  return { position: idx + 1, total: rankingData.value.length, rankChange: rankingData.value[idx].rankChange };
});

// ===== スコアロードマップのレベル（ランキング順位の隣。2026-09-23 追加） =====
/**
 * 表示対象のロードマップレベル（取得前・対象外・集計前は null で非表示）。
 * level は負のレベル（Lv.0 以下）もあり、どのレベルも未達成なら null（表示は「—」）。
 */
const roadmapLevel = ref<{ level: number | null; maxLevel: number } | null>(null);
/**
 * 【computed の役割】 ロードマップレベルを取る相手。自分なら 'self'、管理者閲覧中はその人の ID。
 * フレンド・公開プロフィール等の他人閲覧では API が本人か管理者しか許さないので出さない（null）。
 */
const roadmapTarget = computed<'self' | number | null>(() => {
  if (!user.value) return null;
  if (!isViewingOther.value) return 'self';
  return props.viewingMode === 'admin' && props.viewingUserId ? props.viewingUserId : null;
});
let roadmapSeq = 0;
/** 【関数の役割】 軽量 API（/score-roadmap/level）でレベルだけ取る。失敗しても表示しないだけ。 */
const loadRoadmapLevel = async () => {
  const target = roadmapTarget.value;
  const seq = ++roadmapSeq;
  if (target == null) { roadmapLevel.value = null; return; }
  try {
    const q = target === 'self' ? '' : `?userId=${target}`;
    const res = await fetch(`${API_BASE}/api/scores/score-roadmap/level${q}`, { headers: authHeaders() });
    if (seq !== roadmapSeq) return;
    const data = res.ok ? await res.json() : null;
    roadmapLevel.value = data?.ready ? { level: data.level, maxLevel: data.maxLevel } : null;
  } catch {
    if (seq === roadmapSeq) roadmapLevel.value = null;
  }
};
// 対象が変わったとき・スコアが入れ替わったとき（アップロード後など）に取り直す
watch([roadmapTarget, () => props.scores], loadRoadmapLevel, { immediate: true });

/** 【computed の役割】 自分の 1 個上と 1 個下のランキングエントリを返す（追い越し表示用）。 */
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
