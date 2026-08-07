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
import { onMounted, watch, computed } from 'vue';
import {
  useCompetitionSpectator,
  type SpectatorMatchDto,
} from '../composables/useCompetitionSpectator';
import { useToast } from '../composables/useToast';
import { teamColorClass, genreBadgeClass } from '../composables/competitionColors';
import { KIND_LABEL_JA, kindLevelLabel } from '../composables/competitionMatchKinds';

const props = defineProps<{ token: string }>();

const { view, isLoading, fetchView } = useCompetitionSpectator();
const toast = useToast();

onMounted(() => fetchView(props.token).catch(e => toast.error((e as Error).message)));
watch(() => props.token, () => fetchView(props.token).catch(e => toast.error((e as Error).message)));

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
        </p>
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
  </div>
</template>
