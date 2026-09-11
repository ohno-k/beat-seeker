<script setup lang="ts">
/**
 * 【コンポーネントの役割】 リーグモードのルール説明モーダル。
 *
 * LeagueView のトップ（タイトル横の「ルール説明」ボタン）から開く。
 * 各セクションは「短い文 + 図」で構成する:
 *   概要（流れ図）/ スケジュール（タイムライン）/ DIVISION と課題曲の難易度帯（レンジ図）/
 *   有効な記録＝ライン（ライン図）/ 順位の決め方＝着順ポイント（計算例）/
 *   課題曲をプレーしなかった場合（3 分岐図）/ 昇格・降格＝PT（順位→増減・目盛）
 * 文言はすべて i18n（league.infoModal.*）で ja/en/ko に対応する。
 * リーグはスコアリーグのみ（BP リーグは廃止済み）なので、BP に関する記述は置かない。
 */
import { ref, onMounted } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useAuth } from '../composables/useAuth';
import { useLeague } from '../composables/useLeague';
import DivisionIcon from './DivisionIcon.vue';

const emit = defineEmits<{ (e: 'close'): void; (e: 'joined'): void }>();

const { t } = useI18n();
const { isLoggedIn } = useAuth();
const league = useLeague();

/** すでにスコアリーグに参加登録済みか。 */
const joined = ref(false);
/** 参加リクエスト送信中フラグ。 */
const joining = ref(false);
/** 参加失敗時のエラーメッセージ。 */
const joinError = ref('');

// モーダルを開いた時点の参加状態を取得（ログイン時のみ）。取得失敗しても説明表示は続ける。
onMounted(async () => {
  if (!isLoggedIn.value) return;
  try {
    const entries = await league.fetchMe();
    joined.value = entries.some((e) => e.ladderType === 'score' && e.active);
  } catch {
    /* 参加状態が取れなくても無視（ボタンは押せる状態のまま） */
  }
});

/** リーグ（スコア）に参加登録する。成功したら参加済み表示に切り替え、親に通知する。 */
const doJoin = async () => {
  if (joining.value || joined.value || !isLoggedIn.value) return;
  joining.value = true;
  joinError.value = '';
  try {
    await league.join('score');
    joined.value = true;
    emit('joined');
  } catch (e: any) {
    joinError.value = e?.message || '参加に失敗しました';
  } finally {
    joining.value = false;
  }
};

/** DIVISION の表示名（0 = LEGEND）。 */
const divisionName = (tier: number) =>
  tier === 0 ? t('league.divisionLegend') : t('league.divisionN', { n: tier });

// ─── 概要: 流れ図 ─────────────────────────────────────────────
/** 「同実力の8人 → 課題曲3曲 → 週の順位 → 昇格/降格」の 4 ステップ。 */
const flowSteps = [
  'league.infoModal.flow.group',
  'league.infoModal.flow.songs',
  'league.infoModal.flow.rank',
  'league.infoModal.flow.move',
];

// ─── DIVISION: 難易度帯レンジ図 ───────────────────────────────
/**
 * DIVISION ごとの「初回配属 BEAT-TIER」と「課題曲の難易度帯（非公式難易度 ☆・0.1 単位の整数）」。
 * バックエンドの LeagueDivision.forBeatPt のしきい値、および
 * LeagueSongDrawService.rankBandTenths と揃えること。
 * ティア名は言語共通の英語表記のため i18n しない（最下段の「以下」のみ翻訳）。
 * open = 上限なし（LEGEND は ☆12.5 以上）。難易度表の最大は ☆13.0。
 */
const divisions: { tier: number; beatTier: string | null; lo: number; hi: number; open?: boolean }[] = [
  { tier: 0, beatTier: 'Legend 〜 Mythic 4', lo: 125, hi: 130, open: true },
  { tier: 1, beatTier: 'Mythic 3 〜 Mythic 1', lo: 123, hi: 127 },
  { tier: 2, beatTier: 'Ancient 5 〜 Ancient 2', lo: 121, hi: 125 },
  { tier: 3, beatTier: 'Ancient 1 〜 Master 4', lo: 119, hi: 123 },
  { tier: 4, beatTier: 'Master 3 〜 Elite 5', lo: 118, hi: 122 },
  { tier: 5, beatTier: 'Elite 4 〜 Elite 1', lo: 117, hi: 120 },
  { tier: 6, beatTier: 'Commander 5 〜 Commander 1', lo: 116, hi: 119 },
  { tier: 7, beatTier: 'Veteran 5 〜 Veteran 1', lo: 115, hi: 118 },
  { tier: 8, beatTier: 'Ace 5 〜 Expert 5', lo: 113, hi: 116 },
  { tier: 9, beatTier: 'Expert 4 〜 Advanced 3', lo: 111, hi: 114 },
  { tier: 10, beatTier: null, lo: 110, hi: 112 }, // beatTier は mappingLowest キーで表示
];

