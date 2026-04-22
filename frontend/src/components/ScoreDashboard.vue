<template>
  <div class="w-full space-y-6 animate-fade-in">
    <!-- Email Registration Prompt (未登録の場合のみ表示) -->
    <div v-if="user && !user.email" class="w-full bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-700/50 rounded-2xl p-4 flex flex-col sm:flex-row items-start sm:items-center gap-3">
      <div class="flex items-center gap-3 flex-1">
        <div class="w-9 h-9 bg-amber-100 dark:bg-amber-900/50 rounded-xl flex items-center justify-center shrink-0">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
          </svg>
        </div>
        <div>
          <p class="text-sm font-bold text-amber-800 dark:text-amber-300">{{ t('dashboard.emailNotRegistered') }}</p>
          <p class="text-xs text-amber-600 dark:text-amber-400">{{ t('dashboard.emailHint') }}</p>
        </div>
      </div>
      <button @click="$emit('open-profile-edit')" class="shrink-0 px-4 py-2 bg-amber-500 hover:bg-amber-400 text-white text-sm font-bold rounded-xl transition-colors shadow-sm">
        {{ t('dashboard.registerNow') }}
      </button>
    </div>

    <!-- Tier Cards Row -->
    <div class="grid grid-cols-1 gap-6" :class="{ 'sm:grid-cols-2': showRateTier }">
      <!-- Beat-Tier (Lv11/12) -->
      <div class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 flex flex-col items-center justify-center relative overflow-hidden transition-colors duration-200">
        <div class="absolute inset-0 bg-gradient-to-br from-blue-50/50 dark:from-blue-900/20 to-transparent pointer-events-none"></div>
        <div class="absolute top-4 right-4 z-20">
          <button
            @click="showInfoModal = true"
            class="group flex items-center gap-1.5 text-blue-500 dark:text-blue-400 hover:text-blue-700 dark:hover:text-blue-300 transition-all font-bold"
          >
            <span class="text-[10px] uppercase tracking-wider opacity-0 group-hover:opacity-100 transition-opacity">{{ t('dashboard.whatIsBeatTier') }}</span>
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          </button>
        </div>
        <p class="text-sm font-medium text-slate-500 dark:text-slate-400 mb-1 z-10 font-bold uppercase tracking-widest">Beat-Tier</p>
        <div class="flex flex-col items-center z-10 text-center">
          <RankIcon :rank-name="rankInfo.name" :tier="rankInfo.tier" size="lg" class="mb-2" :is-supporter="user?.isSupporter && user?.showSupporterBorder" />
          <h3 class="text-2xl sm:text-3xl font-black mb-1 line-clamp-1" :class="rankInfo.color">
            {{ rankInfo.name }} {{ rankInfo.tier || '' }}
          </h3>
          <p class="text-xs sm:text-sm font-bold text-slate-400 dark:text-slate-500">{{ totalPoints.toFixed(1) }} pt</p>
        </div>
        <!-- Progress Bar -->
        <div class="w-full mt-4 bg-slate-100 dark:bg-slate-700 h-1.5 rounded-full overflow-hidden z-10">
          <div
            class="h-full bg-blue-500 dark:bg-blue-400 rounded-full transition-all duration-1000"
            :style="{ width: `${nextRankInfo.progress}%` }"
          ></div>
        </div>
        <p v-if="nextRankInfo.nextRank" class="text-[10px] font-bold text-slate-400 dark:text-slate-500 mt-2 z-10 uppercase tracking-widest text-center">
          Next: {{ nextRankInfo.nextRank.name }} {{ nextRankInfo.nextRank.tier || '' }}<br/>
          {{ t('dashboard.remaining') }} ({{ nextRankInfo.nextRank.minPoints - totalPoints > 0 ? (nextRankInfo.nextRank.minPoints - totalPoints).toFixed(1) : 0 }} pt)
        </p>
        <!-- DJ Name pie (topRanker only) -->
        <div v-if="isTopRankerView && beatTierDjPie.length > 0" class="w-full mt-5 pt-4 border-t border-slate-100 dark:border-slate-700 z-10 flex flex-col sm:flex-row items-center gap-4">
          <svg viewBox="0 0 100 100" class="w-24 h-24 shrink-0" role="img" aria-label="DJNAME別 対象入り曲 内訳">
            <path v-for="(slice, i) in beatTierDjPie" :key="i" :d="slice.path" :fill="slice.color" stroke="white" stroke-width="0.5" />
          </svg>
          <ul class="flex-1 w-full grid grid-cols-2 sm:grid-cols-1 gap-x-3 gap-y-1 max-h-[28rem] overflow-y-auto text-[10px] sm:text-[11px]">
            <li v-for="(slice, i) in beatTierDjPie" :key="i" class="flex items-center gap-1.5 min-w-0">
              <span class="w-2.5 h-2.5 rounded-sm shrink-0" :style="{ backgroundColor: slice.color }"></span>
              <span class="truncate font-bold text-slate-700 dark:text-slate-200" :title="slice.name">{{ slice.name }}</span>
              <span class="ml-auto tabular-nums text-slate-500 dark:text-slate-400 shrink-0">{{ slice.count }}曲 ({{ slice.pct.toFixed(0) }}%)</span>
            </li>
          </ul>
        </div>
      </div>

      <!-- Rate-Tier (全難度 ANOTHER/LEGGENDARIA) -->
      <div v-if="showRateTier" class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 flex flex-col items-center justify-center relative overflow-hidden transition-colors duration-200">
        <div class="absolute inset-0 bg-gradient-to-br from-emerald-50/50 dark:from-emerald-900/20 to-transparent pointer-events-none"></div>
        <div class="absolute top-4 right-4 z-20">
          <button
            @click="showRateInfoModal = true"
            class="group flex items-center gap-1.5 text-emerald-500 dark:text-emerald-400 hover:text-emerald-700 dark:hover:text-emerald-300 transition-all font-bold"
          >
            <span class="text-[10px] uppercase tracking-wider opacity-0 group-hover:opacity-100 transition-opacity">{{ t('dashboard.whatIsRateTier') }}</span>
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          </button>
        </div>
        <p class="text-sm font-medium text-slate-500 dark:text-slate-400 mb-1 z-10 font-bold uppercase tracking-widest">Rate-Tier</p>
        <div class="flex flex-col items-center z-10 text-center">
          <RankIcon :rank-name="rateTierRankInfo.name" :tier="rateTierRankInfo.tier" size="lg" class="mb-2" :is-supporter="user?.isSupporter && user?.showSupporterBorder" />
          <h3 class="text-2xl sm:text-3xl font-black mb-1 line-clamp-1" :class="rateTierRankInfo.color">
            {{ rateTierRankInfo.name }} {{ rateTierRankInfo.tier || '' }}
          </h3>
          <p class="text-xs sm:text-sm font-bold text-slate-400 dark:text-slate-500">{{ rateTierPoints.toFixed(1) }} pt</p>
        </div>
        <!-- Progress Bar -->
        <div class="w-full mt-4 bg-slate-100 dark:bg-slate-700 h-1.5 rounded-full overflow-hidden z-10">
          <div
            class="h-full bg-emerald-500 dark:bg-emerald-400 rounded-full transition-all duration-1000"
            :style="{ width: `${rateTierNextRankInfo.progress}%` }"
          ></div>
        </div>
        <p v-if="rateTierNextRankInfo.nextRank" class="text-[10px] font-bold text-slate-400 dark:text-slate-500 mt-2 z-10 uppercase tracking-widest text-center">
          Next: {{ rateTierNextRankInfo.nextRank.name }} {{ rateTierNextRankInfo.nextRank.tier || '' }}<br/>
          {{ t('dashboard.remaining') }} ({{ rateTierNextRankInfo.nextRank.minPoints - rateTierPoints > 0 ? (rateTierNextRankInfo.nextRank.minPoints - rateTierPoints).toFixed(1) : 0 }} pt)
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

    <!-- Ranking + Lv12 Stats Row -->
    <div v-if="!isTopRankerView && !isPrivateView" class="bg-white dark:bg-slate-800 p-4 sm:p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 flex flex-col justify-between transition-colors duration-200">
        <!-- Ranking Position -->
        <div v-if="!isTopRankerView && !isPrivateView" class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 mb-5 pb-5 border-b border-slate-100 dark:border-slate-700">
          <div>
            <p class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">{{ t('dashboard.currentRank') }}</p>
            <div class="flex items-end gap-3">
              <span v-if="myRankingPosition" class="text-5xl font-black text-slate-800 dark:text-slate-100 tabular-nums leading-none"># {{ myRankingPosition.position }}</span>
              <span v-else class="text-3xl font-black text-slate-300 dark:text-slate-600 leading-none">-</span>
              <div v-if="myRankingPosition" class="flex flex-col pb-1 gap-0.5">
                <span class="text-sm font-bold text-slate-400">/ {{ myRankingPosition.total }} {{ t('dashboard.outOf') }}</span>
                <span v-if="myRankingPosition.rankChange === null" class="text-[11px] font-bold text-blue-500 uppercase">{{ t('dashboard.newEntry') }}</span>
                <span v-else-if="myRankingPosition.rankChange > 0" class="text-[11px] font-bold text-emerald-500">{{ t('dashboard.rankUp', { n: myRankingPosition.rankChange }) }}</span>
                <span v-else-if="myRankingPosition.rankChange < 0" class="text-[11px] font-bold text-red-500">{{ t('dashboard.rankDown', { n: Math.abs(myRankingPosition.rankChange) }) }}</span>
                <span v-else class="text-[11px] font-bold text-slate-400 dark:text-slate-500">{{ t('dashboard.rankNoChange') }}</span>
              </div>
            </div>
          </div>
          <div v-if="myRankingPosition" class="flex items-center gap-2 self-end sm:self-auto">
            <div class="text-right">
              <p class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-0.5">{{ t('dashboard.neighbors') }}</p>
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

        <!-- Lv12 Quick Stats (fixed, no settings needed) -->
        <div v-if="!isTopRankerView && !isPrivateView" class="grid grid-cols-2 md:grid-cols-4 gap-3 sm:gap-4 mt-auto">
          <div class="flex flex-col items-center justify-center p-3 rounded-xl bg-slate-50/50 dark:bg-slate-700/30 border border-slate-100 dark:border-slate-700">
            <p class="text-[9px] font-bold text-slate-400 uppercase tracking-widest mb-1">{{ t('dashboard.lv12Total') }}</p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-slate-700 dark:text-slate-200">{{ lv12Total }}</h3>
          </div>
          <div class="flex flex-col items-center justify-center p-3 rounded-xl bg-blue-50/30 dark:bg-blue-900/10 border border-blue-100/50 dark:border-blue-800/50 border-t-2 border-t-blue-400 dark:border-t-blue-500">
            <p class="text-[9px] font-bold text-blue-400 dark:text-blue-500 uppercase tracking-widest mb-1">{{ t('dashboard.lv12ClearRate') }}</p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-blue-600 dark:text-blue-400 flex items-baseline gap-0.5">
              {{ lv12ClearRate }}<span class="text-sm font-bold opacity-70">%</span>
            </h3>
          </div>
          <div class="flex flex-col items-center justify-center p-3 rounded-xl bg-amber-50/30 dark:bg-amber-900/10 border border-amber-100/50 dark:border-amber-800/50 border-t-2 border-t-amber-400 dark:border-t-amber-500">
            <p class="text-[9px] font-bold text-amber-500 uppercase tracking-widest mb-1">{{ t('dashboard.lv12AaaRate') }}</p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-amber-500 flex items-baseline gap-0.5">
              {{ lv12AaaRate }}<span class="text-sm font-bold opacity-70">%</span>
            </h3>
          </div>
          <div class="flex flex-col items-center justify-center p-3 rounded-xl bg-purple-50/30 dark:bg-purple-900/10 border border-purple-100/50 dark:border-purple-800/50 border-t-2 border-t-purple-400 dark:border-t-purple-500">
            <p class="text-[9px] font-bold text-purple-400 dark:text-purple-500 uppercase tracking-widest mb-1" :title="t('dashboard.maxMinusHint')">{{ t('dashboard.lv12MaxMinusRate') }}</p>
            <h3 class="text-2xl sm:text-3xl font-extrabold text-purple-600 dark:text-purple-400 flex items-baseline gap-0.5">
              {{ lv12MaxMinusRate }}<span class="text-sm font-bold opacity-70">%</span>
            </h3>
          </div>
        </div>
    </div>

    <!-- Private user notice: hide per-song breakdowns since scores are unavailable -->
    <div v-if="isPrivateView" class="bg-amber-50 dark:bg-amber-900/20 p-5 rounded-2xl border border-amber-200 dark:border-amber-800 text-center">
      <div class="flex items-center justify-center gap-2 text-amber-700 dark:text-amber-300 font-bold">
        <span>🔒</span>
        <span>{{ t('dashboard.privateUserNotice') }}</span>
      </div>
    </div>

    <!-- Unofficial Difficulty Table -->
    <UnofficialDifficultyTable v-if="!isPrivateView" :scores="allFlattenedScores" />

    <!-- Rank Up Advice -->
    <RankUpAdvice v-if="!isPrivateView" :flat-scores="allFlattenedScores" :total-points="props.totalPoints" />

    <!-- Activity Feed (全体ニュース) -->
    <ActivityFeed v-if="!isPrivateView" />

    <!-- Info Modal -->
    <BeatTierInfoModal v-if="showInfoModal" @close="showInfoModal = false" />
    <RateTierInfoModal v-if="showRateInfoModal" @close="showRateInfoModal = false" />
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
 *
 * @prop scores 階層化されたスコアデータ。
 * @prop totalPoints 現在の Beat-PT 合計。
 * @prop viewingIidxId 他人を閲覧中なら IIDX ID が入る（自分のダッシュボード表示時は undefined）。
 * @prop viewingMode 閲覧モード。UI の「編集ボタン」表示・API 呼出先・機能制限の分岐に使用。
 * @prop rateTierPointsOverride TOP ランカー等、サーバ算出済み Rate-PT が優先される場合に使用する値。
 * @emits open-profile-edit メール未登録等の促しバナーからプロフィール編集を要求。
 */
