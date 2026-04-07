<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useAuth, API_BASE } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';
import { useGameData, type SongDataEntry } from '../composables/useGameData';

const props = defineProps<{
  viewingUserId?: number | null;
  viewingMode?: 'admin' | 'friend' | null;
}>();

const { t } = useI18n();
const { isLoggedIn, authHeaders } = useAuth();
const { songDataBody } = useGameData();

// adminモードで別ユーザーを閲覧中かどうか
const isAdminViewing = computed(() =>
  props.viewingMode === 'admin' && props.viewingUserId != null
);

// ── 曲選択 ──────────────────────────────────────────────────
const searchQuery = ref('');
const selectedEntry = ref<SongDataEntry | null>(null);

// ANOTHER(4) / LEGGENDARIA(10) で textage を持つ曲のみ
const targetEntries = computed((): SongDataEntry[] => {
  return songDataBody.value.filter(
    s => (s.difficulty === '4' || s.difficulty === '10') && !!s.textage
  );
});

const filteredEntries = computed((): SongDataEntry[] => {
  const q = searchQuery.value.trim().toLowerCase();
  if (!q) return targetEntries.value.slice(0, 50);
  return targetEntries.value
    .filter(s => s.title.toLowerCase().includes(q) || s.artist.toLowerCase().includes(q))
    .slice(0, 80);
});

function selectEntry(entry: SongDataEntry) {
  if (selectedEntry.value?.textage === entry.textage && !isAdminViewing.value) return;
  selectedEntry.value = entry;
  predictionResult.value = null;
  predictionError.value = '';
  fetchPrediction(entry.textage!);
}

// 閲覧ユーザーが変わったら現在の曲の予測を再取得
watch(() => props.viewingUserId, () => {
  predictionResult.value = null;
  predictionError.value = '';
  if (selectedEntry.value?.textage) {
    fetchPrediction(selectedEntry.value.textage);
  }
});

function diffLabel(difficulty: string): string {
  return difficulty === '10' ? 'LEGGENDARIA' : 'ANOTHER';
}

function diffBadgeClass(difficulty: string): string {
  return difficulty === '10'
    ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300'
    : 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300';
}

// ── 予測API ─────────────────────────────────────────────────
interface SimilarSong {
  title: string;
  difficultyName: string;
  textage: string;
  score?: number;
  scoreRate?: number;
  similarity: number;
  played: boolean;
}

// ── 類似度デバッグ ───────────────────────────────────────────
const debugResult = ref<Record<string, any> | null>(null);
const isDebugLoading = ref(false);

async function fetchSimilarityDebug(textageB: string) {
  if (!selectedEntry.value?.textage) return;
  isDebugLoading.value = true;
  debugResult.value = null;
  try {
    const res = await fetch(
      `${API_BASE}/api/admin/similarity-debug?textageA=${encodeURIComponent(selectedEntry.value.textage)}&textageB=${encodeURIComponent(textageB)}`,
      { headers: authHeaders() }
    );
    debugResult.value = await res.json();
  } catch (e: any) {
    debugResult.value = { error: e.message };
  } finally {
    isDebugLoading.value = false;
  }
}

interface PredictionResult {
  textage: string;
  title: string;
  difficulty: string;
  level: number;
  notes: number;
  dominantEff16: number;
  predictedScore: number;
  predictedScoreRate: number;
  currentScore?: number;
  currentScoreRate?: number;
  similarSongs: SimilarSong[];
  message?: string;
  error?: string;
}

const predictionResult = ref<PredictionResult | null>(null);
const predictionError = ref('');
const isLoading = ref(false);

async function fetchPrediction(textage: string) {
  if (!isLoggedIn.value) return;
  isLoading.value = true;
  predictionError.value = '';
  predictionResult.value = null;

  try {
    const url = isAdminViewing.value
      ? `${API_BASE}/api/admin/score-prediction?textage=${encodeURIComponent(textage)}&userId=${props.viewingUserId}`
      : `${API_BASE}/api/analysis/score-prediction?textage=${encodeURIComponent(textage)}`;
    const res = await fetch(url, { headers: authHeaders() });
    const data = await res.json();
    if (!res.ok || data.error) {
      predictionError.value = data.error ?? `エラー: ${res.status}`;
    } else {
      predictionResult.value = data as PredictionResult;
    }
  } catch (e: any) {
    predictionError.value = e.message ?? '通信エラー';
  } finally {
    isLoading.value = false;
  }
}

