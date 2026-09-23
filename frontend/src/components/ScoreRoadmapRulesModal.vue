<script setup lang="ts">
/**
 * 【コンポーネントの役割】 スコアロードマップのルール説明（図解）モーダル。
 *
 * ページ上部の長い説明文をここへ移した（2026-09-23 ユーザー要望）。判定の数字は
 * utils/roadmapLevels.ts（minPlayedFor・3 分の 2）と必ず揃える。
 */
import { formatJstDateTime } from '../utils/jstTime';

defineProps<{
  /** レベル表に入れる最低プレー人数。 */
  minPlayers: number;
  revision: number | null;
  frozenAt: string | null;
}>();
defineEmits<{ (e: 'close'): void }>();

/** 図の目標の状態: done = 達成、played = プレー済みだが未達成、none = 未プレー。 */
type Mark = 'done' | 'played' | 'none';
const EXAMPLES: { title: string; marks: Mark[]; result: string; tone: 'ok' | 'ng' | 'skip'; note: string }[] = [
  { title: '例 1', marks: ['done', 'done', 'done', 'played', 'none', 'none'], result: 'レベル達成', tone: 'ok',
    note: 'プレー済み 4 件のうち 3 件達成（3 分の 2 以上）' },
  { title: '例 2', marks: ['done', 'played', 'played', 'none', 'none', 'none'], result: '未達成', tone: 'ng',
    note: 'プレー済み 3 件のうち 1 件（3 分の 2 に届かない）' },
  { title: '例 3', marks: ['done', 'none', 'none', 'none', 'none', 'none'], result: '判定なし', tone: 'skip',
    note: 'プレー済みが 1 件だけ（6 目標のレベルは 2 件以上必要）' },
];
/** 条件 ① の早見表: 目標数 → 必要なプレー済み数（min(n, max(2, ⌈n/3⌉))）。 */
const MIN_PLAYED_TABLE: [string, number][] = [['1', 1], ['2', 2], ['3〜6', 2], ['7〜9', 3], ['10〜12', 4]];
/** 「あなたのレベル」の図: 達成レベルの一番上。途中に未達成があってもよい。 */
const LADDER: boolean[] = [true, true, false, true, true, false, false];
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[110] flex items-center justify-center p-4 animate-fade-in">
      <div class="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" @click="$emit('close')"></div>
      <div class="relative w-full max-w-2xl max-h-[85vh] flex flex-col bg-white dark:bg-slate-800 rounded-2xl shadow-2xl overflow-hidden">
        <div class="px-6 py-4 border-b border-slate-100 dark:border-slate-700/50 flex justify-between items-center">
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-100">スコアロードマップのルール</h3>
          <button
            class="p-2 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-full transition-all"
            aria-label="閉じる"
            @click="$emit('close')"
          >
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div class="flex-1 overflow-y-auto custom-scrollbar px-6 py-5 space-y-7 text-sm text-slate-700 dark:text-slate-300">
          <!-- 1. 目標とレベル -->
          <section>
            <h4 class="font-bold text-slate-900 dark:text-white mb-2">1. 目標とレベル</h4>
            <p class="mb-3">1 つの譜面に「AA」「AAA」「MAX-」の 3 つの目標があります。全目標を達成に必要な実力の順に並べ、難度 0.02 ごとに区切ったものがレベルです。</p>
            <div class="rounded-lg border border-slate-200 dark:border-slate-700 p-3">
              <div class="flex items-center gap-2 text-[11px] text-slate-500 mb-2">
                <span>易しい</span>
                <span class="flex-1 h-px bg-gradient-to-r from-slate-300 to-slate-500 dark:from-slate-600 dark:to-slate-400"></span>
                <span>難しい</span>
              </div>
              <div class="grid grid-cols-3 gap-2">
                <div v-for="(lv, i) in [
                  [['AAA', '曲A'], ['AAA', '曲B']],
                  [['MAX-', '曲A'], ['AAA', '曲C']],
                  [['MAX-', '曲B'], ['MAX-', '曲C']],
                ]" :key="i" class="rounded-md bg-slate-50 dark:bg-slate-900/50 p-2">
                  <div class="text-xs font-bold font-mono text-slate-900 dark:text-white mb-1">Lv.{{ i + 1 }}</div>
                  <div v-for="t in lv" :key="t.join()" class="flex items-center gap-1 text-xs py-0.5">
                    <span
                      class="text-[10px] font-bold font-mono px-1 rounded w-10 text-center"
                      :class="t[0] === 'MAX-' ? 'bg-slate-800 text-white dark:bg-slate-200 dark:text-slate-900' : 'bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-200'"
                    >{{ t[0] }}</span>
                    <span>{{ t[1] }}</span>
                  </div>
                </div>
              </div>
              <p class="text-[11px] text-slate-500 mt-2">同じ曲でも AA・AAA・MAX- は別のレベルに入ります（AA → AAA → MAX- の順に上）。</p>
              <p class="text-[11px] text-slate-500 mt-1">Lv.1 より易しい AA の目標は、Lv.0・Lv.-1・Lv.-2 … と下に続くレベルに入ります。</p>
            </div>
          </section>

          <!-- 2. レベル達成 -->
          <section>
            <h4 class="font-bold text-slate-900 dark:text-white mb-2">2. レベル達成の条件</h4>
            <div class="grid gap-2 mb-3" style="grid-template-columns: repeat(auto-fit, minmax(220px, 1fr))">
              <div class="rounded-md bg-slate-50 dark:bg-slate-900/50 p-3">
                <div class="text-xs font-bold text-slate-500 mb-1">条件 ①　遊んだ数</div>
                <div>そのレベルの目標の <b>3 分の 1 以上</b>、かつ <b>2 件以上</b>をプレー済み</div>
                <div class="text-[11px] text-slate-500 mt-1">目標が 1 件だけのレベルは 1 件で OK。目標が 2 件のレベルは 2 件ともプレーが必要</div>
                <table class="mt-2 text-[11px] font-mono text-slate-600 dark:text-slate-300">
                  <tr>
                    <th class="pr-2 text-left font-normal text-slate-500">目標数</th>
                    <td v-for="r in MIN_PLAYED_TABLE" :key="r[0]" class="px-1.5 text-center">{{ r[0] }}</td>
                  </tr>
                  <tr>
                    <th class="pr-2 text-left font-normal text-slate-500">必要なプレー</th>
                    <td v-for="r in MIN_PLAYED_TABLE" :key="r[0]" class="px-1.5 text-center font-bold">{{ r[1] }}</td>
                  </tr>
                </table>
                <div class="text-[11px] text-slate-500 mt-1">以降も目標 3 件ごとに 1 件ずつ増えます</div>
              </div>
              <div class="rounded-md bg-slate-50 dark:bg-slate-900/50 p-3">
                <div class="text-xs font-bold text-slate-500 mb-1">条件 ②　達成した数</div>
                <div>プレー済みの目標のうち <b>3 分の 2 以上</b>を達成</div>
                <div class="text-[11px] text-slate-500 mt-1">未プレーの目標は数えない</div>
              </div>
            </div>
            <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px] text-slate-500 mb-2">
              <span class="inline-flex items-center gap-1"><span class="inline-block w-3.5 h-3.5 rounded bg-blue-600 dark:bg-blue-400"></span>達成</span>
              <span class="inline-flex items-center gap-1"><span class="inline-block w-3.5 h-3.5 rounded border-2 border-slate-400"></span>プレー済み・未達成</span>
              <span class="inline-flex items-center gap-1"><span class="inline-block w-3.5 h-3.5 rounded border border-dashed border-slate-300 dark:border-slate-600"></span>未プレー</span>
            </div>
            <div class="space-y-2">
              <div v-for="ex in EXAMPLES" :key="ex.title" class="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border border-slate-200 dark:border-slate-700 px-3 py-2">
                <span class="text-xs text-slate-500 w-8">{{ ex.title }}</span>
                <span class="flex gap-1">
                  <span
                    v-for="(m, mi) in ex.marks"
                    :key="mi"
                    class="inline-block w-5 h-5 rounded"
                    :class="m === 'done' ? 'bg-blue-600 dark:bg-blue-400' : m === 'played' ? 'border-2 border-slate-400' : 'border border-dashed border-slate-300 dark:border-slate-600'"
                  ></span>
                </span>
                <span
                  class="text-xs font-bold w-20"
                  :class="ex.tone === 'ok' ? 'text-blue-700 dark:text-blue-300' : ex.tone === 'ng' ? 'text-rose-600 dark:text-rose-400' : 'text-slate-500'"
                >→ {{ ex.result }}</span>
                <span class="text-xs text-slate-500">{{ ex.note }}</span>
              </div>
            </div>
          </section>

          <!-- 3. 完全制覇 -->
          <section>
            <h4 class="font-bold text-slate-900 dark:text-white mb-2">3. 完全制覇</h4>
            <div class="flex flex-wrap items-center gap-3">
              <span class="flex gap-1">
                <span v-for="i in 6" :key="i" class="inline-block w-5 h-5 rounded bg-amber-400 dark:bg-amber-500"></span>
              </span>
              <span class="inline-flex items-center gap-1 text-xs font-bold text-amber-600 dark:text-amber-400">
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4"><path d="M9.05 2.93c.3-.92 1.6-.92 1.9 0l1.52 4.67a1 1 0 00.95.69h4.91c.97 0 1.37 1.24.59 1.81l-3.98 2.89a1 1 0 00-.36 1.12l1.52 4.67c.3.92-.76 1.69-1.54 1.12l-3.97-2.89a1 1 0 00-1.18 0l-3.97 2.89c-.78.57-1.84-.2-1.54-1.12l1.52-4.67a1 1 0 00-.36-1.12L1.08 10.1c-.78-.57-.38-1.81.59-1.81h4.91a1 1 0 00.95-.69l1.52-4.67z" /></svg>
                完全制覇
              </span>
              <span class="text-xs text-slate-500">未プレーも含めて、そのレベルの目標を全部達成</span>
            </div>
          </section>

          <!-- 4. あなたのレベル -->
          <section>
            <h4 class="font-bold text-slate-900 dark:text-white mb-2">4. あなたのレベル</h4>
            <p class="mb-3">達成したレベルのうち、<b>一番高い番号</b>があなたのレベルです。途中に未達成のレベルがあっても構いません。</p>
            <div class="flex items-end gap-1.5">
              <div v-for="(ok, i) in LADDER" :key="i" class="flex flex-col items-center gap-1">
                <span
                  class="text-[10px] font-bold h-4"
                  :class="i === LADDER.lastIndexOf(true) ? 'text-blue-700 dark:text-blue-300' : 'text-transparent'"
                >あなた</span>
                <span
                  class="w-10 rounded-t"
                  :style="{ height: `${16 + i * 6}px` }"
                  :class="ok ? 'bg-blue-600 dark:bg-blue-400' : 'bg-slate-200 dark:bg-slate-700'"
                ></span>
                <span class="text-[10px] font-mono text-slate-500">Lv.{{ i + 1 }}</span>
              </div>
            </div>
            <p class="text-[11px] text-slate-500 mt-2">この例では Lv.3 が未達成でも、Lv.5 を達成しているので「Lv.5」。判定は自己歴代ベスト（前作までのスコアを含む）で行います。</p>
          </section>

          <!-- 5. レベル表 -->
          <section>
            <h4 class="font-bold text-slate-900 dark:text-white mb-2">5. レベル表について</h4>
            <ul class="list-disc pl-5 space-y-1">
              <li>
                各レベルの課題曲は固定です。難度の集計（3 時間ごと）で数値が動いても、入れ替わりません。
                <span v-if="revision" class="text-slate-500">（現在は第{{ revision }}版・{{ formatJstDateTime(frozenAt) }} に固定）</span>
              </li>
              <li>プレー人数 {{ minPlayers }} 人以上の譜面だけが入ります。後から {{ minPlayers }} 人に達した譜面は、一番近いレベルに追加されます。</li>
              <li>譜面の右に出る難度は最新の集計値です。</li>
              <li>上部の絞り込み（AA / AAA / MAX-・☆・未達成だけ）は表示を変えるだけで、レベルの番号や判定は変わりません。</li>
            </ul>
          </section>
        </div>
      </div>
    </div>
  </Teleport>
</template>
