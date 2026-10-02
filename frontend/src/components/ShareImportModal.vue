<script setup lang="ts">
/**
 * 【コンポーネントの役割】 共有 / 選択した画像を「曲名検索 → 該当譜面に保存」するモーダル。
 *
 * 主な入口:
 *  - PWA Share Target: OS の「共有」から beat-seeker に画像を送ると App.vue が本モーダルを開く
 *  - 手動: モーダル内でファイル選択 / クリップボード貼り付け（iOS など Share Target 非対応環境向け）
 *
 * フロー: 画像プレビュー → 曲タイトルで検索（strategy_card_songs.json）→ 譜面（曲名×難易度）を
 *         クリック → 圧縮して R2 へ保存（既存の useResultImages / imageCompress を利用）。
 */
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import strategySongs from '../data/strategy_card_songs.json';
import { compressImage } from '../utils/imageCompress';
import { useResultImages } from '../composables/useResultImages';
import { useAuth } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';
import { mdiClose, mdiImagePlus, mdiMagnify } from '@mdi/js';

type Song = { id: number; version: string; title: string; diff: 'A' | 'L'; level: number };
type Genre = 'NOTES' | 'PEAK' | 'CHORD' | 'CHARGE' | 'SCRATCH' | 'SOF-LAN' | 'INSANE';

const props = defineProps<{
  open: boolean;
  /** 共有/呼び出し元から渡される画像（無い場合はモーダル内で選択・貼り付け）。 */
  imageBlob?: Blob | null;
}>();
const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'login'): void;
  (e: 'uploaded'): void;
}>();

const { upload } = useResultImages();
const { isLoggedIn } = useAuth();
const { t } = useI18n();

const ALL_GENRES: Genre[] = ['NOTES', 'PEAK', 'CHORD', 'CHARGE', 'SCRATCH', 'SOF-LAN', 'INSANE'];
const ALL_LEVELS = [8, 9, 10, 11, 12] as const;
const songsRoot = strategySongs as Record<Genre, Record<string, Song[]>>;

const search = ref('');
const levelFilter = ref<number | 'ALL'>('ALL');
const localBlob = ref<Blob | null>(null);
const localUrl = ref<string | null>(null);
const uploading = ref(false);
const error = ref<string | null>(null);
const savedChart = ref<string | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);

/** プレビュー画像を差し替える（前の objectURL は破棄）。 */
function setImage(blob: Blob | null) {
  if (localUrl.value) URL.revokeObjectURL(localUrl.value);
  localBlob.value = blob;
  localUrl.value = blob ? URL.createObjectURL(blob) : null;
}

watch(() => props.imageBlob, (b) => { if (b) setImage(b); }, { immediate: true });
watch(() => props.open, (v) => {
  if (v) {
    search.value = '';
    error.value = null;
    savedChart.value = null;
    if (props.imageBlob) setImage(props.imageBlob);
  }
});

const MAX_RESULTS = 200;
type Hit = { id: number; title: string; version: string; diff: 'A' | 'L'; level: number };
const hits = computed<{ list: Hit[]; overflow: boolean }>(() => {
  const q = search.value.trim().toLowerCase();
  const levels = levelFilter.value === 'ALL' ? ALL_LEVELS.map(String) : [String(levelFilter.value)];
  const out: Hit[] = [];
  for (const g of ALL_GENRES) {
    for (const lv of levels) {
      const arr = songsRoot[g]?.[lv] ?? [];
      for (const s of arr) {
        if (q && !s.title.toLowerCase().includes(q)) continue;
        out.push({ id: s.id, title: s.title, version: s.version, diff: s.diff, level: s.level });
        if (out.length > MAX_RESULTS) return { list: out.slice(0, MAX_RESULTS), overflow: true };
      }
    }
  }
  return { list: out, overflow: false };
});

function triggerPick() {
  fileInput.value?.click();
}
function onFileSelected(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0];
  if (f) setImage(f);
  (e.target as HTMLInputElement).value = '';
}
function onPaste(e: ClipboardEvent) {
  if (!props.open) return;
  const items = e.clipboardData?.items;
  if (!items) return;
  for (const it of Array.from(items)) {
    if (it.type.startsWith('image/')) {
      const f = it.getAsFile();
      if (f) { setImage(f); break; }
    }
  }
}

/** 譜面を選んで保存する。 */
async function pickChart(h: Hit) {
  if (!isLoggedIn.value) { emit('login'); return; }
  if (!localBlob.value || uploading.value) return;
  uploading.value = true;
  error.value = null;
  try {
    const compressed = await compressImage(localBlob.value);
    const difficultyName = h.diff === 'L' ? 'LEGGENDARIA' : 'ANOTHER';
    await upload({
      title: h.title,
      difficultyName,
      difficultyLevel: h.level,
      blob: compressed.blob,
      type: compressed.type,
      width: compressed.width,
      height: compressed.height,
    });
    savedChart.value = `${h.title} [${difficultyName}]`;
    emit('uploaded');
  } catch (e: any) {
    error.value = e?.message ?? '保存に失敗しました';
  } finally {
    uploading.value = false;
  }
}

/** 保存後に「別の画像を登録」: 画像と完了状態をリセット。 */
function reset() {
  setImage(null);
  savedChart.value = null;
  error.value = null;
  search.value = '';
}

function close() {
  emit('close');
}

