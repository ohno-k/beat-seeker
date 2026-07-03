<script setup lang="ts">
/**
 * 【Viewの役割】 曲別平均スコアレート一覧（プレイヤー全体の傾向比較）。
 *
 * 機能:
 *  - API `/api/scores/song-avg-score-rates` から全プレイヤーの曲別平均スコアレート・MAX- 割合・AAA 割合を取得。
 *  - 難易度表のアクティブランク＋ドラフトランク（管理者向け）を併記し、ランク変動案を可視化する。
 *  - 各列クリックでソート。MAX- 昇順で固定順位（#）を割り当て、難易度表ランク境界に赤線を引く。
 *  - 各行にコメントアイコン。クリックで `TierCommentModal` を開き、譜面ごとの投票コメントを閲覧可能。
 *
 * 依存:
 *  - `useGameData.diffTableRanks` — アクティブ難易度表。
 *  - `useAuth` — 認証ヘッダ（ドラフト取得用）。
 *  - API: `/api/scores/song-avg-score-rates`, `/api/admin/game-data/difficulty-table/draft`, `/api/tier-comments/stats`。
 *  - `TierCommentModal` — 譜面コメント閲覧/投稿モーダル。
 */
import { ref, computed, onMounted } from 'vue';
import { useGameData } from '../composables/useGameData';
import { useAuth } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';
import TierCommentModal from '../components/TierCommentModal.vue';

const { t } = useI18n();
const { diffTableRanks } = useGameData();
const { authHeaders, isLoggedIn } = useAuth();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

/** APIレスポンスの1行分。title＋difficultyName で一意 */
interface ScoreRateEntry {
  title: string;
  difficultyName: string;
  avgScoreRate: number;
  playerCount: number;
  maxMinusRate: number;
  maxMinusCount: number;
  aaaRate: number;
  aaaCount: number;
}

/** API取得中フラグ */
const isLoading = ref(true);
/** エラーメッセージ */
const errorMsg = ref('');
/** APIから取得した全データ */
const scoreRates = ref<ScoreRateEntry[]>([]);
/** 現在のソートキー。初期値は MAX- 割合 */
const sortKey = ref<'avgScoreRate' | 'title' | 'playerCount' | 'rank' | 'maxMinusRate' | 'aaaRate'>('maxMinusRate');
/** 現在のソート方向 */
const sortDir = ref<'asc' | 'desc'>('asc');

/** 難易度表から「曲名→ランク」の逆引きマップを生成 */
const rankMap = computed(() => {
  const map = new Map<string, string>();
  for (const rank of diffTableRanks.value) {
    for (const song of rank.songs) {
      map.set(song, rank.rank);
    }
  }
  return map;
});

/**
 * 【関数の役割】 行の曲＋難易度から難易度表ランクを引く。
 * LEGGENDARIA は難易度表で `[L]` サフィックスが付くため、キーを合成して引く。
 */
function getRank(row: ScoreRateEntry): string {
  const entry = row.difficultyName === 'LEGGENDARIA' ? row.title + '[L]' : row.title;
  return rankMap.value.get(entry) || '-';
}

/** ドラフト難易度表（管理者用）: 曲→ドラフトランクのマップ。未ログイン時は空 */
const draftRankMap = ref(new Map<string, string>());

/**
 * 【関数の役割】 ドラフトのランクを引く（ドラフトに無ければアクティブへフォールバック）。
 */
function getDraftRank(row: ScoreRateEntry): string {
  const entry = row.difficultyName === 'LEGGENDARIA' ? row.title + '[L]' : row.title;
  return draftRankMap.value.get(entry) || rankMap.value.get(entry) || '-';
}

/**
 * 【関数の役割】 アクティブ→ドラフトの変更差分を人間向けの短い文字列で返す。
 *  - ドラフトに無ければ空文字（表示しない）。
 *  - アクティブに存在せずドラフトだけにあれば「新規 ◯◯」。
 *  - 同じランクなら空文字。違うなら「旧 → 新」を返す。
 */
function getDraft(row: ScoreRateEntry): string {
  const entry = row.difficultyName === 'LEGGENDARIA' ? row.title + '[L]' : row.title;
  const draftRank = draftRankMap.value.get(entry);
  if (!draftRank) return '';
  const activeRank = rankMap.value.get(entry);
  if (!activeRank) return `新規 ${draftRank}`;
  if (activeRank === draftRank) return '';
  return `${activeRank} → ${draftRank}`;
}

