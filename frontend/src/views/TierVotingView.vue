<script setup lang="ts">
/**
 * 【Viewの役割】 譜面 Tier 投票ページ。ユーザーが譜面ごとに昇格/据え置き/降格 or Tier 値を投票できる。
 *
 * 機能:
 *  - 既にランクが付いている譜面には PROMOTE / STAY / DEMOTE の3択ボタンを表示し、投票を集計する。
 *  - 未カテゴリ（uncategorized）譜面は 11.0〜13.0 の 0.1 刻み Tier 値プルダウンで投票する。
 *  - 同じ投票を再クリックで取り消し（トグル）、違う投票でも上書き（前の票をデクリメント）する。
 *  - 譜面クリック or コメントバッジクリックで `TierCommentModal` を開いてディスカッション可能。
 *  - 検索で曲名絞り込み、最新コメント時刻が新しい順に並べ替え。
 *
 * 依存:
 *  - `useGameData.diffTableRanks` — ランク別の曲一覧。
 *  - API: `/api/tier-votes/all`, `/api/tier-votes/mine`, `/api/tier-votes`, `/api/tier-comments/stats`。
 *  - `TierCommentModal` — コメント閲覧/投稿。
 */
import { ref, computed, onMounted, watch } from 'vue';
import { useGameData } from '../composables/useGameData';
import { useAuth } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';
import { API_BASE } from '../composables/useAuth';
import { toJstDate } from '../utils/jstTime';
import TierCommentModal from '../components/TierCommentModal.vue';
import { mdiMagnify, mdiMessageOutline } from '@mdi/js';

const { t } = useI18n();
const { diffTableRanks } = useGameData();
const { isLoggedIn, authHeaders } = useAuth();

// Tier 選択肢: 11.0 〜 13.0 を 0.1 刻みで生成（整数ループで浮動小数誤差を回避）
const TIER_OPTIONS: string[] = [];
for (let i = 110; i <= 130; i++) {
  TIER_OPTIONS.push((i / 10).toFixed(1));
}

/** Tier プルダウン（v-select）の選択肢。先頭は「未投票」（空文字 = 投票削除）。 */
const tierSelectItems = computed(() => [
  { title: t('tierVoting.noVote'), value: '' },
  ...TIER_OPTIONS.map(tier => ({ title: tier, value: tier })),
]);

/** 全ユーザーの投票集計: "title|difficultyName" → { 投票タイプ: 票数 } */
const allVotes = ref<Map<string, Record<string, number>>>(new Map());
/** ログインユーザー本人の投票: "title|difficultyName" → 投票文字列（PROMOTE/STAY/DEMOTE or Tier 値） */
const myVotes = ref<Map<string, string>>(new Map());

/** 投票データのロード中フラグ */
const isLoadingVotes = ref(false);
/** 曲名検索クエリ */
const searchQuery = ref('');

/** コメント統計（件数と最新投稿時刻） */
const commentStats = ref<Map<string, { count: number; latest: string }>>(new Map());
/** コメントモーダルの表示フラグ */
const showCommentModal = ref(false);
/** モーダルに表示中の曲名 */
const activeCommentTitle = ref('');
/** モーダルに表示中の難易度名 */
const activeCommentDiff = ref('');

/**
 * 【関数の役割】 全曲のコメント統計を取得してキャッシュする。
 * APIが返す配列を「title|difficultyName → {count, latest}」の Map に再構築する。
 */
const fetchCommentStats = async () => {
  try {
    const res = await fetch(`${API_BASE}/api/tier-comments/stats`);
    if (res.ok) {
      const data: Array<{ title: string; difficultyName: string; commentCount: number; latestCommentAt: string }> = await res.json();
      const map = new Map();
      for (const item of data) {
        map.set(`${item.title}|${item.difficultyName}`, { count: item.commentCount, latest: item.latestCommentAt });
      }
      commentStats.value = map;
    }
  } catch {
    // ネットワークエラー等は静かに無視（統計は補助的情報なので致命ではない）
  }
};

/** コメントモーダルを特定譜面で開く */
const openCommentModal = (title: string, difficultyName: string) => {
  activeCommentTitle.value = title;
  activeCommentDiff.value = difficultyName;
  showCommentModal.value = true;
};

