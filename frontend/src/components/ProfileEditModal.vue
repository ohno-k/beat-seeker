<script setup lang="ts">
/**
 * 【コンポーネントの役割】 プロフィール編集モーダル。表示名・段位・パスワード・メール・公開範囲などを一括変更。
 *
 * 機能:
 *  - `user` の現在値でフォームを初期化（モーダルが開いた瞬間）
 *  - パスワード変更は「現在パス + 新パス + 確認」がそろった時のみ送信
 *  - RateTier 表示トグルは `useRateTierVisibility` 側でローカルストレージに永続化
 *  - Supporter のみ金縁トグルを表示
 *
 * props:
 *  - isOpen: 開閉フラグ
 * emits:
 *  - close: 閉じる
 */
import { ref, watch } from 'vue';
import { useAuth } from '../composables/useAuth';
import { useRateTierVisibility } from '../composables/useRateTierVisibility';
import { useI18n } from '../composables/useI18n';
import { useToast } from '../composables/useToast';
import { useModalEscape } from '../composables/useModalEscape';
import { DAN_RANK_OPTIONS, ARENA_RANKS } from '../composables/constants';
import { mdiClose } from '@mdi/js';

const { t } = useI18n();
const toast = useToast();

const props = defineProps<{
  isOpen: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
}>();

// Esc キーで閉じる（背景クリックと同等）。
useModalEscape(() => props.isOpen, () => emit('close'));

// 認証 composable: ログインユーザー情報 + 更新 API。
const { user, updateProfile } = useAuth();
// RateTier 表示可否（ユーザーの見た目設定）。
const { showRateTier, setRateTier } = useRateTierVisibility();

/** 送信中フラグ（二重送信防止）。 */
const isSubmitting = ref(false);
/** エラー赤バナー。 */
const errorMsg = ref('');
/** 成功緑バナー。 */
const successMsg = ref('');

// ===== フォーム入力値（開くたびに user の値で再初期化される） =====
const displayName = ref('');
const danRank = ref('');
const arenaRank = ref('');
const playSide = ref('');
const privacyLevel = ref(0);

const email = ref('');
const currentPassword = ref('');
const newPassword = ref('');
const newPasswordConfirm = ref('');

/** 段位プルダウン選択肢（constants から共通定義を参照、i18n ラベル付き）。 */
const danRankOptions = DAN_RANK_OPTIONS;

/** アリーナランク選択肢（constants から共通定義を参照）。 */
const arenaRanks = ARENA_RANKS;

// モーダルが開かれたタイミング（isOpen が true になった時）にフォームへ現在値をコピー。
// この `watch` は「再オープンしても下書きが残っていた」という UX バグを防ぐ役割。
watch(() => props.isOpen, (newVal) => {
  if (newVal && user.value) {
    displayName.value = user.value.displayName;
    danRank.value = user.value.danRank;
    arenaRank.value = user.value.arenaRank;
    playSide.value = user.value.playSide;
    privacyLevel.value = user.value.privacyLevel ?? 0;
    email.value = user.value.email ?? '';

    currentPassword.value = '';
    newPassword.value = '';
    newPasswordConfirm.value = '';
    errorMsg.value = '';
    successMsg.value = '';
  }
});

/**
 * 【関数の役割】 更新ボタン押下時に実行。バリデーション → payload 組立 → サーバ送信 → 成功/失敗表示。
 * 成功から 1.5 秒後に自動でモーダルを閉じる（successMsg が残っている場合のみ）。
 */
const handleUpdate = async () => {
  errorMsg.value = '';
  successMsg.value = '';

  if (!displayName.value.trim()) {
    errorMsg.value = t('profile.displayNameRequired');
    return;
  }

  // パスワード変更用 3 入力がいずれか埋まっている場合のみ、厳しめにバリデーション。
  if (newPassword.value || currentPassword.value || newPasswordConfirm.value) {
    if (!currentPassword.value) {
      errorMsg.value = t('profile.currentPasswordRequired');
      return;
    }
    if (newPassword.value !== newPasswordConfirm.value) {
      errorMsg.value = t('profile.passwordMismatch');
      return;
    }
    if (newPassword.value.length < 4) {
      errorMsg.value = t('profile.passwordTooShort');
      return;
    }
  }

  isSubmitting.value = true;
  
  try {
    const payload: any = {
      displayName: displayName.value,
      danRank: danRank.value,
      arenaRank: arenaRank.value,
      playSide: playSide.value,
      privacyLevel: privacyLevel.value,
      email: email.value.trim() || undefined
    };

    if (newPassword.value) {
      payload.currentPassword = currentPassword.value;
      payload.newPassword = newPassword.value;
    }

    await updateProfile(payload);

    // パスワード入力欄は送信後に必ずクリア（画面に残すのは危険）。
    currentPassword.value = '';
    newPassword.value = '';
    newPasswordConfirm.value = '';

    // モーダルを即時に閉じ、保存完了は画面右下のトーストで通知する
    // （閉じてから視認できるので、画面遷移後でも見落としにくい）。
    toast.success(t('profile.updateSuccess'));
    emit('close');

  } catch (err: any) {
    errorMsg.value = err.message || t('profile.updateFailed');
  } finally {
    isSubmitting.value = false;
  }
};
</script>

