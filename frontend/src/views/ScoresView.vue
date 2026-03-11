<script setup lang="ts">
import { inject } from 'vue'
import type { Ref } from 'vue'
import ScoreSummary from '../components/ScoreSummary.vue'
import type { ScoreData } from '../types/ScoreData'

const scoreData = inject<Ref<ScoreData[]>>('scoreData')!
const updateTotalPoints = inject<(points: number) => void>('updateTotalPoints')!
const resetData = inject<() => void>('resetData')!
</script>

<template>
  <ScoreSummary
    :scores="scoreData"
    @reset="resetData"
    @update:totalPoints="updateTotalPoints"
    class="w-full"
  />
</template>
