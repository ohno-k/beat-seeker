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
import type { ConsistencyWarning } from '../composables/infinitasAutoImport';
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
  'NO PLAY',
  'FAILED',
  'ASSIST CLEAR',
  'EASY CLEAR',
  'CLEAR',
  'HARD CLEAR',
  'EX HARD CLEAR',
  'FULLCOMBO CLEAR',
];

// ── 編集用のローカル state ──
// INFINITAS 取り込みは常に今回プレイ(current)を登録する（自己ベスト送信は廃止済み）。
// リザルトの自己ベスト欄は取得元が曖昧で（アーケード等のベストが混ざり得る）、また確認時に
// INFINITAS を最小化してクリックする手間になるため、自己ベストとの比較表示は廃止した。
const selectedSong = ref<SongDataEntry | null>(props.result.songEntry);
const difficulty = ref<'ANOTHER' | 'LEGGENDARIA'>(props.result.difficulty || 'ANOTHER');
const score = ref<number>(props.result.score ?? 0);
const missCount = ref<number>(props.result.missCount ?? 0);
const clearType = ref<string>(props.result.clearType || 'CLEAR');
/**
 * DJ LEVEL は EX SCORE と最大スコア(notes×2)から計算する（OCR せず）。
 * notes は「特定できた曲の notes」を最優先し、無ければ認識した notesCount を使う。
 * 曲をユーザーが選び直すと selectedSong.notes 経由で自動的に再計算される。
 */
const djLevel = computed<string>(() => {
  const notes = selectedSong.value?.notes ?? props.result.notesCount ?? null;
  return computeDjLevel(score.value, notes) ?? '---';
});
// JUDGE 内訳は今回プレイのみ取得可能。
const pgreat = ref<number>(props.result.pgreat ?? 0);
const great = ref<number>(props.result.great ?? 0);
const good = ref<number>(props.result.good ?? 0);
const bad = ref<number>(props.result.bad ?? 0);
const poor = ref<number>(props.result.poor ?? 0);

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
/**
 * 認識時の整合性警告（① JUDGE クロスチェック / ② 妥当性ガード）を i18n メッセージへ変換する。
 * useInfinitasMonitor が抽出時に算出した {@link InfinitasResult.validationWarnings} をそのまま表示する。
 * 1 箇所の誤読が EX SCORE → great/djLevel に波及するため、ここで「どの値が怪しいか」をユーザーに明示する。
 */
const WARN_KEYS: Record<ConsistencyWarning['code'], string> = {
  'judge-mismatch': 'infinitas.warnJudgeMismatch',
  'over-max': 'infinitas.warnOverMax',
  'negative-great': 'infinitas.warnNegativeGreat',
  'miss-over-notes': 'infinitas.warnMissOverNotes',
};
const consistencyMessages = computed(() =>
  props.result.validationWarnings.map(w => t(WARN_KEYS[w.code], w.params)),
);

