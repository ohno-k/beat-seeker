<template>
  <div class="w-full max-w-5xl mx-auto px-4 py-6 animate-fade-in">
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-xl font-black text-slate-800 dark:text-white">曲別順位</h1>
      <div class="flex items-center gap-2">
        <button
          @click="migrateBeatPt"
          :disabled="isMigrating"
          class="flex items-center gap-2 px-4 py-2 text-sm font-bold bg-emerald-500 hover:bg-emerald-600 text-white rounded-xl transition-colors disabled:opacity-50"
          title="既存ユーザーのtotalBeatPtをscore_history_logsから移行（初回のみ）"
        >
          {{ isMigrating ? '移行中...' : 'BeatPt移行' }}
        </button>
        <button
          @click="recalculate"
          :disabled="isRecalculating"
          class="flex items-center gap-2 px-4 py-2 text-sm font-bold bg-indigo-500 hover:bg-indigo-600 text-white rounded-xl transition-colors disabled:opacity-50"
          title="全プレイヤーの順位を再計算（数分かかる場合があります）"
        >
          <svg v-if="isRecalculating" class="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
          </svg>
          <svg v-else xmlns="http://www.w3.org/2000/svg" class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 7h6m0 10v-3m-3 3h.01M9 17h.01M9 14h.01M12 14h.01M15 11h.01M12 11h.01M9 11h.01M7 21h10a2 2 0 002-2V5a2 2 0 00-2-2H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
          </svg>
          {{ isRecalculating ? '計算中...' : '全員再計算' }}
        </button>
        <button
          @click="load"
          :disabled="isLoading"
          class="flex items-center gap-2 px-4 py-2 text-sm font-bold bg-amber-500 hover:bg-amber-600 text-white rounded-xl transition-colors disabled:opacity-50"
        >
          <svg v-if="isLoading" class="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
          </svg>
          <svg v-else xmlns="http://www.w3.org/2000/svg" class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
          </svg>
          更新
        </button>
      </div>
    </div>

    <!-- Recalculate message -->
    <div v-if="recalculateMsg" class="mb-4 px-4 py-3 rounded-xl bg-indigo-50 dark:bg-indigo-900/30 text-indigo-700 dark:text-indigo-300 text-sm font-bold">
      {{ recalculateMsg }}
    </div>

    <!-- Stats summary -->
    <div v-if="rows.length > 0" class="grid grid-cols-3 gap-3 mb-4">
      <div class="bg-white dark:bg-slate-800 rounded-xl p-4 border border-slate-100 dark:border-slate-700 text-center">
        <div class="text-2xl font-black text-slate-800 dark:text-white">{{ filteredRows.length }}</div>
        <div class="text-xs text-slate-500 dark:text-slate-400 mt-1">登録曲数</div>
      </div>
      <div class="bg-white dark:bg-slate-800 rounded-xl p-4 border border-slate-100 dark:border-slate-700 text-center">
        <div class="text-2xl font-black text-green-600 dark:text-green-400">{{ top3Count }}</div>
        <div class="text-xs text-slate-500 dark:text-slate-400 mt-1">3位以内</div>
      </div>
      <div class="bg-white dark:bg-slate-800 rounded-xl p-4 border border-slate-100 dark:border-slate-700 text-center">
        <div class="text-2xl font-black text-amber-500">{{ firstPlaceCount }}</div>
        <div class="text-xs text-slate-500 dark:text-slate-400 mt-1">1位</div>
      </div>
    </div>

    <!-- Filters -->
    <div v-if="rows.length > 0" class="flex flex-wrap gap-3 mb-4">
      <label v-for="lv in availableLevels" :key="lv" class="flex items-center gap-2 cursor-pointer select-none">
        <input type="checkbox" v-model="showLevels[lv]" class="w-4 h-4 rounded accent-amber-500" />
        <span class="text-sm font-bold text-slate-700 dark:text-slate-300">Lv.{{ lv }}</span>
      </label>
      <div class="w-px bg-slate-200 dark:bg-slate-700 mx-1"></div>
      <label class="flex items-center gap-2 cursor-pointer select-none">
        <input type="checkbox" v-model="showAnother" class="w-4 h-4 rounded accent-red-500" />
        <span class="text-sm font-bold text-red-600 dark:text-red-400">ANOTHER</span>
      </label>
      <label class="flex items-center gap-2 cursor-pointer select-none">
        <input type="checkbox" v-model="showLeggendaria" class="w-4 h-4 rounded accent-purple-500" />
        <span class="text-sm font-bold text-purple-600 dark:text-purple-400">LEGGENDARIA</span>
      </label>
    </div>

    <!-- Loading -->
    <div v-if="isLoading" class="flex justify-center py-20">
      <div class="w-10 h-10 border-4 border-amber-100 border-t-amber-500 rounded-full animate-spin"></div>
    </div>

    <!-- Empty -->
    <div v-else-if="rows.length === 0 && loaded" class="text-center py-20 text-slate-400 dark:text-slate-500">
      データがありません
    </div>

    <!-- Table -->
    <div v-else-if="filteredRows.length > 0" class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-100 dark:border-slate-700 overflow-hidden">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-slate-100 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/30">
            <th class="py-3 px-4 text-left font-bold text-slate-500 dark:text-slate-400 w-16">順位</th>
            <th class="py-3 px-4 text-left font-bold text-slate-500 dark:text-slate-400">曲名</th>
            <th class="py-3 px-4 text-center font-bold text-slate-500 dark:text-slate-400 w-32">難易度</th>
            <th class="py-3 px-4 text-right font-bold text-slate-500 dark:text-slate-400 w-32">順位 / 人数</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
          <tr
            v-for="row in filteredRows"
            :key="`${row.title}_${row.difficultyName}`"
            class="hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors"
          >
            <!-- Rank badge -->
            <td class="py-3 px-4">
              <span
                class="inline-flex items-center justify-center w-8 h-8 rounded-full text-sm font-black"
                :class="rankBadgeClass(row.rank)"
              >{{ row.rank }}</span>
            </td>
            <!-- Title -->
            <td class="py-3 px-4">
              <div class="font-semibold text-slate-800 dark:text-white leading-tight">{{ row.title }}</div>
            </td>
            <!-- Difficulty -->
            <td class="py-3 px-4 text-center">
              <span class="inline-block px-2 py-0.5 rounded text-xs font-bold" :class="diffClass(row.difficultyName)">
                Lv.{{ row.difficultyLevel }}
              </span>
              <span class="ml-1 inline-block px-2 py-0.5 rounded text-xs font-bold" :class="diffClass(row.difficultyName)">
                {{ row.difficultyName }}
              </span>
            </td>
            <!-- Rank / Total -->
            <td class="py-3 px-4 text-right font-mono font-bold text-slate-700 dark:text-slate-200">
              {{ row.rank }} <span class="text-xs font-normal text-slate-400 dark:text-slate-500">/ {{ row.total }}人</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Filtered empty -->
    <div v-else-if="rows.length > 0 && filteredRows.length === 0" class="text-center py-20 text-slate-400 dark:text-slate-500">
      条件に一致する曲がありません
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 管理者専用の「曲別順位」閲覧画面。
 * - ログイン中の管理者自身が各譜面で全体の何位にいるかの一覧を表示
 * - Lv8〜12 / ANOTHER / LEGGENDARIA のチェックボックスで絞り込み
 * - 1 位数・TOP3 数・総登録曲数を集計カードで表示
 * - 追加のメンテナンス操作：
 *     - totalBeatPt 移行（score_history_logs から user テーブルへ集計値を一度だけ移行）
 *     - 全員の順位再計算（サーバで非同期バックグラウンド処理、202 Accepted で即応答）
 */
