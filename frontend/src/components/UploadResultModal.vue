<template>
  <Teleport to="body">
    <!--
      レスポンシブ指定の注意: src/output.css（古い Tailwind 断片）が本体 CSS より後に読み込まれ、
      flex / block / hidden / flex-col / p-4 / px-3 / text-sm などを再定義している。これらを素のクラスで
      置いた要素に sm: 等の上書きを重ねても負けるので、このファイルでは
        - 表示切替は display 系クラスを持たない要素に max-sm:hidden / sm:hidden だけを付ける
        - 方向の切替は max-sm:flex-col（素の flex-col を置かない）
      で書いている。
    -->
    <div v-if="isOpen" class="fixed inset-0 z-[110] bg-slate-900/60 dark:bg-slate-950/80 backdrop-blur-sm flex items-center justify-center p-2 sm:p-6 animate-fade-in" @click.self="close">
      <div id="report-container" class="relative bg-white dark:bg-slate-900 w-full max-w-3xl rounded-md shadow-xl flex flex-col overflow-hidden max-h-[94vh] animate-slide-up border border-slate-200 dark:border-slate-800">

        <!-- ヘッダー -->
        <div class="shrink-0 flex items-center gap-3 px-4 py-3 border-b border-slate-200 dark:border-slate-800">
          <div class="w-9 h-9 rounded-md bg-blue-700 dark:bg-blue-600 text-white flex items-center justify-center shrink-0">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          </div>
          <div class="min-w-0 flex-1">
            <h2 class="text-base font-bold text-slate-900 dark:text-slate-100 leading-tight">{{ t('report.title') }}</h2>
            <p class="text-[11px] text-slate-500 dark:text-slate-400 truncate">{{ reportDate ? `${dateLabel} ・ ${versionLabel}` : t('report.subtitle') }}</p>
          </div>
          <button id="modal-close-btn" @click="close" class="shrink-0 w-8 h-8 rounded-md flex items-center justify-center text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors" :aria-label="t('common.close')">
            <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- スクロール可能な本文領域（ティア / 集計 / フォルダ / リーグ / 更新曲） -->
        <div id="report-body" class="flex-1 overflow-y-auto p-3 sm:p-5 bg-slate-50 dark:bg-slate-900">
          <div v-if="diffData" class="space-y-3">

            <!-- ティアカード: 現在ティア・合計 PT・増分・次ティアまでの進捗を 1 枚に収める。昇格時は金枠 + TIER UP。 -->
            <div class="grid gap-3" :class="tierCards.length > 1 ? 'sm:grid-cols-2' : ''">
              <div
                v-for="c in tierCards"
                :key="c.key"
                class="card p-3 sm:p-4"
                :class="c.tierUp ? 'ring-1 ring-amber-400 !border-amber-400 dark:!border-amber-500' : ''"
              >
                <div class="flex items-center justify-between gap-2">
                  <p class="section-label tracking-widest">{{ c.label }}</p>
                  <p v-if="c.tierUp" class="flex items-center gap-1.5 min-w-0">
                    <span class="text-[10px] font-bold text-slate-400 line-through truncate">{{ c.oldTierName }}</span>
                    <span class="shrink-0 text-[10px] font-bold px-2 py-0.5 rounded bg-amber-400 text-amber-950 tracking-wide">{{ t('report.tierUpBadge') }}</span>
                  </p>
                </div>
                <div class="mt-2 flex items-center gap-3">
                  <RankIcon :rank-name="c.tier.name" :tier="c.tier.tier" size="md" disable-party :is-supporter="ownIconProps.isSupporter" v-bind="c.frame" />
                  <div class="min-w-0 flex-1">
                    <p class="text-lg font-bold leading-tight truncate" :class="c.tier.color">{{ c.tierName }}</p>
                    <p class="text-[11px] font-semibold text-slate-500 dark:text-slate-400 tabular-nums truncate">
                      {{ c.oldTotal }} → <span class="text-slate-800 dark:text-slate-100">{{ c.total }}</span> {{ t('common.pt') }}
                    </p>
                  </div>
                  <p class="shrink-0 text-2xl font-bold tabular-nums tracking-tight" :class="c.delta > 0 ? c.deltaClass : 'text-slate-400 dark:text-slate-500'">
                    {{ c.deltaText }}
                  </p>
                </div>
                <div class="mt-3">
                  <div class="flex items-center justify-between text-[10px] font-bold text-slate-500 dark:text-slate-400">
                    <span v-if="c.next">NEXT <span :class="c.next.color">{{ tierLabel(c.next) }}</span></span>
                    <span v-else>MAX TIER</span>
                    <span v-if="c.next" class="tabular-nums">{{ t('report.remainingPt', { n: c.remaining }) }}</span>
                  </div>
                  <div class="mt-1 h-1.5 rounded-full bg-slate-100 dark:bg-slate-700 overflow-hidden">
                    <div class="h-full rounded-full" :class="c.barClass" :style="{ width: `${c.progress}%` }"></div>
                  </div>
                </div>
              </div>
            </div>

            <!-- 集計タイル -->
            <div v-if="diffData.updatedSongs.length > 0" class="grid grid-cols-2 sm:grid-cols-4 gap-2">
              <!-- スマホは「値 ラベル」を横に並べて高さを半分にする（素の flex を置くと output.css に負けるので max-sm: で指定） -->
              <div v-for="s in statTiles" :key="s.key" class="card px-3 py-2 max-sm:flex max-sm:items-baseline max-sm:gap-1.5 min-w-0">
                <p class="text-base sm:text-xl font-bold tabular-nums leading-tight shrink-0" :class="s.value > 0 ? s.colorClass : 'text-slate-300 dark:text-slate-600'">{{ s.text }}</p>
                <p class="section-label truncate">{{ s.label }}</p>
              </div>
            </div>

            <!-- フォルダアナウンス（☆11/☆12 フォルダのランクアサイン、ランクアップ、残り数を通知） -->
            <div v-if="diffData.folderAnnouncements && diffData.folderAnnouncements.length > 0" class="card p-3">
              <p class="section-label mb-2">{{ t('report.folderNews') }}</p>
              <div class="flex flex-wrap gap-1.5">
                <div v-for="ann in diffData.folderAnnouncements" :key="ann.folder + ann.type"
                  class="inline-flex items-center gap-1.5 px-2 py-1 rounded border text-xs font-bold"
                  :class="ann.type === 'rank_assigned'
                    ? 'bg-amber-50 dark:bg-amber-900/20 border-amber-200 dark:border-amber-800/50 text-amber-700 dark:text-amber-300'
                    : ann.type === 'rank_up'
                      ? 'bg-violet-50 dark:bg-violet-900/20 border-violet-200 dark:border-violet-800/50 text-violet-700 dark:text-violet-300'
                      : 'bg-slate-50 dark:bg-slate-800 border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300'"
                >
                  <span class="opacity-70">☆{{ ann.folder }}</span>
                  <span v-if="ann.type === 'rank_assigned'">{{ t('report.folderRankAssigned', { rank: ann.newRankName }) }}</span>
                  <span v-else-if="ann.type === 'rank_up'">{{ t('report.folderRankUp', { oldRank: ann.oldRankName, newRank: ann.newRankName }) }}</span>
                  <span v-else-if="ann.type === 'remaining'">{{ t('report.folderRemaining', { n: ann.remaining }) }}</span>
                </div>
              </div>
            </div>

            <!-- リーグモードの進捗（今回の更新に課題曲が含まれたときだけ。アップロード時点の順位・有効曲・見込みPT を保存したもの） -->
            <div v-if="leagueProgress" class="card p-3">
              <div class="flex items-center gap-2 flex-wrap">
                <DivisionIcon :tier="leagueProgress.tier" :size="24" class="shrink-0" />
                <p class="text-sm font-bold text-slate-800 dark:text-slate-200">{{ t('report.league.title') }}</p>
                <span class="badge">{{ divisionName(leagueProgress.tier) }} / {{ t('league.groupN', { n: leagueProgress.groupIndex + 1 }) }}</span>
                <span v-if="leagueWeekLabel" class="ml-auto text-[11px] font-bold text-slate-400 tabular-nums">{{ leagueWeekLabel }}</span>
              </div>
              <div class="mt-2 grid grid-cols-4 gap-2">
                <div class="rounded-md bg-slate-50 dark:bg-slate-900/50 border border-slate-100 dark:border-slate-700/60 px-2 py-1.5 text-center">
                  <p class="section-label truncate">{{ t('league.rank') }}</p>
                  <p class="text-lg font-bold text-slate-800 dark:text-slate-100 tabular-nums leading-tight">{{ leagueProgress.rank }}<span class="text-xs text-slate-400">/{{ leagueProgress.groupSize }}</span></p>
                </div>
                <div class="rounded-md bg-slate-50 dark:bg-slate-900/50 border border-slate-100 dark:border-slate-700/60 px-2 py-1.5 text-center">
                  <p class="section-label truncate">{{ t('league.validSongs') }}</p>
                  <p class="text-lg font-bold text-slate-800 dark:text-slate-100 tabular-nums leading-tight">{{ leagueProgress.validSongs }}<span class="text-xs text-slate-400">/{{ leagueProgress.songCount }}</span></p>
                </div>
                <div class="rounded-md bg-slate-50 dark:bg-slate-900/50 border border-slate-100 dark:border-slate-700/60 px-2 py-1.5 text-center">
                  <p class="section-label truncate">{{ t('league.leaguePoints') }}</p>
                  <p class="text-lg font-bold text-slate-800 dark:text-slate-100 tabular-nums leading-tight">{{ leagueProgress.resultValue ?? 0 }}</p>
                </div>
                <div class="rounded-md bg-slate-50 dark:bg-slate-900/50 border border-slate-100 dark:border-slate-700/60 px-2 py-1.5 text-center">
                  <p class="section-label truncate">{{ t('report.league.projected') }}</p>
                  <p class="text-lg font-bold tabular-nums leading-tight"
                     :class="leagueProgress.zone === 'promote' ? 'text-emerald-500' : leagueProgress.zone === 'relegate' ? 'text-red-500' : 'text-slate-800 dark:text-slate-100'">
                    {{ leagueProgress.projectedPoints > 0 ? '+' : '' }}{{ leagueProgress.projectedPoints }}
                  </p>
                  <p v-if="leagueProgress.zone !== 'stay'" class="text-[10px] font-bold leading-tight" :class="leagueProgress.zone === 'promote' ? 'text-emerald-500' : 'text-red-500'">
                    {{ leagueProgress.zone === 'promote' ? t('league.promoteZone') : t('league.relegateZone') }}
                  </p>
                </div>
              </div>
              <!-- 課題曲ごとの内訳: 自己ベスト vs ライン。今回更新された曲と、今回有効化された曲を強調する。 -->
              <div class="mt-2 rounded-md border border-slate-200 dark:border-slate-700 divide-y divide-slate-100 dark:divide-slate-700/60 overflow-hidden">
                <div v-for="s in leagueProgress.songs" :key="s.slot"
                     class="px-3 py-2"
                     :class="s.updated ? 'bg-blue-50 dark:bg-blue-950/30' : ''">
                  <!-- 曲名 / 難易度 / 今回更新バッジ / 有効判定バッジ -->
                  <div class="flex items-center gap-2">
                    <span class="text-[10px] font-bold text-slate-400 tabular-nums shrink-0">{{ s.slot }}.</span>
                    <span class="min-w-0 text-sm font-bold text-slate-800 dark:text-slate-100 truncate">{{ s.title }}</span>
                    <span class="text-[10px] font-bold text-slate-400 shrink-0 whitespace-nowrap">
                      {{ s.difficultyName }}<template v-if="s.level"> ☆{{ s.level }}</template>
                    </span>
                    <span v-if="s.updated"
                          class="shrink-0 text-[10px] font-bold px-1.5 py-0.5 rounded bg-blue-700 text-white whitespace-nowrap tabular-nums">
                      {{ t('report.league.updated') }}<template v-if="s.scoreIncrease > 0"> +{{ s.scoreIncrease }}</template>
                    </span>
                    <span class="ml-auto shrink-0 inline-flex items-center gap-1 text-[10px] font-bold px-1.5 py-0.5 rounded whitespace-nowrap"
                          :class="s.justActivated
                            ? 'bg-emerald-500 text-white'
                            : s.valid
                              ? 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400'
                              : 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400'">
                      <svg v-if="s.valid" class="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="3">
                        <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
                      </svg>
                      {{ s.justActivated ? t('report.league.activated') : s.valid ? t('report.league.valid') : t('report.league.invalid') }}
                    </span>
                  </div>
                  <!-- 自己ベスト vs ライン（+ 未達なら残り EX） -->
                  <div class="mt-0.5 flex items-center gap-x-4 gap-y-0.5 flex-wrap text-[11px]">
                    <span class="text-slate-500 dark:text-slate-400">
                      {{ t('report.league.best') }}
                      <span class="font-bold tabular-nums"
                            :class="s.valid ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-700 dark:text-slate-200'">
                        {{ s.bestEx ?? '—' }}
                      </span>
                      <span v-if="s.rate != null" class="tabular-nums">({{ s.rate.toFixed(2) }}%)</span>
                    </span>
                    <span class="text-slate-500 dark:text-slate-400">
                      {{ t('report.league.line') }}
                      <template v-if="s.lineEx != null">
                        <span class="font-bold tabular-nums text-amber-600 dark:text-amber-400">{{ s.lineEx }}</span>
                        <span v-if="s.lineRate != null" class="tabular-nums">({{ s.lineRate.toFixed(2) }}%)</span>
                      </template>
                      <span v-else class="font-bold text-slate-400">{{ t('report.league.noLine') }}</span>
                    </span>
                    <span v-if="s.toLine != null && s.toLine > 0"
                          class="ml-auto font-bold text-slate-500 dark:text-slate-400 tabular-nums whitespace-nowrap">
                      {{ t('report.league.toLine', { n: s.toLine }) }}
                    </span>
                  </div>
                </div>
              </div>
            </div>

            <!-- 更新曲リスト: 1 曲 1 行の表形式。行を開くと詳細とオプション投票が出る。 -->
            <div>
              <div class="flex items-center gap-2 flex-wrap px-1">
                <h3 class="text-sm font-bold text-slate-800 dark:text-slate-200">{{ t('report.updatedSongs') }}</h3>
                <span class="badge tabular-nums">{{ diffData.updatedSongs.length }}</span>
                <select
                  v-if="diffData.updatedSongs.length > 1"
                  v-model="listSort"
                  class="ml-auto text-xs font-semibold rounded-md border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 pl-2 pr-7 py-1"
                  :aria-label="t('report.sortLabel')"
                >
                  <option v-for="o in sortOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
                </select>
              </div>

              <div v-if="filterChips.length > 1" class="mt-2 flex flex-wrap gap-1.5 px-1">
                <button
                  v-for="f in filterChips"
                  :key="f.value"
                  @click="listFilter = f.value"
                  class="px-2.5 py-1 rounded-full text-[11px] font-bold border transition-colors tabular-nums"
                  :class="listFilter === f.value
                    ? 'bg-blue-700 border-blue-700 text-white dark:bg-blue-600 dark:border-blue-600'
                    : 'bg-white dark:bg-slate-800 border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700'"
                >{{ f.label }} <span class="opacity-70">{{ f.count }}</span></button>
              </div>

              <div v-if="diffData.updatedSongs.length === 0" class="mt-2 text-center p-8 bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 border-dashed">
                <p class="text-slate-500 dark:text-slate-400 font-medium">{{ t('report.noUpdates') }}</p>
              </div>

              <template v-else>
                <!-- 列見出し（PC のみ） -->
                <div class="max-sm:hidden">
                  <div class="mt-2 flex items-center gap-3 pl-5 pr-9 text-[10px] font-bold text-slate-400 dark:text-slate-500 tracking-wider">
                    <span class="flex-1">{{ t('report.rowHint') }}</span>
                    <span class="w-[72px] text-right">EX SCORE</span>
                    <span class="w-[76px] text-right">DJ LEVEL</span>
                    <span class="w-16 text-right">{{ ptColumn === 'rate' ? t('common.ratePt') : t('common.beatPt') }}</span>
                  </div>
                </div>
                <p class="sm:hidden mt-2 px-1 text-[10px] font-bold text-slate-400 dark:text-slate-500">{{ t('report.rowHint') }}</p>

                <ul class="mt-1 card divide-y divide-slate-100 dark:divide-slate-700/60 overflow-hidden">
                  <li
                    v-for="song in visibleSongs"
                    :key="song.title + song.difficulty"
                    :class="song.allTimeBestUpdated
                      ? 'bg-amber-50 dark:bg-amber-900/20'
                      : song.allTimeBestExtended
                        ? 'bg-amber-50/40 dark:bg-amber-900/10'
                        : ''"
                  >
                    <div
                      role="button"
                      tabindex="0"
                      :aria-expanded="expandedKey === song.title + song.difficulty"
                      class="flex items-center gap-2.5 sm:gap-3 pl-2 pr-2 py-2 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-700/40 transition-colors"
                      @click="toggleRow(song)"
                      @keydown.enter.prevent="toggleRow(song)"
                      @keydown.space.prevent="toggleRow(song)"
                    >
                      <!-- 難易度色の縦バー -->
                      <span class="w-1 self-stretch rounded-full shrink-0" :class="getDifficultyBarClass(song.difficulty)"></span>

                      <!-- 曲名 + 補足（難易度 / ☆ / ランプ変化 / 歴代 / 順位 / 投票済み） -->
                      <div class="min-w-0 flex-1">
                        <p class="text-sm font-bold text-slate-900 dark:text-slate-100 truncate" :title="song.title">{{ song.title }}</p>
                        <div class="mt-0.5 flex items-center gap-x-2 gap-y-0.5 flex-wrap text-[10px] font-bold leading-4">
                          <span :class="getDifficultyTextClass(song.difficulty)">{{ song.difficulty }}</span>
                          <span v-if="getNumericRank(song.informalRank)" class="text-slate-600 dark:text-slate-300">☆{{ getNumericRank(song.informalRank) }}</span>
                          <span v-if="song.clearTypeImproved" class="whitespace-nowrap">
                            <span class="text-slate-400 line-through">{{ clearTypeShort(song.oldClearType) }}</span>
                            <span class="text-slate-400"> → </span>
                            <span :class="getClearTypeColor(song.newClearType)">{{ clearTypeShort(song.newClearType) }}</span>
                          </span>
                          <!-- 過去作が持っていた歴代ベストを塗り替えたときだけ出す -->
                          <span
                            v-if="song.allTimeBestUpdated"
                            class="px-1 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300 whitespace-nowrap"
                            :title="song.allTimeBeatenVersion ? t('report.allTimeBestHint', { version: `${song.allTimeBeatenVersion} ${versionName(song.allTimeBeatenVersion)}` }) : ''"
                          >★ {{ t('report.stat.allTimeBest') }}<template v-if="allTimeGain(song) > 0"> +{{ allTimeGain(song) }}</template></span>
                          <!-- 元々現行作が歴代ベストだった譜面をさらに伸ばしたとき（上より控えめ） -->
                          <span
                            v-else-if="song.allTimeBestExtended"
                            class="text-amber-600 dark:text-amber-400/90 whitespace-nowrap"
                            :title="t('report.allTimeBestExtendedHint')"
                          >☆ {{ t('report.allTimeBestExtended') }}</span>
                          <span v-if="song.songRank" class="tabular-nums" :class="song.songRank === 1 ? 'text-amber-600 dark:text-amber-400' : 'text-slate-500 dark:text-slate-400'">#{{ song.songRank }}<span class="font-medium">/{{ song.songRankTotal }}</span></span>
                          <span v-if="song.isInRateTop100 && song.newRatePt > 0 && showRateTier" class="max-sm:hidden text-emerald-600 dark:text-emerald-400 whitespace-nowrap">RATE TOP100</span>
                          <!-- スマホは DJ LEVEL 列を畳むので補足行に 1 つだけ出す。近い方のボーダー基準（MAX-12 / AAA-30 / AA+50）。 -->
                          <span v-if="song.maxScore > 0" class="sm:hidden tabular-nums" :class="mainGradeColorClass(song)">{{ getScoreGradeInfo(song.newScore, song.maxScore).nearest }}</span>
                          <span v-for="v in votedLabels(song)" :key="v" class="text-blue-700 dark:text-blue-400 whitespace-nowrap">✔ {{ v }}</span>
                        </div>
                      </div>

                      <!-- 単曲ティア（変動時は 旧 → 新）。PC のみ。 -->
                      <div class="max-sm:hidden shrink-0">
                        <div class="flex items-center gap-1 w-[52px] justify-end">
                          <template v-for="tr in [getSongTierTransition(song)]" :key="'tier-icon'">
                            <template v-if="tr.newTier">
                              <RankIcon v-if="tr.oldTier" :rank-name="tr.oldTier.name" :tier="tr.oldTier.tier" size="2xs" disable-party lite class="opacity-50" />
                              <svg v-if="tr.oldTier" xmlns="http://www.w3.org/2000/svg" class="h-2 w-2 text-slate-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="3"><path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" /></svg>
                              <RankIcon :rank-name="tr.newTier.name" :tier="tr.newTier.tier" size="2xs" disable-party lite />
                            </template>
                          </template>
                        </div>
                      </div>

                      <!-- EX SCORE -->
                      <div class="shrink-0 w-14 sm:w-[72px] text-right tabular-nums">
                        <p class="text-sm font-bold text-slate-800 dark:text-slate-100 leading-tight">{{ song.newScore }}</p>
                        <p class="text-[10px] font-bold leading-tight" :class="song.oldScore > 0 ? 'text-blue-600 dark:text-blue-400' : 'text-slate-400'">{{ scoreSub(song) }}</p>
                      </div>

                      <!-- DJ LEVEL / MAX-（PC のみ） -->
                      <div class="max-sm:hidden shrink-0 w-[76px] text-right tabular-nums">
                        <template v-if="song.maxScore > 0">
                          <p class="text-xs font-bold leading-tight" :class="mainGradeColorClass(song)">{{ getScoreGradeInfo(song.newScore, song.maxScore).main }}</p>
                          <p class="text-[10px] font-bold text-slate-400 leading-tight">{{ getScoreGradeInfo(song.newScore, song.maxScore).sub }}</p>
                        </template>
                      </div>

                      <!-- PT（並び順が RATE-PT のときは RATE-PT 列になる） -->
                      <div class="shrink-0 w-14 sm:w-16 text-right tabular-nums">
                        <p class="text-sm font-bold leading-tight" :class="ptClass(song)">{{ ptMain(song) }}</p>
                        <p class="text-[10px] font-bold leading-tight" :class="ptClass(song)">{{ ptSub(song) }}</p>
                      </div>

                      <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5 text-slate-400 shrink-0 transition-transform" :class="expandedKey === song.title + song.difficulty ? 'rotate-180' : ''" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
                        <path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" />
                      </svg>
                    </div>

                    <!-- 展開部: 数値の内訳 + オプション投票（正規/MIRROR/RANDOM/R-RAN/S-RAN） -->
                    <div v-if="expandedKey === song.title + song.difficulty" class="pl-5 pr-3 pb-3 pt-2 border-t border-slate-100 dark:border-slate-700/60 bg-slate-50/70 dark:bg-slate-900/40">
                      <dl class="grid grid-cols-2 sm:grid-cols-4 gap-x-4 gap-y-2 text-[11px] font-bold text-slate-700 dark:text-slate-200 tabular-nums">
                        <div>
                          <dt class="section-label">EX SCORE</dt>
                          <dd>{{ song.oldScore > 0 ? `${song.oldScore} → ` : '' }}{{ song.newScore }}<span v-if="song.scoreRate > 0" class="text-slate-400 font-semibold"> ({{ song.scoreRate.toFixed(2) }}%)</span></dd>
                        </div>
                        <div v-if="song.maxScore > 0">
                          <dt class="section-label">DJ LEVEL</dt>
                          <dd>
                            <template v-for="info in [getScoreGradeInfo(song.newScore, song.maxScore)]" :key="'grade'">
                              <span :class="gradeColorClass(song)">{{ info.grade }}</span>
                              <span class="text-slate-400"> ・ {{ info.fromMax }}</span>
                              <span v-if="info.nextGrade" class="text-slate-400"> ・ {{ info.nextGrade.name }}{{ info.nextGrade.gap }}</span>
                            </template>
                          </dd>
                        </div>
                        <div>
                          <dt class="section-label">{{ t('common.beatPt') }}</dt>
                          <dd>
                            {{ song.newBeatPt.toFixed(1) }}<span v-if="song.beatPtIncrease > 0" class="text-blue-600 dark:text-blue-400"> (+{{ song.beatPtIncrease.toFixed(1) }})</span>
                            <span v-if="song.isInTop100 !== undefined" class="ml-1 text-[10px] px-1 rounded" :class="song.isInTop100 ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300' : 'bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-400'">{{ song.isInTop100 ? t('report.inTop100') : t('report.outOfRange') }}</span>
                          </dd>
                        </div>
                        <div v-if="showRateTier && song.newRatePt > 0">
                          <dt class="section-label">{{ t('common.ratePt') }}</dt>
                          <dd>
                            {{ song.newRatePt.toFixed(1) }}<span v-if="song.ratePtIncrease > 0" class="text-emerald-600 dark:text-emerald-400"> (+{{ song.ratePtIncrease.toFixed(1) }})</span>
                            <span v-if="song.isInRateTop100" class="ml-1 text-[10px] px-1 rounded bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400">{{ t('report.inTop100') }}</span>
                          </dd>
                        </div>
                      </dl>

                      <!-- スマホは単曲ティアを行に出していないのでここで見せる -->
                      <div class="sm:hidden">
                        <div v-if="getSongTierTransition(song).newTier" class="mt-2 flex items-center gap-1.5 text-[11px] font-bold">
                          <template v-for="tr in [getSongTierTransition(song)]" :key="'tier-detail'">
                            <template v-if="tr.oldTier">
                              <RankIcon :rank-name="tr.oldTier.name" :tier="tr.oldTier.tier" size="2xs" disable-party lite class="opacity-50" />
                              <span class="text-slate-400">→</span>
                            </template>
                            <RankIcon v-if="tr.newTier" :rank-name="tr.newTier.name" :tier="tr.newTier.tier" size="2xs" disable-party lite />
                            <span v-if="tr.newTier" :class="tr.newTier.color">{{ tierLabel(tr.newTier) }}</span>
                          </template>
                        </div>
                      </div>

                      <p v-if="song.allTimeBestUpdated && song.allTimeBeatenVersion" class="mt-2 text-[11px] font-bold text-amber-700 dark:text-amber-300">
                        ★ {{ t('report.allTimeBestHint', { version: `${song.allTimeBeatenVersion} ${versionName(song.allTimeBeatenVersion)}` }) }}
                      </p>

                      <div class="mt-2.5">
                        <p class="section-label mb-1">{{ t('report.vote') }}</p>
                        <div class="flex flex-wrap gap-1">
                          <button
                            v-for="opt in optionTypes"
                            :key="opt.value"
                            @click.stop="castVote(song.title, song.difficulty, opt.value)"
                            :disabled="votingKey === (song.title + song.difficulty)"
                            class="px-2 py-1 rounded-md text-[10px] font-bold border transition-all disabled:opacity-50 flex items-center gap-0.5"
                            :class="getVoteClass(song.title, song.difficulty, opt.value, opt)"
                          >
                            {{ opt.icon }} {{ opt.label }}<span v-if="songVotes[song.title + song.difficulty]?.myVotes?.includes(opt.value)"> ✔</span>
                          </button>
                        </div>
                      </div>
                    </div>
                  </li>
                </ul>

                <p v-if="filteredSongs.length === 0" class="mt-2 text-center text-xs font-medium text-slate-500 dark:text-slate-400 py-4">{{ t('report.noMatch') }}</p>

                <button
                  v-if="filteredSongs.length > visibleSongs.length"
                  @click="visibleCount += PAGE_SIZE"
                  class="btn-secondary w-full mt-2"
                >{{ t('report.showMore', { n: filteredSongs.length - visibleSongs.length }) }}</button>
              </template>
            </div>

            <!-- スコアロードマップの進捗（今回のアップロードで新しく達成した目標・レベルがあるときだけ。最下部） -->
            <div v-if="roadmapProgress" class="card p-3">
              <div class="flex items-center gap-2">
                <p class="text-sm font-bold text-slate-800 dark:text-slate-200">{{ t('report.roadmap.title') }}</p>
                <button
                  class="ml-auto text-[11px] font-bold text-blue-700 dark:text-blue-300 hover:underline shrink-0"
                  @click="$emit('navigate', 'score-roadmap')"
                >{{ t('report.roadmap.open') }} →</button>
              </div>

              <!-- レベルの変化 -->
              <div class="mt-2 flex items-baseline gap-2 flex-wrap tabular-nums">
                <template v-if="roadmapProgress.newLevel !== roadmapProgress.oldLevel">
                  <span class="text-sm font-bold font-mono text-slate-400">{{ formatRoadmapLevel(roadmapProgress.oldLevel) }}</span>
                  <span class="text-slate-400">→</span>
                  <span class="text-2xl font-bold font-mono text-blue-700 dark:text-blue-300">{{ formatRoadmapLevel(roadmapProgress.newLevel) }}</span>
                  <span v-if="roadmapProgress.oldLevel != null && roadmapProgress.newLevel != null" class="text-xs font-bold px-1.5 py-0.5 rounded bg-blue-700 text-white">
                    {{ roadmapProgress.newLevel > roadmapProgress.oldLevel ? '+' : '' }}{{ roadmapProgress.newLevel - roadmapProgress.oldLevel }}
                  </span>
                </template>
                <span v-else class="text-2xl font-bold font-mono text-slate-800 dark:text-slate-100">{{ formatRoadmapLevel(roadmapProgress.newLevel) }}</span>
                <span class="text-xs text-slate-400">/ {{ roadmapProgress.maxLevel }}</span>
              </div>
              <div class="mt-1 flex flex-wrap gap-1.5 text-[11px] font-bold">
                <span v-if="roadmapProgress.newlyCleared > 0" class="px-2 py-0.5 rounded border bg-blue-50 dark:bg-blue-900/20 border-blue-200 dark:border-blue-800/50 text-blue-700 dark:text-blue-300">
                  {{ t('report.roadmap.newLevels', { n: roadmapProgress.newlyCleared }) }}
                </span>
                <span v-if="roadmapProgress.newlyComplete > 0" class="px-2 py-0.5 rounded border bg-amber-50 dark:bg-amber-900/20 border-amber-200 dark:border-amber-800/50 text-amber-700 dark:text-amber-300">
                  ★ {{ t('report.roadmap.newComplete', { n: roadmapProgress.newlyComplete }) }}
                </span>
                <span v-if="roadmapProgress.next" class="px-2 py-0.5 rounded border bg-slate-50 dark:bg-slate-800 border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300">
                  {{ t('report.roadmap.next', { lv: roadmapProgress.next.no, n: roadmapProgress.next.remaining }) }}
                </span>
              </div>

              <!-- 新たに達成した目標（難しいレベル順） -->
              <div v-if="roadmapProgress.targets.length" class="mt-2">
                <p class="section-label mb-1">{{ t('report.roadmap.newTargets', { n: roadmapProgress.targets.length }) }}</p>
                <ul class="rounded-md border border-slate-200 dark:border-slate-700 divide-y divide-slate-100 dark:divide-slate-700/60 overflow-hidden">
                  <li v-for="tg in roadmapTargetsShown" :key="tg.key" class="px-2.5 py-1.5 flex items-center gap-2 text-xs">
                    <span
                      class="text-[10px] font-bold font-mono px-1.5 rounded w-11 text-center shrink-0"
                      :class="tg.line === 'maxMinus' ? 'bg-slate-800 text-white dark:bg-slate-200 dark:text-slate-900'
                        : tg.line === 'aaa' ? 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-200'
                        : 'border border-slate-200 text-slate-500 dark:border-slate-600 dark:text-slate-300'"
                    >{{ ROADMAP_LINE_LABEL[tg.line] }}</span>
                    <span class="font-mono text-slate-400 shrink-0">☆{{ tg.level }}</span>
                    <span class="min-w-0 font-bold text-slate-800 dark:text-slate-100 break-words">{{ roadmapChartName(tg.title, tg.difficultyName) }}</span>
                    <span class="ml-auto font-mono text-slate-500 shrink-0">Lv.{{ tg.no }}</span>
                  </li>
                </ul>
                <button
                  v-if="!showAllRoadmapTargets && roadmapProgress.targets.length > ROADMAP_TARGETS_SHOWN"
                  class="btn-secondary w-full mt-2"
                  @click="showAllRoadmapTargets = true"
                >{{ t('report.showMore', { n: roadmapProgress.targets.length - ROADMAP_TARGETS_SHOWN }) }}</button>
              </div>
            </div>
          </div>
        </div>

        <!-- フッター: X シェアボタン + 閉じるボタン -->
        <div id="modal-footer" class="px-4 py-3 border-t border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 shrink-0 flex gap-2">
          <button @click="openShareOptions" :disabled="isSharing" class="flex-1 min-w-0 py-3 bg-black hover:bg-slate-800 text-white font-bold rounded-md transition-colors flex items-center justify-center gap-2 disabled:opacity-50 text-xs sm:text-sm">
            <template v-if="!isSharing">
              <svg class="w-4 h-4 fill-current" viewBox="0 0 24 24">
                <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 22.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.005 4.09H5.078z"/>
              </svg>
              <!-- 狭い画面では「／画像保存」の手前で折り返す（「画像」「保存」で割れないよう後半は nowrap）。 -->
              <span class="min-w-0">{{ t('report.shareX') }}<span class="whitespace-nowrap">{{ t('report.shareXOrSave') }}</span></span>
            </template>
            <template v-else>
              <svg class="animate-spin -ml-1 mr-2 h-4 w-4 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
              </svg>
              {{ t('report.generatingImage') }}
            </template>
          </button>
          <button @click="close" class="flex-1 min-w-0 py-3 bg-slate-100 dark:bg-slate-800 hover:bg-slate-200 dark:hover:bg-slate-700 text-slate-800 dark:text-white font-bold rounded-md transition-colors flex items-center justify-center gap-2 text-xs sm:text-sm">
            {{ t('report.backToDashboard') }}
          </button>
        </div>

        <!-- 画像出力オプション（並び順 / DJ NAME の有無）+ 縮小プレビュー -->
        <div v-if="isShareOptionsOpen && diffData" class="absolute inset-0 z-50 bg-slate-900/70 backdrop-blur-sm flex items-end sm:items-center justify-center p-3" @click.self="isShareOptionsOpen = false">
          <div class="bg-white dark:bg-slate-800 w-full max-w-2xl max-h-full rounded-md shadow-xl border border-slate-200 dark:border-slate-700 overflow-hidden flex flex-col">
            <div class="px-5 py-3 border-b border-slate-100 dark:border-slate-700 shrink-0">
              <h3 class="font-bold text-slate-800 dark:text-slate-100 text-base">{{ t('report.outputOptions') }}</h3>
              <p class="text-xs text-slate-500 dark:text-slate-400 mt-0.5">{{ t('report.outputOptionsSub') }}</p>
            </div>
            <div class="flex-1 min-h-0 overflow-y-auto p-4 flex max-sm:flex-col gap-4">
              <!-- プレビュー: 実物と同じコンポーネントを CSS で縮小して見せる -->
              <div class="shrink-0 self-center sm:self-start">
                <p class="section-label mb-1">{{ t('report.preview') }}</p>
                <div class="rounded-md overflow-hidden border border-slate-200 dark:border-slate-700 bg-slate-900" :style="{ width: `${SHARE_WIDTH * PREVIEW_SCALE}px`, height: `${previewHeight * PREVIEW_SCALE}px` }">
                  <div :style="{ width: `${SHARE_WIDTH}px`, transform: `scale(${PREVIEW_SCALE})`, transformOrigin: 'top left' }">
                    <UploadReportShareImage ref="previewImage" v-bind="shareImageProps" />
                  </div>
                </div>
              </div>
              <div class="flex-1 min-w-0 space-y-2">
                <!-- 載せる曲の選び方（並び順の上位 10 曲 / 自由選択） -->
                <div role="radiogroup" :aria-label="t('report.outputOptionsSub')" class="grid grid-cols-2 gap-2">
                  <button
                    v-for="opt in shareModeOptions"
                    :key="opt.value"
                    type="button"
                    role="radio"
                    :aria-checked="shareMode === opt.value"
                    @click="selectShareMode(opt.value)"
                    class="min-w-0 flex items-start gap-2 p-2.5 rounded-md border-2 transition-all text-left"
                    :class="shareMode === opt.value ? SHARE_TONE[opt.tone].box : 'border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700/50'"
                  >
                    <span class="mt-0.5 w-4 h-4 rounded-full border-2 flex items-center justify-center shrink-0" :class="shareMode === opt.value ? SHARE_TONE[opt.tone].ring : 'border-slate-300 dark:border-slate-600'">
                      <span v-if="shareMode === opt.value" class="w-2 h-2 rounded-full" :class="SHARE_TONE[opt.tone].dot"></span>
                    </span>
                    <span class="min-w-0">
                      <span class="block font-bold text-[13px] leading-5" :class="shareMode === opt.value ? SHARE_TONE[opt.tone].text : 'text-slate-700 dark:text-slate-200'">{{ opt.label }}</span>
                      <span class="block text-[11px] leading-4 text-slate-500 dark:text-slate-400">{{ opt.desc }}</span>
                    </span>
                  </button>
                </div>

                <!-- 自由選択: 更新曲の中から最大 10 曲。選んだ順がそのまま画像の並び順になる -->
                <div v-if="shareMode === 'custom'" class="rounded-md border border-slate-200 dark:border-slate-700 overflow-hidden">
                  <div class="flex items-center justify-between gap-2 px-3 py-2 bg-slate-50 dark:bg-slate-900/40 border-b border-slate-200 dark:border-slate-700">
                    <p class="text-xs font-bold text-slate-700 dark:text-slate-200">
                      {{ t('report.pick.selected') }}
                      <span class="ml-1 tabular-nums" :class="isPickFull ? 'text-amber-600 dark:text-amber-400' : 'text-slate-500 dark:text-slate-400'">{{ customSongs.length }}/{{ SHARE_MAX_SONGS }}</span>
                    </p>
                    <button
                      type="button"
                      @click="customKeys = []"
                      :disabled="customSongs.length === 0"
                      class="text-[11px] font-bold text-slate-500 dark:text-slate-400 hover:text-red-600 dark:hover:text-red-400 disabled:opacity-40 disabled:hover:text-slate-500 transition-colors"
                    >{{ t('report.pick.clear') }}</button>
                  </div>

                  <!-- 選んだ曲（番号 = 画像での並び順。押すと外れる） -->
                  <ul v-if="customSongs.length > 0" class="flex flex-wrap gap-1 px-3 py-2 border-b border-slate-200 dark:border-slate-700">
                    <li v-for="(song, i) in customSongs" :key="songKey(song)" class="min-w-0 max-w-full">
                      <button
                        type="button"
                        @click="togglePick(song)"
                        :title="t('report.pick.remove')"
                        class="max-w-full inline-flex items-center gap-1 pl-1.5 pr-1 py-0.5 rounded bg-slate-100 dark:bg-slate-700 hover:bg-red-50 dark:hover:bg-red-900/30 text-[11px] font-bold text-slate-700 dark:text-slate-200 transition-colors"
                      >
                        <span class="tabular-nums text-slate-400 dark:text-slate-500">{{ i + 1 }}</span>
                        <span class="truncate">{{ displayTitle(song) }}</span>
                        <span class="text-slate-400 dark:text-slate-500" aria-hidden="true">×</span>
                      </button>
                    </li>
                  </ul>
                  <p v-else class="px-3 py-2 text-[11px] font-bold text-amber-600 dark:text-amber-400 border-b border-slate-200 dark:border-slate-700">{{ t('report.pick.needOne') }}</p>

                  <!-- 画像の右端の列 -->
                  <div class="flex items-center gap-2 px-3 py-2 border-b border-slate-200 dark:border-slate-700">
                    <span class="shrink-0 text-[11px] font-bold text-slate-500 dark:text-slate-400">{{ t('report.pick.column') }}</span>
                    <div role="group" :aria-label="t('report.pick.column')" class="flex items-center gap-1 p-0.5 rounded-md bg-slate-100 dark:bg-slate-700/50">
                      <button
                        v-for="col in customColumnOptions"
                        :key="col.value"
                        type="button"
                        @click="customColumn = col.value"
                        :aria-pressed="shareColumn === col.value"
                        class="px-2 py-0.5 text-[11px] font-bold rounded whitespace-nowrap transition-colors"
                        :class="shareColumn === col.value
                          ? 'bg-white dark:bg-slate-800 text-blue-700 dark:text-blue-400 shadow-sm'
                          : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
                      >{{ col.label }}</button>
                    </div>
                  </div>

                  <!-- 候補の絞り込み・並び順 -->
                  <div class="flex items-center gap-2 px-3 py-2 border-b border-slate-200 dark:border-slate-700">
                    <input
                      v-model="pickQuery"
                      type="search"
                      :placeholder="t('report.pick.search')"
                      :aria-label="t('report.pick.search')"
                      class="flex-1 min-w-0 px-2 py-1 text-xs rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 text-slate-800 dark:text-slate-100 placeholder:text-slate-400"
                    />
                    <select
                      v-model="pickSort"
                      :aria-label="t('report.sortLabel')"
                      class="shrink-0 px-1.5 py-1 text-xs font-bold rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 text-slate-700 dark:text-slate-200"
                    >
                      <option v-for="opt in sortOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
                    </select>
                  </div>

                  <!-- 候補（更新曲）。上限に達したら未選択の行は選べない -->
                  <ul class="pick-list divide-y divide-slate-100 dark:divide-slate-700/60">
                    <li v-for="song in pickVisible" :key="songKey(song)">
                      <label
                        class="flex items-center gap-2 px-3 py-1.5 select-none transition-colors"
                        :class="!isPicked(song) && isPickFull ? 'opacity-40 cursor-not-allowed' : 'cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-700/40'"
                      >
                        <input
                          type="checkbox"
                          class="w-4 h-4 shrink-0 accent-blue-700"
                          :checked="isPicked(song)"
                          :disabled="!isPicked(song) && isPickFull"
                          @change="togglePick(song)"
                        />
                        <span class="w-1 self-stretch rounded-full shrink-0" :class="getDifficultyBarClass(song.difficulty)"></span>
                        <span class="min-w-0 flex-1">
                          <span class="block truncate text-xs font-bold text-slate-900 dark:text-slate-100">{{ displayTitle(song) }}</span>
                          <span class="block truncate text-[10px] font-bold leading-4 text-slate-500 dark:text-slate-400">
                            <template v-if="getNumericRank(song.informalRank)">☆{{ getNumericRank(song.informalRank) }} ・ </template>EX {{ song.newScore }}<template v-if="song.oldScore > 0 && song.scoreIncrease > 0"> (+{{ song.scoreIncrease }})</template>
                          </span>
                        </span>
                        <span v-if="isPicked(song)" class="shrink-0 w-5 h-5 rounded-full bg-blue-600 text-white text-[10px] font-bold tabular-nums flex items-center justify-center">{{ pickOrder(song) }}</span>
                        <span class="shrink-0 text-xs font-bold tabular-nums text-slate-700 dark:text-slate-300">{{ pickMetric(song) }}</span>
                      </label>
                    </li>
                    <li v-if="pickCandidates.length === 0" class="px-3 py-4 text-center text-xs text-slate-500 dark:text-slate-400">{{ t('report.pick.empty') }}</li>
                  </ul>
                  <button
                    v-if="pickCandidates.length > pickVisible.length"
                    type="button"
                    @click="pickVisibleCount += PAGE_SIZE"
                    class="w-full py-1.5 text-[11px] font-bold text-blue-700 dark:text-blue-400 hover:bg-slate-50 dark:hover:bg-slate-700/40 border-t border-slate-200 dark:border-slate-700 transition-colors"
                  >{{ t('report.showMore', { n: pickCandidates.length - pickVisible.length }) }}</button>
                  <p v-if="isPickFull" class="px-3 py-1.5 text-[11px] text-slate-500 dark:text-slate-400 border-t border-slate-200 dark:border-slate-700">{{ t('report.pick.full', { max: SHARE_MAX_SONGS }) }}</p>
                </div>

                <label v-if="canShowOwner" class="flex items-center gap-3 px-4 py-3 rounded-md border border-slate-200 dark:border-slate-700 cursor-pointer select-none">
                  <input type="checkbox" v-model="showDjName" class="w-4 h-4 accent-blue-700" />
                  <span class="text-sm font-bold text-slate-700 dark:text-slate-200">{{ t('report.showDjName') }}</span>
                </label>
              </div>
            </div>
            <!--
              押す前に経路を伝えておく。PC は X のタブへ移ると後の案内パネルが目に入らないので、
              「投稿画面で貼り付ける」操作はここで知らせる。
            -->
            <p class="px-4 pt-3 text-[11px] leading-relaxed text-slate-500 dark:text-slate-400 border-t border-slate-100 dark:border-slate-700 shrink-0">
              {{ t(usesShareSheet ? 'report.shareHintMobile' : 'report.shareHintPc') }}
            </p>
            <div class="px-4 py-3 flex gap-2 shrink-0">
              <button @click="isShareOptionsOpen = false" class="py-2.5 px-3 bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 font-bold rounded-md text-xs sm:text-sm transition-colors">
                {{ t('common.cancel') }}
              </button>
              <button @click="saveShareImage" :disabled="!shareBlob" class="flex-1 min-w-0 py-2.5 bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 font-bold rounded-md text-xs sm:text-sm transition-colors disabled:opacity-60">
                {{ t('report.saveImage') }}
              </button>
              <!-- 画像は先回りで生成しておき、click 時は共有だけ行う（ユーザー操作の有効期間内に navigator.share / window.open を呼ぶため） -->
              <button @click="confirmShare" :disabled="isGeneratingShare || isSharing || !hasShareSongs" class="flex-1 min-w-0 py-2.5 bg-black hover:bg-slate-800 text-white font-bold rounded-md text-xs sm:text-sm transition-colors flex items-center justify-center gap-1.5 disabled:opacity-60">
                <template v-if="isGeneratingShare || isSharing">
                  <svg class="animate-spin h-4 w-4 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                    <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                    <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
                  </svg>
                  {{ t('report.generatingImage') }}
                </template>
                <template v-else-if="shareGenError">
                  {{ t('report.regenerateImage') }}
                </template>
                <template v-else>
                  <svg class="w-4 h-4 fill-current" viewBox="0 0 24 24">
                    <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 22.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.005 4.09H5.078z"/>
                  </svg>
                  {{ t('report.generateAndShare') }}
                </template>
              </button>
            </div>
            <p v-if="shareGenError" class="px-4 pb-3 text-xs font-bold text-red-600 dark:text-red-400 shrink-0">{{ t('report.generateError') }}</p>
          </div>
        </div>

        <!--
          PC ブラウザ（と Web Share が使えないスマホ）向けの案内。画像はコピー済み / ダウンロード済みで、
          X の投稿画面は window.open で開いている。ポップアップブロック等で開かなかった場合のために
          通常のリンク（ユーザーの click で開くのでブロックされない）も置く。
        -->
        <div v-if="shareFallback" class="absolute inset-0 z-50 bg-slate-900/70 backdrop-blur-sm flex items-end sm:items-center justify-center p-3" @click.self="shareFallback = null">
          <div class="bg-white dark:bg-slate-800 w-full max-w-md rounded-md shadow-xl border border-slate-200 dark:border-slate-700 overflow-hidden flex flex-col">
            <div class="px-5 py-4">
              <h3 class="font-bold text-slate-800 dark:text-slate-100 text-base">{{ t('report.postReadyTitle') }}</h3>
              <p class="text-sm text-slate-700 dark:text-slate-200 mt-2">{{ t(shareFallback === 'copied' ? 'report.fallbackCopied' : 'report.fallbackDownloaded') }}</p>
              <p class="text-xs text-slate-500 dark:text-slate-400 mt-2">{{ t('report.fallbackHint') }}</p>
            </div>
            <div class="px-4 py-3 border-t border-slate-100 dark:border-slate-700 flex gap-2 shrink-0">
              <button @click="shareFallback = null" class="py-2.5 px-4 bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 font-bold rounded-md text-sm transition-colors">
                {{ t('common.close') }}
              </button>
              <button @click="saveShareImage" class="flex-1 py-2.5 bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 font-bold rounded-md text-sm transition-colors">
                {{ t('report.saveImage') }}
              </button>
              <a :href="intentUrl" target="_blank" rel="noopener" class="flex-1 py-2.5 bg-black hover:bg-slate-800 text-white font-bold rounded-md text-sm transition-colors flex items-center justify-center gap-1.5">
                <svg class="w-4 h-4 fill-current" viewBox="0 0 24 24">
                  <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 22.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.005 4.09H5.078z"/>
                </svg>
                {{ t('report.openX') }}
              </a>
            </div>
          </div>
        </div>

      </div>
    </div>

    <!-- X シェア用オフスクリーン領域（画面外に置いて html2canvas でキャプチャ。レイアウトは UploadReportShareImage） -->
    <div v-if="isOpen && diffData" class="fixed top-0 -left-[3000px] z-[-1] pointer-events-none" aria-hidden="true">
      <UploadReportShareImage ref="captureImage" v-bind="shareImageProps" />
    </div>
  </Teleport>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 アップロード結果 / 履歴差分を表示するモーダル。
 *
 * 機能:
 *  - ティア（昇格は金枠）/ 集計タイル / フォルダアナウンス / リーグ進捗 / 更新曲リストを表示
 *  - 更新曲は 1 曲 1 行の表形式。並び替え・絞り込み・段階表示に対応し、行を開くと内訳と
 *    「正規 / MIRROR / RANDOM / R-RAN / S-RAN」のオプション投票が出る
 *  - X (Twitter) 共有用の画像（UploadReportShareImage）を html2canvas でオフスクリーン生成。
 *    オプション画面を開いている間に先回りで生成し、ポストボタンの click 中に共有処理だけを走らせる
 *    （生成を待ってから navigator.share / window.open を呼ぶとユーザー操作の有効期間が切れて
 *    ブロックされ、X に飛ばない）
 *  - 共有の経路は端末で分ける（utils/shareToX）。スマホ / タブレットは Web Share API でファイル共有
 *    （共有シートから X アプリを選ぶと画像 + 定型文入りの投稿画面が開く）、PC はクリップボードコピー
 *    （不可ならダウンロード）+ ブラウザで定型文入りの X 投稿画面（x.com/intent/post）を開く
 *
 * props:
 *  - isOpen: モーダル開閉
 *  - diffData: 差分情報（UploadDiffResult 型）
 *  - reportDate: 成長記録から開いたときの取り込み日時（ISO）。未指定なら「いま」として扱う
 *  - reportVersion: その差分が属する作品番号。未指定なら現行作
 *  - hideOwner: 他人の履歴を見ているとき true。共有画像に自分の DJ NAME やアイコン装飾を載せない
 * emits:
 *  - close: 閉じる
 *  - navigate: 他タブへのナビゲーション要求
 */
