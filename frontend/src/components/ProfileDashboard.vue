<template>
  <div class="w-full space-y-6 animate-fade-in">

    <!-- 成長軌跡 -->
    <div class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100 mb-1">{{ t('dashboard.title') }}</h2>
      <p class="text-slate-500 dark:text-slate-400 text-sm mb-6">{{ t('dashboard.subtitle') }}</p>

      <div v-if="isLoading" class="flex flex-col items-center justify-center py-12">
        <div class="w-10 h-10 border-4 border-blue-200 dark:border-blue-900 border-t-blue-600 dark:border-t-blue-500 rounded-full animate-spin mb-4"></div>
        <p class="text-slate-500 dark:text-slate-400 font-medium">{{ t('dashboard.loading') }}</p>
      </div>

      <div v-else-if="historyData.length === 0" class="py-12 text-center border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-xl">
        <p class="text-slate-500 dark:text-slate-400 font-medium">{{ t('dashboard.noData') }}<br/>{{ t('dashboard.noDataHint') }}</p>
      </div>

      <div v-else class="space-y-10">
        <!-- 成長サマリー -->
        <div>
          <div class="section-header">
            <div class="w-1 h-5 bg-violet-500 rounded-full"></div>
            <h3 class="font-bold text-slate-700 dark:text-slate-200">{{ t('dashboard.summary') }}</h3>
          </div>
          <div class="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-7 gap-3">
            <div class="stat-card border-blue-100 dark:border-slate-600 bg-blue-50/50 dark:bg-slate-700/50">
              <span class="text-[9px] font-bold text-blue-500 dark:text-blue-400 uppercase tracking-widest mb-1">{{ t('dashboard.snapshot') }}</span>
              <span class="text-2xl font-black text-slate-700 dark:text-slate-200">{{ historyData.length }}</span>
            </div>
            <div class="stat-card border-violet-100 dark:border-slate-600 bg-violet-50/50 dark:bg-slate-700/50">
              <span class="text-[9px] font-bold text-violet-500 dark:text-violet-400 uppercase tracking-widest mb-1">{{ t('dashboard.latestBeatPt') }}</span>
              <span class="text-xl font-black text-slate-700 dark:text-slate-200">{{ latestBeatPt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}</span>
            </div>
            <div class="stat-card border-amber-100 dark:border-slate-600 bg-amber-50/50 dark:bg-slate-700/50">
              <span class="text-[9px] font-bold text-amber-500 dark:text-amber-400 uppercase tracking-widest mb-1">{{ t('dashboard.totalExScore') }}</span>
              <span class="text-lg font-black text-slate-700 dark:text-slate-200">{{ latestTotalScore.toLocaleString() }}</span>
            </div>
            <div class="stat-card border-yellow-100 dark:border-slate-600 bg-yellow-50/50 dark:bg-slate-700/50">
              <span class="text-[9px] font-bold text-yellow-600 dark:text-yellow-400 uppercase tracking-widest mb-1">{{ t('dashboard.latestAaa') }}</span>
              <span class="text-2xl font-black text-slate-700 dark:text-slate-200">{{ latestAaaCount }}</span>
            </div>
            <div class="stat-card border-emerald-100 dark:border-slate-600 bg-emerald-50/50 dark:bg-slate-700/50">
              <span class="text-[9px] font-bold text-emerald-500 dark:text-emerald-400 uppercase tracking-widest mb-1">{{ t('dashboard.latestFc') }}</span>
              <span class="text-2xl font-black text-slate-700 dark:text-slate-200">{{ latestFcCount }}</span>
            </div>
            <div class="stat-card border-purple-100 dark:border-slate-600 bg-purple-50/50 dark:bg-slate-700/50">
              <span class="text-[9px] font-bold text-purple-500 dark:text-purple-400 uppercase tracking-widest mb-1">{{ t('dashboard.avgIncrease') }}</span>
              <span class="text-xl font-black text-slate-700 dark:text-slate-200">{{ avgBeatPtIncrease.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}</span>
            </div>
            <div class="stat-card border-indigo-100 dark:border-slate-600 bg-indigo-50/50 dark:bg-slate-700/50">
              <span class="text-[9px] font-bold text-indigo-500 dark:text-indigo-400 uppercase tracking-widest mb-1">{{ t('dashboard.maxIncrease') }}</span>
              <span class="text-xl font-black text-slate-700 dark:text-slate-200">{{ maxBeatPtIncrease.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}</span>
            </div>
          </div>
        </div>

        <!-- 時系列推移 -->
        <div>
          <div class="section-header">
            <div class="w-1 h-5 bg-blue-500 rounded-full"></div>
            <h3 class="font-bold text-slate-700 dark:text-slate-200">{{ t('dashboard.trends') }}</h3>
          </div>
          <div class="grid grid-cols-1 lg:grid-cols-2 gap-5">
            <div class="chart-card lg:col-span-2">
              <h4 class="chart-title">{{ t('dashboard.beatPtTrend') }}</h4>
              <div class="h-56"><LineChart v-if="beatPtChartData" :data="beatPtChartData" :options="lineOpts" /></div>
            </div>
            <div class="chart-card lg:col-span-2">
              <h4 class="chart-title">{{ t('dashboard.increaseTrend') }}</h4>
              <div class="h-44"><BarChart v-if="uploadIncreaseChartData" :data="uploadIncreaseChartData" :options="barOpts" /></div>
            </div>
            <div class="chart-card">
              <h4 class="chart-title">{{ t('dashboard.scoreTrend') }}</h4>
              <div class="h-44"><LineChart v-if="scoreChartData" :data="scoreChartData" :options="lineOpts" /></div>
            </div>
            <div class="chart-card">
              <h4 class="chart-title">{{ t('dashboard.djLevelTrend') }}</h4>
              <div class="h-44"><LineChart v-if="djLevelTrendData" :data="djLevelTrendData" :options="lineOpts" /></div>
            </div>
            <div class="chart-card lg:col-span-2">
              <h4 class="chart-title">{{ t('dashboard.clearTypeTrend') }}</h4>
              <div class="h-44"><LineChart v-if="clearChartData" :data="clearChartData" :options="lineOpts" /></div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- スコア分析 -->
    <div v-if="myAnotherLegg.length > 0" class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <div class="flex items-center justify-between mb-4">
        <div>
          <h2 class="text-xl font-bold text-slate-800 dark:text-slate-100">{{ t('dashboard.analysis') }}</h2>
          <p class="text-sm text-slate-500 dark:text-slate-400">{{ t('dashboard.analysisHint', { n: myScoresActive.length }) }}</p>
        </div>
        <div v-if="avgPgreatRate !== null" class="text-right">
          <span class="text-[9px] font-bold text-slate-400 uppercase tracking-widest block">{{ t('dashboard.avgPgreatRate') }}</span>
          <span class="text-2xl font-black text-slate-700 dark:text-slate-200">{{ avgPgreatRate }}%</span>
        </div>
      </div>

      <!-- Level filter -->
      <div class="flex items-center gap-3 mb-6 pb-4 border-b border-slate-100 dark:border-slate-700">
        <span class="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-widest">{{ t('dashboard.targetLevel') }}</span>
        <div class="flex items-center bg-slate-100 dark:bg-slate-800 p-0.5 rounded-lg border border-slate-200 dark:border-slate-700">
          <button
            v-for="lvl in ['ALL', '11', '12']" :key="lvl"
            @click="selectedAnalysisLevel = lvl as 'ALL' | '11' | '12'"
            class="px-3 py-1 text-xs font-bold rounded-md transition-all"
            :class="selectedAnalysisLevel === lvl
              ? 'bg-white dark:bg-slate-700 text-blue-600 dark:text-blue-400 shadow-sm'
              : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300'"
          >{{ lvl === 'ALL' ? t('common.all') : `☆${lvl}` }}</button>
        </div>
      </div>

      <div class="space-y-8">
        <!-- クリアタイプ + DJレベル + スコアレート分布 -->
        <div>
          <div class="section-header">
            <div class="w-1 h-5 bg-emerald-500 rounded-full"></div>
            <h3 class="font-bold text-slate-700 dark:text-slate-200">{{ t('dashboard.clearStatus') }}</h3>
          </div>
          <div class="grid grid-cols-1 lg:grid-cols-2 gap-5">
            <div class="chart-card">
              <h4 class="chart-title">{{ t('dashboard.clearTypeDist') }}</h4>
              <div class="h-52"><DoughnutChart v-if="clearTypeDoughnut" :data="clearTypeDoughnut" :options="doughnutOpts" /></div>
            </div>
            <div class="chart-card">
              <h4 class="chart-title">{{ t('dashboard.djLevelDist') }}</h4>
              <div class="h-52"><BarChart v-if="djLevelCurrentData" :data="djLevelCurrentData" :options="barOpts" /></div>
            </div>
            <div class="chart-card lg:col-span-2">
              <h4 class="chart-title">{{ t('dashboard.scoreRateDist') }} <span class="text-[10px] font-normal text-slate-400 ml-2">{{ t('dashboard.clickForList') }}</span></h4>
              <div class="h-44"><BarChart v-if="scoreRateHistData" :data="scoreRateHistData" :options="scoreRateHistOpts" /></div>
            </div>
            <div class="chart-card lg:col-span-2">
              <h4 class="chart-title">{{ t('dashboard.songRankDist') }}</h4>
              <div class="h-44"><BarChart v-if="songRankDistData" :data="songRankDistData" :options="songRankBarOpts" /></div>
            </div>
          </div>
        </div>

        <!-- 非公式難易度別クリア状況 -->
        <div>
          <div class="section-header">
            <div class="w-1 h-5 bg-amber-500 rounded-full"></div>
            <h3 class="font-bold text-slate-700 dark:text-slate-200">{{ t('dashboard.informalClearStatus') }}</h3>
          </div>
          <div class="overflow-x-auto">
            <table class="w-full text-sm">
              <thead>
                <tr class="border-b border-slate-100 dark:border-slate-700 text-xs font-black uppercase tracking-wide">
                  <th class="pb-3 pl-2 text-left text-slate-400">{{ t('dashboard.rank') }}</th>
                  <th class="pb-3 text-center text-emerald-500">FC</th>
                  <th class="pb-3 text-center text-amber-500">EXH</th>
                  <th class="pb-3 text-center text-red-500">HARD</th>
                  <th class="pb-3 text-center text-blue-500">CLEAR</th>
                  <th class="pb-3 text-center text-green-500">EASY</th>
                  <th class="pb-3 text-center text-slate-400">{{ t('common.other') }}</th>
                  <th class="pb-3 text-center text-slate-400">{{ t('common.total') }}</th>
                  <th class="pb-3 pr-2 text-right text-slate-400">{{ t('dashboard.clearRate') }}</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
                <tr v-for="row in informalRankStats" :key="row.rank" class="hover:bg-slate-50 dark:hover:bg-slate-700/20 transition-colors">
                  <td class="py-2 pl-2 font-black text-slate-700 dark:text-slate-200">{{ row.rank }}</td>
                  <td class="py-2 text-center font-bold text-emerald-600 dark:text-emerald-400">{{ row.fc || '-' }}</td>
                  <td class="py-2 text-center font-bold text-amber-600 dark:text-amber-400">{{ row.exh || '-' }}</td>
                  <td class="py-2 text-center font-bold text-red-600 dark:text-red-400">{{ row.hard || '-' }}</td>
                  <td class="py-2 text-center font-bold text-blue-600 dark:text-blue-400">{{ row.clear || '-' }}</td>
                  <td class="py-2 text-center font-bold text-green-600 dark:text-green-400">{{ row.easy || '-' }}</td>
                  <td class="py-2 text-center text-slate-400">{{ row.other || '-' }}</td>
                  <td class="py-2 text-center text-slate-500 dark:text-slate-400">{{ row.total }}</td>
                  <td class="py-2 pr-2">
                    <div class="flex items-center justify-end gap-2">
                      <div class="w-16 h-1.5 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
                        <div class="h-full bg-emerald-500 rounded-full transition-all" :style="{ width: `${Math.round((row.fc + row.exh + row.hard + row.clear + row.easy) / row.total * 100)}%` }"></div>
                      </div>
                      <span class="text-xs font-bold text-slate-500 dark:text-slate-400 tabular-nums w-9 text-right">{{ Math.round((row.fc + row.exh + row.hard + row.clear + row.easy) / row.total * 100) }}%</span>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- BEAT-PT上位100曲の難易度分布 -->
        <div>
          <div class="section-header">
            <div class="w-1 h-5 bg-indigo-500 rounded-full"></div>
            <h3 class="font-bold text-slate-700 dark:text-slate-200">{{ t('dashboard.top100Dist') }}</h3>
          </div>
          <div class="chart-card">
            <h4 class="chart-title">{{ t('dashboard.top100CountByType') }}</h4>
            <div class="h-44"><BarChart v-if="top100DiffHistData" :data="top100DiffHistData" :options="barOpts" /></div>
          </div>
        </div>

        <!-- BEAT-PT上位10曲 -->
        <div>
          <div class="section-header">
            <div class="w-1 h-5 bg-violet-500 rounded-full"></div>
            <h3 class="font-bold text-slate-700 dark:text-slate-200">{{ t('dashboard.top10') }}</h3>
          </div>
          <div class="overflow-x-auto">
            <table class="w-full text-sm">
              <thead>
                <tr class="border-b border-slate-100 dark:border-slate-700 text-xs font-black uppercase text-slate-400">
                  <th class="pb-3 pl-2 text-left w-6">#</th>
                  <th class="pb-3 text-left">{{ t('table.colTitle') }}</th>
                  <th class="pb-3 text-center w-16">{{ t('dashboard.rank') }}</th>
                  <th class="pb-3 text-center w-10">☆</th>
                  <th class="pb-3 text-right w-20">{{ t('table.colRate') }}</th>
                  <th class="pb-3 pr-2 text-right w-24 text-violet-500">BEAT-PT</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
                <tr v-for="(s, i) in beatPtTop10" :key="`${s.title}_${s.difficultyName}`" class="hover:bg-slate-50 dark:hover:bg-slate-700/20 transition-colors">
                  <td class="py-2 pl-2 text-slate-400 font-bold text-xs">{{ i + 1 }}</td>
                  <td class="py-2">
                    <div class="font-bold text-slate-700 dark:text-slate-200 truncate max-w-[200px] sm:max-w-xs">{{ s.title }}</div>
                    <div class="text-xs text-slate-400">{{ s.difficultyName }}</div>
                  </td>
                  <td class="py-2 text-center text-xs font-bold text-slate-500 dark:text-slate-400">{{ s.informalRank || '-' }}</td>
                  <td class="py-2 text-center font-bold text-slate-600 dark:text-slate-300">{{ s.difficultyLevel }}</td>
                  <td class="py-2 text-right font-mono text-xs text-slate-600 dark:text-slate-300">{{ s.maxScore > 0 ? s.scoreRate.toFixed(2) + '%' : '-' }}</td>
                  <td class="py-2 pr-2 text-right font-black text-violet-600 dark:text-violet-400 tabular-nums">{{ s.beatPt.toFixed(2) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>

    <!-- Score Rate Band Modal -->
    <Teleport to="body">
      <div v-if="histModalOpen" class="fixed inset-0 z-50 flex items-center justify-center p-4" @click.self="histModalOpen = false">
        <div class="absolute inset-0 bg-black/50 backdrop-blur-sm"></div>
        <div class="relative bg-white dark:bg-slate-800 rounded-2xl shadow-2xl border border-slate-200 dark:border-slate-700 w-full max-w-2xl max-h-[80vh] flex flex-col">
          <div class="flex items-center justify-between px-6 py-4 border-b border-slate-100 dark:border-slate-700">
            <div>
              <h3 class="font-bold text-slate-800 dark:text-slate-100">{{ histModalLabel }}</h3>
              <p class="text-xs text-slate-400 mt-0.5">{{ histModalSongs.length }} 曲</p>
            </div>
            <button @click="histModalOpen = false" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition-colors p-1 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700">
              <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
            </button>
          </div>
          <div class="overflow-y-auto flex-1">
            <table class="w-full text-sm">
              <thead class="sticky top-0 bg-white dark:bg-slate-800 border-b border-slate-100 dark:border-slate-700">
                <tr class="text-xs font-black uppercase text-slate-400 tracking-wide">
                  <th class="pb-3 pt-3 pl-6 text-left">{{ t('table.colTitle') }}</th>
                  <th class="pb-3 pt-3 text-center w-14">☆</th>
                  <th class="pb-3 pt-3 text-center w-14">{{ t('table.colInformal') }}</th>
                  <th class="pb-3 pt-3 text-right w-24">{{ t('table.exScore') }}</th>
                  <th class="pb-3 pt-3 text-right pr-6 w-32">{{ t('table.nextBand') }}</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
                <tr v-for="s in histModalSongs" :key="`${s.title}_${s.difficultyName}`"
                  class="hover:bg-slate-50 dark:hover:bg-slate-700/20 transition-colors">
                  <td class="py-2 pl-6">
                    <div class="font-bold text-slate-700 dark:text-slate-200 truncate max-w-[200px]">{{ s.title }}</div>
                    <div class="text-xs text-slate-400">{{ s.difficultyName }}</div>
                  </td>
                  <td class="py-2 text-center font-bold text-slate-600 dark:text-slate-300 text-xs">{{ s.difficultyLevel }}</td>
                  <td class="py-2 text-center text-xs font-bold text-slate-500 dark:text-slate-400">{{ s.informalRank || '-' }}</td>
                  <td class="py-2 text-right font-mono text-xs text-slate-600 dark:text-slate-300 tabular-nums">
                    <div>{{ s.score.toLocaleString() }}</div>
                    <div class="text-slate-400 text-[10px]">{{ s.scoreRate.toFixed(2) }}%</div>
                  </td>
                  <td class="py-2 pr-6 text-right text-xs tabular-nums">
                    <template v-if="s.nextBandPts !== null && s.nextBandPts > 0">
                      <div class="font-bold text-blue-600 dark:text-blue-400">{{ s.nextBandLabel }}</div>
                      <div class="text-slate-400 text-[10px]">{{ t('dashboard.remainingPoints', { n: s.nextBandPts.toLocaleString() }) }}</div>
                    </template>
                    <template v-else>
                      <span class="text-purple-500 font-bold text-[10px]">{{ t('table.achieved') }}</span>
                    </template>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- URL 共有 -->
    <div v-if="!props.viewingUserId && !props.shareToken" class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <h3 class="text-lg font-bold text-slate-800 dark:text-slate-100 mb-2 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-blue-500" viewBox="0 0 20 20" fill="currentColor">
          <path d="M15 8a3 3 0 10-2.977-2.63l-4.94 2.47a3 3 0 100 4.319l4.94 2.47a3 3 0 10.895-1.789l-4.94-2.47a3.027 3.027 0 000-.74l4.94-2.47C13.456 7.68 14.19 8 15 8z" />
        </svg>
        URL共有
      </h3>
      <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">
        ログインしていない人に向けて、ダッシュボードやスコア一覧を期間限定で公開できます。
      </p>
      <button
        type="button"
        @click="isShareModalOpen = true"
        class="px-6 py-2.5 rounded-xl font-bold text-sm bg-blue-600 hover:bg-blue-700 text-white active:scale-95 transition-all shadow-sm"
      >
        共有 URL を管理
      </button>
    </div>

    <ShareTokenModal :is-open="isShareModalOpen" @close="isShareModalOpen = false" />

    <!-- 外部連携トークン -->
    <div v-if="!props.viewingUserId && !props.shareToken" class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <h3 class="text-lg font-bold text-slate-800 dark:text-slate-100 mb-2 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-blue-500" viewBox="0 0 20 20" fill="currentColor">
          <path fill-rule="evenodd" d="M12.586 4.586a2 2 0 112.828 2.828l-3 3a2 2 0 01-2.828 0 1 1 0 00-1.414 1.414 4 4 0 005.656 0l3-3a4 4 0 00-5.656-5.656l-1.5 1.5a1 1 0 101.414 1.414l1.5-1.5zm-5 5a2 2 0 012.828 0 1 1 0 101.414-1.414 4 4 0 00-5.656 0l-3 3a4 4 0 105.656 5.656l1.5-1.5a1 1 0 10-1.414-1.414l-1.5 1.5a2 2 0 11-2.828-2.828l3-3z" clip-rule="evenodd" />
        </svg>
        外部連携トークン
      </h3>
      <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">
        iidx-memo など連携先アプリの設定欄に貼り付けて使うトークンを発行・管理できます。
      </p>
      <button
        type="button"
        @click="isIntegrationModalOpen = true"
        class="px-6 py-2.5 rounded-xl font-bold text-sm bg-blue-600 hover:bg-blue-700 text-white active:scale-95 transition-all shadow-sm"
      >
        連携トークンを管理
      </button>
    </div>

    <IntegrationTokenModal :is-open="isIntegrationModalOpen" @close="isIntegrationModalOpen = false" />

    <!-- 通知設定 -->
    <div v-if="!props.viewingUserId && !props.shareToken" class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <h3 class="text-lg font-bold text-slate-800 dark:text-slate-100 mb-2 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-blue-500" viewBox="0 0 20 20" fill="currentColor">
          <path d="M10 2a6 6 0 00-6 6v3.586l-.707.707A1 1 0 004 14h12a1 1 0 00.707-1.707L16 11.586V8a6 6 0 00-6-6zM10 18a3 3 0 01-3-3h6a3 3 0 01-3 3z" />
        </svg>
        {{ t('dashboard.notifications') }}
      </h3>
      <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">
        {{ t('dashboard.notificationsHint') }}
        <br/>{{ t('dashboard.iosPwaHint') }}
      </p>
      <div class="flex items-center gap-4">
        <button
          @click="handleEnableNotifications"
          :disabled="isSubscribing || notificationStatus === 'granted'"
          class="px-6 py-2.5 rounded-xl font-bold text-sm transition-all duration-200 flex items-center gap-2 shadow-sm"
          :class="notificationStatus === 'granted'
            ? 'bg-emerald-100 dark:bg-emerald-500/20 text-emerald-600 dark:text-emerald-400 cursor-default'
            : 'bg-blue-600 hover:bg-blue-700 text-white active:scale-95 disabled:opacity-50'"
        >
          <span v-if="isSubscribing" class="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin"></span>
          <span v-else-if="notificationStatus === 'granted'">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clip-rule="evenodd" />
            </svg>
          </span>
          {{ notificationStatus === 'granted' ? t('dashboard.notificationsEnabled') : t('dashboard.enableNotifications') }}
        </button>
        <button v-if="notificationStatus === 'granted'"
          @click="handleTestNotification"
          :disabled="isTesting"
          class="px-4 py-2 bg-slate-100 hover:bg-slate-200 dark:bg-slate-700 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 rounded-lg text-sm font-bold transition-colors disabled:opacity-50"
        >
          <span v-if="isTesting">{{ t('dashboard.sending') }}</span>
          <span v-else>{{ t('dashboard.testNotification') }}</span>
        </button>
        <span class="text-xs text-slate-400 dark:text-slate-500">
          {{ t('dashboard.currentStatus') }}: {{ notificationStatus === 'granted' ? t('dashboard.statusGranted') : notificationStatus === 'denied' ? t('dashboard.statusDenied') : t('dashboard.statusDefault') }}
        </span>
      </div>
    </div>

  </div>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 プロフィールダッシュボード。成長軌跡（時系列推移）とスコア分析をまとめて描画する。
 * - ユーザーの履歴スナップショットから Beat-PT・EX スコア・AAA 数・FC 数などの推移を Chart.js で可視化
 * - 最新スナップショットに基づき現在値カード（成長サマリー）を表示
 * - 日単位に集約（同日の複数アップロードは最終値を採用、増加量は合算）
 * - スコア分析ブロックでは ANOTHER/LEGGENDARIA の全曲をグルーピングして PGREAT 率、非公式難易度別統計などを計算
 * - プッシュ通知の許可要求・テスト送信ボタンも内包（ログイン中ユーザー本人のみ）
 *
 * @prop viewingUserId null/undefined なら自分のプロフィール、数値なら管理者が他ユーザーを閲覧中（admin 用 API に切替）。
 */
import { ref, onMounted, computed } from 'vue';
import {
  Chart as ChartJS, CategoryScale, LinearScale,
  PointElement, LineElement, BarElement, BarController,
  ArcElement, DoughnutController,
  Title, Tooltip, Legend
} from 'chart.js';
import { Line as LineChart, Bar as BarChart, Doughnut as DoughnutChart } from 'vue-chartjs';
import { useDarkMode } from '../composables/useDarkMode';
import { useAuth } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';
import { useFriends } from '../composables/useFriends';
import { calculatePoints, WEIGHTS, getFolderRankInfoByRate } from '../utils/beatTier';
import { songData as songDataBodyRef, diffTable as diffTableRanksRef, getDifficultyCode } from '../composables/useGameData';
import ShareTokenModal from './ShareTokenModal.vue';
import IntegrationTokenModal from './IntegrationTokenModal.vue';

const props = defineProps<{
  viewingUserId?: number | null;
  /** 共有 URL 経由の閲覧時に渡される。指定時は /api/share/{token}/... から取得する。 */
  shareToken?: string | null;
}>();

const { isDarkMode } = useDarkMode();
const { authHeaders } = useAuth();
const { t } = useI18n();
// プッシュ通知は「自分を閲覧中」のみ有効（viewingUserId が無いケース）。
const { requestNotificationPermission, sendTestNotification } = useFriends();

/** URL 共有モーダルの開閉状態。 */
const isShareModalOpen = ref(false);

/** 外部連携トークン管理モーダルの開閉状態。 */
const isIntegrationModalOpen = ref(false);

/** ブラウザのプッシュ通知許可状態（'default' / 'granted' / 'denied'）。 */
const notificationStatus = ref(typeof Notification !== 'undefined' ? (Notification.permission || 'default') : 'default');
/** 通知登録 API 呼び出し中フラグ。 */
const isSubscribing = ref(false);
/** テスト送信 API 呼び出し中フラグ。 */
const isTesting = ref(false);

/**
 * 【関数の役割】 プッシュ通知の許可要求 → サービスワーカー購読を試みる。
 * 成功時は permission 再読取り、失敗時は i18n メッセージで alert 通知。
 */
const handleEnableNotifications = async () => {
  isSubscribing.value = true;
  try {
    const success = await requestNotificationPermission();
    if (success) {
      notificationStatus.value = 'granted';
      alert(t('profile.notificationSuccess'));
    } else {
      alert(t('profile.notificationError'));
    }
  } catch (e) {
    console.error(e);
  } finally {
    isSubscribing.value = false;
    notificationStatus.value = typeof Notification !== 'undefined' ? (Notification.permission || 'default') : 'default';
  }
};

/** 【関数の役割】 テスト用プッシュ通知を自分宛てに送信する（ちゃんと届くか確認用）。 */
const handleTestNotification = async () => {
  isTesting.value = true;
  try {
    await sendTestNotification();
    alert(t('profile.testNotificationSuccess'));
  } catch (e: any) {
    alert(e.message || t('profile.testNotificationError'));
  } finally {
    isTesting.value = false;
  }
};

ChartJS.register(
  CategoryScale, LinearScale,
  PointElement, LineElement,
  BarElement, BarController,
  ArcElement, DoughnutController,
  Title, Tooltip, Legend
);

/**
 * 履歴スナップショット 1 件を表す型。
 * updatedCount: そのアップロードで実際にスコアが更新された曲数（0 なら難易度表改定などのメンテナンス的変化）。
 * beatPtIncrease: 前回スナップショットからの Beat-PT 増分。
 */
interface HistoryRecord {
  snapshotId: string;
  date: string;
  totalScore: number;
  totalBeatPt: number;
  fcCount: number;
  exhCount: number;
  hCount: number;
  clearCount: number;
  easyCount: number;
  aaaCount: number;
  aaCount: number;
  aCount: number;
  beatPtIncrease: number;
  updatedCount: number;
}

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

const isLoading = ref(true);
const historyData = ref<HistoryRecord[]>([]);
const myScores = ref<any[]>([]);

// Build lookup maps from reactive game data
const songDict = new Map<string, number>();
if (songDataBodyRef.value && Array.isArray(songDataBodyRef.value)) {
  songDataBodyRef.value.forEach((s: any) => {
    if (s.notes) songDict.set(`${s.title}_${s.difficulty}`, s.notes * 2);
  });
}

const informalDict = new Map<string, string>();
if (diffTableRanksRef.value && Array.isArray(diffTableRanksRef.value)) {
  diffTableRanksRef.value.forEach((r: any) => {
    r.songs.forEach((songTitle: string) => {
      if (songTitle.endsWith('[L]')) {
        informalDict.set(`${songTitle.slice(0, -3)}_LEGGENDARIA`, r.rank);
      } else {
        informalDict.set(`${songTitle}_ANOTHER`, r.rank);
      }
    });
  });
}

/**
 * 初回マウント時に履歴と現在スコアを並列取得する。
 * 閲覧ユーザーが自分以外（props.viewingUserId が数値）のときは /api/admin/users/{id}/... に切替える。
 * allSettled で取得するため片方が失敗しても他方の表示は続行する。
 */
onMounted(async () => {
  try {
    const histEndpoint = props.shareToken
        ? `${API_BASE}/api/share/${encodeURIComponent(props.shareToken)}/history`
        : props.viewingUserId
        ? `${API_BASE}/api/admin/users/${props.viewingUserId}/history`
        : `${API_BASE}/api/scores/history`;
    const scoresEndpoint = props.shareToken
        ? `${API_BASE}/api/share/${encodeURIComponent(props.shareToken)}/scores`
        : props.viewingUserId
        ? `${API_BASE}/api/admin/users/${props.viewingUserId}/scores`
        : `${API_BASE}/api/scores/me`;

    // share-token モードでは認証ヘッダ不要（公開エンドポイント）。
    const headers = props.shareToken ? {} : authHeaders();
    const [histRes, scoresRes] = await Promise.allSettled([
      fetch(histEndpoint, { headers }),
      fetch(scoresEndpoint, { headers }),
    ]);

    if (histRes.status === 'fulfilled' && histRes.value.ok) {
      historyData.value = await histRes.value.json();
      historyData.value.sort((a, b) => new Date(a.date).getTime() - new Date(b.date).getTime());
    }
    if (scoresRes.status === 'fulfilled' && scoresRes.value.ok) {
      myScores.value = await scoresRes.value.json();
    }
  } catch (e) {
    console.error('Failed to load data', e);
  } finally {
    isLoading.value = false;
  }
});

// ── History-based computeds ──────────────────────────────────────────────────

const latestRecord = computed(() => historyData.value[historyData.value.length - 1] ?? null);
const latestBeatPt = computed(() => latestRecord.value?.totalBeatPt ?? 0);
const latestTotalScore = computed(() => latestRecord.value?.totalScore ?? 0);
const latestAaaCount = computed(() => latestRecord.value?.aaaCount ?? 0);
const latestFcCount = computed(() => latestRecord.value?.fcCount ?? 0);

/**
 * 【computed の役割】 日単位に集約した履歴データ。
 * 同じ日の複数スナップショットは「最終値」を totalBeatPt 等の累計値として採用し、
 * beatPtIncrease はその日の合計として合算する。
 */
const dailyHistory = computed<HistoryRecord[]>(() => {
  if (!historyData.value.length) return [];
  const byDate = new Map<string, HistoryRecord[]>();
  for (const r of historyData.value) {
    const day = r.date.slice(0, 10); // YYYY-MM-DD
    if (!byDate.has(day)) byDate.set(day, []);
    byDate.get(day)!.push(r);
  }
  // Sort days and aggregate
  return Array.from(byDate.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([, records]) => {
      const last = records[records.length - 1]; // 最後のスナップショットの累計値を使用
      const totalIncrease = records.reduce((sum, r) => sum + (r.beatPtIncrease ?? 0), 0);
      return { ...last, beatPtIncrease: totalIncrease };
    });
});

// 初回インポートを除いたデータ（BEAT-PT増加量グラフ・統計用）
const historyWithoutFirst = computed(() =>
  dailyHistory.value.length > 1 ? dailyHistory.value.slice(1) : dailyHistory.value
);

const avgBeatPtIncrease = computed(() => {
  const vals = historyWithoutFirst.value.map(r => r.beatPtIncrease ?? 0).filter(v => v > 0);
  if (!vals.length) return 0;
  return Math.round(vals.reduce((a, b) => a + b, 0) / vals.length * 10) / 10;
});

const maxBeatPtIncrease = computed(() =>
  Math.round(Math.max(0, ...historyWithoutFirst.value.map(r => r.beatPtIncrease ?? 0)) * 10) / 10
);

const labels = computed(() =>
  dailyHistory.value.map(r => {
    const d = new Date(r.date);
    return `${d.getMonth() + 1}/${d.getDate()}`;
  })
);

const beatPtChartData = computed(() => {
  if (!dailyHistory.value.length) return null;
  return {
    labels: labels.value,
    datasets: [{
      label: t('dashboard.beatPtTrend'),
      data: dailyHistory.value.map(r => r.totalBeatPt),
      borderColor: '#a855f7',
      backgroundColor: 'rgba(168,85,247,0.1)',
      fill: true, tension: 0.3, pointRadius: 4, pointBackgroundColor: '#a855f7'
    }]
  };
});

const uploadIncreaseLabels = computed(() =>
  historyWithoutFirst.value.map(r => {
    const d = new Date(r.date);
    return `${d.getMonth() + 1}/${d.getDate()}`;
  })
);

const uploadIncreaseChartData = computed(() => {
  if (!historyWithoutFirst.value.length) return null;
  return {
    labels: uploadIncreaseLabels.value,
    datasets: [{
      label: t('dashboard.increaseTrend'),
      data: historyWithoutFirst.value.map(r => r.beatPtIncrease ?? 0),
      backgroundColor: historyWithoutFirst.value.map(r => (r.beatPtIncrease ?? 0) > 0 ? 'rgba(168,85,247,0.65)' : 'rgba(148,163,184,0.35)'),
      borderColor: historyWithoutFirst.value.map(r => (r.beatPtIncrease ?? 0) > 0 ? '#a855f7' : '#94a3b8'),
      borderWidth: 1, borderRadius: 4,
    }]
  };
});

const scoreChartData = computed(() => {
  if (!dailyHistory.value.length) return null;
  return {
    labels: labels.value,
    datasets: [{
      label: t('dashboard.scoreTrend'),
      data: dailyHistory.value.map(r => r.totalScore),
      borderColor: '#3b82f6', backgroundColor: 'rgba(59,130,246,0.1)',
      fill: true, tension: 0.3, pointRadius: 3, pointBackgroundColor: '#3b82f6'
    }]
  };
});

const djLevelTrendData = computed(() => {
  if (!dailyHistory.value.length) return null;
  return {
    labels: labels.value,
    datasets: [
      { label: 'AAA', data: dailyHistory.value.map(r => r.aaaCount), borderColor: '#fbbf24', backgroundColor: '#fbbf24', tension: 0.3, pointRadius: 3 },
      { label: 'AA', data: dailyHistory.value.map(r => r.aaCount), borderColor: '#94a3b8', backgroundColor: '#94a3b8', tension: 0.3, pointRadius: 3 },
      { label: 'A', data: dailyHistory.value.map(r => r.aCount), borderColor: '#22c55e', backgroundColor: '#22c55e', tension: 0.3, pointRadius: 3 }
    ]
  };
});

const clearChartData = computed(() => {
  if (!dailyHistory.value.length) return null;
  return {
    labels: labels.value,
    datasets: [
      { label: 'FC', data: dailyHistory.value.map(r => r.fcCount), borderColor: '#10b981', backgroundColor: '#10b981', tension: 0.3, pointRadius: 3 },
      { label: 'EX HARD', data: dailyHistory.value.map(r => r.exhCount), borderColor: '#f59e0b', backgroundColor: '#f59e0b', tension: 0.3, pointRadius: 3 },
      { label: 'HARD', data: dailyHistory.value.map(r => r.hCount), borderColor: '#ef4444', backgroundColor: '#ef4444', tension: 0.3, pointRadius: 3 }
    ]
  };
});

// ── Current scores computeds (Lv11/12 ANOTHER/LEGGENDARIA) ───────────────────

// Level filter for score analysis section
const selectedAnalysisLevel = ref<'ALL' | '11' | '12'>('ALL');

// All Lv11/12 ANOTHER/LEGGENDARIA entries (including score=0, for clear status table)
const myAnotherLegg = computed(() =>
  myScores.value.filter(s => {
    if (s.difficultyName !== 'ANOTHER' && s.difficultyName !== 'LEGGENDARIA') return false;
    if (s.difficultyLevel !== 11 && s.difficultyLevel !== 12) return false;
    if (selectedAnalysisLevel.value !== 'ALL' && s.difficultyLevel !== parseInt(selectedAnalysisLevel.value)) return false;
    return true;
  })
);

// Enriched with scoreRate, beatPt, informalRank (all entries including score=0)
const myScoresEnriched = computed(() =>
  myAnotherLegg.value.map(s => {
    const diffCode = String(getDifficultyCode(s.difficultyName));
    const maxScore = songDict.get(`${s.title}_${diffCode}`) ?? 0;
    const scoreRate = maxScore > 0 ? (s.score / maxScore) * 100 : 0;
    const informalRank = informalDict.get(`${s.title}_${s.difficultyName}`);
    const beatPt = calculatePoints(scoreRate, informalRank);
    return { ...s, scoreRate, beatPt, informalRank: informalRank ?? '', maxScore };
  })
);

// Active entries: score > 0, for general analysis
const myScoresActive = computed(() => myScoresEnriched.value.filter(s => s.score > 0));

const beatPtTop10 = computed(() =>
  [...myScoresActive.value]
    .filter(s => s.beatPt > 0)
    .sort((a, b) => b.beatPt - a.beatPt)
    .slice(0, 10)
);

const beatPtTop100 = computed(() =>
  [...myScoresActive.value]
    .filter(s => s.beatPt > 0)
    .sort((a, b) => b.beatPt - a.beatPt)
    .slice(0, 100)
);

const top100DiffHistData = computed(() => {
  if (!beatPtTop100.value.length) return null;
  const keys = Object.keys(WEIGHTS).sort((a, b) => parseFloat(a) - parseFloat(b));
  const counts: Record<string, number> = Object.fromEntries(keys.map(k => [k, 0]));
  beatPtTop100.value.forEach(s => {
    const match = s.informalRank?.match(/(\d+\.\d+)/);
    const key = match ? match[1] : null;
    if (key && key in counts) counts[key]++;
  });
  const colors = keys.map(k => {
    const v = parseFloat(k);
    if (v >= 12.5) return 'rgba(168,85,247,0.75)';
    if (v >= 12.0) return 'rgba(99,102,241,0.75)';
    if (v >= 11.5) return 'rgba(59,130,246,0.75)';
    return 'rgba(148,163,184,0.6)';
  });
  return {
    labels: keys.map(k => `☆${k}`),
    datasets: [{ label: t('common.songCount'), data: keys.map(k => counts[k]), backgroundColor: colors, borderRadius: 4 }]
  };
});

// Clear type doughnut (excludes NO PLAY / ---)
const clearTypeDoughnut = computed(() => {
  if (!myAnotherLegg.value.length) return null;
  const order = ['FULLCOMBO CLEAR', 'EX HARD CLEAR', 'HARD CLEAR', 'CLEAR', 'EASY CLEAR', 'ASSIST CLEAR', 'FAILED'];
  const lbls = ['FC', 'EX HARD', 'HARD', 'CLEAR', 'EASY', 'ASSIST', 'FAILED'];
  const colors = ['#10b981', '#f59e0b', '#ef4444', '#3b82f6', '#22c55e', '#a855f7', '#64748b'];
  const counts = order.map(ct => myAnotherLegg.value.filter(s => s.clearType === ct).length);
  if (!counts.some(c => c > 0)) return null;
  return {
    labels: lbls,
    datasets: [{
      data: counts,
      backgroundColor: colors,
      borderWidth: 2,
      borderColor: isDarkMode.value ? '#1e293b' : '#ffffff',
      hoverOffset: 4
    }]
  };
});

// DJ level distribution for current scores snapshot (score > 0)
const djLevelCurrentData = computed(() => {
  if (!myScoresActive.value.length) return null;
  const levels = ['MAX-', 'AAA', 'AA', 'A', 'B', 'C', 'D', 'E', 'F'];
  const counts: Record<string, number> = levels.reduce((acc, l) => ({ ...acc, [l]: 0 }), {});
  myScoresActive.value.forEach(s => {
    if (s.clearType === 'NO PLAY' || s.clearType === '---') return;
    if (s.scoreRate >= 94.45) {
      counts['MAX-']++;
    } else {
      const lvl = s.djLevel;
      if (counts[lvl] !== undefined) counts[lvl]++;
    }
  });
  const djColors = ['#a855f7', '#fbbf24', '#94a3b8', '#22c55e', '#3b82f6', '#8b5cf6', '#d946ef', '#f43f5e', '#64748b'];
  return {
    labels: levels,
    datasets: [{
      label: t('common.songCount'),
      backgroundColor: djColors,
      data: levels.map(l => counts[l]),
      borderRadius: 4,
    }]
  };
});

// Score rate histogram (score > 0 only) — 7 bands aligned to DJ level boundaries
const scoreRateHistData = computed(() => {
  if (!myScoresActive.value.length) return null;
  const bands = [
    { label: ['~A+', '~66.67%'], count: 0 },
    { label: ['A+', '66.67~'], count: 0 },
    { label: ['AA-', '72.23~'], count: 0 },
    { label: ['AA+', '77.78~'], count: 0 },
    { label: ['AAA-', '83.34~'], count: 0 },
    { label: ['AAA+', '88.89~'], count: 0 },
    { label: ['MAX-', '94.45~'], count: 0 },
  ];
  myScoresActive.value.forEach(s => {
    const r = s.scoreRate;
    if (r < 66.67)      bands[0].count++;
    else if (r < 72.23) bands[1].count++;
    else if (r < 77.78) bands[2].count++;
    else if (r < 83.34) bands[3].count++;
    else if (r < 88.89) bands[4].count++;
    else if (r < 94.45) bands[5].count++;
    else                bands[6].count++;
  });
  return {
    labels: bands.map(b => b.label),
    datasets: [{
      label: t('common.songCount'),
      data: bands.map(b => b.count),
      backgroundColor: ['#94a3b8', '#60a5fa', '#38bdf8', '#fbbf24', '#f59e0b', '#10b981', '#a855f7'],
      borderRadius: 6,
    }]
  };
});

/**
 * 【computed の役割】 単曲ティア（= スコア一覧「ランク」列の Folder Rank）別の曲数分布。
 *
 * 各譜面のスコアレートと非公式ランクから `getFolderRankInfoByRate` でランクを判定し、
 * Beginner（左端）→ Novice I..V → ... → Mythic I..V → Legend（右端）の 52 バーで集計する。
 * 各ランクブロックは I（最下位 = 明） 〜 V（最上位 = 濃）の濃淡で表現する。
 *
 * 0 点曲は集計対象外（`myScoresActive` を使用）。
 */
const songRankDistData = computed(() => {
  if (!myScoresActive.value.length) return null;
  // 下位ランクから上位ランクへ昇順に並べたブロック定義（色は beatTier.ts の各ブロックと揃える）
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
  // ベース色を白方向に lightenPct% だけ薄める。tier 1 が最も薄く、tier 5 がベース色そのまま。
  const shade = (hex: string, lightenPct: number): string => {
    const r = parseInt(hex.slice(1, 3), 16);
    const g = parseInt(hex.slice(3, 5), 16);
    const b = parseInt(hex.slice(5, 7), 16);
    const m = lightenPct / 100;
    return `rgb(${Math.round(r + (255 - r) * m)}, ${Math.round(g + (255 - g) * m)}, ${Math.round(b + (255 - b) * m)})`;
  };
  // 全バー（左 = 低位、右 = 高位）を組み立てる。ラベルにはフルネームを入れて
  // ツールチップで "Ancient II" などサブティアまで表示されるようにする。
  // X 軸の表示は songRankBarOpts の ticks.callback 側で親ランク名（tier III 位置）だけに間引く。
  const romans = ['I', 'II', 'III', 'IV', 'V'];
  const bars: { label: string; key: string; color: string }[] = [];
  blocks.forEach(block => {
    if (!block.sub) {
      bars.push({ label: block.name, key: block.name, color: block.color });
    } else {
      for (let tier = 1; tier <= 5; tier++) {
        // tier 5 → 0%（濃）, tier 1 → 60%（淡）
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
  myScoresActive.value.forEach(s => {
    if (!s.informalRank) return;
    const rank = getFolderRankInfoByRate(s.scoreRate, s.informalRank);
    const key = rank.tier ? `${rank.name}|${rank.tier}` : rank.name;
    if (counts[key] !== undefined) counts[key]++;
  });
  return {
    labels: bars.map(b => b.label),
    datasets: [{
      label: t('common.songCount'),
      data: bars.map(b => counts[b.key]),
      backgroundColor: bars.map(b => b.color),
      borderRadius: 1,
      // バー間隔を詰める（1.0 で隙間なし）
      categoryPercentage: 1.0,
      barPercentage: 1.0,
    }],
  };
});

// Informal rank table (includes score=0 for clear status)
const informalRankStats = computed(() => {
  const stats: Record<string, { fc: number; exh: number; hard: number; clear: number; easy: number; other: number; total: number }> = {};
  myScoresEnriched.value.forEach(s => {
    const rank = s.informalRank;
    if (!rank) return;
    if (!stats[rank]) stats[rank] = { fc: 0, exh: 0, hard: 0, clear: 0, easy: 0, other: 0, total: 0 };
    stats[rank].total++;
    const ct = s.clearType;
    if (ct === 'FULLCOMBO CLEAR') stats[rank].fc++;
    else if (ct === 'EX HARD CLEAR') stats[rank].exh++;
    else if (ct === 'HARD CLEAR') stats[rank].hard++;
    else if (ct === 'CLEAR') stats[rank].clear++;
    else if (ct === 'EASY CLEAR') stats[rank].easy++;
    else stats[rank].other++;
  });
  return Object.entries(stats)
    .map(([rank, s]) => ({ rank, ...s }))
    .sort((a, b) => parseFloat(b.rank) - parseFloat(a.rank));
});

const avgPgreatRate = computed(() => {
  const scored = myScoresActive.value.filter(s => (s.pgreat ?? 0) + (s.great ?? 0) > 0);
  if (!scored.length) return null;
  const sum = scored.reduce((acc, s) => acc + (s.pgreat / (s.pgreat + s.great)) * 100, 0);
  return Math.round(sum / scored.length * 10) / 10;
});

// ── Score rate histogram modal ────────────────────────────────────────────────

const RATE_BANDS = [
  { min: 0,     max: 66.67,   label: '~A+ (~66.67%)',       next: { label: 'A+',   rate: 66.67 } },
  { min: 66.67, max: 72.23,   label: 'A+ (66.67~72.23%)',   next: { label: 'AA-',  rate: 72.23 } },
  { min: 72.23, max: 77.78,   label: 'AA- (72.23~77.78%)',  next: { label: 'AA+',  rate: 77.78 } },
  { min: 77.78, max: 83.34,   label: 'AA+ (77.78~83.34%)',  next: { label: 'AAA-', rate: 83.34 } },
  { min: 83.34, max: 88.89,   label: 'AAA- (83.34~88.89%)', next: { label: 'AAA+', rate: 88.89 } },
  { min: 88.89, max: 94.45,   label: 'AAA+ (88.89~94.45%)', next: { label: 'MAX-', rate: 94.45 } },
  { min: 94.45, max: Infinity, label: 'MAX- (94.45%~)',      next: null },
] as const;

const histModalOpen = ref(false);
const histModalBandIndex = ref<number | null>(null);

const histModalLabel = computed(() =>
  histModalBandIndex.value !== null ? RATE_BANDS[histModalBandIndex.value].label : ''
);

const histModalSongs = computed(() => {
  if (histModalBandIndex.value === null) return [];
  const band = RATE_BANDS[histModalBandIndex.value];
  return myScoresActive.value
    .filter(s => s.scoreRate >= band.min && s.scoreRate < band.max)
    .map(s => {
      const nextBandPts = band.next
        ? Math.max(1, Math.ceil(s.maxScore * band.next.rate / 100) - s.score)
        : null;
      return { ...s, nextBandLabel: band.next?.label ?? null, nextBandPts };
    })
    .sort((a, b) => (a.nextBandPts ?? Infinity) - (b.nextBandPts ?? Infinity));
});

function openHistModal(idx: number) {
  histModalBandIndex.value = idx;
  histModalOpen.value = true;
}

// ── Chart options ─────────────────────────────────────────────────────────────

const lineOpts = computed(() => ({
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: {
      position: 'top' as const,
      labels: { usePointStyle: true, color: isDarkMode.value ? '#cbd5e1' : '#475569', font: { family: "'Inter', sans-serif" } }
    },
    tooltip: {
      mode: 'index' as const, intersect: false,
      backgroundColor: isDarkMode.value ? 'rgba(15,23,42,0.9)' : 'rgba(255,255,255,0.95)',
      titleColor: isDarkMode.value ? '#f8fafc' : '#0f172a',
      bodyColor: isDarkMode.value ? '#cbd5e1' : '#334155',
      borderColor: isDarkMode.value ? '#334155' : '#e2e8f0', borderWidth: 1
    }
  },
  interaction: { mode: 'nearest' as const, axis: 'x' as const, intersect: false },
  scales: {
    x: { ticks: { color: isDarkMode.value ? '#94a3b8' : '#64748b' }, grid: { color: isDarkMode.value ? '#334155' : '#f1f5f9' } },
    y: { ticks: { color: isDarkMode.value ? '#94a3b8' : '#64748b' }, grid: { color: isDarkMode.value ? '#334155' : '#f1f5f9' } }
  }
}));

const barOpts = computed(() => ({
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: { display: false },
    tooltip: {
      backgroundColor: isDarkMode.value ? 'rgba(15,23,42,0.9)' : 'rgba(255,255,255,0.95)',
      titleColor: isDarkMode.value ? '#f8fafc' : '#0f172a',
      bodyColor: isDarkMode.value ? '#cbd5e1' : '#334155',
      borderColor: isDarkMode.value ? '#334155' : '#e2e8f0', borderWidth: 1
    }
  },
  scales: {
    x: { ticks: { color: isDarkMode.value ? '#94a3b8' : '#64748b', font: { size: 11 } }, grid: { display: false } },
    y: { ticks: { color: isDarkMode.value ? '#94a3b8' : '#64748b' }, grid: { color: isDarkMode.value ? '#334155' : '#f1f5f9' }, beginAtZero: true }
  }
}));

const scoreRateHistOpts = computed(() => ({
  ...barOpts.value,
  onClick: (_event: any, elements: any[]) => {
    if (!elements.length) return;
    openHistModal(elements[0].index);
  },
  plugins: {
    ...barOpts.value.plugins,
    tooltip: { ...barOpts.value.plugins.tooltip },
  },
}));

// 単曲ティア分布用: 52 バー × 親ランクだけ X 軸ラベルを表示する設定。
// データのラベル自体はフルネーム（"Novice III" など）なのでツールチップにはサブティアまで出る。
// X 軸はコールバックで「tier III の親ランク名」「Beginner」「Legend」だけ残し、他は空にする。
const songRankBarOpts = computed(() => ({
  ...barOpts.value,
  scales: {
    ...barOpts.value.scales,
    x: {
      ...barOpts.value.scales.x,
      ticks: {
        ...barOpts.value.scales.x.ticks,
        font: { size: 10 },
        autoSkip: false,
        maxRotation: 0,
        minRotation: 0,
        callback(this: any, value: any) {
          const label = this.getLabelForValue(value);
          if (label === 'Beginner' || label === 'Legend') return label;
          const m = label.match(/^(\S+) III$/);
          return m ? m[1] : '';
        },
      },
      grid: { display: false },
    },
  },
}));

const doughnutOpts = computed(() => ({
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: {
      position: 'right' as const,
      labels: { usePointStyle: true, color: isDarkMode.value ? '#cbd5e1' : '#475569', font: { size: 11 } }
    },
    tooltip: {
      backgroundColor: isDarkMode.value ? 'rgba(15,23,42,0.9)' : 'rgba(255,255,255,0.95)',
      titleColor: isDarkMode.value ? '#f8fafc' : '#0f172a',
      bodyColor: isDarkMode.value ? '#cbd5e1' : '#334155',
      borderColor: isDarkMode.value ? '#334155' : '#e2e8f0', borderWidth: 1
    }
  }
}));
</script>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.4s ease-out forwards;
}
@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
.section-header {
  @apply flex items-center gap-2 mb-4;
}
.stat-card {
  @apply p-3 rounded-xl border flex flex-col items-center text-center transition-colors duration-200;
}
.chart-card {
  @apply bg-slate-50 dark:bg-slate-700/20 p-4 rounded-2xl border border-slate-100 dark:border-slate-700/50;
}
.chart-title {
  @apply font-bold text-slate-700 dark:text-slate-200 text-sm mb-3;
}
</style>
