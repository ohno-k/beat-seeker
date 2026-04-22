<template>
  <Teleport to="body">
    <!-- 画面全体に被せるエイプリルフールのオーバーレイ効果 -->
    <div v-if="isAprilFools" class="af-overlay pointer-events-none fixed inset-0 z-[9999]" aria-hidden="true">
      <!-- 画面上部から落ちてくる絵文字パーティクル -->
      <div v-for="p in particles" :key="p.id" class="af-particle" :style="p.style">
        {{ p.emoji }}
      </div>

      <!-- 画面外枠をレインボーに光らせる装飾ボーダー -->
      <div class="af-rainbow-border"></div>

      <!-- 左上コーナーの絵文字装飾 -->
      <div class="af-corner af-corner-tl">🎉</div>
      <!-- 右上コーナーの絵文字装飾 -->
      <div class="af-corner af-corner-tr">🎊</div>
    </div>

    <!-- エイプリルフール用のバナー（ユーザーが×で閉じられる） -->
    <div
      v-if="isAprilFools && !bannerDismissed"
      class="fixed top-0 left-0 right-0 z-[10000] pointer-events-none"
    >
      <div class="af-banner flex items-center justify-center px-8 py-2.5 text-white text-sm font-bold overflow-hidden relative shadow-md">
        <span class="af-banner-text pointer-events-none">🎉 HAPPY APRIL FOOLS! 🤡 Beat-Seekerが「パーティーモード」に突入しました! 🎶💃🕺</span>
        <button 
          @click="bannerDismissed = true" 
          class="absolute right-2 top-1/2 -translate-y-1/2 px-2 py-0.5 bg-white/20 hover:bg-white/30 rounded text-xs backdrop-blur-sm transition-colors z-10 pointer-events-auto shadow-sm"
        >
          ✕
        </button>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 エイプリルフール期間中に画面全体へ被せる「パーティー演出」オーバーレイ。
 *
 * 機能:
 *  - 画面上から落下する絵文字パーティクル
 *  - レインボーに変化する外枠ボーダー
 *  - 四隅の装飾（🎉🎊）
 *  - 画面上部のスクロール式バナー（閉じるボタン付き）
 *
 * NOTE: 現在 `useAprilFools` 側で強制 false に抑止されているため、
 *       期間中でも描画されない。将来復活させる時は composable 側のフラグを戻すだけで動く。
 *
 * props/emits: なし（グローバル演出なので `<Teleport to="body">` で body に投入）。
 */
import { ref, computed } from 'vue';
import { useAprilFools } from '../composables/useAprilFools';

// エイプリルフール判定（期間中なら true）。
const { isAprilFools } = useAprilFools();
/** バナー閉じるボタンを押したかどうか。ページをリロードしない限り閉じたままにする。 */
const bannerDismissed = ref(false);

// パーティクル用の絵文字プール。一通り回るようにインデックス剰余で循環させる。
const emojis = ['🎵', '🎶', '🎸', '🎤', '🎹', '🥁', '🎺', '🎷', '💃', '🕺', '🌸', '⭐', '✨', '🎀', '🍭', '🦄', '🌈', '🎠'];

/** 1 個のパーティクルを表す型。`style` は CSS 変数/値を直接 bind するため Record で保持。 */
interface Particle {
  id: number;
  emoji: string;
  style: Record<string, string>;
}

/** 画面に落下させる 20 個のパーティクル。ランダムな左位置・遅延・速度・サイズを持たせて賑やかに演出。 */
const particles = computed<Particle[]>(() => {
  if (!isAprilFools.value) return [];
  const result: Particle[] = [];
  for (let i = 0; i < 20; i++) {
    const emoji = emojis[i % emojis.length];
    const left = Math.random() * 100;
    const delay = Math.random() * 15;
    const duration = 8 + Math.random() * 12;
    const size = 14 + Math.random() * 16;
    const startY = -10;
    result.push({
      id: i,
      emoji,
      style: {
        left: `${left}%`,
        fontSize: `${size}px`,
        animationDelay: `${delay}s`,
        animationDuration: `${duration}s`,
        top: `${startY}%`,
      },
    });
  }
  return result;
});
</script>

<style scoped>
/* ===== Floating Particle Animation ===== */
@keyframes af-float-down {
  0% {
    transform: translateY(-20px) rotate(0deg) scale(0.8);
    opacity: 0;
  }
  10% {
    opacity: 1;
  }
  90% {
    opacity: 0.7;
  }
  100% {
    transform: translateY(110vh) rotate(720deg) scale(1.2);
    opacity: 0;
  }
}

.af-particle {
  position: fixed;
  animation: af-float-down linear infinite;
  will-change: transform, opacity;
  z-index: 9999;
  filter: drop-shadow(0 0 4px rgba(255, 255, 255, 0.5));
}

/* ===== Rainbow Border ===== */
@keyframes af-rainbow-shift {
  0% { background-position: 0% 50%; }
  50% { background-position: 100% 50%; }
  100% { background-position: 0% 50%; }
}

.af-rainbow-border {
  position: fixed;
  inset: 0;
  pointer-events: none;
  border: 3px solid transparent;
  border-image: linear-gradient(
    90deg,
    #ff6b6b, #ffa07a, #ffd700, #7cff7c, #6bcfff, #a06bff, #ff6bdf, #ff6b6b
  ) 1;
  animation: af-rainbow-shift 4s linear infinite;
  background-size: 400% 100%;
  z-index: 9998;
}

/* ===== Corner Decorations ===== */
.af-corner {
  position: fixed;
  font-size: 32px;
  animation: af-corner-bounce 2s ease-in-out infinite;
  z-index: 9999;
}

@keyframes af-corner-bounce {
  0%, 100% { transform: scale(1) rotate(0deg); }
  25% { transform: scale(1.2) rotate(10deg); }
  50% { transform: scale(1) rotate(0deg); }
  75% { transform: scale(1.2) rotate(-10deg); }
}

.af-corner-tl {
  top: 72px;
  left: 8px;
}
.af-corner-tr {
  top: 72px;
  right: 8px;
  animation-delay: 1s;
}

/* ===== Banner ===== */
.af-banner {
  background: linear-gradient(
    90deg,
    #ff6b6b, #ff9a6b, #ffd36b, #6bff6b, #6bcfff, #a06bff, #ff6bdf, #ff6b6b
  );
  background-size: 400% 100%;
  animation: af-rainbow-shift 3s linear infinite;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.3);
}

@keyframes af-banner-scroll {
  0% { transform: translateX(100%); }
  100% { transform: translateX(-100%); }
}

.af-banner-text {
  animation: af-banner-scroll 20s linear infinite;
  white-space: nowrap;
}
</style>
