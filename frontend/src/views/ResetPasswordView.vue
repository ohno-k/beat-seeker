<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useAuth } from '../composables/useAuth';

const { resetPassword } = useAuth();

const token = ref('');
const newPassword = ref('');
const newPasswordConfirm = ref('');
const isSubmitting = ref(false);
const errorMsg = ref('');
const successMsg = ref('');

onMounted(() => {
  const params = new URLSearchParams(window.location.search);
  token.value = params.get('token') ?? '';
  if (!token.value) {
    errorMsg.value = '無効なリセットリンクです。';
  }
});

const goToTop = () => { window.location.href = '/'; };

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
  <div class="min-h-screen flex items-center justify-center bg-slate-50 dark:bg-slate-950 p-4">
    <div class="w-full max-w-md bg-white dark:bg-slate-800 rounded-2xl shadow-lg border border-slate-200 dark:border-slate-700 p-8">
      <h1 class="text-xl font-black text-slate-800 dark:text-white mb-6">パスワードのリセット</h1>

      <div v-if="successMsg" class="mb-4 p-4 bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-200 dark:border-emerald-700 rounded-xl">
        <p class="text-sm font-bold text-emerald-700 dark:text-emerald-400">{{ successMsg }}</p>
        <button @click="goToTop" class="mt-3 text-sm font-bold text-blue-600 dark:text-blue-400 hover:underline">
          トップページへ戻る
        </button>
      </div>

      <form v-else @submit.prevent="handleSubmit" class="space-y-4">
        <div>
          <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">新しいパスワード</label>
          <input
            type="password"
            v-model="newPassword"
            placeholder="4文字以上"
            required
            :disabled="!token || isSubmitting"
            class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 transition-colors text-slate-800 dark:text-slate-100 disabled:opacity-50"
          />
        </div>
        <div>
          <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">新しいパスワード（確認）</label>
          <input
            type="password"
            v-model="newPasswordConfirm"
            placeholder="もう一度入力"
            required
            :disabled="!token || isSubmitting"
            class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 transition-colors text-slate-800 dark:text-slate-100 disabled:opacity-50"
          />
        </div>

        <div v-if="errorMsg" class="p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-700 rounded-xl">
          <p class="text-sm font-bold text-red-600 dark:text-red-400">{{ errorMsg }}</p>
        </div>

        <button
          type="submit"
          :disabled="!token || isSubmitting"
          class="w-full py-3 bg-blue-600 hover:bg-blue-700 text-white font-bold rounded-xl transition-colors disabled:opacity-50"
        >
          {{ isSubmitting ? '処理中...' : 'パスワードを変更する' }}
        </button>
      </form>
    </div>
  </div>
</template>
