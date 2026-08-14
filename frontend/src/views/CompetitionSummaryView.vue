<script setup lang="ts">
/**
 * 【View の役割】 団体戦 (team5) 大会のサマリー `/competition/summary/{competitionId}` スタンドアロン画面。
 *
 * 運営画面の「📊 サマリー」ボタンから別タブで開かれる、大会終了後の振り返り用ページ。
 * App.vue の最上位 v-else-if で他の大会系スタンドアロン View と並列にレンダリングされる。
 *
 * 表示内容 (読み取り専用・2 タブ構成):
 *  - 試合別: matchup ごとに 先鋒 / 中堅 / 大将 … の各試合を並べ、2 曲のスコアと曲ごと・試合ごとの勝敗を出す
 *  - 選手別: 参加者ごとに通算成績 (勝分敗・曲勝敗・戦pt) と、出場した全試合のスコア・勝敗を出す
 *
 * データは 1 本の API (`GET /api/competitions/{id}/summary`) で取得する。集計は
 * backend の CompetitionTeamSummaryService 側で完結しており、ここは表示だけを担う。
 * 認証は運営 (Competition ホワイトリスト) の Bearer トークン: 未ログイン / 権限なしはエラー表示になる。
 */
import { computed, onMounted, ref } from 'vue';
import {
  useCompetitionAdmin,
  type CompetitionSummaryDto,
  type CompetitionSummaryMatch,
  type CompetitionSummaryMatchup,
  type CompetitionSummaryPlayer,
  type SummaryOutcome,
  type SummaryResult,
} from '../composables/useCompetitionAdmin';
import { teamColorClass, genreBadgeClass } from '../composables/competitionColors';

const props = defineProps<{ competitionId: number }>();

const { fetchSummary } = useCompetitionAdmin();

const summary = ref<CompetitionSummaryDto | null>(null);
const isLoading = ref(false);
const errorMessage = ref<string | null>(null);

/** 表示軸。試合別 ⇄ 選手別 をタブで切り替える。 */
const activeTab = ref<'match' | 'player'>('match');

/**
 * 未記録の試合も表示するか。
 * 既定は「記録済みのみ」= サマリーとして意味のある行だけを出す。
 * 運営が記録漏れを探すときのために切り替えられるようにしている。
 */
const showUnrecorded = ref(false);

/** サマリーを取得する。権限エラー等はメッセージをそのまま出す。 */
const load = async () => {
  isLoading.value = true;
  errorMessage.value = null;
  try {
    summary.value = await fetchSummary(props.competitionId);
  } catch (e) {
    summary.value = null;
    errorMessage.value = (e as Error).message;
  } finally {
    isLoading.value = false;
  }
};

onMounted(load);

// ── 試合別 ────────────────────────────────────────────────

/**
 * 表示対象の matchup。
 * 未設定 (configured=false) の matchup は運営がまだ実施対象にしていない枠なので既定では隠す
 * (「未記録も表示」で出す)。並びは matchupOrder 昇順、決勝は末尾。
 */
const visibleMatchups = computed<CompetitionSummaryMatchup[]>(() => {
  const all = summary.value?.matchups ?? [];
  const filtered = showUnrecorded.value ? all : all.filter(mu => mu.configured && mu.matches.some(m => m.recorded));
  return [...filtered].sort((a, b) => {
    if (a.isFinals !== b.isFinals) return a.isFinals ? 1 : -1;
    return a.matchupOrder - b.matchupOrder;
  });
});

/** matchup 内で表示する試合 (既定は記録済みのみ)。 */
const visibleMatches = (mu: CompetitionSummaryMatchup): CompetitionSummaryMatch[] =>
  showUnrecorded.value ? mu.matches : mu.matches.filter(m => m.recorded);

/** 試合別の勝敗ラベル (A 側視点で ○ / × / △)。未記録は「-」。 */
const resultLabel = (r: SummaryResult | null, side: 'A' | 'B'): string => {
  if (!r) return '-';
  if (r === 'D') return '△';
  return (r === 'A') === (side === 'A') ? '○' : '×';
};

/** 勝敗に応じた文字色。勝ち=緑 / 負け=赤 / 引分=灰。 */
const resultColor = (r: SummaryResult | null, side: 'A' | 'B'): string => {
  if (!r) return 'text-slate-400';
  if (r === 'D') return 'text-slate-500 dark:text-slate-400';
  return (r === 'A') === (side === 'A')
    ? 'text-emerald-600 dark:text-emerald-300'
    : 'text-rose-500 dark:text-rose-400';
};

