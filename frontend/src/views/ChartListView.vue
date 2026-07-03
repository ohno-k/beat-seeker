<script setup lang="ts">
/**
 * 【Viewの役割】 譜面一覧ページ。全譜面を曲名検索・難易度・レベル・BPM でフィルタ／ソート表示する。
 *
 * 機能:
 *  - 曲名／アーティスト名のフリーワード検索。
 *  - 難易度（BEGINNER〜LEGGENDARIA）とレベル（1〜12+）のフィルタ。
 *  - タイトル・レベル・ノーツ数・BPM の各列でソート切替。
 *  - 1ページ50件のページネーション。
 *  - textage.cc へのリンク列（該当譜面の外部プレビュー）。
 *
 * 依存:
 *  - `useGameData.songData` — 全譜面データ（リアクティブ）。
 *  - `useI18n` — 多言語。
 */
import { ref, computed } from 'vue';
import { useI18n } from '../composables/useI18n';
import { songData as songDataBody } from '../composables/useGameData';
import type { SongDataEntry } from '../composables/useGameData';

const { t } = useI18n();

/** 難易度コード→表示用情報のマップ（名称・文字色・背景色） */
const DIFF_MAP: Record<string, { name: string; color: string; bg: string }> = {
  '1': { name: 'BEGINNER', color: 'text-emerald-600 dark:text-emerald-400', bg: 'bg-emerald-100 dark:bg-emerald-900/40' },
  '2': { name: 'NORMAL', color: 'text-blue-600 dark:text-blue-400', bg: 'bg-blue-100 dark:bg-blue-900/40' },
  '3': { name: 'HYPER', color: 'text-amber-600 dark:text-amber-400', bg: 'bg-amber-100 dark:bg-amber-900/40' },
  '4': { name: 'ANOTHER', color: 'text-red-600 dark:text-red-400', bg: 'bg-red-100 dark:bg-red-900/40' },
  '10': { name: 'LEGGENDARIA', color: 'text-purple-600 dark:text-purple-400', bg: 'bg-purple-100 dark:bg-purple-900/40' },
};

/** 検索クエリ。title／artist に対する部分一致 */
const searchQuery = ref('');
/** 難易度フィルタ。空文字なら全難易度 */
const selectedDifficulty = ref<string>('');
/** レベルフィルタ。空文字なら全レベル */
const selectedLevel = ref<number | ''>('');
/** ソート対象カラム */
const sortKey = ref<'title' | 'level' | 'notes' | 'bpm'>('level');
/** ソート方向 */
const sortDir = ref<'asc' | 'desc'>('desc');
/** 現在のページ番号（1始まり） */
const currentPage = ref(1);
/** 1ページあたりの表示件数 */
const PAGE_SIZE = 50;

/**
 * 【関数の役割】 BPM 文字列（例: "140", "140-200", "140/200"）から最大値を数値で取り出す。
 * ソフラン譜面は複数値が "/" や "-" で列挙されるため、最大値でソートする方針。
 */
const parseBpmNum = (bpm: string): number => {
  if (!bpm) return 0;
  const nums = bpm.match(/\d+/g);
  if (!nums) return 0;
  return Math.max(...nums.map(Number));
};

/** 全譜面データから出現するレベル値を重複排除＋昇順で取得。プルダウン選択肢用 */
const availableLevels = computed(() => {
  const levels = new Set<number>();
  for (const s of songDataBody.value) {
    if (s.level) levels.add(s.level);
  }
  return [...levels].sort((a, b) => a - b);
});

/** 検索・難易度・レベルの複合フィルタを順に適用した配列 */
const filtered = computed(() => {
  let rows = songDataBody.value;

  // 曲名 or アーティスト名に含まれるか判定（大文字小文字無視）
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase();
    rows = rows.filter(s => s.title.toLowerCase().includes(q) || s.artist.toLowerCase().includes(q));
  }

  // 難易度フィルタ（厳密一致）
  if (selectedDifficulty.value) {
    rows = rows.filter(s => s.difficulty === selectedDifficulty.value);
  }

  // レベルフィルタ（数値一致）
  if (selectedLevel.value !== '') {
    rows = rows.filter(s => s.level === selectedLevel.value);
  }

  return rows;
});

