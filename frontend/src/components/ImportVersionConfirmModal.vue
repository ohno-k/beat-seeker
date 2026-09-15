<script setup lang="ts">
/**
 * 【コンポーネントの役割】 過去作 CSV の取り込み確認と、判定に失敗した CSV の理由提示を行うダイアログ。
 *
 * 作品バージョンはユーザーに選ばせず、CSV の「バージョン」列から自動判定する
 * （判定原理は `utils/iidxVersions.ts` の冒頭コメントを参照）。
 *
 * 表示するのは次の 3 ケース:
 *  - 過去作（30〜前作）と判定された … 通常とは別テーブルへ保存する特殊な操作なので同意を取る
 *  - 前作と判定されたが新作稼働直後（`ambiguous`） … 新作で新曲を 1 曲も踏んでいない人の
 *    新作 CSV は前作と区別が付かないため、「前作の CSV」「新作の CSV」のどちらとして取り込むかを選ばせる
 *  - 判定に失敗した               … 理由を提示して取り込みを中止する
 * 現行作と判定された CSV は確認を挟まずそのまま取り込まれるため、ここには渡ってこない。
 *
 * 判定に失敗した CSV は取り込ませない。特に「未知の作品名を含む CSV」を
 * 現行作として取り込むと、未対応の新作スコアが現行スコアに混ざりランキングや
 * BEAT-PT が汚染される。元に戻せない事故なので、ここで確実に止める。
 *
 * @emits confirm         取り込み実行。親が判定済みバージョン（過去作）で取り込み処理を行う。
 * @emits confirm-current 現行作の CSV として取り込む（`ambiguous` のときだけ）。親は通常の取り込みへ進む。
 * @emits cancel          取り込み中止。
 */
import { computed } from 'vue';
import { useI18n } from '../composables/useI18n';
import type { VersionDetectionResult } from '../utils/csvParser';
import { versionName, versionBadgeClass, MIN_PAST_VERSION, CURRENT_VERSION } from '../utils/iidxVersions';

const { t } = useI18n();

const props = defineProps<{
  /** 表示フラグ。 */
  isOpen: boolean;
  /** 自動判定の結果。null の間は何も描画しない。 */
  detection: VersionDetectionResult | null;
  /** CSV に含まれる曲数（判定成功時のみ表示）。 */
  songCount: number;
  /** CSV 内の最終プレー日時。空文字なら行を出さない。 */
  lastPlayTime: string;
  /** 同じ作品で既に取り込み済みの譜面数。未取込 or 現行作なら null。 */
  existingCount: number | null;
  /** 取り込み処理中フラグ（ボタンの二度押し防止）。 */
  isSubmitting?: boolean;
  /** 前作／現行作のどちらとして取り込むかをユーザーに選ばせるモード（新作稼働直後のみ）。 */
  ambiguous?: boolean;
}>();

const emit = defineEmits<{
  (e: 'confirm'): void;
  (e: 'confirm-current'): void;
  (e: 'cancel'): void;
}>();

/** 判定に成功したか。false ならエラー表示に切り替える。 */
const isOk = computed(() => props.detection?.ok === true);

/** 判定されたバージョン番号（成功時のみ）。 */
const detectedVersion = computed(() => (props.detection?.ok ? props.detection.version : null));

/** 「どちらの作品か」を選ばせる文言に使う置換値。 */
const ambiguousParams = computed(() => ({
  previous: versionName(detectedVersion.value),
  current: versionName(CURRENT_VERSION),
}));

/**
 * 判定失敗時に表示する本文。理由ごとに文言を切り替える。
 * ユーザーが次に何をすればよいか（公式 CSV をそのまま使う等）まで含める。
 */
const errorMessage = computed(() => {
  const d = props.detection;
  if (!d || d.ok) return '';

  switch (d.reason) {
    case 'noLabels':
      return t('past.error.noLabels');
    case 'unknownLabel':
      return t('past.error.unknownLabel', { labels: d.unknownLabels.join(', ') });
    case 'tooOld':
      return t('past.error.tooOld', {
        detected: `${d.version} ${versionName(d.version)}`,
        min: `${MIN_PAST_VERSION} ${versionName(MIN_PAST_VERSION)}`,
      });
    default:
      return '';
  }
});
</script>