/** スコアの表示 (未入力は「-」)。 */
const scoreLabel = (v: number | null): string => (v === null || v === undefined ? '-' : String(v));

// ── 選手別 ────────────────────────────────────────────────

/**
 * 表示対象の選手。
 * 既定は「1 試合でも出場した選手」のみ。並びは所属チーム順 → 出場数の多い順 → 名前順。
 */
const visiblePlayers = computed<CompetitionSummaryPlayer[]>(() => {
  const all = summary.value?.players ?? [];
  const filtered = showUnrecorded.value ? all : all.filter(p => p.matchCount > 0);
  return [...filtered].sort((a, b) => {
    const byTeam = (a.teamOrder ?? 99) - (b.teamOrder ?? 99);
    if (byTeam !== 0) return byTeam;
    if (a.matchCount !== b.matchCount) return b.matchCount - a.matchCount;
    return a.displayName.localeCompare(b.displayName, 'ja');
  });
});

/** 選手別の勝敗ラベル (本人視点)。 */
const OUTCOME_LABEL: Record<SummaryOutcome, string> = { win: '○', lose: '×', draw: '△' };
const OUTCOME_COLOR: Record<SummaryOutcome, string> = {
  win: 'text-emerald-600 dark:text-emerald-300',
  lose: 'text-rose-500 dark:text-rose-400',
  draw: 'text-slate-500 dark:text-slate-400',
};

/** 「2勝1分1敗」形式の通算成績。 */
const recordLabel = (p: CompetitionSummaryPlayer): string =>
  `${p.wins}勝 ${p.draws}分 ${p.losses}敗`;

/** 選手一覧の並び用に、チーム名の見出しを差し込むかどうか (直前の選手と別チームなら true)。 */
const isTeamHead = (index: number): boolean => {
  const list = visiblePlayers.value;
  if (index === 0) return true;
  return list[index].teamId !== list[index - 1].teamId;
};

/** 大会全体で記録済みの試合数 (ヘッダの進捗表示用)。 */
const recordedMatchCount = computed(() =>
  (summary.value?.matchups ?? []).reduce(
    (sum, mu) => sum + mu.matches.filter(m => m.recorded).length, 0));

/** 大会全体の試合数 (未設定 matchup も含む総枠数)。 */
const totalMatchCount = computed(() =>
  (summary.value?.matchups ?? []).reduce((sum, mu) => sum + mu.matches.length, 0));

/** 印刷 / PDF 保存。ブラウザの印刷ダイアログをそのまま使う。 */
const handlePrint = () => window.print();
</script>

