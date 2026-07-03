<script setup lang="ts">
/**
 * 【コンポーネントの役割】 URL 共有トークンの発行 / 一覧 / 失効モーダル（全画面）。
 *
 * 機能:
 *  - 公開範囲（ダッシュボード / スコア一覧 / 成長記録 / プロフィール）を多選択
 *    ※ プロフィールは「成長軌跡＋スコア分析」の意味で、URL 共有・通知設定は公開しない
 *  - 有効期限（24時間 / 1週間 / 1ヶ月 / 無期限）を選んで発行
 *  - 発行済みトークンを一覧表示し、コピー / 失効ができる
 *  - 失効済み / 期限切れのトークンには赤バッジ
 *
 * 共有先のページ実装は `/share/:token` ルートに用意した ShareView.vue 側。
 */
import { ref, watch } from 'vue';
import { useShareTokens, type ShareTokenInfo, type ShareExpiresIn } from '../composables/useShareTokens';
import { useToast } from '../composables/useToast';
import { useModalEscape } from '../composables/useModalEscape';

const props = defineProps<{ isOpen: boolean }>();
const emit = defineEmits<{ (e: 'close'): void }>();

useModalEscape(() => props.isOpen, () => emit('close'));

const { listTokens, issueToken, revokeToken, deleteToken, buildShareUrl, isLoading } = useShareTokens();
const toast = useToast();

const tokens = ref<ShareTokenInfo[]>([]);
const errorMsg = ref('');

const scopeDashboard = ref(true);
const scopeScores = ref(true);
const scopeHistory = ref(false);
const scopeProfile = ref(false);
const expiresIn = ref<ShareExpiresIn>('1w');

const expiryOptions: Array<{ value: ShareExpiresIn; label: string }> = [
    { value: '24h', label: '24時間' },
    { value: '1w', label: '1週間' },
    { value: '1m', label: '1ヶ月' },
    { value: 'unlimited', label: '無期限' },
];

const refresh = async () => {
    try {
        tokens.value = await listTokens();
    } catch {
        tokens.value = [];
    }
};

watch(() => props.isOpen, (open) => {
    if (open) {
        errorMsg.value = '';
        scopeDashboard.value = true;
        scopeScores.value = true;
        scopeHistory.value = false;
        scopeProfile.value = false;
        expiresIn.value = '1w';
        refresh();
    }
});

const handleIssue = async () => {
    errorMsg.value = '';
    if (!scopeDashboard.value && !scopeScores.value && !scopeHistory.value && !scopeProfile.value) {
        errorMsg.value = '公開する画面を1つ以上選択してください';
        return;
    }
    try {
        const created = await issueToken(
            {
                scopeDashboard: scopeDashboard.value,
                scopeScores: scopeScores.value,
                scopeHistory: scopeHistory.value,
                scopeProfile: scopeProfile.value,
            },
            expiresIn.value,
        );
        const url = buildShareUrl(created.token);
        try {
            await navigator.clipboard.writeText(url);
            toast.success('共有URLを発行してクリップボードにコピーしました');
        } catch {
            toast.success('共有URLを発行しました');
        }
        await refresh();
    } catch (e: any) {
        errorMsg.value = e?.message || '発行に失敗しました';
    }
};

const handleCopy = async (t: ShareTokenInfo) => {
    const url = buildShareUrl(t.token);
    try {
        await navigator.clipboard.writeText(url);
        toast.success('URL をコピーしました');
    } catch {
        toast.error('コピーに失敗しました');
    }
};

const handleRevoke = async (t: ShareTokenInfo) => {
    if (!confirm('このリンクを失効させますか？閲覧している人は見られなくなります。')) return;
    try {
        await revokeToken(t.id);
        toast.success('リンクを失効しました');
        await refresh();
    } catch (e: any) {
        toast.error(e?.message || '失効に失敗しました');
    }
};

const handleDelete = async (t: ShareTokenInfo) => {
    if (!confirm('このリンクを一覧から完全に削除しますか？この操作は取り消せません。')) return;
    try {
        await deleteToken(t.id);
        toast.success('リンクを削除しました');
        await refresh();
    } catch (e: any) {
        toast.error(e?.message || '削除に失敗しました');
    }
};

const formatDateTime = (iso: string | null) => {
    if (!iso) return '無期限';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    return `${y}/${m}/${day} ${hh}:${mm}`;
};

const scopeLabel = (t: ShareTokenInfo) => {
    const parts: string[] = [];
    if (t.scopeDashboard) parts.push('ダッシュボード');
    if (t.scopeScores) parts.push('スコア一覧');
    if (t.scopeHistory) parts.push('成長記録');
    if (t.scopeProfile) parts.push('プロフィール');
    return parts.length === 0 ? '(なし)' : parts.join(' / ');
};

