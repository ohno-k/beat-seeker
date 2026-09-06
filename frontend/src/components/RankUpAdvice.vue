<template>
  <!-- Rank up suggestion panel -->
  <div
    v-if="nextRankGap > 0"
    class="bg-white dark:bg-slate-800 p-4 sm:p-6 rounded-md border border-slate-200 dark:border-slate-700 transition-colors duration-200"
  >
    <div class="flex items-center justify-between mb-1">
      <h3 class="text-sm sm:text-base font-bold text-slate-800 dark:text-slate-100 flex items-center gap-2">
        <svg class="w-4 h-4 text-blue-500 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
        </svg>
        {{ t('advice.title') }}
      </h3>
    </div>
    <p class="text-xs text-slate-400 dark:text-slate-500 mb-1">
      {{ t('advice.remaining', { n: nextRankGap.toFixed(1) }) }}
    </p>
    <p class="text-[10px] text-slate-400 dark:text-slate-500 mb-4">
      {{ t('advice.basedOnExpectedValue') }}
    </p>

    <div v-if="isLoading" class="text-center py-6 text-slate-400 dark:text-slate-500 text-sm">
      {{ t('advice.computingPotential') }}
    </div>
    <div v-else-if="loadError" class="text-center py-6 text-rose-500 text-xs">
      {{ t('advice.potentialError', { msg: loadError }) }}
    </div>
    <div v-else-if="suggestions.length === 0" class="text-center py-6 text-slate-400 dark:text-slate-500 text-sm">
      {{ t('advice.noSuggestions') }}
    </div>
    <div v-else class="space-y-2">
      <div
        v-for="(sug, i) in visibleSuggestions"
        :key="`${sug.title}|${sug.difficultyName}`"
        class="flex items-center gap-2 p-2 sm:p-3 rounded-md border transition-colors"
        :class="sug.unplayed
          ? 'bg-blue-50/50 dark:bg-blue-900/10 border-blue-100 dark:border-blue-900/50'
          : 'bg-slate-50/50 dark:bg-slate-700/20 border-slate-100 dark:border-slate-700/50'"
      >
        <span class="text-[10px] font-bold text-slate-400 dark:text-slate-500 shrink-0 w-5 text-right">{{ i + 1 }}</span>
        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-1.5 min-w-0">
            <p class="font-bold text-slate-800 dark:text-slate-200 text-xs sm:text-sm truncate">{{ sug.title }}</p>
            <InformalRankBadge :rank="sug.informalRank" size="xs" class="shrink-0" />
            <span
              v-if="sug.unplayed"
              class="shrink-0 text-[9px] font-bold px-1 py-px rounded bg-blue-500 text-white"
            >{{ t('advice.unplayedTag') }}</span>
            <!-- ペア回帰の参照が無く、加法モデルで概算した候補。σ が大きいので達成率も控えめに出ている -->
            <span
              v-if="isRough(sug.accuracy)"
              class="shrink-0 text-[9px] font-bold px-1 py-px rounded bg-slate-200 dark:bg-slate-600 text-slate-600 dark:text-slate-300"
              :title="accuracyLabel(sug.accuracy)"
            >{{ t('advice.roughTag') }}</span>
          </div>
          <p class="text-[10px] text-slate-500 dark:text-slate-400">
            {{ sug.difficultyName }} /
            <template v-if="sug.unplayed">{{ t('advice.notPlayedYet') }}</template>
            <template v-else>{{ t('common.current') }} {{ sug.currentBeatPt.toFixed(1) }} pt</template>
          </p>
        </div>
        <div class="text-right shrink-0" :title="t('advice.supportHint', { n: sug.supportCount, acc: accuracyLabel(sug.accuracy) })">
          <p v-if="sug.targetLabel" class="text-[10px] font-bold text-blue-500 dark:text-blue-400">
            {{ t('advice.targetBorder', { label: sug.targetLabel }) }}
          </p>
          <p class="text-xs font-bold text-slate-700 dark:text-slate-200">
            {{ t('advice.targetScore', { n: sug.targetScore.toLocaleString() }) }}
          </p>
          <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500">→ {{ sug.targetRate.toFixed(2) }}%</p>
          <p class="text-[10px] font-bold" :class="probabilityClass(sug.achieveProbability)">
            {{ t('advice.achieveProbability', { p: Math.round(sug.achieveProbability * 100) }) }}
          </p>
          <p class="text-[10px] font-bold text-emerald-600 dark:text-emerald-400">
            {{ t('advice.expectedGain', { n: sug.expectedGain.toFixed(1) }) }}
          </p>
        </div>
      </div>

      <!-- 10 件ずつ追加表示。候補は次ランクに届くまで並んでいるので、下まで開けば必要な曲がすべて見える -->
      <button
        v-if="hiddenCount > 0"
        type="button"
        @click="showMore"
        class="w-full py-2 text-xs font-semibold rounded-md border border-dashed border-slate-300 dark:border-slate-600 text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-700/40 hover:text-slate-700 dark:hover:text-slate-200 transition-colors"
      >
        {{ t('advice.showMore', { n: hiddenCount }) }}
      </button>
    </div>

    <!-- Total summary -->
    <div
      v-if="suggestions.length > 0"
      class="mt-4 p-3 rounded-md border-2 flex items-center justify-between"
      :class="totalExpectedGain >= nextRankGap
        ? 'bg-emerald-50 dark:bg-emerald-900/20 border-emerald-300 dark:border-emerald-700'
        : 'bg-amber-50 dark:bg-amber-900/20 border-amber-300 dark:border-amber-700'"
    >
      <div>
        <p class="text-[10px] font-bold"
          :class="totalExpectedGain >= nextRankGap ? 'text-emerald-600 dark:text-emerald-400' : 'text-amber-600 dark:text-amber-400'">
          {{ totalExpectedGain >= nextRankGap ? t('common.achievable') : t('common.shortfall') }}
        </p>
        <p class="text-lg font-bold"
          :class="totalExpectedGain >= nextRankGap ? 'text-emerald-700 dark:text-emerald-300' : 'text-amber-700 dark:text-amber-300'">
          {{ t('advice.expectedTotal') }} +{{ totalExpectedGain.toFixed(1) }} pt
        </p>
      </div>
      <div class="text-right text-xs font-bold text-slate-500 dark:text-slate-400">
        <p>{{ t('advice.goal') }}</p>
        <p class="text-sm font-bold text-slate-700 dark:text-slate-200">+{{ nextRankGap.toFixed(1) }} pt</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 次のティアまでの残り pt を、達成率の高い順に「どれを埋めれば埋まるか」で提示するパネル。
 *
 * バックエンドの `/api/analysis/fill-recommendation`（コスパ埋めレコメンド）が
 * 全譜面（未プレイ含む）について
 *   期待獲得 pt = E[ max(0, BEAT-PT(到達スコア) − 押し出しライン) ]
 * と達成率を算出し、達成率降順に「残り pt を満たすまで」を返してくる。
 * 本コンポーネントは残り pt（gap）を渡して結果を 10 件ずつ表示するだけを担う。
 *
 * 【枯渇対策】
 * バックエンドはペア回帰の参照が無い譜面も加法モデル（実力 + 譜面効果）で予測するので、
 * 難易度表に載っている譜面は原則すべて候補になる。概算の候補は `accuracy` が BASE / RANK で返り、
 * 「概算」バッジを付けて区別する。
 *
 * 【旧実装との違い】
 * 以前は「伸びしろ API の予測スコア = 確実に出せる上限」と決め打ちし、フロント側で
 * ボーダー探索と TOP100 判定をやっていた。そのため
 *  - 自分がすでに A 以上で出している譜面しか提案できず、「埋め」の提案ができない
 *  - 予測が外れる確率を無視するので、実際には取れない譜面が上位に来る
 * という 2 点が弱かった。判定はすべてバックエンドの期待値計算に寄せている。
 *
 * @prop totalPoints 現在の Beat-PT 合計。次ランクまでの gap 計算に使用。
 */
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useAuth } from '../composables/useAuth';
import { getNextRankInfo } from '../utils/beatTier';
import InformalRankBadge from './InformalRankBadge.vue';

