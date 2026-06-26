<template>
  <div class="min-h-screen bg-slate-50 dark:bg-slate-900 text-slate-800 dark:text-slate-100 transition-colors">
    <!-- ヘッダ（大会ブランディング） -->
    <header class="bg-gradient-to-br from-indigo-600 via-violet-600 to-fuchsia-600 text-white shadow-lg">
      <div class="max-w-5xl mx-auto px-4 py-8 sm:py-10">
        <p class="text-xs sm:text-sm font-semibold tracking-widest text-white/80 uppercase">beat-seeker 特設ページ</p>
        <h1 class="mt-1 text-3xl sm:text-4xl font-black tracking-tight">きんじょー杯</h1>
        <p class="mt-2 text-sm sm:text-base text-white/90">参加者一覧 ／ ドラフト選考 参考データ</p>
      </div>
    </header>

    <main class="max-w-5xl mx-auto px-4 py-6 sm:py-8">
      <!-- 未ログイン: ログインへ誘導 -->
      <div v-if="accessState === 'unauthorized'" class="max-w-md mx-auto text-center py-16">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-12 w-12 mx-auto text-slate-300 dark:text-slate-600 mb-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
        </svg>
        <h2 class="text-lg font-bold text-slate-700 dark:text-slate-200">ログインが必要です</h2>
        <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">
          このページはきんじょー杯の選考関係者のみ閲覧できます。<br />beat-seeker にログインしてからアクセスしてください。
        </p>
        <a href="/" class="inline-block mt-5 px-5 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-bold rounded-lg transition-colors">
          トップページでログイン
        </a>
      </div>

      <!-- ログイン済みだが権限なし -->
      <div v-else-if="accessState === 'forbidden'" class="max-w-md mx-auto text-center py-16">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-12 w-12 mx-auto text-slate-300 dark:text-slate-600 mb-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636" />
        </svg>
        <h2 class="text-lg font-bold text-slate-700 dark:text-slate-200">閲覧権限がありません</h2>
        <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">
          このページはきんじょー杯の選考関係者のみ閲覧できます。<br />
          閲覧が必要な場合は主催者までご連絡ください。
        </p>
      </div>

      <!-- 閲覧可能: 通常表示 -->
      <template v-else>
      <!-- ツールバー: 件数 + (管理者のみ) 追加ボタン -->
      <div class="flex items-center justify-between gap-3 mb-4">
        <p class="text-sm text-slate-500 dark:text-slate-400">
          <span class="font-bold text-slate-700 dark:text-slate-200">{{ participants.length }}</span> 名の参加者
          <span class="hidden sm:inline">（総合力 Beat-Pt 降順）</span>
        </p>
        <button
          v-if="isAdmin"
          @click="openAddModal"
          class="inline-flex items-center gap-1.5 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-bold rounded-lg shadow-sm transition-colors"
        >
          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
            <path fill-rule="evenodd" d="M10 5a1 1 0 011 1v3h3a1 1 0 110 2h-3v3a1 1 0 11-2 0v-3H6a1 1 0 110-2h3V6a1 1 0 011-1z" clip-rule="evenodd" />
          </svg>
          参加者を追加
        </button>
      </div>

      <!-- ローディング -->
      <div v-if="isLoading && participants.length === 0" class="flex flex-col items-center justify-center py-20">
        <div class="w-8 h-8 border-4 border-indigo-200 border-t-indigo-600 rounded-full animate-spin mb-4"></div>
        <p class="text-slate-500 font-medium">参加者データを取得中...</p>
      </div>

      <!-- エラー -->
      <div v-else-if="loadError" class="bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-300 p-4 rounded-xl border border-red-200 dark:border-red-800">
        {{ loadError }}
      </div>

      <!-- 空状態 -->
      <div v-else-if="participants.length === 0" class="text-center py-20">
        <p class="text-slate-500 dark:text-slate-400 font-medium">まだ参加者が登録されていません。</p>
        <p v-if="isAdmin" class="mt-2 text-sm text-slate-400">右上の「参加者を追加」から登録してください。</p>
      </div>

      <!-- 参加者テーブル -->
      <div v-else class="overflow-hidden rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 shadow-sm">
        <table class="w-full text-sm">
          <thead>
            <tr class="bg-slate-50 dark:bg-slate-900/50 text-slate-500 dark:text-slate-400 text-xs uppercase tracking-wider">
              <th class="px-3 sm:px-4 py-3 text-center font-semibold w-12">#</th>
              <th class="px-3 sm:px-4 py-3 text-left font-semibold">DJ名</th>
              <th class="px-3 sm:px-4 py-3 text-right font-semibold">総合力</th>
              <th class="px-3 sm:px-4 py-3 text-center font-semibold hidden sm:table-cell">段位</th>
              <th class="px-3 sm:px-4 py-3 text-center font-semibold hidden sm:table-cell">アリーナ</th>
              <th v-if="isAdmin" class="px-3 sm:px-4 py-3 text-center font-semibold w-12"></th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100 dark:divide-slate-700">
            <tr
              v-for="(p, idx) in participants"
              :key="p.id"
              class="hover:bg-slate-50 dark:hover:bg-slate-700/40 transition-colors"
            >
              <!-- 順位 -->
              <td class="px-3 sm:px-4 py-3 text-center">
                <span :class="rankBadgeClass(idx)">{{ idx + 1 }}</span>
              </td>
              <!-- DJ名（詳細ページへのリンク） -->
              <td class="px-3 sm:px-4 py-3">
                <a
                  :href="`/user/${p.userId}`"
                  class="font-bold text-indigo-600 dark:text-indigo-400 hover:underline break-all"
                >{{ p.displayName || '名無し' }}</a>
                <div class="flex items-center gap-2 mt-0.5 sm:hidden">
                  <span v-if="p.danRank" class="px-1.5 py-0.5 bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400 text-[10px] font-bold rounded">{{ p.danRank }}</span>
                  <span v-if="p.arenaRank" class="px-1.5 py-0.5 bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400 text-[10px] font-bold rounded">{{ p.arenaRank }}</span>
                </div>
                <p v-if="p.lastUploadedAt" class="text-[11px] text-slate-400 mt-0.5">更新: {{ formatDate(p.lastUploadedAt) }}</p>
              </td>
              <!-- 総合力 -->
              <td class="px-3 sm:px-4 py-3 text-right font-mono font-bold text-slate-700 dark:text-slate-200 whitespace-nowrap">
                {{ formatBeatPt(p.totalBeatPt) }}
              </td>
              <!-- 段位 -->
              <td class="px-3 sm:px-4 py-3 text-center hidden sm:table-cell">
                <span v-if="p.danRank" class="px-2 py-0.5 bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400 text-xs font-bold rounded">{{ p.danRank }}</span>
                <span v-else class="text-slate-300 dark:text-slate-600">—</span>
              </td>
              <!-- アリーナ -->
              <td class="px-3 sm:px-4 py-3 text-center hidden sm:table-cell">
                <span v-if="p.arenaRank" class="px-2 py-0.5 bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400 text-xs font-bold rounded">{{ p.arenaRank }}</span>
                <span v-else class="text-slate-300 dark:text-slate-600">—</span>
              </td>
              <!-- 削除（管理者のみ） -->
              <td v-if="isAdmin" class="px-3 sm:px-4 py-3 text-center">
                <button
                  @click="handleRemove(p)"
                  :disabled="removingId === p.id"
                  class="text-slate-400 hover:text-red-600 dark:hover:text-red-400 transition-colors disabled:opacity-40"
                  title="名簿から削除"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                    <path fill-rule="evenodd" d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z" clip-rule="evenodd" />
                  </svg>
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <p class="mt-4 text-[11px] text-slate-400 dark:text-slate-500">
        ※ 総合力 (Beat-Pt) は beat-seeker の総合実力指標です。DJ名をタップすると各参加者の詳細データを確認できます。
      </p>
      </template>
    </main>

    <!-- ============ 参加者追加モーダル（管理者のみ） ============ -->
    <Teleport to="body">
      <div
        v-if="showAddModal"
        class="fixed inset-0 z-[100] bg-slate-900/60 dark:bg-slate-950/80 flex items-center justify-center p-4 backdrop-blur-sm"
        @click.self="showAddModal = false"
      >
        <div class="bg-white dark:bg-slate-900 w-full max-w-lg rounded-2xl shadow-2xl flex flex-col overflow-hidden max-h-[85vh] border border-slate-200 dark:border-slate-800">
          <div class="p-5 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between shrink-0">
            <h2 class="text-lg font-bold text-slate-800 dark:text-white">参加者を追加</h2>
            <button @click="showAddModal = false" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 p-1.5 -mr-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
                <path fill-rule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clip-rule="evenodd" />
              </svg>
            </button>
          </div>

          <div class="p-4 border-b border-slate-200 dark:border-slate-800 shrink-0">
            <input
              v-model="userSearch"
              type="text"
              placeholder="DJ名 または IIDX ID で検索"
              class="w-full px-3 py-2 rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <p v-if="addError" class="mt-2 text-xs text-red-600 dark:text-red-400">{{ addError }}</p>
          </div>

          <div class="flex-1 overflow-y-auto p-2 bg-slate-50 dark:bg-slate-900/50">
            <div v-if="loadingUsers" class="flex items-center justify-center py-10 text-slate-500 text-sm">
              <div class="w-5 h-5 border-2 border-indigo-200 border-t-indigo-600 rounded-full animate-spin mr-2"></div>
              ユーザー一覧を取得中...
            </div>
            <div v-else-if="usersError" class="p-4 text-sm text-red-600 dark:text-red-400">{{ usersError }}</div>
            <div v-else-if="filteredCandidates.length === 0" class="p-6 text-center text-sm text-slate-400">
              該当するユーザーがいません。
            </div>
            <ul v-else class="space-y-1">
              <li
                v-for="u in filteredCandidates"
                :key="u.id"
                class="flex items-center justify-between gap-3 px-3 py-2.5 rounded-lg bg-white dark:bg-slate-800 border border-slate-100 dark:border-slate-700"
              >
                <div class="flex items-center gap-2 min-w-0">
                  <span class="font-bold text-slate-800 dark:text-white truncate">{{ u.displayName || '名無し' }}</span>
                  <span class="text-[11px] text-slate-400 font-mono shrink-0">{{ u.iidxId }}</span>
                  <span v-if="u.danRank" class="px-1.5 py-0.5 bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400 text-[10px] font-bold rounded shrink-0">{{ u.danRank }}</span>
                </div>
                <button
                  @click="handleAdd(u)"
                  :disabled="addingId === u.id"
                  class="px-3 py-1 bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-bold rounded-md transition-colors disabled:opacity-50 shrink-0"
                >
                  {{ addingId === u.id ? '追加中...' : '追加' }}
                </button>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
