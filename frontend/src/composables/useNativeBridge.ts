/**
 * 【コンポーザブルの役割】 Android アプリ（WebView ラッパー）との橋渡しを担う。
 *
 * 背景: ブラウザのクロスオリジン制約により、beat-seeker のページから eagate のスコアページを
 * ユーザーのログイン Cookie 付きで取得することはできない。そのため Web ではブックマークレット
 * （eagate 上で自前のコードを走らせる）を使っている。
 * Android アプリ版では、アプリが非表示 WebView で eagate を開いて同じ収集スクリプト
 * （`/native-scraper.js`）を注入し、結果をこのページへ返す。ユーザー操作は
 * 「アプリ内のボタンを 1 回押す」だけになる。
 *
 * 【アプリ側から注入されるもの】
 * `window.BeatSeekerNative`（`addJavascriptInterface`。beat-seeker オリジンの WebView にのみ注入）
 *  - `startImport(eagateUrl: string): void`
 *      … 収集を開始する。開く eagate のページ URL は「作品バージョンを知っている」Web 側が渡す。
 *        こうすることで、新作稼働時にアプリを再リリースせず {@link CURRENT_VERSION} の更新だけで追従できる。
 *  - `version(): string` … アプリのバージョン名（任意）。
 *
 * 【このページ側が公開するもの】
 * `window.__beatSeekerNative`（アプリが `evaluateJavascript` で呼ぶ）
 *  - `onProgress(message)`                  … 進捗表示
 *  - `onResultChunk(seq, total, data)`      … 結果 JSON の分割データ（seq は 0 始まり）
 *  - `onNeedLogin()`                        … eagate 未ログイン。アプリがログイン画面を出す
 *  - `onError(message)`                     … 失敗（ログインをキャンセルした場合も含む）
 *
 * `onNeedLogin` では待機を解除しない。アプリはログイン成功後に自動で収集を再実行するため、
 * ここで Promise を棄却してしまうと再実行の結果を誰も受け取れなくなる。
 * ログインがキャンセルされた場合はアプリが `onError` を送ってくるので、そこで棄却される。
 *
 * 結果 JSON は数百 KB になりうる。WebView 越しに巨大な文字列を 1 回で渡すのは不安定なため、
 * アプリ側で分割して送り、ここで seq 順に連結して復元する。
 *
 * アプリが未インストールの通常ブラウザでは `isNativeApp` が false になり、UI 側は
 * 従来どおりブックマークレット導線だけを表示する。
 */
import { ref, computed } from 'vue';
import { CURRENT_VERSION } from '../utils/iidxVersions';
import { useI18n } from './useI18n';

/** アプリが注入するネイティブ側インターフェース。 */
type BeatSeekerNative = {
  startImport: (eagateUrl: string) => void;
  version?: () => string;
};

/** このページがアプリへ公開するコールバック群。 */
type NativeCallbacks = {
  onProgress: (message: string) => void;
  onResultChunk: (seq: number, total: number, data: string) => void;
  onError: (message: string) => void;
  onNeedLogin: () => void;
};

declare global {
  interface Window {
    BeatSeekerNative?: BeatSeekerNative;
    __beatSeekerNative?: NativeCallbacks;
  }
}

/** 取り込みの進行状態。 */
export type NativeImportStatus = 'idle' | 'running' | 'error';

/**
 * アプリが最初に開く eagate のページ。
 * ここから収集スクリプトが `location.pathname` を見て作品バージョンを判定するため、
 * 必ずバージョン番号を含む URL にする。
 */
const EAGATE_ENTRY_URL = `https://p.eagate.573.jp/game/2dx/${CURRENT_VERSION}/djdata/index.html`;

/** 現在の進行状態（同時に 2 つ走らせない）。 */
const status = ref<NativeImportStatus>('idle');
/** 進捗メッセージ（アプリから逐次送られてくる）。 */
const message = ref('');

/** 実行中の取り込みの解決関数。ネイティブからのコールバックで解決／棄却する。 */
let pending: { resolve: (json: string) => void; reject: (e: Error) => void } | null = null;
/** 受信済みチャンク（seq → data）。全 total 個そろった時点で連結して解決する。 */
let chunks: string[] = [];
/** 受信済みチャンク数。 */
let received = 0;

/** 【関数の役割】 実行中の取り込みを終了させ、待機中の Promise を解決／棄却する。 */
function settle(error: Error | null, json?: string): void {
  const p = pending;
  pending = null;
  chunks = [];
  received = 0;
  status.value = error ? 'error' : 'idle';
  message.value = '';
  if (!p) return;
  if (error) p.reject(error);
  else p.resolve(json ?? '');
}

// アプリから呼ばれるコールバックを登録する。アプリ以外の環境では誰も呼ばないため無害。
if (typeof window !== 'undefined' && !window.__beatSeekerNative) {
  window.__beatSeekerNative = {
    onProgress(msg: string) {
      message.value = msg;
    },
    onResultChunk(seq: number, total: number, data: string) {
      if (!pending) return; // 取り込み終了後に遅れて届いたものは捨てる
      if (chunks.length !== total) {
        chunks = new Array(total).fill('');
        received = 0;
      }
      // 同じ seq が二重に届いても受信数を二重計上しない。
      if (!chunks[seq]) received++;
      chunks[seq] = data;
      message.value = `データ受信中… ${received}/${total}`;
      if (received >= total) settle(null, chunks.join(''));
    },
    onError(msg: string) {
      settle(new Error(msg || 'native import failed'));
    },
    onNeedLogin() {
      // アプリ側が eagate のログイン画面を表示し、成功したら収集を自動で再実行する。
      // よってここでは待機を解除せず、表示を切り替えるだけにする。
      message.value = useI18n().t('import.nativeLoggingIn');
    },
  };
}

/**
 * 【コンポーザブル】 ネイティブ取り込み機能へのアクセスを提供する。
 */
export function useNativeBridge() {
  /** Android アプリ内で表示されているか（＝1 タップ取り込みが使えるか）。 */
  const isNativeApp = computed(() => typeof window !== 'undefined' && !!window.BeatSeekerNative);

  /**
   * 【関数の役割】 アプリ側に収集を依頼し、結果 JSON 文字列を返す。
   *
   * 返る JSON はブックマークレットがクリップボードに入れるものと同一形式のため、
   * 既存の取り込み処理（`UnifiedImport` の `processText`）へそのまま流せる。
   *
   * @throws 収集に失敗した場合。未ログインの場合はメッセージが `NEED_LOGIN`。
   */
  const startNativeImport = (): Promise<string> => {
    if (!window.BeatSeekerNative) return Promise.reject(new Error('native bridge unavailable'));
    if (pending) return Promise.reject(new Error('import already running'));

    status.value = 'running';
    message.value = '';
    chunks = [];
    received = 0;
    return new Promise<string>((resolve, reject) => {
      pending = { resolve, reject };
      try {
        window.BeatSeekerNative!.startImport(EAGATE_ENTRY_URL);
      } catch (e) {
        settle(e instanceof Error ? e : new Error(String(e)));
      }
    });
  };

  return { isNativeApp, status, message, startNativeImport };
}
