<script setup lang="ts">
/**
 * 【コンポーネントの役割】 指定された非公式難易度（☆11.0〜☆13.0）のフォルダ単体ランキングをモーダル表示する。
 * - props.rank に応じて `/api/scores/ranking/by-rank?rank=...` を取得
 * - 各ユーザーの「平均スコアレート → フォルダランク」を算出してティアアイコンを表示
 *   ・全曲プレイ済みでない場合は薄いアイコン（opacity-30）
 * - 自分の行までジャンプする「自分の順位を表示」ボタン付き、1 ページ 50 件のページネーション
 *
 * @prop rank        対象難易度（"11.0" 〜 "13.0"）
 * @prop totalCount  そのフォルダの総曲数（薄表示判定用）
 * @emits close      閉じる要求
 */
import { ref, computed, watch, nextTick, onMounted } from 'vue';
import { useAuth } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';
import { getFolderRankInfoByRate } from '../utils/beatTier';
import RankIcon from './RankIcon.vue';

const props = defineProps<{
  rank: string;
  totalCount: number;
}>();

const emit = defineEmits<{ (e: 'close'): void }>();

const { t } = useI18n();
const { user } = useAuth();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

interface RankingEntry {
  userId: number | null;
  displayName: string;
  iidxId: string;
  privacyLevel: number | null;
  totalBeatPt: number;
  totalScore: number;
  totalMaxScore: number;
  playedCount: number;
  isSupporter?: boolean;
  lastUpdatedAt?: string | null;
}

const ranking = ref<RankingEntry[]>([]);
const isLoading = ref(true);
const error = ref('');
/** 全曲プレイ済みのユーザーのみ表示するトグル。 */
const onlyFullPlay = ref(true);

const PAGE_SIZE = 50;
const page = ref(1);

/** トグルに応じてフィルタしたランキング（順位は元のランクを維持せず、表示時の連番）。 */
const filteredRanking = computed(() => {
  if (!onlyFullPlay.value) return ranking.value;
  return ranking.value.filter(r => r.playedCount >= props.totalCount);
});

const totalPages = computed(() => Math.max(1, Math.ceil(filteredRanking.value.length / PAGE_SIZE)));

const paginated = computed(() => {
  const start = (page.value - 1) * PAGE_SIZE;
  return filteredRanking.value.slice(start, start + PAGE_SIZE);
});

// フィルタ切替で 1 ページ目に戻す。
watch(onlyFullPlay, () => { page.value = 1; });

async function fetchRanking() {
  isLoading.value = true;
  error.value = '';
  try {
    const res = await fetch(`${API_BASE}/api/scores/ranking/by-rank?rank=${encodeURIComponent(props.rank)}`);
    if (!res.ok) throw new Error('failed');
    const raw = await res.json();
    ranking.value = (raw as any[]).map((r: any) => ({
      userId: r.userId ?? null,
      displayName: r.displayName ?? '',
      iidxId: r.iidxId ?? '',
      privacyLevel: r.privacyLevel ?? 1,
      totalBeatPt: Number(r.totalBeatPt ?? 0),
      totalScore: Number(r.totalScore ?? 0),
      totalMaxScore: Number(r.totalMaxScore ?? 0),
      playedCount: Number(r.playedCount ?? 0),
      isSupporter: !!r.isSupporter,
      lastUpdatedAt: r.lastUpdatedAt ?? null,
    }));
  } catch (e) {
    console.error(e);
    error.value = t('ranking.error');
  } finally {
    isLoading.value = false;
  }
}

onMounted(fetchRanking);
watch(() => props.rank, () => {
  page.value = 1;
  fetchRanking();
});

function averageRate(entry: RankingEntry): number {
  return entry.totalMaxScore > 0 ? (entry.totalScore / entry.totalMaxScore) * 100 : 0;
}

function tierFor(entry: RankingEntry) {
  return getFolderRankInfoByRate(averageRate(entry), props.rank);
}

const myIidxId = computed(() => user.value?.iidxId);

