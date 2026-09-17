/**
 * html2canvas(1.4.1) を Tailwind のページで使うときの対策集。
 */

/**
 * 【関数の役割】 html2canvas で文字が下にずれる問題の対策。run の間だけ補正用のスタイルを差し込む。
 *
 * html2canvas はフォントのベースラインを「span の横に 1px の img を並べ、両者の offsetTop の差を取る」
 * 方法で測る（FontMetrics.parseMetrics）。計測用の div は *元の* document の body 末尾に一瞬だけ追加される。
 * ところが Tailwind の preflight が `img { display: block }` を当てるため img が次の行へ落ち、
 * ベースラインが 1 行分近く過大になる。結果、キャプチャ画像では全テキストが下へずれ、
 * overflow: hidden の行では文字の下半分が欠ける。
 *
 * キャプチャの間だけ「body 直下・末尾の div の直下にある img」を inline に戻して正しく測らせる。
 * 対象がこの計測用コンテナに限られるよう、セレクタは最小限にしてある。
 */
export async function withHtml2canvasTextFix<T>(run: () => Promise<T>): Promise<T> {
  const style = document.createElement('style');
  style.textContent = 'body > div:last-child > img { display: inline !important; }';
  document.head.appendChild(style);
  try {
    return await run();
  } finally {
    style.remove();
  }
}

/**
 * 【関数の役割】 html2canvas の `ignoreElements` に渡す述語。キャプチャ対象とその祖先・子孫、
 * および <head>（スタイル）だけを複製対象に残し、それ以外の body 配下を複製しない。
 *
 * html2canvas は対象が一部でも document 全体を iframe に複製するので、背後に大きな画面
 * （ダッシュボードや数百行の一覧）があると複製だけで数秒かかる。画面外に置いた共有画像のように
 * 周囲と無関係な要素を撮るときに使う。
 */
export function ignoreOutside(target: Element): (el: Element) => boolean {
  return (el) => {
    if (el === target || target.contains(el) || el.contains(target)) return false;
    if (el.closest('head')) return false;
    return true;
  };
}
