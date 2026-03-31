<template>
  <!-- Dancing Rank Icon Wrapper -->
  <div class="relative flex items-center justify-center select-none shrink-0" :class="[sizeClass, { 'april-fools-icon af-wander-screen': isAprilFoolsActive }]" :style="wanderStyle">
    <!-- Dancing Limbs (April Fools only) -->
    <svg
      v-if="isAprilFoolsActive"
      :viewBox="limbsViewBox"
      class="absolute inset-0 w-full h-full pointer-events-none z-10"
      style="overflow: visible"
    >
      <!-- Left Arm -->
      <g :class="['af-limb af-left-arm', danceClass]" :style="limbStyle('leftArm')">
        <line x1="20" y1="55" x2="2" y2="40" :stroke="limbColor" stroke-width="3" stroke-linecap="round" />
        <circle cx="2" cy="38" r="3" :fill="limbColor" />
      </g>
      <!-- Right Arm -->
      <g :class="['af-limb af-right-arm', danceClass]" :style="limbStyle('rightArm')">
        <line x1="80" y1="55" x2="98" y2="40" :stroke="limbColor" stroke-width="3" stroke-linecap="round" />
        <circle cx="98" cy="38" r="3" :fill="limbColor" />
      </g>
      <!-- Left Leg -->
      <g :class="['af-limb af-left-leg', danceClass]" :style="limbStyle('leftLeg')">
        <line x1="35" y1="88" x2="22" y2="108" :stroke="limbColor" stroke-width="3" stroke-linecap="round" />
        <ellipse cx="20" cy="110" rx="5" ry="2.5" :fill="limbColor" />
      </g>
      <!-- Right Leg -->
      <g :class="['af-limb af-right-leg', danceClass]" :style="limbStyle('rightLeg')">
        <line x1="65" y1="88" x2="78" y2="108" :stroke="limbColor" stroke-width="3" stroke-linecap="round" />
        <ellipse cx="80" cy="110" rx="5" ry="2.5" :fill="limbColor" />
      </g>
      <!-- Face -->
      <g class="af-face">
        <circle cx="40" cy="45" r="3" fill="white" opacity="0.9" />
        <circle cx="60" cy="45" r="3" fill="white" opacity="0.9" />
        <circle cx="41" cy="45.5" r="1.5" fill="#333" />
        <circle cx="61" cy="45.5" r="1.5" fill="#333" />
        <path d="M42 56 Q50 64 58 56" fill="none" stroke="white" stroke-width="2" stroke-linecap="round" opacity="0.9" />
      </g>
    </svg>

    <!-- SVG Icon -->
    <svg 
      viewBox="0 0 100 100" 
      fill="none" 
      xmlns="http://www.w3.org/2000/svg"
      class="w-full h-full filter drop-shadow-xl"
      :class="{ 'af-bounce': isAprilFoolsActive }"
    >
      <defs>
        <!-- Complex Gradients -->
        <linearGradient :id="`grad-${uid}`" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" :stop-color="isAprilFoolsActive ? aprilColors.primary : colors.primary" />
          <stop offset="50%" :stop-color="isAprilFoolsActive ? aprilColors.highlight : colors.highlight" />
          <stop offset="100%" :stop-color="isAprilFoolsActive ? aprilColors.secondary : colors.secondary" />
        </linearGradient>
        
        <linearGradient :id="`glow-grad-${uid}`" x1="0%" y1="0%" x2="0%" y2="100%">
          <stop offset="0%" :stop-color="colors.primary" stop-opacity="1" />
          <stop offset="100%" :stop-color="colors.primary" stop-opacity="0.2" />
        </linearGradient>

        <!-- Dynamic Filters -->
        <filter :id="`inner-glow-${uid}`">
          <feGaussianBlur in="SourceAlpha" stdDeviation="2" result="blur" />
          <feComposite in="SourceGraphic" in2="blur" operator="out" result="glow" />
          <feFlood :flood-color="colors.stroke" flood-opacity="0.8" result="color" />
          <feComposite in="color" in2="glow" operator="in" />
          <feComposite in2="SourceGraphic" operator="over" />
        </filter>

        <filter :id="`outer-glow-${uid}`" x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur in="SourceAlpha" stdDeviation="3" result="blur" />
          <feFlood :flood-color="colors.primary" flood-opacity="0.5" result="color" />
          <feComposite in="color" in2="blur" operator="in" result="shadow" />
          <feOffset in="shadow" dx="0" dy="0" result="offsetShadow" />
          <feMerge>
            <feMergeNode in="offsetShadow" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
      </defs>

      <!-- Glow Underlay for high ranks -->
      <path 
        v-if="tier && tier > 0"
        :d="shapePath" 
        :fill="colors.primary" 
        fill-opacity="0.1" 
        :filter="`url(#outer-glow-${uid})`"
      />

      <!-- Base Shape -->
      <path 
        :d="shapePath" 
        :fill="`url(#grad-${uid})`"
        :stroke="strokeColor"
        stroke-width="2.5"
        stroke-linejoin="round"
        :filter="`url(#inner-glow-${uid})`"
      />
      
      <!-- Tier Segments (More visible "Energy Bars") -->
      <g v-if="tier && tier > 0" class="tier-segments">
        <path 
          v-for="n in tier" 
          :key="n" 
          :d="getSegmentPath(n)" 
          fill="white" 
          fill-opacity="0.9"
          class="drop-shadow-[0_0_2px_rgba(255,255,255,0.8)]"
        />
      </g>

      <!-- Advanced Highlight for special ranks -->
      <path 
        v-if="isLegend || rankName === 'Mythic'"
        :d="shapePath" 
        fill="white" 
        fill-opacity="0.1" 
        class="animate-shimmer"
      />
    </svg>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useAprilFools } from '../composables/useAprilFools';

