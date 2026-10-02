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
import { mdiClose } from '@mdi/js';
import { ref, watch } from 'vue';
import {
    useIntegrationTokens,
    type IntegrationTokenInfo,
    type IntegrationExpiresIn,
} from '../composables/useIntegrationTokens';
import { useToast } from '../composables/useToast';
import { useModalEscape } from '../composables/useModalEscape';
import { jstParts } from '../utils/jstTime';

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

/** 日時を「YYYY/MM/DD HH:mm」形式（JST 固定）で表示する。 */
const formatDateTime = (iso: string | null) => {
    if (!iso) return '無期限';
    const p = jstParts(iso);
    if (!p) return iso;
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${p.year}/${pad(p.month)}/${pad(p.day)} ${pad(p.hour)}:${pad(p.minute)}`;
};

const statusLabel = (t: IntegrationTokenInfo) => {
    if (t.revokedAt) return { text: '失効済み', cls: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300' };
    if (!t.active) return { text: '期限切れ', cls: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300' };
    return { text: '有効', cls: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300' };
};
</script>

<template>
  <v-dialog
    :model-value="isOpen"
    max-width="448"
    @update:model-value="(v: boolean) => { if (!v) emit('close') }"
  >
    <v-card aria-labelledby="integration-token-title" class="w-full overflow-hidden flex flex-col max-h-[90vh] transition-colors duration-200">

      <div class="p-4 border-b border-slate-200 dark:border-slate-700 flex justify-between items-center bg-slate-50 dark:bg-slate-900/50">
        <h3 id="integration-token-title" class="text-lg font-bold text-slate-800 dark:text-slate-100">外部連携トークン</h3>
        <v-btn icon variant="text" size="small" aria-label="閉じる" class="text-slate-400" @click="emit('close')">
          <v-icon :icon="mdiClose" />
        </v-btn>
      </div>

      <div class="p-6 overflow-y-auto space-y-6">

        <p class="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
          iidx-memo 等の連携先アプリに貼り付けて使うトークンを発行します。<br />
          発行されたトークンは <b>1 回しか表示されません</b>。漏洩した場合は失効させて再発行してください。
        </p>

        <!-- 発行直後の平文トークン表示 -->
        <v-alert v-if="newlyIssuedPlain" type="warning" :icon="false" class="p-5">
          <div class="space-y-3">
            <h4 class="text-sm font-bold text-amber-700 dark:text-amber-300">新しいトークン（このタイミングでしか表示されません）</h4>
            <div class="bg-white dark:bg-slate-900 rounded-md border border-amber-200 dark:border-amber-800 p-3 break-all font-mono text-xs text-slate-800 dark:text-slate-200">
              {{ newlyIssuedPlain }}
            </div>
            <div class="flex gap-2">
              <v-btn color="warning" size="small" class="text-xs" @click="handleCopyPlain">トークンをコピー</v-btn>
              <v-btn variant="tonal" size="small" class="text-xs" @click="newlyIssuedPlain = null">閉じる</v-btn>
            </div>
          </div>
        </v-alert>

        <!-- 発行フォーム -->
        <v-card class="p-5 space-y-5 bg-slate-50/60 dark:bg-slate-800/40">
          <h4 class="text-base font-bold text-slate-700 dark:text-slate-200">新しいトークンを発行</h4>

          <div>
            <label class="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2">ラベル（任意）</label>
            <v-text-field
              v-model="nameInput"
              type="text"
              maxlength="80"
              placeholder="例: 自宅 PC の iidx-memo"
              class="text-sm"
            />
          </div>

          <div>
            <label class="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2">連携先</label>
            <v-text-field
              v-model="partnerInput"
              type="text"
              maxlength="40"
              class="text-sm"
            />
          </div>

          <div>
            <label class="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2">有効期限</label>
            <v-select
              v-model="expiresIn"
              :items="expiryOptions"
              item-title="label"
              item-value="value"
              class="text-sm"
            />
          </div>

          <div v-if="errorMsg" class="text-xs font-bold text-red-600 dark:text-red-400">{{ errorMsg }}</div>

          <v-btn
            color="primary"
            block
            :disabled="isLoading"
            @click="handleIssue"
          >
            トークンを発行
          </v-btn>
        </v-card>

        <!-- 一覧 -->
        <div class="space-y-3">
          <h4 class="text-base font-bold text-slate-700 dark:text-slate-200">発行済みトークン</h4>

          <div v-if="tokens.length === 0" class="text-xs text-slate-500 dark:text-slate-400 py-6 text-center border border-dashed border-slate-200 dark:border-slate-700 rounded-md">
            まだ発行したトークンはありません。
          </div>

          <ul v-else class="space-y-3">
            <v-card v-for="t in tokens" :key="t.id" tag="li" class="p-4 bg-white dark:bg-slate-800/40">
              <div class="flex items-center justify-between gap-2 mb-2">
                <v-chip label size="x-small" variant="flat" class="text-[10px] font-bold" :class="statusLabel(t).cls">{{ statusLabel(t).text }}</v-chip>
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
                <v-btn
                  v-if="t.active"
                  variant="tonal"
                  color="warning"
                  size="small"
                  class="text-xs"
                  @click="handleRevoke(t)"
                >失効する</v-btn>
                <v-btn
                  v-else
                  variant="tonal"
                  color="error"
                  size="small"
                  class="text-xs"
                  @click="handleDelete(t)"
                >一覧から削除</v-btn>
              </div>
            </v-card>
          </ul>
        </div>
      </div>
    </v-card>
  </v-dialog>
</template>
