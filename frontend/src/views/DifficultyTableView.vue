<script setup lang="ts">
/**
 * 【Viewの役割】 非公式難易度表（譜面難易度ランクごとの曲リスト）を表示するページ。
 *
 * 機能:
 *  - `useGameData` が保持する `diffTableRanks` をベースに、ランクをアコーディオン UI で表示する。
 *  - 数値ランク（12.0 等）と非数値ランク（個人差・未定義 等）を分けて並べる。
 *  - 曲名フリーワード検索で該当のみフィルタし、ヒットしたランクを自動展開する。
 *  - 未ログインユーザーに向けたログインCTAバナーを先頭に表示する。
 *
 * 依存:
 *  - `useGameData` — `diffTableRanks`（ランク別曲リスト）。
 *  - `useAuth` — ログイン判定。
 *  - `useI18n` — 多言語対応。
 */
import { ref, computed } from 'vue';
import { useGameData } from '../composables/useGameData';
import { useAuth } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();
const { diffTableRanks, extraOfficialLevelRanks } = useGameData();
const { isLoggedIn } = useAuth();

/** 検索ボックスの入力文字列（小文字化して曲名と照合） */
const searchQuery = ref('');
/** 展開中のランク名の集合。Setにしている理由は O(1) でトグルしたいため */
const expandedRanks = ref<Set<string>>(new Set());

/**
 * 【関数の役割】 ランク文字列が数値（12.0, 11.5 等）として解釈できるか判定。
 * @param rank ランク名
 * @returns 数値として扱える場合 true
 */
function isNumericRank(rank: string): boolean {
  return !isNaN(parseFloat(rank)) && isFinite(Number(rank));
}

/** 数値ランクのみ抽出し、降順（難しい順）に並べ替えた配列 */
const numericRanks = computed(() =>
  [...diffTableRanks.value].filter(r => isNumericRank(r.rank)).reverse()
);

/** 非数値ランク（個人差・未定義 等）のみ抽出 */
const nonNumericRanks = computed(() =>
  diffTableRanks.value.filter(r => !isNumericRank(r.rank))
);

/**
 * 非公式表の末尾に並べる「公式 LV10 以下」の追加カテゴリ。
 * useGameData 側で `informalRank` 用途とは分離して提供されているので、ここでだけ結合する。
 */
const officialLowLevelRanks = computed(() => [...extraOfficialLevelRanks.value]);

/** 検索文字列で曲名をフィルタリングしたランク配列。空クエリなら全件返す */
const filteredRanks = computed(() => {
  const all = [...numericRanks.value, ...nonNumericRanks.value, ...officialLowLevelRanks.value];
  const q = searchQuery.value.trim().toLowerCase();
  if (!q) return all;
  return all
    .map(r => ({
      ...r,
      songs: r.songs.filter(s => s.toLowerCase().includes(q)),
    }))
    .filter(r => r.songs.length > 0);
});

/** 表内の総曲数（ヘッダー統計用） */
const totalSongs = computed(() =>
  diffTableRanks.value.reduce((acc, r) => acc + r.songs.length, 0)
  + extraOfficialLevelRanks.value.reduce((acc, r) => acc + r.songs.length, 0)
);

/**
 * 【関数の役割】 ランクアコーディオンの展開／折りたたみをトグルする。
 * @param rank クリックされたランク名
 */
function toggleRank(rank: string) {
  if (expandedRanks.value.has(rank)) {
    expandedRanks.value.delete(rank);
  } else {
    expandedRanks.value.add(rank);
  }
}

/** フィルタ後に見えている全ランクを展開する */
function expandAll() {
  filteredRanks.value.forEach(r => expandedRanks.value.add(r.rank));
}

/** すべてのランクを折りたたむ */
function collapseAll() {
  expandedRanks.value.clear();
}

/**
 * 【関数の役割】 ランク値に応じた文字色クラスを返す。
 * 13以上=赤、12.5以上=橙、12.0以上=琥珀、11.5以上=緑、それ以外=青。
 */
function rankColor(rank: string): string {
  if (!isNumericRank(rank)) return 'text-slate-500 dark:text-slate-400';
  const v = parseFloat(rank);
  if (v >= 13.0) return 'text-red-600 dark:text-red-400';
  if (v >= 12.5) return 'text-orange-500 dark:text-orange-400';
  if (v >= 12.0) return 'text-amber-500 dark:text-amber-400';
  if (v >= 11.5) return 'text-emerald-600 dark:text-emerald-400';
  return 'text-blue-600 dark:text-blue-400';
}

/**
 * 【関数の役割】 ランクカードの背景色＋ボーダー色クラスを返す（上の色分けと統一感を持たせる）。
 */
function rankBadgeBg(rank: string): string {
  if (!isNumericRank(rank)) return 'bg-slate-100 dark:bg-slate-700';
  const v = parseFloat(rank);
  if (v >= 13.0) return 'bg-red-50 dark:bg-red-900/20 border-red-200 dark:border-red-800';
  if (v >= 12.5) return 'bg-orange-50 dark:bg-orange-900/20 border-orange-200 dark:border-orange-800';
  if (v >= 12.0) return 'bg-amber-50 dark:bg-amber-900/20 border-amber-200 dark:border-amber-800';
  if (v >= 11.5) return 'bg-emerald-50 dark:bg-emerald-900/20 border-emerald-200 dark:border-emerald-800';
  return 'bg-blue-50 dark:bg-blue-900/20 border-blue-200 dark:border-blue-800';
}

