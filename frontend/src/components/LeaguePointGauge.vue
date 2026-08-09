<script setup lang="ts">
/**
 * 昇降格ポイント（PT）のインジケーター。
 *
 * PT は 0 を中心に -8〜+8 の範囲を動き、+8 到達で昇格・-8 到達で降格する。
 * 数字だけでは「あとどれくらいで昇格/降格か」が分かりにくいため、
 * 1 PT = 1 目盛のバーで中央から左右に伸ばして可視化する。
 * 上位 DIVISION（LEGEND）は昇格側、最下位（DIVISION 10）は降格側が存在しないので、
 * その側は目盛を薄くして注記を出す。
 */
import { computed } from 'vue';
import { useI18n } from '../composables/useI18n';

const props = withDefaults(defineProps<{
  /** 現在の昇降格ポイント。 */
  points: number | null | undefined;
  /** 所属 DIVISION（0 = LEGEND、10 = 最下位）。端の DIVISION の注記に使う。 */
  tier?: number | null;
  /** 昇降格に必要な PT（= 目盛の数。バックエンドの POINT_CAP と合わせる）。 */
  cap?: number;
}>(), {
  tier: null,
  cap: 8,
});

const { t } = useI18n();

/** 表示に使う PT（null は 0 扱い・範囲外はクランプ）。 */
const value = computed(() => Math.max(-props.cap, Math.min(props.cap, props.points ?? 0)));

/** 最上位 DIVISION（これ以上の昇格が無い）。 */
const isTop = computed(() => props.tier === 0);
/** 最下位 DIVISION（これ以上の降格が無い）。 */
const isBottom = computed(() => props.tier === 10);

/** 昇格まで / 降格まで の残り PT。 */
const toPromote = computed(() => Math.max(0, props.cap - value.value));
const toRelegate = computed(() => Math.max(0, props.cap + value.value));

/** 符号付きの PT 表示（+2 / -3 / 0）。 */
const label = computed(() => (value.value > 0 ? `+${value.value}` : `${value.value}`));

/**
 * 降格側の目盛（左から -cap ... -1 の順）。i は 1..cap。
 * i 番目の目盛が表す PT は -(cap - i + 1)。現在値がそこまで達していれば点灯。
 */
const negOn = (i: number) => value.value <= -(props.cap - i + 1);

/** 昇格側の目盛（左から +1 ... +cap の順）。i 番目は +i を表す。 */
const posOn = (i: number) => value.value >= i;
</script>

<template>
  <div class="w-full sm:w-80">
    <div class="flex items-center justify-between text-[10px] font-semibold leading-none">
      <span :class="isBottom ? 'text-slate-400 dark:text-slate-600' : 'text-rose-600 dark:text-rose-400'">
        ▼ {{ t('league.gauge.relegate') }}
      </span>
      <span class="text-xs font-bold tabular-nums text-slate-700 dark:text-slate-200">
        {{ t('league.points') }} {{ label }}
      </span>
      <span :class="isTop ? 'text-slate-400 dark:text-slate-600' : 'text-emerald-600 dark:text-emerald-400'">
        {{ t('league.gauge.promote') }} ▲
      </span>
    </div>

    <!-- 目盛バー: 中央が 0、左が降格側、右が昇格側。1 目盛 = 1 PT。 -->
    <div class="mt-1 flex items-center gap-px" :aria-label="`${t('league.points')} ${label}`">
      <span
        v-for="i in cap"
        :key="`neg-${i}`"
        class="h-2.5 flex-1 rounded-[2px] transition-colors"
        :class="negOn(i)
          ? 'bg-rose-500 dark:bg-rose-400'
          : (isBottom ? 'bg-slate-100 dark:bg-slate-800' : 'bg-slate-200 dark:bg-slate-700')"
      ></span>
      <span class="w-0.5 h-4 rounded-full bg-slate-400 dark:bg-slate-500 mx-0.5"></span>
      <span
        v-for="i in cap"
        :key="`pos-${i}`"
        class="h-2.5 flex-1 rounded-[2px] transition-colors"
        :class="posOn(i)
          ? 'bg-emerald-500 dark:bg-emerald-400'
          : (isTop ? 'bg-slate-100 dark:bg-slate-800' : 'bg-slate-200 dark:bg-slate-700')"
      ></span>
    </div>

    <p class="mt-1 text-[10px] leading-tight text-slate-500 dark:text-slate-400">
      <template v-if="isTop">{{ t('league.gauge.topDivision') }}</template>
      <template v-else>{{ t('league.gauge.toPromote', { n: toPromote }) }}</template>
      <span class="mx-1 text-slate-300 dark:text-slate-600">/</span>
      <template v-if="isBottom">{{ t('league.gauge.bottomDivision') }}</template>
      <template v-else>{{ t('league.gauge.toRelegate', { n: toRelegate }) }}</template>
    </p>
  </div>
</template>
