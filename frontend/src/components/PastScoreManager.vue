<script setup lang="ts">
/**
 * 【コンポーネントの役割】 過去作スコアの取り込み状況を作品別に一覧表示し、作品単位の削除を提供する。
 *
 * 過去作スコアは専用テーブル（`past_scores`）に保存されており、現行作のスコアや
 * ランキング・BEAT-PT とは完全に独立している。そのため作品単位の削除は安全で、
 * 「間違ったファイルを取り込んでしまった」場合の確実なリカバリ手段になる。
 *
 * 取り込み自体は通常の CSV 取り込み UI が担当する（作品は CSV から自動判定される）。
 * ここは状況確認と削除だけを受け持つ。
 */
import { ref, computed, onMounted, inject } from 'vue';
import type { Ref } from 'vue';
import { useI18n } from '../composables/useI18n';
import { usePastScores } from '../composables/usePastScores';
import { flattenScores } from '../utils/scoreData';
import type { ScoreData } from '../types/ScoreData';
import {
  SUPPORTED_VERSIONS,
  versionBadgeClass,
} from '../utils/iidxVersions';

const { t } = useI18n();
const { summary, fetchSummary, deletePastVersion } = usePastScores();

const props = defineProps<{
  /** 現行作の登録譜面数。省略時は provide された scoreData から算出する。 */
  currentChartCount?: number;
}>();

/** App.vue が provide している現行作スコア。現行作の譜面数表示にだけ使う。 */
const injectedScores = inject<Ref<ScoreData[]> | null>('scoreData', null);

/** 現行作の登録譜面数。props 優先、無ければ provide 済みスコアから算出する。 */
const currentChartCount = computed<number | null>(() => {
  if (props.currentChartCount != null) return props.currentChartCount;
  const scores = injectedScores?.value;
  if (!scores || scores.length === 0) return null;
  return flattenScores(scores).length;
});

/** 読み込み中フラグ。 */
const isLoading = ref(true);
/** 削除処理中の作品番号（ボタンの二度押し防止）。 */
const deletingVersion = ref<number | null>(null);
/** エラーメッセージ。 */
const errorMsg = ref('');

/**
 * 表示する行。対応作品（33〜30）を必ず全行出し、未取込の作品も「未取込」として見せる。
 * 「どの作品がまだ取り込めるか」が一目で分かるようにするため、取り込み済みだけを並べない。
 */
const rows = computed(() => SUPPORTED_VERSIONS.map(v => {
  const entry = summary.value.find(s => s.version === v.num);
  return {
    ...v,
    chartCount: v.current ? currentChartCount.value : (entry?.chartCount ?? null),
    importedAt: entry?.importedAt ?? null,
    lastPlayedAt: entry?.lastPlayedAt ?? null,
    /** 削除できるのは過去作のうち取り込み済みのものだけ。現行作は通常スコアなので対象外。 */
    canDelete: !v.current && !!entry && entry.chartCount > 0,
  };
}));

/** ISO 日時文字列を "YYYY-MM-DD" に丸める（時刻までは管理画面に不要）。 */
const formatDate = (iso: string | null): string => {
  if (!iso) return '';
  return iso.slice(0, 10);
};

const load = async () => {
  isLoading.value = true;
  errorMsg.value = '';
  try {
    await fetchSummary();
  } catch (e: any) {
    errorMsg.value = e?.message || t('past.manager.loadFailed');
  } finally {
    isLoading.value = false;
  }
};

/** 【関数の役割】 作品単位の削除。誤操作防止のため確認を挟む。 */
const handleDelete = async (version: number, name: string) => {
  if (!confirm(t('past.manager.deleteConfirm', { name: `${version} ${name}` }))) return;

  deletingVersion.value = version;
  errorMsg.value = '';
  try {
    await deletePastVersion(version);
    await fetchSummary();
  } catch (e: any) {
    errorMsg.value = e?.message || t('past.manager.deleteFailed');
  } finally {
    deletingVersion.value = null;
  }
};

onMounted(load);
</script>

<template>
  <div class="card p-4">
    <h3 class="text-sm font-bold text-slate-800 dark:text-slate-100 mb-1">{{ t('past.manager.title') }}</h3>
    <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">{{ t('past.manager.hint') }}</p>

    <p v-if="errorMsg" class="text-xs text-red-600 dark:text-red-400 mb-2">{{ errorMsg }}</p>

    <div v-if="isLoading" class="text-xs text-slate-500 dark:text-slate-400 py-4 text-center">
      {{ t('common.loading') }}
    </div>

    <div v-else class="overflow-x-auto">
      <table class="w-full text-left text-[11px] sm:text-sm">
        <thead>
          <tr class="text-slate-500 dark:text-slate-400 border-b border-slate-200 dark:border-slate-700">
            <th class="py-1.5 pr-2 font-medium">{{ t('past.manager.colVersion') }}</th>
            <th class="py-1.5 px-2 font-medium text-right">{{ t('past.manager.colCount') }}</th>
            <th class="py-1.5 px-2 font-medium max-sm:hidden">{{ t('past.manager.colImported') }}</th>
            <th class="py-1.5 pl-2"></th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100 dark:divide-slate-700/50">
          <tr v-for="row in rows" :key="row.num">
            <td class="py-2 pr-2">
              <div class="flex items-center gap-2">
                <span
                  class="px-1.5 py-0.5 text-[10px] font-bold rounded border"
                  :class="versionBadgeClass(row.num)"
                >{{ row.num }}</span>
                <span class="text-slate-800 dark:text-slate-100">{{ row.name }}</span>
                <span
                  v-if="row.current"
                  class="px-1.5 py-0.5 text-[10px] rounded bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300"
                >{{ t('past.manager.current') }}</span>
              </div>
            </td>
            <td class="py-2 px-2 text-right tabular-nums text-slate-800 dark:text-slate-100">
              <span v-if="row.chartCount !== null">{{ row.chartCount.toLocaleString() }}</span>
              <span v-else class="text-slate-400 dark:text-slate-500">{{ t('past.manager.notImported') }}</span>
            </td>
            <td class="py-2 px-2 max-sm:hidden tabular-nums text-slate-600 dark:text-slate-300">
              <span v-if="row.current" class="text-slate-400 dark:text-slate-500">{{ t('past.manager.currentNote') }}</span>
              <span v-else>{{ formatDate(row.importedAt) || '—' }}</span>
            </td>
            <td class="py-2 pl-2 text-right">
              <button
                v-if="row.canDelete"
                class="text-xs text-red-600 dark:text-red-400 hover:underline disabled:opacity-50"
                :disabled="deletingVersion === row.num"
                @click="handleDelete(row.num, row.name)"
              >
                {{ deletingVersion === row.num ? t('past.manager.deleting') : t('past.manager.delete') }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <p class="text-xs text-slate-500 dark:text-slate-400 mt-3">{{ t('past.notRanked') }}</p>
  </div>
</template>
