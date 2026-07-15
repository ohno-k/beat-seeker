/**
 * 【ユーティリティの役割】 beat-seeker 汎用ブックマークレットのソース文字列を提供する。
 *
 * ブックマークレットとは: ブラウザの「お気に入り」に `javascript:` 始まりの URL を登録し、
 * クリックするだけで任意のページ上でスクリプトを実行できる仕組み。
 *
 * このブックマークレットを実行すると外部スクリプト /bookmarklet.js を読み込んで実行する。
 * /bookmarklet.js は ./mainBookmarklet.ts からビルド時に生成される。
 */

export const BOOKMARKLET_CODE = `javascript:(function(){var s=document.createElement('script');s.src='${window.location.origin}/bookmarklet.js';document.body.appendChild(s);})();`;
