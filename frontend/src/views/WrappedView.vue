<script setup lang="ts">
/**
 * 【Viewの役割】 月末振り返り（Spotify Wrapped 風）の全画面表示ビュー。
 *
 * URL パターン:
 *  - `/wrapped/:year/:month`                 … 自分の振り返り（要ログイン）
 *  - `/user/:userId/wrapped/:year/:month`    … 他ユーザーの公開振り返り（OGP 展開対象）
 *
 * UI:
 *  - 縦スクロール式の全画面カード集（CSS scroll-snap）
 *  - 末尾にシェアボタン（X 投稿フォーム + 1200×675 画像ダウンロード/Web Share API）
 *  - 公開ビュー時のみ OGP/Twitter Card メタタグを動的に書き換える
 *
 * シェア画像:
 *  - 画面外（translateX -200%）に 1200×675 の DOM を常時マウントしておき、
 *    シェアボタン押下時に html2canvas でキャプチャ→Blob 化する。
 *  - Web Share API（files 対応）が使える場合はネイティブ共有ダイアログ、
 *    無ければ画像ダウンロード＋X 投稿フォームを別タブで開くフォールバック。
 */
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import html2canvas from 'html2canvas';
import { useAuth } from '../composables/useAuth';
import { useMonthlyWrapped } from '../composables/useMonthlyWrapped';

const route = useRoute();
const router = useRouter();
const { isLoggedIn } = useAuth();
const { data, isLoading, error, fetchMyWrapped, fetchPublicWrapped } = useMonthlyWrapped();

/** URL パラメータから年月を Number に正規化。不正値は NaN になる。 */
const year = computed(() => Number(route.params.year));
const month = computed(() => Number(route.params.month));
/** /user/:userId/wrapped 配下なら数値、自分用なら null。 */
const targetUserId = computed(() => {
  const u = route.params.userId;
  return u ? Number(u) : null;
});
/** 公開ビュー（OGP 展開対象）かどうか。 */
const isPublicView = computed(() => targetUserId.value !== null);

/** API リクエスト。route 変化時にも再実行。 */
async function load() {
  if (Number.isNaN(year.value) || Number.isNaN(month.value)) {
    return;
  }
  if (isPublicView.value) {
    await fetchPublicWrapped(targetUserId.value!, year.value, month.value);
  } else {
    await fetchMyWrapped(year.value, month.value);
  }
}

onMounted(load);
watch([year, month, targetUserId], load);

// ─── カード総数（RATE-TIER / KENBAN-SARA-TIER の表示設定を反映） ─────
const totalCards = computed(() => {
  const d = data.value;
  if (!d) return 0;
  // 必ず表示するカード: オープニング・プレー量・AAA・ランプ・BEAT-PT・BEAT-PT TOP10・先月比・クロージング = 8
  let base = 8;
  // RATE-TIER 表示時のみ RATE-PT 変動と RATE-PT TOP10 を加算
  if (d.showRateTier) base += 2;
  // KENBAN/SARA はサポーター × 表示設定 ON のとき各 1 枚
  if (d.isSupporter && d.showKenbanSaraTier) base += 2;
  return base;
});

// ─── スクロール位置とページインジケーター ─────────────────────────
const scrollContainer = ref<HTMLElement | null>(null);
const currentIndex = ref(0);

function onScroll(e: Event) {
  const el = e.currentTarget as HTMLElement;
  const h = el.clientHeight;
  if (h === 0) return;
  currentIndex.value = Math.round(el.scrollTop / h);
}

function scrollToCard(index: number) {
  const el = scrollContainer.value;
  if (!el) return;
  el.scrollTo({ top: index * el.clientHeight, behavior: 'smooth' });
}

// ─── シェア画像生成 ─────────────────────────────────────────────
const shareImageRef = ref<HTMLElement | null>(null);
const isGeneratingShare = ref(false);

async function generateShareBlob(): Promise<Blob | null> {
  const target = shareImageRef.value;
  if (!target) return null;
  isGeneratingShare.value = true;
  try {
    const canvas = await html2canvas(target, {
      width: 1200,
      height: 675,
      scale: 1,
      backgroundColor: null,
      useCORS: true,
    });
    return await new Promise<Blob | null>((resolve) =>
      canvas.toBlob((b) => resolve(b), 'image/png', 0.95),
    );
  } finally {
    isGeneratingShare.value = false;
  }
}

