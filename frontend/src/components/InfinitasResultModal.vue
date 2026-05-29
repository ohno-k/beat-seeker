<script setup lang="ts">
/**
 * 【コンポーネントの役割】 INFINITAS リザルトの OCR 抽出結果を表示し、
 * ユーザーに編集 → 確定させる確認モーダル。
 *
 * 入力: useInfinitasMonitor で抽出した InfinitasResult + beat-seeker 上の既存スコア
 * 出力:
 *  - 'confirm': 編集後の ScoreData[]（1 曲分）を親に通知。親は useScoreUpload で送信
 *  - 'skip': 検出をスキップして監視継続
 *
 * スコア選択ロジック:
 *  - 自己ベスト更新時（今回プレイ ≥ 自己ベスト）→ 今回プレイのスコアを使用
 *  - beat-seeker 上にスコアが存在しない場合 → 自己ベストを使用
 *  - それ以外 → 今回プレイ（サーバー側でベスト判定）
 */
import { ref, computed, watch } from 'vue';
import type { InfinitasResult } from '../composables/useInfinitasMonitor';
import { songData, type SongDataEntry } from '../composables/useGameData';
import { learnFromConfirmation, computeDjLevel } from '../composables/infinitasResultRecognizer';
import type { ScoreData, DifficultyStats } from '../types/ScoreData';
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();

const props = defineProps<{
  result: InfinitasResult;
  /** beat-seeker 上の既存スコア。スコア選択ロジックに使用。 */
  existingScores: ScoreData[];
}>();
const emit = defineEmits<{
  (e: 'confirm', score: ScoreData): void;
  (e: 'skip'): void;
}>();

/** ANOTHER (4) / LEGGENDARIA (10) のみ。 */
const DIFF_CODES: Record<'ANOTHER' | 'LEGGENDARIA', string> = { ANOTHER: '4', LEGGENDARIA: '10' };
const CLEAR_TYPE_OPTIONS = [
  'FAILED',
  'ASSIST CLEAR',
  'EASY CLEAR',
  'CLEAR',
  'HARD CLEAR',
  'EX HARD CLEAR',
  'FULLCOMBO CLEAR',
];

/** スコアの取得元。 */
type ScoreSource = 'current' | 'best';

/**
 * beat-seeker 上に該当曲・難易度のスコアが存在するかを判定する。
 */
function hasExistingScore(title: string, diff: 'ANOTHER' | 'LEGGENDARIA'): boolean {
  const diffKey = diff.toLowerCase() as 'another' | 'leggendaria';
  return props.existingScores.some(s =>
    s.title === title &&
    s[diffKey] &&
    (s[diffKey] as DifficultyStats).clearType !== 'NO PLAY' &&
    (s[diffKey] as DifficultyStats).score > 0
  );
}

/**
 * 今回プレイと自己ベストのどちらを使うかを決定する。
 * - 自己ベスト更新（今回 ≥ ベスト）→ current
 * - beat-seeker にスコアなし → best（自己ベストが今回より高い場合）
 * - それ以外 → current
 */
function resolveScoreSource(): ScoreSource {
  const currentScore = props.result.score ?? 0;
  const bestScore = props.result.bestScore ?? 0;

  // 自己ベストが読み取れなかった場合は今回プレイを使用
  if (bestScore === 0) return 'current';

  // 自己ベスト更新時（今回プレイ ≥ 自己ベスト）→ 今回プレイを使用
  if (currentScore >= bestScore) return 'current';

  // beat-seeker 上にスコアが存在しない場合 → 自己ベストを使用
  const title = props.result.songEntry?.title;
  const diff = props.result.difficulty;
  if (title && diff && !hasExistingScore(title, diff)) {
    return 'best';
  }

  // beat-seeker 上に既存スコアありかつ自己ベスト未更新 → 今回プレイ（サーバー側で棄却）
  return 'current';
}

