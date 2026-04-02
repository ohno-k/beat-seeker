<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useScores } from '../composables/useScores';
import { useFriends } from '../composables/useFriends';
import { useI18n } from '../composables/useI18n';
import { flattenScores, type ScoreRecord } from '../utils/scoreData';
import type { ScoreData, DifficultyStats } from '../types/ScoreData';
import { calculateScoreRateTierPoints, SCORE_RATE_THRESHOLDS, calculatePoints } from '../utils/beatTier';

const { t } = useI18n();

type Mode = 'rival' | 'border' | 'beat-pt' | 'rate-pt';
type BorderTarget = 'aa' | 'aaa' | 'max-minus';

interface SongCard {
  key: string;
  title: string;
  difficultyName: string;
  difficultyColor: string;
  difficultyLevel: number | null;
  score: number;
  maxScore: number;
  scoreRate: number;
  djLevel: string;
  informalRank: string | undefined;
  beatTierPoints: number;
  maxBeatTierPoints: number;
  gap: number;
  gapLabel: string;
  subLabel: string;
  friendScore?: number;
}

const { fetchMyScores } = useScores();
const { fetchFriends, fetchFriendScores, friends } = useFriends();

const isLoading = ref(true);
const myScores = ref<ScoreRecord[]>([]);
const mode = ref<Mode | null>(null);
const showLv11 = ref(true);
const showLv12 = ref(true);
const selectedFriendId = ref<number | null>(null);
const friendScores = ref<ScoreRecord[]>([]);
const isFriendLoading = ref(false);
const rivalSortClosest = ref(true);
const borderTarget = ref<BorderTarget>('aa');
const beatPtTarget = ref<number | null>(null);
const ratePtTarget = ref<number | null>(null);

const AA_RATE = 77.77;
const AAA_RATE = 88.88;
const MAX_MINUS_RATE = 94.44;

const BEAT_BONUS_THRESHOLDS = [
  { rate: 77.78, label: 'AA' },
  { rate: 88.89, label: 'AAA' },
  { rate: 94.45, label: 'MAX-' },
  { rate: 100,   label: 'MAX' },
] as const;


// Only friends whose scores are public (privacyLevel !== 2)
const publicFriends = computed(() =>
  friends.value.filter(f => (f.privacyLevel ?? 0) !== 2)
);

function groupFriendScores(flatScores: any[]): ScoreData[] {
  const grouped = new Map<string, any>();
  const emptyDiff = (): DifficultyStats => ({
    difficulty: null, score: 0, pgreat: 0, great: 0, missCount: null,
    clearType: 'NO PLAY', djLevel: '---'
  });
  flatScores.forEach((s: any) => {
    if (!grouped.has(s.title)) {
      grouped.set(s.title, {
        version: '0', title: s.title, genre: s.genre || '', artist: s.artist || '',
        playCount: 0, lastPlayTime: '',
        beginner: emptyDiff(), normal: emptyDiff(), hyper: emptyDiff(),
        another: emptyDiff(), leggendaria: emptyDiff()
      });
    }
    const entry = grouped.get(s.title);
    const diffKey = s.difficultyName.toLowerCase();
    if (entry[diffKey]) {
      entry[diffKey] = {
        id: s.id, difficulty: s.difficultyLevel, score: s.score,
        pgreat: s.pgreat || 0, great: s.great || 0, missCount: s.missCount,
        clearType: s.clearType, djLevel: s.djLevel
      };
    }
  });
  return Array.from(grouped.values());
}

function borderScore(maxScore: number, threshold: number): number {
  return Math.floor(maxScore * threshold / 100) + 1;
}

// Binary search: find the scoreRate that gives exactly targetPt BEAT-PT for a given informalRank
function findScoreRateForBeatPt(targetPt: number, informalRank: string | undefined): number {
  if (!informalRank) return 100;
  let lo = 66.667, hi = 100;
  for (let i = 0; i < 60; i++) {
    const mid = (lo + hi) / 2;
    if (calculatePoints(mid, informalRank) < targetPt) lo = mid;
    else hi = mid;
  }
  return hi;
}