/**
 * 【関数の役割】 全員の投票集計を取得して `allVotes` に反映する。
 * サーバ側では投票タイプ別カラムで返ってくるため、title/difficultyName を分離してキー化する。
 */
const fetchAllVotes = async () => {
  isLoadingVotes.value = true;
  try {
    const res = await fetch(`${API_BASE}/api/tier-votes/all`);
    if (res.ok) {
      const data: Array<Record<string, any>> = await res.json();
      const map = new Map<string, Record<string, number>>();
      for (const item of data) {
        const { title, difficultyName, ...rest } = item;
        const counts: Record<string, number> = {};
        for (const [k, v] of Object.entries(rest)) {
          counts[k] = Number(v) || 0;
        }
        map.set(`${title}|${difficultyName}`, counts);
      }
      allVotes.value = map;
    }
  } finally {
    isLoadingVotes.value = false;
  }
};

/**
 * 【関数の役割】 自分自身の投票履歴を取得。未ログインなら即 return。
 */
const fetchMyVotes = async () => {
  if (!isLoggedIn.value) return;
  try {
    const res = await fetch(`${API_BASE}/api/tier-votes/mine`, {
      headers: authHeaders(),
    });
    if (res.ok) {
      const data: Array<{ title: string; difficultyName: string; vote: string }> = await res.json();
      const map = new Map<string, string>();
      for (const item of data) {
        map.set(`${item.title}|${item.difficultyName}`, item.vote);
      }
      myVotes.value = map;
    }
  } catch {
    // ignore
  }
};

// マウント時に 3 種類のデータを直列で取得する（依存はないが順序固定で予測可能性を上げる）
onMounted(async () => {
  await fetchAllVotes();
  await fetchCommentStats();
  await fetchMyVotes();
});

// ログイン状態が切り替わったら自分の投票を再取得／クリアする
watch(isLoggedIn, (val) => {
  if (val) fetchMyVotes();
  else myVotes.value = new Map();
});

/**
 * 【関数の役割】 "曲名[L]" の形式をパースして ANOTHER/LEGGENDARIA に分ける。
 * "[L]" サフィックスが LEGGENDARIA の印。
 */
const parseSong = (songTitle: string): { title: string; difficultyName: 'ANOTHER' | 'LEGGENDARIA' } => {
  if (songTitle.endsWith('[L]')) {
    return { title: songTitle.slice(0, -3), difficultyName: 'LEGGENDARIA' };
  }
  return { title: songTitle, difficultyName: 'ANOTHER' };
};

/** ランク名が未カテゴリ（まだ Tier が決まっていない）か判定 */
const isUncategorized = (rankName: string) => rankName.toLowerCase().includes('uncategorized');

/** 指定譜面の全員分の投票集計を取得。無ければ空オブジェクト */
const getVotes = (title: string, difficultyName: string): Record<string, number> => {
  return allVotes.value.get(`${title}|${difficultyName}`) ?? {};
};

/** 自分のこの譜面への投票。未投票なら null */
const getMyVote = (title: string, difficultyName: string): string | null => {
  return myVotes.value.get(`${title}|${difficultyName}`) ?? null;
};

/** 自分がこの譜面に投票済みか（他の人の票を見せるか判定するゲート） */
const hasVoted = (title: string, difficultyName: string): boolean => {
  return myVotes.value.has(`${title}|${difficultyName}`);
};

/** 未カテゴリ譜面の Tier 投票総数（全 Tier の合算） */
const getTotalTierVotes = (title: string, difficultyName: string): number => {
  const counts = getVotes(title, difficultyName);
  return TIER_OPTIONS.reduce((sum, t) => sum + (counts[t] ?? 0), 0);
};

/**
 * 【関数の役割】 未カテゴリ譜面の Tier 投票の内訳を Tier 昇順で返す。
 * 票が 0 の Tier は除外。`isTop` は最多得票 Tier に true（同票なら全員に true）。
 */
const getTierBreakdown = (title: string, difficultyName: string): Array<{ tier: string; count: number; isTop: boolean }> => {
  const counts = getVotes(title, difficultyName);
  const entries = TIER_OPTIONS
    .map(tier => ({ tier, count: counts[tier] ?? 0 }))
    .filter(e => e.count > 0);
  if (entries.length === 0) return [];
  const max = entries.reduce((m, e) => Math.max(m, e.count), 0);
  return entries.map(e => ({ ...e, isTop: e.count === max }));
};

