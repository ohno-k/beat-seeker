<script setup lang="ts">
/**
 * 【コンポーネントの役割】 Cmd/Ctrl + K で開くグローバル検索パレット。
 *
 * Spotlight / VS Code の Command Palette 風の操作モデルを提供する：
 *  - 入力でナビゲーション・クイックアクション・曲名を横断検索
 *  - ↑↓ で選択、Enter で実行、Esc で閉じる
 *  - 背景クリックでも閉じる、開いた瞬間に検索ボックスへ自動フォーカス
 *
 * Sidebar の navigationItems と二重定義になるが、CommandPalette は権限フィルタや
 * 多言語見出しの並びがほぼ同じなので、ここに必要最小限のラベル + アイコンを内包する。
 * 「実際にどのタブが表示可能か」は親（App.vue）が `availableTabIds` で渡してくる。
 */
import { ref, computed, watch, nextTick } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useModalEscape } from '../composables/useModalEscape';
import type { ScoreData } from '../types/ScoreData';

const { t } = useI18n();

const props = defineProps<{
  /** パレットの開閉状態（v-model:isOpen での双方向バインド想定）。 */
  isOpen: boolean;
  /** 現在アクティブなタブ ID（ハイライト用）。 */
  activeTab: string;
  /** ログイン済みかどうか。一部アクションを出し分ける。 */
  isLoggedIn: boolean;
  /** ダークモードフラグ（トグル時の表示用）。 */
  isDark: boolean;
  /** 曲名検索の母集団。App.vue の scoreData をそのまま渡す。 */
  scoreData: ScoreData[];
  /** 表示可能なタブ ID のリスト。Sidebar 側のフィルタ結果と同じものを渡す想定。 */
  availableTabIds: string[];
  /** ログイン済みユーザーのみが使えるアクションを抑止するためのフラグ（他ユーザー閲覧中など）。 */
  isViewingOther: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:isOpen', v: boolean): void;
  (e: 'select-tab', tabId: string): void;
  (e: 'action', name: 'upload' | 'profile-edit' | 'toggle-dark' | 'logout'): void;
  (e: 'select-song', title: string): void;
}>();

useModalEscape(() => props.isOpen, () => close());

