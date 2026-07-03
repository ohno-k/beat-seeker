<script setup lang="ts">
/**
 * 【コンポーネントの役割】 「非公式難易度クイズ」のセッション UI（5 問完結）。
 *
 * 流れ:
 *  - open=true で問題セットを構築（復習プールから 0〜2 問 + 自分のプレイ済みから残り）
 *  - 1 問ごとに 4 択（正解 ± 0.1〜0.4 の近傍ランク）から選ぶ
 *  - 回答送信 API で XP/Lv をサーバーに永続化
 *  - 5 問終わるとサマリー画面（正答率・獲得 XP・LV UP 通知）
 *
 * 役割の分担:
 *  - 進捗の永続化と復習プール提供 → {@link useRankQuiz}（サーバー呼び出し）
 *  - 問題生成・出題順制御・UI → このコンポーネント（クライアントロジック）
 */
import { ref, computed, watch, inject, onMounted } from 'vue';
import type { Ref } from 'vue';
import { useRankQuiz, type ReviewPoolItem } from '../composables/useRankQuiz';
import { useModalEscape } from '../composables/useModalEscape';
import { flattenScores, type ScoreRecord } from '../utils/scoreData';
import type { ScoreData } from '../types/ScoreData';

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{ (e: 'close'): void }>();

const { progress, fetchProgress, submitAnswer, levelProgressPct } = useRankQuiz();

useModalEscape(() => props.open, () => emit('close'));

// App.vue が provide した scoreData。クイズの「自分が触った譜面」プールに使う。
const scoreData = inject<Ref<ScoreData[]>>('scoreData', undefined as any);

// ── 問題定義 ────────────────────────────────────────────────
interface Question {
  title: string;
  difficultyName: string;
  correctRank: string;
  choices: string[];
  isReview: boolean;
}

interface AnsweredQuestion extends Question {
  chosenRank: string;
  correct: boolean;
  xpGained: number;
}

const SESSION_SIZE = 5;
const MAX_REVIEW_PER_SESSION = 2;

const questions = ref<Question[]>([]);
const currentIndex = ref(0);
const answered = ref<AnsweredQuestion[]>([]);
const lastResult = ref<{ correct: boolean; xpGained: number; leveledUp: boolean; oldLevel: number; newLevel: number } | null>(null);
const isSubmitting = ref(false);
const sessionFinished = ref(false);
const initError = ref('');

const currentQuestion = computed<Question | null>(() => {
  if (currentIndex.value >= questions.value.length) return null;
  return questions.value[currentIndex.value];
});

const currentAnswered = computed<AnsweredQuestion | null>(() => {
  return answered.value[currentIndex.value] ?? null;
});

/** モーダルが開かれたら（または scoreData が変わったら）状態リセット＋問題構築。 */
watch(() => props.open, async (open) => {
  if (open) {
    initError.value = '';
    isSubmitting.value = false;
    sessionFinished.value = false;
    answered.value = [];
    lastResult.value = null;
    currentIndex.value = 0;
    // 最新進捗 + 復習プール取得
    await fetchProgress();
    questions.value = buildQuestions();
    if (questions.value.length === 0) {
      initError.value = '対象曲（ANOTHER / LEGGENDARIA で非公式難易度のあるプレイ済譜面）が見つかりません。CSV をアップロードしてからプレイしてください。';
    }
  }
});

onMounted(async () => {
  if (props.open) {
    await fetchProgress();
    questions.value = buildQuestions();
  }
});

/**
 * 【関数】 セッション用の問題セットを構築する。
 *  - 復習プールから最大 {@link MAX_REVIEW_PER_SESSION} 問を抽出（mistakeCount が多い順 → ランダム）
 *  - 残りはユーザーがプレイ済かつ informalRank を持つ曲からランダム
 *  - 合計 {@link SESSION_SIZE} 問を超えない（足りない場合はあるだけ）
 */