/**
 * 【関数の役割】 PROMOTE/STAY/DEMOTE 投票を行う。同じ投票を再度押したら取り消し（トグル）。
 *
 * 処理の流れ:
 *  - 手順1: 未ログインなら何もしない。
 *  - 手順2: 同じ票の再クリックなら DELETE でサーバから削除し、ローカルの集計もデクリメント。
 *  - 手順3: 新規/変更なら POST で送信。旧票があればデクリメントしてから新票を+1。
 */
const castVote = async (title: string, difficultyName: string, voteType: string) => {
  if (!isLoggedIn.value) return;
  const key = `${title}|${difficultyName}`;
  const currentVote = myVotes.value.get(key) ?? null;
  const counts = { ...(allVotes.value.get(key) ?? {}) };

  if (currentVote === voteType) {
    const res = await fetch(
      `${API_BASE}/api/tier-votes?title=${encodeURIComponent(title)}&difficultyName=${encodeURIComponent(difficultyName)}`,
      { method: 'DELETE', headers: authHeaders() }
    );
    if (res.ok) {
      counts[currentVote] = Math.max(0, (counts[currentVote] ?? 0) - 1);
      allVotes.value.set(key, counts);
      myVotes.value.delete(key);
    }
  } else {
    const res = await fetch(`${API_BASE}/api/tier-votes`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ title, difficultyName, vote: voteType }),
    });
    if (res.ok) {
      if (currentVote) counts[currentVote] = Math.max(0, (counts[currentVote] ?? 0) - 1);
      counts[voteType] = (counts[voteType] ?? 0) + 1;
      allVotes.value.set(key, counts);
      myVotes.value.set(key, voteType);
    }
  }
};

/**
 * 【関数の役割】 未カテゴリ譜面への Tier 値投票。空文字 `''` 指定で削除。
 * PROMOTE/STAY/DEMOTE 用の `castVote` と分離されているが、動作はほぼ同じ（トグル＋書き換え）。
 */
const castTierVote = async (title: string, difficultyName: string, tier: string) => {
  if (!isLoggedIn.value) return;
  const key = `${title}|${difficultyName}`;
  const currentVote = myVotes.value.get(key) ?? null;
  const counts = { ...(allVotes.value.get(key) ?? {}) };

  if (!tier || tier === currentVote) {
    // 投票削除: 未選択を選んだ or 同じ Tier を再選択
    const res = await fetch(
      `${API_BASE}/api/tier-votes?title=${encodeURIComponent(title)}&difficultyName=${encodeURIComponent(difficultyName)}`,
      { method: 'DELETE', headers: authHeaders() }
    );
    if (res.ok) {
      if (currentVote) counts[currentVote] = Math.max(0, (counts[currentVote] ?? 0) - 1);
      allVotes.value.set(key, counts);
      myVotes.value.delete(key);
    }
  } else {
    const res = await fetch(`${API_BASE}/api/tier-votes`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ title, difficultyName, vote: tier }),
    });
    if (res.ok) {
      if (currentVote) counts[currentVote] = Math.max(0, (counts[currentVote] ?? 0) - 1);
      counts[tier] = (counts[tier] ?? 0) + 1;
      allVotes.value.set(key, counts);
      myVotes.value.set(key, tier);
    }
  }
};

/**
 * 検索クエリと最新コメント時刻でランクごとに並べ替えたリスト。
 *  - 手順1: クエリで曲名絞り込み。
 *  - 手順2: 同じランク内の曲を最新コメント降順で並べ替え（活発な議論を上に）。
 *  - 手順3: 曲が 0 件になったランクは表示しない。
 *  - 手順4: Uncategorized (未配置) ランクを先頭に出す。
 */
