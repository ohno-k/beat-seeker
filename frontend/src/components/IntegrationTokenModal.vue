<script setup lang="ts">
/**
 * 【コンポーネントの役割】 外部 API トークン（iidx-memo 等と連携するための個人トークン）の
 * 発行 / 一覧 / 失効モーダル。
 *
 * 機能:
 *  - ラベル名・連携先・有効期限を入力して発行
 *  - 発行直後の平文トークンを 1 度だけ強調表示し、コピーボタンを提供（再表示不可）
 *  - 発行済みトークン一覧（末尾 4 文字のみ識別子として表示）
 *  - 失効 / 一覧から削除
 *
 * ShareTokenModal と作りは同じ。違いは「平文の一度きり表示」と「公開先が URL ではなく
 * 連携アプリの設定欄」という点。
 */
import { ref, watch } from 'vue';
import {
    useIntegrationTokens,
    type IntegrationTokenInfo,
    type IntegrationExpiresIn,
} from '../composables/useIntegrationTokens';
import { useToast } from '../composables/useToast';
import { useModalEscape } from '../composables/useModalEscape';

const props = defineProps<{ isOpen: boolean }>();
const emit = defineEmits<{ (e: 'close'): void }>();

useModalEscape(() => props.isOpen, () => emit('close'));

const { listTokens, issueToken, revokeToken, deleteToken, isLoading } = useIntegrationTokens();
const toast = useToast();

const tokens = ref<IntegrationTokenInfo[]>([]);
const errorMsg = ref('');

const nameInput = ref('');
const partnerInput = ref('iidx-memo');
const expiresIn = ref<IntegrationExpiresIn>('1y');

/** 発行直後に表示する平文。コピー後に「閉じる」を押すまで保持。 */
const newlyIssuedPlain = ref<string | null>(null);

const expiryOptions: Array<{ value: IntegrationExpiresIn; label: string }> = [
    { value: '30d', label: '30日' },
    { value: '90d', label: '90日' },
    { value: '1y', label: '1年' },
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
        newlyIssuedPlain.value = null;
        nameInput.value = '';
        partnerInput.value = 'iidx-memo';
        expiresIn.value = '1y';
        refresh();
    }
});

const handleIssue = async () => {
    errorMsg.value = '';
    try {
        const created = await issueToken({
            name: nameInput.value.trim() || null,
            partner: partnerInput.value.trim() || null,
            expiresIn: expiresIn.value,
        });
        if (created.plainToken) {
            newlyIssuedPlain.value = created.plainToken;
            try {
                await navigator.clipboard.writeText(created.plainToken);
                toast.success('トークンを発行してクリップボードにコピーしました');
            } catch {
                toast.success('トークンを発行しました');
            }
        }
        await refresh();
    } catch (e: any) {
        errorMsg.value = e?.message || '発行に失敗しました';
    }
};

const handleCopyPlain = async () => {
    if (!newlyIssuedPlain.value) return;
    try {
        await navigator.clipboard.writeText(newlyIssuedPlain.value);
        toast.success('トークンをコピーしました');
    } catch {
        toast.error('コピーに失敗しました');
    }
};

const handleRevoke = async (t: IntegrationTokenInfo) => {
    if (!confirm('このトークンを失効させますか？連携アプリ側で再連携が必要になります。')) return;
    try {
        await revokeToken(t.id);
        toast.success('トークンを失効しました');
        await refresh();
    } catch (e: any) {
        toast.error(e?.message || '失効に失敗しました');
    }
};

