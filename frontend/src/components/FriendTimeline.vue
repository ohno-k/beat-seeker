<script setup lang="ts">
/**
 * 【コンポーネントの役割】 フレンド画面の「タイムライン」タブの中身。
 *
 * 自分と全フレンドのアップロード由来のイベントを時系列降順で表示する。
 *  - SCORE_UPDATE  : 譜面スコアの更新（上位 N 件をプレビュー表示）
 *  - OVERTAKE_SONG : 譜面 EX スコアでフレンド/仮想ライバルを抜いた
 *  - OVERTAKE_TOTAL: 総合 BEAT-PT でフレンド/仮想ライバルを抜いた
 *
 * バックエンド: GET /api/timeline
 */
import { onMounted, onUnmounted, watch, computed, ref } from 'vue';
import { useTimeline } from '../composables/useTimeline';
import { useAdmin } from '../composables/useAdmin';
import { useAuth } from '../composables/useAuth';
import FriendTimelineEventBody from './FriendTimelineEventBody.vue';
import { mdiAccountGroupOutline, mdiClockOutline, mdiRefresh } from '@mdi/js';
import { formatJstDate, formatJstShortDateTime, toJstDate } from '../utils/jstTime';
import type {
  TimelineEntry,
  OvertakeSongPayload,
  OvertakeTotalPayload,
} from '../composables/useTimeline';

const { entries, isLoading, error, fetchTimeline, hasMore, isLoadingMore, loadOlder, backfillMine, backfillAll, backfillStatus } = useTimeline();
const { isAdmin } = useAdmin();
const { user } = useAuth();

/** ログイン中ユーザーの ID（OVERTAKE 系の視点判定に使う）。未ログインなら 0。 */
const myId = computed(() => user.value?.id ?? 0);

/**
 * OVERTAKE イベントを「ビューワーから見た関係性」で 4 通りに分類する。
 *  - 'OVERTAKE_BY_ME'     : 自分が誰かを抜いた
 *  - 'OVERTAKEN_BY_OTHER' : 自分が誰かに抜かれた
 *  - 'BETWEEN_OTHERS'     : 自分以外の二者間で起きた
 *  - null                 : OVERTAKE 系ではない or 判定不可
 */
type ViewerRelation = 'OVERTAKE_BY_ME' | 'OVERTAKEN_BY_OTHER' | 'BETWEEN_OTHERS' | null;
const viewerRelation = (e: TimelineEntry): ViewerRelation => {
  if (e.type !== 'OVERTAKE_SONG' && e.type !== 'OVERTAKE_TOTAL') return null;
  const p = e.payload as OvertakeSongPayload | OvertakeTotalPayload | null;
  if (!p) return null;
  // 仮想ライバルは isVirtual=true で必ず「他人」扱い（人間 user_id とぶつからない）。
  const rivalIsMe = !p.isVirtual && p.rivalId === myId.value;
  if (e.isMe) return 'OVERTAKE_BY_ME';
  if (rivalIsMe) return 'OVERTAKEN_BY_OTHER';
  return 'BETWEEN_OTHERS';
};

/** バックフィル実行直後にユーザへ結果を見せるための一時メッセージ。 */
const backfillMsg = ref<string | null>(null);
/** バックフィル処理中フラグ（ボタン無効化用、表示中の進捗用）。 */
const isBackfilling = ref(false);

/** 空状態または更新ボタンから呼ばれる「自分のみ過去履歴から再構築」処理。 */
const handleBackfill = async (force: boolean) => {
  isBackfilling.value = true;
  backfillMsg.value = null;
  try {
    const data = await backfillMine(force);
    if (!data) {
      backfillMsg.value = 'バックフィルに失敗しました。コンソールログを確認してください。';
      return;
    }
    if (data.skipped) {
      backfillMsg.value = `既存のタイムラインがあるためスキップしました（履歴ログ ${data.historyLogs} 件あり）。再実行したい場合は「強制再生成」を押してください。`;
    } else if (data.createdEvents === 0) {
      backfillMsg.value = `履歴ログ ${data.historyLogs} 件をチェックしましたが、SCORE_UPDATE を作成できる差分情報を含む履歴がありませんでした。`;
    } else {
      backfillMsg.value = `履歴ログ ${data.historyLogs} 件から ${data.createdEvents} 件のタイムラインイベントを生成しました。`;
    }
  } finally {
    isBackfilling.value = false;
  }
};

