<template>
  <Teleport to="body">
    <!-- Global April Fools overlay effects -->
    <div v-if="isAprilFools" class="af-overlay pointer-events-none fixed inset-0 z-[9999]" aria-hidden="true">
      <!-- Floating emoji particles -->
      <div v-for="p in particles" :key="p.id" class="af-particle" :style="p.style">
        {{ p.emoji }}
      </div>

      <!-- Rainbow border accent -->
      <div class="af-rainbow-border"></div>

      <!-- Corner decoration: top-left -->
      <div class="af-corner af-corner-tl">🎉</div>
      <!-- Corner decoration: top-right -->
      <div class="af-corner af-corner-tr">🎊</div>
    </div>

    <!-- April Fools Banner (dismissible) -->
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
import { ref, computed } from 'vue';
import { useAprilFools } from '../composables/useAprilFools';

const { isAprilFools } = useAprilFools();
const bannerDismissed = ref(false);

// Generate random floating particles
const emojis = ['🎵', '🎶', '🎸', '🎤', '🎹', '🥁', '🎺', '🎷', '💃', '🕺', '🌸', '⭐', '✨', '🎀', '🍭', '🦄', '🌈', '🎠'];

interface Particle {
  id: number;
  emoji: string;
  style: Record<string, string>;
}

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
