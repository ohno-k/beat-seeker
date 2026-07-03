<script setup lang="ts">
/**
 * 【コンポーネントの役割】 楽曲詳細（スコア詳細モーダル）のリザルト画像セクション。
 *
 * - 指定譜面 (曲名 × 難易度名) に紐づくリザルト画像のサムネイル一覧を表示
 * - 「リザルトを追加」で端末のファイルを選択 → ブラウザ内で圧縮 → アップロード（複数枚可）
 * - サムネイルをタップするとライトボックスで拡大表示（前後送り対応）
 * - 各画像は本人のみ削除可能
 *
 * 画像本体は Cloudflare R2 に保存され、ここでは署名付き URL を `<img>` で表示するだけ。
 * 未ログイン時はログインを促すヒントのみ表示する。
 */
import { ref, computed, watch, onBeforeUnmount } from 'vue';
import { useResultImages, type ResultImageDto } from '../composables/useResultImages';
import { compressImage } from '../utils/imageCompress';
import { useAuth } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';

const props = defineProps<{
  title: string;
  difficultyName: string;
  difficultyLevel: number | null;
}>();

const { list, upload, remove } = useResultImages();
const { isLoggedIn } = useAuth();
const { t } = useI18n();

const images = ref<ResultImageDto[]>([]);
const loading = ref(false);
const uploading = ref(false);
const error = ref<string | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);

/** ライトボックスで表示中の画像インデックス（null なら閉じている）。 */
const lightboxIndex = ref<number | null>(null);
const lightboxOpen = computed(() => lightboxIndex.value !== null);
const lightboxImage = computed(() =>
  lightboxIndex.value !== null ? images.value[lightboxIndex.value] ?? null : null
);

/** 指定譜面の画像一覧を読み込む。 */
async function load(): Promise<void> {
  if (!isLoggedIn.value || !props.title || !props.difficultyName) {
    images.value = [];
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    images.value = await list(props.title, props.difficultyName);
  } catch (e: any) {
    error.value = e?.message ?? '読み込みに失敗しました';
  } finally {
    loading.value = false;
  }
}

// 対象譜面・ログイン状態が変わるたびに再読み込み。
watch(
  () => [props.title, props.difficultyName, isLoggedIn.value] as const,
  load,
  { immediate: true }
);

function triggerPick(): void {
  fileInput.value?.click();
}

/** ファイル選択 → 圧縮 → 順次アップロード。 */
async function onFilesSelected(e: Event): Promise<void> {
  const input = e.target as HTMLInputElement;
  const files = input.files;
  if (!files || files.length === 0) return;

  uploading.value = true;
  error.value = null;
  try {
    for (const file of Array.from(files)) {
      if (!file.type.startsWith('image/')) continue;
      const compressed = await compressImage(file);
      const dto = await upload({
        title: props.title,
        difficultyName: props.difficultyName,
        difficultyLevel: props.difficultyLevel,
        blob: compressed.blob,
        type: compressed.type,
        width: compressed.width,
        height: compressed.height,
      });
      images.value.push(dto);
    }
  } catch (e: any) {
    error.value = e?.message ?? 'アップロードに失敗しました';
  } finally {
    uploading.value = false;
    // 同じファイルを連続選択できるよう input をリセット。
    input.value = '';
  }
}

/** 画像を削除する。 */
async function onDelete(img: ResultImageDto): Promise<void> {
  if (!window.confirm(t('resultImage.confirmDelete'))) return;
  try {
    await remove(img.id);
    images.value = images.value.filter((i) => i.id !== img.id);
    // ライトボックス表示中の整合性を保つ。
    if (lightboxIndex.value !== null) {
      if (images.value.length === 0) lightboxIndex.value = null;
      else if (lightboxIndex.value >= images.value.length) lightboxIndex.value = images.value.length - 1;
    }
  } catch (e: any) {
    error.value = e?.message ?? '削除に失敗しました';
  }
}

