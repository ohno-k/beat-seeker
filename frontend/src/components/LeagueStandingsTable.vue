<script setup lang="ts">
/**
 * リーグのグループ順位表（1 グループ分）。
 *
 * 進行中の週・過去週（履歴の折り畳み）の両方で同じ表を出すためのコンポーネント。
 * 表示に必要な値はすべて props で受け取り、データ取得は行わない。
 *
 * 曲別セルは「その週にラインを超えて有効になったリザルト」の EX と、その曲の着順・
 * 着順ポイントだけを出す（未達の自己ベストは競技結果ではないのでサーバー側で除去済み）。
 */
import { computed } from 'vue';
import { useI18n } from '../composables/useI18n';
import RankIcon from './RankIcon.vue';
import { getRankInfo } from '../utils/beatTier';
import type { LeagueSongInfo, LeagueStandingRow } from '../composables/useLeague';

const props = withDefaults(defineProps<{
  /** 課題曲（スロット順）。曲別セルの列見出しに使う。 */
  songs: LeagueSongInfo[];
  /** 順位表の行（順位順）。 */
  standings: LeagueStandingRow[];
  /** 自分の userId（行を強調する）。未ログイン・観戦時は null。 */
  myUserId?: number | null;
  /** 昇降格の帯色を出すか（過去週では確定した昇降格を表す）。 */
  showZone?: boolean;
}>(), {
  myUserId: null,
  showZone: true,
});

const { t } = useI18n();

/** DIVISION の表示名（tier 0 = DIVISION LEGEND、1..10 = DIVISION n）。 */
const divisionName = (tier: number | null | undefined) => {
  if (tier == null) return '';
  return tier === 0 ? t('league.divisionLegend') : t('league.divisionN', { n: tier });
};

/** DIVISION の短縮表記（立場バッジ用）。 */
const divisionShort = (tier: number | null | undefined) => {
  if (tier == null) return '';
  return tier === 0 ? 'LEGEND' : `D${tier}`;
};

/** 昇降格PTの符号付き表示（+3 / -2 / 0）。null は '-'。 */
const fmtPt = (p: number | null | undefined) => {
  if (p == null) return '-';
  return p > 0 ? `+${p}` : `${p}`;
};

/** 得点（着順ポイント）の表示。整数は小数点なし、半端は1桁。null は '-'。 */
const fmtPts = (p: number | null | undefined) => {
  if (p == null) return '-';
  return Number.isInteger(p) ? String(p) : p.toFixed(1);
};

/** 総合 BEAT-PT から Beat-Tier ランク情報（名前・ティア）を得る。 */
const beatTier = (pt: number | null | undefined) => getRankInfo(pt ?? 0);

/** 立場バッジの短い記号とクラス（チャレンジ=挑 / ディフェンス=防）。normal は null。 */
const roleBadge = (role: string | null | undefined) => {
  if (role === 'challenge') return { label: t('league.roleChallenge'), cls: 'bg-orange-100 dark:bg-orange-900/40 text-orange-700 dark:text-orange-300' };
  if (role === 'defense') return { label: t('league.roleDefense'), cls: 'bg-sky-100 dark:bg-sky-900/40 text-sky-700 dark:text-sky-300' };
  return null;
};

/**
 * 有効曲数の分母（無効化された課題曲を除いた曲数）。
 * 管理者が解禁不可能な曲を無効化した週は 3 曲ではなくなるため、曲リストから数える。
 */
const scoredSongCount = computed(() => {
  const enabled = props.songs.filter(s => !s.disabled).length;
  return props.songs.length ? enabled : 3;
});

/** 行の帯色（昇格圏 = 緑 / 降格圏 = 赤）。 */
const zoneClass = (row: LeagueStandingRow) => {
  if (!props.showZone) return '';
  if (row.zone === 'promote') return 'bg-emerald-50 dark:bg-emerald-900/20';
  if (row.zone === 'relegate') return 'bg-rose-50 dark:bg-rose-900/20';
  return '';
};
</script>

