<script setup lang="ts">
/**
 * 【Viewの役割】 作品（バージョン）単位のスコア一覧ページ。
 *
 * プロフィールの「過去作スコア」から作品ラベルをクリックすると開く。
 * その作品で記録したスコアだけを 1 譜面 1 行で並べ、
 * 「その行が自分の歴代ベストかどうか」を強調表示する。
 *
 * 機能:
 *  - 曲名フリーワード検索・難易度フィルタ・★レベルフィルタ。
 *  - 曲名 / ★ / EX / レート / BP の各列でソート切替。
 *  - 「自己歴代スコアのみ表示」トグル（歴代ベストがこの作品の行だけに絞る）。
 *  - 歴代ベストの行はアンバーで強調し、それ以外の行には歴代ベストとの差分と
 *    ベストを持っている作品を並べる。
 *  - 1 ページ 50 件のページネーション。
 *
 * 設計上の前提:
 *  - 現行作（{@link CURRENT_VERSION}）の行は通常スコア（`scores`）由来、
 *    過去作の行は `past_scores` 由来。どちらも
 *    {@link usePastScores.buildChartHistories} が譜面単位に突き合わせた結果を使うので、
 *    このビューは「作品で絞り込む」だけでよく、両者を区別する必要がない。
 *  - 歴代ベストの判定は円グラフ（PastScoreManager）と同じ規則。
 *    同点なら新しい作品を勝たせるので、「歴代のみ表示」の件数は
 *    円グラフのその作品のスライスの件数と一致する。
 *
 * 依存:
 *  - provide/inject: `scoreData`（App.vue が provide する現行作スコア）。
 *  - `usePastScores` — 過去作スコアの取得と歴代の突き合わせ。
 */
import { ref, computed, inject, onMounted, watch } from 'vue';
import type { Ref } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useDarkMode } from '../composables/useDarkMode';
import { usePastScores } from '../composables/usePastScores';
import { flattenScores } from '../utils/scoreData';
import type { ScoreData } from '../types/ScoreData';
import {
  CURRENT_VERSION,
  versionBadgeClass,
  versionName,
  versionShort,
} from '../utils/iidxVersions';

const props = defineProps<{
  /** 表示対象の作品バージョン番号（30〜33）。 */
  version: number;
}>();

const emit = defineEmits<{
  /** 「戻る」クリック。呼び出し元（App.vue）がプロフィールへ戻す。 */
  (e: 'back'): void;
}>();

const { t } = useI18n();
const { isDarkMode } = useDarkMode();
const { fetchPastBest, fetchSummary, buildChartHistories, summary } = usePastScores();

/** App.vue が provide している現行作スコア。現行作の行を組み立てるのに使う。 */
const injectedScores = inject<Ref<ScoreData[]> | null>('scoreData', null);

/** 初期ロード（過去スコアの遅延取得）中フラグ。 */
const isLoading = ref(true);
/** 取得エラー。空文字なら非表示。 */
const errorMsg = ref('');

/** 曲名の部分一致検索クエリ。 */
const searchQuery = ref('');
/** 難易度フィルタ。空文字なら全難易度。 */
const selectedDifficulty = ref<string>('');
/** ★レベルフィルタ。空文字なら全レベル。 */
const selectedLevel = ref<number | ''>('');
/** 「自己歴代スコアのみ表示」トグル。 */
const onlyAllTimeBest = ref(false);
/** ソート対象カラム。 */
const sortKey = ref<'title' | 'level' | 'score' | 'rate' | 'bp'>('score');
/** ソート方向。 */
const sortDir = ref<'asc' | 'desc'>('desc');
/** 現在のページ番号（1 始まり）。 */
const currentPage = ref(1);
/** 1 ページあたりの表示件数。 */
const PAGE_SIZE = 50;

/** 難易度名 → バッジの配色。ChartListView と同じ色体系に揃える。 */
const DIFF_STYLE: Record<string, string> = {
  BEGINNER: 'text-emerald-600 dark:text-emerald-400 bg-emerald-100 dark:bg-emerald-900/40',
  NORMAL: 'text-blue-600 dark:text-blue-400 bg-blue-100 dark:bg-blue-900/40',
  HYPER: 'text-amber-600 dark:text-amber-400 bg-amber-100 dark:bg-amber-900/40',
  ANOTHER: 'text-red-600 dark:text-red-400 bg-red-100 dark:bg-red-900/40',
  LEGGENDARIA: 'text-purple-600 dark:text-purple-400 bg-purple-100 dark:bg-purple-900/40',
};

