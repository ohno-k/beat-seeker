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
  CURRENT_VERSION,
  SUPPORTED_VERSIONS,
  versionBadgeClass,
  versionChartColor,
  versionName,
} from '../utils/iidxVersions';

const { t } = useI18n();
const {
  summary,
  fetchSummary,
  deletePastVersion,
  fetchPastBest,
  buildChartHistories,
  pastRows,
} = usePastScores();

const props = defineProps<{
  /** 現行作の登録譜面数。省略時は provide された scoreData から算出する。 */
  currentChartCount?: number;
}>();

const emit = defineEmits<{
  /**
   * 作品ラベルがクリックされた。親（ProfileDashboard → App.vue）が
   * その作品のスコア一覧ページへ遷移させる。
   */
  (e: 'open-version', version: number): void;
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
    /**
     * スコア一覧ページを開けるか。
     * 現行作はスコアを取り込み済みなら、過去作は取り込み済みなら開ける（未取込は行き先が空になるので開かせない）。
     */
    canOpen: v.current ? (currentChartCount.value ?? 0) > 0 : !!entry && entry.chartCount > 0,
  };
}));

/** 【関数の役割】 作品ラベルのクリック。その作品のスコア一覧ページへの遷移を親に依頼する。 */
const openVersion = (version: number) => {
  emit('open-version', version);
};

/** ISO 日時文字列を "YYYY-MM-DD" に丸める（時刻までは管理画面に不要）。 */
const formatDate = (iso: string | null): string => {
  if (!iso) return '';
  return iso.slice(0, 10);
};

// ── 歴代ベストの作品内訳（円グラフ）──────────────────────────────
/** 円グラフ用に `/past/best` を取得中かどうか。 */
const isPieLoading = ref(false);

/** 現行作のフラットなスコア。歴代ベスト判定の一方の入力。 */
const currentRecords = computed(() => flattenScores(injectedScores?.value ?? []));

/**
 * 【computed の役割】 「各譜面の歴代ベストスコアがどの作品のものか」を作品別に数える。
 *
 * {@link buildChartHistories} は同点なら新しい作品を勝たせるので、
 * 現行作が過去作に並んでいる譜面は現行作としてカウントされる。
 * つまり過去作のスライスは「まだ当時の自分を超えられていない譜面」の数になる。
 *
 * 過去作を 1 作も取り込んでいない（= `pastRows` が空）場合は、
 * 全譜面が現行作の 1 色になって情報量が無いので描画しない。
 */