const props = defineProps<{
  rankName: string;
  tier?: number;
  size?: 'xs' | 'sm' | 'md' | 'lg';
  disableParty?: boolean;
}>();

const uid = Math.random().toString(36).substring(2, 11);
const { isAprilFools } = useAprilFools();

const isAprilFoolsActive = computed(() => isAprilFools.value && !props.disableParty);

// Generate random wandering parameters
const wanderStyle = computed(() => {
  if (!isAprilFoolsActive.value) return {};
  const duration = 15 + Math.random() * 20; // 15-35s
  const delay = -(Math.random() * 20); // start at random point in animation
  const tx1 = (Math.random() - 0.5) * 80; // vw
  const ty1 = (Math.random() - 0.5) * 80; // vh
  const rot1 = (Math.random() - 0.5) * 180;
  const tx2 = (Math.random() - 0.5) * 80; // vw
  const ty2 = (Math.random() - 0.5) * 80; // vh
  const rot2 = (Math.random() - 0.5) * 180;
  
  return {
    '--af-wander-dur': `${duration}s`,
    '--af-wander-delay': `${delay}s`,
    '--af-tx1': `${tx1}vw`,
    '--af-ty1': `${ty1}vh`,
    '--af-rot1': `${rot1}deg`,
    '--af-tx2': `${tx2}vw`,
    '--af-ty2': `${ty2}vh`,
    '--af-rot2': `${rot2}deg`,
  };
});

const sizeClass = computed(() => {
  switch (props.size) {
    case 'xs': return 'w-6 h-6 sm:w-8 sm:h-8';
    case 'sm': return 'w-8 h-8';
    case 'md': return 'w-12 h-12';
    case 'lg': return 'w-24 h-24 sm:w-32 sm:h-32';
    default: return 'w-16 h-16 sm:w-20 sm:h-20';
  }
});

const isLegend = computed(() => props.rankName === 'Legend');

