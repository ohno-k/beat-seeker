<script setup lang="ts">
import { ref, watch } from 'vue';
import { useAuth } from '../composables/useAuth';
import { useRateTierVisibility } from '../composables/useRateTierVisibility';
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();

const props = defineProps<{
  isOpen: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
}>();

const { user, updateProfile } = useAuth();
const { showRateTier, setRateTier } = useRateTierVisibility();

const isSubmitting = ref(false);
const errorMsg = ref('');
const successMsg = ref('');

// Form fields
const displayName = ref('');
const danRank = ref('');
const arenaRank = ref('');
const playSide = ref('');
const privacyLevel = ref(0);

const email = ref('');
const currentPassword = ref('');
const newPassword = ref('');
const newPasswordConfirm = ref('');

const danRanks = [
  '七級', '六級', '五級', '四級', '三級', '二級', '一級',
  '初段', '二段', '三段', '四段', '五段', '六段', '七段', '八段', '九段', '十段', '中伝', '皆伝'
];

const arenaRanks = [
  'A1', 'A2', 'A3', 'A4', 'A5',
  'B1', 'B2', 'B3', 'B4', 'B5',
  'C1', 'C2', 'C3', 'C4', 'C5',
  'D1', 'D2', 'D3', 'D4', 'D5'
];

// Initialize form when modal opens
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

const handleUpdate = async () => {
  errorMsg.value = '';
  successMsg.value = '';
  
  if (!displayName.value.trim()) {
    errorMsg.value = t('profile.displayNameRequired');
    return;
  }

  // Password change validation
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
    successMsg.value = t('profile.updateSuccess');
    
    // Clear password fields
    currentPassword.value = '';
    newPassword.value = '';
    newPasswordConfirm.value = '';
    
    setTimeout(() => {
        if (successMsg.value) emit('close');
    }, 1500);

  } catch (err: any) {
    errorMsg.value = err.message || t('profile.updateFailed');
  } finally {
    isSubmitting.value = false;
  }
};
</script>

<template>
  <div v-if="isOpen" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/50 backdrop-blur-sm animate-in fade-in duration-200" @click.self="emit('close')">
    <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-xl w-full max-w-md overflow-hidden flex flex-col max-h-[90vh] transition-colors duration-200">
      
      <div class="p-4 border-b border-slate-200 dark:border-slate-700 flex justify-between items-center bg-slate-50 dark:bg-slate-900/50">
        <h3 class="text-lg font-bold text-slate-800 dark:text-slate-100">{{ t('profile.editTitle') }}</h3>
        <button @click="emit('close')" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
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
                  <option v-for="rank in danRanks" :key="rank" :value="rank">{{ rank }}</option>
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

          <!-- Email section -->
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

          <!-- Display Settings -->
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
