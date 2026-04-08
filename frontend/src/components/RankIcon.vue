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
        <!-- Shape Clip for internal shading -->
        <clipPath :id="`shape-clip-${uid}`">
          <path :d="shapePath" />
        </clipPath>

        <!-- Rich Metallic/Jewel Gradient -->
        <linearGradient :id="`grad-${uid}`" x1="15%" y1="5%" x2="85%" y2="95%">
          <stop offset="0%" :stop-color="isAprilFoolsActive ? aprilColors.highlight : colors.highlight" />
          <stop offset="35%" :stop-color="isAprilFoolsActive ? aprilColors.primary : colors.primary" />
          <stop offset="70%" :stop-color="isAprilFoolsActive ? aprilColors.primary : colors.primary" />
          <stop offset="100%" :stop-color="isAprilFoolsActive ? aprilColors.secondary : colors.secondary" />
        </linearGradient>
        
        <!-- Glass Reflection Layer -->
        <linearGradient :id="`glass-grad-${uid}`" x1="0%" y1="0%" x2="50%" y2="100%">
          <stop offset="0%" stop-color="white" stop-opacity="0.6" />
          <stop offset="45%" stop-color="white" stop-opacity="0.1" />
          <stop offset="50%" stop-color="white" stop-opacity="0.0" />
          <stop offset="100%" stop-color="white" stop-opacity="0.0" />
        </linearGradient>

        <!-- Bottom Inner Glow -->
        <linearGradient :id="`bottom-glow-${uid}`" x1="0%" y1="100%" x2="0%" y2="0%">
          <stop offset="0%" :stop-color="isAprilFoolsActive ? aprilColors.highlight : colors.highlight" stop-opacity="0.8" />
          <stop offset="50%" :stop-color="isAprilFoolsActive ? aprilColors.highlight : colors.highlight" stop-opacity="0.0" />
        </linearGradient>

        <!-- Dynamic Filters -->
        <filter :id="`inner-glow-${uid}`">
          <feGaussianBlur in="SourceAlpha" stdDeviation="2.5" result="blur" />
          <feComposite in="SourceGraphic" in2="blur" operator="out" result="glow" />
          <feFlood :flood-color="colors.secondary" flood-opacity="0.9" result="color" />
          <feComposite in="color" in2="glow" operator="in" />
          <feComposite in2="SourceGraphic" operator="over" />
        </filter>

        <filter :id="`outer-glow-${uid}`" x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur in="SourceAlpha" stdDeviation="4" result="blur" />
          <feFlood :flood-color="colors.primary" flood-opacity="0.6" result="color" />
          <feComposite in="color" in2="blur" operator="in" result="shadow" />
          <feOffset in="shadow" dx="0" dy="0" result="offsetShadow" />
          <feMerge>
            <feMergeNode in="offsetShadow" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>

        <!-- Gorgeous Aura for Legend -->
        <filter :id="`legend-aura-${uid}`" x="-40%" y="-40%" width="180%" height="180%">
          <feGaussianBlur in="SourceGraphic" stdDeviation="8" result="blur" />
        </filter>

        <!-- Supporter Gold Border Gradient -->
        <linearGradient :id="`supporter-grad-${uid}`" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="100" y2="100">
          <stop offset="0%" stop-color="#fde68a" />
          <stop offset="25%" stop-color="#fbbf24" />
          <stop offset="50%" stop-color="#f59e0b" />
          <stop offset="75%" stop-color="#fbbf24" />
          <stop offset="100%" stop-color="#fde68a" />
        </linearGradient>

        <!-- Supporter Static Outer Glow -->
        <filter :id="`supporter-glow-${uid}`" x="-30%" y="-30%" width="160%" height="160%">
          <feGaussianBlur in="SourceAlpha" stdDeviation="3.5" result="blur" />
          <feFlood flood-color="#f59e0b" flood-opacity="0.5" result="color" />
          <feComposite in="color" in2="blur" operator="in" result="shadow" />
          <feMerge>
            <feMergeNode in="shadow" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>

        <!-- Drop shadow for tier segments -->
        <filter :id="`segment-shadow-${uid}`" x="-20%" y="-20%" width="140%" height="140%">
          <feDropShadow dx="0" dy="1" stdDeviation="1" flood-color="black" flood-opacity="0.5" />
        </filter>
      </defs>

      <!-- Glow Underlay for high ranks -->
      <path 
        v-if="tier && tier > 1 || isLegend"
        :d="shapePath" 
        :fill="colors.primary" 
        fill-opacity="0.1" 
        :filter="`url(#outer-glow-${uid})`"
      />

      <!-- Extra Radiant Aura for Legend -->
      <path 
        v-if="isLegend"
        :d="shapePath" 
        :fill="colors.highlight"
        fill-opacity="0.4"
        :filter="`url(#legend-aura-${uid})`"
        class="animate-pulse"
      />

      <!-- Supporter Gold Outer Border -->
      <g v-if="isSupporter">
        <!-- Soft gold glow behind border -->
        <path
          :d="shapePath"
          fill="none"
          stroke="#fbbf24"
          stroke-width="10"
          stroke-linejoin="round"
          stroke-opacity="0.35"
          :filter="`url(#supporter-glow-${uid})`"
          transform="scale(1.08)"
          style="transform-origin: 50% 50%"
        />
        <!-- Gold border ring -->
        <path
          :d="shapePath"
          fill="none"
          :stroke="`url(#supporter-grad-${uid})`"
          stroke-width="5"
          stroke-linejoin="round"
          transform="scale(1.07)"
          style="transform-origin: 50% 50%"
        />
      </g>

      <!-- Base Shape -->
      <path
        :d="shapePath"
        :fill="`url(#grad-${uid})`"
        :stroke="colors.stroke"
        stroke-width="2.5"
        stroke-linejoin="round"
        :filter="`url(#inner-glow-${uid})`"
      />

      <!-- Internal Premium Effects (clipped to shape) -->
      <g :clip-path="`url(#shape-clip-${uid})`">
        <!-- Inner Border Ring -->
        <path 
          :d="shapePath" 
          fill="none"
          :stroke="colors.highlight"
          stroke-width="1.5"
          stroke-opacity="0.9"
          transform="scale(0.88)"
          style="transform-origin: 50% 50%"
        />

        <!-- Glass Reflection Overlay -->
        <path 
          :d="shapePath" 
          :fill="`url(#glass-grad-${uid})`"
        />

        <!-- Bottom Bounce Light -->
        <path 
          :d="shapePath" 
          :fill="`url(#bottom-glow-${uid})`"
        />
      </g>
      
      <!-- Max Tier (5) Diamond Emblem -->
      <g v-if="tier === 5" class="tier-segments" :filter="`url(#segment-shadow-${uid})`">
        <!-- Top Left Facet (Lightest) -->
        <path d="M50 35 L38 50 L50 50 Z" fill="#ffffff" />
        <!-- Top Right Facet -->
        <path d="M50 35 L62 50 L50 50 Z" fill="#f1f5f9" />
        <!-- Bottom Left Facet -->
        <path d="M50 50 L38 50 L50 65 Z" fill="#e2e8f0" />
        <!-- Bottom Right Facet (Darkest) -->
        <path d="M50 50 L62 50 L50 65 Z" fill="#cbd5e1" />
        <!-- Thin Unified Border -->
        <path d="M50 35 L62 50 L50 65 L38 50 Z" fill="none" stroke="#ffffff" stroke-width="0.75" stroke-linejoin="round" />
      </g>

      <!-- Tier Segments (More visible "Energy Bars") for tiers 1-4 -->
      <g v-else-if="tier && tier > 0" class="tier-segments">
        <path 
          v-for="n in tier" 
          :key="n" 
          :d="getSegmentPath(n)" 
          fill="white" 
          :filter="`url(#segment-shadow-${uid})`"
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
  isSupporter?: boolean;
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
  // Premium metallic/jewel palettes
  if (name === 'beginner') return { primary: '#64748b', highlight: '#f8fafc', secondary: '#334155', stroke: '#e2e8f0' };
  if (name === 'novice') return { primary: '#5c7c99', highlight: '#e2e8f0', secondary: '#2e455e', stroke: '#94a3b8' };
  if (name === 'intermediate') return { primary: '#0284c7', highlight: '#e0f2fe', secondary: '#082f49', stroke: '#7dd3fc' };
  if (name === 'advanced') return { primary: '#0d9488', highlight: '#ccfbf1', secondary: '#134e4a', stroke: '#5eead4' };
  if (name === 'expert') return { primary: '#059669', highlight: '#d1fae5', secondary: '#064e3b', stroke: '#6ee7b7' };
  if (name === 'veteran') return { primary: '#65a30d', highlight: '#ecfccb', secondary: '#365314', stroke: '#bef264' };
  if (name === 'commander') return { primary: '#eab308', highlight: '#fefce8', secondary: '#713f12', stroke: '#fef08a' };
  if (name === 'elite') return { primary: '#f97316', highlight: '#fff7ed', secondary: '#9a3412', stroke: '#fed7aa' };
  if (name === 'master') return { primary: '#be123c', highlight: '#fff1f2', secondary: '#4c0519', stroke: '#fecdd3' };
  if (name === 'ancient') return { primary: '#4338ca', highlight: '#e0e7ff', secondary: '#1e1b4b', stroke: '#a5b4fc' };
  if (name === 'mythic') return { primary: '#9333ea', highlight: '#fae8ff', secondary: '#3b0764', stroke: '#d8b4fe' };
  if (name === 'legend') return { primary: '#fbbf24', highlight: '#ffffff', secondary: '#92400e', stroke: '#fef08a' }; // Radiant Gold
  return { primary: '#64748b', highlight: '#f8fafc', secondary: '#334155', stroke: '#e2e8f0' };
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
