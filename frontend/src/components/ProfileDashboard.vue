<template>
  <div class="w-full space-y-6 animate-fade-in">

    <!-- 成長軌跡 -->
    <div class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100 mb-1">プロフィール・成長軌跡</h2>
      <p class="text-slate-500 dark:text-slate-400 text-sm mb-6">アップロード履歴からあなたの成長を多角的に分析します。</p>

      <div v-if="isLoading" class="flex flex-col items-center justify-center py-12">
        <div class="w-10 h-10 border-4 border-blue-200 dark:border-blue-900 border-t-blue-600 dark:border-t-blue-500 rounded-full animate-spin mb-4"></div>
        <p class="text-slate-500 dark:text-slate-400 font-medium">データを読み込み中...</p>
      </div>

      <div v-else-if="historyData.length === 0" class="py-12 text-center border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-xl">
        <p class="text-slate-500 dark:text-slate-400 font-medium">履歴データがありません。<br/>スコアを複数回アップロードすると、ここに成長グラフが表示されます。</p>
      </div>

      <div v-else class="space-y-10">
        <!-- 成長サマリー -->
        <div>
          <div class="section-header">
            <div class="w-1 h-5 bg-violet-500 rounded-full"></div>
            <h3 class="font-bold text-slate-700 dark:text-slate-200">成長サマリー</h3>
          </div>
          <div class="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-7 gap-3">
            <div class="stat-card border-blue-100 dark:border-slate-600 bg-blue-50/50 dark:bg-slate-700/50">
              <span class="text-[9px] font-bold text-blue-500 dark:text-blue-400 uppercase tracking-widest mb-1">スナップショット</span>
              <span class="text-2xl font-black text-slate-700 dark:text-slate-200">{{ historyData.length }}</span>
            </div>
            <div class="stat-card border-violet-100 dark:border-slate-600 bg-violet-50/50 dark:bg-slate-700/50">
              <span class="text-[9px] font-bold text-violet-500 dark:text-violet-400 uppercase tracking-widest mb-1">最新 BEAT-PT</span>
              <span class="text-xl font-black text-slate-700 dark:text-slate-200">{{ latestBeatPt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}</span>
            </div>
            <div class="stat-card border-amber-100 dark:border-slate-600 bg-amber-50/50 dark:bg-slate-700/50">
              <span class="text-[9px] font-bold text-amber-500 dark:text-amber-400 uppercase tracking-widest mb-1">累計 EXスコア</span>
              <span class="text-lg font-black text-slate-700 dark:text-slate-200">{{ latestTotalScore.toLocaleString() }}</span>
            </div>
            <div class="stat-card border-yellow-100 dark:border-slate-600 bg-yellow-50/50 dark:bg-slate-700/50">
              <span class="text-[9px] font-bold text-yellow-600 dark:text-yellow-400 uppercase tracking-widest mb-1">最新 AAA数</span>
              <span class="text-2xl font-black text-slate-700 dark:text-slate-200">{{ latestAaaCount }}</span>
            </div>
            <div class="stat-card border-emerald-100 dark:border-slate-600 bg-emerald-50/50 dark:bg-slate-700/50">
              <span class="text-[9px] font-bold text-emerald-500 dark:text-emerald-400 uppercase tracking-widest mb-1">最新 FC数</span>
              <span class="text-2xl font-black text-slate-700 dark:text-slate-200">{{ latestFcCount }}</span>
            </div>
            <div class="stat-card border-purple-100 dark:border-slate-600 bg-purple-50/50 dark:bg-slate-700/50">
              <span class="text-[9px] font-bold text-purple-500 dark:text-purple-400 uppercase tracking-widest mb-1">平均増加/回</span>
              <span class="text-xl font-black text-slate-700 dark:text-slate-200">{{ avgBeatPtIncrease.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}</span>
            </div>
            <div class="stat-card border-indigo-100 dark:border-slate-600 bg-indigo-50/50 dark:bg-slate-700/50">
              <span class="text-[9px] font-bold text-indigo-500 dark:text-indigo-400 uppercase tracking-widest mb-1">最大増加</span>
              <span class="text-xl font-black text-slate-700 dark:text-slate-200">{{ maxBeatPtIncrease.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}</span>
            </div>
          </div>
        </div>

        <!-- 時系列推移 -->
        <div>
          <div class="section-header">
            <div class="w-1 h-5 bg-blue-500 rounded-full"></div>
            <h3 class="font-bold text-slate-700 dark:text-slate-200">時系列推移</h3>
          </div>
          <div class="grid grid-cols-1 lg:grid-cols-2 gap-5">
            <div class="chart-card lg:col-span-2">
              <h4 class="chart-title">総 BEAT-PT の推移</h4>
              <div class="h-56"><LineChart v-if="beatPtChartData" :data="beatPtChartData" :options="lineOpts" /></div>
            </div>
            <div class="chart-card lg:col-span-2">
              <h4 class="chart-title">アップロード別 BEAT-PT 増加量</h4>
              <div class="h-44"><BarChart v-if="uploadIncreaseChartData" :data="uploadIncreaseChartData" :options="barOpts" /></div>
            </div>
            <div class="chart-card">
              <h4 class="chart-title">累計 EXスコアの推移</h4>
              <div class="h-44"><LineChart v-if="scoreChartData" :data="scoreChartData" :options="lineOpts" /></div>
            </div>
            <div class="chart-card">
              <h4 class="chart-title">DJレベル取得数の推移</h4>
              <div class="h-44"><LineChart v-if="djLevelTrendData" :data="djLevelTrendData" :options="lineOpts" /></div>
            </div>
            <div class="chart-card lg:col-span-2">
              <h4 class="chart-title">上位クリアタイプの推移</h4>
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
          <h2 class="text-xl font-bold text-slate-800 dark:text-slate-100">スコア分析</h2>
          <p class="text-sm text-slate-500 dark:text-slate-400">Lv11/12 の ANOTHER / LEGGENDARIA {{ myScoresActive.length }} 曲を分析（0点除く）</p>
        </div>
        <div v-if="avgPgreatRate !== null" class="text-right">
          <span class="text-[9px] font-bold text-slate-400 uppercase tracking-widest block">平均 P-GREAT 率</span>
          <span class="text-2xl font-black text-slate-700 dark:text-slate-200">{{ avgPgreatRate }}%</span>
        </div>
      </div>

      <!-- Level filter -->
      <div class="flex items-center gap-3 mb-6 pb-4 border-b border-slate-100 dark:border-slate-700">
        <span class="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-widest">対象レベル</span>
        <div class="flex items-center bg-slate-100 dark:bg-slate-800 p-0.5 rounded-lg border border-slate-200 dark:border-slate-700">
          <button
            v-for="lvl in ['ALL', '11', '12']" :key="lvl"
            @click="selectedAnalysisLevel = lvl as 'ALL' | '11' | '12'"
            class="px-3 py-1 text-xs font-bold rounded-md transition-all"
            :class="selectedAnalysisLevel === lvl
              ? 'bg-white dark:bg-slate-700 text-blue-600 dark:text-blue-400 shadow-sm'
              : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300'"
          >{{ lvl === 'ALL' ? 'すべて' : `☆${lvl}` }}</button>
        </div>
      </div>

      <div class="space-y-8">
        <!-- クリアタイプ + DJレベル + スコアレート分布 -->
        <div>
          <div class="section-header">
            <div class="w-1 h-5 bg-emerald-500 rounded-full"></div>
            <h3 class="font-bold text-slate-700 dark:text-slate-200">クリア状況（Lv11/12）</h3>
          </div>
          <div class="grid grid-cols-1 lg:grid-cols-2 gap-5">
            <div class="chart-card">
              <h4 class="chart-title">クリアタイプ分布</h4>
              <div class="h-52"><DoughnutChart v-if="clearTypeDoughnut" :data="clearTypeDoughnut" :options="doughnutOpts" /></div>
            </div>
            <div class="chart-card">
              <h4 class="chart-title">DJレベル分布</h4>
              <div class="h-52"><BarChart v-if="djLevelCurrentData" :data="djLevelCurrentData" :options="barOpts" /></div>
            </div>
            <div class="chart-card lg:col-span-2">
              <h4 class="chart-title">スコアレート分布（0点除く）<span class="text-[10px] font-normal text-slate-400 ml-2">棒をクリックで曲一覧</span></h4>
              <div class="h-44"><BarChart v-if="scoreRateHistData" :data="scoreRateHistData" :options="scoreRateHistOpts" /></div>
            </div>
          </div>
        </div>

        <!-- 非公式難易度別クリア状況 -->
        <div>
          <div class="section-header">
            <div class="w-1 h-5 bg-amber-500 rounded-full"></div>
            <h3 class="font-bold text-slate-700 dark:text-slate-200">非公式難易度別クリア状況</h3>
          </div>
          <div class="overflow-x-auto">
            <table class="w-full text-sm">
              <thead>
                <tr class="border-b border-slate-100 dark:border-slate-700 text-xs font-black uppercase tracking-wide">
                  <th class="pb-3 pl-2 text-left text-slate-400">難度</th>
                  <th class="pb-3 text-center text-emerald-500">FC</th>
                  <th class="pb-3 text-center text-amber-500">EXH</th>
                  <th class="pb-3 text-center text-red-500">HARD</th>
                  <th class="pb-3 text-center text-blue-500">CLEAR</th>
                  <th class="pb-3 text-center text-green-500">EASY</th>
                  <th class="pb-3 text-center text-slate-400">他</th>
                  <th class="pb-3 text-center text-slate-400">計</th>
                  <th class="pb-3 pr-2 text-right text-slate-400">クリア率</th>
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

        <!-- BEAT-PT上位10曲 -->
        <div>
          <div class="section-header">
            <div class="w-1 h-5 bg-violet-500 rounded-full"></div>
            <h3 class="font-bold text-slate-700 dark:text-slate-200">BEAT-PT 上位10曲</h3>
          </div>
          <div class="overflow-x-auto">
            <table class="w-full text-sm">
              <thead>
                <tr class="border-b border-slate-100 dark:border-slate-700 text-xs font-black uppercase text-slate-400">
                  <th class="pb-3 pl-2 text-left w-6">#</th>
                  <th class="pb-3 text-left">楽曲</th>
                  <th class="pb-3 text-center w-16">難度</th>
                  <th class="pb-3 text-center w-10">☆</th>
                  <th class="pb-3 text-right w-20">スコア率</th>
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

    <!-- 他プレイヤーとの比較 -->
    <div v-if="comparisonData" class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <div class="mb-5">
        <h2 class="text-xl font-bold text-slate-800 dark:text-slate-100">他プレイヤーとの比較</h2>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">総合BEAT-PT ±200pt 以内の {{ comparisonData.nearbyCount }} 名との {{ comparisonData.totalCompared }} 曲の比較（Lv11/12、0点除く）</p>
      </div>
      <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
        <div>
          <div class="section-header">
            <div class="w-1 h-5 bg-emerald-500 rounded-full"></div>
            <h3 class="font-bold text-slate-700 dark:text-slate-200">あなたが得意な曲 TOP5</h3>
          </div>
          <div class="space-y-2">
            <div v-for="c in comparisonData.good" :key="`g_${c.title}_${c.diff}`"
              class="p-3 rounded-xl bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-100 dark:border-emerald-800/30">
              <div class="flex justify-between items-start gap-2">
                <div class="min-w-0">
                  <div class="font-bold text-slate-700 dark:text-slate-200 text-sm truncate">{{ c.title }}</div>
                  <div class="text-xs text-slate-400">{{ c.diff }} ☆{{ c.difficultyLevel }}<span v-if="c.informalRank"> · {{ c.informalRank }}</span></div>
                </div>
                <div class="text-right shrink-0">
                  <div class="text-emerald-600 dark:text-emerald-400 font-black text-sm">+{{ c.delta.toFixed(1) }}%</div>
                  <div class="text-xs text-slate-400">自 {{ c.myRate.toFixed(1) }}% / 平均 {{ c.avgRate.toFixed(1) }}%</div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div>
          <div class="section-header">
            <div class="w-1 h-5 bg-red-500 rounded-full"></div>
            <h3 class="font-bold text-slate-700 dark:text-slate-200">あなたが苦手な曲 TOP5</h3>
          </div>
          <div class="space-y-2">
            <div v-for="c in comparisonData.bad" :key="`b_${c.title}_${c.diff}`"
              class="p-3 rounded-xl bg-red-50 dark:bg-red-900/20 border border-red-100 dark:border-red-800/30">
              <div class="flex justify-between items-start gap-2">
                <div class="min-w-0">
                  <div class="font-bold text-slate-700 dark:text-slate-200 text-sm truncate">{{ c.title }}</div>
                  <div class="text-xs text-slate-400">{{ c.diff }} ☆{{ c.difficultyLevel }}<span v-if="c.informalRank"> · {{ c.informalRank }}</span></div>
                </div>
                <div class="text-right shrink-0">
                  <div class="text-red-600 dark:text-red-400 font-black text-sm">{{ c.delta.toFixed(1) }}%</div>
                  <div class="text-xs text-slate-400">自 {{ c.myRate.toFixed(1) }}% / 平均 {{ c.avgRate.toFixed(1) }}%</div>
                </div>
              </div>
            </div>
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
                  <th class="pb-3 pt-3 pl-6 text-left">曲名</th>
                  <th class="pb-3 pt-3 text-center w-14">☆</th>
                  <th class="pb-3 pt-3 text-center w-14">難度</th>
                  <th class="pb-3 pt-3 text-right w-24">スコア</th>
                  <th class="pb-3 pt-3 text-right pr-6 w-32">次の区分まで</th>
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
                      <div class="text-slate-400 text-[10px]">あと {{ s.nextBandPts.toLocaleString() }} 点</div>
                    </template>
                    <template v-else>
                      <span class="text-purple-500 font-bold text-[10px]">MAX-達成済</span>
                    </template>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 通知設定 -->
    <div class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <h3 class="text-lg font-bold text-slate-800 dark:text-slate-100 mb-2 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-blue-500" viewBox="0 0 20 20" fill="currentColor">
          <path d="M10 2a6 6 0 00-6 6v3.586l-.707.707A1 1 0 004 14h12a1 1 0 00.707-1.707L16 11.586V8a6 6 0 00-6-6zM10 18a3 3 0 01-3-3h6a3 3 0 01-3 3z" />
        </svg>
        プッシュ通知設定
      </h3>
      <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">
        ライバル申請が届いた時にリアルタイムで通知を受け取れます。
        <br/>※iOS/iPadOSの場合は「ホーム画面に追加」してから設定してください。
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
          {{ notificationStatus === 'granted' ? '通知は有効です' : '通知を有効にする' }}
        </button>
        <span class="text-xs text-slate-400 dark:text-slate-500">
          現在の状態: {{ notificationStatus === 'granted' ? '許可済み' : notificationStatus === 'denied' ? 'ブロック中' : '未設定' }}
        </span>
      </div>
    </div>

  </div>
</template>

<script setup lang="ts">
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
import { useFriends } from '../composables/useFriends';
import { calculatePoints } from '../utils/beatTier';
import songDataRaw from '../data/song_data.json';
import diffTableRaw from '../data/difficulty_table.json';

const { isDarkMode } = useDarkMode();
const { authHeaders, user } = useAuth();
const { requestNotificationPermission } = useFriends();

const notificationStatus = ref(Notification?.permission || 'default');
const isSubscribing = ref(false);

const handleEnableNotifications = async () => {
  isSubscribing.value = true;
  try {
    const success = await requestNotificationPermission();
    if (success) {
      notificationStatus.value = 'granted';
      alert('通知が有効になりました。');
    } else {
      alert('通知の許可が得られなかったか、エラーが発生しました。');
    }
  } catch (e) {
    console.error(e);
  } finally {
    isSubscribing.value = false;
    notificationStatus.value = Notification?.permission || 'default';
  }
};

ChartJS.register(
  CategoryScale, LinearScale,
  PointElement, LineElement,
  BarElement, BarController,
  ArcElement, DoughnutController,
  Title, Tooltip, Legend
);

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
const allUserScores = ref<any[]>([]);

// Build lookup maps from static data files
const songDict = new Map<string, number>();
if ((songDataRaw as any)?.body) {
  (songDataRaw as any).body.forEach((s: any) => {
    if (s.notes) songDict.set(`${s.title}_${s.difficulty}`, s.notes * 2);
  });
}

const informalDict = new Map<string, string>();
if ((diffTableRaw as any)?.ranks) {
  (diffTableRaw as any).ranks.forEach((r: any) => {
    r.songs.forEach((songTitle: string) => {
      if (songTitle.endsWith('[L]')) {
        informalDict.set(`${songTitle.slice(0, -3)}_LEGGENDARIA`, r.rank);
      } else {
        informalDict.set(`${songTitle}_ANOTHER`, r.rank);
      }
    });
  });
}

onMounted(async () => {
  try {
    const [histRes, scoresRes, allScoresRes] = await Promise.allSettled([
      fetch(`${API_BASE}/api/scores/history`, { headers: authHeaders() }),
      fetch(`${API_BASE}/api/scores/me`, { headers: authHeaders() }),
      fetch(`${API_BASE}/api/scores/all-user-scores`, { headers: authHeaders() }),
    ]);

    if (histRes.status === 'fulfilled' && histRes.value.ok) {
      historyData.value = await histRes.value.json();
      historyData.value.sort((a, b) => new Date(a.date).getTime() - new Date(b.date).getTime());
    }
    if (scoresRes.status === 'fulfilled' && scoresRes.value.ok) {
      myScores.value = await scoresRes.value.json();
    }
    if (allScoresRes.status === 'fulfilled' && allScoresRes.value.ok) {
      allUserScores.value = await allScoresRes.value.json();
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

const avgBeatPtIncrease = computed(() => {
  const vals = historyData.value.map(r => r.beatPtIncrease ?? 0).filter(v => v > 0);
  if (!vals.length) return 0;
  return Math.round(vals.reduce((a, b) => a + b, 0) / vals.length * 10) / 10;
});

const maxBeatPtIncrease = computed(() =>
  Math.round(Math.max(0, ...historyData.value.map(r => r.beatPtIncrease ?? 0)) * 10) / 10
);

const labels = computed(() =>
  historyData.value.map(r => {
    const d = new Date(r.date);
    return `${d.getMonth() + 1}/${d.getDate()}`;
  })
);

const beatPtChartData = computed(() => {
  if (!historyData.value.length) return null;
  return {
    labels: labels.value,
    datasets: [{
      label: '総 BEAT-PT',
      data: historyData.value.map(r => r.totalBeatPt),
      borderColor: '#a855f7',
      backgroundColor: 'rgba(168,85,247,0.1)',
      fill: true, tension: 0.3, pointRadius: 4, pointBackgroundColor: '#a855f7'
    }]
  };
});

const uploadIncreaseChartData = computed(() => {
  if (!historyData.value.length) return null;
  return {
    labels: labels.value,
    datasets: [{
      label: 'BEAT-PT 増加',
      data: historyData.value.map(r => r.beatPtIncrease ?? 0),
      backgroundColor: historyData.value.map(r => (r.beatPtIncrease ?? 0) > 0 ? 'rgba(168,85,247,0.65)' : 'rgba(148,163,184,0.35)'),
      borderColor: historyData.value.map(r => (r.beatPtIncrease ?? 0) > 0 ? '#a855f7' : '#94a3b8'),
      borderWidth: 1, borderRadius: 4,
    }]
  };
});

const scoreChartData = computed(() => {
  if (!historyData.value.length) return null;
  return {
    labels: labels.value,
    datasets: [{
      label: '累計EXスコア',
      data: historyData.value.map(r => r.totalScore),
      borderColor: '#3b82f6', backgroundColor: 'rgba(59,130,246,0.1)',
      fill: true, tension: 0.3, pointRadius: 3, pointBackgroundColor: '#3b82f6'
    }]
  };
});

const djLevelTrendData = computed(() => {
  if (!historyData.value.length) return null;
  return {
    labels: labels.value,
    datasets: [
      { label: 'AAA', data: historyData.value.map(r => r.aaaCount), borderColor: '#fbbf24', backgroundColor: '#fbbf24', tension: 0.3, pointRadius: 3 },
      { label: 'AA', data: historyData.value.map(r => r.aaCount), borderColor: '#94a3b8', backgroundColor: '#94a3b8', tension: 0.3, pointRadius: 3 },
      { label: 'A', data: historyData.value.map(r => r.aCount), borderColor: '#22c55e', backgroundColor: '#22c55e', tension: 0.3, pointRadius: 3 }
    ]
  };
});

const clearChartData = computed(() => {
  if (!historyData.value.length) return null;
  return {
    labels: labels.value,
    datasets: [
      { label: 'FC', data: historyData.value.map(r => r.fcCount), borderColor: '#10b981', backgroundColor: '#10b981', tension: 0.3, pointRadius: 3 },
      { label: 'EX HARD', data: historyData.value.map(r => r.exhCount), borderColor: '#f59e0b', backgroundColor: '#f59e0b', tension: 0.3, pointRadius: 3 },
      { label: 'HARD', data: historyData.value.map(r => r.hCount), borderColor: '#ef4444', backgroundColor: '#ef4444', tension: 0.3, pointRadius: 3 }
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
    const diffCode = s.difficultyName === 'ANOTHER' ? '4' : '10';
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
      label: '楽曲数',
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
      label: '楽曲数',
      data: bands.map(b => b.count),
      backgroundColor: ['#94a3b8', '#60a5fa', '#38bdf8', '#fbbf24', '#f59e0b', '#10b981', '#a855f7'],
      borderRadius: 6,
    }]
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

// ── Abstract comparison (±100pt peers, Lv11/12, score > 0) ───────────────────

const comparisonData = computed(() => {
  if (!allUserScores.value.length || !user.value) return null;
  const myUserId = user.value.id;
  const myBeatPt = latestBeatPt.value;
  if (myBeatPt === 0) return null;

  // Group other users' scores
  const userScoresByUser = new Map<number, any[]>();
  allUserScores.value.forEach((s: any) => {
    if (s.userId === myUserId) return;
    if (!userScoresByUser.has(s.userId)) userScoresByUser.set(s.userId, []);
    userScoresByUser.get(s.userId)!.push(s);
  });

  // Compute each other user's totalBeatPt (top-100 approach)
  const userBeatPtMap = new Map<number, number>();
  for (const [uid, scores] of userScoresByUser.entries()) {
    const pts = scores
      .map((s: any) => {
        const diffCode = s.difficultyName === 'ANOTHER' ? '4' : '10';
        const maxScore = songDict.get(`${s.title}_${diffCode}`) ?? 0;
        const scoreRate = maxScore > 0 ? (s.score / maxScore) * 100 : -1;
        const informalRank = informalDict.get(`${s.title}_${s.difficultyName}`);
        return calculatePoints(scoreRate, informalRank);
      })
      .filter((pt: number) => pt > 0)
      .sort((a: number, b: number) => b - a)
      .slice(0, 100);
    userBeatPtMap.set(uid, pts.reduce((a: number, b: number) => a + b, 0));
  }

  // Filter to users within ±200pt
  const nearbyUserIds = new Set(
    [...userBeatPtMap.entries()]
      .filter(([, pt]) => Math.abs(pt - myBeatPt) <= 200)
      .map(([uid]) => uid)
  );

  if (nearbyUserIds.size === 0) return null;

  // Build avg score rate per song from nearby users (Lv11/12, score > 0)
  const songStats = new Map<string, { sum: number; count: number }>();
  allUserScores.value.forEach((s: any) => {
    if (!nearbyUserIds.has(s.userId)) return;
    if (s.difficultyLevel !== 11 && s.difficultyLevel !== 12) return;
    if (s.score === 0) return;
    const diffCode = s.difficultyName === 'ANOTHER' ? '4' : '10';
    const max = songDict.get(`${s.title}_${diffCode}`) ?? 0;
    if (max === 0) return;
    const rate = (s.score / max) * 100;
    const key = `${s.title}_${s.difficultyName}`;
    if (!songStats.has(key)) songStats.set(key, { sum: 0, count: 0 });
    const st = songStats.get(key)!;
    st.sum += rate;
    st.count++;
  });

  // Compare with my active scores
  const comparisons: Array<{ title: string; diff: string; myRate: number; avgRate: number; delta: number; informalRank: string; difficultyLevel: number }> = [];
  myScoresActive.value.forEach(s => {
    const key = `${s.title}_${s.difficultyName}`;
    const stat = songStats.get(key);
    if (!stat || stat.count < 2) return;
    const avgRate = stat.sum / stat.count;
    comparisons.push({
      title: s.title, diff: s.difficultyName,
      myRate: s.scoreRate, avgRate,
      delta: s.scoreRate - avgRate,
      informalRank: s.informalRank,
      difficultyLevel: s.difficultyLevel
    });
  });

  if (!comparisons.length) return null;
  const sorted = [...comparisons].sort((a, b) => b.delta - a.delta);
  return {
    good: sorted.slice(0, 5),
    bad: [...comparisons].sort((a, b) => a.delta - b.delta).slice(0, 5),
    totalCompared: comparisons.length,
    nearbyCount: nearbyUserIds.size,
  };
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
