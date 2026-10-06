<script setup lang="ts">
/**
 * 【コンポーネントの役割】 リーグの週が締まった後、最初に beat-seeker を開いたときに
 * その週の自分の結果を見せ、X へポストできるようにするモーダル。
 *
 * 表示条件（すべて満たしたときのみ）:
 *  - ログイン済み
 *  - 自分の直近の締め済み週（`GET /api/league/history` の先頭）に順位がある
 *  - その週の終了から {@link SHOW_WITHIN_DAYS} 日以内（久しぶりに開いた人に古い結果を出さない）
 *  - この端末でその週の結果をまだ表示していない（localStorage・週ごと）
 *
 * ポストは本文だけ（x.com/intent/post）。画像は付けないので端末別の経路分けは不要。
 */
import { computed, ref, watch } from 'vue';
import { useAuth } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';
import { useLeague, type LeagueHistoryRow } from '../composables/useLeague';
import { xIntentUrl } from '../utils/shareToX';
import DivisionIcon from './DivisionIcon.vue';

/** 表示済みフラグの localStorage キー（末尾に週 ID）。 */
const STORAGE_PREFIX = 'leagueResultShown:';
/** 週の終了からこの日数を過ぎた結果は出さない。 */
const SHOW_WITHIN_DAYS = 7;

const { isLoggedIn } = useAuth();
const { t } = useI18n();
const league = useLeague();

const result = ref<LeagueHistoryRow | null>(null);

const divisionName = (tier: number) =>
  tier === 0 ? t('league.divisionLegend') : t('league.divisionN', { n: tier });

const weekLabel = computed(() => {
  const no = result.value?.weekNo;
  return no == null ? t('league.preseason') : t('league.weekNo', { n: no });
});

/** 昇降格の起点（ホーム DIVISION）。旧データは着席卓で代用。 */
const homeTier = computed(() => result.value?.homeTier ?? result.value?.tier ?? 10);

/** 締め後の所属 DIVISION（昇格 -1 / 降格 +1、端は据え置き）。 */
const nextTier = computed(() => {
  const r = result.value;
  if (!r) return homeTier.value;
  if (r.movement === 'promote') return Math.max(0, homeTier.value - 1);
  if (r.movement === 'relegate') return Math.min(10, homeTier.value + 1);
  return homeTier.value;
});

const fmtPt = (p: number | null | undefined) => (p == null ? '-' : p > 0 ? `+${p}` : `${p}`);
const fmtPts = (p: number | null | undefined) =>
  p == null ? '-' : Number.isInteger(p) ? String(p) : p.toFixed(1);

const rankText = computed(() => {
  const r = result.value;
  if (!r || r.finalRank == null) return '-';
  return r.groupSize
    ? t('league.resultModal.rankOf', { rank: r.finalRank, size: r.groupSize })
    : t('league.songRank', { n: r.finalRank });
});

const shareText = computed(() => {
  const r = result.value;
  if (!r) return '';
  const lines = [
    t('league.resultModal.shareTitle', { week: weekLabel.value }),
    t('league.resultModal.shareBody', {
      division: divisionName(r.tier),
      group: t('league.groupN', { n: r.groupIndex + 1 }),
      rank: rankText.value,
      score: fmtPts(r.resultValue),
      pt: fmtPt(r.pointDelta),
    }),
  ];
  if (r.movement === 'promote' && nextTier.value !== homeTier.value) {
    lines.push(t('league.resultModal.sharePromote', { to: divisionName(nextTier.value) }));
  } else if (r.movement === 'relegate' && nextTier.value !== homeTier.value) {
    lines.push(t('league.resultModal.shareRelegate', { to: divisionName(nextTier.value) }));
  }
  lines.push('https://beat-seeker.com', '#BeatSeeker');
  return lines.join('\n');
});