/** フィルタ済み配列をソートした結果。`sortKey` に応じて比較関数を切替 */
const sorted = computed(() => {
  const arr = [...filtered.value];
  const dir = sortDir.value === 'asc' ? 1 : -1;

  arr.sort((a, b) => {
    switch (sortKey.value) {
      case 'title':
        // 日本語ロケールで localeCompare。ひらがな／カタカナ／漢字を適切に並べる
        return dir * a.title.localeCompare(b.title, 'ja');
      case 'level':
        return dir * ((a.level || 0) - (b.level || 0));
      case 'notes':
        return dir * ((a.notes || 0) - (b.notes || 0));
      case 'bpm':
        return dir * (parseBpmNum(a.bpm) - parseBpmNum(b.bpm));
      default:
        return 0;
    }
  });

  return arr;
});

/** ソート済み件数を PAGE_SIZE で割った総ページ数（最低1ページ） */
const totalPages = computed(() => Math.max(1, Math.ceil(sorted.value.length / PAGE_SIZE)));

/** 現ページに表示すべきスライス */
const paged = computed(() => {
  const start = (currentPage.value - 1) * PAGE_SIZE;
  return sorted.value.slice(start, start + PAGE_SIZE);
});

/**
 * 【関数の役割】 カラムヘッダクリック時のソート切替。
 * 同じキーなら昇降反転、別キーなら切替＋初期方向を決定（title だけは昇順開始）。
 * 常に1ページ目にリセットして表示ズレを防ぐ。
 */
const toggleSort = (key: typeof sortKey.value) => {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc';
  } else {
    sortKey.value = key;
    sortDir.value = key === 'title' ? 'asc' : 'desc';
  }
  currentPage.value = 1;
};

/** 現在のソート対象なら三角記号（▲/▼）を、違うなら空文字を返すヘルパー */
const sortIcon = (key: string) => {
  if (sortKey.value !== key) return '';
  return sortDir.value === 'asc' ? '\u25B2' : '\u25BC';
};

/** 難易度コードを DIFF_MAP から引く。未知のコードはデフォルト色でフォールバック */
const getDiff = (d: string) => DIFF_MAP[d] || { name: d, color: 'text-slate-600', bg: 'bg-slate-100' };

/** textage.cc の譜面閲覧URLを生成（空なら空文字） */
const textageUrl = (s: SongDataEntry) => {
  if (!s.textage) return '';
  return `https://textage.cc/score/${s.textage}`;
};
</script>

