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
import { mdiClose } from '@mdi/js';
import { ref, watch } from 'vue';
import { useShareTokens, type ShareTokenInfo, type ShareExpiresIn } from '../composables/useShareTokens';
import { useToast } from '../composables/useToast';
import { useModalEscape } from '../composables/useModalEscape';
import { jstParts } from '../utils/jstTime';

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

/** 日時を「YYYY/MM/DD HH:mm」形式（JST 固定）で表示する。 */
const formatDateTime = (iso: string | null) => {
    if (!iso) return '無期限';
    const p = jstParts(iso);
    if (!p) return iso;
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${p.year}/${pad(p.month)}/${pad(p.day)} ${pad(p.hour)}:${pad(p.minute)}`;
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
    <v-card aria-labelledby="share-token-title" class="w-full flex flex-col max-h-[90vh]">

      <v-card-item class="shrink-0">
        <v-card-title id="share-token-title">URL共有の管理</v-card-title>
        <template #append>
          <v-btn icon variant="text" size="small" aria-label="閉じる" @click="emit('close')">
            <v-icon :icon="mdiClose" />
          </v-btn>
        </template>
      </v-card-item>
      <v-divider />

      <v-card-text class="overflow-y-auto space-y-6">

        <p class="text-medium-emphasis leading-relaxed">
          発行した URL を知っている人なら誰でも、ログインせずに選択した画面を閲覧できます。<br />
          SNS への投稿などは、期限を短めに設定したり、不要になったら失効させてください。
        </p>

        <!-- 発行フォーム -->
        <v-card variant="outlined">
          <v-card-title>新しい共有 URL を発行</v-card-title>
          <v-card-text class="space-y-4">
            <div>
              <div class="text-subtitle-2 mb-1">公開する画面</div>
              <div class="grid grid-cols-1 sm:grid-cols-2">
                <v-checkbox v-model="scopeDashboard" label="ダッシュボード" color="primary" density="compact" hide-details />
                <v-checkbox v-model="scopeScores" label="スコア一覧" color="primary" density="compact" hide-details />
                <v-checkbox v-model="scopeHistory" label="成長記録" color="primary" density="compact" hide-details />
                <v-checkbox v-model="scopeProfile" label="プロフィール" color="primary" density="compact" hide-details />
              </div>
              <p class="text-caption text-medium-emphasis mt-2 leading-relaxed">
                プロフィールには成長軌跡とスコア分析のみが含まれます（URL共有・通知設定は公開されません）。
              </p>
            </div>

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
              URL を発行
            </v-btn>
          </v-card-text>
        </v-card>

        <!-- 一覧 -->
        <div class="space-y-3">
          <h4 class="text-base font-bold">発行済みリンク</h4>

          <div v-if="tokens.length === 0" class="text-xs text-slate-500 dark:text-slate-400 py-6 text-center border border-dashed border-slate-200 dark:border-slate-700 rounded-md">
            まだ発行したリンクはありません。
          </div>

          <ul v-else class="space-y-3">
            <v-card v-for="t in tokens" :key="t.id" tag="li" variant="outlined">
              <v-card-text>
              <div class="flex items-center justify-between gap-2 mb-2">
                <v-chip label size="x-small" variant="flat" :color="statusLabel(t).color">{{ statusLabel(t).text }}</v-chip>
                <span class="text-[10px] text-medium-emphasis">発行: {{ formatDateTime(t.createdAt) }}</span>
              </div>

              <div class="text-xs mb-1 break-all">
                <span class="font-mono">{{ buildShareUrl(t.token) }}</span>
              </div>

              <div class="text-[11px] text-medium-emphasis">
                公開: {{ scopeLabel(t) }} ／ 期限: {{ formatDateTime(t.expiresAt) }}
              </div>
              </v-card-text>

              <v-card-actions class="flex-wrap">
                <v-btn color="primary" @click="handleCopy(t)">URL をコピー</v-btn>
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