/** シェア用の公開 URL（OGP が展開される /user/:userId/wrapped/... 形式）。 */
const sharePublicUrl = computed(() => {
  if (!data.value) return '';
  const uid = data.value.userId;
  return `${window.location.origin}/user/${uid}/wrapped/${year.value}/${month.value}`;
});

/** Twitter 投稿用テンプレート。 */
const shareText = computed(() => {
  if (!data.value) return '';
  const d = data.value;
  return `${d.displayName} の ${d.displayMonth} の記録\n` +
    `更新譜面 ${d.uniqueUpdatedSongCount} 曲 / 新規 AAA +${d.djLevelIncrease?.aaa ?? 0} / BEAT-PT ${d.beatPtIncrease >= 0 ? '+' : ''}${d.beatPtIncrease.toFixed(2)}\n` +
    `#beatseeker #beatmaniaIIDX`;
});

async function handleShare() {
  const blob = await generateShareBlob();
  if (!blob) return;
  const fileName = `beat-seeker-wrapped-${year.value}-${String(month.value).padStart(2, '0')}.png`;
  const file = new File([blob], fileName, { type: 'image/png' });

  // Web Share API（files 対応ブラウザ）
  const nav = navigator as any;
  if (nav.canShare && nav.canShare({ files: [file] })) {
    try {
      await nav.share({ files: [file], text: shareText.value, url: sharePublicUrl.value });
      return;
    } catch {
      // ユーザーがキャンセル等。フォールバックへ。
    }
  }

  // フォールバック：画像ダウンロード + X 投稿フォーム別タブ
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);

  const tweet = `https://x.com/intent/tweet?text=${encodeURIComponent(shareText.value)}&url=${encodeURIComponent(sharePublicUrl.value)}`;
  window.open(tweet, '_blank', 'noopener');
}

// ─── OGP / Twitter Card メタタグ（公開ビューのみ） ─────────────────
const ogElements: HTMLMetaElement[] = [];

function setMeta(key: { property?: string; name?: string }, content: string) {
  const selector = key.property
    ? `meta[property="${key.property}"]`
    : `meta[name="${key.name}"]`;
  let el = document.head.querySelector<HTMLMetaElement>(selector);
  let created = false;
  if (!el) {
    el = document.createElement('meta');
    if (key.property) el.setAttribute('property', key.property);
    if (key.name) el.setAttribute('name', key.name);
    document.head.appendChild(el);
    created = true;
  }
  el.setAttribute('content', content);
  if (created) ogElements.push(el);
}

const originalTitle = ref<string>('');

watch(data, (d) => {
  if (!d || !isPublicView.value) return;
  if (!originalTitle.value) originalTitle.value = document.title;

  const title = `${d.displayName}さんの ${d.displayMonth} の振り返り - beat-seeker`;
  const desc = `更新譜面 ${d.uniqueUpdatedSongCount}曲、新規 AAA +${d.djLevelIncrease?.aaa ?? 0}、BEAT-PT ${d.beatPtIncrease >= 0 ? '+' : ''}${d.beatPtIncrease.toFixed(2)}。`;

  document.title = title;
  setMeta({ property: 'og:title' }, title);
  setMeta({ property: 'og:description' }, desc);
  setMeta({ property: 'og:url' }, window.location.href);
  setMeta({ property: 'og:type' }, 'website');
  setMeta({ name: 'twitter:card' }, 'summary_large_image');
  setMeta({ name: 'twitter:title' }, title);
  setMeta({ name: 'twitter:description' }, desc);
});

onBeforeUnmount(() => {
  // 動的追加した OGP タグだけ片付け（既存タグはそのまま）
  for (const el of ogElements) el.remove();
  ogElements.length = 0;
  if (originalTitle.value) document.title = originalTitle.value;
});

// 自分用ビューで未ログインの場合はログイン誘導
const showLoginPrompt = computed(() => !isPublicView.value && !isLoggedIn.value);
</script>

