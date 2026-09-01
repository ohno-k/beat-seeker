<script setup lang="ts">
/**
 * 【コンポーネントの役割】 リーグモードの DIVISION 別ランキングモーダル。
 *
 * LeagueView のヘッダー（タイトル横の「ランキング」ボタン）から開く。
 * 各 DIVISION の参加者を昇降格ポイント（PT）の降順で一覧する。進行中の週の順位表が
 * 「今週そのグループで何位か」なのに対し、こちらは「DIVISION の中で昇格にどれだけ近いか」を
 * 通しで見るためのもの（PT は週次締めで増減し、+8 で昇格・-8 で降格）。
 *
 * 掲載されるのは DIVISION 配属済みの人（次回配属待ちは含まない）。離脱（休止）中の人も
 * 同じ並びに薄く表示し、順位は付けない（競っていないため）。
 */
import { ref, computed, onMounted } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useLeague, type LadderType, type LeagueRankingDivision } from '../composables/useLeague';
import { getRankInfo } from '../utils/beatTier';
import RankIcon from './RankIcon.vue';

const props = withDefaults(defineProps<{
  /** 対象ラダー（現状はスコアリーグのみ運用）。 */
  ladder?: LadderType;
  /** 閲覧者のユーザー ID。自分の行を強調表示するのに使う。 */
  myUserId?: number | null;
}>(), { ladder: 'score', myUserId: null });

defineEmits<{ (e: 'close'): void }>();

const { t } = useI18n();
const league = useLeague();

/** DIVISION 別ランキング（tier 昇順 = 上位 DIVISION から）。 */
const divisions = ref<LeagueRankingDivision[]>([]);
const loading = ref(true);
const error = ref('');

/** 参加者が 1 人も居ない場合（全 DIVISION 空）。 */
const isEmpty = computed(() => !divisions.value.some(d => d.entries.length));

onMounted(async () => {
  try {
    divisions.value = await league.fetchRankings(props.ladder);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
});

/** DIVISION の表示名（tier 0 = DIVISION LEGEND、1..10 = DIVISION n）。 */
const divisionName = (tier: number) =>
  tier === 0 ? t('league.divisionLegend') : t('league.divisionN', { n: tier });

/** 昇降格 PT の符号付き表示（+3 / -2 / 0）。 */
const fmtPt = (p: number) => (p > 0 ? `+${p}` : `${p}`);

/** PT の色（プラス = 昇格寄り・緑 / マイナス = 降格寄り・赤 / 0 = 中立）。 */
const ptClass = (p: number) => {
  if (p > 0) return 'text-emerald-600 dark:text-emerald-400';
  if (p < 0) return 'text-rose-600 dark:text-rose-400';
  return 'text-slate-500 dark:text-slate-400';
};

/** 総合 BEAT-PT から Beat-Tier ランク情報（ティアアイコン用）。 */
const beatTier = (pt: number | null) => getRankInfo(pt ?? 0);

/**
 * 離脱（休止）中の行を薄くするクラス。
 * 並び順は参加中と同じ（PT 降順）ままで、見た目だけ落として「今は競っていない」ことを示す。
 */
const rowClass = (active: boolean) => (active ? '' : 'opacity-50');
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[110] flex items-center justify-center p-4 animate-fade-in">
      <!-- 背景オーバーレイ（クリックで閉じる） -->
      <div class="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" @click="$emit('close')"></div>

      <!-- 本体パネル -->
      <div class="relative w-full max-w-2xl max-h-[85vh] flex flex-col bg-white dark:bg-slate-800 rounded-2xl shadow-2xl overflow-hidden transition-colors duration-200">
        <!-- ヘッダー -->
        <div class="px-6 py-4 border-b border-slate-100 dark:border-slate-700/50 flex justify-between items-center">
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-100">{{ t('league.rankingModal.title') }}</h3>
          <button
            class="p-2 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-full transition-all"
            @click="$emit('close')"
          >
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- 本文（スクロール領域） -->
        <div class="flex-1 overflow-y-auto custom-scrollbar p-6">
          <p class="text-xs leading-relaxed text-slate-500 dark:text-slate-400">{{ t('league.rankingModal.desc') }}</p>

          <div v-if="loading" class="mt-6 text-center text-sm text-slate-400 dark:text-slate-500">
            {{ t('league.rankingModal.loading') }}
          </div>
          <div v-else-if="error"
               class="mt-4 rounded-lg border border-rose-200 dark:border-rose-800 bg-rose-50 dark:bg-rose-900/30 px-3 py-2 text-sm text-rose-700 dark:text-rose-300">
            {{ error }}
          </div>
          <div v-else-if="isEmpty" class="mt-6 text-center text-sm text-slate-400 dark:text-slate-500">
            {{ t('league.rankingModal.empty') }}
          </div>

          <!-- DIVISION ごと（上位 DIVISION から順に） -->
          <section v-for="div in divisions" :key="div.tier" class="mt-5 first:mt-4">
            <h4 class="text-sm font-bold text-slate-800 dark:text-slate-100 mb-2 flex items-center gap-2">
              <span class="w-1.5 h-5 bg-indigo-600 dark:bg-indigo-500 rounded-full"></span>
              {{ divisionName(div.tier) }}
              <span class="text-xs font-normal text-slate-400 dark:text-slate-500">
                {{ t('league.rankingModal.members', { n: div.memberCount }) }}
                <template v-if="div.inactiveCount">
                  / {{ t('league.rankingModal.inactiveMembers', { n: div.inactiveCount }) }}
                </template>
              </span>
            </h4>
            <div class="overflow-x-auto">
              <table class="w-full text-sm">
                <thead>
                  <tr class="text-left text-xs text-slate-400 dark:text-slate-500 border-b border-slate-200 dark:border-slate-700">
                    <th class="py-1.5 pr-2 w-10">{{ t('league.rank') }}</th>
                    <th class="py-1.5 pr-2">{{ t('league.player') }}</th>
                    <th class="py-1.5 pl-2 text-right w-16">{{ t('league.rankingModal.pt') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="row in div.entries"
                    :key="row.userId"
                    class="border-b border-slate-100 dark:border-slate-700/50"
                    :class="[
                      row.userId === myUserId ? 'bg-indigo-50 dark:bg-indigo-900/30 font-semibold' : '',
                      rowClass(row.active),
                    ]"
                  >
                    <td class="py-1.5 pr-2 tabular-nums text-slate-600 dark:text-slate-300">
                      {{ row.rank ?? '–' }}
                    </td>
                    <td class="py-1.5 pr-2 break-words text-slate-700 dark:text-slate-200">
                      <span class="inline-flex items-center gap-1.5 align-middle">
                        <RankIcon
                          :rank-name="beatTier(row.totalBeatPt).name"
                          :tier="beatTier(row.totalBeatPt).tier"
                          size="2xs"
                          lite
                          disable-party
                        />
                        <span>{{ row.displayName }}</span>
                        <span
                          v-if="!row.active"
                          class="shrink-0 rounded px-1 py-px text-[10px] font-normal leading-tight border border-slate-300 dark:border-slate-600 text-slate-500 dark:text-slate-400"
                          :title="t('league.rankingModal.inactiveTitle')"
                        >{{ t('league.rankingModal.inactive') }}</span>
                      </span>
                    </td>
                    <td class="py-1.5 pl-2 text-right tabular-nums font-semibold" :class="ptClass(row.points)">
                      {{ fmtPt(row.points) }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </div>
      </div>
    </div>
  </Teleport>
</template>
