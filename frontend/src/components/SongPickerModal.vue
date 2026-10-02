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
import { mdiClose } from '@mdi/js';
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
  <v-dialog
    :model-value="open"
    max-width="768"
    @update:model-value="(v: boolean) => { if (!v) emit('close') }"
  >
    <v-card class="w-full flex flex-col max-h-[85vh]">
      <!-- ヘッダ -->
      <v-card-item class="shrink-0">
        <v-card-title>曲を選択</v-card-title>
        <template #append>
          <v-btn icon variant="text" size="small" aria-label="close" @click="emit('close')">
            <v-icon :icon="mdiClose" />
          </v-btn>
        </template>
      </v-card-item>

      <!-- フィルタ -->
      <v-card-text class="shrink-0 space-y-3 pt-0">
        <!-- Lv -->
        <div class="flex flex-wrap gap-1 items-center">
          <span class="text-caption text-medium-emphasis w-16">Lv</span>
          <v-btn-toggle v-model="levelFilter" mandatory color="primary" variant="outlined" density="compact" divided class="flex-wrap h-auto">
            <v-btn value="ALL" size="small">全て</v-btn>
            <v-btn
              v-for="lv in ALL_LEVELS"
              :key="lv"
              :value="lv"
              size="small"
            >Lv {{ lv }}</v-btn>
          </v-btn-toggle>
        </div>
        <!-- 検索 -->
        <v-text-field
          v-model="search"
          type="text"
          placeholder="曲タイトルで絞り込み (部分一致)"
          hide-details
          class="w-full"
        />
      </v-card-text>
      <v-divider />

      <!-- リスト -->
      <div class="flex-1 overflow-y-auto">
        <p
          v-if="filteredSongs.hits.length === 0"
          class="px-5 py-10 text-center text-sm text-slate-400 italic"
        >該当する曲がありません</p>
        <v-list v-else lines="two">
          <v-list-item
            v-for="h in filteredSongs.hits"
            :key="`${h.genre}-${h.strategyId}`"
            :active="currentTitle === h.title"
            color="primary"
            :title="h.title"
            :subtitle="`${h.version} · ${h.diff === 'L' ? 'LEGGENDARIA' : 'ANOTHER'} · Lv ${h.level}`"
            @click="pickSong(h)"
          >
            <template #prepend>
              <span class="shrink-0 text-caption font-mono text-medium-emphasis tabular-nums w-12 text-right mr-3">#{{ h.strategyId }}</span>
            </template>
          </v-list-item>
        </v-list>
        <p
          v-if="filteredSongs.overflow"
          class="px-5 py-3 text-center text-[11px] text-slate-400 italic border-t border-slate-100 dark:border-slate-700/60"
        >該当多数 ({{ MAX_RESULTS }} 件まで表示)。さらに絞り込んでください。</p>
      </div>
    </v-card>
  </v-dialog>
</template>
