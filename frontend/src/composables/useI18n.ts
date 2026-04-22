import { ref, computed } from 'vue';
import { translations } from '../locales';
import { API_BASE, TOKEN_KEY } from './constants';

/**
 * 【Composable の役割】 アプリ全体の多言語対応（i18n）を提供する。
 *
 * 機能:
 *  - 現在の言語を localStorage から復元（未設定時はデフォルト日本語）
 *  - 翻訳キー → 現在言語の文字列に解決する `t()` 関数
 *  - 言語切替時に `<html lang>` と、ログイン中ならサーバ側プロフィールも同期
 *
 * 使い方:
 * ```ts
 * const { t, currentLang, setLanguage } = useI18n();
 * // {{ t('hello', { name: 'IIDX' }) }}
 * // setLanguage('en');
 * ```
 */

/**
 * 現在選択中の言語コード（'ja' / 'en' など）。
 *
 * モジュールトップに置くことで、画面・コンポーネントをまたいで
 * 同じ ref インスタンスが参照される（シングルトン）。
 */
export const currentLang = ref<string>(localStorage.getItem('beat-seeker-lang') || 'ja');

export function useI18n() {
  /**
   * 翻訳キーを現在言語の文字列に変換する。
   *
   * フォールバック順:
   *  1. 現在言語の辞書
   *  2. 日本語辞書（マスター言語）
   *  3. キー名そのもの（未翻訳の検出用）
   *
   * @param key 翻訳キー
   * @param params `{name}` 形式のプレースホルダを差し替えるパラメータ
   */
  const t = (key: string, params?: Record<string, any>) => {
    const lang = currentLang.value;
    const langTranslations = translations[lang] || translations['ja'];
    let text = langTranslations[key] || translations['ja'][key] || key;

    // `{name}` 形式のプレースホルダを単純置換で差し替える
    if (params) {
      Object.entries(params).forEach(([k, v]) => {
        text = text.replace(`{${k}}`, String(v));
      });
    }

    return text;
  };

  /**
   * 言語を切り替える。
   *
   * 副作用:
   *  - `currentLang` 更新（リアクティブに全コンポーネントへ伝播）
   *  - localStorage へ永続化
   *  - `<html lang>` 属性を更新（スクリーンリーダー等のため）
   *  - ログイン済みならユーザープロフィールにも保存（別端末ログイン時の再現用）
   */
  const setLanguage = (lang: string) => {
    if (translations[lang]) {
      currentLang.value = lang;
      localStorage.setItem('beat-seeker-lang', lang);
      document.documentElement.lang = lang;

      // サーバ永続化（失敗は黙って無視 - ローカル状態は既に更新済みのため）
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
    /** 翻訳関数。テンプレートやスクリプト内で `t('key')` として使う。 */
    t,
    /** 現在言語（読み取り専用 computed）。テンプレートで現在選択表示に使う。 */
    currentLang: computed(() => currentLang.value),
    /** 言語切替関数。UI の言語選択ドロップダウンから呼ぶ。 */
    setLanguage,
    /** 選択可能な言語コード一覧（例: `['ja', 'en']`）。 */
    availableLanguages: Object.keys(translations)
  };
}
