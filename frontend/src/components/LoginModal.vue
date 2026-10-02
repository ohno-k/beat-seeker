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
    <v-card>

      <!-- タブ切替（ログイン / 新規登録 / パスワードを忘れた） -->
      <v-tabs
        :model-value="mode"
        grow
        color="primary"
      >
        <v-tab value="login" @click="switchMode('login')">
          {{ t('auth.login') }}
        </v-tab>
        <v-tab value="register" @click="switchMode('register')">
          {{ t('auth.register') }}
        </v-tab>
        <v-tab value="forgot" @click="switchMode('forgot')">
          {{ t('auth.forgotPassword') }}
        </v-tab>
      </v-tabs>
      <v-divider />

      <v-card-text class="overflow-y-auto">
        <form @submit.prevent="handleSubmit" class="space-y-2">

          <v-alert v-if="errorMsg" type="error" density="compact" class="mb-4">
            {{ errorMsg }}
          </v-alert>
          <v-alert v-if="successMsg" type="success" density="compact" class="mb-4">
            {{ successMsg }}
          </v-alert>

          <!-- パスワードリセットフォーム -->
          <template v-if="mode === 'forgot'">
            <p class="text-sm text-slate-600 dark:text-slate-400 mb-4">{{ t('auth.forgotHint') }}</p>
            <v-text-field
              v-model="inputIidxId"
              :label="t('auth.iidxId')"
              type="text"
              placeholder="1234-5678"
              pattern="\d{4}-\d{4}"
              maxlength="9"
              @input="formatIidxId"
            />
            <v-text-field v-model="forgotEmail" :label="t('auth.registeredEmail')" type="email" placeholder="example@email.com" />
            <div class="pt-2 flex gap-3">
              <v-btn type="button" variant="text" size="large" class="flex-1" @click="emit('close')">{{ t('auth.cancel') }}</v-btn>
              <v-btn type="submit" color="primary" variant="flat" size="large" class="flex-[2]" :loading="isSubmitting" :disabled="isSubmitting">
                {{ t('auth.resetSend') }}
              </v-btn>
            </div>
          </template>

          <v-text-field
            v-if="mode !== 'forgot'"
            v-model="inputIidxId"
            :label="t('auth.iidxId')"
            type="text"
            required
            placeholder="1234-5678"
            pattern="\d{4}-\d{4}"
            maxlength="9"
            :hint="mode === 'register' ? t('auth.iidxIdHint') : undefined"
            :persistent-hint="mode === 'register'"
            @input="formatIidxId"
          />

          <v-text-field v-if="mode !== 'forgot'" v-model="password" :label="t('auth.password')" type="password" required placeholder="••••••••" minlength="4" />

          <v-text-field v-if="mode === 'register'" v-model="passwordConfirm" :label="t('auth.passwordConfirm')" type="password" required placeholder="••••••••" minlength="4" />

          <template v-if="mode === 'register'">
            <v-text-field v-model="displayName" :label="t('auth.username')" type="text" required :placeholder="t('auth.displayName')" />

            <div class="grid grid-cols-2 gap-4">
              <v-select
                v-model="danRank"
                :label="t('auth.danRank')"
                :items="danRankOptions.map((rank) => ({ title: t(rank.labelKey), value: rank.value }))"
                item-title="title"
                item-value="value"
              />
              <v-select v-model="arenaRank" :label="t('auth.arenaRank')" :items="[...arenaRanks]" />
            </div>

            <v-radio-group
              v-model="playSide"
              :label="t('auth.playSide')"
              inline
              color="primary"
              :hint="t('auth.playSideHint')"
              persistent-hint
            >
              <v-radio value="1P" label="1P" />
              <v-radio value="2P" label="2P" />
            </v-radio-group>
          </template>

          <div v-if="mode !== 'forgot'" class="pt-2 flex gap-3">
            <v-btn type="button" variant="text" size="large" class="flex-1" @click="emit('close')">
              {{ t('auth.cancel') }}
            </v-btn>
            <v-btn type="submit" color="primary" variant="flat" size="large" class="flex-[2]" :loading="isSubmitting" :disabled="isSubmitting">
              {{ mode === 'login' ? t('auth.loginBtn') : t('auth.registerBtn') }}
            </v-btn>
          </div>

        </form>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>
