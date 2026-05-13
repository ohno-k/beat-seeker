<script setup lang="ts">
/**
 * 【View の役割】 招待 URL `/competition/player/{token}` でアクセスする参加者向け画面。
 *
 * スタンドアロンページ (サイドバー・グローバルヘッダなし・ログイン不要)。
 * App.vue の最上位 v-else-if で StrategyCardView 等と並列にレンダリングされる。
 *
 * Phase 4 モデル:
 *  - 自選曲は (プレイヤー × 試合) 単位。試合ごとに独立した曲を提出する。
 *  - 各試合には運営が指定したジャンル ({@code requiredGenre}) があり、プレイヤーは
 *    そのジャンルからしか曲を選べない。未指定の試合は提出 UI を出さない (運営待ち)。
 *
 * 編集制約:
 *  - 試合の自分側ロック済み → 編集不可
 *  - 試合の requiredGenre が null → 編集 UI を見せない
 *  - 大会 status === 'finished' で全編集不可
 *  - StrategyCard は対象試合の相手側ロック完了後のみ操作可
 */
import { ref, computed, onMounted, watch } from 'vue';
import strategySongs from '../data/strategy_card_songs.json';
import {
  useCompetitionPlayer,
  type MatchKind,
  type SongGenre,
  type PlayerMatchDto,
} from '../composables/useCompetitionPlayer';
import { useToast } from '../composables/useToast';
import { useI18n } from '../composables/useI18n';

const props = defineProps<{ token: string }>();

const { view, isLoading, fetchView, upsertPick, deletePick, setStrategy } = useCompetitionPlayer();
const toast = useToast();
const { t, currentLang, setLanguage, availableLanguages } = useI18n();

onMounted(() => fetchView(props.token).catch(e => toast.error((e as Error).message)));
watch(() => props.token, () => fetchView(props.token).catch(e => toast.error((e as Error).message)));

// ── 静的データ ────────────────────────────────────────────
type Song = { id: number; version: string; title: string; diff: 'A' | 'L'; level: number };
const songs = strategySongs as Record<SongGenre, Record<string, Song[]>>;

const LEVELS_FOR_KIND: Record<MatchKind, number[]> = {
  vanguard: [8, 9, 10],
  middle: [11],
  captain: [12],
};
/** 戦種別ラベルは i18n 経由で解決する (テンプレート/ハンドラ両方から呼べるよう関数化)。 */
const kindLabel = (kind: MatchKind) => t(`competition.matchKind.${kind}`);

// ── 編集状態 ──────────────────────────────────────────────
/** どの試合の pick エディタを開いているか。null なら全カード閉じ。 */
const editingMatchId = ref<number | null>(null);
/** 編集中に選んだ Lv (vanguard で必要)。 */
const editingLevel = ref<number | null>(null);
/**
 * 曲管理番号の直接入力欄。
 * ユーザーが ID を入れて Enter (or 確定ボタン) すると、現在編集中の試合の requiredGenre 内で
 * 該当 ID の曲を探して即 submit する (Lv 選択をスキップ)。
 */
const directIdInput = ref('');

/** editingMatchId が指す試合本体。 */
const editingMatch = computed<PlayerMatchDto | null>(() => {
  if (editingMatchId.value === null || !view.value) return null;
  return view.value.matches.find(m => m.matchId === editingMatchId.value) ?? null;
});

const startEditing = (m: PlayerMatchDto) => {
  if (!m.requiredGenre) {
    toast.error('運営がジャンルを指定するまで提出できません');
    return;
  }
  if (m.myLocked) {
    toast.error('ロック済のため変更できません');
    return;
  }
  editingMatchId.value = m.matchId;
  // Lv が一意の戦 (middle / captain) は自動セット
  editingLevel.value = m.matchKind === 'middle' ? 11 : m.matchKind === 'captain' ? 12 : null;
  directIdInput.value = '';
};
const cancelEditing = () => {
  editingMatchId.value = null;
  editingLevel.value = null;
  directIdInput.value = '';
};