function openLightbox(i: number): void {
  lightboxIndex.value = i;
}
function closeLightbox(): void {
  lightboxIndex.value = null;
}
function showPrev(): void {
  if (lightboxIndex.value === null || images.value.length === 0) return;
  lightboxIndex.value = (lightboxIndex.value - 1 + images.value.length) % images.value.length;
}
function showNext(): void {
  if (lightboxIndex.value === null || images.value.length === 0) return;
  lightboxIndex.value = (lightboxIndex.value + 1) % images.value.length;
}

// ライトボックス操作のキーボードショートカット（Esc / ←→）。
function onKeydown(e: KeyboardEvent): void {
  if (!lightboxOpen.value) return;
  if (e.key === 'Escape') closeLightbox();
  else if (e.key === 'ArrowLeft') showPrev();
  else if (e.key === 'ArrowRight') showNext();
}
watch(lightboxOpen, (open) => {
  if (open) window.addEventListener('keydown', onKeydown);
  else window.removeEventListener('keydown', onKeydown);
});
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown));
</script>

<template>
  <div
    class="border border-slate-200 dark:border-slate-700 rounded-md overflow-hidden bg-white dark:bg-slate-800 transition-colors duration-200 mt-6"
  >
    <!-- ヘッダ: タイトル + 枚数 + 追加ボタン -->
    <div
      class="bg-slate-100 dark:bg-slate-900/50 px-4 sm:px-6 py-2 sm:py-4 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between gap-3 transition-colors duration-200"
    >
      <p class="text-xs sm:text-sm font-bold text-slate-600 dark:text-slate-400 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
          <path fill-rule="evenodd" d="M4 3a2 2 0 00-2 2v10a2 2 0 002 2h12a2 2 0 002-2V5a2 2 0 00-2-2H4zm12 12H4l4-8 3 6 2-4 3 6z" clip-rule="evenodd" />
        </svg>
        {{ t('resultImage.section') }}
        <span v-if="images.length > 0" class="text-slate-400 dark:text-slate-500 font-bold">{{ images.length }}</span>
      </p>
      <button
        v-if="isLoggedIn"
        type="button"
        class="rounded-lg px-3 py-1.5 text-xs font-bold bg-blue-600 hover:bg-blue-700 text-white disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center gap-1.5 shrink-0"
        :disabled="uploading"
        @click="triggerPick"
      >
        <svg v-if="!uploading" xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" viewBox="0 0 20 20" fill="currentColor">
          <path fill-rule="evenodd" d="M10 3a1 1 0 011 1v5h5a1 1 0 110 2h-5v5a1 1 0 11-2 0v-5H4a1 1 0 110-2h5V4a1 1 0 011-1z" clip-rule="evenodd" />
        </svg>
        <svg v-else class="h-3.5 w-3.5 animate-spin" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
          <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
          <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
        </svg>
        {{ uploading ? t('resultImage.uploading') : t('resultImage.add') }}
      </button>
    </div>

    <div class="p-4 sm:p-6">
      <!-- 未ログイン: ヒントのみ -->
      <p v-if="!isLoggedIn" class="text-xs sm:text-sm text-slate-500 dark:text-slate-400 text-center py-4">
        {{ t('resultImage.loginRequired') }}
      </p>

      <template v-else>
        <!-- エラー -->
        <div
          v-if="error"
          class="mb-3 p-3 rounded-md border border-red-300 dark:border-red-700 bg-red-50 dark:bg-red-950/40 text-xs sm:text-sm text-red-700 dark:text-red-300"
        >
          {{ error }}
        </div>

        <!-- 読み込み中（初回かつ未取得時） -->
        <p v-if="loading && images.length === 0" class="text-xs sm:text-sm text-slate-400 dark:text-slate-500 text-center py-4">
          …
        </p>

        <!-- 空 -->
        <p
          v-else-if="images.length === 0"
          class="text-xs sm:text-sm text-slate-400 dark:text-slate-500 text-center py-4"
        >
          {{ t('resultImage.empty') }}
        </p>

        <!-- サムネイルグリッド -->
        <div v-else class="grid grid-cols-3 sm:grid-cols-4 gap-2 sm:gap-3">
          <div
            v-for="(img, i) in images"
            :key="img.id"
            class="relative group aspect-square rounded-lg overflow-hidden border border-slate-200 dark:border-slate-700 bg-slate-100 dark:bg-slate-900"
          >
            <img
              :src="img.url"
              alt="result"
              loading="lazy"
              class="w-full h-full object-cover cursor-zoom-in transition-opacity duration-200 hover:opacity-90"
              @click="openLightbox(i)"
            />
            <button
              type="button"
              class="absolute top-1 right-1 w-6 h-6 rounded-full bg-black/55 hover:bg-red-600 text-white flex items-center justify-center text-sm leading-none transition-colors"
              :aria-label="t('resultImage.delete')"
              @click.stop="onDelete(img)"
            >
              ×
            </button>
          </div>
        </div>
      </template>
    </div>

    <!-- ファイル選択（非表示） -->
    <input
      ref="fileInput"
      type="file"
      accept="image/*"
      multiple
      class="hidden"
      @change="onFilesSelected"
    />
  </div>

  <!-- ライトボックス（拡大表示） -->
  <Teleport to="body">
    <div
      v-if="lightboxOpen && lightboxImage"
      class="fixed inset-0 z-[120] flex items-center justify-center bg-black/90 p-4 animate-fade-in"
      @click.self="closeLightbox"
    >
      <!-- 閉じる -->
      <button
        type="button"
        class="absolute top-4 right-4 w-10 h-10 rounded-full bg-white/15 hover:bg-white/30 text-white flex items-center justify-center text-2xl leading-none transition-colors"
        aria-label="close"
        @click="closeLightbox"
      >
        ×
      </button>

      <!-- 前へ -->
      <button
        v-if="images.length > 1"
        type="button"
        class="absolute left-3 sm:left-6 w-10 h-10 rounded-full bg-white/15 hover:bg-white/30 text-white flex items-center justify-center transition-colors"
        aria-label="prev"
        @click.stop="showPrev"
      >
        <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" viewBox="0 0 20 20" fill="currentColor">
          <path fill-rule="evenodd" d="M12.707 5.293a1 1 0 010 1.414L9.414 10l3.293 3.293a1 1 0 01-1.414 1.414l-4-4a1 1 0 010-1.414l4-4a1 1 0 011.414 0z" clip-rule="evenodd" />
        </svg>
      </button>

      <img
        :src="lightboxImage.url"
        alt="result"
        class="max-w-full max-h-[90vh] object-contain rounded-lg shadow-xl"
        @click.stop
      />

      <!-- 次へ -->
      <button
        v-if="images.length > 1"
        type="button"
        class="absolute right-3 sm:right-6 w-10 h-10 rounded-full bg-white/15 hover:bg-white/30 text-white flex items-center justify-center transition-colors"
        aria-label="next"
        @click.stop="showNext"
      >
        <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" viewBox="0 0 20 20" fill="currentColor">
          <path fill-rule="evenodd" d="M7.293 14.707a1 1 0 010-1.414L10.586 10 7.293 6.707a1 1 0 011.414-1.414l4 4a1 1 0 010 1.414l-4 4a1 1 0 01-1.414 0z" clip-rule="evenodd" />
        </svg>
      </button>

      <!-- カウンタ -->
      <div
        v-if="images.length > 1"
        class="absolute bottom-4 left-1/2 -translate-x-1/2 px-3 py-1 rounded bg-white/15 text-white text-xs font-bold tabular-nums"
      >
        {{ (lightboxIndex ?? 0) + 1 }} / {{ images.length }}
      </div>
    </div>
  </Teleport>
</template>