function goToMyRank() {
  if (!myIidxId.value) return;
  const idx = filteredRanking.value.findIndex(r => r.iidxId === myIidxId.value);
  if (idx === -1) return;
  page.value = Math.floor(idx / PAGE_SIZE) + 1;
  nextTick(() => {
    const row = document.getElementById(`diff-ranking-row-${myIidxId.value}`);
    if (row) row.scrollIntoView({ behavior: 'smooth', block: 'center' });
  });
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50 flex items-center justify-center p-2 sm:p-4">
      <div class="fixed inset-0 bg-black/50 backdrop-blur-sm" @click="emit('close')"></div>
      <div class="relative z-10 bg-white dark:bg-slate-800 rounded-xl sm:rounded-2xl shadow-2xl border border-slate-200 dark:border-slate-700 w-full max-w-3xl max-h-[90vh] flex flex-col">
        <!-- Header -->
        <div class="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700 shrink-0">
          <div class="flex items-center gap-2">
            <span class="inline-block w-2.5 h-2.5 rounded-full" :class="parseFloat(props.rank) >= 12.5 ? 'bg-purple-500 dark:bg-purple-400' : 'bg-blue-500 dark:bg-blue-400'"></span>
            <h3 class="font-bold text-sm sm:text-base text-slate-800 dark:text-slate-100">
              ☆{{ props.rank }} {{ t('table.difficultyRankingTitle') }}
            </h3>
          </div>
          <button @click="emit('close')" aria-label="閉じる" class="w-7 h-7 rounded-full bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-500 dark:text-slate-400 font-bold text-sm flex items-center justify-center transition-colors shrink-0 ml-2">×</button>
        </div>

        <!-- Toolbar: find-my-rank + 全曲プレイ済みフィルタ -->
        <div v-if="!isLoading && !error && ranking.length > 0" class="px-4 pt-3 flex items-center justify-between gap-3 flex-wrap shrink-0">
          <button
            v-if="user"
            @click="goToMyRank"
            class="flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-bold bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 border border-blue-200 dark:border-blue-700 hover:bg-blue-100 dark:hover:bg-blue-900/50 transition-colors"
          >
            <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" /></svg>
            {{ t('ranking.findMyRank') }}
          </button>
          <label class="flex items-center gap-2 cursor-pointer group whitespace-nowrap ml-auto">
            <div class="relative inline-flex items-center">
              <input type="checkbox" v-model="onlyFullPlay" class="sr-only peer">
              <div class="w-9 h-5 bg-slate-200 dark:bg-slate-700 rounded-full peer peer-checked:bg-amber-500 dark:peer-checked:bg-amber-600 transition-colors"></div>
              <div class="absolute left-0.5 top-0.5 w-4 h-4 bg-white rounded-full transition-transform peer-checked:translate-x-4"></div>
            </div>
            <span class="text-xs font-bold text-slate-600 dark:text-slate-400 group-hover:text-slate-800 dark:group-hover:text-slate-200 transition-colors">{{ t('table.onlyFullPlay') }}</span>
          </label>
        </div>

        <!-- Body -->
        <div class="overflow-auto flex-1 px-2 sm:px-4 py-3">
          <div v-if="isLoading" class="flex flex-col items-center justify-center py-16">
            <div class="w-10 h-10 border-4 border-blue-100 dark:border-slate-700 border-t-blue-600 dark:border-t-blue-500 rounded-full animate-spin mb-3"></div>
            <p class="text-slate-500 dark:text-slate-400 font-bold text-sm">{{ t('ranking.loading') }}</p>
          </div>

          <div v-else-if="error" class="p-6 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-2xl text-center font-bold text-sm">
            {{ error }}
          </div>

          <div v-else-if="ranking.length === 0" class="text-center py-16 border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-2xl">
            <p class="text-slate-500 dark:text-slate-400 font-bold text-sm">{{ t('ranking.empty') }}</p>
          </div>

          <div v-else-if="filteredRanking.length === 0" class="text-center py-16 border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-2xl">
            <p class="text-slate-500 dark:text-slate-400 font-bold text-sm">{{ t('table.noFullPlayUsers') }}</p>
          </div>

          <table v-else-if="paginated.length > 0" class="w-full">
            <thead>
              <tr class="text-left border-b border-slate-100 dark:border-slate-700/50">
                <th class="pb-3 pl-2 text-xs font-black text-slate-400 uppercase tracking-widest w-14">{{ t('ranking.colRank') }}</th>
                <th class="pb-3 text-xs font-black text-slate-400 uppercase tracking-widest">{{ t('ranking.colPlayer') }}</th>
                <th class="pb-3 text-xs font-black text-slate-400 uppercase tracking-widest w-14 text-center">{{ t('ranking.colTier') }}</th>
                <th class="pb-3 text-xs font-black text-slate-400 uppercase tracking-widest text-right pr-2">{{ t('table.colTotalPt') }}</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
              <tr
                v-for="(entry, idx) in paginated"
                :key="entry.iidxId || `anon-${idx}`"
                :id="`diff-ranking-row-${entry.iidxId}`"
                class="transition-colors"
                :class="user && entry.iidxId === user.iidxId
                  ? 'bg-blue-50 dark:bg-blue-900/20 border-l-4 border-l-blue-500'
                  : 'hover:bg-slate-50 dark:hover:bg-slate-700/30'"
              >
                <td class="py-2 pl-2">
                  <div
                    class="flex items-center justify-center w-7 h-7 rounded-lg font-black text-xs"
                    :class="[
                      ((page - 1) * PAGE_SIZE + idx) === 0 ? 'bg-amber-100 text-amber-700 dark:bg-amber-500 dark:text-white' :
                      ((page - 1) * PAGE_SIZE + idx) === 1 ? 'bg-slate-200 text-slate-700 dark:bg-slate-400 dark:text-white' :
                      ((page - 1) * PAGE_SIZE + idx) === 2 ? 'bg-orange-100 text-orange-700 dark:bg-orange-400 dark:text-white' :
                      user && entry.iidxId === user.iidxId ? 'bg-blue-500 text-white' :
                      'text-slate-400 border border-slate-100 dark:border-slate-700'
                    ]"
                  >
                    {{ (page - 1) * PAGE_SIZE + idx + 1 }}
                  </div>
                </td>
                <td class="py-2">
                  <div class="flex items-center gap-2 flex-wrap">
                    <span class="font-bold text-sm text-slate-800 dark:text-slate-100">
                      {{ entry.displayName || 'Unnamed Player' }}
                    </span>
                    <span v-if="(entry.privacyLevel ?? 1) !== 0" class="text-xs text-slate-400" :title="(entry.privacyLevel ?? 1) === 2 ? '非公開' : 'フレンドのみ公開'">🔒</span>
                    <span v-if="user && entry.iidxId === user.iidxId"
                      class="text-[9px] font-black uppercase tracking-wider px-1.5 py-0.5 rounded bg-blue-500 text-white">{{ t('ranking.you') }}</span>
                    <span class="text-[10px] font-bold text-slate-400 dark:text-slate-500">
                      {{ entry.playedCount }}/{{ props.totalCount }} {{ t('table.colPlayed') }}
                    </span>
                  </div>
                </td>
                <td class="py-2 px-1 text-center">
                  <div class="flex justify-center">
                    <RankIcon
                      :rank-name="tierFor(entry).name"
                      :tier="tierFor(entry).tier"
                      size="sm"
                      disable-party
                      :is-supporter="entry.isSupporter"
                      :class="entry.playedCount < props.totalCount ? 'opacity-30' : ''"
                    />
                  </div>
                </td>
                <td class="py-2 text-right pr-2">
                  <div class="flex items-baseline justify-end gap-1">
                    <span class="text-base sm:text-lg font-black tabular-nums"
                      :class="user && entry.iidxId === user.iidxId ? 'text-blue-700 dark:text-blue-300' : 'text-slate-800 dark:text-slate-100'">
                      {{ entry.totalBeatPt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}
                    </span>
                    <span class="text-[9px] font-bold text-slate-400 uppercase tracking-widest">PT</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Pagination -->
        <div v-if="!isLoading && !error && totalPages > 1" class="flex items-center justify-center gap-2 py-3 border-t border-slate-100 dark:border-slate-700/50 shrink-0">
          <button @click="page = 1" :disabled="page === 1"
            class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
            &laquo;
          </button>
          <button @click="page--" :disabled="page === 1"
            class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
            &lsaquo;
          </button>
          <span class="text-xs font-bold text-slate-500 dark:text-slate-400 px-2 tabular-nums">
            {{ page }} / {{ totalPages }}
          </span>
          <button @click="page++" :disabled="page === totalPages"
            class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
            &rsaquo;
          </button>
          <button @click="page = totalPages" :disabled="page === totalPages"
            class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
            &raquo;
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
