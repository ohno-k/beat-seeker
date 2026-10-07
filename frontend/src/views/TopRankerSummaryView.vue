<script setup lang="ts">
/**
 * 【ビューの役割】 TOP RANKER（作品×地域ごとの仮想ユーザー）のまとめページ。
 *
 * 公式 TOP RANKER ページ（masaoblue 版 / eagate 公式）から取り込んだ「地域ごとの曲別トップスコア」を
 * 1 人の仮想プレイヤーとみなした BEAT-PT / RATE-PT を、作品別・地域別に一覧する。
 *  - 作品別: 作品（歴代 / 各作品）を選び、その作品の全地域を並べる
 *  - 地域別: 地域を選び、その地域の作品ごとの推移を並べる
 * 行を押すと仮想プロフィール（ダッシュボード）へ飛ぶ。
 *
 * API: `/api/scores/ranking/top-rankers`, `/api/scores/rate-ranking/top-rankers`（認証不要）。
 */
import { ref, computed, onMounted } from 'vue';
import { useI18n } from '../composables/useI18n';
import RankIcon from '../components/RankIcon.vue';
import { getRankInfo, getRateTierRankInfo } from '../utils/beatTier';

const { t } = useI18n();

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

const emit = defineEmits<{
  (e: 'view-top-ranker', payload: { versionNum: number; versionName: string; prefectureFileNum: number; prefectureName: string }): void;
}>();

interface Row {
  versionNum: number;
  versionName: string;
  prefectureFileNum: number;
  prefectureName: string;
  beatPt: number;
  ratePt: number;
}

const rows = ref<Row[]>([]);
const isLoading = ref(true);
const loadError = ref(false);

/** 'version' = 作品を選んで地域を並べる / 'area' = 地域を選んで作品を並べる。 */
const mode = ref<'version' | 'area'>('version');
/** 作品別モードで選択中の作品番号（0 = 歴代）。 */
const selectedVersion = ref(0);
/** 地域別モードで選択中の地域（prefectureFileNum。0 = 全国）。 */
const selectedArea = ref(0);
const sortKey = ref<'beat' | 'rate'>('beat');

/**
 * 地域の同一判定キー。prefectureFileNum は古い作品で海外エリアの番号を使い回している
 * （51 = タイ/米国 など）ので、地域別モードでは名前で束ねる。
 */
const areaKey = (r: Row) => r.prefectureName;

onMounted(async () => {
  try {
    const [beatRes, rateRes] = await Promise.all([
      fetch(`${API_BASE}/api/scores/ranking/top-rankers`),
      fetch(`${API_BASE}/api/scores/rate-ranking/top-rankers`),
    ]);
    if (!beatRes.ok || !rateRes.ok) throw new Error('fetch failed');
    const beat: Omit<Row, 'ratePt'>[] = await beatRes.json();
    const rate: { versionNum: number; prefectureFileNum: number; ratePt: number }[] = await rateRes.json();
    const rateByKey = new Map(rate.map(r => [`${r.versionNum}:${r.prefectureFileNum}`, r.ratePt]));
    // 古い作品は現行の曲マスタに譜面が無く 0 pt になる。ランキング画面と同じく出さない。
    rows.value = beat
      .map(b => ({ ...b, ratePt: rateByKey.get(`${b.versionNum}:${b.prefectureFileNum}`) ?? 0 }))
      .filter(r => r.beatPt > 0 || r.ratePt > 0);
  } catch {
    loadError.value = true;
  } finally {
    isLoading.value = false;
  }
});

/** 作品の選択肢（歴代を先頭に、新しい作品から）。 */
const versionOptions = computed(() => {
  const m = new Map<number, string>();
  for (const r of rows.value) m.set(r.versionNum, r.versionName);
  return Array.from(m.entries())
    .map(([num, name]) => ({ num, name }))
    .sort((a, b) => (a.num === 0 ? -1 : b.num === 0 ? 1 : b.num - a.num));
});