const colors = computed(() => {
  const name = props.rankName.toLowerCase();
  // Multi-stop color sets for richness
  if (name === 'beginner') return { primary: '#475569', highlight: '#94a3b8', secondary: '#1e293b', stroke: '#cbd5e1' };
  if (name === 'novice') return { primary: '#334155', highlight: '#64748b', secondary: '#0f172a', stroke: '#94a3b8' };
  if (name === 'intermediate') return { primary: '#1e40af', highlight: '#60a5fa', secondary: '#1e3a8a', stroke: '#bfdbfe' };
  if (name === 'advanced') return { primary: '#155e75', highlight: '#22d3ee', secondary: '#083344', stroke: '#a5f3fc' };
  if (name === 'expert') return { primary: '#115e59', highlight: '#2dd4bf', secondary: '#042f2e', stroke: '#99f6e4' };
  if (name === 'veteran') return { primary: '#064e3b', highlight: '#34d399', secondary: '#022c22', stroke: '#a7f3d0' };
  if (name === 'commander') return { primary: '#854d0e', highlight: '#eab308', secondary: '#422006', stroke: '#fef08a' };
  if (name === 'elite') return { primary: '#9a3412', highlight: '#fb923c', secondary: '#431407', stroke: '#ffedd5' };
  if (name === 'master') return { primary: '#991b1b', highlight: '#f87171', secondary: '#450a0a', stroke: '#fee2e2' };
  if (name === 'ancient') return { primary: '#3730a3', highlight: '#818cf8', secondary: '#1e1b4b', stroke: '#e0e7ff' };
  if (name === 'mythic') return { primary: '#6b21a8', highlight: '#c084fc', secondary: '#2e1065', stroke: '#f3e8ff' };
  if (name === 'legend') return { primary: '#854d0e', highlight: '#fbbf24', secondary: '#422006', stroke: '#fef3c7' };
  return { primary: '#475569', highlight: '#94a3b8', secondary: '#1e293b', stroke: '#cbd5e1' };
});

// April Fools pastel rainbow colors
const aprilColors = computed(() => {
  const name = props.rankName.toLowerCase();
  if (name === 'beginner') return { primary: '#f9a8d4', highlight: '#fbcfe8', secondary: '#f472b6' };
  if (name === 'novice') return { primary: '#a78bfa', highlight: '#c4b5fd', secondary: '#8b5cf6' };
  if (name === 'intermediate') return { primary: '#67e8f9', highlight: '#a5f3fc', secondary: '#22d3ee' };
  if (name === 'advanced') return { primary: '#86efac', highlight: '#bbf7d0', secondary: '#4ade80' };
  if (name === 'expert') return { primary: '#fcd34d', highlight: '#fde68a', secondary: '#f59e0b' };
  if (name === 'veteran') return { primary: '#fb923c', highlight: '#fdba74', secondary: '#f97316' };
  if (name === 'commander') return { primary: '#f87171', highlight: '#fca5a5', secondary: '#ef4444' };
  if (name === 'elite') return { primary: '#e879f9', highlight: '#f0abfc', secondary: '#d946ef' };
  if (name === 'master') return { primary: '#fb7185', highlight: '#fda4af', secondary: '#f43f5e' };
  if (name === 'ancient') return { primary: '#c084fc', highlight: '#d8b4fe', secondary: '#a855f7' };
  if (name === 'mythic') return { primary: '#f472b6', highlight: '#f9a8d4', secondary: '#ec4899' };
  if (name === 'legend') return { primary: '#fbbf24', highlight: '#fde68a', secondary: '#f59e0b' };
  return { primary: '#f9a8d4', highlight: '#fbcfe8', secondary: '#f472b6' };
});

const strokeColor = computed(() => colors.value.stroke);

const limbColor = computed(() => {
  return colors.value.stroke;
});

const limbsViewBox = '0 0 100 115';

