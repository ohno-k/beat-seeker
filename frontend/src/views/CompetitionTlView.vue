<script setup lang="ts">
/**
 * 【View の役割】 TL (チームリーダー) 専用 URL `/competition/tl/{token}` のスタンドアロン画面。
 *
 * App.vue の最上位 v-else-if で StrategyCardView / CompetitionPlayerView と並列にレンダリングされる。
 * ログイン不要・サイドバーなし。URL の token がそのまま認証材料。
 *
 * 機能:
 *  - 自チーム情報の表示 (チーム名・大会名・メンバー 4 人一覧)
 *  - 4 matchup ぶんの一覧表示 (相手チーム別)
 *  - 各 matchup 内の 3 試合 (先鋒/中堅/大将) に対する自チームメンバーのアサイン UI
 *  - 同一 matchup 内で同じメンバー二重アサインを試みるとサーバ側でブロック (エラー表示)
 *  - 自チーム側がロックされた slot は読み取り専用
 */
import { ref, onMounted, watch, computed } from 'vue';
import { useCompetitionTl, type MatchKind, type TlMatchDto, type TlMatchupDto } from '../composables/useCompetitionTl';
import { useToast } from '../composables/useToast';
import { useI18n } from '../composables/useI18n';
import { teamColorClass, genreBadgeClass } from '../composables/competitionColors';
import CompetitionChatWidget from '../components/CompetitionChatWidget.vue';

const props = defineProps<{ token: string }>();

const { view, isLoading, fetchView, assign, unassign, setStrategy } = useCompetitionTl();
const toast = useToast();
const { t, currentLang, setLanguage, availableLanguages } = useI18n();

onMounted(() => fetchView(props.token).catch(e => toast.error((e as Error).message)));
watch(() => props.token, () => fetchView(props.token).catch(e => toast.error((e as Error).message)));

/**
 * StrategyCard の意思決定 (発動する / 発動しない) を記録する。
 * 「発動しない」も明示的な決定として保存され、未決定と区別される。サーバ反映後に再 fetch で同期。
 */
const handleSetStrategy = async (match: TlMatchDto, enabled: boolean) => {
  try {
    await setStrategy(props.token, match.matchId, enabled);
    toast.success(enabled ? t('competition.tl.strategyToastUse') : t('competition.tl.strategyToastSkip'));
  } catch (e) {
    toast.error((e as Error).message);
  }
};

/** 戦種別ラベルは i18n 経由。 */
const kindLabel = (kind: MatchKind) => t(`competition.matchKind.${kind}`);
const KIND_LV: Record<MatchKind, string> = {
  vanguard: 'Lv 8-10',
  middle: 'Lv 11',
  captain: 'Lv 12',
};

const statusLabel = (s: string) => {
  if (['draft', 'open', 'locked', 'finished'].includes(s)) {
    return t(`competition.status.${s}`);
  }
  return s;
};

/**
 * セレクタの change イベント。値は数値 (participantId) または空文字 ('') = 未割当。
 * 楽観更新せずサーバ反映後に再 fetch で同期する。
 */