const beatPtAchievedCount = computed(() => {
  if (!beatPtTarget.value || beatPtTarget.value <= 0) return null;
  const eligible = myScores.value.filter(s => s.informalRank && s.maxScore > 0);
  const achieved = eligible.filter(s => s.beatTierPoints >= beatPtTarget.value!);
  return { achieved: achieved.length, total: eligible.length };
});

const ratePtAchievedCount = computed(() => {
  if (!ratePtTarget.value || ratePtTarget.value <= 0) return null;
  const eligible = myScores.value.filter(s =>
    (s.difficultyName === 'ANOTHER' || s.difficultyName === 'LEGGENDARIA') && s.maxScore > 0 && s.scoreRate > 0
  );
  const achieved = eligible.filter(s => calculateScoreRateTierPoints(s.scoreRate) >= ratePtTarget.value!);
  return { achieved: achieved.length, total: eligible.length };
});

onMounted(async () => {
  isLoading.value = true;
  try {
    const raw = await fetchMyScores();
    myScores.value = flattenScores(raw);
  } finally {
    isLoading.value = false;
  }
  fetchFriends();
});

watch(selectedFriendId, async (id) => {
  if (!id) { friendScores.value = []; return; }
  isFriendLoading.value = true;
  try {
    const raw = await fetchFriendScores(id);
    friendScores.value = flattenScores(groupFriendScores(raw));
  } finally {
    isFriendLoading.value = false;
  }
});

const levelFiltered = computed(() =>
  myScores.value.filter(s =>
    (s.difficultyLevel === 11 && showLv11.value) ||
    (s.difficultyLevel === 12 && showLv12.value)
  )
);