/** レンジ図の横軸: ☆11.0 〜 ☆13.0 を 0.1 刻みのセル（両端含む 21 セル）で表す。 */
const AXIS_MIN = 110;
const AXIS_MAX = 130;
const AXIS_CELLS = AXIS_MAX - AXIS_MIN + 1;
/** ☆x.y のセル左端位置(%)。目盛線・軸ラベルはここに置く。 */
const cellLeft = (tenths: number) => ((tenths - AXIS_MIN) / AXIS_CELLS) * 100;
/** 帯の幅(%)。lo〜hi を両端含むので、hi のセルの右端まで伸ばす。 */
const bandWidth = (lo: number, hi: number) => ((hi - lo + 1) / AXIS_CELLS) * 100;
/** 軸ラベルを出す目盛（☆0.5 刻み）。 */
const axisTicks = [110, 115, 120, 125, 130];
/** 0.1 単位の整数 → "☆12.5" 表記。 */
const fmtRank = (tenths: number) => `☆${(tenths / 10).toFixed(1)}`;
/** 帯のラベル（"☆12.3〜12.7" / LEGEND は "☆12.5 以上"）。 */
const rangeLabel = (d: { lo: number; hi: number; open?: boolean }) =>
  d.open
    ? t('league.infoModal.chart.andAbove', { rank: fmtRank(d.lo) })
    : `${fmtRank(d.lo)}〜${(d.hi / 10).toFixed(1)}`;
/** 軸ラベルの寄せ方: 左端は左寄せ・右端は右寄せ・それ以外は中央寄せ（はみ出し防止）。 */
const tickClass = (tick: number) =>
  tick === axisTicks[0] ? '' : tick === axisTicks[axisTicks.length - 1] ? '-translate-x-full' : '-translate-x-1/2';

/** 帯の色（DivisionIcon の中間色 / 縁色と揃える。index = tier）。 */
const BAND_COLORS = [
  { fill: '#FFD54A', border: '#E0A400' }, // LEGEND
  { fill: '#F5B000', border: '#B4780A' }, // 1
  { fill: '#FBBF24', border: '#D97706' }, // 2
  { fill: '#D3DCE6', border: '#94A3B8' }, // 3
  { fill: '#CBD5E1', border: '#8595A8' }, // 4
  { fill: '#CD7F32', border: '#8B4A1E' }, // 5
  { fill: '#B87333', border: '#764618' }, // 6
  { fill: '#9CA3AF', border: '#697180' }, // 7
  { fill: '#8C97A6', border: '#5B6472' }, // 8
  { fill: '#A8A29E', border: '#736A62' }, // 9
  { fill: '#B08D57', border: '#7A5C38' }, // 10
];

// ─── ライン図 ─────────────────────────────────────────────────
/** 例示の記録点。x は横位置(%)、valid = ライン超え（右側・緑）。 */
const linePoints = [
  { x: 18, valid: false },
  { x: 33, valid: false },
  { x: 50, valid: false }, // ライン上（＝ラインを作った過去ベスト）
  { x: 72, valid: true },
];

// ─── 着順ポイントの計算例（8 人グループ） ─────────────────────
const pointsExample = [
  { song: 1, rank: 2, pts: 7 },
  { song: 2, rank: 5, pts: 4 },
  { song: 3, rank: 1, pts: 8 },
];
const pointsTotal = pointsExample.reduce((s, e) => s + e.pts, 0);