<template>
  <!-- Spotify Wrapped 風の全画面オーバーレイ。App.vue のレイアウトに被せて没入体験を提供する。 -->
  <div class="fixed inset-0 z-50 bg-slate-900 overflow-hidden">
    <!-- 未ログイン（自分の振り返りは要ログイン） -->
    <div v-if="showLoginPrompt" class="flex flex-col items-center justify-center min-h-screen gap-4 text-slate-700 dark:text-slate-300">
      <p>この振り返りを見るにはログインが必要です</p>
      <button @click="router.push('/')" class="px-6 py-2 bg-blue-600 text-white rounded-xl font-bold">トップへ</button>
    </div>

    <!-- 読み込み中 -->
    <div v-else-if="isLoading" class="flex items-center justify-center min-h-screen text-slate-500">
      <p>読み込み中...</p>
    </div>

    <!-- エラー -->
    <div v-else-if="error" class="flex flex-col items-center justify-center min-h-screen gap-3 text-slate-700 dark:text-slate-300">
      <p class="text-red-500">{{ error }}</p>
      <button @click="router.back()" class="text-sm text-blue-500 underline">戻る</button>
    </div>

    <!-- 振り返り本体 -->
    <template v-else-if="data">
      <div
        ref="scrollContainer"
        @scroll.passive="onScroll"
        class="h-screen overflow-y-auto snap-y snap-mandatory scrollbar-hide bg-slate-900 text-white"
      >
        <!-- カード 1: オープニング -->
        <section class="snap-start min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-slate-900 via-blue-900 to-slate-900 p-8 text-center">
          <p class="text-xs md:text-sm tracking-[0.4em] opacity-70 mb-3">BEAT-SEEKER WRAPPED</p>
          <h1 class="text-5xl md:text-7xl font-black mb-4">{{ data.displayMonth }}</h1>
          <p class="text-lg md:text-2xl opacity-80 mb-12">{{ data.displayName }} さんの記録</p>
          <button @click="scrollToCard(1)" class="text-sm opacity-60 hover:opacity-100 transition-opacity animate-bounce">
            ↓ スクロールして見る
          </button>
        </section>

        <!-- カード 2: プレー量 -->
        <section class="snap-start min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-cyan-900 via-slate-900 to-slate-900 p-8 text-center">
          <p class="text-base md:text-lg opacity-70 mb-4">今月のアップロード</p>
          <p class="text-7xl md:text-9xl font-black mb-6">
            {{ data.uploadCount }}<span class="text-3xl md:text-4xl opacity-70 ml-2">回</span>
          </p>
          <p class="text-xl md:text-2xl opacity-80">
            <span class="font-bold text-cyan-300">{{ data.uniqueUpdatedSongCount }}</span> 譜面を更新
          </p>
          <p class="text-sm opacity-50 mt-1">（延べ {{ data.totalUpdatedCount }} 件）</p>
        </section>

        <!-- カード 3: 新規 AAA -->
        <section class="snap-start min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-amber-900 via-slate-900 to-slate-900 p-8 text-center">
          <p class="text-base md:text-lg opacity-70 mb-4">DJ LEVEL 到達</p>
          <p class="text-2xl opacity-60 mb-2">AAA に到達した譜面</p>
          <p class="text-7xl md:text-9xl font-black text-amber-300 mb-8">+{{ data.djLevelIncrease?.aaa ?? 0 }}</p>
          <div class="flex gap-6 md:gap-10 text-base md:text-lg opacity-80">
            <div class="text-center">
              <p class="opacity-60">AA</p>
              <p class="text-3xl font-bold">+{{ data.djLevelIncrease?.aa ?? 0 }}</p>
            </div>
            <div class="text-center">
              <p class="opacity-60">A</p>
              <p class="text-3xl font-bold">+{{ data.djLevelIncrease?.a ?? 0 }}</p>
            </div>
          </div>
        </section>

        <!-- カード 4: ランプ更新 -->
        <section class="snap-start min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-emerald-900 via-slate-900 to-slate-900 p-8 text-center">
          <p class="text-base md:text-lg opacity-70 mb-6">クリアランプ更新</p>
          <div class="space-y-3 md:space-y-4 text-2xl md:text-3xl">
            <div class="flex items-center gap-4 md:gap-6 justify-between min-w-[260px] md:min-w-[340px]">
              <span class="text-pink-300 font-bold">FULLCOMBO</span>
              <span class="font-black">+{{ data.clearTypeIncrease?.fullcombo ?? 0 }}</span>
            </div>
            <div class="flex items-center gap-4 md:gap-6 justify-between">
              <span class="text-yellow-300 font-bold">EX HARD</span>
              <span class="font-black">+{{ data.clearTypeIncrease?.exhard ?? 0 }}</span>
            </div>
            <div class="flex items-center gap-4 md:gap-6 justify-between">
              <span class="text-red-300 font-bold">HARD</span>
              <span class="font-black">+{{ data.clearTypeIncrease?.hard ?? 0 }}</span>
            </div>
            <div class="flex items-center gap-4 md:gap-6 justify-between">
              <span class="text-cyan-300 font-bold">CLEAR</span>
              <span class="font-black">+{{ data.clearTypeIncrease?.clear ?? 0 }}</span>
            </div>
            <div class="flex items-center gap-4 md:gap-6 justify-between">
              <span class="text-green-300 font-bold">EASY</span>
              <span class="font-black">+{{ data.clearTypeIncrease?.easy ?? 0 }}</span>
            </div>
          </div>
        </section>

        <!-- カード 5: BEAT-PT -->
        <section class="snap-start min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-violet-900 via-slate-900 to-slate-900 p-8 text-center">
          <p class="text-base md:text-lg opacity-70 mb-4">BEAT-PT</p>
          <div class="flex items-baseline gap-3 mb-4 flex-wrap justify-center">
            <span class="text-2xl md:text-3xl opacity-60">{{ data.startBeatPt.toFixed(2) }}</span>
            <span class="text-xl opacity-60">→</span>
            <span class="text-4xl md:text-6xl font-black">{{ data.endBeatPt.toFixed(2) }}</span>
          </div>
          <p :class="['text-4xl md:text-5xl font-black', data.beatPtIncrease >= 0 ? 'text-emerald-300' : 'text-red-300']">
            {{ data.beatPtIncrease >= 0 ? '+' : '' }}{{ data.beatPtIncrease.toFixed(2) }}
          </p>
          <p v-if="data.newBeatPtBest" class="mt-6 text-amber-300 font-bold text-lg animate-pulse">★ 自己ベスト更新 ★</p>
        </section>

        <!-- カード 6: BEAT-PT を支える譜面 TOP10（現在の BEAT-PT 降順 × 月内更新フィルタ） -->
        <section class="snap-start min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-violet-900 via-slate-900 to-slate-900 p-6 md:p-8">
          <p class="text-base md:text-lg opacity-70 mb-1 text-center">BEAT-PT を支える譜面</p>
          <p class="text-xs opacity-50 mb-6 text-center">TOP 10 (今月更新分)</p>
          <div v-if="data.topBeatPtSongs.length > 0" class="w-full max-w-2xl space-y-1.5">
            <div
              v-for="(s, i) in data.topBeatPtSongs"
              :key="`bp-${s.title}-${s.difficulty}`"
              class="flex items-center gap-3 bg-white/10 rounded-xl px-3 py-2"
            >
              <span class="text-lg md:text-xl font-black opacity-60 w-5 md:w-6 text-center flex-shrink-0">{{ i + 1 }}</span>
              <div class="flex-1 min-w-0">
                <p class="font-bold truncate text-xs md:text-sm">{{ s.title }}</p>
                <p class="text-[10px] md:text-xs opacity-60 truncate">
                  {{ s.difficulty }} / {{ s.oldScore ?? 0 }} → {{ s.newScore ?? 0 }}
                </p>
              </div>
              <div class="text-right whitespace-nowrap flex-shrink-0">
                <p class="text-violet-300 font-bold text-base md:text-lg">{{ s.newBeatPt.toFixed(1) }}</p>
                <p class="text-[10px] md:text-xs">
                  <span v-if="s.beatPtIncrease > 0" class="text-emerald-300">+{{ s.beatPtIncrease.toFixed(1) }}</span>
                  <span v-else class="opacity-50">±0</span>
                </p>
              </div>
            </div>
          </div>
          <p v-else class="opacity-50 text-center">BEAT-PT に貢献した譜面はありませんでした</p>
        </section>

        <!-- カード 7: RATE-PT (RATE-TIER 表示設定 ON のユーザーのみ) -->
        <section
          v-if="data.showRateTier"
          class="snap-start min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-rose-900 via-slate-900 to-slate-900 p-8 text-center"
        >
          <p class="text-base md:text-lg opacity-70 mb-4">RATE-PT</p>
          <div class="flex items-baseline gap-3 mb-4 flex-wrap justify-center">
            <span class="text-2xl md:text-3xl opacity-60">{{ data.startRatePt.toFixed(2) }}</span>
            <span class="text-xl opacity-60">→</span>
            <span class="text-4xl md:text-6xl font-black">{{ data.endRatePt.toFixed(2) }}</span>
          </div>
          <p :class="['text-4xl md:text-5xl font-black', data.ratePtIncrease >= 0 ? 'text-emerald-300' : 'text-red-300']">
            {{ data.ratePtIncrease >= 0 ? '+' : '' }}{{ data.ratePtIncrease.toFixed(2) }}
          </p>
          <p v-if="data.newRatePtBest" class="mt-6 text-amber-300 font-bold text-lg animate-pulse">★ 自己ベスト更新 ★</p>
        </section>

        <!-- カード 8: RATE-PT 増加 TOP10（譜面別の内訳、RATE-TIER 表示設定 ON のユーザーのみ） -->
        <section
          v-if="data.showRateTier"
          class="snap-start min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-rose-900 via-slate-900 to-slate-900 p-6 md:p-8"
        >
          <p class="text-base md:text-lg opacity-70 mb-1 text-center">RATE-PT を伸ばした譜面</p>
          <p class="text-xs opacity-50 mb-6 text-center">TOP 10 (ANOTHER / LEGGENDARIA)</p>
          <div v-if="data.topRatePtSongs.length > 0" class="w-full max-w-2xl space-y-1.5">
            <div
              v-for="(s, i) in data.topRatePtSongs"
              :key="`rp-${s.title}-${s.difficulty}`"
              class="flex items-center gap-3 bg-white/10 rounded-xl px-3 py-2"
            >
              <span class="text-lg md:text-xl font-black opacity-60 w-5 md:w-6 text-center flex-shrink-0">{{ i + 1 }}</span>
              <div class="flex-1 min-w-0">
                <p class="font-bold truncate text-xs md:text-sm">{{ s.title }}</p>
                <p class="text-[10px] md:text-xs opacity-60 truncate">
                  {{ s.difficulty }} / {{ s.oldScore ?? 0 }} → {{ s.newScore ?? 0 }}
                </p>
              </div>
              <div class="text-right whitespace-nowrap flex-shrink-0">
                <p class="text-rose-300 font-bold text-sm md:text-base">+{{ s.ratePtIncrease.toFixed(1) }}</p>
                <p class="text-[9px] md:text-[10px] opacity-50">{{ s.oldRatePt.toFixed(1) }} → {{ s.newRatePt.toFixed(1) }}</p>
              </div>
            </div>
          </div>
          <p v-else class="opacity-50 text-center">RATE-PT に貢献した譜面はありませんでした</p>
        </section>

        <!-- カード 8: 先月比 -->
        <section class="snap-start min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-teal-900 via-slate-900 to-slate-900 p-8 text-center">
          <p class="text-base md:text-lg opacity-70 mb-6">先月との比較 (BEAT-PT 増分)</p>
          <div class="space-y-6">
            <div>
              <p class="text-xs md:text-sm opacity-60 mb-1">先月の伸び</p>
              <p class="text-2xl md:text-3xl font-bold opacity-70">
                {{ data.prevMonthBeatPtIncrease >= 0 ? '+' : '' }}{{ data.prevMonthBeatPtIncrease.toFixed(2) }}
              </p>
            </div>
            <div>
              <p class="text-xs md:text-sm opacity-60 mb-1">今月の伸び</p>
              <p :class="['text-5xl md:text-6xl font-black', data.beatPtIncrease >= data.prevMonthBeatPtIncrease ? 'text-emerald-300' : 'text-amber-300']">
                {{ data.beatPtIncrease >= 0 ? '+' : '' }}{{ data.beatPtIncrease.toFixed(2) }}
              </p>
            </div>
            <p v-if="data.beatPtIncrease > data.prevMonthBeatPtIncrease" class="text-emerald-300 font-bold">
              先月を超えました
            </p>
            <p v-else-if="data.beatPtIncrease < data.prevMonthBeatPtIncrease" class="opacity-70">
              先月より控えめでした
            </p>
          </div>
        </section>

        <!-- カード サポーター: KENBAN-PT -->
        <section
          v-if="data.isSupporter && data.showKenbanSaraTier"
          class="snap-start min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-indigo-900 via-slate-900 to-slate-900 p-8 text-center"
        >
          <p class="text-base md:text-lg opacity-70 mb-4">KENBAN-PT</p>
          <div class="flex items-baseline gap-3 mb-4 flex-wrap justify-center">
            <span class="text-2xl md:text-3xl opacity-60">{{ data.startKenbanPt.toFixed(2) }}</span>
            <span class="text-xl opacity-60">→</span>
            <span class="text-4xl md:text-6xl font-black">{{ data.endKenbanPt.toFixed(2) }}</span>
          </div>
          <p :class="['text-4xl md:text-5xl font-black', data.kenbanPtIncrease >= 0 ? 'text-emerald-300' : 'text-red-300']">
            {{ data.kenbanPtIncrease >= 0 ? '+' : '' }}{{ data.kenbanPtIncrease.toFixed(2) }}
          </p>
        </section>

        <!-- カード サポーター: SARA-PT -->
        <section
          v-if="data.isSupporter && data.showKenbanSaraTier"
          class="snap-start min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-purple-900 via-slate-900 to-slate-900 p-8 text-center"
        >
          <p class="text-base md:text-lg opacity-70 mb-4">SARA-PT</p>
          <div class="flex items-baseline gap-3 mb-4 flex-wrap justify-center">
            <span class="text-2xl md:text-3xl opacity-60">{{ data.startSaraPt.toFixed(2) }}</span>
            <span class="text-xl opacity-60">→</span>
            <span class="text-4xl md:text-6xl font-black">{{ data.endSaraPt.toFixed(2) }}</span>
          </div>
          <p :class="['text-4xl md:text-5xl font-black', data.saraPtIncrease >= 0 ? 'text-emerald-300' : 'text-red-300']">
            {{ data.saraPtIncrease >= 0 ? '+' : '' }}{{ data.saraPtIncrease.toFixed(2) }}
          </p>
        </section>

        <!-- カード 9: クロージング + シェア -->
        <section class="snap-start min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 p-8 text-center">
          <p class="text-xs md:text-sm tracking-[0.3em] opacity-70 mb-3">{{ data.displayMonth }}</p>
          <h2 class="text-3xl md:text-5xl font-black mb-3">お疲れさまでした</h2>
          <p class="opacity-70 mb-10 text-sm md:text-base">来月もよい記録を</p>
          <button
            @click="handleShare"
            :disabled="isGeneratingShare"
            class="px-8 py-4 bg-black text-white font-bold text-base md:text-lg rounded-2xl shadow-2xl hover:scale-105 active:scale-95 transition-all disabled:opacity-50"
          >
            <span v-if="isGeneratingShare">画像を生成中...</span>
            <span v-else>𝕏 でシェア</span>
          </button>
          <button
            @click="router.push('/dashboard')"
            class="mt-6 text-xs md:text-sm opacity-60 hover:opacity-100 underline"
          >
            ダッシュボードへ戻る
          </button>
        </section>
      </div>

      <!-- ページインジケーター（右側固定） -->
      <div class="fixed right-3 md:right-5 top-1/2 -translate-y-1/2 flex flex-col gap-2 pointer-events-auto z-10">
        <button
          v-for="i in totalCards"
          :key="i"
          @click="scrollToCard(i - 1)"
          :class="['w-2 h-2 rounded-full transition-all', currentIndex === (i - 1) ? 'bg-white h-6' : 'bg-white/30 hover:bg-white/60']"
          :aria-label="`セクション ${i}`"
        />
      </div>

      <!--
        オフスクリーンのシェア画像生成用 DOM (1200×675、X の summary_large_image 比率)。
        - BEAT-PT TOP5
        - RATE-PT TOP5 (data.showRateTier=true のときのみ)
        - 新規 AAA / MAX- TOP5 (非公式難易度降順)
        の 2〜3 カラム構成。html2canvas で PNG 化してシェア。
      -->
      <div
        ref="shareImageRef"
        class="fixed left-0 top-0 -translate-x-[200%] pointer-events-none"
        style="width: 1200px; height: 675px;"
        aria-hidden="true"
      >
        <div class="w-full h-full bg-gradient-to-br from-slate-900 via-blue-900 to-purple-900 text-white p-10 flex flex-col">
          <!-- ヘッダー: 月 + ユーザー名 + 軽い統計 -->
          <div class="flex items-end justify-between mb-5">
            <div>
              <p class="text-sm tracking-[0.4em] opacity-70">BEAT-SEEKER WRAPPED</p>
              <h1 class="text-5xl font-black mt-1">{{ data.displayMonth }}</h1>
            </div>
            <div class="text-right">
              <p class="text-xl opacity-90 font-bold">{{ data.displayName }}</p>
              <p class="text-xs opacity-60 mt-1">
                更新譜面 {{ data.uniqueUpdatedSongCount }} 曲 / 新規 AAA +{{ data.djLevelIncrease?.aaa ?? 0 }}
              </p>
            </div>
          </div>

          <!-- 2 or 3 カラムリスト -->
          <div :class="['grid gap-4 flex-1', data.showRateTier ? 'grid-cols-3' : 'grid-cols-2']">
            <!-- BEAT-PT TOP5 -->
            <div class="bg-white/5 rounded-2xl p-5 flex flex-col">
              <p class="text-xs tracking-wider opacity-70 mb-3 font-semibold">BEAT-PT を支える譜面</p>
              <div class="space-y-2 flex-1">
                <div
                  v-for="(s, i) in data.topBeatPtSongs.slice(0, 5)"
                  :key="`sbp-${i}`"
                  class="flex items-center gap-2"
                >
                  <span class="opacity-60 font-bold w-4 text-xs text-center">{{ i + 1 }}</span>
                  <span class="flex-1 truncate text-xs">{{ s.title }}</span>
                  <span class="text-violet-300 font-bold text-sm whitespace-nowrap">{{ s.newBeatPt.toFixed(1) }}</span>
                </div>
                <p v-if="data.topBeatPtSongs.length === 0" class="text-xs opacity-50">該当なし</p>
              </div>
            </div>

            <!-- RATE-PT TOP5 (showRateTier のみ) -->
            <div v-if="data.showRateTier" class="bg-white/5 rounded-2xl p-5 flex flex-col">
              <p class="text-xs tracking-wider opacity-70 mb-3 font-semibold">RATE-PT 増加</p>
              <div class="space-y-2 flex-1">
                <div
                  v-for="(s, i) in data.topRatePtSongs.slice(0, 5)"
                  :key="`srp-${i}`"
                  class="flex items-center gap-2"
                >
                  <span class="opacity-60 font-bold w-4 text-xs text-center">{{ i + 1 }}</span>
                  <span class="flex-1 truncate text-xs">{{ s.title }}</span>
                  <span class="text-rose-300 font-bold text-sm whitespace-nowrap">+{{ s.ratePtIncrease.toFixed(1) }}</span>
                </div>
                <p v-if="data.topRatePtSongs.length === 0" class="text-xs opacity-50">該当なし</p>
              </div>
            </div>

            <!-- 新規 AAA / MAX- TOP5 (非公式難易度降順) -->
            <div class="bg-white/5 rounded-2xl p-5 flex flex-col">
              <p class="text-xs tracking-wider opacity-70 mb-3 font-semibold">新規 AAA / MAX-</p>
              <div class="space-y-2 flex-1">
                <div
                  v-for="(a, i) in data.topAchievements.slice(0, 5)"
                  :key="`sa-${i}`"
                  class="flex items-center gap-2"
                >
                  <span
                    :class="[
                      'font-black text-[10px] text-center px-1.5 py-0.5 rounded w-12 flex-shrink-0',
                      a.achievementType === 'MAX-' ? 'bg-amber-300 text-slate-900' : 'bg-white/20'
                    ]"
                  >
                    {{ a.achievementType }}
                  </span>
                  <span class="flex-1 truncate text-xs">{{ a.title }}</span>
                  <span class="opacity-60 text-xs whitespace-nowrap">☆{{ a.informalRank ?? '?' }}</span>
                </div>
                <p v-if="data.topAchievements.length === 0" class="text-xs opacity-50">該当なし</p>
              </div>
            </div>
          </div>

          <!-- フッター -->
          <p class="text-xs opacity-60 mt-4 text-right">beat-seeker.com</p>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.scrollbar-hide {
  scrollbar-width: none;
  -ms-overflow-style: none;
}
.scrollbar-hide::-webkit-scrollbar {
  display: none;
}
</style>