const suggestions = computed((): SongCard[] => {
  if (!mode.value) return [];
  const base = levelFiltered.value;

  if (mode.value === 'rival') {
    if (!selectedFriendId.value || friendScores.value.length === 0) return [];
    const friendMap = new Map(friendScores.value.map(s => [`${s.title}_${s.difficultyName}`, s]));
    const cards: SongCard[] = [];
    for (const s of base) {
      const f = friendMap.get(`${s.title}_${s.difficultyName}`);
      if (!f || f.score <= 0 || f.score <= s.score) continue;
      const gap = f.score - s.score;
      cards.push({
        key: `${s.title}_${s.difficultyName}`,
        title: s.title,
        difficultyName: s.difficultyName, difficultyColor: s.difficultyColor, difficultyLevel: s.difficultyLevel,
        score: s.score, maxScore: s.maxScore, scoreRate: s.scoreRate,
        djLevel: s.djLevel, informalRank: s.informalRank,
        beatTierPoints: s.beatTierPoints, maxBeatTierPoints: s.maxBeatTierPoints,
        gap,
        gapLabel: t('arcade.gapRival', { gap }),
        subLabel: t('arcade.subRival', { my: s.score.toLocaleString(), rival: f.score.toLocaleString() }),
        friendScore: f.score
      });
    }
    return cards.sort((a, b) => rivalSortClosest.value ? a.gap - b.gap : b.gap - a.gap).slice(0, 50);
  }

  if (mode.value === 'border') {
    const config = {
      'aa':        { minRate: 0,        maxRate: AA_RATE,       label: 'AA',   targetRate: AA_RATE },
      'aaa':       { minRate: AA_RATE,  maxRate: AAA_RATE,      label: 'AAA',  targetRate: AAA_RATE },
      'max-minus': { minRate: AAA_RATE, maxRate: MAX_MINUS_RATE, label: 'MAX-', targetRate: MAX_MINUS_RATE },
    }[borderTarget.value];

    return base
      .filter(s => s.maxScore > 0 && s.scoreRate > config.minRate && s.scoreRate < config.maxRate)
      .map(s => {
        const bScore = borderScore(s.maxScore, config.targetRate);
        const gap = bScore - s.score;
        return {
          key: `${s.title}_${s.difficultyName}`,
          title: s.title,
          difficultyName: s.difficultyName, difficultyColor: s.difficultyColor, difficultyLevel: s.difficultyLevel,
          score: s.score, maxScore: s.maxScore, scoreRate: s.scoreRate,
          djLevel: s.djLevel, informalRank: s.informalRank,
          beatTierPoints: s.beatTierPoints, maxBeatTierPoints: s.maxBeatTierPoints,
          gap: Math.max(0, gap),
          gapLabel: gap > 0
            ? t('arcade.gapBorder', { label: config.label, gap })
            : t('arcade.borderAchieved', { label: config.label }),
          subLabel: t('arcade.subBorder', { rate: s.scoreRate.toFixed(2), target: config.targetRate }),
        };
      })
      .filter(s => s.gap > 0)
      .sort((a, b) => a.gap - b.gap)
      .slice(0, 50);
  }

  if (mode.value === 'beat-pt') {
    const target = beatPtTarget.value;
    const cards: SongCard[] = [];
    for (const s of myScores.value) {
      if (!s.informalRank || s.maxScore <= 0) continue; // 達成不可能
      if (target && target > 0) {
        // 単曲目標モード
        if (s.beatTierPoints >= target) continue; // 達成済み
        if (s.maxBeatTierPoints < target - 0.005) continue; // 達成不可能
        const targetRate = findScoreRateForBeatPt(target, s.informalRank);
        const targetScore = Math.ceil(s.maxScore * targetRate / 100);
        const scoreGap = targetScore - s.score;
        if (scoreGap <= 0) continue;
        cards.push({
          key: `${s.title}_${s.difficultyName}`,
          title: s.title,
          difficultyName: s.difficultyName, difficultyColor: s.difficultyColor, difficultyLevel: s.difficultyLevel,
          score: s.score, maxScore: s.maxScore, scoreRate: s.scoreRate,
          djLevel: s.djLevel, informalRank: s.informalRank,
          beatTierPoints: s.beatTierPoints, maxBeatTierPoints: s.maxBeatTierPoints,
          gap: scoreGap,
          gapLabel: t('arcade.gapPtNext', { gap: scoreGap.toLocaleString() }),
          subLabel: t('arcade.subBeatPtTarget', { current: s.beatTierPoints.toFixed(2), target: target.toFixed(2) }),
        });
      } else {
        // 目標未入力: 次のボーナス閾値まで表示
        if (s.beatTierPoints >= s.maxBeatTierPoints * 0.999) continue; // 達成済み
        const nextTh = BEAT_BONUS_THRESHOLDS.find(th => s.scoreRate < th.rate);
        if (!nextTh) continue;
        const targetScore = nextTh.rate >= 100 ? s.maxScore : Math.ceil(s.maxScore * nextTh.rate / 100);
        const scoreGap = targetScore - s.score;
        if (scoreGap <= 0) continue;
        const newRate = (targetScore / s.maxScore) * 100;
        const ptGain = Math.max(0, calculatePoints(newRate, s.informalRank) - s.beatTierPoints);
        if (ptGain < 0.005) continue;
        cards.push({
          key: `${s.title}_${s.difficultyName}`,
          title: s.title,
          difficultyName: s.difficultyName, difficultyColor: s.difficultyColor, difficultyLevel: s.difficultyLevel,
          score: s.score, maxScore: s.maxScore, scoreRate: s.scoreRate,
          djLevel: s.djLevel, informalRank: s.informalRank,
          beatTierPoints: s.beatTierPoints, maxBeatTierPoints: s.maxBeatTierPoints,
          gap: scoreGap,
          gapLabel: t('arcade.gapPtNext', { gap: scoreGap.toLocaleString() }),
          subLabel: t('arcade.subBeatPtNext', { label: nextTh.label, gain: ptGain.toFixed(2) }),
        });
      }
    }
    return cards.sort((a, b) => a.gap - b.gap).slice(0, 50);
  }

  if (mode.value === 'rate-pt') {
    const target = ratePtTarget.value;
    const cards: SongCard[] = [];
    for (const s of myScores.value) {
      if (s.difficultyName !== 'ANOTHER' && s.difficultyName !== 'LEGGENDARIA') continue;
      if (s.maxScore <= 0 || s.scoreRate <= 0) continue; // 達成不可能
      const currentPt = calculateScoreRateTierPoints(s.scoreRate);
      if (target && target > 0) {
        // 単曲目標モード
        if (currentPt >= target) continue; // 達成済み
        const targetTh = SCORE_RATE_THRESHOLDS.find(th => th.points >= target);
        if (!targetTh) continue; // 達成不可能 (target > 512)
        const targetScore = Math.ceil(s.maxScore * targetTh.rate / 100);
        const scoreGap = targetScore - s.score;
        if (scoreGap <= 0) continue;
        cards.push({
          key: `${s.title}_${s.difficultyName}`,
          title: s.title,
          difficultyName: s.difficultyName, difficultyColor: s.difficultyColor, difficultyLevel: s.difficultyLevel,
          score: s.score, maxScore: s.maxScore, scoreRate: s.scoreRate,
          djLevel: s.djLevel, informalRank: s.informalRank,
          beatTierPoints: s.beatTierPoints, maxBeatTierPoints: s.maxBeatTierPoints,
          gap: scoreGap,
          gapLabel: t('arcade.gapPtNext', { gap: scoreGap.toLocaleString() }),
          subLabel: t('arcade.subRatePtTarget', { current: currentPt.toFixed(1), target }),
        });
      } else {
        // 目標未入力: 次の閾値まで表示
        const nextTh = SCORE_RATE_THRESHOLDS.find(th => th.rate > s.scoreRate);
        if (!nextTh) continue; // 達成済み
        const targetScore = Math.ceil(s.maxScore * nextTh.rate / 100);
        const scoreGap = targetScore - s.score;
        if (scoreGap <= 0) continue;
        const ptGain = nextTh.points - currentPt;
        cards.push({
          key: `${s.title}_${s.difficultyName}`,
          title: s.title,
          difficultyName: s.difficultyName, difficultyColor: s.difficultyColor, difficultyLevel: s.difficultyLevel,
          score: s.score, maxScore: s.maxScore, scoreRate: s.scoreRate,
          djLevel: s.djLevel, informalRank: s.informalRank,
          beatTierPoints: s.beatTierPoints, maxBeatTierPoints: s.maxBeatTierPoints,
          gap: scoreGap,
          gapLabel: t('arcade.gapPtNext', { gap: scoreGap.toLocaleString() }),
          subLabel: t('arcade.subRatePtNext', { rate: nextTh.rate, gain: ptGain.toFixed(1) }),
        });
      }
    }
    return cards.sort((a, b) => a.gap - b.gap).slice(0, 50);
  }

  return [];
});