// 画面マウント時に3つのデータを取得する。
//   手順1: 曲別平均スコアレート（メインデータ）
//   手順2: コメント統計（件数マップ）
//   手順3: ドラフト難易度表（ログイン時のみ、404等は無視）
onMounted(async () => {
  try {
    const res = await fetch(`${API_BASE}/api/scores/song-avg-score-rates`, {
      headers: authHeaders(),
    });
    if (res.ok) {
      scoreRates.value = await res.json();
    } else {
      errorMsg.value = `APIエラー: ${res.status}`;
    }
  } catch (e: any) {
    errorMsg.value = `通信エラー: ${e.message}`;
  } finally {
    isLoading.value = false;
  }

  fetchCommentStats();

  // ドラフト難易度表を取得（ログイン時のみ、失敗しても無視）
  if (isLoggedIn.value) {
    try {
      const res = await fetch(`${API_BASE}/api/admin/game-data/difficulty-table/draft`, {
        headers: authHeaders(),
      });
      if (res.ok) {
        const data: { ranks: { rank: string; songs: string[] }[] } = await res.json();
        const map = new Map<string, string>();
        for (const r of data.ranks) {
          for (const song of r.songs) {
            map.set(song, r.rank);
          }
        }
        draftRankMap.value = map;
      }
    } catch {}
  }
});

/**
 * 【関数の役割】 カラム見出しクリック時のソート切替。
 * 同じキーなら昇降反転、別キーなら昇順から開始。
 */
function toggleSort(key: typeof sortKey.value) {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc';
  } else {
    sortKey.value = key;
    sortDir.value = 'asc';
  }
}

/**
 * MAX-割合の昇順に並べ、その順位を「固定順位」としてマップ化する。
 * 表示の左端「#」列はソート方向に依らず常にこの順位を示す。
 */
const fixedRankMap = computed(() => {
  const map = new Map<string, number>();
  const sorted = [...scoreRates.value].sort((a, b) => a.maxMinusRate - b.maxMinusRate);
  sorted.forEach((row, idx) => {
    map.set(`${row.title}_${row.difficultyName}`, idx + 1);
  });
  return map;
});

/** 行ごとの固定順位（# 列用）を引く */
function getFixedRank(row: ScoreRateEntry): number {
  return fixedRankMap.value.get(`${row.title}_${row.difficultyName}`) || 0;
}

/**
 * 現在のソートキー・方向を適用した表示用データ。
 * プライマリキーで同順位の場合は MAX- 昇順をタイブレーカに使う。
 */
const sortedData = computed(() => {
  const data = [...scoreRates.value];
  const dir = sortDir.value === 'asc' ? 1 : -1;
  data.sort((a, b) => {
    let primary = 0;
    switch (sortKey.value) {
      case 'avgScoreRate':
        primary = (a.avgScoreRate - b.avgScoreRate) * dir;
        break;
      case 'title':
        primary = a.title.localeCompare(b.title) * dir;
        break;
      case 'playerCount':
        primary = (a.playerCount - b.playerCount) * dir;
        break;
      case 'rank':
        // ランク列は数値化して比較（"12.5" 等を parseFloat）
        primary = ((parseFloat(getRank(a)) || 0) - (parseFloat(getRank(b)) || 0)) * dir;
        break;
      case 'maxMinusRate':
        primary = (a.maxMinusRate - b.maxMinusRate) * dir;
        break;
      case 'aaaRate':
        primary = (a.aaaRate - b.aaaRate) * dir;
        break;
    }
    if (primary !== 0) return primary;
    // タイブレーカ: MAX- 割合の昇順
    return a.maxMinusRate - b.maxMinusRate;
  });
  return data;
});

/**
 * 【関数の役割】 ランクでソート中に限り、ランクの境界行に赤線を引くか判定する。
 * 同じランクの塊を視覚的に区切るためのヘルパー。
 */
function hasRedLine(idx: number): boolean {
  if (idx === 0) return false;
  if (sortKey.value === 'rank') {
    return getRank(sortedData.value[idx - 1]) !== getRank(sortedData.value[idx]);
  }
  return false;
}

/** ソートアイコン文字。未選択は ↕、昇順 ↑、降順 ↓ */
function sortIcon(key: string): string {
  if (sortKey.value !== key) return '↕';
  return sortDir.value === 'asc' ? '↑' : '↓';
}

