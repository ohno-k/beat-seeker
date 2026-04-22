import { ref, onUnmounted } from 'vue'

/**
 * 【Composable の役割】 ユーザーが開いているタブで、アプリが新バージョンにデプロイされたことを検知する。
 *
 * 機能:
 *  - 5 分ごとに `/version.json` を取得し、ビルド時に埋め込んだ `__APP_VERSION__` と比較する
 *  - バージョンが変われば `hasUpdate` を `true` にし、UI 側でリロード促進バナー等を出せるようにする
 *
 * 使い方:
 * ```ts
 * const { hasUpdate } = useAppUpdate();
 * // <div v-if="hasUpdate">新バージョンが利用可能です。リロードしてください。</div>
 * ```
 */

/** ポーリング間隔（ミリ秒）。5 分ごとに新バージョンをチェックする。 */
const CHECK_INTERVAL = 5 * 60 * 1000 // 5 分

export function useAppUpdate() {
  /** 新バージョンが検出されたかどうか。テンプレートからバインドする。 */
  const hasUpdate = ref(false)
  /** ビルド時に Vite の `define` で埋め込まれたバージョン文字列（コミットハッシュ等）。 */
  const currentVersion = __APP_VERSION__

  /**
   * サーバ上の `/version.json` を取得して現在のビルドと比較する。
   * ネットワークエラーは黙って無視する（次回のポーリングで再試行される）。
   */
  const check = async () => {
    try {
      // クエリに現在時刻を付与してブラウザ/プロキシのキャッシュを回避する
      const res = await fetch(`/version.json?t=${Date.now()}`)
      if (!res.ok) return
      const data = await res.json()
      // バージョン差異があれば即座にフラグを立てる
      if (data.version && data.version !== currentVersion) {
        hasUpdate.value = true
      }
    } catch {
      // ネットワークエラー — 無視して次回に期待
    }
  }

  // コンポーネントライフサイクルに紐付けて定期実行する。
  // 最初の実行は CHECK_INTERVAL 後（即チェックはしない）。
  const timer = setInterval(check, CHECK_INTERVAL)
  // アンマウント時にタイマーを掃除（リーク防止）
  onUnmounted(() => clearInterval(timer))

  return {
    /** 新バージョン検出フラグ。検出後は `true` のまま固定される。 */
    hasUpdate
  }
}