/**
 * 管理者向け: 全ユーザを対象にバックフィルを実行する。
 * サーバ側は非同期ジョブで走り、composable がポーリングして完了を待つ。
 */
const handleBackfillAll = async (force: boolean) => {
  isBackfilling.value = true;
  backfillMsg.value = '全ユーザバックフィルを開始しました。進捗を確認中...';
  try {
    const data = await backfillAll(force);
    if (!data) {
      backfillMsg.value = '全ユーザバックフィルに失敗しました。コンソールログを確認してください。';
      return;
    }
    if (data.lastError) {
      backfillMsg.value = `エラーで終了: ${data.lastError}`;
    } else {
      backfillMsg.value = `全ユーザ対象に再構築完了: 処理 ${data.processedUsers} ユーザ / スキップ ${data.skippedUsers} ユーザ / 生成 ${data.createdEvents} 件のイベント。`;
    }
  } finally {
    isBackfilling.value = false;
  }
};

/** 進捗率 (0-100)。未起動時は 0。 */
const backfillProgressPct = computed(() => {
  const s = backfillStatus.value;
  if (!s || s.totalUsers <= 0) return 0;
  return Math.min(100, Math.round((s.processedUsers / s.totalUsers) * 100));
});

onMounted(() => {
  fetchTimeline();
});

/** 無限スクロール用センチネル要素。ビューポート手前に入ったら過去分を追加取得する。 */
const sentinel = ref<HTMLElement | null>(null);
let observer: IntersectionObserver | null = null;

// センチネル要素が(再)描画されるたびに IntersectionObserver を貼り直す。
// entries が空→ありに変わって初めて要素が現れるため、ref の変化を監視する。
watch(sentinel, (el) => {
  observer?.disconnect();
  observer = null;
  if (el) {
    observer = new IntersectionObserver(
      (obsEntries) => { if (obsEntries[0]?.isIntersecting) loadOlder(); },
      { rootMargin: '300px' },
    );
    observer.observe(el);
  }
});

onUnmounted(() => observer?.disconnect());

/**
 * クリックで「ほか N 譜面を表示」を展開したイベント ID の集合。
 * 子コンポーネント {@link FriendTimelineEventBody} へ `expandedSongs` として渡し、
 * `toggle-songs` を受け取ってここで状態を切り替える（状態はスレッド親で 1 か所に集約）。
 */
const expandedSongIds = ref<Set<number>>(new Set());

/** SCORE_UPDATE の譜面リスト展開状態をトグルする。 */
const toggleSongs = (id: number) => {
  const next = new Set(expandedSongIds.value);
  if (next.has(id)) next.delete(id); else next.add(id);
  expandedSongIds.value = next;
};

/** ISO 日時を「◯分前 / ◯時間前 / M/D HH:mm」表記（日時は JST 固定）に整形する。 */
const formatRelative = (iso: string): string => {
  const d = toJstDate(iso);
  if (!d) return '';
  const diffMs = Date.now() - d.getTime();
  const diffMin = Math.floor(diffMs / 60000);
  if (diffMin < 1) return 'たった今';
  if (diffMin < 60) return `${diffMin}分前`;
  const diffH = Math.floor(diffMin / 60);
  if (diffH < 24) return `${diffH}時間前`;
  const diffD = Math.floor(diffH / 24);
  if (diffD < 7) return `${diffD}日前`;
  return formatJstShortDateTime(d);
};

/** スレッド: 同日・同ユーザのイベント群（親 1 + 子 N の集合）。 */
interface Thread {
  /** スレッド識別子（"YYYY/MM/DD::userId"）。展開状態の管理キーに使う。 */
  key: string;
  /** ユーザ情報（このスレッドの主体）。 */
  user: { id: number; displayName: string; isMe: boolean };
  /** スレッドに属するイベント（新しい順）。 */
  items: TimelineEntry[];
}