import { ref, computed, watch, nextTick } from 'vue';
import type { UploadDiffResult, UpdatedSong } from './../types/UploadDiff';
import { getNextRankInfo, getNextRateTierRankInfo, previousTierFrame } from '../utils/beatTier';
import type { RankInfo } from '../utils/beatTier';
import {
  clearTypeShort,
  computeReportStats,
  displayTitle,
  formatStatValue,
  getNumericRank,
  getScoreGradeInfo,
  getSongTierInfo,
  getSongTierTransition,
  isNewAaa,
  pickStatTiles,
  SHARE_MAX_SONGS,
  songKey,
  sortUpdatedSongs,
  tierLabel,
} from '../utils/uploadReport';
import type { ShareColumn, SongSort, StatKey } from '../utils/uploadReport';
import RankIcon from './RankIcon.vue';
import DivisionIcon from './DivisionIcon.vue';
import UploadReportShareImage from './UploadReportShareImage.vue';
import { useAuth, API_BASE } from '../composables/useAuth';
import { usePastScores, chartKey as pastChartKey } from '../composables/usePastScores';
import {
  ROADMAP_LINES, ROADMAP_LINE_BUCKET, ROADMAP_LINE_LABEL, remainingToClear, scoreToBucket, summarizeRoadmap,
  formatRoadmapLevel, roadmapMaxLevel,
} from '../utils/roadmapLevels';
import type { RoadmapChartLike, RoadmapLevelTableLike, RoadmapLine } from '../utils/roadmapLevels';
import { useRateTierVisibility } from '../composables/useRateTierVisibility';
import { useI18n } from '../composables/useI18n';
import { CURRENT_VERSION, versionName } from '../utils/iidxVersions';
import { ignoreOutside, withHtml2canvasTextFix } from '../utils/html2canvasHelpers';
import { jstParts, nowJstParts } from '../utils/jstTime';
import { canShareImageNatively, copyImageToClipboard, downloadBlob, isIosDevice, xIntentUrl } from '../utils/shareToX';
import html2canvas from 'html2canvas';