function buildQuestions(): Question[] {
  const pool: Question[] = [];

  // 1) 復習プールから採用
  const reviewItems = (progress.value?.reviewPool ?? [])
    .slice() // copy
    .filter(r => isNumericRank(r.correctRank));
  // mistakeCount 多い順に並べた上で、上位 8 件からランダム抽出する
  reviewItems.sort((a, b) => (b.mistakeCount ?? 0) - (a.mistakeCount ?? 0));
  const reviewCandidates = reviewItems.slice(0, 8);
  shuffle(reviewCandidates);
  const reviewPicked = reviewCandidates.slice(0, Math.min(MAX_REVIEW_PER_SESSION, reviewCandidates.length));
  for (const r of reviewPicked) {
    pool.push({
      title: r.title,
      difficultyName: r.difficultyName,
      correctRank: r.correctRank,
      choices: buildChoices(r.correctRank),
      isReview: true,
    });
  }

  // 復習で使った曲は新規候補から除外したいので Set 化
  const reviewKeySet = new Set(reviewPicked.map(r => `${r.title}|${r.difficultyName}`));

  // 2) 残りはプレイ済からランダム
  const remaining = SESSION_SIZE - pool.length;
  if (remaining > 0) {
    const flat = scoreData?.value ? flattenScores(scoreData.value) : [];
    const candidates = flat.filter((s: ScoreRecord) => {
      if (!s.informalRank || !isNumericRank(s.informalRank)) return false;
      if (s.difficultyName !== 'ANOTHER' && s.difficultyName !== 'LEGGENDARIA') return false;
      const k = `${s.title}|${s.difficultyName}`;
      if (reviewKeySet.has(k)) return false;
      return true;
    });
    shuffle(candidates);
    const newPicked = candidates.slice(0, remaining);
    for (const c of newPicked) {
      pool.push({
        title: c.title,
        difficultyName: c.difficultyName,
        correctRank: c.informalRank!,
        choices: buildChoices(c.informalRank!),
        isReview: false,
      });
    }
  }

  // 全体をシャッフル（復習が固まらないように）
  shuffle(pool);
  return pool;
}

/**
 * 正解ランクから 4 択を組み立てる。
 *  - 正解 + 近傍 ±0.1〜±0.4 の中からダミー 3 つ（重複なく、11.0〜13.0 内）
 *  - 足りない場合は ±0.5〜0.7 を許容
 */
function buildChoices(correct: string): string[] {
  const c = parseFloat(correct);
  if (!Number.isFinite(c)) return [correct];
  const offsets = [-0.4, -0.3, -0.2, -0.1, 0.1, 0.2, 0.3, 0.4];
  shuffle(offsets);
  const set = new Set<string>([correct]);
  for (const o of offsets) {
    if (set.size >= 4) break;
    const v = +(c + o).toFixed(1);
    if (v < 11.0 || v > 13.0) continue;
    set.add(v.toFixed(1));
  }
  // それでも足りなければ広く取る
  let extra = 0.5;
  while (set.size < 4 && extra <= 1.5) {
    for (const sign of [-1, 1]) {
      if (set.size >= 4) break;
      const v = +(c + sign * extra).toFixed(1);
      if (v >= 11.0 && v <= 13.0) set.add(v.toFixed(1));
    }
    extra += 0.1;
  }
  const arr = [...set];
  shuffle(arr);
  return arr;
}

/** 数値ランクか判定。"個人差A" のようなラベルは除外。 */
function isNumericRank(r: string): boolean {
  const n = parseFloat(r);
  return Number.isFinite(n);
}

/** Fisher–Yates シャッフル（in-place）。 */
function shuffle<T>(arr: T[]): T[] {
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
  return arr;
}

/**
 * 選択肢押下ハンドラ。サーバーに回答送信し、結果を内部状態に反映する。
 */