/** 難易度名の表示順（フィルタのプルダウンとソートの安定化に使う）。 */
const DIFF_ORDER = ['BEGINNER', 'NORMAL', 'HYPER', 'ANOTHER', 'LEGGENDARIA'];

/** クリアランプごとの文字色。ScoreSummary の配色に合わせる。 */
const clearTypeColor = (clearType: string): string => {
  switch (clearType) {
    case 'FULLCOMBO CLEAR': return isDarkMode.value ? 'text-cyan-400' : 'text-cyan-600';
    case 'EX HARD CLEAR': return isDarkMode.value ? 'text-yellow-400' : 'text-yellow-600';
    case 'HARD CLEAR': return isDarkMode.value ? 'text-red-400' : 'text-red-500';
    case 'CLEAR': return isDarkMode.value ? 'text-blue-400' : 'text-blue-500';
    case 'EASY CLEAR': return isDarkMode.value ? 'text-green-400' : 'text-green-500';
    case 'ASSIST CLEAR': return isDarkMode.value ? 'text-purple-400' : 'text-purple-500';
    default: return isDarkMode.value ? 'text-slate-500' : 'text-slate-400';
  }
};

/** "FULLCOMBO CLEAR" → "FC" のように短縮する（列幅を抑えるため）。 */
const clearLabel = (clearType: string): string =>
  clearType.replace('FULLCOMBO', 'FC').replace(' CLEAR', '');

/** 現行作のフラットなスコア。歴代突き合わせの一方の入力。 */
const currentRecords = computed(() => flattenScores(injectedScores?.value ?? []));

/** 一覧 1 行ぶんの表示データ。 */
interface Row {
  title: string;
  difficultyName: string;
  /** この作品での★。作品によって変動するため、その作品の値をそのまま出す。 */
  level: number | null;
  score: number;
  scoreRate: number | null;
  clearType: string;
  djLevel: string;
  missCount: number | null;
  /** この作品のスコアが歴代ベストか。 */
  isAllTimeBest: boolean;
  /** 歴代ベストスコア（他作品のこともある）。 */
  bestScore: number;
  /** 歴代ベストを出した作品。 */
  bestVersion: number;
}

/**
 * 【computed の役割】 対象作品にスコアがある譜面だけを行に落とす。
 *
 * {@link buildChartHistories} は全作品ぶんの推移を返すので、
 * ここでは対象作品のエントリを持つ譜面だけを拾い、歴代ベストとの関係を添える。
 */
const rows = computed<Row[]>(() => {
  const list: Row[] = [];

  buildChartHistories(currentRecords.value).forEach(history => {
    const entry = history.entries.find(e => e.version === props.version);
    if (!entry) return;

    list.push({
      title: history.title,
      difficultyName: history.difficultyName,
      level: entry.difficultyLevel,
      score: entry.score,
      scoreRate: entry.scoreRate,
      clearType: entry.clearType,
      djLevel: entry.djLevel,
      missCount: entry.missCount,
      isAllTimeBest: history.bestScore.version === props.version,
      bestScore: history.bestScore.score,
      bestVersion: history.bestScore.version,
    });
  });

  return list;
});

/** 歴代ベストがこの作品の譜面数。ヘッダーの件数表示とトグルの説明に使う。 */
const allTimeBestCount = computed(() => rows.value.filter(r => r.isAllTimeBest).length);

/** プルダウンに出す難易度（この作品に実際に存在するものだけ）。 */
const availableDifficulties = computed(() => {
  const set = new Set(rows.value.map(r => r.difficultyName));
  return DIFF_ORDER.filter(d => set.has(d));
});

/** プルダウンに出す★レベル（この作品に実際に存在するものだけ、昇順）。 */
const availableLevels = computed(() => {
  const set = new Set<number>();
  rows.value.forEach(r => { if (r.level) set.add(r.level); });
  return [...set].sort((a, b) => a - b);
});