const { t } = useI18n();

const props = defineProps<{
  isOpen: boolean;
  diffData: UploadDiffResult | null;
  reportDate?: string | null;
  reportVersion?: number | null;
  hideOwner?: boolean;
}>();

const { authHeaders, user } = useAuth();
const { showRateTier } = useRateTierVisibility();
/** 過去作ベスト（App がアップロード時に取得済み。ロードマップの「更新前」を作るのに使う）。 */
const { pastBestByChart } = usePastScores();

/**
 * リーグモードの進捗。アップロード時点のスナップショット（utils/leagueReport.ts）をそのまま表示する。
 * 今回の更新に課題曲が含まれないレポート・2026-09-23 より前の成長記録では null（欄を出さない）。
 */
const leagueProgress = computed(() => props.diffData?.league ?? null);
/** 「#6 9/21〜9/27」のような開催回の表記（番号も期間も無ければ空）。 */
const leagueWeekLabel = computed(() => {
  const lp = leagueProgress.value;
  if (!lp) return '';
  const md = (iso: string | null) => {
    if (!iso) return '';
    const p = jstParts(iso);
    return p ? `${p.month}/${p.day}` : '';
  };
  const range = lp.startsAt && lp.endsAt ? `${md(lp.startsAt)}〜${md(lp.endsAt)}` : '';
  return [lp.weekNo != null ? `#${lp.weekNo}` : '', range].filter(Boolean).join(' ');
});

