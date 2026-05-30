<script setup lang="ts">
/**
 * 【コンポーネントの役割】 INFINITAS の画面共有 → 自動リザルト取り込みフローの UI を提供する。
 *
 * フロー:
 *  1. 「監視開始」ボタン → getDisplayMedia 起動（useInfinitasMonitor.start）
 *  2. 検出ループが「STAGE RESULT」を発見 → 確認モーダル（InfinitasResultModal）
 *  3. ユーザーが値を確認/編集して「登録」 → useScoreUpload.upload で 1 譜面ずつ送信
 *  4. 監視は継続。次のリザルトを待機
 *
 * 注意:
 *  - INFINITAS は FHD 解像度。画面共有はフルスクリーンの INFINITAS ウィンドウ全体を選ぶ前提
 *  - この機能はログイン済みユーザーへ一般開放（親側で未ログイン時は導線を出さない）
 */
import { ref, onBeforeUnmount } from 'vue';
import { useInfinitasMonitor, type InfinitasResult, type PlaySide } from '../composables/useInfinitasMonitor';
import { classifyResult, resolveScoreSource, buildScoreData } from '../composables/infinitasAutoImport';
import { useScoreUpload } from '../composables/useScoreUpload';
import { useScores } from '../composables/useScores';
import { useAuth } from '../composables/useAuth';
import InfinitasResultModal from './InfinitasResultModal.vue';
import type { ScoreData } from '../types/ScoreData';
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();
const { user } = useAuth();
// 登録プレイサイドに応じて 1P/2P の ROI セットを選択。未設定なら auto（両側 OCR で判定）。
const playSide: PlaySide = user.value?.playSide === '2P' ? '2P'
  : user.value?.playSide === '1P' ? '1P'
  : 'auto';
// classifyResult を注入: 全情報が揃って整合していれば 'auto'（モーダル無しで自動登録）、
// 曖昧なら 'manual'（確認モーダル）、演出中は 'skip'（無視して監視継続）。
const { status, errorMessage, detectionCount, recentResults, start, stop, resumeMonitoring, onResult } =
  useInfinitasMonitor(playSide, classifyResult);
const { upload } = useScoreUpload();
const { fetchMyScores } = useScores();

/**
 * beat-seeker 上の既存スコアキャッシュ。
 * 監視開始時に 1 回だけ取得し、INFINITAS モーダルで「既存スコアの有無」を判定するのに使う。
 */
const existingScores = ref<ScoreData[]>([]);

const videoRef = ref<HTMLVideoElement | null>(null);
const pendingResult = ref<InfinitasResult | null>(null);

/** アップロード状況のメッセージ表示。 */
const uploadingMessage = ref('');
const uploadErrorMessage = ref('');
/** 累計アップロード件数。 */
const uploadedCount = ref(0);

onResult((result, decision) => {
  if (decision === 'auto') {
    // 全情報が揃って整合 → 確認モーダルを出さずに自動登録
    autoImport(result);
  } else {
    // 曖昧 → 確認モーダルで人手確認
    pendingResult.value = result;
  }
});

/**
 * 確認モーダルを介さず自動登録する。
 * resolveScoreSource で今回/ベストを選び、buildScoreData で ScoreData を組み立てて送信。
 * 送信後は監視を再開する。auto-learn は行わない（ユーザー確定値ではないため）。
 */
const autoImport = async (result: InfinitasResult) => {
  const source = resolveScoreSource(result, existingScores.value);
  const scoreData = buildScoreData(result, source);
  if (!scoreData) { resumeMonitoring(); return; }
  await doUpload(scoreData, true);
};

const handleStart = async () => {
  if (!videoRef.value) return;
  uploadErrorMessage.value = '';
  // 監視開始時に beat-seeker 上の既存スコアを取得してキャッシュ
  try {
    existingScores.value = await fetchMyScores();
  } catch {
    existingScores.value = [];
  }
  await start(videoRef.value);
};

const handleStop = () => {
  stop();
  pendingResult.value = null;
};

/**
 * ScoreData を送信する共通処理。手動確認(handleConfirm)と自動登録(autoImport)の両方から使う。
 * @param scoreData 送信するスコア
 * @param auto 自動登録か（成功メッセージの文言出し分けのみ）
 */
const doUpload = async (scoreData: ScoreData, auto = false) => {
  uploadingMessage.value = t('infinitas.uploading');
  uploadErrorMessage.value = '';
  try {
    // source="infinitas" を明示的に渡す。サーバ側で arcade マスタに存在しない曲は
    // skippedInfinitasOnly でカウントされて保存されない（INFINITAS のみ収録曲の除外）。
    const res = await upload([scoreData], 'infinitas');
    if ((res.skippedInfinitasOnly ?? 0) > 0 && res.updatedCount === 0) {
      // 唯一送ったレコードが INFINITAS のみ収録扱いで弾かれたケース。ユーザーに明示する。
      uploadingMessage.value = '';
      uploadErrorMessage.value = t('infinitas.skippedInfinitasOnly');
    } else {
      uploadedCount.value++;
      const msg = auto
        ? t('infinitas.autoUploadSuccess', { title: scoreData.title, count: res.updatedCount })
        : t('infinitas.uploadSuccess', { count: res.updatedCount });
      uploadingMessage.value = msg;
      setTimeout(() => { uploadingMessage.value = ''; }, 3500);
    }
  } catch (e: any) {
    console.error('[infinitas-monitor] upload failed:', e);
    uploadErrorMessage.value = e?.message || t('infinitas.uploadFailed');
  } finally {
    resumeMonitoring();
  }
};

const handleConfirm = async (scoreData: ScoreData) => {
  pendingResult.value = null;
  await doUpload(scoreData, false);
};

