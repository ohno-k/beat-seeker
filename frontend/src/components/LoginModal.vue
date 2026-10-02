<script setup lang="ts">
/**
 * 【コンポーネントの役割】 ログイン / 新規登録 / パスワードリセットの 3 モードを持つモーダル。
 *
 * 機能:
 *  - タブ切替で 3 モードを 1 ダイアログに統合
 *  - IIDX ID（NNNN-NNNN）を入力中に自動フォーマット
 *  - 登録時は段位・アリーナランク・プレイサイドまで同時に入力
 *  - forgotPassword 送信で成功メッセージをそのまま表示（マスキング方針はサーバ側）
 *
 * props:
 *  - isOpen: 開閉フラグ
 * emits:
 *  - close: ×ボタンや背景クリックで発火
 *  - registered: 新規登録成功時（オンボーディング起動用）
 */
import { ref } from 'vue';
import { useAuth } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';
import { useModalEscape } from '../composables/useModalEscape';
import { DAN_RANK_OPTIONS, ARENA_RANKS } from '../composables/constants';

const { t } = useI18n();

const props = defineProps<{
  isOpen: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'registered'): void;
}>();

// Esc キーで閉じられるようにする（背景クリックと同等の挙動）。
useModalEscape(() => props.isOpen, () => emit('close'));

// 認証系の API はすべてこの composable 経由で叩く。
const { login, registerUser, forgotPassword } = useAuth();

/** 現在のモード（ログイン/登録/パスワード忘れ）。 */
const mode = ref<'login' | 'register' | 'forgot'>('login');
/** 送信中フラグ（ボタンの二重押し抑制 + スピナー）。 */
const isSubmitting = ref(false);
/** エラー時に画面上部へ赤バナーで出すメッセージ。 */
const errorMsg = ref('');
/** 成功時の緑バナー（主に forgotPassword 用）。 */
const successMsg = ref('');

// ===== フォーム入力値 =====
const inputIidxId = ref('');
const password = ref('');
const passwordConfirm = ref('');
const displayName = ref('');
const danRank = ref('初段');
const arenaRank = ref('C5');
const playSide = ref('1P');

/** 段位プルダウンの選択肢（constants から共通定義を参照）。 */
const danRankOptions = DAN_RANK_OPTIONS;

/** アリーナランクの選択肢（constants から共通定義を参照）。 */
const arenaRanks = ARENA_RANKS;

/**
 * 【関数の役割】 IIDX ID 入力欄の値を「NNNN-NNNN」形式に自動整形する。
 * 数字以外を除去し、5 文字目の直前にハイフンを差し込んでフォーマットを固定する。
 */
const formatIidxId = (e: Event) => {
  const target = e.target as HTMLInputElement;
  let val = target.value.replace(/[^\d]/g, ''); // 数字以外を除去

  if (val.length > 4) {
    val = val.substring(0, 4) + '-' + val.substring(4, 8);
  }

  inputIidxId.value = val;
  target.value = val;
};

/** パスワードリセット時に入力する登録メールアドレス。 */
const forgotEmail = ref('');

/**
 * 【関数の役割】 モードに応じて「ログイン / 登録 / パスワードリセット」の適切な API を叩く。
 * バリデーション → 送信 → エラー/成功メッセージの表示 → 閉じる or イベント発火。
 */
const handleSubmit = async () => {
  errorMsg.value = '';
  successMsg.value = '';

  if (mode.value === 'forgot') {
    if (!inputIidxId.value.match(/^\d{4}-\d{4}$/)) {
      errorMsg.value = t('auth.iidxIdError');
      return;
    }
    if (!forgotEmail.value.trim()) {
      errorMsg.value = t('auth.emailRequired');
      return;
    }
    isSubmitting.value = true;
    try {
      const msg = await forgotPassword(inputIidxId.value, forgotEmail.value.trim());
      successMsg.value = msg;
    } catch (err: any) {
      errorMsg.value = err.message || t('auth.error');
    } finally {
      isSubmitting.value = false;
    }
    return;
  }

  if (!inputIidxId.value.match(/^\d{4}-\d{4}$/)) {
    errorMsg.value = t('auth.iidxIdError');
    return;
  }

  if (!password.value) {
    errorMsg.value = t('auth.passwordRequired');
    return;
  }

  isSubmitting.value = true;

  try {
    if (mode.value === 'login') {
      await login(inputIidxId.value, password.value);
    } else {
      if (!displayName.value.trim()) {
        throw new Error(t('auth.usernameRequired'));
      }
        await registerUser({
          iidxId: inputIidxId.value,
          password: password.value,
          displayName: displayName.value,
          danRank: danRank.value,
          arenaRank: arenaRank.value,
          playSide: playSide.value
        });
        emit('registered');
      }
      emit('close');
  } catch (err: any) {
    errorMsg.value = err.message || t('auth.error');
  } finally {
    isSubmitting.value = false;
  }
};

/**
 * 【関数の役割】 タブ切替。モード変更時にメッセージ類を掃除する。
 * @param newMode 'login' | 'register' | 'forgot'
 */
const switchMode = (newMode: 'login' | 'register' | 'forgot') => {
  mode.value = newMode;
  errorMsg.value = '';
  successMsg.value = '';
};
</script>