/**
 * 【ビューの役割】「きんじょー杯」特設ページ（/kinjocup）。
 *
 * - 誰でも閲覧できる読み取り専用の参加者一覧（総合力 Beat-Pt 降順）。各行から /user/:userId の詳細へリンク。
 * - 管理者がログイン中のときだけ「参加者を追加 / 削除」の GUI を表示する。
 *   追加は既存ユーザーを検索して名簿に登録する方式（参加者は beat-seeker ユーザーであることが前提）。
 *
 * App.vue が `/kinjocup` パスを検知してこのビューを単独描画する（サイドバー等は描画しない）。
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { useKinjoCup, KinjoCupAccessError, type KinjoCupParticipant } from '../composables/useKinjoCup';
import { useScores } from '../composables/useScores';
import { useAdmin } from '../composables/useAdmin';

const { isLoading, fetchParticipants, addParticipant, removeParticipant } = useKinjoCup();
const { fetchAllUsers } = useScores();
const { isAdmin } = useAdmin();

/** 参加者一覧（サーバから総合力降順で返る）。 */
const participants = ref<KinjoCupParticipant[]>([]);
/** 一覧取得エラー文言。 */
const loadError = ref('');
/** 削除中の参加者エントリ ID（多重押下防止）。 */
const removingId = ref<number | null>(null);
/** 閲覧アクセス状態。ok=表示 / unauthorized=未ログイン / forbidden=権限なし / error=その他失敗。 */
const accessState = ref<'ok' | 'unauthorized' | 'forbidden' | 'error'>('ok');

