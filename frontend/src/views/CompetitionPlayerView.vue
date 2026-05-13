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

const props = defineProps<{ token: string }>();

const { view, isLoading, fetchView, upsertPick, deletePick, setStrategy } = useCompetitionPlayer();
const toast = useToast();

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
const KIND_LABEL: Record<MatchKind, string> = {
  vanguard: '先鋒戦',
  middle: '中堅戦',
  captain: '大将戦',
};

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

const submitSong = async (s: Song) => {
  if (!editingMatch.value || !editingMatch.value.requiredGenre) return;
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
    cancelEditing();
  } catch (e) {
    toast.error((e as Error).message);
  }
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
    toast.error(`${KIND_LABEL[kind]} の ${genre} に管理番号 ${idNum} は存在しません`);
    return;
  }
  await submitSong(found);
};

const removePick = async (m: PlayerMatchDto) => {
  if (!confirm(`${KIND_LABEL[m.matchKind]}の自選曲を取り消しますか?`)) return;
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
const statusLabel = (s: string) => ({
  draft: '編成中',
  open: '受付中',
  locked: 'ロック済',
  finished: '終了',
} as Record<string, string>)[s] ?? s;

/** 編集ボタンを出して良いか。ロック済 / ジャンル未指定 / 大会終了で false。 */
const canEditMatch = (m: PlayerMatchDto): boolean => {
  if (!view.value) return false;
  if (view.value.competition.status === 'finished') return false;
  if (m.myLocked) return false;
  if (!m.requiredGenre) return false;
  return true;
};
</script>

<template>
  <div class="competition-player-view min-h-screen bg-slate-50 dark:bg-slate-900 text-slate-800 dark:text-slate-100 p-4 sm:p-8">
    <div v-if="isLoading && !view" class="text-center py-20 text-slate-400 text-sm">読み込み中…</div>

    <div
      v-else-if="!view"
      class="max-w-2xl mx-auto bg-rose-50 dark:bg-rose-900/30 border border-rose-200 dark:border-rose-700 rounded-2xl p-6 text-center"
    >
      <p class="text-lg font-bold text-rose-700 dark:text-rose-300">招待 URL が無効です</p>
      <p class="text-sm text-rose-600 dark:text-rose-400 mt-2">URL を再確認するか、主催者にお問い合わせください。</p>
    </div>

    <div v-else class="max-w-4xl mx-auto space-y-6">
      <!-- ヘッダ -->
      <div>
        <p class="text-[10px] font-mono uppercase tracking-[0.3em] text-slate-400 dark:text-slate-500">{{ view.competition.name }}</p>
        <div class="flex items-baseline gap-2 mt-1 flex-wrap">
          <h1 class="text-2xl sm:text-3xl font-black tracking-tight">{{ view.team.teamName }}</h1>
          <span class="text-slate-400">·</span>
          <p class="text-lg sm:text-2xl font-bold">{{ view.participant.displayName }}</p>
          <span v-if="view.participant.isTl" class="text-[10px] font-black px-2 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300 tracking-wider">TL</span>
        </div>
        <p class="text-xs text-slate-500 dark:text-slate-400 mt-2 font-mono">
          状態 <span class="font-bold">{{ statusLabel(view.competition.status) }}</span>
          <span v-if="view.competition.deadlineAt"> · 締切 {{ new Date(view.competition.deadlineAt).toLocaleString() }}</span>
        </p>
      </div>

      <!-- ===== 担当試合 ===== -->
      <section class="space-y-3">
        <h2 class="text-xs font-black tracking-[0.3em] uppercase text-slate-500">担当試合</h2>
        <p
          v-if="view.matches.length === 0"
          class="text-center text-sm text-slate-400 italic py-10 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl"
        >
          担当試合はまだアサインされていません。<br />TL のラインアップ確定をお待ちください。
        </p>

        <div
          v-for="m in view.matches"
          :key="m.matchId"
          class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl overflow-hidden"
        >
          <!-- カードヘッダ -->
          <div class="px-4 py-3 bg-slate-50 dark:bg-slate-900/60 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between flex-wrap gap-2">
            <p class="font-bold text-sm">
              vs
              <span v-if="m.opponent" class="text-blue-600 dark:text-blue-400">{{ m.opponent.displayName }}</span>
              <span v-else-if="!m.opponentLineupPublished" class="text-slate-400 italic">起用未公開</span>
              <span v-else class="text-slate-400 italic">未アサイン</span>
              <span v-if="m.opponent?.teamName" class="text-xs text-slate-400 font-mono ml-1">({{ m.opponent.teamName }})</span>
            </p>
            <div class="flex items-center gap-2 flex-wrap">
              <p class="text-[10px] font-mono text-slate-400 tracking-[0.25em] uppercase">
                {{ KIND_LABEL[m.matchKind] }} ·
                {{ LEVELS_FOR_KIND[m.matchKind].length === 1
                    ? `Lv ${LEVELS_FOR_KIND[m.matchKind][0]}`
                    : `Lv ${LEVELS_FOR_KIND[m.matchKind].join('/')}` }}
              </p>
              <span
                v-if="m.requiredGenre"
                class="text-[10px] font-black px-2 py-0.5 rounded bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300 tracking-wider"
              >
                指定: {{ m.requiredGenre }}
              </span>
              <span
                v-else
                class="text-[10px] font-black px-2 py-0.5 rounded bg-slate-200 text-slate-500 dark:bg-slate-700 dark:text-slate-400 tracking-wider"
              >
                ジャンル未指定
              </span>
            </div>
          </div>

          <!-- 自分側 / 相手側パネル -->
          <div class="px-4 py-4 grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
            <!-- 自分 -->
            <div class="bg-slate-50 dark:bg-slate-900/40 rounded-lg p-3 space-y-2">
              <p class="text-[10px] font-mono text-slate-400 uppercase tracking-wider">自分</p>
              <div v-if="m.myPick" class="space-y-0.5">
                <p class="font-bold truncate text-sm">{{ m.myPick.songTitle }}</p>
                <p class="text-[10px] font-mono text-slate-400">
                  {{ m.myPick.songGenre }} · Lv {{ m.myPick.songLevel }} ·
                  {{ m.myPick.songDiff === 'L' ? 'LEGGENDARIA' : 'ANOTHER' }}
                </p>
              </div>
              <p v-else-if="m.requiredGenre" class="italic text-slate-400">未提出</p>
              <p v-else class="italic text-slate-400">運営のジャンル指定待ち</p>

              <!-- 編集 / 取消 ボタン -->
              <div v-if="canEditMatch(m) && editingMatchId !== m.matchId" class="flex items-center gap-2 pt-1">
                <button
                  type="button"
                  @click="startEditing(m)"
                  class="px-3 py-1 text-[11px] font-bold rounded-lg bg-blue-600 text-white hover:bg-blue-700 transition-colors"
                >{{ m.myPick ? '編集' : '+ 曲を選ぶ' }}</button>
                <button
                  v-if="m.myPick"
                  type="button"
                  @click="removePick(m)"
                  class="px-2 py-1 text-[10px] font-bold rounded bg-rose-50 text-rose-600 hover:bg-rose-100 dark:bg-rose-900/30 dark:text-rose-300"
                >取消</button>
              </div>

              <p
                class="text-[10px] mt-1 font-mono"
                :class="m.myLocked ? 'text-amber-600 dark:text-amber-300 font-bold' : 'text-slate-400'"
              >
                {{ m.myLocked ? '🔒 ロック済' : 'ロック前' }}
              </p>
            </div>

            <!-- 相手 -->
            <div class="bg-slate-50 dark:bg-slate-900/40 rounded-lg p-3 space-y-2">
              <p class="text-[10px] font-mono text-slate-400 uppercase tracking-wider">相手</p>
              <p v-if="m.opponentPick" class="font-bold truncate text-sm">{{ m.opponentPick.songTitle }}</p>
              <p v-else class="italic text-slate-400">
                {{ m.opponentPickPublished ? '未提出' : '選曲未公開' }}
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
                {{ m.opponentPickPublished ? '✓ 公開済' : m.opponentLocked ? 'ロック済 · 未公開' : '未公開' }}
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
                {{ KIND_LABEL[m.matchKind] }} - <span class="text-emerald-600 dark:text-emerald-300 font-black">{{ m.requiredGenre }}</span> から選曲
              </p>
              <button
                type="button"
                @click="cancelEditing"
                class="px-3 py-1 text-[10px] font-bold rounded-lg bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 transition-colors"
              >× 閉じる</button>
            </div>

            <!--
              曲管理番号を直接入力するショートカット。
              ジャンル別 TXT に振られた id (1〜) を入れて Enter で即提出。
              Lv 帯は matchKind から自動。
            -->
            <div class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-lg p-3">
              <p class="text-[10px] font-mono uppercase tracking-[0.25em] text-slate-500 mb-2">
                曲管理番号で直接指定
              </p>
              <div class="flex items-center gap-2">
                <input
                  v-model="directIdInput"
                  type="text"
                  inputmode="numeric"
                  pattern="[0-9]*"
                  placeholder="例: 23"
                  class="flex-1 px-3 py-1.5 text-sm rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400"
                  @keydown.enter.prevent="submitByDirectId"
                />
                <button
                  type="button"
                  @click="submitByDirectId"
                  class="px-4 py-1.5 rounded-lg text-xs font-bold bg-blue-600 text-white hover:bg-blue-700 disabled:bg-slate-300 dark:disabled:bg-slate-600 disabled:cursor-not-allowed"
                  :disabled="!String(directIdInput).trim()"
                >
                  確定
                </button>
              </div>
              <p class="text-[10px] font-mono text-slate-400 mt-1">
                ジャンルごとの TXT (NOTES.txt 等) の先頭列「曲管理番号」と一致。下のリストから選んでも OK。
              </p>
            </div>

            <!-- Step 1: Lv (vanguard のみ複数選択肢、他は自動セット済) -->
            <div v-if="availableLevels.length > 1">
              <p class="text-[10px] font-mono uppercase tracking-[0.25em] text-slate-500 mb-2">Step 1: Lv</p>
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
                {{ availableLevels.length > 1 ? 'Step 2' : 'Step 1' }}: 曲を選ぶ ({{ availableSongs.length }} 曲)
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
                <p class="text-xs font-bold">Strategy Card</p>
                <p class="text-[10px] text-slate-400">相手の自選曲を同じジャンル × Lv 帯でランダム化する</p>
              </div>
              <button
                type="button"
                @click="toggleStrategy(m.matchId, m.myStrategyUse?.enabled ?? false)"
                class="px-4 py-2 text-xs font-black tracking-wider uppercase rounded-lg transition-all"
                :class="m.myStrategyUse?.enabled
                  ? 'bg-gradient-to-r from-fuchsia-500 to-amber-500 text-white shadow hover:shadow-lg'
                  : 'bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-slate-300 dark:hover:bg-slate-600'"
              >
                {{ m.myStrategyUse?.enabled ? '✓ 使用する' : '使用しない' }}
              </button>
            </div>
            <p v-else class="text-[11px] text-slate-400 italic">
              Strategy Card: 相手の自選曲が公開されてから判断できます
            </p>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>