/** 今回のアップロードで新たに達成したロードマップの目標（譜面 × AA / AAA / MAX-）。 */
interface RoadmapNewTarget { key: string; title: string; difficultyName: string; level: number; line: RoadmapLine; no: number }

/**
 * スコアロードマップの進捗（今回のアップロードで変化があったときだけ。無ければ null）。
 * next = 今のレベルより上で最初の未達成レベルと、その達成までの残り件数。
 */
const roadmapProgress = ref<null | {
  /** null = どのレベルも未達成（レベル 0 は実在するので 0 ではない）。 */
  oldLevel: number | null;
  newLevel: number | null;
  maxLevel: number;
  newlyCleared: number;
  newlyComplete: number;
  targets: RoadmapNewTarget[];
  next: { no: number; remaining: number } | null;
}>(null);
/** 新たに達成した目標のうち、最初に見せる件数。 */
const ROADMAP_TARGETS_SHOWN = 8;
const showAllRoadmapTargets = ref(false);

/**
 * 【関数の役割】 アップロード直後のロードマップの変化を計算する（2026-09-23 追加）。
 *
 * 更新後の歴代ベストは API（サーバーが scores ∪ past_scores を読む）から取る。更新前は、今回更新された譜面だけ
 * 「旧スコアと過去作ベストの大きい方」に差し戻して作る（それ以外の譜面は今回変わっていない）。
 * 判定は utils/roadmapLevels.ts（ロードマップ画面と同じ規則）。取得できない・変化が無いときは何も出さない。
 * 成長記録から開いた過去のレポート（reportDate あり）では、今の状態と比べても意味が無いので出さない。
 */