/** 参加者一覧を取得して participants に反映する。401/403 はアクセス状態に振り分ける。 */
const loadParticipants = async () => {
  loadError.value = '';
  try {
    participants.value = await fetchParticipants();
    accessState.value = 'ok';
  } catch (e: any) {
    if (e instanceof KinjoCupAccessError) {
      accessState.value = e.code; // 'unauthorized' | 'forbidden'
    } else {
      accessState.value = 'error';
      loadError.value = e?.message || '参加者の取得に失敗しました。';
    }
  }
};

// ---------- 追加モーダル ----------
/** 追加モーダルの開閉。 */
const showAddModal = ref(false);
/** 全ユーザー候補（管理者 API から取得）。 */
const allUsers = ref<any[]>([]);
/** ユーザー一覧取得中フラグ。 */
const loadingUsers = ref(false);
/** ユーザー一覧取得エラー。 */
const usersError = ref('');
/** 検索文字列。 */
const userSearch = ref('');
/** 追加処理中のユーザー ID。 */
const addingId = ref<number | null>(null);
/** 追加処理のエラー文言。 */
const addError = ref('');

/** 既に名簿に登録済みのユーザー ID 集合（候補から除外するため）。 */
const registeredUserIds = computed(() => new Set(participants.value.map(p => p.userId)));