// Rank intensity: lower ranks dance slowly, higher ranks dance wildly
const rankIntensity = computed(() => {
  const name = props.rankName.toLowerCase();
  const intensityMap: Record<string, number> = {
    'beginner': 1,
    'novice': 2,
    'intermediate': 3,
    'advanced': 4,
    'expert': 5,
    'veteran': 6,
    'commander': 7,
    'elite': 8,
    'master': 9,
    'ancient': 10,
    'mythic': 11,
    'legend': 12,
  };
  return intensityMap[name] || 1;
});

// Dance class that determines the animation style
const danceClass = computed(() => {
  const intensity = rankIntensity.value;
  if (intensity <= 2) return 'af-dance-sway';
  if (intensity <= 4) return 'af-dance-wave';
  if (intensity <= 6) return 'af-dance-groove';
  if (intensity <= 8) return 'af-dance-disco';
  if (intensity <= 10) return 'af-dance-breakdance';
  return 'af-dance-rave';
});

// Animation speed varies by rank
const limbStyle = (limb: string) => {
  const baseSpeed = Math.max(0.3, 1.5 - rankIntensity.value * 0.1);
  const delays: Record<string, number> = {
    leftArm: 0,
    rightArm: baseSpeed * 0.25,
    leftLeg: baseSpeed * 0.5,
    rightLeg: baseSpeed * 0.75,
  };
  return {
    '--af-speed': `${baseSpeed}s`,
    '--af-delay': `${delays[limb] || 0}s`,
    '--af-intensity': rankIntensity.value,
  };
};

// Shape Paths
const shapePath = computed(() => {
  const name = props.rankName.toLowerCase();
  if (name === 'beginner') return "M50 10 L85 25 L85 75 L50 90 L15 75 L15 25 Z"; // shield
  if (name === 'novice') return "M50 15 A35 35 0 1 1 49.9 15 Z"; // circle
  if (name === 'intermediate') return "M50 15 L85 80 L15 80 Z"; // triangle
  if (name === 'advanced') return "M50 15 L85 50 L50 85 L15 50 Z"; // diamond
  if (name === 'expert') return "M50 15 L85 40 L70 85 L30 85 L15 40 Z"; // pentagon
  if (name === 'veteran') return "M50 15 L80 30 L80 70 L50 85 L20 70 L20 30 Z"; // hexagon
  if (name === 'commander') return "M50 10 L78 25 L88 55 L68 82 L32 82 L12 55 L22 25 Z"; // heptagon
  if (name === 'elite') return "M50 10 L78 22 L90 50 L78 78 L50 90 L22 78 L10 50 L22 22 Z"; // octagon
  if (name === 'master') return "M50 10 L65 35 L90 50 L65 65 L50 90 L35 65 L10 50 L35 35 Z"; // cross-star
  if (name === 'ancient') return "M50 10 L60 40 L90 40 L65 60 L75 90 L50 75 L25 90 L35 60 L10 40 L40 40 Z"; // star
  if (name === 'mythic') return "M50 5 L90 30 L90 70 L50 95 L10 70 L10 30 Z M50 20 L75 35 L75 65 L50 80 L25 65 L25 35 Z"; // double shield
  if (name === 'legend') return "M50 5 L80 20 L95 50 L80 80 L50 95 L20 80 L5 50 L20 20 Z M50 25 L65 40 L65 60 L50 75 L35 60 L35 40 Z"; // radiant style
  return "M50 15 A35 35 0 1 1 49.9 15 Z";
});

const getSegmentPath = (n: number) => {
  // Bolder, more visible segments centered in the shape
  const width = 12;
  const height = 3;
  const spacing = 6;
  const startY = 50 - (spacing * 2);
  const y = startY + (n - 1) * spacing;
  return `M${50 - width / 2} ${y} h${width} v${height} h${-width} Z`;
};
</script>

<style scoped>
@keyframes shimmer {
  0% { transform: translateX(-100%) skewX(-20deg); }
  50% { opacity: 0.5; }
  100% { transform: translateX(100%) skewX(-20deg); }
}

.animate-shimmer {
  animation: shimmer 3s infinite linear;
}