/** 地域の選択肢（全国 → 都道府県 → 海外の公式順。番号の使い回しは名前で 1 つにまとめる）。 */
const areaOptions = computed(() => {
  const m = new Map<string, number>();
  for (const r of rows.value) {
    const cur = m.get(areaKey(r));
    // 使い回しのある名前は最新作の番号で並べる
    if (cur === undefined || r.versionNum === 0 || r.prefectureFileNum > cur) m.set(areaKey(r), r.prefectureFileNum);
  }
  return Array.from(m.entries())
    .map(([name, num]) => ({ name, num }))
    .sort((a, b) => a.num - b.num);
});

const selectedAreaName = computed(() =>
  areaOptions.value.find(a => a.num === selectedArea.value)?.name ?? '全国');

/** 表示行（選択条件で絞り、PT の降順）。 */
const visibleRows = computed(() => {
  const list = mode.value === 'version'
    ? rows.value.filter(r => r.versionNum === selectedVersion.value)
    : rows.value.filter(r => areaKey(r) === selectedAreaName.value);
  const key = sortKey.value === 'beat' ? 'beatPt' : 'ratePt';
  return [...list].sort((a, b) => b[key] - a[key]);
});

/** 歴代を除く全作品で、全国 TOP の BEAT-PT が最も高い作品（見出しの要約用）。 */
const strongestVersion = computed(() => {
  const nat = rows.value.filter(r => r.versionNum !== 0 && r.prefectureFileNum === 0);
  return nat.sort((a, b) => b.beatPt - a.beatPt)[0] ?? null;
});

const fmt = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 });

const openRow = (r: Row) => {
  emit('view-top-ranker', {
    versionNum: r.versionNum,
    versionName: r.versionName,
    prefectureFileNum: r.prefectureFileNum,
    prefectureName: r.prefectureName,
  });
};
</script>