const modeLabel = computed((): string => {
  if (!mode.value) return '';
  return {
    'rival':    t('arcade.modeLabelRival'),
    'border':   t('arcade.modeLabelBorder'),
    'beat-pt':  t('arcade.modeLabelBeatPt'),
    'rate-pt':  t('arcade.modeLabelRatePt'),
  }[mode.value] ?? '';
});

const modeSortNote = computed((): string => {
  if (!mode.value) return '';
  if (mode.value === 'rival') return rivalSortClosest.value ? t('arcade.sortNoteRivalClosest') : t('arcade.sortNoteRivalWidest');
  return {
    'border':  t('arcade.sortNoteBorder'),
    'beat-pt': t('arcade.sortNoteBeatPt'),
    'rate-pt': t('arcade.sortNoteRatePt'),
  }[mode.value] ?? '';
});

const selectedFriend = computed(() =>
  friends.value.find(f => f.id === selectedFriendId.value) ?? null
);
</script>

<template>
  <div class="min-h-screen bg-slate-50 dark:bg-slate-900 pb-12">

    <!-- Sticky Filter Bar -->
    <div class="sticky top-0 z-20 bg-white dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 shadow-sm">
      <div class="px-4 py-3 flex items-center justify-between gap-3">
        <div class="flex items-center gap-2">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-violet-600 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7" />
          </svg>
          <span class="font-black text-slate-800 dark:text-white text-sm tracking-wide">{{ t('arcade.title') }}</span>
        </div>

        <!-- Level Checkboxes -->
        <div class="flex items-center gap-3">
          <label class="flex items-center gap-1 cursor-pointer select-none">
            <input type="checkbox" v-model="showLv11" class="w-4 h-4 rounded accent-violet-500 cursor-pointer" />
            <span class="text-xs font-black" :class="showLv11 ? 'text-violet-600 dark:text-violet-400' : 'text-slate-400'">Lv.11</span>
          </label>
          <label class="flex items-center gap-1 cursor-pointer select-none">
            <input type="checkbox" v-model="showLv12" class="w-4 h-4 rounded accent-violet-500 cursor-pointer" />
            <span class="text-xs font-black" :class="showLv12 ? 'text-violet-600 dark:text-violet-400' : 'text-slate-400'">Lv.12</span>
          </label>
        </div>
      </div>
    </div>

    <div class="px-4 py-5 space-y-5">

      <!-- Loading -->
      <div v-if="isLoading" class="flex flex-col items-center justify-center py-20 gap-4">
        <div class="w-10 h-10 border-4 border-violet-100 border-t-violet-600 rounded-full animate-spin"></div>
        <p class="text-slate-500 font-bold text-sm">{{ t('arcade.loading') }}</p>
      </div>

      <template v-else>

        <!-- Mode Selection Grid (1x2) -->
        <div>
          <p class="text-[10px] font-black text-slate-400 uppercase tracking-widest mb-3">{{ t('arcade.chooseGoal') }}</p>
          <div class="grid grid-cols-2 gap-3">

            <!-- BEAT-PT -->
            <button
              @click="mode = mode === 'beat-pt' ? null : 'beat-pt'"
              class="flex flex-col items-start p-4 rounded-2xl border-2 transition-all text-left active:scale-95"
              :class="mode === 'beat-pt'
                ? 'bg-violet-600 border-violet-600 text-white shadow-lg shadow-violet-200 dark:shadow-violet-900'
                : 'bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200'"
            >
              <span class="text-2xl mb-1">⭐</span>
              <span class="text-sm font-black leading-tight whitespace-pre-line">{{ t('arcade.beatPtBtn') }}</span>
            </button>

            <!-- RATE-PT -->
            <button
              @click="mode = mode === 'rate-pt' ? null : 'rate-pt'"
              class="flex flex-col items-start p-4 rounded-2xl border-2 transition-all text-left active:scale-95"
              :class="mode === 'rate-pt'
                ? 'bg-indigo-600 border-indigo-600 text-white shadow-lg shadow-indigo-200 dark:shadow-indigo-900'
                : 'bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200'"
            >
              <span class="text-2xl mb-1">📈</span>
              <span class="text-sm font-black leading-tight whitespace-pre-line">{{ t('arcade.ratePtBtn') }}</span>
            </button>

            <!-- Rival -->
            <button
              @click="mode = mode === 'rival' ? null : 'rival'"
              class="flex flex-col items-start p-4 rounded-2xl border-2 transition-all text-left active:scale-95"
              :class="mode === 'rival'
                ? 'bg-rose-600 border-rose-600 text-white shadow-lg shadow-rose-200 dark:shadow-rose-900'
                : 'bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200'"
            >
              <span class="text-2xl mb-1">🔥</span>
              <span class="text-sm font-black leading-tight whitespace-pre-line">{{ t('arcade.rivalBtn') }}</span>
            </button>

            <!-- Border -->
            <button
              @click="mode = mode === 'border' ? null : 'border'"
              class="flex flex-col items-start p-4 rounded-2xl border-2 transition-all text-left active:scale-95"
              :class="mode === 'border'
                ? 'bg-amber-500 border-amber-500 text-white shadow-lg shadow-amber-200 dark:shadow-amber-900'
                : 'bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200'"
            >
              <span class="text-2xl mb-1">🎵</span>
              <span class="text-sm font-black leading-tight whitespace-pre-line">{{ t('arcade.borderBtn') }}</span>
            </button>

          </div>
        </div>

        <!-- Rival: Friend Selector -->
        <div v-if="mode === 'rival'" class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 p-4 space-y-3">
          <p class="text-xs font-black text-slate-500 uppercase tracking-widest">{{ t('arcade.selectRival') }}</p>
          <select
            v-model="selectedFriendId"
            class="w-full px-3 py-2.5 bg-slate-50 dark:bg-slate-700 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-bold text-slate-800 dark:text-white focus:outline-none focus:ring-2 focus:ring-rose-400"
          >
            <option :value="null">{{ t('arcade.selectFriendPlaceholder') }}</option>
            <option v-for="f in publicFriends" :key="f.id" :value="f.id">{{ f.displayName }}</option>
          </select>

          <!-- Sort Toggle -->
          <div v-if="selectedFriendId" class="flex items-center justify-between">
            <span class="text-xs font-bold text-slate-500">{{ t('arcade.sort') }}</span>
            <div class="flex rounded-lg overflow-hidden border border-slate-200 dark:border-slate-600 text-xs font-bold">
              <button
                @click="rivalSortClosest = true"
                class="px-3 py-1.5 transition-colors"
                :class="rivalSortClosest ? 'bg-rose-600 text-white' : 'bg-white dark:bg-slate-700 text-slate-600 dark:text-slate-300'"
              >{{ t('arcade.sortClosest') }}</button>
              <button
                @click="rivalSortClosest = false"
                class="px-3 py-1.5 transition-colors"
                :class="!rivalSortClosest ? 'bg-rose-600 text-white' : 'bg-white dark:bg-slate-700 text-slate-600 dark:text-slate-300'"
              >{{ t('arcade.sortWidest') }}</button>
            </div>
          </div>

          <!-- Loading friend scores -->
          <div v-if="isFriendLoading" class="flex items-center gap-2 text-xs text-slate-500 font-bold">
            <div class="w-4 h-4 border-2 border-rose-200 border-t-rose-500 rounded-full animate-spin"></div>
            {{ t('arcade.loadingScores') }}
          </div>
        </div>

        <!-- Border: Target Selector -->
        <div v-if="mode === 'border'" class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 p-4 space-y-3">
          <p class="text-xs font-black text-slate-500 uppercase tracking-widest">{{ t('arcade.borderTarget') }}</p>
          <select
            v-model="borderTarget"
            class="w-full px-3 py-2.5 bg-slate-50 dark:bg-slate-700 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-bold text-slate-800 dark:text-white focus:outline-none focus:ring-2 focus:ring-amber-400"
          >
            <option value="aa">{{ t('arcade.borderAaOption') }}</option>
            <option value="aaa">{{ t('arcade.borderAaaOption') }}</option>
            <option value="max-minus">{{ t('arcade.borderMaxMinusOption') }}</option>
          </select>
        </div>

        <!-- BEAT-PT: Target Input -->
        <div v-if="mode === 'beat-pt'" class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 p-4 space-y-3">
          <p class="text-xs font-black text-slate-500 uppercase tracking-widest">{{ t('arcade.beatPtTargetLabel') }}</p>
          <input
            type="number"
            v-model.number="beatPtTarget"
            min="0"
            step="any"
            :placeholder="t('arcade.beatPtTargetPlaceholder')"
            class="w-full px-3 py-2.5 bg-slate-50 dark:bg-slate-700 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-bold text-slate-800 dark:text-white focus:outline-none focus:ring-2 focus:ring-violet-400"
          />
          <p v-if="beatPtAchievedCount" class="text-xs font-bold text-slate-500">
            {{ t('arcade.achievedCount', { achieved: beatPtAchievedCount.achieved, total: beatPtAchievedCount.total }) }}
          </p>
        </div>

        <!-- RATE-PT: Target Input -->
        <div v-if="mode === 'rate-pt'" class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 p-4 space-y-3">
          <p class="text-xs font-black text-slate-500 uppercase tracking-widest">{{ t('arcade.ratePtTargetLabel') }}</p>
          <input
            type="number"
            v-model.number="ratePtTarget"
            min="0"
            step="1"
            :placeholder="t('arcade.ratePtTargetPlaceholder')"
            class="w-full px-3 py-2.5 bg-slate-50 dark:bg-slate-700 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-bold text-slate-800 dark:text-white focus:outline-none focus:ring-2 focus:ring-indigo-400"
          />
          <p v-if="ratePtAchievedCount" class="text-xs font-bold text-slate-500">
            {{ t('arcade.achievedCount', { achieved: ratePtAchievedCount.achieved, total: ratePtAchievedCount.total }) }}
          </p>
        </div>

        <!-- Results -->
        <template v-if="mode">
          <!-- Header -->
          <div class="flex items-center justify-between">
            <div>
              <p class="font-black text-slate-800 dark:text-white text-sm">{{ modeLabel }}</p>
              <p class="text-[10px] text-slate-400 font-bold mt-0.5">{{ modeSortNote }}</p>
            </div>
            <span class="text-xs font-black text-slate-400 bg-slate-100 dark:bg-slate-700 px-2 py-1 rounded-full">
              {{ t('arcade.count', { n: suggestions.length }) }}
            </span>
          </div>

          <!-- Empty States -->
          <div v-if="mode === 'rival' && !selectedFriendId" class="text-center py-12">
            <p class="text-4xl mb-3">👆</p>
            <p class="text-sm font-bold text-slate-500">{{ t('arcade.pickFriendHint') }}</p>
          </div>

          <div v-else-if="mode === 'rival' && selectedFriendId && !isFriendLoading && suggestions.length === 0" class="text-center py-12">
            <p class="text-4xl mb-3">🎉</p>
            <p class="text-sm font-bold text-slate-600 dark:text-slate-300">{{ t('arcade.noRivalLoss', { name: selectedFriend?.displayName ?? '' }) }}</p>
          </div>

          <div v-else-if="mode !== 'rival' && suggestions.length === 0" class="text-center py-12">
            <p class="text-4xl mb-3">✨</p>
            <p class="text-sm font-bold text-slate-600 dark:text-slate-300">{{ t('arcade.noSongs') }}</p>
          </div>

          <!-- Song Cards -->
          <div v-else-if="suggestions.length > 0" class="space-y-2">
            <div
              v-for="(s, i) in suggestions"
              :key="s.key"
              class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-100 dark:border-slate-700 p-3.5 flex items-start gap-3 shadow-sm"
            >
              <!-- Rank Number -->
              <div class="shrink-0 w-6 text-center text-[11px] font-black text-slate-400 dark:text-slate-500 pt-0.5">
                {{ i + 1 }}
              </div>

              <!-- Main Content -->
              <div class="flex-1 min-w-0">
                <!-- Title -->
                <p class="font-black text-slate-900 dark:text-white text-sm leading-tight truncate">{{ s.title }}</p>

                <!-- Badges Row -->
                <div class="flex flex-wrap items-center gap-1.5 mt-1.5">
                  <span class="px-1.5 py-0.5 rounded text-[10px] font-black" :class="s.difficultyColor">
                    {{ s.difficultyName.slice(0, 3) }} {{ s.difficultyLevel }}
                  </span>
                  <span v-if="s.informalRank" class="px-1.5 py-0.5 rounded text-[10px] font-black bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300">
                    ☆{{ s.informalRank.match(/(\d+\.\d+)/)?.[1] ?? s.informalRank }}
                  </span>
                  <span class="px-1.5 py-0.5 rounded text-[10px] font-bold bg-slate-50 dark:bg-slate-700/50 text-slate-500">
                    {{ s.djLevel }}
                  </span>
                </div>

                <!-- Score Row -->
                <div class="flex items-baseline gap-2 mt-1.5">
                  <span class="text-xs font-bold text-slate-500 dark:text-slate-400">
                    {{ s.score.toLocaleString() }}
                    <span v-if="s.maxScore > 0" class="text-[10px] font-normal"> / {{ s.maxScore.toLocaleString() }}</span>
                  </span>
                  <span v-if="s.scoreRate > 0" class="text-[10px] font-bold text-slate-400">
                    {{ s.scoreRate.toFixed(2) }}%
                  </span>
                </div>

                <!-- Sub Label -->
                <p class="text-[10px] text-slate-400 dark:text-slate-500 font-bold mt-0.5">{{ s.subLabel }}</p>
              </div>

              <!-- Gap Label -->
              <div class="shrink-0 text-right">
                <span
                  class="inline-block px-2.5 py-1 rounded-xl text-xs font-black whitespace-nowrap"
                  :class="{
                    'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300': mode === 'rival',
                    'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300': mode === 'border',
                    'bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-300': mode === 'beat-pt',
                    'bg-indigo-100 text-indigo-700 dark:bg-indigo-900/40 dark:text-indigo-300': mode === 'rate-pt',
                  }"
                >
                  {{ s.gapLabel }}
                </span>
              </div>
            </div>
          </div>
        </template>

      </template>
    </div>
  </div>
</template>