const handleAssign = async (match: TlMatchDto, raw: string) => {
  const id = raw === '' ? null : Number(raw);
  if (id !== null && Number.isNaN(id)) return;
  try {
    if (id === null) await unassign(props.token, match.matchId);
    else await assign(props.token, match.matchId, id);
    toast.success(id === null ? '未割当に戻しました' : 'アサインしました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

/**
 * matchup 1 件の中で、現在その matchup の他 slot に割り当て済みの participantId 集合。
 * <option> を disabled 表示してダブルアサインを UI 側でも誘導しないようにする
 * (サーバ側の検証は別途あるので二重防御)。
 */
const usedInMatchup = (matchup: TlMatchupDto, exceptMatchId: number): Set<number> => {
  const set = new Set<number>();
  for (const m of matchup.matches) {
    if (m.matchId === exceptMatchId) continue;
    if (m.myAssigned?.participantId) set.add(m.myAssigned.participantId);
  }
  return set;
};

/**
 * 当該プレイヤーをこの戦に新規アサインしようとしたとき、コスト不足になるか。
 * 既にこの slot にアサイン済みなら自分自身の分は除外して判定する。
 * 決勝 matchup ではコスト無制限のため常に false。
 */
const wouldExceedCostFor = (
  participantId: number,
  match: TlMatchDto,
): boolean => {
  if (!view.value) return false;
  const member = view.value.members.find(mm => mm.id === participantId);
  if (!member) return false;
  const costForThisKind = view.value.costPerKind[match.matchKind] ?? 0;
  // この slot に既に同じプレイヤーがアサイン済みなら、その分は member.spentCost に含まれているので二重カウント回避
  const alreadyAssignedHere = match.myAssigned?.participantId === participantId;
  const spentExceptThis = alreadyAssignedHere
    ? member.spentCost - costForThisKind
    : member.spentCost;
  return spentExceptThis + costForThisKind > view.value.initialCost;
};

/** 4 matchup の表示順 (matchupOrder の昇順を保証)。 */
const sortedMatchups = computed<TlMatchupDto[]>(() => {
  if (!view.value) return [];
  return [...view.value.matchups].sort((a, b) => a.matchupOrder - b.matchupOrder);
});

/** いま決定できるのに TL がまだどちらのボタンも押していない試合数 (決定漏れの確認用)。 */
const undecidedStrategyCount = computed<number>(() =>
  sortedMatchups.value
    .flatMap(mu => mu.matches)
    .filter(m => m.strategyDecidable && !m.myStrategyDecided).length,
);
</script>

<template>
  <div class="competition-tl-view min-h-screen bg-slate-50 dark:bg-slate-900 text-slate-800 dark:text-slate-100 p-4 sm:p-8 relative">
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
      <p class="text-lg font-bold text-rose-700 dark:text-rose-300">{{ t('competition.tl.invalidToken') }}</p>
      <p class="text-sm text-rose-600 dark:text-rose-400 mt-2">{{ t('competition.tl.invalidTokenHint') }}</p>
    </div>

    <div v-else class="max-w-5xl mx-auto space-y-6">
      <!-- ヘッダ: 大会名・自チーム名・メンバー -->
      <div>
        <p class="text-[10px] font-mono uppercase tracking-[0.3em] text-slate-400 dark:text-slate-500">{{ view.competition.name }}</p>
        <div class="flex items-baseline gap-2 mt-1 flex-wrap">
          <h1 class="text-2xl sm:text-3xl font-black tracking-tight" :class="teamColorClass(view.team.teamName)">{{ view.team.teamName }}</h1>
          <span class="text-[10px] font-black px-2 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300 tracking-wider">{{ t('competition.common.tlAdminBadge') }}</span>
        </div>
        <p class="text-xs text-slate-500 dark:text-slate-400 mt-2 font-mono">
          {{ t('competition.common.status') }} <span class="font-bold">{{ statusLabel(view.competition.status) }}</span>
          <span v-if="view.competition.deadlineAt"> · {{ t('competition.common.deadline') }} {{ new Date(view.competition.deadlineAt).toLocaleString() }}</span>
        </p>
      </div>

      <!-- メンバー一覧 + 予選コスト残量 -->
      <section class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl p-4">
        <div class="flex items-center justify-between flex-wrap gap-2 mb-3">
          <p class="text-xs font-black tracking-[0.3em] uppercase text-slate-500">{{ t('competition.tl.membersHeader') }}</p>
          <div class="flex items-center gap-3 flex-wrap">
            <p class="text-[10px] font-mono text-slate-400">
              {{ t('competition.tl.costInitial', { n: view.initialCost }) }} /
              {{ t('competition.matchKind.vanguard') }} {{ view.costPerKind.vanguard }} ·
              {{ t('competition.matchKind.middle') }} {{ view.costPerKind.middle }} ·
              {{ t('competition.matchKind.captain') }} {{ view.costPerKind.captain }}
            </p>
            <span class="text-[10px] font-black px-2 py-0.5 rounded bg-fuchsia-100 text-fuchsia-700 dark:bg-fuchsia-900/40 dark:text-fuchsia-300 tracking-wider">
              ⚡ ストラテジー {{ view.strategyUsedMatchupCount }} / {{ view.strategyLimit }} 組
            </span>
            <!-- 決定できる状態なのに未決定の試合が残っていれば警告表示 (意思決定漏れの防止) -->
            <span
              v-if="undecidedStrategyCount > 0"
              class="text-[10px] font-black px-2 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300 tracking-wider"
            >{{ t('competition.tl.strategyUndecidedCount', { n: undecidedStrategyCount }) }}</span>
          </div>
        </div>
        <div class="grid grid-cols-2 sm:grid-cols-4 gap-2">
          <div
            v-for="m in view.members"
            :key="m.id"
            class="px-3 py-2 rounded-lg bg-slate-50 dark:bg-slate-900/40 border border-slate-200 dark:border-slate-700"
          >
            <p class="font-bold text-sm truncate">
              {{ m.displayName }}
              <span v-if="m.isTl" class="ml-1 text-[9px] font-black px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300 tracking-wider align-middle">{{ t('competition.common.tlBadge') }}</span>
            </p>
            <div class="mt-1 flex items-center gap-2 text-[10px] font-mono">
              <span class="text-slate-400">{{ t('competition.tl.costRemaining') }}</span>
              <span
                class="font-bold tabular-nums"
                :class="m.remainingCost <= 0
                  ? 'text-rose-600 dark:text-rose-300'
                  : m.remainingCost < view.costPerKind.captain
                    ? 'text-amber-600 dark:text-amber-300'
                    : 'text-emerald-600 dark:text-emerald-300'"
              >{{ m.remainingCost }}</span>
              <span class="text-slate-500">/ {{ view.initialCost }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- 4 matchup ぶんのアサイン UI -->
      <section class="space-y-4">
        <p class="text-xs font-black tracking-[0.3em] uppercase text-slate-500">{{ t('competition.tl.matchupsHeader', { n: sortedMatchups.length }) }}</p>
        <p v-if="sortedMatchups.length === 0" class="text-center text-sm text-slate-400 italic py-6 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl">
          {{ t('competition.tl.noMatchups') }}<br />{{ t('competition.tl.noMatchupsHint') }}
        </p>

        <div
          v-for="mu in sortedMatchups"
          :key="mu.matchupId"
          class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl overflow-hidden"
        >
          <div class="px-4 py-3 bg-slate-50 dark:bg-slate-900/60 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between flex-wrap gap-2">
            <p class="font-bold text-sm">
              {{ t('competition.player.vs') }} <span :class="teamColorClass(mu.opponentTeam.teamName)">{{ mu.opponentTeam.teamName ?? '?' }}</span>
            </p>
            <div class="flex items-center gap-2 flex-wrap">
              <span
                v-if="mu.myLineupPublished"
                class="text-[9px] font-black px-1.5 py-0.5 rounded bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300 tracking-wider"
              >{{ t('competition.tl.mySidePublished') }}</span>
              <span
                v-else
                class="text-[9px] font-black px-1.5 py-0.5 rounded bg-slate-200 text-slate-500 dark:bg-slate-700 dark:text-slate-400 tracking-wider"
              >{{ t('competition.tl.mySideUnpublished') }}</span>
              <p class="text-[10px] font-mono text-slate-400 tracking-wider uppercase">
                {{ t('competition.tl.matchupOrder', { n: mu.matchupOrder }) }} · {{ t('competition.tl.mySideLabel', { side: mu.mySide.toUpperCase() }) }}
              </p>
            </div>
          </div>

          <ul class="divide-y divide-slate-100 dark:divide-slate-700/60">
            <li v-for="match in mu.matches" :key="match.matchId" class="px-4 py-3 grid grid-cols-1 sm:grid-cols-[140px_1fr_1fr] gap-3 items-center">
              <!-- 戦表記 + 運営指定ジャンルバッジ -->
              <div>
                <p class="font-bold text-sm">{{ kindLabel(match.matchKind) }}</p>
                <p class="text-[10px] font-mono text-slate-400">{{ KIND_LV[match.matchKind] }}</p>
                <span
                  v-if="match.requiredGenre"
                  class="inline-block mt-1 text-[9px] font-black px-1.5 py-0.5 rounded tracking-wider"
                  :class="genreBadgeClass(match.requiredGenre)"
                >
                  {{ t('competition.tl.requiredGenrePrefix') }} {{ match.requiredGenre }}
                </span>
                <span
                  v-else
                  class="inline-block mt-1 text-[9px] font-black px-1.5 py-0.5 rounded bg-slate-200 text-slate-500 dark:bg-slate-700 dark:text-slate-400 tracking-wider"
                >
                  {{ t('competition.tl.genreUnspecified') }}
                </span>
              </div>

              <!-- 自軍 slot: select で 4 メンバーから選ぶ -->
              <div>
                <p class="text-[10px] font-mono text-slate-400 uppercase tracking-wider mb-1">{{ t('competition.tl.myArmy') }}</p>
                <div class="flex items-center gap-2">
                  <select
                    :value="match.myAssigned?.participantId ?? ''"
                    :disabled="match.myLocked"
                    @change="handleAssign(match, ($event.target as HTMLSelectElement).value)"
                    class="flex-1 px-3 py-1.5 rounded-lg text-sm bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    <option value="">未割当</option>
                    <option
                      v-for="m in view.members"
                      :key="m.id"
                      :value="m.id"
                      :disabled="usedInMatchup(mu, match.matchId).has(m.id) || wouldExceedCostFor(m.id, match)"
                    >
                      {{ m.displayName }}{{ m.isTl ? ' (TL)' : '' }}
                      (残{{ m.remainingCost }})
                      <template v-if="usedInMatchup(mu, match.matchId).has(m.id)"> ※他試合で起用中</template>
                      <template v-else-if="wouldExceedCostFor(m.id, match)"> ※コスト不足</template>
                    </option>
                  </select>
                </div>
                <div class="mt-1 flex items-center gap-2 text-[10px] font-mono">
                  <span v-if="match.myAssigned" class="text-slate-400">
                    {{ t('competition.tl.pickLabel') }}
                    <span :class="match.myAssigned.pickSubmitted ? 'text-emerald-500 font-bold' : 'text-rose-500 font-bold'">
                      {{ match.myAssigned.pickSubmitted ? t('competition.tl.pickSubmitted') : t('competition.tl.pickNotSubmitted') }}
                    </span>
                  </span>
                  <span
                    class="ml-auto"
                    :class="match.myLocked ? 'text-amber-600 dark:text-amber-300 font-bold' : 'text-slate-400'"
                  >
                    {{ match.myLocked ? t('competition.tl.lockedAlready') : t('competition.tl.lockBefore') }}
                  </span>
                </div>

                <!--
                  StrategyCard の意思決定 (起用ロック&相手のオーダー公開後に TL が決定)。
                  「発動する」「発動しない」の 2 ボタンで明示的に選ばせ、どちらも押していない状態を
                  「未決定」として区別表示する (意思決定が済んだことを確認できるようにするため)。
                -->
                <div class="mt-1.5 pt-1.5 border-t border-slate-100 dark:border-slate-700/40">
                  <template v-if="match.strategyDecidable">
                    <div class="flex items-center justify-between gap-2">
                      <span class="text-[10px] font-mono text-slate-400 uppercase tracking-wider">{{ t('competition.tl.strategyLabel') }}</span>
                      <div class="flex items-center gap-1">
                        <button
                          type="button"
                          @click="handleSetStrategy(match, true)"
                          class="px-2.5 py-1 rounded-lg text-[10px] font-black tracking-wider uppercase transition-all"
                          :class="match.myStrategyDecided && match.myStrategyEnabled
                            ? 'bg-gradient-to-r from-fuchsia-500 to-amber-500 text-white shadow'
                            : 'bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-fuchsia-100 hover:text-fuchsia-700 dark:hover:bg-fuchsia-900/40 dark:hover:text-fuchsia-300'"
                        >{{ t('competition.tl.strategyUse') }}</button>
                        <button
                          type="button"
                          @click="handleSetStrategy(match, false)"
                          class="px-2.5 py-1 rounded-lg text-[10px] font-black tracking-wider uppercase transition-all"
                          :class="match.myStrategyDecided && !match.myStrategyEnabled
                            ? 'bg-slate-600 text-white shadow dark:bg-slate-500'
                            : 'bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-slate-300 dark:hover:bg-slate-600'"
                        >{{ t('competition.tl.strategySkip') }}</button>
                      </div>
                    </div>
                    <p class="mt-1 text-[10px] font-mono text-right">
                      <span v-if="!match.myStrategyDecided" class="text-amber-600 dark:text-amber-300 font-black">{{ t('competition.tl.strategyUndecided') }}</span>
                      <span v-else-if="match.myStrategyEnabled" class="text-fuchsia-500 dark:text-fuchsia-300 font-black">{{ t('competition.tl.strategyDecidedUse') }}</span>
                      <span v-else class="text-slate-500 dark:text-slate-400 font-bold">{{ t('competition.tl.strategyDecidedSkip') }}</span>
                    </p>
                  </template>
                  <p v-else class="text-[10px] font-mono">
                    <span v-if="match.myStrategyDecided && match.myStrategyEnabled" class="text-fuchsia-500 dark:text-fuchsia-300 font-black">{{ t('competition.tl.strategyDecidedUse') }}</span>
                    <span v-else-if="match.myStrategyDecided" class="text-slate-500 dark:text-slate-400 font-bold">{{ t('competition.tl.strategyDecidedSkip') }}</span>
                    <span v-else class="text-slate-400 italic">{{ t('competition.tl.strategyNotYet') }}</span>
                  </p>
                </div>
              </div>

              <!-- 相手軍 slot: ラインアップ公開済のときだけ起用名を表示 -->
              <div>
                <p class="text-[10px] font-mono text-slate-400 uppercase tracking-wider mb-1">{{ t('competition.tl.opponentArmy') }}</p>
                <p class="px-3 py-1.5 rounded-lg text-sm bg-slate-50 dark:bg-slate-900/40 border border-slate-200 dark:border-slate-700 truncate">
                  <template v-if="!mu.opponentLineupPublished">
                    <span class="text-slate-400 italic">{{ t('competition.tl.opponentLineupHidden') }}</span>
                  </template>
                  <span v-else-if="match.opponentAssigned">{{ match.opponentAssigned.displayName }}</span>
                  <span v-else class="text-slate-400 italic">{{ t('competition.tl.unassigned') }}</span>
                </p>
                <p
                  class="mt-1 text-[10px] font-mono text-right"
                  :class="match.opponentLocked ? 'text-amber-600 dark:text-amber-300 font-bold' : 'text-slate-400'"
                >
                  {{ match.opponentLocked ? t('competition.tl.lockedAlready') : t('competition.tl.lockBefore') }}
                </p>

                <!-- 相手の自選曲 (StrategyCard 発動判断用) -->
                <div class="mt-1.5 pt-1.5 border-t border-slate-100 dark:border-slate-700/40">
                  <p class="text-[10px] font-mono text-slate-400 uppercase tracking-wider mb-1">{{ t('competition.tl.opponentPickLabel') }}</p>
                  <template v-if="match.opponentPick">
                    <p class="text-sm font-bold truncate">{{ match.opponentPick.songTitle }}</p>
                    <p class="text-[10px] font-mono text-slate-400 truncate">
                      {{ match.opponentPick.songGenre }} · Lv {{ match.opponentPick.songLevel }} ·
                      {{ match.opponentPick.songDiff === 'L' ? 'LEGGENDARIA' : 'ANOTHER' }}
                    </p>
                  </template>
                  <p v-else class="text-[10px] font-mono text-slate-400 italic">
                    {{ match.opponentPickPublished ? t('competition.tl.opponentPickNotSubmitted') : t('competition.tl.opponentPickHidden') }}
                  </p>
                </div>
              </div>
            </li>
          </ul>
        </div>
      </section>

      <!-- 運営チャット (チャットボット風フローティングウィジェット) -->
      <CompetitionChatWidget :token="props.token" />
    </div>
  </div>
</template>
