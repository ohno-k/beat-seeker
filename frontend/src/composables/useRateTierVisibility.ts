import { ref } from 'vue';
import { API_BASE, TOKEN_KEY } from './constants';

/**
 * 【Composable の役割】 Rate-Tier（レート階級）表示の ON/OFF を管理する。
 *
 * 機能:
 *  - 初期値は localStorage から復元（未設定なら ON とみなす）
 *  - ログイン中のユーザーはサーバー側プロフィールにも同期保存
 *
 * 使い方:
 * ```ts
 * const { showRateTier, toggleRateTier, setRateTier } = useRateTierVisibility();
 * ```
 */

/**
 * Rate-Tier の表示状態（`true`: 表示, `false`: 非表示）。
 *
 * モジュールトップに置くことで、どのコンポーネントから呼んでも同じ ref が共有される。
 * localStorage に 'false' が保存されていた場合だけ非表示、それ以外（未設定含む）は表示。
 */
export const showRateTierRef = ref(localStorage.getItem('showRateTier') !== 'false');

/**
 * 選択状態をサーバー側のユーザープロフィールに保存する。
 * ログイン中だけ実行。ネットワーク失敗は黙殺（ローカル状態は既に更新済み）。
 */
function saveToDb(value: boolean) {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) {
    fetch(`${API_BASE}/api/auth/me/profile`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
      body: JSON.stringify({ showRateTier: value })
    }).catch(() => {});
  }
}

export function useRateTierVisibility() {
  /** 現在値を反転させ、localStorage・サーバ両方に同期する。 */
  const toggleRateTier = () => {
    showRateTierRef.value = !showRateTierRef.value;
    localStorage.setItem('showRateTier', String(showRateTierRef.value));
    saveToDb(showRateTierRef.value);
  };

  /** 明示的に `true` / `false` を指定してセットする（初期化復元時などに使う）。 */
  const setRateTier = (value: boolean) => {
    showRateTierRef.value = value;
    localStorage.setItem('showRateTier', String(value));
    saveToDb(value);
  };

  return {
    /** 表示状態の ref。テンプレートで `v-if="showRateTier"` の形で使う。 */
    showRateTier: showRateTierRef,
    /** トグル関数。ボタン `@click` で呼ぶ想定。 */
    toggleRateTier,
    /** 明示セット関数。サーバから取得した値で復元する場合などに使う。 */
    setRateTier
  };
}