onMounted(() => window.addEventListener('paste', onPaste));
onBeforeUnmount(() => {
  window.removeEventListener('paste', onPaste);
  if (localUrl.value) URL.revokeObjectURL(localUrl.value);
});
</script>

<template>
  <v-dialog
    :model-value="open"
    max-width="672"
    @update:model-value="(v: boolean) => { if (!v) close() }"
  >
    <v-card class="w-full overflow-hidden flex flex-col max-h-[92vh]">
      <!-- ヘッダ -->
      <v-card-title class="flex items-center justify-between">
        <span>{{ t('shareImport.title') }}</span>
        <v-btn
          icon
          variant="text"
          size="small"
          aria-label="close"
          @click="close"
        ><v-icon :icon="mdiClose" /></v-btn>
      </v-card-title>
      <v-divider />

      <!-- 保存完了 -->
      <div v-if="savedChart" class="p-6 flex flex-col items-center text-center gap-4">
        <div class="w-12 h-12 rounded-full bg-emerald-100 dark:bg-emerald-900/40 flex items-center justify-center text-emerald-600 dark:text-emerald-400 text-2xl">✓</div>
        <div>
          <p class="font-bold text-slate-800 dark:text-slate-100">{{ t('shareImport.saved') }}</p>
          <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">{{ savedChart }}</p>
        </div>
        <div class="flex gap-2 w-full max-w-xs">
          <v-btn variant="outlined" class="flex-1" @click="reset">
            {{ t('shareImport.another') }}
          </v-btn>
          <v-btn color="primary" class="flex-1" @click="close">
            {{ t('shareImport.close') }}
          </v-btn>
        </div>
      </div>

      <!-- 本体 -->
      <template v-else>
        <!-- プレビュー / 画像選択 -->
        <div class="p-4 border-b border-slate-100 dark:border-slate-700/60 bg-slate-50 dark:bg-slate-900/40">
          <div v-if="localUrl" class="flex items-center gap-3">
            <img :src="localUrl" alt="" class="h-20 w-20 object-cover rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-100 dark:bg-slate-900" />
            <div class="min-w-0 flex-1">
              <p class="text-xs text-slate-500 dark:text-slate-400">{{ t('shareImport.pickChart') }}</p>
            </div>
            <v-btn variant="text" size="small" color="primary" class="shrink-0" @click="triggerPick">
              {{ t('shareImport.changeImage') }}
            </v-btn>
          </div>
          <v-btn
            v-else
            variant="tonal"
            color="primary"
            block
            size="x-large"
            :prepend-icon="mdiImagePlus"
            @click="triggerPick"
          >
            {{ t('shareImport.pickImage') }}
          </v-btn>
          <input ref="fileInput" type="file" accept="image/*" class="hidden" @change="onFileSelected" />
        </div>

        <!-- ログイン必須の案内 -->
        <v-alert v-if="!isLoggedIn" type="warning" :icon="false" class="m-4">
          <div class="flex items-center justify-between gap-3">
            <p class="text-xs sm:text-sm">{{ t('shareImport.loginRequired') }}</p>
            <v-btn size="small" color="warning" class="shrink-0" @click="emit('login')">
              {{ t('shareImport.login') }}
            </v-btn>
          </div>
        </v-alert>

        <!-- 検索 + Lv フィルタ -->
        <div class="px-4 py-3 border-b border-slate-100 dark:border-slate-700/60 space-y-2">
          <v-text-field
            v-model="search"
            type="text"
            :placeholder="t('shareImport.searchPlaceholder')"
            :prepend-inner-icon="mdiMagnify"
            hide-details
          />
          <v-chip-group v-model="levelFilter" mandatory selected-class="text-primary" column>
            <v-chip value="ALL" size="small" filter>ALL</v-chip>
            <v-chip
              v-for="lv in ALL_LEVELS"
              :key="lv"
              :value="lv"
              size="small"
              filter
            >Lv {{ lv }}</v-chip>
          </v-chip-group>
        </div>

        <!-- エラー -->
        <v-alert v-if="error" type="error" density="compact" class="mx-4 mt-3">
          {{ error }}
        </v-alert>

        <!-- 曲リスト -->
        <div class="flex-1 overflow-y-auto relative">
          <p v-if="hits.list.length === 0" class="px-5 py-10 text-center text-sm text-slate-400 italic">
            {{ t('shareImport.empty') }}
          </p>
          <v-list v-else density="compact" lines="two">
            <v-list-item
              v-for="h in hits.list"
              :key="`${h.id}-${h.diff}`"
              :disabled="!localBlob || uploading"
              :title="h.title"
              :subtitle="`${h.version} · ${h.diff === 'L' ? 'LEGGENDARIA' : 'ANOTHER'} · Lv ${h.level}`"
              @click="pickChart(h)"
            />
          </v-list>
          <p v-if="hits.overflow" class="px-5 py-3 text-center text-[11px] text-slate-400 italic">
            {{ t('shareImport.empty') }}
          </p>

          <!-- アップロード中オーバーレイ -->
          <div v-if="uploading" class="absolute inset-0 bg-white/70 dark:bg-slate-800/70 flex items-center justify-center gap-2">
            <v-progress-circular indeterminate size="20" width="2" />
            <span class="text-sm font-bold text-slate-600 dark:text-slate-300">{{ t('shareImport.uploading') }}</span>
          </div>
        </div>
      </template>
    </v-card>
  </v-dialog>
</template>
