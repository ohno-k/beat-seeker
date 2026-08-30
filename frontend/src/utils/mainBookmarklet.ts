/**
 * 【ユーティリティの役割】 beat-seeker 汎用ブックマークレットのメインスクリプトを提供する。
 *
 * ブックマークレットとは: ブラウザの「お気に入り」に `javascript:` 始まりの URL を登録し、
 * クリックするだけで任意のページ上でスクリプトを実行できる仕組み。
 *
 * このファイルはあくまで「エントリ」で、実際の収集処理は `eagateScraper.ts` の
 * {@link scrapeEagate} が担う（Android アプリ用エントリ `nativeScraper.ts` と共通）。
 * ここでの責務は 3 つ:
 *  1. 画面上部に進捗バーを出し、{@link scrapeEagate} の進捗を表示する
 *  2. 取得結果を JSON 化し、Base64 で URL fragment に載せる
 *  3. 完了後にボタンを表示。クリックでクリップボードへ全文コピー → beat-seeker へ遷移
 *     （長時間の取得後でもユーザー操作起点でコピーするため確実に成功する）
 *
 * 注意:
 *  - データが 50000 文字を超える場合は CSV を URL から外し、クリップボード経由で受け渡す。
 *    全曲取得ではほぼ必ずこの経路になるため、遷移先ではクリップボードからの取り込み操作が要る。
 *    Android アプリ経由（`nativeScraper.ts`）ではこの制約自体が無くなる。
 */
import { scrapeEagate } from './eagateScraper';

declare const __APP_ORIGIN__: string;
(async function () {
  const statusEl = document.createElement('div');
  statusEl.style.cssText =
    'position:fixed;top:0;left:0;right:0;z-index:2147483647;background:#0f172a;color:#fff;padding:12px;text-align:center;font:bold 14px sans-serif;box-shadow:0 2px 10px rgba(0,0,0,.45)';
  statusEl.textContent = 'beat-seeker: 取得を開始します…';
  document.body.appendChild(statusEl);

  // ブックマークレットは従来どおり難易度別ページの巡回で取得する。
  // スコアは毎作リセットされるがクリアランプは永続するため、「スコア0・ランプあり」の譜面も拾える。
  const result = await scrapeEagate(
    (message) => {
      statusEl.textContent = message;
    },
    { scoreSource: 'difficulty' }
  );

  const { chartCount, songCount, ...payload } = result;
  const fullData = JSON.stringify(payload);

  // URL fragment は長さ制限があるため、大きい場合は CSV を外して battles だけ載せる。
  // CSV はクリップボード経由（fullData）で受け渡す。
  const urlData =
    fullData.length > 50000
      ? JSON.stringify({ ...payload, scoresCsv: '' })
      : fullData;
  const encoded = btoa(unescape(encodeURIComponent(urlData)));
  const url = `${__APP_ORIGIN__}?import=open#data=` + encoded;

  statusEl.innerHTML = '';
  const info = document.createElement('div');
  info.textContent =
    '取得完了！ ARENA ' +
    result.battles.length +
    '件 / スコア ' +
    chartCount +
    '譜面・' +
    songCount +
    '曲';
  info.style.marginBottom = '8px';

  const btn = document.createElement('button');
  btn.textContent = '📋 コピーして beat-seeker を開く';
  btn.style.cssText =
    'font:bold 15px sans-serif;padding:10px 20px;border:0;border-radius:8px;background:#2563eb;color:#fff;cursor:pointer';
  btn.onclick = async function () {
    try {
      await navigator.clipboard.writeText(fullData);
    } catch (e) {
      // コピー失敗（権限拒否等）でも遷移は行う。fragment に載っていれば取り込める。
    }
    location.href = url;
  };
  statusEl.appendChild(info);
  statusEl.appendChild(btn);
})();
