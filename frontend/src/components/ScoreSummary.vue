<template>
  <!--
    ============================================================
    ScoreSummary.vue 繝ｫ繝ｼ繝医ユ繝ｳ繝励Ξ繝ｼ繝�
      - 繝倥ャ繝: 繧ｿ繧､繝医Ν + 莉ｶ謨ｰ陦ｨ遉ｺ + 繝輔ぅ繝ｫ繧ｿ鄒､�医ぞ繝ｭ髱櫁｡ｨ遉ｺ/繝ｬ繝吶Ν/髮｣譏灘ｺｦ/DJ LEVEL/繧ｯ繝ｪ繧｢繧ｿ繧､繝�/讀懃ｴ｢��
      - 繝｢繝ｼ繝峨ち繝厄ｼ�BEAT-TIER / RATE-TIER��
      - 繝��繧ｿ繝��繝悶Ν��displayScores 繧� v-for��
      - 繝壹�繧ｸ繝阪�繧ｷ繝ｧ繝ｳ
      - 隧ｳ邏ｰ繝｢繝ｼ繝繝ｫ��selectedRecord !== null 縺ｮ髢薙□縺� v-if 陦ｨ遉ｺ��
    ============================================================
  -->
  <div class="w-full mx-auto space-y-6 animate-fade-in relative">
    <!-- ===== 繝輔ぅ繝ｫ繧ｿ繝ｻ讀懃ｴ｢繝倥ャ繝 ===== -->
    <div class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 flex flex-col xl:flex-row xl:items-center justify-between gap-4 transition-colors duration-200">
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
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 dark:border-slate-700 rounded-xl bg-slate-50 dark:bg-slate-900 text-xs sm:text-sm font-medium text-slate-700 dark:text-slate-200 transition-colors hover:bg-white dark:hover:bg-slate-800 shadow-sm"
              :title="filterLevel.length > 0 ? filterLevel.map(l => '笘�'+l).join(', ') : t('table.level')"
            >
              <span class="truncate">{{ filterLevel.length > 0 ? filterLevel.map(l => '笘�'+l).join(', ') : t('table.level') }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0 ml-1" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'level'" class="absolute z-20 mt-1 w-48 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
              <label v-for="l in (viewMode === 'rate' ? [1,2,3,4,5,6,7,8,9,10,11,12] : [11,12])" :key="l" class="flex items-center px-4 py-2 hover:bg-slate-50 dark:hover:bg-slate-700/50 cursor-pointer group">
                <input 
                  type="checkbox" 
                  :checked="isSelected(filterLevel, l.toString())"
                  @change="toggleFilterValue(filterLevel, l.toString())"
                  class="h-4 w-4 text-blue-600 rounded border-slate-300 dark:border-slate-600 focus:ring-blue-500 dark:focus:ring-blue-600 transition-all cursor-pointer bg-white dark:bg-slate-900"
                >
                <span class="ml-3 text-sm font-medium text-slate-600 dark:text-slate-300 group-hover:text-slate-900 dark:group-hover:text-white transition-colors">笘�{{ l }}</span>
              </label>
            </div>
          </div>

          <!-- Difficulty Filter -->
          <div class="relative w-full md:w-36">
            <button 
              @click.stop="toggleDropdown('difficulty')"
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 dark:border-slate-700 rounded-xl bg-slate-50 dark:bg-slate-900 text-xs sm:text-sm font-medium text-slate-700 dark:text-slate-200 transition-colors hover:bg-white dark:hover:bg-slate-800 shadow-sm"
              :title="filterDifficulty.length > 0 ? filterDifficulty.map(d => d.substring(0,3)).join(', ') : t('table.difficulty')"
            >
              <span class="truncate">{{ filterDifficulty.length > 0 ? filterDifficulty.map(d => d.substring(0,3)).join(', ') : t('table.difficulty') }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0 ml-1" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'difficulty'" class="absolute z-20 mt-1 w-48 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
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
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 dark:border-slate-700 rounded-xl bg-slate-50 dark:bg-slate-900 text-xs sm:text-sm font-medium text-slate-700 dark:text-slate-200 transition-colors hover:bg-white dark:hover:bg-slate-800 shadow-sm"
            >
              <span class="truncate">{{ t('table.rank') }}{{ filterDjLevel.length > 0 ? ` (${filterDjLevel.length})` : '' }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'djLevel'" class="absolute z-20 mt-1 w-48 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
              <label v-for="lvl in DJ_LEVELS" :key="lvl" class="flex items-center px-4 py-2 hover:bg-slate-50 dark:hover:bg-slate-700/50 cursor-pointer group">
                <input
                  type="checkbox"
                  :checked="isSelected(filterDjLevel, lvl)"
                  @change="toggleFilterValue(filterDjLevel, lvl)"
                  class="h-4 w-4 text-blue-600 rounded border-slate-300 dark:border-slate-600 focus:ring-blue-500 dark:focus:ring-blue-600 transition-all cursor-pointer bg-white dark:bg-slate-900"
                >
                <span class="ml-3 text-sm font-black text-slate-600 dark:text-slate-300 group-hover:text-slate-900 dark:group-hover:text-white transition-colors">{{ lvl }}</span>
              </label>
            </div>
          </div>

          <!-- Clear Type Filter -->
          <div class="relative w-full md:w-36">
            <button
              @click.stop="toggleDropdown('clearType')"
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 dark:border-slate-700 rounded-xl bg-slate-50 dark:bg-slate-900 text-xs sm:text-sm font-medium text-slate-700 dark:text-slate-200 transition-colors hover:bg-white dark:hover:bg-slate-800 shadow-sm"
            >
              <span class="truncate">{{ t('table.lamp') }}{{ filterClearType.length > 0 ? ` (${filterClearType.length})` : '' }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'clearType'" class="absolute z-20 mt-1 w-56 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
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

          <!-- Source Filter��INFINITAS / 繧｢繝ｼ繧ｱ繝ｼ繝会ｼ峨�INFINITAS 繧ｹ繧ｳ繧｢繧貞叙繧願ｾｼ繧薙〒縺�ｋ蝣ｴ蜷医�縺ｿ陦ｨ遉ｺ縲� -->
          <div v-if="hasInfinitasScores" class="relative w-full md:w-36">
            <button
              @click.stop="toggleDropdown('source')"
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 dark:border-slate-700 rounded-xl bg-slate-50 dark:bg-slate-900 text-xs sm:text-sm font-medium text-slate-700 dark:text-slate-200 transition-colors hover:bg-white dark:hover:bg-slate-800 shadow-sm"
            >
              <span class="truncate">{{ t('table.source') }}{{ filterSource.length > 0 ? ` (${filterSource.length})` : '' }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'source'" class="absolute z-20 mt-1 w-48 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
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
            class="block w-full pl-9 pr-3 py-2 border border-slate-200 dark:border-slate-700 rounded-xl leading-5 bg-slate-50 dark:bg-slate-900 text-slate-700 dark:text-slate-200 placeholder-slate-400 dark:placeholder-slate-500 focus:outline-none focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 sm:text-sm transition-colors"
            :placeholder="t('table.searchPlaceholder')"
          >
        </div>
      </div>
    </div>

    <!-- ===== 驕ｩ逕ｨ貂医∩繝輔ぅ繝ｫ繧ｿ繝√ャ繝苓｡鯉ｼ医ヵ繧｣繝ｫ繧ｿ縺� 1 縺､莉･荳頑寺縺九▲縺ｦ縺�ｋ譎ゅ□縺題｡ｨ遉ｺ�� ===== -->
    <!-- 蜷�メ繝��縺ｮ ﾃ� 縺ｧ蛟句挨隗｣髯､縲∝承遶ｯ縺ｮ縲悟�繧ｯ繝ｪ繧｢縲阪〒荳諡ｬ隗｣髯､縺ｧ縺阪ｋ縲� -->
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
        class="ml-auto inline-flex items-center gap-1 px-3 py-1 text-xs font-bold text-slate-600 dark:text-slate-300 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-full hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
      >
        <svg aria-hidden="true" class="h-3 w-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
        </svg>
        {{ t('filter.clearAll') }}
      </button>
    </div>

    <!-- ===== 繝｢繝ｼ繝峨ち繝厄ｼ�BEAT-TIER / RATE-TIER 蛻�崛縲ＴhowRateTier 縺� true 縺ｮ縺ｨ縺阪�縺ｿ陦ｨ遉ｺ�� ===== -->
    <div v-if="showRateTier" class="flex gap-1 bg-slate-100 dark:bg-slate-800/80 p-1 rounded-xl w-fit border border-slate-200 dark:border-slate-700">
      <button
        @click="viewMode = 'beat'"
        class="px-4 py-2 rounded-lg text-sm font-bold transition-colors"
        :class="viewMode === 'beat' ? 'bg-white dark:bg-slate-700 text-blue-600 dark:text-blue-400 shadow-sm' : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
      >BEAT-TIER</button>
      <button
        @click="viewMode = 'rate'"
        class="px-4 py-2 rounded-lg text-sm font-bold transition-colors"
        :class="viewMode === 'rate' ? 'bg-white dark:bg-slate-700 text-emerald-600 dark:text-emerald-400 shadow-sm' : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
      >RATE-TIER</button>
    </div>

    <!-- ===== 繝��繧ｿ繝��繝悶Ν��displayScores 繧呈緒逕ｻ縲ゅ�繝�ム蛻励け繝ｪ繝�け縺ｧ toggleSort�� ===== -->
    <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
      <div class="overflow-x-auto">
        <table class="w-full text-left text-[10px] sm:text-sm text-slate-600 dark:text-slate-300">
          <thead class="sticky top-0 z-10 bg-slate-50 dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200 font-semibold h-10 sm:h-12 shadow-sm">
            <tr>
              <th class="px-1 sm:px-6 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-black text-slate-500 dark:text-slate-400 uppercase tracking-wider group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors w-auto sm:w-4/12" @click="toggleSort('title')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  {{ t('table.colTitle') }}
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'title'">
                    {{ sortOrder === 'asc' ? '笆ｲ' : '笆ｼ' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">竊�</span>
                </div>
              </th>
              <th class="px-1 sm:px-4 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-black text-slate-700 dark:text-slate-200 uppercase tracking-wider w-auto sm:w-1/12 group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors" @click="toggleSort('difficultyLevel')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  {{ t('table.colOfficial') }}
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'difficultyLevel'">
                    {{ sortOrder === 'asc' ? '笆ｲ' : '笆ｼ' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">竊�</span>
                </div>
              </th>
              <th class="px-1 sm:px-4 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-black text-slate-700 dark:text-slate-200 uppercase tracking-wider w-auto sm:w-1/12 group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors" @click="toggleSort('informalRank')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  {{ t('table.colInformal') }}
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'informalRank'">
                    {{ sortOrder === 'asc' ? '笆ｲ' : '笆ｼ' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">竊�</span>
                </div>
              </th>
              <th class="max-sm:hidden px-1 sm:px-6 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-black text-slate-500 dark:text-slate-400 uppercase tracking-wider group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors w-auto sm:w-2/12" @click="toggleSort('clearType')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  {{ t('table.colScore') }}
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'clearType'">
                    {{ sortOrder === 'asc' ? '笆ｲ' : '笆ｼ' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">竊�</span>
                </div>
              </th>
              <th class="px-1 sm:px-4 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-black text-slate-500 dark:text-slate-400 uppercase tracking-wider group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors w-auto sm:w-1/12" @click="toggleSort('scoreRate')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  {{ t('table.colRate') }}
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'scoreRate'">
                    {{ sortOrder === 'asc' ? '笆ｲ' : '笆ｼ' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">竊�</span>
                </div>
              </th>
              <th class="px-1 sm:px-2 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-black text-slate-500 dark:text-slate-400 uppercase tracking-wider group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors w-8 sm:w-12" @click="toggleSort('unofficialSongRank')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  {{ t('table.colRank') }}
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'unofficialSongRank'">
                    {{ sortOrder === 'asc' ? '笆ｲ' : '笆ｼ' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">竊�</span>
                </div>
              </th>
              <th class="px-1 sm:px-6 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-black uppercase tracking-wider group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors w-auto sm:w-2/12"
                :class="viewMode === 'rate' ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-500 dark:text-slate-400'"
                @click="toggleSort('beatTierPoints')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  {{ viewMode === 'rate' ? t('table.colPoints') : t('table.colBeatPt') }}
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'beatTierPoints'">
                    {{ sortOrder === 'asc' ? '笆ｲ' : '笆ｼ' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">竊�</span>
                </div>
              </th>
              <th class="px-1 sm:px-4 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-black uppercase tracking-wider w-auto sm:w-2/12"
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
                        <span class="text-slate-300 dark:text-slate-600">ﾂｷ</span>
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
                  <!-- INF 繧ｿ繧ｰ: 陦ｨ遉ｺ荳ｭ縺ｮ繧ｹ繧ｳ繧｢縺� INFINITAS 蜿門ｾ暦ｼ医い繝ｼ繧ｱ繝ｼ繝峨ｈ繧企ｫ倥＞�上い繝ｼ繧ｱ繝ｼ繝画悴繝励Ξ繧､�峨�蝣ｴ蜷医↓莉倅ｸ弱� -->
                  <span v-if="record.source === 'infinitas'"
                        class="px-1 sm:px-1.5 py-0 rounded text-[7px] sm:text-[9px] font-black whitespace-nowrap inline-block w-fit text-sky-700 bg-sky-100 border border-sky-300 dark:text-sky-300 dark:bg-sky-900/40 dark:border-sky-700"
                        title="INFINITAS 縺ｧ蜿悶ｊ霎ｼ繧薙□繧ｹ繧ｳ繧｢�医い繝ｼ繧ｱ繝ｼ繝峨→縺ｯ蛻･邂｡逅�ゆｸ｡譁ｹ縺ゅｋ蝣ｴ蜷医� EX SCORE 縺碁ｫ倥＞譁ｹ繧定｡ｨ遉ｺ��">
                    INF
                  </span>
                  <!-- 隴憺擇繧ｿ繧､繝暦ｼ磯嵯逶､/繝舌Λ繝ｳ繧ｹ/逧ｿ�峨ヰ繝�ず縲�KENBAN/SARA-Tier 繝医げ繝ｫ ON 縺ｮ縺ｨ縺阪□縺題｡ｨ遉ｺ縲� -->
                  <span v-if="showKenbanSaraTier && recordChartType(record) !== 'unknown'"
                        :class="['px-1 sm:px-1.5 py-0 rounded text-[7px] sm:text-[9px] font-bold whitespace-nowrap inline-block w-fit', chartTypeBadgeClass(recordChartType(record))]"
                        :title="chartTypeTitle(record)">
                    {{ chartTypeLabel(recordChartType(record)) }}
                  </span>
                </div>
              </td>
              <td class="px-1 sm:px-4 py-1.5 sm:py-2 whitespace-nowrap">
                  <InformalRankBadge :rank="record.informalRank" size="xs" />
              </td>
              <td class="max-sm:hidden px-1 sm:px-6 py-1.5 sm:py-2">
                <div class="flex flex-col gap-0.5">
                  <div class="flex items-center gap-1 sm:gap-2">
                    <span class="font-black text-[8px] sm:text-[10px] truncate max-w-[36px] sm:max-w-none" :class="getClearTypeColor(record.clearType)">
                      {{ record.clearType === 'FULLCOMBO CLEAR' ? 'FC' : record.clearType === 'EX HARD CLEAR' ? 'EXH' : record.clearType === 'HARD CLEAR' ? 'H' : record.clearType === 'CLEAR' ? 'C' : record.clearType === 'EASY CLEAR' ? 'E' : record.clearType === 'ASSIST CLEAR' ? 'AC' : 'F' }}
                    </span>
                    <span class="font-black text-[10px] sm:text-sm" :class="getDjLevelColor(record.djLevel)">{{ record.djLevel !== '---' ? record.djLevel : '' }}</span>
                    <template v-if="isLoggedIn && songRankMap.get(record.title + '|' + record.difficultyName)">
                      <span class="font-bold text-[9px] sm:text-[11px]" :class="songRankMap.get(record.title + '|' + record.difficultyName)!.rank === 1 ? 'text-amber-500 dark:text-amber-400' : 'text-slate-400 dark:text-slate-500'">
                        #{{ songRankMap.get(record.title + '|' + record.difficultyName)!.rank }}<span class="font-normal text-slate-400 dark:text-slate-600">/{{ songRankMap.get(record.title + '|' + record.difficultyName)!.total }}</span>
                      </span>
                    </template>
                  </div>
                  <div class="flex items-center gap-0.5 sm:gap-1">
                     <span class="font-black text-slate-800 dark:text-slate-200 text-[9px] sm:text-xs">{{ record.score }}</span>
                  </div>
                </div>
              </td>
              <td class="px-1 sm:px-4 py-1.5 sm:py-2 whitespace-nowrap">
                <span v-if="record.scoreRate >= 0" class="font-bold text-[9px] sm:text-xs" :class="record.scoreRate >= 94.45 ? 'text-purple-600 dark:text-purple-400' : record.scoreRate >= 88.89 ? 'text-amber-500 dark:text-amber-400' : 'text-slate-600 dark:text-slate-400'">{{ record.scoreRate.toFixed(2) }}%</span>
                <span v-else class="text-[9px] sm:text-[10px] font-bold text-slate-400 dark:text-slate-500">---</span>
              </td>

              <!-- 蜊俶峇繝ｩ繝ｳ繧ｯ�亥ｿ�ｦ√せ繧ｳ繧｢繝ｬ繝ｼ繝郁｡ｨ蟇ｾ蠢懶ｼ峨ゅい繧､繧ｳ繝ｳ縺ｮ縺ｿ陦ｨ遉ｺ縲� -->
              <td class="px-1 sm:px-2 py-1.5 sm:py-2 whitespace-nowrap w-8 sm:w-12">
                <template v-for="rankInfo of [getSongUnofficialRank(record)]" :key="0">
                  <RankIcon v-if="rankInfo" :rank-name="rankInfo.name" :tier="rankInfo.tier" size="xs" />
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
                    <span class="font-black" :class="top100Keys.has(record.title + '|' + record.difficultyName) ? 'text-blue-700 dark:text-blue-400 text-[10px] sm:text-base' : 'text-slate-800 dark:text-slate-200 text-[9px] sm:text-sm'">
                      {{ record.beatTierPoints.toFixed(1) }}
                    </span>
                    <span v-if="top100Keys.has(record.title + '|' + record.difficultyName)" class="hidden sm:inline-block px-1 py-0.5 rounded bg-blue-100 dark:bg-blue-900/50 text-blue-600 dark:text-blue-400 text-[6px] sm:text-[8px] font-black uppercase border border-blue-200 dark:border-blue-800 shadow-sm">
                      TOP
                    </span>
                  </div>
                  <span class="text-[7px] sm:text-[10px] font-bold text-slate-400 dark:text-slate-500">/{{ record.maxBeatTierPoints.toFixed(1) }}</span>
                </div>
                <div v-else class="flex items-center justify-center">
                  <span class="text-[9px] sm:text-[10px] font-black text-slate-700 dark:text-slate-500 italic">N/A</span>
                </div>
              </td>
              <!-- RATE-TIER mode: 迯ｲ蠕猶T cell -->
              <td v-else class="px-1 sm:px-6 py-1.5 sm:py-2 whitespace-nowrap transition-colors"
                :class="hasPerfectRateOverflow && record.scoreRate >= 100 ? 'bg-amber-100/80 dark:bg-amber-900/30' : rateTop100Keys.has(record.title + '|' + record.difficultyName) ? 'bg-emerald-50/80 dark:bg-emerald-900/20' : ''"
              >
                <div v-if="record.scoreRate > 0" class="flex items-center gap-0.5 sm:gap-1">
                  <span class="font-black" :class="rateTop100Keys.has(record.title + '|' + record.difficultyName) ? 'text-emerald-700 dark:text-emerald-400 text-[10px] sm:text-base' : 'text-slate-800 dark:text-slate-200 text-[9px] sm:text-sm'">
                    {{ calculateScoreRateTierPoints(record.scoreRate).toFixed(2) }}
                  </span>
                  <span v-if="rateTop100Keys.has(record.title + '|' + record.difficultyName)" class="hidden sm:inline-block px-1 py-0.5 rounded bg-emerald-100 dark:bg-emerald-900/50 text-emerald-600 dark:text-emerald-400 text-[6px] sm:text-[8px] font-black uppercase border border-emerald-200 dark:border-emerald-800 shadow-sm">
                    TOP
                  </span>
                </div>
                <span v-else class="text-[9px] sm:text-[10px] font-black text-slate-700 dark:text-slate-500 italic">---</span>
              </td>
              <!-- TOP100 column -->
              <td class="px-1 sm:px-4 py-1.5 sm:py-2 whitespace-nowrap">
                <div
                  v-if="viewMode === 'beat' && top100ScoreNeededMap.has(record.title + '|' + record.difficultyName)"
                  class="flex flex-col gap-0.5"
                >
                  <span class="text-[9px] sm:text-[11px] font-black text-orange-500 dark:text-orange-400">{{ t('table.top100GapStart') }}</span>
                  <span class="text-xs sm:text-sm font-black text-orange-600 dark:text-orange-300 tabular-nums">{{ top100ScoreNeededMap.get(record.title + '|' + record.difficultyName)!.toLocaleString() }}{{ t('common.points') }}</span>
                  <span class="text-[8px] sm:text-[10px] font-bold text-orange-400 dark:text-orange-500">{{ t('table.top100GapEnd') }}</span>
                </div>
              </td>
            </tr>
            <tr v-if="displayScores.length === 0">
              <td colspan="8" class="px-6 py-12 text-center text-slate-500 dark:text-slate-400 w-full">
                {{ t('table.noMatchingScores') }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      
      <!-- ===== 繝壹�繧ｸ繝阪�繧ｷ繝ｧ繝ｳ�井ｻｶ謨ｰ陦ｨ遉ｺ + 1 繝壹�繧ｸ縺ゅ◆繧贋ｻｶ謨ｰ繧ｻ繝ｬ繧ｯ繧ｿ + 蜑榊ｾ後�繧ｿ繝ｳ�� ===== -->
      <div v-if="filteredScores.length > 0" class="px-6 py-4 border-t border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-800/50 flex flex-col sm:flex-row items-center justify-between gap-4 transition-colors duration-200">
        <div class="flex flex-col sm:flex-row items-center gap-4">
          <div class="text-sm text-slate-500 dark:text-slate-400">
            {{ t('table.displayCount', { start: (currentPage - 1) * itemsPerPage + 1, end: Math.min(currentPage * itemsPerPage, filteredScores.length), total: filteredScores.length }) }}
          </div>
          <div class="flex items-center gap-2">
            <span class="text-sm text-slate-500 dark:text-slate-400">{{ t('table.itemsPerPage') }}:</span>
            <select v-model="itemsPerPage" class="text-sm border border-slate-200 dark:border-slate-700 rounded-lg bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300 px-2 py-1 outline-none focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-600 transition-colors shadow-sm cursor-pointer">
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
            class="px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300 shadow-sm"
          >
            {{ t('table.prev') }}
          </button>
          <span class="text-sm font-medium text-slate-600 dark:text-slate-400 px-2 min-w-[3rem] text-center">{{ currentPage }} / {{ totalPages }}</span>
          <button 
            @click="nextPage" 
            :disabled="currentPage === totalPages"
            class="px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300 shadow-sm"
          >
            {{ t('table.next') }}
          </button>
        </div>
      </div>
    </div>

    <!--
      ===== 蜈ｨ逕ｻ髱｢隧ｳ邏ｰ繝｢繝ｼ繝繝ｫ =====
      selectedRecord 縺碁撼 null 縺ｮ髢薙□縺� Teleport 縺ｧ <body> 逶ｴ荳九↓繝ｬ繝ｳ繝繝ｪ繝ｳ繧ｰ縺輔ｌ繧九�
      讒矩�:
        - Sticky Header: 譖ｲ蜷�/繧｢繝ｼ繝�ぅ繧ｹ繝� + 髢峨§繧九�繧ｿ繝ｳ + 繧ｿ繝悶ヰ繝ｼ��detail / rate-tier / rivals / ranking / history��
        - 蜷�ち繝悶�繧ｳ繝ｳ繝�Φ繝�ｼ�v-if 縺ｧ縺ｲ縺ｨ縺､縺�縺題｡ｨ遉ｺ��
        - Sticky Footer: 繝｢繝舌う繝ｫ髢峨§繧九�繧ｿ繝ｳ
    -->
    <Teleport to="body">
      <div v-if="selectedRecord" class="fixed inset-0 z-[100] bg-slate-50 dark:bg-slate-900 flex flex-col animate-fade-in transition-colors duration-200" @click.self="closeDetailModal">

      <!-- ===== 繝｢繝ｼ繝繝ｫ Sticky 繝倥ャ繝�域峇諠��ｱ + 髢峨§繧九�繧ｿ繝ｳ + 繧ｿ繝悶ヰ繝ｼ�� ===== -->
      <div class="bg-white dark:bg-slate-900 sticky top-0 z-10 w-full shadow-sm border-b border-slate-200 dark:border-slate-800 transition-colors duration-200">
        <div class="px-4 py-3 sm:px-6 sm:py-5 flex justify-between items-center">
          <div class="flex flex-col pr-4 max-w-full overflow-hidden">
            <h3 class="text-xl sm:text-3xl font-black text-slate-800 dark:text-slate-100 leading-tight mb-0.5 sm:mb-1 truncate" :title="selectedRecord.title">{{ selectedRecord.title }}</h3>
            <p class="text-xs sm:text-base font-medium text-slate-500 dark:text-slate-400 truncate" :title="`${selectedRecord.artist} 窶｢ ${selectedRecord.genre}`">{{ selectedRecord.artist }} 窶｢ {{ selectedRecord.genre }}</p>
          </div>
          <button @click="closeDetailModal" class="flex-shrink-0 text-slate-400 dark:text-slate-500 hover:text-slate-700 dark:hover:text-slate-300 bg-slate-50 dark:bg-slate-800 hover:bg-slate-200 dark:hover:bg-slate-700 rounded-full transition-colors p-2 sm:p-3 shadow-sm border border-slate-200 dark:border-slate-700">
            <svg class="w-5 h-5 sm:w-8 sm:h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
        <!-- 繝｢繝ｼ繝繝ｫ蜀�ち繝悶ヰ繝ｼ: detail / rate-tier / rivals(=ranking) / history縲ゅΟ繧ｰ繧､繝ｳ迥ｶ諷九ｄ髮｣譏灘ｺｦ縺ｧ荳驛ｨ縺ｮ縺ｿ陦ｨ遉ｺ -->
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
            v-if="isLoggedIn"
            @click="handleRivalTabClick"
            class="flex-1 py-2 text-sm font-bold border-b-2 transition-colors flex items-center justify-center gap-1"
            :class="modalTab === 'rivals' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-700 dark:hover:text-slate-300'"
          >
            {{ t('table.ranking') }}
            <span v-if="rankingList.length > 0" class="text-xs bg-blue-100 dark:bg-blue-900/40 text-blue-600 dark:text-blue-400 rounded-full px-1.5">{{ rankingList.length }}</span>
          </button>
          <button
            v-if="isLoggedIn && ['ANOTHER', 'LEGGENDARIA'].includes(selectedRecord?.difficultyName ?? '')"
            @click="handleHistoryTabClick"
            class="flex-1 py-2 text-sm font-bold border-b-2 transition-colors"
            :class="modalTab === 'history' ? 'border-violet-500 text-violet-600 dark:text-violet-400' : 'border-transparent text-slate-500 hover:text-slate-700 dark:hover:text-slate-300'"
          >{{ t('table.history') }}</button>
        </div>
      </div>
      
      <!-- ===== 繝｢繝ｼ繝繝ｫ縺ｮ繧ｹ繧ｯ繝ｭ繝ｼ繝ｫ蜿ｯ閭ｽ縺ｪ譛ｬ菴馴�伜沺 ===== -->
      <div class="flex-1 overflow-y-auto p-3 sm:p-8 lg:p-12 pb-24">

        <!-- ===== Rate-Tier 繧ｿ繝�: 迯ｲ蠕猶T + 繧ｹ繧ｳ繧｢繝ｬ繝ｼ繝� + 髢ｾ蛟､陦ｨ ===== -->
        <div v-if="modalTab === 'rate-tier'" class="w-full max-w-4xl mx-auto space-y-6">
          <!-- 迯ｲ蠕猶T 縺ｨ 繧ｹ繧ｳ繧｢繝ｬ繝ｼ繝� 繧貞､ｧ縺阪￥讓ｪ荳ｦ縺ｳ縺ｧ陦ｨ遉ｺ -->
          <div class="grid grid-cols-2 gap-4">
            <div class="bg-emerald-900/10 dark:bg-emerald-900/20 p-6 sm:p-8 rounded-2xl shadow-md flex flex-col items-center justify-center border border-emerald-100 dark:border-emerald-800/50">
              <p class="text-xs sm:text-sm font-bold text-emerald-600 dark:text-emerald-400 uppercase tracking-widest mb-2">{{ t('table.colPoints') }}</p>
              <p class="text-4xl sm:text-6xl font-black text-emerald-700 dark:text-emerald-300 tracking-tight">
                {{ calculateScoreRateTierPoints(selectedRecord!.scoreRate).toFixed(2) }}
              </p>
              <p class="text-xs font-bold text-emerald-500 dark:text-emerald-500 mt-1">/ 256 pt (MAX)</p>
            </div>
            <div class="bg-blue-50 dark:bg-slate-800 p-6 sm:p-8 rounded-2xl border-4 border-blue-200 dark:border-slate-700 flex flex-col items-center justify-center">
              <p class="text-xs sm:text-sm font-bold text-blue-500 dark:text-blue-400 uppercase tracking-widest mb-2">{{ t('table.colRate') }}</p>
              <p class="text-4xl sm:text-6xl font-black text-blue-600 dark:text-blue-300 tracking-tight flex items-baseline">
                <template v-if="selectedRecord!.scoreRate >= 0">{{ selectedRecord!.scoreRate.toFixed(2) }}</template>
                <template v-else>---</template>
                <span class="text-xl sm:text-3xl font-bold ml-1">%</span>
              </p>
            </div>
          </div>

          <!-- Rate-Tier 縺ｮ髢ｾ蛟､繝��繝悶Ν�亥推髢ｾ蛟､縺ｮ蛻ｰ驕�/譛ｪ蛻ｰ驕斐ｒ 笨� / +蟾ｮ% 縺ｧ陦ｨ遉ｺ�� -->
          <div class="border border-slate-200 dark:border-slate-700 rounded-2xl overflow-hidden shadow-sm bg-white dark:bg-slate-800">
            <div class="bg-slate-100 dark:bg-slate-900/50 px-6 py-3 border-b border-slate-200 dark:border-slate-700">
              <p class="text-xs font-bold text-slate-600 dark:text-slate-400 uppercase tracking-widest">{{ t('table.rateTierThresholds') }}</p>
            </div>
            <table class="w-full text-sm">
              <thead>
                <tr class="border-b border-slate-100 dark:border-slate-700">
                  <th class="px-6 py-3 text-left text-xs font-black text-slate-400 uppercase tracking-widest">{{ t('table.colRate') }}</th>
                  <th class="px-6 py-3 text-right text-xs font-black text-slate-400 uppercase tracking-widest">{{ t('table.colPoints') }}</th>
                  <th class="px-6 py-3 text-center text-xs font-black text-slate-400 uppercase tracking-widest">{{ t('table.achieved') }}</th>
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
                  <td class="px-6 py-3 text-right font-black tabular-nums" :class="selectedRecord!.scoreRate >= th.rate ? 'text-emerald-700 dark:text-emerald-300' : 'text-slate-500 dark:text-slate-400'">
                    {{ th.points }}
                  </td>
                  <td class="px-6 py-3 text-center">
                    <span v-if="selectedRecord!.scoreRate >= th.rate" class="text-emerald-500 font-black text-base">笨�</span>
                    <span v-else class="text-slate-300 dark:text-slate-600 font-black text-sm">
                      +{{ (th.rate - selectedRecord!.scoreRate).toFixed(2) }}%
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ===== Ranking 繧ｿ繝�: 閾ｪ蛻� + 繝輔Ξ繝ｳ繝� + (莉ｻ諢�)蜈ｬ髢九Θ繝ｼ繧ｶ繝ｼ + (莉ｻ諢�)TOP繝ｩ繝ｳ繧ｫ繝ｼ莉ｮ諠ｳ繝ｦ繝ｼ繧ｶ繝ｼ繧堤ｵｱ蜷郁｡ｨ遉ｺ ===== -->
        <div v-else-if="modalTab === 'rivals'" class="w-full">
          <!-- 陦ｨ遉ｺ繝輔ぅ繝ｫ繧ｿ: 蜈ｬ髢九Θ繝ｼ繧ｶ繝ｼ / 莉ｮ諠ｳ繝ｦ繝ｼ繧ｶ繝ｼ縺ｮ陦ｨ遉ｺ蛻�崛 -->
          <div class="flex flex-wrap items-center gap-4 mb-4 p-3 bg-slate-50 dark:bg-slate-700/30 rounded-xl border border-slate-100 dark:border-slate-700">
            <label class="flex items-center gap-2 text-sm font-bold text-slate-700 dark:text-slate-200 cursor-pointer">
              <input type="checkbox" v-model="showPublicUsers" class="w-4 h-4 rounded accent-blue-600" />
              繧ｹ繧ｳ繧｢蜈ｬ髢九Θ繝ｼ繧ｶ繝ｼ繧り｡ｨ遉ｺ
            </label>
            <label class="flex items-center gap-2 text-sm font-bold text-slate-700 dark:text-slate-200 cursor-pointer">
              <input type="checkbox" v-model="showVirtualUsers" class="w-4 h-4 rounded accent-amber-500" />
              TOP繝ｩ繝ｳ繧ｫ繝ｼ莉ｮ諠ｳ繝ｦ繝ｼ繧ｶ繝ｼ繧定｡ｨ遉ｺ
            </label>
          </div>

          <!-- ===== 蜊俶峇繝ｩ繝ｳ繧ｯ蛻�ｸ�: 繧ｹ繧ｳ繧｢縺ｮ縺ゅｋ蜈ｨ螳溘Θ繝ｼ繧ｶ繝ｼ�磯撼蜈ｬ髢句性繧繝ｻ蛹ｿ蜷埼寔險茨ｼ峨�蜊俶峇繝ｩ繝ｳ繧ｯ繧偵ヲ繧ｹ繝医げ繝ｩ繝�陦ｨ遉ｺ ===== -->
          <div v-if="songTierDist" class="mb-4 p-4 bg-white dark:bg-slate-800 rounded-xl border border-slate-100 dark:border-slate-700">
            <div class="flex flex-wrap items-baseline justify-between gap-2 mb-2">
              <h4 class="text-sm font-black text-slate-700 dark:text-slate-200">{{ t('table.songRankDist') }}</h4>
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
                  <th class="pb-3 pl-3 text-xs font-black text-slate-400 uppercase tracking-widest w-12">{{ t('table.colRankNum') }}</th>
                  <th class="pb-3 text-xs font-black text-slate-400 uppercase tracking-widest">{{ t('table.colPlayer') }}</th>
                  <th class="pb-3 text-xs font-black text-slate-400 uppercase tracking-widest w-16 text-center">{{ t('table.colSongRank') }}</th>
                  <th class="pb-3 text-xs font-black text-slate-400 uppercase tracking-widest text-right w-24">{{ t('table.colScore') }}</th>
                  <th class="pb-3 text-xs font-black text-slate-400 uppercase tracking-widest text-right w-20">{{ t('table.colRate') }}</th>
                  <th class="pb-3 pr-3 text-xs font-black text-slate-400 uppercase tracking-widest text-right w-20">{{ t('table.colBeatPt') }}</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
                <tr
                  v-for="row in rankingList"
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
                  <!-- 鬆�ｽ� -->
                  <td class="py-3 pl-3">
                    <div v-if="row.rank != null" class="flex items-center justify-center w-7 h-7 rounded-lg font-black text-xs"
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
                  <!-- 繝励Ξ繧､繝､繝ｼ蜷� / 莉ｮ諠ｳ繝ｦ繝ｼ繧ｶ -->
                  <td class="py-3">
                    <template v-if="row.kind === 'virtual'">
                      <div class="flex items-center gap-2 min-w-0">
                        <span
                          v-if="row.virtualBadge === 'allTimeGlobal' || row.virtualBadge === 'globalAllTime'"
                          class="inline-flex items-center px-1.5 py-0.5 rounded bg-rose-500 text-white text-[10px] font-black tracking-wider shrink-0"
                        >豁ｴ莉｣</span>
                        <span
                          v-else-if="row.virtualBadge === 'allTimeArea'"
                          class="inline-flex items-center px-1.5 py-0.5 rounded bg-rose-500 text-white text-[10px] font-black tracking-wider shrink-0"
                        >繧ｨ繝ｪ繧｢豁ｴ莉｣</span>
                        <span
                          v-else-if="row.virtualBadge === 'versionTop'"
                          class="inline-flex items-center px-1.5 py-0.5 rounded bg-indigo-500 text-white text-[10px] font-black tracking-wider shrink-0"
                        >繝舌�繧ｸ繝ｧ繝ｳTOP</span>
                        <span
                          v-else
                          class="inline-flex items-center px-1.5 py-0.5 rounded bg-amber-500 text-white text-[10px] font-black tracking-wider shrink-0"
                        >TOP</span>
                        <span class="font-bold text-slate-800 dark:text-slate-100 text-sm truncate">
                          {{ row.virtualEntry!.versionName }} {{ row.virtualEntry!.prefectureName }}
                          <span class="ml-1 text-xs text-slate-500 dark:text-slate-400">({{ row.virtualEntry!.djName }})</span>
                        </span>
                        <span v-if="row.isFriend" class="text-[10px] font-black text-emerald-600 dark:text-emerald-400 uppercase tracking-wider shrink-0">Friend</span>
                      </div>
                    </template>
                    <template v-else>
                      <div class="flex items-center gap-2 min-w-0">
                        <span class="font-bold text-sm truncate" :class="row.isSelf ? 'text-blue-700 dark:text-blue-300' : 'text-slate-800 dark:text-slate-100'">
                          {{ row.displayName }}
                        </span>
                        <span v-if="!row.isSelf && (row.privacyLevel ?? 1) !== 0" class="text-xs text-slate-400 shrink-0" :title="(row.privacyLevel ?? 1) === 2 ? '髱槫�髢�' : '繝輔Ξ繝ｳ繝峨�縺ｿ蜈ｬ髢�'">白</span>
                        <span v-if="row.isFriend && !row.isSelf" class="text-[10px] font-black text-emerald-600 dark:text-emerald-400 uppercase tracking-wider shrink-0">Friend</span>
                      </div>
                    </template>
                  </td>
                  <!-- 蜊俶峇繝ｩ繝ｳ繧ｯ繧｢繧､繧ｳ繝ｳ�医◎縺ｮ隴憺擇縺ｮ繧ｹ繧ｳ繧｢繝ｬ繝ｼ繝医°繧臥ｮ怜�縲ゅせ繧ｳ繧｢髱槫�髢玖｡後�髱櫁｡ｨ遉ｺ�� -->
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
                        />
                      </div>
                      <span v-else class="text-slate-300 dark:text-slate-600 text-xs">-</span>
                    </template>
                  </td>
                  <!-- 繧ｹ繧ｳ繧｢ -->
                  <td class="py-3 text-right">
                    <template v-if="row.kind === 'user' && !row.isSelf && (row.privacyLevel ?? 1) === 2 && !row.isFriend">
                      <span class="text-slate-400 dark:text-slate-500 text-sm font-bold">{{ t('table.privateShort') }}</span>
                    </template>
                    <template v-else-if="row.score != null">
                      <span class="font-black text-slate-800 dark:text-slate-100 text-sm tabular-nums">{{ row.score.toLocaleString() }}</span>
                    </template>
                    <template v-else>
                      <span class="text-slate-400 text-sm">---</span>
                    </template>
                  </td>
                  <!-- 繧ｹ繧ｳ繧｢繝ｬ繝ｼ繝� -->
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
                      class="font-black text-indigo-600 dark:text-indigo-400 text-sm tabular-nums"
                    >
                      {{ calculatePoints(row.score / selectedRecord.maxScore * 100, selectedRecord.informalRank).toFixed(1) }}
                    </span>
                    <span v-else class="text-slate-400 text-sm">---</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ===== History 繧ｿ繝�: 隴憺擇縺ｮ繧ｹ繧ｳ繧｢譖ｴ譁ｰ螻･豁ｴ繧呈凾邉ｻ蛻苓｡ｨ遉ｺ ===== -->
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
                  <th class="pb-3 pl-3 text-xs font-black text-slate-400 uppercase tracking-widest">{{ t('table.colDate') }}</th>
                  <th class="pb-3 text-xs font-black text-slate-400 uppercase tracking-widest text-right w-28">{{ t('table.exScore') }}</th>
                  <th class="pb-3 pr-3 text-xs font-black text-slate-400 uppercase tracking-widest text-right w-24">BEAT-PT</th>
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
                  <td class="py-3 text-right font-black text-slate-800 dark:text-slate-100 text-sm tabular-nums">
                    {{ entry.score != null ? entry.score.toLocaleString() : '---' }}
                  </td>
                  <td class="py-3 pr-3 text-right font-black text-indigo-600 dark:text-indigo-400 text-sm tabular-nums">
                    {{ entry.beatPt != null ? entry.beatPt.toFixed(1) : '---' }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ===== 繝�ヵ繧ｩ繝ｫ繝茨ｼ�Detail�峨ち繝�: 隴憺擇諠��ｱ + 蜷�せ繧ｳ繧｢繝代ロ繝ｫ + 蛻､螳壼�險ｳ + 謚慕･ｨ + 逶ｮ讓呵ｨ育ｮ� + 繝｡繝｢ ===== -->
        <div v-else class="max-w-4xl mx-auto space-y-4 sm:space-y-8">

          <!-- 隴憺擇繝｡繧ｿ諠��ｱ�磯屮譏灘ｺｦ繝舌ャ繧ｸ + 繧ｿ繧､繝医Ν�� -->
          <div class="flex flex-col items-center sm:items-start gap-2 sm:gap-4">
            <div class="flex flex-wrap gap-2 justify-center sm:justify-start">
              <span :class="['px-3 py-1 sm:px-4 sm:py-1.5 rounded-full text-xs sm:text-sm font-black tracking-wide shadow-sm', selectedRecord.difficultyColor]">
                {{ selectedRecord.difficultyName }} {{ selectedRecord.difficultyLevel ? `笘�${selectedRecord.difficultyLevel}` : '' }}
              </span>
              <span v-if="selectedRecord.informalRank" class="px-3 py-1 sm:px-4 sm:py-1.5 rounded-full text-xs sm:text-sm font-black tracking-wide shadow-sm bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 border border-slate-200 dark:border-slate-700">
                {{ t('table.colInformal') }}: {{ selectedRecord.informalRank }}
              </span>
            </div>
            <h3 class="text-2xl sm:text-5xl font-black text-slate-800 dark:text-slate-100 tracking-tight text-center sm:text-left leading-tight mt-1 sm:mt-0">
              {{ selectedRecord.title }}
            </h3>
          </div>
          <!-- 譛邨ゅ�繝ｬ繝ｼ譌･譎ゅヰ繝�ず -->
          <div class="flex flex-wrap items-center justify-between gap-3">
            <span class="text-xs sm:text-sm font-bold text-slate-500 dark:text-slate-400 border border-slate-300 dark:border-slate-600 px-3 sm:px-4 py-1.5 sm:py-2 rounded-lg bg-white dark:bg-slate-800 shadow-sm transition-colors">
              {{ t('table.lastPlayTime') }}: <span class="text-slate-700 dark:text-slate-200 font-black">{{ selectedRecord.lastPlayTime || t('table.unknown') }}</span>
            </span>
          </div>

          <!-- 繧ｹ繧ｳ繧｢繝代ロ繝ｫ 2ﾃ�2 繧ｰ繝ｪ繝�ラ: 繝ｩ繝ｳ繝� / DJ LEVEL / BEAT-PT / EX 繧ｹ繧ｳ繧｢ + 繧ｹ繧ｳ繧｢繝ｬ繝ｼ繝� -->
          <div class="grid grid-cols-2 gap-3 sm:gap-6">
            <div class="bg-white dark:bg-slate-800 p-3 sm:p-6 rounded-xl sm:rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm flex flex-col items-center justify-center relative overflow-hidden transition-colors duration-200">
              <div class="absolute top-0 left-0 w-full h-1 sm:h-2" :class="getClearTypeBgColor(selectedRecord.clearType)"></div>
              <p class="text-xs sm:text-sm font-bold text-slate-400 dark:text-slate-500 uppercase tracking-widest mb-1 mt-1 sm:mb-2 sm:mt-2">{{ t('table.lamp') }}</p>
              <p class="text-lg sm:text-4xl font-black text-center" :class="getClearTypeColor(selectedRecord.clearType)">
                {{ selectedRecord.clearType }}
              </p>
            </div>
            <div class="bg-white dark:bg-slate-800 p-3 sm:p-6 rounded-xl sm:rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm flex flex-col items-center justify-center relative overflow-hidden transition-colors duration-200">
              <div class="absolute top-0 left-0 w-full h-1 sm:h-2" :class="getDjLevelBgColor(selectedRecord.djLevel)"></div>
              <p class="text-xs sm:text-sm font-bold text-slate-400 dark:text-slate-500 uppercase tracking-widest mb-1 mt-1 sm:mb-2 sm:mt-2">{{ t('table.colRank') }}</p>
              <div class="flex flex-col items-center">
                <p class="text-4xl sm:text-6xl font-black text-center" :class="getDjLevelColor(selectedRecord.djLevel)">
                  {{ selectedRecord.djLevel }}
                </p>
              </div>
            </div>
            
            <div class="bg-indigo-900/10 dark:bg-indigo-900/20 p-4 sm:p-8 rounded-xl sm:rounded-2xl shadow-md flex flex-col items-center justify-center col-span-2 sm:col-span-1 transition-colors duration-200 border border-indigo-100 dark:border-indigo-800/50">
              <p class="text-xs sm:text-sm font-bold text-indigo-500 dark:text-indigo-400 uppercase tracking-widest mb-1 sm:mb-2">BEAT-PT</p>
              <div class="flex items-baseline gap-1 sm:gap-2">
                <p class="text-4xl sm:text-6xl font-black text-indigo-700 dark:text-indigo-300 tracking-tight">
                  {{ selectedRecord.beatTierPoints.toFixed(1) }}
                </p>
                <p v-if="selectedRecord.maxBeatTierPoints > 0" class="text-sm sm:text-xl font-bold text-indigo-400 dark:text-indigo-500">/ {{ selectedRecord.maxBeatTierPoints.toFixed(1) }}</p>
              </div>
            </div>
            
            <div class="bg-slate-800 dark:bg-slate-700 p-4 sm:p-8 rounded-xl sm:rounded-2xl shadow-md flex flex-col items-center justify-center col-span-2 sm:col-span-1 transition-colors duration-200">
              <p class="text-xs sm:text-sm font-bold text-slate-400 dark:text-slate-300 uppercase tracking-widest mb-1 sm:mb-2">{{ t('table.exScore') }}</p>
              <div class="flex items-baseline gap-1 sm:gap-2">
                <p class="text-4xl sm:text-6xl font-black text-white tracking-tight">
                  {{ selectedRecord.score }}
                </p>
                <p v-if="selectedRecord.maxScore > 0" class="text-sm sm:text-xl font-bold text-slate-500 dark:text-slate-400">/ {{ selectedRecord.maxScore }}</p>
              </div>
            </div>
            <div 
               class="p-4 sm:p-8 rounded-xl sm:rounded-2xl flex flex-col items-center justify-center col-span-2 sm:col-span-1 transition-colors relative duration-200"
               :class="selectedRecord.scoreRate >= 0 ? 'bg-blue-50 dark:bg-slate-800 border-4 border-blue-200 dark:border-slate-700' : 'bg-slate-100 dark:bg-slate-800/50 border-dashed border-4 border-slate-300 dark:border-slate-600 group cursor-help'"
               :title="selectedRecord.scoreRate >= 0 ? '' : t('table.rateCalculabilityHint')"
            >
               <p class="text-xs sm:text-sm font-bold uppercase tracking-widest mb-1 sm:mb-2" :class="selectedRecord.scoreRate >= 0 ? 'text-blue-500 dark:text-blue-400' : 'text-slate-500 dark:text-slate-400'">{{ t('table.individualRate') }}</p>
               <p class="text-4xl sm:text-6xl font-black tracking-tight flex items-baseline" :class="selectedRecord.scoreRate >= 0 ? 'text-blue-600 dark:text-blue-300' : 'text-slate-300 dark:text-slate-600 transition-colors group-hover:text-slate-400 dark:group-hover:text-slate-500'">
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

          <!-- 蛻､螳壼�險ｳ: PGREAT / GREAT / MISS 繧� 3 繧ｫ繝ｩ繝�縺ｧ陦ｨ遉ｺ -->
          <div class="border border-slate-200 dark:border-slate-700 rounded-xl sm:rounded-2xl overflow-hidden shadow-sm bg-white dark:bg-slate-800 transition-colors duration-200">
            <div class="bg-slate-100 dark:bg-slate-900/50 px-4 sm:px-6 py-2 sm:py-4 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between transition-colors duration-200">
              <p class="text-xs sm:text-sm font-bold text-slate-600 dark:text-slate-400 uppercase tracking-widest">{{ t('table.judgmentDetail') }}</p>
            </div>
            <div class="grid grid-cols-3 divide-x divide-slate-200 dark:divide-slate-700">
              <div class="p-3 sm:p-6 lg:p-8 flex flex-col items-center justify-center bg-gradient-to-b from-amber-50/50 dark:from-slate-800/50 to-white dark:to-slate-800 transition-colors duration-200">
                <span class="text-[10px] sm:text-sm text-amber-500 dark:text-amber-400 font-bold tracking-widest mb-1 sm:mb-2">PGREAT</span>
                <span class="text-2xl sm:text-5xl font-black text-slate-800 dark:text-slate-200">{{ selectedRecord.pgreat }}</span>
              </div>
              <div class="p-3 sm:p-6 lg:p-8 flex flex-col items-center justify-center bg-gradient-to-b from-yellow-50/50 dark:from-slate-800/50 to-white dark:to-slate-800 transition-colors duration-200">
                <span class="text-[10px] sm:text-sm text-yellow-500 dark:text-yellow-400 font-bold tracking-widest mb-1 sm:mb-2">GREAT</span>
                <span class="text-2xl sm:text-5xl font-black text-slate-800 dark:text-slate-200">{{ selectedRecord.great }}</span>
              </div>
              <div class="p-3 sm:p-6 lg:p-8 flex flex-col items-center justify-center bg-gradient-to-b from-red-50/50 dark:from-slate-800/50 to-white dark:to-slate-800 transition-colors duration-200">
                <span class="text-[10px] sm:text-sm text-red-400 dark:text-red-500 font-bold tracking-widest mb-1 sm:mb-2">MISS</span>
                <span class="text-2xl sm:text-5xl font-black text-slate-800 dark:text-slate-200">{{ selectedRecord.missCount !== null ? selectedRecord.missCount : '-' }}</span>
              </div>
            </div>
          </div>

          <!-- ===== 繧ｪ繝励す繝ｧ繝ｳ謚慕･ｨ繧ｻ繧ｯ繧ｷ繝ｧ繝ｳ�域ｭ｣隕�/MIRROR/RANDOM/R-RAN/S-RAN��===== -->
          <div class="border border-slate-200 dark:border-slate-700 rounded-2xl overflow-hidden shadow-sm bg-white dark:bg-slate-800 mt-6 transition-colors duration-200">
            <div class="bg-emerald-50 dark:bg-emerald-900/30 px-4 sm:px-6 py-3 sm:py-4 border-b border-emerald-100 dark:border-emerald-800/50 flex items-center justify-between transition-colors duration-200">
              <p class="text-xs sm:text-sm font-bold text-emerald-700 dark:text-emerald-400 uppercase tracking-widest flex items-center gap-2">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                  <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
                </svg>
                {{ t('table.optionVote') }}
              </p>
              <span v-if="voteData.totalVotes > 0" class="text-[10px] sm:text-xs font-bold text-emerald-500 dark:text-emerald-400 bg-emerald-100 dark:bg-emerald-900/50 px-2 py-0.5 rounded-full">{{ t('table.voteCount', { n: voteData.totalVotes }) }}</span>
            </div>
            <div class="p-4 sm:p-6">
              <div class="mb-4 p-3 bg-slate-50 dark:bg-slate-800/50 rounded-xl border border-slate-200 dark:border-slate-700/50">
                <p class="text-[11px] sm:text-xs text-slate-600 dark:text-slate-400 leading-relaxed font-medium">
                  {{ t('table.voteHint') }}
                </p>
              </div>
              
              <!-- 謚慕･ｨ繝懊ち繝ｳ�医Ο繧ｰ繧､繝ｳ荳ｭ縺ｮ縺ｿ��: 閾ｪ蛻��逾ｨ縺ｨ荳閾ｴ縺励◆繝懊ち繝ｳ縺ｯ active 濶ｲ縺ｧ蠑ｷ隱ｿ -->
              <div v-if="isLoggedIn" class="flex flex-wrap gap-2 mb-4">
                <button
                  v-for="opt in optionTypes"
                  :key="opt.value"
                  @click="castVote(opt.value)"
                  :disabled="isVoting"
                  class="px-3 py-2 rounded-xl text-xs sm:text-sm font-bold border-2 transition-all flex items-center gap-1.5 disabled:opacity-50"
                  :class="voteData.myVotes.includes(opt.value)
                    ? `${opt.activeBg} ${opt.activeText} ${opt.activeBorder} shadow-sm`
                    : 'bg-slate-50 dark:bg-slate-900 text-slate-600 dark:text-slate-400 border-slate-200 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800'"
                >
                  <span>{{ opt.icon }}</span>
                  {{ opt.label }}
                  <span v-if="voteData.myVotes.includes(opt.value)" class="text-[10px]">笨�</span>
                </button>
              </div>
              <div v-else class="mb-4 p-3 bg-slate-50 dark:bg-slate-900 rounded-xl text-sm text-slate-500 dark:text-slate-400 italic text-center">
                {{ t('table.loginToVote') }}
              </div>
              
              <!-- 謚慕･ｨ邨先棡縺ｮ繝舌�繝√Ε繝ｼ繝郁｡ｨ遉ｺ�亥推繧ｪ繝励す繝ｧ繝ｳ縺ｮ逾ｨ謨ｰ縺ｨ蜑ｲ蜷茨ｼ� -->
              <div v-if="voteData.totalVotes > 0" class="space-y-2">
                <div v-for="opt in optionTypes" :key="opt.value" class="flex items-center gap-2">
                  <span class="text-[10px] sm:text-xs font-bold w-20 sm:w-24 text-right shrink-0" :class="opt.labelColor">{{ opt.label }}</span>
                  <div class="flex-1 h-6 sm:h-7 bg-slate-100 dark:bg-slate-700 rounded-lg overflow-hidden relative">
                    <div
                      class="h-full rounded-lg transition-all duration-500 flex items-center justify-end pr-2"
                      :class="opt.barColor"
                      :style="{ width: `${getVotePercent(opt.value)}%`, minWidth: (voteData.counts[opt.value] || 0) > 0 ? '24px' : '0px' }"
                    >
                      <span v-if="(voteData.counts[opt.value] || 0) > 0" class="text-[10px] sm:text-xs font-black text-white drop-shadow-sm">{{ voteData.counts[opt.value] }}</span>
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

          <!-- ===== BEAT-PT 逶ｮ讓呵ｨ育ｮ励そ繧ｯ繧ｷ繝ｧ繝ｳ: 繧ｹ繝ｩ繧､繝繝ｼ縺ｧ逶ｮ讓� PT 蠅怜�繧帝∈縺ｶ縺ｨ縲∝ｿ�ｦ√せ繧ｳ繧｢繧帝�ｮ励＠縺ｦ陦ｨ遉ｺ ===== -->
          <div v-if="selectedRecord.maxScore > 0 && selectedRecord.maxBeatTierPoints > 0 && selectedRecord.beatTierPoints < selectedRecord.maxBeatTierPoints" class="border border-slate-200 dark:border-slate-700 rounded-2xl overflow-hidden shadow-sm bg-white dark:bg-slate-800 mt-6 transition-colors duration-200">
            <div class="bg-indigo-50 dark:bg-indigo-900/30 px-6 py-4 border-b border-indigo-100 dark:border-indigo-800/50 flex items-center justify-between transition-colors duration-200">
              <p class="text-sm font-bold text-indigo-700 dark:text-indigo-400 uppercase tracking-widest flex items-center gap-2">
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
                    <span class="text-2xl font-black text-indigo-600 dark:text-indigo-400">+{{ targetBeatPtSlider.toFixed(1) }} <span class="text-sm">pt</span></span>
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
                
                <div v-if="targetBeatPtSlider > 0" class="mt-4 p-4 bg-indigo-50 dark:bg-indigo-900/20 rounded-xl border border-indigo-100 dark:border-indigo-800/50 flex flex-col items-center justify-center text-center">
                  <p class="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">{{ t('scores.targetScoreNeededLabel') }}</p>
                  <p class="text-4xl font-black text-indigo-700 dark:text-indigo-400 flex items-baseline gap-1">
                    {{ t('scores.targetScoreNeededValue', { n: targetScoreNeeded }) }}
                  </p>
                  <p class="text-xs font-medium text-indigo-500 dark:text-indigo-400 mt-2">
                    {{ t('scores.targetFinalScore', { score: selectedRecord.score + targetScoreNeeded, max: selectedRecord.maxScore, rate: (((selectedRecord.score + targetScoreNeeded) / selectedRecord.maxScore) * 100).toFixed(2) }) }}
                  </p>
                </div>
              </div>
            </div>
          </div>

          <!-- ===== 繝｡繝｢ 繧ｻ繧ｯ繧ｷ繝ｧ繝ｳ: 隴憺擇縺斐→縺ｮ繝輔Μ繝ｼ繝�く繧ｹ繝医Γ繝｢�医Ο繧ｰ繧､繝ｳ譎ゅ�縺ｿ邱ｨ髮�庄�� ===== -->
          <div v-if="selectedRecord.id || !isLoggedIn" class="border border-slate-200 dark:border-slate-700 rounded-2xl overflow-hidden shadow-sm bg-white dark:bg-slate-800 mt-6 transition-colors duration-200">
            <div class="bg-slate-100 dark:bg-slate-900/50 px-6 py-4 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between transition-colors duration-200">
              <p class="text-sm font-bold text-slate-600 dark:text-slate-400 uppercase tracking-widest">{{ t('scores.options') }}</p>
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
                        class="inline-flex items-center px-3 py-1 rounded-full text-sm font-bold bg-blue-50 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300 border border-blue-200 dark:border-blue-800">
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
      
      <!-- ===== 繝｢繝ｼ繝繝ｫ Sticky 繝輔ャ繧ｿ: 縲御ｸ隕ｧ縺ｫ謌ｻ繧九阪�繧ｿ繝ｳ�亥�繧ｿ繝門�騾夲ｼ� ===== -->
      <div class="sticky bottom-0 bg-white dark:bg-slate-900 border-t border-slate-200 dark:border-slate-800 p-4 sm:p-6 shadow-[0_-10px_20px_-10px_rgba(0,0,0,0.05)] dark:shadow-[0_-10px_20px_-10px_rgba(0,0,0,0.2)] w-full flex justify-center z-10 transition-colors duration-200">
         <button @click="closeDetailModal" class="w-full max-w-md px-8 py-4 bg-blue-600 hover:bg-blue-700 text-white text-lg font-bold rounded-2xl shadow-lg transition-colors flex items-center justify-center gap-2">
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
 * 縲舌さ繝ｳ繝昴�繝阪Φ繝医�蠖ｹ蜑ｲ縲� 譖ｲ蛻･繧ｹ繧ｳ繧｢荳隕ｧ逕ｻ髱｢縺ｮ荳ｭ譬ｸ縲ゅユ繝ｼ繝悶Ν謠冗判繝ｻ繝輔ぅ繝ｫ繧ｿ繝ｻ荳ｦ縺ｳ譖ｿ縺医�隧ｳ邏ｰ繝｢繝ｼ繝繝ｫ繧偵☆縺ｹ縺ｦ諡�ｽ薙☆繧九�
 *
 * 逕ｻ髱｢蜈ｨ菴捺ｧ矩�:
 *  - 荳頑ｮｵ繝輔ぅ繝ｫ繧ｿ: 繝ｬ繝吶Ν / 髮｣譏灘ｺｦ / DJ LEVEL / 繧ｯ繝ｪ繧｢繝ｩ繝ｳ繝� / 讀懃ｴ｢隱� / 0 轤ｹ髱櫁｡ｨ遉ｺ
 *  - 繝｢繝ｼ繝牙�譖ｿ: BEAT-TIER 繝｢繝ｼ繝峨→ RATE-TIER 繝｢繝ｼ繝峨�繧ｿ繝厄ｼ�RATE 讖溯�縺ｯ composable 縺ｧ蛻ｶ蠕｡��
 *  - 繝��繧ｿ繝��繝悶Ν: 譖ｲ繝ｻ隴憺擇繝ｻ繧ｹ繧ｳ繧｢繝ｬ繝ｼ繝医�BEAT-PT�医∪縺溘� RATE-PT�峨ｒ荳隕ｧ陦ｨ遉ｺ
 *      - BEAT-TIER: TOP100 繝上う繝ｩ繧､繝茨ｼ九後≠縺ｨ菴慕せ縺ｧ TOP100 縺ｫ蜈･繧後ｋ縺九阪ｒ陦ｨ遉ｺ
 *      - RATE-TIER: RATE-PT 縺ｮ TOP100 繝上う繝ｩ繧､繝茨ｼ九ヱ繝ｼ繝輔ぉ繧ｯ繝郁ｶ�℃譎ゅ�蠑ｷ隱ｿ
 *  - 繝壹�繧ｸ繝阪�繧ｷ繝ｧ繝ｳ: 10/25/50/100 莉ｶ蛻�崛
 *  - 隧ｳ邏ｰ繝｢繝ｼ繝繝ｫ�医ヵ繝ｫ繧ｹ繧ｯ繝ｪ繝ｼ繝ｳ Teleport��:
 *      - 隧ｳ邏ｰ繧ｿ繝�: 繝ｩ繝ｳ繝�/AAA/PGREAT/GREAT/MISS 遲峨�邏ｰ縺九＞繧ｹ繝��繧ｿ繧ｹ
 *      - Rate-Tier 繧ｿ繝�: 髢ｾ蛟､繝��繝悶Ν
 *      - 繝ｩ繝ｳ繧ｭ繝ｳ繧ｰ繧ｿ繝�: 閾ｪ蛻�+繝輔Ξ繝ｳ繝�+蜈ｬ髢�+莉ｮ諠ｳ(TOP繝ｩ繝ｳ繧ｫ繝ｼ) 繧偵�繝ｼ繧ｸ縺励◆繝槭Ν繝√た繝ｼ繧ｹ鬆�ｽ崎｡ｨ
 *      - 螻･豁ｴ繧ｿ繝�: 閾ｪ蛻��繧｢繝��繝ｭ繝ｼ繝牙ｱ･豁ｴ縺九ｉ繧ｹ繧ｳ繧｢謗ｨ遘ｻ繧定｡ｨ遉ｺ
 *      - 繧ｪ繝励す繝ｧ繝ｳ謚慕･ｨ繝ｻ繝｡繝｢邱ｨ髮��逶ｮ讓儕T髮ｻ蜊� 繧ゅΔ繝ｼ繝繝ｫ蜀�↓蜿朱鹸
 *
 * Props:
 *  - scores: 逕ｻ髱｢螟悶°繧画ｸ｡縺輔ｌ繧九せ繧ｳ繧｢繝��繧ｿ�域峇蜊倅ｽ阪�驟榊���
 *  - viewingMode: 莉悶Θ繝ｼ繧ｶ繝ｼ髢ｲ隕ｧ繝｢繝ｼ繝峨�'topRanker' 縺ｮ縺ｨ縺阪� djName 遲峨�莉ｮ諠ｳ繝ｦ繝ｼ繧ｶ繝ｼ陦ｨ遉ｺ繧定ｿｽ蜉�縺吶ｋ
 *
 * Emits:
 *  - reset: 隕ｪ繧ｳ繝ｳ繝昴�繝阪Φ繝医↓縲後ｄ繧顔峩縺�/蜀榊叙繧願ｾｼ縺ｿ縲阪ｒ隕∵ｱ�
 *  - update:totalPoints: 邱� BEAT-PT 繧定ｦｪ縺ｸ騾夂衍��TOP100 蜷郁ｨ茨ｼ�
 *  - view-user: 繝��繝悶Ν陦�/繝ｩ繝ｳ繧ｭ繝ｳ繧ｰ陦後°繧牙�髢九Θ繝ｼ繧ｶ繝ｼ髢ｲ隕ｧ縺ｸ驕ｷ遘ｻ
 *  - view-top-ranker: TOP 繝ｩ繝ｳ繧ｫ繝ｼ�井ｻｮ諠ｳ繝ｦ繝ｼ繧ｶ繝ｼ�蛾夢隕ｧ縺ｸ驕ｷ遘ｻ
 *
 * 萓晏ｭ� Composable:
 *  - `useScores`: 繝｡繝｢譖ｴ譁ｰ API
 *  - `useDarkMode`: 繝繝ｼ繧ｯ繝｢繝ｼ繝牙愛螳夲ｼ郁牡蛻�ｲ舌〒蜿ら���
 *  - `useAuth`: 隱崎ｨｼ繝ｻ繝倥ャ繝莉倅ｸ�
 *  - `useRateTierVisibility`: RATE-TIER 讖溯�縺ｮ ON/OFF 繝輔Λ繧ｰ�医し繝ｼ繝舌�蛛ｴ縺ｮ谿ｵ髫守噪繝ｪ繝ｪ繝ｼ繧ｹ蟇ｾ蠢懶ｼ�
 *  - `useGameData`: song_data.json / 髮｣譏灘ｺｦ陦ｨ繝槭せ繧ｿ繝ｼ
 */
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { useI18n } from '../composables/useI18n';
import type { ScoreData } from '../types/ScoreData';
import { flattenScores, type ScoreRecord } from '../utils/scoreData';
import { songData as songDataBodyRef, diffTable as diffTableRanksRef } from '../composables/useGameData';
import { calculatePoints, getMaxPoints, getRankInfo, calculateScoreRateTierPoints, SCORE_RATE_THRESHOLDS, getChartType, getFolderRankInfoByRate, FOLDER_RANK_DEFS, type ChartType, type RankInfo } from '../utils/beatTier';
import { useScratchSummary } from '../composables/useScratchSummary';
import { useScores } from '../composables/useScores';
import { useDarkMode } from '../composables/useDarkMode';
import { useAuth } from '../composables/useAuth';
import { useAdmin } from '../composables/useAdmin';
import { useRateTierVisibility } from '../composables/useRateTierVisibility';
import { useKenbanSaraTierVisibility } from '../composables/useKenbanSaraTierVisibility';
import { useFriends } from '../composables/useFriends';
import { DJ_LEVELS } from '../composables/constants';
import RankIcon from './RankIcon.vue';
import InformalRankBadge from './InformalRankBadge.vue';
import { Bar as BarChart } from 'vue-chartjs';
import { Chart as ChartJS, CategoryScale, LinearScale, BarElement, BarController, Tooltip, Legend } from 'chart.js';

ChartJS.register(CategoryScale, LinearScale, BarElement, BarController, Tooltip, Legend);

/** 髮｣譏灘ｺｦ繝輔ぅ繝ｫ繧ｿ縺ｮ驕ｸ謚櫁い��ANOTHER / LEGGENDARIA 縺ｫ蝗ｺ螳壹�BEAT-PT 髮�ｨ亥ｯｾ雎｡�峨� */
const DIFFICULTY_FILTER_OPTIONS = ['ANOTHER', 'LEGGENDARIA'] as const;

const { isDarkMode } = useDarkMode();
const { isLoggedIn, authHeaders, user } = useAuth();
/** 邂｡逅��愛螳壹ょ愛螳壹Ο繧ｸ繝�け縺ｯ useAdmin composable 縺ｫ髮�ｴ�＆繧後※縺�ｋ縲� */
const { isAdmin } = useAdmin();

/** API 繝吶�繧ｹ URL縲よ悴險ｭ螳壽凾縺ｯ繝ｭ繝ｼ繧ｫ繝ｫ髢狗匱逕ｨ縺ｮ繝�ヵ繧ｩ繝ｫ繝医� */
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

const props = defineProps<{
  scores: ScoreData[];
  viewingMode?: 'admin' | 'friend' | 'public' | 'topRanker' | null;
}>();

// emit 縺ｮ螳夂ｾｩ縺ｯ totalBeatTierPoints 縺ｮ螳夂ｾｩ逶ｴ蠕後↓縺ｾ縺ｨ繧√ｋ�亥盾辣ｧ鬆��驛ｽ蜷茨ｼ�

const { showRateTier } = useRateTierVisibility();
const { t } = useI18n();
/** 迴ｾ蝨ｨ縺ｮ繝｢繝ｼ繝峨�'beat' 縺ｯ BEAT-TIER縲�'rate' 縺ｯ RATE-TIER 陦ｨ遉ｺ縲� */
const viewMode = ref<'beat' | 'rate'>('beat');

/** 隴憺擇蛻･繝ｩ繝ｳ繧ｭ繝ｳ繧ｰ繝槭ャ繝励ゅく繝ｼ縺ｯ `title|difficultyName`縲り�蛻�′蜈ｨ繝ｦ繝ｼ繧ｶ繝ｼ荳ｭ菴穂ｽ阪°繧呈�ｼ邏阪☆繧九� */
const songRankMap = ref<Map<string, { rank: number; total: number }>>(new Map());

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 閾ｪ蛻��隴憺擇蛻･鬆�ｽ阪ｒ繝舌ャ繧ｯ繧ｨ繝ｳ繝峨°繧牙叙蠕励＠縲～songRankMap` 縺ｫ隧ｰ繧∵崛縺医ｋ縲�
 * 譛ｪ繝ｭ繧ｰ繧､繝ｳ縺ｪ繧我ｽ輔ｂ縺励↑縺�ゅお繝ｩ繝ｼ縺ｯ謠｡繧頑ｽｰ縺励※ UI 繧貞｣翫＆縺ｪ縺��
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
  } catch { /* 謠｡繧頑ｽｰ縺� */ }
};
// 縲陣atch縲� RATE-TIER 讖溯�繝輔Λ繧ｰ縺� OFF 縺ｫ蛻�ｊ譖ｿ繧上▲縺溽椪髢薙ヽATE 繝｢繝ｼ繝芽｡ｨ遉ｺ縺ｪ繧� BEAT 繝｢繝ｼ繝峨∈閾ｪ蜍募ｾｩ蟶ｰ縲�
watch(showRateTier, (val) => { if (!val && viewMode.value === 'rate') viewMode.value = 'beat'; });


/** 繝�く繧ｹ繝域､懃ｴ｢繧ｯ繧ｨ繝ｪ縲ゅち繧､繝医Ν/繧｢繝ｼ繝�ぅ繧ｹ繝�/繧ｸ繝｣繝ｳ繝ｫ/繝ｩ繝ｳ繝励↓蟇ｾ縺励※驛ｨ蛻�ｸ閾ｴ縺吶ｋ縲� */
const searchQuery = ref('');
/** 髮｣譏灘ｺｦ繝輔ぅ繝ｫ繧ｿ��'ANOTHER' / 'LEGGENDARIA' 縺ｮ螟夐∈謚橸ｼ峨らｩｺ驟榊�縺ｯ縲悟�縺ｦ縲阪� */
const filterDifficulty = ref<string[]>([]);
/** 繝ｬ繝吶Ν繝輔ぅ繝ｫ繧ｿ��'11' / '12' 縺ｪ縺ｩ縲３ATE 繝｢繝ｼ繝峨〒縺ｯ 1縲�12�峨らｩｺ驟榊�縺ｯ縲悟�縺ｦ縲阪� */
const filterLevel = ref<string[]>([]);
/** DJ LEVEL 繝輔ぅ繝ｫ繧ｿ��'AAA' 縲� 'F'�峨らｩｺ驟榊�縺ｯ縲悟�縺ｦ縲阪� */
const filterDjLevel = ref<string[]>([]);
/** 繧ｯ繝ｪ繧｢繝ｩ繝ｳ繝励ヵ繧｣繝ｫ繧ｿ��'FULLCOMBO CLEAR' 縺ｪ縺ｩ�峨らｩｺ驟榊�縺ｯ縲悟�縺ｦ縲阪� */
const filterClearType = ref<string[]>([]);
/** 蜿門ｾ怜�繝輔ぅ繝ｫ繧ｿ��'infinitas' / 'arcade'�峨らｩｺ驟榊�縺ｯ縲悟�縺ｦ縲阪� */
const filterSource = ref<string[]>([]);
/** 0 轤ｹ隴憺擇繧帝撼陦ｨ遉ｺ縺ｫ縺吶ｋ繝医げ繝ｫ縲よ悴繝励Ξ繧､譖ｲ繧帝國縺励◆縺��ｴ蜷医↓菴ｿ縺�� */
const hideZeroScore = ref(false);

/** 迴ｾ蝨ｨ髢九＞縺ｦ縺�ｋ繝峨Ο繝��繝繧ｦ繝ｳ蜷阪Ｏull 縺ｯ髢峨§縺溽憾諷九� */
const openDropdown = ref<string | null>(null);

/** 謖�ｮ壹ラ繝ｭ繝��繝繧ｦ繝ｳ繧帝幕髢峨☆繧九よ里縺ｫ髢九＞縺ｦ縺�ｌ縺ｰ髢峨§繧九� */
const toggleDropdown = (name: string) => {
  openDropdown.value = openDropdown.value === name ? null : name;
};

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 繝輔ぅ繝ｫ繧ｿ驟榊�縺ｫ蟇ｾ縺吶ｋ蛟､縺ｮ繝医げ繝ｫ謫堺ｽ懊�
 * 驟榊�縺ｫ蛟､縺後≠繧後�蜿悶ｊ髯､縺阪∫┌縺代ｌ縺ｰ譛ｫ蟆ｾ縺ｫ霑ｽ蜉�縺吶ｋ縲�
 */
const toggleFilterValue = (arr: string[], value: string) => {
  const index = arr.indexOf(value);
  if (index === -1) {
    arr.push(value);
  } else {
    arr.splice(index, 1);
  }
};

/** 繝√ぉ繝�け繝懊ャ繧ｯ繧ｹ縺ｮ驕ｸ謚樒憾諷句愛螳夂畑繝倥Ν繝代� */
const isSelected = (arr: string[], value: string) => {
  return arr.includes(value);
};

/**
 * 縲芯omputed 縺ｮ蠖ｹ蜑ｲ縲� 迴ｾ蝨ｨ驕ｩ逕ｨ荳ｭ縺ｮ繝輔ぅ繝ｫ繧ｿ繧偵メ繝��陦ｨ遉ｺ逕ｨ縺ｮ驟榊�縺ｫ豁｣隕丞喧縺吶ｋ縲�
 *
 * 蜷�お繝ｳ繝医Μ縺ｯ `{ id, label, remove }` 縺ｮ蠖｢縺ｧ縲√メ繝��縺ｮ `ﾃ輿 繝懊ち繝ｳ縺ｧ `remove()` 繧貞他縺ｶ縺ｨ
 * 隧ｲ蠖薙ヵ繧｣繝ｫ繧ｿ縺�縺代′隗｣髯､縺輔ｌ繧九ゅヵ繧｣繝ｫ繧ｿ縺御ｽ輔ｂ縺九°縺｣縺ｦ縺�↑縺代ｌ縺ｰ遨ｺ驟榊�繧定ｿ斐＠縲�
 * 繝√ャ繝苓｡後→縺昴�縲悟�繧ｯ繝ｪ繧｢縲阪�繧ｿ繝ｳ閾ｪ菴薙ｒ髱櫁｡ｨ遉ｺ縺ｫ縺ｧ縺阪ｋ縲�
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
      label: `笘�${lv}`,
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

/** 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 蜈ｨ繝輔ぅ繝ｫ繧ｿ繧剃ｸ諡ｬ隗｣髯､縺吶ｋ縲よ､懃ｴ｢繝懊ャ繧ｯ繧ｹ縺ｨ縲�0轤ｹ髱櫁｡ｨ遉ｺ縲阪ヨ繧ｰ繝ｫ繧ょ�譛溽憾諷九↓謌ｻ縺吶� */
const clearAllFilters = () => {
  searchQuery.value = '';
  filterLevel.value = [];
  filterDifficulty.value = [];
  filterDjLevel.value = [];
  filterClearType.value = [];
  filterSource.value = [];
  hideZeroScore.value = false;
};

/** 迴ｾ蝨ｨ縺ｮ繝壹�繧ｸ逡ｪ蜿ｷ��1 蟋九∪繧奇ｼ峨� */
const currentPage = ref(1);
/** 1 繝壹�繧ｸ縺ゅ◆繧翫�陦ｨ遉ｺ莉ｶ謨ｰ縲�10/25/50/100 縺九ｉ驕ｸ謚槫庄閭ｽ縲� */
const itemsPerPage = ref(50);

type SortKey = 'title' | 'clearType' | 'scoreRate' | 'informalRank' | 'difficultyLevel' | 'djLevel' | 'beatTierPoints' | 'songRank' | 'unofficialSongRank' | null;
type SortOrder = 'asc' | 'desc';

/** 迴ｾ蝨ｨ縺ｮ荳ｦ縺ｳ譖ｿ縺医く繝ｼ縲ょ�譛溷､縺ｯ縲碁撼蜈ｬ蠑城屮譏灘ｺｦ��informalRank�峨埼剄鬆�� */
const sortKey = ref<SortKey>('informalRank');
/** 荳ｦ縺ｳ譖ｿ縺磯��Ｂsc/desc縲� */
const sortOrder = ref<SortOrder>('desc');

/**
 * 繧ｯ繝ｪ繧｢繝ｩ繝ｳ繝励�蠑ｷ蠑ｱ繧呈焚蛟､蛹悶＠縺溘ユ繝ｼ繝悶Ν縲ゆｸｦ縺ｳ譖ｿ縺域凾縺ｫ菴ｿ縺��
 * 螟ｧ縺阪＞縺ｻ縺ｩ縲御ｸ贋ｽ阪Λ繝ｳ繝励搾ｼ�FULLCOMBO > EX HARD > HARD > CLEAR > ...�峨�
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
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 蜊俶峇縺ｮ繝ｬ繝ｼ繝医→謇螻樣屮譏灘ｺｦ縺九ｉ縲∝ｿ�ｦ√せ繧ｳ繧｢繝ｬ繝ｼ繝郁｡ｨ縺ｫ蟇ｾ蠢懊☆繧九Λ繝ｳ繧ｯ繧定ｿ斐☆縲�
 * 譛ｪ繝励Ξ繧､��scoreRate <= 0�峨∪縺溘�髱槫�蠑上Λ繝ｳ繧ｯ辟｡縺励�譖ｲ縺ｯ null縲�
 * Novice 1 縺ｫ繧ょｱ翫°縺ｪ縺�せ繧ｳ繧｢縺ｯ Beginner 縺ｨ縺励※陦ｨ遉ｺ縺吶ｋ��null 縺ｫ縺ｯ縺励↑縺�ｼ峨�
 */
const getSongUnofficialRank = (record: ScoreRecord): RankInfo | null => {
  if (record.scoreRate <= 0 || !record.informalRank) return null;
  return getFolderRankInfoByRate(record.scoreRate, record.informalRank);
};

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� RankInfo 繧偵鍬egend 縺梧怙螟ｧ縲。eginner 縺梧怙蟆上阪�謨ｰ蛟､縺ｫ螟画鋤縺吶ｋ縲�
 * FOLDER_RANK_DEFS 縺ｮ荳ｦ縺ｳ�亥�鬆ｭ縺� Legend�峨ｒ騾���ｺ上→縺励※菴ｿ縺�∵悴隧ｲ蠖薙� -1 繧定ｿ斐☆縲�
 */
const getRankOrderValue = (info: RankInfo | null): number => {
  if (!info) return -1;
  const idx = FOLDER_RANK_DEFS.findIndex(d => d.name === info.name && (d.tier ?? null) === (info.tier ?? null));
  return idx === -1 ? -1 : (FOLDER_RANK_DEFS.length - idx);
};

/**
 * 縲芯omputed縲� BEAT-TIER 繝｢繝ｼ繝臥畑縺ｮ縲娯�11/笘�12 縺ｮ ANOTHER/LEGGENDARIA 蜈ｨ隴憺擇縲埼�蛻励�
 *
 * 蜃ｦ逅��豬√ｌ:
 *  謇矩��1: 貂｡縺輔ｌ縺� props.scores 繧偵ヵ繝ｩ繝�ヨ蛹悶＠縲∝ｯｾ雎｡隴憺擇縺�縺第歓蜃ｺ�医�繝ｬ繧､貂医∩�峨�
 *  謇矩��2: 繧ｿ繧､繝医Ν+髮｣譏灘ｺｦ繧偵く繝ｼ縺ｫ繝励Ξ繧､貂医∩繝槭ャ繝励ｒ讒狗ｯ峨�
 *  謇矩��3: 髮｣譏灘ｺｦ陦ｨ縺九ｉ髱槫�蠑上Λ繝ｳ繧ｯ繧定ｾ樊嶌蛹厄ｼ域悴繝励Ξ繧､譖ｲ縺ｧ繧� informalRank 繧剃ｻ倥￠繧九◆繧�ｼ峨�
 *  謇矩��4: song_data.json 縺ｮ蜈ｨ隴憺擇繧偵Ν繝ｼ繝励＠縲√�繝ｬ繧､貂医∩縺ｪ繧画里蟄倥Ξ繧ｳ繝ｼ繝峨ｒ霑斐＠縲∵悴繝励Ξ繧､縺ｪ繧�
 *         score=0 縺ｮ繝繝溘�繝ｬ繧ｳ繝ｼ繝峨ｒ逕滓�縺励※霑斐☆�医ユ繝ｼ繝悶Ν荳翫梧悴繝励Ξ繧､縲阪→縺励※陦ｨ遉ｺ縺吶ｋ縺溘ａ�峨�
 */
const allRecords = computed<ScoreRecord[]>(() => {
  // 謇矩��1: 繝ｦ繝ｼ繧ｶ繝ｼ縺ｮ繝励Ξ繧､貂医∩繧ｹ繧ｳ繧｢縺九ｉ 笘�11/笘�12 ANOTHER/LEGGENDARIA 縺ｮ縺ｿ謚ｽ蜃ｺ
  const playedRecords = flattenScores(props.scores).filter(r =>
    r.difficultyLevel &&
    r.difficultyLevel >= 11 &&
    ['ANOTHER', 'LEGGENDARIA'].includes(r.difficultyName)
  );

  // 謇矩��2: 繧ｿ繧､繝医Ν+髮｣譏灘ｺｦ繧偵く繝ｼ縺ｫ縺吶ｋ Map 繧堤ｵ�ｓ縺ｧ O(1) 縺ｧ蠑輔￠繧九ｈ縺�↓縺吶ｋ
  const playedMap = new Map<string, ScoreRecord>();
  playedRecords.forEach(r => playedMap.set(`${r.title}|${r.difficultyName}`, r));

  // 謇矩��3: 譛ｪ繝励Ξ繧､譖ｲ縺ｫ繧る撼蜈ｬ蠑上Λ繝ｳ繧ｯ繧剃ｻ倥￠縺溘＞縺ｮ縺ｧ縲�屮譏灘ｺｦ陦ｨ繝槭せ繧ｿ繝ｼ縺九ｉ霎樊嶌蛹悶☆繧�
  //        譖ｲ蜷肴忰蟆ｾ '[L]' 縺ｯ LEGGENDARIA 謖�ｮ壹ゅ◎繧御ｻ･螟悶� ANOTHER 謇ｱ縺��
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

  // 謇矩��4: song_data.json 蛛ｴ縺ｧ螳夂ｾｩ縺輔ｌ縺ｦ縺�ｋ蜈ｨ隴憺擇繧定ｵｰ譟ｻ縺励※縲∵悴繝励Ξ繧､縺ｪ繧臥ｩｺ繝ｬ繧ｳ繝ｼ繝峨ｒ逕滓�縺吶ｋ縲�
  //        difficulty "4" = ANOTHER縲�"10" = LEGGENDARIA 縺ｫ蟇ｾ蠢懊�
  const difMap: Record<string, string> = { "4": "ANOTHER", "10": "LEGGENDARIA" };
  const baseRecords: ScoreRecord[] = (songDataBodyRef.value as any[])
    .filter((s: any) => s.level >= 11 && (s.difficulty === "4" || s.difficulty === "10"))
    .map((s: any) => {
      const diffName = difMap[s.difficulty];
      const key = `${s.title}|${diffName}`;
      
      // 繝励Ξ繧､貂医∩縺ｪ繧峨◎縺ｮ縺ｾ縺ｾ譌｢蟄倥�繝ｬ繧ｳ繝ｼ繝峨ｒ謗｡逕ｨ縲�
      if (playedMap.has(key)) {
        return playedMap.get(key)!;
      }

      // 譛ｪ繝励Ξ繧､縺ｪ繧芽ｾ樊嶌縺九ｉ髱槫�蠑上Λ繝ｳ繧ｯ繧貞ｼ輔￥�郁ｦ九▽縺九ｉ縺ｪ縺��ｴ蜷医� undefined 縺ｮ縺ｾ縺ｾ��
      const informalKey = `${s.title}_${diffName}`;
      let informalRank = informalDict.get(informalKey);
      if (!informalRank && diffName === 'ANOTHER') {
          informalRank = informalDict.get(`${s.title}_ANOTHER`);
      }

      // 譛ｪ繝励Ξ繧､隴憺擇縺ｮ遨ｺ繝ｬ繧ｳ繝ｼ繝峨ｒ逕滓���score=0 / clearType='NO PLAY'�峨�
      // maxScore 縺ｯ notes * 2��IIDX 縺ｮ EX 繧ｹ繧ｳ繧｢逅�ｫ門､�峨〒險育ｮ励☆繧九�
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

/** INFINITAS 蜿門ｾ励せ繧ｳ繧｢縺� 1 莉ｶ縺ｧ繧ょｭ伜惠縺吶ｋ縺九ょ叙蠕怜�繝輔ぅ繝ｫ繧ｿ縺ｮ陦ｨ遉ｺ隕∝凄縺ｫ菴ｿ縺�� */
const hasInfinitasScores = computed(() => allRecords.value.some(r => r.source === 'infinitas'));

const emit = defineEmits<{
  (e: 'reset'): void;
  (e: 'update:totalPoints', points: number): void;
  (e: 'view-user', payload: { id: number; displayName: string; iidxId: string }): void;
  (e: 'view-top-ranker', payload: { versionNum: number; versionName: string; prefectureFileNum: number; prefectureName: string }): void;
}>();

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 繝ｩ繝ｳ繧ｭ繝ｳ繧ｰ陦後�縺�■螳溷惠繝ｦ繝ｼ繧ｶ繝ｼ陦後ｒ繧ｯ繝ｪ繝�け縺励◆縺ｨ縺阪�驕ｷ遘ｻ繝上Φ繝峨Λ縲�
 *  - userId 縺檎┌縺�ｼ磯壻ｿ｡繧ｨ繝ｩ繝ｼ遲会ｼ峨↑繧我ｽ輔ｂ縺励↑縺�
 *  - privacyLevel 縺� 0 莉･螟厄ｼ�= 髱槫�髢� / 繝輔Ξ繝ｳ繝蛾剞螳夲ｼ峨�繧ｯ繝ｪ繝�け荳榊庄縺ｨ縺吶ｋ�郁ｪ､繧ｿ繝��縺ｧ驕ｷ遘ｻ縺輔○縺ｪ縺�ｼ�
 */
function handleSongUserRowClick(entry: SongRankingEntry) {
  if (!entry.userId) return;
  const priv = entry.privacyLevel ?? 1;
  if (priv !== 0) return;
  emit('view-user', { id: entry.userId, displayName: entry.displayName, iidxId: entry.iidxId ?? '' });
}

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 繝ｩ繝ｳ繧ｭ繝ｳ繧ｰ陦後�縺�■莉ｮ諠ｳ TOP 繝ｩ繝ｳ繧ｫ繝ｼ陦後ｒ繧ｯ繝ｪ繝�け縺励◆縺ｨ縺阪�驕ｷ遘ｻ繝上Φ繝峨Λ縲�
 * 隕ｪ繧ｳ繝ｳ繝昴�繝阪Φ繝茨ｼ�App.vue�峨∈ view-top-ranker 繧､繝吶Φ繝医ｒ荳翫￡縲√お繝ｪ繧｢諠��ｱ繧呈ｸ｡縺吶�
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
 * 縲芯omputed縲� BEAT-PT 邱丞粋蛟､縲ょ�隴憺擇縺九ｉ BEAT-PT 髯埼��〒荳ｦ縺ｹ縲∽ｸ贋ｽ� 100 隴憺擇繧貞粋險医＠縺ｦ霑斐☆縲�
 * 縺薙ｌ縺� BEAT-Tier 谿ｵ菴榊愛螳壹�蜈�､縺ｫ縺ｪ繧九�
 */
const totalBeatTierPoints = computed(() => {
    // 蜈ｨ隴憺擇繧� BEAT-PT 髯埼��〒繧ｽ繝ｼ繝医＠縲∽ｸ贋ｽ� 100 隴憺擇縺ｮ蜷郁ｨ医ｒ邂怜�
    const sorted = [...allRecords.value].sort((a, b) => b.beatTierPoints - a.beatTierPoints);
    const top100 = sorted.slice(0, 100);
    return top100.reduce((acc, curr) => acc + curr.beatTierPoints, 0);
});

// 縲陣atch縲� BEAT-PT 邱丞粋蛟､縺悟､峨ｏ繧九◆縺ｳ縺ｫ隕ｪ縺ｸ emit 縺吶ｋ縲�
// immediate: true 縺ｧ繝槭え繝ｳ繝育峩蠕後�蛻晄悄蛟､繧りｦｪ縺ｫ莨昴∴繧九�
watch(totalBeatTierPoints, (newVal) => {
    emit('update:totalPoints', newVal);
}, { immediate: true });

/**
 * 縲芯omputed縲� BEAT-TIER 縺ｮ TOP100 隴憺擇繧ｭ繝ｼ髮�粋縲ゅワ繧､繝ｩ繧､繝郁｡ｨ遉ｺ逕ｨ縺ｫ Set 蛹悶�
 * 繧ｭ繝ｼ縺ｯ `title|difficultyName` 蠖｢蠑上�
 */
const top100Keys = computed(() => {
    const sorted = [...allRecords.value].sort((a, b) => b.beatTierPoints - a.beatTierPoints);
    return new Set(sorted.slice(0, 100).map(r => `${r.title}|${r.difficultyName}`));
});

/**
 * 縲芯omputed縲� RATE-TIER 逕ｨ縺ｮ蜈ｨ隴憺擇繝ｪ繧ｹ繝医�
 * BEAT-TIER 縺ｨ驕輔＞繝ｬ繝吶Ν蛻ｶ髯舌↑縺暦ｼ遺�1縲懌�12 縺吶∋縺ｦ蟇ｾ雎｡�峨、NOTHER/LEGGENDARIA 縺ｮ繝励Ξ繧､貂医∩縺ｮ縺ｿ縲�
 */
const rateAllRecords = computed<ScoreRecord[]>(() =>
    flattenScores(props.scores).filter(r =>
        ['ANOTHER', 'LEGGENDARIA'].includes(r.difficultyName)
    )
);

/** 縲芯omputed縲� RATE-TIER 縺ｮ TOP100 繧ｭ繝ｼ髮�粋縲３ATE-PT 髯埼��〒荳贋ｽ� 100 隴憺擇繧貞叙繧雁�縺吶� */
const rateTop100Keys = computed(() => {
    const sorted = [...rateAllRecords.value]
        .filter(r => r.scoreRate > 0)
        .sort((a, b) => calculateScoreRateTierPoints(b.scoreRate) - calculateScoreRateTierPoints(a.scoreRate));
    return new Set(sorted.slice(0, 100).map(r => `${r.title}|${r.difficultyName}`));
});

/**
 * 縲芯omputed縲� 繝代�繝輔ぉ繧ｯ繝茨ｼ�100% 驕疲��峨�譖ｲ縺� 100 隴憺擇繧定ｶ�∴縺溘°縺ｩ縺�°縲�
 * 雜�∴縺溷�ｴ蜷医∝句挨 RATE-PT 512 縺ｮ譖ｲ繧偵ｈ繧雁ｼｷ隱ｿ陦ｨ遉ｺ縺吶ｋ�亥粋險� 51200 繧定ｶ�∴繧九が繝ｼ繝舌�繝輔Ο繝ｼ迥ｶ諷九�譏守､ｺ�峨�
 */
const hasPerfectRateOverflow = computed(() =>
    rateAllRecords.value.filter(r => r.scoreRate >= 100).length > 100
);

/**
 * 縲芯omputed縲� TOP100 縺ｮ繝懊�繝繝ｼ繝ｩ繧､繝ｳ��=100 菴阪� BEAT-PT�峨�
 * 100 隴憺擇譛ｪ貅縺励°繝励Ξ繧､縺励※縺�↑縺��ｴ蜷医� 0 繧定ｿ斐☆縲�
 */
const top100Threshold = computed(() => {
    const sorted = [...allRecords.value]
        .filter(r => r.beatTierPoints > 0)
        .sort((a, b) => b.beatTierPoints - a.beatTierPoints);
    return sorted.length >= 100 ? sorted[99].beatTierPoints : 0;
});

/**
 * 縲芯omputed縲� 蜷� TOP100 螟悶�隴憺擇縺ｫ縺､縺�※縲後≠縺ｨ菴慕せ莨ｸ縺ｰ縺帙� TOP100 蜈･繧翫〒縺阪ｋ縺九阪ｒ邂怜�縺励◆ Map縲�
 * 繧ｭ繝ｼ: `title|difficultyName`縲∝､: 蠢�ｦ√↑邏�繧ｹ繧ｳ繧｢蠅怜刈驥上�
 *
 * 蜃ｦ逅��豬√ｌ:
 *  謇矩��1: 縺昴ｂ縺昴ｂ TOP100 縺悟沂縺ｾ縺｣縺ｦ縺�↑縺��ｴ蜷医�遨ｺ Map 繧定ｿ斐☆縲�
 *  謇矩��2: TOP100 蜀��隴憺擇繝ｻ辟｡蜉ｹ隴憺擇縺ｯ繧ｹ繧ｭ繝��縲�
 *  謇矩��3: 逅�ｫ門､��100%�峨〒繧る明蛟､縺ｫ螻翫°縺ｪ縺�ｭ憺擇縺ｯ陦ｨ遉ｺ蟇ｾ雎｡螟悶�
 *  謇矩��4: 迴ｾ蝨ｨ繧ｹ繧ｳ繧｢縲懈怙螟ｧ繧ｹ繧ｳ繧｢縺ｮ遽�峇縺ｧ莠悟�謗｢邏｢縺励�明蛟､雜�∴縺吶ｋ譛蟆上せ繧ｳ繧｢繧堤匱隕九�
 *  謇矩��5: 縺昴�蟾ｮ蛻�ｼ茨ｼ晏ｿ�ｦ∫せ謨ｰ�峨ｒ Map 縺ｫ譬ｼ邏阪�
 */
const top100ScoreNeededMap = computed(() => {
    const map = new Map<string, number>();
    if (top100Threshold.value === 0) return map;

    for (const record of allRecords.value) {
        const key = `${record.title}|${record.difficultyName}`;
        if (top100Keys.value.has(key)) continue; // 譌｢縺ｫ TOP100 蜈･繧翫＠縺ｦ縺�ｋ隴憺擇縺ｯ繧ｹ繧ｭ繝��
        if (!record.informalRank || record.maxScore <= 0) continue; // 險育ｮ嶺ｸ崎�縺ｪ繝��繧ｿ縺ｯ繧ｹ繧ｭ繝��

        const targetPt = top100Threshold.value;
        // 100% 縺ｧ繧る明蛟､縺ｫ螻翫°縺ｪ縺�ｭ憺擇縺ｯ�育炊隲紋ｸ� TOP100 蜈･繧翫〒縺阪↑縺��縺ｧ�芽｡ｨ遉ｺ縺励↑縺�
        if (calculatePoints(100, record.informalRank) <= targetPt) continue;

        // 莠悟�謗｢邏｢: BEAT-PT > 髢ｾ蛟､ 縺ｨ縺ｪ繧区怙蟆上せ繧ｳ繧｢繧呈爾縺�
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

// --- 繧ｿ繝�メ繧ｹ繧ｯ繝ｭ繝ｼ繝ｫ蛻､螳� ---
// 繝｢繝舌う繝ｫ縺ｧ縲瑚｡後ち繝�� = 隧ｳ邏ｰ繝｢繝ｼ繝繝ｫ縲阪→縲檎ｸｦ繧ｹ繧ｯ繝ｭ繝ｼ繝ｫ縲阪ｒ蛹ｺ蛻･縺吶ｋ縺溘ａ縺ｮ迥ｶ諷九�
let touchStartY = 0;
let touchStartX = 0;
let isTouchScrolling = false;

/** 繧ｿ繝�メ髢句ｧ倶ｽ咲ｽｮ繧定ｨ倬鹸縺励√せ繧ｯ繝ｭ繝ｼ繝ｫ繝輔Λ繧ｰ繧偵Μ繧ｻ繝�ヨ縺吶ｋ縲� */
const handleTouchStart = (e: TouchEvent) => {
  touchStartY = e.touches[0].clientY;
  touchStartX = e.touches[0].clientX;
  isTouchScrolling = false;
};

/** 謖�′ 8px 繧定ｶ�∴縺ｦ蜍輔＞縺溘ｉ繧ｹ繧ｯ繝ｭ繝ｼ繝ｫ荳ｭ縺ｨ蛻､螳壹＠縲∝ｾ檎ｶ壹�繧ｯ繝ｪ繝�け繧､繝吶Φ繝医ｒ辟｡隕悶＆縺帙ｋ縲� */
const handleTouchMove = (e: TouchEvent) => {
  const dy = Math.abs(e.touches[0].clientY - touchStartY);
  const dx = Math.abs(e.touches[0].clientX - touchStartX);
  if (dy > 8 || dx > 8) {
    isTouchScrolling = true;
  }
};

/** 陦後け繝ｪ繝�け: 繧ｹ繧ｯ繝ｭ繝ｼ繝ｫ蛻､螳壹′遶九▲縺ｦ縺�↑縺代ｌ縺ｰ隧ｳ邏ｰ繝｢繝ｼ繝繝ｫ繧帝幕縺上� */
const handleRowClick = (record: ScoreRecord) => {
  if (isTouchScrolling) return;
  openDetailModal(record);
};

// --- 隧ｳ邏ｰ繝｢繝ｼ繝繝ｫ縺ｮ迥ｶ諷� ---
/** 迴ｾ蝨ｨ驕ｸ謚樔ｸｭ縺ｮ隴憺擇繝ｬ繧ｳ繝ｼ繝峨Ｏull 縺ｮ縺ｨ縺阪�繝｢繝ｼ繝繝ｫ髱櫁｡ｨ遉ｺ縲� */
const selectedRecord = ref<ScoreRecord | null>(null);
/** 隧ｳ邏ｰ繝｢繝ｼ繝繝ｫ蜀�〒陦ｨ遉ｺ荳ｭ縺ｮ繧ｿ繝悶� */
const modalTab = ref<'detail' | 'rate-tier' | 'rivals' | 'ranking' | 'history'>('detail');

/** 逶ｮ讓� BEAT-PT 繧ｹ繝ｩ繧､繝繝ｼ縺ｮ蛟､��0縲� 譛螟ｧ谿� PT�峨ら岼讓吝芦驕斐↓蠢�ｦ√↑繧ｹ繧ｳ繧｢繧帝�ｮ励＠縺ｦ陦ｨ遉ｺ縺吶ｋ縲� */
const targetBeatPtSlider = ref(0);

// --- 繝ｩ繧､繝舌Ν�医ヵ繝ｬ繝ｳ繝会ｼ峨せ繧ｳ繧｢蜿門ｾ礼畑縺ｮ迥ｶ諷九→蝙� ---
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
/** 繝輔Ξ繝ｳ繝峨�隧ｲ蠖楢ｭ憺擇繧ｹ繧ｳ繧｢荳隕ｧ縲ゅち繝門�陦ｨ遉ｺ譎ゅ↓蜿門ｾ励☆繧九� */
const rivalScores = ref<RivalScore[]>([]);

// --- 隴憺擇蛻･繝ｩ繝ｳ繧ｭ繝ｳ繧ｰ�井ｻ悶Θ繝ｼ繧ｶ繝ｼ�臥畑縺ｮ迥ｶ諷九→蝙� ---
// 髱槫庄隕悶Θ繝ｼ繧ｶ繝ｼ縺ｯ鬆�ｽ咲ｮ怜�縺ｮ縺溘ａ縺�縺代↓霑斐▲縺ｦ縺上ｋ縺ｮ縺ｧ縲∬ｭ伜挨諠��ｱ��userId / iidxId / displayName 遲会ｼ�
// 繧� totalBeatPt 縺ｯ null 縺ｧ繝槭せ繧ｯ縺輔ｌ蠕励ｋ縲�
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
/** 隴憺擇繝ｩ繝ｳ繧ｭ繝ｳ繧ｰ�亥�髢�/繝輔Ξ繝ｳ繝�/閾ｪ蛻�ｼ峨�逕溘ョ繝ｼ繧ｿ縲� */
const songRankingList = ref<SongRankingEntry[]>([]);
/** 隴憺擇繝ｩ繝ｳ繧ｭ繝ｳ繧ｰ蜿門ｾ嶺ｸｭ繝輔Λ繧ｰ縲� */
const isLoadingSongRanking = ref(false);
/** 縲後せ繧ｳ繧｢蜈ｬ髢九Θ繝ｼ繧ｶ繝ｼ繧り｡ｨ遉ｺ縲阪メ繧ｧ繝�け縲� */
const showPublicUsers = ref(false);
/** 縲卦OP繝ｩ繝ｳ繧ｫ繝ｼ莉ｮ諠ｳ繝ｦ繝ｼ繧ｶ繝ｼ繧定｡ｨ遉ｺ縲阪メ繧ｧ繝�け縲� */
const showVirtualUsers = ref(false);

interface SongTopRankerEntry {
  versionNum: number;
  versionName: string;
  prefectureFileNum: number;
  prefectureName: string;
  djName: string;
  score: number;
}
/** 隴憺擇縺ｫ邏舌▼縺丈ｻｮ諠ｳ繝ｦ繝ｼ繧ｶ繝ｼ��TOP繝ｩ繝ｳ繧ｫ繝ｼ�峨�荳隕ｧ縲ゅち繝冶｡ｨ遉ｺ譎ゅ↓蜿門ｾ励☆繧九� */
const songTopRankersList = ref<SongTopRankerEntry[]>([]);

// --- 閾ｪ蛻�′逋ｻ骭ｲ貂医∩縺ｮ莉ｮ諠ｳ繝ｩ繧､繝舌Ν�医ヵ繝ｬ繝ｳ繝画桶縺�〒蟶ｸ譎り｡ｨ遉ｺ縺吶ｋ蟇ｾ雎｡��---
const { fetchVirtualRivals } = useFriends();
/** 閾ｪ蛻�′逋ｻ骭ｲ貂医∩縺ｮ莉ｮ諠ｳ繝ｩ繧､繝舌Ν繧� `${versionNum}:${prefectureFileNum}` 蠖｢蠑上〒菫晄戟縺励◆繧ｻ繝�ヨ縲� */
const registeredVirtualRivalKeys = ref<Set<string>>(new Set());
/** 莉ｮ諠ｳ繝ｩ繧､繝舌Ν荳隕ｧ繧貞叙蠕玲ｸ医∩縺九←縺�°縲ゅΛ繝ｳ繧ｭ繝ｳ繧ｰ繧ｿ繝門�陦ｨ遉ｺ譎ゅ↓荳蠎ｦ縺�縺代ヵ繧ｧ繝�メ縺吶ｋ縲� */
const virtualRivalsLoaded = ref(false);

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 閾ｪ蛻�′逋ｻ骭ｲ貂医∩縺ｮ莉ｮ諠ｳ繝ｩ繧､繝舌Ν繧偵ヵ繧ｧ繝�メ縺励√そ繝�ヨ縺ｫ隧ｰ繧∫峩縺吶�
 * 繝ｩ繝ｳ繧ｭ繝ｳ繧ｰ繧ｿ繝門�陦ｨ遉ｺ譎ゅ↓荳蠎ｦ縺�縺大他縺ｰ繧後ｋ縲ょ､ｱ謨玲凾縺ｯ遨ｺ縺ｮ縺ｾ縺ｾ��UI 縺ｯ螢翫＆縺ｪ縺�ｼ峨�
 */
const loadRegisteredVirtualRivals = async () => {
  if (!isLoggedIn.value) return;
  try {
    const rivals = await fetchVirtualRivals();
    const set = new Set<string>();
    for (const r of rivals) set.add(`${r.versionNum}:${r.prefectureFileNum}`);
    registeredVirtualRivalKeys.value = set;
  } catch {
    // 謠｡繧頑ｽｰ縺�
  } finally {
    virtualRivalsLoaded.value = true;
  }
};

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 隴憺擇繝ｩ繝ｳ繧ｭ繝ｳ繧ｰ�亥ｮ溘Θ繝ｼ繧ｶ繝ｼ�峨→ TOP 繝ｩ繝ｳ繧ｫ繝ｼ�井ｻｮ諠ｳ繝ｦ繝ｼ繧ｶ繝ｼ�峨ｒ荳ｦ陦悟叙蠕励☆繧九�
 * Promise.all 縺ｧ 2 譛ｬ縺ｮ API 繧貞酔譎ゅ↓蜻ｼ縺ｳ縲√←縺｡繧峨°縺悟､ｱ謨励＠縺ｦ繧� UI 繧貞｣翫＆縺ｪ縺��
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
    // 謠｡繧頑ｽｰ縺�: 繝ｩ繝ｳ繧ｭ繝ｳ繧ｰ陦ｨ遉ｺ縺ｯ陬懷勧諠��ｱ縺ｪ縺ｮ縺ｧ螟ｱ謨励＠縺ｦ繧ゅΔ繝ｼ繝繝ｫ縺ｯ蜍輔°縺�
  } finally {
    isLoadingSongRanking.value = false;
  }
};

/** 隧ｳ邏ｰ繝｢繝ｼ繝繝ｫ縺ｮ繝ｩ繝ｳ繧ｭ繝ｳ繧ｰ繧ｿ繝悶〒謠冗判縺吶ｋ陦後�蜈ｱ騾壼梛縲ょｮ溘Θ繝ｼ繧ｶ繝ｼ陦後→莉ｮ諠ｳ繝ｦ繝ｼ繧ｶ繝ｼ陦後ｒ荳譛ｬ蛹悶☆繧九� */
/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 繝ｩ繝ｳ繧ｭ繝ｳ繧ｰ陦後�繧ｹ繧ｳ繧｢縺九ｉ蜊俶峇繝ｩ繝ｳ繧ｯ�亥ｿ�ｦ√せ繧ｳ繧｢繝ｬ繝ｼ繝郁｡ｨ蟇ｾ蠢懶ｼ峨ｒ豎ゅａ繧九�
 * maxScore / informalRank 縺檎┌縺�ｭ憺擇��Uncategorized 遲会ｼ峨ｄ繧ｹ繧ｳ繧｢ 0 縺ｯ null縲�
 */
const songRankOfScore = (score: number | null | undefined): RankInfo | null => {
  const rec = selectedRecord.value;
  if (score == null || score <= 0 || !rec || rec.maxScore <= 0 || !rec.informalRank) return null;
  return getFolderRankInfoByRate(score / rec.maxScore * 100, rec.informalRank);
};

/**
 * 縲芯omputed 縺ｮ蠖ｹ蜑ｲ縲� 繝ｩ繝ｳ繧ｭ繝ｳ繧ｰ繧ｿ繝門�鬆ｭ縺ｮ縲悟腰譖ｲ繝ｩ繝ｳ繧ｯ蛻�ｸ�阪メ繝｣繝ｼ繝医ｒ邨�∩遶九※繧九�
 *
 *  - 髮�ｨ亥ｯｾ雎｡: songRankingList 縺ｮ蜈ｨ螳溘Θ繝ｼ繧ｶ繝ｼ�磯撼蜈ｬ髢九�縺溘ａ蛹ｿ蜷榊喧縺輔ｌ縺溯｡後ｂ蜷ｫ繧��
 *    + 繝輔Ξ繝ｳ繝会ｼ�rivalScores縲（idxId 縺ｧ驥崎､�賜髯､��+ 閾ｪ蛻�ゆｻｮ諠ｳ TOP 繝ｩ繝ｳ繧ｫ繝ｼ縺ｯ髯､螟悶�
 *  - ProfileDashboard 縺ｮ蜊俶峇繝�ぅ繧｢蛻�ｸ�→蜷後§ 52 繝舌�讒区�
 *    ��Beginner / 蜷�ヶ繝ｭ繝�け I縲弖 / Legend縲！=豺｡ 竊� V=豼�∝ｷｦ=菴惹ｽ� 竊� 蜿ｳ=鬮倅ｽ搾ｼ峨�
 *  - 謌ｻ繧雁､: { data: chart.js 繝��繧ｿ, total: 髮�ｨ井ｺｺ謨ｰ } / 髮�ｨ井ｸ崎�譎ゅ� null縲�
 */
const songTierDist = computed(() => {
  const rec = selectedRecord.value;
  if (!rec || rec.maxScore <= 0 || !rec.informalRank) return null;
  const myIidx = user.value?.iidxId ?? '';

  // --- 繧ｹ繧ｳ繧｢繝ｬ繝ｼ繝医�蜿朱寔�亥ｮ溘Θ繝ｼ繧ｶ繝ｼ縺ｮ縺ｿ�� ---
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

  // --- 52 繝舌�螳夂ｾｩ��ProfileDashboard 縺ｮ蜊俶峇繝�ぅ繧｢蛻�ｸ�→蜷後§荳ｦ縺ｳ繝ｻ驟崎牡�� ---
  const blocks: { name: string; color: string; sub: boolean }[] = [
    { name: 'Beginner',     color: '#94a3b8', sub: false },
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
  rates.forEach(rate => {
    const rank = getFolderRankInfoByRate(rate, rec.informalRank);
    const key = rank.tier ? `${rank.name}|${rank.tier}` : rank.name;
    if (counts[key] !== undefined) counts[key]++;
  });
  return {
    total: rates.length,
    data: {
      labels: bars.map(b => b.label),
      datasets: [{
        label: t('common.songCount'),
        data: bars.map(b => counts[b.key]),
        backgroundColor: bars.map(b => b.color),
        borderRadius: 1,
        categoryPercentage: 1.0,
        barPercentage: 1.0,
      }],
    },
  };
});

/** 蜊俶峇繝ｩ繝ｳ繧ｯ蛻�ｸ�メ繝｣繝ｼ繝医�謠冗判繧ｪ繝励す繝ｧ繝ｳ��X 霆ｸ縺ｯ隕ｪ繝ｩ繝ｳ繧ｯ蜷阪�縺ｿ陦ｨ遉ｺ�峨� */
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
          if (label === 'Beginner' || label === 'Legend') return label;
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
 * 縲芯omputed 縺ｮ蠖ｹ蜑ｲ縲� 繝ｩ繝ｳ繧ｭ繝ｳ繧ｰ繧ｿ繝悶〒陦ｨ遉ｺ縺吶ｋ陦後ｒ邨�∩遶九※繧九�
 *
 * 蜃ｦ逅�ヵ繝ｭ繝ｼ:
 *  謇矩��1: songRankingList��API 蜿門ｾ励�螳溘Θ繝ｼ繧ｶ繝ｼ�峨ｒ iidxId 縺ｧ Map 縺ｫ謚募�縲り�蛻��髯､螟悶�
 *  謇矩��2: rivalScores�医ヵ繝ｬ繝ｳ繝会ｼ峨ｒ蜷後§ Map 縺ｫ繝槭�繧ｸ縲よ里蟄倥↑繧� isFriend 繝輔Λ繧ｰ縺�縺醍ｫ九※繧九�
 *  謇矩��3: 閾ｪ蛻�ｼ�selfRow�峨ｒ菴懈�縺励∝粋邂怜ｯｾ雎｡縺ｫ蜉�縺医ｋ縲�
 *  謇矩��4: showVirtualUsers 縺� true 縺ｮ縺ｨ縺阪ゝOP 繝ｩ繝ｳ繧ｫ繝ｼ莉ｮ諠ｳ繝ｦ繝ｼ繧ｶ繝ｼ繧剃ｻ･荳九�繝ｫ繝ｼ繝ｫ縺ｧ蛻�｡�:
 *           - allTimeGlobal: 蜈ｨ蝗ｽ(prefNum=0)縺ｮ豁ｴ莉｣ TOP
 *           - globalAllTime: 逵悟挨縺ｮ豁ｴ莉｣ TOP 縺悟�蝗ｽ豁ｴ莉｣ TOP 縺ｨ蜷後せ繧ｳ繧｢/蜷悟錐縺ｪ繧画�譬ｼ
 *           - allTimeArea:   逵悟挨縺ｮ豁ｴ莉｣ TOP�亥�蝗ｽ縺ｨ荳閾ｴ縺励↑縺�ｂ縺ｮ��
 *           - versionTop:    繝舌�繧ｸ繝ｧ繝ｳ蛻･蜈ｨ蝗ｽ TOP
 *           - top:           縺昴�莉悶ヰ繝ｼ繧ｸ繝ｧ繝ｳ蛻･逵悟挨 TOP
 *         驥崎､�ｼ亥�蝗ｽ�晉恁, version 蜈ｨ蝗ｽ�晉恁�峨�荳譁ｹ縺�縺第ｮ九☆縲�
 *  謇矩��5: 繧ｹ繧ｳ繧｢縺ｮ縺ゅｋ螳溘Θ繝ｼ繧ｶ繝ｼ陦後↓ dense 1-indexed 縺ｮ鬆�ｽ阪ｒ莉倅ｸ趣ｼ亥酔轤ｹ縺ｯ蜷碁��ｽ搾ｼ峨�
 *         莉ｮ諠ｳ繝ｦ繝ｼ繧ｶ繝ｼ��TOP 繝ｩ繝ｳ繧ｫ繝ｼ�峨�繝励Ξ繧､螳溽ｸｾ縺ｧ縺ｯ縺ｪ縺�◆繧���ｽ榊ｯｾ雎｡縺九ｉ螟悶☆縲�
 *  謇矩��6: 陦ｨ遉ｺ逕ｨ縺ｫ繝輔ぅ繝ｫ繧ｿ: 閾ｪ蛻� + 莉ｮ諠ｳ + 蜈ｬ髢九ヵ繝ｬ繝ｳ繝� + (showPublicUsers 縺九▽ privacy=0)縲�
 *  謇矩��7: 繧ｹ繧ｳ繧｢髯埼��〒繧ｽ繝ｼ繝医＠縺ｦ霑斐☆縲ゅせ繧ｳ繧｢ null 縺ｯ譛ｫ蟆ｾ縲�
 */
const rankingList = computed<RankingRow[]>(() => {
  if (!selectedRecord.value) return [];
  const rec = selectedRecord.value;
  const myIidx = user.value?.iidxId ?? '';

  // 繝輔Ξ繝ｳ繝峨� iidxId 繧ｻ繝�ヨ縲ょｮ溘Θ繝ｼ繧ｶ繝ｼ陦後∈ isFriend 繝輔Λ繧ｰ繧堤ｫ九※繧九�縺ｫ菴ｿ縺��
  const friendIidxSet = new Set(rivalScores.value.map(r => r.iidxId).filter(Boolean));

  // 謇矩��1縲�2: songRankingList + rivalScores 繧� iidxId 縺ｧ繝槭�繧ｸ縲り�蛻��髯､螟悶�
  // 髱槫庄隕悶Θ繝ｼ繧ｶ繝ｼ�医ヰ繝�け繧ｨ繝ｳ繝峨〒 iidxId 縺� NULL 縺ｫ繝槭せ繧ｯ縺輔ｌ縺溯｡鯉ｼ峨�鬆�ｽ咲ｮ怜�縺ｮ縺溘ａ縺�縺代↓
  // 蛻･驟榊�縺ｧ菫晄戟縺励∬｡ｨ遉ｺ繝輔ぅ繝ｫ繧ｿ縺ｧ閾ｪ辟ｶ縺ｫ髯､螟悶☆繧九�
  const usersByIidx = new Map<string, RankingRow>();
  const hiddenRows: RankingRow[] = [];
  let hiddenIdx = 0;
  for (const entry of songRankingList.value) {
    const iidxId = entry.iidxId ?? '';
    if (iidxId === myIidx && iidxId) continue;
    if (!iidxId) {
      // 髱槫庄隕悶Θ繝ｼ繧ｶ繝ｼ: 隴伜挨諠��ｱ縺後�繧ｹ繧ｯ縺輔ｌ縺ｦ縺�ｋ縲ゅせ繧ｳ繧｢縺ｮ縺ｿ鬆�ｽ崎ｨ育ｮ励↓蟇�ｸ弱＆縺帙ｋ縲�
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

  // 謇矩��3: 閾ｪ蛻��陦後ｒ蜷域���displayName 縺ｯ蝗ｺ螳壹〒縲後≠縺ｪ縺溘搾ｼ峨�
  const selfRow: RankingRow = {
    key: 'self',
    kind: 'user',
    displayName: '縺ゅ↑縺�',
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

  // 謇矩��4: 莉ｮ諠ｳ繝ｦ繝ｼ繧ｶ繝ｼ陦後ｒ讒狗ｯ峨ょ推繝舌ャ繧ｸ縺ｮ蛻､螳壹Ο繧ｸ繝�け縺ｯ莉･荳九�
  // 陦ｨ遉ｺ繝輔ぅ繝ｫ繧ｿ縺ｯ蠕梧ｮｵ縺ｧ陦後≧縲ら匳骭ｲ貂医∩縺ｮ莉ｮ諠ｳ繝ｩ繧､繝舌Ν縺ｯ繝輔Ξ繝ｳ繝画桶縺�〒蟶ｸ譎り｡ｨ遉ｺ縺吶ｋ縺溘ａ縲�
  // 縺薙％縺ｧ縺ｯ showVirtualUsers 縺ｮ蛟､縺ｫ髢｢菫ゅ↑縺丞�莉ｶ菴懊▲縺ｦ縺九ｉ邨槭ｊ霎ｼ繧縲�
  const virtualRows: RankingRow[] = [];
  {
    // 逵悟挨縺ｮ豁ｴ莉｣ TOP 繧帝寔繧√ｋ��versionNum === 0 縺後梧ｭｴ莉｣縲阪�諢丞袖�峨�
    const allTimeByPref = new Map<number, { djName: string; score: number }>();
    for (const e of songTopRankersList.value) {
      if (e.versionNum === 0) allTimeByPref.set(e.prefectureFileNum, { djName: e.djName, score: e.score });
    }
    // 蜈ｨ蝗ｽ��prefectureFileNum === 0�峨�豁ｴ莉｣ TOP縲�
    const globalAllTime = allTimeByPref.get(0);
    // 蜈ｨ蝗ｽ豁ｴ莉｣ TOP 縺ｨ螳悟�荳閾ｴ縺吶ｋ逵悟挨豁ｴ莉｣ TOP 縺後≠繧九°繧呈､懷�縲�
    // 荳｡譁ｹ陦ｨ遉ｺ縺吶ｋ縺ｨ驥崎､�☆繧九�縺ｧ縲∝ｾ梧ｮｵ縺ｧ蜈ｨ蝗ｽ豁ｴ莉｣繧帝俣蠑輔￥譬ｹ諡�縺ｫ縺吶ｋ縲�
    let prefectureMatchesGlobalAllTime = false;
    if (globalAllTime) {
      for (const [prefNum, at] of allTimeByPref) {
        if (prefNum !== 0 && at.djName === globalAllTime.djName && at.score === globalAllTime.score) {
          prefectureMatchesGlobalAllTime = true;
          break;
        }
      }
    }
    // 繝舌�繧ｸ繝ｧ繝ｳ蛻･ 蜈ｨ蝗ｽ TOP 繧帝寔繧√ｋ縲�
    const globalTopByVersion = new Map<number, { djName: string; score: number }>();
    for (const e of songTopRankersList.value) {
      if (e.versionNum !== 0 && e.prefectureFileNum === 0) {
        globalTopByVersion.set(e.versionNum, { djName: e.djName, score: e.score });
      }
    }
    // 縲後ヰ繝ｼ繧ｸ繝ｧ繝ｳ蛻･ 蜈ｨ蝗ｽ TOP縲阪→蜷後§繧ｹ繧ｳ繧｢/蜷榊燕繧呈戟縺､縲後ヰ繝ｼ繧ｸ繝ｧ繝ｳ蛻･ 逵悟挨 TOP縲阪′縺ゅｋ繝舌�繧ｸ繝ｧ繝ｳ繧呈､懷�縲�
    // 隧ｲ蠖薙☆繧句�ｴ蜷医√ヰ繝ｼ繧ｸ繝ｧ繝ｳ蛻･ 蜈ｨ蝗ｽ陦後�蜀鈴聞縺ｪ縺ｮ縺ｧ蠕梧ｮｵ縺ｧ髯､螟悶☆繧九�
    const versionHasPrefectureMatch = new Set<number>();
    for (const e of songTopRankersList.value) {
      if (e.versionNum === 0 || e.prefectureFileNum === 0) continue;
      const g = globalTopByVersion.get(e.versionNum);
      if (g && g.djName === e.djName && g.score === e.score) versionHasPrefectureMatch.add(e.versionNum);
    }
    // key 逕ｨ縺ｮ繝ｦ繝九�繧ｯ繧､繝ｳ繝�ャ繧ｯ繧ｹ縲�
    let idx = 0;
    // 繝舌�繧ｸ繝ｧ繝ｳ縺斐→縺ｮ繧ｨ繝ｳ繝医Μ繧定ｵｰ譟ｻ縺励√ヰ繝�ず繧呈ｱｺ繧√ｋ縲ＷersionNum === 0�域ｭｴ莉｣陦鯉ｼ峨�繝舌�繧ｸ繝ｧ繝ｳ蛻励�蜑肴ｮｵ縺ｧ譌｢縺ｫ蜃ｦ逅�ｸ医∩縺ｪ縺ｮ縺ｧ繧ｹ繧ｭ繝��縲�
    for (const e of songTopRankersList.value) {
      if (e.versionNum === 0) continue;
      // 縺薙�繧ｨ繝ｳ繝医Μ縺後∫恁蛻･豁ｴ莉｣ TOP 縺ｨ蜷御ｸ��= 豁ｴ莉｣險倬鹸�峨°蛻､螳壹�
      const at = allTimeByPref.get(e.prefectureFileNum);
      const isAllTime = at !== undefined && at.djName === e.djName && at.score === e.score;
      // 蜈ｨ蝗ｽ豁ｴ莉｣縺ｨ逵悟挨豁ｴ莉｣縺御ｸ閾ｴ縺吶ｋ縺ｪ繧峨∝�蝗ｽ豁ｴ莉｣陦後�蜀鈴聞縺ｪ縺ｮ縺ｧ髯､螟悶�
      if (isAllTime && e.prefectureFileNum === 0 && prefectureMatchesGlobalAllTime) continue;
      // 繝舌�繧ｸ繝ｧ繝ｳ蜈ｨ蝗ｽ TOP 縺ｨ蜷後ヰ繝ｼ繧ｸ繝ｧ繝ｳ逵悟挨 TOP 縺悟酔繧ｹ繧ｳ繧｢縺ｪ繧峨∝�蝗ｽ陦後�髯､螟悶�
      if (e.prefectureFileNum === 0 && versionHasPrefectureMatch.has(e.versionNum)) continue;
      // 逵悟挨豁ｴ莉｣ TOP 縺ｮ縺�■縲∝�蝗ｽ豁ｴ莉｣ TOP 縺ｨ荳閾ｴ縺励※縺�ｋ繧ゅ�繧呈�譬ｼ謇ｱ縺�↓縺吶ｋ縲�
      const isGlobalAllTime = isAllTime && e.prefectureFileNum !== 0 && globalAllTime !== undefined
        && globalAllTime.djName === e.djName && globalAllTime.score === e.score;
      // 繝舌�繧ｸ繝ｧ繝ｳ蛻･蜈ｨ蝗ｽ TOP 縺ｨ荳閾ｴ縺吶ｋ逵悟挨繧ｨ繝ｳ繝医Μ縺具ｼ�
      let isVersionTop = false;
      if (e.prefectureFileNum !== 0) {
        const g = globalTopByVersion.get(e.versionNum);
        if (g && g.djName === e.djName && g.score === e.score) isVersionTop = true;
      }
      // 繝舌ャ繧ｸ蜆ｪ蜈亥ｺｦ: allTimeGlobal > globalAllTime > allTimeArea > versionTop > top縲�
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

  // 縺吶∋縺ｦ縺ｮ陦後ｒ 1 譛ｬ縺ｮ驟榊�縺ｫ髮�ｴ�ｼ郁�蛻� + 螳溘Θ繝ｼ繧ｶ繝ｼ + 髱槫�髢九Θ繝ｼ繧ｶ繝ｼ + 莉ｮ諠ｳ�峨�
  // hiddenRows 縺ｯ鬆�ｽ崎ｨ育ｮ励�縺溘ａ縺�縺代↓蜷ｫ繧√ｋ�郁｡ｨ遉ｺ繝輔ぅ繝ｫ繧ｿ縺ｧ閾ｪ辟ｶ縺ｫ髯､螟悶＆繧後ｋ�峨�
  const all: RankingRow[] = [selfRow, ...usersByIidx.values(), ...hiddenRows, ...virtualRows];

  // 謇矩��5: 繧ｹ繧ｳ繧｢菫晄怏閠�↓ dense 1-indexed 縺ｮ鬆�ｽ阪ｒ莉倅ｸ弱る撼陦ｨ遉ｺ繝ｦ繝ｼ繧ｶ繝ｼ繧る��ｽ崎ｨ育ｮ励↓縺ｯ蜷ｫ繧√ｋ
  //        ��= 陦ｨ遉ｺ荳翫�4菴阪阪′谺�逡ｪ縺ｫ隕九∴縺ｦ繧ゅ∬｣上〒髫�繧後◆ 3 菴阪′蟄伜惠縺怜ｾ励ｋ�峨�
  //        莉ｮ諠ｳ繝ｦ繝ｼ繧ｶ繝ｼ (TOP 繝ｩ繝ｳ繧ｫ繝ｼ) 縺ｯ螳溘�繝ｬ繧､繝､繝ｼ縺ｧ縺ｯ縺ｪ縺�◆繧���ｽ崎ｨ育ｮ励°繧蛾勁螟悶＠縲�
  //        rank: null 縺ｮ縺ｾ縺ｾ縺ｫ縺励※縺翫￥�医ユ繝ｳ繝励Ξ繝ｼ繝亥�縺ｧ縲�-縲崎｡ｨ遉ｺ縺ｫ縺ｪ繧具ｼ峨�
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

  // 謇矩��6: 陦ｨ遉ｺ繝輔ぅ繝ｫ繧ｿ縲り�蛻��蟶ｸ縺ｫ陦ｨ遉ｺ縲∽ｻｮ諠ｳ繝ｦ繝ｼ繧ｶ繝ｼ縺ｯ showVirtualUsers 繝√ぉ繝�け譎ゅ∪縺溘�
  //        逋ｻ骭ｲ貂医∩莉ｮ諠ｳ繝ｩ繧､繝舌Ν�医ヵ繝ｬ繝ｳ繝画桶縺�ｼ峨�縺ｿ陦ｨ遉ｺ縲∝ｮ溘ヵ繝ｬ繝ｳ繝峨�繝励Λ繧､繝舌す繝ｼ 2�亥ｮ悟�髱槫�髢具ｼ我ｻ･螟悶�
  //        蜈ｬ髢九Θ繝ｼ繧ｶ繝ｼ縺ｯ showPublicUsers 繝√ぉ繝�け譎ゅ�縺ｿ陦ｨ遉ｺ縲�

  const display = all.filter(r => {
    if (r.isSelf) return true;
    if (r.kind === 'virtual') return showVirtualUsers.value || !!r.isFriend;
    if (r.isFriend && (r.privacyLevel ?? 1) !== 2) return true;
    if (showPublicUsers.value && (r.privacyLevel ?? 1) === 0) return true;
    return false;
  });

  // 謇矩��7: 繧ｹ繧ｳ繧｢髯埼��〒繧ｽ繝ｼ繝医Ｔcore === null 縺ｮ陦後�譛ｫ蟆ｾ縺ｫ蟇�○繧九�
  display.sort((a, b) => {
    if (a.score == null && b.score == null) return 0;
    if (a.score == null) return 1;
    if (b.score == null) return -1;
    return (b.score as number) - (a.score as number);
  });

  return display;
});

/** 繝輔Ξ繝ｳ繝峨せ繧ｳ繧｢蜿門ｾ嶺ｸｭ繝輔Λ繧ｰ縲� */
const isLoadingRivals = ref(false);

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 驕ｸ謚樔ｸｭ隴憺擇縺ｫ蟇ｾ縺吶ｋ繝輔Ξ繝ｳ繝峨せ繧ｳ繧｢繧� API 縺九ｉ蜿門ｾ励�
 * 邨先棡縺ｯ rivalScores 縺ｫ譬ｼ邏阪＆繧後〉ankingList computed 縺ｫ繧ょ渚譏�縺輔ｌ繧九�
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
    // 謠｡繧頑ｽｰ縺�: 繝ｩ繧､繝舌Ν繧ｿ繝悶�陬懷勧諠��ｱ縺ｮ縺溘ａ繝｢繝ｼ繝繝ｫ縺ｯ邯ｭ謖�
  } finally {
    isLoadingRivals.value = false;
  }
};

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 繝ｩ繧､繝舌Ν繧ｿ繝悶け繝ｪ繝�け譎ゅ�繝上Φ繝峨Λ縲�
 * 繧ｿ繝門�譖ｿ + 蛻晏屓縺ｮ縺ｿ rivalScores / songRanking 繧貞叙蠕励☆繧具ｼ医く繝｣繝�す繝･蜉ｹ譫懶ｼ峨�
 */
const handleRivalTabClick = () => {
  modalTab.value = 'rivals';
  if (rivalScores.value.length === 0 && !isLoadingRivals.value) {
    fetchRivalScores();
  }
  if (songRankingList.value.length === 0 && songTopRankersList.value.length === 0 && !isLoadingSongRanking.value) {
    fetchSongRanking();
  }
  if (!virtualRivalsLoaded.value) {
    loadRegisteredVirtualRivals();
  }
};

// --- 隴憺擇蜊倅ｽ阪�謌宣聞螻･豁ｴ ---
/** 繧ｹ繧ｳ繧｢螟牙喧繧呈凾邉ｻ蛻励〒陦ｨ遉ｺ縺吶ｋ髫帙� 1 繝ｬ繧ｳ繝ｼ繝峨� */
interface SongHistoryEntry {
  uploadedAt: string;
  score: number | null;
  beatPt: number | null;
}
/** 繝偵せ繝医Μ繝ｼ繧ｿ繝悶〒陦ｨ遉ｺ縺吶ｋ螻･豁ｴ繝��繧ｿ縲ゅち繝門�陦ｨ遉ｺ譎ゅ↓蜿門ｾ励� */
const songHistory = ref<SongHistoryEntry[]>([]);
/** 螻･豁ｴ隱ｭ霎ｼ荳ｭ繝輔Λ繧ｰ縲� */
const isLoadingHistory = ref(false);

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 驕ｸ謚樔ｸｭ隴憺擇縺ｮ繧ｹ繧ｳ繧｢譖ｴ譁ｰ螻･豁ｴ��uploadedAt / score / beatPt�峨ｒ蜿門ｾ励�
 * 繧ｰ繝ｩ繝�/陦ｨ縺ｮ蜈�ョ繝ｼ繧ｿ縺ｫ縺ｪ繧九�
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
    // 謠｡繧頑ｽｰ縺�: 螻･豁ｴ縺ｯ陬懷勧諠��ｱ
  } finally {
    isLoadingHistory.value = false;
  }
};

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 繝偵せ繝医Μ繝ｼ繧ｿ繝悶け繝ｪ繝�け譎ゅ�繝上Φ繝峨Λ縲�
 * 蛻晏屓縺ｮ縺ｿ songHistory 繧偵ヵ繧ｧ繝�メ�井ｻ･髯阪�繧ｭ繝｣繝�す繝･繧貞�蛻ｩ逕ｨ�峨�
 */
const handleHistoryTabClick = () => {
  modalTab.value = 'history';
  if (songHistory.value.length === 0 && !isLoadingHistory.value) {
    fetchSongHistory();
  }
};

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 繝舌ャ繧ｯ繧ｨ繝ｳ繝峨� LocalDateTime 譁�ｭ怜��医ち繧､繝�繧ｾ繝ｼ繝ｳ縺ｪ縺暦ｼ峨ｒ JST 謇ｱ縺�〒謨ｴ蠖｢縺吶ｋ縲�
 * 譌｢縺ｫ繧ｿ繧､繝�繧ｾ繝ｼ繝ｳ縺御ｻ倥＞縺ｦ縺�ｌ縺ｰ縺昴�縺ｾ縺ｾ菴ｿ逕ｨ縲∫┌縺代ｌ縺ｰ `+09:00` 繧剃ｻ倅ｸ弱＠縺ｦ Date 蛹悶☆繧九�
 */
const formatHistoryDate = (dateStr: string) => {
  // 繝舌ャ繧ｯ繧ｨ繝ｳ繝峨�繧ｿ繧､繝�繧ｾ繝ｼ繝ｳ諠��ｱ繧呈戟縺溘↑縺� LocalDateTime 繧定ｿ斐☆縲�JST 縺ｨ縺励※隗｣驥医☆繧九�
  const jstStr = /[Z+\-]\d{2}:?\d{2}$/.test(dateStr) ? dateStr : dateStr + '+09:00';
  return new Date(jstStr).toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
};

/**
 * 縲陣atch 縺ｮ蠖ｹ蜑ｲ縲� 驕ｸ謚樔ｸｭ隴憺擇縺悟､峨ｏ繧九◆縺ｳ縺ｫ縲√Δ繝ｼ繝繝ｫ蜀�ち繝悶＃縺ｨ縺ｮ繧ｭ繝｣繝�す繝･繧偵Μ繧ｻ繝�ヨ縺励�
 * 謚慕･ｨ繝��繧ｿ繧貞�蜿門ｾ励☆繧九ゅ％縺薙〒 rivalScores / songRanking / history 繧堤ｩｺ縺ｫ縺励※縺翫￥縺薙→縺ｧ縲�
 * 谺｡蝗槭ち繝悶け繝ｪ繝�け譎ゅ↓ fetch 縺悟�蠎ｦ襍ｰ繧九ｈ縺�ｻ募髄縺代ｋ縲�
 */
watch(() => selectedRecord.value?.title, () => {
  targetBeatPtSlider.value = 0;
  rivalScores.value = [];
  songRankingList.value = [];
  songTopRankersList.value = [];
  songHistory.value = [];
  // 譁ｰ縺励＞隴憺擇縺ｮ謚慕･ｨ繝��繧ｿ繧貞叉譎ょ叙蠕暦ｼ医ち繝門�譖ｿ縺ｫ萓昴ｉ縺壹Γ繧､繝ｳ繧ｿ繝悶〒繧り｡ｨ遉ｺ縺吶ｋ縺溘ａ�峨�
  if (selectedRecord.value) {
    fetchVotes(selectedRecord.value.title, selectedRecord.value.difficultyName);
  }
});

// --- 繧ｪ繝励す繝ｧ繝ｳ謚慕･ｨ繧ｷ繧ｹ繝�Β ---
// 隴憺擇縺斐→縺ｫ縲後←縺ｮ繧ｪ繝励す繝ｧ繝ｳ�域ｭ｣隕�/MIRROR/RANDOM/R-RAN/S-RAN�峨〒驕翫ｓ縺ｧ縺�ｋ縺九阪ｒ謚慕･ｨ繝ｻ髮�ｨ医☆繧倶ｻ慕ｵ�∩縲�
/**
 * 繝舌ャ繧ｯ繧ｨ繝ｳ繝峨°繧牙女縺大叙繧区兜逾ｨ髮�ｨ医ョ繝ｼ繧ｿ縺ｮ蠖｢縲�
 *
 * 隍�焚驕ｸ謚槫ｯｾ蠢�:
 *  - {@code myVotes} 縺ｯ驟榊�縲�1 繝ｦ繝ｼ繧ｶ繝ｼ縺悟酔荳隴憺擇縺ｫ隍�焚繧ｪ繝励す繝ｧ繝ｳ繧呈兜逾ｨ縺ｧ縺阪ｋ縲�
 *  - {@code totalVotes} 縺ｯ **繝ｦ繝九�繧ｯ繝ｦ繝ｼ繧ｶ繝ｼ謨ｰ**�郁､�焚驕ｸ謚槭〒繧� 1 縺ｨ謨ｰ縺医ｋ�峨�
 *    繝舌�繝√Ε繝ｼ繝医�蛻�ｯ阪→縺励※菴ｿ縺�り､�焚驕ｸ謚槭↑縺ｮ縺ｧ��粋險医� 100% 繧定ｶ�∴蠕励ｋ縲�
 */
interface VoteDataType {
  counts: Record<string, number>;
  totalVotes: number;
  myVotes: string[];
}

/** 迴ｾ蝨ｨ陦ｨ遉ｺ荳ｭ隴憺擇縺ｮ謚慕･ｨ繝��繧ｿ縲りｭ憺擇蛻�崛譎ゅ↓ fetch 縺励↑縺翫☆縲� */
const voteData = ref<VoteDataType>({
  counts: { REGULAR: 0, MIRROR: 0, RANDOM: 0, 'R-RANDOM': 0, 'S-RANDOM': 0 },
  totalVotes: 0,
  myVotes: []
});
/** 謚慕･ｨ POST/DELETE 荳ｭ縺ｮ莠碁㍾騾∽ｿ｡髦ｲ豁｢繝輔Λ繧ｰ縲� */
const isVoting = ref(false);

/** 謚慕･ｨ繝懊ち繝ｳ縺ｮ荳ｦ縺ｳ + 濶ｲ繝��繝槫ｮ夂ｾｩ縲ゅユ繝ｳ繝励Ξ蛛ｴ縺ｧ v-for 縺励※謠冗判縺吶ｋ縲� */
const optionTypes = [
  { value: 'REGULAR', label: '豁｣隕�', icon: '笆ｶ', activeBg: 'bg-blue-50 dark:bg-blue-900/30', activeText: 'text-blue-700 dark:text-blue-400', activeBorder: 'border-blue-300 dark:border-blue-700', barColor: 'bg-blue-500', labelColor: 'text-blue-600 dark:text-blue-400' },
  { value: 'MIRROR', label: 'MIRROR', icon: '笳', activeBg: 'bg-purple-50 dark:bg-purple-900/30', activeText: 'text-purple-700 dark:text-purple-400', activeBorder: 'border-purple-300 dark:border-purple-700', barColor: 'bg-purple-500', labelColor: 'text-purple-600 dark:text-purple-400' },
  { value: 'RANDOM', label: 'RANDOM', icon: '軸', activeBg: 'bg-emerald-50 dark:bg-emerald-900/30', activeText: 'text-emerald-700 dark:text-emerald-400', activeBorder: 'border-emerald-300 dark:border-emerald-700', barColor: 'bg-emerald-500', labelColor: 'text-emerald-600 dark:text-emerald-400' },
  { value: 'R-RANDOM', label: 'R-RAN', icon: '楳', activeBg: 'bg-amber-50 dark:bg-amber-900/30', activeText: 'text-amber-700 dark:text-amber-400', activeBorder: 'border-amber-300 dark:border-amber-700', barColor: 'bg-amber-500', labelColor: 'text-amber-600 dark:text-amber-400' },
  { value: 'S-RANDOM', label: 'S-RAN', icon: '鴫', activeBg: 'bg-rose-50 dark:bg-rose-900/30', activeText: 'text-rose-700 dark:text-rose-400', activeBorder: 'border-rose-300 dark:border-rose-700', barColor: 'bg-rose-500', labelColor: 'text-rose-600 dark:text-rose-400' },
];

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 謖�ｮ壹が繝励す繝ｧ繝ｳ縺ｮ謚慕･ｨ謨ｰ繧貞�菴薙�繝代�繧ｻ繝ｳ繝��繧ｸ縺ｧ霑斐☆縲�
 * 繝励Ο繧ｰ繝ｬ繧ｹ繝舌�蟷��邂怜�縺ｫ菴ｿ縺��0 逾ｨ縺ｮ蝣ｴ蜷医� 0 繧定ｿ斐☆�医ぞ繝ｭ髯､邂怜屓驕ｿ�峨�
 */
const getVotePercent = (optionValue: string): number => {
  if (voteData.value.totalVotes === 0) return 0;
  return ((voteData.value.counts[optionValue] || 0) / voteData.value.totalVotes) * 100;
};

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 謖�ｮ夊ｭ憺擇縺ｮ謚慕･ｨ繝��繧ｿ繧貞叙蠕励☆繧九ゅΟ繧ｰ繧､繝ｳ荳ｭ縺ｪ繧� myVote 繧ょ性縺ｾ繧後ｋ縲�
 * 螟ｱ謨励＠縺ｦ繧るｻ呎ｮｺ��UI 繧貞｣翫＆縺ｪ縺�ｼ峨�
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
    // 謠｡繧頑ｽｰ縺�
  }
};

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 蛟句挨繧ｪ繝励す繝ｧ繝ｳ縺ｮ toggle 繧定｡後≧�郁､�焚驕ｸ謚槫ｯｾ蠢懶ｼ峨�
 *   - 閾ｪ蛻�′譌｢縺ｫ縺昴�繧ｪ繝励す繝ｧ繝ｳ縺ｫ謚慕･ｨ縺励※縺�◆蝣ｴ蜷� 竊� 縺昴�繧ｪ繝励す繝ｧ繝ｳ縺�縺代ｒ DELETE
 *   - 縺昴ｌ莉･螟�                                       竊� POST 縺ｧ霑ｽ蜉��井ｻ悶�繧ｪ繝励す繝ｧ繝ｳ縺ｯ貂ｩ蟄假ｼ�
 * 譛蠕後↓ fetchVotes 縺ｧ髮�ｨ医ｒ譛譁ｰ蛹悶☆繧九�
 */
const castVote = async (optionType: string) => {
  if (!selectedRecord.value) return;
  isVoting.value = true;
  try {
    const alreadyVoted = voteData.value.myVotes.includes(optionType);
    if (alreadyVoted) {
      // 縺昴�繧ｪ繝励す繝ｧ繝ｳ縺�縺代ｒ蜿悶ｊ豸医☆縲ゆｻ悶�謚慕･ｨ縺ｫ縺ｯ蠖ｱ髻ｿ縺励↑縺��
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
    // 騾∽ｿ｡蠕後∬�蛻��逾ｨ繧貞性繧√※譛譁ｰ縺ｮ髮�ｨ医ｒ蜀榊叙蠕励�
    await fetchVotes(selectedRecord.value.title, selectedRecord.value.difficultyName);
  } catch {
    // 謠｡繧頑ｽｰ縺�
  } finally {
    isVoting.value = false;
  }
};

/**
 * 縲芯omputed 縺ｮ蠖ｹ蜑ｲ縲� targetBeatPtSlider�育岼讓� BEAT-PT 蠅怜��峨↓蟇ｾ縺励�
 * 縲檎樟繧ｹ繧ｳ繧｢縺九ｉ菴慕せ荳翫￡繧後�逶ｮ讓� PT 縺ｫ蛻ｰ驕斐☆繧九°縲阪ｒ莠悟�謗｢邏｢縺ｧ騾�ｮ励☆繧九�
 *
 * 蜃ｦ逅�ヵ繝ｭ繝ｼ:
 *  謇矩��1: 迴ｾ蝨ｨ PT + 繧ｹ繝ｩ繧､繝繝ｼ蛟､ = 逶ｮ讓� PT縲�
 *  謇矩��2: 謗｢邏｢遽�峇繧� [迴ｾ繧ｹ繧ｳ繧｢, maxScore] 縺ｫ蜿悶ｊ縲。EAT-PT 縺檎岼讓吩ｻ･荳翫↓縺ｪ繧区怙蟆上せ繧ｳ繧｢繧呈爾縺吶�
 *  謇矩��3: (蠢�ｦ√せ繧ｳ繧｢ - 迴ｾ繧ｹ繧ｳ繧｢) 繧定ｿ斐☆縲よ里縺ｫ蛻ｰ驕疲ｸ医∩縺ｪ繧� 0縲�
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
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 陦後け繝ｪ繝�け遲峨〒蜻ｼ縺ｰ繧後∬ｩｳ邏ｰ繝｢繝ｼ繝繝ｫ繧帝幕縺上Ｃody 縺ｮ繧ｹ繧ｯ繝ｭ繝ｼ繝ｫ繧貞崋螳壹☆繧具ｼ郁レ譎ｯ縺悟虚縺九↑縺�ｈ縺�↓�峨�
 */
const openDetailModal = (record: ScoreRecord) => {
  selectedRecord.value = record;
  modalTab.value = 'detail';
  document.body.style.overflow = 'hidden';
};

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 繝｡繝｢邱ｨ髮�ｒ菫晏ｭ倥☆繧九�API 謌仙粥譎ゅ�繝｢繝ｼ繝繝ｫ蜀��陦ｨ遉ｺ繧ょ叉譎ょ渚譏�縲�
 * 螟ｱ謨玲凾縺ｯ繧｢繝ｩ繝ｼ繝医〒騾夂衍縲ゆｿ晏ｭ倅ｸｭ繝輔Λ繧ｰ縺ｧ莠碁㍾騾∽ｿ｡繧帝亟縺舌�
 */

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 隧ｳ邏ｰ繝｢繝ｼ繝繝ｫ繧帝哩縺倥ｋ縲�
 * selectedRecord 繧� null 縺ｫ縺吶ｌ縺ｰ v-if 縺ｧ繝｢繝ｼ繝繝ｫ縺梧ｶ医∴繧九Ｃody 縺ｮ繧ｹ繧ｯ繝ｭ繝ｼ繝ｫ蝗ｺ螳壹ｂ隗｣髯､縲�
 */
const closeDetailModal = () => {
  selectedRecord.value = null;
  document.body.style.overflow = '';
};

/** 隕ｪ縺九ｉ貂｡縺輔ｌ繧� scores 縺悟､峨ｏ縺｣縺滂ｼ医い繝��繝ｭ繝ｼ繝牙ｾ後↑縺ｩ�峨ｉ縲√け繝ｪ繧｢繧ｿ繧､繝鈴寔險医�縺溘ａ縺ｫ蜀榊叙蠕励� */
watch(() => props.scores, () => { fetchSongRanks(); }, { deep: false });

/** 繝ｭ繧ｰ繧､繝ｳ迥ｶ諷九′蠕後°繧臥｢ｺ螳壹☆繧句�ｴ蜷医′縺ゅｋ縺ｮ縺ｧ縲（sLoggedIn 縺� true 縺ｫ驕ｷ遘ｻ縺励◆迸ｬ髢薙↓繧ょ叙蠕励� */
watch(isLoggedIn, (val) => { if (val) fetchSongRanks(); });

/** 繝輔ぅ繝ｫ繧ｿ/繧ｽ繝ｼ繝�/莉ｶ謨ｰ縺ｮ縺ｩ繧後°縺悟､峨ｏ縺｣縺溘ｉ繝壹�繧ｸ逡ｪ蜿ｷ繧� 1 縺ｫ謌ｻ縺呻ｼ�UX 謾ｹ蝟�ｼ峨� */
watch(
  [searchQuery, filterDifficulty, filterLevel, filterDjLevel, filterClearType, filterSource, hideZeroScore, viewMode, sortKey, sortOrder, itemsPerPage],
  () => {
    currentPage.value = 1;
  },
  { deep: true }
);

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 繝峨Ο繝��繝繧ｦ繝ｳ�医ヵ繧｣繝ｫ繧ｿ縺ｮ繝√ぉ繝�け繝懊ャ繧ｯ繧ｹ鄒､�牙､悶け繝ｪ繝�け縺ｧ髢峨§繧九◆繧√�繝上Φ繝峨Λ縲�
 * 蟇ｾ雎｡縺� .relative 繝ｩ繝�ヱ繝ｼ縺ｮ蜀��縺九←縺�°縺ｧ蛻､螳壹＠縺ｦ縺�ｋ縲�
 */
const handleClickOutside = (event: MouseEvent) => {
  if (openDropdown.value && !(event.target as Element).closest('.relative')) {
    openDropdown.value = null;
  }
};

/** 隴憺擇繧ｿ繧､繝励ヰ繝�ず逕ｨ縺ｫ逧ｿ邇� Map 繧貞�譛牙叙蠕励� */
const { scratchPctMap, loadScratchSummary } = useScratchSummary();
/** KENBAN/SARA-Tier 陦ｨ遉ｺ繝医げ繝ｫ�医ヰ繝�ず陦ｨ遉ｺ縺ｮ蜿ｯ蜷ｦ蛻､螳壹↓菴ｿ逕ｨ�峨� */
const { showKenbanSaraTier } = useKenbanSaraTierVisibility();

/** 隴憺擇繧ｿ繧､繝暦ｼ�ANOTHER/LEGGENDARIA 縺九▽ scratchPct 縺悟愛譏弱＠縺ｦ縺�ｋ蝣ｴ蜷医�縺ｿ蛻､螳壹√◎繧御ｻ･螟悶� 'unknown'�峨� */
function recordChartType(record: ScoreRecord): ChartType {
  if (record.difficultyName !== 'ANOTHER' && record.difficultyName !== 'LEGGENDARIA') return 'unknown';
  return getChartType(scratchPctMap.value.get(`${record.title}|${record.difficultyName}`));
}

/** 隴憺擇繧ｿ繧､繝励ヰ繝�ず縺ｮ Tailwind 繧ｯ繝ｩ繧ｹ縲� */
function chartTypeBadgeClass(type: ChartType): string {
  switch (type) {
    case 'kenban':  return 'bg-cyan-100 dark:bg-cyan-900/40 text-cyan-700 dark:text-cyan-300';
    case 'sara':    return 'bg-orange-100 dark:bg-orange-900/40 text-orange-700 dark:text-orange-300';
    case 'balance': return 'bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-300';
    default:        return '';
  }
}
function chartTypeLabel(type: ChartType): string {
  switch (type) {
    case 'kenban':  return '骰ｵ逶､';
    case 'sara':    return '逧ｿ';
    case 'balance': return '�奇ｾ橸ｾ暦ｾ晢ｽｽ';
    default:        return '';
  }
}
function chartTypeTitle(record: ScoreRecord): string {
  const pct = scratchPctMap.value.get(`${record.title}|${record.difficultyName}`);
  return pct == null ? '' : `逧ｿ邇� ${pct.toFixed(1)}%`;
}

/** 繝槭え繝ｳ繝域凾縺ｫ螟悶け繝ｪ繝�け逶｣隕悶ｒ逋ｻ骭ｲ縺励《ongRanks�医け繝ｪ繧｢繧ｿ繧､繝怜挨髮�ｨ茨ｼ峨→逧ｿ邇�し繝槭Μ繧貞叙蠕励� */
onMounted(() => {
  window.addEventListener('click', handleClickOutside);
  fetchSongRanks();
  // KENBAN/SARA-Tier 繝医げ繝ｫ縺� ON 縺ｮ繝ｦ繝ｼ繧ｶ繝ｼ縺ｮ縺ｿ逧ｿ邇� Map 繧貞叙蠕暦ｼ医ヰ繝�ず陦ｨ遉ｺ縺ｫ蠢�ｦ�ｼ峨�
  if (showKenbanSaraTier.value) loadScratchSummary();
});
// 繝医げ繝ｫ縺悟ｾ後°繧� ON 縺ｫ蛻�ｊ譖ｿ繧上▲縺溷�ｴ蜷医ｂ lazy 繝ｭ繝ｼ繝峨☆繧九�
watch(showKenbanSaraTier, (val) => { if (val) loadScratchSummary(); });

/** 繧｢繝ｳ繝槭え繝ｳ繝域凾縺ｫ繧､繝吶Φ繝医Μ繧ｹ繝願ｧ｣髯､ + 荳�ｸ谿九▲縺ｦ縺�ｋ body 縺ｮ繧ｹ繧ｯ繝ｭ繝ｼ繝ｫ蝗ｺ螳壹ｒ隗｣髯､縲� */
onUnmounted(() => {
  window.removeEventListener('click', handleClickOutside);
  document.body.style.overflow = '';
});

/**
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 繧ｽ繝ｼ繝亥�繝倥ャ繝繧ｯ繝ｪ繝�け譎ゅ�蜃ｦ逅��
 *   - 蜷後§蛻励ｒ蜀阪け繝ｪ繝�け: 譏���/髯埼��ｒ繝医げ繝ｫ
 *   - 蛻･縺ｮ蛻励ｒ繧ｯ繝ｪ繝�け:   蛻励ｒ蛻�崛縺医√ョ繝輔か繝ｫ繝医�蜷代″�医せ繧ｳ繧｢邉ｻ縺ｯ desc / 繝ｩ繝ｳ繧ｯ縺ｯ asc�峨ｒ險ｭ螳�
 */
const toggleSort = (key: SortKey) => {
  if (sortKey.value === key) {
    // 譌｢縺ｫ驕ｸ謚樔ｸｭ縺ｮ蛻励↑繧� asc/desc 繧貞渚霆｢縲�
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc';
  } else {
    sortKey.value = key;
    // 蛻励＃縺ｨ縺ｮ譌｢螳壹た繝ｼ繝亥髄縺搾ｼ医せ繧ｳ繧｢/PT/繧ｯ繝ｪ繧｢繧ｿ繧､繝�/谿ｵ髫弱�髯埼��√Λ繝ｳ繧ｭ繝ｳ繧ｰ縺ｯ譏���ｼ峨�
    if (key === 'scoreRate' || key === 'informalRank' || key === 'beatTierPoints' || key === 'clearType' || key === 'djLevel' || key === 'unofficialSongRank') {
        sortOrder.value = 'desc';
    } else if (key === 'songRank') {
        sortOrder.value = 'asc';
    } else {
        sortOrder.value = 'asc';
    }
  }
};

/**
 * 縲芯omputed 縺ｮ蠖ｹ蜑ｲ縲� 蜈ｨ繝ｬ繧ｳ繝ｼ繝峨↓蟇ｾ縺励∫ｵ槭ｊ霎ｼ縺ｿ + 讀懃ｴ｢ + 繧ｽ繝ｼ繝医ｒ驕ｩ逕ｨ縺励◆譛邨ゅΜ繧ｹ繝医ｒ霑斐☆縲�
 *
 * 蜃ｦ逅�ヵ繝ｭ繝ｼ:
 *  謇矩��1: 繝｢繝ｼ繝会ｼ磯壼ｸｸ / rate�峨↓蠢懊§縺溘�繝ｼ繧ｹ繝ｪ繧ｹ繝医ｒ隍�｣ｽ縲�
 *  謇矩��2: hideZeroScore / difficulty / level / djLevel / clearType 縺ｮ繝輔ぅ繝ｫ繧ｿ繧帝��ｬ｡驕ｩ逕ｨ縲�
 *  謇矩��3: 讀懃ｴ｢繝ｯ繝ｼ繝峨〒 title / artist / genre / clearType 縺ｮ驛ｨ蛻�ｸ閾ｴ繝輔ぅ繝ｫ繧ｿ縲�
 *  謇矩��4: sortKey 縺斐→縺ｫ蟆ら畑縺ｮ繧ｽ繝ｼ繝域ｯ碑ｼ�未謨ｰ繧帝←逕ｨ縲�
 *         - informalRank: 譛ｫ蟆ｾ縺ｮ謨ｰ蛟､�井ｾ� "12.5"�峨ｒ謚ｽ蜃ｺ縺励※豈碑ｼ�∵ｬ｡轤ｹ縺ｧ difficultyLevel 竊� title縲�
 *         - beatTierPoints: rate 繝｢繝ｼ繝峨〒縺ｯ scoreRateTierPoints 繧貞�險育ｮ励＠縺ｦ豈碑ｼ��
 *         - clearType:   clearTypeRankings 縺ｧ螳壹ａ縺滄��ｽ阪ユ繝ｼ繝悶Ν縺ｧ豈碑ｼ��
 *         - djLevel:     AAA竊巽竊�--- 縺ｮ鬆�ｽ阪�繝��縺ｧ豈碑ｼ��
 *         - songRank:    譛ｪ遏･縺ｯ 999999 謇ｱ縺�〒譛ｫ蟆ｾ騾√ｊ縲�
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
      // 蜷御ｸ繝ｩ繝ｳ繧ｯ蜀�〒縺ｯ繝ｬ繝ｼ繝磯剄鬆�〒螳牙ｮ壹＆縺帙ｋ
      const rateA = a.scoreRate >= 0 ? a.scoreRate : -2;
      const rateB = b.scoreRate >= 0 ? b.scoreRate : -2;
      return sortOrder.value === 'asc' ? rateA - rateB : rateB - rateA;
    });
  }

  return result;
});

/**
 * 縲陣atch 縺ｮ蠖ｹ蜑ｲ縲� 繝｢繝ｼ繝会ｼ磯壼ｸｸ/rate�牙�譖ｿ譎ゅ↓繝壹�繧ｸ逡ｪ蜿ｷ繝ｻ繝ｬ繝吶Ν繝輔ぅ繝ｫ繧ｿ繝ｻ繧ｽ繝ｼ繝医ｒ蛻晄悄蛹悶�
 * 繝｢繝ｼ繝峨′螟峨ｏ繧九→陦ｨ遉ｺ繝ｬ繧ｳ繝ｼ繝峨�遞ｮ鬘槭′螟峨ｏ繧九◆繧√√Θ繝ｼ繧ｶ繝ｼ譛溷ｾ�↓蜷医ｏ縺帙※繝ｪ繧ｻ繝�ヨ縺吶ｋ縲�
 */
watch(viewMode, () => {
  currentPage.value = 1;
  filterLevel.value = [];
  sortKey.value = 'beatTierPoints';
  sortOrder.value = 'desc';
});

/** 蜈ｨ繝壹�繧ｸ謨ｰ�域怙菴� 1�峨�Math.ceil 縺ｧ遶ｯ謨ｰ蛻�ｊ荳翫￡縲� */
const totalPages = computed(() => Math.ceil(filteredScores.value.length / itemsPerPage.value) || 1);

/** 迴ｾ蝨ｨ繝壹�繧ｸ縺ｫ陦ｨ遉ｺ縺吶ｋ陦後�縺ｿ繧偵せ繝ｩ繧､繧ｹ縺励◆陦ｨ遉ｺ逕ｨ繝ｪ繧ｹ繝医� */
const displayScores = computed(() => {
  const start = (currentPage.value - 1) * itemsPerPage.value;
  const end = start + itemsPerPage.value;
  return filteredScores.value.slice(start, end);
});

/** 繝壹�繧ｸ繝阪�繧ｷ繝ｧ繝ｳ: 蜑阪�繝ｼ繧ｸ縺ｸ遘ｻ蜍輔�1 繝壹�繧ｸ逶ｮ縺ｧ縺ｯ菴輔ｂ縺励↑縺�� */
const prevPage = () => {
  if (currentPage.value > 1) currentPage.value--;
};

/** 繝壹�繧ｸ繝阪�繧ｷ繝ｧ繝ｳ: 谺｡繝壹�繧ｸ縺ｸ遘ｻ蜍輔よ怙邨ゅ�繝ｼ繧ｸ縺ｧ縺ｯ菴輔ｂ縺励↑縺�� */
const nextPage = () => {
  if (currentPage.value < totalPages.value) currentPage.value++;
};

// --- 濶ｲ繝ｦ繝ｼ繝�ぅ繝ｪ繝�ぅ ---
/** 繧ｯ繝ｪ繧｢繧ｿ繧､繝励＃縺ｨ縺ｮ譁�ｭ苓牡�医ユ繝ｼ繝悶Ν陦ｨ遉ｺ逕ｨ�峨ゅム繝ｼ繧ｯ繝｢繝ｼ繝牙ｯｾ蠢懊� */
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

/** 繧ｯ繝ｪ繧｢繧ｿ繧､繝励＃縺ｨ縺ｮ閭梧勹濶ｲ�医ヰ繝�ず繝ｻ騾ｲ謐励ヰ繝ｼ逕ｨ�峨� */
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
 * 縲宣未謨ｰ縺ｮ蠖ｹ蜑ｲ縲� 繝��繝悶Ν荳翫〒繧ｹ繧ｳ繧｢縺ｮ縲瑚ｿ代＆縲阪ｒ遉ｺ縺� 2 谿ｵ繝ｩ繝吶Ν繧剃ｽ懊ｋ縲�
 *
 * 蛻､螳壼渕貅�:
 *  - scoreRate >= 94.45% (MAX 縺ｫ霑代＞)  竊� primary: MAX-谿句ｷｮ / secondary: AAA+蟾ｮ蛻�
 *  - scoreRate >= 88.89% (AAA 莉･荳�)    竊� primary: AAA+蟾ｮ蛻� / secondary: MAX-谿句ｷｮ
 *  - 縺昴ｌ莉･荳�                          竊� primary: AAA-荳崎ｶｳ / secondary: AAﾂｱ蟾ｮ蛻�
 * 繧ｹ繧ｳ繧｢譛ｪ繝励Ξ繧､��<= 0�峨ｄ maxScore 荳肴�縺ｮ蝣ｴ蜷医� null 繧定ｿ斐＠縲√ユ繝ｳ繝励Ξ縺ｯ菴輔ｂ陦ｨ遉ｺ縺励↑縺��
 */
const getScoreGradeLabel = (record: ScoreRecord) => {
  if (record.maxScore <= 0 || record.scoreRate < 0 || record.score <= 0) return null;
  const maxScore = record.maxScore;
  const score = record.score;
  // AAA/AA 縺ｮ髢ｾ蛟､縺ｯ 8/9, 7/9 縺ｧ蛻�ｊ荳翫￡��IIDX 蜈ｬ蠑丈ｻ墓ｧ假ｼ峨�
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

/** DJ LEVEL��AAA/AA/A/...�峨�譁�ｭ苓牡縲ゅム繝ｼ繧ｯ繝｢繝ｼ繝峨〒譏主ｺｦ繧剃ｸ翫￡繧九� */
const getDjLevelColor = (djLevel: string) => {
  switch (djLevel) {
    case 'AAA': return isDarkMode.value ? 'text-amber-400' : 'text-amber-500';
    case 'AA': return isDarkMode.value ? 'text-yellow-400' : 'text-yellow-500';
    case 'A': return isDarkMode.value ? 'text-emerald-400' : 'text-emerald-500';
    default: return isDarkMode.value ? 'text-slate-500' : 'text-slate-400';
  }
};

/** DJ LEVEL 縺ｮ閭梧勹濶ｲ縲ゅヰ繝�ず繝ｻ騾ｲ謐励ヰ繝ｼ逕ｨ縲� */
const getDjLevelBgColor = (djLevel: string) => {
  switch (djLevel) {
    case 'AAA': return 'bg-amber-500';
    case 'AA': return 'bg-yellow-500';
    case 'A': return 'bg-emerald-500';
    default: return isDarkMode.value ? 'bg-slate-700' : 'bg-slate-200';
  }
};

/**
 * 縲仙､夜Κ蜈ｬ髢九� 譖ｲ蜷阪°繧峨Ξ繧ｳ繝ｼ繝峨ｒ蠑輔＞縺ｦ隧ｳ邏ｰ繝｢繝ｼ繝繝ｫ繧帝幕縺上�
 * OCR 繧ｫ繝｡繝ｩ讀懃ｴ｢縺ｪ縺ｩ縺ｮ螟夜Κ蟆守ｷ壹°繧牙他縺ｰ繧後ｋ縲ＢllRecords 縺ｯ 笘�11/笘�12 ANOTHER/LEGGENDARIA
 * 縺ｮ縺ｿ繧貞性繧縺溘ａ縲∫ｯ�峇螟悶�譖ｲ縺ｯ隕九▽縺九ｉ縺� false 繧定ｿ斐☆�亥他縺ｳ蜃ｺ縺怜�縺ｧ縲碁幕縺九↑縺�榊愛譁ｭ縺ｫ菴ｿ縺医ｋ�峨�
 * ANOTHER 竊� LEGGENDARIA 竊� 縺昴�莉� 縺ｮ鬆�〒蜆ｪ蜈医＠縺ｦ繝槭ャ繝√＆縺帙ｋ縲�
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
