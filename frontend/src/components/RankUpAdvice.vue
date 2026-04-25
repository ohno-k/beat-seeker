<template>
  <!-- Rank up suggestion panel -->
  <div
    v-if="nextRankGap > 0"
    class="bg-white dark:bg-slate-800 p-4 sm:p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors duration-200"
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
      {{ t('advice.basedOnPotential') }}
    </p>

    <div v-if="isGrowthLoading" class="text-center py-6 text-slate-400 dark:text-slate-500 text-sm">
      {{ t('advice.computingPotential') }}
    </div>
    <div v-else-if="growthError" class="text-center py-6 text-rose-500 text-xs">
      {{ t('advice.potentialError', { msg: growthError }) }}
    </div>
    <div v-else-if="suggestions.length === 0" class="text-center py-6 text-slate-400 dark:text-slate-500 text-sm">
      {{ t('advice.noSuggestions') }}
    </div>
    <div v-else class="space-y-2">
      <div
        v-for="(sug, i) in suggestions"
        :key="i"
        class="flex items-center gap-2 p-2 sm:p-3 rounded-xl border transition-colors"
        :class="sug.crossesBorder
          ? 'bg-blue-50/50 dark:bg-blue-900/10 border-blue-100 dark:border-blue-900/50'
          : 'bg-slate-50/50 dark:bg-slate-700/20 border-slate-100 dark:border-slate-700/50'"
      >
        <span class="text-[10px] font-black text-slate-400 dark:text-slate-500 shrink-0 w-4 text-right">{{ i + 1 }}</span>
        <div class="flex-1 min-w-0">
          <p class="font-bold text-slate-800 dark:text-slate-200 text-xs sm:text-sm truncate">{{ sug.song.title }}</p>
          <p class="text-[10px] text-slate-500 dark:text-slate-400">
            {{ sug.song.difficultyName }} / {{ t('common.current') }} {{ sug.song.beatTierPoints.toFixed(1) }} pt
          </p>
        </div>
        <div class="text-right shrink-0">
          <p v-if="sug.targetLabel" class="text-[10px] font-bold text-blue-500 dark:text-blue-400">{{ sug.targetLabel }}</p>
          <p class="text-xs font-black text-slate-700 dark:text-slate-200">
            {{ t('advice.scoreIncrease', { n: sug.scoreIncrease.toLocaleString() }) }}
          </p>
          <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500">→ {{ sug.newScoreRate.toFixed(2) }}%</p>
          <p class="text-[10px] font-bold text-emerald-600 dark:text-emerald-400">+{{ sug.ptGain.toFixed(1) }} pt</p>
        </div>
      </div>
    </div>

    <!-- Total summary -->
    <div
      v-if="suggestions.length > 0"
      class="mt-4 p-3 rounded-xl border-2 flex items-center justify-between"
      :class="totalSuggestionGain >= nextRankGap
        ? 'bg-emerald-50 dark:bg-emerald-900/20 border-emerald-300 dark:border-emerald-700'
        : 'bg-amber-50 dark:bg-amber-900/20 border-amber-300 dark:border-amber-700'"
    >
      <div>
        <p class="text-[10px] font-bold uppercase tracking-wider"
          :class="totalSuggestionGain >= nextRankGap ? 'text-emerald-600 dark:text-emerald-400' : 'text-amber-600 dark:text-amber-400'">
          {{ totalSuggestionGain >= nextRankGap ? t('common.achievable') : t('common.shortfall') }}
        </p>
        <p class="text-lg font-black"
          :class="totalSuggestionGain >= nextRankGap ? 'text-emerald-700 dark:text-emerald-300' : 'text-amber-700 dark:text-amber-300'">
          {{ t('common.total') }} +{{ totalSuggestionGain.toFixed(1) }} pt
        </p>
      </div>
      <div class="text-right text-xs font-bold text-slate-500 dark:text-slate-400">
        <p>{{ t('advice.goal') }}</p>
        <p class="text-sm font-black text-slate-700 dark:text-slate-200">+{{ nextRankGap.toFixed(1) }} pt</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 次のティアまで残 pt を埋めるのに効率の良い楽曲を推薦するパネル。
 * - props.flatScores（全譜面スコア）と totalPoints（現在の Beat-PT 合計）から、
 *   次ランクまでの差分 nextRankGap を算出する
 * - 各譜面ごとにスコア加算（AA/AAA/MAX- の達成ボーダー越えを優先）を試算して ptGain を計算
 * - 効率（ptGain / scoreIncrease）の高い順に、上限 19 曲または gap を超えるまで選出
 * - TOP100 外の譜面は threshold（100 位のスコア）を基準に「TOP100 に入った後の増分」で評価
 *
 * 【アルゴリズム概要】
 *   1. sortedScored: beatTierPoints > 0 の曲を降順ソート（TOP100 判定用）
 *   2. getScoreCap: 自分の中の順位に応じて score 加算上限を 10〜50 pts で制限（上位曲は伸ばしづらい補正）
 *   3. buildCandidates: 各曲で最も近いボーダー（AA/AAA/MAX-）を優先的に狙い、無ければ +cap
 *   4. pickBestSuggestions: 効率降順で貪欲法、gap を埋めきるまで採用
 *
 * @prop flatScores フラットなスコアレコード配列。
 * @prop totalPoints 現在の Beat-PT 合計。次ランクの gap 計算に使用。
 */
