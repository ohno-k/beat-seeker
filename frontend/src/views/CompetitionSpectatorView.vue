<script setup lang="ts">
/**
 * 【View の役割】 観戦客向け対戦表 `/competition/spectator/{token}` のスタンドアロン画面。
 *
 * App.vue の最上位 v-else-if で CompetitionTlView / CompetitionPlayerView と並列にレンダリングされる。
 * ログイン不要・サイドバーなし。URL の token がそのまま閲覧キー。
 *
 * 表示内容 (読み取り専用):
 *  - 大会名 / ステータス
 *  - 順位表 / 途中経過マトリクス (運営画面と同じ集計。操作ボタンは持たない)
 *  - 設定済み matchup ごとに TeamA vs TeamB
 *  - 各 matchup の 3 試合 (先鋒 / 中堅 / 大将): 指定ジャンル・起用 (公開済みのみ)・結果 (記録済みのみ)
 *
 * 未公開の起用や未記録の結果はサーバ側で伏せられて返ってくるため、ここでは「未公開 / 未記録」表示に倒す。
 */
import { onMounted, ref, watch, computed } from 'vue';
import {
  useCompetitionSpectator,
  type SpectatorMatchDto,
} from '../composables/useCompetitionSpectator';
import { useToast } from '../composables/useToast';
import { teamColorClass, genreBadgeClass } from '../composables/competitionColors';
import { KIND_LABEL_JA, kindLevelLabel, pointsPerSong } from '../composables/competitionMatchKinds';

const props = defineProps<{ token: string }>();

const { view, isLoading, fetchView } = useCompetitionSpectator();
const toast = useToast();

/** 最後に取得できた時刻。自動更新はしないので「いつ時点の情報か」を出す。 */
const lastUpdatedAt = ref<Date | null>(null);

/** 対戦表を取り直す。成功したら true (更新 FAB のトースト出し分けに使う)。 */
const load = async (): Promise<boolean> => {
  try {
    await fetchView(props.token);
    lastUpdatedAt.value = new Date();
    return true;
  } catch (e) {
    toast.error((e as Error).message);
    return false;
  }
};

onMounted(load);
watch(() => props.token, load);

/** 更新 FAB。多重タップは isLoading で弾く。 */
const handleRefresh = async () => {
  if (isLoading.value) return;
  if (await load()) toast.success('最新の状態に更新しました');
};

/** 最終更新の表示 (JST の時刻のみ)。未取得なら null。 */
const lastUpdatedLabel = computed<string | null>(() => {
  if (!lastUpdatedAt.value) return null;
  return lastUpdatedAt.value.toLocaleTimeString('ja-JP', { hour12: false });
});

// 戦種別のラベル / Lv 帯 (予選 3 戦 / 決勝 7 戦) は competitionMatchKinds に集約。
const KIND_LABEL = KIND_LABEL_JA;

const STATUS_LABEL: Record<string, string> = {
  draft: '編成中',
  open: '受付中',
  locked: 'ロック済',
  finished: '終了',
};
const statusLabel = (s: string) => STATUS_LABEL[s] ?? s;

/** 設定済み matchup を matchupOrder 昇順で (サーバ側で既に整列済みだが念のため)。 */
const sortedMatchups = computed(() => {
  if (!view.value) return [];
  return [...view.value.matchups].sort((a, b) => a.matchupOrder - b.matchupOrder);
});

// ── 順位表 / 途中経過マトリクス ─────────────────────────
// 集計はサーバ (CompetitionTeamStandingsService) 側で運営画面と共通。ここは表示だけ。

/**
 * row × col セルの表示内容 (運営画面の途中経過マトリクスと同じ規則)。
 *  - null: 対角 (同チーム同士)
 *  - undefined: 該当 matchup が未記録
 *  - object: row 視点の戦ポイント + 相手の戦ポイント + 勝敗マーカー
 */
interface MatrixCell {
  /** 行チームが獲得した戦ポイント (3 戦合計、勝ち点は含まない)。 */
  rowPts: number;
  /** 列チームが獲得した戦ポイント。 */
  colPts: number;
  /** 行視点の勝敗: ○=勝ち / ×=負け / △=引分。 */
  marker: '○' | '×' | '△';
}