// ── スコアソース選択 ──
const scoreSource = ref<ScoreSource>(resolveScoreSource());

/** 自己ベストが OCR で取得できたか。 */
const hasBestData = computed(() => (props.result.bestScore ?? 0) > 0);

/** 自己ベスト更新かどうか。 */
const isBestUpdated = computed(() => {
  const current = props.result.score ?? 0;
  const best = props.result.bestScore ?? 0;
  return current >= best && current > 0;
});

// ── 編集用のローカル state（scoreSource に応じて初期化）──
function initScore() {
  return scoreSource.value === 'best' ? (props.result.bestScore ?? 0) : (props.result.score ?? 0);
}
function initMissCount() {
  return scoreSource.value === 'best' ? (props.result.bestMissCount ?? 0) : (props.result.missCount ?? 0);
}
function initClearType() {
  return scoreSource.value === 'best' ? (props.result.bestClearType || 'CLEAR') : (props.result.clearType || 'CLEAR');
}

const selectedSong = ref<SongDataEntry | null>(props.result.songEntry);
const difficulty = ref<'ANOTHER' | 'LEGGENDARIA'>(props.result.difficulty || 'ANOTHER');
const score = ref<number>(initScore());
const missCount = ref<number>(initMissCount());
const clearType = ref<string>(initClearType());
/**
 * DJ LEVEL は EX SCORE と最大スコア(notes×2)から計算する（OCR せず）。
 * notes は「特定できた曲の notes」を最優先し、無ければ認識した notesCount を使う。
 * 曲をユーザーが選び直すと selectedSong.notes 経由で自動的に再計算される。
 */
const djLevel = computed<string>(() => {
  const notes = selectedSong.value?.notes ?? props.result.notesCount ?? null;
  return computeDjLevel(score.value, notes) ?? '---';
});
// JUDGE 内訳は今回プレイのみ取得可能。自己ベスト選択時は 0 で初期化。
const pgreat = ref<number>(scoreSource.value === 'best' ? 0 : (props.result.pgreat ?? 0));
const great = ref<number>(scoreSource.value === 'best' ? 0 : (props.result.great ?? 0));
const good = ref<number>(scoreSource.value === 'best' ? 0 : (props.result.good ?? 0));
const bad = ref<number>(scoreSource.value === 'best' ? 0 : (props.result.bad ?? 0));
const poor = ref<number>(scoreSource.value === 'best' ? 0 : (props.result.poor ?? 0));

// scoreSource が切り替えられたら編集値を復元する
watch(scoreSource, (src) => {
  if (src === 'best') {
    score.value = props.result.bestScore ?? 0;
    missCount.value = props.result.bestMissCount ?? 0;
    // djLevel は computed（score から自動再計算）なので代入不要
    clearType.value = props.result.bestClearType || 'CLEAR';
    pgreat.value = 0;
    great.value = 0;
    good.value = 0;
    bad.value = 0;
    poor.value = 0;
  } else {
    score.value = props.result.score ?? 0;
    missCount.value = props.result.missCount ?? 0;
    clearType.value = props.result.clearType || 'CLEAR';
    pgreat.value = props.result.pgreat ?? 0;
    great.value = props.result.great ?? 0;
    good.value = props.result.good ?? 0;
    bad.value = props.result.bad ?? 0;
    poor.value = props.result.poor ?? 0;
  }
});

// ── 曲名検索ボックス ──
const songSearchQuery = ref('');
const showSongPicker = ref(false);
const songSearchResults = computed<SongDataEntry[]>(() => {
  const q = songSearchQuery.value.trim().toLowerCase();
  if (q.length < 2) return [];
  const code = DIFF_CODES[difficulty.value];
  return songData.value
    .filter(s => s.difficulty === code && (s.title.toLowerCase().includes(q) || s.artist.toLowerCase().includes(q)))
    .slice(0, 20);
});

