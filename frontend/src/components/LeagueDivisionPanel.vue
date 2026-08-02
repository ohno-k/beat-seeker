<script setup lang="ts">
/**
 * 【コンポーネントの役割】 ダッシュボード最上部に出す「現在の DIVISION」パネル。
 *
 * リーグ（score ラダー）に参加中のユーザーにだけ表示する。表示は 2 状態:
 *  - 開催中（週が active かつ自分がその週のメンバー）: 強調表示。「開催中」バッジ・
 *    グループ・締切カウントダウン・リーグ画面へのボタンを出す。
 *  - それ以外（週の合間・次週から参加など）: 従来どおり DIVISION だけの控えめな表示。
 *
 * 週の状態は GET /api/league/current（entry / week / member をまとめて返す）で判定する。
 * 未参加・休止中・未配属、および取得失敗時は何も描画しない。
 */
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useLeague, type LeagueCurrent } from '../composables/useLeague';
import DivisionIcon from './DivisionIcon.vue';

const emit = defineEmits<{ (e: 'open-league'): void }>();

const { t } = useI18n();
const league = useLeague();

/** GET /api/league/current の取得結果。未取得・失敗時は null。 */
const current = ref<LeagueCurrent | null>(null);

/** カウントダウン表示用の現在時刻（30 秒ごとに更新。LeagueView と同じ間隔）。 */
const now = ref(Date.now());
let timer: ReturnType<typeof setInterval> | null = null;

onMounted(async () => {
  timer = setInterval(() => { now.value = Date.now(); }, 30000);
  try {
    current.value = await league.fetchCurrent('score');
  } catch {
    /* 未ログイン・通信失敗時はパネルを出さないだけ（ダッシュボード本体には影響させない） */
  }
});
onUnmounted(() => {
  if (timer) clearInterval(timer);
});

/** 自分の DIVISION（参加中かつ配属済みのときだけ値が入る）。未参加・休止中は null。 */
const myTier = computed<number | null>(() => {
  const e = current.value?.entry;
  if (!e || !e.active || e.currentTier == null) return null;
  return e.currentTier;
});

/** パネルを出すか（リーグ参加中のときだけ）。 */
const visible = computed(() => myTier.value != null);

/**
 * 開催中か（週が進行中・自分がその週に編成済み・締切前）。
 * 締切（日曜 21:00）を過ぎた週は、締め処理が遅れて active のままでも開催中扱いにしない。
 */
const isLive = computed(() => {
  const w = current.value?.week;
  if (!w || current.value?.member == null) return false;
  return new Date(w.endsAt).getTime() > now.value;
});

/** DIVISION の表示名（0=DIVISION LEGEND、1..10=DIVISION n）。 */
const divisionLabel = computed(() =>
  myTier.value === 0 ? t('league.divisionLegend') : t('league.divisionN', { n: myTier.value ?? 0 })
);

/** 開催中の自分のグループ表示（「グループ3」）。編成前は空。 */
const groupLabel = computed(() => {
  const m = current.value?.member;
  return m ? t('league.groupN', { n: m.groupIndex + 1 }) : '';
});

/** 週の締切までの残り時間表示（"2日 5時間" / "3時間 12分"）。終了後は空。 */
const countdown = computed(() => {
  const endsAt = current.value?.week?.endsAt;
  if (!endsAt) return '';
  const diff = new Date(endsAt).getTime() - now.value;
  if (diff <= 0) return '';
  const minutes = Math.floor(diff / 60000);
  const days = Math.floor(minutes / 1440);
  const hours = Math.floor((minutes % 1440) / 60);
  const mins = minutes % 60;
  if (days > 0) return t('league.countdownDh', { d: days, h: hours });
  if (hours > 0) return t('league.countdownHm', { h: hours, m: mins });
  return t('league.countdownM', { m: mins });
});
</script>

<template>
  <!-- 開催中: 最上部で目立たせる（枠・背景・バッジ・カウントダウン・導線） -->
  <div
    v-if="visible && isLive"
    class="w-full rounded-xl border-2 border-indigo-400 dark:border-indigo-500/60 bg-gradient-to-r from-indigo-50 via-white to-white dark:from-indigo-950/60 dark:via-slate-800 dark:to-slate-800 px-4 sm:px-5 py-4 shadow-sm flex items-center gap-3 sm:gap-4 transition-colors duration-200"
  >
    <DivisionIcon :tier="myTier ?? 10" :size="60" class="shrink-0" />
    <div class="flex-1 min-w-0">
      <div class="flex items-center gap-2 flex-wrap">
        <span class="inline-flex items-center gap-1.5 rounded-full bg-indigo-600 px-2 py-0.5 text-[10px] font-bold tracking-wider text-white">
          <span class="relative flex h-1.5 w-1.5">
            <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-white opacity-75"></span>
            <span class="relative inline-flex h-1.5 w-1.5 rounded-full bg-white"></span>
          </span>
          {{ t('dashboard.leagueLive.badge') }}
        </span>
        <span class="text-[10px] font-bold text-slate-400">{{ t('dashboard.currentDivision') }}</span>
        <span v-if="groupLabel" class="text-[10px] font-bold text-indigo-600 dark:text-indigo-300">{{ groupLabel }}</span>
      </div>
      <p class="text-2xl sm:text-3xl font-bold text-slate-800 dark:text-slate-100 leading-tight mt-0.5">{{ divisionLabel }}</p>
      <p v-if="countdown" class="text-xs font-semibold text-amber-600 dark:text-amber-400 mt-0.5">
        {{ t('league.endsIn', { time: countdown }) }}
      </p>
    </div>
    <button
      type="button"
      @click="emit('open-league')"
      class="shrink-0 px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-bold whitespace-nowrap transition-colors"
    >{{ t('dashboard.leagueLive.view') }}</button>
  </div>

  <!-- 非開催（週の合間・次週から参加）: 従来どおりの控えめな DIVISION 表示 -->
  <div
    v-else-if="visible"
    class="w-full bg-white dark:bg-slate-800 p-4 rounded-md border border-slate-200 dark:border-slate-700 flex items-center justify-center gap-3 transition-colors duration-200"
  >
    <DivisionIcon :tier="myTier ?? 10" :size="52" class="shrink-0" />
    <div class="text-center sm:text-left">
      <p class="text-[10px] font-bold text-slate-400">{{ t('dashboard.currentDivision') }}</p>
      <p class="text-2xl font-bold text-slate-800 dark:text-slate-100 leading-tight">{{ divisionLabel }}</p>
    </div>
  </div>
</template>
