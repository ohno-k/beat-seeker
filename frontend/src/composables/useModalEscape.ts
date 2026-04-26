import { watch, onBeforeUnmount, type Ref } from 'vue';

/**
 * 【Composable の役割】 モーダルが開いている間だけ Escape キーで閉じられるようにする。
 *
 * 1 回の `keydown` リスナーをドキュメントに張り、`isOpen` が true の時だけ
 * onClose を発火する。同時に複数モーダルが開いていても、各モーダルが
 * それぞれのリスナーを持っているので最前面のモーダルから順に閉じる挙動になる
 * （Esc 押下で全部のモーダルが閉じうるが、複数同時に開く設計は元々無いので問題なし）。
 *
 * 使い方:
 * ```ts
 * useModalEscape(() => props.isOpen, () => emit('close'));
 * ```
 *
 * @param isOpenGetter 開閉状態を返すゲッター（getter にすることでリアクティブに追従）
 * @param onClose Esc が押された瞬間に呼ぶコールバック
 */
export function useModalEscape(
  isOpenGetter: (() => boolean) | Ref<boolean>,
  onClose: () => void,
): void {
  const handler = (e: KeyboardEvent) => {
    if (e.key !== 'Escape') return;
    const isOpen = typeof isOpenGetter === 'function' ? isOpenGetter() : isOpenGetter.value;
    if (!isOpen) return;
    e.stopPropagation();
    onClose();
  };

  // isOpen の変化に追従して keydown リスナーを着脱する。
  // 開いている間だけ document に登録するので、閉じている時の Esc は他のショートカットに譲る。
  watch(
    () => (typeof isOpenGetter === 'function' ? isOpenGetter() : isOpenGetter.value),
    (open) => {
      if (open) {
        document.addEventListener('keydown', handler);
      } else {
        document.removeEventListener('keydown', handler);
      }
    },
    { immediate: true },
  );

  onBeforeUnmount(() => {
    document.removeEventListener('keydown', handler);
  });
}