const { t } = useI18n();
const { authHeaders } = useAuth();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

const props = defineProps<{
  totalPoints: number;
}>();

/** 一度に見せる件数。「さらに表示」を押すたびにこの数だけ増える。 */
const PAGE_SIZE = 10;
/** 現在表示している件数。取得し直したら先頭の 1 ページに戻す。 */
const visibleCount = ref(PAGE_SIZE);

/** 予測の出どころ。HIGH / LOW はペア回帰、BASE / RANK は加法モデルによる概算。 */
type Accuracy = 'HIGH' | 'LOW' | 'BASE' | 'RANK';

/** 概算系（ペア回帰の参照が無い）かどうか。バッジ表示の判定に使う。 */
function isRough(acc: Accuracy): boolean {
  return acc === 'BASE' || acc === 'RANK';
}

/** ツールチップ用の精度ラベル。 */
function accuracyLabel(acc: Accuracy): string {
  switch (acc) {
    case 'HIGH': return t('advice.accuracyHigh');
    case 'LOW': return t('advice.accuracyLow');
    case 'BASE': return t('advice.accuracyBase');
    case 'RANK': return t('advice.accuracyRank');
    default: return String(acc);
  }
}

/** `/api/analysis/fill-recommendation` の items 1 件ぶん。 */
interface FillRecommendationItem {
  title: string;
  difficultyName: string;
  informalRank: string;
  difficultyLevel: number;
  /** 未プレイ譜面（＝純粋な「埋め」候補）なら true。 */
  unplayed: boolean;
  currentScore: number;
  currentRate: number;
  currentBeatPt: number;
  inTop100: boolean;
  maxScore: number;
  predictedScore: number;
  predictedRate: number;
  /** 予測のばらつき（スコアレート % 換算の 1σ）。 */
  sigmaRate: number;
  /** 損益分岐スコア。ここを超えて初めて総合 BEAT-PT が増える。 */
  breakEvenScore: number;
  /** P(損益分岐スコア以上を出せる | 推定能力)。 */
  achieveProbability: number;
  targetScore: number;
  targetRate: number;
  /** 'AA' / 'AAA' / 'MAX-'。狙えるボーダーが無ければ空文字。 */
  targetLabel: string;
  targetProbability: number;
  targetGain: number;
  /** 期待獲得 pt。この降順で返ってくる。 */
  expectedGain: number;
  supportCount: number;
  accuracy: Accuracy;
}