/** 検索・難易度・★・歴代トグルの複合フィルタを適用した配列。 */
const filtered = computed(() => {
  let list = rows.value;

  if (onlyAllTimeBest.value) {
    list = list.filter(r => r.isAllTimeBest);
  }
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase();
    list = list.filter(r => r.title.toLowerCase().includes(q));
  }
  if (selectedDifficulty.value) {
    list = list.filter(r => r.difficultyName === selectedDifficulty.value);
  }
  if (selectedLevel.value !== '') {
    list = list.filter(r => r.level === selectedLevel.value);
  }

  return list;
});

/** フィルタ済み配列をソートした結果。 */
const sorted = computed(() => {
  const arr = [...filtered.value];
  const dir = sortDir.value === 'asc' ? 1 : -1;

  arr.sort((a, b) => {
    switch (sortKey.value) {
      case 'title':
        return dir * a.title.localeCompare(b.title, 'ja');
      case 'level':
        return dir * ((a.level ?? 0) - (b.level ?? 0));
      case 'rate':
        // レートを出せない譜面（現行の曲マスタに無い削除曲）は常に末尾へ寄せる。
        if (a.scoreRate === null && b.scoreRate === null) return 0;
        if (a.scoreRate === null) return 1;
        if (b.scoreRate === null) return -1;
        return dir * (a.scoreRate - b.scoreRate);
      case 'bp':
        // BP は「小さいほど優秀」。未計測（null）は最悪値として末尾に置く。
        if (a.missCount === null && b.missCount === null) return 0;
        if (a.missCount === null) return 1;
        if (b.missCount === null) return -1;
        return dir * (a.missCount - b.missCount);
      case 'score':
      default:
        return dir * (a.score - b.score);
    }
  });

  return arr;
});

/** 総ページ数（最低 1 ページ）。 */
const totalPages = computed(() => Math.max(1, Math.ceil(sorted.value.length / PAGE_SIZE)));

/** 現ページに表示すべきスライス。 */
const paged = computed(() => {
  const start = (currentPage.value - 1) * PAGE_SIZE;
  return sorted.value.slice(start, start + PAGE_SIZE);
});

/** この作品の取り込み日（過去作のみ。現行作は通常スコアなので持たない）。 */
const importedAt = computed(() => {
  const entry = summary.value.find(s => s.version === props.version);
  return entry?.importedAt ? entry.importedAt.slice(0, 10) : '';
});

/**
 * 【関数の役割】 カラムヘッダクリック時のソート切替。
 * 同じキーなら昇降反転、別キーなら切替＋初期方向（曲名だけ昇順、それ以外は降順）。
 */
const toggleSort = (key: typeof sortKey.value) => {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc';
  } else {
    sortKey.value = key;
    // BP は小さいほど優秀なので、降順スタート（= dir -1）だと最悪値が先頭に来てしまう。
    sortDir.value = key === 'title' || key === 'bp' ? 'asc' : 'desc';
  }
  currentPage.value = 1;
};

/** 現在のソート対象なら三角記号（▲/▼）を返すヘルパー。 */
const sortIcon = (key: string): string => {
  if (sortKey.value !== key) return '';
  return sortDir.value === 'asc' ? '▲' : '▼';
};

/** フィルタ変更時は 1 ページ目に戻す（表示ズレ防止）。 */
watch([searchQuery, selectedDifficulty, selectedLevel, onlyAllTimeBest], () => {
  currentPage.value = 1;
});

// 作品を切り替えたら（別の作品ラベルから開き直したら）絞り込みを初期化する。
watch(() => props.version, () => {
  searchQuery.value = '';
  selectedDifficulty.value = '';
  selectedLevel.value = '';
  onlyAllTimeBest.value = false;
  currentPage.value = 1;
});

onMounted(async () => {
  isLoading.value = true;
  try {
    // 過去スコアは数千件になり得るのでモジュールスコープにキャッシュされる。
    // 既に歴代タブ等で取得済みなら fetchPastBest() は何もしない。
    await Promise.all([fetchPastBest(), fetchSummary()]);
  } catch (e: any) {
    errorMsg.value = e?.message || t('past.manager.loadFailed');
  } finally {
    isLoading.value = false;
  }
});
</script>