<template>
  <div class="space-y-6">
    <!-- ヘッダー: ページタイトルと説明 -->
    <div>
      <h1 class="text-2xl font-bold text-slate-900 dark:text-white">{{ t('chartList.title') }}</h1>
      <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">{{ t('chartList.subtitle') }}</p>
    </div>

    <!-- フィルタ領域: 検索＋難易度＋レベル＋結果件数表示 -->
    <div class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 p-4">
      <div class="flex flex-wrap gap-3 items-center">
        <!-- 検索入力: 入力のたびに1ページ目に戻す -->
        <div class="relative flex-1 min-w-[200px]">
          <svg xmlns="http://www.w3.org/2000/svg" class="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
          </svg>
          <input
            v-model="searchQuery"
            type="text"
            :placeholder="t('chartList.searchPlaceholder')"
            class="w-full pl-10 pr-4 py-2.5 rounded-md border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900/50 text-sm text-slate-900 dark:text-white placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            @input="currentPage = 1"
          />
        </div>

        <!-- 難易度フィルタ: DIFF_MAP のキーをプルダウン化 -->
        <select
          v-model="selectedDifficulty"
          class="px-4 py-2.5 rounded-md border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900/50 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
          @change="currentPage = 1"
        >
          <option value="">{{ t('chartList.allDifficulties') }}</option>
          <option v-for="(info, code) in DIFF_MAP" :key="code" :value="code">{{ info.name }}</option>
        </select>

        <!-- レベルフィルタ: 全譜面に存在するレベル値のみ選択肢化 -->
        <select
          v-model="selectedLevel"
          class="px-4 py-2.5 rounded-md border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900/50 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
          @change="currentPage = 1"
        >
          <option value="">{{ t('chartList.allLevels') }}</option>
          <option v-for="lv in availableLevels" :key="lv" :value="lv">Lv.{{ lv }}</option>
        </select>

        <!-- 件数表示: 現在のフィルタに一致する件数 -->
        <span class="text-xs font-bold text-slate-400 dark:text-slate-500 whitespace-nowrap">
          {{ filtered.length.toLocaleString() }} {{ t('chartList.charts') }}
        </span>
      </div>
    </div>

    <!-- 譜面一覧テーブル: ヘッダクリックでソート、行ホバーで色付け -->
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
                {{ t('chartList.colTitle') }} <span aria-label="並び替え">{{ sortIcon('title') }}</span>
              </th>
              <th scope="col" class="px-4 py-3 text-left font-bold text-slate-600 dark:text-slate-400 whitespace-nowrap">
                {{ t('chartList.colArtist') }}
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
                :aria-sort="sortKey === 'notes' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
                class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 cursor-pointer hover:text-blue-600 dark:hover:text-blue-400 select-none whitespace-nowrap"
                @click="toggleSort('notes')"
                @keydown.enter.prevent="toggleSort('notes')"
                @keydown.space.prevent="toggleSort('notes')"
              >
                {{ t('chartList.colNotes') }} <span aria-label="並び替え">{{ sortIcon('notes') }}</span>
              </th>
              <th
                scope="col"
                role="button"
                tabindex="0"
                :aria-sort="sortKey === 'bpm' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
                class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 cursor-pointer hover:text-blue-600 dark:hover:text-blue-400 select-none whitespace-nowrap"
                @click="toggleSort('bpm')"
                @keydown.enter.prevent="toggleSort('bpm')"
                @keydown.space.prevent="toggleSort('bpm')"
              >
                BPM <span aria-label="並び替え">{{ sortIcon('bpm') }}</span>
              </th>
              <th scope="col" class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 whitespace-nowrap">
                {{ t('chartList.colTextage') }}
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-50 dark:divide-slate-700/50">
            <tr
              v-for="(song, i) in paged"
              :key="`${song.title}-${song.difficulty}-${i}`"
              class="hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors"
            >
              <td class="px-4 py-3 font-medium text-slate-900 dark:text-white max-w-[300px] truncate">
                {{ song.title }}
              </td>
              <td class="px-4 py-3 text-slate-500 dark:text-slate-400 max-w-[200px] truncate">
                {{ song.artist }}
              </td>
              <td class="px-3 py-3 text-center">
                <span
                  :class="[getDiff(song.difficulty).color, getDiff(song.difficulty).bg]"
                  class="inline-block px-2 py-0.5 text-[10px] font-bold rounded-md"
                >
                  {{ getDiff(song.difficulty).name }}
                </span>
              </td>
              <td class="px-3 py-3 text-center font-bold text-slate-700 dark:text-slate-300">
                {{ song.level }}
              </td>
              <td class="px-3 py-3 text-center font-mono text-slate-600 dark:text-slate-400">
                {{ song.notes?.toLocaleString() || '-' }}
              </td>
              <td class="px-3 py-3 text-center font-mono text-slate-600 dark:text-slate-400">
                {{ song.bpm || '-' }}
              </td>
              <td class="px-3 py-3 text-center">
                <a
                  v-if="textageUrl(song)"
                  :href="textageUrl(song)"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="inline-flex items-center gap-1 text-blue-500 hover:text-blue-600 dark:text-blue-400 dark:hover:text-blue-300 transition-colors"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
                  </svg>
                </a>
                <span v-else class="text-slate-300 dark:text-slate-600">-</span>
              </td>
            </tr>
            <tr v-if="paged.length === 0">
              <td colspan="7" class="px-4 py-12 text-center text-slate-400 dark:text-slate-500 font-medium">
                {{ t('chartList.noResults') }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- ページネーション: 総ページ数が2以上のときだけ表示 -->
      <div v-if="totalPages > 1" class="flex items-center justify-between px-4 py-3 border-t border-slate-100 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-900/20">
        <button
          :disabled="currentPage <= 1"
          @click="currentPage--"
          class="px-3 py-1.5 text-xs font-bold rounded-lg border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-400 hover:bg-white dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
        >
          {{ t('chartList.prev') }}
        </button>
        <span class="text-xs font-bold text-slate-500 dark:text-slate-400">
          {{ currentPage }} / {{ totalPages }}
        </span>
        <button
          :disabled="currentPage >= totalPages"
          @click="currentPage++"
          class="px-3 py-1.5 text-xs font-bold rounded-lg border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-400 hover:bg-white dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
        >
          {{ t('chartList.next') }}
        </button>
      </div>
    </div>
  </div>
</template>