const hasGenreLevel = (genre: SongGenre, level: number): boolean => !!songs[genre]?.[String(level)]?.length;

/** 編集中の (戦 × ジャンル) で実際に曲がある Lv 群。 */
const availableLevels = computed<number[]>(() => {
  if (!editingMatch.value || !editingMatch.value.requiredGenre) return [];
  return LEVELS_FOR_KIND[editingMatch.value.matchKind]
    .filter(lv => hasGenreLevel(editingMatch.value!.requiredGenre!, lv));
});

const availableSongs = computed<Song[]>(() => {
  if (!editingMatch.value || !editingMatch.value.requiredGenre || editingLevel.value === null) return [];
  return songs[editingMatch.value.requiredGenre]?.[String(editingLevel.value)] ?? [];
});

const selectLevel = (lv: number) => { editingLevel.value = lv; };

/**
 * 確認ダイアログ用の保留中の曲。
 * 曲を選んだ後、すぐにサーバ送信ぜずにこの ref にセットしてモーダル表示する。
 * ユーザーが「はい」を押したら submitSongConfirmed() で確定送信。
 */
const pendingSubmitSong = ref<Song | null>(null);

/** 曲リスト or 管理番号で曲が選ばれた最初のエントリポイント。確認モーダルを開く。 */
const submitSong = (s: Song) => {
  pendingSubmitSong.value = s;
};

/** 確認モーダル「はい」で実際にサーバへ提出する。 */
const submitSongConfirmed = async () => {
  if (!pendingSubmitSong.value) return;
  if (!editingMatch.value || !editingMatch.value.requiredGenre) return;
  const s = pendingSubmitSong.value;
  const matchId = editingMatch.value.matchId;
  const genre = editingMatch.value.requiredGenre;
  try {
    await upsertPick(props.token, matchId, {
      songGenre: genre,
      songLevel: s.level,
      songStrategyId: s.id,
      songTitle: s.title,
      songDiff: s.diff,
    });
    toast.success('自選曲を更新しました');
    pendingSubmitSong.value = null;
    cancelEditing();
  } catch (e) {
    toast.error((e as Error).message);
  }
};

const cancelSubmitConfirm = () => {
  pendingSubmitSong.value = null;
};

/**
 * 編集中の試合の requiredGenre 内で「曲管理番号 = directIdInput」の曲を探し、見つかれば即 submit。
 * Lv 帯外 (vanguard なのに Lv12 など) や、該当 ID 不在の場合はエラーを通知する。
 */
const submitByDirectId = async () => {
  if (!editingMatch.value || !editingMatch.value.requiredGenre) return;
  const raw = directIdInput.value.trim();
  if (!raw) {
    toast.error('曲管理番号を入力してください');
    return;
  }
  const idNum = Number(raw);
  if (!Number.isInteger(idNum) || idNum <= 0) {
    toast.error('正の整数を入力してください');
    return;
  }
  const kind = editingMatch.value.matchKind;
  const genre = editingMatch.value.requiredGenre;
  // matchKind の Lv 帯を一通りスキャンして該当 ID の曲を探す
  let found: Song | null = null;
  for (const lv of LEVELS_FOR_KIND[kind]) {
    const arr = songs[genre]?.[String(lv)] ?? [];
    const hit = arr.find(s => s.id === idNum);
    if (hit) { found = hit; break; }
  }
  if (!found) {
    toast.error(`${kindLabel(kind)} の ${genre} に管理番号 ${idNum} は存在しません`);
    return;
  }
  await submitSong(found);
};

