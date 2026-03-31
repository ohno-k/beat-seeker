import { ref, computed, onMounted, onUnmounted } from 'vue';

/**
 * April Fools composable – active only on April 1st, 00:00–11:59 local time.
 * Provides a reactive `isAprilFools` flag consumed across the app.
 */

// ⚠️ テスト用: true にすると常にエイプリルフールモードが有効になる
// 本番デプロイ前に false に戻すこと！
const DEBUG_FORCE_APRIL_FOOLS = false;

const _isAprilFools = ref(DEBUG_FORCE_APRIL_FOOLS);
let _timer: ReturnType<typeof setInterval> | null = null;
let _refCount = 0;

// 機能全体を無効化するマスタースイッチ
const ENABLE_APRIL_FOOLS = false;

function checkAprilFools(): boolean {
  if (DEBUG_FORCE_APRIL_FOOLS) return true;
  if (!ENABLE_APRIL_FOOLS) return false;
  
  // Check against JST (Asia/Tokyo)
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Tokyo',
    month: 'numeric',
    day: 'numeric'
  });
  
  const parts = formatter.formatToParts(new Date());
  const month = parts.find(p => p.type === 'month')?.value;
  const day = parts.find(p => p.type === 'day')?.value;
  
  // April 1st all day
  return month === '4' && day === '1';
}

export function useAprilFools() {
  onMounted(() => {
    _isAprilFools.value = checkAprilFools();
    _refCount++;
    if (_refCount === 1) {
      // Check every 60 seconds in case noon arrives while user is using the app
      _timer = setInterval(() => {
        _isAprilFools.value = checkAprilFools();
      }, 60_000);
    }
  });

  onUnmounted(() => {
    _refCount--;
    if (_refCount <= 0 && _timer) {
      clearInterval(_timer);
      _timer = null;
      _refCount = 0;
    }
  });

  return {
    isAprilFools: computed(() => _isAprilFools.value),
  };
}