// ── 表示ヘルパー ─────────────────────────────────────────────
function djLevel(rate: number): string {
  if (rate >= 100) return 'MAX';
  if (rate >= 94.45) return 'MAX-';
  if (rate >= 88.89) return 'AAA';
  if (rate >= 77.78) return 'AA';
  if (rate >= 66.67) return 'A';
  if (rate >= 55.56) return 'B';
  if (rate >= 44.44) return 'C';
  if (rate >= 33.33) return 'D';
  if (rate >= 22.22) return 'E';
  return 'F';
}

function djLevelClass(rate: number): string {
  if (rate >= 94.45) return 'text-purple-600 dark:text-purple-400 font-extrabold';
  if (rate >= 88.89) return 'text-yellow-500 dark:text-yellow-400 font-extrabold';
  if (rate >= 77.78) return 'text-blue-500 dark:text-blue-400 font-bold';
  if (rate >= 66.67) return 'text-green-500 dark:text-green-400 font-bold';
  if (rate >= 55.56) return 'text-slate-700 dark:text-slate-300 font-semibold';
  return 'text-slate-500 dark:text-slate-400';
}

const displayScore = computed(() => {
  const r = predictionResult.value;
  if (!r) return null;
  const diff = (r.currentScore != null)
    ? r.predictedScore - r.currentScore
    : null;
  const diffRate = (r.currentScoreRate != null)
    ? Math.round((r.predictedScoreRate - r.currentScoreRate) * 10) / 10
    : null;
  return { score: r.predictedScore, rate: r.predictedScoreRate, diff, diffRate };
});

// ── プロファイル詳細取得 ────────────────────────────────────
interface TendencyProfile {
  title: string;
  difficulty: string;
  level: number;
  notes: number;
  bpmRaw: string;
  bpmMain: number;
  isSoflan: boolean;
  dominantEff16: number;
  weightedEff16: number;
  scratchPct: number;
  chordPct: number;
  singlePct: number;
  ranuchi: number;
  tagsJson: string | null;
  intervalDistJson: string | null;
}

// tick値 → 日本語音符名のマッピング（96 ticks/quarter note基準）
const TICK_TO_NOTE: { tick: number; label: string }[] = [
  { tick: 384, label: '全音符' },
  { tick: 288, label: '付点2分' },
  { tick: 192, label: '2分' },
  { tick: 144, label: '付点4分' },
  { tick: 96,  label: '4分' },
  { tick: 72,  label: '付点8分' },
  { tick: 64,  label: '6分' },
  { tick: 48,  label: '8分' },
  { tick: 36,  label: '付点16分' },
  { tick: 32,  label: '12分' },
  { tick: 24,  label: '16分' },
  { tick: 18,  label: '付点32分' },
  { tick: 16,  label: '24分' },
  { tick: 12,  label: '32分' },
  { tick: 8,   label: '48分' },
  { tick: 6,   label: '64分' },
];

interface NoteDistEntry {
  label: string;
  pct: number;
  count: number;
}

const NOTE_COLORS = [
  'bg-blue-500',
  'bg-emerald-500',
  'bg-amber-500',
  'bg-rose-500',
  'bg-violet-500',
  'bg-cyan-500',
  'bg-orange-500',
  'bg-teal-500',
  'bg-pink-500',
  'bg-indigo-500',
  'bg-lime-500',
];

const noteDistribution = computed((): NoteDistEntry[] => {
  if (!tendencyProfile.value?.intervalDistJson) return [];
  try {
    const dist = JSON.parse(tendencyProfile.value.intervalDistJson) as Record<string, { name: string; count: number; pct: number; eff16: number }>;
    const tickMap = new Map(TICK_TO_NOTE.map(t => [String(t.tick), t.label]));

    const entries: NoteDistEntry[] = [];
    let knownPct = 0;
    for (const [tick, data] of Object.entries(dist)) {
      const label = tickMap.get(tick);
      if (label && data.pct > 0) {
        entries.push({ label, pct: data.pct, count: data.count });
        knownPct += data.pct;
      }
    }
    // TICK_TO_NOTE の順に並べる（全音符→64分）
    const order = new Map(TICK_TO_NOTE.map((t, i) => [t.label, i]));
    entries.sort((a, b) => (order.get(a.label) ?? 99) - (order.get(b.label) ?? 99));
    // 残りを「その他」として追加
    const otherPct = Math.round((100 - knownPct) * 10) / 10;
    if (otherPct > 0) {
      entries.push({ label: 'その他', pct: otherPct, count: 0 });
    }
    return entries;
  } catch {
    return [];
  }
});