/** 全ナビ項目のラベルキーとアイコン定義。Sidebar.vue の navigationItems と並びを揃えてある。 */
const NAV_DEFS: ReadonlyArray<{ id: string; labelKey: string; iconPath: string }> = [
  { id: 'dashboard', labelKey: 'nav.dashboard', iconPath: 'M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6' },
  { id: 'table', labelKey: 'nav.scoreList', iconPath: 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-3 7h3m-3 4h3m-6-4h.01M9 16h.01' },
  { id: 'profile', labelKey: 'nav.profile', iconPath: 'M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z' },
  { id: 'ranking', labelKey: 'nav.ranking', iconPath: 'M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z' },
  { id: 'friends', labelKey: 'nav.friends', iconPath: 'M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z' },
  { id: 'history', labelKey: 'nav.history', iconPath: 'M13 7h8m0 0v8m0-8l-8 8-4-4-6 6' },
  { id: 'arena', labelKey: 'nav.arena', iconPath: 'M9 12l2 2 4-4M7.835 4.697a3.42 3.42 0 001.946-.806 3.42 3.42 0 014.438 0 3.42 3.42 0 001.946.806 3.42 3.42 0 013.138 3.138 3.42 3.42 0 00.806 1.946 3.42 3.42 0 010 4.438 3.42 3.42 0 00-.806 1.946 3.42 3.42 0 01-3.138 3.138 3.42 3.42 0 00-1.946.806 3.42 3.42 0 01-4.438 0 3.42 3.42 0 00-1.946-.806 3.42 3.42 0 01-3.138-3.138z' },
  { id: 'arcade-assist', labelKey: 'nav.arcadeAssist', iconPath: 'M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7' },
  { id: 'tier-voting', labelKey: 'nav.tierVoting', iconPath: 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 7l2 2 4-4' },
  { id: 'song-avg', labelKey: 'nav.songAvg', iconPath: 'M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z' },
  { id: 'diff-table', labelKey: 'nav.diffTable', iconPath: 'M4 6h16M4 10h16M4 14h16M4 18h16' },
  { id: 'rank-comparison', labelKey: 'nav.rankComparison', iconPath: 'M3 6l3 1m0 0l-3 9a5.002 5.002 0 006.001 0M6 7l3 9M6 7l6-2m6 2l3-1m-3 1l-3 9a5.002 5.002 0 006.001 0M18 7l3 9m-3-9l-6-2m0-2v2m0 16V5m0 16H9m3 0h3' },
  { id: 'changelog', labelKey: 'nav.changelog', iconPath: 'M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z' },
  { id: 'about', labelKey: 'nav.about', iconPath: 'M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z' },
];

/** 入力中の検索文字列。空のときは「すべて」を表示する。 */
const query = ref('');
/** 現在ハイライト中の項目インデックス（filteredItems に対する 0 始まり）。 */
const selectedIdx = ref(0);
/** 検索ボックス DOM への参照。開いた瞬間に focus() するため。 */
const inputRef = ref<HTMLInputElement | null>(null);
/** 結果リストの DOM 参照。選択行が見えるよう scroll into view する用。 */
const listRef = ref<HTMLDivElement | null>(null);

interface PaletteItem {
  id: string;
  group: 'nav' | 'action' | 'song';
  label: string;
  sub?: string;
  iconPath: string;
  onSelect: () => void;
}

const closeIconPath = 'M6 18L18 6M6 6l12 12';
const uploadIconPath = 'M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12';
const profileIconPath = 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2';
const moonIconPath = 'M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z';
const sunIconPath = 'M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z';
const logoutIconPath = 'M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1';
const songIconPath = 'M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zM9 10l12-3';

/**
 * 【computed の役割】 ナビ・アクション・曲のすべてを 1 配列にフラット化したパレット項目集合。
 *
 * - ナビ: `availableTabIds` に含まれる項目のみ
 * - アクション: ログイン状態 / 閲覧モードに応じて出し分け
 * - 曲: scoreData から最大 60 件まで（多すぎると検索結果のスクロールが破綻するため）
 */
const allItems = computed<PaletteItem[]>(() => {
  const items: PaletteItem[] = [];

  // ナビゲーション
  for (const def of NAV_DEFS) {
    if (!props.availableTabIds.includes(def.id)) continue;
    items.push({
      id: `nav:${def.id}`,
      group: 'nav',
      label: t(def.labelKey),
      iconPath: def.iconPath,
      onSelect: () => emit('select-tab', def.id),
    });
  }

  // クイックアクション
  if (!props.isViewingOther) {
    items.push({
      id: 'action:upload',
      group: 'action',
      label: t('cmdk.action.upload'),
      iconPath: uploadIconPath,
      onSelect: () => emit('action', 'upload'),
    });
  }
  if (props.isLoggedIn && !props.isViewingOther) {
    items.push({
      id: 'action:profile-edit',
      group: 'action',
      label: t('cmdk.action.profileEdit'),
      iconPath: profileIconPath,
      onSelect: () => emit('action', 'profile-edit'),
    });
  }
  items.push({
    id: 'action:toggle-dark',
    group: 'action',
    label: t('cmdk.action.toggleDark'),
    iconPath: props.isDark ? sunIconPath : moonIconPath,
    onSelect: () => emit('action', 'toggle-dark'),
  });
  if (props.isLoggedIn) {
    items.push({
      id: 'action:logout',
      group: 'action',
      label: t('cmdk.action.logout'),
      iconPath: logoutIconPath,
      onSelect: () => emit('action', 'logout'),
    });
  }

  // 曲（スコア取り込み済みのとき）
  for (const song of props.scoreData) {
    items.push({
      id: `song:${song.title}`,
      group: 'song',
      label: song.title,
      sub: song.artist,
      iconPath: songIconPath,
      onSelect: () => emit('select-song', song.title),
    });
  }

  return items;
});

/**
 * 【関数の役割】 検索クエリと項目ラベル（・サブ）に対する単純な部分一致スコアリング。
 *
 * 完全一致 / 接頭辞一致 / 含有 で順位を分けて返す。曲数が数百規模なので Fuse.js 等は不要。
 * 0 ならマッチ無し（リストから除外）。
 */
function score(item: PaletteItem, q: string): number {
  if (!q) return 1; // クエリ空: 全件残す
  const haystack = (item.label + ' ' + (item.sub ?? '')).toLowerCase();
  const needle = q.toLowerCase();
  if (haystack === needle) return 100;
  if (haystack.startsWith(needle)) return 80;
  if (item.label.toLowerCase().includes(needle)) return 60;
  if (haystack.includes(needle)) return 40;
  return 0;
}

/**
 * 【computed の役割】 query に応じて allItems を絞り込み、グループ順 → スコア順で並べる。
 *
 * クエリ空のときはナビ → アクション → 曲（曲は最初の 0 件）の自然順。
 * クエリありのときは曲も対象になり、スコア降順で混じる。
 */
const filteredItems = computed<PaletteItem[]>(() => {
  const q = query.value.trim();
  const groupOrder: Record<PaletteItem['group'], number> = { nav: 0, action: 1, song: 2 };

  if (!q) {
    // クエリ空時: 曲は出さない（多すぎる）。ナビとアクションのみ。
    return allItems.value
      .filter(i => i.group !== 'song')
      .sort((a, b) => groupOrder[a.group] - groupOrder[b.group]);
  }

  const scored = allItems.value
    .map(item => ({ item, s: score(item, q) }))
    .filter(x => x.s > 0)
    .sort((a, b) => {
      // 同スコアなら group 順を優先
      if (b.s !== a.s) return b.s - a.s;
      return groupOrder[a.item.group] - groupOrder[b.item.group];
    })
    .slice(0, 60);
  return scored.map(x => x.item);
});

/** グループラベル（i18n キー）を返す。 */
function groupLabel(group: PaletteItem['group']): string {
  if (group === 'nav') return t('cmdk.group.nav');
  if (group === 'action') return t('cmdk.group.action');
  return t('cmdk.group.song');
}

/**
 * 【computed の役割】 filteredItems を group ごとにグループ化した配列。
 * テンプレートで「グループ見出し → 各アイテム」の形に描画するため。
 */
const groupedItems = computed(() => {
  const groups: Array<{ group: PaletteItem['group']; items: PaletteItem[] }> = [];
  let current: { group: PaletteItem['group']; items: PaletteItem[] } | null = null;
  for (const item of filteredItems.value) {
    if (!current || current.group !== item.group) {
      current = { group: item.group, items: [] };
      groups.push(current);
    }
    current.items.push(item);
  }
  return groups;
});

/** 【関数の役割】 パレットを閉じる。query もリセットして次回開いた時にきれいな状態にする。 */
function close() {
  emit('update:isOpen', false);
  query.value = '';
  selectedIdx.value = 0;
}

/**
 * 【関数の役割】 現在ハイライト中の項目を実行する。実行後はパレットを閉じる。
 * filteredItems が空のときは何もしない（誤操作防止）。
 */
function executeSelected() {
  const items = filteredItems.value;
  if (items.length === 0) return;
  const idx = Math.min(selectedIdx.value, items.length - 1);
  items[idx].onSelect();
  close();
}

/** ↑↓ で selectedIdx を移動する（ラップアラウンドなしで端で止める）。 */
function moveSelection(delta: number) {
  const items = filteredItems.value;
  if (items.length === 0) return;
  const next = selectedIdx.value + delta;
  selectedIdx.value = Math.max(0, Math.min(items.length - 1, next));
  // 選択行を視野内へ
  nextTick(() => {
    const el = listRef.value?.querySelector<HTMLElement>(`[data-cmdk-idx="${selectedIdx.value}"]`);
    el?.scrollIntoView({ block: 'nearest' });
  });
}

/** 検索ボックスでのキーハンドラ。↑↓ Enter で操作する。 */
function onInputKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') {
    e.preventDefault();
    moveSelection(1);
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    moveSelection(-1);
  } else if (e.key === 'Enter') {
    e.preventDefault();
    executeSelected();
  }
}

