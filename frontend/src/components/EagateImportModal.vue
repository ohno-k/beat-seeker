<script setup lang="ts">
import { ref, watch } from 'vue';
import { useAuth, API_BASE } from '../composables/useAuth';

const props = defineProps<{ isOpen: boolean }>();
const emit = defineEmits<{ (e: 'close'): void }>();

const { getLinkToken, regenerateLinkToken } = useAuth();

const linkToken = ref('');
const isLoading = ref(false);
const isCopied = ref(false);
const isCopiedUrl = ref(false);
const isCopiedShortcuts = ref(false);
const isRegenerating = ref(false);
const activeTab = ref<'bookmarklet' | 'shortcuts'>('bookmarklet');

const EAGATE_CSV_URL = 'https://p.eagate.573.jp/game/2dx/33/djdata/score_download.html?style=SP';

watch(() => props.isOpen, async (open) => {
  if (open && !linkToken.value) {
    isLoading.value = true;
    try {
      linkToken.value = await getLinkToken();
    } finally {
      isLoading.value = false;
    }
  }
});

// Bookmarklet: auto-fetches SP data if not already loaded on the page
const bookmarkletCode = () => {
  const apiBase = API_BASE;
  const token = linkToken.value;
  return `javascript:(function(){function doImport(c){fetch('${apiBase}/api/scores/import-csv',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:'${token}',csvText:c})}).then(function(r){return r.json()}).then(function(d){alert(d.message||'インポート完了')}).catch(function(e){alert('エラー: '+e)})}var t=document.getElementById('score_data');if(t&&t.value.trim()){doImport(t.value)}else{var f=new FormData();f.append('style','SP');fetch(location.pathname,{method:'POST',body:f}).then(function(r){return r.text()}).then(function(h){var d=new DOMParser().parseFromString(h,'text/html');var ta=d.getElementById('score_data');var v=ta?(ta.value||ta.textContent).trim():'';if(!v){alert('CSVデータが取得できませんでした');return;}doImport(v)}).catch(function(e){alert('エラー: '+e)})}})();`;
};

// iOS Shortcuts JS: same logic but uses completion() to return result
const shortcutsJsCode = () => {
  const apiBase = API_BASE;
  const token = linkToken.value;
  return `function doImport(c){fetch('${apiBase}/api/scores/import-csv',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:'${token}',csvText:c})}).then(function(r){return r.json()}).then(function(d){completion(d.message||'インポート完了')}).catch(function(e){completion('エラー: '+e)})}var t=document.getElementById('score_data');if(t&&t.value.trim()){doImport(t.value)}else{var f=new FormData();f.append('style','SP');fetch(location.pathname,{method:'POST',body:f}).then(function(r){return r.text()}).then(function(h){var d=new DOMParser().parseFromString(h,'text/html');var ta=d.getElementById('score_data');var v=ta?(ta.value||ta.textContent).trim():'';if(!v){completion('CSVデータが取得できませんでした');return;}doImport(v)}).catch(function(e){completion('エラー: '+e)})}`;
};

const copyBookmarklet = async () => {
  try {
    await navigator.clipboard.writeText(bookmarkletCode());
    isCopied.value = true;
    setTimeout(() => (isCopied.value = false), 2000);
  } catch {
    alert('コピーに失敗しました。テキストを手動でコピーしてください。');
  }
};

const copyEagateUrl = async () => {
  try {
    await navigator.clipboard.writeText(EAGATE_CSV_URL);
    isCopiedUrl.value = true;
    setTimeout(() => (isCopiedUrl.value = false), 2000);
  } catch {
    alert('コピーに失敗しました。');
  }
};

const copyShortcutsJs = async () => {
  try {
    await navigator.clipboard.writeText(shortcutsJsCode());
    isCopiedShortcuts.value = true;
    setTimeout(() => (isCopiedShortcuts.value = false), 2000);
  } catch {
    alert('コピーに失敗しました。テキストを手動でコピーしてください。');
  }
};

const handleRegenerate = async () => {
  if (!confirm('トークンを再発行すると、以前のブックマークレット・ショートカットは使えなくなります。続けますか？')) return;
  isRegenerating.value = true;
  try {
    linkToken.value = await regenerateLinkToken();
    isCopied.value = false;
    isCopiedShortcuts.value = false;
  } finally {
    isRegenerating.value = false;
  }
};
</script>

