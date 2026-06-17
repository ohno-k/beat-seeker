import { useAuth } from './useAuth';
import { API_BASE } from './constants';

/**
 * 【Composable の役割】 リザルト画像 API（/api/result-images）への薄いラッパー。
 *
 * 譜面は (曲名 title, 難易度名 difficultyName) で同定する。
 * 画像本体はバックエンド経由で Cloudflare R2 に保存され、ここで扱うのは
 * 一覧（署名付き URL 付き）・登録（multipart）・削除のみ。
 */

/** サーバから返るリザルト画像の DTO。 */
export interface ResultImageDto {
  id: number;
  /** 署名付き GET URL（短時間有効）。`<img src>` で直接表示できる。 */
  url: string;
  width: number | null;
  height: number | null;
  uploadedAt: string | null;
}

/** アップロード時に渡すパラメータ。 */
export interface UploadResultImageParams {
  title: string;
  difficultyName: string;
  difficultyLevel?: number | null;
  blob: Blob;
  /** Blob の MIME タイプ（拡張子決定に使う）。 */
  type: string;
  width?: number;
  height?: number;
}

export function useResultImages() {
  const { authHeaders } = useAuth();

  /** 指定譜面のリザルト画像一覧を取得する。 */
  async function list(title: string, difficultyName: string): Promise<ResultImageDto[]> {
    const params = new URLSearchParams({ title, difficultyName });
    const res = await fetch(`${API_BASE}/api/result-images?${params.toString()}`, {
      headers: authHeaders(),
    });
    if (!res.ok) throw new Error('リザルト画像の取得に失敗しました');
    return res.json();
  }

  /** リザルト画像を 1 枚アップロードする。 */
  async function upload(opts: UploadResultImageParams): Promise<ResultImageDto> {
    const ext = opts.type === 'image/webp' ? 'webp' : opts.type === 'image/png' ? 'png' : 'jpg';
    const fd = new FormData();
    fd.append('file', opts.blob, `result.${ext}`);
    fd.append('title', opts.title);
    fd.append('difficultyName', opts.difficultyName);
    if (opts.difficultyLevel != null) fd.append('difficultyLevel', String(opts.difficultyLevel));
    if (opts.width != null) fd.append('width', String(opts.width));
    if (opts.height != null) fd.append('height', String(opts.height));

    // Content-Type は付与しない（ブラウザが multipart 境界付きで自動設定する）。
    const res = await fetch(`${API_BASE}/api/result-images`, {
      method: 'POST',
      headers: authHeaders(),
      body: fd,
    });
    if (!res.ok) {
      const data = await res.json().catch(() => ({} as any));
      throw new Error(data.message || data.error || 'リザルト画像のアップロードに失敗しました');
    }
    return res.json();
  }

  /** 自分のリザルト画像を 1 枚削除する。 */
  async function remove(id: number): Promise<void> {
    const res = await fetch(`${API_BASE}/api/result-images/${id}`, {
      method: 'DELETE',
      headers: authHeaders(),
    });
    if (!res.ok) throw new Error('リザルト画像の削除に失敗しました');
  }

  return { list, upload, remove };
}
