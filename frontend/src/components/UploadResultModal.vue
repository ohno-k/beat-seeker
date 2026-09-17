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

            <!-- リーグモードの進捗（開催中の週に参加していれば表示。今回反映後の順位・有効曲・見込みPT） -->
            <div v-if="leagueProgress" class="card p-3">
              <div class="flex items-center gap-2 flex-wrap">
                <DivisionIcon :tier="leagueProgress.tier" :size="24" class="shrink-0" />
                <p class="text-sm font-bold text-slate-800 dark:text-slate-200">{{ t('report.league.title') }}</p>
                <span class="badge">{{ divisionName(leagueProgress.tier) }} / {{ t('league.groupN', { n: leagueProgress.groupIndex + 1 }) }}</span>
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
                          <span v-if="song.maxScore > 0" class="sm:hidden tabular-nums" :class="gradeColorClass(song)">{{ getScoreGradeInfo(song.newScore, song.maxScore).nearest }}</span>
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
                          <p class="text-xs font-bold leading-tight" :class="gradeColorClass(song)">{{ getScoreGradeInfo(song.newScore, song.maxScore).grade }}</p>
                          <p class="text-[10px] font-bold text-slate-400 leading-tight">{{ getScoreGradeInfo(song.newScore, song.maxScore).fromMax }}</p>
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
          </div>
        </div>

        <!-- フッター: X シェアボタン + 閉じるボタン -->
        <div id="modal-footer" class="px-4 py-3 border-t border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 shrink-0 flex gap-2">
          <button @click="openShareOptions" :disabled="isSharing" class="flex-1 min-w-0 py-3 bg-black hover:bg-slate-800 text-white font-bold rounded-md transition-colors flex items-center justify-center gap-2 disabled:opacity-50 text-xs sm:text-sm">
            <template v-if="!isSharing">
              <svg class="w-4 h-4 fill-current" viewBox="0 0 24 24">
                <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 22.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.005 4.09H5.078z"/>
              </svg>
              {{ t('report.shareX') }}
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
                <button
                  @click="shareSortMode = 'beat'"
                  class="w-full flex items-center gap-3 px-4 py-3 rounded-md border-2 transition-all text-left"
                  :class="shareSortMode === 'beat' ? 'border-blue-600 bg-blue-50 dark:bg-blue-900/30' : 'border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700/50'"
                >
                  <div class="w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0" :class="shareSortMode === 'beat' ? 'border-blue-600' : 'border-slate-300 dark:border-slate-600'">
                    <div v-if="shareSortMode === 'beat'" class="w-2.5 h-2.5 rounded-full bg-blue-600"></div>
                  </div>
                  <div>
                    <p class="font-bold text-sm" :class="shareSortMode === 'beat' ? 'text-blue-700 dark:text-blue-300' : 'text-slate-700 dark:text-slate-200'">{{ t('report.sortByBeatPt') }}</p>
                    <p class="text-[11px] text-slate-500 dark:text-slate-400">{{ t('report.sortByBeatPtDesc') }}</p>
                  </div>
                </button>
                <button
                  v-if="showRateTier"
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
                <label v-if="canShowOwner" class="flex items-center gap-3 px-4 py-3 rounded-md border border-slate-200 dark:border-slate-700 cursor-pointer select-none">
                  <input type="checkbox" v-model="showDjName" class="w-4 h-4 accent-blue-700" />
                  <span class="text-sm font-bold text-slate-700 dark:text-slate-200">{{ t('report.showDjName') }}</span>
                </label>
              </div>
            </div>
            <div class="px-4 py-3 border-t border-slate-100 dark:border-slate-700 flex gap-2 shrink-0">
              <button @click="isShareOptionsOpen = false" class="flex-1 py-2.5 bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 font-bold rounded-md text-sm transition-colors">
                {{ t('common.cancel') }}
              </button>
              <!-- 画像は先回りで生成しておき、click 時は共有だけ行う（ユーザー操作の有効期間内に navigator.share / window.open を呼ぶため） -->
              <button @click="confirmShare" :disabled="isGeneratingShare || isSharing" class="flex-1 py-2.5 bg-black hover:bg-slate-800 text-white font-bold rounded-md text-sm transition-colors flex items-center justify-center gap-1.5 disabled:opacity-60">
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
          Web Share が使えない環境（PC ブラウザ等）向けの案内。画像はコピー済み / ダウンロード済みで、
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
              <button @click="downloadShareImage" class="flex-1 py-2.5 bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 font-bold rounded-md text-sm transition-colors">
                {{ t('report.saveImage') }}
              </button>
              <a :href="xIntentUrl" target="_blank" rel="noopener" class="flex-1 py-2.5 bg-black hover:bg-slate-800 text-white font-bold rounded-md text-sm transition-colors flex items-center justify-center gap-1.5">
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
 *  - Web Share API でファイル共有（共有シートから X アプリを選ぶと画像付きの投稿画面が開く）、
 *    不可ならクリップボードコピー or ダウンロード + X の投稿画面（x.com/intent/post）を開く
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
  formatStatValue,
  getNumericRank,
  getScoreGradeInfo,
  getSongTierTransition,
  isNewAaa,
  pickStatTiles,
  tierLabel,
} from '../utils/uploadReport';
import type { StatKey } from '../utils/uploadReport';
import RankIcon from './RankIcon.vue';
import DivisionIcon from './DivisionIcon.vue';
import UploadReportShareImage from './UploadReportShareImage.vue';
import { useAuth, API_BASE } from '../composables/useAuth';
import { useLeague } from '../composables/useLeague';
import { useRateTierVisibility } from '../composables/useRateTierVisibility';
import { useI18n } from '../composables/useI18n';
import { CURRENT_VERSION, versionName } from '../utils/iidxVersions';
import { ignoreOutside, withHtml2canvasTextFix } from '../utils/html2canvasHelpers';
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
const league = useLeague();

