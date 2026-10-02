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
    if (t.revokedAt) return { text: '失効済み', color: 'error' };
    if (!t.active) return { text: '期限切れ', color: 'warning' };
    return { text: '有効', color: 'success' };
};
</script>

<template>
  <v-dialog
    :model-value="isOpen"
    max-width="448"
    @update:model-value="(v: boolean) => { if (!v) emit('close') }"
  >
    <v-card aria-labelledby="integration-token-title" class="w-full flex flex-col max-h-[90vh]">

      <v-card-item class="shrink-0">
        <v-card-title id="integration-token-title">外部連携トークン</v-card-title>
        <template #append>
          <v-btn icon variant="text" size="small" aria-label="閉じる" @click="emit('close')">
            <v-icon :icon="mdiClose" />
          </v-btn>
        </template>
      </v-card-item>
      <v-divider />

      <v-card-text class="overflow-y-auto space-y-6">

        <p class="text-medium-emphasis leading-relaxed">
          iidx-memo 等の連携先アプリに貼り付けて使うトークンを発行します。<br />
          発行されたトークンは <b>1 回しか表示されません</b>。漏洩した場合は失効させて再発行してください。
        </p>

        <!-- 発行直後の平文トークン表示 -->
        <v-alert v-if="newlyIssuedPlain" type="warning" variant="tonal" title="新しいトークン（このタイミングでしか表示されません）">
          <div class="space-y-3 mt-2">
            <v-sheet rounded border class="p-3 break-all font-mono text-xs">
              {{ newlyIssuedPlain }}
            </v-sheet>
            <div class="flex gap-2">
              <v-btn color="warning" size="small" @click="handleCopyPlain">トークンをコピー</v-btn>
              <v-btn variant="text" size="small" @click="newlyIssuedPlain = null">閉じる</v-btn>
            </div>
          </div>
        </v-alert>

        <!-- 発行フォーム -->
        <v-card variant="outlined">
          <v-card-title>新しいトークンを発行</v-card-title>
          <v-card-text class="space-y-2">
            <v-text-field
              v-model="nameInput"
              type="text"
              maxlength="80"
              label="ラベル（任意）"
              placeholder="例: 自宅 PC の iidx-memo"
            />

            <v-text-field
              v-model="partnerInput"
              type="text"
              maxlength="40"
              label="連携先"
            />

            <v-select
              v-model="expiresIn"
              :items="expiryOptions"
              item-title="label"
              item-value="value"
              label="有効期限"
            />

            <v-alert v-if="errorMsg" type="error" variant="tonal" density="compact">{{ errorMsg }}</v-alert>

            <v-btn
              color="primary"
              block
              :disabled="isLoading"
              @click="handleIssue"
            >
              トークンを発行
            </v-btn>
          </v-card-text>
        </v-card>

        <!-- 一覧 -->
        <div class="space-y-3">
          <h4 class="text-base font-bold">発行済みトークン</h4>

          <div v-if="tokens.length === 0" class="text-xs text-slate-500 dark:text-slate-400 py-6 text-center border border-dashed border-slate-200 dark:border-slate-700 rounded-md">
            まだ発行したトークンはありません。
          </div>

          <ul v-else class="space-y-3">
            <v-card v-for="t in tokens" :key="t.id" tag="li" variant="outlined">
              <v-card-text>
              <div class="flex items-center justify-between gap-2 mb-2">
                <v-chip label size="x-small" variant="flat" :color="statusLabel(t).color">{{ statusLabel(t).text }}</v-chip>
                <span class="text-[10px] text-medium-emphasis">発行: {{ formatDateTime(t.createdAt) }}</span>
              </div>

              <div class="text-sm font-bold mb-1">
                {{ t.name || '(無名)' }}
                <span v-if="t.partner" class="ml-2 text-[10px] font-normal text-medium-emphasis">→ {{ t.partner }}</span>
              </div>

              <div class="text-xs text-medium-emphasis mb-1 font-mono">
                bs_live_...{{ t.tokenPrefix }}
              </div>

              <div class="text-[11px] text-medium-emphasis">
                期限: {{ formatDateTime(t.expiresAt) }}<span v-if="t.lastUsedAt"> ／ 最終利用: {{ formatDateTime(t.lastUsedAt) }}</span>
              </div>
              </v-card-text>

              <v-card-actions>
                <v-btn
                  v-if="t.active"
                  color="warning"
                  @click="handleRevoke(t)"
                >失効する</v-btn>
                <v-btn
                  v-else
                  color="error"
                  @click="handleDelete(t)"
                >一覧から削除</v-btn>
              </v-card-actions>
            </v-card>
          </ul>
        </div>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>
