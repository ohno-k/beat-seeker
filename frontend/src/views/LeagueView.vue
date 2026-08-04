<script setup lang="ts">
/**
 * 【View の役割】 リーグモード（/league）のメイン画面（スコアリーグ）。
 *
 * 以下を表示する:
 *  - 参加/離脱カード（未参加時は参加の流れとプライバシー注記を表示）
 *  - 進行中の週: 期間・締切カウントダウン・課題曲 3 曲・自分のグループのライブ順位表
 *    （昇格圏 = 緑 / 降格圏 = 赤 の帯表示。行の perSong で曲別の有効状況も見せる）
 *  - 他グループの順位表（観戦）
 *  - 自分の過去週成績（アコーディオン）
 *  - 管理者セクション（useAdmin.isAdmin のときのみ表示。サーバ側でも管理者判定される）:
 *    draft 週の課題曲差し替え・再抽選、週次処理の手動実行
 */
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useAuth } from '../composables/useAuth';
import { useAdmin } from '../composables/useAdmin';
import LeagueInfoModal from '../components/LeagueInfoModal.vue';
import RankIcon from '../components/RankIcon.vue';
import { getRankInfo } from '../utils/beatTier';
import {
  useLeague,
  type LadderType,
  type LeagueCurrent,
  type LeagueEntry,
  type LeagueHistoryRow,
  type LeagueTierOverview,
  type LeagueAdminLadder,
  type LeagueAdminWeek,
  type LeagueAdminMember,
  type LeagueStandingRow,
  type LeagueSongInfo,
  type LeaguePerSong,
  type LeaguePreview,
  type LeaguePoolSong,
} from '../composables/useLeague';

const { t } = useI18n();
const { user, isLoggedIn } = useAuth();
const { isAdmin } = useAdmin();
const league = useLeague();

/** 運用中のラダー（現在はスコアのみ）。 */
const ladder: LadderType = 'score';

/** 進行中の週の情報（課題曲・自分のグループの順位表）。 */
const current = ref<LeagueCurrent | null>(null);
/** 自分の参加エントリー。 */
const entries = ref<LeagueEntry[]>([]);
/** 自分の過去週成績。 */
const history = ref<LeagueHistoryRow[]>([]);
/** 進行中の週の階級/グループ構成（観戦用）。 */
const overviewTiers = ref<LeagueTierOverview[]>([]);
/** 観戦用に開いた他グループの順位表。 */
const otherStandings = ref<{ tier: number; groupIndex: number; songs: LeagueSongInfo[]; standings: LeagueStandingRow[] } | null>(null);
/** 過去成績アコーディオンの開閉。 */
const showHistory = ref(false);
/** ルール説明モーダルの開閉。 */
const showInfo = ref(false);
/** 管理者 overview（管理者のみ取得）。 */
const adminLadders = ref<LeagueAdminLadder[]>([]);
/** 仮編成プレビュー（管理者が生成したときのみ。DB は更新しない）。 */
const preview = ref<LeaguePreview | null>(null);

const error = ref('');
const notice = ref('');
const busy = ref(false);

/** カウントダウン表示用の現在時刻（30 秒ごとに更新）。 */
const now = ref(Date.now());
let timer: ReturnType<typeof setInterval> | null = null;

/** 自分のエントリー。 */
const myEntry = computed(() => entries.value.find(e => e.ladderType === ladder) ?? null);
/** 参加中（active なエントリーがある）か。 */
const isJoined = computed(() => myEntry.value?.active === true);
/**
 * 参加登録は済んでいるが、開催中の週の編成には入っていない状態（＝次週から参戦）。
 * この状態で今週の課題曲を出すと「もう参加している」と誤解されるため、表示を切り替える。
 */
const isPendingNextWeek = computed(() =>
  isJoined.value && !!current.value?.week && !current.value?.member
);
/** 順位表の自分の行（ハイライト用）。 */
const myRow = computed(() =>
  current.value?.standings?.find(r => r.userId === user.value?.id) ?? null
);

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

/** 日付を「7/21」のような短い形式で表示する。 */
const shortDate = (iso: string) => {
  const d = new Date(iso);
  return `${d.getMonth() + 1}/${d.getDate()}`;
};