<template>
  <v-dialog
    :model-value="isOpen"
    max-width="448"
    aria-labelledby="profile-edit-title"
    @update:model-value="(v) => { if (!v) emit('close') }"
  >
    <v-card class="overflow-hidden flex flex-col transition-colors duration-200">

      <div class="p-4 border-b border-slate-200 dark:border-slate-700 flex justify-between items-center bg-slate-50 dark:bg-slate-900/50">
        <h3 id="profile-edit-title" class="text-lg font-bold text-slate-800 dark:text-slate-100">{{ t('profile.editTitle') }}</h3>
        <v-btn icon variant="text" size="small" :aria-label="t('a11y.modal.close')" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200" @click="emit('close')">
          <v-icon :icon="mdiClose" />
        </v-btn>
      </div>

      <v-card-text class="p-6 overflow-y-auto">
        <form @submit.prevent="handleUpdate" class="space-y-6">

          <v-alert v-if="errorMsg" type="error" class="text-sm border border-red-200 dark:border-red-800/50">
            {{ errorMsg }}
          </v-alert>

          <v-alert v-if="successMsg" type="success" class="text-sm border border-emerald-200 dark:border-emerald-800/50">
            {{ successMsg }}
          </v-alert>

          <div class="space-y-4">
            <div>
              <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.displayName') }}</label>
              <v-text-field v-model="displayName" type="text" required />
            </div>

            <div class="grid grid-cols-2 gap-4">
              <div>
                <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.danRank') }}</label>
                <v-select
                  v-model="danRank"
                  :items="danRankOptions.map((rank) => ({ title: t(rank.labelKey), value: rank.value }))"
                  item-title="title"
                  item-value="value"
                />
              </div>
              <div>
                <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.arenaRank') }}</label>
                <v-select v-model="arenaRank" :items="[...arenaRanks]" />
              </div>
            </div>

            <div class="grid grid-cols-1">
              <div>
                <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.playSide') }}</label>
                <v-radio-group v-model="playSide" inline class="py-1">
                  <v-radio value="1P" class="mr-4">
                    <template #label><span class="text-sm font-bold text-slate-600 dark:text-slate-300">1P</span></template>
                  </v-radio>
                  <v-radio value="2P">
                    <template #label><span class="text-sm font-bold text-slate-600 dark:text-slate-300">2P</span></template>
                  </v-radio>
                </v-radio-group>
              </div>
              <div>
                <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.privacySetting') }}</label>
                <v-select
                  v-model="privacyLevel"
                  :items="[
                    { title: t('profile.privacyPublic'), value: 0 },
                    { title: t('profile.privacyFriendsOnly'), value: 1 },
                    { title: t('profile.privacyPrivate'), value: 2 },
                  ]"
                  item-title="title"
                  item-value="value"
                />
              </div>
            </div>
          </div>

          <!-- メール登録セクション（未登録ならバッジで促す） -->
          <div class="pt-4 border-t border-slate-100 dark:border-slate-700 space-y-4">
            <div class="flex items-center gap-2">
              <h4 class="text-sm font-bold text-slate-400">{{ t('profile.emailSection') }}</h4>
              <v-chip v-if="!user?.email" label variant="outlined" class="h-auto text-[10px] font-bold px-2 py-0.5 bg-amber-100 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400 border-amber-200 dark:border-amber-800/50">{{ t('profile.emailNotRegistered') }}</v-chip>
            </div>
            <div>
              <v-text-field v-model="email" type="email" :placeholder="t('profile.emailNotRegistered')" />
              <p class="text-xs text-slate-500 dark:text-slate-400 mt-1.5 ml-1">{{ t('profile.emailHint') }}</p>
            </div>
          </div>

          <div class="pt-4 border-t border-slate-100 dark:border-slate-700 space-y-4">
            <h4 class="text-sm font-bold text-slate-400">{{ t('profile.passwordChange') }}</h4>

            <div>
              <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.currentPassword') }}</label>
              <v-text-field v-model="currentPassword" type="password" :placeholder="t('profile.currentPasswordPlaceholder')" />
            </div>

            <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.newPassword') }}</label>
                <v-text-field v-model="newPassword" type="password" :placeholder="t('profile.newPasswordPlaceholder')" />
              </div>
              <div>
                <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.confirmNewPassword') }}</label>
                <v-text-field v-model="newPasswordConfirm" type="password" :placeholder="t('profile.newPasswordPlaceholder')" />
              </div>
            </div>
          </div>

          <!-- 表示設定（RateTier トグル / サポーター金縁トグル） -->
          <div class="pt-4 border-t border-slate-100 dark:border-slate-700 space-y-4">
            <h4 class="text-sm font-bold text-slate-400">{{ t('profile.displaySettings') }}</h4>
            <div class="flex items-center justify-between">
              <div>
                <p class="text-sm font-semibold text-slate-700 dark:text-slate-300">{{ t('profile.showRateTier') }}</p>
                <p class="text-xs text-slate-400 dark:text-slate-500 mt-0.5">{{ t('profile.showRateTierHint') }}</p>
              </div>
              <v-switch
                :model-value="showRateTier"
                color="success"
                class="ml-4 shrink-0 flex-none"
                :aria-label="t('profile.showRateTier')"
                @update:model-value="(v) => setRateTier(!!v)"
              />
            </div>
          </div>

          <div class="pt-4 flex gap-3">
            <v-btn type="button" variant="tonal" size="large" class="flex-1" @click="emit('close')">
              {{ t('common.cancel') }}
            </v-btn>
            <v-btn type="submit" color="primary" size="large" class="flex-[2]" :disabled="isSubmitting">
              <v-progress-circular v-if="isSubmitting" size="16" width="2" color="white" class="mr-2" />
              {{ isSubmitting ? t('profile.saving') : t('profile.saveChanges') }}
            </v-btn>
          </div>

        </form>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>
