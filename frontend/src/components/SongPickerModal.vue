<script setup lang="ts">
/**
 * 【コンポーネントの役割】 strategy_card_songs.json から楽曲を 1 曲選ばせるモーダル。
 *
 * 個人戦 (Competition format = individual4) の試合結果記録で 4 曲のタイトルを入力するために使う。
 * Lv フィルタ + タイトル部分一致検索の 2 軸で絞り込み、クリック 1 回で確定する。
 *
 * モーダルは {@code <Teleport to="body">} で body 直下にポータルする。これによりサイドバー
 * や祖先要素の transform/filter 等で fixed の包含ブロックがずれるのを回避し、画面全体に
 * オーバーレイされる。
 */
import { ref, computed, watch } from 'vue';
import strategySongs from '../data/strategy_card_songs.json';

type Song = { id: number; version: string; title: string; diff: 'A' | 'L'; level: number };
type Genre = 'NOTES' | 'PEAK' | 'CHORD' | 'CHARGE' | 'SCRATCH' | 'SOF-LAN' | 'INSANE';

const props = defineProps<{
  /** モーダル表示状態。 */
  open: boolean;
  /** 既に選ばれている曲タイトル (再オープン時のインジケータ用)。 */
  currentTitle?: string | null;
}>();
const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'select', song: { strategyId: number; title: string; diff: 'A' | 'L'; level: number; genre: Genre }): void;
}>();

const ALL_GENRES: Genre[] = ['NOTES', 'PEAK', 'CHORD', 'CHARGE', 'SCRATCH', 'SOF-LAN', 'INSANE'];
const ALL_LEVELS = [8, 9, 10, 11, 12] as const;

const songsRoot = strategySongs as Record<Genre, Record<string, Song[]>>;

/** Lv フィルタ + 検索クエリ。初期値は Lv 全て。 */
const levelFilter = ref<number | 'ALL'>('ALL');
const search = ref('');

watch(() => props.open, (v) => {
  if (v) {
    // 再オープン時に検索をクリア (前回入力が残らないように)
    search.value = '';
  }
});

/** フィルタ + 検索を適用した結果。曲数が多いので最大 200 件で切る (オーバーフロー時はメッセージ表示)。 */
const MAX_RESULTS = 200;
type Hit = { strategyId: number; title: string; version: string; diff: 'A' | 'L'; level: number; genre: Genre };
const filteredSongs = computed<{ hits: Hit[]; overflow: boolean }>(() => {
  const q = search.value.trim().toLowerCase();
  const levels = levelFilter.value === 'ALL'
    ? ALL_LEVELS.map(String)
    : [String(levelFilter.value)];
  const out: Hit[] = [];
  // ジャンルは絞らず全カテゴリ走査 (json 内のジャンル分類は内部表現としては保持するが、UI 上は隠す)
  for (const g of ALL_GENRES) {
    for (const lv of levels) {
      const arr = songsRoot[g]?.[lv] ?? [];
      for (const s of arr) {
        if (q && !s.title.toLowerCase().includes(q)) continue;
        out.push({
          strategyId: s.id,
          title: s.title,
          version: s.version,
          diff: s.diff,
          level: s.level,
          genre: g,
        });
        if (out.length >= MAX_RESULTS + 1) {
          // 1 件多めに取って overflow 判定に使う
          return { hits: out.slice(0, MAX_RESULTS), overflow: true };
        }
      }
    }
  }
  return { hits: out, overflow: false };
});

const pickSong = (h: Hit) => {
  emit('select', {
    strategyId: h.strategyId,
    title: h.title,
    diff: h.diff,
    level: h.level,
    genre: h.genre,
  });
};
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="fixed inset-0 z-[200] flex items-center justify-center bg-black/60 backdrop-blur-sm p-4"
      @click.self="emit('close')"
    >
      <div class="w-full max-w-3xl bg-white dark:bg-slate-800 rounded-md shadow-xl flex flex-col max-h-[85vh] overflow-hidden">
        <!-- ヘッダ -->
        <div class="px-5 py-3 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between">
          <p class="text-sm font-bold">曲を選択</p>
          <button
            type="button"
            @click="emit('close')"
            class="px-2 py-1 text-xs font-bold text-slate-500 hover:text-slate-700 dark:hover:text-slate-200"
          >×</button>
        </div>

        <!-- フィルタ -->
        <div class="px-5 py-3 border-b border-slate-100 dark:border-slate-700 space-y-2 bg-slate-50 dark:bg-slate-900/40">
          <!-- Lv -->
          <div class="flex flex-wrap gap-1 items-center text-xs">
            <span class="text-[10px] font-mono text-slate-400 w-16">Lv</span>
            <button
              type="button"
              @click="levelFilter = 'ALL'"
              class="px-2 py-1 rounded font-bold transition-colors"
              :class="levelFilter === 'ALL'
                ? 'bg-blue-600 text-white'
                : 'bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-200 hover:bg-slate-300 dark:hover:bg-slate-600'"
            >全て</button>
            <button
              v-for="lv in ALL_LEVELS"
              :key="lv"
              type="button"
              @click="levelFilter = lv"
              class="px-2 py-1 rounded font-bold transition-colors"
              :class="levelFilter === lv
                ? 'bg-blue-600 text-white'
                : 'bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-200 hover:bg-slate-300 dark:hover:bg-slate-600'"
            >Lv {{ lv }}</button>
          </div>
          <!-- 検索 -->
          <input
            v-model="search"
            type="text"
            placeholder="曲タイトルで絞り込み (部分一致)"
            class="w-full px-3 py-1.5 text-sm rounded-lg bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400"
          />
        </div>

        <!-- リスト -->
        <div class="flex-1 overflow-y-auto divide-y divide-slate-100 dark:divide-slate-700/60">
          <p
            v-if="filteredSongs.hits.length === 0"
            class="px-5 py-10 text-center text-sm text-slate-400 italic"
          >該当する曲がありません</p>
          <button
            v-for="h in filteredSongs.hits"
            :key="`${h.genre}-${h.strategyId}`"
            type="button"
            @click="pickSong(h)"
            class="w-full text-left px-5 py-2 hover:bg-blue-50 dark:hover:bg-blue-900/30 transition-colors flex items-baseline gap-3"
            :class="currentTitle === h.title ? 'bg-blue-50 dark:bg-blue-900/20' : ''"
          >
            <span class="shrink-0 text-[10px] font-mono text-slate-400 tabular-nums w-12 text-right">#{{ h.strategyId }}</span>
            <span class="flex-1 min-w-0">
              <p class="font-bold text-sm truncate">{{ h.title }}</p>
              <p class="text-[10px] font-mono text-slate-400 mt-0.5">
                {{ h.version }} · {{ h.diff === 'L' ? 'LEGGENDARIA' : 'ANOTHER' }} · Lv {{ h.level }}
              </p>
            </span>
          </button>
          <p
            v-if="filteredSongs.overflow"
            class="px-5 py-3 text-center text-[11px] text-slate-400 italic border-t border-slate-100 dark:border-slate-700/60"
          >該当多数 ({{ MAX_RESULTS }} 件まで表示)。さらに絞り込んでください。</p>
        </div>
      </div>
    </div>
  </Teleport>
</template>