/* ===== April Fools Dancing Animations ===== */

.april-fools-icon {
  overflow: visible !important;
}

/* Body bounce */
@keyframes af-body-bounce {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-4px); }
}

.af-bounce {
  animation: af-body-bounce var(--af-speed, 1s) ease-in-out infinite;
}

/* 1. SWAY - Beginner/Novice: gentle side-to-side */
@keyframes af-sway-arm-left {
  0%, 100% { transform: rotate(0deg); }
  50% { transform: rotate(-15deg); }
}
@keyframes af-sway-arm-right {
  0%, 100% { transform: rotate(0deg); }
  50% { transform: rotate(15deg); }
}
@keyframes af-sway-leg {
  0%, 100% { transform: rotate(0deg); }
  50% { transform: rotate(5deg); }
}

.af-dance-sway.af-left-arm { animation: af-sway-arm-left var(--af-speed, 1.4s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 20px 55px; }
.af-dance-sway.af-right-arm { animation: af-sway-arm-right var(--af-speed, 1.4s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 80px 55px; }
.af-dance-sway.af-left-leg { animation: af-sway-leg var(--af-speed, 1.4s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 35px 88px; }
.af-dance-sway.af-right-leg { animation: af-sway-leg var(--af-speed, 1.4s) ease-in-out infinite reverse; animation-delay: var(--af-delay, 0s); transform-origin: 65px 88px; }

/* 2. WAVE - Intermediate/Advanced: arms waving more */
@keyframes af-wave-arm-left {
  0%, 100% { transform: rotate(0deg); }
  25% { transform: rotate(-30deg); }
  75% { transform: rotate(10deg); }
}
@keyframes af-wave-arm-right {
  0%, 100% { transform: rotate(0deg); }
  25% { transform: rotate(30deg); }
  75% { transform: rotate(-10deg); }
}
@keyframes af-wave-leg {
  0%, 100% { transform: rotate(0deg); }
  25% { transform: rotate(-10deg); }
  75% { transform: rotate(10deg); }
}

.af-dance-wave.af-left-arm { animation: af-wave-arm-left var(--af-speed, 1.1s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 20px 55px; }
.af-dance-wave.af-right-arm { animation: af-wave-arm-right var(--af-speed, 1.1s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 80px 55px; }
.af-dance-wave.af-left-leg { animation: af-wave-leg var(--af-speed, 1.1s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 35px 88px; }
.af-dance-wave.af-right-leg { animation: af-wave-leg var(--af-speed, 1.1s) ease-in-out infinite reverse; animation-delay: var(--af-delay, 0s); transform-origin: 65px 88px; }

/* 3. GROOVE - Expert/Veteran: funky moves */
@keyframes af-groove-arm-left {
  0%, 100% { transform: rotate(0deg) translateY(0); }
  25% { transform: rotate(-45deg) translateY(-5px); }
  50% { transform: rotate(-20deg) translateY(0); }
  75% { transform: rotate(-55deg) translateY(-8px); }
}
@keyframes af-groove-arm-right {
  0%, 100% { transform: rotate(0deg) translateY(0); }
  25% { transform: rotate(55deg) translateY(-8px); }
  50% { transform: rotate(20deg) translateY(0); }
  75% { transform: rotate(45deg) translateY(-5px); }
}
@keyframes af-groove-leg {
  0%, 100% { transform: rotate(0deg); }
  25% { transform: rotate(-15deg); }
  50% { transform: rotate(5deg); }
  75% { transform: rotate(15deg); }
}

.af-dance-groove.af-left-arm { animation: af-groove-arm-left var(--af-speed, 0.9s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 20px 55px; }
.af-dance-groove.af-right-arm { animation: af-groove-arm-right var(--af-speed, 0.9s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 80px 55px; }
.af-dance-groove.af-left-leg { animation: af-groove-leg var(--af-speed, 0.9s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 35px 88px; }
.af-dance-groove.af-right-leg { animation: af-groove-leg var(--af-speed, 0.9s) ease-in-out infinite reverse; animation-delay: var(--af-delay, 0s); transform-origin: 65px 88px; }

/* 4. DISCO - Commander/Elite: disco fever */
@keyframes af-disco-arm-left {
  0% { transform: rotate(0deg); }
  20% { transform: rotate(-70deg); }
  40% { transform: rotate(-20deg); }
  60% { transform: rotate(-80deg); }
  80% { transform: rotate(-10deg); }
  100% { transform: rotate(0deg); }
}
@keyframes af-disco-arm-right {
  0% { transform: rotate(0deg); }
  20% { transform: rotate(20deg); }
  40% { transform: rotate(80deg); }
  60% { transform: rotate(10deg); }
  80% { transform: rotate(70deg); }
  100% { transform: rotate(0deg); }
}
@keyframes af-disco-leg {
  0%, 100% { transform: rotate(0deg) translateX(0); }
  25% { transform: rotate(-20deg) translateX(-3px); }
  50% { transform: rotate(0deg) translateX(0); }
  75% { transform: rotate(20deg) translateX(3px); }
}

.af-dance-disco.af-left-arm { animation: af-disco-arm-left var(--af-speed, 0.7s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 20px 55px; }
.af-dance-disco.af-right-arm { animation: af-disco-arm-right var(--af-speed, 0.7s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 80px 55px; }
.af-dance-disco.af-left-leg { animation: af-disco-leg var(--af-speed, 0.7s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 35px 88px; }
.af-dance-disco.af-right-leg { animation: af-disco-leg var(--af-speed, 0.7s) ease-in-out infinite reverse; animation-delay: var(--af-delay, 0s); transform-origin: 65px 88px; }

/* 5. BREAKDANCE - Master/Ancient: intense breakdancing */
@keyframes af-breakdance-arm-left {
  0% { transform: rotate(0deg) scale(1); }
  15% { transform: rotate(-90deg) scale(1.1); }
  30% { transform: rotate(-30deg) scale(1); }
  45% { transform: rotate(-100deg) scale(1.15); }
  60% { transform: rotate(-10deg) scale(0.95); }
  75% { transform: rotate(-80deg) scale(1.1); }
  100% { transform: rotate(0deg) scale(1); }
}
@keyframes af-breakdance-arm-right {
  0% { transform: rotate(0deg) scale(1); }
  15% { transform: rotate(30deg) scale(1); }
  30% { transform: rotate(100deg) scale(1.15); }
  45% { transform: rotate(10deg) scale(0.95); }
  60% { transform: rotate(90deg) scale(1.1); }
  75% { transform: rotate(20deg) scale(1); }
  100% { transform: rotate(0deg) scale(1); }
}
@keyframes af-breakdance-leg {
  0%, 100% { transform: rotate(0deg) scaleY(1); }
  20% { transform: rotate(-30deg) scaleY(0.9); }
  40% { transform: rotate(25deg) scaleY(1.1); }
  60% { transform: rotate(-25deg) scaleY(0.9); }
  80% { transform: rotate(30deg) scaleY(1.1); }
}

.af-dance-breakdance.af-left-arm { animation: af-breakdance-arm-left var(--af-speed, 0.5s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 20px 55px; }
.af-dance-breakdance.af-right-arm { animation: af-breakdance-arm-right var(--af-speed, 0.5s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 80px 55px; }
.af-dance-breakdance.af-left-leg { animation: af-breakdance-leg var(--af-speed, 0.5s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 35px 88px; }
.af-dance-breakdance.af-right-leg { animation: af-breakdance-leg var(--af-speed, 0.5s) ease-in-out infinite reverse; animation-delay: var(--af-delay, 0s); transform-origin: 65px 88px; }

/* 6. RAVE - Mythic/Legend: absolute maximum chaos */
@keyframes af-rave-arm-left {
  0% { transform: rotate(0deg) translateY(0) scale(1); }
  10% { transform: rotate(-120deg) translateY(-10px) scale(1.2); }
  20% { transform: rotate(-30deg) translateY(0) scale(0.9); }
  30% { transform: rotate(-100deg) translateY(-15px) scale(1.3); }
  40% { transform: rotate(10deg) translateY(0) scale(1); }
  50% { transform: rotate(-110deg) translateY(-12px) scale(1.2); }
  60% { transform: rotate(-20deg) translateY(5px) scale(0.9); }
  70% { transform: rotate(-130deg) translateY(-10px) scale(1.15); }
  80% { transform: rotate(0deg) translateY(0) scale(1); }
  90% { transform: rotate(-90deg) translateY(-8px) scale(1.1); }
  100% { transform: rotate(0deg) translateY(0) scale(1); }
}
@keyframes af-rave-arm-right {
  0% { transform: rotate(0deg) translateY(0) scale(1); }
  10% { transform: rotate(100deg) translateY(-15px) scale(1.3); }
  20% { transform: rotate(20deg) translateY(5px) scale(0.9); }
  30% { transform: rotate(120deg) translateY(-10px) scale(1.2); }
  40% { transform: rotate(-10deg) translateY(0) scale(1); }
  50% { transform: rotate(130deg) translateY(-12px) scale(1.2); }
  60% { transform: rotate(30deg) translateY(0) scale(0.9); }
  70% { transform: rotate(110deg) translateY(-10px) scale(1.15); }
  80% { transform: rotate(0deg) translateY(0) scale(1); }
  90% { transform: rotate(90deg) translateY(-8px) scale(1.1); }
  100% { transform: rotate(0deg) translateY(0) scale(1); }
}
@keyframes af-rave-leg {
  0%, 100% { transform: rotate(0deg) translateX(0) scaleY(1); }
  12.5% { transform: rotate(-35deg) translateX(-5px) scaleY(0.85); }
  25% { transform: rotate(30deg) translateX(5px) scaleY(1.15); }
  37.5% { transform: rotate(-40deg) translateX(-4px) scaleY(0.9); }
  50% { transform: rotate(35deg) translateX(4px) scaleY(1.1); }
  62.5% { transform: rotate(-30deg) translateX(-3px) scaleY(0.85); }
  75% { transform: rotate(40deg) translateX(5px) scaleY(1.15); }
  87.5% { transform: rotate(-25deg) translateX(-2px) scaleY(0.9); }
}

.af-dance-rave.af-left-arm { animation: af-rave-arm-left var(--af-speed, 0.4s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 20px 55px; }
.af-dance-rave.af-right-arm { animation: af-rave-arm-right var(--af-speed, 0.4s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 80px 55px; }
.af-dance-rave.af-left-leg { animation: af-rave-leg var(--af-speed, 0.4s) ease-in-out infinite; animation-delay: var(--af-delay, 0s); transform-origin: 35px 88px; }
.af-dance-rave.af-right-leg { animation: af-rave-leg var(--af-speed, 0.4s) ease-in-out infinite reverse; animation-delay: var(--af-delay, 0s); transform-origin: 65px 88px; }

/* 7. WANDERING AROUND SCREEN */
@keyframes af-wander-anim {
  0% { transform: translate(0, 0) rotate(0deg); z-index: 1000; }
  33% { transform: translate(var(--af-tx1), var(--af-ty1)) rotate(var(--af-rot1)); z-index: 1000; }
  66% { transform: translate(var(--af-tx2), var(--af-ty2)) rotate(var(--af-rot2)); z-index: 1000; }
  100% { transform: translate(0, 0) rotate(0deg); z-index: 1000; }
}

.af-wander-screen {
  animation: af-wander-anim var(--af-wander-dur, 20s) ease-in-out infinite !important;
  animation-delay: var(--af-wander-delay, 0s) !important;
  pointer-events: none; /* Do not block clicks beneath */
  position: relative;
  z-index: 9999;
}
</style>