/** 表示条件を確かめ、満たしたときだけ開く。 */
async function maybeShow() {
  if (!isLoggedIn.value) return;
  let latest: LeagueHistoryRow | undefined;
  try {
    latest = (await league.fetchHistory('score'))[0];
  } catch {
    return;
  }
  if (!latest || latest.finalRank == null) return;
  const endsAt = Date.parse(latest.endsAt);
  if (Number.isNaN(endsAt) || Date.now() - endsAt > SHOW_WITHIN_DAYS * 86_400_000) return;
  try {
    if (localStorage.getItem(STORAGE_PREFIX + latest.weekId)) return;
  } catch {
    // localStorage が使えないと「1 回だけ」を保証できず開くたびに出てしまうので、出さない。
    return;
  }
  result.value = latest;
}

// 初回ロードは /api/auth/me の解決待ちのため、ログイン状態が確定してから判定する。
watch(isLoggedIn, () => maybeShow(), { immediate: true });

function close() {
  const r = result.value;
  if (r) {
    try {
      localStorage.setItem(STORAGE_PREFIX + r.weekId, '1');
    } catch {
      /* ignore */
    }
  }
  result.value = null;
}

function post() {
  window.open(xIntentUrl(shareText.value), '_blank', 'noopener');
  close();
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="result"
      class="fixed inset-0 z-[125] flex items-center justify-center bg-slate-900/70 backdrop-blur-sm p-4 animate-fade-in"
      @click.self="close"
    >
      <div class="bg-white dark:bg-slate-800 rounded-md shadow-xl w-full max-w-sm overflow-hidden flex flex-col">
        <div class="p-6 text-center">
          <p class="text-xs font-semibold tracking-wide text-indigo-600 dark:text-indigo-400 mb-1">
            {{ t('league.resultModal.ended') }}
          </p>
          <h2 class="text-lg font-bold text-slate-800 dark:text-slate-100">
            {{ t('league.resultModal.title', { week: weekLabel }) }}
          </h2>
          <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
            {{ divisionName(result.tier) }} / {{ t('league.groupN', { n: result.groupIndex + 1 }) }}
          </p>

          <div class="flex justify-center my-4">
            <DivisionIcon :tier="nextTier" :size="72" />
          </div>

          <p class="text-3xl font-black text-slate-800 dark:text-slate-100 tabular-nums">{{ rankText }}</p>

          <div class="mt-4 grid grid-cols-2 gap-2 text-sm">
            <div class="rounded-md bg-slate-50 dark:bg-slate-900/40 py-2">
              <div class="text-xs text-slate-500 dark:text-slate-400">{{ t('league.resultModal.score') }}</div>
              <div class="font-bold tabular-nums text-slate-800 dark:text-slate-100">{{ fmtPts(result.resultValue) }}</div>
            </div>
            <div class="rounded-md bg-slate-50 dark:bg-slate-900/40 py-2">
              <div class="text-xs text-slate-500 dark:text-slate-400">{{ t('league.resultModal.pt') }}</div>
              <div class="font-bold tabular-nums text-slate-800 dark:text-slate-100">{{ fmtPt(result.pointDelta) }}</div>
            </div>
          </div>

          <p
            v-if="result.movement === 'promote' || result.movement === 'relegate'"
            class="mt-4 text-sm font-bold rounded-md py-2"
            :class="result.movement === 'promote'
              ? 'bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-300'
              : 'bg-rose-100 dark:bg-rose-900/40 text-rose-700 dark:text-rose-300'"
          >
            {{ t(result.movement === 'promote' ? 'league.resultModal.promoted' : 'league.resultModal.relegated', { to: divisionName(nextTier) }) }}
          </p>
        </div>

        <div class="p-4 border-t border-slate-200 dark:border-slate-700 flex gap-2">
          <button
            type="button"
            class="flex-1 rounded-md px-4 py-2.5 text-sm font-bold bg-slate-100 hover:bg-slate-200 dark:bg-slate-700 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 transition-colors"
            @click="close"
          >
            {{ t('league.resultModal.close') }}
          </button>
          <button
            type="button"
            class="flex-1 rounded-md px-4 py-2.5 text-sm font-bold bg-black hover:bg-slate-800 text-white transition-colors flex items-center justify-center gap-2"
            @click="post"
          >
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" class="w-4 h-4" fill="currentColor" aria-hidden="true">
              <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z" />
            </svg>
            {{ t('league.resultModal.post') }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
