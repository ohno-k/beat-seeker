/**
 * 【ユーティリティの役割】 Android アプリの非表示 WebView（eagate オリジン）で実行され、
 * スコア CSV と ARENA 対戦履歴を収集してネイティブ側へ受け渡すエントリスクリプト。
 *
 * ビルド成果物は `public/native-scraper.js`。アプリはこれを実行時に beat-seeker から
 * 読み込んで注入する（アプリに同梱しない）。eagate の HTML 構造変更に対して
 * アプリの再リリース無しで追従できるようにするため。
 *
 * 収集処理そのものは `eagateScraper.ts` の {@link scrapeEagate} で、ブックマークレットと共通。
 * このファイルの責務はネイティブとの通信プロトコルだけ。
 *
 * 【通信プロトコル】
 * ネイティブ側は AndroidX の `WebViewCompat.addWebMessageListener` で
 * `window.bsBridge` を注入する（許可オリジンは eagate のみ）。本スクリプトは
 * `bsBridge.postMessage(JSON.stringify(msg))` で以下のメッセージを送る:
 *
 *  - `{kind:'progress', message}`              … 進捗表示用
 *  - `{kind:'needLogin'}`                      … eagate 未ログインと推定される
 *  - `{kind:'error', message}`                 … 収集中の例外
 *  - `{kind:'chunk', seq, total, data}`        … 結果 JSON の分割データ（seq は 0 始まり）
 *
 * 結果 JSON は数百 KB になりうる。WebView のメッセージ受け渡しで大きな文字列は不安定なため、
 * {@link CHUNK_SIZE} 文字ずつに分割して送り、ネイティブ側で seq 順に連結して復元する。
 * `total` は分割数で、ネイティブは total 個そろった時点で完了と判断する。
 *
 * なぜ `addJavascriptInterface` ではなく WebMessageListener か:
 *  - `addJavascriptInterface` は注入先ページ全体に無条件でネイティブ API を露出させる。
 *    eagate は外部サイトなので、オリジンを限定できる WebMessageListener を使う。
 */
import { scrapeEagate } from './eagateScraper';

/** 1 メッセージあたりの最大文字数。WebView のメッセージ受け渡しの安定性を優先した値。 */
const CHUNK_SIZE = 64 * 1024;

/** ネイティブ側が注入するブリッジオブジェクト（WebMessageListener）。 */
type NativeBridge = { postMessage: (message: string) => void };

declare global {
  interface Window {
    bsBridge?: NativeBridge;
    /** 二重注入による多重実行を防ぐためのフラグ。 */
    __beatSeekerScraping?: boolean;
  }
}

/** 【関数の役割】 ネイティブへ 1 メッセージ送る。ブリッジ未注入時は握り潰す（通常の Web では動かないため）。 */
function post(message: Record<string, unknown>): void {
  try {
    window.bsBridge?.postMessage(JSON.stringify(message));
  } catch (e) {
    console.warn('bsBridge postMessage failed', e);
  }
}

(async function () {
  if (!window.bsBridge) {
    // ネイティブ以外の環境で誤って読み込まれた場合は何もしない。
    console.warn('native-scraper.js: bsBridge not found; aborting.');
    return;
  }
  // アプリ側のリトライ等でスクリプトが二重注入されても収集は 1 回だけにする。
  if (window.__beatSeekerScraping) return;
  window.__beatSeekerScraping = true;

  try {
    const result = await scrapeEagate((message) => post({ kind: 'progress', message }));

    // 未ログイン時、eagate は djdata をログインページへリダイレクトするため
    // 譜面も ARENA も 0 件になる。これを未ログインの推定条件とする。
    if (result.chartCount === 0 && result.battles.length === 0) {
      post({ kind: 'needLogin' });
      return;
    }

    const { chartCount, songCount, ...payload } = result;
    post({
      kind: 'progress',
      message: '取得完了 スコア ' + chartCount + '譜面・' + songCount + '曲',
    });

    const json = JSON.stringify(payload);
    const total = Math.max(1, Math.ceil(json.length / CHUNK_SIZE));
    for (let seq = 0; seq < total; seq++) {
      post({
        kind: 'chunk',
        seq,
        total,
        data: json.slice(seq * CHUNK_SIZE, (seq + 1) * CHUNK_SIZE),
      });
    }
  } catch (e) {
    post({ kind: 'error', message: e instanceof Error ? e.message : String(e) });
  } finally {
    window.__beatSeekerScraping = false;
  }
})();
