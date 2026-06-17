/**
 * 画像をブラウザ内でリサイズ・圧縮するユーティリティ。
 *
 * リザルト画像（スマホ撮影やスクショ）は数MBになりがちなので、アップロード前に
 * 長辺を一定サイズに縮小し WebP（非対応環境は JPEG）へ再エンコードして容量を大幅に削減する。
 * これによりオブジェクトストレージの使用量と転送量を抑える。
 */

export interface CompressedImage {
  /** 圧縮後の画像データ。 */
  blob: Blob;
  /** 圧縮後の幅（px）。 */
  width: number;
  /** 圧縮後の高さ（px）。 */
  height: number;
  /** 出力 MIME タイプ（'image/webp' または 'image/jpeg'）。 */
  type: string;
}

/** WebP エンコード対応可否（一度だけ判定してキャッシュ）。 */
let webpSupported: boolean | null = null;
function supportsWebp(): boolean {
  if (webpSupported !== null) return webpSupported;
  try {
    const c = document.createElement('canvas');
    c.width = 1;
    c.height = 1;
    webpSupported = c.toDataURL('image/webp').startsWith('data:image/webp');
  } catch {
    webpSupported = false;
  }
  return webpSupported;
}

/**
 * 画像ファイルを縮小・再圧縮する。
 *
 * @param file    入力画像（`<input type="file">` の File、共有/クリップボード由来の Blob いずれも可）
 * @param maxEdge 長辺の最大ピクセル数（既定 1600）。これより小さい画像は拡大しない。
 * @param quality エンコード品質 0〜1（既定 0.82）
 * @returns 圧縮後の Blob と寸法・MIME タイプ
 */
export async function compressImage(
  file: Blob,
  maxEdge = 1600,
  quality = 0.82
): Promise<CompressedImage> {
  const source = await loadImage(file);
  const ow = 'naturalWidth' in source ? source.naturalWidth : source.width;
  const oh = 'naturalHeight' in source ? source.naturalHeight : source.height;
  if (!ow || !oh) {
    closeBitmap(source);
    throw new Error('画像のサイズを取得できませんでした');
  }

  const scale = Math.min(1, maxEdge / Math.max(ow, oh));
  const w = Math.max(1, Math.round(ow * scale));
  const h = Math.max(1, Math.round(oh * scale));

  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    closeBitmap(source);
    throw new Error('canvas の 2D コンテキストを取得できませんでした');
  }
  ctx.drawImage(source as CanvasImageSource, 0, 0, w, h);
  closeBitmap(source);

  const type = supportsWebp() ? 'image/webp' : 'image/jpeg';
  const blob = await new Promise<Blob | null>((resolve) =>
    canvas.toBlob(resolve, type, quality)
  );
  if (!blob) throw new Error('画像の変換に失敗しました');

  return { blob, width: w, height: h, type };
}

/** 画像 Blob/File を ImageBitmap（不可なら HTMLImageElement）として読み込む。EXIF 回転も反映する。 */
async function loadImage(file: Blob): Promise<ImageBitmap | HTMLImageElement> {
  if (typeof createImageBitmap === 'function') {
    try {
      return await createImageBitmap(file, { imageOrientation: 'from-image' } as ImageBitmapOptions);
    } catch {
      // 一部ブラウザは imageOrientation 未対応。フォールバックへ。
    }
  }
  return await new Promise<HTMLImageElement>((resolve, reject) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      URL.revokeObjectURL(url);
      resolve(img);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('画像の読み込みに失敗しました'));
    };
    img.src = url;
  });
}

/** ImageBitmap なら明示的に破棄してメモリを解放する。 */
function closeBitmap(source: ImageBitmap | HTMLImageElement): void {
  if (typeof ImageBitmap !== 'undefined' && source instanceof ImageBitmap) {
    source.close();
  }
}
