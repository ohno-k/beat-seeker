<script setup lang="ts">
/**
 * 【Viewの役割】 パスワードリセットページ。
 *
 * 機能:
 *  - URL クエリパラメータの `token` を読み、メール経由のリセットリンクであることを検証する。
 *  - 新しいパスワードと確認入力を受け取り、最低文字数と一致をクライアント側で検証する。
 *  - `resetPassword` API を叩いて変更を確定し、成功時はトップページへ誘導するボタンを表示する。
 *
 * 依存:
 *  - `useAuth` — `resetPassword` API ラッパー。
 */
import { ref, onMounted } from 'vue';
import { useAuth } from '../composables/useAuth';

const { resetPassword } = useAuth();

/** URL クエリから取り出したリセットトークン。空なら無効扱い */
const token = ref('');
/** ユーザーが入力する新しいパスワード */
const newPassword = ref('');
/** 新パスワードの確認入力（タイポ防止のための二重入力） */
const newPasswordConfirm = ref('');
/** 送信中フラグ。重複クリック防止と UI 無効化に使用 */
const isSubmitting = ref(false);
/** 画面に表示するエラーメッセージ */
const errorMsg = ref('');
/** 画面に表示する成功メッセージ（サーバー返却の文言） */
const successMsg = ref('');

// マウント直後に URL クエリ `?token=...` を取り出して保持する。
// トークンが空ならフォームを無効化し、ユーザーにメッセージを表示する。
onMounted(() => {
  const params = new URLSearchParams(window.location.search);
  token.value = params.get('token') ?? '';
  if (!token.value) {
    errorMsg.value = '無効なリセットリンクです。';
  }
});

/** トップページへ遷移するための関数。成功メッセージ表示後の導線 */
const goToTop = () => { window.location.href = '/'; };

/**
 * 【関数の役割】 フォーム送信ハンドラ。クライアント側の検証 → API 呼び出し → 成功/失敗メッセージ表示の流れを管理する。
 *
 * 処理の流れ:
 *  - 手順1: エラーメッセージをクリアする。
 *  - 手順2: パスワードが4文字未満なら拒否。
 *  - 手順3: 確認入力と一致しなければ拒否。
 *  - 手順4: `isSubmitting` を立てて `resetPassword` を呼び出し、成功文言を保存。
 *  - 手順5: 例外時はメッセージを表示。最終的に送信フラグを降ろす。
 */
const handleSubmit = async () => {
  errorMsg.value = '';
  if (newPassword.value.length < 4) {
    errorMsg.value = 'パスワードは4文字以上で入力してください。';
    return;
  }
  if (newPassword.value !== newPasswordConfirm.value) {
    errorMsg.value = 'パスワードが一致しません。';
    return;
  }
  isSubmitting.value = true;
  try {
    const msg = await resetPassword(token.value, newPassword.value);
    successMsg.value = msg;
  } catch (e: any) {
    errorMsg.value = e.message;
  } finally {
    isSubmitting.value = false;
  }
};
</script>

<template>
  <!-- 画面全体: フルビューポートで中央にリセットフォームカードを配置 -->
  <div class="min-h-screen flex items-center justify-center bg-slate-50 dark:bg-slate-950 p-4">
    <!-- カード本体: 新パスワード入力 or 成功メッセージを表示 -->
    <v-card class="w-full max-w-md p-8">
      <h1 class="text-xl font-bold text-slate-800 dark:text-white mb-6">パスワードのリセット</h1>

      <!-- 成功時: 完了メッセージとトップページ導線 -->
      <v-alert v-if="successMsg" type="success" class="mb-4">
        <p class="text-sm font-bold">{{ successMsg }}</p>
        <v-btn variant="text" color="primary" size="small" class="mt-3 px-0 text-sm" @click="goToTop">
          トップページへ戻る
        </v-btn>
      </v-alert>

      <!-- 通常時: パスワード入力フォーム。`prevent` でブラウザ送信をブロックしてJSで処理 -->
      <form v-else @submit.prevent="handleSubmit" class="space-y-4">
        <div>
          <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">新しいパスワード</label>
          <v-text-field
            type="password"
            v-model="newPassword"
            placeholder="4文字以上"
            required
            :disabled="!token || isSubmitting"
          />
        </div>
        <div>
          <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">新しいパスワード（確認）</label>
          <v-text-field
            type="password"
            v-model="newPasswordConfirm"
            placeholder="もう一度入力"
            required
            :disabled="!token || isSubmitting"
          />
        </div>

        <!-- エラー表示: クライアント検証失敗 or API エラー時 -->
        <v-alert v-if="errorMsg" type="error" class="text-sm font-bold">
          {{ errorMsg }}
        </v-alert>

        <v-btn
          type="submit"
          color="primary"
          block
          size="large"
          :disabled="!token || isSubmitting"
        >
          {{ isSubmitting ? '処理中...' : 'パスワードを変更する' }}
        </v-btn>
      </form>
    </v-card>
  </div>
</template>