const handleSkip = () => {
  pendingResult.value = null;
  resumeMonitoring();
};

onBeforeUnmount(() => {
  stop();
});
</script>

<template>
  <div class="space-y-4">
    <!-- 注意書き -->
    <div class="flex items-start gap-2.5 p-3 bg-indigo-50 dark:bg-indigo-950/40 border border-indigo-200 dark:border-indigo-800 rounded-xl">
      <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-indigo-500 shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
      <div class="text-xs text-indigo-800 dark:text-indigo-300 leading-relaxed">
        <p class="font-bold">{{ t('infinitas.notice') }}</p>
        <ul class="mt-1 list-disc list-inside space-y-0.5">
          <li>{{ t('infinitas.noticeResolution') }}</li>
          <li>{{ t('infinitas.noticeFullscreen') }}</li>
          <li>{{ t('infinitas.noticeScope') }}</li>
        </ul>
      </div>
    </div>

    <!-- ステータス -->
    <div class="flex items-center justify-between p-3 rounded-xl border" :class="{
      'bg-slate-100 dark:bg-slate-800/60 border-slate-200 dark:border-slate-700': status === 'idle',
      'bg-amber-50 dark:bg-amber-900/30 border-amber-200 dark:border-amber-800': status === 'initializing',
      'bg-emerald-50 dark:bg-emerald-900/30 border-emerald-200 dark:border-emerald-800': status === 'monitoring',
      'bg-blue-50 dark:bg-blue-900/30 border-blue-200 dark:border-blue-800': status === 'extracting' || status === 'awaiting',
      'bg-red-50 dark:bg-red-900/30 border-red-200 dark:border-red-800': status === 'error',
    }">
      <div class="flex items-center gap-2">
        <span class="inline-block w-2 h-2 rounded-full" :class="{
          'bg-slate-400': status === 'idle',
          'bg-amber-500 animate-pulse': status === 'initializing',
          'bg-emerald-500 animate-pulse': status === 'monitoring',
          'bg-blue-500 animate-pulse': status === 'extracting' || status === 'awaiting',
          'bg-red-500': status === 'error',
        }"></span>
        <span class="text-sm font-bold text-slate-700 dark:text-slate-200">
          {{ t(`infinitas.status_${status}`) }}
        </span>
      </div>
      <span v-if="status !== 'idle' && status !== 'error'" class="text-xs text-slate-500 dark:text-slate-400 tabular-nums">
        {{ t('infinitas.detected', { n: detectionCount }) }}
      </span>
    </div>

    <!-- エラー -->
    <div v-if="errorMessage" class="p-3 bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 rounded-xl text-sm text-red-700 dark:text-red-300">
      {{ errorMessage }}
    </div>

    <!-- アップロードフィードバック -->
    <div v-if="uploadingMessage" class="p-3 bg-green-50 dark:bg-green-900/30 border border-green-200 dark:border-green-800 rounded-xl text-sm text-green-700 dark:text-green-300 font-medium">
      {{ uploadingMessage }}
    </div>
    <div v-if="uploadErrorMessage" class="p-3 bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 rounded-xl text-sm text-red-700 dark:text-red-300">
      {{ uploadErrorMessage }}
    </div>

    <!--
      プレビュー領域。videoRef は常に同一要素を指すため、表示/非表示の切替は v-show で行う。
      v-if/v-else だと再マウントで ref が貼り替わり、start() が古い要素を掴んだまま走る不具合になる。
    -->
    <div v-show="status !== 'idle' && status !== 'error'" class="rounded-xl overflow-hidden border border-slate-200 dark:border-slate-700 bg-black">
      <video ref="videoRef" autoplay muted playsinline class="w-full aspect-video object-contain" />
    </div>

    <!-- 開始/停止ボタン -->
    <button
      v-if="status === 'idle' || status === 'error'"
      @click="handleStart"
      class="w-full py-3 bg-gradient-to-r from-blue-600 to-indigo-600 text-white font-bold rounded-xl shadow-lg shadow-blue-500/20 hover:shadow-blue-500/40 transition-all"
    >
      {{ t('infinitas.start') }}
    </button>
    <button
      v-else
      @click="handleStop"
      class="w-full py-3 bg-slate-700 dark:bg-slate-600 text-white font-bold rounded-xl hover:bg-slate-800 dark:hover:bg-slate-500 transition-colors"
    >
      {{ t('infinitas.stop') }}
    </button>

    <!-- 取り込み履歴 -->
    <div v-if="recentResults.length > 0">
      <p class="text-xs font-bold text-slate-600 dark:text-slate-300 mb-2">{{ t('infinitas.recent') }}</p>
      <div class="space-y-1.5 max-h-48 overflow-y-auto">
        <div
          v-for="r in recentResults"
          :key="r.capturedAt.toISOString()"
          class="flex items-center gap-3 px-3 py-2 rounded-lg bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 text-xs"
        >
          <div class="flex-1 min-w-0">
            <p class="font-bold text-slate-800 dark:text-slate-100 truncate">
              {{ r.songEntry?.title || r.ocrTitle || '?' }}
            </p>
            <p class="text-slate-500 dark:text-slate-400 tabular-nums">
              {{ r.difficulty || '?' }} / {{ r.djLevel || '?' }} / {{ r.score ?? '?' }} / {{ r.clearType || '?' }}
            </p>
          </div>
          <span class="text-[10px] text-slate-400 tabular-nums shrink-0">
            {{ r.capturedAt.toLocaleTimeString() }}
          </span>
        </div>
      </div>
    </div>

    <!-- 確認モーダル -->
    <InfinitasResultModal
      v-if="pendingResult"
      :result="pendingResult"
      :existing-scores="existingScores"
      @confirm="handleConfirm"
      @skip="handleSkip"
    />
  </div>
</template>