import { computed, ref, onMounted } from 'vue';
import { useI18n } from '../composables/useI18n';
import type { ScoreData } from '../types/ScoreData';
import { getRankInfo, getNextRankInfo, getRateTierRankInfo, getNextRateTierRankInfo, calculateScoreRateTierPoints } from '../utils/beatTier';
import BeatTierInfoModal from './BeatTierInfoModal.vue';
import RateTierInfoModal from './RateTierInfoModal.vue';
import RankIcon from './RankIcon.vue';
import UnofficialDifficultyTable from './UnofficialDifficultyTable.vue';
import RankUpAdvice from './RankUpAdvice.vue';
import ActivityFeed from './ActivityFeed.vue';
import { useAuth } from '../composables/useAuth';
import { flattenScores } from '../utils/scoreData';
import { useRateTierVisibility } from '../composables/useRateTierVisibility';

const { showRateTier } = useRateTierVisibility();
const { t } = useI18n();
const { user } = useAuth();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

const emit = defineEmits<{ (e: 'open-profile-edit'): void }>();

const props = defineProps<{
  scores: ScoreData[];
  totalPoints: number;
  viewingIidxId?: string;
  viewingDisplayName?: string;
  viewingMode?: 'admin' | 'friend' | 'public' | 'topRanker' | 'private' | null;
  rateTierPointsOverride?: number | null;
}>();

