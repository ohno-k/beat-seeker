<script setup lang="ts">
/**
 * 【コンポーネントの役割】 管理番号つきの全楽曲から 1 曲を選ばせる、行内インライン型の
 * 検索付きプルダウン (コンボボックス)。
 *
 * 大会の結果記録 UI で「実際に演奏された曲」を運営が選び直すために使う。通常その枠は
 * 自選曲 / StrategyCard 抽選曲から自動導出されるが、現地で曲が差し替わった場合などに
 * 手動で上書きできるようにするためのもの。
 *
 * 候補リストは {@code strategy_card_songs.json} を管理番号で重複排除したもの。同 json は
 * ジャンル × Lv の入れ子で、1 曲が複数ジャンルに登録されるため素直に走査すると同じ曲が
 * 何度も出てくる (2560 エントリ / 実 1934 曲)。ここでは id 単位に畳んで管理番号順に並べる。
 *
 * 【表示位置について】 候補パネルは {@code <Teleport to="body">} で body 直下に出し、
 * トリガの {@code getBoundingClientRect()} から fixed 座標を計算して重ねる。結果記録 UI は
 * スクロール領域や overflow を持つカードの中にあるため、通常の absolute だと祖先にクリップ
 * されてしまう。開いている間にスクロールされると位置がずれるので、その場合は閉じる。
 */
import { ref, computed, nextTick, onBeforeUnmount, watch } from 'vue';
import strategySongs from '../data/strategy_card_songs.json';

type Song = { id: number; version: string; title: string; diff: 'A' | 'L'; level: number };

/**
 * 親に返す選択結果。
 * ({@code <script setup>} は ES export を持てないので型は外に出さず、親側で構造的に受ける)
 */
type SongSelectValue = {
  strategyId: number;
  title: string;
  version: string;
  diff: 'A' | 'L';
  level: number;
};

const props = defineProps<{
  /** 現在の管理番号 (未決定なら null)。 */
  strategyId: number | null;
  /** 現在の曲名 (未決定なら null)。 */
  title: string | null;
  /** 右端に出す導出元バッジの文言。省略時はバッジ無し。 */
  badgeLabel?: string;
  /** 導出元バッジの配色クラス。 */
  badgeClass?: string;
}>();

const emit = defineEmits<{ (e: 'select', song: SongSelectValue): void }>();

/**
 * 全楽曲マスタ (管理番号順・重複排除済み)。json は静的 import なのでモジュール初期化時に 1 度だけ構築する。
 */
const ALL_SONGS: Song[] = (() => {
  const byId = new Map<number, Song>();
  const root = strategySongs as unknown as Record<string, Record<string, Song[]>>;
  for (const byLevel of Object.values(root)) {
    for (const arr of Object.values(byLevel)) {
      for (const s of arr) {
        if (!byId.has(s.id)) byId.set(s.id, s);
      }
    }
  }
  return [...byId.values()].sort((a, b) => a.id - b.id);
})();

// ── 開閉 ────────────────────────────────────────────
const open = ref(false);
const query = ref('');
const activeIndex = ref(0);
const triggerEl = ref<HTMLElement | null>(null);
const searchEl = ref<HTMLInputElement | null>(null);
const listEl = ref<HTMLElement | null>(null);
/** パネルの fixed 座標 (トリガの矩形から算出)。 */
const panelStyle = ref<Record<string, string>>({});

/** 候補パネルの最大高。下に入らなければトリガの上へ反転させる。 */
const PANEL_MAX_HEIGHT = 288;

const updatePanelPosition = () => {
  const el = triggerEl.value;
  if (!el) return;
  const r = el.getBoundingClientRect();
  const spaceBelow = window.innerHeight - r.bottom;
  // 下に入りきらず、かつ上のほうが広いなら上に出す
  const flipUp = spaceBelow < PANEL_MAX_HEIGHT + 16 && r.top > spaceBelow;
  const style: Record<string, string> = {
    position: 'fixed',
    left: `${Math.max(8, r.left)}px`,
    width: `${Math.max(r.width, 320)}px`,
    zIndex: '210',
  };
  if (flipUp) style.bottom = `${window.innerHeight - r.top + 4}px`;
  else style.top = `${r.bottom + 4}px`;
  panelStyle.value = style;
};