<template>
  <v-dialog
    :model-value="isOpen"
    max-width="448"
    :aria-label="t('a11y.dialog.login')"
    @update:model-value="(v) => { if (!v) emit('close') }"
  >
    <v-card class="overflow-hidden flex flex-col transition-colors duration-200">

      <!-- タブ切替（ログイン / 新規登録 / パスワードを忘れた） -->
      <v-tabs
        :model-value="mode"
        grow
        class="border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 transition-colors duration-200"
      >
        <v-tab value="login" class="text-sm font-bold text-none" @click="switchMode('login')">
          {{ t('auth.login') }}
        </v-tab>
        <v-tab value="register" class="text-sm font-bold text-none" @click="switchMode('register')">
          {{ t('auth.register') }}
        </v-tab>
        <v-tab value="forgot" class="text-xs font-bold text-none" @click="switchMode('forgot')">
          {{ t('auth.forgotPassword') }}
        </v-tab>
      </v-tabs>

      <v-card-text class="p-6 overflow-y-auto">
        <form @submit.prevent="handleSubmit" class="space-y-5">

          <v-alert v-if="errorMsg" type="error" class="text-sm transition-colors duration-200">
            {{ errorMsg }}
          </v-alert>
          <v-alert v-if="successMsg" type="success" class="text-sm">
            {{ successMsg }}
          </v-alert>

          <!-- パスワードリセットフォーム -->
          <template v-if="mode === 'forgot'">
            <p class="text-sm text-slate-600 dark:text-slate-400">{{ t('auth.forgotHint') }}</p>
            <div>
              <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('auth.iidxId') }}</label>
              <v-text-field
                v-model="inputIidxId"
                type="text"
                placeholder="1234-5678"
                pattern="\d{4}-\d{4}"
                maxlength="9"
                @input="formatIidxId"
              />
            </div>
            <div>
              <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('auth.registeredEmail') }}</label>
              <v-text-field v-model="forgotEmail" type="email" placeholder="example@email.com" />
            </div>
            <div class="pt-2 flex gap-3">
              <v-btn type="button" variant="tonal" size="large" class="flex-1" @click="emit('close')">{{ t('auth.cancel') }}</v-btn>
              <v-btn type="submit" color="primary" size="large" class="flex-[2]" :disabled="isSubmitting">
                <v-progress-circular v-if="isSubmitting" size="16" width="2" color="white" class="mr-2" />
                {{ t('auth.resetSend') }}
              </v-btn>
            </div>
          </template>

          <div v-if="mode !== 'forgot'">
            <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5 transition-colors duration-200">{{ t('auth.iidxId') }}</label>
            <v-text-field
              v-model="inputIidxId"
              type="text"
              required
              placeholder="1234-5678"
              pattern="\d{4}-\d{4}"
              maxlength="9"
              @input="formatIidxId"
            />
            <p v-if="mode === 'register'" class="text-xs text-slate-500 dark:text-slate-400 mt-1.5 ml-1 transition-colors duration-200">{{ t('auth.iidxIdHint') }}</p>
          </div>

          <div v-if="mode !== 'forgot'">
            <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5 transition-colors duration-200">{{ t('auth.password') }}</label>
            <v-text-field v-model="password" type="password" required placeholder="••••••••" minlength="4" />
          </div>

          <div v-if="mode === 'register'">
            <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5 transition-colors duration-200">{{ t('auth.passwordConfirm') }}</label>
            <v-text-field v-model="passwordConfirm" type="password" required placeholder="••••••••" minlength="4" />
          </div>

          <template v-if="mode === 'register'">
            <div>
              <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5 transition-colors duration-200">{{ t('auth.username') }}</label>
              <v-text-field v-model="displayName" type="text" required :placeholder="t('auth.displayName')" />
            </div>

            <div class="grid grid-cols-2 gap-4">
              <div>
                <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5 transition-colors duration-200">{{ t('auth.danRank') }}</label>
                <v-select
                  v-model="danRank"
                  :items="danRankOptions.map((rank) => ({ title: t(rank.labelKey), value: rank.value }))"
                  item-title="title"
                  item-value="value"
                />
              </div>
              <div>
                <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5 transition-colors duration-200">{{ t('auth.arenaRank') }}</label>
                <v-select v-model="arenaRank" :items="[...arenaRanks]" />
              </div>
            </div>

            <div>
              <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5 transition-colors duration-200">{{ t('auth.playSide') }}</label>
              <v-radio-group v-model="playSide" inline>
                <v-radio value="1P" class="mr-4">
                  <template #label><span class="text-sm font-bold text-slate-600 dark:text-slate-300">1P</span></template>
                </v-radio>
                <v-radio value="2P">
                  <template #label><span class="text-sm font-bold text-slate-600 dark:text-slate-300">2P</span></template>
                </v-radio>
              </v-radio-group>
              <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">{{ t('auth.playSideHint') }}</p>
            </div>
          </template>

          <div v-if="mode !== 'forgot'" class="pt-2 flex gap-3">
            <v-btn type="button" variant="tonal" size="large" class="flex-1" @click="emit('close')">
              {{ t('auth.cancel') }}
            </v-btn>
            <v-btn type="submit" color="primary" size="large" class="flex-[2]" :disabled="isSubmitting">
              <v-progress-circular v-if="isSubmitting" size="16" width="2" color="white" class="mr-2" />
              {{ mode === 'login' ? t('auth.loginBtn') : t('auth.registerBtn') }}
            </v-btn>
          </div>

        </form>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>