const versionPie = computed(() => {
  if (pastRows.value.length === 0) return null;

  const counts = new Map<number, number>();
  buildChartHistories(currentRecords.value).forEach(h => {
    // 未プレー（ランプだけ残っている等でスコア 0）の譜面は作品を語れないので除外する。
    if (h.bestScore.score <= 0) return;
    counts.set(h.bestScore.version, (counts.get(h.bestScore.version) ?? 0) + 1);
  });

  const total = Array.from(counts.values()).reduce((a, b) => a + b, 0);
  if (total === 0) return null;

  // 新しい作品から時計回りに並べる（テーブルの行順と揃える）。
  const items = SUPPORTED_VERSIONS
    .map(v => ({ version: v.num, name: versionName(v.num), count: counts.get(v.num) ?? 0 }))
    .filter(e => e.count > 0);

  // pathLength=100 の円に沿ってドーナツを描くので、長さはすべて % で扱える。
  // スライス間には背景色の隙間を 1 つぶん入れて境界を立たせる（単一スライスのときは不要）。
  const GAP = items.length > 1 ? 0.8 : 0;
  let offset = 0;
  const slices = items.map(e => {
    const pct = (e.count / total) * 100;
    const slice = {
      ...e,
      pct,
      color: versionChartColor(e.version),
      /** 実描画長。GAP を引いても消えないよう下限を設ける。 */
      dash: Math.max(0.4, pct - GAP),
      offset,
    };
    offset += pct;
    return slice;
  });

  return {
    total,
    slices,
    /** 歴代ベストが過去作のままの譜面数（= 現行作でまだ超えていない譜面）。 */
    pastWins: slices.filter(s => s.version !== CURRENT_VERSION).reduce((a, s) => a + s.count, 0),
  };
});

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

  // 円グラフ用の全件データは重い（数千件）ので、過去作を取り込み済みのときだけ後追いで取る。
  if (!summary.value.some(s => s.chartCount > 0)) return;
  isPieLoading.value = true;
  try {
    await fetchPastBest();
  } catch {
    // 取得失敗時は円グラフが出ないだけ。テーブルの表示は妨げない。
  } finally {
    isPieLoading.value = false;
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
              <!-- 取り込み済みの作品ラベルは、その作品のスコア一覧ページへの導線を兼ねる -->
              <component
                :is="row.canOpen ? 'button' : 'div'"
                class="flex items-center gap-2 text-left"
                :class="row.canOpen ? 'group cursor-pointer' : ''"
                :title="row.canOpen ? t('past.manager.openList') : undefined"
                @click="row.canOpen && openVersion(row.num)"
              >
                <span
                  class="px-1.5 py-0.5 text-[10px] font-bold rounded border"
                  :class="versionBadgeClass(row.num)"
                >{{ row.num }}</span>
                <span
                  class="text-slate-800 dark:text-slate-100"
                  :class="row.canOpen ? 'group-hover:text-blue-600 dark:group-hover:text-blue-400 group-hover:underline transition-colors' : ''"
                >{{ row.name }}</span>
                <span
                  v-if="row.current"
                  class="px-1.5 py-0.5 text-[10px] rounded bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300"
                >{{ t('past.manager.current') }}</span>
                <!-- クリックできる行であることの手がかり（ホバーで青くなる） -->
                <svg
                  v-if="row.canOpen"
                  xmlns="http://www.w3.org/2000/svg"
                  class="h-3 w-3 text-slate-300 dark:text-slate-600 group-hover:text-blue-500 dark:group-hover:text-blue-400 transition-colors"
                  fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true"
                >
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M9 5l7 7-7 7" />
                </svg>
              </component>
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

    <!-- 歴代ベストの作品内訳。過去作を取り込むまでは 1 色にしかならないので出さない -->
    <div v-if="isPieLoading || versionPie" class="mt-5 pt-4 border-t border-slate-100 dark:border-slate-700/60">
      <h4 class="text-xs font-bold text-slate-700 dark:text-slate-200">{{ t('past.manager.pieTitle') }}</h4>
      <p class="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">{{ t('past.manager.pieHint') }}</p>

      <div v-if="isPieLoading" class="text-xs text-slate-500 dark:text-slate-400 py-6 text-center">
        {{ t('common.loading') }}
      </div>

      <div v-else-if="versionPie" class="mt-3 flex flex-col sm:flex-row items-center gap-5">
        <!-- ドーナツ本体。pathLength=100 にして dasharray を % として扱う -->
        <div class="relative shrink-0">
          <svg viewBox="0 0 100 100" class="w-28 h-28" role="img" :aria-label="t('past.manager.pieTitle')">
            <g transform="rotate(-90 50 50)">
              <circle
                v-for="s in versionPie.slices"
                :key="s.version"
                cx="50" cy="50" r="40" fill="none"
                pathLength="100"
                stroke-width="15"
                :stroke="s.color"
                :stroke-dasharray="`${s.dash} ${100 - s.dash}`"
                :stroke-dashoffset="-s.offset"
              >
                <title>{{ s.version }} {{ s.name }}: {{ s.count.toLocaleString() }} ({{ s.pct.toFixed(1) }}%)</title>
              </circle>
            </g>
          </svg>
          <!-- 中央に総譜面数。ドーナツの穴を凡例の合計値として使う -->
          <div class="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
            <span class="text-lg font-bold text-slate-800 dark:text-slate-100 tabular-nums leading-none">{{ versionPie.total.toLocaleString() }}</span>
            <span class="text-[9px] text-slate-400 dark:text-slate-500 mt-0.5">{{ t('past.manager.pieTotal') }}</span>
          </div>
        </div>

        <!-- 凡例。色だけに頼らないよう作品名・譜面数・比率を並べる -->
        <div class="w-full min-w-0">
          <ul class="space-y-1.5">
            <li v-for="s in versionPie.slices" :key="s.version">
              <!-- 凡例もテーブルの作品ラベルと同じくスコア一覧ページへの導線にする -->
              <button
                class="group w-full flex items-center gap-2 text-xs text-left rounded px-1 -mx-1 py-0.5 hover:bg-slate-50 dark:hover:bg-slate-700/40 transition-colors"
                :title="t('past.manager.openList')"
                @click="openVersion(s.version)"
              >
                <span class="w-2.5 h-2.5 rounded-sm shrink-0" :style="{ backgroundColor: s.color }"></span>
                <span class="text-slate-700 dark:text-slate-200 truncate group-hover:text-blue-600 dark:group-hover:text-blue-400 group-hover:underline transition-colors">
                  {{ s.version }} {{ s.name }}
                  <span v-if="s.version === CURRENT_VERSION" class="text-slate-400 dark:text-slate-500">({{ t('past.manager.current') }})</span>
                </span>
                <span class="ml-auto shrink-0 tabular-nums text-slate-600 dark:text-slate-300">{{ s.count.toLocaleString() }}</span>
                <span class="shrink-0 w-12 text-right tabular-nums text-slate-400 dark:text-slate-500">{{ s.pct.toFixed(1) }}%</span>
              </button>
            </li>
          </ul>
          <p class="text-[11px] text-slate-500 dark:text-slate-400 mt-2.5">
            {{ t('past.manager.pieSummary', { n: versionPie.pastWins.toLocaleString() }) }}
          </p>
        </div>
      </div>
    </div>

    <p class="text-xs text-slate-500 dark:text-slate-400 mt-3">{{ t('past.notRanked') }}</p>
  </div>
</template>