<template>
  <div class="space-y-4">
    <div class="bg-white dark:bg-slate-800 p-4 sm:p-6 rounded-md border border-slate-200 dark:border-slate-700">
      <h2 class="text-lg sm:text-xl font-bold text-slate-800 dark:text-slate-100">{{ t('topRankers.title') }}</h2>
      <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">{{ t('topRankers.desc') }}</p>
      <p v-if="strongestVersion" class="text-xs text-slate-500 dark:text-slate-400 mt-1">
        {{ t('topRankers.strongest', { name: strongestVersion.versionName, pt: fmt(strongestVersion.beatPt) }) }}
      </p>

      <div class="mt-4 flex flex-wrap items-center gap-3">
        <div class="inline-flex rounded-md border border-slate-200 dark:border-slate-700 overflow-hidden text-sm font-bold">
          <button type="button" class="px-3 py-1.5 transition-colors"
            :class="mode === 'version' ? 'bg-blue-600 text-white' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700'"
            @click="mode = 'version'">{{ t('topRankers.byVersion') }}</button>
          <button type="button" class="px-3 py-1.5 transition-colors"
            :class="mode === 'area' ? 'bg-blue-600 text-white' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700'"
            @click="mode = 'area'">{{ t('topRankers.byArea') }}</button>
        </div>

        <select v-if="mode === 'version'" v-model.number="selectedVersion"
          class="px-3 py-1.5 border border-slate-200 dark:border-slate-700 rounded-md bg-slate-50 dark:bg-slate-900 text-sm font-medium text-slate-700 dark:text-slate-200">
          <option v-for="v in versionOptions" :key="v.num" :value="v.num">{{ v.name }}</option>
        </select>
        <select v-else v-model.number="selectedArea"
          class="px-3 py-1.5 border border-slate-200 dark:border-slate-700 rounded-md bg-slate-50 dark:bg-slate-900 text-sm font-medium text-slate-700 dark:text-slate-200">
          <option v-for="a in areaOptions" :key="a.name" :value="a.num">{{ a.name }}</option>
        </select>

        <div class="inline-flex rounded-md border border-slate-200 dark:border-slate-700 overflow-hidden text-xs font-bold ml-auto">
          <button type="button" class="px-3 py-1.5 transition-colors"
            :class="sortKey === 'beat' ? 'bg-slate-700 text-white dark:bg-slate-200 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-400'"
            @click="sortKey = 'beat'">BEAT-PT</button>
          <button type="button" class="px-3 py-1.5 transition-colors"
            :class="sortKey === 'rate' ? 'bg-slate-700 text-white dark:bg-slate-200 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-400'"
            @click="sortKey = 'rate'">RATE-PT</button>
        </div>
      </div>
    </div>

    <div class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 overflow-hidden">
      <div v-if="isLoading" class="p-10 flex justify-center">
        <span class="w-6 h-6 border-2 border-blue-500/30 border-t-blue-500 rounded-full animate-spin"></span>
      </div>
      <p v-else-if="loadError" class="p-6 text-sm text-red-500">{{ t('topRankers.loadError') }}</p>
      <p v-else-if="visibleRows.length === 0" class="p-6 text-sm text-slate-500 dark:text-slate-400">{{ t('topRankers.empty') }}</p>
      <div v-else class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead class="bg-slate-50 dark:bg-slate-900 text-[11px] text-slate-500 dark:text-slate-400">
            <tr>
              <th class="py-2 pl-4 pr-2 text-right font-bold w-10">#</th>
              <th class="py-2 px-2 text-left font-bold">{{ mode === 'version' ? t('topRankers.colArea') : t('topRankers.colVersion') }}</th>
              <th class="py-2 px-2 text-left font-bold">Beat-Tier</th>
              <th class="py-2 px-2 text-right font-bold">BEAT-PT</th>
              <th class="py-2 px-2 text-left font-bold">Rate-Tier</th>
              <th class="py-2 pl-2 pr-4 text-right font-bold">RATE-PT</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in visibleRows" :key="`${r.versionNum}:${r.prefectureFileNum}`"
              class="border-t border-slate-100 dark:border-slate-700/60 cursor-pointer hover:bg-blue-50/60 dark:hover:bg-blue-900/20 transition-colors"
              @click="openRow(r)">
              <td class="py-2 pl-4 pr-2 text-right tabular-nums text-slate-400">{{ i + 1 }}</td>
              <td class="py-2 px-2 font-bold text-slate-700 dark:text-slate-200 whitespace-nowrap">
                {{ mode === 'version' ? r.prefectureName : r.versionName }}
              </td>
              <td class="py-2 px-2">
                <div class="flex items-center gap-2 whitespace-nowrap">
                  <RankIcon :rank-name="getRankInfo(r.beatPt).name" :tier="getRankInfo(r.beatPt).tier" size="sm" disable-party />
                  <span class="text-xs font-bold" :class="getRankInfo(r.beatPt).color">{{ getRankInfo(r.beatPt).name }} {{ getRankInfo(r.beatPt).tier || '' }}</span>
                </div>
              </td>
              <td class="py-2 px-2 text-right tabular-nums font-bold" :class="sortKey === 'beat' ? 'text-slate-800 dark:text-slate-100' : 'text-slate-500 dark:text-slate-400'">{{ fmt(r.beatPt) }}</td>
              <td class="py-2 px-2 whitespace-nowrap">
                <span class="text-xs font-bold" :class="getRateTierRankInfo(r.ratePt).color">{{ getRateTierRankInfo(r.ratePt).name }} {{ getRateTierRankInfo(r.ratePt).tier || '' }}</span>
              </td>
              <td class="py-2 pl-2 pr-4 text-right tabular-nums font-bold" :class="sortKey === 'rate' ? 'text-slate-800 dark:text-slate-100' : 'text-slate-500 dark:text-slate-400'">{{ fmt(r.ratePt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
