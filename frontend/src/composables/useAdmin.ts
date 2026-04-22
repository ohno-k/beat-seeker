import { computed } from 'vue';
import { useAuth } from './useAuth';

/**
 * 管理者として扱うユーザー ID（数値）。
 *
 * Vite の環境変数 `VITE_ADMIN_USER_ID` で上書き可能。未設定時は従来の
 * ハードコード値 `18` を利用する。`.env` を用意しなくても既存の挙動を
 * 維持できるよう、デフォルトを必ず残している。
 */
const ADMIN_USER_ID: number = (() => {
  const raw = import.meta.env.VITE_ADMIN_USER_ID;
  const parsed = raw != null ? Number(raw) : NaN;
  return Number.isFinite(parsed) ? parsed : 18;
})();

/**
 * 管理者として扱う IIDX ID 文字列（例: `5787-1145`）。
 *
 * Vite の環境変数 `VITE_ADMIN_IIDX_ID` で上書き可能。未設定時は従来の
 * ハードコード値 `5787-1145` を利用する。
 */
const ADMIN_IIDX_ID: string = import.meta.env.VITE_ADMIN_IIDX_ID ?? '5787-1145';

/**
 * 【composable の役割】 フロントエンド全体で共通利用する「管理者判定」を提供する。
 *
 * 従来は Sidebar / RankingList / ScoreSummary などで
 * `user.id === 18` や `user.iidxId === '5787-1145'` が個別に書かれていたが、
 * 設定値の一元化と将来的なロールベース認可への置換をしやすくするため
 * 本 composable に集約した。
 *
 * - 判定ロジック: `user.id === ADMIN_USER_ID || user.iidxId === ADMIN_IIDX_ID`
 * - 未ログイン（`user` が `null`）のときは常に `false`
 *
 * @returns `isAdmin` … 管理者なら `true` を返す Vue computed ref
 */
export function useAdmin() {
  const { user } = useAuth();

  /**
   * 現在のログインユーザーが管理者かどうかの computed。
   * `user` が未設定のときは `false` を返す。
   */
  const isAdmin = computed<boolean>(() => {
    const u = user.value;
    if (!u) return false;
    return u.id === ADMIN_USER_ID || u.iidxId === ADMIN_IIDX_ID;
  });

  return { isAdmin };
}