const items = ref<FillRecommendationItem[]>([]);
const isLoading = ref(false);
const loadError = ref('');

/** 取得中に再取得を要求されたら、完了後に 1 回だけ追いかけるためのフラグ。 */
let pendingRefetch = false;

/** バックエンドがキャッシュ構築中（503）のときの再試行タイマー。アンマウント時に止める。 */
let retryTimer: ReturnType<typeof setTimeout> | null = null;
/** 503 での再試行回数。上限を超えたらエラー表示に切り替える。 */
let buildingRetries = 0;
/** 503 再試行の上限。15 秒 × 12 = 約 3 分。本番の構築は数十秒なので通常 1〜3 回で抜ける。 */
const MAX_BUILDING_RETRIES = 12;
/** 503 の応答に retryAfterSec が無いときの待ち秒数。 */
const DEFAULT_RETRY_AFTER_SEC = 15;

/**
 * コスパ埋めレコメンドを取得する。
 * ダッシュボード表示直後は合計 pt が数回入れ替わるため、取得中の要求は 1 回にまとめる。
 *
 * 次ランクまでの残り pt（gap）を渡すと、バックエンドは達成率降順にその差分を満たすまで返す。
 * フロントの BEAT-PT 計算と同じ値を渡すので、表示している「あと n pt」と候補の範囲が一致する。
 *
 * バックエンドのペア回帰キャッシュは起動直後や日次再構築の間は未完成で、その間 API は
 * 503 + retryAfterSec を返す。その場合は「計算中」の表示のまま待って取り直す。
 */