const tendencyProfile = ref<TendencyProfile | null>(null);

watch(selectedEntry, async (entry) => {
  tendencyProfile.value = null;
  if (!entry?.textage) return;
  try {
    const res = await fetch(
      `${API_BASE}/api/analysis/tendency-profile?textage=${encodeURIComponent(entry.textage)}`,
      { headers: authHeaders() }
    );
    if (res.ok) {
      tendencyProfile.value = await res.json();
    }
  } catch {
    // 取得失敗は無視
  }
});

</script>

<template>
  <div class="px-4 py-6">
    <h2 class="text-2xl font-bold text-slate-800 dark:text-white mb-1">
      {{ t('nav.scorePrediction') }}
    </h2>
    <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">
      {{ t('scorePrediction.subtitle') }}
    </p>

    <!-- adminモード中バナー -->
    <div v-if="isAdminViewing"
      class="mb-5 flex items-center gap-2 rounded-lg bg-indigo-50 dark:bg-indigo-900/30 border border-indigo-200 dark:border-indigo-700 px-4 py-2.5 text-sm text-indigo-700 dark:text-indigo-300 font-medium"
    >
      <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M5.121 17.804A13.937 13.937 0 0112 16c2.5 0 4.847.655 6.879 1.804M15 10a3 3 0 11-6 0 3 3 0 016 0zM19 10a7 7 0 11-14 0 7 7 0 0114 0z" />
      </svg>
      閲覧中ユーザーのスコアを使用して予測しています
    </div>

    <!-- 未ログイン -->
    <div v-if="!isLoggedIn"
      class="rounded-xl border border-amber-300 bg-amber-50 dark:bg-amber-900/20 dark:border-amber-700 p-6 text-center">
      <p class="text-amber-700 dark:text-amber-300 font-medium">{{ t('scorePrediction.loginRequired') }}</p>
    </div>

    <div v-else class="flex flex-col lg:flex-row gap-6">

      <!-- 左: 曲選択 -->
      <div class="w-full lg:w-80 shrink-0 flex flex-col gap-3">
        <input
          v-model="searchQuery"
          type="text"
          :placeholder="t('scorePrediction.searchPlaceholder')"
          class="w-full px-4 py-2.5 rounded-lg border border-slate-300 dark:border-slate-600
                 bg-white dark:bg-slate-800 text-slate-800 dark:text-white
                 focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm"
        />
        <div class="text-xs text-slate-400 dark:text-slate-500">
          {{ filteredEntries.length }} {{ t('scorePrediction.songCount') }}
        </div>

        <div class="flex flex-col gap-1 max-h-[70vh] overflow-y-auto pr-1">
          <button
            v-for="entry in filteredEntries"
            :key="entry.textage"
            @click="selectEntry(entry)"
            class="w-full text-left px-3 py-2.5 rounded-lg border transition-colors text-sm"
            :class="selectedEntry?.textage === entry.textage
              ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300'
              : 'border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 hover:bg-slate-50 dark:hover:bg-slate-700 text-slate-700 dark:text-slate-300'"
          >
            <div class="flex items-start gap-2">
              <span
                class="shrink-0 mt-0.5 px-1.5 py-0.5 rounded text-xs font-bold"
                :class="diffBadgeClass(entry.difficulty)"
              >{{ diffLabel(entry.difficulty) }}</span>
              <div class="min-w-0">
                <div class="font-medium truncate">{{ entry.title }}</div>
                <div class="text-xs text-slate-400 dark:text-slate-500">Lv.{{ entry.level }}</div>
              </div>
            </div>
          </button>
        </div>
      </div>

      <!-- 右: 結果パネル -->
      <div class="flex-1 min-w-0">

        <!-- 未選択 -->
        <div v-if="!selectedEntry"
          class="h-48 flex items-center justify-center text-slate-400 dark:text-slate-500 text-sm border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-xl">
          {{ t('scorePrediction.selectSong') }}
        </div>

        <!-- ローディング -->
        <div v-else-if="isLoading"
          class="h-48 flex items-center justify-center text-slate-400 dark:text-slate-500">
          <svg class="animate-spin h-6 w-6 mr-2" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
          </svg>
          {{ t('scorePrediction.calculating') }}
        </div>

        <!-- エラー -->
        <div v-else-if="predictionError"
          class="rounded-xl border border-red-300 bg-red-50 dark:bg-red-900/20 dark:border-red-700 p-6">
          <p class="text-red-600 dark:text-red-400 text-sm">{{ predictionError }}</p>
        </div>

        <!-- 結果 -->
        <div v-else-if="predictionResult" class="flex flex-col gap-4">

          <!-- 曲ヘッダ -->
          <div class="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5">
            <div class="flex flex-wrap items-start gap-3 mb-3">
              <span
                class="px-2 py-1 rounded text-xs font-bold shrink-0"
                :class="diffBadgeClass(predictionResult.difficulty)"
              >{{ diffLabel(predictionResult.difficulty) }}</span>
              <div>
                <div class="text-lg font-bold text-slate-800 dark:text-white">{{ predictionResult.title }}</div>
                <div class="text-sm text-slate-500 dark:text-slate-400">
                  Lv.{{ predictionResult.level }}
                  &nbsp;·&nbsp;{{ predictionResult.notes }} notes
                  <template v-if="tendencyProfile">
                    &nbsp;·&nbsp;{{ tendencyProfile.bpmRaw }} BPM
                    <span v-if="tendencyProfile.isSoflan" class="ml-1 text-amber-500 font-semibold">(ソフラン)</span>
                  </template>
                </div>
              </div>
            </div>

            <!-- 傾向数値 -->
            <div v-if="tendencyProfile" class="grid grid-cols-3 gap-3 text-center">
              <div class="rounded-lg bg-slate-50 dark:bg-slate-700/50 p-2">
                <div class="text-xs text-slate-400 dark:text-slate-500">実効BPM</div>
                <div class="text-base font-bold text-slate-700 dark:text-slate-200">{{ tendencyProfile.dominantEff16.toFixed(0) }}</div>
              </div>
              <div class="rounded-lg bg-orange-50 dark:bg-orange-900/20 p-2">
                <div class="text-xs text-orange-500 dark:text-orange-400">SCR率</div>
                <div class="text-base font-bold text-orange-600 dark:text-orange-300">{{ tendencyProfile.scratchPct.toFixed(1) }}%</div>
              </div>
              <div class="rounded-lg bg-blue-50 dark:bg-blue-900/20 p-2">
                <div class="text-xs text-blue-500 dark:text-blue-400">同時押し率</div>
                <div class="text-base font-bold text-blue-600 dark:text-blue-300">{{ tendencyProfile.chordPct.toFixed(1) }}%</div>
              </div>
            </div>

            <!-- 音符割合分布（スタックバー） -->
            <div v-if="noteDistribution.length" class="mt-3">
              <div class="text-xs font-medium text-slate-400 dark:text-slate-500 mb-2">音符割合</div>
              <!-- スタックバー -->
              <div class="w-full h-6 rounded-lg overflow-hidden flex">
                <div v-for="(nd, i) in noteDistribution" :key="nd.label"
                  class="h-full flex items-center justify-center text-[10px] font-bold text-white transition-all"
                  :class="NOTE_COLORS[i % NOTE_COLORS.length]"
                  :style="{ width: `${nd.pct}%` }"
                  :title="`${nd.label}: ${nd.pct}%`"
                >
                  <span v-if="nd.pct >= 5">{{ nd.pct }}%</span>
                </div>
              </div>
              <!-- 凡例 -->
              <div class="flex flex-wrap gap-x-3 gap-y-1 mt-2">
                <div v-for="(nd, i) in noteDistribution" :key="nd.label"
                  class="flex items-center gap-1 text-[11px]">
                  <span class="inline-block w-2.5 h-2.5 rounded-sm shrink-0"
                    :class="NOTE_COLORS[i % NOTE_COLORS.length]"></span>
                  <span class="font-bold text-slate-600 dark:text-slate-300">{{ nd.label }}</span>
                  <span class="tabular-nums text-slate-400 dark:text-slate-500">{{ nd.pct }}%</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 予測スコア -->
          <div class="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5">
            <h3 class="text-sm font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide mb-4">
              {{ t('scorePrediction.prediction') }}
            </h3>

            <div v-if="predictionResult.message && predictionResult.predictedScore === 0"
              class="text-sm text-slate-500 dark:text-slate-400 py-4 text-center">
              {{ predictionResult.message }}
            </div>

            <div v-else-if="displayScore" class="flex flex-col sm:flex-row items-center gap-6">
              <!-- スコア大表示 -->
              <div class="text-center shrink-0">
                <div class="text-4xl font-extrabold text-slate-800 dark:text-white tabular-nums">
                  {{ displayScore.score.toLocaleString() }}
                </div>
                <div class="text-sm text-slate-400 dark:text-slate-500 mt-0.5">
                  / {{ (predictionResult.notes * 2).toLocaleString() }}
                </div>
                <div class="mt-2 text-2xl font-bold" :class="djLevelClass(displayScore.rate)">
                  {{ djLevel(displayScore.rate) }}
                </div>
                <!-- 現在スコアとの差分 -->
                <div v-if="displayScore.diff != null" class="mt-2 text-xs tabular-nums"
                  :class="displayScore.diff > 0
                    ? 'text-emerald-600 dark:text-emerald-400'
                    : displayScore.diff < 0
                    ? 'text-red-500 dark:text-red-400'
                    : 'text-slate-400 dark:text-slate-500'"
                >
                  <span class="font-bold">{{ displayScore.diff > 0 ? '+' : '' }}{{ displayScore.diff.toLocaleString() }}</span>
                  <span class="ml-1">({{ displayScore.diffRate! > 0 ? '+' : '' }}{{ displayScore.diffRate }}%)</span>
                  <div class="text-slate-400 dark:text-slate-500 font-normal mt-0.5">vs 現在スコア</div>
                </div>
              </div>

              <!-- プログレスバー -->
              <div class="flex-1 w-full">
                <div class="flex justify-between text-xs text-slate-500 dark:text-slate-400 mb-1">
                  <span>{{ t('scorePrediction.scoreRate') }}</span>
                  <span>{{ displayScore.rate }}%</span>
                </div>
                <div class="w-full h-3 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
                  <div
                    class="h-full rounded-full transition-all duration-700"
                    :class="displayScore.rate >= 88.89
                      ? 'bg-yellow-400'
                      : displayScore.rate >= 77.78
                      ? 'bg-blue-400'
                      : displayScore.rate >= 66.67
                      ? 'bg-green-400'
                      : 'bg-slate-400'"
                    :style="{ width: `${Math.min(displayScore.rate, 100)}%` }"
                  />
                </div>
                <!-- DJランク目安ライン -->
                <div class="relative h-4 mt-1">
                  <div v-for="(mark, idx) in [
                    { label: 'A', pct: 66.67, cls: 'text-green-500' },
                    { label: 'AA', pct: 77.78, cls: 'text-blue-500' },
                    { label: 'AAA', pct: 88.89, cls: 'text-yellow-500' },
                  ]" :key="idx"
                    class="absolute text-xs font-bold -translate-x-1/2"
                    :class="mark.cls"
                    :style="{ left: `${mark.pct}%` }"
                  >{{ mark.label }}</div>
                </div>
              </div>
            </div>
          </div>

          <!-- 類似譜面 -->
          <div v-if="predictionResult.similarSongs.length"
            class="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5">
            <h3 class="text-sm font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide mb-3">
              {{ t('scorePrediction.similarSongs') }}
              <span class="ml-1 font-normal">({{ predictionResult.similarSongs.length }}件)</span>
            </h3>
            <div class="overflow-x-auto">
              <table class="w-full text-sm">
                <thead>
                  <tr class="text-xs text-slate-400 dark:text-slate-500 border-b border-slate-100 dark:border-slate-700">
                    <th class="text-left pb-2 pr-3 font-medium">曲名</th>
                    <th class="text-left pb-2 pr-3 font-medium whitespace-nowrap">難易度</th>
                    <th class="text-right pb-2 pr-3 font-medium whitespace-nowrap">自分のスコア</th>
                    <th class="text-right pb-2 pr-3 font-medium whitespace-nowrap">スコア率</th>
                    <th class="text-right pb-2 font-medium whitespace-nowrap">類似度</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="song in predictionResult.similarSongs"
                    :key="`${song.title}_${song.difficultyName}`"
                    class="border-b border-slate-50 dark:border-slate-700/50 last:border-0 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-700/40 transition-colors"
                    @click="fetchSimilarityDebug(song.textage)"
                  >
                    <td class="py-2 pr-3 text-slate-700 dark:text-slate-300 max-w-[180px] truncate">{{ song.title }}</td>
                    <td class="py-2 pr-3">
                      <span class="px-1.5 py-0.5 rounded text-xs font-bold"
                        :class="song.difficultyName === 'LEGGENDARIA'
                          ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300'
                          : 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300'">
                        {{ song.difficultyName === 'LEGGENDARIA' ? 'LEGG' : 'ANO' }}
                      </span>
                    </td>
                    <td class="py-2 pr-3 text-right tabular-nums" :class="song.played ? 'text-slate-700 dark:text-slate-300' : 'text-slate-400 dark:text-slate-500'">
                      <template v-if="song.played">{{ song.score!.toLocaleString() }}</template>
                      <template v-else>-</template>
                    </td>
                    <td class="py-2 pr-3 text-right tabular-nums" :class="song.played ? djLevelClass(song.scoreRate!) : 'text-slate-400 dark:text-slate-500'">
                      <template v-if="song.played">{{ song.scoreRate }}%</template>
                      <template v-else>未プレイ</template>
                    </td>
                    <td class="py-2 text-right tabular-nums text-slate-500 dark:text-slate-400">
                      {{ (song.similarity * 100).toFixed(2) }}%
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- 類似度デバッグモーダル -->
          <Teleport to="body">
            <div v-if="debugResult || isDebugLoading"
              class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
              @click.self="debugResult = null">
              <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-xl w-full max-w-2xl max-h-[90vh] overflow-y-auto p-6">
                <div class="flex justify-between items-center mb-4">
                  <h3 class="text-base font-bold text-slate-800 dark:text-slate-100">類似度計算過程</h3>
                  <button @click="debugResult = null" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200">✕</button>
                </div>
                <div v-if="isDebugLoading" class="flex justify-center py-10">
                  <div class="w-8 h-8 border-4 border-blue-200 border-t-blue-500 rounded-full animate-spin"></div>
                </div>
                <div v-else-if="debugResult">
                  <!-- 曲名 -->
                  <div class="flex gap-3 mb-4 text-sm">
                    <div class="flex-1 bg-slate-50 dark:bg-slate-900 rounded-xl p-3">
                      <div class="text-xs text-slate-400 mb-1">対象曲 (A)</div>
                      <div class="font-bold text-slate-800 dark:text-slate-100">{{ debugResult.songA?.title }}</div>
                      <div class="text-xs text-slate-500">難易度 {{ debugResult.songA?.informalRank }}</div>
                    </div>
                    <div class="flex-1 bg-slate-50 dark:bg-slate-900 rounded-xl p-3">
                      <div class="text-xs text-slate-400 mb-1">参照曲 (B)</div>
                      <div class="font-bold text-slate-800 dark:text-slate-100">{{ debugResult.songB?.title }}</div>
                      <div class="text-xs text-slate-500">難易度 {{ debugResult.songB?.informalRank }}</div>
                    </div>
                  </div>
                  <!-- 生データ比較 -->
                  <div class="mb-4">
                    <div class="text-xs font-bold text-slate-500 uppercase tracking-wide mb-2">生データ比較</div>
                    <table class="w-full text-xs">
                      <thead><tr class="text-slate-400"><th class="text-left pb-1">指標</th><th class="text-right pb-1">A</th><th class="text-right pb-1">B</th></tr></thead>
                      <tbody class="divide-y divide-slate-100 dark:divide-slate-700">
                        <tr v-for="[key, label] in [
                          ['nps', 'ノーツ密度 (nps)'],
                          ['dominantEff16', '主要インターバル実効BPM'],
                          ['weightedEff16', '加重平均実効BPM'],
                          ['scratchPct', 'スクラッチ割合 (%)'],
                          ['chordPct', '同時押し割合 (%)'],
                          ['cnRatio', 'CN割合'],
                        ]" :key="key">
                          <td class="py-1 text-slate-500">{{ label }}</td>
                          <td class="py-1 text-right tabular-nums text-slate-700 dark:text-slate-300">{{ debugResult.rawA?.[key] }}</td>
                          <td class="py-1 text-right tabular-nums text-slate-700 dark:text-slate-300">{{ debugResult.rawB?.[key] }}</td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                  <!-- グループ別結果 -->
                  <div class="space-y-3">
                    <div v-for="[label, key, color, paramLabels] in ([
                      ['Group1: 密度', 'group1_density', 'blue', {
                        dNps_norm: 'ノーツ密度差（正規化）',
                        dEff16_norm: '主要BPM差（正規化）',
                        dWEff16_norm: '加重BPM差（正規化）',
                        dRank_norm: '非公式難易度差（正規化）',
                        dist2: '距離二乗和',
                        densitySim: '密度類似度',
                      }],
                      ['Group2: スクラッチ', 'group2_scratch', 'orange', {
                        dScratchPct_norm: 'スクラッチ割合差（正規化）',
                        scrScalar: 'スクラッチ量スカラー類似度',
                        scrIntervalCosineSim: 'スクラッチリズムコサイン類似度',
                        scratchSim: 'スクラッチ総合類似度',
                        'weight(scrW)': '重み (max側の割合)',
                        contribution: 'グループ寄与度',
                      }],
                      ['Group3: 鍵盤パターン', 'group3_pattern', 'green', {
                        dChordPct_norm: '同時押し割合差（正規化）',
                        chordScalar: '同時押しスカラー類似度',
                        kbdIntervalCosineSim: '鍵盤リズムコサイン類似度',
                        patternSim: '鍵盤パターン総合類似度',
                        'weight(kbdW)': '重み (1 - 平均スクラッチ割合)',
                        contribution: 'グループ寄与度',
                      }],
                      ['Group4: CN', 'group4_cn', 'purple', {
                        cnRatioA: 'CN割合 A',
                        cnRatioB: 'CN割合 B',
                        dCnRatio_norm: 'CN割合差（正規化）',
                        dCnScratch_norm: 'CNスクラッチ割合差（正規化）',
                        cnScalar: 'CNスカラー類似度',
                        cnIntervalCosineSim: 'CNリズムコサイン類似度',
                        cnSim: 'CN総合類似度',
                        'weight(cnW)': '重み (平均CN割合)',
                        contribution: 'グループ寄与度',
                      }],
                    ] as any[])" :key="key" class="rounded-xl border border-slate-100 dark:border-slate-700 p-3">
                      <div class="flex justify-between items-center mb-2">
                        <span class="text-xs font-bold text-slate-600 dark:text-slate-300">{{ label }}</span>
                        <span class="text-sm font-black tabular-nums"
                          :class="color === 'blue' ? 'text-blue-600 dark:text-blue-400' : color === 'orange' ? 'text-orange-600 dark:text-orange-400' : color === 'green' ? 'text-green-600 dark:text-green-400' : 'text-purple-600 dark:text-purple-400'">
                          寄与: {{ ((debugResult[key]?.contribution ?? 1) * 100).toFixed(2) }}%
                        </span>
                      </div>
                      <div class="grid grid-cols-2 gap-x-4 gap-y-0.5 text-xs text-slate-500">
                        <template v-for="(val, k) in debugResult[key]" :key="k">
                          <span class="truncate" :title="String(k)">{{ paramLabels[k] ?? k }}</span>
                          <span class="text-right tabular-nums text-slate-700 dark:text-slate-300">{{ val }}</span>
                        </template>
                      </div>
                    </div>
                  </div>
                  <!-- 最終結果 -->
                  <div class="mt-4 rounded-xl bg-slate-900 dark:bg-slate-950 p-4 text-center">
                    <div class="text-xs text-slate-400 mb-1">最終類似度</div>
                    <div class="text-3xl font-black text-white">{{ debugResult.result?.finalSimilarityPct }}</div>
                    <div class="text-xs text-slate-500 mt-1">
                      統合値 (G1×G2^scrW×G3^kbdW×G4^cnW): {{ debugResult.result?.combined }}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </Teleport>

        </div><!-- /predictionResult -->

      </div><!-- /右パネル -->
    </div><!-- /flex -->

    <!-- 謝辞 -->
    <div class="mt-8 pt-4 border-t border-slate-200 dark:border-slate-700 text-center text-xs text-slate-400 dark:text-slate-500">
      譜面データは
      <a href="https://textage.cc/" target="_blank" rel="noopener noreferrer"
         class="text-blue-500 hover:text-blue-400 underline">TexTage</a>
      を利用しています。
    </div>
  </div>
</template>
