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
import { kindLevelLabel, isAdjacentKind } from '../composables/competitionMatchKinds';
import CompetitionChatWidget from '../components/CompetitionChatWidget.vue';
import { formatJstDateTime } from '../utils/jstTime';

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

/**
 * この slot に当該メンバーを選べない理由 (選べるなら null)。<option> の disabled と注記に使う。
 *
 * 予選 (3 戦): 1 人 1 戦まで + 予選コスト制限。
 * 決勝 (7 戦): コスト対象外。1 人 {@code finalsMaxMatchesPerPlayer} 戦まで + 連続する戦への起用は禁止。
 * (7 枠 ÷ 2 戦 = 最低 4 人必要なので、全枠を埋めれば 4 人全員が自動的に出場する)
 * サーバ側 (CompetitionTlController#assign) でも同じ検証をしているので UI 制御は二重防御。
 */
const assignBlockReason = (
  matchup: TlMatchupDto,
  match: TlMatchDto,
  participantId: number,
): string | null => {
  if (!view.value) return null;
  const others = matchup.matches.filter(
    m => m.matchId !== match.matchId && m.myAssigned?.participantId === participantId,
  );
  if (!matchup.isFinals) {
    if (others.length > 0) return t('competition.tl.usedInOtherMatch');
    return wouldExceedCostFor(participantId, match) ? t('competition.tl.costInsufficient') : null;
  }
  if (others.some(m => isAdjacentKind(m.matchKind, match.matchKind))) {
    return t('competition.tl.finalsNoConsecutive');
  }
  if (others.length >= view.value.finalsMaxMatchesPerPlayer) {
    return t('competition.tl.finalsMaxReached', { n: view.value.finalsMaxMatchesPerPlayer });
  }
  return null;
};