/**
 * payload 同一性のフィンガープリント。重複イベントの集約判定に使う。
 *
 * バックフィルと通常保存の双方が同じ ScoreHistoryLog 由来でイベントを作ったケースなど、
 * type + payload が完全に同じイベントが複数並ぶことがあるので、それをスレッド内で 1 件に統合する。
 */
const fingerprint = (e: TimelineEntry): string => {
  try {
    return e.type + '::' + JSON.stringify(e.payload);
  } catch {
    // JSON 化できない異常データは ID で個別扱い（マージしない）。
    return e.type + '::' + e.id;
  }
};

/** 日付ごとに、ユーザでまとめたスレッド配列を作る。同一 payload の重複は除く。 */
const threadedEntries = computed<{ date: string; threads: Thread[] }[]>(() => {
  const dateGroups: { date: string; threads: Thread[] }[] = [];
  let currentDateKey = '';
  // その日のユーザ ID → 対応するスレッド。日付が変わるごとに作り直す。
  let userMap = new Map<number, Thread>();
  // 各スレッドで「すでに見た payload」を追跡。同じものは 2 件目以降をスキップする。
  let threadSeen = new Map<string, Set<string>>();

  for (const e of entries.value) {
    // 日付の切れ目も JST 基準（端末 TZ が違っても同じ日付でまとまる）。
    const dateKey = formatJstDate(e.createdAt);
    if (dateKey !== currentDateKey) {
      dateGroups.push({ date: dateKey, threads: [] });
      currentDateKey = dateKey;
      userMap = new Map();
      threadSeen = new Map();
    }
    const group = dateGroups[dateGroups.length - 1];
    let thread = userMap.get(e.userId);
    if (!thread) {
      thread = {
        key: `${dateKey}::${e.userId}`,
        user: { id: e.userId, displayName: e.displayName, isMe: e.isMe },
        items: [],
      };
      userMap.set(e.userId, thread);
      threadSeen.set(thread.key, new Set());
      group.threads.push(thread);
    }
    // 同じ payload のイベントはスレッドに 1 件しか入れない（バックフィル + 通常保存の重複対策）。
    const seen = threadSeen.get(thread.key)!;
    const fp = fingerprint(e);
    if (seen.has(fp)) continue;
    seen.add(fp);
    thread.items.push(e);
  }
  return dateGroups;
});

/** スレッド展開状態。「親 + 直近 1 件」だけ見せ、それ以上はクリックで展開する。 */
const expandedThreadKeys = ref<Set<string>>(new Set());
/** スレッド展開状態をトグル。 */
const toggleThread = (key: string) => {
  const next = new Set(expandedThreadKeys.value);
  if (next.has(key)) next.delete(key); else next.add(key);
  expandedThreadKeys.value = next;
};
/** スレッドが展開中か。 */
const isThreadExpanded = (key: string) => expandedThreadKeys.value.has(key);

/** イベントバッジのラベル文字列を返す（自分視点を反映）。 */
const badgeLabel = (e: TimelineEntry): string => {
  if (e.type === 'SCORE_UPDATE') return 'SCORE';
  const r = viewerRelation(e);
  if (e.type === 'OVERTAKE_SONG') return r === 'OVERTAKEN_BY_OTHER' ? 'スコアを抜かれた' : 'スコアを抜いた';
  return r === 'OVERTAKEN_BY_OTHER' ? 'BEAT-PT で抜かれた' : 'BEAT-PT で抜いた';
};

/** イベントバッジの色（Vuetify の color）を返す。 */
const badgeColor = (e: TimelineEntry): string | undefined => {
  if (e.type === 'SCORE_UPDATE') return 'success';
  const r = viewerRelation(e);
  if (r === 'OVERTAKEN_BY_OTHER') return 'error';
  if (r === 'OVERTAKE_BY_ME') return 'warning';
  return undefined;
};
</script>