/** 【computed の役割】 他ユーザーを閲覧中かどうか（viewingIidxId が存在する）。 */
const isViewingOther = computed(() => !!props.viewingIidxId);
/** 【computed の役割】 都道府県 TOP ランカー（バーチャル）の閲覧中かどうか。 */
const isTopRankerView = computed(() => props.viewingMode === 'topRanker');
/** 【computed の役割】 プライベート設定ユーザーの閲覧中かどうか（詳細非表示モード）。 */
const isPrivateView = computed(() => props.viewingMode === 'private');

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
 * 【computed の役割】 TOP ランカー閲覧時の Beat-Tier DJ 名円グラフ。
 * beatTierPoints 降順 TOP100 譜面を DJ 名でグループ化し、件数比率の円グラフに変換する。
 */
const beatTierDjPie = computed(() => {
  if (!isTopRankerView.value) return [];
  const top100 = [...allFlattenedScores.value]
    .filter(s => s.beatTierPoints > 0)
    .sort((a, b) => b.beatTierPoints - a.beatTierPoints)
    .slice(0, 100);
  const counts = new Map<string, number>();
  for (const s of top100) {
    const name = (s.djName && s.djName.trim()) || '(不明)';
    counts.set(name, (counts.get(name) || 0) + 1);
  }
  const entries = Array.from(counts.entries())
    .map(([name, count]) => ({ name, count }))
    .sort((a, b) => b.count - a.count);
  return buildPieSlices(entries);
});

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