// 難易度を変えたら、現在選択中の曲を新しい難易度のエントリに差し替え
watch(difficulty, (newDiff) => {
  if (!selectedSong.value) return;
  const matched = songData.value.find(s => s.title === selectedSong.value!.title && s.difficulty === DIFF_CODES[newDiff]);
  if (matched) selectedSong.value = matched;
});

const pickSong = (s: SongDataEntry) => {
  selectedSong.value = s;
  showSongPicker.value = false;
  songSearchQuery.value = '';
};

// ── 検証 ──
/** SCORE = PGREAT*2 + GREAT が一致するかの整合性チェック。自己ベスト選択時は JUDGE 内訳が無いのでスキップ。 */
const scoreMatchesJudge = computed(() => {
  if (scoreSource.value === 'best') return true;
  const expected = pgreat.value * 2 + great.value;
  return expected === score.value;
});
const isValid = computed(() => !!selectedSong.value && score.value > 0);

// ── 確定 ──
/** ScoreData の空 DifficultyStats を作る。 */
const emptyStats = (): DifficultyStats => ({
  difficulty: null,
  score: 0,
  pgreat: 0,
  great: 0,
  missCount: null,
  clearType: 'NO PLAY',
  djLevel: '---',
});

/**
 * 確定値から未知 digit hash を学習辞書に追加する。
 *
 * 対応マッピング:
 *  - score.value × hashRecords.scoreCurrent または scoreBest（scoreSource に従う）
 *  - missCount.value × hashRecords.missCountCurrent または missCountBest（同上）
 *  - 選択された曲の notes × hashRecords.notes
 *  - pgreat.value × hashRecords.pgreat（今回プレイ時のみ）
 *
 * 未認識セル（左パディング）は学習対象外。組み込み辞書既知の hash は触らない。
 * 詳細は [[file:infinitasResultRecognizer.ts]] の `learnFromConfirmation` を参照。
 */
function applyAutoLearning(): void {
  const rec = props.result.hashRecords;
  if (!rec) return;
  const scoreRecord = scoreSource.value === 'best' ? rec.scoreBest : rec.scoreCurrent;
  const missRecord  = scoreSource.value === 'best' ? rec.missCountBest : rec.missCountCurrent;
  const learnedScore = learnFromConfirmation(scoreRecord, score.value, 'score');
  const learnedMiss  = learnFromConfirmation(missRecord, missCount.value, 'score');
  const learnedNotes = selectedSong.value?.notes
    ? learnFromConfirmation(rec.notes, selectedSong.value.notes, 'notes')
    : 0;
  // PGREAT は今回プレイ列のみ認識対象。自己ベスト選択時は pgreat=0 になるので学習しない。
  const learnedPg = scoreSource.value === 'best' ? 0 : learnFromConfirmation(rec.pgreat, pgreat.value, 'pgreat');
  if (learnedScore + learnedMiss + learnedNotes + learnedPg > 0) {
    console.info(`[infinitas-monitor] auto-learn: +${learnedScore} score / +${learnedMiss} miss / +${learnedNotes} notes / +${learnedPg} pgreat hashes`);
  }
}