async function chooseAnswer(choice: string) {
  const q = currentQuestion.value;
  if (!q || isSubmitting.value || currentAnswered.value) return;

  isSubmitting.value = true;
  const oldLevel = progress.value?.level ?? 1;
  const result = await submitAnswer({
    title: q.title,
    difficultyName: q.difficultyName,
    correctRank: q.correctRank,
    chosenRank: choice,
    isReview: q.isReview,
  });
  isSubmitting.value = false;

  if (!result) {
    // 通信失敗時はクライアント側だけで判定して継続（XP は加算しない）
    const correct = choice === q.correctRank;
    answered.value[currentIndex.value] = {
      ...q,
      chosenRank: choice,
      correct,
      xpGained: 0,
    };
    lastResult.value = { correct, xpGained: 0, leveledUp: false, oldLevel, newLevel: oldLevel };
    return;
  }

  answered.value[currentIndex.value] = {
    ...q,
    chosenRank: choice,
    correct: result.correct,
    xpGained: result.xpGained,
  };
  lastResult.value = {
    correct: result.correct,
    xpGained: result.xpGained,
    leveledUp: result.leveledUp,
    oldLevel,
    newLevel: result.level,
  };
}

/** 「次の問題」進む。最後の問題ならサマリーへ。 */
function next() {
  if (currentIndex.value < questions.value.length - 1) {
    currentIndex.value++;
    lastResult.value = null;
  } else {
    sessionFinished.value = true;
  }
}

/** もう 1 セットやる。 */
async function restart() {
  await fetchProgress();
  questions.value = buildQuestions();
  currentIndex.value = 0;
  answered.value = [];
  lastResult.value = null;
  sessionFinished.value = false;
}

const correctCount = computed(() => answered.value.filter(a => a?.correct).length);
const xpGainedTotal = computed(() => answered.value.reduce((s, a) => s + (a?.xpGained ?? 0), 0));
const startLevel = ref<number | null>(null);
watch(() => props.open, (open) => {
  if (open && startLevel.value === null && progress.value) {
    startLevel.value = progress.value.level;
  } else if (!open) {
    startLevel.value = null;
  }
});

const sessionLeveledUp = computed(() => {
  if (startLevel.value == null || !progress.value) return false;
  return progress.value.level > startLevel.value;
});
</script>