const filteredRanks = computed(() => {
  const query = searchQuery.value.trim().toLowerCase();
  const ranks = [...diffTableRanks.value].sort((a, b) => {
    const aUnc = isUncategorized(a.rank) ? 0 : 1;
    const bUnc = isUncategorized(b.rank) ? 0 : 1;
    return aUnc - bUnc;
  });
  return ranks
    .map(rank => {
      let filteredSongs = rank.songs;
      if (query) {
        filteredSongs = filteredSongs.filter(s => s.toLowerCase().includes(query));
      }

      // 最新コメント時刻の降順でソート（未コメントは 0 扱いで末尾へ）
      filteredSongs = [...filteredSongs].sort((a, b) => {
        const aParsed = parseSong(a);
        const bParsed = parseSong(b);
        const aStats = commentStats.value.get(`${aParsed.title}|${aParsed.difficultyName}`);
        const bStats = commentStats.value.get(`${bParsed.title}|${bParsed.difficultyName}`);

        const aTime = aStats ? (toJstDate(aStats.latest)?.getTime() ?? 0) : 0;
        const bTime = bStats ? (toJstDate(bStats.latest)?.getTime() ?? 0) : 0;

        if (aTime !== bTime) {
          return bTime - aTime;
        }
        return 0; // コメントなし or 同時刻なら元順序を維持
      });

      return {
        ...rank,
        songs: filteredSongs,
      };
    })
    .filter(rank => rank.songs.length > 0);
});

/** 自分が投票した譜面の総数（上部カウンタ表示用） */
const totalVotedCount = computed(() => myVotes.value.size);
</script>