const matrixCellOf = (rowTeamId: number, colTeamId: number): MatrixCell | null | undefined => {
  if (rowTeamId === colTeamId) return null;
  const breakdown = view.value?.standings?.matchupBreakdown ?? [];
  for (const e of breakdown) {
    const isAB = e.teamAId === rowTeamId && e.teamBId === colTeamId;
    const isBA = e.teamBId === rowTeamId && e.teamAId === colTeamId;
    if (!isAB && !isBA) continue;
    if (!e.recorded) return undefined;
    const rowPts = isAB ? e.aSongPoints : e.bSongPoints;
    const colPts = isAB ? e.bSongPoints : e.aSongPoints;
    const marker: '○' | '×' | '△' =
      rowPts > colPts ? '○' :
      rowPts < colPts ? '×' : '△';
    return { rowPts, colPts, marker };
  }
  return undefined;
};

/** マトリクスのセル色 (○=緑 / ×=赤 / △=橙)。 */
const matrixCellClass = (rowTeamId: number, colTeamId: number): string => {
  const c = matrixCellOf(rowTeamId, colTeamId);
  if (!c) return '';
  if (c.marker === '○') return 'text-emerald-600 dark:text-emerald-300';
  if (c.marker === '×') return 'text-rose-500 dark:text-rose-400';
  return 'text-amber-600 dark:text-amber-300';
};

/** standings から指定チームの勝ち点合計 (matchup 勝点) を取得 (見つからなければ 0)。 */
const teamMatchupPoints = (teamId: number): number =>
  view.value?.standings?.rows.find(r => r.teamId === teamId)?.matchupPoints ?? 0;

// ── matchup の総合結果 (先鋒〜大将の全戦合計) ────────────
/**
 * 1 matchup ぶんの総合成績 (運営画面の総合バンドと同じ規則)。
 *
 * 集計ルールは backend の {@code CompetitionTeamStandingsService} と同じ:
 * 勝ち曲数 × 戦ポイント (予選 先鋒2/中堅3/大将4) の合計が多い側が matchup 勝ち。
 * サーバは未記録の結果を伏せて返すため、ここで合算しても staged reveal は壊れない
 * (未記録の戦は加算されず、途中経過として表示される)。
 */
interface MatchupTotal {
  /** A 側 / B 側の戦ポイント合計 (matchup の勝敗はこれで決まる)。 */
  aPoints: number;
  bPoints: number;
  /** A 側 / B 側の勝ち曲数合計 (参考表示)。 */
  aSongs: number;
  bSongs: number;
  /** 結果記録済みの戦数 / この matchup の総戦数。 */
  recorded: number;
  total: number;
  /** 確定した勝敗。全戦が記録済みになるまでは null (= 途中経過)。 */
  winner: 'a' | 'b' | 'draw' | null;
}

/** matchup ID → 総合成績。 */
const matchupTotals = computed<Record<number, MatchupTotal>>(() => {
  const out: Record<number, MatchupTotal> = {};
  for (const mu of view.value?.matchups ?? []) {
    let aPoints = 0, bPoints = 0, aSongs = 0, bSongs = 0, recorded = 0;
    for (const m of mu.matches) {
      if (!m.resultRecorded) continue;
      recorded++;
      const a = m.aSongsWon ?? 0;
      const b = m.bSongsWon ?? 0;
      const pt = pointsPerSong(m.matchKind, mu.isFinals);
      aPoints += a * pt;
      bPoints += b * pt;
      aSongs += a;
      bSongs += b;
    }
    const allRecorded = mu.matches.length > 0 && recorded === mu.matches.length;
    out[mu.matchupId] = {
      aPoints, bPoints, aSongs, bSongs,
      recorded,
      total: mu.matches.length,
      winner: !allRecorded ? null : aPoints > bPoints ? 'a' : bPoints > aPoints ? 'b' : 'draw',
    };
  }
  return out;
});

/** 試合の勝者側 ('a' | 'b' | 'draw' | null)。null は結果未記録。 */
const winnerSide = (m: SpectatorMatchDto): 'a' | 'b' | 'draw' | null => {
  if (!m.resultRecorded) return null;
  const a = m.aSongsWon ?? 0;
  const b = m.bSongsWon ?? 0;
  if (a > b) return 'a';
  if (b > a) return 'b';
  return 'draw';
};
</script>