/** リーグ課題曲 1 曲分の内訳（自己ベストとラインの比較・今回の更新有無）。 */
interface LeagueSongProgress {
  slot: number;
  title: string;
  difficultyName: string;
  level: number | null;
  /** リザルトが有効か（週内プレー + ライン超え）。 */
  valid: boolean;
  /** 現在の自己ベスト EX とそのスコアレート(%)。未プレーは null。 */
  bestEx: number | null;
  rate: number | null;
  /** グループ共通のライン（週開始時点の最高 EX）とそのレート(%)。誰も未プレーなら null。 */
  lineEx: number | null;
  lineRate: number | null;
  /** 今回のアップロードでこの譜面が更新されたか。 */
  updated: boolean;
  /** 今回の EX 増加量（updated のときのみ 1 以上）。 */
  scoreIncrease: number;
  /** 今回のアップロードでラインを超えて有効化されたか。 */
  justActivated: boolean;
  /** ライン超えに必要な残り EX。達成済み・ライン未設定なら null。 */
  toLine: number | null;
}

/** リーグモードの進捗（開催中の週に参加していれば、今回反映後の順位を保持。無ければ null）。 */
const leagueProgress = ref<null | {
  tier: number;
  groupIndex: number;
  rank: number;
  groupSize: number;
  validSongs: number;
  /** 集計対象の課題曲数（管理者が無効化した曲を除いた数。通常は 3）。 */
  songCount: number;
  resultValue: number | null;
  projectedPoints: number;
  zone: 'promote' | 'stay' | 'relegate';
  songs: LeagueSongProgress[];
}>(null);

/** DIVISION 表示名（0=LEGEND）。 */
const divisionName = (tier: number) => (tier === 0 ? 'DIVISION LEGEND' : `DIVISION ${tier}`);

/**
 * 【関数の役割】 開催中のリーグ週に自分が参加していれば、今回のアップロード反映後の
 * 自分の順位・有効曲・見込み PT を取り込む。未参加・未開催・取得失敗時は何も表示しない。
 */
