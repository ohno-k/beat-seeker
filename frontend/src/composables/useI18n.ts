import { ref, computed } from 'vue';
import { translations } from '../locales';
import { API_BASE, TOKEN_KEY } from './constants';
export const currentLang = ref<string>(localStorage.getItem('beat-seeker-lang') || 'ja');

export function useI18n() {
  const t = (key: string, params?: Record<string, any>) => {
    const lang = currentLang.value;
    const langTranslations = translations[lang] || translations['ja'];
    let text = langTranslations[key] || translations['ja'][key] || key;

    if (params) {
      Object.entries(params).forEach(([k, v]) => {
        text = text.replace(`{${k}}`, String(v));
      });
    }

    return text;
  };

  const setLanguage = (lang: string) => {
    if (translations[lang]) {
      currentLang.value = lang;
      localStorage.setItem('beat-seeker-lang', lang);
      document.documentElement.lang = lang;

      const token = localStorage.getItem(TOKEN_KEY);
      if (token) {
        fetch(`${API_BASE}/api/auth/me/profile`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
          body: JSON.stringify({ language: lang })
        }).catch(() => {});
      }
    }
  };

  return {
    t,
    currentLang: computed(() => currentLang.value),
    setLanguage,
    availableLanguages: Object.keys(translations)
  };
}
