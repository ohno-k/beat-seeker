/**
 * usePlaySide.ts
 *
 * 【役割】 ログイン中ユーザーのプレイサイド（プロフィールの設定 user.playSide: '1P' / '2P'）を 1 / 2 で返す。
 * 譜面再生・RANDOM の配置評価・当たり配置ランキングは、この値で皿の位置と評価のサイドを決める。
 * 未ログインなら null（呼び出し側の既定・切り替えを使う）。
 */
import { computed } from 'vue';
import { useAuth } from './useAuth';

export function usePlaySide() {
  const { user } = useAuth();
  /** プロフィールのプレイサイド（未ログインなら null） */
  const profileSide = computed<1 | 2 | null>(() => {
    const u = user.value as { playSide?: string } | null;
    if (!u) return null;
    return u.playSide === '2P' ? 2 : 1;
  });
  return { profileSide };
}