const loadLeagueProgress = async () => {
  leagueProgress.value = null;
  const uid = user.value?.id;
  if (!uid) return;
  try {
    const cur = await league.fetchCurrent('score');
    // 開催中(active)の週に「メンバーとして」参加していることが条件（途中参加者は翌週から）。
    if (!cur.member || cur.week?.status !== 'active' || !cur.standings) return;
    const myRow = cur.standings.find((r) => r.userId === uid);
    if (!myRow) return;
    // 今回のアップロードで更新された譜面を (曲名|難易度) で引けるようにする。
    const updatedByChart = new Map<string, UpdatedSong>();
    for (const u of props.diffData?.updatedSongs ?? []) {
      updatedByChart.set(`${u.title}|${u.difficulty}`, u);
    }

    // 管理者が無効化した課題曲（解禁不可能な選曲など）は集計対象外なので、この報告からも外す。
    const allSongs = cur.songs || [];
    const scoredSongs = allSongs.filter((s) => !s.disabled);

    leagueProgress.value = {
      tier: cur.member.tier,
      groupIndex: cur.member.groupIndex,
      rank: myRow.rank,
      groupSize: cur.standings.length,
      validSongs: myRow.validSongs,
      songCount: allSongs.length ? scoredSongs.length : 3,
      resultValue: myRow.resultValue,
      projectedPoints: myRow.projectedPoints ?? 0,
      zone: myRow.zone,
      songs: scoredSongs.map((s) => {
        const ps = myRow.perSong?.find((p) => p.slot === s.slot);
        const up = updatedByChart.get(`${s.title}|${s.difficultyName}`);
        const valid = !!ps?.valid;
        const bestEx = ps?.bestEx ?? null;
        const lineEx = ps?.lineEx ?? s.lineEx ?? null;
        // レートはノーツ数（MAX = notes * 2）から出す。自己ベスト側はサーバ計算値をそのまま使う。
        const lineRate = lineEx != null && s.notes > 0 ? (lineEx / (s.notes * 2)) * 100 : null;
        return {
          slot: s.slot,
          title: s.title,
          difficultyName: s.difficultyName,
          level: s.level ?? null,
          valid,
          bestEx,
          rate: ps?.rate ?? null,
          lineEx,
          lineRate,
          updated: !!up,
          scoreIncrease: up?.scoreIncrease ?? 0,
          // 今回の更新で初めてラインを超えた曲（＝このアップロードで有効化された曲）。
          justActivated: !!up && valid && (lineEx == null || up.oldScore <= lineEx),
          // ライン超えに必要な残り EX（ラインちょうどでは無効なので +1 必要）。
          toLine: !valid && lineEx != null ? lineEx - (bestEx ?? 0) + 1 : null,
        };
      }),
    };
  } catch {
    /* 未参加・未開催・取得失敗時は表示しない */
  }
};

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

type ListSort = 'beat' | 'rate' | 'gain' | 'level';
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
  opts.push({ value: 'gain', label: t('report.sort.gain') }, { value: 'level', label: t('report.sort.level') });
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

const levelOf = (s: UpdatedSong) => Number(getNumericRank(s.informalRank) ?? 0);