// ─── 課題曲をプレーしなかった場合: 3 分岐 ───────────────────
const noPlayCases = [
  {
    key: 'valid',
    labelKey: 'league.infoModal.noPlay.validLabel',
    resultKey: 'league.infoModal.noPlay.validResult',
    icon: 'check',
    iconClass: 'bg-emerald-100 text-emerald-600 dark:bg-emerald-900/40 dark:text-emerald-400',
  },
  {
    key: 'played',
    labelKey: 'league.infoModal.noPlay.playedLabel',
    resultKey: 'league.infoModal.noPlay.playedResult',
    icon: 'minus',
    iconClass: 'bg-amber-100 text-amber-600 dark:bg-amber-900/40 dark:text-amber-400',
  },
  {
    key: 'absent',
    labelKey: 'league.infoModal.noPlay.absentLabel',
    resultKey: 'league.infoModal.noPlay.absentResult',
    icon: 'x',
    iconClass: 'bg-slate-200 text-slate-500 dark:bg-slate-700 dark:text-slate-400',
  },
];

// ─── PT 図 ────────────────────────────────────────────────────
/** 8 人グループの「順位 → 増減 PT」（バックエンド LeagueStandingsService.deltaForRank(8, rank) と一致）。 */
const weeklyDelta = Array.from({ length: 8 }, (_, i) => {
  const rank = i + 1;
  return { rank, delta: rank <= 4 ? 4 - rank + 1 : -(rank - 4) };
});
const fmtDelta = (d: number) => (d > 0 ? `+${d}` : `${d}`);