const statusLabel = (t: ShareTokenInfo) => {
    if (t.revokedAt) return { text: '失効済み', cls: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300' };
    if (!t.active) return { text: '期限切れ', cls: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300' };
    return { text: '有効', cls: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300' };
};
</script>

<template>
  <Teleport to="body">
  <div
    v-if="isOpen"
    role="dialog"
    aria-modal="true"
    aria-labelledby="share-token-title"
    class="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-slate-900/50 backdrop-blur-sm animate-in fade-in duration-200"
    @click.self="emit('close')"
  >
    <div class="bg-white dark:bg-slate-800 rounded-md shadow-xl w-full max-w-md overflow-hidden flex flex-col max-h-[90vh] transition-colors duration-200">

      <div class="p-4 border-b border-slate-200 dark:border-slate-700 flex justify-between items-center bg-slate-50 dark:bg-slate-900/50">
        <h3 id="share-token-title" class="text-lg font-bold text-slate-800 dark:text-slate-100">URL共有の管理</h3>
        <button type="button" aria-label="閉じる" @click="emit('close')" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200">
          <svg xmlns="http://www.w3.org/2000/svg" aria-hidden="true" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div class="p-6 overflow-y-auto space-y-6">

        <p class="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
          発行した URL を知っている人なら誰でも、ログインせずに選択した画面を閲覧できます。<br />
          SNS への投稿などは、期限を短めに設定したり、不要になったら失効させてください。
        </p>

        <!-- 発行フォーム -->
        <div class="rounded-md border border-slate-200 dark:border-slate-700 p-5 space-y-5 bg-slate-50/60 dark:bg-slate-800/40">
          <h4 class="text-base font-bold text-slate-700 dark:text-slate-200">新しい共有 URL を発行</h4>

          <div>
            <span class="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2">公開する画面</span>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <label class="flex items-center gap-2 text-sm cursor-pointer p-2 rounded-lg hover:bg-white dark:hover:bg-slate-800 transition-colors">
                <input type="checkbox" v-model="scopeDashboard" class="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500" />
                <span class="text-slate-700 dark:text-slate-200">ダッシュボード</span>
              </label>
              <label class="flex items-center gap-2 text-sm cursor-pointer p-2 rounded-lg hover:bg-white dark:hover:bg-slate-800 transition-colors">
                <input type="checkbox" v-model="scopeScores" class="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500" />
                <span class="text-slate-700 dark:text-slate-200">スコア一覧</span>
              </label>
              <label class="flex items-center gap-2 text-sm cursor-pointer p-2 rounded-lg hover:bg-white dark:hover:bg-slate-800 transition-colors">
                <input type="checkbox" v-model="scopeHistory" class="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500" />
                <span class="text-slate-700 dark:text-slate-200">成長記録</span>
              </label>
              <label class="flex items-center gap-2 text-sm cursor-pointer p-2 rounded-lg hover:bg-white dark:hover:bg-slate-800 transition-colors">
                <input type="checkbox" v-model="scopeProfile" class="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500" />
                <span class="text-slate-700 dark:text-slate-200">プロフィール</span>
              </label>
            </div>
            <p class="text-[11px] text-slate-500 dark:text-slate-400 mt-2 leading-relaxed">
              プロフィールには成長軌跡とスコア分析のみが含まれます（URL共有・通知設定は公開されません）。
            </p>
          </div>

          <div>
            <label class="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2">有効期限</label>
            <select v-model="expiresIn"
              class="w-full px-3 py-2 rounded-md border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-sm text-slate-800 dark:text-slate-100">
              <option v-for="opt in expiryOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
          </div>

          <div v-if="errorMsg" class="text-xs font-bold text-red-600 dark:text-red-400">{{ errorMsg }}</div>

          <button
            type="button"
            @click="handleIssue"
            :disabled="isLoading"
            class="w-full px-4 py-2.5 rounded-md bg-blue-600 hover:bg-blue-700 text-white text-sm font-bold transition-colors disabled:opacity-50"
          >
            URL を発行
          </button>
        </div>

        <!-- 一覧 -->
        <div class="space-y-3">
          <h4 class="text-base font-bold text-slate-700 dark:text-slate-200">発行済みリンク</h4>

          <div v-if="tokens.length === 0" class="text-xs text-slate-500 dark:text-slate-400 py-6 text-center border border-dashed border-slate-200 dark:border-slate-700 rounded-md">
            まだ発行したリンクはありません。
          </div>

          <ul v-else class="space-y-3">
            <li v-for="t in tokens" :key="t.id" class="rounded-md border border-slate-200 dark:border-slate-700 p-4 bg-white dark:bg-slate-800/40">
              <div class="flex items-center justify-between gap-2 mb-2">
                <span class="text-[10px] font-bold px-2 py-0.5 rounded" :class="statusLabel(t).cls">{{ statusLabel(t).text }}</span>
                <span class="text-[10px] text-slate-400">発行: {{ formatDateTime(t.createdAt) }}</span>
              </div>

              <div class="text-xs text-slate-600 dark:text-slate-300 mb-1 break-all">
                <span class="font-mono">{{ buildShareUrl(t.token) }}</span>
              </div>

              <div class="text-[11px] text-slate-500 dark:text-slate-400 mb-3">
                公開: {{ scopeLabel(t) }} ／ 期限: {{ formatDateTime(t.expiresAt) }}
              </div>

              <div class="flex flex-wrap gap-2">
                <button
                  type="button"
                  @click="handleCopy(t)"
                  class="px-3 py-1.5 rounded-lg bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-200 text-xs font-bold hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
                >URL をコピー</button>
                <button
                  v-if="t.active"
                  type="button"
                  @click="handleRevoke(t)"
                  class="px-3 py-1.5 rounded-lg bg-amber-50 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400 text-xs font-bold hover:bg-amber-100 dark:hover:bg-amber-900/50 transition-colors"
                >失効する</button>
                <button
                  v-else
                  type="button"
                  @click="handleDelete(t)"
                  class="px-3 py-1.5 rounded-lg bg-red-50 dark:bg-red-900/30 text-red-600 dark:text-red-400 text-xs font-bold hover:bg-red-100 dark:hover:bg-red-900/50 transition-colors"
                >一覧から削除</button>
              </div>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </div>
  </Teleport>
</template>