async function fetchRecommendation() {
  if (isLoading.value) {
    pendingRefetch = true;
    return;
  }
  isLoading.value = true;
  loadError.value = '';
  let waitingForBuild = false;
  try {
    const params = new URLSearchParams({ gap: nextRankGap.value.toFixed(2) });
    const res = await fetch(`${API_BASE}/api/analysis/fill-recommendation?${params}`, { headers: authHeaders() });
    if (res.status === 503 && buildingRetries < MAX_BUILDING_RETRIES) {
      buildingRetries++;
      const body = await res.json().catch(() => ({} as { retryAfterSec?: number }));
      const waitMs = Number(body?.retryAfterSec ?? DEFAULT_RETRY_AFTER_SEC) * 1000;
      waitingForBuild = true;
      retryTimer = setTimeout(() => {
        retryTimer = null;
        isLoading.value = false; // 再入ガードを外してから取り直す
        fetchRecommendation();
      }, waitMs);
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json() as { items: FillRecommendationItem[] };
    items.value = data.items ?? [];
    visibleCount.value = PAGE_SIZE;
    buildingRetries = 0;
  } catch (e: any) {
    loadError.value = e?.message ?? 'fetch failed';
    items.value = [];
  } finally {
    // 構築待ちの間は「計算中」を出し続けるので isLoading を立てたままにする。
    if (!waitingForBuild) isLoading.value = false;
  }
  if (!waitingForBuild && pendingRefetch) {
    pendingRefetch = false;
    await fetchRecommendation();
  }
}

onBeforeUnmount(() => {
  if (retryTimer) clearTimeout(retryTimer);
});

/** 【computed の役割】 次ランクの必要 pt と現在 pt の差。既に最高位なら 0 を返す。 */
const nextRankGap = computed(() => {
  const { nextRank } = getNextRankInfo(props.totalPoints);
  if (!nextRank) return 0;
  return Math.max(0, nextRank.minPoints - props.totalPoints);
});

/**
 * 【computed の役割】 達成率の高い順に、残り pt を満たすまで採用した提案リスト。
 *
 * items はバックエンドで達成率降順に並び、gap を満たした所で打ち切られて返ってくる。
 * ここでは同じ規則（上から足して gap に届いたら止める）を保険として重ね、
 * 表示中の gap とバックエンドの打ち切り位置がずれても「届いた所まで」に揃える。
 * 厳密には 1 曲埋めるたびに 100 位ラインが上がって後続の期待値は少し下がるが、
 * 「次に触る曲」を決めるための目安なので、ここでは独立に足し合わせている。
 */
const suggestions = computed(() => {
  const gap = nextRankGap.value;
  if (gap <= 0) return [];

  const picked: FillRecommendationItem[] = [];
  let accumulated = 0;
  for (const item of items.value) {
    if (accumulated >= gap) break;
    picked.push(item);
    accumulated += item.expectedGain;
  }
  return picked;
});

/** 【computed の役割】 いま画面に出している分（先頭から visibleCount 件）。 */
const visibleSuggestions = computed(() => suggestions.value.slice(0, visibleCount.value));

/** 【computed の役割】 「さらに表示」で開ける残り件数。0 ならボタンを出さない。 */
const hiddenCount = computed(() => Math.max(0, suggestions.value.length - visibleCount.value));

/** 【関数の役割】 表示件数を 1 ページ分増やす。 */
function showMore() {
  visibleCount.value += PAGE_SIZE;
}

/** 【computed の役割】 採用した提案の期待獲得 pt 合計。gap と比較して「達成可能 / 不足」を出す。 */
const totalExpectedGain = computed(() =>
  suggestions.value.reduce((acc, s) => acc + s.expectedGain, 0)
);

/** 達成確率の色分け。高い＝取りやすい（緑）、低い＝一発狙い（琥珀）。 */
function probabilityClass(p: number): string {
  if (p >= 0.7) return 'text-emerald-600 dark:text-emerald-400';
  if (p >= 0.4) return 'text-slate-500 dark:text-slate-400';
  return 'text-amber-600 dark:text-amber-400';
}

/** 最高ランクに到達済み（残り pt が無い）ならパネル自体を出さないので、取得もしない。 */
function fetchIfNeeded() {
  if (nextRankGap.value <= 0) return;
  fetchRecommendation();
}

onMounted(fetchIfNeeded);
// スコアを取り込み直すと合計 pt が変わる。そのタイミングで推薦も取り直す。
watch(() => props.totalPoints, fetchIfNeeded);
</script>