<template>
  <div class="space-y-6">
    <!-- ヘッダ + 再読込ボタン -->
    <v-card>
     <v-card-item>
      <v-card-title>タイムライン</v-card-title>
      <v-card-subtitle>自分とフレンドの活動を新しい順に表示します</v-card-subtitle>
      <template #append>
      <div class="flex items-center gap-2">
        <v-btn
          v-if="isAdmin"
          @click="handleBackfillAll(false)"
          :disabled="isBackfilling || isLoading"
          variant="tonal"
          color="error"
          class="whitespace-nowrap"
          title="【管理者専用】全ユーザの CSV アップロード履歴から SCORE_UPDATE を再生成します"
        >
          <template #prepend>
            <v-icon :icon="mdiAccountGroupOutline" size="16" :class="{ 'animate-spin': isBackfilling }" />
          </template>
          全ユーザを再構築
        </v-btn>
        <v-btn
          @click="handleBackfill(false)"
          :disabled="isBackfilling || isLoading"
          variant="tonal"
          color="primary"
          class="whitespace-nowrap"
          title="自分の過去の CSV アップロード履歴から SCORE_UPDATE イベントを再生成します"
        >
          <template #prepend>
            <v-icon :icon="mdiClockOutline" size="16" :class="{ 'animate-spin': isBackfilling }" />
          </template>
          履歴から再構築
        </v-btn>
        <v-btn
          @click="fetchTimeline()"
          :disabled="isLoading"
          variant="tonal"
        >
          <template #prepend>
            <v-icon :icon="mdiRefresh" size="20" :class="{ 'animate-spin': isLoading }" />
          </template>
          更新
        </v-btn>
      </div>
      </template>
     </v-card-item>
    </v-card>

    <!-- バックフィル結果メッセージ -->
    <v-alert v-if="backfillMsg" type="info">
      <div class="space-y-2">
        <div>{{ backfillMsg }}</div>
        <!-- バックフィル走行中のみ進捗バーを表示 -->
        <div v-if="backfillStatus?.running" class="space-y-1.5">
          <div class="flex justify-between text-[11px] font-bold tabular-nums">
            <span>{{ backfillStatus.processedUsers }} / {{ backfillStatus.totalUsers }} ユーザ処理中</span>
            <span>イベント {{ backfillStatus.createdEvents }} 件生成</span>
          </div>
          <v-progress-linear
            :model-value="backfillProgressPct"
            height="8"
            rounded
          />
        </div>
      </div>
    </v-alert>

    <!-- ローディング -->
    <v-card v-if="isLoading && entries.length === 0">
     <v-card-text class="flex flex-col items-center justify-center py-12">
      <v-progress-circular indeterminate size="40" width="4" class="mb-4" />
      <p class="text-slate-500 dark:text-slate-400">読み込み中...</p>
     </v-card-text>
    </v-card>

    <!-- エラー -->
    <v-alert v-else-if="error" type="error">
      {{ error }}
    </v-alert>

    <!-- 空状態 -->
    <v-card v-else-if="entries.length === 0">
     <v-card-text class="flex flex-col items-center justify-center py-12">
      <div class="w-16 h-16 bg-slate-100 dark:bg-slate-700 rounded-full flex items-center justify-center text-slate-400 mb-4">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
          <path stroke-linecap="round" stroke-linejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
      </div>
      <p class="text-slate-500 dark:text-slate-400 font-bold">まだタイムラインに表示できる活動がありません</p>
      <p class="text-slate-400 dark:text-slate-500 text-sm mt-1 mb-4">CSV をアップロードするとイベントが追加されます。</p>
      <div class="flex flex-col items-center gap-2 mt-2">
        <v-btn
          @click="handleBackfill(false)"
          :disabled="isBackfilling"
          color="primary"
        >
          <template #prepend>
            <v-icon :icon="mdiRefresh" size="16" :class="{ 'animate-spin': isBackfilling }" />
          </template>
          過去のアップロード履歴から再構築する
        </v-btn>
        <p class="text-[11px] text-slate-400 dark:text-slate-500 max-w-xs text-center">
          スコア履歴ログから SCORE_UPDATE イベントを生成します（過去の「抜き」情報は復元できません）。
        </p>
      </div>
     </v-card-text>
    </v-card>

    <!-- イベント本体 -->
    <div v-else class="space-y-4">
      <div v-for="group in threadedEntries" :key="group.date" class="space-y-3">
        <h3 class="text-xs font-bold text-slate-400 dark:text-slate-500 px-2">{{ group.date }}</h3>

        <v-card class="divide-y divide-slate-100 dark:divide-slate-700/60">
          <article
            v-for="thread in group.threads"
            :key="thread.key"
            class="p-4 sm:p-5 hover:bg-slate-50/60 dark:hover:bg-slate-700/20 transition-colors"
          >
            <!-- ヘッダ行: アバター + ユーザ名 + 親イベントのバッジ + 時刻 + 件数 を 1 行に統合 -->
            <div class="flex items-center gap-3 flex-wrap">
              <div
                class="w-10 h-10 rounded-full flex items-center justify-center text-white text-sm font-bold shrink-0"
                :class="thread.user.isMe ? 'bg-blue-600' : 'bg-slate-500'"
              >
                {{ thread.user.displayName.charAt(0) || '?' }}
              </div>
              <span class="font-bold text-slate-900 dark:text-white text-sm">
                {{ thread.user.displayName }}
              </span>
              <v-chip v-if="thread.user.isMe" size="x-small" label color="primary">自分</v-chip>
              <!-- 親イベント (最新) のバッジ + 時刻 -->
              <v-chip
                size="x-small"
                label
                :color="badgeColor(thread.items[0])"
              >{{ badgeLabel(thread.items[0]) }}</v-chip>
              <span class="text-[11px] text-slate-400 dark:text-slate-500">{{ formatRelative(thread.items[0].createdAt) }}</span>
              <span v-if="thread.items.length > 1" class="text-[11px] font-bold text-slate-400 dark:text-slate-500">
                ・ {{ thread.items.length }} 件の活動
              </span>
            </div>

            <!-- 親イベント本文 -->
            <div class="mt-2 ml-13 sm:ml-[3.25rem]">
              <FriendTimelineEventBody
                :entry="thread.items[0]"
                :viewer-rel="viewerRelation(thread.items[0])"
                :expanded-songs="expandedSongIds.has(thread.items[0].id)"
                @toggle-songs="toggleSongs(thread.items[0].id)"
              />
            </div>

            <!-- 子イベント（スレッド展開時のみ）: 左に縦線でぶら下げる -->
            <div
              v-if="isThreadExpanded(thread.key) && thread.items.length > 1"
              class="mt-3 ml-13 sm:ml-[3.25rem] pl-4 border-l-2 border-slate-200 dark:border-slate-700/60 space-y-3"
            >
              <div v-for="child in thread.items.slice(1)" :key="child.id">
                <!-- 子のバッジ + 時刻 -->
                <div class="flex items-center gap-2 flex-wrap">
                  <v-chip
                    size="x-small"
                    label
                    :color="badgeColor(child)"
                  >{{ badgeLabel(child) }}</v-chip>
                  <span class="text-[11px] text-slate-400 dark:text-slate-500">{{ formatRelative(child.createdAt) }}</span>
                </div>
                <div class="mt-2">
                  <FriendTimelineEventBody
                    :entry="child"
                    :viewer-rel="viewerRelation(child)"
                    :expanded-songs="expandedSongIds.has(child.id)"
                    @toggle-songs="toggleSongs(child.id)"
                  />
                </div>
              </div>
            </div>

            <!-- スレッドトグルボタン: 折りたたみ時「ほか N 件」展開時「折りたたむ」 -->
            <v-btn
              v-if="thread.items.length > 1"
              type="button"
              variant="text"
              color="primary"
              size="x-small"
              @click="toggleThread(thread.key)"
              class="mt-2 ml-13 sm:ml-[3.25rem]"
            >
              <template v-if="isThreadExpanded(thread.key)">スレッドを折りたたむ</template>
              <template v-else>ほか {{ thread.items.length - 1 }} 件のイベントを表示</template>
            </v-btn>
          </article>
        </v-card>
      </div>

      <!-- 無限スクロール: 末尾のセンチネルがビューポート手前に来たら過去分を追加取得 -->
      <div v-if="isLoadingMore" class="flex items-center justify-center py-6">
        <v-progress-circular indeterminate size="24" width="3" />
      </div>
      <p v-else-if="!hasMore" class="text-center text-xs text-slate-400 dark:text-slate-500 py-6">
        これ以上の活動はありません
      </p>
      <div ref="sentinel" class="h-px" aria-hidden="true"></div>
    </div>
  </div>
</template>