import { ref, computed, onMounted } from 'vue';
import { useAuth } from '../composables/useAuth';

/** 曲単位の順位情報（1 曲 × 1 難易度 = 1 行）。 */
interface SongRankRow {
  title: string;
  difficultyName: string;
  difficultyLevel: number;
  rank: number;
  total: number;
}

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';
const { authHeaders } = useAuth();

/** サーバから取得した曲別順位の全件。 */
const rows = ref<SongRankRow[]>([]);
/** 順位取得 API 呼び出し中フラグ。 */
const isLoading = ref(false);
/** 全員再計算 API 呼び出し中フラグ。 */
const isRecalculating = ref(false);
/** 初回ロードが完了したか（空データ表示の切り分け用）。 */
const loaded = ref(false);

/** レベル絞り込み用チェックボックスの状態（Lv8〜12）。 */
const showLevels = ref<Record<number, boolean>>({ 8: true, 9: true, 10: true, 11: true, 12: true });
/** ANOTHER 譜面を表示するか。 */
const showAnother = ref(true);
/** LEGGENDARIA 譜面を表示するか。 */
const showLeggendaria = ref(true);

/** 【computed の役割】 実データに存在するレベルだけを昇順で返す（UI のチェックボックス表示用）。 */
const availableLevels = computed(() => {
  const lvs = new Set(rows.value.map(r => r.difficultyLevel));
  return [8, 9, 10, 11, 12].filter(lv => lvs.has(lv));
});

/**
 * 【computed の役割】 チェックボックス条件で絞り込んだ行リスト。
 * - Lv8〜12 以外（例: Lv7 以下）は常に表示
 * - ANOTHER / LEGGENDARIA は個別トグルで制御
 */