/** 検索 + 登録済み除外を適用した追加候補（DJ名昇順）。 */
const filteredCandidates = computed(() => {
  const q = userSearch.value.trim().toLowerCase();
  return allUsers.value
    .filter(u => !registeredUserIds.value.has(u.id))
    .filter(u => {
      if (!q) return true;
      return (u.displayName || '').toLowerCase().includes(q) || (u.iidxId || '').toLowerCase().includes(q);
    })
    .sort((a, b) => (a.displayName ?? '').localeCompare(b.displayName ?? '', 'ja'))
    .slice(0, 100);
});

/** 追加モーダルを開き、初回のみ全ユーザーを取得する。 */
const openAddModal = async () => {
  showAddModal.value = true;
  addError.value = '';
  if (allUsers.value.length > 0 || loadingUsers.value) return;
  loadingUsers.value = true;
  usersError.value = '';
  try {
    allUsers.value = await fetchAllUsers();
  } catch (e: any) {
    usersError.value = e?.message || 'ユーザー一覧の取得に失敗しました。';
  } finally {
    loadingUsers.value = false;
  }
};

/** 候補ユーザーを名簿に追加する。成功したら一覧を更新（候補からも自動で消える）。 */
const handleAdd = async (u: any) => {
  addingId.value = u.id;
  addError.value = '';
  try {
    await addParticipant(u.id);
    await loadParticipants();
  } catch (e: any) {
    addError.value = `${u.displayName || u.iidxId} の追加に失敗: ${e?.message || ''}`;
  } finally {
    addingId.value = null;
  }
};

/** 参加者を名簿から削除する（確認あり）。 */
const handleRemove = async (p: KinjoCupParticipant) => {
  if (!confirm(`${p.displayName || '名無し'} を参加者名簿から削除しますか？`)) return;
  removingId.value = p.id;
  try {
    await removeParticipant(p.id);
    participants.value = participants.value.filter(x => x.id !== p.id);
  } catch (e: any) {
    alert(e?.message || '削除に失敗しました。');
  } finally {
    removingId.value = null;
  }
};

// ---------- 表示ヘルパー ----------
/** 総合力 Beat-Pt を見やすく整形する。 */
const formatBeatPt = (v: number): string =>
  (v ?? 0).toLocaleString(undefined, { maximumFractionDigits: 1 });

/** ISO 日時を YYYY/MM/DD 表記にする。 */
const formatDate = (iso: string): string => {
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}`;
};

/** 上位 3 名に金銀銅のバッジ装飾を付ける。 */
const rankBadgeClass = (idx: number): string => {
  const base = 'inline-flex items-center justify-center w-7 h-7 rounded-full text-xs font-black';
  if (idx === 0) return `${base} bg-amber-400 text-amber-900`;
  if (idx === 1) return `${base} bg-slate-300 text-slate-700`;
  if (idx === 2) return `${base} bg-orange-400 text-orange-900`;
  return `${base} text-slate-500 dark:text-slate-400`;
};

// ---------- ライフサイクル ----------
let robotsMeta: HTMLMetaElement | null = null;
onMounted(() => {
  document.title = 'きんじょー杯 参加者一覧 | beat-seeker';
  // 参加者の個人データを含むため検索エンジンには載せない。
  robotsMeta = document.createElement('meta');
  robotsMeta.name = 'robots';
  robotsMeta.content = 'noindex,nofollow';
  document.head.appendChild(robotsMeta);
  loadParticipants();
});

onBeforeUnmount(() => {
  if (robotsMeta) {
    document.head.removeChild(robotsMeta);
    robotsMeta = null;
  }
});
</script>
