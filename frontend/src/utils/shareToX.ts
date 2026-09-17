/**
 * 生成した画像を X（旧 Twitter）へ渡すための共通処理。
 *
 * X の投稿画面 URL（x.com/intent/post）は本文しか受け取れず、画像を添付するパラメータが無い。
 * 画像が貼られた状態の投稿画面を開けるのは OS の共有シート（Web Share API）経由だけなので、
 *  - スマホ / タブレット: Web Share API → 共有シートで X アプリを選ぶと画像 + 本文入りの投稿画面が開く
 *  - PC: 画像をクリップボードへコピー → ブラウザで本文入りの投稿画面を開く → Ctrl+V で貼り付け
 * と経路を分ける。
 *
 * PC の Chrome / Edge / Safari も canShare({ files }) は true を返すが、開くのは X が並ばない
 * OS の共有ダイアログで、コピーもダウンロードも走らず行き止まりになる。そのため Web Share を使うかは
 * API の対応可否ではなく端末種別で決める。
 */

/** iPhone / iPad か。iPadOS 13 以降は Mac を名乗るのでタッチ点数で見分ける。 */
export const isIosDevice = (): boolean =>
  /iPhone|iPad|iPod/i.test(navigator.userAgent)
  || (/Macintosh/i.test(navigator.userAgent) && navigator.maxTouchPoints > 1);

/** スマホ / タブレットか（共有シートに X アプリが並ぶ端末）。 */
export const isMobileDevice = (): boolean => isIosDevice() || /Android/i.test(navigator.userAgent);

/**
 * 【関数の役割】 共有シート経由で画像を X アプリへ渡せる環境か。
 * file を省略すると PNG のダミーで判定する（ボタンを押す前の案内文の出し分け用）。
 */
export const canShareImageNatively = (file?: File): boolean => {
  if (!isMobileDevice()) return false;
  if (typeof navigator.share !== 'function' || typeof navigator.canShare !== 'function') return false;
  try {
    return navigator.canShare({ files: [file ?? new File([new Uint8Array(1)], 'probe.png', { type: 'image/png' })] });
  } catch {
    return false;
  }
};

/** X の投稿画面 URL（本文プリセット）。スマホで X アプリが入っていればアプリ側の投稿画面で開く。 */
export const xIntentUrl = (text: string): string => `https://x.com/intent/post?text=${encodeURIComponent(text)}`;

/**
 * 【関数の役割】 画像をクリップボードへ入れる。成功したら true。
 * 別タブを開いてフォーカスが移ると書き込めなくなるので、window.open より前に呼んで await すること。
 */
export const copyImageToClipboard = async (blob: Blob): Promise<boolean> => {
  try {
    if (!navigator.clipboard || typeof ClipboardItem === 'undefined') return false;
    await navigator.clipboard.write([new ClipboardItem({ [blob.type]: blob })]);
    return true;
  } catch (e) {
    console.warn('Clipboard image copy failed:', e);
    return false;
  }
};

/** 【関数の役割】 Blob をファイルとして保存させる。 */
export const downloadBlob = (blob: Blob, fileName: string): void => {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  // DOM に入っていない a の click() ではダウンロードが始まらないブラウザがある。
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  a.remove();
  // 即時に revoke するとブラウザによってはダウンロードが始まらないので少し待つ。
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};