const filteredRows = computed(() =>
  rows.value.filter(r => {
    const lvInRange = [8, 9, 10, 11, 12].includes(r.difficultyLevel);
    if (lvInRange && !showLevels.value[r.difficultyLevel]) return false;
    if (r.difficultyName === 'ANOTHER' && !showAnother.value) return false;
    if (r.difficultyName === 'LEGGENDARIA' && !showLeggendaria.value) return false;
    return true;
  })
);

/** 【computed の役割】 フィルタ後の 1 位の数（上部サマリ表示用）。 */
const firstPlaceCount = computed(() => filteredRows.value.filter(r => r.rank === 1).length);
/** 【computed の役割】 フィルタ後の 3 位以内の数（TOP3 カード表示用）。 */
const top3Count = computed(() => filteredRows.value.filter(r => r.rank <= 3).length);

/**
 * 【関数の役割】 管理 API から現在の管理者自身の曲別順位を取得する。
 * 数値フィールドは Number() で再変換し、BigInt 由来の文字列や浮動小数を整数化する。
 */
const load = async () => {
  isLoading.value = true;
  try {
    const res = await fetch(`${API_BASE}/api/scores/admin-song-ranks`, { headers: authHeaders() });
    if (res.ok) {
      const data = await res.json();
      rows.value = data.map((d: any) => ({
        title: d.title,
        difficultyName: d.difficultyName,
        difficultyLevel: Number(d.difficultyLevel),
        rank: Number(d.rank),
        total: Number(d.total),
      }));
    }
  } catch {
    // エラーは静かに握りつぶす（UI 上はローディング解除のみ）。
  } finally {
    isLoading.value = false;
    loaded.value = true;
  }
};

/** 【関数の役割】 順位バッジの背景色クラスを返す（1 位=金、2 位=銀、3 位=銅、10 位以内=青、その他=灰）。 */
const rankBadgeClass = (rank: number) => {
  if (rank === 1) return 'bg-amber-400 text-white';
  if (rank === 2) return 'bg-slate-300 dark:bg-slate-500 text-white';
  if (rank === 3) return 'bg-amber-700 text-white';
  if (rank <= 10) return 'bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-300';
  return 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300';
};

/** 【関数の役割】 難易度名に応じた色クラスを返す（LEGGENDARIA=紫、ANOTHER=赤、その他=灰）。 */
const diffClass = (name: string) => {
  if (name === 'LEGGENDARIA') return 'bg-purple-100 dark:bg-purple-900/40 text-purple-700 dark:text-purple-300';
  if (name === 'ANOTHER') return 'bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-300';
  return 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300';
};

/** 再計算や移行の処理結果メッセージ（画面上部に表示される）。 */
const recalculateMsg = ref('');
/** BeatPt 移行 API 呼び出し中フラグ。 */
const isMigrating = ref(false);

/**
 * 【関数の役割】 既存ユーザーの totalBeatPt を score_history_logs から集計して user テーブルに書き戻す一回限りの移行バッチ。
 * 初回デプロイ時だけ管理者が手動実行することを想定（多重押下防止 + 確認ダイアログ付き）。
 */
const migrateBeatPt = async () => {
  if (isMigrating.value) return;
  if (!confirm('既存ユーザーのtotalBeatPtを移行します。初回デプロイ後に一度だけ実行してください。続けますか？')) return;
  isMigrating.value = true;
  try {
    const res = await fetch(`${API_BASE}/api/admin/migrate-user-beat-pt`, {
      method: 'POST',
      headers: authHeaders(),
    });
    const data = await res.json();
    recalculateMsg.value = data.message ?? '移行完了';
  } catch {
    recalculateMsg.value = '移行中にエラーが発生しました。';
  } finally {
    isMigrating.value = false;
  }
};

/**
 * 【関数の役割】 全プレイヤーの順位再計算をサーバに依頼する。
 * サーバ側は 202 Accepted を返して非同期バックグラウンドで処理するため、
 * クライアントは即座にメッセージを表示して再読込は手動任せ（負荷対策）。
 */
const recalculate = async () => {
  if (isRecalculating.value) return;
  isRecalculating.value = true;
  recalculateMsg.value = '';
  try {
    const res = await fetch(`${API_BASE}/api/scores/recalculate-song-ranks`, {
      method: 'POST',
      headers: authHeaders(),
    });
    if (res.status === 202) {
      recalculateMsg.value = 'バックグラウンドで計算を開始しました。数分後に「更新」ボタンで結果を確認してください。';
    }
  } catch {
    recalculateMsg.value = 'エラーが発生しました。';
  } finally {
    isRecalculating.value = false;
  }
};

// 初回マウント時に曲別順位を取得する。
onMounted(load);
</script>