import { computed, ref, watch, onMounted } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useAuth } from '../composables/useAuth';
import type { ScoreRecord } from '../utils/scoreData';
import { calculatePoints, getNextRankInfo } from '../utils/beatTier';

const { t } = useI18n();
const { authHeaders } = useAuth();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

const props = defineProps<{
  flatScores: ScoreRecord[];
  totalPoints: number;
}>();

// ── 伸びしろ API: 「現実的に出せるはずのスコア」を譜面ごとに供給 ────────
interface GrowthPotentialItem {
  title: string;
  difficultyName: string;
  currentScore: number;
  predictedScore: number;
  gap: number;
}
/** key = "title|difficultyName" -> 伸びしろ gap (正の整数)。
 *  ランクアップアドバイスでは、各譜面の score 上限値として使う。 */
const growthGapMap = ref<Map<string, number>>(new Map());
const isGrowthLoading = ref(false);
const growthError = ref('');

/** バックエンドの伸びしろAPIを叩いて map に変換。初回はキャッシュ構築で数秒かかることがある。 */
async function fetchGrowthPotential() {
  isGrowthLoading.value = true;
  growthError.value = '';
  try {
    const res = await fetch(`${API_BASE}/api/analysis/growth-potential`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json() as { items: GrowthPotentialItem[] };
    const m = new Map<string, number>();
    for (const it of data.items ?? []) {
      const g = Math.floor(it.gap);
      if (g > 0) m.set(`${it.title}|${it.difficultyName}`, g);
    }
    growthGapMap.value = m;
  } catch (e: any) {
    growthError.value = e?.message ?? 'fetch failed';
    growthGapMap.value = new Map();
  } finally {
    isGrowthLoading.value = false;
  }
}

// ── Top-100 状態 ────────────────────────────────────────────────────────

/** 【computed の役割】 自分の Beat-PT TOP 順にソートした譜面リスト（0 pt の譜面は除外）。 */
const sortedScored = computed(() =>
  props.flatScores
    .filter(s => s.beatTierPoints > 0)
    .sort((a, b) => b.beatTierPoints - a.beatTierPoints)
);

/** 【computed の役割】 TOP100 圏内の最低 pt（100 位の pt）。未達の曲が TOP100 に入るために超えるべきライン。 */
const threshold = computed(() => {
  const s = sortedScored.value;
  return s.length >= 100 ? s[99].beatTierPoints : 0;
});

/** 【computed の役割】 TOP100 圏内の譜面キーセット（"title|difficultyName"）。候補評価時の判定に使用。 */
const top100Set = computed(() =>
  new Set(sortedScored.value.slice(0, 100).map(s => `${s.title}|${s.difficultyName}`))
);

// ── ランクアップまでの差分 ──────────────────────────────────────────────

/** 【computed の役割】 次ランクの必要 pt と現在 pt の差。既に最高位なら 0 を返す。 */
const nextRankGap = computed(() => {
  const { nextRank } = getNextRankInfo(props.totalPoints);
  if (!nextRank) return 0;
  return Math.max(0, nextRank.minPoints - props.totalPoints);
});

// ── スコア向上の目標ボーダー（AA / AAA / MAX- を超えるとボーナス pt が付く） ─
// ボーナス判定が確実に含まれるよう、実境界値よりわずかに上の rate を使う。
const IMPROVEMENT_THRESHOLDS = [
  { rate: 66.67, label: t('advice.thresholdBorder') },
  { rate: 77.78, label: t('advice.thresholdAa') },
  { rate: 88.89, label: t('advice.thresholdAaa') },
  { rate: 94.45, label: t('advice.thresholdMax') },
];

/** 提案 1 件の構造（曲、目標ラベル、増加量、予想 Beat-PT 増分）。 */
interface Suggestion {
  song: ScoreRecord;
  targetLabel: string;   // 越える最大のボーダー名（なければ空文字）
  crossesBorder: boolean;
  scoreIncrease: number; // 加算するスコア量（≤ 50）
  newScoreRate: number;  // 加算後のスコアレート %
  ptGain: number;        // 実質 Beat-PT 増分
}

/**
 * 【関数の役割】 曲ごとの score 加算上限 (cap) を、伸びしろAPIの予測 gap から取得する。
 * 伸びしろAPIに含まれない譜面（Lv11/12 ANOTHER/LEGG 以外、または support 不足）は 0 を返し、
 * 呼び出し側で skip させる。
 */
function getScoreCap(song: ScoreRecord): number {
  return growthGapMap.value.get(`${song.title}|${song.difficultyName}`) ?? 0;
}

/**
 * 【関数の役割】 全譜面を走査して、Beat-PT を稼げる候補 Suggestion 配列を組み立てる。
 * - 既に満点 or 非公式ランク無 → スキップ
 * - 伸びしろAPIに予測がない譜面 → スキップ（=「他者傾向から押せる根拠がない」ため除外）
 * - cap (= 伸びしろ gap) 以内で届く一番高いボーダー（AA → AAA → MAX-）を目標に採用
 * - TOP100 圏外の場合は threshold (100 位) を基準に純増分を計算
 * - 0.05 pt 未満の候補は UI 上 "+0.0 pt" と表示されるため除外
 */
function buildCandidates(): Suggestion[] {
  const candidates: Suggestion[] = [];
  const th = threshold.value;

  for (const song of props.flatScores) {
    if (song.maxScore <= 0 || !song.informalRank || song.scoreRate < 0) continue;
    if (song.score >= song.maxScore) continue;

    const cap = getScoreCap(song);
    if (cap <= 0) continue; // 伸びしろ予測なし or gap=0 → スキップ

    // 最も近いボーダーを探索。届かなければ +cap で据え置き。
    let scoreIncrease = cap;
    let targetLabel = '';
    let crossesBorder = false;

    for (const thr of IMPROVEMENT_THRESHOLDS) {
      if (song.scoreRate >= thr.rate - 0.01) continue;
      const needed = Math.ceil(song.maxScore * thr.rate / 100) - song.score;
      if (needed > 0 && needed <= cap) {
        scoreIncrease = needed;
        targetLabel = thr.label;
        crossesBorder = true;
        break;
      }
    }

    const newScore = Math.min(song.score + scoreIncrease, song.maxScore);
    const actualIncrease = newScore - song.score;
    if (actualIncrease <= 0) continue;

    const newScoreRate = (newScore / song.maxScore) * 100;
    const newBeatPT = calculatePoints(newScoreRate, song.informalRank);
    const rawGain = newBeatPT - song.beatTierPoints;
    if (rawGain <= 0) continue;

    const inTop100 = top100Set.value.has(`${song.title}|${song.difficultyName}`);
    let netGain: number;
    if (inTop100) {
      // TOP100 内は rawGain がそのまま合計 pt に反映される。
      netGain = rawGain;
    } else if (th > 0) {
      // TOP100 外は「100 位 pt を押し出した分」だけが純増分になる。
      netGain = newBeatPT - th;
    } else {
      netGain = rawGain;
    }

    if (netGain < 0.05) continue; // 表示上 0.0 pt になる候補は捨てる

    candidates.push({ song, targetLabel, crossesBorder, scoreIncrease: actualIncrease, newScoreRate, ptGain: netGain });
  }

  return candidates;
}

/**
 * 【関数の役割】 候補群から効率順に採用し、gap が埋まるまで 19 曲以内で選ぶ貪欲選択。
 * 同効率ならボーダー越え（AA/AAA/MAX-）の候補を優先、それも同じなら ptGain の大きい方。
 */
function pickBestSuggestions(): Suggestion[] {
  const gap = nextRankGap.value;
  if (gap <= 0) return [];

  const candidates = buildCandidates();
  if (candidates.length === 0) return [];

  // 効率（ptGain / scoreIncrease）で降順、タイなら crossesBorder を優先、さらに ptGain 降順。
  const sorted = [...candidates].sort((a, b) => {
    const effA = a.ptGain / a.scoreIncrease;
    const effB = b.ptGain / b.scoreIncrease;
    if (Math.abs(effA - effB) > 0.001) return effB - effA;
    if (a.crossesBorder !== b.crossesBorder) return a.crossesBorder ? -1 : 1;
    return b.ptGain - a.ptGain;
  });

  // 効率の高い曲から順に、gap を超えるまで採用（最大 19 曲）。
  const result: Suggestion[] = [];
  let accumulated = 0;

  for (const c of sorted) {
    if (result.length >= 19) break;
    if (accumulated >= gap) break;
    result.push(c);
    accumulated += c.ptGain;
  }

  return result;
}

/** 表示用の提案リスト（onMounted + watch で最新化）。 */
const suggestions = ref<Suggestion[]>([]);

// 初回マウント時に 伸びしろ取得 → 候補算出。
onMounted(async () => {
  await fetchGrowthPotential();
  suggestions.value = pickBestSuggestions();
});
// props.flatScores / totalPoints / 伸びしろ map が変化するたびに再算出。
watch(() => [props.flatScores, props.totalPoints, growthGapMap.value], () => {
  suggestions.value = pickBestSuggestions();
}, { deep: false });

/** 【computed の役割】 採用候補の ptGain 合計。gap と比較して「達成可能 / 不足」を表示するのに使用。 */
const totalSuggestionGain = computed(() =>
  suggestions.value.reduce((acc, s) => acc + s.ptGain, 0)
);
</script>