// ===== コメント関連 =====
/** 曲＋難易度 → コメント件数 のマップ（バッジ表示に使用） */
const commentStats = ref(new Map<string, number>());
/** モーダル表示フラグ */
const commentModalShow = ref(false);
/** モーダルに表示中の曲名 */
const commentModalTitle = ref('');
/** モーダルに表示中の難易度名 */
const commentModalDiff = ref('');

/** commentStats のキー生成。曲名にパイプが含まれないことを前提としている */
function getCommentKey(title: string, difficultyName: string): string {
  return `${title}|${difficultyName}`;
}

/** 行に対するコメント件数を取得。未取得・無コメントは 0 */
function getCommentCount(row: ScoreRateEntry): number {
  return commentStats.value.get(getCommentKey(row.title, row.difficultyName)) || 0;
}

/** 行のコメントアイコンをクリックされたらモーダルを開く */
function openCommentModal(row: ScoreRateEntry) {
  commentModalTitle.value = row.title;
  commentModalDiff.value = row.difficultyName;
  commentModalShow.value = true;
}

/**
 * 【関数の役割】 コメント統計（全曲のコメント件数マップ）を取得してキャッシュする。
 * 投稿後の @update イベントで再呼び出しされ、件数バッジを最新化する。
 */
async function fetchCommentStats() {
  try {
    const res = await fetch(`${API_BASE}/api/tier-comments/stats`);
    if (res.ok) {
      const data: Array<{ title: string; difficultyName: string; commentCount: number }> = await res.json();
      const map = new Map<string, number>();
      for (const row of data) {
        map.set(getCommentKey(row.title, row.difficultyName), row.commentCount);
      }
      commentStats.value = map;
    }
  } catch {}
}
</script>