/** 検索入力のたびにヒット対象を一括展開して可視化する */
function handleSearch() {
  if (searchQuery.value.trim()) {
    expandAll();
  }
}
</script>

<template>
  <div class="space-y-6 pb-20 animate-fade-in">
    <!-- ヘッダー: タイトル・統計・展開／折りたたみボタン・検索ボックス -->
    <div class="bg-white dark:bg-slate-800 rounded-md p-6 border border-slate-200 dark:border-slate-700">
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 class="text-2xl font-bold text-slate-900 dark:text-white">
            {{ t('diffTable.title') }}
          </h1>
          <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
            {{ t('diffTable.subtitle', { total: totalSongs, ranks: numericRanks.length }) }}
          </p>
        </div>
        <div class="flex gap-2 shrink-0">
          <button
            @click="expandAll"
            class="px-3 py-1.5 text-xs font-bold rounded-lg bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
          >
            {{ t('diffTable.expandAll') }}
          </button>
          <button
            @click="collapseAll"
            class="px-3 py-1.5 text-xs font-bold rounded-lg bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
          >
            {{ t('diffTable.collapseAll') }}
          </button>
        </div>
      </div>

      <!-- 検索ボックス: 入力のたびに handleSearch で該当ランクを展開 -->
      <div class="mt-4 relative">
        <svg class="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
        </svg>
        <input
          v-model="searchQuery"
          @input="handleSearch"
          type="text"
          :placeholder="t('diffTable.searchPlaceholder')"
          class="w-full pl-9 pr-4 py-2.5 text-sm rounded-md border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-700/50 text-slate-900 dark:text-white placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent transition"
        />
      </div>
    </div>

    <!-- ログイン促進バナー: 未ログイン時のみ表示 -->
    <div
      v-if="!isLoggedIn"
      class="bg-blue-700 dark:bg-blue-600 rounded-md p-6 text-white"
    >
      <div class="flex flex-col sm:flex-row items-center gap-4">
        <div class="w-12 h-12 bg-white/20 rounded-md flex items-center justify-center shrink-0">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-7 w-7" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
          </svg>
        </div>
        <div class="text-center sm:text-left flex-1">
          <h3 class="font-bold text-lg">{{ t('diffTable.ctaTitle') }}</h3>
          <p class="text-blue-100 text-sm mt-1">{{ t('diffTable.ctaDesc') }}</p>
        </div>
      </div>
    </div>

    <!-- ランクリスト: アコーディオン形式でランクごとに曲一覧を展開 -->
    <div v-if="filteredRanks.length === 0" class="text-center py-12 text-slate-400 dark:text-slate-500">
      {{ t('diffTable.noResults') }}
    </div>

    <div v-else class="space-y-3">
      <div
        v-for="rankEntry in filteredRanks"
        :key="rankEntry.rank"
        class="bg-white dark:bg-slate-800 rounded-md border overflow-hidden transition-colors"
        :class="rankBadgeBg(rankEntry.rank)"
      >
        <!-- ランクヘッダー（クリックで展開／折りたたみトグル） -->
        <button
          class="w-full flex items-center justify-between px-5 py-4 text-left hover:bg-black/5 dark:hover:bg-white/5 transition-colors"
          :aria-expanded="expandedRanks.has(rankEntry.rank)"
          :aria-controls="`diff-rank-panel-${rankEntry.rank}`"
          @click="toggleRank(rankEntry.rank)"
        >
          <div class="flex items-center gap-3">
            <span
              class="text-2xl font-bold min-w-[3.5rem]"
              :class="rankColor(rankEntry.rank)"
            >
              ☆{{ rankEntry.rank }}
            </span>
            <span class="text-sm text-slate-500 dark:text-slate-400 font-medium">
              {{ rankEntry.songs.length }}{{ t('diffTable.songs') }}
            </span>
          </div>
          <svg
            class="h-5 w-5 text-slate-400 transition-transform duration-200"
            :class="{ 'rotate-180': expandedRanks.has(rankEntry.rank) }"
            xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor"
          >
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
          </svg>
        </button>

        <!-- 曲リスト: 3カラムグリッドで並べる。曲名末尾 [L] は LEGGENDARIA バッジとして装飾 -->
        <div v-if="expandedRanks.has(rankEntry.rank)" :id="`diff-rank-panel-${rankEntry.rank}`" class="px-5 pb-4">
          <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-x-4 gap-y-1">
            <div
              v-for="song in rankEntry.songs"
              :key="song"
              class="py-1.5 text-sm text-slate-700 dark:text-slate-300 border-b border-slate-100 dark:border-slate-700/50 last:border-0 truncate"
            >
              <span
                v-if="song.endsWith('[L]')"
                class="inline-block mr-1.5 text-xs font-bold text-purple-600 dark:text-purple-400 bg-purple-100 dark:bg-purple-900/30 px-1.5 rounded"
              >L</span>
              {{ song.endsWith('[L]') ? song.slice(0, -3) : song }}
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* scoped: このコンポーネント内の要素のみに適用されるフェードインアニメーション */
.animate-fade-in {
  animation: fadeIn 0.3s ease-out forwards;
}
@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