// ---- Beat-Tier 算出 ----
/** 【computed の役割】 現在の Beat-PT に対応するティア情報（名称、色、アイコン等）。 */
const rankInfo = computed(() => getRankInfo(props.totalPoints));
/** 【computed の役割】 次のティアまでの差分情報（必要 pt、ティア名）。 */
const nextRankInfo = computed(() => getNextRankInfo(props.totalPoints));

/** 【computed の役割】 階層化スコアをフラット配列に変換（曲 × 難易度 1 レコード）。 */
const allFlattenedScores = computed(() => flattenScores(props.scores));

/**
 * 【computed の役割】 Rate-Tier ポイント算出。
 *   - ANOTHER/LEGGENDARIA の 上位 100 曲分の Rate-PT を合算
 *   - 100% 達成曲が 100 件を超えた場合、超過分 1 件あたり +1 pt のオーバーフローボーナスを加算
 *     （バックエンド ScoreRecalculationService と一致する挙動）
 *   - rateTierPointsOverride（TOP ランカー等の既計算値）が与えられていればそれを優先
 */
const rateTierPoints = computed(() => {
  if (props.rateTierPointsOverride != null) return props.rateTierPointsOverride;
  const eligible = allFlattenedScores.value
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
});
/** 【computed の役割】 Rate-Tier の現在ティア情報。 */
const rateTierRankInfo = computed(() => getRateTierRankInfo(rateTierPoints.value));
/** 【computed の役割】 Rate-Tier の次ティア情報。 */
const rateTierNextRankInfo = computed(() => getNextRateTierRankInfo(rateTierPoints.value));

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