<template>
  <div class="overflow-x-auto">
    <table class="w-full text-sm">
      <thead>
        <tr class="text-left text-xs text-slate-400 dark:text-slate-500 border-b border-slate-200 dark:border-slate-700">
          <th class="py-2 pr-2 w-10">{{ t('league.rank') }}</th>
          <th class="py-2 pr-2">{{ t('league.player') }}</th>
          <th class="py-2 pr-2 text-center">{{ t('league.validSongs') }}</th>
          <th class="py-2 pr-2 text-right">{{ t('league.leaguePoints') }}</th>
          <th class="py-2 pr-2 text-center">{{ t('league.points') }}</th>
          <th class="py-2 pr-1 text-center whitespace-nowrap" v-for="s in songs" :key="s.id"
              :title="s.disabled ? `${s.slot}. ${s.title}（${t('league.songDisabled')}）` : `${s.slot}. ${s.title}`">
            <span :class="s.disabled ? 'line-through' : ''">{{ s.slot }}</span>
            <span v-if="s.disabled" class="font-normal text-[10px] text-rose-500 dark:text-rose-400">{{ t('league.songDisabledShort') }}</span>
            <span v-else class="font-normal text-[10px] text-slate-300 dark:text-slate-600">{{ t('league.songPoints') }}</span>
          </th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in standings"
          :key="row.userId"
          class="border-b border-slate-100 dark:border-slate-700/50"
          :class="[zoneClass(row), row.userId === myUserId ? 'font-semibold' : '']"
        >
          <td class="py-2 pr-2">{{ row.rank }}</td>
          <td class="py-2 pr-2 break-words">
            <span class="inline-flex items-center gap-1.5 align-middle">
              <RankIcon :rank-name="beatTier(row.totalBeatPt).name" :tier="beatTier(row.totalBeatPt).tier" size="2xs" lite disable-party />
              <span>{{ row.displayName }}</span>
              <span v-if="roleBadge(row.role)"
                    class="inline-flex items-center gap-0.5 px-1.5 py-px rounded text-[10px] font-bold leading-none"
                    :class="roleBadge(row.role)!.cls"
                    :title="roleBadge(row.role)!.label + (row.homeTier != null ? ' / ' + divisionName(row.homeTier) : '')">{{ roleBadge(row.role)!.label }}<span v-if="row.homeTier != null" class="font-semibold opacity-80">{{ divisionShort(row.homeTier) }}</span></span>
              <span v-if="row.userId === myUserId" class="text-[10px] text-indigo-500 dark:text-indigo-400">YOU</span>
            </span>
          </td>
          <td class="py-2 pr-2 text-center">{{ row.validSongs }}/{{ scoredSongCount }}</td>
          <td class="py-2 pr-2 text-right tabular-nums">{{ fmtPts(row.resultValue) }}</td>
          <td class="py-2 pr-2 text-center tabular-nums whitespace-nowrap">
            <template v-if="row.points != null">
              {{ fmtPt(row.points) }}
              <span class="text-xs text-slate-400 dark:text-slate-500">({{ fmtPt(row.pointDelta) }})</span>
            </template>
            <!-- 過去週は締め時の増減だけが残る（そのときの累計は保存していない）。 -->
            <template v-else>{{ fmtPt(row.pointDelta) }}</template>
          </td>
          <!-- 曲別セル: 有効になったリザルトの EX ＋ 着順とその曲の着順ポイント。 -->
          <td v-for="ps in row.perSong" :key="ps.slot" class="py-2 px-1 text-center text-xs tabular-nums whitespace-nowrap">
            <!-- 無効化された課題曲は集計対象外なので、記録も着順も出さない。 -->
            <template v-if="ps.disabled">
              <div class="text-slate-300 dark:text-slate-600">–</div>
              <div class="text-[10px] leading-tight text-rose-400 dark:text-rose-500">{{ t('league.songDisabledShort') }}</div>
            </template>
            <template v-else>
              <div v-if="ps.valid && ps.bestEx != null && ps.bestEx > 0"
                   class="font-semibold text-emerald-600 dark:text-emerald-400">
                {{ ps.bestEx }}
              </div>
              <div v-else class="text-slate-300 dark:text-slate-600">–</div>
              <div class="text-[10px] leading-tight text-slate-400 dark:text-slate-500">
                <span v-if="ps.rank != null">{{ t('league.songRank', { n: ps.rank }) }} </span>
                <span v-if="ps.points != null">{{ fmtPts(ps.points) }}{{ t('league.songPoints') }}</span>
              </div>
            </template>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