/** 日時を「7/21 12:00」のような形式で表示する（週の開始/終了は時刻が重要）。 */
const shortDateTime = (iso: string) => {
  const d = new Date(iso);
  const hm = `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
  return `${d.getMonth() + 1}/${d.getDate()} ${hm}`;
};

/** DIVISION の表示名（tier 0 = DIVISION LEGEND、1..10 = DIVISION n）。 */
const divisionName = (tier: number | null | undefined) => {
  if (tier == null) return '';
  return tier === 0 ? t('league.divisionLegend') : t('league.divisionN', { n: tier });
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

/** 総合 BEAT-PT から Beat-Tier ランク情報（名前・ティア）を得る（順位表のティアアイコン用）。 */
const beatTier = (pt: number | null | undefined) => getRankInfo(pt ?? 0);

/** 立場バッジの短い記号とクラス（チャレンジ=挑 / ディフェンス=防）。normal は null。 */
const roleBadge = (role: string | null | undefined) => {
  if (role === 'challenge') return { label: t('league.roleChallenge'), cls: 'bg-orange-100 dark:bg-orange-900/40 text-orange-700 dark:text-orange-300' };
  if (role === 'defense') return { label: t('league.roleDefense'), cls: 'bg-sky-100 dark:bg-sky-900/40 text-sky-700 dark:text-sky-300' };
  return null;
};

/** 課題曲のライン（有効化に必要な目標記録）の表示文字列。 */
const lineLabel = (ps: LeaguePerSong, song: LeagueSongInfo) => {
  if (ps.lineEx == null) return t('league.lineNone');
  const rate = song.notes > 0 ? ((ps.lineEx / (song.notes * 2)) * 100).toFixed(2) : '?';
  return t('league.lineScore', { ex: ps.lineEx, rate });
};

/** グループ共通のライン（song.lineEx / lineRate）からラベルを作る（観戦・他グループ表示用）。 */
const songLineLabel = (song: LeagueSongInfo) => {
  if (song.lineEx == null) return t('league.lineNone');
  const rate = song.lineRate != null
    ? song.lineRate.toFixed(2)
    : (song.notes > 0 ? ((song.lineEx / (song.notes * 2)) * 100).toFixed(2) : '?');
  return t('league.lineScore', { ex: song.lineEx, rate });
};

/** DIVISION の短縮表記（0=LEGEND、1..10=D1..D10）。立場タグの補足表示用。 */
const divisionShort = (tier: number | null | undefined) => {
  if (tier == null) return '';
  return tier === 0 ? 'LEGEND' : `D${tier}`;
};

/** 成績値（着順ポイントの3曲合計＝得点）の表示。null は "-"。 */
const formatResult = (value: number | null) => fmtPts(value);

/** 順位表の行の帯色（昇格圏 = 緑 / 降格圏 = 赤）。 */
const zoneClass = (row: LeagueStandingRow) => {
  if (row.zone === 'promote') return 'bg-emerald-50 dark:bg-emerald-900/20';
  if (row.zone === 'relegate') return 'bg-rose-50 dark:bg-rose-900/20';
  return '';
};

/** 進行中の週・順位表・履歴を読み込む。 */
const loadCurrent = async () => {
  error.value = '';
  otherStandings.value = null;
  try {
    current.value = await league.fetchCurrent(ladder);
    const ov = await league.fetchOverview(ladder);
    overviewTiers.value = ov.tiers;
    history.value = await league.fetchHistory(ladder);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
};

/** 参加状態を読み込む。 */
const loadMe = async () => {
  try {
    entries.value = await league.fetchMe();
  } catch {
    entries.value = [];
  }
};

/** 管理者 overview を読み込む（管理者のみ）。 */
const loadAdmin = async () => {
  if (!isAdmin.value) return;
  try {
    adminLadders.value = await league.fetchAdminOverview();
  } catch {
    adminLadders.value = [];
  }
  // 差し替え UI を表示する DIVISION（編集を開いている / 未編成）の選曲プールを先読みする。
  for (const al of adminLadders.value) {
    const draft = al.draftWeek;
    if (!draft) continue;
    for (const tierInfo of draft.tiers) {
      if (!tierInfo.groups.length || isSongEditOpen(draft.id, tierInfo.tier)) ensureSongPool(tierInfo.tier);
    }
  }
};

/** 参加する。 */
const handleJoin = async () => {
  busy.value = true;
  error.value = '';
  try {
    await league.join(ladder);
    notice.value = t('league.joinedNotice');
    await loadMe();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** 離脱する。 */
const handleLeave = async () => {
  if (!confirm(t('league.confirmLeave'))) return;
  busy.value = true;
  error.value = '';
  try {
    await league.leave(ladder);
    notice.value = '';
    await loadMe();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** 他グループの順位表を開く。 */
const openGroup = async (tier: number, groupIndex: number) => {
  const week = current.value?.week;
  if (!week) return;
  try {
    const res = await league.fetchStandings(week.id, tier, groupIndex);
    otherStandings.value = { tier, groupIndex, songs: res.songs, standings: res.standings };
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
};

// -------------------------------------------------------------------
// 管理者操作
// -------------------------------------------------------------------

/** 課題曲差し替えフォームの入力値を取得（無ければ現曲で初期化）。 */
/** DIVISION ごとの選曲プール（差し替えドロップダウンの選択肢）。開いたときに一度だけ取得する。 */
const songPools = ref<Record<number, LeaguePoolSong[]>>({});

/** 選曲プールを取得する（取得済み・取得中なら何もしない）。 */
const ensureSongPool = async (tier: number) => {
  if (songPools.value[tier] || poolLoading.has(tier)) return;
  poolLoading.add(tier);
  try {
    songPools.value[tier] = await league.fetchSongPool(tier);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    poolLoading.delete(tier);
  }
};
const poolLoading = new Set<number>();

/**
 * 差し替えドロップダウンの選択肢。プールに加え、現在の課題曲がプール外（帯の外の曲へ
 * 差し替え済みなど）の場合は先頭に足して、選択状態が空にならないようにする。
 */
const songOptions = (tier: number, song: LeagueSongInfo): LeaguePoolSong[] => {
  const pool = songPools.value[tier] ?? [];
  const inPool = pool.some(p => p.title === song.title && p.difficultyName === song.difficultyName);
  if (inPool) return pool;
  return [{ title: song.title, difficultyName: song.difficultyName, level: song.level, notes: song.notes }, ...pool];
};

/** 現在の課題曲が選択肢の何番目か（select の value）。 */
const currentOptionIndex = (tier: number, song: LeagueSongInfo) =>
  songOptions(tier, song).findIndex(o => o.title === song.title && o.difficultyName === song.difficultyName);

/**
 * 選曲プールから課題曲を選んで即時に差し替える（draft 週のみ）。
 * 差し替え後に overview を取り直すので、そのグループのライン・ライン保持者・各メンバーの
 * 自己ベストがその場で更新される。
 */
const handlePickSong = async (weekId: number, tier: number, song: LeagueSongInfo, ev: Event) => {
  const index = Number((ev.target as HTMLSelectElement).value);
  const picked = songOptions(tier, song)[index];
  if (!picked || (picked.title === song.title && picked.difficultyName === song.difficultyName)) return;
  busy.value = true;
  error.value = '';
  try {
    await league.replaceSong(weekId, song.id, picked.title, picked.difficultyName);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    // 成功時は新しいライン・保持者を、失敗時は元の選択状態を描き直す。
    await loadAdmin();
    busy.value = false;
  }
};

/** 指定階級の課題曲を再抽選する（draft 週のみ）。 */
const handleRedraw = async (weekId: number, tier: number) => {
  busy.value = true;
  error.value = '';
  try {
    await league.redrawTier(weekId, tier);
    await loadAdmin();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** 週次処理（締め → 編成 → 開始）を手動実行する。 */
const handleRunWeekly = async (ladder: LadderType) => {
  if (!confirm(t('league.admin.confirmRunWeekly'))) return;
  busy.value = true;
  error.value = '';
  try {
    await league.runWeekly(ladder);
    await Promise.all([loadAdmin(), loadCurrent(), loadMe()]);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** draft 週を手動作成する。 */
const handleCreateDraft = async (ladder: LadderType) => {
  busy.value = true;
  error.value = '';
  try {
    await league.createDraft(ladder);
    await loadAdmin();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** 参加締切後に draft 週を編成する（開始はしない。本番と同じ卓・グループ・課題曲を確定して確認できる）。 */
const handleForm = async (ladder: LadderType) => {
  busy.value = true;
  error.value = '';
  notice.value = '';
  try {
    await league.formDraft(ladder);
    notice.value = t('league.admin.formDone');
    await loadAdmin();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** 誤って開始した週を中止し、開始前の状態（空 draft）に戻す。昇降格 PT・DIVISION には影響しない。 */
const handleAbort = async (ladder: LadderType) => {
  if (!confirm(t('league.admin.confirmAbort'))) return;
  busy.value = true;
  error.value = '';
  notice.value = '';
  try {
    await league.abortWeek(ladder);
    notice.value = t('league.admin.abortDone');
    await Promise.all([loadAdmin(), loadCurrent(), loadMe()]);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** 仮編成プレビューを生成する（DB は更新しない）。押すたびにランダムで組み直す。 */
const handlePreview = async (ladder: LadderType) => {
  busy.value = true;
  error.value = '';
  try {
    preview.value = await league.fetchAdminPreview(ladder);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/**
 * 表示中の仮編成プレビューをそのまま draft 週へ適用する（既存の編成を置き換える）。
 * 生成後に参加者が増減している場合はサーバ側で拒否されるので、その時は作り直してから押す。
 */
const handleApplyPreview = async (ladder: LadderType) => {
  if (!preview.value) return;
  if (!confirm(t('league.admin.preview.confirmApply'))) return;
  busy.value = true;
  error.value = '';
  notice.value = '';
  try {
    await league.applyPreview(ladder, preview.value);
    notice.value = t('league.admin.preview.applyDone');
    await loadAdmin();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** レート(%)の表示（小数第2位）。null は '-'。 */
const fmtRate = (r: number | null | undefined) => (r == null ? '-' : `${r.toFixed(2)}%`);

/** draft 課題曲をグループ→スロット順に並べる（グループ別編成の確認を見やすくする）。 */
const orderedSongs = (songs: LeagueSongInfo[]) =>
  [...songs].sort((a, b) => ((a.groupIndex ?? -1) - (b.groupIndex ?? -1)) || (a.slot - b.slot));

/** draft 週の指定グループの課題曲（スロット順）。編成表の列に使う。 */
const groupSongs = (tierInfo: LeagueAdminWeek['tiers'][number], groupIndex: number) =>
  tierInfo.songs.filter(s => (s.groupIndex ?? 0) === groupIndex).sort((a, b) => a.slot - b.slot);

/** 編成表のセル（メンバーの指定スロットの自己ベスト）。未取得なら null。 */
const memberCell = (member: LeagueAdminMember, slot: number) =>
  member.bests?.find(b => b.slot === slot) ?? null;

/** その DIVISION（卓）の編成人数。 */
const tierMemberCount = (tierInfo: LeagueAdminWeek['tiers'][number]) =>
  tierInfo.groups.reduce((sum, g) => sum + g.members.length, 0);

/** 課題曲の差し替えフォームを開いている (weekId, tier)。既定は畳んで編成表を見やすくする。 */
const songEditOpen = ref<Set<string>>(new Set());
const isSongEditOpen = (weekId: number, tier: number) => songEditOpen.value.has(`${weekId}-${tier}`);
const toggleSongEdit = (weekId: number, tier: number) => {
  const key = `${weekId}-${tier}`;
  if (songEditOpen.value.has(key)) {
    songEditOpen.value.delete(key);
  } else {
    songEditOpen.value.add(key);
    ensureSongPool(tier); // 開いたタイミングで選曲プールを取りに行く
  }
};

watch(isLoggedIn, (v) => {
  if (v) {
    loadMe();
    loadCurrent();
    loadAdmin();
  }
});

onMounted(() => {
  timer = setInterval(() => { now.value = Date.now(); }, 30000);
  if (isLoggedIn.value) {
    loadMe();
    loadCurrent();
    loadAdmin();
  }
});
onUnmounted(() => {
  if (timer) clearInterval(timer);
});
</script>

<template>
  <div class="space-y-6">
    <!-- ヘッダー -->
    <div>
      <div class="flex items-center gap-3">
        <h1 class="text-2xl font-bold text-slate-800 dark:text-slate-100">{{ t('league.title') }}</h1>
        <button
          class="flex items-center gap-1 text-xs px-2.5 py-1 rounded-full border border-indigo-300 dark:border-indigo-700 text-indigo-600 dark:text-indigo-400 hover:bg-indigo-50 dark:hover:bg-indigo-900/30 transition-colors"
          @click="showInfo = true"
        >
          <svg class="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          {{ t('league.info') }}
        </button>
      </div>
      <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">{{ t('league.subtitle') }}</p>
      <p class="mt-2 text-xs text-slate-500 dark:text-slate-400">{{ t('league.scoreDesc') }}</p>
    </div>

    <!-- 未ログイン -->
    <div v-if="!isLoggedIn" class="bg-white dark:bg-slate-800 rounded-xl shadow p-6 text-center text-slate-500 dark:text-slate-400">
      {{ t('league.loginRequired') }}
    </div>

    <template v-else>
      <!-- エラー / 通知 -->
      <div v-if="error" class="bg-rose-50 dark:bg-rose-900/30 border border-rose-200 dark:border-rose-800 text-rose-700 dark:text-rose-300 rounded-lg px-4 py-3 text-sm">
        {{ error }}
      </div>
      <div v-if="notice" class="bg-emerald-50 dark:bg-emerald-900/30 border border-emerald-200 dark:border-emerald-800 text-emerald-700 dark:text-emerald-300 rounded-lg px-4 py-3 text-sm">
        {{ notice }}
      </div>

      <!-- 参加/離脱カード -->
      <div class="bg-white dark:bg-slate-800 rounded-xl shadow p-5">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div>
            <div class="flex items-center gap-2">
              <span class="font-semibold text-slate-800 dark:text-slate-100">
                {{ isJoined ? t('league.joined') : t('league.notJoined') }}
              </span>
              <span v-if="isJoined && myEntry?.currentTier != null"
                    class="text-xs px-2 py-0.5 rounded-full bg-indigo-100 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300 font-semibold">
                {{ divisionName(myEntry.currentTier) }}
              </span>
              <span v-if="isJoined && myEntry"
                    class="text-xs px-2 py-0.5 rounded-full bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 font-semibold tabular-nums">
                {{ t('league.points') }} {{ fmtPt(myEntry.points) }}
              </span>
              <!-- 参加登録済みだが今週の編成には入っていない（次週から参戦）。 -->
              <span v-if="isPendingNextWeek"
                    class="text-xs px-2 py-0.5 rounded-full bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300 font-semibold">
                {{ t('league.pendingPlacement') }}
              </span>
            </div>
            <p v-if="!isJoined" class="mt-1 text-xs text-slate-500 dark:text-slate-400">{{ t('league.joinNote') }}</p>
            <p v-if="!isJoined" class="mt-1 text-xs text-amber-600 dark:text-amber-400">{{ t('league.privacyNote') }}</p>
          </div>
          <button
            v-if="!isJoined"
            class="px-5 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-semibold disabled:opacity-50"
            :disabled="busy"
            @click="handleJoin"
          >{{ t('league.join') }}</button>
          <button
            v-else
            class="px-4 py-2 rounded-lg border border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 text-sm hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-50"
            :disabled="busy"
            @click="handleLeave"
          >{{ t('league.leave') }}</button>
        </div>
      </div>

      <!-- 開催中の週 -->
      <div v-if="!current?.week" class="bg-white dark:bg-slate-800 rounded-xl shadow p-6 text-center text-slate-500 dark:text-slate-400 text-sm">
        {{ t('league.noWeek') }}
      </div>
      <template v-else>
        <!-- 週ヘッダー + 課題曲 -->
        <div class="bg-white dark:bg-slate-800 rounded-xl shadow p-5">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <h2 class="font-bold text-slate-800 dark:text-slate-100">
              {{ t('league.weekOf', { start: shortDateTime(current.week.startsAt), end: shortDateTime(current.week.endsAt) }) }}
              <span v-if="current.member" class="ml-2 text-xs font-semibold text-indigo-600 dark:text-indigo-400">
                {{ divisionName(current.member.tier) }} / {{ t('league.groupN', { n: current.member.groupIndex + 1 }) }}
              </span>
              <span v-else-if="isPendingNextWeek"
                    class="ml-2 text-xs font-semibold px-2 py-0.5 rounded-full bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300">
                {{ t('league.notInThisWeek') }}
              </span>
            </h2>
            <span v-if="countdown" class="text-xs font-semibold text-amber-600 dark:text-amber-400">
              {{ t('league.endsIn', { time: countdown }) }}
            </span>
          </div>
          <!-- チャレンジ/ディフェンス（他卓に着席中）の説明 -->
          <div v-if="current.member && current.member.role && current.member.role !== 'normal'"
               class="mt-2 text-xs rounded-lg px-3 py-2"
               :class="current.member.role === 'challenge'
                 ? 'bg-orange-50 dark:bg-orange-900/20 text-orange-700 dark:text-orange-300'
                 : 'bg-sky-50 dark:bg-sky-900/20 text-sky-700 dark:text-sky-300'">
            {{ current.member.role === 'challenge'
              ? t('league.challengeNote', { home: divisionName(current.member.homeTier), table: divisionName(current.member.tier) })
              : t('league.defenseNote', { home: divisionName(current.member.homeTier), table: divisionName(current.member.tier) }) }}
          </div>

          <!-- 次週から参戦: 今週の課題曲は自分の対象ではないので出さない（参加中との誤解を防ぐ）。 -->
          <div v-if="isPendingNextWeek"
               class="mt-4 rounded-lg border border-amber-200 dark:border-amber-700/50 bg-amber-50 dark:bg-amber-900/20 px-4 py-3">
            <p class="text-sm font-semibold text-amber-700 dark:text-amber-300">{{ t('league.pendingTitle') }}</p>
            <p class="mt-1 text-xs leading-relaxed text-amber-700/90 dark:text-amber-300/90">{{ t('league.pendingNote') }}</p>
          </div>

          <template v-else>
          <h3 class="mt-4 text-sm font-semibold text-slate-600 dark:text-slate-300">
            <template v-if="current.member">{{ t('league.songs') }}</template>
            <template v-else>
              {{ t('league.songsPreview') }}
              <span v-if="current.previewTier != null" class="ml-1 text-indigo-600 dark:text-indigo-400">{{ divisionName(current.previewTier) }}</span>
            </template>
          </h3>
          <div class="mt-2 grid grid-cols-1 sm:grid-cols-3 gap-3">
            <div
              v-for="song in current.songs"
              :key="song.id"
              class="rounded-lg border border-slate-200 dark:border-slate-700 p-3"
            >
              <div class="text-xs text-slate-400 dark:text-slate-500">
                {{ song.difficultyName }} <span v-if="song.level">☆{{ song.level }}</span>
              </div>
              <div class="mt-0.5 font-semibold text-sm text-slate-800 dark:text-slate-100 break-words">{{ song.title }}</div>
              <template v-if="myRow">
                <div v-for="ps in myRow.perSong.filter(p => p.slot === song.slot)" :key="ps.slot" class="mt-2 text-xs space-y-1">
                  <div class="text-amber-600 dark:text-amber-400 font-semibold">{{ lineLabel(ps, song) }}</div>
                  <div>
                    <span
                      class="px-1.5 py-0.5 rounded font-semibold"
                      :class="ps.valid
                        ? 'bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-300'
                        : 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400'"
                    >{{ ps.valid ? t('league.played') : t('league.notPlayed') }}</span>
                    <span class="ml-2 text-slate-600 dark:text-slate-300">
                      {{ ps.rate != null ? `${ps.rate.toFixed(2)}% (${ps.bestEx})` : '-' }}
                    </span>
                    <span v-if="ps.rank != null" class="ml-2 font-semibold text-indigo-600 dark:text-indigo-400">{{ t('league.songRank', { n: ps.rank }) }}</span>
                  </div>
                </div>
              </template>
            </div>
          </div>
          <p v-if="current.member" class="mt-3 text-xs text-slate-400 dark:text-slate-500">{{ t('league.playRequired') }}</p>
          </template>
        </div>

        <!-- 自分のグループの順位表 -->
        <div v-if="current.standings" class="bg-white dark:bg-slate-800 rounded-xl shadow p-5">
          <div class="flex items-center justify-between">
            <h3 class="font-bold text-slate-800 dark:text-slate-100">{{ t('league.standings') }}</h3>
            <div class="flex gap-3 text-xs">
              <span class="flex items-center gap-1 text-emerald-600 dark:text-emerald-400">
                <span class="w-2.5 h-2.5 rounded-sm bg-emerald-200 dark:bg-emerald-900/60 inline-block"></span>{{ t('league.promoteZone') }}
              </span>
              <span class="flex items-center gap-1 text-rose-600 dark:text-rose-400">
                <span class="w-2.5 h-2.5 rounded-sm bg-rose-200 dark:bg-rose-900/60 inline-block"></span>{{ t('league.relegateZone') }}
              </span>
            </div>
          </div>
          <div class="mt-3 overflow-x-auto">
            <table class="w-full text-sm">
              <thead>
                <tr class="text-left text-xs text-slate-400 dark:text-slate-500 border-b border-slate-200 dark:border-slate-700">
                  <th class="py-2 pr-2 w-10">{{ t('league.rank') }}</th>
                  <th class="py-2 pr-2">{{ t('league.player') }}</th>
                  <th class="py-2 pr-2 text-center">{{ t('league.validSongs') }}</th>
                  <th class="py-2 pr-2 text-right">{{ t('league.leaguePoints') }}</th>
                  <th class="py-2 pr-2 text-center">{{ t('league.points') }}</th>
                  <th class="py-2 pr-1 text-center whitespace-nowrap" v-for="s in current.songs" :key="s.id"
                      :title="`${s.slot}. ${s.title}`">
                    {{ s.slot }}
                    <span class="font-normal text-[10px] text-slate-300 dark:text-slate-600">{{ t('league.songPoints') }}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="row in current.standings"
                  :key="row.userId"
                  class="border-b border-slate-100 dark:border-slate-700/50"
                  :class="[zoneClass(row), row.userId === user?.id ? 'font-semibold' : '']"
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
                      <span v-if="row.userId === user?.id" class="text-[10px] text-indigo-500 dark:text-indigo-400">YOU</span>
                    </span>
                  </td>
                  <td class="py-2 pr-2 text-center">{{ row.validSongs }}/3</td>
                  <td class="py-2 pr-2 text-right tabular-nums">{{ formatResult(row.resultValue) }}</td>
                  <td class="py-2 pr-2 text-center tabular-nums whitespace-nowrap">
                    {{ fmtPt(row.points) }}
                    <span class="text-xs text-slate-400 dark:text-slate-500">({{ fmtPt(row.pointDelta) }})</span>
                  </td>
                  <!-- 曲別セル: 有効になったリザルトの EX ＋ 着順とその曲の着順ポイント。
                       未達（ライン超え前）の自己ベストは競技結果ではないので出さない。 -->
                  <td v-for="ps in row.perSong" :key="ps.slot" class="py-2 px-1 text-center text-xs tabular-nums whitespace-nowrap">
                    <div v-if="ps.valid && ps.bestEx != null && ps.bestEx > 0"
                         class="font-semibold text-emerald-600 dark:text-emerald-400">
                      {{ ps.bestEx }}
                    </div>
                    <div v-else class="text-slate-300 dark:text-slate-600">–</div>
                    <div class="text-[10px] leading-tight text-slate-400 dark:text-slate-500">
                      <span v-if="ps.rank != null">{{ t('league.songRank', { n: ps.rank }) }} </span>
                      <span v-if="ps.points != null">{{ fmtPts(ps.points) }}{{ t('league.songPoints') }}</span>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="mt-2 text-[11px] leading-relaxed text-slate-400 dark:text-slate-500">{{ t('league.songPointsHint') }}</p>
        </div>

        <!-- 他グループ（観戦） -->
        <div v-if="overviewTiers.length" class="bg-white dark:bg-slate-800 rounded-xl shadow p-5">
          <h3 class="font-bold text-slate-800 dark:text-slate-100">{{ t('league.otherGroups') }}</h3>
          <div class="mt-3 space-y-2">
            <div v-for="tierInfo in overviewTiers" :key="tierInfo.tier" class="flex flex-wrap items-center gap-2">
              <span class="text-xs font-semibold text-slate-500 dark:text-slate-400 w-32">{{ divisionName(tierInfo.tier) }}</span>
              <button
                v-for="g in tierInfo.groups"
                :key="g.groupIndex"
                class="text-xs px-3 py-1 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700"
                @click="openGroup(tierInfo.tier, g.groupIndex)"
              >
                {{ t('league.groupN', { n: g.groupIndex + 1 }) }} ({{ g.memberCount }})
              </button>
            </div>
          </div>

          <!-- 開いた他グループの順位表 -->
          <div v-if="otherStandings" class="mt-4 border-t border-slate-200 dark:border-slate-700 pt-3">
            <div class="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">
              {{ divisionName(otherStandings.tier) }} / {{ t('league.groupN', { n: otherStandings.groupIndex + 1 }) }}
            </div>
            <!-- 他グループの課題曲 + 有効ライン -->
            <div v-if="otherStandings.songs.length" class="grid grid-cols-1 sm:grid-cols-3 gap-2 mb-3">
              <div v-for="song in otherStandings.songs" :key="song.slot"
                   class="rounded-lg border border-slate-200 dark:border-slate-700 p-2">
                <div class="text-[10px] text-slate-400 dark:text-slate-500">
                  {{ song.difficultyName }} <span v-if="song.level">☆{{ song.level }}</span>
                </div>
                <div class="text-xs font-semibold text-slate-800 dark:text-slate-100 break-words">{{ song.title }}</div>
                <div class="mt-0.5 text-[11px] font-semibold text-amber-600 dark:text-amber-400">{{ songLineLabel(song) }}</div>
              </div>
            </div>
            <div class="overflow-x-auto">
              <table class="w-full text-sm">
                <thead>
                  <tr class="text-left text-xs text-slate-400 dark:text-slate-500 border-b border-slate-200 dark:border-slate-700">
                    <th class="py-1.5 pr-2 w-10">{{ t('league.rank') }}</th>
                    <th class="py-1.5 pr-2">{{ t('league.player') }}</th>
                    <th class="py-1.5 pr-2 text-center">{{ t('league.validSongs') }}</th>
                    <th class="py-1.5 pr-2 text-right">{{ t('league.leaguePoints') }}</th>
                    <th class="py-1.5 pr-2 text-center">{{ t('league.points') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in otherStandings.standings" :key="row.userId"
                      class="border-b border-slate-100 dark:border-slate-700/50" :class="zoneClass(row)">
                    <td class="py-1.5 pr-2">{{ row.rank }}</td>
                    <td class="py-1.5 pr-2 break-words">
                      <span class="inline-flex items-center gap-1.5 align-middle">
                        <RankIcon :rank-name="beatTier(row.totalBeatPt).name" :tier="beatTier(row.totalBeatPt).tier" size="2xs" lite disable-party />
                        <span>{{ row.displayName }}</span>
                        <span v-if="roleBadge(row.role)"
                              class="inline-flex items-center gap-0.5 px-1.5 py-px rounded text-[10px] font-bold leading-none"
                              :class="roleBadge(row.role)!.cls"
                              :title="roleBadge(row.role)!.label + (row.homeTier != null ? ' / ' + divisionName(row.homeTier) : '')">{{ roleBadge(row.role)!.label }}<span v-if="row.homeTier != null" class="font-semibold opacity-80">{{ divisionShort(row.homeTier) }}</span></span>
                      </span>
                    </td>
                    <td class="py-1.5 pr-2 text-center">{{ row.validSongs }}/3</td>
                    <td class="py-1.5 pr-2 text-right tabular-nums">{{ formatResult(row.resultValue) }}</td>
                    <td class="py-1.5 pr-2 text-center tabular-nums whitespace-nowrap">
                      {{ fmtPt(row.points) }}
                      <span class="text-xs text-slate-400 dark:text-slate-500">({{ fmtPt(row.pointDelta) }})</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </template>

      <!-- 過去の成績 -->
      <div class="bg-white dark:bg-slate-800 rounded-xl shadow p-5">
        <button class="w-full flex items-center justify-between text-left" @click="showHistory = !showHistory">
          <h3 class="font-bold text-slate-800 dark:text-slate-100">{{ t('league.history') }}</h3>
          <span class="text-slate-400">{{ showHistory ? '▲' : '▼' }}</span>
        </button>
        <div v-if="showHistory" class="mt-3">
          <p v-if="!history.length" class="text-sm text-slate-400 dark:text-slate-500">{{ t('league.noHistory') }}</p>
          <table v-else class="w-full text-sm">
            <thead>
              <tr class="text-left text-xs text-slate-400 dark:text-slate-500 border-b border-slate-200 dark:border-slate-700">
                <th class="py-2 pr-2">{{ t('league.week') }}</th>
                <th class="py-2 pr-2">{{ t('league.tier') }}</th>
                <th class="py-2 pr-2 text-center">{{ t('league.rank') }}</th>
                <th class="py-2 pr-2 text-right">{{ t('league.leaguePoints') }}</th>
                <th class="py-2 pr-2 text-center">{{ t('league.points') }}</th>
                <th class="py-2 pr-2 text-center">{{ t('league.movementLabel') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="h in history" :key="h.weekId" class="border-b border-slate-100 dark:border-slate-700/50">
                <td class="py-2 pr-2">{{ shortDate(h.startsAt) }}〜{{ shortDate(h.endsAt) }}</td>
                <td class="py-2 pr-2">{{ divisionName(h.tier) }}</td>
                <td class="py-2 pr-2 text-center">{{ h.finalRank ?? '-' }}</td>
                <td class="py-2 pr-2 text-right tabular-nums">{{ formatResult(h.resultValue) }}</td>
                <td class="py-2 pr-2 text-center tabular-nums">{{ fmtPt(h.pointDelta) }}</td>
                <td class="py-2 pr-2 text-center">
                  <span
                    v-if="h.movement"
                    class="text-xs px-2 py-0.5 rounded-full font-semibold"
                    :class="h.movement === 'promote'
                      ? 'bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-300'
                      : h.movement === 'relegate'
                        ? 'bg-rose-100 dark:bg-rose-900/40 text-rose-700 dark:text-rose-300'
                        : 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400'"
                  >{{ t(`league.movement.${h.movement}`) }}</span>
                  <span v-else>-</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 管理者セクション -->
      <div v-if="isAdmin" class="bg-white dark:bg-slate-800 rounded-xl shadow p-5 border-2 border-amber-300 dark:border-amber-700">
        <h3 class="font-bold text-amber-700 dark:text-amber-400">{{ t('league.admin.title') }}</h3>
        <div v-for="al in adminLadders" :key="al.ladder" class="mt-4 border-t border-slate-200 dark:border-slate-700 pt-3">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div class="font-semibold text-slate-700 dark:text-slate-200">
              {{ t('league.scoreLadder') }}
              <span class="ml-2 text-xs text-slate-400">{{ t('league.admin.entryCount') }}: {{ al.activeEntryCount }}</span>
            </div>
            <div class="flex flex-wrap gap-2">
              <button class="text-xs px-3 py-1.5 rounded-lg bg-slate-600 hover:bg-slate-700 text-white disabled:opacity-50"
                      :disabled="busy" @click="handleCreateDraft(al.ladder)">{{ t('league.admin.createDraft') }}</button>
              <button class="text-xs px-3 py-1.5 rounded-lg bg-teal-600 hover:bg-teal-700 text-white disabled:opacity-50"
                      :disabled="busy" @click="handleForm(al.ladder)">{{ t('league.admin.form') }}</button>
              <button class="text-xs px-3 py-1.5 rounded-lg bg-amber-600 hover:bg-amber-700 text-white disabled:opacity-50"
                      :disabled="busy" @click="handleRunWeekly(al.ladder)">{{ t('league.admin.runWeekly') }}</button>
              <button v-if="al.activeWeek" class="text-xs px-3 py-1.5 rounded-lg bg-rose-600 hover:bg-rose-700 text-white disabled:opacity-50"
                      :disabled="busy" @click="handleAbort(al.ladder)">{{ t('league.admin.abort') }}</button>
            </div>
          </div>

          <!-- draft 週の課題曲編集 -->
          <div v-if="al.draftWeek" class="mt-3">
            <div class="text-xs font-semibold text-slate-500 dark:text-slate-400">
              {{ t('league.admin.draftWeek') }}: {{ shortDateTime(al.draftWeek.startsAt) }}〜{{ shortDateTime(al.draftWeek.endsAt) }}
              <span v-if="al.draftWeek.memberCount" class="ml-1 text-teal-600 dark:text-teal-400">
                / {{ t('league.admin.formedCount', { n: al.draftWeek.memberCount }) }}
              </span>
              <span v-else class="ml-1 text-slate-400">/ {{ t('league.admin.notFormed') }}</span>
            </div>
            <div v-for="tierInfo in al.draftWeek.tiers" :key="tierInfo.tier" class="mt-3 rounded-lg border border-slate-200 dark:border-slate-700 p-3">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <div class="text-sm font-bold text-slate-700 dark:text-slate-200">
                  {{ divisionName(tierInfo.tier) }}
                  <span class="text-xs font-normal text-slate-400">({{ tierMemberCount(tierInfo) }})</span>
                </div>
                <div class="flex flex-wrap gap-2">
                  <button class="text-xs px-2 py-1 rounded border border-slate-300 dark:border-slate-600 text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700"
                          @click="toggleSongEdit(al.draftWeek!.id, tierInfo.tier)">
                    {{ isSongEditOpen(al.draftWeek!.id, tierInfo.tier) ? t('league.admin.editSongsClose') : t('league.admin.editSongs') }}
                  </button>
                  <button class="text-xs px-2 py-1 rounded border border-slate-300 dark:border-slate-600 text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-50"
                          :disabled="busy" @click="handleRedraw(al.draftWeek!.id, tierInfo.tier)">{{ t('league.admin.redraw') }}</button>
                </div>
              </div>

              <!-- 課題曲の差し替え（既定は畳んでおく。未編成の draft では常に出す）。
                   選曲プールから選ぶと即時に差し替わり、ライン・保持者・自己ベスト表が更新される。 -->
              <div v-if="isSongEditOpen(al.draftWeek.id, tierInfo.tier) || !tierInfo.groups.length"
                   class="mt-2 rounded-lg bg-slate-50 dark:bg-slate-900/40 p-2 space-y-1.5">
                <div class="text-[11px] text-slate-400">
                  {{ songPools[tierInfo.tier]
                    ? t('league.admin.poolCount', { n: songPools[tierInfo.tier].length })
                    : t('league.admin.poolLoading') }}
                </div>
                <div v-for="song in orderedSongs(tierInfo.songs)" :key="song.id" class="flex flex-wrap items-center gap-2 text-xs">
                  <span v-if="song.groupIndex != null"
                        class="text-[10px] px-1.5 py-0.5 rounded bg-slate-200 dark:bg-slate-700 text-slate-500 dark:text-slate-400 whitespace-nowrap">
                    {{ t('league.groupN', { n: song.groupIndex + 1 }) }}
                  </span>
                  <span class="text-slate-400 w-4">{{ song.slot }}.</span>
                  <select
                    class="flex-1 min-w-40 px-2 py-1 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 text-slate-700 dark:text-slate-200 disabled:opacity-50"
                    :disabled="busy || !songPools[tierInfo.tier]"
                    :value="String(currentOptionIndex(tierInfo.tier, song))"
                    @change="handlePickSong(al.draftWeek!.id, tierInfo.tier, song, $event)"
                  >
                    <option v-for="(opt, i) in songOptions(tierInfo.tier, song)" :key="`${opt.title}|${opt.difficultyName}`" :value="String(i)">
                      {{ opt.title }} [{{ opt.difficultyName }}{{ opt.level ? ` ☆${opt.level}` : '' }}]
                    </option>
                  </select>
                </div>
              </div>

              <!-- グループごとの編成（仮編成プレビューと同じ表: 課題曲 × メンバーの自己ベスト） -->
              <div v-for="g in tierInfo.groups" :key="g.groupIndex" class="mt-3">
                <div class="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">
                  {{ t('league.groupN', { n: g.groupIndex + 1 }) }} ({{ g.members.length }})
                </div>
                <div class="overflow-x-auto">
                  <table class="min-w-full text-xs border-collapse">
                    <thead>
                      <tr class="align-bottom">
                        <th class="text-left font-semibold py-1 pr-3 whitespace-nowrap text-slate-500 dark:text-slate-400">
                          {{ t('league.admin.preview.player') }}
                        </th>
                        <th v-for="s in groupSongs(tierInfo, g.groupIndex)" :key="s.id"
                            class="text-left font-semibold py-1 px-2 align-bottom min-w-[9rem]">
                          <div class="text-slate-700 dark:text-slate-200 break-words leading-tight">{{ s.title }}</div>
                          <div class="text-[10px] font-normal text-slate-400">
                            {{ s.difficultyName }} <span v-if="s.level">☆{{ s.level }}</span>
                          </div>
                          <div class="text-[10px] font-semibold text-amber-600 dark:text-amber-400">
                            {{ t('league.admin.preview.lineLabel') }}:
                            <template v-if="s.lineEx != null">{{ s.lineEx }} ({{ fmtRate(s.lineRate) }})</template>
                            <template v-else>{{ t('league.lineNone') }}</template>
                          </div>
                          <div v-if="s.lineHolders && s.lineHolders.length"
                               class="text-[10px] font-normal text-slate-500 dark:text-slate-400 break-words leading-tight">
                            {{ t('league.admin.preview.lineHolder') }}: {{ s.lineHolders.join(' / ') }}
                          </div>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="mem in g.members" :key="mem.userId"
                          class="border-t border-slate-100 dark:border-slate-700/60">
                        <td class="py-1 pr-3 whitespace-nowrap text-slate-700 dark:text-slate-200">
                          {{ mem.displayName || mem.iidxId || '—' }}
                          <span v-if="roleBadge(mem.role)"
                                class="ml-1 inline-flex items-center gap-0.5 px-1 py-0.5 rounded text-[10px]"
                                :class="roleBadge(mem.role)!.cls">
                            {{ roleBadge(mem.role)!.label }}<span class="font-semibold opacity-80">{{ divisionShort(mem.homeTier) }}</span>
                          </span>
                        </td>
                        <td v-for="s in groupSongs(tierInfo, g.groupIndex)" :key="s.id"
                            class="py-1 px-2 whitespace-nowrap tabular-nums"
                            :class="memberCell(mem, s.slot)?.isLine
                              ? 'bg-amber-100 dark:bg-amber-900/40 font-bold text-amber-800 dark:text-amber-200'
                              : 'text-slate-600 dark:text-slate-300'">
                          <template v-if="memberCell(mem, s.slot)?.played">
                            {{ memberCell(mem, s.slot)!.ex }}
                            <span class="text-[10px]"
                                  :class="memberCell(mem, s.slot)!.isLine ? 'text-amber-700 dark:text-amber-300' : 'text-slate-400'">({{ fmtRate(memberCell(mem, s.slot)!.rate) }})</span>
                            <span v-if="memberCell(mem, s.slot)!.isLine" class="ml-0.5 text-[10px]">◆</span>
                          </template>
                          <span v-else class="text-slate-300 dark:text-slate-600">—</span>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="mt-2 text-xs text-slate-400">{{ t('league.admin.draftWeek') }}: {{ t('league.admin.none') }}</div>

          <!-- active 週のサマリ -->
          <div class="mt-2 text-xs text-slate-400">
            {{ t('league.admin.activeWeek') }}:
            <template v-if="al.activeWeek">
              {{ shortDateTime(al.activeWeek.startsAt) }}〜{{ shortDateTime(al.activeWeek.endsAt) }}
              ({{ al.activeWeek.memberCount }})
            </template>
            <template v-else>{{ t('league.admin.none') }}</template>
          </div>
        </div>

        <!-- 仮編成プレビュー（DB 非更新のテスト用） -->
        <div class="mt-5 border-t border-slate-200 dark:border-slate-700 pt-3">
          <div class="flex flex-wrap items-start justify-between gap-2">
            <div>
              <div class="font-semibold text-slate-700 dark:text-slate-200">{{ t('league.admin.preview.title') }}</div>
              <p class="text-xs text-slate-400 mt-0.5 max-w-lg">{{ t('league.admin.preview.desc') }}</p>
            </div>
            <div class="flex flex-wrap gap-2">
              <button class="text-xs px-3 py-1.5 rounded-lg bg-teal-600 hover:bg-teal-700 text-white disabled:opacity-50 whitespace-nowrap"
                      :disabled="busy" @click="handlePreview(ladder)">{{ t('league.admin.preview.generate') }}</button>
              <button v-if="preview && preview.tiers.length"
                      class="text-xs px-3 py-1.5 rounded-lg bg-rose-600 hover:bg-rose-700 text-white disabled:opacity-50 whitespace-nowrap"
                      :disabled="busy" @click="handleApplyPreview(ladder)">{{ t('league.admin.preview.apply') }}</button>
            </div>
          </div>

          <div v-if="preview" class="mt-3">
            <div class="text-xs text-slate-500 dark:text-slate-400">
              {{ t('league.admin.preview.entryCount', { n: preview.entryCount }) }}
              <span class="ml-2 text-slate-400">{{ t('league.admin.preview.lineHint') }}</span>
            </div>
            <p class="mt-1 text-[11px] text-slate-400">{{ t('league.admin.preview.applyHint') }}</p>
            <div v-if="!preview.tiers.length" class="mt-2 text-xs text-slate-400">{{ t('league.admin.preview.empty') }}</div>

            <!-- 卓（host DIVISION）ごと -->
            <div v-for="tp in preview.tiers" :key="tp.host"
                 class="mt-3 rounded-lg border border-slate-200 dark:border-slate-700 p-3">
              <div class="text-sm font-bold text-slate-700 dark:text-slate-200">
                {{ divisionName(tp.host) }}
                <span class="text-xs font-normal text-slate-400">({{ tp.memberCount }})</span>
              </div>

              <!-- グループごと -->
              <div v-for="g in tp.groups" :key="g.groupIndex" class="mt-3">
                <div class="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">
                  {{ t('league.groupN', { n: g.groupIndex + 1 }) }} ({{ g.memberCount }})
                </div>
                <div class="overflow-x-auto">
                  <table class="min-w-full text-xs border-collapse">
                    <thead>
                      <tr class="align-bottom">
                        <th class="text-left font-semibold py-1 pr-3 whitespace-nowrap text-slate-500 dark:text-slate-400">
                          {{ t('league.admin.preview.player') }}
                        </th>
                        <th v-for="s in g.songs" :key="s.slot"
                            class="text-left font-semibold py-1 px-2 align-bottom min-w-[9rem]">
                          <div class="text-slate-700 dark:text-slate-200 break-words leading-tight">{{ s.title }}</div>
                          <div class="text-[10px] font-normal text-slate-400">
                            {{ s.difficultyName }} <span v-if="s.level">☆{{ s.level }}</span>
                          </div>
                          <div class="text-[10px] font-semibold text-amber-600 dark:text-amber-400">
                            {{ t('league.admin.preview.lineLabel') }}:
                            <template v-if="s.lineEx != null">{{ s.lineEx }} ({{ fmtRate(s.lineRate) }})</template>
                            <template v-else>{{ t('league.lineNone') }}</template>
                          </div>
                          <div v-if="s.lineHolders && s.lineHolders.length"
                               class="text-[10px] font-normal text-slate-500 dark:text-slate-400 break-words leading-tight">
                            {{ t('league.admin.preview.lineHolder') }}: {{ s.lineHolders.join(' / ') }}
                          </div>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="(p, pi) in g.players" :key="pi"
                          class="border-t border-slate-100 dark:border-slate-700/60">
                        <td class="py-1 pr-3 whitespace-nowrap text-slate-700 dark:text-slate-200">
                          {{ p.displayName || '—' }}
                          <span v-if="roleBadge(p.role)"
                                class="ml-1 inline-flex items-center gap-0.5 px-1 py-0.5 rounded text-[10px]"
                                :class="roleBadge(p.role)!.cls">
                            {{ roleBadge(p.role)!.label }}<span class="font-semibold opacity-80">{{ divisionShort(p.homeTier) }}</span>
                          </span>
                        </td>
                        <td v-for="cell in p.bests" :key="cell.slot"
                            class="py-1 px-2 whitespace-nowrap tabular-nums"
                            :class="cell.isLine
                              ? 'bg-amber-100 dark:bg-amber-900/40 font-bold text-amber-800 dark:text-amber-200'
                              : 'text-slate-600 dark:text-slate-300'">
                          <template v-if="cell.played">
                            {{ cell.ex }}
                            <span class="text-[10px]" :class="cell.isLine ? 'text-amber-700 dark:text-amber-300' : 'text-slate-400'">({{ fmtRate(cell.rate) }})</span>
                            <span v-if="cell.isLine" class="ml-0.5 text-[10px]">◆</span>
                          </template>
                          <span v-else class="text-slate-300 dark:text-slate-600">—</span>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- ルール説明モーダル -->
    <LeagueInfoModal v-if="showInfo" @close="showInfo = false" />
  </div>
</template>
