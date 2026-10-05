<script setup lang="ts">
/**
 * 【コンポーネントの役割】 リーグの楽曲 BAN モーダル。
 *
 * LeagueView のヘッダー（タイトル横の「楽曲BAN」ボタン）から開く。DIVISION 配属済みの参加者だけ。
 * DIVISION ごとに、その DIVISION の課題曲の候補（難易度帯のプール）から最大 maxBans 曲まで
 * 「課題曲に出てほしくない曲」を選んで保存する。登録はユーザー × DIVISION ごとに残り、毎週有効
 * （日曜 23:59 までの登録が月曜 0:00 の事前編成の抽選に使われる。グループのメンバーが
 * その卓の DIVISION に登録した BAN を除いて抽選する）。
 * 事前編成〜開始（月曜 0:00〜12:00）は読み取りだけ。
 */
import { ref, computed, onMounted } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useLeague, type LeagueBans, type LeagueBanPoolSong } from '../composables/useLeague';

const emit = defineEmits<{ (e: 'close'): void }>();

const { t } = useI18n();
const league = useLeague();

const data = ref<LeagueBans | null>(null);
const loading = ref(true);
const saving = ref(false);
const error = ref('');
const notice = ref('');
/** 選択中のタイトル（保存前の編集状態。登録順を保つ） */
const selected = ref<string[]>([]);
/** 保存済みのタイトル（変更の有無の判定用） */
const savedTitles = ref<string[]>([]);
const query = ref('');

const ALL_TIERS = Array.from({ length: 11 }, (_, i) => i);
const divisionName = (tier: number) =>
  tier === 0 ? t('league.divisionLegend') : t('league.divisionN', { n: tier });

