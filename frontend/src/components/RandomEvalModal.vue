<script setup lang="ts">
/**
 * 【コンポーネントの役割】 RANDOM の配置評価の評価表（減点の形ごとの該当ノーツ数）と評価基準の説明を出すモーダル。
 *
 * RandomPanel の「評価表を見る」から開く。並べる並び（1 位・今の並び・正規・MIRROR・R-RANDOM 最良）は呼び出し側が決める。
 * どの並びでも 0 の形（連皿・CN の無い譜面など）は行を出さない。
 */
import { computed } from 'vue';
import { PENALTY_KEYS, PENALTY_LABELS, type RandomCandidate } from '../utils/randomEval';
import PatternChips from './PatternChips.vue';

const props = defineProps<{
  /** 表の列（見出しと並び） */
  columns: { label: string; cand: RandomCandidate }[];
  side: 1 | 2;
}>();
defineEmits<{ (e: 'close'): void }>();

const rows = computed(() => PENALTY_KEYS
  .map(key => {
    const values = props.columns.map(c => c.cand.metrics[key]);
    const best = Math.min(...values);
    return { key, label: PENALTY_LABELS[key], values, best };
  })
  .filter(r => r.values.some(v => v > 0)));
const bestTotal = computed(() => Math.min(...props.columns.map(c => c.cand.score)));
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[110] flex items-center justify-center p-4 animate-fade-in">
      <div class="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" @click="$emit('close')"></div>
      <div class="relative w-full max-w-3xl max-h-[85vh] flex flex-col bg-white dark:bg-slate-800 rounded-2xl shadow-2xl overflow-hidden">
        <div class="px-5 py-4 border-b border-slate-100 dark:border-slate-700/50 flex justify-between items-center">
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-100">RANDOM の評価表</h3>
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

        <div class="flex-1 overflow-y-auto custom-scrollbar px-5 py-4 space-y-5 text-sm text-slate-700 dark:text-slate-300">
          <section>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-2">
              押しにくい形（減点の形）に当たるノーツの数です。合計が少ないほど当たり（{{ side }}P・5,040 通り中の順位）。
              各行で一番少ない数を太字にしています。
            </p>
            <div class="overflow-x-auto">
              <table class="eval-table tabular-nums">
                <thead>
                  <tr>
                    <th>減点の形</th>
                    <th v-for="c in columns" :key="c.cand.pattern">
                      <div class="col-head">
                        <span class="col-label">{{ c.label }}</span>
                        <PatternChips class="col-chips" :pattern="c.cand.pattern" small />
                        <span class="col-digits">{{ c.cand.pattern }}</span>
                        <span class="col-rank">{{ c.cand.rank }} 位</span>
                      </div>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="r in rows" :key="r.key">
                    <td>{{ r.label }}</td>
                    <td v-for="(v, n) in r.values" :key="n" :class="{ best: v === r.best }">{{ v }}</td>
                  </tr>
                  <tr class="total">
                    <td>合計</td>
                    <td v-for="c in columns" :key="c.cand.pattern" :class="{ best: c.cand.score === bestTotal }">{{ c.cand.score }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <section class="text-xs leading-relaxed space-y-2">
            <h4 class="font-bold text-slate-900 dark:text-white">評価基準</h4>
            <p>
              重み付けはせず、1 つのノーツが複数の形に当たれば形ごとに数えます。RANDOM は鍵盤をレーンごと入れ替えるので、縦連打はどの並びでも同じです。
              BPM 150 より速い譜面は、音符の長さではなく実際の速さを BPM 150 に換算して見ます（MAX 300 の 8 分は 16 分扱い）。
              皿側の手が {{ side === 1 ? '1〜3' : '5〜7' }}、もう一方の手が {{ side === 1 ? '4〜7' : '1〜4' }} レーンを押す前提です。
            </p>
            <dl class="criteria-list">
              <dt>皿と同時なのに逆の手</dt><dd>単発の皿と同じタイミングの鍵盤が、皿を回さない方の手に来た数</dd>
              <dt>連皿中に皿側の手</dt><dd>連続スクラッチ（BPM 140 の 16 分以上の間隔で 3 回以上続く皿）の最中の鍵盤が、皿側の手に来た数</dd>
              <dt>皿の前後に皿側の手</dt><dd>皿と同時ではないが前後 0.1 秒に皿がある鍵盤が、皿側の手に来た数（連皿の最中は除く）</dd>
              <dt>16 分が割れない</dt><dd>16 分以上の速さで続く 2 つの打鍵で、後ろの打鍵のうち前の打鍵と同じ手に来た鍵盤の数（一時的な階段は除く＝片手の速い移動と同じ。BPM 150 換算で 24 分以上の速さは、同じ手が 3 打以上続いたときだけ数える＝片手ずつ 2 打ずつは数えない）</dd>
              <dt>片手の速い移動</dt><dd>同じ手で 0.105 秒未満に続く打鍵で、新しく別のレーンを押した鍵盤の数（同じレーンの縦連打と、階段＝同じ手の単打が隣のレーンへ同じ向きに 3 打以上続く指の転がしは数えない。ただし 16 分以上で途切れない流れの中で、階段のノーツが 7 打を超えた分＝繰り返す階段は数える）</dd>
              <dt>トリル</dt><dd>6⇔7・1⇔3 の単打の交互、47⇔56 の交互、BPM 180 の 16 分より速い親指（レーン 5）の交互が 3 打鍵以上続いたときの、3 打鍵目以降の鍵盤の数（片手の速い移動にも入る）</dd>
              <dt>折り返し階段</dt><dd>同じ手で 3 打以上の階段がすぐ隣のレーンへ引き返す箇所（例: 3-2-1-2）の、引き返す点とその前後の 3 打（2-1-2。BPM 150 換算で 24 分以上の速さのときだけ。階段そのものは数えない）</dd>
              <dt>16 分縦連の衝突</dt><dd>1 秒ごとに、16 分縦連のある鍵盤と同じ手に来た別の連打鍵盤（一緒に押す和音を除く）の打鍵の数</dd>
              <dt>CN 押しっぱなし中の同じ手</dt><dd>1P でレーン 3・4・6 の CN を押している間に、同じ手に来るほかの鍵盤（レーン 3 の CN なら皿も）の数</dd>
            </dl>
            <p>
              1P は運指上やりやすい形を数えません: 皿を回さない手の親指（レーン 5）だけの打鍵と親指以外の打鍵の交互（BPM 180 の 16 分まで。それより速いと数える）、2 鍵ずつの交互 45⇔67・57⇔46・47⇔56（47⇔56 は 3 打鍵目から数える）、
              皿側の手の交互 13⇔2・1⇔23・12⇔3。これらは 16 分の割れでも割れたものとして扱います（2P は 1P を左右反転して数えます）。
              片手で自分のレーンの鍵盤をまとめて押す和音（白黒分けの 246 など）は普通の押し方なので数えません。押しやすさの目安です。
            </p>
          </section>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.eval-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.eval-table th, .eval-table td { padding: 0.35rem 0.5rem; border-bottom: 1px solid rgb(226 232 240); text-align: right; white-space: nowrap; }
.eval-table th { font-weight: 600; color: rgb(100 116 139); vertical-align: bottom; }
/* 1 列目（減点の形）は左寄せ・折り返し可 */
.eval-table th:first-child, .eval-table td:first-child { text-align: left; white-space: normal; }
.col-digits { display: none; font-weight: 700; letter-spacing: 0.04em; color: rgb(51 65 85); }
.dark .col-digits { color: rgb(226 232 240); }
/* スマホ幅: 鍵盤チップの代わりに数字の並びを出し、余白と文字を詰めて 5 列を画面に収める */
@media (max-width: 519px) {
  .eval-table { font-size: 11px; }
  .eval-table th, .eval-table td { padding: 0.3rem 0.2rem; }
  .eval-table th:first-child, .eval-table td:first-child { min-width: 5.5rem; }
  .col-chips { display: none; }
  .col-digits { display: inline; font-size: 10px; letter-spacing: 0; }
  .col-label { white-space: normal; text-align: right; }
}
.eval-table td.best { font-weight: 800; color: rgb(37 99 235); }
.eval-table tr.total td { font-weight: 700; color: rgb(30 41 59); border-bottom: none; }
.eval-table tr.total td.best { color: rgb(37 99 235); }
.dark .eval-table th, .dark .eval-table td { border-bottom-color: rgb(51 65 85); }
.dark .eval-table td.best, .dark .eval-table tr.total td.best { color: rgb(147 197 253); }
.dark .eval-table tr.total td { color: rgb(241 245 249); }
.col-head { display: flex; flex-direction: column; align-items: flex-end; gap: 0.2rem; }
.col-label { font-weight: 700; color: rgb(51 65 85); }
.dark .col-label { color: rgb(226 232 240); }
.col-rank { font-size: 10px; font-weight: 600; color: rgb(148 163 184); }
.criteria-list { display: grid; grid-template-columns: max-content 1fr; gap: 0.3rem 0.75rem; }
.criteria-list dt { font-weight: 700; color: rgb(51 65 85); }
.criteria-list dd { margin: 0; color: rgb(100 116 139); }
.dark .criteria-list dt { color: rgb(226 232 240); }
.dark .criteria-list dd { color: rgb(148 163 184); }
@media (max-width: 519px) {
  .criteria-list { grid-template-columns: 1fr; gap: 0.1rem; }
  .criteria-list dd { margin-bottom: 0.4rem; }
}
</style>