/** SCORE = PGREAT*2 + GREAT が一致するかの整合性チェック。 */
const scoreMatchesJudge = computed(() => {
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
 * 対応マッピング（常に今回プレイ列）:
 *  - score.value × hashRecords.scoreCurrent
 *  - missCount.value × hashRecords.missCountCurrent
 *  - 選択された曲の notes × hashRecords.notes
 *  - pgreat.value × hashRecords.pgreat
 *
 * 未認識セル（左パディング）は学習対象外。組み込み辞書既知の hash は触らない。
 * 詳細は [[file:infinitasResultRecognizer.ts]] の `learnFromConfirmation` を参照。
 */
function applyAutoLearning(): void {
  const rec = props.result.hashRecords;
  if (!rec) return;
  const learnedScore = learnFromConfirmation(rec.scoreCurrent, score.value, 'score');
  const learnedMiss  = learnFromConfirmation(rec.missCountCurrent, missCount.value, 'score');
  const learnedNotes = selectedSong.value?.notes
    ? learnFromConfirmation(rec.notes, selectedSong.value.notes, 'notes')
    : 0;
  const learnedPg = learnFromConfirmation(rec.pgreat, pgreat.value, 'pgreat');
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
      <div class="bg-white dark:bg-slate-800 rounded-md shadow-xl w-full max-w-2xl overflow-hidden flex flex-col max-h-[92vh]">
        <!-- ヘッダ -->
        <div class="px-6 py-4 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between shrink-0">
          <div>
            <h2 class="text-lg font-bold text-slate-900 dark:text-white">{{ t('infinitas.confirmTitle') }}</h2>
            <p class="text-xs text-slate-500 dark:text-slate-400 mt-0.5">{{ t('infinitas.confirmSubtitle') }}</p>
          </div>
          <button @click="emit('skip')" class="w-8 h-8 flex items-center justify-center rounded-lg text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
          </button>
        </div>

        <div class="p-6 space-y-4 overflow-y-auto">
          <!-- スナップショット -->
          <div v-if="result.snapshot" class="rounded-md overflow-hidden border border-slate-200 dark:border-slate-700">
            <img :src="result.snapshot" alt="captured" class="w-full" />
          </div>

          <!-- 整合性警告バナー（独立読み取りの不一致・妥当性 NG）。誤読の可能性が高い項目を明示する。 -->
          <div v-if="consistencyMessages.length" class="p-3 rounded-md border border-amber-300 dark:border-amber-700 bg-amber-50 dark:bg-amber-950/40">
            <div class="flex items-center gap-2 mb-1">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 shrink-0 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01M5.07 19h13.86a2 2 0 001.74-2.99l-6.93-12a2 2 0 00-3.48 0l-6.93 12A2 2 0 005.07 19z" />
              </svg>
              <span class="text-xs font-bold text-amber-700 dark:text-amber-300">{{ t('infinitas.warnTitle') }}</span>
            </div>
            <ul class="list-disc list-inside space-y-0.5">
              <li v-for="(m, i) in consistencyMessages" :key="i" class="text-[11px] text-amber-700 dark:text-amber-300">{{ m }}</li>
            </ul>
          </div>

          <!-- 曲名 -->
          <div>
            <label class="text-xs font-bold text-slate-600 dark:text-slate-300">{{ t('infinitas.song') }}</label>
            <div class="mt-1">
              <div v-if="selectedSong" class="p-3 rounded-md border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/40 flex items-start gap-3">
                <div class="flex-1 min-w-0">
                  <p class="text-sm font-bold text-slate-900 dark:text-white break-words">{{ selectedSong.title }}</p>
                  <p class="text-xs text-slate-600 dark:text-slate-400 truncate">{{ selectedSong.artist }}</p>
                </div>
                <button @click="showSongPicker = !showSongPicker" class="text-xs text-blue-600 dark:text-blue-400 hover:underline shrink-0">
                  {{ t('infinitas.changeSong') }}
                </button>
              </div>
              <div v-else class="p-3 rounded-md border border-red-200 dark:border-red-800 bg-red-50 dark:bg-red-900/30">
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
                <!--
                  候補が 1 件も無い = (難易度, NOTES) に一致する譜面が songData に存在しない。
                  AC 未収録（INFINITAS 限定）曲の可能性が高いのでその旨を明示する。
                -->
                <p v-else class="mt-2 text-[11px] font-bold text-red-700 dark:text-red-300">
                  {{ t('infinitas.maybeAcOnly', { notes: result.notesCount ?? '?' }) }}
                </p>
                <button @click="showSongPicker = true" class="mt-2 text-xs font-bold text-blue-600 dark:text-blue-400 hover:underline">
                  {{ t('infinitas.pickSongManually') }}
                </button>
              </div>

              <!-- 曲ピッカー -->
              <div v-if="showSongPicker" class="mt-2 p-3 border border-slate-200 dark:border-slate-700 rounded-md bg-white dark:bg-slate-900">
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
          <button @click="emit('skip')" class="flex-1 px-4 py-2.5 text-sm font-bold text-slate-700 dark:text-slate-200 bg-white dark:bg-slate-700 border border-slate-300 dark:border-slate-600 rounded-md hover:bg-slate-50 dark:hover:bg-slate-600 transition-colors">
            {{ t('infinitas.skip') }}
          </button>
          <button
            @click="confirm"
            :disabled="!isValid"
            class="flex-1 px-4 py-2.5 text-sm font-bold text-white bg-blue-700 hover:bg-blue-800 dark:bg-blue-600 dark:hover:bg-blue-500 rounded-md disabled:opacity-50 disabled:cursor-not-allowed transition-all"
          >
            {{ t('infinitas.register') }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