<template>
  <div
    v-if="isOpen && detection"
    class="fixed inset-0 z-[120] bg-slate-900/60 dark:bg-slate-950/80 flex items-center justify-center p-4 backdrop-blur-sm"
    @click.self="emit('cancel')"
  >
    <div class="card w-full max-w-md p-6">

      <!-- 判定成功: 取り込み内容の確認 -->
      <template v-if="isOk">
        <h3 class="text-base font-bold text-slate-800 dark:text-slate-100 mb-4">
          {{ ambiguous ? t('past.confirm.ambiguousTitle') : t('past.confirm.title') }}
        </h3>

        <!-- 判定された作品を主役として大きく見せる -->
        <div class="flex items-center gap-3 mb-3">
          <span
            class="px-2 py-0.5 text-xs font-bold rounded border"
            :class="versionBadgeClass(detectedVersion)"
          >{{ detectedVersion }}</span>
          <span class="text-lg font-bold text-slate-800 dark:text-slate-100">
            {{ versionName(detectedVersion) }}
          </span>
        </div>

        <p class="text-sm text-slate-600 dark:text-slate-300 mb-4">
          {{ ambiguous ? t('past.confirm.ambiguousMessage', ambiguousParams) : t('past.confirm.messagePast') }}
        </p>

        <!-- 取り込み内容の内訳 -->
        <dl class="text-sm border-t border-slate-200 dark:border-slate-700 pt-3 space-y-1.5 mb-4">
          <div class="flex justify-between">
            <dt class="text-slate-500 dark:text-slate-400">{{ t('past.confirm.songCount') }}</dt>
            <dd class="font-medium text-slate-800 dark:text-slate-100 tabular-nums">{{ songCount.toLocaleString() }}</dd>
          </div>
          <div v-if="lastPlayTime" class="flex justify-between">
            <dt class="text-slate-500 dark:text-slate-400">{{ t('past.confirm.lastPlay') }}</dt>
            <dd class="font-medium text-slate-800 dark:text-slate-100 tabular-nums">{{ lastPlayTime }}</dd>
          </div>
          <div v-if="existingCount !== null" class="flex justify-between">
            <dt class="text-slate-500 dark:text-slate-400">{{ t('past.confirm.existing') }}</dt>
            <dd class="font-medium text-slate-800 dark:text-slate-100">
              {{ t('past.confirm.existingMerge', { count: existingCount.toLocaleString() }) }}
            </dd>
          </div>
        </dl>

        <!-- 前作／現行作を選ばせるモード: 2 つの取り込み先を縦に並べる -->
        <template v-if="ambiguous">
          <div class="flex flex-col gap-2 mb-3">
            <button class="btn-primary w-full" :disabled="isSubmitting" @click="emit('confirm-current')">
              {{ t('past.confirm.asCurrent', ambiguousParams) }}
            </button>
            <button class="btn-secondary w-full" :disabled="isSubmitting" @click="emit('confirm')">
              {{ isSubmitting ? t('past.confirm.submitting') : t('past.confirm.asPast', ambiguousParams) }}
            </button>
          </div>
          <p class="text-xs text-slate-500 dark:text-slate-400 mb-1">
            {{ t('past.confirm.asCurrentNote') }}
          </p>
          <p class="text-xs text-slate-500 dark:text-slate-400 mb-4">
            {{ t('past.notRanked') }}
          </p>
          <div class="flex justify-end">
            <button class="btn-secondary" :disabled="isSubmitting" @click="emit('cancel')">
              {{ t('past.confirm.cancel') }}
            </button>
          </div>
        </template>

        <!-- 通常の過去作取り込み -->
        <template v-else>
          <p class="text-xs text-slate-500 dark:text-slate-400 mb-5">
            {{ t('past.notRanked') }}
          </p>

          <div class="flex justify-end gap-2">
            <button class="btn-secondary" :disabled="isSubmitting" @click="emit('cancel')">
              {{ t('past.confirm.cancel') }}
            </button>
            <button class="btn-primary" :disabled="isSubmitting" @click="emit('confirm')">
              {{ isSubmitting ? t('past.confirm.submitting') : t('past.confirm.submit') }}
            </button>
          </div>
        </template>
      </template>

      <!-- 判定失敗: 理由の提示のみ。取り込みボタンは出さない -->
      <template v-else>
        <h3 class="text-base font-bold text-red-600 dark:text-red-400 mb-3">
          {{ t('past.error.title') }}
        </h3>
        <p class="text-sm text-slate-600 dark:text-slate-300 whitespace-pre-line mb-5">
          {{ errorMessage }}
        </p>
        <div class="flex justify-end">
          <button class="btn-secondary" @click="emit('cancel')">
            {{ t('past.error.close') }}
          </button>
        </div>
      </template>

    </div>
  </div>
</template>