/** 決勝 matchup で、まだ 1 戦も起用されていないメンバー名 (全員出場のチェック用)。 */
const finalsUnusedMembers = (matchup: TlMatchupDto): string[] => {
  if (!view.value || !matchup.isFinals) return [];
  const used = new Set(
    matchup.matches.map(m => m.myAssigned?.participantId).filter((id): id is number => !!id),
  );
  return view.value.members.filter(m => !used.has(m.id)).map(m => m.displayName);
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
    <v-btn-toggle
      :model-value="currentLang"
      mandatory
      class="absolute top-3 right-3 z-30 bg-white/80 dark:bg-slate-800/80 backdrop-blur shadow-sm"
    >
      <v-btn
        v-for="lang in availableLanguages"
        :key="lang"
        :value="lang"
        size="small"
        @click="setLanguage(lang)"
        :aria-pressed="currentLang === lang"
        class="text-[11px]"
      >{{ t(`lang.${lang}`) }}</v-btn>
    </v-btn-toggle>

    <div v-if="isLoading && !view" class="text-center py-20 text-slate-400 text-sm">
      <v-progress-circular size="20" width="2" class="mr-2" />{{ t('competition.player.loading') }}
    </div>

    <v-alert
      v-else-if="!view"
      type="error"
      :icon="false"
      class="max-w-2xl mx-auto rounded-2xl p-6 text-center"
    >
      <p class="text-lg font-bold text-rose-700 dark:text-rose-300">{{ t('competition.tl.invalidToken') }}</p>
      <p class="text-sm text-rose-600 dark:text-rose-400 mt-2">{{ t('competition.tl.invalidTokenHint') }}</p>
    </v-alert>

    <div v-else class="max-w-5xl mx-auto space-y-6">
      <!-- ヘッダ: 大会名・自チーム名・メンバー -->
      <div>
        <p class="text-[10px] font-mono uppercase tracking-[0.3em] text-slate-400 dark:text-slate-500">{{ view.competition.name }}</p>
        <div class="flex items-baseline gap-2 mt-1 flex-wrap">
          <h1 class="text-2xl sm:text-3xl font-black tracking-tight" :class="teamColorClass(view.team.teamName)">{{ view.team.teamName }}</h1>
          <v-chip size="small" label class="text-[10px] font-black bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300 tracking-wider">{{ t('competition.common.tlAdminBadge') }}</v-chip>
        </div>
        <p class="text-xs text-slate-500 dark:text-slate-400 mt-2 font-mono">
          {{ t('competition.common.status') }} <span class="font-bold">{{ statusLabel(view.competition.status) }}</span>
          <span v-if="view.competition.deadlineAt"> · {{ t('competition.common.deadline') }} {{ formatJstDateTime(view.competition.deadlineAt) }}</span>
        </p>
      </div>

      <!-- メンバー一覧 + 予選コスト残量 -->
      <v-card tag="section" class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl p-4">
        <div class="flex items-center justify-between flex-wrap gap-2 mb-3">
          <p class="text-xs font-black tracking-[0.3em] uppercase text-slate-500">{{ t('competition.tl.membersHeader') }}</p>
          <div class="flex items-center gap-3 flex-wrap">
            <p class="text-[10px] font-mono text-slate-400">
              {{ t('competition.tl.costInitial', { n: view.initialCost }) }} /
              {{ t('competition.matchKind.vanguard') }} {{ view.costPerKind.vanguard }} ·
              {{ t('competition.matchKind.middle') }} {{ view.costPerKind.middle }} ·
              {{ t('competition.matchKind.captain') }} {{ view.costPerKind.captain }}
            </p>
            <v-chip size="small" label class="text-[10px] font-black bg-fuchsia-100 text-fuchsia-700 dark:bg-fuchsia-900/40 dark:text-fuchsia-300 tracking-wider">
              ⚡ ストラテジー {{ view.strategyUsedMatchupCount }} / {{ view.strategyLimit }} 組
            </v-chip>
            <!-- 決定できる状態なのに未決定の試合が残っていれば警告表示 (意思決定漏れの防止) -->
            <v-chip
              v-if="undecidedStrategyCount > 0"
              size="small"
              label
              class="text-[10px] font-black bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300 tracking-wider"
            >{{ t('competition.tl.strategyUndecidedCount', { n: undecidedStrategyCount }) }}</v-chip>
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
              <v-chip v-if="m.isTl" size="x-small" label class="ml-1 text-[9px] font-black bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300 tracking-wider align-middle">{{ t('competition.common.tlBadge') }}</v-chip>
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
      </v-card>

      <!-- 4 matchup ぶんのアサイン UI -->
      <section class="space-y-4">
        <p class="text-xs font-black tracking-[0.3em] uppercase text-slate-500">{{ t('competition.tl.matchupsHeader', { n: sortedMatchups.length }) }}</p>
        <v-card v-if="sortedMatchups.length === 0" class="text-center text-sm text-slate-400 italic py-6 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl">
          {{ t('competition.tl.noMatchups') }}<br />{{ t('competition.tl.noMatchupsHint') }}
        </v-card>

        <v-card
          v-for="mu in sortedMatchups"
          :key="mu.matchupId"
          class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl overflow-hidden"
        >
          <div class="px-4 py-3 bg-slate-50 dark:bg-slate-900/60 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between flex-wrap gap-2">
            <p class="font-bold text-sm">
              {{ t('competition.player.vs') }} <span :class="teamColorClass(mu.opponentTeam.teamName)">{{ mu.opponentTeam.teamName ?? '?' }}</span>
              <v-chip
                v-if="mu.isFinals"
                size="small"
                label
                class="ml-2 text-[10px] font-black bg-amber-500 text-white tracking-wider"
              >🏆 {{ t('competition.tl.finalsBadge') }}</v-chip>
            </p>
            <div class="flex items-center gap-2 flex-wrap">
              <v-chip
                v-if="mu.myLineupPublished"
                size="x-small"
                label
                class="text-[9px] font-black bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300 tracking-wider"
              >{{ t('competition.tl.mySidePublished') }}</v-chip>
              <v-chip
                v-else
                size="x-small"
                label
                class="text-[9px] font-black bg-slate-200 text-slate-500 dark:bg-slate-700 dark:text-slate-400 tracking-wider"
              >{{ t('competition.tl.mySideUnpublished') }}</v-chip>
              <p class="text-[10px] font-mono text-slate-400 tracking-wider uppercase">
                {{ t('competition.tl.matchupOrder', { n: mu.matchupOrder }) }} · {{ t('competition.tl.mySideLabel', { side: mu.mySide.toUpperCase() }) }}
              </p>
            </div>
          </div>

          <!-- 決勝の起用ルール (1 人 2 戦まで・連続出場禁止・4 人全員出場) の案内 -->
          <div
            v-if="mu.isFinals"
            class="px-4 py-2 bg-amber-50 dark:bg-amber-900/20 border-b border-amber-200 dark:border-amber-700/60 text-[10px] font-mono text-amber-700 dark:text-amber-300 space-y-0.5"
          >
            <p>{{ t('competition.tl.finalsRuleNote', { n: view.finalsMaxMatchesPerPlayer }) }}</p>
            <p v-if="finalsUnusedMembers(mu).length > 0">
              {{ t('competition.tl.finalsUnusedMembers', { names: finalsUnusedMembers(mu).join(', ') }) }}
            </p>
          </div>

          <ul class="divide-y divide-slate-100 dark:divide-slate-700/60">
            <li v-for="match in mu.matches" :key="match.matchId" class="px-4 py-3 grid grid-cols-1 sm:grid-cols-[140px_1fr_1fr] gap-3 items-center">
              <!-- 戦表記 + 運営指定ジャンルバッジ -->
              <div>
                <p class="font-bold text-sm">{{ kindLabel(match.matchKind) }}</p>
                <p class="text-[10px] font-mono text-slate-400">{{ kindLevelLabel(match.matchKind) }}</p>
                <v-chip
                  v-if="match.requiredGenre"
                  size="x-small"
                  label
                  class="mt-1 text-[9px] font-black tracking-wider"
                  :class="genreBadgeClass(match.requiredGenre)"
                >
                  {{ t('competition.tl.requiredGenrePrefix') }} {{ match.requiredGenre }}
                </v-chip>
                <v-chip
                  v-else
                  size="x-small"
                  label
                  class="mt-1 text-[9px] font-black bg-slate-200 text-slate-500 dark:bg-slate-700 dark:text-slate-400 tracking-wider"
                >
                  {{ t('competition.tl.genreUnspecified') }}
                </v-chip>
              </div>

              <!-- 自軍 slot: select で 4 メンバーから選ぶ -->
              <div>
                <p class="text-[10px] font-mono text-slate-400 uppercase tracking-wider mb-1">{{ t('competition.tl.myArmy') }}</p>
                <div class="flex items-center gap-2">
                  <!-- 選べない理由 (予選: 他試合で起用中/コスト不足、決勝: 2 戦上限/連続出場) は共通判定 -->
                  <v-select
                    :model-value="match.myAssigned?.participantId ?? ''"
                    :disabled="match.myLocked"
                    :items="[
                      { title: '未割当', value: '' },
                      ...view.members.map(m => ({
                        title: `${m.displayName}${m.isTl ? ' (TL)' : ''}${!mu.isFinals ? ` (残${m.remainingCost})` : ''}${assignBlockReason(mu, match, m.id) ? ` ${assignBlockReason(mu, match, m.id)}` : ''}`,
                        value: m.id,
                        props: { disabled: !!assignBlockReason(mu, match, m.id) },
                      })),
                    ]"
                    @update:model-value="(v: number | string | null) => handleAssign(match, v == null ? '' : String(v))"
                    class="flex-1 text-sm"
                  />
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
                        <v-btn
                          size="x-small"
                          @click="handleSetStrategy(match, true)"
                          class="rounded-lg text-[10px] font-black tracking-wider uppercase"
                          :class="match.myStrategyDecided && match.myStrategyEnabled
                            ? 'bg-gradient-to-r from-fuchsia-500 to-amber-500 text-white shadow'
                            : 'bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-fuchsia-100 hover:text-fuchsia-700 dark:hover:bg-fuchsia-900/40 dark:hover:text-fuchsia-300'"
                        >{{ t('competition.tl.strategyUse') }}</v-btn>
                        <v-btn
                          size="x-small"
                          @click="handleSetStrategy(match, false)"
                          class="rounded-lg text-[10px] font-black tracking-wider uppercase"
                          :class="match.myStrategyDecided && !match.myStrategyEnabled
                            ? 'bg-slate-600 text-white shadow dark:bg-slate-500'
                            : 'bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-slate-300 dark:hover:bg-slate-600'"
                        >{{ t('competition.tl.strategySkip') }}</v-btn>
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
        </v-card>
      </section>

      <!-- 運営チャット (チャットボット風フローティングウィジェット) -->
      <CompetitionChatWidget :token="props.token" />
    </div>
  </div>
</template>