const confirm = () => {
  if (!isValid.value || !selectedSong.value) return;
  applyAutoLearning();
  const stats: DifficultyStats = {
    difficulty: selectedSong.value.level ?? null,
    score: score.value,
    pgreat: pgreat.value,
    great: great.value,
    missCount: missCount.value,
    clearType: clearType.value,
    djLevel: djLevel.value,
  };
  // 1 曲・1 譜面分の ScoreData を組み立てる（他の難易度は空）
  const scoreData: ScoreData = {
    version: 'INFINITAS',
    title: selectedSong.value.title,
    genre: selectedSong.value.genre,
    artist: selectedSong.value.artist,
    playCount: 1,
    beginner: emptyStats(),
    normal: emptyStats(),
    hyper: emptyStats(),
    another: difficulty.value === 'ANOTHER' ? stats : emptyStats(),
    leggendaria: difficulty.value === 'LEGGENDARIA' ? stats : emptyStats(),
    lastPlayTime: new Date().toISOString(),
  };
  emit('confirm', scoreData);
};
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[70] flex items-center justify-center bg-slate-900/80 backdrop-blur-sm p-4 animate-fade-in" @click.self="emit('skip')">
      <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl w-full max-w-2xl overflow-hidden flex flex-col max-h-[92vh]">
        <!-- ヘッダ -->
        <div class="px-6 py-4 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between shrink-0">
          <div>
            <h2 class="text-lg font-black text-slate-900 dark:text-white">{{ t('infinitas.confirmTitle') }}</h2>
            <p class="text-xs text-slate-500 dark:text-slate-400 mt-0.5">{{ t('infinitas.confirmSubtitle') }}</p>
          </div>
          <button @click="emit('skip')" class="w-8 h-8 flex items-center justify-center rounded-lg text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
          </button>
        </div>

        <div class="p-6 space-y-4 overflow-y-auto">
          <!-- スナップショット -->
          <div v-if="result.snapshot" class="rounded-xl overflow-hidden border border-slate-200 dark:border-slate-700">
            <img :src="result.snapshot" alt="captured" class="w-full" />
          </div>

          <!-- スコアソース表示（今回プレイ vs 自己ベスト） -->
          <div v-if="hasBestData" class="p-3 rounded-xl border" :class="{
            'bg-blue-50 dark:bg-blue-950/40 border-blue-200 dark:border-blue-800': scoreSource === 'current',
            'bg-amber-50 dark:bg-amber-950/40 border-amber-200 dark:border-amber-800': scoreSource === 'best',
          }">
            <div class="flex items-center gap-2 mb-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 shrink-0" :class="scoreSource === 'current' ? 'text-blue-500' : 'text-amber-500'" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span class="text-xs font-bold" :class="scoreSource === 'current' ? 'text-blue-700 dark:text-blue-300' : 'text-amber-700 dark:text-amber-300'">
                {{ scoreSource === 'current' ? t('infinitas.usingCurrentPlay') : t('infinitas.usingBestScore') }}
              </span>
              <span v-if="isBestUpdated" class="ml-auto text-[10px] font-bold text-emerald-600 dark:text-emerald-400 bg-emerald-100 dark:bg-emerald-900/40 px-1.5 py-0.5 rounded-full">
                ★ {{ t('infinitas.bestUpdated') }}
              </span>
            </div>
            <!-- スコア比較テーブル -->
            <div class="grid grid-cols-3 gap-1 text-[11px] tabular-nums">
              <div></div>
              <div class="text-center font-bold" :class="scoreSource === 'current' ? 'text-blue-600 dark:text-blue-400' : 'text-slate-400 dark:text-slate-500'">{{ t('infinitas.current') }}</div>
              <div class="text-center font-bold" :class="scoreSource === 'best' ? 'text-amber-600 dark:text-amber-400' : 'text-slate-400 dark:text-slate-500'">{{ t('infinitas.best') }}</div>

              <div class="text-slate-500 dark:text-slate-400">EX SCORE</div>
              <div class="text-center" :class="scoreSource === 'current' ? 'font-bold text-slate-800 dark:text-slate-100' : 'text-slate-400 dark:text-slate-500'">{{ result.score ?? '-' }}</div>
              <div class="text-center" :class="scoreSource === 'best' ? 'font-bold text-slate-800 dark:text-slate-100' : 'text-slate-400 dark:text-slate-500'">{{ result.bestScore ?? '-' }}</div>

              <div class="text-slate-500 dark:text-slate-400">CLEAR</div>
              <div class="text-center" :class="scoreSource === 'current' ? 'font-bold text-slate-800 dark:text-slate-100' : 'text-slate-400 dark:text-slate-500'">{{ result.clearType || '-' }}</div>
              <div class="text-center" :class="scoreSource === 'best' ? 'font-bold text-slate-800 dark:text-slate-100' : 'text-slate-400 dark:text-slate-500'">{{ result.bestClearType || '-' }}</div>

              <div class="text-slate-500 dark:text-slate-400">DJ LV</div>
              <div class="text-center" :class="scoreSource === 'current' ? 'font-bold text-slate-800 dark:text-slate-100' : 'text-slate-400 dark:text-slate-500'">{{ result.djLevel || '-' }}</div>
              <div class="text-center" :class="scoreSource === 'best' ? 'font-bold text-slate-800 dark:text-slate-100' : 'text-slate-400 dark:text-slate-500'">{{ result.bestDjLevel || '-' }}</div>
            </div>
            <!-- ソース切り替えボタン -->
            <div class="mt-2 flex gap-2">
              <button
                @click="scoreSource = 'current'"
                class="flex-1 px-2 py-1.5 text-[11px] font-bold rounded-lg border transition-colors"
                :class="scoreSource === 'current'
                  ? 'bg-blue-600 border-blue-600 text-white'
                  : 'bg-white dark:bg-slate-800 border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700'"
              >{{ t('infinitas.current') }}</button>
              <button
                @click="scoreSource = 'best'"
                class="flex-1 px-2 py-1.5 text-[11px] font-bold rounded-lg border transition-colors"
                :class="scoreSource === 'best'
                  ? 'bg-amber-600 border-amber-600 text-white'
                  : 'bg-white dark:bg-slate-800 border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700'"
              >{{ t('infinitas.best') }}</button>
            </div>
          </div>

          <!-- 曲名 -->
          <div>
            <label class="text-xs font-bold text-slate-600 dark:text-slate-300">{{ t('infinitas.song') }}</label>
            <div class="mt-1">
              <div v-if="selectedSong" class="p-3 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/40 flex items-start gap-3">
                <div class="flex-1 min-w-0">
                  <p class="text-sm font-black text-slate-900 dark:text-white break-words">{{ selectedSong.title }}</p>
                  <p class="text-xs text-slate-600 dark:text-slate-400 truncate">{{ selectedSong.artist }}</p>
                </div>
                <button @click="showSongPicker = !showSongPicker" class="text-xs text-blue-600 dark:text-blue-400 hover:underline shrink-0">
                  {{ t('infinitas.changeSong') }}
                </button>
              </div>
              <div v-else class="p-3 rounded-xl border border-red-200 dark:border-red-800 bg-red-50 dark:bg-red-900/30">
                <p class="text-sm text-red-700 dark:text-red-300 font-medium">{{ t('infinitas.songNotMatched') }}</p>
                <!--
                  曲名 ROI の生画像。OCR で読めなくても視認で曲名がわかる。
                  将来 trie 構築する際の素材としても使える（タイトルが確定したらこの画像と紐付けて保存）。
                -->
                <img v-if="result.titleSnapshot" :src="result.titleSnapshot" alt="title snapshot" class="mt-2 w-full rounded border border-red-300 dark:border-red-700 bg-black/40" />
                <p class="text-[11px] text-red-600 dark:text-red-400 mt-0.5">OCR: "{{ result.ocrTitle }}"</p>
                <!--
                  (難易度, NOTES) で絞り込んだ候補があれば即座にクリック選択可能にする。
                  Fuse の文字列マッチが失敗してもここでカバーできるケースが大半。
                -->
                <div v-if="result.candidates.length > 0" class="mt-2 space-y-1">
                  <p class="text-[11px] font-bold text-red-700 dark:text-red-300">
                    {{ t('infinitas.candidatesFromChartInfo', { n: result.candidates.length, notes: result.notesCount ?? '?' }) }}
                  </p>
                  <button
                    v-for="c in result.candidates"
                    :key="`${c.title}|${c.artist}`"
                    @click="pickSong(c)"
                    class="w-full text-left px-3 py-2 rounded-lg bg-white dark:bg-slate-800 border border-red-200 dark:border-red-700 hover:bg-blue-50 dark:hover:bg-slate-700 transition-colors"
                  >
                    <p class="text-xs font-bold text-slate-800 dark:text-slate-100 truncate">{{ c.title }}</p>
                    <p class="text-[10px] text-slate-500 dark:text-slate-400 truncate">{{ c.artist }}</p>
                  </button>
                </div>
                <button @click="showSongPicker = true" class="mt-2 text-xs font-bold text-blue-600 dark:text-blue-400 hover:underline">
                  {{ t('infinitas.pickSongManually') }}
                </button>
              </div>

              <!-- 曲ピッカー -->
              <div v-if="showSongPicker" class="mt-2 p-3 border border-slate-200 dark:border-slate-700 rounded-xl bg-white dark:bg-slate-900">
                <input
                  v-model="songSearchQuery"
                  type="text"
                  :placeholder="t('infinitas.searchPlaceholder')"
                  class="w-full px-3 py-2 text-sm border border-slate-300 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100"
                />
                <div v-if="songSearchResults.length > 0" class="mt-2 max-h-48 overflow-y-auto space-y-1">
                  <button
                    v-for="s in songSearchResults"
                    :key="`${s.title}|${s.artist}|${s.difficulty}`"
                    @click="pickSong(s)"
                    class="w-full text-left px-3 py-2 rounded-lg hover:bg-blue-50 dark:hover:bg-slate-700 transition-colors"
                  >
                    <p class="text-sm font-bold text-slate-800 dark:text-slate-100 truncate">{{ s.title }}</p>
                    <p class="text-[11px] text-slate-500 dark:text-slate-400 truncate">{{ s.artist }}</p>
                  </button>
                </div>
                <p v-else-if="songSearchQuery.length >= 2" class="mt-2 text-xs text-slate-500 dark:text-slate-400">{{ t('infinitas.noResults') }}</p>
              </div>
            </div>
          </div>

          <!-- 難易度 + サイド -->
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="text-xs font-bold text-slate-600 dark:text-slate-300">{{ t('infinitas.difficulty') }}</label>
              <div class="mt-1 flex gap-2">
                <button
                  v-for="d in (['ANOTHER', 'LEGGENDARIA'] as const)"
                  :key="d"
                  @click="difficulty = d"
                  class="flex-1 px-3 py-2 text-xs font-bold rounded-lg border transition-colors"
                  :class="difficulty === d
                    ? 'bg-blue-600 border-blue-600 text-white'
                    : 'bg-white dark:bg-slate-800 border-slate-300 dark:border-slate-600 text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-700'"
                >{{ d }}</button>
              </div>
            </div>
            <div>
              <label class="text-xs font-bold text-slate-600 dark:text-slate-300">{{ t('infinitas.playSide') }}</label>
              <p class="mt-1 px-3 py-2 text-xs font-bold text-slate-500 dark:text-slate-400 bg-slate-100 dark:bg-slate-900/40 rounded-lg">
                {{ result.playSide || 'SP' }}
              </p>
            </div>
          </div>

          <!-- スコア / ミス -->
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="text-xs font-bold text-slate-600 dark:text-slate-300">EX SCORE</label>
              <input v-model.number="score" type="number" min="0" class="mt-1 w-full px-3 py-2 text-sm tabular-nums border border-slate-300 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-900 text-slate-800 dark:text-slate-100" />
            </div>
            <div>
              <label class="text-xs font-bold text-slate-600 dark:text-slate-300">MISS COUNT</label>
              <input v-model.number="missCount" type="number" min="0" class="mt-1 w-full px-3 py-2 text-sm tabular-nums border border-slate-300 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-900 text-slate-800 dark:text-slate-100" />
            </div>
          </div>

          <!-- DJ LEVEL / クリアタイプ -->
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="text-xs font-bold text-slate-600 dark:text-slate-300">DJ LEVEL</label>
              <!-- EX SCORE / (notes×2) から自動計算（編集不可）。曲を選び直すと再計算される。 -->
              <p class="mt-1 px-3 py-2 text-sm font-bold text-slate-800 dark:text-slate-100 bg-slate-100 dark:bg-slate-900/40 rounded-lg tabular-nums">
                {{ djLevel }}
              </p>
            </div>
            <div>
              <label class="text-xs font-bold text-slate-600 dark:text-slate-300">CLEAR TYPE</label>
              <select v-model="clearType" class="mt-1 w-full px-3 py-2 text-sm font-bold border border-slate-300 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-900 text-slate-800 dark:text-slate-100">
                <option v-for="ct in CLEAR_TYPE_OPTIONS" :key="ct" :value="ct">{{ ct }}</option>
              </select>
            </div>
          </div>

          <!-- JUDGE breakdown -->
          <div>
            <label class="text-xs font-bold text-slate-600 dark:text-slate-300">JUDGE</label>
            <div class="mt-1 grid grid-cols-5 gap-2">
              <div>
                <p class="text-[10px] text-slate-500">PG</p>
                <input v-model.number="pgreat" type="number" min="0" class="w-full px-2 py-1.5 text-xs tabular-nums border border-slate-300 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-900 text-slate-800 dark:text-slate-100" />
              </div>
              <div>
                <p class="text-[10px] text-slate-500">G</p>
                <input v-model.number="great" type="number" min="0" class="w-full px-2 py-1.5 text-xs tabular-nums border border-slate-300 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-900 text-slate-800 dark:text-slate-100" />
              </div>
              <div>
                <p class="text-[10px] text-slate-500">GD</p>
                <input v-model.number="good" type="number" min="0" class="w-full px-2 py-1.5 text-xs tabular-nums border border-slate-300 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-900 text-slate-800 dark:text-slate-100" />
              </div>
              <div>
                <p class="text-[10px] text-slate-500">BD</p>
                <input v-model.number="bad" type="number" min="0" class="w-full px-2 py-1.5 text-xs tabular-nums border border-slate-300 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-900 text-slate-800 dark:text-slate-100" />
              </div>
              <div>
                <p class="text-[10px] text-slate-500">PR</p>
                <input v-model.number="poor" type="number" min="0" class="w-full px-2 py-1.5 text-xs tabular-nums border border-slate-300 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-900 text-slate-800 dark:text-slate-100" />
              </div>
            </div>
            <p v-if="!scoreMatchesJudge" class="mt-1 text-[11px] text-amber-600 dark:text-amber-400 font-medium">
              {{ t('infinitas.judgeMismatch', { expected: pgreat * 2 + great }) }}
            </p>
          </div>
        </div>

        <!-- フッタ -->
        <div class="px-6 py-4 border-t border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/40 flex gap-3 shrink-0">
          <button @click="emit('skip')" class="flex-1 px-4 py-2.5 text-sm font-bold text-slate-700 dark:text-slate-200 bg-white dark:bg-slate-700 border border-slate-300 dark:border-slate-600 rounded-xl hover:bg-slate-50 dark:hover:bg-slate-600 transition-colors">
            {{ t('infinitas.skip') }}
          </button>
          <button
            @click="confirm"
            :disabled="!isValid"
            class="flex-1 px-4 py-2.5 text-sm font-bold text-white bg-gradient-to-r from-blue-600 to-indigo-600 rounded-xl shadow-lg shadow-blue-500/20 hover:shadow-blue-500/40 disabled:opacity-50 disabled:cursor-not-allowed transition-all"
          >
            {{ t('infinitas.register') }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