<template>
  <div class="w-full">
    <!-- ページヘッダー: タイトルと説明 -->
    <div class="mb-6">
      <h1 class="text-2xl font-bold text-slate-900 dark:text-white mb-1">
        {{ t('nav.tierVoting') }}
      </h1>
      <p class="text-sm text-slate-500 dark:text-slate-400">
        {{ t('tierVoting.subtitle') }}
      </p>
    </div>

    <!-- 投票基準の案内文 -->
    <v-card class="mb-5" variant="tonal">
      <v-card-text>
      <p class="text-xs font-bold text-slate-500 dark:text-slate-400 mb-2">{{ t('tierVoting.criteriaTitle') }}</p>
      <ul class="space-y-1 text-sm text-slate-700 dark:text-slate-300">
        <li class="flex items-start gap-2">
          <span class="mt-0.5 shrink-0 text-slate-400">•</span>
          {{ t('tierVoting.criteria1') }}
        </li>
        <li class="flex items-start gap-2">
          <span class="mt-0.5 shrink-0 text-slate-400">•</span>
          {{ t('tierVoting.criteria2') }}
        </li>
      </ul>
      </v-card-text>
    </v-card>

    <!-- 凡例: ランク付き譜面で使う ↑ → ↓ の意味説明 -->
    <div class="mb-4 flex flex-wrap gap-2">
      <v-chip label variant="tonal" color="success" size="small">
        ↑ {{ t('tierVoting.promote') }}
      </v-chip>
      <v-chip label variant="tonal" color="primary" size="small">
        → {{ t('tierVoting.stay') }}
      </v-chip>
      <v-chip label variant="tonal" color="error" size="small">
        ↓ {{ t('tierVoting.demote') }}
      </v-chip>
    </div>

    <!-- 未ログイン警告: ログインしないと投票できない旨を表示 -->
    <v-alert v-if="!isLoggedIn" type="warning" class="mb-5">
      {{ t('tierVoting.loginHint') }}
    </v-alert>

    <!-- 投票済み曲数: ログイン済かつ1件以上投票があるときのみ表示 -->
    <v-alert v-if="isLoggedIn && totalVotedCount > 0" type="info" class="mb-5">
      {{ t('tierVoting.votedCount', { n: totalVotedCount }) }}
    </v-alert>

    <!-- 検索入力: 曲名部分一致 -->
    <div class="mb-6">
      <v-text-field
        v-model="searchQuery"
        type="text"
        :placeholder="t('tierVoting.searchPlaceholder')"
        :prepend-inner-icon="mdiMagnify"
        clearable
        hide-details
        class="w-full max-w-sm"
      />
    </div>

    <!-- ロード中スピナー -->
    <div v-if="isLoadingVotes" class="flex justify-center py-16">
      <v-progress-circular indeterminate color="primary" />
    </div>

    <!-- ランクごとのリスト: ランクヘッダ＋曲行を縦に並べる -->
    <div v-else class="space-y-6">
      <v-card
        v-for="rank in filteredRanks"
        :key="rank.rank"
        :color="isUncategorized(rank.rank) ? 'amber' : undefined"
        :variant="isUncategorized(rank.rank) ? 'outlined' : 'elevated'"
      >
        <!-- ランクヘッダ: ランク名・曲数・未カテゴリならTier選択ヒント -->
        <v-card-title class="flex items-center gap-3">
          <span>{{ rank.rank }}</span>
          <span class="text-xs font-normal text-slate-400 dark:text-slate-500">{{ rank.songs.length }}{{ t('tierVoting.songs') }}</span>
          <v-chip v-if="isUncategorized(rank.rank)" size="x-small" label color="amber-darken-2" variant="flat">
            {{ t('tierVoting.selectTierHint') }}
          </v-chip>
        </v-card-title>
        <v-divider />

        <!-- 曲の行: 行クリックでコメントモーダル。投票ボタンは stop で行のクリックを阻止 -->
        <div class="divide-y divide-slate-100 dark:divide-slate-700/60">
          <div
            v-for="songEntry in rank.songs"
            :key="songEntry"
            class="px-4 py-3 flex flex-col sm:flex-row sm:items-center gap-2 sm:gap-4 transition-colors group hover:bg-slate-50 dark:hover:bg-slate-700/30 cursor-pointer"
            @click="openCommentModal(parseSong(songEntry).title, parseSong(songEntry).difficultyName)"
          >
            <!-- 曲情報: 難易度バッジ＋曲名＋コメントバッジ -->
            <div class="flex items-center gap-2 flex-1 min-w-0 pr-4">
              <v-chip
                size="x-small"
                label
                variant="tonal"
                :color="parseSong(songEntry).difficultyName === 'LEGGENDARIA' ? 'purple' : 'red'"
                class="shrink-0"
              >
                {{ parseSong(songEntry).difficultyName === 'LEGGENDARIA' ? 'LEG' : 'ANO' }}
              </v-chip>
              <span class="font-semibold text-slate-900 dark:text-white text-sm truncate group-hover:text-blue-600 dark:group-hover:text-blue-400 transition-colors">
                {{ parseSong(songEntry).title }}
              </span>

              <!-- コメントバッジ／ボタン: コメント数があれば青、無ければグレー -->
              <v-btn
                size="x-small"
                :variant="commentStats.get(`${parseSong(songEntry).title}|${parseSong(songEntry).difficultyName}`) ? 'tonal' : 'text'"
                :color="commentStats.get(`${parseSong(songEntry).title}|${parseSong(songEntry).difficultyName}`) ? 'primary' : undefined"
                :prepend-icon="mdiMessageOutline"
                @click.stop="openCommentModal(parseSong(songEntry).title, parseSong(songEntry).difficultyName)"
                class="shrink-0 ml-1"
              >
                <template v-if="commentStats.get(`${parseSong(songEntry).title}|${parseSong(songEntry).difficultyName}`)">
                  {{ commentStats.get(`${parseSong(songEntry).title}|${parseSong(songEntry).difficultyName}`)!.count }} 件のスレッド
                </template>
                <template v-else>
                  スレッドを開く
                </template>
              </v-btn>
            </div>

            <!-- 未カテゴリ譜面: Tier 値をプルダウンで投票 + 全 Tier 票分布チップ
                 縦積みで select を上段に固定して、行ごとの横位置ズレを防ぐ -->
            <template v-if="isUncategorized(rank.rank)">
              <div class="flex flex-col items-end gap-1.5 shrink-0" @click.stop>
                <v-select
                  :model-value="getMyVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName) ?? ''"
                  :items="tierSelectItems"
                  item-title="title"
                  item-value="value"
                  :disabled="!isLoggedIn"
                  @update:model-value="(v: string) => castTierVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName, v ?? '')"
                  :color="getMyVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName) ? 'primary' : undefined"
                  :base-color="getMyVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName) ? 'primary' : undefined"
                  density="compact"
                  hide-details
                  class="w-32 flex-none"
                />

                <!-- Tier 票分布: 自分が投票済みのときだけ表示。未投票なら伏字プレースホルダ -->
                <div
                  v-if="hasVoted(parseSong(songEntry).title, parseSong(songEntry).difficultyName)
                    && getTierBreakdown(parseSong(songEntry).title, parseSong(songEntry).difficultyName).length > 0"
                  class="flex flex-wrap justify-end items-center gap-1"
                >
                  <v-chip
                    v-for="entry in getTierBreakdown(parseSong(songEntry).title, parseSong(songEntry).difficultyName)"
                    :key="entry.tier"
                    label
                    size="small"
                    :variant="entry.isTop ? 'tonal' : 'outlined'"
                    :color="entry.isTop ? 'primary' : undefined"
                  >
                    <span>{{ entry.tier }}</span>
                    <span class="ml-1">×{{ entry.count }}</span>
                  </v-chip>
                  <span class="text-[10px] text-slate-400 dark:text-slate-500 ml-1 whitespace-nowrap">
                    計{{ getTotalTierVotes(parseSong(songEntry).title, parseSong(songEntry).difficultyName) }}票
                  </span>
                </div>
                <span
                  v-else
                  class="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[11px] font-medium text-slate-400 dark:text-slate-500 bg-slate-100/60 dark:bg-slate-700/40 border border-dashed border-slate-300 dark:border-slate-600 whitespace-nowrap"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
                  {{ t('tierVoting.hiddenUntilVoted') }}
                </span>
              </div>
            </template>

            <!-- ランク付き譜面: 3種類のボタンで投票。選択中は背景色変化 -->
            <template v-else>
              <div class="flex items-center gap-1.5 shrink-0" @click.stop>
                <!-- 昇格（PROMOTE）ボタン: 票数も表示 -->
                <v-btn
                  size="small"
                  @click.stop="castVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName, 'PROMOTE')"
                  :disabled="!isLoggedIn"
                  :title="t('tierVoting.promote')"
                  color="success"
                  :variant="getMyVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName) === 'PROMOTE' ? 'flat' : 'outlined'"
                >
                  <span>↑</span>
                  <span class="ml-1" v-if="hasVoted(parseSong(songEntry).title, parseSong(songEntry).difficultyName)">{{ getVotes(parseSong(songEntry).title, parseSong(songEntry).difficultyName)['PROMOTE'] ?? 0 }}</span>
                  <span class="ml-1 opacity-50" v-else>?</span>
                </v-btn>
                <!-- 据え置き（STAY）ボタン -->
                <v-btn
                  size="small"
                  @click.stop="castVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName, 'STAY')"
                  :disabled="!isLoggedIn"
                  :title="t('tierVoting.stay')"
                  color="primary"
                  :variant="getMyVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName) === 'STAY' ? 'flat' : 'outlined'"
                >
                  <span>→</span>
                  <span class="ml-1" v-if="hasVoted(parseSong(songEntry).title, parseSong(songEntry).difficultyName)">{{ getVotes(parseSong(songEntry).title, parseSong(songEntry).difficultyName)['STAY'] ?? 0 }}</span>
                  <span class="ml-1 opacity-50" v-else>?</span>
                </v-btn>
                <!-- 降格（DEMOTE）ボタン -->
                <v-btn
                  size="small"
                  @click.stop="castVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName, 'DEMOTE')"
                  :disabled="!isLoggedIn"
                  :title="t('tierVoting.demote')"
                  color="error"
                  :variant="getMyVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName) === 'DEMOTE' ? 'flat' : 'outlined'"
                >
                  <span>↓</span>
                  <span class="ml-1" v-if="hasVoted(parseSong(songEntry).title, parseSong(songEntry).difficultyName)">{{ getVotes(parseSong(songEntry).title, parseSong(songEntry).difficultyName)['DEMOTE'] ?? 0 }}</span>
                  <span class="ml-1 opacity-50" v-else>?</span>
                </v-btn>
              </div>
            </template>
          </div>
        </div>
      </v-card>

      <div v-if="filteredRanks.length === 0" class="text-center py-16 text-slate-400 dark:text-slate-500 text-sm">
        {{ t('tierVoting.noResults') }}
      </div>
    </div>

    <!-- コメントモーダル: 行クリック or バッジクリックで開く。投稿後は統計を再取得 -->
    <TierCommentModal
      :show="showCommentModal"
      :title="activeCommentTitle"
      :difficultyName="activeCommentDiff"
      @close="showCommentModal = false"
      @update="fetchCommentStats"
    />
  </div>
</template>
