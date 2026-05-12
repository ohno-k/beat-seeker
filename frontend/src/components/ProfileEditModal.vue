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
import { useKenbanSaraTierVisibility } from '../composables/useKenbanSaraTierVisibility';
import { useI18n } from '../composables/useI18n';
import { useToast } from '../composables/useToast';
import { useModalEscape } from '../composables/useModalEscape';
import { DAN_RANK_OPTIONS, ARENA_RANKS } from '../composables/constants';

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
// KENBAN/SARA-Tier 表示可否（サポーター限定オプトイン）。
const { showKenbanSaraTier, setKenbanSaraTier } = useKenbanSaraTierVisibility();

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
const showSupporterBorder = ref(true);

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
    showSupporterBorder.value = user.value.showSupporterBorder ?? true;

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
      email: email.value.trim() || undefined,
      showSupporterBorder: showSupporterBorder.value
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
  <div
    v-if="isOpen"
    role="dialog"
    aria-modal="true"
    aria-labelledby="profile-edit-title"
    class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/50 backdrop-blur-sm animate-in fade-in duration-200"
    @click.self="emit('close')"
  >
    <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-xl w-full max-w-md overflow-hidden flex flex-col max-h-[90vh] transition-colors duration-200">

      <div class="p-4 border-b border-slate-200 dark:border-slate-700 flex justify-between items-center bg-slate-50 dark:bg-slate-900/50">
        <h3 id="profile-edit-title" class="text-lg font-bold text-slate-800 dark:text-slate-100">{{ t('profile.editTitle') }}</h3>
        <button type="button" :aria-label="t('a11y.modal.close')" @click="emit('close')" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200">
          <svg xmlns="http://www.w3.org/2000/svg" aria-hidden="true" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>
      
      <div class="p-6 overflow-y-auto">
        <form @submit.prevent="handleUpdate" class="space-y-6">
          
          <div v-if="errorMsg" class="p-3 bg-red-50 dark:bg-red-900/30 text-red-700 dark:text-red-400 text-sm rounded-xl border border-red-200 dark:border-red-800/50 flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 shrink-0" viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7 4a1 1 0 11-2 0 1 1 0 012 0zm-1-9a1 1 0 00-1 1v4a1 1 0 102 0V6a1 1 0 00-1-1z" clip-rule="evenodd" />
            </svg>
            {{ errorMsg }}
          </div>

          <div v-if="successMsg" class="p-3 bg-emerald-50 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 text-sm rounded-xl border border-emerald-200 dark:border-emerald-800/50 flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 shrink-0" viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" />
            </svg>
            {{ successMsg }}
          </div>

          <div class="space-y-4">
            <div>
              <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.displayName') }}</label>
              <input type="text" v-model="displayName" required
                class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 transition-colors text-slate-800 dark:text-slate-100" />
            </div>

            <div class="grid grid-cols-2 gap-4">
              <div>
                <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.danRank') }}</label>
                <select v-model="danRank" 
                  class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 transition-colors text-slate-800 dark:text-slate-100 cursor-pointer appearance-none">
                  <option v-for="rank in danRankOptions" :key="rank.value" :value="rank.value">{{ t(rank.labelKey) }}</option>
                </select>
              </div>
              <div>
                <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.arenaRank') }}</label>
                <select v-model="arenaRank" 
                  class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 transition-colors text-slate-800 dark:text-slate-100 cursor-pointer appearance-none">
                  <option v-for="rank in arenaRanks" :key="rank" :value="rank">{{ rank }}</option>
                </select>
              </div>
            </div>

            <div class="grid grid-cols-1">
              <div>
                <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.playSide') }}</label>
                <div class="flex gap-4 py-2.5">
                  <label class="flex items-center gap-2 cursor-pointer group">
                    <input type="radio" v-model="playSide" value="1P" class="h-4 w-4 text-blue-600 border-slate-300" />
                    <span class="text-sm font-bold text-slate-600 dark:text-slate-300 group-hover:text-slate-900 transition-colors">1P</span>
                  </label>
                  <label class="flex items-center gap-2 cursor-pointer group">
                    <input type="radio" v-model="playSide" value="2P" class="h-4 w-4 text-blue-600 border-slate-300" />
                    <span class="text-sm font-bold text-slate-600 dark:text-slate-300 group-hover:text-slate-900 transition-colors">2P</span>
                  </label>
                </div>
              </div>
              <div>
                <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.privacySetting') }}</label>
                <select v-model="privacyLevel" 
                  class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 transition-colors text-slate-800 dark:text-slate-100 cursor-pointer appearance-none">
                  <option :value="0">{{ t('profile.privacyPublic') }}</option>
                  <option :value="1">{{ t('profile.privacyFriendsOnly') }}</option>
                  <option :value="2">{{ t('profile.privacyPrivate') }}</option>
                </select>
              </div>
            </div>
          </div>

          <!-- メール登録セクション（未登録ならバッジで促す） -->
          <div class="pt-4 border-t border-slate-100 dark:border-slate-700 space-y-4">
            <div class="flex items-center gap-2">
              <h4 class="text-sm font-bold text-slate-400 uppercase tracking-widest">{{ t('profile.emailSection') }}</h4>
              <span v-if="!user?.email" class="text-[10px] font-bold px-2 py-0.5 bg-amber-100 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400 rounded-full border border-amber-200 dark:border-amber-800/50">{{ t('profile.emailNotRegistered') }}</span>
            </div>
            <div>
              <input type="email" v-model="email" :placeholder="t('profile.emailNotRegistered')"
                class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 transition-colors text-slate-800 dark:text-slate-100 placeholder-slate-400" />
              <p class="text-xs text-slate-500 dark:text-slate-400 mt-1.5 ml-1">{{ t('profile.emailHint') }}</p>
            </div>
          </div>

          <div class="pt-4 border-t border-slate-100 dark:border-slate-700 space-y-4">
            <h4 class="text-sm font-bold text-slate-400 uppercase tracking-widest">{{ t('profile.passwordChange') }}</h4>
            
            <div>
              <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.currentPassword') }}</label>
              <input type="password" v-model="currentPassword" :placeholder="t('profile.currentPasswordPlaceholder')"
                class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 transition-colors text-slate-800 dark:text-slate-100" />
            </div>

            <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.newPassword') }}</label>
                <input type="password" v-model="newPassword" :placeholder="t('profile.newPasswordPlaceholder')"
                  class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 transition-colors text-slate-800 dark:text-slate-100" />
              </div>
              <div>
                <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5">{{ t('profile.confirmNewPassword') }}</label>
                <input type="password" v-model="newPasswordConfirm" :placeholder="t('profile.newPasswordPlaceholder')"
                  class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 transition-colors text-slate-800 dark:text-slate-100" />
              </div>
            </div>
          </div>

          <!-- 表示設定（RateTier トグル / サポーター金縁トグル） -->
          <div class="pt-4 border-t border-slate-100 dark:border-slate-700 space-y-4">
            <h4 class="text-sm font-bold text-slate-400 uppercase tracking-widest">{{ t('profile.displaySettings') }}</h4>
            <label class="flex items-center justify-between cursor-pointer group">
              <div>
                <p class="text-sm font-semibold text-slate-700 dark:text-slate-300">{{ t('profile.showRateTier') }}</p>
                <p class="text-xs text-slate-400 dark:text-slate-500 mt-0.5">{{ t('profile.showRateTierHint') }}</p>
              </div>
              <div class="relative inline-flex items-center ml-4 shrink-0">
                <input type="checkbox" :checked="showRateTier" @change="setRateTier(($event.target as HTMLInputElement).checked)" class="sr-only peer">
                <div class="w-11 h-6 bg-slate-200 dark:bg-slate-700 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-emerald-300 dark:peer-focus:ring-emerald-800 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white dark:peer-checked:after:border-slate-800 after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white dark:after:bg-slate-800 after:border-slate-300 dark:after:border-slate-600 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-emerald-500"></div>
              </div>
            </label>
            <!-- KENBAN/SARA-Tier 表示トグル（サポーター限定） -->
            <label v-if="user?.isSupporter" class="flex items-center justify-between cursor-pointer group">
              <div>
                <p class="text-sm font-semibold text-slate-700 dark:text-slate-300">KENBAN / SARA-TIER を表示</p>
                <p class="text-xs text-slate-400 dark:text-slate-500 mt-0.5">譜面の鍵盤/皿傾向で別ティアを算出するサポーター限定機能（実験段階）</p>
              </div>
              <div class="relative inline-flex items-center ml-4 shrink-0">
                <input type="checkbox" :checked="showKenbanSaraTier" @change="setKenbanSaraTier(($event.target as HTMLInputElement).checked)" class="sr-only peer">
                <div class="w-11 h-6 bg-slate-200 dark:bg-slate-700 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-cyan-300 dark:peer-focus:ring-cyan-800 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white dark:peer-checked:after:border-slate-800 after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white dark:after:bg-slate-800 after:border-slate-300 dark:after:border-slate-600 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-cyan-500"></div>
              </div>
            </label>
            <label v-if="user?.isSupporter" class="flex items-center justify-between cursor-pointer group">
              <div>
                <p class="text-sm font-semibold text-slate-700 dark:text-slate-300">{{ t('profile.showSupporterBorder') }}</p>
                <p class="text-xs text-slate-400 dark:text-slate-500 mt-0.5">{{ t('profile.showSupporterBorderHint') }}</p>
              </div>
              <div class="relative inline-flex items-center ml-4 shrink-0">
                <input type="checkbox" v-model="showSupporterBorder" class="sr-only peer">
                <div class="w-11 h-6 bg-slate-200 dark:bg-slate-700 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-amber-300 dark:peer-focus:ring-amber-800 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white dark:peer-checked:after:border-slate-800 after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white dark:after:bg-slate-800 after:border-slate-300 dark:after:border-slate-600 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-amber-500"></div>
              </div>
            </label>
          </div>

          <div class="pt-4 flex gap-3">
            <button type="button" @click="emit('close')"
              class="flex-1 py-3 px-4 bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 font-bold rounded-xl transition-colors">
              {{ t('common.cancel') }}
            </button>
            <button type="submit" :disabled="isSubmitting"
              class="flex-[2] py-3 px-4 bg-blue-600 hover:bg-blue-700 disabled:bg-blue-400 text-white font-bold rounded-xl shadow-sm hover:shadow transition-all flex items-center justify-center gap-2">
              <span v-if="isSubmitting" class="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin"></span>
              {{ isSubmitting ? t('profile.saving') : t('profile.saveChanges') }}
            </button>
          </div>
          
        </form>
      </div>
    </div>
  </div>
</template>