const openPanel = async () => {
  open.value = true;
  query.value = '';
  activeIndex.value = 0;
  updatePanelPosition();
  await nextTick();
  searchEl.value?.focus();
};

const closePanel = () => {
  open.value = false;
};

const togglePanel = () => {
  if (open.value) closePanel();
  else void openPanel();
};

// ── 検索 ────────────────────────────────────────────
/**
 * 一度に描画する最大件数。全 1934 曲を DOM に出すと重いので頭打ちにし、
 * 溢れた場合は「絞り込んでください」と明示する (黙って切り捨てない)。
 */
const MAX_RESULTS = 80;

/**
 * 検索。数字だけ (先頭 # は許容) のクエリは管理番号として扱い、管理番号一致を先に並べる。
 * 曲名にも数字を含むものがある (「5.1.1.」など) ため、管理番号一致のあとに曲名一致も続ける。
 */
const filtered = computed<{ hits: Song[]; overflow: boolean }>(() => {
  const raw = query.value.trim();
  if (!raw) {
    return { hits: ALL_SONGS.slice(0, MAX_RESULTS), overflow: ALL_SONGS.length > MAX_RESULTS };
  }
  const q = raw.toLowerCase();
  const idQuery = raw.replace(/^#/, '');
  const numeric = /^\d+$/.test(idQuery);

  const hits: Song[] = [];
  const seen = new Set<number>();
  if (numeric) {
    for (const s of ALL_SONGS) {
      if (String(s.id).includes(idQuery)) { hits.push(s); seen.add(s.id); }
    }
  }
  for (const s of ALL_SONGS) {
    if (seen.has(s.id)) continue;
    if (s.title.toLowerCase().includes(q)) hits.push(s);
  }
  return { hits: hits.slice(0, MAX_RESULTS), overflow: hits.length > MAX_RESULTS };
});

watch(query, () => { activeIndex.value = 0; });

const pick = (s: Song) => {
  emit('select', {
    strategyId: s.id,
    title: s.title,
    version: s.version,
    diff: s.diff,
    level: s.level,
  });
  closePanel();
};

/** ハイライト行をリスト内に収める。 */
const scrollActiveIntoView = async () => {
  await nextTick();
  const row = listEl.value?.querySelector<HTMLElement>('[data-active="true"]');
  row?.scrollIntoView({ block: 'nearest' });
};

const onSearchKeydown = (e: KeyboardEvent) => {
  const hits = filtered.value.hits;
  if (e.key === 'ArrowDown') {
    e.preventDefault();
    if (hits.length > 0) activeIndex.value = (activeIndex.value + 1) % hits.length;
    void scrollActiveIntoView();
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    if (hits.length > 0) activeIndex.value = (activeIndex.value - 1 + hits.length) % hits.length;
    void scrollActiveIntoView();
  } else if (e.key === 'Enter') {
    e.preventDefault();
    const s = hits[activeIndex.value];
    if (s) pick(s);
  } else if (e.key === 'Escape') {
    e.preventDefault();
    closePanel();
  }
};

// ── 外側クリック / スクロールで閉じる ──────────────────
const onDocPointerDown = (e: PointerEvent) => {
  if (!open.value) return;
  const t = e.target as Node | null;
  if (t && (triggerEl.value?.contains(t) || listEl.value?.contains(t))) return;
  closePanel();
};
/** スクロールするとトリガとパネルの位置がずれるので閉じる (capture でネストした領域も拾う)。 */
const onDocScroll = () => { if (open.value) closePanel(); };

watch(open, (v) => {
  if (v) {
    document.addEventListener('pointerdown', onDocPointerDown, true);
    document.addEventListener('scroll', onDocScroll, true);
    window.addEventListener('resize', onDocScroll);
  } else {
    document.removeEventListener('pointerdown', onDocPointerDown, true);
    document.removeEventListener('scroll', onDocScroll, true);
    window.removeEventListener('resize', onDocScroll);
  }
});

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocPointerDown, true);
  document.removeEventListener('scroll', onDocScroll, true);
  window.removeEventListener('resize', onDocScroll);
});
</script>