<template>
  <div class="competition-summary-view min-h-screen bg-slate-50 dark:bg-slate-900 text-slate-800 dark:text-slate-100 p-4 sm:p-8">
    <div v-if="isLoading && !summary" class="text-center py-20 text-slate-400 text-sm">読み込み中…</div>

    <div
      v-else-if="errorMessage"
      class="max-w-2xl mx-auto bg-rose-50 dark:bg-rose-900/30 border border-rose-200 dark:border-rose-700 rounded-md p-6 text-center"
    >
      <p class="text-lg font-bold text-rose-700 dark:text-rose-300">サマリーを表示できません</p>
      <p class="text-sm text-rose-600 dark:text-rose-400 mt-2">{{ errorMessage }}</p>
      <p class="text-[11px] text-rose-500 dark:text-rose-400 mt-3">
        主催アカウントでログインした状態で開いてください。
      </p>
      <button
        type="button"
        @click="load"
        class="mt-4 px-4 py-2 rounded-md text-xs font-bold bg-rose-500 hover:bg-rose-600 text-white"
      >再読込</button>
    </div>

    <div v-else-if="summary" class="max-w-6xl mx-auto space-y-6">
      <!-- ヘッダ -->
      <div class="flex items-start justify-between gap-4 flex-wrap">
        <div>
          <p class="text-[10px] font-mono text-slate-400 dark:text-slate-500">SUMMARY</p>
          <div class="flex items-baseline gap-2 mt-1 flex-wrap">
            <h1 class="text-2xl sm:text-3xl font-bold tracking-tight">{{ summary.competition.name }}</h1>
            <span class="text-[10px] font-bold px-2 py-0.5 rounded bg-indigo-100 text-indigo-700 dark:bg-indigo-900/40 dark:text-indigo-300">団体戦</span>
          </div>
          <p class="text-xs text-slate-500 dark:text-slate-400 mt-2 font-mono">
            記録済 {{ recordedMatchCount }} / {{ totalMatchCount }} 戦
          </p>
        </div>
        <div class="flex items-center gap-2 print:hidden">
          <label class="flex items-center gap-1.5 text-[11px] text-slate-500 dark:text-slate-400 cursor-pointer">
            <input type="checkbox" v-model="showUnrecorded" class="accent-indigo-500" />
            未記録も表示
          </label>
          <button
            type="button"
            @click="load"
            class="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600"
          >🔄 再読込</button>
          <button
            type="button"
            @click="handlePrint"
            class="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600"
          >🖨️ 印刷</button>
        </div>
      </div>

      <!-- タブ切替 -->
      <div class="flex gap-1 border-b border-slate-200 dark:border-slate-700 print:hidden">
        <button
          type="button"
          @click="activeTab = 'match'"
          class="px-4 py-2 text-xs font-bold rounded-t-md transition-colors"
          :class="activeTab === 'match'
            ? 'bg-white dark:bg-slate-800 text-indigo-600 dark:text-indigo-300 border border-b-0 border-slate-200 dark:border-slate-700'
            : 'text-slate-500 hover:text-slate-700 dark:hover:text-slate-300'"
        >🎯 試合別</button>
        <button
          type="button"
          @click="activeTab = 'player'"
          class="px-4 py-2 text-xs font-bold rounded-t-md transition-colors"
          :class="activeTab === 'player'
            ? 'bg-white dark:bg-slate-800 text-indigo-600 dark:text-indigo-300 border border-b-0 border-slate-200 dark:border-slate-700'
            : 'text-slate-500 hover:text-slate-700 dark:hover:text-slate-300'"
        >👤 選手別</button>
      </div>

      <!-- ══════════ 試合別 ══════════ -->
      <div v-show="activeTab === 'match'" class="space-y-5">
        <p
          v-if="visibleMatchups.length === 0"
          class="text-sm text-slate-400 text-center py-12"
        >表示できる試合がありません。</p>

        <section
          v-for="mu in visibleMatchups"
          :key="mu.matchupId"
          class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3"
        >
          <!-- matchup ヘッダ: チーム名 + 戦pt 合計 + 勝敗 -->
          <div class="flex items-center justify-between gap-3 flex-wrap">
            <h2 class="text-sm font-bold flex items-center gap-2 flex-wrap">
              <span class="text-[10px] font-mono text-slate-400">
                {{ mu.isFinals ? '決勝' : `#${mu.matchupOrder}` }}
              </span>
              <span :class="teamColorClass(mu.teamAName)">{{ mu.teamAName ?? '?' }}</span>
              <span class="text-slate-400 text-xs">vs</span>
              <span :class="teamColorClass(mu.teamBName)">{{ mu.teamBName ?? '?' }}</span>
              <span v-if="mu.isFinals" class="text-[10px] px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300 font-bold">🏆 決勝</span>
              <span v-else-if="!mu.configured" class="text-[10px] px-1.5 py-0.5 rounded bg-slate-200 text-slate-500 dark:bg-slate-700 dark:text-slate-400">未設定</span>
            </h2>
            <div class="text-xs font-mono tabular-nums flex items-center gap-2">
              <span :class="resultColor(mu.result, 'A')" class="font-bold">{{ mu.aPoints }}</span>
              <span class="text-slate-400">-</span>
              <span :class="resultColor(mu.result, 'B')" class="font-bold">{{ mu.bPoints }}</span>
              <span class="text-[10px] text-slate-400">戦pt</span>
              <span v-if="mu.recorded" class="text-[10px] text-slate-500 dark:text-slate-400">
                (試合 {{ mu.aMatchWins }}-{{ mu.matchDraws }}-{{ mu.bMatchWins }})
              </span>
              <span v-else class="text-[10px] text-slate-400">集計中</span>
            </div>
          </div>

          <!-- 試合一覧 -->
          <div class="overflow-x-auto">
            <table class="w-full text-xs min-w-[860px]">
              <thead>
                <tr class="text-[10px] font-mono text-slate-400 border-b border-slate-200 dark:border-slate-700">
                  <th class="text-left py-1 px-2">戦</th>
                  <th class="text-left py-1 px-2">ジャンル</th>
                  <th class="text-right py-1 px-2">{{ mu.teamAName ?? 'A' }}</th>
                  <th class="text-center py-1 px-2">曲</th>
                  <th class="text-left py-1 px-2">{{ mu.teamBName ?? 'B' }}</th>
                  <th class="text-center py-1 px-2">曲勝敗</th>
                  <th class="text-center py-1 px-2">戦績</th>
                  <th class="text-right py-1 px-2">戦pt</th>
                </tr>
              </thead>
              <tbody>
                <!--
                  1 試合 = (選手名の見出し行) + (2 曲ぶんのスコア行)。
                  戦種別 / ジャンル / 試合の勝敗 / 戦pt は試合ごとに 1 セルなので見出し行に rowspan で載せる。
                -->
                <template v-for="m in visibleMatches(mu)" :key="m.matchId">
                  <!-- 見出し行: 両サイドの起用選手 -->
                  <tr :class="m.recorded ? '' : 'opacity-50'">
                    <td :rowspan="m.songs.length + 1" class="py-1.5 px-2 align-middle font-bold whitespace-nowrap border-b border-slate-200 dark:border-slate-700">
                      {{ m.matchKindLabel }}
                    </td>
                    <td :rowspan="m.songs.length + 1" class="py-1.5 px-2 align-middle border-b border-slate-200 dark:border-slate-700">
                      <span
                        v-if="m.requiredGenre"
                        class="text-[10px] font-bold px-1.5 py-0.5 rounded"
                        :class="genreBadgeClass(m.requiredGenre)"
                      >{{ m.requiredGenre }}</span>
                      <span v-else class="text-slate-400">-</span>
                    </td>
                    <td class="py-1 px-2 text-right text-[10px] font-bold truncate" :class="teamColorClass(mu.teamAName)">
                      {{ m.playerAName ?? '未アサイン' }}
                    </td>
                    <td class="py-1 px-2"></td>
                    <td class="py-1 px-2 text-left text-[10px] font-bold truncate" :class="teamColorClass(mu.teamBName)">
                      {{ m.playerBName ?? '未アサイン' }}
                    </td>
                    <td class="py-1 px-2"></td>
                    <!-- 試合の勝敗 + 獲得曲数 -->
                    <td :rowspan="m.songs.length + 1" class="py-1.5 px-2 align-middle text-center whitespace-nowrap border-b border-slate-200 dark:border-slate-700">
                      <span v-if="m.recorded">
                        <span class="font-bold text-base" :class="resultColor(m.result, 'A')">{{ resultLabel(m.result, 'A') }}</span>
                        <span class="text-[10px] text-slate-500 dark:text-slate-400 ml-1 tabular-nums">
                          {{ m.aSongsWon }}-{{ m.bSongsWon }}
                        </span>
                      </span>
                      <span v-else class="text-[10px] text-slate-400">未記録</span>
                    </td>
                    <!-- 戦ポイント -->
                    <td :rowspan="m.songs.length + 1" class="py-1.5 px-2 align-middle text-right tabular-nums font-mono whitespace-nowrap border-b border-slate-200 dark:border-slate-700">
                      <span :class="resultColor(m.result, 'A')">{{ m.aPoints }}</span>
                      <span class="text-slate-400 mx-0.5">-</span>
                      <span :class="resultColor(m.result, 'B')">{{ m.bPoints }}</span>
                      <div class="text-[9px] text-slate-400">1曲 {{ m.pointsPerSong }}pt</div>
                    </td>
                  </tr>
                  <!-- スコア行: song1 (A 側の曲) → song2 (B 側の曲) -->
                  <tr
                    v-for="(song, si) in m.songs"
                    :key="`${m.matchId}-${song.index}`"
                    class="border-b"
                    :class="[
                      si === m.songs.length - 1
                        ? 'border-slate-200 dark:border-slate-700'
                        : 'border-slate-100/70 dark:border-slate-700/40',
                      m.recorded ? '' : 'opacity-50',
                    ]"
                  >
                    <td
                      class="py-1.5 px-2 text-right tabular-nums font-mono whitespace-nowrap"
                      :class="song.winner === 'A' ? 'font-bold text-emerald-600 dark:text-emerald-300' : ''"
                    >{{ scoreLabel(song.scoreA) }}</td>
                    <td class="py-1.5 px-2 text-center">
                      <div class="truncate max-w-[240px] mx-auto" :title="song.title ?? ''">
                        <span class="text-[9px] font-mono text-slate-400 mr-1">{{ song.index }}</span>
                        {{ song.title ?? '(未設定)' }}
                      </div>
                    </td>
                    <td
                      class="py-1.5 px-2 text-left tabular-nums font-mono whitespace-nowrap"
                      :class="song.winner === 'B' ? 'font-bold text-emerald-600 dark:text-emerald-300' : ''"
                    >{{ scoreLabel(song.scoreB) }}</td>
                    <td class="py-1.5 px-2 text-center font-bold" :class="resultColor(song.winner, 'A')">
                      {{ song.winner ? resultLabel(song.winner, 'A') : '-' }}
                    </td>
                  </tr>
                </template>
                <tr v-if="visibleMatches(mu).length === 0">
                  <td colspan="8" class="py-3 px-2 text-center text-slate-400 text-[11px]">記録済みの試合がありません</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>

      <!-- ══════════ 選手別 ══════════ -->
      <div v-show="activeTab === 'player'" class="space-y-5">
        <p
          v-if="visiblePlayers.length === 0"
          class="text-sm text-slate-400 text-center py-12"
        >出場記録のある選手がいません。</p>

        <template v-for="(p, pi) in visiblePlayers" :key="p.participantId">
          <!-- チームの区切り見出し -->
          <h2
            v-if="isTeamHead(pi)"
            class="text-sm font-bold pt-2"
            :class="teamColorClass(p.teamName)"
          >{{ p.teamName ?? '所属なし' }}</h2>

          <section class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3">
            <!-- 選手ヘッダ: 名前 + 通算成績 -->
            <div class="flex items-center justify-between gap-3 flex-wrap">
              <h3 class="text-sm font-bold flex items-center gap-2">
                {{ p.displayName }}
                <span v-if="p.isTl" class="text-[10px] px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300 font-bold">TL</span>
                <span class="text-[10px] font-normal" :class="teamColorClass(p.teamName)">{{ p.teamName }}</span>
              </h3>
              <div class="text-[11px] font-mono tabular-nums flex items-center gap-3 flex-wrap">
                <span class="font-bold">{{ recordLabel(p) }}</span>
                <span class="text-slate-500 dark:text-slate-400">
                  曲 {{ p.songWins }}勝 {{ p.songDraws }}分 {{ p.songLosses }}敗
                </span>
                <span class="text-slate-500 dark:text-slate-400">
                  戦pt <span class="font-bold text-emerald-600 dark:text-emerald-300">{{ p.pointsFor }}</span>
                  / 献上 <span class="text-rose-500 dark:text-rose-400">{{ p.pointsAgainst }}</span>
                </span>
              </div>
            </div>

            <!-- 出場試合一覧 -->
            <div v-if="p.matches.length > 0" class="overflow-x-auto">
              <table class="w-full text-xs min-w-[820px]">
                <thead>
                  <tr class="text-[10px] font-mono text-slate-400 border-b border-slate-200 dark:border-slate-700">
                    <th class="text-left py-1 px-2">試合</th>
                    <th class="text-left py-1 px-2">戦</th>
                    <th class="text-left py-1 px-2">対戦相手</th>
                    <th class="text-left py-1 px-2">曲</th>
                    <th class="text-right py-1 px-2">自分</th>
                    <th class="text-right py-1 px-2">相手</th>
                    <th class="text-center py-1 px-2">曲勝敗</th>
                    <th class="text-center py-1 px-2">戦績</th>
                    <th class="text-right py-1 px-2">戦pt</th>
                  </tr>
                </thead>
                <tbody>
                  <template v-for="pm in p.matches" :key="pm.matchId">
                    <tr
                      v-for="(song, si) in pm.songs"
                      :key="`${pm.matchId}-${song.index}`"
                      class="border-b"
                      :class="si === pm.songs.length - 1
                        ? 'border-slate-200 dark:border-slate-700'
                        : 'border-slate-100/70 dark:border-slate-700/40'"
                    >
                      <td v-if="si === 0" :rowspan="pm.songs.length" class="py-1.5 px-2 align-middle whitespace-nowrap text-[10px] font-mono text-slate-400">
                        {{ pm.isFinals ? '決勝' : `#${pm.matchupOrder}` }}
                      </td>
                      <td v-if="si === 0" :rowspan="pm.songs.length" class="py-1.5 px-2 align-middle whitespace-nowrap font-bold">
                        {{ pm.matchKindLabel }}
                        <div v-if="pm.requiredGenre" class="mt-0.5">
                          <span class="text-[9px] font-bold px-1 py-0.5 rounded" :class="genreBadgeClass(pm.requiredGenre)">
                            {{ pm.requiredGenre }}
                          </span>
                        </div>
                      </td>
                      <td v-if="si === 0" :rowspan="pm.songs.length" class="py-1.5 px-2 align-middle whitespace-nowrap">
                        <div class="font-bold">{{ pm.opponentName ?? '未アサイン' }}</div>
                        <div class="text-[10px]" :class="teamColorClass(pm.opponentTeamName)">{{ pm.opponentTeamName }}</div>
                      </td>
                      <td class="py-1.5 px-2">
                        <span class="truncate max-w-[200px] inline-block align-middle" :title="song.title ?? ''">
                          {{ song.title ?? '(未設定)' }}
                        </span>
                        <!--
                          自枠 = 本人の選曲枠。ストラテジー発動を受けた試合ではこの枠が抽選曲に
                          差し替わるので「自選曲」とは限らない。
                        -->
                        <span
                          v-if="song.ownPick"
                          class="ml-1 text-[9px] px-1 py-0.5 rounded bg-sky-100 text-sky-700 dark:bg-sky-900/40 dark:text-sky-300 align-middle"
                          title="本人の選曲枠 (相手のストラテジー発動時は抽選曲に差し替わる)"
                        >自枠</span>
                      </td>
                      <td
                        class="py-1.5 px-2 text-right tabular-nums font-mono"
                        :class="song.outcome === 'win' ? 'font-bold text-emerald-600 dark:text-emerald-300' : ''"
                      >{{ scoreLabel(song.ownScore) }}</td>
                      <td class="py-1.5 px-2 text-right tabular-nums font-mono text-slate-500 dark:text-slate-400">
                        {{ scoreLabel(song.opponentScore) }}
                      </td>
                      <td class="py-1.5 px-2 text-center font-bold" :class="song.outcome ? OUTCOME_COLOR[song.outcome] : 'text-slate-400'">
                        {{ song.outcome ? OUTCOME_LABEL[song.outcome] : '-' }}
                      </td>
                      <td v-if="si === 0" :rowspan="pm.songs.length" class="py-1.5 px-2 align-middle text-center whitespace-nowrap">
                        <span class="font-bold text-base" :class="OUTCOME_COLOR[pm.result]">{{ OUTCOME_LABEL[pm.result] }}</span>
                        <span class="text-[10px] text-slate-500 dark:text-slate-400 ml-1 tabular-nums">
                          {{ pm.songsWon }}-{{ pm.opponentSongsWon }}
                        </span>
                      </td>
                      <td v-if="si === 0" :rowspan="pm.songs.length" class="py-1.5 px-2 align-middle text-right tabular-nums font-mono whitespace-nowrap">
                        <span class="text-emerald-600 dark:text-emerald-300">{{ pm.points }}</span>
                        <span class="text-slate-400 mx-0.5">-</span>
                        <span class="text-rose-500 dark:text-rose-400">{{ pm.opponentPoints }}</span>
                      </td>
                    </tr>
                  </template>
                </tbody>
              </table>
            </div>
            <p v-else class="text-[11px] text-slate-400">出場記録なし</p>
          </section>
        </template>
      </div>

      <!-- 凡例 -->
      <p class="text-[10px] text-slate-400 leading-relaxed pb-8">
        ○ = 勝ち / × = 負け / △ = 引分。同スコアの曲は運営仕様により両者が取った扱いになるため、獲得曲数
        (例 2-1) の合計は 2 を超えることがあります。戦ポイントは「獲得曲数 × その戦の 1 曲あたり pt」
        (予選 先鋒2 / 中堅3 / 大将4、決勝 先鋒4 / 次鋒4 / 五将5 / 中堅5 / 三将6 / 副将6 / 大将7)。
      </p>
    </div>
  </div>
</template>

<style scoped>
/* 印刷時は 2 タブとも展開したいので、v-show の非表示を上書きせず「表示中のタブだけ」を印刷する。
   背景色はインクを食うので白地に落とす。 */
@media print {
  .competition-summary-view {
    background: #fff !important;
    color: #000 !important;
    padding: 0;
  }
}
</style>
