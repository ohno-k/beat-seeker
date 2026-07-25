<script setup lang="ts">
/**
 * 【コンポーネントの役割】 リーグの DIVISION を表すトロフィー型アイコン（11 段階）。
 *
 * tier（0=DIVISION LEGEND 〜 10）に応じて、素材色・王冠・翼・宝石・オーラを段階的に付与し、
 * 上位ほど豪華になるトロフィーを 1 つの SVG から描き分ける。ダッシュボードやリーグ画面の
 * DIVISION 表示に使う。
 */
import { computed } from 'vue';

const props = withDefaults(defineProps<{ tier: number; size?: number }>(), { size: 48 });

/** gradient の id 衝突を避けるためインスタンス固有のサフィックス。 */
const gid = 'div-' + Math.random().toString(36).slice(2, 8);

/**
 * DIVISION ごとの見た目設定（index 0 = LEGEND が最も豪華）。
 * c: グラデーション3色（明→中→暗）, crown: 王冠サイズ(0=無), wings: 翼, glow: オーラ, gems: 宝石数, emblem: 中央エンブレム色。
 */
const CONFIG = [
  { c: ['#FFFDF0', '#FFD54A', '#E0A400'], crown: 2, wings: true, glow: true, gems: 3, emblem: '#B8860B' }, // 0 LEGEND
  { c: ['#FDE9A8', '#F5B000', '#B4780A'], crown: 2, wings: false, glow: true, gems: 2, emblem: '#8A5A06' }, // 1 gold
  { c: ['#FEF0C7', '#FBBF24', '#D97706'], crown: 1, wings: false, glow: false, gems: 1, emblem: '#9A5B0A' }, // 2 gold-lite
  { c: ['#F8FAFC', '#D3DCE6', '#94A3B8'], crown: 1, wings: false, glow: false, gems: 1, emblem: '#64748B' }, // 3 silver
  { c: ['#F1F5F9', '#CBD5E1', '#8595A8'], crown: 0, wings: false, glow: false, gems: 0, emblem: '#64748B' }, // 4 silver-lite
  { c: ['#F4D8BF', '#CD7F32', '#8B4A1E'], crown: 0, wings: false, glow: false, gems: 1, emblem: '#7A4318' }, // 5 bronze
  { c: ['#EBC8A6', '#B87333', '#764618'], crown: 0, wings: false, glow: false, gems: 0, emblem: '#764618' }, // 6 bronze-lite
  { c: ['#E9ECF0', '#9CA3AF', '#697180'], crown: 0, wings: false, glow: false, gems: 0, emblem: '#697180' }, // 7 steel
  { c: ['#E1E6EC', '#8C97A6', '#5B6472'], crown: 0, wings: false, glow: false, gems: 0, emblem: '#5B6472' }, // 8 steel-dark
  { c: ['#D8D4D0', '#A8A29E', '#736A62'], crown: 0, wings: false, glow: false, gems: 0, emblem: '#736A62' }, // 9 stone
  { c: ['#DCC9AC', '#B08D57', '#7A5C38'], crown: 0, wings: false, glow: false, gems: 0, emblem: '#7A5C38' }, // 10 wood
];

const cfg = computed(() => CONFIG[Math.min(10, Math.max(0, props.tier ?? 10))]);
/** 宝石の色（最大3個）。 */
const GEM_COLORS = ['#ef4444', '#3b82f6', '#10b981'];
const gems = computed(() => {
  const pos = [
    { x: 32, y: 22 },
    { x: 27, y: 25 },
    { x: 37, y: 25 },
  ];
  return pos.slice(0, cfg.value.gems).map((p, i) => ({ ...p, color: GEM_COLORS[i] }));
});
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg"
       :aria-label="tier === 0 ? 'DIVISION LEGEND' : `DIVISION ${tier}`" role="img">
    <defs>
      <linearGradient :id="gid" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" :stop-color="cfg.c[0]" />
        <stop offset="55%" :stop-color="cfg.c[1]" />
        <stop offset="100%" :stop-color="cfg.c[2]" />
      </linearGradient>
    </defs>

    <!-- オーラ（上位のみ） -->
    <circle v-if="cfg.glow" cx="32" cy="28" r="27" :fill="cfg.c[1]" opacity="0.16" />

    <!-- 翼（LEGEND のみ） -->
    <template v-if="cfg.wings">
      <path d="M18 18 C6 13 3 27 14 31 C8 27 12 21 18 23 Z" :fill="cfg.c[0]" opacity="0.85" />
      <path d="M46 18 C58 13 61 27 50 31 C56 27 52 21 46 23 Z" :fill="cfg.c[0]" opacity="0.85" />
    </template>

    <!-- 取っ手 -->
    <path d="M18 16 C9 16 9 28 19 29" :stroke="cfg.c[2]" stroke-width="3" fill="none" stroke-linecap="round" />
    <path d="M46 16 C55 16 55 28 45 29" :stroke="cfg.c[2]" stroke-width="3" fill="none" stroke-linecap="round" />

    <!-- カップ本体 -->
    <path d="M18 13 H46 V20 C46 30.5 39.7 38 32 38 C24.3 38 18 30.5 18 20 Z"
          :fill="`url(#${gid})`" :stroke="cfg.c[2]" stroke-width="1" />
    <!-- ハイライト -->
    <path d="M22 16 C22 24 25 30 30 33" stroke="#ffffff" stroke-width="2" fill="none" opacity="0.35" stroke-linecap="round" />

    <!-- ステム＆台座 -->
    <path d="M29 38 H35 V45 H29 Z" :fill="cfg.c[1]" />
    <path d="M23 45 H41 L43.5 51 H20.5 Z" :fill="cfg.c[2]" />
    <path d="M19 51 H45 V56 H19 Z" :fill="cfg.c[2]" />
    <path d="M19 51 H45 V52.5 H19 Z" fill="#ffffff" opacity="0.25" />

    <!-- 中央エンブレム（星）。宝石がある上位は宝石を優先し星は小さめ。 -->
    <path v-if="cfg.gems === 0"
          d="M32 18.5 l1.8 3.7 4.1 0.6 -3 2.9 0.7 4.1 -3.6 -1.9 -3.6 1.9 0.7 -4.1 -3 -2.9 4.1 -0.6 Z"
          :fill="cfg.emblem" opacity="0.85" />

    <!-- 宝石（上位のみ） -->
    <circle v-for="(g, i) in gems" :key="i" :cx="g.x" :cy="g.y" r="2.1" :fill="g.color" stroke="#ffffff" stroke-width="0.6" />

    <!-- 王冠（上位のみ）。LEGEND(crown=2) は中央に赤い宝石を載せる。 -->
    <g v-if="cfg.crown > 0">
      <path d="M23 12 L26 6 L32 10 L38 6 L41 12 Z"
            fill="#FFD84A" stroke="#E0A400" stroke-width="0.8" stroke-linejoin="round" />
      <circle v-if="cfg.crown === 2" cx="32" cy="7.5" r="1.3" fill="#ef4444" stroke="#ffffff" stroke-width="0.4" />
    </g>
  </svg>
</template>
