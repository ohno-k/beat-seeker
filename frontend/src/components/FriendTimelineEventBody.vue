<script setup lang="ts">
/**
 * 【コンポーネントの役割】 タイムラインの 1 イベント本文（バッジ・時刻は含まない）を描画する。
 *
 * 親 ({@link FriendTimeline.vue}) でスレッド構造を組んだ後、その内側の本文だけをここに任せる。
 * 種別 ({@code entry.type}) と「自分視点」({@code viewerRel}) に応じて
 *  - SCORE_UPDATE : 更新譜面の上位リスト（折りたたみ可）
 *  - OVERTAKE_SONG: 譜面別の抜き／抜かれ
 *  - OVERTAKE_TOTAL: 総合 BEAT-PT の抜き／抜かれ
 * を切り替えて表示する。
 *
 * props:
 *  - entry         : 表示対象のタイムラインイベント
 *  - viewerRel     : 親側で算出済みの「自分視点」関係性
 *  - expandedSongs : SCORE_UPDATE の譜面リストを全展開しているか
 *
 * emits:
 *  - toggle-songs : SCORE_UPDATE 内の「ほか N 譜面を表示／折りたたむ」ボタン押下
 */
import { computed } from 'vue';
import type {
  TimelineEntry,
  TimelineUpdatedSong,
  OvertakeSongPayload,
  OvertakeTotalPayload,
} from '../composables/useTimeline';

type ViewerRelation = 'OVERTAKE_BY_ME' | 'OVERTAKEN_BY_OTHER' | 'BETWEEN_OTHERS' | null;

const props = defineProps<{
  entry: TimelineEntry;
  viewerRel: ViewerRelation;
  expandedSongs: boolean;
}>();

defineEmits<{ (e: 'toggle-songs'): void }>();

/** SCORE_UPDATE の折りたたみ時に見せる先頭件数。 */
const PREVIEW_SONG_LIMIT = 1;

/** SCORE_UPDATE payload を「スコア増加量降順」に整形した配列。 */
const sortedUpdatedSongs = computed<TimelineUpdatedSong[]>(() => {
  const p = props.entry.payload;
  if (!Array.isArray(p)) return [];
  return [...p].sort((a, b) => {
    const aPt = a.beatPtIncrease ?? 0;
    const bPt = b.beatPtIncrease ?? 0;
    if (bPt !== aPt) return bPt - aPt;
    return (b.scoreIncrease ?? 0) - (a.scoreIncrease ?? 0);
  });
});

/** OVERTAKE_SONG payload 型ガード。 */
const asSong = computed<OvertakeSongPayload | null>(() => {
  const p = props.entry.payload;
  return p && !Array.isArray(p) && 'title' in p ? (p as OvertakeSongPayload) : null;
});

/** OVERTAKE_TOTAL payload 型ガード。 */
const asTotal = computed<OvertakeTotalPayload | null>(() => {
  const p = props.entry.payload;
  return p && !Array.isArray(p) && 'rivalBeatPt' in p ? (p as OvertakeTotalPayload) : null;
});

/** 小数 1 桁固定（BEAT-PT 用）。 */
const fmtPt = (n: number) => n.toFixed(1);
</script>