const handleDelete = async (t: IntegrationTokenInfo) => {
    if (!confirm('このトークンを一覧から完全に削除しますか？この操作は取り消せません。')) return;
    try {
        await deleteToken(t.id);
        toast.success('トークンを削除しました');
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

const statusLabel = (t: IntegrationTokenInfo) => {
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
    aria-labelledby="integration-token-title"
    class="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-slate-900/50 backdrop-blur-sm animate-in fade-in duration-200"
    @click.self="emit('close')"
  >
    <div class="bg-white dark:bg-slate-800 rounded-md shadow-xl w-full max-w-md overflow-hidden flex flex-col max-h-[90vh] transition-colors duration-200">

      <div class="p-4 border-b border-slate-200 dark:border-slate-700 flex justify-between items-center bg-slate-50 dark:bg-slate-900/50">
        <h3 id="integration-token-title" class="text-lg font-bold text-slate-800 dark:text-slate-100">外部連携トークン</h3>
        <button type="button" aria-label="閉じる" @click="emit('close')" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200">
          <svg xmlns="http://www.w3.org/2000/svg" aria-hidden="true" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div class="p-6 overflow-y-auto space-y-6">

        <p class="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
          iidx-memo 等の連携先アプリに貼り付けて使うトークンを発行します。<br />
          発行されたトークンは <b>1 回しか表示されません</b>。漏洩した場合は失効させて再発行してください。
        </p>

        <!-- 発行直後の平文トークン表示 -->
        <div v-if="newlyIssuedPlain" class="rounded-md border border-amber-300 dark:border-amber-700 bg-amber-50/80 dark:bg-amber-900/30 p-5 space-y-3">
          <h4 class="text-sm font-bold text-amber-700 dark:text-amber-300">新しいトークン（このタイミングでしか表示されません）</h4>
          <div class="bg-white dark:bg-slate-900 rounded-md border border-amber-200 dark:border-amber-800 p-3 break-all font-mono text-xs text-slate-800 dark:text-slate-200">
            {{ newlyIssuedPlain }}
          </div>
          <div class="flex gap-2">
            <button
              type="button"
              @click="handleCopyPlain"
              class="px-3 py-1.5 rounded-lg bg-amber-600 hover:bg-amber-700 text-white text-xs font-bold transition-colors"
            >トークンをコピー</button>
            <button
              type="button"
              @click="newlyIssuedPlain = null"
              class="px-3 py-1.5 rounded-lg bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-200 text-xs font-bold hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
            >閉じる</button>
          </div>
        </div>

        <!-- 発行フォーム -->
        <div class="rounded-md border border-slate-200 dark:border-slate-700 p-5 space-y-5 bg-slate-50/60 dark:bg-slate-800/40">
          <h4 class="text-base font-bold text-slate-700 dark:text-slate-200">新しいトークンを発行</h4>

          <div>
            <label class="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2">ラベル（任意）</label>
            <input
              v-model="nameInput"
              type="text"
              maxlength="80"
              placeholder="例: 自宅 PC の iidx-memo"
              class="w-full px-3 py-2 rounded-md border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-sm text-slate-800 dark:text-slate-100"
            />
          </div>

          <div>
            <label class="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2">連携先</label>
            <input
              v-model="partnerInput"
              type="text"
              maxlength="40"
              class="w-full px-3 py-2 rounded-md border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-sm text-slate-800 dark:text-slate-100"
            />
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
            トークンを発行
          </button>
        </div>

        <!-- 一覧 -->
        <div class="space-y-3">
          <h4 class="text-base font-bold text-slate-700 dark:text-slate-200">発行済みトークン</h4>

          <div v-if="tokens.length === 0" class="text-xs text-slate-500 dark:text-slate-400 py-6 text-center border border-dashed border-slate-200 dark:border-slate-700 rounded-md">
            まだ発行したトークンはありません。
          </div>

          <ul v-else class="space-y-3">
            <li v-for="t in tokens" :key="t.id" class="rounded-md border border-slate-200 dark:border-slate-700 p-4 bg-white dark:bg-slate-800/40">
              <div class="flex items-center justify-between gap-2 mb-2">
                <span class="text-[10px] font-bold px-2 py-0.5 rounded" :class="statusLabel(t).cls">{{ statusLabel(t).text }}</span>
                <span class="text-[10px] text-slate-400">発行: {{ formatDateTime(t.createdAt) }}</span>
              </div>

              <div class="text-sm font-bold text-slate-700 dark:text-slate-200 mb-1">
                {{ t.name || '(無名)' }}
                <span v-if="t.partner" class="ml-2 text-[10px] font-normal text-slate-500 dark:text-slate-400">→ {{ t.partner }}</span>
              </div>

              <div class="text-xs text-slate-500 dark:text-slate-400 mb-1 font-mono">
                bs_live_...{{ t.tokenPrefix }}
              </div>

              <div class="text-[11px] text-slate-500 dark:text-slate-400 mb-3">
                期限: {{ formatDateTime(t.expiresAt) }}<span v-if="t.lastUsedAt"> ／ 最終利用: {{ formatDateTime(t.lastUsedAt) }}</span>
              </div>

              <div class="flex flex-wrap gap-2">
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
