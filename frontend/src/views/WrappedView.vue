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
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue';
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
    // 公開ビューは URL クエリ ?t=:hmacToken が必須。無ければ 403 を返させる。
    const token = String(route.query.t ?? '');
    await fetchPublicWrapped(targetUserId.value!, year.value, month.value, token);
  } else {
    await fetchMyWrapped(year.value, month.value);
  }
}

onMounted(load);
watch([year, month, targetUserId], load);

// ─── カード総数（RATE-TIER の表示設定を反映） ─────
const totalCards = computed(() => {
  const d = data.value;
  if (!d) return 0;
  // 必ず表示するカード: オープニング・プレー量・AAA・ランプ・BEAT-PT・BEAT-PT TOP10・先月比・クロージング = 8
  let base = 8;
  // RATE-TIER 表示時のみ RATE-PT 変動と RATE-PT TOP10 を加算
  if (d.showRateTier) base += 2;
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
  // スピナー表示を確実に画面に描画してから html2canvas に入る。
  // nextTick で Vue の DOM 反映、続けて 2 回 rAF で次フレームの paint を待つ。
  // (html2canvas は同期的にイベントループをブロックしがちで、これが無いと
  //  ボタン上のローディング表示がクリック直後に切り替わらず体感反応が遅く見える)
  await nextTick();
  await new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
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

/**
 * シェア用の公開 URL（OGP が展開される /user/:userId/wrapped/... 形式）。
 *
 * URL を予測されただけで他人の振り返りが見られないよう、HMAC ベースの shareToken を
 * クエリパラメータ ?t= に付与する。本人取得時のみ shareToken が返るので、
 * 公開ビューでシェアボタンを押した場合は現在 URL の t をそのまま使う。
 */
const sharePublicUrl = computed(() => {
  if (!data.value) return '';
  const uid = data.value.userId;
  const token = data.value.shareToken ?? String(route.query.t ?? '');
  const base = `${window.location.origin}/user/${uid}/wrapped/${year.value}/${month.value}`;
  return token ? `${base}?t=${encodeURIComponent(token)}` : base;
});

/**
 * シェア画像内の曲タイトル表示。LEGGENDARIA 譜面は [L] サフィックスを付けて
 * ANOTHER と区別する (IIDX コミュニティの慣習表記)。
 */
function displayTitleForShare(title: string, difficulty: string): string {
  return difficulty === 'LEGGENDARIA' ? `${title} [L]` : title;
}

/**
 * Twitter 投稿用テンプレート。
 *
 * X の intent/tweet は `text=...&url=...` を受け取ると本文末尾に半角スペースで URL を連結するため、
 * shareText の末尾に改行 (\n\n) を入れて URL との間に空行を作る。
 * 加えてハッシュタグの前にも空行を入れて、本文 / ハッシュタグ / URL の 3 ブロックを視覚的に区切る。
 */
const shareText = computed(() => {
  if (!data.value) return '';
  const d = data.value;
  return `${d.displayName} の ${d.displayMonth} の記録\n` +
    `更新譜面 ${d.uniqueUpdatedSongCount} 曲 / 新規 AAA +${d.djLevelIncrease?.aaa ?? 0} / BEAT-PT ${d.beatPtIncrease >= 0 ? '+' : ''}${d.beatPtIncrease.toFixed(2)}\n` +
    `\n` +
    `#beatseeker #beatmaniaIIDX\n` +
    `\n`;
});

/** 直近のシェア結果メッセージ（ボタン下に表示してユーザーに次の操作を案内する）。 */
const shareHintMessage = ref<string>('');

async function handleShare() {
  shareHintMessage.value = '';
  const blob = await generateShareBlob();
  if (!blob) return;
  const fileName = `beat-seeker-wrapped-${year.value}-${String(month.value).padStart(2, '0')}.png`;
  const file = new File([blob], fileName, { type: 'image/png' });

  // モバイル等の Web Share API（files 対応）はネイティブ共有シートで X アプリが選べる。
  // この場合は画像が直接添付された状態で X が起動するため、デスクトップのフォールバックは不要。
  const nav = navigator as any;
  if (nav.canShare && nav.canShare({ files: [file] })) {
    try {
      await nav.share({ files: [file], text: shareText.value, url: sharePublicUrl.value });
      return;
    } catch {
      // ユーザーがキャンセル等。デスクトップ向けフォールバックへ進む。
    }
  }

  // デスクトップ: X の Web 投稿フォーム (intent/tweet) は画像の自動添付 URL パラメータを
  // 公式にサポートしていない。そのため次善策として:
  //   (a) 画像を**クリップボードに自動コピー**
  //   (b) ダメなら画像をダウンロード
  //   (c) X 投稿フォームを別タブで開く
  //   (d) ボタン下にどの方法でやればよいかを案内
  // を順に行う。ユーザーは X タブで Ctrl+V (Mac は Cmd+V) するだけで画像が添付される。
  let copiedToClipboard = false;
  try {
    const ClipboardItemCtor = (window as any).ClipboardItem;
    if (navigator.clipboard && ClipboardItemCtor) {
      await navigator.clipboard.write([new ClipboardItemCtor({ [blob.type]: blob })]);
      copiedToClipboard = true;
    }
  } catch {
    // Permissions Policy・Safari の未対応・user gesture 切れなどでコピー失敗。ダウンロードへ。
  }

  if (!copiedToClipboard) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  const tweet = `https://x.com/intent/tweet?text=${encodeURIComponent(shareText.value)}&url=${encodeURIComponent(sharePublicUrl.value)}`;
  window.open(tweet, '_blank', 'noopener');

  shareHintMessage.value = copiedToClipboard
    ? '画像をクリップボードにコピーしました。X の投稿画面で Ctrl+V (Mac は Cmd+V) で貼り付けてください。'
    : '画像をダウンロードしました。X の投稿画面でファイルを添付してください。';
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

        <!-- カード 9: クロージング + シェア -->
        <section class="snap-start min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 p-8 text-center">
          <p class="text-xs md:text-sm tracking-[0.3em] opacity-70 mb-3">{{ data.displayMonth }}</p>
          <h2 class="text-3xl md:text-5xl font-black mb-3">お疲れさまでした</h2>
          <p class="opacity-70 mb-10 text-sm md:text-base">来月もよい記録を</p>
          <button
            @click="handleShare"
            :disabled="isGeneratingShare"
            class="px-8 py-4 bg-black text-white font-bold text-base md:text-lg rounded-2xl shadow-2xl hover:scale-105 active:scale-95 transition-all disabled:opacity-70 disabled:cursor-wait inline-flex items-center gap-3"
          >
            <template v-if="isGeneratingShare">
              <svg class="animate-spin h-5 w-5" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" aria-hidden="true">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
                <path class="opacity-90" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
              </svg>
              <span>画像を生成中...</span>
            </template>
            <template v-else>
              <span>𝕏 でシェア</span>
            </template>
          </button>
          <!-- シェア後の案内: クリップボード成功 or ダウンロードのどちらか。fade-in で柔らかく出す。 -->
          <p v-if="shareHintMessage" class="text-xs md:text-sm opacity-90 mt-4 max-w-md leading-relaxed">
            {{ shareHintMessage }}
          </p>
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
        レイアウト:
          - 上 3/4: BEAT-PT TOP5 と RATE-PT TOP5 (showRateTier=false なら BEAT-PT が全幅)
          - 下 1/4: 新規 AAA / MAX- TOP5
        各テキスト行は leading-normal + py-2 で垂直方向の中央位置を明示。
        html2canvas のフォントベースライン補正で文字が下寄り/見切れになる問題への対策。
      -->
      <div
        ref="shareImageRef"
        class="fixed left-0 top-0 -translate-x-[200%] pointer-events-none"
        style="width: 1200px; height: 675px;"
        aria-hidden="true"
      >
        <div class="w-full h-full bg-gradient-to-br from-slate-900 via-blue-900 to-purple-900 text-white p-8 flex flex-col">
          <!--
            html2canvas のフォントベースライン補正対策で、すべての行に
              height = line-height（同値の数値 px）
            を明示し、文字を厳密に中央配置している。flex items-center だけだとずれるため。
          -->
          <!-- ヘッダー: 月 + ユーザー名 -->
          <div class="flex items-end justify-between mb-4 flex-shrink-0">
            <div>
              <p class="tracking-[0.4em] opacity-70" style="font-size: 14px; height: 24px; line-height: 24px;">
                BEAT-SEEKER WRAPPED
              </p>
              <h1 class="font-black" style="font-size: 56px; height: 68px; line-height: 68px;">
                {{ data.displayMonth }}
              </h1>
            </div>
            <div class="text-right">
              <p class="opacity-90 font-bold" style="font-size: 28px; height: 40px; line-height: 40px;">
                {{ data.displayName }}
              </p>
              <p class="opacity-60" style="font-size: 16px; height: 26px; line-height: 26px;">
                更新譜面 {{ data.uniqueUpdatedSongCount }} 曲 / 新規 AAA +{{ data.djLevelIncrease?.aaa ?? 0 }}
              </p>
            </div>
          </div>

          <!-- 上 3/4 と 下 1/4 を 3fr:1fr で分配 -->
          <div class="grid gap-4 flex-1 min-h-0" style="grid-template-rows: 3fr 1fr;">
            <!-- 上段: BEAT-PT + RATE-PT を横並び (showRateTier=false なら BEAT-PT が全幅) -->
            <div :class="['grid gap-4 min-h-0', data.showRateTier ? 'grid-cols-2' : 'grid-cols-1']">
              <!-- BEAT-PT TOP5 -->
              <div class="bg-white/5 rounded-2xl p-4 flex flex-col min-h-0 overflow-hidden">
                <p class="tracking-wider opacity-70 mb-2 font-semibold" style="font-size: 16px; height: 24px; line-height: 24px;">
                  BEAT-PT を支える譜面
                </p>
                <div class="flex-1 flex flex-col min-h-0">
                  <div
                    v-for="(s, i) in data.topBeatPtSongs.slice(0, 5)"
                    :key="`sbp-${i}`"
                    class="flex items-center gap-3 px-1"
                    style="height: 44px;"
                  >
                    <span class="opacity-60 font-bold text-center" style="font-size: 15px; width: 22px; height: 44px; line-height: 44px;">
                      {{ i + 1 }}
                    </span>
                    <span class="flex-1 truncate" style="font-size: 17px; height: 44px; line-height: 44px;">
                      {{ displayTitleForShare(s.title, s.difficulty) }}
                    </span>
                    <span class="text-violet-300 font-bold whitespace-nowrap" style="font-size: 22px; height: 44px; line-height: 44px;">
                      {{ s.newBeatPt.toFixed(1) }}
                    </span>
                  </div>
                  <p v-if="data.topBeatPtSongs.length === 0" style="font-size: 15px; line-height: 22px;" class="opacity-50">該当なし</p>
                </div>
              </div>

              <!-- RATE-PT TOP5 (showRateTier のみ) -->
              <div v-if="data.showRateTier" class="bg-white/5 rounded-2xl p-4 flex flex-col min-h-0 overflow-hidden">
                <p class="tracking-wider opacity-70 mb-2 font-semibold" style="font-size: 16px; height: 24px; line-height: 24px;">
                  RATE-PT 増加
                </p>
                <div class="flex-1 flex flex-col min-h-0">
                  <div
                    v-for="(s, i) in data.topRatePtSongs.slice(0, 5)"
                    :key="`srp-${i}`"
                    class="flex items-center gap-3 px-1"
                    style="height: 44px;"
                  >
                    <span class="opacity-60 font-bold text-center" style="font-size: 15px; width: 22px; height: 44px; line-height: 44px;">
                      {{ i + 1 }}
                    </span>
                    <span class="flex-1 truncate" style="font-size: 17px; height: 44px; line-height: 44px;">
                      {{ displayTitleForShare(s.title, s.difficulty) }}
                    </span>
                    <span class="text-rose-300 font-bold whitespace-nowrap" style="font-size: 22px; height: 44px; line-height: 44px;">
                      +{{ s.ratePtIncrease.toFixed(1) }}
                    </span>
                  </div>
                  <p v-if="data.topRatePtSongs.length === 0" style="font-size: 15px; line-height: 22px;" class="opacity-50">該当なし</p>
                </div>
              </div>
            </div>

            <!--
              下段: 新規 AAA / MAX- (横並びチップリスト)。
              「要素に入るだけ入れる、はみ出しはやめる」要件のため、
                - 配列は slice せず全件描画
                - コンテナに overflow:hidden で物理クリップ (はみ出した最後のチップは見えなくなる)
                - 各チップは height/line-height 固定で中央配置を担保
            -->
            <div class="bg-white/5 rounded-2xl p-3 flex flex-col min-h-0 overflow-hidden">
              <p class="tracking-wider opacity-70 mb-1.5 font-semibold" style="font-size: 13px; height: 20px; line-height: 20px;">
                新規 AAA / MAX-
              </p>
              <div v-if="data.topAchievements.length > 0" class="flex gap-1.5 flex-wrap content-start overflow-hidden flex-1 min-h-0">
                <div
                  v-for="(a, i) in data.topAchievements"
                  :key="`sa-${i}`"
                  class="flex items-center gap-1.5 bg-white/10 rounded"
                  style="height: 24px; padding: 0 8px;"
                >
                  <span
                    :class="[
                      'font-black rounded',
                      a.achievementType === 'MAX-' ? 'bg-amber-300 text-slate-900' : 'bg-white/20'
                    ]"
                    style="font-size: 10px; height: 16px; line-height: 16px; padding: 0 4px;"
                  >
                    {{ a.achievementType }}
                  </span>
                  <span style="font-size: 12px; height: 24px; line-height: 24px;">
                    {{ displayTitleForShare(a.title, a.difficulty) }}
                  </span>
                  <span class="opacity-60" style="font-size: 11px; height: 24px; line-height: 24px;">
                    ☆{{ a.informalRank ?? '?' }}
                  </span>
                </div>
              </div>
              <p v-else style="font-size: 13px; line-height: 20px;" class="opacity-50">今月の新規 AAA / MAX- はありませんでした</p>
            </div>
          </div>

          <!-- フッター -->
          <p class="opacity-60 mt-3 text-right flex-shrink-0" style="font-size: 14px; height: 22px; line-height: 22px;">
            beat-seeker.com
          </p>
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