<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition-opacity duration-200"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition-opacity duration-150"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div
        v-if="open"
        role="dialog"
        aria-modal="true"
        aria-labelledby="rank-quiz-modal-title"
        class="fixed inset-0 z-[100] flex items-center justify-center p-4"
      >
        <div class="absolute inset-0 bg-slate-900/50 backdrop-blur-sm" @click="$emit('close')"></div>

        <div class="relative bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 shadow-xl max-w-md w-full max-h-[90vh] overflow-y-auto">
          <!-- ヘッダ: タイトル + Lv/XP + 閉じる -->
          <div class="px-5 py-4 border-b border-slate-100 dark:border-slate-700 flex items-center gap-3">
            <div class="w-9 h-9 bg-indigo-600 rounded-md flex items-center justify-center text-white shrink-0">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.539 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.196-1.539-1.118l1.518-4.674a1 1 0 00-.363-1.118l-3.976-2.888c-.784-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.518-4.674z" />
              </svg>
            </div>
            <div class="flex-1 min-w-0">
              <h3 id="rank-quiz-modal-title" class="text-sm font-bold text-slate-800 dark:text-slate-100">非公式難易度クイズ</h3>
              <div v-if="progress" class="flex items-center gap-2 mt-0.5">
                <span class="text-[11px] font-bold text-indigo-600 dark:text-indigo-400">Lv.{{ progress.level }}</span>
                <div class="flex-1 h-1 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
                  <div class="h-full bg-indigo-500 dark:bg-indigo-400 rounded-full transition-all" :style="{ width: levelProgressPct + '%' }"></div>
                </div>
                <span class="text-[10px] font-mono text-slate-500 dark:text-slate-400 tabular-nums whitespace-nowrap">{{ progress.xp }}/{{ progress.xpForNextLevel }}</span>
              </div>
            </div>
            <button
              type="button"
              @click="$emit('close')"
              aria-label="閉じる"
              class="p-1.5 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition-colors"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>

          <!-- 本体 -->
          <div class="p-5">
            <!-- エラー or 空状態 -->
            <div v-if="initError" class="text-center py-10 text-sm text-slate-500 dark:text-slate-400">
              {{ initError }}
            </div>

            <!-- セッションサマリー -->
            <template v-else-if="sessionFinished">
              <div class="text-center space-y-3">
                <div class="inline-flex items-center justify-center w-14 h-14 rounded-full bg-emerald-100 dark:bg-emerald-900/40">
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-7 w-7 text-emerald-600 dark:text-emerald-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
                  </svg>
                </div>
                <h4 class="text-lg font-bold text-slate-800 dark:text-slate-100">セッション完了！</h4>
                <p class="text-sm text-slate-500 dark:text-slate-400">
                  正答 <span class="font-bold text-slate-800 dark:text-slate-100">{{ correctCount }}/{{ questions.length }}</span>
                  ・ 獲得 <span class="font-bold text-amber-500">+{{ xpGainedTotal }} XP</span>
                </p>
                <div v-if="sessionLeveledUp" class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded bg-amber-500 text-white font-bold text-xs">
                  🎉 LEVEL UP! Lv.{{ progress?.level }}
                </div>
              </div>
              <!-- 復習サマリー -->
              <ul class="mt-5 space-y-1.5 text-xs">
                <li
                  v-for="(a, i) in answered"
                  :key="i"
                  class="flex items-center gap-2 px-2 py-1.5 rounded-lg"
                  :class="a.correct ? 'bg-emerald-50/60 dark:bg-emerald-900/20' : 'bg-rose-50/60 dark:bg-rose-900/20'"
                >
                  <span :class="a.correct ? 'text-emerald-600 dark:text-emerald-400' : 'text-rose-600 dark:text-rose-400'" class="font-bold w-4 text-center">
                    {{ a.correct ? '○' : '×' }}
                  </span>
                  <span class="font-bold text-slate-700 dark:text-slate-200 truncate flex-1">
                    {{ a.title }}<span class="text-[10px] text-slate-400 ml-1">{{ a.difficultyName === 'LEGGENDARIA' ? '[L]' : '' }}</span>
                  </span>
                  <span class="font-bold text-slate-700 dark:text-slate-200 tabular-nums">★{{ a.correctRank }}</span>
                  <span v-if="!a.correct" class="text-[10px] text-rose-500 tabular-nums">→ ★{{ a.chosenRank }}</span>
                  <span v-if="a.isReview" class="text-[8px] font-bold px-1 py-px rounded bg-purple-100 dark:bg-purple-900/40 text-purple-700 dark:text-purple-300">復習</span>
                </li>
              </ul>
              <div class="flex gap-2 mt-5">
                <button
                  @click="$emit('close')"
                  class="flex-1 px-4 py-2.5 rounded-md text-sm font-bold text-slate-600 dark:text-slate-300 bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
                >閉じる</button>
                <button
                  @click="restart"
                  class="flex-1 px-4 py-2.5 rounded-md text-sm font-bold text-white bg-blue-700 hover:bg-blue-800 dark:bg-blue-600 dark:hover:bg-blue-500 transition-all"
                >もう1セット</button>
              </div>
            </template>

            <!-- 出題 / 結果 -->
            <template v-else-if="currentQuestion">
              <!-- 進捗メーター -->
              <div class="flex items-center justify-between text-[10px] font-bold text-slate-500 dark:text-slate-400 mb-3">
                <span>問題 {{ currentIndex + 1 }} / {{ questions.length }}</span>
                <span v-if="currentQuestion.isReview" class="px-1.5 py-0.5 rounded bg-purple-100 dark:bg-purple-900/40 text-purple-700 dark:text-purple-300 text-[9px] font-bold">復習問題 +{{ 20 }}XP</span>
                <span v-else class="text-slate-400">新規問題 +{{ 10 }}XP</span>
              </div>

              <!-- 設問 -->
              <div class="text-center space-y-2 mb-4">
                <p class="text-[11px] font-bold text-slate-500 dark:text-slate-400">この譜面は ★いくつ？</p>
                <p class="text-xl font-bold text-slate-900 dark:text-white break-words leading-tight">{{ currentQuestion.title }}</p>
                <span
                  class="inline-block px-2.5 py-0.5 rounded-md text-[10px] font-bold"
                  :class="currentQuestion.difficultyName === 'LEGGENDARIA'
                    ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300'
                    : 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300'"
                >{{ currentQuestion.difficultyName }}</span>
              </div>

              <!-- 選択肢 -->
              <div class="grid grid-cols-2 gap-2">
                <button
                  v-for="ch in currentQuestion.choices"
                  :key="ch"
                  type="button"
                  :disabled="!!currentAnswered || isSubmitting"
                  @click="chooseAnswer(ch)"
                  class="px-4 py-3 rounded-md text-base font-bold tabular-nums border-2 transition-all"
                  :class="[
                    !currentAnswered
                      ? 'bg-white dark:bg-slate-700 border-slate-200 dark:border-slate-600 text-slate-800 dark:text-slate-100 hover:border-indigo-400 hover:bg-indigo-50 dark:hover:bg-indigo-900/20'
                      : ch === currentQuestion.correctRank
                        ? 'bg-emerald-50 dark:bg-emerald-900/30 border-emerald-400 text-emerald-700 dark:text-emerald-300'
                        : ch === currentAnswered.chosenRank
                          ? 'bg-rose-50 dark:bg-rose-900/30 border-rose-400 text-rose-700 dark:text-rose-300'
                          : 'bg-slate-50 dark:bg-slate-700/50 border-slate-200 dark:border-slate-600 text-slate-400 dark:text-slate-500'
                  ]"
                >★{{ ch }}</button>
              </div>

              <!-- 結果フィードバック -->
              <div v-if="currentAnswered" class="mt-4 px-3 py-3 rounded-md text-sm font-bold"
                :class="currentAnswered.correct
                  ? 'bg-emerald-50 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-300'
                  : 'bg-rose-50 dark:bg-rose-900/30 text-rose-700 dark:text-rose-300'"
              >
                <div class="flex items-center justify-between gap-2">
                  <span>
                    <template v-if="currentAnswered.correct">○ 正解！ +{{ currentAnswered.xpGained }} XP</template>
                    <template v-else>× 正解は ★{{ currentAnswered.correctRank }}</template>
                  </span>
                  <span v-if="lastResult?.leveledUp" class="text-[10px] font-bold px-2 py-0.5 rounded bg-amber-400 text-white">
                    🎉 Lv UP! → {{ lastResult.newLevel }}
                  </span>
                </div>
                <p class="text-[11px] font-medium mt-1 text-slate-600 dark:text-slate-300">
                  覚え方: <span class="font-bold text-slate-800 dark:text-slate-100">{{ currentAnswered.title }}{{ currentAnswered.difficultyName === 'LEGGENDARIA' ? '[L]' : '' }} = ★{{ currentAnswered.correctRank }}</span>
                </p>
                <button
                  type="button"
                  @click="next"
                  class="mt-3 w-full px-4 py-2 rounded-lg text-sm font-bold text-white bg-blue-700 hover:bg-blue-800 dark:bg-blue-600 dark:hover:bg-blue-500 transition-all"
                >{{ currentIndex < questions.length - 1 ? '次の問題 →' : '結果を見る →' }}</button>
              </div>
            </template>

            <!-- 進捗ロード中等の保険 -->
            <div v-else class="text-center py-10 text-sm text-slate-400">
              読み込み中...
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
