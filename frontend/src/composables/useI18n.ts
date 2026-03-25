import { ref, computed } from 'vue';
import { translations } from '../locales';

const currentLang = ref<string>(localStorage.getItem('beat-seeker-lang') || 'ja');

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
    }
  };

  return {
    t,
    currentLang: computed(() => currentLang.value),
    setLanguage,
    availableLanguages: Object.keys(translations)
  };
}