async function load(tier?: number) {
  loading.value = true;
  error.value = '';
  notice.value = '';
  try {
    const d = await league.fetchBans(tier);
    data.value = d;
    selected.value = d.banned.map(b => b.title);
    savedTitles.value = [...selected.value];
    query.value = '';
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}
onMounted(() => load());

const dirty = computed(() =>
  selected.value.length !== savedTitles.value.length || selected.value.some((x, i) => x !== savedTitles.value[i]));
const maxBans = computed(() => data.value?.maxBans ?? 20);
const full = computed(() => selected.value.length >= maxBans.value);
const locked = computed(() => data.value?.locked === true);

/** 選択中の曲の表示用（候補に無い＝難易度表の改訂で外れた曲も残す） */
const selectedRows = computed(() => {
  const d = data.value;
  if (!d) return [];
  const byTitle = new Map(d.pool.map(p => [p.title, p]));
  const bannedByTitle = new Map(d.banned.map(b => [b.title, b]));
  return selected.value.map(title => {
    const p = byTitle.get(title);
    return {
      title,
      difficultyName: p?.difficultyName ?? bannedByTitle.get(title)?.difficultyName ?? null,
      inPool: !!p,
    };
  });
});

/** 候補を難易度表のランクごとにまとめる（絞り込み後）。並びは API の順＝難易度表の順 */
const groups = computed(() => {
  const d = data.value;
  if (!d) return [];
  const q = query.value.trim().toLowerCase();
  const out: { rank: string; songs: LeagueBanPoolSong[] }[] = [];
  for (const s of d.pool) {
    if (q && !s.title.toLowerCase().includes(q)) continue;
    const last = out[out.length - 1];
    if (last && last.rank === s.rank) last.songs.push(s);
    else out.push({ rank: s.rank, songs: [s] });
  }
  return out;
});

const isSelected = (title: string) => selected.value.includes(title);
function toggle(title: string) {
  if (locked.value) return;
  notice.value = '';
  if (isSelected(title)) selected.value = selected.value.filter(x => x !== title);
  else if (!full.value) selected.value = [...selected.value, title];
}

/** 譜面の表記（LEGGENDARIA は [L]） */
const chartLabel = (title: string, difficultyName: string | null) =>
  difficultyName === 'LEGGENDARIA' ? `${title} [L]` : title;

async function save() {
  const d = data.value;
  if (!d || saving.value) return;
  saving.value = true;
  error.value = '';
  try {
    // 候補から外れた曲は保存できない（抽選にも出ない）ので落として保存する
    const poolTitles = new Set(d.pool.map(p => p.title));
    await league.saveBans(d.tier, selected.value.filter(x => poolTitles.has(x)));
    await load(d.tier);
    notice.value = t('league.banModal.saved');
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    saving.value = false;
  }
}

function changeTier(e: Event) {
  const tier = Number((e.target as HTMLSelectElement).value);
  if (dirty.value && !window.confirm(t('league.banModal.unsaved'))) {
    (e.target as HTMLSelectElement).value = String(data.value?.tier ?? tier);
    return;
  }
  load(tier);
}

function close() {
  if (dirty.value && !window.confirm(t('league.banModal.unsaved'))) return;
  emit('close');
}

const tierCount = (tier: number) => data.value?.counts?.[String(tier)] ?? 0;
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[110] flex items-center justify-center p-4 animate-fade-in">
      <div class="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" @click="close"></div>
      <div class="relative w-full max-w-2xl max-h-[88vh] flex flex-col bg-white dark:bg-slate-800 rounded-2xl shadow-2xl overflow-hidden">
        <!-- ヘッダー -->
        <div class="px-5 py-4 border-b border-slate-100 dark:border-slate-700/50 flex justify-between items-center">
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-100 flex items-center gap-2">
            <svg class="w-5 h-5 text-rose-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <circle cx="12" cy="12" r="9" stroke-width="2" />
              <path stroke-linecap="round" stroke-width="2" d="M5.6 5.6l12.8 12.8" />
            </svg>
            {{ t('league.banModal.title') }}
          </h3>
          <button
            class="p-2 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-full transition-all"
            :aria-label="t('league.banModal.close')"
            @click="close"
          >
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div class="flex-1 overflow-y-auto custom-scrollbar px-5 py-4 space-y-4 text-sm text-slate-700 dark:text-slate-300">
          <p class="text-xs leading-relaxed text-slate-500 dark:text-slate-400">
            {{ t('league.banModal.desc', { n: maxBans }) }}
            <span class="block mt-1">{{ t('league.banModal.roleNote') }}</span>
          </p>

          <div v-if="error" class="rounded-lg px-3 py-2 text-xs bg-rose-50 dark:bg-rose-900/30 text-rose-700 dark:text-rose-300 border border-rose-200 dark:border-rose-800">{{ error }}</div>
          <div v-if="data?.lockMessage" class="rounded-lg px-3 py-2 text-xs bg-amber-50 dark:bg-amber-900/30 text-amber-700 dark:text-amber-300 border border-amber-200 dark:border-amber-800">{{ data.lockMessage }}</div>

          <div v-if="loading && !data" class="py-10 text-center text-slate-400 dark:text-slate-500">{{ t('league.banModal.loading') }}</div>

          <template v-else-if="data">
            <!-- DIVISION の切り替えと件数 -->
            <div class="flex flex-wrap items-center justify-between gap-2">
              <label class="flex items-center gap-2 text-xs font-semibold text-slate-500 dark:text-slate-400">
                {{ t('league.banModal.division') }}
                <select
                  :value="data.tier"
                  class="rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-700 px-2 py-1.5 text-sm font-semibold text-slate-800 dark:text-slate-100"
                  @change="changeTier"
                >
                  <option v-for="tier in ALL_TIERS" :key="tier" :value="tier">
                    {{ divisionName(tier) }}{{ tier === data.homeTier ? `（${t('league.banModal.home')}）` : '' }}{{ tierCount(tier) ? ` ・${tierCount(tier)}` : '' }}
                  </option>
                </select>
              </label>
              <span class="ban-count tabular-nums" :class="{ full }">
                {{ t('league.banModal.count', { n: selected.length, max: maxBans }) }}
              </span>
            </div>

            <!-- 登録する曲 -->
            <section>
              <h4 class="text-xs font-bold text-slate-500 dark:text-slate-400 mb-1.5">{{ t('league.banModal.selected') }}</h4>
              <div v-if="selectedRows.length" class="flex flex-wrap gap-1.5">
                <button
                  v-for="r in selectedRows"
                  :key="r.title"
                  type="button"
                  class="ban-chip"
                  :class="{ stale: !r.inPool }"
                  :disabled="locked"
                  :title="r.inPool ? '' : t('league.banModal.outOfPoolTitle')"
                  @click="toggle(r.title)"
                >
                  <span class="truncate">{{ chartLabel(r.title, r.difficultyName) }}</span>
                  <span v-if="!r.inPool" class="text-[10px] font-normal">{{ t('league.banModal.outOfPool') }}</span>
                  <svg v-if="!locked" class="w-3 h-3 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-width="3" d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              </div>
              <p v-else class="text-xs text-slate-400 dark:text-slate-500">{{ t('league.banModal.none') }}</p>
            </section>

            <!-- 候補の一覧 -->
            <section>
              <div class="flex flex-wrap items-center gap-2 mb-2">
                <input
                  v-model="query"
                  type="search"
                  :placeholder="t('league.banModal.search')"
                  class="flex-1 min-w-0 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-700 px-2.5 py-1.5 text-sm"
                />
                <span class="text-xs text-slate-400 dark:text-slate-500 tabular-nums">{{ t('league.banModal.pool', { n: data.pool.length }) }}</span>
              </div>
              <p v-if="full && !locked" class="mb-2 text-xs font-semibold text-rose-600 dark:text-rose-400">{{ t('league.banModal.full', { n: maxBans }) }}</p>
              <div v-if="groups.length" class="pool-list">
                <template v-for="g in groups" :key="g.rank">
                  <div class="pool-rank">☆{{ g.rank }}</div>
                  <button
                    v-for="s in g.songs"
                    :key="s.title"
                    type="button"
                    class="pool-row"
                    :class="{ on: isSelected(s.title) }"
                    :disabled="locked || (!isSelected(s.title) && full)"
                    @click="toggle(s.title)"
                  >
                    <span class="pool-check" aria-hidden="true">
                      <svg v-if="isSelected(s.title)" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                        <path stroke-linecap="round" stroke-width="3" d="M6 18L18 6M6 6l12 12" />
                      </svg>
                    </span>
                    <span class="flex-1 min-w-0 truncate text-left">{{ chartLabel(s.title, s.difficultyName) }}</span>
                    <span class="text-[11px] text-slate-400 dark:text-slate-500 tabular-nums">☆{{ s.level }}</span>
                  </button>
                </template>
              </div>
              <p v-else class="py-6 text-center text-xs text-slate-400 dark:text-slate-500">{{ t('league.banModal.noMatch') }}</p>
            </section>
          </template>
        </div>

        <!-- フッター -->
        <div v-if="data && !locked" class="px-5 py-3 border-t border-slate-100 dark:border-slate-700/50 flex items-center justify-end gap-2">
          <span v-if="notice" class="mr-auto text-xs font-semibold text-emerald-600 dark:text-emerald-400">{{ notice }}</span>
          <button
            type="button"
            class="px-3 py-1.5 rounded-lg text-sm text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-40"
            :disabled="!dirty || saving"
            @click="selected = [...savedTitles]"
          >{{ t('league.banModal.reset') }}</button>
          <button
            type="button"
            class="px-4 py-1.5 rounded-lg bg-rose-600 hover:bg-rose-700 text-white text-sm font-semibold disabled:opacity-40 disabled:cursor-not-allowed"
            :disabled="!dirty || saving"
            @click="save"
          >{{ saving ? t('league.banModal.saving') : t('league.banModal.save') }}</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.ban-count { font-size: 12px; font-weight: 700; padding: 0.15rem 0.6rem; border-radius: 9999px; background: rgb(241 245 249); color: rgb(71 85 105); }
.dark .ban-count { background: rgb(51 65 85); color: rgb(226 232 240); }
.ban-count.full { background: rgb(255 228 230); color: rgb(190 18 60); }
.dark .ban-count.full { background: rgb(136 19 55 / 0.4); color: rgb(253 164 175); }
.ban-chip {
  display: inline-flex; align-items: center; gap: 0.3rem; max-width: 100%;
  padding: 0.2rem 0.55rem; border-radius: 9999px; font-size: 12px; font-weight: 600;
  background: rgb(255 228 230); color: rgb(159 18 57); border: 1px solid rgb(254 205 211);
}
.ban-chip:hover:not(:disabled) { background: rgb(254 205 211); }
.dark .ban-chip { background: rgb(136 19 55 / 0.35); color: rgb(253 164 175); border-color: rgb(159 18 57 / 0.6); }
.ban-chip.stale { background: rgb(241 245 249); color: rgb(100 116 139); border-color: rgb(226 232 240); text-decoration: line-through; }
.dark .ban-chip.stale { background: rgb(51 65 85); color: rgb(148 163 184); border-color: rgb(71 85 105); }
.pool-list { border: 1px solid rgb(226 232 240); border-radius: 0.6rem; overflow: hidden; }
.dark .pool-list { border-color: rgb(51 65 85); }
.pool-rank {
  padding: 0.25rem 0.75rem; font-size: 11px; font-weight: 700; color: rgb(100 116 139);
  background: rgb(248 250 252); border-bottom: 1px solid rgb(226 232 240);
}
.dark .pool-rank { background: rgb(30 41 59); color: rgb(148 163 184); border-color: rgb(51 65 85); }
.pool-row {
  display: flex; align-items: center; gap: 0.6rem; width: 100%;
  padding: 0.4rem 0.75rem; font-size: 13px; border-bottom: 1px solid rgb(241 245 249);
}
.dark .pool-row { border-color: rgb(51 65 85 / 0.6); }
.pool-row:hover:not(:disabled) { background: rgb(248 250 252); }
.dark .pool-row:hover:not(:disabled) { background: rgb(51 65 85 / 0.5); }
.pool-row:disabled { opacity: 0.45; cursor: not-allowed; }
.pool-row.on { background: rgb(255 241 242); color: rgb(159 18 57); font-weight: 600; }
.dark .pool-row.on { background: rgb(136 19 55 / 0.25); color: rgb(253 164 175); }
.pool-check {
  width: 1rem; height: 1rem; flex-shrink: 0; border-radius: 0.25rem;
  border: 1.5px solid rgb(203 213 225); display: inline-flex; align-items: center; justify-content: center;
}
.pool-row.on .pool-check { background: rgb(225 29 72); border-color: rgb(225 29 72); color: white; }
.pool-check svg { width: 0.7rem; height: 0.7rem; }
</style>
