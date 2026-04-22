<script setup lang="ts">
/**
 * 【Viewの役割】 スコア一覧・検索・編集ページ。
 *
 * 機能:
 *  - `App.vue` などから `provide` された「スコアデータ」「合計ポイント更新関数」「リセット関数」を `inject` で受け取る。
 *  - `ScoreSummary.vue` に渡し、スコア表示・絞り込み・編集の UI を提供する。
 *
 * 依存:
 *  - `../components/ScoreSummary.vue` — スコア一覧本体。
 *  - provide/inject: `scoreData` / `updateTotalPoints` / `resetData`。
 */
import { inject } from 'vue'
import type { Ref } from 'vue'
import ScoreSummary from '../components/ScoreSummary.vue'
import type { ScoreData } from '../types/ScoreData'

/** 親（App.vue）からprovideされたスコア配列。`!` で必ず存在すると断言している */
const scoreData = inject<Ref<ScoreData[]>>('scoreData')!
/** 合計 BEAT-PT を更新する関数。子側の集計結果を親へ反映するために使用 */
const updateTotalPoints = inject<(points: number) => void>('updateTotalPoints')!
/** スコアデータを全リセットする関数（アップロードデータ破棄時など） */
const resetData = inject<() => void>('resetData')!
</script>

<template>
  <!-- 画面全体: スコア一覧・編集。子コンポーネントへ inject した値とイベントを中継 -->
  <ScoreSummary
    :scores="scoreData"
    @reset="resetData"
    @update:totalPoints="updateTotalPoints"
    class="w-full"
  />
</template>
