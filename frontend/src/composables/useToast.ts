import { ref, readonly } from 'vue';

/**
 * 【Composable の役割】 アプリ全体で共有するトースト通知ストア。
 *
 * 既存のインラインバナー（赤=エラー / 緑=成功）が各モーダル・ページごとに自前実装で
 * 散らばっていたのを、画面右下に重ねて出す共通トーストへ寄せる。
 *
 * 使い方:
 * ```ts
 * const toast = useToast();
 * toast.success('保存しました');
 * toast.error('通信に失敗しました', { durationMs: 6000 });
 * ```
 *
 * トーストは表示と同時に一定時間後に自動消去される。
 * `<ToastContainer>` コンポーネントを App ルート直下に 1 つだけ置くこと。
 */

export type ToastType = 'success' | 'error' | 'info';

export interface ToastItem {
  /** 一意な ID（dismiss 用）。 */
  id: number;
  /** 種別。色とアイコンに対応。 */
  type: ToastType;
  /** 表示メッセージ。改行を含むことがある。 */
  message: string;
}

export interface ToastOptions {
  /** 自動消去までのミリ秒（既定: success/info=3000、error=6000）。 */
  durationMs?: number;
}

/** モジュール全体で共有する 1 本のキュー（シングルトン）。 */
const items = ref<ToastItem[]>([]);
let nextId = 1;

/**
 * トーストを 1 件追加する。`<ToastContainer>` がリアクティブに描画する。
 * 指定時間後に自動消去（手動でも `dismiss(id)` で消せる）。
 */
function push(type: ToastType, message: string, opts?: ToastOptions): number {
  const id = nextId++;
  items.value.push({ id, type, message });
  const duration = opts?.durationMs ?? (type === 'error' ? 6000 : 3000);
  if (duration > 0) {
    setTimeout(() => dismiss(id), duration);
  }
  return id;
}

/** 指定 ID のトーストを即時消去する。タイマーより前に閉じる時に使う。 */
function dismiss(id: number) {
  const idx = items.value.findIndex(t => t.id === id);
  if (idx >= 0) items.value.splice(idx, 1);
}

export function useToast() {
  return {
    /** 表示中トースト一覧（読み取り専用）。`<ToastContainer>` から参照する。 */
    items: readonly(items),
    /** 成功トースト（緑）。 */
    success: (message: string, opts?: ToastOptions) => push('success', message, opts),
    /** エラートースト（赤、既定で長め表示）。 */
    error: (message: string, opts?: ToastOptions) => push('error', message, opts),
    /** 情報トースト（青）。 */
    info: (message: string, opts?: ToastOptions) => push('info', message, opts),
    /** 任意のトーストを手動で閉じる。 */
    dismiss,
  };
}
