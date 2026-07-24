<template>
  <!--
    ============================================================
    ScoreSummary.vue ルートテンプレート
      - ヘッダ: タイトル + 件数表示 + フィルタ群（ゼロ非表示/レベル/難易度/DJ LEVEL/クリアタイプ/検索）
      - モードタブ（BEAT-TIER / RATE-TIER）
      - データテーブル（displayScores を v-for）
      - ページネーション
      - 詳細モーダル（selectedRecord !== null の間だけ v-if 表示）
    ============================================================
  -->
  <div class="w-full mx-auto space-y-6 animate-fade-in relative">
    <!-- ===== フィルタ・検索ヘッダ ===== -->
    <div class="bg-white dark:bg-slate-800 p-6 rounded-md border border-slate-200 dark:border-slate-700 flex flex-col xl:flex-row xl:items-center justify-between gap-4 transition-colors duration-200">
      <div>
        <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">{{ t('table.title') }}</h2>
        <p class="text-slate-500 dark:text-slate-400 mt-1">{{ t('table.dataCount', { n: filteredScores.length }) }}</p>
      </div>
      <div class="flex flex-col md:flex-row items-start md:items-center justify-end gap-3 w-full xl:w-auto">
        <div class="flex flex-col sm:flex-row items-start sm:items-center gap-3 sm:gap-4 w-full md:w-auto">
          <!-- Hide Zero Score Toggle -->
          <label class="flex items-center gap-2 cursor-pointer group whitespace-nowrap">
            <div class="relative inline-flex items-center">
              <input type="checkbox" v-model="hideZeroScore" class="sr-only peer">
              <div class="w-9 h-5 bg-slate-200 dark:bg-slate-700 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-blue-300 dark:peer-focus:ring-blue-800 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white dark:peer-checked:after:border-slate-800 after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white dark:after:bg-slate-800 after:border-slate-300 dark:after:border-slate-600 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-blue-600"></div>
            </div>
            <span class="text-xs sm:text-sm font-bold text-slate-600 dark:text-slate-400 group-hover:text-slate-800 dark:group-hover:text-slate-200 transition-colors">{{ t('table.hideZero') }}</span>
          </label>

          <div class="flex items-center gap-2 w-full sm:w-auto">
            <!-- Level Filter -->
          <div class="relative w-full md:w-36">
            <button 
              @click.stop="toggleDropdown('level')"
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 dark:border-slate-700 rounded-md bg-slate-50 dark:bg-slate-900 text-xs sm:text-sm font-medium text-slate-700 dark:text-slate-200 transition-colors hover:bg-white dark:hover:bg-slate-800"
              :title="filterLevel.length > 0 ? filterLevel.map(l => '☆'+l).join(', ') : t('table.level')"
            >
              <span class="truncate">{{ filterLevel.length > 0 ? filterLevel.map(l => '☆'+l).join(', ') : t('table.level') }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0 ml-1" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'level'" class="absolute z-20 mt-1 w-48 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
              <label v-for="l in (viewMode === 'rate' ? [1,2,3,4,5,6,7,8,9,10,11,12] : [11,12])" :key="l" class="flex items-center px-4 py-2 hover:bg-slate-50 dark:hover:bg-slate-700/50 cursor-pointer group">
                <input 
                  type="checkbox" 
                  :checked="isSelected(filterLevel, l.toString())"
                  @change="toggleFilterValue(filterLevel, l.toString())"
                  class="h-4 w-4 text-blue-600 rounded border-slate-300 dark:border-slate-600 focus:ring-blue-500 dark:focus:ring-blue-600 transition-all cursor-pointer bg-white dark:bg-slate-900"
                >
                <span class="ml-3 text-sm font-medium text-slate-600 dark:text-slate-300 group-hover:text-slate-900 dark:group-hover:text-white transition-colors">☆{{ l }}</span>
              </label>
            </div>
          </div>

          <!-- Difficulty Filter -->
          <div class="relative w-full md:w-36">
            <button 
              @click.stop="toggleDropdown('difficulty')"
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 dark:border-slate-700 rounded-md bg-slate-50 dark:bg-slate-900 text-xs sm:text-sm font-medium text-slate-700 dark:text-slate-200 transition-colors hover:bg-white dark:hover:bg-slate-800"
              :title="filterDifficulty.length > 0 ? filterDifficulty.map(d => d.substring(0,3)).join(', ') : t('table.difficulty')"
            >
              <span class="truncate">{{ filterDifficulty.length > 0 ? filterDifficulty.map(d => d.substring(0,3)).join(', ') : t('table.difficulty') }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0 ml-1" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'difficulty'" class="absolute z-20 mt-1 w-48 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
              <label v-for="d in DIFFICULTY_FILTER_OPTIONS" :key="d" class="flex items-center px-4 py-2 hover:bg-slate-50 dark:hover:bg-slate-700/50 cursor-pointer group">
                <input
                  type="checkbox"
                  :checked="isSelected(filterDifficulty, d)"
                  @change="toggleFilterValue(filterDifficulty, d)"
                  class="h-4 w-4 text-blue-600 rounded border-slate-300 dark:border-slate-600 focus:ring-blue-500 dark:focus:ring-blue-600 transition-all cursor-pointer bg-white dark:bg-slate-900"
                >
                <span class="ml-3 text-sm font-bold text-slate-600 dark:text-slate-300 group-hover:text-slate-900 dark:group-hover:text-white transition-colors">{{ t(`table.difficulty.${d.toLowerCase()}`) }}</span>
              </label>
            </div>
          </div>

          <!-- DJ Level Filter -->
          <div class="relative w-full md:w-32">
            <button
              @click.stop="toggleDropdown('djLevel')"
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 dark:border-slate-700 rounded-md bg-slate-50 dark:bg-slate-900 text-xs sm:text-sm font-medium text-slate-700 dark:text-slate-200 transition-colors hover:bg-white dark:hover:bg-slate-800"
            >
              <span class="truncate">{{ t('table.rank') }}{{ filterDjLevel.length > 0 ? ` (${filterDjLevel.length})` : '' }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'djLevel'" class="absolute z-20 mt-1 w-48 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
              <label v-for="lvl in DJ_LEVELS" :key="lvl" class="flex items-center px-4 py-2 hover:bg-slate-50 dark:hover:bg-slate-700/50 cursor-pointer group">
                <input
                  type="checkbox"
                  :checked="isSelected(filterDjLevel, lvl)"
                  @change="toggleFilterValue(filterDjLevel, lvl)"
                  class="h-4 w-4 text-blue-600 rounded border-slate-300 dark:border-slate-600 focus:ring-blue-500 dark:focus:ring-blue-600 transition-all cursor-pointer bg-white dark:bg-slate-900"
                >
                <span class="ml-3 text-sm font-bold text-slate-600 dark:text-slate-300 group-hover:text-slate-900 dark:group-hover:text-white transition-colors">{{ lvl }}</span>
              </label>
            </div>
          </div>

          <!-- Clear Type Filter -->
          <div class="relative w-full md:w-36">
            <button
              @click.stop="toggleDropdown('clearType')"
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 dark:border-slate-700 rounded-md bg-slate-50 dark:bg-slate-900 text-xs sm:text-sm font-medium text-slate-700 dark:text-slate-200 transition-colors hover:bg-white dark:hover:bg-slate-800"
            >
              <span class="truncate">{{ t('table.lamp') }}{{ filterClearType.length > 0 ? ` (${filterClearType.length})` : '' }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'clearType'" class="absolute z-20 mt-1 w-56 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
              <label v-for="ct in ['FULLCOMBO CLEAR', 'EX HARD CLEAR', 'HARD CLEAR', 'CLEAR', 'EASY CLEAR', 'ASSIST CLEAR', 'FAILED', 'NO PLAY']" :key="ct" class="flex items-center px-4 py-2 hover:bg-slate-50 dark:hover:bg-slate-700/50 cursor-pointer group">
                <input
                  type="checkbox"
                  :checked="isSelected(filterClearType, ct)"
                  @change="toggleFilterValue(filterClearType, ct)"
                  class="h-4 w-4 text-blue-600 rounded border-slate-300 dark:border-slate-600 focus:ring-blue-500 dark:focus:ring-blue-600 transition-all cursor-pointer bg-white dark:bg-slate-900"
                >
                <span class="ml-3 text-xs font-medium text-slate-600 dark:text-slate-300 group-hover:text-slate-900 dark:group-hover:text-white transition-colors">{{ ct }}</span>
              </label>
            </div>
          </div>

          <!-- Source Filter（INFINITAS / アーケード）。INFINITAS スコアを取り込んでいる場合のみ表示。 -->
          <div v-if="hasInfinitasScores" class="relative w-full md:w-36">
            <button
              @click.stop="toggleDropdown('source')"
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 dark:border-slate-700 rounded-md bg-slate-50 dark:bg-slate-900 text-xs sm:text-sm font-medium text-slate-700 dark:text-slate-200 transition-colors hover:bg-white dark:hover:bg-slate-800"
            >
              <span class="truncate">{{ t('table.source') }}{{ filterSource.length > 0 ? ` (${filterSource.length})` : '' }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'source'" class="absolute z-20 mt-1 w-48 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
              <label v-for="src in ['infinitas', 'arcade']" :key="src" class="flex items-center px-4 py-2 hover:bg-slate-50 dark:hover:bg-slate-700/50 cursor-pointer group">
                <input
                  type="checkbox"
                  :checked="isSelected(filterSource, src)"
                  @change="toggleFilterValue(filterSource, src)"
                  class="h-4 w-4 text-blue-600 rounded border-slate-300 dark:border-slate-600 focus:ring-blue-500 dark:focus:ring-blue-600 transition-all cursor-pointer bg-white dark:bg-slate-900"
                >
                <span class="ml-3 text-sm font-bold text-slate-600 dark:text-slate-300 group-hover:text-slate-900 dark:group-hover:text-white transition-colors">{{ t(`table.source.${src}`) }}</span>
              </label>
            </div>
          </div>
          </div>
        </div>
        <!-- Search -->
        <div class="relative w-full md:w-64">
          <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
            <svg class="h-4 w-4 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
          </div>
          <input
            v-model="searchQuery"
            type="text"
            class="block w-full pl-9 pr-3 py-2 border border-slate-200 dark:border-slate-700 rounded-md leading-5 bg-slate-50 dark:bg-slate-900 text-slate-700 dark:text-slate-200 placeholder-slate-400 dark:placeholder-slate-500 focus:outline-none focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 sm:text-sm transition-colors"
            :placeholder="t('table.searchPlaceholder')"
          >
        </div>
      </div>
    </div>

    <!-- ===== 適用済みフィルタチップ行（フィルタが 1 つ以上掛かっている時だけ表示） ===== -->
    <!-- 各チップの × で個別解除、右端の「全クリア」で一括解除できる。 -->
    <div v-if="appliedFilterChips.length > 0" class="flex flex-wrap items-center gap-2 animate-fade-in">
      <span
        v-for="chip in appliedFilterChips"
        :key="chip.id"
        class="inline-flex items-center gap-1.5 pl-3 pr-1 py-1 text-xs font-bold rounded-full bg-blue-50 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300 border border-blue-200 dark:border-blue-800"
      >
        {{ chip.label }}
        <button
          type="button"
          :aria-label="t('filter.removeChip', { label: chip.label })"
          class="rounded-full p-0.5 hover:bg-blue-200 dark:hover:bg-blue-800 transition-colors"
          @click="chip.remove()"
        >
          <svg aria-hidden="true" class="h-3 w-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </span>
      <button
        type="button"
        @click="clearAllFilters"
        class="ml-auto inline-flex items-center gap-1 px-3 py-1 text-xs font-bold text-slate-600 dark:text-slate-300 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
      >
        <svg aria-hidden="true" class="h-3 w-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
        </svg>
        {{ t('filter.clearAll') }}
      </button>
    </div>

    <!-- ===== モードタブ（BEAT-TIER / RATE-TIER 切替。showRateTier が true のときのみ表示） ===== -->
    <div v-if="showRateTier" class="flex gap-1 bg-slate-100 dark:bg-slate-800/80 p-1 rounded-md w-fit border border-slate-200 dark:border-slate-700">
      <button
        @click="viewMode = 'beat'"
        class="px-4 py-2 rounded-lg text-sm font-bold transition-colors"
        :class="viewMode === 'beat' ? 'bg-white dark:bg-slate-700 text-blue-600 dark:text-blue-400' : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
      >BEAT-TIER</button>
      <button
        @click="viewMode = 'rate'"
        class="px-4 py-2 rounded-lg text-sm font-bold transition-colors"
        :class="viewMode === 'rate' ? 'bg-white dark:bg-slate-700 text-emerald-600 dark:text-emerald-400' : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
      >RATE-TIER</button>
    </div>

    <!-- ===== データテーブル（displayScores を描画。ヘッダ列クリックで toggleSort） ===== -->
    <div class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
      <div class="overflow-x-auto">
        <table class="w-full text-left text-[10px] sm:text-sm text-slate-600 dark:text-slate-300">
          <thead class="sticky top-0 z-10 bg-slate-50 dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200 font-semibold h-10 sm:h-12">
            <tr>
              <th class="px-1 sm:px-6 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-bold text-slate-500 dark:text-slate-400 group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors w-auto sm:w-4/12" @click="toggleSort('title')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  {{ t('table.colTitle') }}
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'title'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">↕</span>
                </div>
              </th>
              <th class="px-1 sm:px-4 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-bold text-slate-700 dark:text-slate-200 w-auto sm:w-1/12 group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors" @click="toggleSort('difficultyLevel')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  {{ t('table.colOfficial') }}
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'difficultyLevel'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">↕</span>
                </div>
              </th>
              <th class="px-1 sm:px-4 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-bold text-slate-700 dark:text-slate-200 w-auto sm:w-1/12 group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors" @click="toggleSort('informalRank')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  {{ t('table.colInformal') }}
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'informalRank'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">↕</span>
                </div>
              </th>
              <th class="max-sm:hidden px-1 sm:px-6 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-bold text-slate-500 dark:text-slate-400 group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors w-auto sm:w-2/12" @click="toggleSort('clearType')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  {{ t('table.colScore') }}
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'clearType'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">↕</span>
                </div>
              </th>
              <th class="px-1 sm:px-4 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-bold text-slate-500 dark:text-slate-400 group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors w-auto sm:w-1/12" @click="toggleSort('scoreRate')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  {{ t('table.colRate') }}
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'scoreRate'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">↕</span>
                </div>
              </th>
              <!-- BPI 列（きんじょー杯ページ限定。showBpi が true のときだけ表示） -->
              <th v-if="props.showBpi" class="px-1 sm:px-4 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-bold text-violet-600 dark:text-violet-400 group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors w-auto sm:w-1/12" @click="toggleSort('bpi')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  BPI
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'bpi'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">↕</span>
                </div>
              </th>
              <th class="px-1 sm:px-2 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-bold text-slate-500 dark:text-slate-400 group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors w-8 sm:w-12" @click="toggleSort('unofficialSongRank')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  {{ t('table.colRank') }}
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'unofficialSongRank'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">↕</span>
                </div>
              </th>
              <th class="px-1 sm:px-6 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-bold group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors w-auto sm:w-2/12"
                :class="viewMode === 'rate' ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-500 dark:text-slate-400'"
                @click="toggleSort('beatTierPoints')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  {{ viewMode === 'rate' ? t('table.colPoints') : t('table.colBeatPt') }}
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'beatTierPoints'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">↕</span>
                </div>
              </th>
              <th class="px-1 sm:px-4 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-bold w-auto sm:w-2/12"
                :class="viewMode === 'rate' ? 'text-emerald-500 dark:text-emerald-400' : 'text-orange-500 dark:text-orange-400'">
                TOP100
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100 dark:divide-slate-700/50">
            <tr
              v-for="(record, index) in displayScores"
              :key="index"
              @touchstart="handleTouchStart"
              @touchmove="handleTouchMove"
              @click="handleRowClick(record)"
              class="hover:bg-blue-50/70 dark:hover:bg-slate-700/50 cursor-pointer transition-colors h-12 sm:h-14 w-full"
            >
              <td class="px-1 sm:px-6 py-1.5 sm:py-2 font-medium text-slate-800 dark:text-slate-200 max-w-[80px] sm:max-w-[160px] lg:max-w-[240px] xl:max-w-xs" :title="record.title">
                <div class="flex flex-col gap-0.5">
                  <span class="truncate block">{{ record.title }}</span>
                  <span v-if="props.viewingMode === 'topRanker' && record.djName" class="text-[8px] sm:text-[10px] font-bold text-emerald-600 dark:text-emerald-400 truncate block">
                    DJ: {{ record.djName }}
                  </span>
                  <template v-for="label of [getScoreGradeLabel(record)]" :key="0">
                    <div v-if="label" class="flex items-center gap-1 text-[8px] sm:text-[10px] font-bold leading-none">
                      <span :class="record.scoreRate >= 94.45 ? 'text-purple-500 dark:text-purple-400' : record.scoreRate >= 88.89 ? 'text-amber-500 dark:text-amber-400' : 'text-slate-500 dark:text-slate-400'">{{ label.primary }}</span>
                      <span class="text-slate-400 dark:text-slate-500">{{ label.secondary }}</span>
                      <template v-if="isLoggedIn && songRankMap.get(record.title + '|' + record.difficultyName)">
                        <span class="text-slate-300 dark:text-slate-600">·</span>
                        <span :class="songRankMap.get(record.title + '|' + record.difficultyName)!.rank === 1 ? 'text-amber-500 dark:text-amber-400' : 'text-slate-400 dark:text-slate-500'">#{{ songRankMap.get(record.title + '|' + record.difficultyName)!.rank }}<span class="font-normal text-slate-400 dark:text-slate-600">/{{ songRankMap.get(record.title + '|' + record.difficultyName)!.total }}</span></span>
                      </template>
                    </div>
                  </template>
                </div>
              </td>
              <td class="px-1 sm:px-4 py-1.5 sm:py-2 whitespace-nowrap">
                <div class="flex flex-col gap-0.5 sm:gap-1">
                  <span :class="['px-1 sm:px-2 py-0.5 rounded text-[8px] sm:text-[10px] font-bold whitespace-nowrap inline-block w-fit', record.difficultyColor]">
                    {{ record.difficultyName.charAt(0) }}<span class="hidden sm:inline">{{ record.difficultyName.slice(1) }}</span> {{ record.difficultyLevel || '' }}
                  </span>
                  <!-- INF タグ: 表示中のスコアが INFINITAS 取得（アーケードより高い／アーケード未プレイ）の場合に付与。 -->
                  <span v-if="record.source === 'infinitas'"
                        class="px-1 sm:px-1.5 py-0 rounded text-[7px] sm:text-[9px] font-bold whitespace-nowrap inline-block w-fit text-sky-700 bg-sky-100 border border-sky-300 dark:text-sky-300 dark:bg-sky-900/40 dark:border-sky-700"
                        title="INFINITAS で取り込んだスコア（アーケードとは別管理。両方ある場合は EX SCORE が高い方を表示）">
                    INF
                  </span>
                </div>
              </td>
              <td class="px-1 sm:px-4 py-1.5 sm:py-2 whitespace-nowrap">
                  <InformalRankBadge :rank="record.informalRank" size="xs" />
              </td>
              <td class="max-sm:hidden px-1 sm:px-6 py-1.5 sm:py-2">
                <div class="flex flex-col gap-0.5">
                  <div class="flex items-center gap-1 sm:gap-2">
                    <span class="font-bold text-[8px] sm:text-[10px] truncate max-w-[36px] sm:max-w-none" :class="getClearTypeColor(record.clearType)">
                      {{ record.clearType === 'FULLCOMBO CLEAR' ? 'FC' : record.clearType === 'EX HARD CLEAR' ? 'EXH' : record.clearType === 'HARD CLEAR' ? 'H' : record.clearType === 'CLEAR' ? 'C' : record.clearType === 'EASY CLEAR' ? 'E' : record.clearType === 'ASSIST CLEAR' ? 'AC' : 'F' }}
                    </span>
                    <span class="font-bold text-[10px] sm:text-sm" :class="getDjLevelColor(record.djLevel)">{{ record.djLevel !== '---' ? record.djLevel : '' }}</span>
                    <template v-if="isLoggedIn && songRankMap.get(record.title + '|' + record.difficultyName)">
                      <span class="font-bold text-[9px] sm:text-[11px]" :class="songRankMap.get(record.title + '|' + record.difficultyName)!.rank === 1 ? 'text-amber-500 dark:text-amber-400' : 'text-slate-400 dark:text-slate-500'">
                        #{{ songRankMap.get(record.title + '|' + record.difficultyName)!.rank }}<span class="font-normal text-slate-400 dark:text-slate-600">/{{ songRankMap.get(record.title + '|' + record.difficultyName)!.total }}</span>
                      </span>
                    </template>
                  </div>
                  <div class="flex items-center gap-0.5 sm:gap-1">
                     <span class="font-bold text-slate-800 dark:text-slate-200 text-[9px] sm:text-xs">{{ record.score }}</span>
                  </div>
                </div>
              </td>
              <td class="px-1 sm:px-4 py-1.5 sm:py-2 whitespace-nowrap">
                <span v-if="record.scoreRate >= 0" class="font-bold text-[9px] sm:text-xs" :class="record.scoreRate >= 94.45 ? 'text-purple-600 dark:text-purple-400' : record.scoreRate >= 88.89 ? 'text-amber-500 dark:text-amber-400' : 'text-slate-600 dark:text-slate-400'">{{ record.scoreRate.toFixed(2) }}%</span>
                <span v-else class="text-[9px] sm:text-[10px] font-bold text-slate-400 dark:text-slate-500">---</span>
              </td>

              <!-- BPI（きんじょー杯ページ限定。avg/wr を持たない譜面・未プレイは「—」） -->
              <td v-if="props.showBpi" class="px-1 sm:px-4 py-1.5 sm:py-2 whitespace-nowrap">
                <template v-for="bpi of [recordBpi(record)]" :key="0">
                  <span v-if="bpi != null" class="font-bold text-[9px] sm:text-xs tabular-nums" :class="bpi >= 0 ? 'text-violet-600 dark:text-violet-400' : 'text-rose-500 dark:text-rose-400'">{{ bpi.toFixed(1) }}</span>
                  <span v-else class="text-[9px] sm:text-[10px] font-bold text-slate-400 dark:text-slate-500">—</span>
                </template>
              </td>

              <!-- 単曲ランク（必要スコアレート表対応）。アイコンのみ表示。 -->
              <td class="px-1 sm:px-2 py-1.5 sm:py-2 whitespace-nowrap w-8 sm:w-12">
                <template v-for="rankInfo of [getSongUnofficialRank(record)]" :key="0">
                  <RankIcon v-if="rankInfo" :rank-name="rankInfo.name" :tier="rankInfo.tier" size="xs" lite />
                  <span v-else class="text-[9px] sm:text-[10px] font-bold text-slate-400 dark:text-slate-500">---</span>
                </template>
              </td>

              <!-- BEAT-TIER mode: BEAT-PT cell -->
              <td v-if="viewMode === 'beat'" class="px-1 sm:px-6 py-1.5 sm:py-2 whitespace-nowrap transition-colors" :class="[
                top100Keys.has(record.title + '|' + record.difficultyName) ? 'bg-blue-50/80 dark:bg-blue-900/20' : '',
                ((!record.informalRank && record.difficultyLevel && record.difficultyLevel <= 10) || (record.difficultyName === 'HYPER' && record.difficultyLevel && record.difficultyLevel >= 11)) ? 'bg-slate-900' : ''
              ]">
                <div class="flex flex-col sm:flex-row sm:items-center gap-px sm:gap-2" v-if="(record.informalRank || (record.difficultyLevel && record.difficultyLevel > 10)) && !(record.difficultyName === 'HYPER' && record.difficultyLevel && record.difficultyLevel >= 11)">
                  <div class="flex items-center gap-0.5 sm:gap-1">
                    <span class="font-bold" :class="top100Keys.has(record.title + '|' + record.difficultyName) ? 'text-blue-700 dark:text-blue-400 text-[10px] sm:text-base' : 'text-slate-800 dark:text-slate-200 text-[9px] sm:text-sm'">
                      {{ record.beatTierPoints.toFixed(1) }}
                    </span>
                    <span v-if="top100Keys.has(record.title + '|' + record.difficultyName)" class="hidden sm:inline-block px-1 py-0.5 rounded bg-blue-100 dark:bg-blue-900/50 text-blue-600 dark:text-blue-400 text-[6px] sm:text-[8px] font-bold border border-blue-200 dark:border-blue-800">
                      TOP
                    </span>
                  </div>
                  <span class="text-[7px] sm:text-[10px] font-bold text-slate-400 dark:text-slate-500">/{{ record.maxBeatTierPoints.toFixed(1) }}</span>
                </div>
                <div v-else class="flex items-center justify-center">
                  <span class="text-[9px] sm:text-[10px] font-bold text-slate-700 dark:text-slate-500 italic">N/A</span>
                </div>
              </td>
              <!-- RATE-TIER mode: 獲得PT cell -->
              <td v-else class="px-1 sm:px-6 py-1.5 sm:py-2 whitespace-nowrap transition-colors"
                :class="hasPerfectRateOverflow && record.scoreRate >= 100 ? 'bg-amber-100/80 dark:bg-amber-900/30' : rateTop100Keys.has(record.title + '|' + record.difficultyName) ? 'bg-emerald-50/80 dark:bg-emerald-900/20' : ''"
              >
                <div v-if="record.scoreRate > 0" class="flex items-center gap-0.5 sm:gap-1">
                  <span class="font-bold" :class="rateTop100Keys.has(record.title + '|' + record.difficultyName) ? 'text-emerald-700 dark:text-emerald-400 text-[10px] sm:text-base' : 'text-slate-800 dark:text-slate-200 text-[9px] sm:text-sm'">
                    {{ calculateScoreRateTierPoints(record.scoreRate).toFixed(2) }}
                  </span>
                  <span v-if="rateTop100Keys.has(record.title + '|' + record.difficultyName)" class="hidden sm:inline-block px-1 py-0.5 rounded bg-emerald-100 dark:bg-emerald-900/50 text-emerald-600 dark:text-emerald-400 text-[6px] sm:text-[8px] font-bold border border-emerald-200 dark:border-emerald-800">
                    TOP
                  </span>
                </div>
                <span v-else class="text-[9px] sm:text-[10px] font-bold text-slate-700 dark:text-slate-500 italic">---</span>
              </td>
              <!-- TOP100 column -->
              <td class="px-1 sm:px-4 py-1.5 sm:py-2 whitespace-nowrap">
                <div
                  v-if="viewMode === 'beat' && top100ScoreNeededMap.has(record.title + '|' + record.difficultyName)"
                  class="flex flex-col gap-0.5"
                >
                  <span class="text-[9px] sm:text-[11px] font-bold text-orange-500 dark:text-orange-400">{{ t('table.top100GapStart') }}</span>
                  <span class="text-xs sm:text-sm font-bold text-orange-600 dark:text-orange-300 tabular-nums">{{ top100ScoreNeededMap.get(record.title + '|' + record.difficultyName)!.toLocaleString() }}{{ t('common.points') }}</span>
                  <span class="text-[8px] sm:text-[10px] font-bold text-orange-400 dark:text-orange-500">{{ t('table.top100GapEnd') }}</span>
                </div>
              </td>
            </tr>
            <tr v-if="displayScores.length === 0">
              <td :colspan="props.showBpi ? 9 : 8" class="px-6 py-12 text-center text-slate-500 dark:text-slate-400 w-full">
                {{ t('table.noMatchingScores') }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      
      <!-- ===== ページネーション（件数表示 + 1 ページあたり件数セレクタ + 前後ボタン） ===== -->
      <div v-if="filteredScores.length > 0" class="px-6 py-4 border-t border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-800/50 flex flex-col sm:flex-row items-center justify-between gap-4 transition-colors duration-200">
        <div class="flex flex-col sm:flex-row items-center gap-4">
          <div class="text-sm text-slate-500 dark:text-slate-400">
            {{ t('table.displayCount', { start: (currentPage - 1) * itemsPerPage + 1, end: Math.min(currentPage * itemsPerPage, filteredScores.length), total: filteredScores.length }) }}
          </div>
          <div class="flex items-center gap-2">
            <span class="text-sm text-slate-500 dark:text-slate-400">{{ t('table.itemsPerPage') }}:</span>
            <select v-model="itemsPerPage" class="text-sm border border-slate-200 dark:border-slate-700 rounded-lg bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300 px-2 py-1 outline-none focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-600 transition-colors cursor-pointer">
              <option :value="10">10{{ t('scores.count') }}</option>
              <option :value="25">25{{ t('scores.count') }}</option>
              <option :value="50">50{{ t('scores.count') }}</option>
              <option :value="100">100{{ t('scores.count') }}</option>
            </select>
          </div>
        </div>
        <div class="flex items-center gap-2">
          <button 
            @click="prevPage" 
            :disabled="currentPage === 1"
            class="px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300"
          >
            {{ t('table.prev') }}
          </button>
          <span class="text-sm font-medium text-slate-600 dark:text-slate-400 px-2 min-w-[3rem] text-center">{{ currentPage }} / {{ totalPages }}</span>
          <button 
            @click="nextPage" 
            :disabled="currentPage === totalPages"
            class="px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300"
          >
            {{ t('table.next') }}
          </button>
        </div>
      </div>
    </div>

    <!--
      ===== 全画面詳細モーダル =====
      selectedRecord が非 null の間だけ Teleport で <body> 直下にレンダリングされる。
      構造:
        - Sticky Header: 曲名/アーティスト + 閉じるボタン + タブバー（detail / rate-tier / rivals / ranking / history）
        - 各タブのコンテンツ（v-if でひとつだけ表示）
        - Sticky Footer: モバイル閉じるボタン
    -->
    <Teleport to="body">
      <div v-if="selectedRecord" class="fixed inset-0 z-[100] bg-slate-50 dark:bg-slate-900 flex flex-col animate-fade-in transition-colors duration-200" @click.self="closeDetailModal">

      <!-- ===== モーダル Sticky ヘッダ（曲情報 + 閉じるボタン + タブバー） ===== -->
      <div class="bg-white dark:bg-slate-900 sticky top-0 z-10 w-full border-b border-slate-200 dark:border-slate-800 transition-colors duration-200">
        <div class="px-4 py-3 sm:px-6 sm:py-5 flex justify-between items-center">
          <div class="flex flex-col pr-4 max-w-full overflow-hidden">
            <h3 class="text-xl sm:text-3xl font-bold text-slate-800 dark:text-slate-100 leading-tight mb-0.5 sm:mb-1 truncate" :title="selectedRecord.title">{{ selectedRecord.title }}</h3>
            <p class="text-xs sm:text-base font-medium text-slate-500 dark:text-slate-400 truncate" :title="`${selectedRecord.artist} • ${selectedRecord.genre}`">{{ selectedRecord.artist }} • {{ selectedRecord.genre }}</p>
          </div>
          <button @click="closeDetailModal" class="flex-shrink-0 text-slate-400 dark:text-slate-500 hover:text-slate-700 dark:hover:text-slate-300 bg-slate-50 dark:bg-slate-800 hover:bg-slate-200 dark:hover:bg-slate-700 rounded-full transition-colors p-2 sm:p-3 border border-slate-200 dark:border-slate-700">
            <svg class="w-5 h-5 sm:w-8 sm:h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
        <!-- モーダル内タブバー: detail / rate-tier / rivals(=ranking) / history。ログイン状態や難易度で一部のみ表示 -->
        <div class="flex border-t border-slate-100 dark:border-slate-800">
          <button
            @click="modalTab = 'detail'"
            class="flex-1 py-2 text-sm font-bold border-b-2 transition-colors"
            :class="modalTab === 'detail' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-700 dark:hover:text-slate-300'"
          >{{ t('table.detail') }}</button>
          <button
            v-if="['ANOTHER', 'LEGGENDARIA'].includes(selectedRecord?.difficultyName ?? '')"
            @click="modalTab = 'rate-tier'"
            class="flex-1 py-2 text-sm font-bold border-b-2 transition-colors"
            :class="modalTab === 'rate-tier' ? 'border-emerald-600 text-emerald-600' : 'border-transparent text-slate-500 hover:text-slate-700 dark:hover:text-slate-300'"
          >Rate-Tier</button>
          <button
            v-if="showMilestoneTab"
            @click="handleMilestoneTabClick"
            class="flex-1 py-2 text-sm font-bold border-b-2 transition-colors"
            :class="modalTab === 'milestone' ? 'border-amber-500 text-amber-600 dark:text-amber-400' : 'border-transparent text-slate-500 hover:text-slate-700 dark:hover:text-slate-300'"
          >{{ t('table.milestone') }}</button>
          <button
            v-if="isLoggedIn"
            @click="handleRivalTabClick"
            class="flex-1 py-2 text-sm font-bold border-b-2 transition-colors flex items-center justify-center gap-1"
            :class="modalTab === 'rivals' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-700 dark:hover:text-slate-300'"
          >
            {{ t('table.ranking') }}
            <span v-if="rankingList.length > 0" class="text-xs bg-blue-100 dark:bg-blue-900/40 text-blue-600 dark:text-blue-400 rounded px-1.5">{{ rankingList.length }}</span>
          </button>
          <button
            v-if="isLoggedIn && ['ANOTHER', 'LEGGENDARIA'].includes(selectedRecord?.difficultyName ?? '')"
            @click="handleHistoryTabClick"
            class="flex-1 py-2 text-sm font-bold border-b-2 transition-colors"
            :class="modalTab === 'history' ? 'border-violet-500 text-violet-600 dark:text-violet-400' : 'border-transparent text-slate-500 hover:text-slate-700 dark:hover:text-slate-300'"
          >{{ t('table.history') }}</button>
        </div>
      </div>
      
      <!-- ===== モーダルのスクロール可能な本体領域 ===== -->
      <div class="flex-1 overflow-y-auto p-3 sm:p-8 lg:p-12 pb-24">

        <!-- ===== Rate-Tier タブ: 獲得PT + スコアレート + 閾値表 ===== -->
        <div v-if="modalTab === 'rate-tier'" class="w-full max-w-4xl mx-auto space-y-6">
          <!-- 獲得PT と スコアレート を大きく横並びで表示 -->
          <div class="grid grid-cols-2 gap-4">
            <div class="bg-emerald-900/10 dark:bg-emerald-900/20 p-6 sm:p-8 rounded-md flex flex-col items-center justify-center border border-emerald-100 dark:border-emerald-800/50">
              <p class="text-xs sm:text-sm font-bold text-emerald-600 dark:text-emerald-400 mb-2">{{ t('table.colPoints') }}</p>
              <p class="text-4xl sm:text-6xl font-bold text-emerald-700 dark:text-emerald-300 tracking-tight">
                {{ calculateScoreRateTierPoints(selectedRecord!.scoreRate).toFixed(2) }}
              </p>
              <p class="text-xs font-bold text-emerald-500 dark:text-emerald-500 mt-1">/ 256 pt (MAX)</p>
            </div>
            <div class="bg-blue-50 dark:bg-slate-800 p-6 sm:p-8 rounded-md border-4 border-blue-200 dark:border-slate-700 flex flex-col items-center justify-center">
              <p class="text-xs sm:text-sm font-bold text-blue-500 dark:text-blue-400 mb-2">{{ t('table.colRate') }}</p>
              <p class="text-4xl sm:text-6xl font-bold text-blue-600 dark:text-blue-300 tracking-tight flex items-baseline">
                <template v-if="selectedRecord!.scoreRate >= 0">{{ selectedRecord!.scoreRate.toFixed(2) }}</template>
                <template v-else>---</template>
                <span class="text-xl sm:text-3xl font-bold ml-1">%</span>
              </p>
            </div>
          </div>

          <!-- Rate-Tier の閾値テーブル（各閾値の到達/未到達を ✓ / +差% で表示） -->
          <div class="border border-slate-200 dark:border-slate-700 rounded-md overflow-hidden bg-white dark:bg-slate-800">
            <div class="bg-slate-100 dark:bg-slate-900/50 px-6 py-3 border-b border-slate-200 dark:border-slate-700">
              <p class="text-xs font-bold text-slate-600 dark:text-slate-400">{{ t('table.rateTierThresholds') }}</p>
            </div>
            <table class="w-full text-sm">
              <thead>
                <tr class="border-b border-slate-100 dark:border-slate-700">
                  <th class="px-6 py-3 text-left text-xs font-bold text-slate-400">{{ t('table.colRate') }}</th>
                  <th class="px-6 py-3 text-right text-xs font-bold text-slate-400">{{ t('table.colPoints') }}</th>
                  <th class="px-6 py-3 text-center text-xs font-bold text-slate-400">{{ t('table.achieved') }}</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
                <tr
                  v-for="(th, i) in SCORE_RATE_THRESHOLDS"
                  :key="i"
                  class="transition-colors"
                  :class="selectedRecord!.scoreRate >= th.rate ? 'bg-emerald-50/60 dark:bg-emerald-900/10' : 'hover:bg-slate-50 dark:hover:bg-slate-700/20'"
                >
                  <td class="px-6 py-3 font-bold tabular-nums" :class="selectedRecord!.scoreRate >= th.rate ? 'text-emerald-700 dark:text-emerald-300' : 'text-slate-600 dark:text-slate-400'">
                    {{ th.rate.toFixed(2) }}%
                  </td>
                  <td class="px-6 py-3 text-right font-bold tabular-nums" :class="selectedRecord!.scoreRate >= th.rate ? 'text-emerald-700 dark:text-emerald-300' : 'text-slate-500 dark:text-slate-400'">
                    {{ th.points }}
                  </td>
                  <td class="px-6 py-3 text-center">
                    <span v-if="selectedRecord!.scoreRate >= th.rate" class="text-emerald-500 font-bold text-base">✓</span>
                    <span v-else class="text-slate-300 dark:text-slate-600 font-bold text-sm">
                      +{{ (th.rate - selectedRecord!.scoreRate).toFixed(2) }}%
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ===== Ranking タブ: 自分 + フレンド + (任意)公開ユーザー + (任意)TOPランカー仮想ユーザーを統合表示 ===== -->
        <div v-else-if="modalTab === 'rivals'" class="w-full">
          <!-- 表示フィルタ: 公開ユーザー / 仮想ユーザーの表示切替 -->
          <div class="flex flex-wrap items-center gap-4 mb-4 p-3 bg-slate-50 dark:bg-slate-700/30 rounded-md border border-slate-100 dark:border-slate-700">
            <label class="flex items-center gap-2 text-sm font-bold text-slate-700 dark:text-slate-200 cursor-pointer">
              <input type="checkbox" v-model="showPublicUsers" class="w-4 h-4 rounded accent-blue-600" />
              スコア公開ユーザーも表示
            </label>
            <label class="flex items-center gap-2 text-sm font-bold text-slate-700 dark:text-slate-200 cursor-pointer">
              <input type="checkbox" v-model="showVirtualUsers" class="w-4 h-4 rounded accent-amber-500" />
              TOPランカー仮想ユーザーを表示
            </label>
          </div>

          <!-- ===== 単曲ランク分布: スコアのある全実ユーザー（非公開含む・匿名集計）の単曲ランクをヒストグラム表示 ===== -->
          <div v-if="songTierDist" class="mb-4 p-4 bg-white dark:bg-slate-800 rounded-md border border-slate-100 dark:border-slate-700">
            <div class="flex flex-wrap items-baseline justify-between gap-2 mb-2">
              <h4 class="text-sm font-bold text-slate-700 dark:text-slate-200">{{ t('table.songRankDist') }}</h4>
              <span class="text-[10px] text-slate-400 dark:text-slate-500">{{ t('table.songRankDistNote', { n: songTierDist.total }) }}</span>
            </div>
            <div class="h-40"><BarChart :data="songTierDist.data" :options="songTierDistOpts" /></div>
          </div>

          <div v-if="isLoadingRivals || isLoadingSongRanking" class="flex flex-col items-center justify-center py-20">
            <div class="w-10 h-10 border-4 border-blue-100 border-t-blue-600 rounded-full animate-spin mb-4"></div>
            <p class="text-slate-500 dark:text-slate-400">{{ t('common.loading') }}</p>
          </div>
          <div v-else-if="rankingList.length === 0" class="flex flex-col items-center justify-center py-20 text-slate-400 dark:text-slate-500">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-12 w-12 mb-3 opacity-40" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
            </svg>
            <p class="font-bold">{{ t('table.rivalNotPlayed') }}</p>
          </div>
          <div v-else class="overflow-x-auto">
            <table class="w-full">
              <thead>
                <tr class="text-left border-b border-slate-100 dark:border-slate-700/50">
                  <th class="pb-3 pl-3 text-xs font-bold text-slate-400 w-12">{{ t('table.colRankNum') }}</th>
                  <th class="pb-3 text-xs font-bold text-slate-400">{{ t('table.colPlayer') }}</th>
                  <th class="pb-3 text-xs font-bold text-slate-400 w-16 text-center">{{ t('table.colSongRank') }}</th>
                  <th class="pb-3 text-xs font-bold text-slate-400 text-right w-24">{{ t('table.colScore') }}</th>
                  <th class="pb-3 text-xs font-bold text-slate-400 text-right w-20">{{ t('table.colRate') }}</th>
                  <th class="pb-3 pr-3 text-xs font-bold text-slate-400 text-right w-20">{{ t('table.colBeatPt') }}</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
                <tr
                  v-for="row in displayedRankingRows"
                  :key="row.key"
                  class="transition-colors"
                  :class="[
                    row.isSelf ? 'bg-blue-50/60 dark:bg-blue-900/20'
                      : row.kind === 'virtual' ? 'bg-amber-50/30 dark:bg-amber-900/10'
                      : 'hover:bg-slate-50 dark:hover:bg-slate-700/30',
                    row.kind === 'virtual' || (row.kind === 'user' && row.userId && (row.privacyLevel ?? 1) === 0 && !row.isSelf) ? 'cursor-pointer' : ''
                  ]"
                  @click="row.kind === 'virtual' ? handleSongTopRankerRowClick(row.virtualEntry!) : (row.kind === 'user' && !row.isSelf ? handleSongUserRowClick({ userId: row.userId ?? null, iidxId: row.iidxId, privacyLevel: row.privacyLevel ?? null, displayName: row.displayName, score: row.score ?? 0, totalBeatPt: row.totalBeatPt ?? 0 }) : null)"
                >
                  <!-- 順位 -->
                  <td class="py-3 pl-3">
                    <div v-if="row.rank != null" class="flex items-center justify-center w-7 h-7 rounded-lg font-bold text-xs"
                      :class="[
                        row.rank === 1 ? 'bg-amber-100 text-amber-700 dark:bg-amber-500 dark:text-white' :
                        row.rank === 2 ? 'bg-slate-200 text-slate-700 dark:bg-slate-400 dark:text-white' :
                        row.rank === 3 ? 'bg-orange-100 text-orange-700 dark:bg-orange-400 dark:text-white' :
                        'text-slate-400 border border-slate-100 dark:border-slate-700'
                      ]">
                      {{ row.rank }}
                    </div>
                    <div v-else class="flex items-center justify-center w-7 h-7 font-bold text-xs text-slate-300 dark:text-slate-600">-</div>
                  </td>
                  <!-- プレイヤー名 / 仮想ユーザ -->
                  <td class="py-3">
                    <template v-if="row.kind === 'virtual'">
                      <div class="flex items-center gap-2 min-w-0">
                        <span
                          v-if="row.virtualBadge === 'allTimeGlobal' || row.virtualBadge === 'globalAllTime'"
                          class="inline-flex items-center px-1.5 py-0.5 rounded bg-rose-500 text-white text-[10px] font-bold shrink-0"
                        >歴代</span>
                        <span
                          v-else-if="row.virtualBadge === 'allTimeArea'"
                          class="inline-flex items-center px-1.5 py-0.5 rounded bg-rose-500 text-white text-[10px] font-bold shrink-0"
                        >エリア歴代</span>
                        <span
                          v-else-if="row.virtualBadge === 'versionTop'"
                          class="inline-flex items-center px-1.5 py-0.5 rounded bg-indigo-500 text-white text-[10px] font-bold shrink-0"
                        >バージョンTOP</span>
                        <span
                          v-else
                          class="inline-flex items-center px-1.5 py-0.5 rounded bg-amber-500 text-white text-[10px] font-bold shrink-0"
                        >TOP</span>
                        <span class="font-bold text-slate-800 dark:text-slate-100 text-sm truncate">
                          {{ row.virtualEntry!.versionName }} {{ row.virtualEntry!.prefectureName }}
                          <span class="ml-1 text-xs text-slate-500 dark:text-slate-400">({{ row.virtualEntry!.djName }})</span>
                        </span>
                        <span v-if="row.isFriend" class="text-[10px] font-bold text-emerald-600 dark:text-emerald-400 shrink-0">Friend</span>
                      </div>
                    </template>
                    <template v-else>
                      <div class="flex items-center gap-2 min-w-0">
                        <span class="font-bold text-sm truncate" :class="row.isSelf ? 'text-blue-700 dark:text-blue-300' : 'text-slate-800 dark:text-slate-100'">
                          {{ row.displayName }}
                        </span>
                        <span v-if="!row.isSelf && (row.privacyLevel ?? 1) !== 0" class="text-xs text-slate-400 shrink-0" :title="(row.privacyLevel ?? 1) === 2 ? '非公開' : 'フレンドのみ公開'">🔒</span>
                        <span v-if="row.isFriend && !row.isSelf" class="text-[10px] font-bold text-emerald-600 dark:text-emerald-400 shrink-0">Friend</span>
                      </div>
                    </template>
                  </td>
                  <!-- 単曲ランクアイコン（その譜面のスコアレートから算出。スコア非公開行は非表示） -->
                  <td class="py-3 px-2 text-center">
                    <template v-if="row.kind === 'user' && !row.isSelf && (row.privacyLevel ?? 1) === 2 && !row.isFriend">
                      <span class="text-slate-300 dark:text-slate-600 text-xs">-</span>
                    </template>
                    <template v-else>
                      <div v-if="songRankOfScore(row.score)" class="flex justify-center">
                        <RankIcon
                          :rank-name="songRankOfScore(row.score)!.name"
                          :tier="songRankOfScore(row.score)!.tier"
                          size="sm"
                          lite
                        />
                      </div>
                      <span v-else class="text-slate-300 dark:text-slate-600 text-xs">-</span>
                    </template>
                  </td>
                  <!-- スコア -->
                  <td class="py-3 text-right">
                    <template v-if="row.kind === 'user' && !row.isSelf && (row.privacyLevel ?? 1) === 2 && !row.isFriend">
                      <span class="text-slate-400 dark:text-slate-500 text-sm font-bold">{{ t('table.privateShort') }}</span>
                    </template>
                    <template v-else-if="row.score != null">
                      <span class="font-bold text-slate-800 dark:text-slate-100 text-sm tabular-nums">{{ row.score.toLocaleString() }}</span>
                    </template>
                    <template v-else>
                      <span class="text-slate-400 text-sm">---</span>
                    </template>
                  </td>
                  <!-- スコアレート -->
                  <td class="py-3 text-right">
                    <span
                      v-if="row.score != null && selectedRecord && selectedRecord.maxScore > 0"
                      class="font-bold text-sm tabular-nums"
                      :class="(row.score / selectedRecord.maxScore * 100) >= 94.45 ? 'text-purple-600 dark:text-purple-400' : (row.score / selectedRecord.maxScore * 100) >= 88.89 ? 'text-amber-500 dark:text-amber-400' : 'text-slate-600 dark:text-slate-300'"
                    >
                      {{ (row.score / selectedRecord.maxScore * 100).toFixed(2) }}%
                    </span>
                    <span v-else class="text-slate-400 text-sm">---</span>
                  </td>
                  <!-- BEAT-PT -->
                  <td class="py-3 pr-3 text-right">
                    <span
                      v-if="row.score != null && selectedRecord && selectedRecord.maxScore > 0 && selectedRecord.informalRank"
                      class="font-bold text-indigo-600 dark:text-indigo-400 text-sm tabular-nums"
                    >
                      {{ calculatePoints(row.score / selectedRecord.maxScore * 100, selectedRecord.informalRank).toFixed(1) }}
                    </span>
                    <span v-else class="text-slate-400 text-sm">---</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- 描画件数の上限超過分は「残りを表示」で段階的に追加（モバイルのメモリ保護） -->
          <div v-if="hiddenRankingCount > 0" class="pt-3 text-center">
            <button
              type="button"
              @click="showMoreRanking"
              class="text-sm font-bold text-blue-600 dark:text-blue-400 hover:text-blue-700 dark:hover:text-blue-300 hover:underline focus:outline-none"
            >
              残り {{ hiddenRankingCount }} 件を表示
            </button>
          </div>
        </div>

        <!-- ===== History タブ: 譜面のスコア更新履歴を時系列表示 ===== -->
        <div v-else-if="modalTab === 'history'" class="w-full">
          <div v-if="isLoadingHistory" class="flex flex-col items-center justify-center py-20">
            <div class="w-10 h-10 border-4 border-violet-100 border-t-violet-500 rounded-full animate-spin mb-4"></div>
            <p class="text-slate-500 dark:text-slate-400">{{ t('common.loading') }}</p>
          </div>
          <div v-else-if="songHistory.length === 0" class="flex flex-col items-center justify-center py-20 text-slate-400 dark:text-slate-500">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-12 w-12 mb-3 opacity-40" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            <p class="font-bold">{{ t('table.noHistoryData') }}</p>
          </div>
          <div v-else class="overflow-x-auto">
            <table class="w-full">
              <thead>
                <tr class="text-left border-b border-slate-100 dark:border-slate-700/50">
                  <th class="pb-3 pl-3 text-xs font-bold text-slate-400">{{ t('table.colDate') }}</th>
                  <th class="pb-3 text-xs font-bold text-slate-400 text-right w-28">{{ t('table.exScore') }}</th>
                  <th class="pb-3 pr-3 text-xs font-bold text-slate-400 text-right w-24">BEAT-PT</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
                <tr
                  v-for="(entry, index) in songHistory"
                  :key="index"
                  class="hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors"
                >
                  <td class="py-3 pl-3 text-sm text-slate-600 dark:text-slate-300 tabular-nums">
                    {{ formatHistoryDate(entry.uploadedAt) }}
                  </td>
                  <td class="py-3 text-right font-bold text-slate-800 dark:text-slate-100 text-sm tabular-nums">
                    {{ entry.score != null ? entry.score.toLocaleString() : '---' }}
                  </td>
                  <td class="py-3 pr-3 text-right font-bold text-indigo-600 dark:text-indigo-400 text-sm tabular-nums">
                    {{ entry.beatPt != null ? entry.beatPt.toFixed(1) : '---' }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ===== 大台（Milestone）タブ: 100点刻みの節目ラインごとの達成人数を表示 ===== -->
        <div v-else-if="modalTab === 'milestone'" class="w-full max-w-4xl mx-auto space-y-6">
          <!-- サマリー: 集計人数 / 自分のスコア / MAX理論値 -->
          <div class="grid grid-cols-3 gap-3">
            <div class="bg-amber-900/10 dark:bg-amber-900/20 p-4 sm:p-6 rounded-md flex flex-col items-center justify-center border border-amber-100 dark:border-amber-800/50">
              <p class="text-[10px] sm:text-xs font-bold text-amber-600 dark:text-amber-400 mb-1">{{ t('table.milestonePlayers') }}</p>
              <p class="text-2xl sm:text-4xl font-bold text-amber-700 dark:text-amber-300 tabular-nums">{{ milestonePlayerCount }}</p>
            </div>
            <div class="bg-slate-50 dark:bg-slate-800 p-4 sm:p-6 rounded-md flex flex-col items-center justify-center border border-slate-200 dark:border-slate-700">
              <p class="text-[10px] sm:text-xs font-bold text-slate-500 dark:text-slate-400 mb-1">{{ t('table.exScore') }}</p>
              <p class="text-2xl sm:text-4xl font-bold text-slate-700 dark:text-slate-200 tabular-nums">
                <template v-if="selectedRecord!.score > 0">{{ selectedRecord!.score.toLocaleString() }}</template>
                <template v-else>---</template>
              </p>
            </div>
            <div class="bg-slate-50 dark:bg-slate-800 p-4 sm:p-6 rounded-md flex flex-col items-center justify-center border border-slate-200 dark:border-slate-700">
              <p class="text-[10px] sm:text-xs font-bold text-slate-500 dark:text-slate-400 mb-1">MAX</p>
              <p class="text-2xl sm:text-4xl font-bold text-slate-700 dark:text-slate-200 tabular-nums">{{ selectedRecord!.maxScore.toLocaleString() }}</p>
            </div>
          </div>

          <!-- ローディング -->
          <div v-if="isLoadingMilestones" class="flex flex-col items-center justify-center py-20">
            <div class="w-10 h-10 border-4 border-amber-100 border-t-amber-500 rounded-full animate-spin mb-4"></div>
            <p class="text-slate-500 dark:text-slate-400">{{ t('common.loading') }}</p>
          </div>

          <template v-else>
          <!-- AAA / MAX- 達成者の併記 -->
          <div class="grid grid-cols-2 gap-3">
            <div class="bg-white dark:bg-slate-800 p-3 sm:p-4 rounded-md border border-slate-200 dark:border-slate-700">
              <div class="flex items-center justify-between mb-1">
                <span class="text-sm font-bold text-slate-700 dark:text-slate-200">
                  <span v-if="milestoneAaaMax.achievedAaa" class="text-emerald-500">✓ </span>AAA
                </span>
                <span class="text-[10px] text-slate-400 tabular-nums">88.89%〜</span>
              </div>
              <div class="flex items-baseline gap-1.5">
                <span class="text-xl sm:text-2xl font-bold text-amber-700 dark:text-amber-300 tabular-nums">{{ milestoneAaaMax.aaaCount.toLocaleString() }}</span>
                <span class="text-xs text-slate-400">{{ t('table.milestoneAchievers') }}</span>
                <span class="text-xs text-slate-500 dark:text-slate-400 tabular-nums ml-auto">{{ milestoneAaaMax.aaaRate.toFixed(1) }}%</span>
              </div>
            </div>
            <div class="bg-white dark:bg-slate-800 p-3 sm:p-4 rounded-md border border-slate-200 dark:border-slate-700">
              <div class="flex items-center justify-between mb-1">
                <span class="text-sm font-bold text-slate-700 dark:text-slate-200">
                  <span v-if="milestoneAaaMax.achievedMaxMinus" class="text-emerald-500">✓ </span>MAX-
                </span>
                <span class="text-[10px] text-slate-400 tabular-nums">94.44%〜</span>
              </div>
              <div class="flex items-baseline gap-1.5">
                <span class="text-xl sm:text-2xl font-bold text-amber-700 dark:text-amber-300 tabular-nums">{{ milestoneAaaMax.maxMinusCount.toLocaleString() }}</span>
                <span class="text-xs text-slate-400">{{ t('table.milestoneAchievers') }}</span>
                <span class="text-xs text-slate-500 dark:text-slate-400 tabular-nums ml-auto">{{ milestoneAaaMax.maxMinusRate.toFixed(1) }}%</span>
              </div>
            </div>
          </div>

          <!-- 大台ラインテーブル（降順） -->
          <div class="border border-slate-200 dark:border-slate-700 rounded-md overflow-hidden bg-white dark:bg-slate-800">
            <div class="bg-slate-100 dark:bg-slate-900/50 px-4 sm:px-6 py-3 border-b border-slate-200 dark:border-slate-700">
              <p class="text-xs font-bold text-slate-600 dark:text-slate-400">{{ t('table.milestone') }}</p>
            </div>
            <table class="w-full text-sm">
              <thead>
                <tr class="border-b border-slate-100 dark:border-slate-700">
                  <th class="px-4 sm:px-6 py-3 text-left text-xs font-bold text-slate-400">{{ t('table.exScore') }}</th>
                  <th class="px-2 py-3 text-left text-xs font-bold text-slate-400 hidden sm:table-cell">{{ t('table.milestoneRate') }}</th>
                  <th class="px-4 sm:px-6 py-3 text-right text-xs font-bold text-slate-400">{{ t('table.milestoneAchievers') }}</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
                <tr
                  v-for="row in milestoneRows"
                  :key="row.line"
                  class="transition-colors"
                  :class="[
                    row.achievedBySelf ? 'bg-emerald-50/60 dark:bg-emerald-900/10' : 'hover:bg-slate-50 dark:hover:bg-slate-700/20',
                    row.isNextTarget ? 'ring-1 ring-inset ring-amber-400 dark:ring-amber-500/60' : ''
                  ]"
                >
                  <!-- ラインスコア + スコアレート% + 自分マーカー -->
                  <td class="px-4 sm:px-6 py-3">
                    <div class="flex items-center gap-2">
                      <span class="text-emerald-500 font-bold text-base" v-if="row.achievedBySelf">✓</span>
                      <span class="font-bold tabular-nums text-base" :class="row.achievedBySelf ? 'text-emerald-700 dark:text-emerald-300' : 'text-slate-700 dark:text-slate-200'">
                        {{ row.line.toLocaleString() }}
                      </span>
                      <span class="text-[11px] text-slate-400 tabular-nums">{{ row.lineRate.toFixed(2) }}%</span>
                      <span v-if="row.isNextTarget" class="inline-flex items-center px-1.5 py-0.5 rounded bg-amber-500 text-white text-[10px] font-bold shrink-0">
                        {{ t('table.milestoneToGo', { n: row.toGo }) }}
                      </span>
                    </div>
                  </td>
                  <!-- 達成率のプログレスバー -->
                  <td class="px-2 py-3 hidden sm:table-cell">
                    <div class="flex items-center gap-2">
                      <div class="flex-1 h-2 rounded-full bg-slate-100 dark:bg-slate-700 overflow-hidden">
                        <div class="h-full rounded-full bg-amber-500" :style="{ width: row.rate + '%' }"></div>
                      </div>
                      <span class="text-xs text-slate-500 dark:text-slate-400 tabular-nums w-12 text-right">{{ row.rate.toFixed(1) }}%</span>
                    </div>
                  </td>
                  <!-- 達成人数 -->
                  <td class="px-4 sm:px-6 py-3 text-right">
                    <span class="font-bold tabular-nums text-slate-700 dark:text-slate-200">{{ row.count.toLocaleString() }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
            <!-- 0人時の注記 -->
            <div v-if="milestonesLoaded && milestonePlayerCount === 0" class="px-4 sm:px-6 py-4 text-center text-xs text-slate-400 dark:text-slate-500">
              {{ t('table.milestoneNoPlayers') }}
            </div>
          </div>
          </template>

          <!-- 脚注: 匿名集計の注記 -->
          <p class="text-[10px] text-slate-400 dark:text-slate-500 text-center">{{ t('table.milestoneNote') }}</p>
        </div>

        <!-- ===== デフォルト（Detail）タブ: 譜面情報 + 各スコアパネル + 判定内訳 + 投票 + 目標計算 + メモ ===== -->
        <div v-else class="max-w-4xl mx-auto space-y-4 sm:space-y-8">

          <!-- 譜面メタ情報（難易度バッジ + タイトル） -->
          <div class="flex flex-col items-center sm:items-start gap-2 sm:gap-4">
            <div class="flex flex-wrap gap-2 justify-center sm:justify-start">
              <span :class="['px-3 py-1 sm:px-4 sm:py-1.5 rounded text-xs sm:text-sm font-bold', selectedRecord.difficultyColor]">
                {{ selectedRecord.difficultyName }} {{ selectedRecord.difficultyLevel ? `☆${selectedRecord.difficultyLevel}` : '' }}
              </span>
              <span v-if="selectedRecord.informalRank" class="px-3 py-1 sm:px-4 sm:py-1.5 rounded text-xs sm:text-sm font-bold bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 border border-slate-200 dark:border-slate-700">
                {{ t('table.colInformal') }}: {{ selectedRecord.informalRank }}
              </span>
            </div>
            <h3 class="text-2xl sm:text-5xl font-bold text-slate-800 dark:text-slate-100 tracking-tight text-center sm:text-left leading-tight mt-1 sm:mt-0">
              {{ selectedRecord.title }}
            </h3>
          </div>
          <!-- 最終プレー日時バッジ -->
          <div class="flex flex-wrap items-center justify-between gap-3">
            <span class="text-xs sm:text-sm font-bold text-slate-500 dark:text-slate-400 border border-slate-300 dark:border-slate-600 px-3 sm:px-4 py-1.5 sm:py-2 rounded-lg bg-white dark:bg-slate-800 transition-colors">
              {{ t('table.lastPlayTime') }}: <span class="text-slate-700 dark:text-slate-200 font-bold">{{ selectedRecord.lastPlayTime || t('table.unknown') }}</span>
            </span>
          </div>

          <!-- スコアパネル 2×2 グリッド: ランプ / DJ LEVEL / BEAT-PT / EX スコア + スコアレート -->
          <div class="grid grid-cols-2 gap-3 sm:gap-6">
            <div class="bg-white dark:bg-slate-800 p-3 sm:p-6 rounded-md border border-slate-200 dark:border-slate-700 flex flex-col items-center justify-center relative overflow-hidden transition-colors duration-200">
              <div class="absolute top-0 left-0 w-full h-1 sm:h-2" :class="getClearTypeBgColor(selectedRecord.clearType)"></div>
              <p class="text-xs sm:text-sm font-bold text-slate-400 dark:text-slate-500 mb-1 mt-1 sm:mb-2 sm:mt-2">{{ t('table.lamp') }}</p>
              <p class="text-lg sm:text-4xl font-bold text-center" :class="getClearTypeColor(selectedRecord.clearType)">
                {{ selectedRecord.clearType }}
              </p>
            </div>
            <div class="bg-white dark:bg-slate-800 p-3 sm:p-6 rounded-md border border-slate-200 dark:border-slate-700 flex flex-col items-center justify-center relative overflow-hidden transition-colors duration-200">
              <div class="absolute top-0 left-0 w-full h-1 sm:h-2" :class="getDjLevelBgColor(selectedRecord.djLevel)"></div>
              <p class="text-xs sm:text-sm font-bold text-slate-400 dark:text-slate-500 mb-1 mt-1 sm:mb-2 sm:mt-2">{{ t('table.colRank') }}</p>
              <div class="flex flex-col items-center">
                <p class="text-4xl sm:text-6xl font-bold text-center" :class="getDjLevelColor(selectedRecord.djLevel)">
                  {{ selectedRecord.djLevel }}
                </p>
              </div>
            </div>
            
            <div class="bg-indigo-900/10 dark:bg-indigo-900/20 p-4 sm:p-8 rounded-md flex flex-col items-center justify-center col-span-2 sm:col-span-1 transition-colors duration-200 border border-indigo-100 dark:border-indigo-800/50">
              <p class="text-xs sm:text-sm font-bold text-indigo-500 dark:text-indigo-400 mb-1 sm:mb-2">BEAT-PT</p>
              <div class="flex items-baseline gap-1 sm:gap-2">
                <p class="text-4xl sm:text-6xl font-bold text-indigo-700 dark:text-indigo-300 tracking-tight">
                  {{ selectedRecord.beatTierPoints.toFixed(1) }}
                </p>
                <p v-if="selectedRecord.maxBeatTierPoints > 0" class="text-sm sm:text-xl font-bold text-indigo-400 dark:text-indigo-500">/ {{ selectedRecord.maxBeatTierPoints.toFixed(1) }}</p>
              </div>
            </div>
            
            <div class="bg-slate-800 dark:bg-slate-700 p-4 sm:p-8 rounded-md flex flex-col items-center justify-center col-span-2 sm:col-span-1 transition-colors duration-200">
              <p class="text-xs sm:text-sm font-bold text-slate-400 dark:text-slate-300 mb-1 sm:mb-2">{{ t('table.exScore') }}</p>
              <div class="flex items-baseline gap-1 sm:gap-2">
                <p class="text-4xl sm:text-6xl font-bold text-white tracking-tight">
                  {{ selectedRecord.score }}
                </p>
                <p v-if="selectedRecord.maxScore > 0" class="text-sm sm:text-xl font-bold text-slate-500 dark:text-slate-400">/ {{ selectedRecord.maxScore }}</p>
              </div>
            </div>
            <div 
               class="p-4 sm:p-8 rounded-md flex flex-col items-center justify-center col-span-2 sm:col-span-1 transition-colors relative duration-200"
               :class="selectedRecord.scoreRate >= 0 ? 'bg-blue-50 dark:bg-slate-800 border-4 border-blue-200 dark:border-slate-700' : 'bg-slate-100 dark:bg-slate-800/50 border-dashed border-4 border-slate-300 dark:border-slate-600 group cursor-help'"
               :title="selectedRecord.scoreRate >= 0 ? '' : t('table.rateCalculabilityHint')"
            >
               <p class="text-xs sm:text-sm font-bold mb-1 sm:mb-2" :class="selectedRecord.scoreRate >= 0 ? 'text-blue-500 dark:text-blue-400' : 'text-slate-500 dark:text-slate-400'">{{ t('table.individualRate') }}</p>
               <p class="text-4xl sm:text-6xl font-bold tracking-tight flex items-baseline" :class="selectedRecord.scoreRate >= 0 ? 'text-blue-600 dark:text-blue-300' : 'text-slate-300 dark:text-slate-600 transition-colors group-hover:text-slate-400 dark:group-hover:text-slate-500'">
                 <template v-if="selectedRecord.scoreRate >= 0">
                    {{ selectedRecord.scoreRate.toFixed(2) }}
                 </template>
                 <template v-else>
                    ---
                 </template>
                <span class="text-xl sm:text-3xl font-bold ml-1 sm:ml-2">%</span>
              </p>
            </div>
          </div>

          <!-- 判定内訳: PGREAT / GREAT / MISS を 3 カラムで表示 -->
          <div class="border border-slate-200 dark:border-slate-700 rounded-md overflow-hidden bg-white dark:bg-slate-800 transition-colors duration-200">
            <div class="bg-slate-100 dark:bg-slate-900/50 px-4 sm:px-6 py-2 sm:py-4 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between transition-colors duration-200">
              <p class="text-xs sm:text-sm font-bold text-slate-600 dark:text-slate-400">{{ t('table.judgmentDetail') }}</p>
            </div>
            <div class="grid grid-cols-3 divide-x divide-slate-200 dark:divide-slate-700">
              <div class="p-3 sm:p-6 lg:p-8 flex flex-col items-center justify-center bg-amber-50/50 dark:bg-slate-800/50 transition-colors duration-200">
                <span class="text-[10px] sm:text-sm text-amber-500 dark:text-amber-400 font-bold mb-1 sm:mb-2">PGREAT</span>
                <span class="text-2xl sm:text-5xl font-bold text-slate-800 dark:text-slate-200">{{ selectedRecord.pgreat }}</span>
              </div>
              <div class="p-3 sm:p-6 lg:p-8 flex flex-col items-center justify-center bg-yellow-50/50 dark:bg-slate-800/50 transition-colors duration-200">
                <span class="text-[10px] sm:text-sm text-yellow-500 dark:text-yellow-400 font-bold mb-1 sm:mb-2">GREAT</span>
                <span class="text-2xl sm:text-5xl font-bold text-slate-800 dark:text-slate-200">{{ selectedRecord.great }}</span>
              </div>
              <div class="p-3 sm:p-6 lg:p-8 flex flex-col items-center justify-center bg-red-50/50 dark:bg-slate-800/50 transition-colors duration-200">
                <span class="text-[10px] sm:text-sm text-red-400 dark:text-red-500 font-bold mb-1 sm:mb-2">MISS</span>
                <span class="text-2xl sm:text-5xl font-bold text-slate-800 dark:text-slate-200">{{ selectedRecord.missCount !== null ? selectedRecord.missCount : '-' }}</span>
              </div>
            </div>
          </div>

          <!-- ===== リザルト画像セクション（端末ファイルから登録 → R2 保存 → タップで拡大）=====
               リザルト画像は本人専用。他ユーザーのスコア閲覧時（viewingMode あり）は表示しない。 -->
          <ResultImageSection
            v-if="isOwnData"
            :title="selectedRecord.title"
            :difficulty-name="selectedRecord.difficultyName"
            :difficulty-level="selectedRecord.difficultyLevel"
          />

          <!-- ===== オプション投票セクション（正規/MIRROR/RANDOM/R-RAN/S-RAN）===== -->
          <div class="border border-slate-200 dark:border-slate-700 rounded-md overflow-hidden bg-white dark:bg-slate-800 mt-6 transition-colors duration-200">
            <div class="bg-emerald-50 dark:bg-emerald-900/30 px-4 sm:px-6 py-3 sm:py-4 border-b border-emerald-100 dark:border-emerald-800/50 flex items-center justify-between transition-colors duration-200">
              <p class="text-xs sm:text-sm font-bold text-emerald-700 dark:text-emerald-400 flex items-center gap-2">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                  <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
                </svg>
                {{ t('table.optionVote') }}
              </p>
              <span v-if="voteData.totalVotes > 0" class="text-[10px] sm:text-xs font-bold text-emerald-500 dark:text-emerald-400 bg-emerald-100 dark:bg-emerald-900/50 px-2 py-0.5 rounded">{{ t('table.voteCount', { n: voteData.totalVotes }) }}</span>
            </div>
            <div class="p-4 sm:p-6">
              <div class="mb-4 p-3 bg-slate-50 dark:bg-slate-800/50 rounded-md border border-slate-200 dark:border-slate-700/50">
                <p class="text-[11px] sm:text-xs text-slate-600 dark:text-slate-400 leading-relaxed font-medium">
                  {{ t('table.voteHint') }}
                </p>
              </div>
              
              <!-- 投票ボタン（ログイン中のみ）: 自分の票と一致したボタンは active 色で強調 -->
              <div v-if="isLoggedIn" class="flex flex-wrap gap-2 mb-4">
                <button
                  v-for="opt in optionTypes"
                  :key="opt.value"
                  @click="castVote(opt.value)"
                  :disabled="isVoting"
                  class="px-3 py-2 rounded-md text-xs sm:text-sm font-bold border-2 transition-all flex items-center gap-1.5 disabled:opacity-50"
                  :class="voteData.myVotes.includes(opt.value)
                    ? `${opt.activeBg} ${opt.activeText} ${opt.activeBorder}`
                    : 'bg-slate-50 dark:bg-slate-900 text-slate-600 dark:text-slate-400 border-slate-200 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800'"
                >
                  <span>{{ opt.icon }}</span>
                  {{ opt.label }}
                  <span v-if="voteData.myVotes.includes(opt.value)" class="text-[10px]">✔</span>
                </button>
              </div>
              <div v-else class="mb-4 p-3 bg-slate-50 dark:bg-slate-900 rounded-md text-sm text-slate-500 dark:text-slate-400 italic text-center">
                {{ t('table.loginToVote') }}
              </div>
              
              <!-- 投票結果のバーチャート表示（各オプションの票数と割合） -->
              <div v-if="voteData.totalVotes > 0" class="space-y-2">
                <div v-for="opt in optionTypes" :key="opt.value" class="flex items-center gap-2">
                  <span class="text-[10px] sm:text-xs font-bold w-20 sm:w-24 text-right shrink-0" :class="opt.labelColor">{{ opt.label }}</span>
                  <div class="flex-1 h-6 sm:h-7 bg-slate-100 dark:bg-slate-700 rounded-lg overflow-hidden relative">
                    <div
                      class="h-full rounded-lg transition-all duration-500 flex items-center justify-end pr-2"
                      :class="opt.barColor"
                      :style="{ width: `${getVotePercent(opt.value)}%`, minWidth: (voteData.counts[opt.value] || 0) > 0 ? '24px' : '0px' }"
                    >
                      <span v-if="(voteData.counts[opt.value] || 0) > 0" class="text-[10px] sm:text-xs font-bold text-white drop-shadow-sm">{{ voteData.counts[opt.value] }}</span>
                    </div>
                    <span v-if="(voteData.counts[opt.value] || 0) === 0" class="absolute left-2 top-1/2 -translate-y-1/2 text-[10px] font-bold text-slate-400 dark:text-slate-500">0</span>
                  </div>
                  <span class="text-[10px] sm:text-xs font-bold text-slate-400 dark:text-slate-500 w-10 text-right">{{ getVotePercent(opt.value).toFixed(0) }}%</span>
                </div>
              </div>
              <div v-else class="text-center py-4 text-sm text-slate-400 dark:text-slate-500">
                {{ t('table.noVotesYet') }}
              </div>
            </div>
          </div>

          <!-- ===== BEAT-PT 目標計算セクション: スライダーで目標 PT 増分を選ぶと、必要スコアを逆算して表示 ===== -->
          <div v-if="selectedRecord.maxScore > 0 && selectedRecord.maxBeatTierPoints > 0 && selectedRecord.beatTierPoints < selectedRecord.maxBeatTierPoints" class="border border-slate-200 dark:border-slate-700 rounded-md overflow-hidden bg-white dark:bg-slate-800 mt-6 transition-colors duration-200">
            <div class="bg-indigo-50 dark:bg-indigo-900/30 px-6 py-4 border-b border-indigo-100 dark:border-indigo-800/50 flex items-center justify-between transition-colors duration-200">
              <p class="text-sm font-bold text-indigo-700 dark:text-indigo-400 flex items-center gap-2">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                </svg>
                {{ t('scores.targetSimulator') }}
              </p>
            </div>
            <div class="p-6">
              <div class="flex flex-col gap-4">
                <div class="flex justify-between items-end">
                  <span class="text-sm font-bold text-slate-600 dark:text-slate-400">{{ t('scores.targetHowManyPt') }}</span>
                  <div class="flex flex-col items-end">
                    <span class="text-2xl font-bold text-indigo-600 dark:text-indigo-400">+{{ targetBeatPtSlider.toFixed(1) }} <span class="text-sm">pt</span></span>
                    <span v-if="targetBeatPtSlider > 0" class="text-xs font-bold text-indigo-500 dark:text-indigo-500/80">
                      {{ t('scores.targetTotalPt', { n: (selectedRecord.beatTierPoints + targetBeatPtSlider).toFixed(1) }) }}
                    </span>
                  </div>
                </div>
                <input 
                  type="range" 
                  min="0" 
                  :max="selectedRecord.maxBeatTierPoints - selectedRecord.beatTierPoints" 
                  step="0.1" 
                  v-model.number="targetBeatPtSlider"
                  class="w-full h-2 bg-slate-200 rounded-lg appearance-none cursor-pointer dark:bg-slate-700 accent-indigo-600"
                >
                <div class="flex justify-between text-xs font-medium text-slate-400 dark:text-slate-500">
                  <span>0</span>
                  <span>{{ t('scores.targetMaxIncrease', { n: (selectedRecord.maxBeatTierPoints - selectedRecord.beatTierPoints).toFixed(1) }) }}</span>
                </div>
                
                <div v-if="targetBeatPtSlider > 0" class="mt-4 p-4 bg-indigo-50 dark:bg-indigo-900/20 rounded-md border border-indigo-100 dark:border-indigo-800/50 flex flex-col items-center justify-center text-center">
                  <p class="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">{{ t('scores.targetScoreNeededLabel') }}</p>
                  <p class="text-4xl font-bold text-indigo-700 dark:text-indigo-400 flex items-baseline gap-1">
                    {{ t('scores.targetScoreNeededValue', { n: targetScoreNeeded }) }}
                  </p>
                  <p class="text-xs font-medium text-indigo-500 dark:text-indigo-400 mt-2">
                    {{ t('scores.targetFinalScore', { score: selectedRecord.score + targetScoreNeeded, max: selectedRecord.maxScore, rate: (((selectedRecord.score + targetScoreNeeded) / selectedRecord.maxScore) * 100).toFixed(2) }) }}
                  </p>
                </div>
              </div>
            </div>
          </div>

          <!-- ===== メモ セクション: 譜面ごとのフリーテキストメモ（ログイン時のみ編集可） ===== -->
          <div v-if="selectedRecord.id || !isLoggedIn" class="border border-slate-200 dark:border-slate-700 rounded-md overflow-hidden bg-white dark:bg-slate-800 mt-6 transition-colors duration-200">
            <div class="bg-slate-100 dark:bg-slate-900/50 px-6 py-4 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between transition-colors duration-200">
              <p class="text-sm font-bold text-slate-600 dark:text-slate-400">{{ t('scores.options') }}</p>
              <a href="https://www.iidx-memo.com/" target="_blank" rel="noopener noreferrer" class="text-blue-600 dark:text-blue-400 hover:text-blue-700 dark:hover:text-blue-300 text-xs font-bold flex items-center gap-1 transition-colors">
                iidx-memo
                <svg xmlns="http://www.w3.org/2000/svg" class="h-3 w-3" viewBox="0 0 20 20" fill="currentColor">
                  <path d="M11 3a1 1 0 100 2h2.586l-6.293 6.293a1 1 0 101.414 1.414L15 6.414V9a1 1 0 102 0V4a1 1 0 00-1-1h-5z" />
                  <path d="M5 5a2 2 0 00-2 2v8a2 2 0 002 2h8a2 2 0 002-2v-3a1 1 0 10-2 0v3H5V7h3a1 1 0 000-2H5z" />
                </svg>
              </a>
            </div>
            <div class="p-6">
              <template v-if="selectedRecord.options && selectedRecord.options.length > 0">
                <div class="flex flex-wrap gap-2">
                  <span v-for="opt in selectedRecord.options" :key="opt"
                        class="inline-flex items-center px-3 py-1 rounded text-sm font-bold bg-blue-50 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300 border border-blue-200 dark:border-blue-800">
                    {{ opt }}
                  </span>
                </div>
              </template>
              <template v-else>
                <div class="text-slate-400 dark:text-slate-500 italic text-sm">{{ t('scores.noOptions') }}</div>
              </template>
            </div>
          </div>
          
        </div>
      </div>
      
      <!-- ===== モーダル Sticky フッタ: 「一覧に戻る」ボタン（全タブ共通） ===== -->
      <div class="sticky bottom-0 bg-white dark:bg-slate-900 border-t border-slate-200 dark:border-slate-800 p-4 sm:p-6 shadow-[0_-10px_20px_-10px_rgba(0,0,0,0.05)] dark:shadow-[0_-10px_20px_-10px_rgba(0,0,0,0.2)] w-full flex justify-center z-10 transition-colors duration-200">
         <button @click="closeDetailModal" class="w-full max-w-md px-8 py-4 bg-blue-600 hover:bg-blue-700 text-white text-lg font-bold rounded-md transition-colors flex items-center justify-center gap-2">
           <svg class="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
           </svg>
           {{ t('common.backToList') }}
         </button>
      </div>
    </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 曲別スコア一覧画面の中核。テーブル描画・フィルタ・並び替え・詳細モーダルをすべて担当する。
 *
 * 画面全体構造:
 *  - 上段フィルタ: レベル / 難易度 / DJ LEVEL / クリアランプ / 検索語 / 0 点非表示
 *  - モード切替: BEAT-TIER モードと RATE-TIER モードのタブ（RATE 機能は composable で制御）
 *  - データテーブル: 曲・譜面・スコアレート・BEAT-PT（または RATE-PT）を一覧表示
 *      - BEAT-TIER: TOP100 ハイライト＋「あと何点で TOP100 に入れるか」を表示
 *      - RATE-TIER: RATE-PT の TOP100 ハイライト＋パーフェクト超過時の強調
 *  - ページネーション: 10/25/50/100 件切替
 *  - 詳細モーダル（フルスクリーン Teleport）:
 *      - 詳細タブ: ランプ/AAA/PGREAT/GREAT/MISS 等の細かいステータス
 *      - Rate-Tier タブ: 閾値テーブル
 *      - ランキングタブ: 自分+フレンド+公開+仮想(TOPランカー) をマージしたマルチソース順位表
 *      - 履歴タブ: 自分のアップロード履歴からスコア推移を表示
 *      - オプション投票・メモ編集・目標PT電卓 もモーダル内に収録
 *
 * Props:
 *  - scores: 画面外から渡されるスコアデータ（曲単位の配列）
 *  - viewingMode: 他ユーザー閲覧モード。'topRanker' のときは djName 等の仮想ユーザー表示を追加する
 *
 * Emits:
 *  - reset: 親コンポーネントに「やり直し/再取り込み」を要求
 *  - update:totalPoints: 総 BEAT-PT を親へ通知（TOP100 合計）
 *  - view-user: テーブル行/ランキング行から公開ユーザー閲覧へ遷移
 *  - view-top-ranker: TOP ランカー（仮想ユーザー）閲覧へ遷移
 *
 * 依存 Composable:
 *  - `useScores`: メモ更新 API
 *  - `useDarkMode`: ダークモード判定（色分岐で参照）
 *  - `useAuth`: 認証・ヘッダ付与
 *  - `useRateTierVisibility`: RATE-TIER 機能の ON/OFF フラグ（サーバー側の段階的リリース対応）
 *  - `useGameData`: song_data.json / 難易度表マスター
 */
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { useI18n } from '../composables/useI18n';
import type { ScoreData } from '../types/ScoreData';
import { flattenScores, type ScoreRecord } from '../utils/scoreData';
import { computeMilestoneLines } from '../utils/milestones';
import { songData as songDataBodyRef, diffTable as diffTableRanksRef } from '../composables/useGameData';
import { calculatePoints, getMaxPoints, getRankInfo, calculateScoreRateTierPoints, SCORE_RATE_THRESHOLDS, getFolderRankInfoByRate, FOLDER_RANK_DEFS, type RankInfo } from '../utils/beatTier';
import { calcBpi } from '../utils/bpi';
import { useScores } from '../composables/useScores';
import { useDarkMode } from '../composables/useDarkMode';
import { useAuth } from '../composables/useAuth';
import { useAdmin } from '../composables/useAdmin';
import { useRateTierVisibility } from '../composables/useRateTierVisibility';
import { useFriends } from '../composables/useFriends';
import { DJ_LEVELS } from '../composables/constants';
import ResultImageSection from './ResultImageSection.vue';
import RankIcon from './RankIcon.vue';
import InformalRankBadge from './InformalRankBadge.vue';
import { Bar as BarChart } from 'vue-chartjs';
import { Chart as ChartJS, CategoryScale, LinearScale, BarElement, BarController, Tooltip, Legend } from 'chart.js';

ChartJS.register(CategoryScale, LinearScale, BarElement, BarController, Tooltip, Legend);

/** 難易度フィルタの選択肢（ANOTHER / LEGGENDARIA に固定。BEAT-PT 集計対象）。 */
const DIFFICULTY_FILTER_OPTIONS = ['ANOTHER', 'LEGGENDARIA'] as const;

const { isDarkMode } = useDarkMode();
const { isLoggedIn, authHeaders, user } = useAuth();
/** 管理者判定。判定ロジックは useAdmin composable に集約されている。 */
const { isAdmin } = useAdmin();

/** API ベース URL。未設定時はローカル開発用のデフォルト。 */
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

const props = defineProps<{
  scores: ScoreData[];
  viewingMode?: 'admin' | 'friend' | 'public' | 'topRanker' | 'arenaTopRanker' | null;
  /** BPI 列を表示するか。きんじょー杯ページ（/kinjocup）でのみ true を渡す。 */
  showBpi?: boolean;
}>();

/**
 * 自分のデータを表示中か（他ユーザー閲覧モードでないか）。
 * viewingMode が立っている＝他人のスコアを閲覧中なので false。
 * リザルト画像など「本人専用」のUIを出し分けるのに使う。
 */
const isOwnData = computed(() => !props.viewingMode);

// emit の定義は totalBeatTierPoints の定義直後にまとめる（参照順の都合）

const { showRateTier } = useRateTierVisibility();
const { t } = useI18n();
/** 現在のモード。'beat' は BEAT-TIER、'rate' は RATE-TIER 表示。 */
const viewMode = ref<'beat' | 'rate'>('beat');

/** 譜面別ランキングマップ。キーは `title|difficultyName`。自分が全ユーザー中何位かを格納する。 */
const songRankMap = ref<Map<string, { rank: number; total: number }>>(new Map());

/**
 * 【関数の役割】 自分の譜面別順位をバックエンドから取得し、`songRankMap` に詰め替える。
 * 未ログインなら何もしない。エラーは握り潰して UI を壊さない。
 */
const fetchSongRanks = async () => {
  if (!isLoggedIn.value) return;
  try {
    const res = await fetch(`${API_BASE}/api/scores/my-song-ranks`, { headers: authHeaders() });
    if (res.ok) {
      const data: Array<{ title: string; difficultyName: string; rank: number; total: number }> = await res.json();
      const map = new Map<string, { rank: number; total: number }>();
      data.forEach(r => map.set(`${r.title}|${r.difficultyName}`, { rank: r.rank, total: r.total }));
      songRankMap.value = map;
    }
  } catch { /* 握り潰し */ }
};
// 【watch】 RATE-TIER 機能フラグが OFF に切り替わった瞬間、RATE モード表示なら BEAT モードへ自動復帰。
watch(showRateTier, (val) => { if (!val && viewMode.value === 'rate') viewMode.value = 'beat'; });


/** テキスト検索クエリ。タイトル/アーティスト/ジャンル/ランプに対して部分一致する。 */
const searchQuery = ref('');
/** 難易度フィルタ（'ANOTHER' / 'LEGGENDARIA' の多選択）。空配列は「全て」。 */
const filterDifficulty = ref<string[]>([]);
/** レベルフィルタ（'11' / '12' など。RATE モードでは 1〜12）。空配列は「全て」。 */
const filterLevel = ref<string[]>([]);
/** DJ LEVEL フィルタ（'AAA' 〜 'F'）。空配列は「全て」。 */
const filterDjLevel = ref<string[]>([]);
/** クリアランプフィルタ（'FULLCOMBO CLEAR' など）。空配列は「全て」。 */
const filterClearType = ref<string[]>([]);
/** 取得元フィルタ（'infinitas' / 'arcade'）。空配列は「全て」。 */
const filterSource = ref<string[]>([]);
/** 0 点譜面を非表示にするトグル。未プレイ曲を隠したい場合に使う。 */
const hideZeroScore = ref(false);

/** 現在開いているドロップダウン名。null は閉じた状態。 */
const openDropdown = ref<string | null>(null);

/** 指定ドロップダウンを開閉する。既に開いていれば閉じる。 */
const toggleDropdown = (name: string) => {
  openDropdown.value = openDropdown.value === name ? null : name;
};

/**
 * 【関数の役割】 フィルタ配列に対する値のトグル操作。
 * 配列に値があれば取り除き、無ければ末尾に追加する。
 */
const toggleFilterValue = (arr: string[], value: string) => {
  const index = arr.indexOf(value);
  if (index === -1) {
    arr.push(value);
  } else {
    arr.splice(index, 1);
  }
};

/** チェックボックスの選択状態判定用ヘルパ。 */
const isSelected = (arr: string[], value: string) => {
  return arr.includes(value);
};

/**
 * 【computed の役割】 現在適用中のフィルタをチップ表示用の配列に正規化する。
 *
 * 各エントリは `{ id, label, remove }` の形で、チップの `×` ボタンで `remove()` を呼ぶと
 * 該当フィルタだけが解除される。フィルタが何もかかっていなければ空配列を返し、
 * チップ行とその「全クリア」ボタン自体を非表示にできる。
 */
const appliedFilterChips = computed<Array<{ id: string; label: string; remove: () => void }>>(() => {
  const chips: Array<{ id: string; label: string; remove: () => void }> = [];

  if (searchQuery.value.trim()) {
    const q = searchQuery.value.trim();
    chips.push({
      id: 'search',
      label: t('filter.searchTag', { q }),
      remove: () => { searchQuery.value = ''; },
    });
  }
  filterLevel.value.forEach((lv) => {
    chips.push({
      id: `level:${lv}`,
      label: `☆${lv}`,
      remove: () => { filterLevel.value = filterLevel.value.filter(x => x !== lv); },
    });
  });
  filterDifficulty.value.forEach((d) => {
    chips.push({
      id: `diff:${d}`,
      label: t(`table.difficulty.${d.toLowerCase()}`),
      remove: () => { filterDifficulty.value = filterDifficulty.value.filter(x => x !== d); },
    });
  });
  filterDjLevel.value.forEach((lv) => {
    chips.push({
      id: `dj:${lv}`,
      label: lv,
      remove: () => { filterDjLevel.value = filterDjLevel.value.filter(x => x !== lv); },
    });
  });
  filterClearType.value.forEach((ct) => {
    chips.push({
      id: `clear:${ct}`,
      label: ct,
      remove: () => { filterClearType.value = filterClearType.value.filter(x => x !== ct); },
    });
  });
  filterSource.value.forEach((src) => {
    chips.push({
      id: `source:${src}`,
      label: t(`table.source.${src}`),
      remove: () => { filterSource.value = filterSource.value.filter(x => x !== src); },
    });
  });
  if (hideZeroScore.value) {
    chips.push({
      id: 'hideZero',
      label: t('filter.hideZeroTag'),
      remove: () => { hideZeroScore.value = false; },
    });
  }
  return chips;
});

/** 【関数の役割】 全フィルタを一括解除する。検索ボックスと「0点非表示」トグルも初期状態に戻す。 */
const clearAllFilters = () => {
  searchQuery.value = '';
  filterLevel.value = [];
  filterDifficulty.value = [];
  filterDjLevel.value = [];
  filterClearType.value = [];
  filterSource.value = [];
  hideZeroScore.value = false;
};

/** 現在のページ番号（1 始まり）。 */
const currentPage = ref(1);
/** 1 ページあたりの表示件数。10/25/50/100 から選択可能。 */
const itemsPerPage = ref(50);

type SortKey = 'title' | 'clearType' | 'scoreRate' | 'informalRank' | 'difficultyLevel' | 'djLevel' | 'beatTierPoints' | 'songRank' | 'unofficialSongRank' | 'bpi' | null;
type SortOrder = 'asc' | 'desc';

/** 現在の並び替えキー。初期値は「非公式難易度（informalRank）」降順。 */
const sortKey = ref<SortKey>('informalRank');
/** 並び替え順。asc/desc。 */
const sortOrder = ref<SortOrder>('desc');

/**
 * クリアランプの強弱を数値化したテーブル。並び替え時に使う。
 * 大きいほど「上位ランプ」（FULLCOMBO > EX HARD > HARD > CLEAR > ...）。
 */
const clearTypeRankings: Record<string, number> = {
  'FULLCOMBO CLEAR': 7,
  'EX HARD CLEAR': 6,
  'HARD CLEAR': 5,
  'CLEAR': 4,
  'EASY CLEAR': 3,
  'ASSIST CLEAR': 2,
  'FAILED': 1,
  'NO PLAY': 0,
  '---': 0
};

/**
 * 【関数の役割】 単曲のレートと所属難易度から、必要スコアレート表に対応するランクを返す。
 * 未プレイ（scoreRate <= 0）または非公式ランク無しの曲は null。
 * Novice 1 にも届かないスコアは Beginner として表示する（null にはしない）。
 */
const getSongUnofficialRank = (record: ScoreRecord): RankInfo | null => {
  if (record.scoreRate <= 0 || !record.informalRank) return null;
  return getFolderRankInfoByRate(record.scoreRate, record.informalRank);
};

/**
 * 【関数の役割】 RankInfo を「Legend が最大、Beginner が最小」の数値に変換する。
 * FOLDER_RANK_DEFS の並び（先頭が Legend）を逆順序として使い、未該当は -1 を返す。
 */
const getRankOrderValue = (info: RankInfo | null): number => {
  if (!info) return -1;
  const idx = FOLDER_RANK_DEFS.findIndex(d => d.name === info.name && (d.tier ?? null) === (info.tier ?? null));
  return idx === -1 ? -1 : (FOLDER_RANK_DEFS.length - idx);
};

/**
 * 【computed】 BPI 計算に必要なパラメータ（avg/wr/coef/理論値）を `title|difficultyName` で引ける辞書。
 *
 * song_data.json（= songDataBodyRef）の ANOTHER(4)/LEGGENDARIA(10) 各譜面から構築する。
 * 1譜面に複数回ルックアップする都合上、行ごとの線形探索を避けるため一度だけ Map 化する。
 * BPI 列（showBpi）が立っているときだけ参照されるが、構築コストは小さいので常時用意する。
 */
const bpiParamDict = computed(() => {
  const dict = new Map<string, { avg?: number; wr?: number; coef?: number; max: number }>();
  const body = songDataBodyRef.value as any[];
  if (Array.isArray(body)) {
    for (const s of body) {
      const diffName = s.difficulty === '4' ? 'ANOTHER' : s.difficulty === '10' ? 'LEGGENDARIA' : null;
      if (!diffName) continue;
      dict.set(`${s.title}|${diffName}`, {
        avg: s.avg,
        wr: s.wr,
        coef: s.coef,
        max: s.notes ? s.notes * 2 : 0,
      });
    }
  }
  return dict;
});

/**
 * 【関数の役割】 1譜面レコードの BPI を返す。avg/wr を持たない譜面（多くの☆11以下など）は null。
 * 算出ロジックは {@link calcBpi}（本家 BPIManager2 準拠）に委譲する。
 */
const recordBpi = (record: ScoreRecord): number | null => {
  const p = bpiParamDict.value.get(`${record.title}|${record.difficultyName}`);
  if (!p) return null;
  return calcBpi(record.score, p.max || record.maxScore, p.avg, p.wr, p.coef);
};

/**
 * 【computed】 BEAT-TIER モード用の「☆11/☆12 の ANOTHER/LEGGENDARIA 全譜面」配列。
 *
 * 処理の流れ:
 *  手順1: 渡された props.scores をフラット化し、対象譜面だけ抽出（プレイ済み）。
 *  手順2: タイトル+難易度をキーにプレイ済みマップを構築。
 *  手順3: 難易度表から非公式ランクを辞書化（未プレイ曲でも informalRank を付けるため）。
 *  手順4: song_data.json の全譜面をループし、プレイ済みなら既存レコードを返し、未プレイなら
 *         score=0 のダミーレコードを生成して返す（テーブル上「未プレイ」として表示するため）。
 */
const allRecords = computed<ScoreRecord[]>(() => {
  // 手順1: ユーザーのプレイ済みスコアから ☆11/☆12 ANOTHER/LEGGENDARIA のみ抽出
  const playedRecords = flattenScores(props.scores).filter(r =>
    r.difficultyLevel &&
    r.difficultyLevel >= 11 &&
    ['ANOTHER', 'LEGGENDARIA'].includes(r.difficultyName)
  );

  // 手順2: タイトル+難易度をキーにする Map を組んで O(1) で引けるようにする
  const playedMap = new Map<string, ScoreRecord>();
  playedRecords.forEach(r => playedMap.set(`${r.title}|${r.difficultyName}`, r));

  // 手順3: 未プレイ曲にも非公式ランクを付けたいので、難易度表マスターから辞書化する
  //        曲名末尾 '[L]' は LEGGENDARIA 指定。それ以外は ANOTHER 扱い。
  const informalDict = new Map<string, string>();
  if (diffTableRanksRef.value && Array.isArray(diffTableRanksRef.value)) {
      diffTableRanksRef.value.forEach((r: any) => {
          r.songs.forEach((songTitle: string) => {
              if (songTitle.endsWith('[L]')) {
                  const baseTitle = songTitle.slice(0, -3);
                  informalDict.set(`${baseTitle}_LEGGENDARIA`, r.rank);
              } else {
                  informalDict.set(`${songTitle}_ANOTHER`, r.rank);
              }
          });
      });
  }

  // 手順4: song_data.json 側で定義されている全譜面を走査して、未プレイなら空レコードを生成する。
  //        difficulty "4" = ANOTHER、"10" = LEGGENDARIA に対応。
  const difMap: Record<string, string> = { "4": "ANOTHER", "10": "LEGGENDARIA" };
  const baseRecords: ScoreRecord[] = (songDataBodyRef.value as any[])
    .filter((s: any) => s.level >= 11 && (s.difficulty === "4" || s.difficulty === "10"))
    .map((s: any) => {
      const diffName = difMap[s.difficulty];
      const key = `${s.title}|${diffName}`;
      
      // プレイ済みならそのまま既存のレコードを採用。
      if (playedMap.has(key)) {
        return playedMap.get(key)!;
      }

      // 未プレイなら辞書から非公式ランクを引く（見つからない場合は undefined のまま）
      const informalKey = `${s.title}_${diffName}`;
      let informalRank = informalDict.get(informalKey);
      if (!informalRank && diffName === 'ANOTHER') {
          informalRank = informalDict.get(`${s.title}_ANOTHER`);
      }

      // 未プレイ譜面の空レコードを生成（score=0 / clearType='NO PLAY'）。
      // maxScore は notes * 2（IIDX の EX スコア理論値）で計算する。
      return {
        id: undefined,
        playStyle: 'SP',
        title: s.title,
        genre: s.genre,
        artist: s.artist,
        playCount: 0,
        difficultyName: diffName,
        difficultyLevel: s.level,
        score: 0,
        pgreat: 0,
        great: 0,
        missCount: 0,
        clearType: 'NO PLAY',
        djLevel: '---',
        maxScore: s.notes ? s.notes * 2 : 0,
        scoreRate: 0,
        informalRank: informalRank,
        beatTierPoints: 0,
        maxBeatTierPoints: getMaxPoints(informalRank),
        options: undefined,
        difficultyColor: diffName === 'LEGGENDARIA' ? 'text-purple-700 bg-purple-100 border border-purple-300' : 'text-red-700 bg-red-100 border border-red-300',
        lastPlayTime: ''
      } as ScoreRecord;
    });

  return baseRecords;
});

/** INFINITAS 取得スコアが 1 件でも存在するか。取得元フィルタの表示要否に使う。 */
const hasInfinitasScores = computed(() => allRecords.value.some(r => r.source === 'infinitas'));

const emit = defineEmits<{
  (e: 'reset'): void;
  (e: 'update:totalPoints', points: number): void;
  (e: 'view-user', payload: { id: number; displayName: string; iidxId: string }): void;
  (e: 'view-top-ranker', payload: { versionNum: number; versionName: string; prefectureFileNum: number; prefectureName: string }): void;
}>();

/**
 * 【関数の役割】 ランキング行のうち実在ユーザー行をクリックしたときの遷移ハンドラ。
 *  - userId が無い（通信エラー等）なら何もしない
 *  - privacyLevel が 0 以外（= 非公開 / フレンド限定）はクリック不可とする（誤タップで遷移させない）
 */
function handleSongUserRowClick(entry: SongRankingEntry) {
  if (!entry.userId) return;
  const priv = entry.privacyLevel ?? 1;
  if (priv !== 0) return;
  emit('view-user', { id: entry.userId, displayName: entry.displayName, iidxId: entry.iidxId ?? '' });
}

/**
 * 【関数の役割】 ランキング行のうち仮想 TOP ランカー行をクリックしたときの遷移ハンドラ。
 * 親コンポーネント（App.vue）へ view-top-ranker イベントを上げ、エリア情報を渡す。
 */
function handleSongTopRankerRowClick(entry: SongTopRankerEntry) {
  emit('view-top-ranker', {
    versionNum: entry.versionNum,
    versionName: entry.versionName,
    prefectureFileNum: entry.prefectureFileNum,
    prefectureName: entry.prefectureName,
  });
}

/**
 * 【computed】 BEAT-PT 総合値。全譜面から BEAT-PT 降順で並べ、上位 100 譜面を合計して返す。
 * これが BEAT-Tier 段位判定の元値になる。
 */
const totalBeatTierPoints = computed(() => {
    // 全譜面を BEAT-PT 降順でソートし、上位 100 譜面の合計を算出
    const sorted = [...allRecords.value].sort((a, b) => b.beatTierPoints - a.beatTierPoints);
    const top100 = sorted.slice(0, 100);
    return top100.reduce((acc, curr) => acc + curr.beatTierPoints, 0);
});

// 【watch】 BEAT-PT 総合値が変わるたびに親へ emit する。
// immediate: true でマウント直後の初期値も親に伝える。
watch(totalBeatTierPoints, (newVal) => {
    emit('update:totalPoints', newVal);
}, { immediate: true });

/**
 * 【computed】 BEAT-TIER の TOP100 譜面キー集合。ハイライト表示用に Set 化。
 * キーは `title|difficultyName` 形式。
 */
const top100Keys = computed(() => {
    const sorted = [...allRecords.value].sort((a, b) => b.beatTierPoints - a.beatTierPoints);
    return new Set(sorted.slice(0, 100).map(r => `${r.title}|${r.difficultyName}`));
});

/**
 * 【computed】 RATE-TIER 用の全譜面リスト。
 * BEAT-TIER と違いレベル制限なし（☆1〜☆12 すべて対象）、ANOTHER/LEGGENDARIA のプレイ済みのみ。
 */
const rateAllRecords = computed<ScoreRecord[]>(() =>
    flattenScores(props.scores).filter(r =>
        ['ANOTHER', 'LEGGENDARIA'].includes(r.difficultyName)
    )
);

/** 【computed】 RATE-TIER の TOP100 キー集合。RATE-PT 降順で上位 100 譜面を取り出す。 */
const rateTop100Keys = computed(() => {
    const sorted = [...rateAllRecords.value]
        .filter(r => r.scoreRate > 0)
        .sort((a, b) => calculateScoreRateTierPoints(b.scoreRate) - calculateScoreRateTierPoints(a.scoreRate));
    return new Set(sorted.slice(0, 100).map(r => `${r.title}|${r.difficultyName}`));
});

/**
 * 【computed】 パーフェクト（100% 達成）の曲が 100 譜面を超えたかどうか。
 * 超えた場合、個別 RATE-PT 512 の曲をより強調表示する（合計 51200 を超えるオーバーフロー状態の明示）。
 */
const hasPerfectRateOverflow = computed(() =>
    rateAllRecords.value.filter(r => r.scoreRate >= 100).length > 100
);

/**
 * 【computed】 TOP100 のボーダーライン（=100 位の BEAT-PT）。
 * 100 譜面未満しかプレイしていない場合は 0 を返す。
 */
const top100Threshold = computed(() => {
    const sorted = [...allRecords.value]
        .filter(r => r.beatTierPoints > 0)
        .sort((a, b) => b.beatTierPoints - a.beatTierPoints);
    return sorted.length >= 100 ? sorted[99].beatTierPoints : 0;
});

/**
 * 【computed】 各 TOP100 外の譜面について「あと何点伸ばせば TOP100 入りできるか」を算出した Map。
 * キー: `title|difficultyName`、値: 必要な素スコア増加量。
 *
 * 処理の流れ:
 *  手順1: そもそも TOP100 が埋まっていない場合は空 Map を返す。
 *  手順2: TOP100 内の譜面・無効譜面はスキップ。
 *  手順3: 理論値（100%）でも閾値に届かない譜面は表示対象外。
 *  手順4: 現在スコア〜最大スコアの範囲で二分探索し、閾値超えする最小スコアを発見。
 *  手順5: その差分（＝必要点数）を Map に格納。
 */
const top100ScoreNeededMap = computed(() => {
    const map = new Map<string, number>();
    if (top100Threshold.value === 0) return map;

    for (const record of allRecords.value) {
        const key = `${record.title}|${record.difficultyName}`;
        if (top100Keys.value.has(key)) continue; // 既に TOP100 入りしている譜面はスキップ
        if (!record.informalRank || record.maxScore <= 0) continue; // 計算不能なデータはスキップ

        const targetPt = top100Threshold.value;
        // 100% でも閾値に届かない譜面は（理論上 TOP100 入りできないので）表示しない
        if (calculatePoints(100, record.informalRank) <= targetPt) continue;

        // 二分探索: BEAT-PT > 閾値 となる最小スコアを探す
        let low = record.score;
        let high = record.maxScore;
        let bestScore = record.maxScore;

        while (low <= high) {
            const mid = Math.floor((low + high) / 2);
            const midPt = calculatePoints((mid / record.maxScore) * 100, record.informalRank);
            if (midPt > targetPt) {
                bestScore = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }

        const needed = bestScore - record.score;
        if (needed > 0) map.set(key, needed);
    }
    return map;
});

// --- タッチスクロール判定 ---
// モバイルで「行タップ = 詳細モーダル」と「縦スクロール」を区別するための状態。
let touchStartY = 0;
let touchStartX = 0;
let isTouchScrolling = false;

/** タッチ開始位置を記録し、スクロールフラグをリセットする。 */
const handleTouchStart = (e: TouchEvent) => {
  touchStartY = e.touches[0].clientY;
  touchStartX = e.touches[0].clientX;
  isTouchScrolling = false;
};

/** 指が 8px を超えて動いたらスクロール中と判定し、後続のクリックイベントを無視させる。 */
const handleTouchMove = (e: TouchEvent) => {
  const dy = Math.abs(e.touches[0].clientY - touchStartY);
  const dx = Math.abs(e.touches[0].clientX - touchStartX);
  if (dy > 8 || dx > 8) {
    isTouchScrolling = true;
  }
};

/** 行クリック: スクロール判定が立っていなければ詳細モーダルを開く。 */
const handleRowClick = (record: ScoreRecord) => {
  if (isTouchScrolling) return;
  openDetailModal(record);
};

// --- 詳細モーダルの状態 ---
/** 現在選択中の譜面レコード。null のときはモーダル非表示。 */
const selectedRecord = ref<ScoreRecord | null>(null);
/** 詳細モーダル内で表示中のタブ。 */
const modalTab = ref<'detail' | 'rate-tier' | 'rivals' | 'ranking' | 'history' | 'milestone'>('detail');

/** 目標 BEAT-PT スライダーの値（0〜 最大残 PT）。目標到達に必要なスコアを逆算して表示する。 */
const targetBeatPtSlider = ref(0);

// --- ライバル（フレンド）スコア取得用の状態と型 ---
interface RivalScore {
  id: number;
  displayName: string;
  iidxId: string;
  score: number | null;
  clearType: string;
  djLevel: string;
  pgreat: number;
  great: number;
  missCount: number | null;
  isSelf?: boolean;
  privacyLevel?: number;
}
/** フレンドの該当譜面スコア一覧。タブ初表示時に取得する。 */
const rivalScores = ref<RivalScore[]>([]);

// --- 譜面別ランキング（他ユーザー）用の状態と型 ---
// 非可視ユーザーは順位算出のためだけに返ってくるので、識別情報（userId / iidxId / displayName 等）
// や totalBeatPt は null でマスクされ得る。
interface SongRankingEntry {
  userId?: number | null;
  iidxId?: string | null;
  privacyLevel?: number | null;
  displayName: string | null;
  score: number;
  clearType?: string | null;
  djLevel?: string | null;
  totalBeatPt: number | null;
}
/** 譜面ランキング（公開/フレンド/自分）の生データ。 */
const songRankingList = ref<SongRankingEntry[]>([]);
/** 譜面ランキング取得中フラグ。 */
const isLoadingSongRanking = ref(false);
/** 「スコア公開ユーザーも表示」チェック。 */
const showPublicUsers = ref(false);
/** 「TOPランカー仮想ユーザーを表示」チェック。 */
const showVirtualUsers = ref(false);

interface SongTopRankerEntry {
  versionNum: number;
  versionName: string;
  prefectureFileNum: number;
  prefectureName: string;
  djName: string;
  score: number;
}
/** 譜面に紐づく仮想ユーザー（TOPランカー）の一覧。タブ表示時に取得する。 */
const songTopRankersList = ref<SongTopRankerEntry[]>([]);

// --- 自分が登録済みの仮想ライバル（フレンド扱いで常時表示する対象）---
const { fetchVirtualRivals } = useFriends();
/** 自分が登録済みの仮想ライバルを `${versionNum}:${prefectureFileNum}` 形式で保持したセット。 */
const registeredVirtualRivalKeys = ref<Set<string>>(new Set());
/** 仮想ライバル一覧を取得済みかどうか。ランキングタブ初表示時に一度だけフェッチする。 */
const virtualRivalsLoaded = ref(false);

/**
 * 【関数の役割】 自分が登録済みの仮想ライバルをフェッチし、セットに詰め直す。
 * ランキングタブ初表示時に一度だけ呼ばれる。失敗時は空のまま（UI は壊さない）。
 */
const loadRegisteredVirtualRivals = async () => {
  if (!isLoggedIn.value) return;
  try {
    const rivals = await fetchVirtualRivals();
    const set = new Set<string>();
    for (const r of rivals) set.add(`${r.versionNum}:${r.prefectureFileNum}`);
    registeredVirtualRivalKeys.value = set;
  } catch {
    // 握り潰し
  } finally {
    virtualRivalsLoaded.value = true;
  }
};

/**
 * 【関数の役割】 譜面ランキング（実ユーザー）と TOP ランカー（仮想ユーザー）を並行取得する。
 * Promise.all で 2 本の API を同時に呼び、どちらかが失敗しても UI を壊さない。
 */
const fetchSongRanking = async () => {
  if (!selectedRecord.value) return;
  isLoadingSongRanking.value = true;
  try {
    const params = new URLSearchParams({
      title: selectedRecord.value.title,
      difficultyName: selectedRecord.value.difficultyName
    });
    const [userRes, topRes] = await Promise.all([
      fetch(`${API_BASE}/api/scores/song-ranking?${params}`, { headers: authHeaders() }),
      fetch(`${API_BASE}/api/scores/song-top-rankers?${params}`),
    ]);
    if (userRes.ok) {
      songRankingList.value = await userRes.json();
    }
    if (topRes.ok) {
      songTopRankersList.value = await topRes.json();
    }
  } catch {
    // 握り潰し: ランキング表示は補助情報なので失敗してもモーダルは動かす
  } finally {
    isLoadingSongRanking.value = false;
  }
};

/** 詳細モーダルのランキングタブで描画する行の共通型。実ユーザー行と仮想ユーザー行を一本化する。 */
/**
 * 【関数の役割】 ランキング行のスコアから単曲ランク（必要スコアレート表対応）を求める。
 * maxScore / informalRank が無い譜面（Uncategorized 等）やスコア 0 は null。
 */
const songRankOfScore = (score: number | null | undefined): RankInfo | null => {
  const rec = selectedRecord.value;
  if (score == null || score <= 0 || !rec || rec.maxScore <= 0 || !rec.informalRank) return null;
  return getFolderRankInfoByRate(score / rec.maxScore * 100, rec.informalRank);
};

/**
 * 【computed の役割】 ランキングタブ先頭の「単曲ランク分布」チャートを組み立てる。
 *
 *  - 集計対象: songRankingList の全実ユーザー（非公開のため匿名化された行も含む）
 *    + フレンド（rivalScores、iidxId で重複排除）+ 自分。仮想 TOP ランカーは除外。
 *  - ProfileDashboard の単曲ティア分布に準じた 51 バー構成
 *    （各ブロック I〜V / Legend、I=淡 → V=濃、左=低位 → 右=高位）。
 *    Beginner 帯（レート 66.666% 以下）はスコアはあっても分布には算入しない。
 *  - 戻り値: { data: chart.js データ, total: 集計人数 } / 集計不能時は null。
 */
const songTierDist = computed(() => {
  const rec = selectedRecord.value;
  if (!rec || rec.maxScore <= 0 || !rec.informalRank) return null;
  const myIidx = user.value?.iidxId ?? '';

  // --- スコアレートの収集（実ユーザーのみ） ---
  const rates: number[] = [];
  const seen = new Set<string>();
  for (const entry of songRankingList.value) {
    if (entry.score == null || entry.score <= 0) continue;
    const iidxId = entry.iidxId ?? '';
    if (iidxId) {
      if (iidxId === myIidx || seen.has(iidxId)) continue;
      seen.add(iidxId);
    }
    rates.push(entry.score / rec.maxScore * 100);
  }
  for (const f of rivalScores.value) {
    if (!f.iidxId || f.iidxId === myIidx || seen.has(f.iidxId)) continue;
    if (f.score == null || f.score <= 0) continue;
    seen.add(f.iidxId);
    rates.push(f.score / rec.maxScore * 100);
  }
  if (rec.score > 0) rates.push(rec.scoreRate);
  if (rates.length === 0) return null;

  // --- 51 バー定義（Beginner を除く。配色は ProfileDashboard の単曲ティア分布と同じ） ---
  const blocks: { name: string; color: string; sub: boolean }[] = [
    { name: 'Novice',       color: '#475569', sub: true  },
    { name: 'Intermediate', color: '#2563eb', sub: true  },
    { name: 'Advanced',     color: '#0891b2', sub: true  },
    { name: 'Expert',       color: '#0d9488', sub: true  },
    { name: 'Veteran',      color: '#059669', sub: true  },
    { name: 'Commander',    color: '#a16207', sub: true  },
    { name: 'Elite',        color: '#ea580c', sub: true  },
    { name: 'Master',       color: '#dc2626', sub: true  },
    { name: 'Ancient',      color: '#4f46e5', sub: true  },
    { name: 'Mythic',       color: '#9333ea', sub: true  },
    { name: 'Legend',       color: '#f59e0b', sub: false },
  ];
  const shade = (hex: string, lightenPct: number): string => {
    const r = parseInt(hex.slice(1, 3), 16);
    const g = parseInt(hex.slice(3, 5), 16);
    const b = parseInt(hex.slice(5, 7), 16);
    const m = lightenPct / 100;
    return `rgb(${Math.round(r + (255 - r) * m)}, ${Math.round(g + (255 - g) * m)}, ${Math.round(b + (255 - b) * m)})`;
  };
  const romans = ['I', 'II', 'III', 'IV', 'V'];
  const bars: { label: string; key: string; color: string }[] = [];
  blocks.forEach(block => {
    if (!block.sub) {
      bars.push({ label: block.name, key: block.name, color: block.color });
    } else {
      for (let tier = 1; tier <= 5; tier++) {
        const lightenPct = (5 - tier) * 15;
        bars.push({
          label: `${block.name} ${romans[tier - 1]}`,
          key: `${block.name}|${tier}`,
          color: shade(block.color, lightenPct),
        });
      }
    }
  });
  const counts: Record<string, number> = Object.fromEntries(bars.map(b => [b.key, 0]));
  let included = 0;
  rates.forEach(rate => {
    const rank = getFolderRankInfoByRate(rate, rec.informalRank);
    if (rank.name === 'Beginner') return; // C帯未満（66.666%以下）は分布に算入しない
    const key = rank.tier ? `${rank.name}|${rank.tier}` : rank.name;
    if (counts[key] !== undefined) { counts[key]++; included++; }
  });
  if (included === 0) return null;
  return {
    total: included,
    data: {
      labels: bars.map(b => b.label),
      datasets: [{
        label: t('rankComparison.players'),
        data: bars.map(b => counts[b.key]),
        backgroundColor: bars.map(b => b.color),
        borderRadius: 1,
        categoryPercentage: 1.0,
        barPercentage: 1.0,
      }],
    },
  };
});

/** 単曲ランク分布チャートの描画オプション（X 軸は親ランク名のみ表示）。 */
const songTierDistOpts = computed(() => ({
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: { display: false },
    tooltip: {
      backgroundColor: isDarkMode.value ? 'rgba(15,23,42,0.9)' : 'rgba(255,255,255,0.95)',
      titleColor: isDarkMode.value ? '#f8fafc' : '#0f172a',
      bodyColor: isDarkMode.value ? '#cbd5e1' : '#334155',
      borderColor: isDarkMode.value ? '#334155' : '#e2e8f0', borderWidth: 1,
    },
  },
  scales: {
    x: {
      ticks: {
        color: isDarkMode.value ? '#94a3b8' : '#64748b',
        font: { size: 10 }, autoSkip: false, maxRotation: 0, minRotation: 0,
        callback(this: any, value: any) {
          const label = this.getLabelForValue(value);
          if (label === 'Legend') return label;
          const m = label.match(/^(\S+) III$/);
          return m ? m[1] : '';
        },
      },
      grid: { display: false },
    },
    y: {
      ticks: { color: isDarkMode.value ? '#94a3b8' : '#64748b', precision: 0 },
      grid: { color: isDarkMode.value ? '#334155' : '#f1f5f9' },
      beginAtZero: true,
    },
  },
}));

interface RankingRow {
  key: string;
  kind: 'user' | 'virtual';
  isSelf?: boolean;
  isFriend?: boolean;
  userId?: number | null;
  iidxId?: string;
  displayName: string;
  score: number | null;
  clearType?: string;
  djLevel?: string;
  privacyLevel?: number | null;
  totalBeatPt?: number;
  virtualEntry?: SongTopRankerEntry;
  virtualBadge?: 'allTimeGlobal' | 'globalAllTime' | 'allTimeArea' | 'versionTop' | 'top';
  rank: number | null;
}

/**
 * 【computed の役割】 ランキングタブで表示する行を組み立てる。
 *
 * 処理フロー:
 *  手順1: songRankingList（API 取得の実ユーザー）を iidxId で Map に投入。自分は除外。
 *  手順2: rivalScores（フレンド）を同じ Map にマージ。既存なら isFriend フラグだけ立てる。
 *  手順3: 自分（selfRow）を作成し、合算対象に加える。
 *  手順4: showVirtualUsers が true のとき、TOP ランカー仮想ユーザーを以下のルールで分類:
 *           - allTimeGlobal: 全国(prefNum=0)の歴代 TOP
 *           - globalAllTime: 県別の歴代 TOP が全国歴代 TOP と同スコア/同名なら昇格
 *           - allTimeArea:   県別の歴代 TOP（全国と一致しないもの）
 *           - versionTop:    バージョン別全国 TOP
 *           - top:           その他バージョン別県別 TOP
 *         重複（全国＝県, version 全国＝県）は一方だけ残す。
 *  手順5: スコアのある実ユーザー行に dense 1-indexed の順位を付与（同点は同順位）。
 *         仮想ユーザー（TOP ランカー）はプレイ実績ではないため順位対象から外す。
 *  手順6: 表示用にフィルタ: 自分 + 仮想 + 公開フレンド + (showPublicUsers かつ privacy=0)。
 *  手順7: スコア降順でソートして返す。スコア null は末尾。
 */
const rankingList = computed<RankingRow[]>(() => {
  if (!selectedRecord.value) return [];
  const rec = selectedRecord.value;
  const myIidx = user.value?.iidxId ?? '';

  // フレンドの iidxId セット。実ユーザー行へ isFriend フラグを立てるのに使う。
  const friendIidxSet = new Set(rivalScores.value.map(r => r.iidxId).filter(Boolean));

  // 手順1〜2: songRankingList + rivalScores を iidxId でマージ。自分は除外。
  // 非可視ユーザー（バックエンドで iidxId が NULL にマスクされた行）は順位算出のためだけに
  // 別配列で保持し、表示フィルタで自然に除外する。
  const usersByIidx = new Map<string, RankingRow>();
  const hiddenRows: RankingRow[] = [];
  let hiddenIdx = 0;
  for (const entry of songRankingList.value) {
    const iidxId = entry.iidxId ?? '';
    if (iidxId === myIidx && iidxId) continue;
    if (!iidxId) {
      // 非可視ユーザー: 識別情報がマスクされている。スコアのみ順位計算に寄与させる。
      hiddenRows.push({
        key: 'h_' + (hiddenIdx++),
        kind: 'user',
        displayName: '',
        score: entry.score ?? null,
        privacyLevel: entry.privacyLevel ?? 2,
        rank: null,
      });
      continue;
    }
    usersByIidx.set(iidxId, {
      key: 'u_' + iidxId,
      kind: 'user',
      userId: entry.userId ?? null,
      iidxId,
      displayName: entry.displayName,
      score: entry.score ?? null,
      clearType: entry.clearType,
      djLevel: entry.djLevel,
      privacyLevel: entry.privacyLevel ?? null,
      totalBeatPt: entry.totalBeatPt ?? 0,
      isFriend: friendIidxSet.has(iidxId),
      rank: null,
    });
  }
  for (const f of rivalScores.value) {
    if (!f.iidxId || f.iidxId === myIidx) continue;
    const existing = usersByIidx.get(f.iidxId);
    if (existing) {
      existing.isFriend = true;
    } else {
      usersByIidx.set(f.iidxId, {
        key: 'f_' + f.iidxId,
        kind: 'user',
        userId: f.id,
        iidxId: f.iidxId,
        displayName: f.displayName,
        score: f.score ?? null,
        clearType: f.clearType,
        djLevel: f.djLevel,
        privacyLevel: f.privacyLevel ?? null,
        totalBeatPt: 0,
        isFriend: true,
        rank: null,
      });
    }
  }

  // 手順3: 自分の行を合成（displayName は固定で「あなた」）。
  const selfRow: RankingRow = {
    key: 'self',
    kind: 'user',
    displayName: 'あなた',
    iidxId: myIidx,
    userId: null,
    score: rec.score > 0 ? rec.score : null,
    clearType: rec.clearType,
    djLevel: rec.djLevel,
    privacyLevel: null,
    totalBeatPt: totalBeatTierPoints.value,
    isSelf: true,
    rank: null,
  };

  // 手順4: 仮想ユーザー行を構築。各バッジの判定ロジックは以下。
  // 表示フィルタは後段で行う。登録済みの仮想ライバルはフレンド扱いで常時表示するため、
  // ここでは showVirtualUsers の値に関係なく全件作ってから絞り込む。
  const virtualRows: RankingRow[] = [];
  {
    // 県別の歴代 TOP を集める（versionNum === 0 が「歴代」の意味）。
    const allTimeByPref = new Map<number, { djName: string; score: number }>();
    for (const e of songTopRankersList.value) {
      if (e.versionNum === 0) allTimeByPref.set(e.prefectureFileNum, { djName: e.djName, score: e.score });
    }
    // 全国（prefectureFileNum === 0）の歴代 TOP。
    const globalAllTime = allTimeByPref.get(0);
    // 全国歴代 TOP と完全一致する県別歴代 TOP があるかを検出。
    // 両方表示すると重複するので、後段で全国歴代を間引く根拠にする。
    let prefectureMatchesGlobalAllTime = false;
    if (globalAllTime) {
      for (const [prefNum, at] of allTimeByPref) {
        if (prefNum !== 0 && at.djName === globalAllTime.djName && at.score === globalAllTime.score) {
          prefectureMatchesGlobalAllTime = true;
          break;
        }
      }
    }
    // バージョン別 全国 TOP を集める。
    const globalTopByVersion = new Map<number, { djName: string; score: number }>();
    for (const e of songTopRankersList.value) {
      if (e.versionNum !== 0 && e.prefectureFileNum === 0) {
        globalTopByVersion.set(e.versionNum, { djName: e.djName, score: e.score });
      }
    }
    // 「バージョン別 全国 TOP」と同じスコア/名前を持つ「バージョン別 県別 TOP」があるバージョンを検出。
    // 該当する場合、バージョン別 全国行は冗長なので後段で除外する。
    const versionHasPrefectureMatch = new Set<number>();
    for (const e of songTopRankersList.value) {
      if (e.versionNum === 0 || e.prefectureFileNum === 0) continue;
      const g = globalTopByVersion.get(e.versionNum);
      if (g && g.djName === e.djName && g.score === e.score) versionHasPrefectureMatch.add(e.versionNum);
    }
    // key 用のユニークインデックス。
    let idx = 0;
    // バージョンごとのエントリを走査し、バッジを決める。versionNum === 0（歴代行）はバージョン列の前段で既に処理済みなのでスキップ。
    for (const e of songTopRankersList.value) {
      if (e.versionNum === 0) continue;
      // このエントリが、県別歴代 TOP と同一（= 歴代記録）か判定。
      const at = allTimeByPref.get(e.prefectureFileNum);
      const isAllTime = at !== undefined && at.djName === e.djName && at.score === e.score;
      // 全国歴代と県別歴代が一致するなら、全国歴代行は冗長なので除外。
      if (isAllTime && e.prefectureFileNum === 0 && prefectureMatchesGlobalAllTime) continue;
      // バージョン全国 TOP と同バージョン県別 TOP が同スコアなら、全国行は除外。
      if (e.prefectureFileNum === 0 && versionHasPrefectureMatch.has(e.versionNum)) continue;
      // 県別歴代 TOP のうち、全国歴代 TOP と一致しているものを昇格扱いにする。
      const isGlobalAllTime = isAllTime && e.prefectureFileNum !== 0 && globalAllTime !== undefined
        && globalAllTime.djName === e.djName && globalAllTime.score === e.score;
      // バージョン別全国 TOP と一致する県別エントリか？
      let isVersionTop = false;
      if (e.prefectureFileNum !== 0) {
        const g = globalTopByVersion.get(e.versionNum);
        if (g && g.djName === e.djName && g.score === e.score) isVersionTop = true;
      }
      // バッジ優先度: allTimeGlobal > globalAllTime > allTimeArea > versionTop > top。
      let badge: RankingRow['virtualBadge'] = 'top';
      if (isAllTime && e.prefectureFileNum === 0) badge = 'allTimeGlobal';
      else if (isGlobalAllTime) badge = 'globalAllTime';
      else if (isAllTime && e.prefectureFileNum !== 0) badge = 'allTimeArea';
      else if (isVersionTop) badge = 'versionTop';
      const isRegisteredRival = registeredVirtualRivalKeys.value.has(`${e.versionNum}:${e.prefectureFileNum}`);
      virtualRows.push({
        key: 'v_' + (idx++) + '_' + e.versionNum + '_' + e.prefectureFileNum,
        kind: 'virtual',
        displayName: e.versionName + ' ' + e.prefectureName,
        score: e.score,
        virtualEntry: e,
        virtualBadge: badge,
        isFriend: isRegisteredRival,
        rank: null,
      });
    }
  }

  // すべての行を 1 本の配列に集約（自分 + 実ユーザー + 非公開ユーザー + 仮想）。
  // hiddenRows は順位計算のためだけに含める（表示フィルタで自然に除外される）。
  const all: RankingRow[] = [selfRow, ...usersByIidx.values(), ...hiddenRows, ...virtualRows];

  // 手順5: スコア保有者に dense 1-indexed の順位を付与。非表示ユーザーも順位計算には含める
  //        （= 表示上「4位」が欠番に見えても、裏で隠れた 3 位が存在し得る）。
  //        仮想ユーザー (TOP ランカー) は実プレイヤーではないため順位計算から除外し、
  //        rank: null のままにしておく（テンプレート側で「-」表示になる）。
  const scored = all.filter(r => r.score != null && r.kind === 'user').slice().sort((a, b) => (b.score as number) - (a.score as number));
  let prevScore: number | null = null;
  let prevRank = 0;
  scored.forEach((r, i) => {
    const s = r.score as number;
    if (s !== prevScore) {
      prevRank = i + 1;
      prevScore = s;
    }
    r.rank = prevRank;
  });

  // 手順6: 表示フィルタ。自分は常に表示、仮想ユーザーは showVirtualUsers チェック時または
  //        登録済み仮想ライバル（フレンド扱い）のみ表示、実フレンドはプライバシー 2（完全非公開）以外、
  //        公開ユーザーは showPublicUsers チェック時のみ表示。

  const display = all.filter(r => {
    if (r.isSelf) return true;
    if (r.kind === 'virtual') return showVirtualUsers.value || !!r.isFriend;
    if (r.isFriend && (r.privacyLevel ?? 1) !== 2) return true;
    if (showPublicUsers.value && (r.privacyLevel ?? 1) === 0) return true;
    return false;
  });

  // 手順7: スコア降順でソート。score === null の行は末尾に寄せる。
  display.sort((a, b) => {
    if (a.score == null && b.score == null) return 0;
    if (a.score == null) return 1;
    if (b.score == null) return -1;
    return (b.score as number) - (a.score as number);
  });

  return display;
});

/** ランキング一覧の初期描画件数（重い RankIcon を大量描画してモバイルでクラッシュするのを防ぐ）。 */
const RIVALS_RENDER_CHUNK = 100;
/** 現在の描画上限。「残りを表示」で増やす。曲を切り替えると既定値へ戻す。 */
const rivalsRenderLimit = ref(RIVALS_RENDER_CHUNK);

/**
 * 実際にテンプレートへ渡す行。rankingList を rivalsRenderLimit 件までに絞る。
 * 自分の行が上限より後ろにある場合でも必ず含める（自分の順位を常に確認できるように）。
 */
const displayedRankingRows = computed<RankingRow[]>(() => {
  const all = rankingList.value;
  if (all.length <= rivalsRenderLimit.value) return all;
  const head = all.slice(0, rivalsRenderLimit.value);
  const self = all.find(r => r.isSelf);
  if (self && !head.includes(self)) head.push(self);
  return head;
});

/** まだ描画していない（隠れている）行数。「残り N 件を表示」に使う。 */
const hiddenRankingCount = computed(() => Math.max(0, rankingList.value.length - rivalsRenderLimit.value));

/** 描画上限をさらに増やす（段階的に。一度に全件出すと再びクラッシュし得るためチャンク単位）。 */
const showMoreRanking = () => { rivalsRenderLimit.value += 200; };

/** フレンドスコア取得中フラグ。 */
const isLoadingRivals = ref(false);

/**
 * 【関数の役割】 選択中譜面に対するフレンドスコアを API から取得。
 * 結果は rivalScores に格納され、rankingList computed にも反映される。
 */
const fetchRivalScores = async () => {
  if (!selectedRecord.value) return;
  isLoadingRivals.value = true;
  try {
    const params = new URLSearchParams({
      title: selectedRecord.value.title,
      difficultyName: selectedRecord.value.difficultyName
    });
    const res = await fetch(`${API_BASE}/api/friends/scores?${params}`, {
      headers: authHeaders()
    });
    if (res.ok) {
      rivalScores.value = await res.json();
    }
  } catch {
    // 握り潰し: ライバルタブは補助情報のためモーダルは維持
  } finally {
    isLoadingRivals.value = false;
  }
};

/**
 * 【関数の役割】 ライバルタブクリック時のハンドラ。
 * タブ切替 + 初回のみ rivalScores / songRanking を取得する（キャッシュ効果）。
 */
const handleRivalTabClick = () => {
  modalTab.value = 'rivals';
  if (rivalScores.value.length === 0 && !isLoadingRivals.value) {
    fetchRivalScores();
  }
  // ランキング(実ユーザー)または TOPランカー(仮想ユーザー)のどちらかが未取得なら取得する。
  // バックエンドのコールドスタート等で song-top-rankers が一時的に空配列を返したケースでは、
  // 実ユーザー側だけ埋まって「&&」だと二度と再取得されず仮想ユーザーが永続的に欠落するため、
  // OR で判定して再クリック時に回復できるようにする。
  if ((songRankingList.value.length === 0 || songTopRankersList.value.length === 0) && !isLoadingSongRanking.value) {
    fetchSongRanking();
  }
  if (!virtualRivalsLoaded.value) {
    loadRegisteredVirtualRivals();
  }
};

// --- 譜面単位の成長履歴 ---
/** スコア変化を時系列で表示する際の 1 レコード。 */
interface SongHistoryEntry {
  uploadedAt: string;
  score: number | null;
  beatPt: number | null;
}
/** ヒストリータブで表示する履歴データ。タブ初表示時に取得。 */
const songHistory = ref<SongHistoryEntry[]>([]);
/** 履歴読込中フラグ。 */
const isLoadingHistory = ref(false);

/**
 * 【関数の役割】 選択中譜面のスコア更新履歴（uploadedAt / score / beatPt）を取得。
 * グラフ/表の元データになる。
 */
const fetchSongHistory = async () => {
  if (!selectedRecord.value) return;
  isLoadingHistory.value = true;
  try {
    const params = new URLSearchParams({
      title: selectedRecord.value.title,
      difficultyName: selectedRecord.value.difficultyName
    });
    const res = await fetch(`${API_BASE}/api/scores/song-history?${params}`, { headers: authHeaders() });
    if (res.ok) {
      songHistory.value = await res.json();
    }
  } catch {
    // 握り潰し: 履歴は補助情報
  } finally {
    isLoadingHistory.value = false;
  }
};

/**
 * 【関数の役割】 ヒストリータブクリック時のハンドラ。
 * 初回のみ songHistory をフェッチ（以降はキャッシュを再利用）。
 */
const handleHistoryTabClick = () => {
  modalTab.value = 'history';
  if (songHistory.value.length === 0 && !isLoadingHistory.value) {
    fetchSongHistory();
  }
};

// --- 大台（マイルストーン）タブ ---
/** 譜面のユーザー毎ベストスコア（匿名・降順）。タブ初表示時に取得。 */
const milestoneScores = ref<number[]>([]);
/** 集計対象プレイヤー数。 */
const milestonePlayerCount = ref(0);
/**
 * 取得済みフラグ。0 人の譜面では length===0 判定だとタブクリック毎に
 * 再フェッチしてしまうため、専用フラグで初回取得を管理する。
 */
const milestonesLoaded = ref(false);
/** 大台データ読込中フラグ。 */
const isLoadingMilestones = ref(false);

/** 大台タブを出す条件: A/L の Lv11-12 かつ maxScore が判明している譜面のみ。 */
const showMilestoneTab = computed(() => {
  const rec = selectedRecord.value;
  return !!rec
    && ['ANOTHER', 'LEGGENDARIA'].includes(rec.difficultyName)
    && (rec.difficultyLevel === 11 || rec.difficultyLevel === 12)
    && rec.maxScore > 0;
});

/**
 * 大台タブの表示行（降順・高い方が上）。
 * 各行に達成人数 / 達成率 / 自分の到達状況（達成済み・次目標・あと何点）を集約する。
 */
const milestoneRows = computed(() => {
  const rec = selectedRecord.value;
  if (!rec || rec.maxScore <= 0) return [];
  const lines = computeMilestoneLines(rec.maxScore).slice().reverse(); // 高い順
  const total = milestonePlayerCount.value;
  const own = rec.score;
  // 自分の「次の目標ライン」= 未達ラインのうち最小のもの（未プレイ時は対象外）。
  const nextLine = own > 0 ? [...lines].reverse().find(l => own < l) ?? null : null;
  return lines.map(line => {
    const count = milestoneScores.value.filter(s => s >= line).length;
    return {
      line,
      lineRate: (line / rec.maxScore) * 100,           // ラインのスコアレート%
      count,                                            // 達成人数
      rate: total > 0 ? (count / total) * 100 : 0,      // 達成率%
      achievedBySelf: own > 0 && own >= line,
      isNextTarget: nextLine === line,
      toGo: line - own,
    };
  });
});

/**
 * 大台タブに併記する AAA / MAX- 達成者の集計。
 * 閾値は IIDX の定義（AAA = スコアレート 8/9 ≈ 88.89%、MAX- = 17/18 ≈ 94.44%）に従い、
 * 整数比較でバックエンドの findSongAaaCounts / findSongMaxMinusCounts と一致させる。
 *  - AAA:  score × 9  >= maxScore × 8   （notes × 16 と等価。maxScore = notes × 2）
 *  - MAX-: score × 18 >= maxScore × 17  （notes × 17 と等価。分数回避のため両辺 2 倍）
 */
const milestoneAaaMax = computed(() => {
  const rec = selectedRecord.value;
  const empty = { aaaCount: 0, aaaRate: 0, maxMinusCount: 0, maxMinusRate: 0, achievedAaa: false, achievedMaxMinus: false };
  if (!rec || rec.maxScore <= 0) return empty;
  const max = rec.maxScore;
  const total = milestonePlayerCount.value;
  const own = rec.score;
  const aaaCount = milestoneScores.value.filter(s => s * 9 >= max * 8).length;
  const maxMinusCount = milestoneScores.value.filter(s => s * 18 >= max * 17).length;
  return {
    aaaCount,
    aaaRate: total > 0 ? (aaaCount / total) * 100 : 0,
    maxMinusCount,
    maxMinusRate: total > 0 ? (maxMinusCount / total) * 100 : 0,
    achievedAaa: own > 0 && own * 9 >= max * 8,
    achievedMaxMinus: own > 0 && own * 18 >= max * 17,
  };
});

/**
 * 【関数の役割】 選択中譜面の全ユーザーベストスコア（匿名・降順）を取得。
 * 大台ラインごとの達成人数計算の元データになる。公開 API なので authHeaders は不要。
 */
const fetchMilestoneScores = async () => {
  if (!selectedRecord.value) return;
  isLoadingMilestones.value = true;
  try {
    const params = new URLSearchParams({
      title: selectedRecord.value.title,
      difficultyName: selectedRecord.value.difficultyName
    });
    const res = await fetch(`${API_BASE}/api/scores/song-milestone-scores?${params}`);
    if (res.ok) {
      const data = await res.json();
      milestoneScores.value = data.scores ?? [];
      milestonePlayerCount.value = data.playerCount ?? 0;
      milestonesLoaded.value = true;
    }
  } catch {
    // 握り潰し: 大台は補助情報
  } finally {
    isLoadingMilestones.value = false;
  }
};

/**
 * 【関数の役割】 大台タブクリック時のハンドラ。
 * 初回のみ milestoneScores をフェッチ（milestonesLoaded で 0 人譜面の再取得も防ぐ）。
 */
const handleMilestoneTabClick = () => {
  modalTab.value = 'milestone';
  if (!milestonesLoaded.value && !isLoadingMilestones.value) {
    fetchMilestoneScores();
  }
};

/**
 * 【関数の役割】 バックエンドの LocalDateTime 文字列（タイムゾーンなし）を JST 扱いで整形する。
 * 既にタイムゾーンが付いていればそのまま使用、無ければ `+09:00` を付与して Date 化する。
 */
const formatHistoryDate = (dateStr: string) => {
  // バックエンドはタイムゾーン情報を持たない LocalDateTime を返す。JST として解釈する。
  const jstStr = /[Z+\-]\d{2}:?\d{2}$/.test(dateStr) ? dateStr : dateStr + '+09:00';
  return new Date(jstStr).toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
};

/**
 * 【watch の役割】 選択中譜面が変わるたびに、モーダル内タブごとのキャッシュをリセットし、
 * 投票データを再取得する。ここで rivalScores / songRanking / history を空にしておくことで、
 * 次回タブクリック時に fetch が再度走るよう仕向ける。
 */
watch(() => selectedRecord.value ? `${selectedRecord.value.title}|${selectedRecord.value.difficultyName}` : null, () => {
  targetBeatPtSlider.value = 0;
  rivalScores.value = [];
  songRankingList.value = [];
  songTopRankersList.value = [];
  songHistory.value = [];
  milestoneScores.value = [];
  milestonePlayerCount.value = 0;
  milestonesLoaded.value = false;
  rivalsRenderLimit.value = RIVALS_RENDER_CHUNK;
  // 新しい譜面の投票データを即時取得（タブ切替に依らずメインタブでも表示するため）。
  if (selectedRecord.value) {
    fetchVotes(selectedRecord.value.title, selectedRecord.value.difficultyName);
  }
});

// --- オプション投票システム ---
// 譜面ごとに「どのオプション（正規/MIRROR/RANDOM/R-RAN/S-RAN）で遊んでいるか」を投票・集計する仕組み。
/**
 * バックエンドから受け取る投票集計データの形。
 *
 * 複数選択対応:
 *  - {@code myVotes} は配列。1 ユーザーが同一譜面に複数オプションを投票できる。
 *  - {@code totalVotes} は **ユニークユーザー数**（複数選択でも 1 と数える）。
 *    バーチャートの分母として使う。複数選択なので％合計は 100% を超え得る。
 */
interface VoteDataType {
  counts: Record<string, number>;
  totalVotes: number;
  myVotes: string[];
}

/** 現在表示中譜面の投票データ。譜面切替時に fetch しなおす。 */
const voteData = ref<VoteDataType>({
  counts: { REGULAR: 0, MIRROR: 0, RANDOM: 0, 'R-RANDOM': 0, 'S-RANDOM': 0 },
  totalVotes: 0,
  myVotes: []
});
/** 投票 POST/DELETE 中の二重送信防止フラグ。 */
const isVoting = ref(false);

/** 投票ボタンの並び + 色テーマ定義。テンプレ側で v-for して描画する。 */
const optionTypes = [
  { value: 'REGULAR', label: '正規', icon: '▶', activeBg: 'bg-blue-50 dark:bg-blue-900/30', activeText: 'text-blue-700 dark:text-blue-400', activeBorder: 'border-blue-300 dark:border-blue-700', barColor: 'bg-blue-500', labelColor: 'text-blue-600 dark:text-blue-400' },
  { value: 'MIRROR', label: 'MIRROR', icon: '◀', activeBg: 'bg-purple-50 dark:bg-purple-900/30', activeText: 'text-purple-700 dark:text-purple-400', activeBorder: 'border-purple-300 dark:border-purple-700', barColor: 'bg-purple-500', labelColor: 'text-purple-600 dark:text-purple-400' },
  { value: 'RANDOM', label: 'RANDOM', icon: '🎲', activeBg: 'bg-emerald-50 dark:bg-emerald-900/30', activeText: 'text-emerald-700 dark:text-emerald-400', activeBorder: 'border-emerald-300 dark:border-emerald-700', barColor: 'bg-emerald-500', labelColor: 'text-emerald-600 dark:text-emerald-400' },
  { value: 'R-RANDOM', label: 'R-RAN', icon: '🔀', activeBg: 'bg-amber-50 dark:bg-amber-900/30', activeText: 'text-amber-700 dark:text-amber-400', activeBorder: 'border-amber-300 dark:border-amber-700', barColor: 'bg-amber-500', labelColor: 'text-amber-600 dark:text-amber-400' },
  { value: 'S-RANDOM', label: 'S-RAN', icon: '🎰', activeBg: 'bg-rose-50 dark:bg-rose-900/30', activeText: 'text-rose-700 dark:text-rose-400', activeBorder: 'border-rose-300 dark:border-rose-700', barColor: 'bg-rose-500', labelColor: 'text-rose-600 dark:text-rose-400' },
];

/**
 * 【関数の役割】 指定オプションの投票数を全体のパーセンテージで返す。
 * プログレスバー幅の算出に使う。0 票の場合は 0 を返す（ゼロ除算回避）。
 */
const getVotePercent = (optionValue: string): number => {
  if (voteData.value.totalVotes === 0) return 0;
  return ((voteData.value.counts[optionValue] || 0) / voteData.value.totalVotes) * 100;
};

/**
 * 【関数の役割】 指定譜面の投票データを取得する。ログイン中なら myVote も含まれる。
 * 失敗しても黙殺（UI を壊さない）。
 */
const fetchVotes = async (title: string, difficultyName: string) => {
  try {
    const params = new URLSearchParams({ title, difficultyName });
    const res = await fetch(`${API_BASE}/api/votes?${params}`, {
      headers: authHeaders(),
    });
    if (res.ok) {
      const data = await res.json();
      voteData.value = data;
    }
  } catch {
    // 握り潰し
  }
};

/**
 * 【関数の役割】 個別オプションの toggle を行う（複数選択対応）。
 *   - 自分が既にそのオプションに投票していた場合 → そのオプションだけを DELETE
 *   - それ以外                                       → POST で追加（他のオプションは温存）
 * 最後に fetchVotes で集計を最新化する。
 */
const castVote = async (optionType: string) => {
  if (!selectedRecord.value) return;
  isVoting.value = true;
  try {
    const alreadyVoted = voteData.value.myVotes.includes(optionType);
    if (alreadyVoted) {
      // そのオプションだけを取り消す。他の投票には影響しない。
      const params = new URLSearchParams({
        title: selectedRecord.value.title,
        difficultyName: selectedRecord.value.difficultyName,
        optionType
      });
      await fetch(`${API_BASE}/api/votes?${params}`, {
        method: 'DELETE',
        headers: authHeaders(),
      });
    } else {
      await fetch(`${API_BASE}/api/votes`, {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: selectedRecord.value.title,
          difficultyName: selectedRecord.value.difficultyName,
          optionType
        })
      });
    }
    // 送信後、自分の票を含めて最新の集計を再取得。
    await fetchVotes(selectedRecord.value.title, selectedRecord.value.difficultyName);
  } catch {
    // 握り潰し
  } finally {
    isVoting.value = false;
  }
};

/**
 * 【computed の役割】 targetBeatPtSlider（目標 BEAT-PT 増分）に対し、
 * 「現スコアから何点上げれば目標 PT に到達するか」を二分探索で逆算する。
 *
 * 処理フロー:
 *  手順1: 現在 PT + スライダー値 = 目標 PT。
 *  手順2: 探索範囲を [現スコア, maxScore] に取り、BEAT-PT が目標以上になる最小スコアを探す。
 *  手順3: (必要スコア - 現スコア) を返す。既に到達済みなら 0。
 */
const targetScoreNeeded = computed(() => {
  if (!selectedRecord.value || selectedRecord.value.maxScore <= 0 || selectedRecord.value.maxBeatTierPoints <= 0) return 0;
  
  const currentPt = selectedRecord.value.beatTierPoints;
  const targetPt = currentPt + targetBeatPtSlider.value;
  
  if (targetPt <= currentPt) return 0;

  let low = selectedRecord.value.score;
  let high = selectedRecord.value.maxScore;
  let bestScore = selectedRecord.value.maxScore;
  
  const informalRank = selectedRecord.value.informalRank || (selectedRecord.value.difficultyLevel?.toFixed(1) ?? '12.0');

  while (low <= high) {
    let mid = Math.floor((low + high) / 2);
    let midRate = (mid / selectedRecord.value.maxScore) * 100;
    let midPt = calculatePoints(midRate, informalRank);
    
    if (midPt >= targetPt) {
      bestScore = mid;
      high = mid - 1; 
    } else {
      low = mid + 1;
    }
  }

  return Math.max(0, bestScore - selectedRecord.value.score);
});

/**
 * 【関数の役割】 行クリック等で呼ばれ、詳細モーダルを開く。body のスクロールを固定する（背景が動かないように）。
 */
const openDetailModal = (record: ScoreRecord) => {
  selectedRecord.value = record;
  modalTab.value = 'detail';
  document.body.style.overflow = 'hidden';
};

/**
 * 【関数の役割】 メモ編集を保存する。API 成功時はモーダル内の表示も即時反映。
 * 失敗時はアラートで通知。保存中フラグで二重送信を防ぐ。
 */

/**
 * 【関数の役割】 詳細モーダルを閉じる。
 * selectedRecord を null にすれば v-if でモーダルが消える。body のスクロール固定も解除。
 */
const closeDetailModal = () => {
  selectedRecord.value = null;
  document.body.style.overflow = '';
};

/** 親から渡される scores が変わった（アップロード後など）ら、クリアタイプ集計のために再取得。 */
watch(() => props.scores, () => { fetchSongRanks(); }, { deep: false });

/** ログイン状態が後から確定する場合があるので、isLoggedIn が true に遷移した瞬間にも取得。 */
watch(isLoggedIn, (val) => { if (val) fetchSongRanks(); });

/** フィルタ/ソート/件数のどれかが変わったらページ番号を 1 に戻す（UX 改善）。 */
watch(
  [searchQuery, filterDifficulty, filterLevel, filterDjLevel, filterClearType, filterSource, hideZeroScore, viewMode, sortKey, sortOrder, itemsPerPage],
  () => {
    currentPage.value = 1;
  },
  { deep: true }
);

/**
 * 【関数の役割】 ドロップダウン（フィルタのチェックボックス群）外クリックで閉じるためのハンドラ。
 * 対象が .relative ラッパーの内側かどうかで判定している。
 */
const handleClickOutside = (event: MouseEvent) => {
  if (openDropdown.value && !(event.target as Element).closest('.relative')) {
    openDropdown.value = null;
  }
};

/** マウント時に外クリック監視を登録し、songRanks（クリアタイプ別集計）を取得。 */
onMounted(() => {
  window.addEventListener('click', handleClickOutside);
  fetchSongRanks();
});

/** アンマウント時にイベントリスナ解除 + 万一残っている body のスクロール固定を解除。 */
onUnmounted(() => {
  window.removeEventListener('click', handleClickOutside);
  document.body.style.overflow = '';
});

/**
 * 【関数の役割】 ソート列ヘッダクリック時の処理。
 *   - 同じ列を再クリック: 昇順/降順をトグル
 *   - 別の列をクリック:   列を切替え、デフォルトの向き（スコア系は desc / ランクは asc）を設定
 */
const toggleSort = (key: SortKey) => {
  if (sortKey.value === key) {
    // 既に選択中の列なら asc/desc を反転。
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc';
  } else {
    sortKey.value = key;
    // 列ごとの既定ソート向き（スコア/PT/クリアタイプ/段階は降順、ランキングは昇順）。
    if (key === 'scoreRate' || key === 'informalRank' || key === 'beatTierPoints' || key === 'clearType' || key === 'djLevel' || key === 'unofficialSongRank' || key === 'bpi') {
        sortOrder.value = 'desc';
    } else if (key === 'songRank') {
        sortOrder.value = 'asc';
    } else {
        sortOrder.value = 'asc';
    }
  }
};

/**
 * 【computed の役割】 全レコードに対し、絞り込み + 検索 + ソートを適用した最終リストを返す。
 *
 * 処理フロー:
 *  手順1: モード（通常 / rate）に応じたベースリストを複製。
 *  手順2: hideZeroScore / difficulty / level / djLevel / clearType のフィルタを順次適用。
 *  手順3: 検索ワードで title / artist / genre / clearType の部分一致フィルタ。
 *  手順4: sortKey ごとに専用のソート比較関数を適用。
 *         - informalRank: 末尾の数値（例 "12.5"）を抽出して比較、次点で difficultyLevel → title。
 *         - beatTierPoints: rate モードでは scoreRateTierPoints を再計算して比較。
 *         - clearType:   clearTypeRankings で定めた順位テーブルで比較。
 *         - djLevel:     AAA→F→--- の順位マップで比較。
 *         - songRank:    未知は 999999 扱いで末尾送り。
 */
const filteredScores = computed(() => {
  let result = viewMode.value === 'rate' ? [...rateAllRecords.value] : [...allRecords.value];

  if (hideZeroScore.value) {
    result = result.filter(r => r.score > 0);
  }

  if (filterDifficulty.value.length > 0) {
    result = result.filter(r => filterDifficulty.value.includes(r.difficultyName));
  }

  if (filterLevel.value.length > 0) {
    result = result.filter(r => filterLevel.value.includes(r.difficultyLevel?.toString() || ''));
  }

  if (filterDjLevel.value.length > 0) {
    result = result.filter(r => filterDjLevel.value.includes(r.djLevel));
  }

  if (filterClearType.value.length > 0) {
    result = result.filter(r => filterClearType.value.includes(r.clearType));
  }

  if (filterSource.value.length > 0) {
    result = result.filter(r => r.source != null && filterSource.value.includes(r.source));
  }

  const query = searchQuery.value.toLowerCase().trim();
  if (query) {
    result = result.filter(record => 
      record.title.toLowerCase().includes(query) ||
      record.artist.toLowerCase().includes(query) ||
      record.genre.toLowerCase().includes(query) ||
      record.clearType.toLowerCase().includes(query)
    );
  }

  // Sorting
  if (sortKey.value === 'informalRank') {
    const getNumericRank = (rank: string | undefined): number => {
      if (!rank) return -1;
      const match = rank.match(/(\d+\.\d+)/);
      return match ? parseFloat(match[1]) : -1;
    };

    result.sort((a, b) => {
        const valA = getNumericRank(a.informalRank);
        const valB = getNumericRank(b.informalRank);
        
        if (valA !== valB) {
            return sortOrder.value === 'asc' ? valA - valB : valB - valA;
        }

        // Secondary: difficultyLevel
        const levelA = a.difficultyLevel || 0;
        const levelB = b.difficultyLevel || 0;
        if (levelA !== levelB) {
            return sortOrder.value === 'asc' ? levelA - levelB : levelB - levelA;
        }
        
        return a.title.localeCompare(b.title);
    });
  } else if (sortKey.value === 'difficultyLevel') {
    result.sort((a, b) => {
        const levelA = a.difficultyLevel || 0;
        const levelB = b.difficultyLevel || 0;
        if (levelA !== levelB) return sortOrder.value === 'asc' ? levelA - levelB : levelB - levelA;
        return a.title.localeCompare(b.title);
    });
  } else if (sortKey.value === 'title') {
    result.sort((a, b) => {
      const cmp = a.title.localeCompare(b.title);
      return sortOrder.value === 'asc' ? cmp : -cmp;
    });
  } else if (sortKey.value === 'beatTierPoints') {
    if (viewMode.value === 'rate') {
      result.sort((a, b) => {
        const valA = calculateScoreRateTierPoints(a.scoreRate);
        const valB = calculateScoreRateTierPoints(b.scoreRate);
        return sortOrder.value === 'asc' ? valA - valB : valB - valA;
      });
    } else {
      result.sort((a, b) => {
        const valA = a.beatTierPoints || 0;
        const valB = b.beatTierPoints || 0;
        return sortOrder.value === 'asc' ? valA - valB : valB - valA;
      });
    }
  } else if (sortKey.value === 'clearType') {
    result.sort((a, b) => {
      const rankA = clearTypeRankings[a.clearType] || 0;
      const rankB = clearTypeRankings[b.clearType] || 0;
      const diff = rankA - rankB;
      if (diff !== 0) {
          return sortOrder.value === 'asc' ? diff : -diff;
      }
      return 0;
    });
  } else if (sortKey.value === 'scoreRate') {
    result.sort((a, b) => {
      const rateA = a.scoreRate >= 0 ? a.scoreRate : -2;
      const rateB = b.scoreRate >= 0 ? b.scoreRate : -2;
      const diff = rateA - rateB;
      if (diff !== 0) {
          return sortOrder.value === 'asc' ? diff : -diff;
      }
      return 0;
    });
  } else if (sortKey.value === 'djLevel') {
    result.sort((a, b) => {
      const levelMap: Record<string, number> = { 'AAA': 8, 'AA': 7, 'A': 6, 'B': 5, 'C': 4, 'D': 3, 'E': 2, 'F': 1, '---': 0 };
      const valA = levelMap[a.djLevel] || 0;
      const valB = levelMap[b.djLevel] || 0;
      return sortOrder.value === 'asc' ? valA - valB : valB - valA;
    });
  } else if (sortKey.value === 'songRank') {
    result.sort((a, b) => {
      const rankA = songRankMap.value.get(`${a.title}|${a.difficultyName}`)?.rank ?? 999999;
      const rankB = songRankMap.value.get(`${b.title}|${b.difficultyName}`)?.rank ?? 999999;
      return sortOrder.value === 'asc' ? rankA - rankB : rankB - rankA;
    });
  } else if (sortKey.value === 'unofficialSongRank') {
    result.sort((a, b) => {
      const valA = getRankOrderValue(getSongUnofficialRank(a));
      const valB = getRankOrderValue(getSongUnofficialRank(b));
      if (valA !== valB) return sortOrder.value === 'asc' ? valA - valB : valB - valA;
      // 同一ランク内ではレート降順で安定させる
      const rateA = a.scoreRate >= 0 ? a.scoreRate : -2;
      const rateB = b.scoreRate >= 0 ? b.scoreRate : -2;
      return sortOrder.value === 'asc' ? rateA - rateB : rateB - rateA;
    });
  } else if (sortKey.value === 'bpi') {
    // BPI 算出不能（avg/wr 欠落・未プレイ）は常に末尾へ送るため -Infinity 扱い。
    result.sort((a, b) => {
      const valA = recordBpi(a) ?? -Infinity;
      const valB = recordBpi(b) ?? -Infinity;
      if (valA !== valB) return sortOrder.value === 'asc' ? valA - valB : valB - valA;
      return a.title.localeCompare(b.title);
    });
  }

  return result;
});

/**
 * 【watch の役割】 モード（通常/rate）切替時にページ番号・レベルフィルタ・ソートを初期化。
 * モードが変わると表示レコードの種類が変わるため、ユーザー期待に合わせてリセットする。
 */
watch(viewMode, () => {
  currentPage.value = 1;
  filterLevel.value = [];
  sortKey.value = 'beatTierPoints';
  sortOrder.value = 'desc';
});

/** 全ページ数（最低 1）。Math.ceil で端数切り上げ。 */
const totalPages = computed(() => Math.ceil(filteredScores.value.length / itemsPerPage.value) || 1);

/** 現在ページに表示する行のみをスライスした表示用リスト。 */
const displayScores = computed(() => {
  const start = (currentPage.value - 1) * itemsPerPage.value;
  const end = start + itemsPerPage.value;
  return filteredScores.value.slice(start, end);
});

/** ページネーション: 前ページへ移動。1 ページ目では何もしない。 */
const prevPage = () => {
  if (currentPage.value > 1) currentPage.value--;
};

/** ページネーション: 次ページへ移動。最終ページでは何もしない。 */
const nextPage = () => {
  if (currentPage.value < totalPages.value) currentPage.value++;
};

// --- 色ユーティリティ ---
/** クリアタイプごとの文字色（テーブル表示用）。ダークモード対応。 */
const getClearTypeColor = (clearType: string) => {
  switch (clearType) {
    case 'FULLCOMBO CLEAR': return isDarkMode.value ? 'text-cyan-400' : 'text-cyan-600';
    case 'EX HARD CLEAR': return isDarkMode.value ? 'text-yellow-400' : 'text-yellow-600';
    case 'HARD CLEAR': return isDarkMode.value ? 'text-red-400' : 'text-red-500';
    case 'CLEAR': return isDarkMode.value ? 'text-blue-400' : 'text-blue-500';
    case 'EASY CLEAR': return isDarkMode.value ? 'text-green-400' : 'text-green-500';
    case 'ASSIST CLEAR': return isDarkMode.value ? 'text-purple-400' : 'text-purple-500';
    default: return isDarkMode.value ? 'text-slate-500' : 'text-slate-400';
  }
};

/** クリアタイプごとの背景色（バッジ・進捗バー用）。 */
const getClearTypeBgColor = (clearType: string) => {
  switch (clearType) {
    case 'FULLCOMBO CLEAR': return 'bg-cyan-500';
    case 'EX HARD CLEAR': return 'bg-yellow-500';
    case 'HARD CLEAR': return 'bg-red-500';
    case 'CLEAR': return 'bg-blue-500';
    case 'EASY CLEAR': return 'bg-green-500';
    case 'ASSIST CLEAR': return 'bg-purple-500';
    default: return isDarkMode.value ? 'bg-slate-700' : 'bg-slate-200';
  }
};

/**
 * 【関数の役割】 テーブル上でスコアの「近さ」を示す 2 段ラベルを作る。
 *
 * 判定基準:
 *  - scoreRate >= 94.45% (MAX に近い)  → primary: MAX-残差 / secondary: AAA+差分
 *  - scoreRate >= 88.89% (AAA 以上)    → primary: AAA+差分 / secondary: MAX-残差
 *  - それ以下                          → primary: AAA-不足 / secondary: AA±差分
 * スコア未プレイ（<= 0）や maxScore 不明の場合は null を返し、テンプレは何も表示しない。
 */
const getScoreGradeLabel = (record: ScoreRecord) => {
  if (record.maxScore <= 0 || record.scoreRate < 0 || record.score <= 0) return null;
  const maxScore = record.maxScore;
  const score = record.score;
  // AAA/AA の閾値は 8/9, 7/9 で切り上げ（IIDX 公式仕様）。
  const aaaThreshold = Math.ceil(maxScore * 8 / 9);
  const aaThreshold = Math.ceil(maxScore * 7 / 9);
  if (record.scoreRate >= 94.45) {
    return { primary: `MAX-${maxScore - score}`, secondary: `AAA+${score - aaaThreshold}` };
  } else if (record.scoreRate >= 88.89) {
    return { primary: `AAA+${score - aaaThreshold}`, secondary: `MAX-${maxScore - score}` };
  } else {
    const aaDiff = score - aaThreshold;
    return { primary: `AAA-${aaaThreshold - score}`, secondary: aaDiff >= 0 ? `AA+${aaDiff}` : `AA-${-aaDiff}` };
  }
};

/** DJ LEVEL（AAA/AA/A/...）の文字色。ダークモードで明度を上げる。 */
const getDjLevelColor = (djLevel: string) => {
  switch (djLevel) {
    case 'AAA': return isDarkMode.value ? 'text-amber-400' : 'text-amber-500';
    case 'AA': return isDarkMode.value ? 'text-yellow-400' : 'text-yellow-500';
    case 'A': return isDarkMode.value ? 'text-emerald-400' : 'text-emerald-500';
    default: return isDarkMode.value ? 'text-slate-500' : 'text-slate-400';
  }
};

/** DJ LEVEL の背景色。バッジ・進捗バー用。 */
const getDjLevelBgColor = (djLevel: string) => {
  switch (djLevel) {
    case 'AAA': return 'bg-amber-500';
    case 'AA': return 'bg-yellow-500';
    case 'A': return 'bg-emerald-500';
    default: return isDarkMode.value ? 'bg-slate-700' : 'bg-slate-200';
  }
};

/**
 * 【外部公開】 曲名からレコードを引いて詳細モーダルを開く。
 * OCR カメラ検索などの外部導線から呼ばれる。allRecords は ☆11/☆12 ANOTHER/LEGGENDARIA
 * のみを含むため、範囲外の曲は見つからず false を返す（呼び出し側で「開かない」判断に使える）。
 * ANOTHER → LEGGENDARIA → その他 の順で優先してマッチさせる。
 */
const openSongByTitle = (title: string): boolean => {
  const records = allRecords.value;
  const exact = records.find(r => r.title === title && r.difficultyName === 'ANOTHER')
    ?? records.find(r => r.title === title && r.difficultyName === 'LEGGENDARIA')
    ?? records.find(r => r.title === title);
  if (!exact) return false;
  openDetailModal(exact);
  return true;
};
defineExpose({ openSongByTitle });
</script>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.15s ease-out forwards;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