/** 共通スタイル。 */
const sectionTitleClass = 'text-sm font-bold text-slate-800 dark:text-slate-100 mb-2 flex items-center gap-2';
const leadClass = 'text-sm text-slate-600 dark:text-slate-300 leading-relaxed';
const noteClass = 'text-xs text-slate-500 dark:text-slate-400 leading-relaxed';
const figureClass = 'mt-3 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50/60 dark:bg-slate-900/30 p-3';
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[110] flex items-center justify-center p-4 animate-fade-in">
      <!-- 背景オーバーレイ（クリックで閉じる） -->
      <div class="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" @click="$emit('close')"></div>

      <!-- 本体パネル -->
      <div class="relative w-full max-w-2xl max-h-[85vh] flex flex-col bg-white dark:bg-slate-800 rounded-2xl shadow-2xl overflow-hidden transition-colors duration-200">
        <!-- ヘッダー -->
        <div class="px-6 py-4 border-b border-slate-100 dark:border-slate-700/50 flex justify-between items-center">
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-100">{{ t('league.infoModal.title') }}</h3>
          <button
            class="p-2 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-full transition-all"
            @click="$emit('close')"
          >
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- 本文（スクロール領域） -->
        <div class="flex-1 overflow-y-auto custom-scrollbar p-6 space-y-8">

          <!-- 1. 概要 + 流れ図 -->
          <section>
            <h4 :class="sectionTitleClass">
              <span class="w-1.5 h-5 bg-indigo-600 dark:bg-indigo-500 rounded-full"></span>
              {{ t('league.infoModal.overviewTitle') }}
            </h4>
            <p :class="leadClass">{{ t('league.infoModal.overview1') }}</p>
            <div :class="figureClass">
              <div class="flex flex-wrap items-center justify-center gap-2">
                <template v-for="(step, i) in flowSteps" :key="step">
                  <svg v-if="i > 0" class="w-4 h-4 text-slate-400 dark:text-slate-500 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M13 7l5 5m0 0l-5 5m5-5H6" />
                  </svg>
                  <span
                    class="px-3 py-1.5 rounded-lg text-xs font-bold whitespace-nowrap"
                    :class="i === flowSteps.length - 1
                      ? 'bg-indigo-600 text-white'
                      : 'bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 text-slate-700 dark:text-slate-200'"
                  >{{ t(step) }}</span>
                </template>
              </div>
            </div>
            <p :class="[leadClass, 'mt-2']">{{ t('league.infoModal.overview2') }}</p>
          </section>

          <!-- 2. スケジュール + タイムライン -->
          <section>
            <h4 :class="sectionTitleClass">
              <span class="w-1.5 h-5 bg-indigo-600 dark:bg-indigo-500 rounded-full"></span>
              {{ t('league.infoModal.scheduleTitle') }}
            </h4>
            <p :class="leadClass">{{ t('league.infoModal.schedule1') }}</p>
            <div :class="figureClass">
              <div class="grid grid-cols-[auto_1fr_auto_1fr_auto] items-center gap-x-1">
                <!-- 上段: 区間ラベル -->
                <span></span>
                <span class="text-center text-[9px] leading-tight text-slate-400 dark:text-slate-500 px-0.5">{{ t('league.infoModal.tl.forming') }}</span>
                <span></span>
                <span class="text-center text-[9px] leading-tight font-semibold text-indigo-600 dark:text-indigo-400 px-0.5">{{ t('league.infoModal.tl.playing') }}</span>
                <span></span>
                <!-- 中段: 点と線 -->
                <span class="justify-self-center w-3 h-3 rounded-full bg-slate-400 dark:bg-slate-500 ring-4 ring-white dark:ring-slate-800"></span>
                <span class="h-0.5 w-full border-t-2 border-dashed border-slate-300 dark:border-slate-600"></span>
                <span class="justify-self-center w-3 h-3 rounded-full bg-indigo-600 dark:bg-indigo-500 ring-4 ring-white dark:ring-slate-800"></span>
                <span class="h-1 w-full rounded-full bg-indigo-500 dark:bg-indigo-400"></span>
                <span class="justify-self-center w-3 h-3 rounded-full bg-indigo-600 dark:bg-indigo-500 ring-4 ring-white dark:ring-slate-800"></span>
                <!-- 下段: 時刻とイベント名 -->
                <div class="text-center max-w-[5.5rem] mt-1">
                  <div class="text-[11px] font-bold text-slate-700 dark:text-slate-200 whitespace-nowrap">{{ t('league.infoModal.tl.deadlineTime') }}</div>
                  <div class="text-[10px] leading-tight text-slate-500 dark:text-slate-400">{{ t('league.infoModal.tl.deadline') }}</div>
                </div>
                <span></span>
                <div class="text-center max-w-[5.5rem] mt-1">
                  <div class="text-[11px] font-bold text-indigo-700 dark:text-indigo-300 whitespace-nowrap">{{ t('league.infoModal.tl.startTime') }}</div>
                  <div class="text-[10px] leading-tight text-slate-500 dark:text-slate-400">{{ t('league.infoModal.tl.start') }}</div>
                </div>
                <span></span>
                <div class="text-center max-w-[5.5rem] mt-1">
                  <div class="text-[11px] font-bold text-indigo-700 dark:text-indigo-300 whitespace-nowrap">{{ t('league.infoModal.tl.endTime') }}</div>
                  <div class="text-[10px] leading-tight text-slate-500 dark:text-slate-400">{{ t('league.infoModal.tl.end') }}</div>
                </div>
              </div>
            </div>
            <ul class="mt-2 space-y-1">
              <li :class="[noteClass, 'pl-4 relative']">
                <span class="absolute left-0 top-2 w-1.5 h-1.5 rounded-full bg-slate-300 dark:bg-slate-600"></span>
                {{ t('league.infoModal.schedule2') }}
              </li>
              <li :class="[noteClass, 'pl-4 relative']">
                <span class="absolute left-0 top-2 w-1.5 h-1.5 rounded-full bg-slate-300 dark:bg-slate-600"></span>
                {{ t('league.infoModal.schedule3') }}
              </li>
            </ul>
          </section>

          <!-- 3. DIVISION と課題曲の難易度帯（レンジ図） -->
          <section>
            <h4 :class="sectionTitleClass">
              <span class="w-1.5 h-5 bg-indigo-600 dark:bg-indigo-500 rounded-full"></span>
              {{ t('league.infoModal.divisionTitle') }}
            </h4>
            <p :class="leadClass">{{ t('league.infoModal.division1') }}</p>
            <p :class="[leadClass, 'mt-1']">{{ t('league.infoModal.division2') }}</p>
            <p :class="[leadClass, 'mt-1']">{{ t('league.infoModal.division3') }}</p>

            <div :class="figureClass">
              <p class="text-[11px] font-bold text-slate-700 dark:text-slate-200 mb-2">{{ t('league.infoModal.chart.title') }}</p>
              <!-- 見出し行: 左=DIVISION / 中=軸ラベル / 右=帯の数値 -->
              <div class="grid grid-cols-[6.5rem_1fr_4.5rem] sm:grid-cols-[11rem_1fr_5rem] gap-x-2 items-end text-[9px] text-slate-400 dark:text-slate-500 mb-1">
                <span class="leading-tight">{{ t('league.infoModal.chart.divisionCol') }}</span>
                <div class="relative h-4">
                  <span
                    v-for="tick in axisTicks"
                    :key="tick"
                    class="absolute bottom-0 tabular-nums whitespace-nowrap"
                    :class="tickClass(tick)"
                    :style="{ left: cellLeft(tick) + '%' }"
                  >{{ fmtRank(tick) }}</span>
                </div>
                <span class="text-right leading-tight">{{ t('league.infoModal.chart.rangeCol') }}</span>
              </div>
              <!-- 各 DIVISION の行 -->
              <div
                v-for="d in divisions"
                :key="d.tier"
                class="grid grid-cols-[6.5rem_1fr_4.5rem] sm:grid-cols-[11rem_1fr_5rem] gap-x-2 items-center py-[3px]"
              >
                <div class="flex items-center gap-1 min-w-0">
                  <DivisionIcon :tier="d.tier" :size="18" class="flex-shrink-0" />
                  <div class="min-w-0 leading-tight">
                    <div class="text-[11px] font-bold text-slate-700 dark:text-slate-200 whitespace-nowrap">{{ divisionName(d.tier) }}</div>
                    <div class="text-[9px] text-slate-400 dark:text-slate-500 leading-tight break-words">{{ d.beatTier ?? t('league.infoModal.mappingLowest') }}</div>
                  </div>
                </div>
                <div class="relative h-4 rounded bg-slate-200/70 dark:bg-slate-900/70 overflow-hidden">
                  <!-- 目盛線 -->
                  <span
                    v-for="tick in axisTicks"
                    :key="tick"
                    class="absolute top-0 bottom-0 w-px bg-white dark:bg-slate-700"
                    :style="{ left: cellLeft(tick) + '%' }"
                  ></span>
                  <!-- 難易度帯 -->
                  <span
                    class="absolute top-0.5 bottom-0.5 rounded-sm"
                    :class="d.open ? 'rounded-r-none' : ''"
                    :style="{
                      left: cellLeft(d.lo) + '%',
                      width: bandWidth(d.lo, d.hi) + '%',
                      background: BAND_COLORS[d.tier].fill,
                      boxShadow: `inset 0 0 0 1px ${BAND_COLORS[d.tier].border}`,
                    }"
                  ></span>
                </div>
                <span class="text-[10px] tabular-nums text-right text-slate-600 dark:text-slate-300 whitespace-nowrap">{{ rangeLabel(d) }}</span>
              </div>
            </div>
          </section>

          <!-- 4. 有効な記録（ライン）+ ライン図 -->
          <section>
            <h4 :class="sectionTitleClass">
              <span class="w-1.5 h-5 bg-indigo-600 dark:bg-indigo-500 rounded-full"></span>
              {{ t('league.infoModal.lineTitle') }}
            </h4>
            <p :class="leadClass">{{ t('league.infoModal.line1') }}</p>
            <p :class="[leadClass, 'mt-1']">{{ t('league.infoModal.line2') }}</p>
            <div :class="figureClass">
              <div class="relative h-28">
                <!-- ラインの説明（上） -->
                <div class="absolute left-1/2 top-0 -translate-x-1/2 text-center whitespace-nowrap leading-tight">
                  <span class="text-[11px] font-bold text-rose-600 dark:text-rose-400">{{ t('league.infoModal.lineDiagram.line') }}</span>
                  <span class="block text-[9px] text-slate-500 dark:text-slate-400">{{ t('league.infoModal.lineDiagram.lineSub') }}</span>
                </div>
                <!-- 軸バー（左=無効・右=有効） -->
                <div class="absolute left-0 right-0 top-1/2 h-3 -translate-y-1/2 rounded-full overflow-hidden flex">
                  <div class="w-1/2 bg-slate-300 dark:bg-slate-600"></div>
                  <div class="w-1/2 bg-emerald-300 dark:bg-emerald-700"></div>
                </div>
                <!-- ライン（縦線） -->
                <div class="absolute left-1/2 top-7 bottom-6 w-0.5 -translate-x-1/2 bg-rose-500 dark:bg-rose-400"></div>
                <!-- 記録点 -->
                <span
                  v-for="(p, i) in linePoints"
                  :key="i"
                  class="absolute top-1/2 -translate-y-1/2 -translate-x-1/2 w-3.5 h-3.5 rounded-full border-2 border-white dark:border-slate-800 shadow"
                  :class="p.valid ? 'bg-emerald-500' : 'bg-slate-500 dark:bg-slate-400'"
                  :style="{ left: p.x + '%' }"
                ></span>
                <!-- 点の凡例 -->
                <span class="absolute left-[8%] top-[58%] text-[9px] text-slate-500 dark:text-slate-400 whitespace-nowrap">{{ t('league.infoModal.lineDiagram.past') }}</span>
                <span class="absolute left-[72%] top-[58%] -translate-x-1/2 text-[9px] font-semibold text-emerald-700 dark:text-emerald-400 whitespace-nowrap">{{ t('league.infoModal.lineDiagram.thisWeek') }}</span>
                <!-- 下段ラベル -->
                <div class="absolute bottom-0 left-0 text-[10px] font-semibold text-slate-500 dark:text-slate-400">✕ {{ t('league.infoModal.lineDiagram.invalid') }}</div>
                <div class="absolute bottom-0 right-0 text-[10px] font-semibold text-emerald-600 dark:text-emerald-400 text-right">✓ {{ t('league.infoModal.lineDiagram.valid') }}</div>
              </div>
            </div>
            <ul class="mt-2 space-y-1">
              <li :class="[noteClass, 'pl-4 relative']">
                <span class="absolute left-0 top-2 w-1.5 h-1.5 rounded-full bg-slate-300 dark:bg-slate-600"></span>
                {{ t('league.infoModal.line3') }}
              </li>
              <li :class="[noteClass, 'pl-4 relative']">
                <span class="absolute left-0 top-2 w-1.5 h-1.5 rounded-full bg-slate-300 dark:bg-slate-600"></span>
                {{ t('league.infoModal.line4') }}
              </li>
            </ul>
          </section>

          <!-- 5. 順位の決め方（着順ポイント）+ 計算例 -->
          <section>
            <h4 :class="sectionTitleClass">
              <span class="w-1.5 h-5 bg-indigo-600 dark:bg-indigo-500 rounded-full"></span>
              {{ t('league.infoModal.pointsTitle') }}
            </h4>
            <p :class="leadClass">{{ t('league.infoModal.points1') }}</p>
            <div :class="figureClass">
              <p class="text-[10px] text-slate-400 dark:text-slate-500 mb-2">{{ t('league.infoModal.pointsDiagram.example') }}</p>
              <div class="flex flex-wrap items-center justify-center gap-2">
                <template v-for="(s, i) in pointsExample" :key="s.song">
                  <span v-if="i > 0" class="text-base font-bold text-slate-400 dark:text-slate-500">+</span>
                  <div class="rounded-lg bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 px-3 py-1.5 text-center min-w-[4.5rem]">
                    <div class="text-[9px] text-slate-400 dark:text-slate-500">{{ t('league.songNth', { n: s.song }) }}</div>
                    <div class="text-[11px] font-bold text-slate-700 dark:text-slate-200">{{ t('league.songRank', { n: s.rank }) }}</div>
                    <div class="text-sm font-extrabold text-indigo-600 dark:text-indigo-400 tabular-nums">{{ s.pts }}pt</div>
                  </div>
                </template>
                <span class="text-base font-bold text-slate-400 dark:text-slate-500">=</span>
                <div class="rounded-lg bg-indigo-600 text-white px-3 py-1.5 text-center min-w-[4.5rem]">
                  <div class="text-[9px] opacity-80">{{ t('league.infoModal.pointsDiagram.total') }}</div>
                  <div class="text-lg font-extrabold tabular-nums leading-tight">{{ pointsTotal }}pt</div>
                </div>
              </div>
              <p class="mt-2 text-center text-[10px] text-slate-400 dark:text-slate-500">{{ t('league.infoModal.pointsDiagram.scale') }}</p>
            </div>
            <ul class="mt-2 space-y-1">
              <li :class="[noteClass, 'pl-4 relative']">
                <span class="absolute left-0 top-2 w-1.5 h-1.5 rounded-full bg-slate-300 dark:bg-slate-600"></span>
                {{ t('league.infoModal.points2') }}
              </li>
              <li :class="[noteClass, 'pl-4 relative']">
                <span class="absolute left-0 top-2 w-1.5 h-1.5 rounded-full bg-slate-300 dark:bg-slate-600"></span>
                {{ t('league.infoModal.points3') }}
              </li>
              <li :class="[noteClass, 'pl-4 relative']">
                <span class="absolute left-0 top-2 w-1.5 h-1.5 rounded-full bg-slate-300 dark:bg-slate-600"></span>
                {{ t('league.infoModal.points4') }}
              </li>
            </ul>
          </section>

          <!-- 6. 課題曲をプレーしなかった場合（3 分岐図） -->
          <section>
            <h4 :class="sectionTitleClass">
              <span class="w-1.5 h-5 bg-indigo-600 dark:bg-indigo-500 rounded-full"></span>
              {{ t('league.infoModal.noPlayTitle') }}
            </h4>
            <div class="rounded-xl border border-slate-200 dark:border-slate-700 divide-y divide-slate-200 dark:divide-slate-700 overflow-hidden">
              <div v-for="c in noPlayCases" :key="c.key" class="flex items-center gap-2 px-3 py-2 bg-slate-50/60 dark:bg-slate-900/30">
                <span class="w-7 h-7 rounded-full flex items-center justify-center flex-shrink-0" :class="c.iconClass">
                  <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
                    <path v-if="c.icon === 'check'" stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
                    <path v-else-if="c.icon === 'minus'" stroke-linecap="round" stroke-linejoin="round" d="M5 12h14" />
                    <path v-else stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </span>
                <div class="flex-1 min-w-0 text-xs font-bold text-slate-700 dark:text-slate-200 leading-tight">{{ t(c.labelKey) }}</div>
                <svg class="w-4 h-4 text-slate-400 dark:text-slate-500 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M13 7l5 5m0 0l-5 5m5-5H6" />
                </svg>
                <div class="w-[42%] text-[11px] leading-tight text-slate-600 dark:text-slate-300">{{ t(c.resultKey) }}</div>
              </div>
            </div>
            <ul class="mt-2 space-y-1">
              <li :class="[noteClass, 'pl-4 relative']">
                <span class="absolute left-0 top-2 w-1.5 h-1.5 rounded-full bg-slate-300 dark:bg-slate-600"></span>
                {{ t('league.infoModal.noPlay1') }}
              </li>
              <li :class="[noteClass, 'pl-4 relative']">
                <span class="absolute left-0 top-2 w-1.5 h-1.5 rounded-full bg-slate-300 dark:bg-slate-600"></span>
                {{ t('league.infoModal.noPlay2') }}
              </li>
              <li :class="[noteClass, 'pl-4 relative']">
                <span class="absolute left-0 top-2 w-1.5 h-1.5 rounded-full bg-slate-300 dark:bg-slate-600"></span>
                {{ t('league.infoModal.noPlay3') }}
              </li>
            </ul>
          </section>

          <!-- 7. 昇格・降格（PT）+ 順位→増減・目盛図 -->
          <section>
            <h4 :class="sectionTitleClass">
              <span class="w-1.5 h-5 bg-indigo-600 dark:bg-indigo-500 rounded-full"></span>
              {{ t('league.infoModal.ptTitle') }}
            </h4>
            <p :class="leadClass">{{ t('league.infoModal.pt1') }}</p>
            <div :class="[figureClass, 'space-y-3']">
              <!-- 順位 → 増減 PT -->
              <div>
                <p class="text-[10px] text-slate-400 dark:text-slate-500 mb-1">{{ t('league.infoModal.ptDiagram.weekly') }}</p>
                <div class="grid grid-cols-8 gap-1">
                  <div
                    v-for="c in weeklyDelta"
                    :key="c.rank"
                    class="rounded-md text-center py-1"
                    :class="c.delta > 0
                      ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300'
                      : 'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300'"
                  >
                    <div class="text-[9px] opacity-70 whitespace-nowrap">{{ t('league.songRank', { n: c.rank }) }}</div>
                    <div class="text-xs font-extrabold tabular-nums">{{ fmtDelta(c.delta) }}</div>
                  </div>
                </div>
              </div>
              <!-- PT 目盛（-8 〜 +8） -->
              <div>
                <div class="flex items-center justify-between text-[10px] font-semibold leading-none">
                  <span class="text-rose-600 dark:text-rose-400 whitespace-nowrap">▼ {{ t('league.gauge.relegate') }} -8</span>
                  <span class="text-slate-500 dark:text-slate-400">0</span>
                  <span class="text-emerald-600 dark:text-emerald-400 whitespace-nowrap">+8 {{ t('league.gauge.promote') }} ▲</span>
                </div>
                <div class="mt-1 flex items-center gap-px">
                  <span
                    v-for="i in 8"
                    :key="`neg-${i}`"
                    class="h-2.5 flex-1 rounded-[2px] bg-rose-500 dark:bg-rose-400"
                    :style="{ opacity: 0.15 + ((9 - i) / 8) * 0.85 }"
                  ></span>
                  <span class="w-0.5 h-4 rounded-full bg-slate-400 dark:bg-slate-500 mx-0.5"></span>
                  <span
                    v-for="i in 8"
                    :key="`pos-${i}`"
                    class="h-2.5 flex-1 rounded-[2px] bg-emerald-500 dark:bg-emerald-400"
                    :style="{ opacity: 0.15 + (i / 8) * 0.85 }"
                  ></span>
                </div>
                <div class="relative h-4 mt-1 text-[9px] text-slate-500 dark:text-slate-400">
                  <span class="absolute left-1/4 -translate-x-1/2 whitespace-nowrap">▲ {{ t('league.infoModal.ptDiagram.afterPromote') }}</span>
                  <span class="absolute left-3/4 -translate-x-1/2 whitespace-nowrap">▲ {{ t('league.infoModal.ptDiagram.afterRelegate') }}</span>
                </div>
              </div>
            </div>
            <ul class="mt-2 space-y-1">
              <li :class="[noteClass, 'pl-4 relative']">
                <span class="absolute left-0 top-2 w-1.5 h-1.5 rounded-full bg-slate-300 dark:bg-slate-600"></span>
                {{ t('league.infoModal.pt2') }}
              </li>
              <li :class="[noteClass, 'pl-4 relative']">
                <span class="absolute left-0 top-2 w-1.5 h-1.5 rounded-full bg-slate-300 dark:bg-slate-600"></span>
                {{ t('league.infoModal.pt3') }}
              </li>
              <li :class="[noteClass, 'pl-4 relative']">
                <span class="absolute left-0 top-2 w-1.5 h-1.5 rounded-full bg-slate-300 dark:bg-slate-600"></span>
                {{ t('league.infoModal.pt4') }}
              </li>
            </ul>
          </section>
        </div>

        <!-- フッター（参加導線 + 参加締切の注記） -->
        <div class="px-6 py-4 border-t border-slate-100 dark:border-slate-700/50 space-y-3">
          <!-- いつから参戦できるか（毎週 月曜 0:00 締切 → 12:00 開始）の注記 -->
          <p class="flex items-start gap-2 text-xs leading-relaxed text-amber-800 dark:text-amber-300 bg-amber-50 dark:bg-amber-900/20 rounded-lg px-3 py-2">
            <svg class="w-4 h-4 flex-shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v2m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
            </svg>
            <span>{{ t('league.infoModal.signupNote') }}</span>
          </p>

          <div class="flex items-center justify-end gap-2 flex-wrap">
            <span v-if="joinError" class="text-xs text-red-500 mr-auto">{{ joinError }}</span>
            <span v-else-if="!isLoggedIn" class="text-xs text-slate-400 dark:text-slate-500 mr-auto">{{ t('league.infoModal.loginToJoin') }}</span>

            <button
              class="px-4 py-2 rounded-lg bg-slate-100 hover:bg-slate-200 dark:bg-slate-700 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 text-sm font-semibold transition-colors"
              @click="$emit('close')"
            >{{ t('league.infoModal.close') }}</button>

            <!-- 参加済み: 緑のチェック表示 -->
            <span
              v-if="joined"
              class="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-emerald-50 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 text-sm font-bold"
            >
              <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
              </svg>
              {{ t('league.infoModal.joined') }}
            </span>

            <!-- 未参加: 参加ボタン -->
            <button
              v-else
              :disabled="!isLoggedIn || joining"
              class="inline-flex items-center gap-1.5 px-5 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-bold transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              @click="doJoin"
            >
              <svg v-if="!joining" class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M13 7l5 5m0 0l-5 5m5-5H6" />
              </svg>
              {{ joining ? t('league.infoModal.joining') : t('league.infoModal.join') }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>