const removePick = async (m: PlayerMatchDto) => {
  if (!confirm(`${kindLabel(m.matchKind)} ${t('competition.player.cancelButton')}?`)) return;
  try {
    await deletePick(props.token, m.matchId);
    toast.success('取り消しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

// ── StrategyCard 切替 ─────────────────────────────────────
const toggleStrategy = async (matchId: number, currentEnabled: boolean) => {
  try {
    await setStrategy(props.token, matchId, !currentEnabled);
    toast.success(!currentEnabled ? 'StrategyCard を「使用」に設定しました' : 'StrategyCard を「不使用」に設定しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

// ── 表示補助 ──────────────────────────────────────────────
const statusLabel = (s: string) => {
  if (['draft', 'open', 'locked', 'finished'].includes(s)) {
    return t(`competition.status.${s}`);
  }
  return s;
};

/** 編集ボタンを出して良いか。ロック済 / ジャンル未指定 / 大会終了で false。 */
const canEditMatch = (m: PlayerMatchDto): boolean => {
  if (!view.value) return false;
  if (view.value.competition.status === 'finished') return false;
  if (m.myLocked) return false;
  if (!m.requiredGenre) return false;
  return true;
};

/**
 * 当該試合の matchup で StrategyCard を新規に使えるか。
 * 既にこの matchup で自チームが card を使っているなら ON/OFF 切替 OK。
 * そうでない場合、自チームの使用済 matchup 数が limit 未満であれば OK。
 */
const canEnableStrategyHere = (matchId: number): boolean => {
  if (!view.value) return false;
  const quota = view.value.strategyQuota;
  const match = view.value.matches.find(mm => mm.matchId === matchId);
  if (!match) return false;
  // この matchup の matchId を直接持っていないので、同 matchup の他 match と組で判定するのは難しい。
  // サーバ側は matchup 単位で判定するが、フロントは match 単位の matchId しか持っていないので、
  // すでにこの matchId で enabled なら ON 維持可能 / OFF→ON も同じ matchup 内ならカウント増えない。
  // ここでは保守的に「strategyQuota.usedMatchupCount < limit OR この match で既に enabled」のみ許可する。
  if (match.myStrategyUse?.enabled) return true;
  return quota.usedMatchupCount < quota.limit;
};
</script>

<template>
  <div class="competition-player-view min-h-screen bg-slate-50 dark:bg-slate-900 text-slate-800 dark:text-slate-100 p-4 sm:p-8 relative">
    <!-- 言語切替ボタン群 (右上固定) -->
    <div class="absolute top-3 right-3 z-30 flex items-center gap-1 p-1 rounded-xl bg-white/80 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 backdrop-blur shadow-sm">
      <button
        v-for="lang in availableLanguages"
        :key="lang"
        type="button"
        @click="setLanguage(lang)"
        :aria-pressed="currentLang === lang"
        class="px-2 py-1 text-[11px] font-bold rounded-lg transition-all"
        :class="currentLang === lang
          ? 'bg-blue-600 text-white shadow-sm'
          : 'text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700'"
      >{{ t(`lang.${lang}`) }}</button>
    </div>

    <div v-if="isLoading && !view" class="text-center py-20 text-slate-400 text-sm">{{ t('competition.player.loading') }}</div>

    <div
      v-else-if="!view"
      class="max-w-2xl mx-auto bg-rose-50 dark:bg-rose-900/30 border border-rose-200 dark:border-rose-700 rounded-2xl p-6 text-center"
    >
      <p class="text-lg font-bold text-rose-700 dark:text-rose-300">{{ t('competition.player.invalidToken') }}</p>
      <p class="text-sm text-rose-600 dark:text-rose-400 mt-2">{{ t('competition.player.invalidTokenHint') }}</p>
    </div>

    <div v-else class="max-w-4xl mx-auto space-y-6">
      <!-- ヘッダ -->
      <div>
        <p class="text-[10px] font-mono uppercase tracking-[0.3em] text-slate-400 dark:text-slate-500">{{ view.competition.name }}</p>
        <div class="flex items-baseline gap-2 mt-1 flex-wrap">
          <h1 class="text-2xl sm:text-3xl font-black tracking-tight">{{ view.team.teamName }}</h1>
          <span class="text-slate-400">·</span>
          <p class="text-lg sm:text-2xl font-bold">{{ view.participant.displayName }}</p>
          <span v-if="view.participant.isTl" class="text-[10px] font-black px-2 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300 tracking-wider">{{ t('competition.common.tlBadge') }}</span>
        </div>
        <p class="text-xs text-slate-500 dark:text-slate-400 mt-2 font-mono">
          {{ t('competition.common.status') }} <span class="font-bold">{{ statusLabel(view.competition.status) }}</span>
          <span v-if="view.competition.deadlineAt"> · {{ t('competition.common.deadline') }} {{ new Date(view.competition.deadlineAt).toLocaleString() }}</span>
        </p>
        <!-- 自チームの StrategyCard 使用枠 (予選: 4 matchup 中 2 まで) -->
        <p class="text-xs mt-1 font-mono">
          <span class="text-slate-500">{{ t('competition.player.strategyQuota') }}:</span>
          <span
            class="ml-1 font-bold tabular-nums"
            :class="view.strategyQuota.usedMatchupCount >= view.strategyQuota.limit
              ? 'text-rose-500 dark:text-rose-300'
              : 'text-emerald-600 dark:text-emerald-300'"
          >
            {{ view.strategyQuota.usedMatchupCount }} / {{ view.strategyQuota.limit }} {{ t('competition.player.matchupUnit') }}
          </span>
          <span class="text-slate-400 ml-1">{{ t('competition.player.strategyQuotaSuffix') }}</span>
        </p>
      </div>

      <!-- ===== 担当試合 ===== -->
      <section class="space-y-3">
        <h2 class="text-xs font-black tracking-[0.3em] uppercase text-slate-500">{{ t('competition.player.matchesHeader') }}</h2>
        <p
          v-if="view.matches.length === 0"
          class="text-center text-sm text-slate-400 italic py-10 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl"
        >
          {{ t('competition.player.noMatchesAssigned') }}<br />{{ t('competition.player.noMatchesAssignedHint') }}
        </p>

        <div
          v-for="m in view.matches"
          :key="m.matchId"
          class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl overflow-hidden"
        >
          <!-- カードヘッダ -->
          <div class="px-4 py-3 bg-slate-50 dark:bg-slate-900/60 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between flex-wrap gap-2">
            <p class="font-bold text-sm">
              {{ t('competition.player.vs') }}
              <span v-if="m.opponent" class="text-blue-600 dark:text-blue-400">{{ m.opponent.displayName }}</span>
              <span v-else-if="!m.opponentLineupPublished" class="text-slate-400 italic">{{ t('competition.player.opponentLineupHidden') }}</span>
              <span v-else class="text-slate-400 italic">{{ t('competition.player.unassigned') }}</span>
              <span v-if="m.opponent?.teamName" class="text-xs text-slate-400 font-mono ml-1">({{ m.opponent.teamName }})</span>
            </p>
            <div class="flex items-center gap-2 flex-wrap">
              <p class="text-[10px] font-mono text-slate-400 tracking-[0.25em] uppercase">
                {{ kindLabel(m.matchKind) }} ·
                {{ LEVELS_FOR_KIND[m.matchKind].length === 1
                    ? `Lv ${LEVELS_FOR_KIND[m.matchKind][0]}`
                    : `Lv ${LEVELS_FOR_KIND[m.matchKind].join('/')}` }}
              </p>
              <span
                v-if="m.requiredGenre"
                class="text-[10px] font-black px-2 py-0.5 rounded bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300 tracking-wider"
              >
                {{ t('competition.player.requiredGenrePrefix') }} {{ m.requiredGenre }}
              </span>
              <span
                v-else
                class="text-[10px] font-black px-2 py-0.5 rounded bg-slate-200 text-slate-500 dark:bg-slate-700 dark:text-slate-400 tracking-wider"
              >
                {{ t('competition.player.genreUnspecified') }}
              </span>
            </div>
          </div>

          <!-- 自分側 / 相手側パネル -->
          <div class="px-4 py-4 grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
            <!-- 自分 -->
            <div class="bg-slate-50 dark:bg-slate-900/40 rounded-lg p-3 space-y-2">
              <p class="text-[10px] font-mono text-slate-400 uppercase tracking-wider">{{ t('competition.player.mySide') }}</p>
              <div v-if="m.myPick" class="space-y-0.5">
                <p class="font-bold truncate text-sm">{{ m.myPick.songTitle }}</p>
                <p class="text-[10px] font-mono text-slate-400">
                  {{ m.myPick.songGenre }} · Lv {{ m.myPick.songLevel }} ·
                  {{ m.myPick.songDiff === 'L' ? 'LEGGENDARIA' : 'ANOTHER' }}
                </p>
              </div>
              <p v-else-if="m.requiredGenre" class="italic text-slate-400">{{ t('competition.player.notSubmitted') }}</p>
              <p v-else class="italic text-slate-400">{{ t('competition.player.waitingGenre') }}</p>

              <!-- 編集 / 取消 ボタン -->
              <div v-if="canEditMatch(m) && editingMatchId !== m.matchId" class="flex items-center gap-2 pt-1">
                <button
                  type="button"
                  @click="startEditing(m)"
                  class="px-3 py-1 text-[11px] font-bold rounded-lg bg-blue-600 text-white hover:bg-blue-700 transition-colors"
                >{{ m.myPick ? t('competition.player.editButton') : t('competition.player.selectSongButton') }}</button>
                <button
                  v-if="m.myPick"
                  type="button"
                  @click="removePick(m)"
                  class="px-2 py-1 text-[10px] font-bold rounded bg-rose-50 text-rose-600 hover:bg-rose-100 dark:bg-rose-900/30 dark:text-rose-300"
                >{{ t('competition.player.cancelButton') }}</button>
              </div>

              <p
                class="text-[10px] mt-1 font-mono"
                :class="m.myLocked ? 'text-amber-600 dark:text-amber-300 font-bold' : 'text-slate-400'"
              >
                {{ m.myLocked ? t('competition.player.lockedAlready') : t('competition.player.lockBefore') }}
              </p>
            </div>

            <!-- 相手 -->
            <div class="bg-slate-50 dark:bg-slate-900/40 rounded-lg p-3 space-y-2">
              <p class="text-[10px] font-mono text-slate-400 uppercase tracking-wider">{{ t('competition.player.opponentSide') }}</p>
              <p v-if="m.opponentPick" class="font-bold truncate text-sm">{{ m.opponentPick.songTitle }}</p>
              <p v-else class="italic text-slate-400">
                {{ m.opponentPickPublished ? t('competition.player.notSubmitted') : t('competition.player.opponentPickHidden') }}
              </p>
              <p
                v-if="m.opponentPick"
                class="text-[10px] font-mono text-slate-400 truncate"
              >
                {{ m.opponentPick.songGenre }} · Lv {{ m.opponentPick.songLevel }} ·
                {{ m.opponentPick.songDiff === 'L' ? 'LEGGENDARIA' : 'ANOTHER' }}
              </p>
              <p
                class="text-[10px] mt-1 font-mono"
                :class="m.opponentPickPublished ? 'text-emerald-600 dark:text-emerald-300 font-bold' : 'text-slate-400'"
              >
                {{ m.opponentPickPublished ? t('competition.player.published') : m.opponentLocked ? t('competition.player.lockedNotPublished') : t('competition.player.hiddenShort') }}
              </p>
            </div>
          </div>

          <!-- 編集モード: Lv → 曲 の 2 段選択 (ジャンルは運営指定済) -->
          <div
            v-if="editingMatchId === m.matchId && m.requiredGenre"
            class="px-4 py-4 space-y-4 bg-slate-100/70 dark:bg-slate-900/40 border-t border-slate-200 dark:border-slate-700"
          >
            <div class="flex items-center justify-between flex-wrap gap-2">
              <p class="text-[10px] font-mono uppercase tracking-[0.25em] text-slate-500">
                {{ kindLabel(m.matchKind) }} - <span class="text-emerald-600 dark:text-emerald-300 font-black">{{ m.requiredGenre }}</span> {{ t('competition.player.fromGenre') }}
              </p>
              <button
                type="button"
                @click="cancelEditing"
                class="px-3 py-1 text-[10px] font-bold rounded-lg bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 transition-colors"
              >{{ t('competition.player.closeButton') }}</button>
            </div>

            <!--
              曲管理番号を直接入力するショートカット。
              ジャンル別 TXT に振られた id (1〜) を入れて Enter で即提出。
              Lv 帯は matchKind から自動。
            -->
            <div class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-lg p-3">
              <p class="text-[10px] font-mono uppercase tracking-[0.25em] text-slate-500 mb-2">
                {{ t('competition.player.directIdSection') }}
              </p>
              <div class="flex items-center gap-2">
                <input
                  v-model="directIdInput"
                  type="text"
                  inputmode="numeric"
                  pattern="[0-9]*"
                  :placeholder="t('competition.player.directIdPlaceholder')"
                  class="flex-1 px-3 py-1.5 text-sm rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400"
                  @keydown.enter.prevent="submitByDirectId"
                />
                <button
                  type="button"
                  @click="submitByDirectId"
                  class="px-4 py-1.5 rounded-lg text-xs font-bold bg-blue-600 text-white hover:bg-blue-700 disabled:bg-slate-300 dark:disabled:bg-slate-600 disabled:cursor-not-allowed"
                  :disabled="!String(directIdInput).trim()"
                >
                  {{ t('competition.player.confirmInput') }}
                </button>
              </div>
              <p class="text-[10px] font-mono text-slate-400 mt-1">
                {{ t('competition.player.directIdHint') }}
              </p>
            </div>

            <!-- Step 1: Lv (vanguard のみ複数選択肢、他は自動セット済) -->
            <div v-if="availableLevels.length > 1">
              <p class="text-[10px] font-mono uppercase tracking-[0.25em] text-slate-500 mb-2">{{ t('competition.player.step1Lv') }}</p>
              <div class="flex gap-2">
                <button
                  v-for="lv in availableLevels"
                  :key="lv"
                  type="button"
                  @click="selectLevel(lv)"
                  class="px-4 py-2 rounded-lg text-sm font-black transition-colors"
                  :class="editingLevel === lv
                    ? 'bg-blue-600 text-white shadow'
                    : 'bg-white dark:bg-slate-700 hover:bg-blue-50 dark:hover:bg-slate-600 border border-slate-200 dark:border-slate-600'"
                >Lv {{ lv }}</button>
              </div>
            </div>

            <!-- Step 2: 曲リスト -->
            <div v-if="editingLevel">
              <p class="text-[10px] font-mono uppercase tracking-[0.25em] text-slate-500 mb-2">
                {{ availableLevels.length > 1 ? 'Step 2' : 'Step 1' }}: {{ t('competition.player.stepSelectSong') }} ({{ availableSongs.length }})
              </p>
              <div class="max-h-72 overflow-y-auto border border-slate-200 dark:border-slate-600 rounded-lg divide-y divide-slate-100 dark:divide-slate-700/60 bg-white dark:bg-slate-800">
                <button
                  v-for="s in availableSongs"
                  :key="s.id"
                  type="button"
                  @click="submitSong(s)"
                  class="w-full text-left px-3 py-2 hover:bg-blue-50 dark:hover:bg-blue-900/30 transition-colors flex items-baseline gap-2"
                >
                  <span class="shrink-0 text-[10px] font-mono text-slate-400 tabular-nums w-10 text-right">#{{ s.id }}</span>
                  <span class="flex-1 min-w-0">
                    <p class="font-bold text-sm truncate">{{ s.title }}</p>
                    <p class="text-[10px] font-mono text-slate-400 mt-0.5">
                      {{ s.version }} · {{ s.diff === 'L' ? 'LEGGENDARIA' : 'ANOTHER' }} · Lv {{ s.level }}
                    </p>
                  </span>
                </button>
              </div>
            </div>
          </div>

          <!-- StrategyCard 決定 (相手の自選曲が公開済のときだけ可) -->
          <div class="px-4 py-3 border-t border-slate-200 dark:border-slate-700">
            <div v-if="m.opponentPickPublished" class="flex items-center gap-3 flex-wrap">
              <div class="flex-1 min-w-0">
                <p class="text-xs font-bold">{{ t('competition.player.strategyCard') }}</p>
                <p class="text-[10px] text-slate-400">{{ t('competition.player.strategyCardDescription') }}</p>
                <p
                  v-if="!canEnableStrategyHere(m.matchId)"
                  class="text-[10px] text-rose-500 mt-0.5"
                >
                  {{ t('competition.player.strategyLimitReached', { limit: view.strategyQuota.limit }) }}
                </p>
              </div>
              <button
                type="button"
                @click="toggleStrategy(m.matchId, m.myStrategyUse?.enabled ?? false)"
                :disabled="!canEnableStrategyHere(m.matchId) && !(m.myStrategyUse?.enabled)"
                class="px-4 py-2 text-xs font-black tracking-wider uppercase rounded-lg transition-all"
                :class="m.myStrategyUse?.enabled
                  ? 'bg-gradient-to-r from-fuchsia-500 to-amber-500 text-white shadow hover:shadow-lg'
                  : !canEnableStrategyHere(m.matchId)
                    ? 'bg-slate-300 dark:bg-slate-600 text-slate-500 cursor-not-allowed'
                    : 'bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-slate-300 dark:hover:bg-slate-600'"
              >
                {{ m.myStrategyUse?.enabled ? t('competition.player.strategyEnabled') : t('competition.player.strategyDisabled') }}
              </button>
            </div>
            <p v-else class="text-[11px] text-slate-400 italic">
              {{ t('competition.player.strategyWaitOpponent') }}
            </p>
          </div>
        </div>
      </section>
    </div>

    <!-- 自選曲提出の確認モーダル -->
    <div
      v-if="pendingSubmitSong"
      class="fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4"
      @click.self="cancelSubmitConfirm"
    >
      <div class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl max-w-md w-full p-6 space-y-4 shadow-xl">
        <p class="text-xs font-mono uppercase tracking-[0.3em] text-slate-500">{{ t('competition.player.confirmTitle') }}</p>
        <p class="text-base font-bold leading-relaxed">
          {{ t('competition.player.confirmHeading', { kind: editingMatch ? kindLabel(editingMatch.matchKind) : '' }) }}<br />
          <span class="text-rose-500 text-sm">{{ t('competition.player.confirmQuestion') }}</span>
        </p>
        <div class="rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-700 p-4">
          <p class="text-xl font-black truncate">{{ pendingSubmitSong.title }}</p>
          <p class="text-xs font-mono text-slate-500 mt-1">
            <span class="text-emerald-600 dark:text-emerald-300 font-bold">#{{ pendingSubmitSong.id }}</span>
            · {{ pendingSubmitSong.version }}
            · {{ pendingSubmitSong.diff === 'L' ? 'LEGGENDARIA' : 'ANOTHER' }}
            · Lv {{ pendingSubmitSong.level }}
          </p>
          <p v-if="editingMatch?.requiredGenre" class="text-[10px] font-mono text-slate-400 mt-1">
            {{ t('competition.player.confirmGenreLabel') }} <span class="text-emerald-600 dark:text-emerald-300">{{ editingMatch.requiredGenre }}</span>
          </p>
        </div>
        <p class="text-[11px] text-slate-500 leading-relaxed">
          {{ t('competition.player.confirmNote') }}
        </p>
        <div class="flex gap-2 justify-end">
          <button
            type="button"
            @click="cancelSubmitConfirm"
            class="px-4 py-2 rounded-xl text-sm font-bold bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200"
          >{{ t('competition.player.confirmCancel') }}</button>
          <button
            type="button"
            @click="submitSongConfirmed"
            class="px-5 py-2 rounded-xl text-sm font-black tracking-wider uppercase bg-blue-600 text-white hover:bg-blue-700 shadow-md"
          >{{ t('competition.player.confirmYes') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