<template>
  <div class="min-w-0">
    <!-- トリガ (現在の曲を表示。クリックで候補パネル) -->
    <button
      ref="triggerEl"
      type="button"
      @click="togglePanel"
      class="w-full px-2 py-1 rounded border text-xs flex items-center gap-1.5 min-w-0 text-left transition-colors bg-white dark:bg-slate-800/70 border-slate-300 dark:border-slate-600 hover:border-blue-400 dark:hover:border-blue-500"
      :class="open ? 'ring-1 ring-blue-400 border-blue-400' : ''"
      :title="`#${props.strategyId ?? '—'} ${props.title ?? '未決定'}（クリックで曲を変更）`"
    >
      <span class="tabular-nums text-slate-500 shrink-0">#{{ props.strategyId ?? '—' }}</span>
      <span class="font-bold truncate" :class="props.title ? '' : 'italic text-slate-400 font-normal'">
        {{ props.title ?? '未決定' }}
      </span>
      <span
        v-if="props.badgeLabel"
        class="ml-auto shrink-0 text-[9px] font-bold px-1.5 py-0.5 rounded"
        :class="props.badgeClass"
      >{{ props.badgeLabel }}</span>
      <span class="shrink-0 text-slate-400 text-[9px]">▼</span>
    </button>

    <!-- 候補パネル。祖先の overflow にクリップされないよう body 直下へ出す。 -->
    <Teleport to="body">
      <div
        v-if="open"
        ref="listEl"
        :style="panelStyle"
        class="rounded-md shadow-xl border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 overflow-hidden flex flex-col"
      >
        <div class="p-2 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/40">
          <input
            ref="searchEl"
            v-model="query"
            type="text"
            placeholder="曲名 または 管理番号 (例: 1762) で検索"
            @keydown="onSearchKeydown"
            class="w-full px-2 py-1 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 text-xs"
          />
        </div>
        <ul class="overflow-y-auto text-xs" :style="{ maxHeight: `${PANEL_MAX_HEIGHT - 48}px` }">
          <li
            v-for="(s, i) in filtered.hits"
            :key="s.id"
            :data-active="i === activeIndex"
            @click="pick(s)"
            @mouseenter="activeIndex = i"
            class="px-2 py-1.5 cursor-pointer flex items-center gap-2 min-w-0"
            :class="[
              i === activeIndex ? 'bg-blue-50 dark:bg-blue-900/30' : '',
              s.id === props.strategyId ? 'font-bold text-blue-700 dark:text-blue-300' : '',
            ]"
          >
            <span class="tabular-nums text-slate-500 shrink-0 w-12">#{{ s.id }}</span>
            <span class="truncate flex-1">{{ s.title }}</span>
            <span class="shrink-0 tabular-nums text-[10px] font-mono text-slate-400">{{ s.diff }}{{ s.level }}</span>
            <span class="shrink-0 text-[10px] text-slate-400 hidden sm:inline truncate max-w-[90px]">{{ s.version }}</span>
          </li>
          <li v-if="filtered.hits.length === 0" class="px-2 py-3 text-center text-slate-400 italic">
            該当する曲がありません
          </li>
        </ul>
        <p
          v-if="filtered.overflow"
          class="px-2 py-1 text-[10px] text-amber-600 dark:text-amber-300 border-t border-slate-200 dark:border-slate-700 bg-amber-50 dark:bg-amber-900/20"
        >
          先頭 {{ MAX_RESULTS }} 件のみ表示中。絞り込んでください。
        </p>
      </div>
    </Teleport>
  </div>
</template>