<template>
  <div class="space-y-6">
    <!-- ヘッダー: 戻る導線 + 作品バッジ + 件数サマリー -->
    <div>
      <button
        class="inline-flex items-center gap-1 text-xs font-bold text-slate-500 dark:text-slate-400 hover:text-blue-600 dark:hover:text-blue-400 transition-colors"
        @click="emit('back')"
      >
        <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7" />
        </svg>
        {{ t('past.list.back') }}
      </button>

      <div class="flex flex-wrap items-center gap-2 mt-2">
        <span
          class="px-2 py-0.5 text-xs font-bold rounded border"
          :class="versionBadgeClass(props.version)"
        >{{ props.version }}</span>
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white">{{ versionName(props.version) }}</h1>
        <span
          v-if="props.version === CURRENT_VERSION"
          class="px-1.5 py-0.5 text-[10px] rounded bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300"
        >{{ t('past.manager.current') }}</span>
      </div>

      <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
        {{ t('past.list.subtitle', { name: versionName(props.version) }) }}
        <span v-if="importedAt"> · {{ t('past.manager.colImported') }}: {{ importedAt }}</span>
      </p>
    </div>

    <p v-if="errorMsg" class="text-xs text-red-600 dark:text-red-400">{{ errorMsg }}</p>

    <div v-if="isLoading" class="text-sm text-slate-500 dark:text-slate-400 py-12 text-center">
      {{ t('common.loading') }}
    </div>

    <div v-else-if="rows.length === 0" class="text-sm text-slate-500 dark:text-slate-400 py-12 text-center">
      {{ t('past.list.empty') }}
    </div>

    <template v-else>
      <!-- 歴代ベストの件数サマリー。円グラフのスライスと同じ数え方 -->
      <div class="bg-amber-50 dark:bg-amber-900/20 border border-amber-100 dark:border-amber-800/50 rounded-md px-4 py-3">
        <p class="text-sm text-amber-700 dark:text-amber-300 font-bold tabular-nums">
          {{ t('past.list.bestCount', { n: allTimeBestCount.toLocaleString(), total: rows.length.toLocaleString() }) }}
        </p>
        <p class="text-xs text-slate-500 dark:text-slate-400 mt-0.5">{{ t('past.list.bestCountHint') }}</p>
      </div>

      <!-- フィルタ領域: 歴代トグル + 検索 + 難易度 + ★ + 件数 -->
      <div class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 p-4 space-y-3">
        <!-- 自己歴代スコアのみ表示するトグル -->
        <label class="flex items-center gap-2 cursor-pointer group w-fit" :title="t('past.list.onlyBestHint')">
          <div class="relative inline-flex items-center">
            <input v-model="onlyAllTimeBest" type="checkbox" class="sr-only peer">
            <div class="w-9 h-5 bg-slate-200 dark:bg-slate-700 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-amber-300 dark:peer-focus:ring-amber-800 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white dark:peer-checked:after:border-slate-800 after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white dark:after:bg-slate-800 after:border-slate-300 dark:after:border-slate-600 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-amber-500"></div>
          </div>
          <span
            class="text-xs sm:text-sm font-bold transition-colors"
            :class="onlyAllTimeBest ? 'text-amber-600 dark:text-amber-400' : 'text-slate-600 dark:text-slate-400 group-hover:text-slate-800 dark:group-hover:text-slate-200'"
          >{{ t('past.list.onlyBest') }}</span>
        </label>

        <div class="flex flex-wrap gap-3 items-center">
          <!-- 曲名検索 -->
          <div class="relative flex-1 min-w-[200px]">
            <svg xmlns="http://www.w3.org/2000/svg" class="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              v-model="searchQuery"
              type="text"
              :placeholder="t('past.list.searchPlaceholder')"
              class="w-full pl-10 pr-4 py-2.5 rounded-md border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900/50 text-sm text-slate-900 dark:text-white placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
          </div>

          <!-- 難易度フィルタ -->
          <select
            v-model="selectedDifficulty"
            class="px-4 py-2.5 rounded-md border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900/50 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            <option value="">{{ t('chartList.allDifficulties') }}</option>
            <option v-for="d in availableDifficulties" :key="d" :value="d">{{ d }}</option>
          </select>

          <!-- ★レベルフィルタ -->
          <select
            v-model="selectedLevel"
            class="px-4 py-2.5 rounded-md border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900/50 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            <option value="">{{ t('chartList.allLevels') }}</option>
            <option v-for="lv in availableLevels" :key="lv" :value="lv">Lv.{{ lv }}</option>
          </select>

          <span class="text-xs font-bold text-slate-400 dark:text-slate-500 whitespace-nowrap">
            {{ filtered.length.toLocaleString() }} {{ t('chartList.charts') }}
          </span>
        </div>
      </div>

      <!-- スコア一覧テーブル。歴代ベストの行はアンバーで強調する -->
      <div class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 overflow-hidden">
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="border-b border-slate-100 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/30">
                <th
                  scope="col"
                  role="button"
                  tabindex="0"
                  :aria-sort="sortKey === 'title' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
                  class="px-4 py-3 text-left font-bold text-slate-600 dark:text-slate-400 cursor-pointer hover:text-blue-600 dark:hover:text-blue-400 select-none whitespace-nowrap"
                  @click="toggleSort('title')"
                  @keydown.enter.prevent="toggleSort('title')"
                  @keydown.space.prevent="toggleSort('title')"
                >
                  {{ t('table.colTitle') }} <span aria-label="並び替え">{{ sortIcon('title') }}</span>
                </th>
                <th scope="col" class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 whitespace-nowrap">
                  {{ t('chartList.colDifficulty') }}
                </th>
                <th
                  scope="col"
                  role="button"
                  tabindex="0"
                  :aria-sort="sortKey === 'level' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
                  class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 cursor-pointer hover:text-blue-600 dark:hover:text-blue-400 select-none whitespace-nowrap"
                  @click="toggleSort('level')"
                  @keydown.enter.prevent="toggleSort('level')"
                  @keydown.space.prevent="toggleSort('level')"
                >
                  {{ t('chartList.colLevel') }} <span aria-label="並び替え">{{ sortIcon('level') }}</span>
                </th>
                <th
                  scope="col"
                  role="button"
                  tabindex="0"
                  :aria-sort="sortKey === 'score' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
                  class="px-3 py-3 text-right font-bold text-slate-600 dark:text-slate-400 cursor-pointer hover:text-blue-600 dark:hover:text-blue-400 select-none whitespace-nowrap"
                  @click="toggleSort('score')"
                  @keydown.enter.prevent="toggleSort('score')"
                  @keydown.space.prevent="toggleSort('score')"
                >
                  {{ t('past.colEx') }} <span aria-label="並び替え">{{ sortIcon('score') }}</span>
                </th>
                <th
                  scope="col"
                  role="button"
                  tabindex="0"
                  :aria-sort="sortKey === 'rate' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
                  class="px-3 py-3 text-right font-bold text-slate-600 dark:text-slate-400 cursor-pointer hover:text-blue-600 dark:hover:text-blue-400 select-none whitespace-nowrap max-sm:hidden"
                  @click="toggleSort('rate')"
                  @keydown.enter.prevent="toggleSort('rate')"
                  @keydown.space.prevent="toggleSort('rate')"
                >
                  {{ t('table.colRate') }} <span aria-label="並び替え">{{ sortIcon('rate') }}</span>
                </th>
                <th scope="col" class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 whitespace-nowrap">
                  {{ t('past.colLamp') }}
                </th>
                <th
                  scope="col"
                  role="button"
                  tabindex="0"
                  :aria-sort="sortKey === 'bp' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
                  class="px-3 py-3 text-right font-bold text-slate-600 dark:text-slate-400 cursor-pointer hover:text-blue-600 dark:hover:text-blue-400 select-none whitespace-nowrap max-sm:hidden"
                  @click="toggleSort('bp')"
                  @keydown.enter.prevent="toggleSort('bp')"
                  @keydown.space.prevent="toggleSort('bp')"
                >
                  {{ t('past.colBp') }} <span aria-label="並び替え">{{ sortIcon('bp') }}</span>
                </th>
                <th scope="col" class="px-4 py-3 text-right font-bold text-slate-600 dark:text-slate-400 whitespace-nowrap">
                  {{ t('past.list.colAllTime') }}
                </th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-50 dark:divide-slate-700/50">
              <tr
                v-for="(row, i) in paged"
                :key="`${row.title}-${row.difficultyName}-${i}`"
                class="transition-colors"
                :class="row.isAllTimeBest
                  ? 'bg-amber-50 dark:bg-amber-900/20 hover:bg-amber-100/70 dark:hover:bg-amber-900/30'
                  : 'hover:bg-slate-50 dark:hover:bg-slate-700/30'"
              >
                <td
                  class="px-4 py-3 font-medium max-w-[280px] truncate"
                  :class="row.isAllTimeBest
                    ? 'text-amber-800 dark:text-amber-200 border-l-2 border-amber-400 dark:border-amber-500'
                    : 'text-slate-900 dark:text-white'"
                  :title="row.title"
                >
                  {{ row.title }}
                </td>
                <td class="px-3 py-3 text-center">
                  <span
                    class="inline-block px-2 py-0.5 text-[10px] font-bold rounded-md"
                    :class="DIFF_STYLE[row.difficultyName] || 'text-slate-600 dark:text-slate-300 bg-slate-100 dark:bg-slate-700'"
                  >{{ row.difficultyName }}</span>
                </td>
                <td class="px-3 py-3 text-center font-bold text-slate-700 dark:text-slate-300 tabular-nums">
                  {{ row.level ?? '—' }}
                </td>
                <td
                  class="px-3 py-3 text-right font-bold tabular-nums"
                  :class="row.isAllTimeBest ? 'text-amber-700 dark:text-amber-300' : 'text-slate-800 dark:text-slate-100'"
                >
                  {{ row.score.toLocaleString() }}
                  <span class="ml-1 text-[10px] font-medium text-slate-400 dark:text-slate-500">{{ row.djLevel }}</span>
                </td>
                <td class="px-3 py-3 text-right tabular-nums text-slate-600 dark:text-slate-400 max-sm:hidden">
                  {{ row.scoreRate !== null ? row.scoreRate.toFixed(2) + '%' : '—' }}
                </td>
                <td class="px-3 py-3 text-center font-bold text-[11px]" :class="clearTypeColor(row.clearType)">
                  {{ clearLabel(row.clearType) }}
                </td>
                <td class="px-3 py-3 text-right tabular-nums text-slate-600 dark:text-slate-400 max-sm:hidden">
                  {{ row.missCount !== null ? row.missCount : '—' }}
                </td>
                <td class="px-4 py-3 text-right whitespace-nowrap">
                  <!-- 歴代ベストの行はバッジ、そうでない行は「あと何点で当時の自分に並ぶか」を出す -->
                  <span
                    v-if="row.isAllTimeBest"
                    class="inline-block px-2 py-0.5 text-[10px] font-bold rounded-md text-amber-700 bg-amber-100 dark:text-amber-300 dark:bg-amber-900/40"
                  >★ {{ t('past.list.bestBadge') }}</span>
                  <span v-else class="text-xs tabular-nums text-slate-500 dark:text-slate-400">
                    {{ (row.score - row.bestScore).toLocaleString() }}
                    <span class="text-[10px] text-slate-400 dark:text-slate-500">
                      ({{ row.bestVersion }} {{ versionShort(row.bestVersion) }})
                    </span>
                  </span>
                </td>
              </tr>
              <tr v-if="paged.length === 0">
                <td colspan="8" class="px-4 py-12 text-center text-slate-400 dark:text-slate-500 font-medium">
                  {{ onlyAllTimeBest ? t('past.list.noneAllTime') : t('chartList.noResults') }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- ページネーション -->
        <div v-if="totalPages > 1" class="flex items-center justify-between px-4 py-3 border-t border-slate-100 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-900/20">
          <button
            :disabled="currentPage <= 1"
            class="px-3 py-1.5 text-xs font-bold rounded-lg border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-400 hover:bg-white dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
            @click="currentPage--"
          >
            {{ t('chartList.prev') }}
          </button>
          <span class="text-xs font-bold text-slate-500 dark:text-slate-400">
            {{ currentPage }} / {{ totalPages }}
          </span>
          <button
            :disabled="currentPage >= totalPages"
            class="px-3 py-1.5 text-xs font-bold rounded-lg border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-400 hover:bg-white dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
            @click="currentPage++"
          >
            {{ t('chartList.next') }}
          </button>
        </div>
      </div>

      <p class="text-xs text-slate-500 dark:text-slate-400">{{ t('past.rateNote') }}</p>
      <p v-if="props.version !== CURRENT_VERSION" class="text-xs text-slate-500 dark:text-slate-400">{{ t('past.notRanked') }}</p>
    </template>
  </div>
</template>