<template>
  <!-- 画面全体: カード型レイアウトで見出し＋表を配置 -->
  <div class="space-y-6">
    <!-- メインカード: 曲別平均スコアレート一覧 -->
    <div class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 p-6">
      <h2 class="text-xl font-bold text-slate-900 dark:text-white mb-2">曲別平均スコアレート</h2>
      <p class="text-sm text-slate-500 dark:text-slate-400 mb-6">
        全プレイヤーの平均スコアレート（ANOTHER+LEGGENDARIA、☆11+☆12）
      </p>

      <!-- ローディング中: 青スピナー -->
      <div v-if="isLoading" class="flex items-center justify-center py-12">
        <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
        <span class="ml-3 text-sm text-slate-500">読み込み中...</span>
      </div>

      <!-- エラー時: 赤文字でメッセージ -->
      <div v-else-if="errorMsg" class="text-red-500 text-sm py-4">{{ errorMsg }}</div>

      <!-- 通常時: 件数表示＋ソート可能テーブル -->
      <template v-else>
        <p class="text-sm text-slate-500 mb-4">{{ scoreRates.length }}曲</p>

        <div class="overflow-x-auto rounded-md border border-slate-200 dark:border-slate-700">
          <table class="w-full text-sm">
            <thead>
              <tr class="bg-slate-50 dark:bg-slate-700/50 border-b border-slate-200 dark:border-slate-600">
                <th class="text-left px-3 py-2.5 font-bold text-slate-600 dark:text-slate-300 w-8">#</th>
                <th
                  class="text-left px-3 py-2.5 font-bold text-slate-600 dark:text-slate-300 cursor-pointer hover:text-blue-600 select-none"
                  @click="toggleSort('title')"
                >曲名 {{ sortIcon('title') }}</th>
                <th
                  class="text-center px-3 py-2.5 font-bold text-slate-600 dark:text-slate-300 w-20 cursor-pointer hover:text-blue-600 select-none"
                  @click="toggleSort('rank')"
                >難易度 {{ sortIcon('rank') }}</th>
                <th
                  class="text-center px-3 py-2.5 font-bold text-slate-600 dark:text-slate-300 w-28 cursor-pointer hover:text-blue-600 select-none"
                  @click="toggleSort('avgScoreRate')"
                >平均レート {{ sortIcon('avgScoreRate') }}</th>
                <th
                  class="text-center px-3 py-2.5 font-bold text-amber-600 dark:text-amber-400 w-32 cursor-pointer hover:text-amber-700 dark:hover:text-amber-300 select-none bg-amber-50/50 dark:bg-amber-900/20"
                  @click="toggleSort('maxMinusRate')"
                >MAX- {{ sortIcon('maxMinusRate') }}</th>
                <th
                  class="text-center px-3 py-2.5 font-bold text-emerald-600 dark:text-emerald-400 w-32 cursor-pointer hover:text-emerald-700 dark:hover:text-emerald-300 select-none bg-emerald-50/50 dark:bg-emerald-900/20"
                  @click="toggleSort('aaaRate')"
                >AAA {{ sortIcon('aaaRate') }}</th>
                <th
                  class="text-center px-3 py-2.5 font-bold text-slate-600 dark:text-slate-300 w-20 cursor-pointer hover:text-blue-600 select-none"
                  @click="toggleSort('playerCount')"
                >人数 {{ sortIcon('playerCount') }}</th>
                <th class="text-center px-3 py-2.5 font-bold text-slate-600 dark:text-slate-300 w-16">コメ</th>
                <th class="text-center px-3 py-2.5 font-bold text-slate-600 dark:text-slate-300 w-28">ドラフト</th>
              </tr>
            </thead>
            <!-- 各行: ランク境界で赤線（ランクソート時のみ）。セル内容はスコアレート・MAX-・AAA 等 -->
            <tbody>
              <tr
                v-for="(row, idx) in sortedData"
                :key="`${row.title}_${row.difficultyName}`"
                class="hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors"
                :class="hasRedLine(idx) ? 'border-t-2 border-t-red-500 border-b border-b-slate-100 dark:border-b-slate-700/50' : 'border-b border-slate-100 dark:border-slate-700/50'"
              >
                <td class="px-3 py-2 text-slate-400 text-xs">{{ getFixedRank(row) }}</td>
                <td class="px-3 py-2">
                  <span class="font-medium text-slate-800 dark:text-slate-200">{{ row.title }}</span>
                  <span v-if="row.difficultyName === 'LEGGENDARIA'" class="ml-1 text-[10px] font-bold text-orange-500 bg-orange-50 dark:bg-orange-900/30 px-1 rounded">[L]</span>
                </td>
                <td class="px-3 py-2 text-center font-mono text-slate-700 dark:text-slate-300">{{ getRank(row) }}</td>
                <td class="px-3 py-2 text-center font-mono font-bold text-slate-700 dark:text-slate-300">{{ row.avgScoreRate.toFixed(2) }}%</td>
                <td class="px-3 py-2 text-center font-mono font-bold bg-amber-50/50 dark:bg-amber-900/20 whitespace-nowrap"
                    :class="row.maxMinusRate >= 50 ? 'text-amber-600 dark:text-amber-400' : row.maxMinusRate >= 20 ? 'text-amber-500 dark:text-amber-500' : 'text-slate-500 dark:text-slate-400'"
                >{{ row.maxMinusRate.toFixed(1) }}% <span class="text-slate-400 dark:text-slate-500 font-normal">({{ row.maxMinusCount }})</span></td>
                <td class="px-3 py-2 text-center font-mono font-bold bg-emerald-50/50 dark:bg-emerald-900/20 whitespace-nowrap"
                    :class="row.aaaRate >= 50 ? 'text-emerald-600 dark:text-emerald-400' : row.aaaRate >= 20 ? 'text-emerald-500 dark:text-emerald-500' : 'text-slate-500 dark:text-slate-400'"
                >{{ row.aaaRate.toFixed(1) }}% <span class="text-slate-400 dark:text-slate-500 font-normal">({{ row.aaaCount }})</span></td>
                <td class="px-3 py-2 text-center text-slate-500 dark:text-slate-400">{{ row.playerCount }}</td>
                <td class="px-3 py-2 text-center">
                  <button
                    @click="openCommentModal(row)"
                    class="inline-flex items-center gap-0.5 text-xs hover:bg-blue-50 dark:hover:bg-blue-900/30 px-1.5 py-0.5 rounded transition-colors"
                    :class="getCommentCount(row) > 0 ? 'text-blue-600 dark:text-blue-400 font-bold' : 'text-slate-400 dark:text-slate-500'"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
                    <span v-if="getCommentCount(row) > 0">{{ getCommentCount(row) }}</span>
                  </button>
                </td>
                <td class="px-3 py-2 text-center text-xs">
                  <span v-if="getDraft(row)" class="px-1.5 py-0.5 rounded bg-purple-50 dark:bg-purple-900/30 text-purple-600 dark:text-purple-300 font-bold">{{ getDraft(row) }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>
  </div>

  <!-- 譜面コメントモーダル: 行のコメントアイコンクリックで開く。投稿後は統計を再取得 -->
  <TierCommentModal
    :show="commentModalShow"
    :title="commentModalTitle"
    :difficulty-name="commentModalDiff"
    @close="commentModalShow = false"
    @update="fetchCommentStats()"
  />
</template>
