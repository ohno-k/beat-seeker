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
import { getRankInfo, previousTierFrame } from '../utils/beatTier';
import RankIcon from './RankIcon.vue';
import { mdiClose } from '@mdi/js';

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
  <v-dialog
    :model-value="true"
    @update:model-value="(v: boolean) => { if (!v) $emit('close') }"
    max-width="672"
  >
      <!-- 本体パネル -->
      <v-card class="max-h-[85vh] flex flex-col overflow-hidden">
        <!-- ヘッダー -->
        <v-card-title class="flex justify-between items-center">
          <span>{{ t('league.rankingModal.title') }}</span>
          <v-btn
            icon
            variant="text"
            size="small"
            @click="$emit('close')"
          >
            <v-icon :icon="mdiClose" />
          </v-btn>
        </v-card-title>
        <v-divider />

        <!-- 本文（スクロール領域） -->
        <v-card-text class="flex-1 overflow-y-auto custom-scrollbar">
          <p class="text-xs leading-relaxed text-slate-500 dark:text-slate-400">{{ t('league.rankingModal.desc') }}</p>

          <div v-if="loading" class="mt-6 text-center text-sm text-slate-400 dark:text-slate-500">
            {{ t('league.rankingModal.loading') }}
          </div>
          <v-alert v-else-if="error" type="error" variant="tonal" class="mt-4">
            {{ error }}
          </v-alert>
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
            <v-table density="compact">
                <thead>
                  <tr>
                    <th class="w-10">{{ t('league.rank') }}</th>
                    <th>{{ t('league.player') }}</th>
                    <th class="text-right w-16">{{ t('league.rankingModal.pt') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="row in div.entries"
                    :key="row.userId"
                    :class="[
                      row.userId === myUserId ? 'bg-indigo-50 dark:bg-indigo-900/30 font-semibold' : '',
                      rowClass(row.active),
                    ]"
                  >
                    <td class="tabular-nums">
                      {{ row.rank ?? '–' }}
                    </td>
                    <td class="break-words">
                      <span class="inline-flex items-center gap-1.5 align-middle">
                        <RankIcon
                          :rank-name="beatTier(row.totalBeatPt).name"
                          :tier="beatTier(row.totalBeatPt).tier"
                          size="2xs"
                          lite
                          disable-party
                          v-bind="previousTierFrame(row.previousBeatPt, 'beat')"
                        />
                        <span>{{ row.displayName }}</span>
                        <v-chip
                          v-if="!row.active"
                          size="x-small"
                          label
                          variant="outlined"
                          class="shrink-0"
                          :title="t('league.rankingModal.inactiveTitle')"
                        >{{ t('league.rankingModal.inactive') }}</v-chip>
                      </span>
                    </td>
                    <td class="text-right tabular-nums font-semibold" :class="ptClass(row.points)">
                      {{ fmtPt(row.points) }}
                    </td>
                  </tr>
                </tbody>
            </v-table>
          </section>
        </v-card-text>
      </v-card>
  </v-dialog>
</template>
