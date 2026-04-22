import { ref, computed, onMounted, onUnmounted } from 'vue';

/**
 * 【Composable の役割】 4 月 1 日のエイプリルフール限定エフェクトを有効化するフラグを提供する。
 *
 * 機能:
 *  - JST（Asia/Tokyo）で 4 月 1 日の終日 `isAprilFools` を `true` にする
 *  - 日付判定は 60 秒ごとに再評価され、日をまたぐ瞬間に自動で切り替わる
 *  - デバッグ用スイッチ（`DEBUG_FORCE_APRIL_FOOLS`）とマスタースイッチ（`ENABLE_APRIL_FOOLS`）を併用
 *
 * 使い方:
 * ```ts
 * const { isAprilFools } = useAprilFools();
 * // <div v-if="isAprilFools">🐟 エイプリルフール演出 🐟</div>
 * ```
 */

/**
 * 強制的にエイプリルフールモードを有効化するデバッグ用フラグ。
 *
 * 警告: `true` で長期間放置しないこと。本番デプロイ前に必ず `false` に戻す。
 */
const DEBUG_FORCE_APRIL_FOOLS = false;

/**
 * エイプリルフール状態を**アプリ全体で共有する**リアクティブ参照（モジュールシングルトン）。
 *
 * 関数外に置く理由は `useDarkMode.ts` と同じで、どのコンポーネントから呼んでも同じ ref が返るようにするため。
 */
const _isAprilFools = ref(DEBUG_FORCE_APRIL_FOOLS);
/** 60 秒ごとのポーリングタイマー。全コンポーネントで 1 本だけ持つ。 */
let _timer: ReturnType<typeof setInterval> | null = null;
/** useAprilFools を呼び出しているコンポーネント数。0 になったらタイマーを停止する。 */
let _refCount = 0;

/**
 * 機能全体を無効化するマスタースイッチ。
 * 例えば「今年はエイプリルフール演出を見送る」と判断した場合は `false` のままにする。
 */
const ENABLE_APRIL_FOOLS = false;

/**
 * 現在の時刻が JST における 4 月 1 日かどうかを判定する。
 * @returns エイプリルフールモードにするべきなら `true`
 */
function checkAprilFools(): boolean {
  // デバッグフラグが最優先（ENABLE_APRIL_FOOLS より強い）
  if (DEBUG_FORCE_APRIL_FOOLS) return true;
  // マスタースイッチが OFF なら常に false
  if (!ENABLE_APRIL_FOOLS) return false;

  // ユーザーのローカルタイムに関わらず JST（Asia/Tokyo）基準で判定する。
  // これにより海外から使うユーザーでも「日本の 4/1」に合わせて演出が出る。
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Tokyo',
    month: 'numeric',
    day: 'numeric'
  });

  const parts = formatter.formatToParts(new Date());
  const month = parts.find(p => p.type === 'month')?.value;
  const day = parts.find(p => p.type === 'day')?.value;

  // 4 月 1 日終日
  return month === '4' && day === '1';
}

export function useAprilFools() {
  onMounted(() => {
    // マウント時点の判定を即座に反映
    _isAprilFools.value = checkAprilFools();
    _refCount++;
    // 最初のコンポーネントが付いたタイミングでだけタイマーを起動
    if (_refCount === 1) {
      // ユーザーがアプリを開いたまま日をまたぐ可能性があるので、60 秒間隔でポーリング
      _timer = setInterval(() => {
        _isAprilFools.value = checkAprilFools();
      }, 60_000);
    }
  });

  onUnmounted(() => {
    _refCount--;
    // 全コンポーネントがアンマウントされたらタイマーを停止してリークを防ぐ
    if (_refCount <= 0 && _timer) {
      clearInterval(_timer);
      _timer = null;
      _refCount = 0;
    }
  });

  return {
    /** 現在エイプリルフールモードかどうか。読み取り専用 computed で公開。 */
    isAprilFools: computed(() => _isAprilFools.value),
  };
}