const loadRoadmapProgress = async () => {
  roadmapProgress.value = null;
  showAllRoadmapTargets.value = false;
  const updates = props.diffData?.updatedSongs ?? [];
  if (!user.value || props.reportDate || updates.length === 0) return;
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15000);
    let data: any;
    try {
      const res = await fetch(`${API_BASE}/api/scores/score-roadmap`, { headers: authHeaders(), signal: controller.signal });
      if (!res.ok) return;
      data = await res.json();
    } finally {
      clearTimeout(timer);
    }
    const table: RoadmapLevelTableLike | undefined = data?.model?.levelTable;
    if (!data?.ready || !table?.slots?.length || !data.user?.found) return;
    const charts: RoadmapChartLike[] = data.charts;
    const newBuckets = new Map<number, number>(data.user.plays ?? []);

    // 更新前: 今回更新された譜面だけ「旧スコアと過去作ベストの大きい方」に戻す
    const pastBest = pastBestByChart();
    const chartByKey = new Map(charts.map((c) => [`${c.title}\u0000${c.difficultyName}`, c]));
    const oldBuckets = new Map(newBuckets);
    for (const u of updates) {
      const c = chartByKey.get(`${u.title}\u0000${u.difficulty}`);
      if (!c) continue;
      const before = Math.max(u.oldScore, pastBest.get(pastChartKey(u.title, u.difficulty))?.score ?? 0);
      if (before > 0) oldBuckets.set(c.i, scoreToBucket(before, c.notes));
      else oldBuckets.delete(c.i);
    }

    const before = summarizeRoadmap(charts, table, oldBuckets);
    const after = summarizeRoadmap(charts, table, newBuckets);
    const targets: RoadmapNewTarget[] = [];
    for (const c of charts) {
      if (!c.levels) continue;
      for (const line of ROADMAP_LINES) {
        const no = c.levels[line];
        if (no == null) continue;
        const line0 = ROADMAP_LINE_BUCKET[line];
        if ((newBuckets.get(c.i) ?? -1) >= line0 && (oldBuckets.get(c.i) ?? -1) < line0) {
          targets.push({ key: `${c.i}:${line}`, title: c.title, difficultyName: c.difficultyName, level: c.level, line, no });
        }
      }
    }
    const newlyCleared = after.levels.filter((l, k) => l.cleared && !before.levels[k].cleared).length;
    const newlyComplete = after.levels.filter((l, k) => l.complete && !before.levels[k].complete).length;
    if (targets.length === 0 && newlyCleared === 0 && after.myLevel === before.myLevel) return;

    // 難しいレベル順。同じレベルなら MAX- → AAA → AA
    targets.sort((a, b) => b.no - a.no || ROADMAP_LINES.indexOf(b.line) - ROADMAP_LINES.indexOf(a.line));
    const nextLv = after.levels.find((l) => l.no > (after.myLevel ?? -Infinity) && !l.cleared);
    roadmapProgress.value = {
      oldLevel: before.myLevel,
      newLevel: after.myLevel,
      maxLevel: roadmapMaxLevel(table),
      newlyCleared,
      newlyComplete,
      targets,
      next: nextLv ? { no: nextLv.no, remaining: remainingToClear(nextLv.n, nextLv.played, nextLv.done) } : null,
    };
  } catch {
    // 握り潰し: ロードマップはレポートの付加情報なので、取れなければ出さないだけ
  }
};
const roadmapTargetsShown = computed(() => {
  const all = roadmapProgress.value?.targets ?? [];
  return showAllRoadmapTargets.value ? all : all.slice(0, ROADMAP_TARGETS_SHOWN);
});
/** 曲名の表記（ロードマップ画面と同じ: ANOTHER は表記なし、LEGGENDARIA は末尾に [L]）。 */
const roadmapChartName = (title: string, difficultyName: string) =>
  (difficultyName === 'LEGGENDARIA' ? `${title}[L]` : title);