<template>
  <!-- SCORE_UPDATE: スコア更新譜面のリスト -->
  <template v-if="entry.type === 'SCORE_UPDATE'">
    <p class="text-sm text-slate-700 dark:text-slate-300 mb-2">
      <span class="font-bold">{{ sortedUpdatedSongs.length }}</span> 譜面でスコアを更新
    </p>
    <ul class="space-y-1.5">
      <li
        v-for="(s, idx) in (expandedSongs ? sortedUpdatedSongs : sortedUpdatedSongs.slice(0, PREVIEW_SONG_LIMIT))"
        :key="idx"
        class="text-xs flex flex-wrap items-baseline gap-x-2 gap-y-0.5 bg-slate-50 dark:bg-slate-900/40 rounded-lg px-3 py-2"
      >
        <span class="font-bold text-slate-800 dark:text-slate-100 truncate max-w-[15rem]">{{ s.title }}</span>
        <span class="text-[10px] font-bold text-slate-500 dark:text-slate-400">{{ s.difficulty }}</span>
        <span class="text-slate-600 dark:text-slate-300">
          {{ s.oldScore.toLocaleString() }}
          <span class="text-slate-400 mx-1">→</span>
          <span class="font-bold text-emerald-600 dark:text-emerald-400">{{ s.newScore.toLocaleString() }}</span>
          <span class="text-emerald-600 dark:text-emerald-400 font-bold ml-1">(+{{ s.scoreIncrease.toLocaleString() }})</span>
        </span>
        <span v-if="s.clearTypeImproved && s.newClearType" class="text-[10px] font-bold text-amber-600 dark:text-amber-400">
          → {{ s.newClearType }}
        </span>
        <span v-if="s.beatPtIncrease && s.beatPtIncrease > 0" class="text-[10px] font-bold text-blue-600 dark:text-blue-400">
          +{{ fmtPt(s.beatPtIncrease) }} BEAT-PT
        </span>
        <span v-if="s.songRank && s.songRankTotal" class="text-[10px] text-slate-400 dark:text-slate-500">
          ({{ s.songRank }}/{{ s.songRankTotal }} 位)
        </span>
      </li>
    </ul>
    <button
      v-if="sortedUpdatedSongs.length > PREVIEW_SONG_LIMIT"
      type="button"
      @click="$emit('toggle-songs')"
      class="mt-1.5 ml-1 text-[11px] font-bold text-blue-600 dark:text-blue-400 hover:text-blue-700 dark:hover:text-blue-300 hover:underline focus:outline-none"
    >
      <template v-if="expandedSongs">折りたたむ</template>
      <template v-else>ほか {{ sortedUpdatedSongs.length - PREVIEW_SONG_LIMIT }} 譜面を表示</template>
    </button>
  </template>

  <!-- OVERTAKE_SONG: 譜面単位の抜き／抜かれ -->
  <template v-else-if="entry.type === 'OVERTAKE_SONG' && asSong">
    <p v-if="viewerRel === 'OVERTAKEN_BY_OTHER'" class="text-sm text-slate-700 dark:text-slate-300">
      <span class="font-bold">{{ entry.displayName }}</span>
      に <span class="font-bold text-rose-600 dark:text-rose-400">あなた</span> のスコアを抜かれました
    </p>
    <p v-else-if="viewerRel === 'OVERTAKE_BY_ME'" class="text-sm text-slate-700 dark:text-slate-300">
      <span class="font-bold">{{ asSong.rivalName }}</span>
      <span v-if="asSong.isVirtual" class="text-[10px] bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300 px-1.5 py-0.5 rounded ml-1">仮想</span>
      のスコアを抜きました
    </p>
    <p v-else class="text-sm text-slate-700 dark:text-slate-300">
      <span class="font-bold">{{ entry.displayName }}</span>
      が
      <span class="font-bold">{{ asSong.rivalName }}</span>
      <span v-if="asSong.isVirtual" class="text-[10px] bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300 px-1.5 py-0.5 rounded ml-1">仮想</span>
      のスコアを抜きました
    </p>
    <div
      class="mt-2 text-xs rounded-lg px-3 py-2 flex flex-wrap items-baseline gap-x-2 gap-y-0.5"
      :class="viewerRel === 'OVERTAKEN_BY_OTHER'
        ? 'bg-rose-50 dark:bg-rose-950/30 border border-rose-200 dark:border-rose-800/50'
        : viewerRel === 'OVERTAKE_BY_ME'
          ? 'bg-amber-50 dark:bg-amber-950/30 border border-amber-200 dark:border-amber-800/50'
          : 'bg-slate-50 dark:bg-slate-900/40 border border-slate-200 dark:border-slate-700'"
    >
      <span class="font-bold text-slate-800 dark:text-slate-100 truncate max-w-[15rem]">{{ asSong.title }}</span>
      <span class="text-[10px] font-bold text-slate-500 dark:text-slate-400">{{ asSong.difficulty }}</span>
      <template v-if="viewerRel === 'OVERTAKEN_BY_OTHER'">
        <span class="text-slate-600 dark:text-slate-300 ml-1">
          あなた <span class="font-bold">{{ asSong.rivalScore.toLocaleString() }}</span>
        </span>
        <span class="text-slate-600 dark:text-slate-300">
          相手 {{ asSong.myOldScore.toLocaleString() }}
          <span class="text-slate-400 mx-1">→</span>
          <span class="font-bold text-rose-600 dark:text-rose-400">{{ asSong.myNewScore.toLocaleString() }}</span>
        </span>
        <span class="text-rose-600 dark:text-rose-400 font-bold ml-1">
          -{{ (asSong.myNewScore - asSong.rivalScore).toLocaleString() }} 点差
        </span>
      </template>
      <template v-else>
        <span class="text-slate-600 dark:text-slate-300 ml-1">
          相手 <span class="font-bold">{{ asSong.rivalScore.toLocaleString() }}</span>
        </span>
        <span class="text-slate-600 dark:text-slate-300">
          {{ viewerRel === 'OVERTAKE_BY_ME' ? '自分' : entry.displayName }}
          {{ asSong.myOldScore.toLocaleString() }}
          <span class="text-slate-400 mx-1">→</span>
          <span class="font-bold text-amber-600 dark:text-amber-400">{{ asSong.myNewScore.toLocaleString() }}</span>
        </span>
        <span class="text-amber-600 dark:text-amber-400 font-bold ml-1">
          +{{ (asSong.myNewScore - asSong.rivalScore).toLocaleString() }} 点差
        </span>
      </template>
    </div>
  </template>

  <!-- OVERTAKE_TOTAL: 総合 BEAT-PT の抜き／抜かれ -->
  <template v-else-if="entry.type === 'OVERTAKE_TOTAL' && asTotal">
    <p v-if="viewerRel === 'OVERTAKEN_BY_OTHER'" class="text-sm text-slate-700 dark:text-slate-300">
      <span class="font-bold">{{ entry.displayName }}</span>
      に <span class="font-bold text-rose-600 dark:text-rose-400">あなた</span> の総合 BEAT-PT を抜かれました
    </p>
    <p v-else-if="viewerRel === 'OVERTAKE_BY_ME'" class="text-sm text-slate-700 dark:text-slate-300">
      <span class="font-bold">{{ asTotal.rivalName }}</span>
      <span v-if="asTotal.isVirtual" class="text-[10px] bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300 px-1.5 py-0.5 rounded ml-1">仮想</span>
      を総合 BEAT-PT で抜きました
    </p>
    <p v-else class="text-sm text-slate-700 dark:text-slate-300">
      <span class="font-bold">{{ entry.displayName }}</span>
      が
      <span class="font-bold">{{ asTotal.rivalName }}</span>
      <span v-if="asTotal.isVirtual" class="text-[10px] bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300 px-1.5 py-0.5 rounded ml-1">仮想</span>
      を総合 BEAT-PT で抜きました
    </p>
    <div
      class="mt-2 text-xs rounded-lg px-3 py-2 flex flex-wrap items-baseline gap-x-2 gap-y-0.5"
      :class="viewerRel === 'OVERTAKEN_BY_OTHER'
        ? 'bg-rose-50 dark:bg-rose-950/30 border border-rose-200 dark:border-rose-800/50'
        : viewerRel === 'OVERTAKE_BY_ME'
          ? 'bg-amber-50 dark:bg-amber-950/30 border border-amber-200 dark:border-amber-800/50'
          : 'bg-slate-50 dark:bg-slate-900/40 border border-slate-200 dark:border-slate-700'"
    >
      <template v-if="viewerRel === 'OVERTAKEN_BY_OTHER'">
        <span class="text-slate-600 dark:text-slate-300">
          あなた <span class="font-bold">{{ fmtPt(asTotal.rivalBeatPt) }} pt</span>
        </span>
        <span class="text-slate-600 dark:text-slate-300">
          相手 {{ fmtPt(asTotal.myOldBeatPt) }}
          <span class="text-slate-400 mx-1">→</span>
          <span class="font-bold text-rose-600 dark:text-rose-400">{{ fmtPt(asTotal.myNewBeatPt) }} pt</span>
        </span>
        <span class="text-rose-600 dark:text-rose-400 font-bold ml-1">
          -{{ fmtPt(asTotal.myNewBeatPt - asTotal.rivalBeatPt) }} pt 差
        </span>
      </template>
      <template v-else>
        <span class="text-slate-600 dark:text-slate-300">
          相手 <span class="font-bold">{{ fmtPt(asTotal.rivalBeatPt) }} pt</span>
        </span>
        <span class="text-slate-600 dark:text-slate-300">
          {{ viewerRel === 'OVERTAKE_BY_ME' ? '自分' : entry.displayName }}
          {{ fmtPt(asTotal.myOldBeatPt) }}
          <span class="text-slate-400 mx-1">→</span>
          <span class="font-bold text-amber-600 dark:text-amber-400">{{ fmtPt(asTotal.myNewBeatPt) }} pt</span>
        </span>
        <span class="text-amber-600 dark:text-amber-400 font-bold ml-1">
          +{{ fmtPt(asTotal.myNewBeatPt - asTotal.rivalBeatPt) }} pt 差
        </span>
      </template>
    </div>
  </template>
</template>