// クエリ変更で選択を先頭にリセット（マッチ件数が変わるため）。
watch(query, () => { selectedIdx.value = 0; });

// 開いた瞬間にフォーカスを検索ボックスへ（ユーザーは即座にタイプ開始できる）。
watch(() => props.isOpen, (open) => {
  if (open) {
    nextTick(() => inputRef.value?.focus());
  }
});

/**
 * 【computed の役割】 グループ表示時に、各アイテムが filteredItems 全体で何番目かを返すためのオフセット計算。
 * テンプレート内で `idxOffset(group) + indexInGroup` のように使い、selectedIdx と合わせる。
 */
function idxOffset(targetGroup: PaletteItem['group']): number {
  let n = 0;
  for (const g of groupedItems.value) {
    if (g.group === targetGroup) return n;
    n += g.items.length;
  }
  return n;
}
</script>

<template>
  <Transition
    enter-active-class="transition ease-out duration-150"
    enter-from-class="opacity-0"
    enter-to-class="opacity-100"
    leave-active-class="transition ease-in duration-100"
    leave-from-class="opacity-100"
    leave-to-class="opacity-0"
  >
    <div
      v-if="isOpen"
      role="dialog"
      aria-modal="true"
      :aria-label="t('a11y.cmdk.title')"
      class="fixed inset-0 z-[120] flex items-start justify-center pt-[14vh] px-4 bg-slate-900/60 backdrop-blur-sm"
      @click.self="close"
    >
      <div
        class="w-full max-w-xl bg-white dark:bg-slate-800 rounded-md shadow-xl border border-slate-200 dark:border-slate-700 overflow-hidden flex flex-col animate-scale-pop"
      >
        <!-- 検索バー -->
        <div class="flex items-center gap-3 px-4 py-3 border-b border-slate-200 dark:border-slate-700">
          <svg aria-hidden="true" class="h-5 w-5 text-slate-400 dark:text-slate-500 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
          </svg>
          <input
            ref="inputRef"
            v-model="query"
            type="text"
            :placeholder="t('cmdk.placeholder')"
            class="flex-1 bg-transparent border-0 outline-none text-base text-slate-800 dark:text-slate-100 placeholder-slate-400 dark:placeholder-slate-500"
            @keydown="onInputKeydown"
          />
          <button
            type="button"
            :aria-label="t('a11y.modal.close')"
            class="p-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
            @click="close"
          >
            <svg aria-hidden="true" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" :d="closeIconPath" />
            </svg>
          </button>
        </div>

        <!-- 結果リスト -->
        <div ref="listRef" class="flex-1 overflow-y-auto max-h-[60vh] py-2">
          <div v-if="filteredItems.length === 0" class="px-6 py-10 text-center text-sm text-slate-500 dark:text-slate-400">
            {{ t('cmdk.empty') }}
          </div>
          <template v-else>
            <div v-for="g in groupedItems" :key="g.group" class="py-1">
              <div class="px-4 pt-2 pb-1 text-[10px] font-bold text-slate-400 dark:text-slate-500">
                {{ groupLabel(g.group) }}
              </div>
              <button
                v-for="(item, i) in g.items"
                :key="item.id"
                type="button"
                :data-cmdk-idx="idxOffset(g.group) + i"
                @mousemove="selectedIdx = idxOffset(g.group) + i"
                @click="item.onSelect(); close()"
                class="w-full flex items-center gap-3 px-4 py-2.5 text-sm text-left transition-colors"
                :class="selectedIdx === idxOffset(g.group) + i
                  ? 'bg-blue-50 dark:bg-blue-900/40 text-blue-700 dark:text-blue-200'
                  : 'text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-700/60'"
              >
                <svg aria-hidden="true" class="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" :d="item.iconPath" />
                </svg>
                <span class="flex-1 truncate font-medium">{{ item.label }}</span>
                <span v-if="item.sub" class="text-xs text-slate-400 dark:text-slate-500 truncate max-w-[40%]">{{ item.sub }}</span>
                <span
                  v-if="item.group === 'nav' && activeTab === item.id.replace('nav:', '')"
                  class="text-[9px] font-bold px-1.5 py-0.5 rounded bg-blue-100 dark:bg-blue-800 text-blue-600 dark:text-blue-300"
                >
                  ●
                </span>
              </button>
            </div>
          </template>
        </div>

        <!-- フッター: ショートカットヒント -->
        <div class="flex items-center justify-between gap-3 px-4 py-2 border-t border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/40 text-[11px] text-slate-500 dark:text-slate-400">
          <div class="flex items-center gap-3">
            <span class="inline-flex items-center gap-1">
              <kbd class="px-1.5 py-0.5 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 font-mono text-[10px]">↑↓</kbd>
            </span>
            <span class="inline-flex items-center gap-1">
              <kbd class="px-1.5 py-0.5 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 font-mono text-[10px]">Enter</kbd>
              {{ t('cmdk.kbdEnter') }}
            </span>
            <span class="inline-flex items-center gap-1">
              <kbd class="px-1.5 py-0.5 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 font-mono text-[10px]">Esc</kbd>
              {{ t('cmdk.kbdEsc') }}
            </span>
          </div>
          <span class="hidden sm:inline-flex items-center gap-1 font-mono">
            <kbd class="px-1.5 py-0.5 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 text-[10px]">{{ t('cmdk.shortcut.hint') }}</kbd>
          </span>
        </div>
      </div>
    </div>
  </Transition>
</template>