/** DIVISION 表示名（0=LEGEND）。 */
const divisionName = (tier: number) => (tier === 0 ? 'DIVISION LEGEND' : `DIVISION ${tier}`);

// ─── ティアカード / 集計タイル ───────────────────────────────────

/** 自分のレポートのときだけ、ティアアイコンにアプリ内と同じ装飾（サポーター光沢・前作ティア外枠）を付ける。 */
const ownIconProps = computed(() => {
  if (props.hideOwner) return { isSupporter: false, beatFrame: {}, rateFrame: {} };
  return {
    isSupporter: !!user.value?.isSupporter,
    beatFrame: previousTierFrame(user.value?.previousBeatPt, 'beat'),
    rateFrame: previousTierFrame(user.value?.previousRatePt, 'rate'),
  };
});

interface TierCard {
  key: 'beat' | 'rate';
  label: string;
  tier: RankInfo;
  tierName: string;
  oldTierName: string;
  tierUp: boolean;
  oldTotal: string;
  total: string;
  delta: number;
  deltaText: string;
  deltaClass: string;
  barClass: string;
  next: RankInfo | null;
  remaining: string;
  progress: number;
  frame: { frameRankName?: string; frameTier?: number };
}

const FALLBACK_TIER: RankInfo = { name: 'Beginner', minPoints: 0, color: 'text-slate-400' };

const tierCards = computed<TierCard[]>(() => {
  const d = props.diffData;
  if (!d) return [];
  const build = (
    key: 'beat' | 'rate', label: string, oldTier: RankInfo | null, newTier: RankInfo | null,
    oldTotal: number, total: number, delta: number, next: { nextRank?: RankInfo; progress: number },
  ): TierCard => ({
    key,
    label,
    tier: newTier ?? FALLBACK_TIER,
    tierName: tierLabel(newTier),
    oldTierName: tierLabel(oldTier),
    tierUp: !!oldTier && !!newTier && oldTier.minPoints < newTier.minPoints,
    oldTotal: oldTotal.toFixed(1),
    total: total.toFixed(1),
    delta,
    deltaText: `${delta > 0 ? '+' : ''}${delta.toFixed(1)}`,
    deltaClass: key === 'beat' ? 'text-blue-700 dark:text-blue-400' : 'text-emerald-600 dark:text-emerald-400',
    barClass: key === 'beat' ? 'bg-blue-600' : 'bg-emerald-500',
    next: next.nextRank ?? null,
    remaining: next.nextRank ? Math.max(0, next.nextRank.minPoints - total).toFixed(1) : '',
    progress: next.progress,
    frame: key === 'beat' ? ownIconProps.value.beatFrame : ownIconProps.value.rateFrame,
  });

  const cards = [
    build('beat', 'BEAT-TIER', d.oldTier, d.newTier, d.oldTotalBeatPt, d.newTotalBeatPt, d.totalBeatPtIncrease, getNextRankInfo(d.newTotalBeatPt)),
  ];
  // Rate-Tier は表示が ON かつ +0.1 以上増えた場合のみ。
  const newRate = d.newTotalRatePt ?? 0;
  const oldRate = d.oldTotalRatePt ?? 0;
  if (showRateTier.value && newRate > 0 && newRate - oldRate >= 0.1) {
    cards.push(build('rate', 'RATE-TIER', d.oldRateTier, d.newRateTier, oldRate, newRate, newRate - oldRate, getNextRateTierRankInfo(newRate)));
  }
  return cards;
});

const STAT_COLOR_CLASS: Record<StatKey, string> = {
  updated: 'text-slate-900 dark:text-slate-100',
  exGain: 'text-blue-700 dark:text-blue-400',
  allTimeBest: 'text-amber-600 dark:text-amber-400',
  newAaa: 'text-yellow-600 dark:text-yellow-400',
  lampUp: 'text-emerald-600 dark:text-emerald-400',
  top100: 'text-amber-600 dark:text-amber-400',
};

const reportStats = computed(() => computeReportStats(props.diffData));

const statTiles = computed(() => pickStatTiles(reportStats.value).map(({ key, value }) => ({
  key,
  value,
  text: formatStatValue(key, value),
  label: t(`report.stat.${key}`),
  colorClass: STAT_COLOR_CLASS[key],
})));

// ─── 更新曲リスト（並び替え / 絞り込み / 段階表示 / 行の展開） ───────────

type ListSort = SongSort;
type ListFilter = 'all' | 'allTimeBest' | 'newAaa' | 'lampUp' | 'top100';

/** 1 度に描画する行数。新作の初回取り込みでは 1000 行を超えるので段階表示にする。 */
const PAGE_SIZE = 50;

const listSort = ref<ListSort>('beat');
const listFilter = ref<ListFilter>('all');
const visibleCount = ref(PAGE_SIZE);
/** 展開中の行（曲名 + 難易度）。同時に開くのは 1 行だけ。 */
const expandedKey = ref<string | null>(null);

const sortOptions = computed(() => {
  const opts: { value: ListSort; label: string }[] = [{ value: 'beat', label: t('report.sort.beat') }];
  if (showRateTier.value) opts.push({ value: 'rate', label: t('report.sort.rate') });
  opts.push(
    { value: 'tier', label: t('report.sort.tier') },
    { value: 'gain', label: t('report.sort.gain') },
    { value: 'level', label: t('report.sort.level') },
  );
  return opts;
});

/** PT 列に出す指標。並び順が RATE-PT のときだけ RATE-PT に切り替える。 */
const ptColumn = computed<'beat' | 'rate'>(() => (listSort.value === 'rate' ? 'rate' : 'beat'));

const FILTERS: Record<ListFilter, (s: UpdatedSong) => boolean> = {
  all: () => true,
  allTimeBest: (s) => !!s.allTimeBestUpdated,
  newAaa: isNewAaa,
  lampUp: (s) => s.clearTypeImproved,
  top100: (s) => !!s.isInTop100 && s.beatPtIncrease > 0,
};

/** 絞り込みチップ。該当 0 件のものは出さない（「すべて」だけなら行ごと非表示）。 */
const filterChips = computed(() => {
  const s = reportStats.value;
  const chips: { value: ListFilter; label: string; count: number }[] = [
    { value: 'all', label: t('report.filter.all'), count: s.updated },
    { value: 'allTimeBest', label: `★ ${t('report.stat.allTimeBest')}`, count: s.allTimeBest },
    { value: 'newAaa', label: t('report.stat.newAaa'), count: s.newAaa },
    { value: 'lampUp', label: t('report.stat.lampUp'), count: s.lampUp },
    { value: 'top100', label: t('report.stat.top100'), count: s.top100 },
  ];
  return chips.filter(c => c.value === 'all' || c.count > 0);
});

const filteredSongs = computed(() => {
  if (!props.diffData) return [];
  return sortUpdatedSongs(props.diffData.updatedSongs.filter(FILTERS[listFilter.value]), listSort.value);
});

const visibleSongs = computed(() => filteredSongs.value.slice(0, visibleCount.value));

// 並び順・絞り込みを変えたら先頭から出し直す。
watch([listSort, listFilter], () => {
  visibleCount.value = PAGE_SIZE;
  expandedKey.value = null;
});

const toggleRow = (song: UpdatedSong) => {
  const key = song.title + song.difficulty;
  expandedKey.value = expandedKey.value === key ? null : key;
};

/** EX SCORE の 2 行目。現行作で初プレーの譜面は増分 = スコアそのものなので NEW と出す。 */
const scoreSub = (song: UpdatedSong) => {
  if (!(song.oldScore > 0)) return t('report.newPlay');
  return song.scoreIncrease > 0 ? `+${song.scoreIncrease}` : '±0';
};

/** 塗り替えた歴代ベストとの差（値を持たない古い履歴では 0）。 */
const allTimeGain = (song: UpdatedSong) => (song.allTimeBeatenScore ? Math.max(0, song.newScore - song.allTimeBeatenScore) : 0);

const ptMain = (song: UpdatedSong) => ((ptColumn.value === 'rate' ? song.newRatePt : song.newBeatPt) ?? 0).toFixed(1);
const ptSub = (song: UpdatedSong) => {
  const inc = ptColumn.value === 'rate' ? song.ratePtIncrease : song.beatPtIncrease;
  return inc > 0 ? `+${inc.toFixed(1)}` : '';
};
/** TOP100 対象は色付き（BEAT=金 / RATE=緑）、圏外は無彩色。 */
const ptClass = (song: UpdatedSong) => {
  if (ptColumn.value === 'rate') return song.isInRateTop100 ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-500 dark:text-slate-400';
  return song.isInTop100 ? 'text-amber-500 dark:text-amber-400' : 'text-slate-500 dark:text-slate-400';
};

const GRADE_COLOR_CLASS: Record<string, string> = { AAA: 'text-yellow-500', AA: 'text-blue-400', A: 'text-green-500' };
const gradeColorClass = (song: UpdatedSong) => GRADE_COLOR_CLASS[getScoreGradeInfo(song.newScore, song.maxScore).gradeName] ?? 'text-slate-400';
/** DJ LEVEL 列の大きい文字（近い方の境界表記）の色。MAX-n は紫（スコア一覧の MAX- と同じ）。 */
const MAIN_GRADE_COLOR_CLASS: Record<string, string> = { ...GRADE_COLOR_CLASS, MAX: 'text-yellow-500', 'MAX-': 'text-purple-500 dark:text-purple-400' };
const mainGradeColorClass = (song: UpdatedSong) => MAIN_GRADE_COLOR_CLASS[getScoreGradeInfo(song.newScore, song.maxScore).mainColorKey] ?? 'text-slate-400';

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

