<template>
  <div class="relative flex items-center justify-center select-none" :class="sizeClass">
    <!-- SVG Icon -->
    <svg 
      viewBox="0 0 100 100" 
      fill="none" 
      xmlns="http://www.w3.org/2000/svg"
      class="w-full h-full filter drop-shadow-xl"
    >
      <defs>
        <!-- Complex Gradients -->
        <linearGradient :id="`grad-${rankName}-${tier}`" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" :stop-color="colors.primary" />
          <stop offset="50%" :stop-color="colors.highlight" />
          <stop offset="100%" :stop-color="colors.secondary" />
        </linearGradient>
        
        <linearGradient :id="`glow-grad-${rankName}`" x1="0%" y1="0%" x2="0%" y2="100%">
          <stop offset="0%" :stop-color="colors.primary" stop-opacity="1" />
          <stop offset="100%" :stop-color="colors.primary" stop-opacity="0.2" />
        </linearGradient>

        <!-- Dynamic Filters -->
        <filter :id="`inner-glow-${rankName}`">
          <feGaussianBlur in="SourceAlpha" stdDeviation="2" result="blur" />
          <feComposite in="SourceGraphic" in2="blur" operator="out" result="glow" />
          <feFlood :flood-color="colors.stroke" flood-opacity="0.8" result="color" />
          <feComposite in="color" in2="glow" operator="in" />
          <feComposite in2="SourceGraphic" operator="over" />
        </filter>

        <filter :id="`outer-glow-${rankName}`" x="-20%" y="-20%" width="140%" height="140%">
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
        :filter="`url(#outer-glow-${rankName})`"
      />

      <!-- Base Shape -->
      <path 
        :d="shapePath" 
        :fill="`url(#grad-${rankName}-${tier})`"
        :stroke="strokeColor"
        stroke-width="2.5"
        stroke-linejoin="round"
        :filter="`url(#inner-glow-${rankName})`"
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

const props = defineProps<{
  rankName: string;
  tier?: number;
  size?: 'sm' | 'md' | 'lg';
}>();

const sizeClass = computed(() => {
  switch (props.size) {
    case 'sm': return 'w-8 h-8';
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
  if (name === 'elite') return { primary: '#9a3412', highlight: '#fb923c', secondary: '#431407', stroke: '#ffedd5' };
  if (name === 'master') return { primary: '#991b1b', highlight: '#f87171', secondary: '#450a0a', stroke: '#fee2e2' };
  if (name === 'ancient') return { primary: '#3730a3', highlight: '#818cf8', secondary: '#1e1b4b', stroke: '#e0e7ff' };
  if (name === 'mythic') return { primary: '#6b21a8', highlight: '#c084fc', secondary: '#2e1065', stroke: '#f3e8ff' };
  if (name === 'legend') return { primary: '#854d0e', highlight: '#fbbf24', secondary: '#422006', stroke: '#fef3c7' };
  return { primary: '#475569', highlight: '#94a3b8', secondary: '#1e293b', stroke: '#cbd5e1' };
});

const strokeColor = computed(() => colors.value.stroke);

// Shape Paths
const shapePath = computed(() => {
  const name = props.rankName.toLowerCase();
  if (name === 'beginner') return "M50 10 L85 25 L85 75 L50 90 L15 75 L15 25 Z"; // shield
  if (name === 'novice') return "M50 15 A35 35 0 1 1 49.9 15 Z"; // circle
  if (name === 'intermediate') return "M50 15 L85 80 L15 80 Z"; // triangle
  if (name === 'advanced') return "M50 15 L85 50 L50 85 L15 50 Z"; // diamond
  if (name === 'expert') return "M50 15 L85 40 L70 85 L30 85 L15 40 Z"; // pentagon
  if (name === 'veteran') return "M50 15 L80 30 L80 70 L50 85 L20 70 L20 30 Z"; // hexagon
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
</style>
