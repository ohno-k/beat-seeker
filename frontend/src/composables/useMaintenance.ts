import { ref } from 'vue';

/**
 * 【Composable の役割】 サイト全体をメンテナンス画面に切り替えるためのフラグを提供する。
 *
 * 機能:
 *  - マスタースイッチ `MAINTENANCE_MODE` を `true` にすると、全ページが
 *    `MaintenanceView.vue`（「メンテナンス中です。」の全画面表示）に置き換わる
 *  - 運営が動作確認する用の抜け道として `?maintenance=off` を用意（下記参照）
 *
 * 使い方:
 * ```ts
 * const { isMaintenance } = useMaintenance();
 * // <MaintenanceView v-if="isMaintenance" />
 * ```
 *
 * メンテナンスを終了するときは `MAINTENANCE_MODE` を `false` に戻してデプロイするだけでよい。
 */

/**
 * メンテナンスモードのマスタースイッチ。
 *
 * `true`: サイト全体を「メンテナンス中です。」の画面に差し替える。
 * `false`: 通常どおりアプリを表示する。
 */
export const MAINTENANCE_MODE = true;

/** 抜け道フラグを保持する sessionStorage のキー。タブを閉じれば自動的に失効する。 */
const BYPASS_KEY = 'maintenanceBypass';

/**
 * 【関数の役割】 メンテナンス画面をスキップする「抜け道」が有効かどうかを判定する。
 *
 * 運営がメンテナンス中でも本来の画面を確認できるように、以下の URL パラメータを受け付ける。
 *  - `?maintenance=off`: 以降このタブではメンテナンス画面を出さない（sessionStorage に記録）
 *  - `?maintenance=on`: 抜け道を解除して通常どおりメンテナンス画面を出す
 *
 * @returns 抜け道が有効（＝メンテナンス画面を出さない）なら `true`
 */
function checkBypass(): boolean {
  try {
    const param = new URLSearchParams(window.location.search).get('maintenance');
    if (param === 'off') {
      sessionStorage.setItem(BYPASS_KEY, '1');
      return true;
    }
    if (param === 'on') {
      sessionStorage.removeItem(BYPASS_KEY);
      return false;
    }
    return sessionStorage.getItem(BYPASS_KEY) === '1';
  } catch {
    // プライベートブラウジング等で sessionStorage が使えない環境では抜け道なしとして扱う。
    return false;
  }
}

/**
 * メンテナンス状態を**アプリ全体で共有する**リアクティブ参照（モジュールシングルトン）。
 * `useDarkMode.ts` などと同じく、どこから呼んでも同じ ref を返すために関数外に置いている。
 */
const _isMaintenance = ref(MAINTENANCE_MODE && !checkBypass());

/**
 * メンテナンスモードの状態を取得する。
 * @returns `isMaintenance`: メンテナンス画面を表示すべきなら `true`
 */
export function useMaintenance() {
  return {
    /** メンテナンス画面を表示すべきかどうか。 */
    isMaintenance: _isMaintenance
  };
}
