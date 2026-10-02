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
import { mdiClose } from '@mdi/js';
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
  <v-dialog
    :model-value="open"
    max-width="448"
    @update:model-value="(v: boolean) => { if (!v) $emit('close') }"
  >
    <v-card aria-labelledby="rank-quiz-modal-title" class="w-full max-h-[90vh] overflow-y-auto">
      <!-- ヘッダ: タイトル + Lv/XP + 閉じる -->
      <v-card-item>
        <template #prepend>
          <v-avatar color="indigo" rounded size="36">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.539 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.196-1.539-1.118l1.518-4.674a1 1 0 00-.363-1.118l-3.976-2.888c-.784-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.518-4.674z" />
            </svg>
          </v-avatar>
        </template>
        <v-card-title id="rank-quiz-modal-title">非公式難易度クイズ</v-card-title>
        <div v-if="progress" class="flex items-center gap-2 mt-0.5">
          <span class="text-[11px] font-bold text-indigo-600 dark:text-indigo-400">Lv.{{ progress.level }}</span>
          <v-progress-linear
            :model-value="levelProgressPct"
            color="indigo"
            height="4"
            rounded
            class="flex-1"
          />
          <span class="text-[10px] font-mono text-slate-500 dark:text-slate-400 tabular-nums whitespace-nowrap">{{ progress.xp }}/{{ progress.xpForNextLevel }}</span>
        </div>
        <template #append>
          <v-btn
            icon
            variant="text"
            size="small"
            aria-label="閉じる"
            @click="$emit('close')"
          >
            <v-icon :icon="mdiClose" />
          </v-btn>
        </template>
      </v-card-item>
      <v-divider />

      <!-- 本体 -->
      <v-card-text>
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
            <v-chip v-if="sessionLeveledUp" label variant="flat" color="amber">
              🎉 LEVEL UP! Lv.{{ progress?.level }}
            </v-chip>
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
              <v-chip v-if="a.isReview" label size="x-small" color="purple">復習</v-chip>
            </li>
          </ul>
          <div class="flex gap-2 mt-5">
            <v-btn variant="tonal" size="large" class="flex-1" @click="$emit('close')">閉じる</v-btn>
            <v-btn color="primary" size="large" class="flex-1" @click="restart">もう1セット</v-btn>
          </div>
        </template>

        <!-- 出題 / 結果 -->
        <template v-else-if="currentQuestion">
          <!-- 進捗メーター -->
          <div class="flex items-center justify-between text-[10px] font-bold text-slate-500 dark:text-slate-400 mb-3">
            <span>問題 {{ currentIndex + 1 }} / {{ questions.length }}</span>
            <v-chip v-if="currentQuestion.isReview" label size="x-small" color="purple">復習問題 +{{ 20 }}XP</v-chip>
            <span v-else class="text-slate-400">新規問題 +{{ 10 }}XP</span>
          </div>

          <!-- 設問 -->
          <div class="text-center space-y-2 mb-4">
            <p class="text-[11px] font-bold text-slate-500 dark:text-slate-400">この譜面は ★いくつ？</p>
            <p class="text-xl font-bold text-slate-900 dark:text-white break-words leading-tight">{{ currentQuestion.title }}</p>
            <v-chip
              label
              size="x-small"
              :color="currentQuestion.difficultyName === 'LEGGENDARIA' ? 'purple' : 'red'"
            >{{ currentQuestion.difficultyName }}</v-chip>
          </div>

          <!-- 選択肢 -->
          <div class="grid grid-cols-2 gap-2">
            <!-- 回答後も色（正解=success / 誤答=error）を見せたいので disabled ではなく readonly でクリックを止める -->
            <v-btn
              v-for="ch in currentQuestion.choices"
              :key="ch"
              size="large"
              class="tabular-nums"
              :readonly="!!currentAnswered || isSubmitting"
              :variant="currentAnswered && (ch === currentQuestion.correctRank || ch === currentAnswered.chosenRank) ? 'flat' : 'outlined'"
              :color="!currentAnswered
                ? 'primary'
                : ch === currentQuestion.correctRank
                  ? 'success'
                  : ch === currentAnswered.chosenRank
                    ? 'error'
                    : undefined"
              @click="chooseAnswer(ch)"
            >★{{ ch }}</v-btn>
          </div>

          <!-- 結果フィードバック -->
          <v-alert
            v-if="currentAnswered"
            :type="currentAnswered.correct ? 'success' : 'error'"
            variant="tonal"
            class="mt-4"
          >
            <div class="flex items-center justify-between gap-2">
              <span>
                <template v-if="currentAnswered.correct">○ 正解！ +{{ currentAnswered.xpGained }} XP</template>
                <template v-else>× 正解は ★{{ currentAnswered.correctRank }}</template>
              </span>
              <v-chip v-if="lastResult?.leveledUp" label size="x-small" variant="flat" color="amber">
                🎉 Lv UP! → {{ lastResult.newLevel }}
              </v-chip>
            </div>
            <p class="text-[11px] font-medium mt-1">
              覚え方: <span class="font-bold">{{ currentAnswered.title }}{{ currentAnswered.difficultyName === 'LEGGENDARIA' ? '[L]' : '' }} = ★{{ currentAnswered.correctRank }}</span>
            </p>
            <v-btn color="primary" block class="mt-3" @click="next">{{ currentIndex < questions.length - 1 ? '次の問題 →' : '結果を見る →' }}</v-btn>
          </v-alert>
        </template>

        <!-- 進捗ロード中等の保険 -->
        <div v-else class="text-center py-10 text-sm text-slate-400">
          読み込み中...
        </div>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>
