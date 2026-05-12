import { ref } from 'vue';
import { API_BASE, TOKEN_KEY } from './constants';

/**
 * 【Composable の役割】 KENBAN-TIER / SARA-TIER 表示の ON/OFF を管理する。
 *
 * 機能:
 *  - サポーター（`isSupporter === true`）限定のオプトイン機能
 *  - 初期値は localStorage から復元（未設定なら OFF）
 *  - ログイン中のユーザーはサーバー側プロフィールにも同期保存
 *
 * 仕様:
 *  - useRateTierVisibility と同じパターンで作っているが、初期値は **OFF**
 *  - 非サポーターは `setKenbanSaraTier(true)` を呼んでもサーバー側で無視される
 */

/**
 * KENBAN/SARA-Tier の表示状態（`true`: 表示, `false`: 非表示）。
 *
 * モジュールトップに置くことで、どのコンポーネントから呼んでも同じ ref が共有される。
 * localStorage に 'true' が保存されていた場合だけ表示、それ以外（未設定含む）は非表示。
 */
export const showKenbanSaraTierRef = ref(localStorage.getItem('showKenbanSaraTier') === 'true');

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
      body: JSON.stringify({ showKenbanSaraTier: value })
    }).catch(() => {});
  }
}

export function useKenbanSaraTierVisibility() {
  /** 現在値を反転させ、localStorage・サーバ両方に同期する。 */
  const toggleKenbanSaraTier = () => {
    showKenbanSaraTierRef.value = !showKenbanSaraTierRef.value;
    localStorage.setItem('showKenbanSaraTier', String(showKenbanSaraTierRef.value));
    saveToDb(showKenbanSaraTierRef.value);
  };

  /** 明示的に `true` / `false` を指定してセットする（初期化復元時などに使う）。 */
  const setKenbanSaraTier = (value: boolean) => {
    showKenbanSaraTierRef.value = value;
    localStorage.setItem('showKenbanSaraTier', String(value));
    saveToDb(value);
  };

  return {
    /** 表示状態の ref。テンプレートで `v-if="showKenbanSaraTier"` の形で使う。 */
    showKenbanSaraTier: showKenbanSaraTierRef,
    /** トグル関数。ボタン `@click` で呼ぶ想定。 */
    toggleKenbanSaraTier,
    /** 明示セット関数。サーバから取得した値で復元する場合などに使う。 */
    setKenbanSaraTier
  };
}