/** 畳んだ行に出す「投票済みオプション」のラベル。 */
const votedLabels = (song: UpdatedSong) => {
  const mine = songVotes.value[song.title + song.difficulty]?.myVotes ?? [];
  return optionTypes.filter(o => mine.includes(o.value)).map(o => o.label);
};

/**
 * 【関数の役割】 投票ボタンに適用する Tailwind クラスを返す。
 * 自分が投票済みのオプションは色付きハイライトに、それ以外はグレー枠に。
 */
const getVoteClass = (title: string, difficulty: string, optValue: string, opt: typeof optionTypes[0]) => {
  const key = title + difficulty;
  const isVoted = songVotes.value[key]?.myVotes?.includes(optValue) ?? false;
  if (isVoted) return `${opt.activeBg} ${opt.activeText} ${opt.activeBorder}`;
  return 'bg-white dark:bg-slate-900 text-slate-600 dark:text-slate-400 border-slate-200 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800';
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

// ─── 共有画像 ─────────────────────────────────────────────

/** 共有画像の横幅（px）。UploadReportShareImage の .sr の幅と揃える。 */
const SHARE_WIDTH = 1080;
/** キャプチャ倍率。出力は 1620px 幅になる。 */
const SHARE_SCALE = 1.5;
/** オプション画面のプレビュー倍率（1080px → 270px）。 */
const PREVIEW_SCALE = 0.25;
const SHOW_NAME_KEY = 'beat-seeker-report-show-name';

/** キャプチャ用（画面外）とプレビュー用の UploadReportShareImage。 */
const captureImage = ref<InstanceType<typeof UploadReportShareImage> | null>(null);
const previewImage = ref<InstanceType<typeof UploadReportShareImage> | null>(null);
/** プレビューの元の高さ（px）。内容で変わるので描画後に測る。 */
const previewHeight = ref(1440);
/** 画像生成 + シェア進行中フラグ（ボタンにスピナー表示）。 */
const isSharing = ref(false);
/** シェアオプション（並び順など）の開閉。 */
const isShareOptionsOpen = ref(false);
/**
 * 共有画像に載せる曲の選び方。'beat' / 'rate' / 'tier' はその順の上位 10 曲、
 * 'allTime' は歴代自己ベストを更新した曲だけを BEAT-PT 順に上位 10 曲、
 * 'custom' は更新曲の中からユーザーが選んだ曲（最大 10 曲、選んだ順）。
 */
type ShareMode = 'beat' | 'rate' | 'tier' | 'allTime' | 'custom';
const shareMode = ref<ShareMode>('beat');
/** 自由選択で選んだ曲のキー（{@link songKey}）。配列の順がそのまま画像の並び順になる。 */
const customKeys = ref<string[]>([]);
/** 自由選択のとき画像の右端の列に出す指標（並び順モードではその並び順の指標で固定）。 */
const customColumn = ref<ShareColumn>('beat');
/** 自由選択の候補リスト: 曲名の絞り込み・並び順・描画件数（初回取り込みは 1000 行を超えるので段階表示）。 */
const pickQuery = ref('');
const pickSort = ref<SongSort>('beat');
const pickVisibleCount = ref(PAGE_SIZE);
/** 画像に DJ NAME を入れるか（端末ごとに記憶）。 */
const showDjName = ref(localStorage.getItem(SHOW_NAME_KEY) !== '0');
watch(showDjName, (v) => localStorage.setItem(SHOW_NAME_KEY, v ? '1' : '0'));

/** 自分のレポートで、かつ表示名があるときだけ DJ NAME の選択肢を出す。 */
const canShowOwner = computed(() => !props.hideOwner && !!user.value?.displayName);

/** 取り込み日（JST）を "2026.09.17" 形式で。 */
const dateLabel = computed(() => {
  const p = jstParts(props.reportDate || new Date()) ?? nowJstParts();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${p.year}.${pad(p.month)}.${pad(p.day)}`;
});

const versionLabel = computed(() => {
  const v = props.reportVersion ?? CURRENT_VERSION;
  return `IIDX ${v} ${versionName(v)}`;
});

/**
 * 歴代自己ベストを更新した曲（★ 過去作のベストを塗り替えた / ☆ 今作のベストをさらに更新）だけを
 * BEAT-PT 順に並べたもの。「歴代ベストのみ」の選択肢はこれが 1 曲以上あるときだけ出す
 * （過去作 CSV 未取り込みのユーザーには常に 0 件になるため）。
 */
const allTimeBestSongs = computed<UpdatedSong[]>(() => {
  const songs = (props.diffData?.updatedSongs ?? []).filter(s => s.allTimeBestUpdated || s.allTimeBestExtended);
  return sortUpdatedSongs(songs, 'beat');
});

/** 選び方の選択肢。RATE-PT 順は Rate-Tier を表示しているユーザーにだけ出す。 */
const shareModeOptions = computed(() => {
  const opts: { value: ShareMode; label: string; desc: string; tone: 'blue' | 'emerald' | 'amber' | 'slate' }[] = [
    { value: 'beat', label: t('report.sortByBeatPt'), desc: t('report.sortByBeatPtDesc'), tone: 'blue' },
  ];
  if (showRateTier.value) opts.push({ value: 'rate', label: t('report.sortByRatePt'), desc: t('report.sortByRatePtDesc'), tone: 'emerald' });
  opts.push({ value: 'tier', label: t('report.sortBySongTier'), desc: t('report.sortBySongTierDesc'), tone: 'amber' });
  if (allTimeBestSongs.value.length > 0) {
    opts.push({ value: 'allTime', label: `★ ${t('report.onlyAllTimeBest')}`, desc: t('report.onlyAllTimeBestDesc', { max: SHARE_MAX_SONGS }), tone: 'amber' });
  }
  opts.push({ value: 'custom', label: t('report.pickCustom'), desc: t('report.pickCustomDesc', { max: SHARE_MAX_SONGS }), tone: 'slate' });
  return opts;
});

/** 選択中の選択肢の枠・丸・文字の色（選び方ごとに色を変えて、どれを選んでいるか一目で分かるようにする）。 */
const SHARE_TONE: Record<string, { box: string; ring: string; dot: string; text: string }> = {
  blue: { box: 'border-blue-600 bg-blue-50 dark:bg-blue-900/30', ring: 'border-blue-600', dot: 'bg-blue-600', text: 'text-blue-700 dark:text-blue-300' },
  emerald: { box: 'border-emerald-500 bg-emerald-50 dark:bg-emerald-900/30', ring: 'border-emerald-500', dot: 'bg-emerald-500', text: 'text-emerald-700 dark:text-emerald-300' },
  amber: { box: 'border-amber-500 bg-amber-50 dark:bg-amber-900/30', ring: 'border-amber-500', dot: 'bg-amber-500', text: 'text-amber-700 dark:text-amber-300' },
  slate: { box: 'border-slate-700 bg-slate-100 dark:border-slate-300 dark:bg-slate-700/60', ring: 'border-slate-700 dark:border-slate-300', dot: 'bg-slate-700 dark:bg-slate-300', text: 'text-slate-900 dark:text-slate-100' },
};

/** 更新曲をキーで引く表（自由選択のキー → 曲）。 */
const songByKey = computed(() => new Map((props.diffData?.updatedSongs ?? []).map(s => [songKey(s), s] as const)));

/** 自由選択で選んだ曲（選んだ順）。 */
const customSongs = computed(() => customKeys.value.map(k => songByKey.value.get(k)).filter((s): s is UpdatedSong => !!s));

/** 共有画像に載せる曲（最大 SHARE_MAX_SONGS 曲、表示順）。 */
const shareSongs = computed<UpdatedSong[]>(() => {
  if (!props.diffData) return [];
  if (shareMode.value === 'custom') return customSongs.value;
  if (shareMode.value === 'allTime') return allTimeBestSongs.value.slice(0, SHARE_MAX_SONGS);
  return sortUpdatedSongs(props.diffData.updatedSongs, shareMode.value).slice(0, SHARE_MAX_SONGS);
});

/** 自由選択の右端の列の選択肢。 */
const customColumnOptions = computed(() => {
  const opts: { value: ShareColumn; label: string }[] = [{ value: 'beat', label: 'BEAT-PT' }];
  if (showRateTier.value) opts.push({ value: 'rate', label: 'RATE-PT' });
  opts.push({ value: 'tier', label: t('report.songTier') });
  return opts;
});

/** 画像の右端の列。並び順モードはその指標（歴代ベストのみは BEAT-PT）、自由選択はユーザーの選択（RATE-PT は表示 ON のときだけ）。 */
const shareColumn = computed<ShareColumn>(() => {
  if (shareMode.value === 'allTime') return 'beat';
  if (shareMode.value !== 'custom') return shareMode.value;
  return customColumn.value === 'rate' && !showRateTier.value ? 'beat' : customColumn.value;
});

const SHARE_MODE_LABEL_KEY: Record<ShareMode, string> = {
  beat: 'report.sort.beat',
  rate: 'report.sort.rate',
  tier: 'report.sort.tier',
  allTime: 'report.onlyAllTimeBest',
  custom: 'report.pickCustom',
};

/** 載せる曲が 1 曲も無い間は画像を作らない（空のリストを共有させない）。 */
const hasShareSongs = computed(() => (shareMode.value !== 'custom' && shareMode.value !== 'allTime') || shareSongs.value.length > 0);

// ── 自由選択の候補リスト（並び順の選択肢は一覧と同じ sortOptions を使う） ──

/** 候補（曲名で絞り込み → 並び替え）。 */
const pickCandidates = computed(() => {
  if (!props.diffData) return [];
  const q = pickQuery.value.trim().toLowerCase();
  const songs = q ? props.diffData.updatedSongs.filter(s => s.title.toLowerCase().includes(q)) : props.diffData.updatedSongs;
  return sortUpdatedSongs(songs, pickSort.value);
});
const pickVisible = computed(() => pickCandidates.value.slice(0, pickVisibleCount.value));
watch([pickQuery, pickSort], () => { pickVisibleCount.value = PAGE_SIZE; });

const isPicked = (song: UpdatedSong) => customKeys.value.includes(songKey(song));
/** 選んだ順の番号（1 始まり）。画像もこの順に並ぶ。 */
const pickOrder = (song: UpdatedSong) => customKeys.value.indexOf(songKey(song)) + 1;
const isPickFull = computed(() => customKeys.value.length >= SHARE_MAX_SONGS);

/** 【関数の役割】 候補の選択を切り替える。上限に達している間は追加しない。 */
const togglePick = (song: UpdatedSong) => {
  const key = songKey(song);
  if (customKeys.value.includes(key)) customKeys.value = customKeys.value.filter(k => k !== key);
  else if (!isPickFull.value) customKeys.value = [...customKeys.value, key];
};

/** 候補リストの右端に出す値（並び順に合わせた指標）。 */
const pickMetric = (song: UpdatedSong) => {
  if (pickSort.value === 'rate') return song.newRatePt.toFixed(1);
  if (pickSort.value === 'tier') return tierLabel(getSongTierInfo(song));
  if (pickSort.value === 'gain') return song.oldScore > 0 ? `+${song.scoreIncrease}` : t('report.newPlay');
  return song.newBeatPt.toFixed(1);
};

/**
 * 【関数の役割】 選び方を切り替える。自由選択へ初めて入るときは、直前に表示していた上位 10 曲を
 * 選択済みにしておく（空の画像から始めさせない。「上位から 2〜3 曲だけ入れ替える」使い方もしやすい）。
 */
const selectShareMode = (mode: ShareMode) => {
  if (mode === 'custom' && customKeys.value.length === 0) {
    customKeys.value = shareSongs.value.map(songKey);
    // 歴代ベストのみは BEAT-PT を出しているので、そこから移ったときも BEAT-PT 列のままにする。
    if (shareMode.value !== 'custom') customColumn.value = shareMode.value === 'allTime' ? 'beat' : shareMode.value;
  }
  shareMode.value = mode;
};

const shareImageProps = computed(() => ({
  diffData: props.diffData as UploadDiffResult,
  songs: shareSongs.value,
  column: shareColumn.value,
  listLabel: t(SHARE_MODE_LABEL_KEY[shareMode.value]),
  picked: shareMode.value === 'custom',
  showRateTier: showRateTier.value,
  ownerName: canShowOwner.value && showDjName.value ? user.value?.displayName ?? null : null,
  dateLabel: dateLabel.value,
  versionLabel: versionLabel.value,
  isSupporter: ownIconProps.value.isSupporter,
  beatFrame: ownIconProps.value.beatFrame,
  rateFrame: ownIconProps.value.rateFrame,
}));

const measurePreview = async () => {
  await nextTick();
  const el = previewImage.value?.el;
  if (el?.offsetHeight) previewHeight.value = el.offsetHeight;
};
// 曲数や右端の列が変わると画像の高さも変わるので、画像の内容が変わるたびに測り直す。
watch([isShareOptionsOpen, shareImageProps], () => { if (isShareOptionsOpen.value) measurePreview(); });

const SHARE_FILE_NAME = 'beat-seeker-report.png';
/** 投稿本文。Web Share の text と intent URL の text で共通。 */
const shareText = computed(() => `${t('report.shareText')}\nhttps://beat-seeker.com \n#BeatSeeker`);
/** X の投稿画面 URL（本文プリセット）。 */
const intentUrl = computed(() => xIntentUrl(shareText.value));
/**
 * 共有シート（Web Share）経由で X アプリへ画像を渡す端末か。スマホ / タブレットだけ true。
 * PC は canShare が true でも X が並ばない OS の共有ダイアログが開くだけなので使わない。
 */
const usesShareSheet = canShareImageNatively();

/**
 * 先回りで生成した共有画像（PNG）。オプション画面を開いている間に作っておき、ポストボタンの click では
 * 共有処理だけを行う。html2canvas はスマホだと数秒かかり、その後に navigator.share / window.open を
 * 呼ぶとユーザー操作の有効期間（transient activation）が切れてブロックされるため。
 */
const shareBlob = ref<Blob | null>(null);
const isGeneratingShare = ref(false);
const shareGenError = ref(false);
/** 生成の世代番号。オプション変更で進めて、古い生成結果は捨てる。 */
let shareGenSeq = 0;
let shareGenTimer: ReturnType<typeof setTimeout> | null = null;
/** 共有後の案内パネル（Web Share を使わなかったとき）。画像をどう渡したかで文言を変える。 */
const shareFallback = ref<'copied' | 'downloaded' | null>(null);

/** 【関数の役割】 オフスクリーンの UploadReportShareImage を html2canvas で PNG 化して shareBlob に入れる。 */
const generateShareImage = async () => {
  const seq = ++shareGenSeq;
  shareBlob.value = null;
  shareGenError.value = false;
  isGeneratingShare.value = true;
  try {
    await nextTick();
    const target = captureImage.value?.el;
    if (!target) return;
    const canvas = await withHtml2canvasTextFix(() => html2canvas(target, {
      // X は長辺 2048px に縮小するので、1080×1440 の 1.5 倍（1620×2160）で頭打ち。2 倍は容量が増えるだけ。
      scale: SHARE_SCALE,
      backgroundColor: '#0a0f1d',
      logging: false,
      windowWidth: SHARE_WIDTH,
      windowHeight: target.offsetHeight,
      scrollX: 0,
      scrollY: 0,
      // 背後のダッシュボードや更新曲リストまで複製させない（複製だけで数秒かかる）。
      ignoreElements: ignoreOutside(target),
    }));
    if (seq !== shareGenSeq) return; // 生成中にオプションが変わった → この結果は捨てる
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'));
    if (seq !== shareGenSeq) return;
    if (!blob) throw new Error('Blob is null');
    shareBlob.value = blob;
  } catch (error) {
    console.error('Share image generation failed:', error);
    if (seq === shareGenSeq) shareGenError.value = true;
  } finally {
    if (seq === shareGenSeq) isGeneratingShare.value = false;
  }
};

/** 【関数の役割】 生成済み画像を無効化し、少し待ってから作り直す（連続のオプション変更で何度も走らせない）。 */
const scheduleShareImage = () => {
  // 自由選択で 1 曲も選んでいない間は作らない（選ばれた時点で shareImageProps が変わって作り直される）。
  if (!hasShareSongs.value) { discardShareImage(); return; }
  shareGenSeq++;
  shareBlob.value = null;
  shareGenError.value = false;
  isGeneratingShare.value = true;
  if (shareGenTimer) clearTimeout(shareGenTimer);
  shareGenTimer = setTimeout(() => { shareGenTimer = null; generateShareImage(); }, 300);
};

/** 【関数の役割】 生成済み画像を破棄する（モーダルを閉じたとき等）。進行中の生成結果も捨てる。 */
const discardShareImage = () => {
  shareGenSeq++;
  if (shareGenTimer) { clearTimeout(shareGenTimer); shareGenTimer = null; }
  shareBlob.value = null;
  shareGenError.value = false;
  isGeneratingShare.value = false;
};

// 画像の内容（並び順・DJ NAME・差分データ等）が変わったら作り直す。オプション画面を閉じている間は開いたときに作る。
watch(shareImageProps, () => { if (isShareOptionsOpen.value) scheduleShareImage(); else discardShareImage(); });
watch(isShareOptionsOpen, (open) => { if (open && !shareBlob.value) scheduleShareImage(); });

/**
 * 【関数の役割】 シェアオプションを開く。一覧を RATE-PT 順・単曲ティア順で見ていたら画像もそれに合わせ、
 * 「★ 歴代ベスト更新」で絞り込んで見ていたら画像も歴代ベストのみにする。
 * 自由選択で曲を選んである間は、開き直してもその選択を保つ。
 */
const openShareOptions = () => {
  if (!(shareMode.value === 'custom' && customKeys.value.length > 0)) {
    shareMode.value = listFilter.value === 'allTimeBest' && allTimeBestSongs.value.length > 0 ? 'allTime'
      : listSort.value === 'rate' && showRateTier.value ? 'rate'
      : listSort.value === 'tier' ? 'tier'
      : 'beat';
  }
  isShareOptionsOpen.value = true;
};

/**
 * 【関数の役割】 「画像を保存」ボタン。iPhone / iPad は共有シートの「画像を保存」で写真アプリに入れられるので
 * そちらを開く（ダウンロードだと「ファイル」アプリ行きになる）。それ以外はファイルとしてダウンロード。
 */
const saveShareImage = async () => {
  const blob = shareBlob.value;
  if (!blob) return;
  const file = new File([blob], SHARE_FILE_NAME, { type: 'image/png' });
  if (isIosDevice() && canShareImageNatively(file)) {
    try {
      await navigator.share({ files: [file] });
      return;
    } catch (e) {
      if ((e as Error).name === 'AbortError') return;
      console.error('Web Share failed, downloading instead:', e);
    }
  }
  downloadBlob(blob, SHARE_FILE_NAME);
};

/**
 * 【関数の役割】 PC ブラウザ（と Web Share が使えないスマホ）向け。画像をクリップボードへ（不可ならダウンロード）
 * 入れてから X の投稿画面を開く。画像は生成済みなので click からの経過は短く、window.open はポップアップ扱いされない。
 * 万一ブロックされても案内パネルのリンクから開ける。
 */
const postViaIntent = async (blob: Blob) => {
  // 先にクリップボードへ。window.open で別タブにフォーカスが移ると書き込めなくなるため順番を守る。
  const copied = await copyImageToClipboard(blob);
  if (!copied) downloadBlob(blob, SHARE_FILE_NAME);
  isShareOptionsOpen.value = false;
  shareFallback.value = copied ? 'copied' : 'downloaded';
  window.open(intentUrl.value, '_blank');
};

/**
 * 【関数の役割】 ポストボタン。生成済みの画像を端末に合った経路で送り出す。
 *  - スマホ / タブレット: Web Share API。OS の共有シートから X アプリを選ぶと画像 + 定型文入りの投稿画面が開く
 *  - PC: クリップボードコピー（不可ならダウンロード）+ ブラウザで定型文入りの投稿画面を開く
 *    （X の投稿画面 URL には画像を添付する手段が無いので、貼り付けだけはユーザーにやってもらう）
 * 生成失敗後に押されたら作り直すだけ（共有はもう一度押してもらう）。
 */
const confirmShare = async () => {
  const blob = shareBlob.value;
  if (!blob) {
    if (!isGeneratingShare.value) scheduleShareImage();
    return;
  }
  if (isSharing.value) return;
  isSharing.value = true;
  try {
    const file = new File([blob], SHARE_FILE_NAME, { type: 'image/png' });
    if (canShareImageNatively(file)) {
      try {
        // title は渡さない（共有先によっては本文の前に件名として差し込まれ、定型文が崩れる）。
        await navigator.share({ text: shareText.value, files: [file] });
        isShareOptionsOpen.value = false;
        return;
      } catch (e) {
        // ユーザーが共有シートを閉じただけならオプション画面に戻す。
        if ((e as Error).name === 'AbortError') return;
        console.error('Web Share failed, falling back to intent URL:', e);
      }
    }
    await postViaIntent(blob);
  } finally {
    isSharing.value = false;
  }
};

// モーダルが開いた（＝アップロード完了）タイミングでロードマップの進捗を取り込み、一覧の状態を初期化する。
// （リーグの進捗は diffData.league のスナップショットを表示するだけなので、ここでは取りに行かない）
// immediate で setup 中に走るので、ここで触る ref がすべて宣言済みになる位置（末尾側）に置くこと。
watch(() => props.isOpen, (open) => {
  if (!open || !props.diffData) return;
  listSort.value = 'beat';
  listFilter.value = 'all';
  visibleCount.value = PAGE_SIZE;
  expandedKey.value = null;
  isShareOptionsOpen.value = false;
  shareFallback.value = null;
  // 共有画像の選び方も初期化する（別のレポートの選択を持ち越さない）。
  shareMode.value = 'beat';
  customKeys.value = [];
  customColumn.value = 'beat';
  pickQuery.value = '';
  pickSort.value = 'beat';
  discardShareImage();
  loadRoadmapProgress();
}, { immediate: true });

/** 難易度名 → 行の左端に置く縦バーの色（BEG/NOR/HYP/ANO/LEG の IIDX 慣用色）。 */
const getDifficultyBarClass = (difficulty: string) => {
  switch (difficulty.toLowerCase()) {
    case 'beginner': return 'bg-emerald-500';
    case 'normal': return 'bg-blue-500';
    case 'hyper': return 'bg-amber-500';
    case 'another': return 'bg-red-500';
    case 'leggendaria': return 'bg-purple-500';
    default: return 'bg-slate-400';
  }
};

/** 難易度名 → 補足行の難易度テキストの色。 */
const getDifficultyTextClass = (difficulty: string) => {
  switch (difficulty.toLowerCase()) {
    case 'beginner': return 'text-emerald-600 dark:text-emerald-400';
    case 'normal': return 'text-blue-600 dark:text-blue-400';
    case 'hyper': return 'text-amber-600 dark:text-amber-400';
    case 'another': return 'text-red-600 dark:text-red-400';
    case 'leggendaria': return 'text-purple-600 dark:text-purple-400';
    default: return 'text-slate-500 dark:text-slate-400';
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

/* 自由選択の候補リスト。初回取り込みでは数百行になるので、オプション画面全体ではなくここだけをスクロールさせる。 */
.pick-list {
  max-height: 15rem;
  overflow-y: auto;
  overscroll-behavior: contain;
}
</style>