<template>
  <Transition
    enter-active-class="transition-opacity duration-200"
    enter-from-class="opacity-0"
    leave-active-class="transition-opacity duration-150"
    leave-to-class="opacity-0"
  >
    <div v-if="isOpen" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm" @click.self="emit('close')">
      <div class="w-full max-w-lg bg-white dark:bg-slate-800 rounded-2xl shadow-2xl border border-slate-200 dark:border-slate-700 overflow-hidden flex flex-col max-h-[90vh]">
        <!-- Header -->
        <div class="flex items-center justify-between px-6 py-4 border-b border-slate-200 dark:border-slate-700 shrink-0">
          <div class="flex items-center gap-2">
            <div class="w-7 h-7 bg-orange-100 dark:bg-orange-900/40 rounded-lg flex items-center justify-center">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-orange-600 dark:text-orange-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
              </svg>
            </div>
            <h2 class="text-base font-bold text-slate-800 dark:text-white">eagate連携</h2>
          </div>
          <button @click="emit('close')" class="p-1.5 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition-colors rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- Tabs -->
        <div class="flex border-b border-slate-200 dark:border-slate-700 shrink-0">
          <button
            @click="activeTab = 'bookmarklet'"
            class="flex-1 px-4 py-2.5 text-sm font-semibold transition-colors"
            :class="activeTab === 'bookmarklet'
              ? 'text-blue-600 border-b-2 border-blue-600 dark:text-blue-400 dark:border-blue-400'
              : 'text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200'"
          >
            ブックマークレット
          </button>
          <button
            @click="activeTab = 'shortcuts'"
            class="flex-1 px-4 py-2.5 text-sm font-semibold transition-colors flex items-center justify-center gap-1.5"
            :class="activeTab === 'shortcuts'
              ? 'text-blue-600 border-b-2 border-blue-600 dark:text-blue-400 dark:border-blue-400'
              : 'text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200'"
          >
            <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="currentColor">
              <path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.8-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M13 3.5c.73-.83 1.94-1.46 2.94-1.5.13 1.17-.34 2.35-1.04 3.19-.69.85-1.83 1.51-2.95 1.42-.15-1.15.41-2.35 1.05-3.11z"/>
            </svg>
            iOSショートカット<span class="text-[10px] bg-green-100 dark:bg-green-900/40 text-green-700 dark:text-green-400 px-1.5 py-0.5 rounded font-bold ml-1">1タップ</span>
          </button>
        </div>

        <!-- Body -->
        <div class="px-6 py-5 space-y-5 overflow-y-auto">

          <!-- Loading -->
          <div v-if="isLoading" class="h-32 flex items-center justify-center">
            <div class="w-6 h-6 border-2 border-blue-200 border-t-blue-600 rounded-full animate-spin"></div>
          </div>

          <template v-else>
            <!-- ====== Bookmarklet Tab ====== -->
            <template v-if="activeTab === 'bookmarklet'">
              <ol class="space-y-3 text-sm">
                <li class="flex gap-3">
                  <span class="w-5 h-5 rounded-full bg-blue-600 text-white text-xs font-bold flex items-center justify-center shrink-0 mt-0.5">1</span>
                  <span class="text-slate-600 dark:text-slate-300">下のコードをコピーし、ブラウザのブックマークの「URL」欄に貼り付けて保存します。</span>
                </li>
                <li class="flex gap-3">
                  <span class="w-5 h-5 rounded-full bg-blue-600 text-white text-xs font-bold flex items-center justify-center shrink-0 mt-0.5">2</span>
                  <span class="text-slate-600 dark:text-slate-300">eagateにログインした状態でCSVダウンロードページを開き、登録したブックマークをクリックします。SPデータを自動取得してインポートします。</span>
                </li>
              </ol>

              <div class="space-y-2">
                <div class="flex items-center justify-between">
                  <span class="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider">ブックマークレットのコード</span>
                  <button
                    @click="handleRegenerate"
                    :disabled="isRegenerating"
                    class="text-xs text-slate-400 hover:text-red-500 dark:hover:text-red-400 transition-colors flex items-center gap-1"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                      <path stroke-linecap="round" stroke-linejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                    </svg>
                    トークンを再発行
                  </button>
                </div>
                <div class="bg-slate-50 dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-700 p-3 font-mono text-[10px] text-slate-600 dark:text-slate-400 break-all max-h-24 overflow-y-auto leading-relaxed">
                  {{ bookmarkletCode() }}
                </div>
                <button
                  @click="copyBookmarklet"
                  class="w-full py-2.5 rounded-xl font-bold text-sm transition-all flex items-center justify-center gap-2"
                  :class="isCopied ? 'bg-green-500 text-white' : 'bg-blue-600 hover:bg-blue-700 text-white'"
                >
                  <svg v-if="!isCopied" xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                  </svg>
                  <svg v-else xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
                  </svg>
                  {{ isCopied ? 'コピーしました！' : 'コードをコピー' }}
                </button>
              </div>
            </template>

            <!-- ====== iOS Shortcuts Tab ====== -->
            <template v-else>
              <div class="bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded-xl p-3 text-xs text-blue-700 dark:text-blue-300">
                iPhoneの「ショートカット」アプリを使うと、<strong>ホーム画面から1タップ</strong>でeagateのSPスコアをインポートできます。
              </div>

              <ol class="space-y-4 text-sm">
                <li class="flex gap-3">
                  <span class="w-5 h-5 rounded-full bg-blue-600 text-white text-xs font-bold flex items-center justify-center shrink-0 mt-0.5">1</span>
                  <div class="flex-1 space-y-2">
                    <span class="text-slate-600 dark:text-slate-300">「ショートカット」アプリで新規ショートカットを作成し、<strong>「URLを開く」</strong>アクションを追加して下記URLを設定します。</span>
                    <div class="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-200 dark:border-slate-700 p-2 font-mono text-[10px] text-slate-600 dark:text-slate-400 break-all">{{ EAGATE_CSV_URL }}</div>
                    <button
                      @click="copyEagateUrl"
                      class="w-full py-1.5 rounded-lg text-xs font-bold transition-all flex items-center justify-center gap-1.5"
                      :class="isCopiedUrl ? 'bg-green-500 text-white' : 'bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-200 hover:bg-slate-300 dark:hover:bg-slate-600'"
                    >
                      {{ isCopiedUrl ? 'コピーしました！' : 'URLをコピー' }}
                    </button>
                  </div>
                </li>

                <li class="flex gap-3">
                  <span class="w-5 h-5 rounded-full bg-blue-600 text-white text-xs font-bold flex items-center justify-center shrink-0 mt-0.5">2</span>
                  <div class="flex-1 space-y-2">
                    <span class="text-slate-600 dark:text-slate-300">次に<strong>「Webページでスクリプトを実行」</strong>アクションを追加し、下記のコードを貼り付けます。</span>
                    <div class="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-200 dark:border-slate-700 p-2 font-mono text-[10px] text-slate-600 dark:text-slate-400 break-all max-h-20 overflow-y-auto leading-relaxed">
                      {{ shortcutsJsCode() }}
                    </div>
                    <button
                      @click="copyShortcutsJs"
                      class="w-full py-1.5 rounded-lg text-xs font-bold transition-all flex items-center justify-center gap-1.5"
                      :class="isCopiedShortcuts ? 'bg-green-500 text-white' : 'bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-200 hover:bg-slate-300 dark:hover:bg-slate-600'"
                    >
                      {{ isCopiedShortcuts ? 'コピーしました！' : 'スクリプトをコピー' }}
                    </button>
                  </div>
                </li>

                <li class="flex gap-3">
                  <span class="w-5 h-5 rounded-full bg-blue-600 text-white text-xs font-bold flex items-center justify-center shrink-0 mt-0.5">3</span>
                  <span class="text-slate-600 dark:text-slate-300">さらに<strong>「通知を表示」</strong>アクションを追加し、入力元を「Webページでスクリプトを実行」の結果に設定します。</span>
                </li>

                <li class="flex gap-3">
                  <span class="w-5 h-5 rounded-full bg-blue-600 text-white text-xs font-bold flex items-center justify-center shrink-0 mt-0.5">4</span>
                  <span class="text-slate-600 dark:text-slate-300">ショートカットを保存し、「ホーム画面に追加」でアイコンを作成します。以後は1タップでインポートできます。</span>
                </li>
              </ol>

              <div class="bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-xl p-3 text-xs text-amber-700 dark:text-amber-300">
                実行前にSafariでeagateにログインしておく必要があります。またトークンを再発行した場合はショートカットのスクリプトも更新してください。
              </div>

              <div class="flex justify-end">
                <button
                  @click="handleRegenerate"
                  :disabled="isRegenerating"
                  class="text-xs text-slate-400 hover:text-red-500 dark:hover:text-red-400 transition-colors flex items-center gap-1"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                  </svg>
                  トークンを再発行
                </button>
              </div>
            </template>

            <p class="text-xs text-slate-400 dark:text-slate-500 bg-slate-50 dark:bg-slate-900/50 rounded-lg p-3">
              このトークンはあなた専用のものです。他人に教えないでください。漏洩した場合は「トークンを再発行」してください。
            </p>
          </template>
        </div>
      </div>
    </div>
  </Transition>
</template>
