<script setup lang="ts">
/**
 * 【コンポーネントの役割】 スコアロードマップのレベルランキングモーダル（管理者専用ページから開く）。
 *
 * カラム: 順位 / ティアアイコン / 名前 / レベル（2026-09-23 ユーザー指定）。
 *  - レベルはサーバーの土台作成時点（3 時間ごとのバッチ）の値。判定規則はロードマップ画面と同じ
 *    （ScoreRoadmapService.computeRanking）。同じレベルは同順位。
 *  - ティアアイコンは現行作の総合 BEAT-PT から（リーグのランキングと同じく前作ティアの枠付き）。
 *  - 行をクリックすると、そのユーザーのロードマップを表示する（select を emit）。
 *
 * API: `/api/scores/score-roadmap/ranking`（管理者専用）。
 */
import { ref, computed, onMounted } from 'vue';
import { useAuth } from '../composables/useAuth';
import { getRankInfo, previousTierFrame } from '../utils/beatTier';
import { formatJstDateTime } from '../utils/jstTime';
import RankIcon from './RankIcon.vue';

const props = withDefaults(defineProps<{
  /** 強調表示するユーザー（ロードマップで表示中の人）。 */
  highlightUserId?: number | null;
}>(), { highlightUserId: null });

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'select', userId: number, displayName: string | null): void;
}>();

interface Entry {
  rank: number; userId: number; displayName: string | null; level: number;
  clearedLevels: number; completeLevels: number; totalBeatPt: number; previousBeatPt: number | null;
}

const { authHeaders } = useAuth();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

const entries = ref<Entry[]>([]);
const maxLevel = ref<number | null>(null);
const computedAt = ref<string | null>(null);
const loading = ref(true);
const error = ref('');
const query = ref('');

onMounted(async () => {
  try {
    const res = await fetch(`${API_BASE}/api/scores/score-roadmap/ranking`, { headers: authHeaders() });
    if (!res.ok) throw new Error(res.status === 403 ? '管理者専用です' : `APIエラー: ${res.status}`);
    const data = await res.json();
    if (!data.ready) throw new Error('ロードマップの集計がまだ終わっていません。少し待ってから開き直してください。');
    entries.value = data.entries;
    maxLevel.value = data.maxLevel;
    computedAt.value = data.computedAt;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
});

/** 名前で絞り込み（順位は全体での順位のまま）。 */
const shown = computed(() => {
  const q = query.value.trim().toLowerCase();
  return q ? entries.value.filter((e) => (e.displayName ?? '').toLowerCase().includes(q)) : entries.value;
});

/** 総合 BEAT-PT から Beat-Tier ランク情報（ティアアイコン用）。 */
const beatTier = (pt: number | null) => getRankInfo(pt ?? 0);

function choose(e: Entry) {
  emit('select', e.userId, e.displayName);
  emit('close');
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[110] flex items-center justify-center p-4 animate-fade-in">
      <div class="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" @click="$emit('close')"></div>

      <div class="relative w-full max-w-xl max-h-[85vh] flex flex-col bg-white dark:bg-slate-800 rounded-2xl shadow-2xl overflow-hidden transition-colors duration-200">
        <div class="px-6 py-4 border-b border-slate-100 dark:border-slate-700/50 flex justify-between items-center">
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-100">ロードマップ レベルランキング</h3>
          <button
            class="p-2 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-full transition-all"
            aria-label="閉じる"
            @click="$emit('close')"
          >
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div class="flex-1 overflow-y-auto custom-scrollbar px-6 pb-6">
          <div class="sticky top-0 bg-white dark:bg-slate-800 pt-4 pb-3 z-10">
            <p class="text-xs leading-relaxed text-slate-500 dark:text-slate-400">
              達成しているレベルのうち一番高いもので並べています（同じレベルは同順位）。
              <template v-if="computedAt">{{ formatJstDateTime(computedAt) }} 時点の集計（3 時間ごとに更新）。</template>
              行を押すとその人のロードマップを表示します。
            </p>
            <input
              id="roadmap-ranking-search"
              v-model="query"
              type="text"
              autocomplete="off"
              placeholder="名前で絞り込み"
              class="mt-2 w-full px-3 py-1.5 text-sm rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 text-slate-800 dark:text-slate-100"
            />
          </div>

          <div v-if="loading" class="mt-6 text-center text-sm text-slate-400 dark:text-slate-500">読み込み中...</div>
          <div v-else-if="error"
               class="mt-4 rounded-lg border border-rose-200 dark:border-rose-800 bg-rose-50 dark:bg-rose-900/30 px-3 py-2 text-sm text-rose-700 dark:text-rose-300">
            {{ error }}
          </div>
          <div v-else-if="!shown.length" class="mt-6 text-center text-sm text-slate-400 dark:text-slate-500">該当するユーザーがいません</div>

          <table v-else class="w-full text-sm">
            <thead>
              <tr class="text-left text-xs text-slate-400 dark:text-slate-500 border-b border-slate-200 dark:border-slate-700">
                <th class="py-1.5 pr-2 w-12">順位</th>
                <th class="py-1.5 pr-2 w-10">ティア</th>
                <th class="py-1.5 pr-2">名前</th>
                <th class="py-1.5 pl-2 text-right w-24">レベル</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in shown"
                :key="row.userId"
                class="border-b border-slate-100 dark:border-slate-700/50 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-700/40"
                :class="row.userId === props.highlightUserId ? 'bg-indigo-50 dark:bg-indigo-900/30 font-semibold' : ''"
                @click="choose(row)"
              >
                <td class="py-1.5 pr-2 tabular-nums text-slate-600 dark:text-slate-300">{{ row.rank }}</td>
                <td class="py-1.5 pr-2">
                  <RankIcon
                    :rank-name="beatTier(row.totalBeatPt).name"
                    :tier="beatTier(row.totalBeatPt).tier"
                    size="2xs"
                    lite
                    disable-party
                    v-bind="previousTierFrame(row.previousBeatPt, 'beat')"
                  />
                </td>
                <td class="py-1.5 pr-2 break-words text-slate-700 dark:text-slate-200">{{ row.displayName ?? '(名前なし)' }}</td>
                <td class="py-1.5 pl-2 text-right tabular-nums font-semibold text-slate-800 dark:text-slate-100">
                  Lv.{{ row.level }}<span v-if="maxLevel" class="text-xs font-normal text-slate-400"> / {{ maxLevel }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </Teleport>
</template>