<template>
  <div class="competition-spectator-view min-h-screen bg-slate-50 dark:bg-slate-900 text-slate-800 dark:text-slate-100 p-4 sm:p-8">
    <div v-if="isLoading && !view" class="text-center py-20 text-slate-400 text-sm">読み込み中…</div>

    <div
      v-else-if="!view"
      class="max-w-2xl mx-auto bg-rose-50 dark:bg-rose-900/30 border border-rose-200 dark:border-rose-700 rounded-md p-6 text-center"
    >
      <p class="text-lg font-bold text-rose-700 dark:text-rose-300">対戦表が見つかりません</p>
      <p class="text-sm text-rose-600 dark:text-rose-400 mt-2">
        URL が間違っているか、主催により無効化された可能性があります。
      </p>
    </div>

    <div v-else class="max-w-5xl mx-auto space-y-6">
      <!-- ヘッダ -->
      <div>
        <p class="text-[10px] font-mono text-slate-400 dark:text-slate-500">SPECTATOR</p>
        <div class="flex items-baseline gap-2 mt-1 flex-wrap">
          <h1 class="text-2xl sm:text-3xl font-bold tracking-tight">{{ view.competition.name }}</h1>
          <span class="text-[10px] font-bold px-2 py-0.5 rounded bg-sky-100 text-sky-700 dark:bg-sky-900/40 dark:text-sky-300">観戦</span>
        </div>
        <p class="text-xs text-slate-500 dark:text-slate-400 mt-2 font-mono">
          ステータス <span class="font-bold">{{ statusLabel(view.competition.status) }}</span>
          <span v-if="lastUpdatedLabel" class="text-slate-400 dark:text-slate-500 ml-2">
            · 最終更新 {{ lastUpdatedLabel }}
          </span>
        </p>
        <!--
          サマリーページ (試合別 / 選手別の全結果一覧) への導線。
          ログイン不要で誰でも読め、伏せ方はこの観戦ページと同じ規則になっている。
        -->
        <a
          :href="`/competition/summary/${view.competition.id}`"
          target="_blank"
          rel="noopener"
          class="inline-block mt-3 px-3 py-1.5 rounded-md text-xs font-bold bg-indigo-50 text-indigo-600 hover:bg-indigo-100 dark:bg-indigo-900/30 dark:text-indigo-300 border border-indigo-200 dark:border-indigo-800"
        >📊 サマリー (試合別 / 選手別)</a>
      </div>

      <!-- 順位表 (運営画面と同じ集計。再計算 / 決勝生成の操作は持たない) -->
      <section
        v-if="view.standings && view.standings.rows.length > 0"
        class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3"
      >
        <h2 class="text-sm font-bold text-slate-500">
          順位表 ({{ view.standings.prelimRecordedCount }} / {{ view.standings.prelimMatchupCount }} matchup 記録済)
        </h2>
        <div class="overflow-x-auto">
          <table class="w-full text-sm min-w-[520px]">
            <thead>
              <tr class="text-[10px] font-mono text-slate-400 border-b border-slate-200 dark:border-slate-700">
                <th class="text-left py-1 px-2">順位</th>
                <th class="text-left py-1 px-2">チーム</th>
                <th class="text-right py-1 px-2">勝</th>
                <th class="text-right py-1 px-2">分</th>
                <th class="text-right py-1 px-2">負</th>
                <th class="text-right py-1 px-2">戦pt</th>
                <th class="text-right py-1 px-2 font-bold text-slate-700 dark:text-slate-200">勝点</th>
                <th class="text-right py-1 px-2">ストラテジー</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in view.standings.rows"
                :key="row.teamId"
                class="border-b border-slate-100 dark:border-slate-700/60"
                :class="row.rank <= 2 ? 'bg-amber-50/40 dark:bg-amber-900/10 font-bold' : ''"
              >
                <td class="py-1.5 px-2 tabular-nums">
                  <span v-if="row.rank === 1">🥇</span>
                  <span v-else-if="row.rank === 2">🥈</span>
                  <span v-else>{{ row.rank }}</span>
                </td>
                <td class="py-1.5 px-2 truncate font-bold" :class="teamColorClass(row.teamName)">{{ row.teamName }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums text-emerald-600 dark:text-emerald-300">{{ row.wins }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums text-slate-500">{{ row.draws }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums text-rose-500 dark:text-rose-400">{{ row.losses }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums">{{ row.songPoints }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums font-bold">{{ row.matchupPoints }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums">
                  <span
                    :class="row.strategyUsedMatchupCount >= row.strategyLimit
                      ? 'text-rose-500 dark:text-rose-400 font-bold'
                      : 'text-slate-500 dark:text-slate-400'"
                  >⚡ {{ row.strategyUsedMatchupCount }} / {{ row.strategyLimit }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="text-[11px] text-slate-500 pt-2 border-t border-slate-100 dark:border-slate-700/40">
          <span v-if="view.standings.finalsExists" class="text-amber-600 dark:text-amber-300 font-bold">🏆 決勝生成済</span>
          <span v-else-if="view.standings.allPrelimRecorded">予選全結果記録済</span>
          <span v-else>予選 {{ view.standings.prelimMatchupCount - view.standings.prelimRecordedCount }} 試合の結果記録待ち</span>
        </p>
      </section>

      <!-- 途中経過マトリクス: 5×5 で各 matchup の row 視点の戦ポイントを表示 -->
      <section
        v-if="view.standings && view.teams.length > 0"
        class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3"
      >
        <h2 class="text-sm font-bold text-slate-500">途中経過</h2>
        <p class="text-[11px] text-slate-500">
          セル「自軍戦pt ○/×/△ 相手戦pt」: ○=行チームが勝ち / ×=負け / △=引分。「?」 = 未記録、「-」 = 同チーム同士。合計列は勝ち点合計 (matchup 勝点のみ)。
        </p>
        <div class="overflow-x-auto">
          <table class="text-xs border-collapse">
            <thead>
              <tr>
                <th class="py-1 px-2 text-[10px] font-mono text-slate-400"></th>
                <th
                  v-for="colTeam in view.teams"
                  :key="colTeam.id"
                  class="py-2 px-3 text-[10px] font-mono border-b border-slate-200 dark:border-slate-700 text-center min-w-[90px]"
                  :class="teamColorClass(colTeam.teamName)"
                >
                  {{ colTeam.teamName }}
                </th>
                <th class="py-2 px-3 text-[10px] font-mono text-slate-700 dark:text-slate-200 font-bold border-b border-slate-200 dark:border-slate-700 text-center min-w-[80px]">
                  合計
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="rowTeam in view.teams"
                :key="rowTeam.id"
                class="border-b border-slate-100 dark:border-slate-700/60"
              >
                <th class="py-2 px-3 text-left text-xs font-bold whitespace-nowrap" :class="teamColorClass(rowTeam.teamName)">
                  {{ rowTeam.teamName }}
                </th>
                <td
                  v-for="colTeam in view.teams"
                  :key="colTeam.id"
                  class="py-2 px-3 text-center tabular-nums"
                  :class="rowTeam.id === colTeam.id ? 'bg-slate-100 dark:bg-slate-900/40' : ''"
                >
                  <template v-if="rowTeam.id === colTeam.id">
                    <span class="text-slate-400">-</span>
                  </template>
                  <template v-else-if="matrixCellOf(rowTeam.id, colTeam.id) === undefined">
                    <span class="text-slate-400">?</span>
                  </template>
                  <template v-else>
                    <span class="font-bold whitespace-nowrap" :class="matrixCellClass(rowTeam.id, colTeam.id)">
                      {{ matrixCellOf(rowTeam.id, colTeam.id)?.rowPts }}<span class="mx-0.5">{{ matrixCellOf(rowTeam.id, colTeam.id)?.marker }}</span>{{ matrixCellOf(rowTeam.id, colTeam.id)?.colPts }}
                    </span>
                  </template>
                </td>
                <td class="py-2 px-3 text-center tabular-nums font-bold text-base bg-slate-50 dark:bg-slate-900/30">
                  {{ teamMatchupPoints(rowTeam.id) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- 対戦表 -->
      <section class="space-y-4">
        <p class="text-xs font-bold text-slate-500">
          対戦表 ({{ sortedMatchups.length }} 組)
        </p>
        <p
          v-if="sortedMatchups.length === 0"
          class="text-center text-sm text-slate-400 italic py-6 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md"
        >
          まだ公開された対戦はありません。<br />主催が対戦を設定すると表示されます。
        </p>

        <div
          v-for="mu in sortedMatchups"
          :key="mu.matchupId"
          class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md overflow-hidden"
        >
          <!--
            総合 (先鋒〜大将の全戦合計)。戦ポイント = 勝ち曲数 × 戦の配点 (予選 先鋒2/中堅3/大将4)
            で、多い側がこの matchup の勝ち。運営画面の同じバンドと表示を揃えている。
          -->
          <div class="px-4 py-2 flex items-center gap-x-3 gap-y-1 flex-wrap bg-slate-100 dark:bg-slate-900">
            <span class="text-[10px] font-bold text-slate-400 shrink-0">
              総合 ({{ matchupTotals[mu.matchupId]?.total ?? 0 }} 戦合計)
            </span>
            <template v-if="(matchupTotals[mu.matchupId]?.recorded ?? 0) > 0">
              <span class="font-mono font-bold text-lg tabular-nums leading-none">
                <span :class="matchupTotals[mu.matchupId].aPoints >= matchupTotals[mu.matchupId].bPoints
                  ? teamColorClass(mu.teamA?.teamName) : 'text-slate-400'">{{ matchupTotals[mu.matchupId].aPoints }}</span>
                <span class="text-slate-400 mx-1">-</span>
                <span :class="matchupTotals[mu.matchupId].bPoints >= matchupTotals[mu.matchupId].aPoints
                  ? teamColorClass(mu.teamB?.teamName) : 'text-slate-400'">{{ matchupTotals[mu.matchupId].bPoints }}</span>
                <span class="text-[10px] font-normal text-slate-400 ml-1">pt</span>
              </span>
              <!-- 勝敗は全戦記録済みで確定。途中は「途中経過」バッジに留める。 -->
              <span
                v-if="matchupTotals[mu.matchupId].winner === 'a' || matchupTotals[mu.matchupId].winner === 'b'"
                class="text-[11px] font-bold px-2 py-0.5 rounded bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300"
              >○ {{ (matchupTotals[mu.matchupId].winner === 'a' ? mu.teamA?.teamName : mu.teamB?.teamName) ?? '?' }} 勝ち</span>
              <span
                v-else-if="matchupTotals[mu.matchupId].winner === 'draw'"
                class="text-[11px] font-bold px-2 py-0.5 rounded bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300"
              >△ 引分</span>
              <span
                v-else
                class="text-[11px] font-bold px-2 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300"
              >途中経過</span>
              <span class="text-[10px] font-mono text-slate-400">
                曲数 {{ matchupTotals[mu.matchupId].aSongs }} - {{ matchupTotals[mu.matchupId].bSongs }}
                ・ {{ matchupTotals[mu.matchupId].recorded }}/{{ matchupTotals[mu.matchupId].total }} 戦記録済み
              </span>
            </template>
            <span v-else class="text-[11px] text-slate-400 italic">
              未記録 (各戦の結果が記録されると合計が出ます)
            </span>
          </div>

          <!-- matchup ヘッダ -->
          <div class="px-4 py-3 bg-slate-50 dark:bg-slate-900/60 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between flex-wrap gap-2">
            <p class="font-bold text-sm flex items-center gap-2 flex-wrap">
              <span :class="teamColorClass(mu.teamA?.teamName)">{{ mu.teamA?.teamName ?? '?' }}</span>
              <span class="text-slate-400 font-mono text-xs">vs</span>
              <span :class="teamColorClass(mu.teamB?.teamName)">{{ mu.teamB?.teamName ?? '?' }}</span>
            </p>
            <p class="text-[10px] font-mono text-slate-400">
              {{ mu.isFinals ? 'FINALS' : '予選第 ' + mu.matchupOrder + ' 試合' }}
            </p>
          </div>

          <ul class="divide-y divide-slate-100 dark:divide-slate-700/60">
            <li
              v-for="match in mu.matches"
              :key="match.matchId"
              class="px-4 py-3 grid grid-cols-1 sm:grid-cols-[150px_1fr_1fr] gap-3 items-center"
            >
              <!-- 戦種別 + 指定ジャンル -->
              <div>
                <p class="font-bold text-sm">{{ KIND_LABEL[match.matchKind] }}</p>
                <p class="text-[10px] font-mono text-slate-400">{{ kindLevelLabel(match.matchKind) }}</p>
                <span
                  v-if="match.requiredGenre"
                  class="inline-block mt-1 text-[9px] font-bold px-1.5 py-0.5 rounded"
                  :class="genreBadgeClass(match.requiredGenre)"
                >
                  指定 {{ match.requiredGenre }}
                </span>
                <span
                  v-else
                  class="inline-block mt-1 text-[9px] font-bold px-1.5 py-0.5 rounded bg-slate-200 text-slate-500 dark:bg-slate-700 dark:text-slate-400"
                >
                  ジャンル未指定
                </span>
              </div>

              <!-- A 側 -->
              <div>
                <p class="text-[10px] font-mono mb-1 font-bold" :class="teamColorClass(mu.teamA?.teamName)">
                  {{ mu.teamA?.teamName ?? 'A 側' }}
                </p>
                <p
                  class="px-3 py-1.5 rounded-lg text-sm border truncate"
                  :class="winnerSide(match) === 'a'
                    ? 'bg-emerald-50 dark:bg-emerald-900/30 border-emerald-300 dark:border-emerald-600 font-bold'
                    : 'bg-slate-50 dark:bg-slate-900/40 border-slate-200 dark:border-slate-700'"
                >
                  <span v-if="match.playerAName">{{ match.playerAName }}</span>
                  <span v-else class="text-slate-400 italic">未公開</span>
                  <span
                    v-if="match.resultRecorded"
                    class="ml-1 text-xs font-mono tabular-nums text-slate-500"
                  >({{ match.aSongsWon ?? 0 }})</span>
                </p>
              </div>

              <!-- B 側 -->
              <div>
                <p class="text-[10px] font-mono mb-1 font-bold" :class="teamColorClass(mu.teamB?.teamName)">
                  {{ mu.teamB?.teamName ?? 'B 側' }}
                </p>
                <p
                  class="px-3 py-1.5 rounded-lg text-sm border truncate"
                  :class="winnerSide(match) === 'b'
                    ? 'bg-emerald-50 dark:bg-emerald-900/30 border-emerald-300 dark:border-emerald-600 font-bold'
                    : 'bg-slate-50 dark:bg-slate-900/40 border-slate-200 dark:border-slate-700'"
                >
                  <span v-if="match.playerBName">{{ match.playerBName }}</span>
                  <span v-else class="text-slate-400 italic">未公開</span>
                  <span
                    v-if="match.resultRecorded"
                    class="ml-1 text-xs font-mono tabular-nums text-slate-500"
                  >({{ match.bSongsWon ?? 0 }})</span>
                </p>
              </div>

              <!-- 結果詳細 (記録済みのみ): 2 曲のタイトル + EXSCORE -->
              <div
                v-if="match.resultRecorded"
                class="sm:col-span-3 mt-1 grid grid-cols-1 sm:grid-cols-2 gap-2"
              >
                <div
                  v-for="(song, i) in [
                    { title: match.song1Title, a: match.song1ScoreA, b: match.song1ScoreB },
                    { title: match.song2Title, a: match.song2ScoreA, b: match.song2ScoreB },
                  ]"
                  :key="i"
                  class="px-3 py-1.5 rounded-lg bg-slate-50 dark:bg-slate-900/40 border border-slate-100 dark:border-slate-700/60"
                >
                  <p class="text-[11px] font-bold truncate">
                    {{ i + 1 }}曲目: <span class="font-normal">{{ song.title || '—' }}</span>
                  </p>
                  <p class="text-[10px] font-mono text-slate-500 tabular-nums mt-0.5">
                    <span :class="(song.a ?? 0) > (song.b ?? 0) ? 'text-emerald-600 dark:text-emerald-300 font-bold' : ''">
                      {{ song.a ?? '—' }}
                    </span>
                    <span class="mx-1 text-slate-300">vs</span>
                    <span :class="(song.b ?? 0) > (song.a ?? 0) ? 'text-emerald-600 dark:text-emerald-300 font-bold' : ''">
                      {{ song.b ?? '—' }}
                    </span>
                  </p>
                </div>
              </div>
            </li>
          </ul>
        </div>
      </section>

      <p class="text-center text-[10px] text-slate-400 dark:text-slate-500 font-mono pt-4">
        beat-seeker · 観戦用対戦表 (読み取り専用)
      </p>
    </div>

    <!--
      更新 FAB: 自動更新はしないので手動で取り直す。スマホ観戦前提で右下に固定追従させ、
      iOS のホームバーに被らないよう safe-area ぶん底を空ける。読み込み失敗時 (view なし) も
      再試行できるよう v-if の外に置く。
    -->
    <button
      type="button"
      aria-label="対戦表を更新"
      :disabled="isLoading"
      @click="handleRefresh"
      class="fixed z-40 right-4 bottom-[calc(1rem_+_env(safe-area-inset-bottom))] flex items-center gap-1.5 pl-4 pr-5 py-3 rounded-full shadow-lg bg-blue-600 hover:bg-blue-700 active:scale-95 disabled:opacity-60 text-white text-sm font-bold transition-all"
    >
      <svg
        aria-hidden="true"
        class="h-5 w-5"
        :class="isLoading ? 'animate-spin' : ''"
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
      >
        <path
          stroke-linecap="round"
          stroke-linejoin="round"
          stroke-width="2"
          d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
        />
      </svg>
      {{ isLoading ? '更新中' : '更新' }}
    </button>
  </div>
</template>
