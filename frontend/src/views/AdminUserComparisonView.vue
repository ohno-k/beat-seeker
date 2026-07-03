<script setup lang="ts">
/**
 * 【View の役割】 管理者が任意の 2 ユーザーを選んでスコア勝敗を比較する画面。
 *
 * 構成:
 *  - 管理者 API `/api/admin/users` で全ユーザー一覧を取得し、2 つのプルダウンに表示
 *  - ユーザー A / B を選択して「比較する」を押すと AdminComparisonModal を開く
 *
 * 権限:
 *  - useAdmin.isAdmin で表示ガード (実権限チェックはサーバ側)
 *  - 大会管理 (/competition-admin) とは別ルートの汎用管理者機能として独立
 */
import { ref, computed, onMounted } from 'vue';
import { useAdmin } from '../composables/useAdmin';
import { useScores } from '../composables/useScores';
import AdminComparisonModal, { type AdminUserSummary } from '../components/AdminComparisonModal.vue';

const { isAdmin } = useAdmin();
const { fetchAllUsers, isFetching } = useScores();

/** 全ユーザー一覧 (id 昇順)。プルダウンの選択肢に使う。 */
const users = ref<AdminUserSummary[]>([]);
const loadError = ref<string | null>(null);

const selectedAId = ref<number | null>(null);
const selectedBId = ref<number | null>(null);

const selectedA = computed(() => users.value.find(u => u.id === selectedAId.value) ?? null);
const selectedB = computed(() => users.value.find(u => u.id === selectedBId.value) ?? null);

/** 同一ユーザー選択は禁止 + 両方選ばれている時のみ比較ボタンを有効にする。 */
const canCompare = computed(() =>
  selectedA.value != null && selectedB.value != null && selectedA.value.id !== selectedB.value.id
);

const isModalOpen = ref(false);
const openComparison = () => {
  if (canCompare.value) isModalOpen.value = true;
};
const closeComparison = () => {
  isModalOpen.value = false;
};

onMounted(async () => {
  if (!isAdmin.value) return;
  try {
    const list: any[] = await fetchAllUsers();
    users.value = list
      .map(u => ({
        id: u.id,
        displayName: u.displayName ?? '(no name)',
        iidxId: u.iidxId ?? '',
      }))
      .sort((a, b) => a.displayName.localeCompare(b.displayName, 'ja'));
  } catch (e: any) {
    loadError.value = e?.message ?? 'ユーザー一覧の取得に失敗しました';
  }
});
</script>

<template>
  <div class="max-w-4xl mx-auto p-4 sm:p-6 space-y-6">
    <header class="space-y-1">
      <h1 class="text-2xl sm:text-3xl font-bold text-slate-900 dark:text-white">
        ユーザー間スコア比較 (管理者)
      </h1>
      <p class="text-sm font-bold text-slate-500 dark:text-slate-400">
        任意の 2 ユーザーを選んで EX-SCORE の勝敗を集計します。
      </p>
    </header>

    <!-- 権限なし時の警告 -->
    <div v-if="!isAdmin" class="bg-amber-50 dark:bg-amber-900/20 p-6 rounded-md border border-amber-200 dark:border-amber-900/30">
      <p class="text-amber-700 dark:text-amber-300 font-bold">
        この画面は管理者のみアクセス可能です。
      </p>
    </div>

    <template v-else>
      <!-- ロードエラー -->
      <div v-if="loadError" class="bg-red-50 dark:bg-red-900/20 p-4 rounded-md border border-red-100 dark:border-red-900/30">
        <p class="text-red-600 dark:text-red-400 font-bold">{{ loadError }}</p>
      </div>

      <!-- 選択カード -->
      <section class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 p-6 space-y-5">
        <div v-if="isFetching && users.length === 0" class="flex items-center gap-3 text-slate-500 font-bold">
          <div class="w-5 h-5 border-2 border-slate-200 border-t-blue-600 rounded-full animate-spin"></div>
          ユーザー一覧を取得中...
        </div>

        <div v-else class="grid grid-cols-1 md:grid-cols-2 gap-5">
          <!-- ユーザー A -->
          <label class="block space-y-2">
            <span class="text-xs font-bold text-blue-600 dark:text-blue-400">
              ユーザー A
            </span>
            <select
              v-model="selectedAId"
              class="w-full px-3 py-3 rounded-md border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 text-slate-900 dark:text-white font-bold focus:outline-none focus:ring-2 focus:ring-blue-400"
            >
              <option :value="null">-- 選択してください --</option>
              <option v-for="u in users" :key="u.id" :value="u.id">
                {{ u.displayName }} ({{ u.iidxId }})
              </option>
            </select>
          </label>

          <!-- ユーザー B -->
          <label class="block space-y-2">
            <span class="text-xs font-bold text-red-500 dark:text-red-400">
              ユーザー B
            </span>
            <select
              v-model="selectedBId"
              class="w-full px-3 py-3 rounded-md border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 text-slate-900 dark:text-white font-bold focus:outline-none focus:ring-2 focus:ring-red-400"
            >
              <option :value="null">-- 選択してください --</option>
              <option v-for="u in users" :key="u.id" :value="u.id">
                {{ u.displayName }} ({{ u.iidxId }})
              </option>
            </select>
          </label>
        </div>

        <div v-if="selectedA && selectedB && selectedA.id === selectedB.id"
             class="text-xs font-bold text-amber-600 dark:text-amber-400">
          ※ 同じユーザーは比較できません。
        </div>

        <div class="flex justify-end">
          <button
            @click="openComparison"
            :disabled="!canCompare"
            class="px-8 py-3 rounded-md font-bold text-white transition-all active:scale-95"
            :class="canCompare
              ? 'bg-blue-600 hover:bg-blue-700'
              : 'bg-slate-300 dark:bg-slate-600 cursor-not-allowed'"
          >
            比較する
          </button>
        </div>
      </section>
    </template>

    <!-- 比較モーダル -->
    <AdminComparisonModal
      v-if="selectedA && selectedB && canCompare"
      :user-a="selectedA"
      :user-b="selectedB"
      :is-open="isModalOpen"
      @close="closeComparison"
    />
  </div>
</template>