const filteredSongs = computed(() => {
  if (!props.diffData) return [];
  const songs = props.diffData.updatedSongs.filter(FILTERS[listFilter.value]);
  switch (listSort.value) {
    case 'rate': return songs.sort((a, b) => b.newRatePt - a.newRatePt);
    case 'gain': return songs.sort((a, b) => b.scoreIncrease - a.scoreIncrease || b.newBeatPt - a.newBeatPt);
    case 'level': return songs.sort((a, b) => levelOf(b) - levelOf(a) || b.newBeatPt - a.newBeatPt);
    default: return songs.sort((a, b) => b.newBeatPt - a.newBeatPt);
  }
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
/** シェア画像の楽曲ソート軸。'beat' なら Beat-PT、'rate' なら Rate-PT で降順。 */
const shareSortMode = ref<'beat' | 'rate'>('beat');
/** 画像に DJ NAME を入れるか（端末ごとに記憶）。 */
const showDjName = ref(localStorage.getItem(SHOW_NAME_KEY) !== '0');
watch(showDjName, (v) => localStorage.setItem(SHOW_NAME_KEY, v ? '1' : '0'));

/** 自分のレポートで、かつ表示名があるときだけ DJ NAME の選択肢を出す。 */
const canShowOwner = computed(() => !props.hideOwner && !!user.value?.displayName);

/** 取り込み日（JST）を "2026.09.17" 形式で。バックエンドがタイムゾーン無しで返す日時は UTC として読む。 */
const dateLabel = computed(() => {
  const raw = props.reportDate;
  const d = raw ? new Date(/(Z|[+-]\d{2}:?\d{2})$/.test(raw) ? raw : `${raw}Z`) : new Date();
  const parts = new Intl.DateTimeFormat('ja-JP', { timeZone: 'Asia/Tokyo', year: 'numeric', month: '2-digit', day: '2-digit' })
    .formatToParts(Number.isNaN(d.getTime()) ? new Date() : d);
  const pick = (type: string) => parts.find(p => p.type === type)?.value ?? '';
  return `${pick('year')}.${pick('month')}.${pick('day')}`;
});

const versionLabel = computed(() => {
  const v = props.reportVersion ?? CURRENT_VERSION;
  return `IIDX ${v} ${versionName(v)}`;
});

const shareImageProps = computed(() => ({
  diffData: props.diffData as UploadDiffResult,
  sortMode: shareSortMode.value,
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
watch([isShareOptionsOpen, shareSortMode, showDjName], () => { if (isShareOptionsOpen.value) measurePreview(); });

/** 投稿本文。Web Share の text と intent URL の text で共通。 */
const shareText = computed(() => `${t('report.shareText')}\nhttps://beat-seeker.com \n#BeatSeeker`);
/**
 * X の投稿画面 URL（本文プリセット）。twitter.com/intent/tweet の現行版。
 * スマホでは X アプリが入っていれば x.com のリンクはアプリ側（投稿画面）で開く。
 */
const xIntentUrl = computed(() => `https://x.com/intent/post?text=${encodeURIComponent(shareText.value)}`);

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

/** 【関数の役割】 シェアオプションを開く。一覧を RATE-PT 順で見ていたら画像もそれに合わせる。 */
const openShareOptions = () => {
  shareSortMode.value = listSort.value === 'rate' && showRateTier.value ? 'rate' : 'beat';
  isShareOptionsOpen.value = true;
};

/** 【関数の役割】 PNG をファイルとして保存させる。 */
const downloadBlob = (blob: Blob) => {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'beat-seeker-report.png';
  a.click();
  // 即時に revoke するとブラウザによってはダウンロードが始まらないので少し待つ。
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};

const downloadShareImage = () => { if (shareBlob.value) downloadBlob(shareBlob.value); };

/**
 * 【関数の役割】 Web Share が使えない環境向け。画像をクリップボードへ（不可ならダウンロード）入れてから
 * X の投稿画面を開く。画像は生成済みなので click からの経過は短く、window.open はポップアップ扱いされない。
 * 万一ブロックされても案内パネルのリンクから開ける。
 */
const postViaIntent = async (blob: Blob) => {
  let copied = false;
  try {
    // 先にクリップボードへ。window.open で別タブにフォーカスが移ると書き込めなくなるため順番を守る。
    await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]);
    copied = true;
  } catch (e) {
    console.warn('Clipboard copy failed, downloading instead:', e);
    downloadBlob(blob);
  }
  isShareOptionsOpen.value = false;
  shareFallback.value = copied ? 'copied' : 'downloaded';
  window.open(xIntentUrl.value, '_blank');
};

/**
 * 【関数の役割】 ポストボタン。生成済みの画像を共有可能な経路で送り出す。
 * 優先順位:
 *  1. Web Share API（スマホ）: OS の共有シートから X アプリを選ぶと画像付きの投稿画面が開く
 *  2. クリップボードコピー（不可ならダウンロード）+ X の投稿画面を開く（PC ブラウザ等）
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
    const file = new File([blob], 'beat-seeker-report.png', { type: 'image/png' });
    if (navigator.share && navigator.canShare && navigator.canShare({ files: [file] })) {
      try {
        await navigator.share({ title: 'beat-seeker Report', text: shareText.value, files: [file] });
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

// モーダルが開いた（＝アップロード完了）タイミングでリーグ状況を取り込み、一覧の状態を初期化する。
// immediate で setup 中に走るので、ここで触る ref がすべて宣言済みになる位置（末尾側）に置くこと。
watch(() => props.isOpen, (open) => {
  if (!open || !props.diffData) return;
  listSort.value = 'beat';
  listFilter.value = 'all';
  visibleCount.value = PAGE_SIZE;
  expandedKey.value = null;
  isShareOptionsOpen.value = false;
  shareFallback.value = null;
  discardShareImage();
  loadLeagueProgress();
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
</style>
