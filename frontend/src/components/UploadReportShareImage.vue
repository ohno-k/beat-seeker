<template>
  <!-- ルート要素の前にコメントを置かないこと（開発ビルドでは複数ルート扱いになり、親の $el が div でなくなる）。 -->
  <div ref="root" class="sr">
    <div class="sr-stripe"></div>

    <!-- ヘッダー: ブランド + 持ち主・日付 -->
    <div class="sr-head">
      <div class="sr-brand">
        <div class="sr-logo">B</div>
        <div>
          <p class="sr-wordmark">beat-seeker</p>
          <p class="sr-kicker">PLAY REPORT</p>
        </div>
      </div>
      <div class="sr-owner">
        <p v-if="ownerName" class="sr-owner-name">{{ ownerName }}</p>
        <p class="sr-owner-meta">{{ metaLine }}</p>
      </div>
    </div>

    <!-- ティアパネル（Rate 表示 OFF なら BEAT の 1 枚を横長に使う） -->
    <div class="sr-hero" :class="{ 'sr-hero--single': panels.length === 1 }">
      <div v-for="p in panels" :key="p.key" class="sr-panel">
        <div class="sr-panel-accent" :style="{ backgroundImage: `linear-gradient(90deg, ${p.tierColor} 0%, rgba(255,255,255,0) 100%)` }"></div>
        <div class="sr-panel-top">
          <p class="sr-label">{{ p.label }}</p>
          <p v-if="p.tierUp" class="sr-tierup">TIER UP</p>
        </div>
        <div class="sr-panel-body">
          <div class="sr-panel-main">
            <div class="sr-icon">
              <RankIcon
                :rank-name="p.tier.name"
                :tier="p.tier.tier"
                disable-party
                bleed
                :is-supporter="p.isSupporter"
                v-bind="p.frame"
                class="!w-full !h-full"
              />
            </div>
            <div class="sr-panel-names">
              <p class="sr-tier" :style="{ color: p.tierColor }">{{ p.tierName }}</p>
              <p class="sr-total">{{ p.total }}<span class="sr-total-unit">pt</span></p>
            </div>
          </div>
          <div class="sr-delta-row">
            <p class="sr-delta" :style="{ color: p.delta > 0 ? p.accent : '#64748b' }">{{ p.deltaText }}</p>
            <p v-if="p.tierUp" class="sr-from">{{ p.oldTierName }} → {{ p.tierName }}</p>
          </div>
          <div class="sr-next">
            <div class="sr-next-text">
              <p v-if="p.nextName" class="sr-next-name">NEXT&nbsp;&nbsp;<span :style="{ color: p.nextColor }">{{ p.nextName }}</span></p>
              <p v-else class="sr-next-name">MAX TIER</p>
              <p v-if="p.nextName" class="sr-next-remaining">{{ t('report.remainingPt', { n: p.remaining }) }}</p>
            </div>
            <div class="sr-bar"><div class="sr-bar-fill" :style="{ width: `${p.progress}%`, backgroundColor: p.accent }"></div></div>
          </div>
        </div>
      </div>
    </div>

    <!-- 集計タイル -->
    <div class="sr-stats">
      <div v-for="s in statTiles" :key="s.key" class="sr-stat">
        <p class="sr-stat-value" :style="{ color: s.color }">{{ s.value }}</p>
        <p class="sr-stat-label">{{ s.label }}</p>
      </div>
    </div>

    <!-- 更新曲（最大 10 曲。並び順の上位か、自由選択で選んだ曲） -->
    <template v-if="rows.length > 0">
      <div class="sr-list-head sr-grid" :class="{ 'sr-grid--tier': column === 'tier' }">
        <p class="sr-list-title">UPDATED CHARTS<span class="sr-list-count">{{ picked ? 'PICK' : 'TOP' }} {{ rows.length }} / {{ totalSongs }}</span><span class="sr-list-sort">{{ listLabel }}</span></p>
        <p class="sr-col-cap">EX SCORE</p>
        <p class="sr-col-cap">DJ LEVEL</p>
        <p class="sr-col-cap">{{ COLUMN_CAPTION[column] }}</p>
      </div>

      <div class="sr-rows">
        <div
          v-for="r in rows"
          :key="r.key"
          class="sr-row sr-grid"
          :class="{ 'sr-grid--tier': column === 'tier', 'sr-row--gold': r.song.allTimeBestUpdated, 'sr-row--softgold': r.song.allTimeBestExtended }"
        >
          <div class="sr-title-cell">
            <div class="sr-diff" :style="{ backgroundColor: r.diffColor }">{{ r.diffLetter }}</div>
            <div class="sr-title-block">
              <p class="sr-title" :style="{ fontSize: `${r.titleSize}px` }">{{ r.titleText }}</p>
              <div class="sr-sub">
                <p v-if="r.level" class="sr-sub-level">☆{{ r.level }}</p>
                <p v-if="r.song.clearTypeImproved" class="sr-sub-item">
                  <span class="sr-lamp-old">{{ clearTypeShort(r.song.oldClearType) }}</span>
                  <span class="sr-arrow">→</span>
                  <span :style="{ color: lampColor(r.song.newClearType) }">{{ clearTypeShort(r.song.newClearType) }}</span>
                </p>
                <p v-else-if="r.showLamp" class="sr-sub-item" :style="{ color: lampColor(r.song.newClearType) }">{{ clearTypeShort(r.song.newClearType) }}</p>
                <p v-if="r.song.allTimeBestUpdated" class="sr-sub-item sr-gold">★ {{ t('report.stat.allTimeBest') }}<template v-if="r.allTimeGain"> +{{ r.allTimeGain }}</template></p>
                <p v-else-if="r.song.allTimeBestExtended" class="sr-sub-item sr-softgold">☆ {{ t('report.allTimeBestExtended') }}</p>
                <p v-if="r.showRank" class="sr-sub-item" :class="r.song.songRank === 1 ? 'sr-gold' : 'sr-muted'">#{{ r.song.songRank }}/{{ r.song.songRankTotal }}</p>
              </div>
            </div>
            <div class="sr-tiericon">
              <RankIcon v-if="r.tier" :rank-name="r.tier.name" :tier="r.tier.tier" disable-party lite class="!w-full !h-full" />
            </div>
          </div>

          <div class="sr-num">
            <p class="sr-num-main">{{ r.song.newScore }}</p>
            <p class="sr-num-sub" :style="{ color: r.song.oldScore > 0 ? '#60a5fa' : '#94a3b8' }">{{ r.scoreSub }}</p>
          </div>
          <div class="sr-num">
            <p class="sr-num-grade" :style="{ color: r.gradeColor }">{{ r.grade.main || '—' }}</p>
            <p class="sr-num-sub sr-muted">{{ r.grade.sub }}</p>
          </div>
          <div class="sr-num">
            <p :class="column === 'tier' ? 'sr-num-tier' : 'sr-num-main'" :style="{ color: r.ptColor }">{{ r.ptMain }}</p>
            <p class="sr-num-sub" :style="{ color: column === 'tier' ? '#94a3b8' : r.ptColor }">{{ r.ptSub }}</p>
          </div>
        </div>
      </div>
    </template>
    <div v-else class="sr-empty">{{ picked ? t('report.pick.needOne') : t('report.noUpdates') }}</div>

    <!-- フッター -->
    <div class="sr-foot">
      <p class="sr-more"><template v-if="totalSongs > rows.length">{{ t('report.otherUpdates', { n: totalSongs - rows.length }) }}</template></p>
      <p class="sr-site">beat-seeker.com<span class="sr-tag">#BeatSeeker</span></p>
    </div>

    <!-- 曲名の幅を測るための不可視要素 -->
    <span ref="measure" class="sr-measure" aria-hidden="true"></span>
  </div>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 プレイ成果レポートの X 共有画像（html2canvas のキャプチャ対象）。
 *
 * UploadResultModal が画面外に 1 つ（キャプチャ用）、共有オプションのプレビューに 1 つ（CSS で縮小）置く。
 * 親は expose している `el`（ルートの div）を html2canvas に渡す。
 *
 * レイアウト: 横 1080px 固定・高さは内容に応じて可変（18 曲満載で約 1914px ≒ 9:16）。
 * 固定部分（ヘッダー〜リスト見出し + フッター）が約 732px、1 行が 60px + 間隔 6px なので 18 行で 1920px に収まる。
 * 行の高さを変えるときは SHARE_MAX_SONGS も合わせて見直すこと。更新曲が少ないときは高さが縮む。
 *
 * html2canvas 向けの決まり事:
 *  - 配色はテーマに依存しない固定のダーク（dark: バリアントを使わない）
 *  - テキスト行は height = line-height を px で明示して高さを確定させる
 *    （文字が下にずれる件そのものは utils/html2canvasHelpers の withHtml2canvasTextFix で直している）
 *  - text-overflow: ellipsis は html2canvas が描けないので、曲名は JS で幅に合わせて縮小・省略する（fitTitles）
 *  - filter / backdrop-filter / background-clip:text / box-shadow は使わない
 *
 * props:
 *  - diffData: 差分情報
 *  - songs: 載せる更新曲（表示順のまま、最大 SHARE_MAX_SONGS 曲）。どの曲を選ぶかは親が決める
 *    （並び順の上位 10 曲、または自由選択で選んだ曲）
 *  - column: 右端の列に出す指標（'beat' = BEAT-PT / 'rate' = RATE-PT / 'tier' = 単曲ティア名 + スコアレート）
 *  - listLabel: 見出しに添える選び方の名前（「BEAT-PT順」「自由選択」など）
 *  - picked: 自由選択で選んだ曲か（見出しが TOP n ではなく PICK n になる）
 *  - showRateTier: RATE-TIER パネルを出すか
 *  - ownerName: 右上に入れる DJ NAME（null なら出さない）
 *  - dateLabel / versionLabel: 右上の日付・作品名
 *  - isSupporter / beatFrame / rateFrame: ヒーローのティアアイコンをアプリ内と同じ見た目にする
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import type { UploadDiffResult, UpdatedSong } from '../types/UploadDiff';
import { getNextRankInfo, getNextRateTierRankInfo } from '../utils/beatTier';
import type { RankInfo } from '../utils/beatTier';
import {
  clearTypeShort,
  computeReportStats,
  displayTitle,
  formatStatValue,
  getNumericRank,
  getScoreGradeInfo,
  getSongTierInfo,
  pickStatTiles,
  SHARE_MAX_SONGS,
  songKey,
  tierLabel,
} from '../utils/uploadReport';
import type { ScoreGradeInfo, ShareColumn, StatKey } from '../utils/uploadReport';
import RankIcon from './RankIcon.vue';
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();

const props = defineProps<{
  diffData: UploadDiffResult;
  songs: UpdatedSong[];
  column: ShareColumn;
  listLabel: string;
  picked?: boolean;
  showRateTier: boolean;
  ownerName?: string | null;
  dateLabel: string;
  versionLabel?: string | null;
  isSupporter?: boolean;
  beatFrame?: { frameRankName?: string; frameTier?: number };
  rateFrame?: { frameRankName?: string; frameTier?: number };
}>();

/** 右端の列の見出し。 */
const COLUMN_CAPTION: Record<ShareColumn, string> = { beat: 'BEAT-PT', rate: 'RATE-PT', tier: 'SONG TIER' };
/** 曲名のフォントサイズ（px）。幅に収まらなければ MIN まで縮め、それでも溢れたら「…」で切る。 */
const TITLE_SIZE_MAX = 26;
const TITLE_SIZE_MIN = 20;

const BEAT_ACCENT = '#60a5fa';
const RATE_ACCENT = '#34d399';

/** ティア名 → ダーク背景で読める色（RankIcon の配色に合わせる）。 */
const TIER_COLORS: Record<string, string> = {
  beginner: '#94a3b8',
  novice: '#a8bccf',
  intermediate: '#38bdf8',
  advanced: '#2dd4bf',
  expert: '#34d399',
  veteran: '#a3e635',
  commander: '#facc15',
  elite: '#fb923c',
  master: '#fb7185',
  ancient: '#818cf8',
  mythic: '#c084fc',
  legend: '#fbbf24',
};
const tierColor = (info: RankInfo | null | undefined) => TIER_COLORS[(info?.name ?? '').toLowerCase()] ?? '#94a3b8';

const DIFF_STYLE: Record<string, { letter: string; color: string }> = {
  BEGINNER: { letter: 'B', color: '#16a34a' },
  NORMAL: { letter: 'N', color: '#2563eb' },
  HYPER: { letter: 'H', color: '#d97706' },
  ANOTHER: { letter: 'A', color: '#dc2626' },
  LEGGENDARIA: { letter: 'L', color: '#9333ea' },
};

const LAMP_COLORS: Record<string, string> = {
  'FULLCOMBO CLEAR': '#67e8f9',
  'EX HARD CLEAR': '#fde047',
  'HARD CLEAR': '#f87171',
  'CLEAR': '#60a5fa',
  'EASY CLEAR': '#4ade80',
  'ASSIST CLEAR': '#c084fc',
  'FAILED': '#fb923c',
};
const lampColor = (type: string) => LAMP_COLORS[type] ?? '#94a3b8';

/** DJ LEVEL 列の大きい文字の色（MAX-n は紫 = スコア一覧の MAX- と同じ purple-400）。 */
const GRADE_COLORS: Record<string, string> = { MAX: '#facc15', 'MAX-': '#c084fc', AAA: '#facc15', AA: '#60a5fa', A: '#4ade80' };

const metaLine = computed(() => [props.dateLabel, props.versionLabel].filter(Boolean).join('  ・  '));

interface TierPanel {
  key: string;
  label: string;
  accent: string;
  tier: RankInfo;
  tierName: string;
  tierColor: string;
  oldTierName: string;
  tierUp: boolean;
  total: string;
  delta: number;
  deltaText: string;
  nextName: string | null;
  nextColor: string;
  remaining: string;
  progress: number;
  isSupporter: boolean;
  frame: { frameRankName?: string; frameTier?: number };
}

const FALLBACK_TIER: RankInfo = { name: 'Beginner', minPoints: 0, color: '' };

function buildPanel(
  key: string,
  label: string,
  accent: string,
  oldTier: RankInfo | null,
  newTier: RankInfo | null,
  total: number,
  delta: number,
  next: { nextRank?: RankInfo; progress: number },
  frame: { frameRankName?: string; frameTier?: number } | undefined,
): TierPanel {
  const tier = newTier ?? FALLBACK_TIER;
  return {
    key,
    label,
    accent,
    tier,
    tierName: tierLabel(tier),
    tierColor: tierColor(tier),
    oldTierName: tierLabel(oldTier),
    tierUp: !!oldTier && !!newTier && oldTier.minPoints < newTier.minPoints,
    total: total.toFixed(1),
    delta,
    deltaText: delta > 0 ? `+${delta.toFixed(1)}` : '±0.0',
    nextName: next.nextRank ? tierLabel(next.nextRank) : null,
    nextColor: tierColor(next.nextRank),
    remaining: next.nextRank ? Math.max(0, next.nextRank.minPoints - total).toFixed(1) : '',
    progress: next.progress,
    isSupporter: !!props.isSupporter,
    frame: frame ?? {},
  };
}

const panels = computed<TierPanel[]>(() => {
  const d = props.diffData;
  const list = [
    buildPanel('beat', 'BEAT-TIER', BEAT_ACCENT, d.oldTier, d.newTier, d.newTotalBeatPt, d.totalBeatPtIncrease,
      getNextRankInfo(d.newTotalBeatPt), props.beatFrame),
  ];
  if (props.showRateTier) {
    const newRate = d.newTotalRatePt ?? 0;
    const oldRate = d.oldTotalRatePt ?? 0;
    list.push(buildPanel('rate', 'RATE-TIER', RATE_ACCENT, d.oldRateTier, d.newRateTier, newRate, newRate - oldRate,
      getNextRateTierRankInfo(newRate), props.rateFrame));
  }
  return list;
});

const STAT_COLORS: Record<StatKey, string> = {
  updated: '#f8fafc',
  exGain: BEAT_ACCENT,
  allTimeBest: '#fbbf24',
  newAaa: '#facc15',
  lampUp: RATE_ACCENT,
  top100: '#fbbf24',
};

/** 集計タイル（4 枚）。 */
const statTiles = computed(() => pickStatTiles(computeReportStats(props.diffData)).map(({ key, value }) => ({
  key,
  value: formatStatValue(key, value),
  label: t(`report.stat.${key}`),
  color: value > 0 ? STAT_COLORS[key] : '#64748b',
})));

const totalSongs = computed(() => props.diffData.updatedSongs.length);

/** 載せる曲。親が上限を守って渡してくるが、レイアウト（9:16）が崩れないよう念のためここでも切る。 */
const shownSongs = computed<UpdatedSong[]>(() => props.songs.slice(0, SHARE_MAX_SONGS));

/** 曲名の収まり（キー → 表示テキストとフォントサイズ）。fitTitles が埋める。 */
const fitted = ref<Record<string, { text: string; size: number }>>({});

interface Row {
  key: string;
  song: UpdatedSong;
  titleText: string;
  titleSize: number;
  diffLetter: string;
  diffColor: string;
  level: string | null;
  showLamp: boolean;
  showRank: boolean;
  allTimeGain: number;
  tier: RankInfo | null;
  scoreSub: string;
  grade: ScoreGradeInfo;
  gradeColor: string;
  ptMain: string;
  ptSub: string;
  ptColor: string;
}

/** 右端の列（BEAT-PT / RATE-PT は値と増分、単曲ティアはティア名とスコアレート）。 */
const lastColumn = (song: UpdatedSong, tier: RankInfo | null): Pick<Row, 'ptMain' | 'ptSub' | 'ptColor'> => {
  if (props.column === 'tier') {
    return {
      ptMain: tier ? tierLabel(tier) : '—',
      ptSub: song.scoreRate && song.scoreRate > 0 ? `${song.scoreRate.toFixed(2)}%` : '',
      ptColor: tier ? tierColor(tier) : '#64748b',
    };
  }
  const isRate = props.column === 'rate';
  const inTop = isRate ? !!song.isInRateTop100 : !!song.isInTop100;
  const ptNew = isRate ? song.newRatePt : song.newBeatPt;
  const ptInc = isRate ? song.ratePtIncrease : song.beatPtIncrease;
  return {
    ptMain: (ptNew ?? 0).toFixed(1),
    ptSub: ptInc > 0 ? `+${ptInc.toFixed(1)}` : '',
    ptColor: inTop ? (isRate ? RATE_ACCENT : '#fbbf24') : '#cbd5e1',
  };
};

const rows = computed<Row[]>(() => shownSongs.value.map((song) => {
  const key = songKey(song);
  const fit = fitted.value[key];
  const diff = DIFF_STYLE[song.difficulty?.toUpperCase()] ?? { letter: '?', color: '#475569' };
  const grade = getScoreGradeInfo(song.newScore, song.maxScore);
  const tier = getSongTierInfo(song);
  return {
    key,
    song,
    titleText: fit?.text ?? displayTitle(song),
    titleSize: fit?.size ?? TITLE_SIZE_MAX,
    diffLetter: diff.letter,
    diffColor: diff.color,
    level: getNumericRank(song.informalRank),
    showLamp: !!song.newClearType && song.newClearType !== 'NO PLAY' && song.newClearType !== '---',
    // ランプ変化と歴代ベストが両方出る行は横幅が足りなくなるので、優先度の低い単曲順位を落とす。
    showRank: !!song.songRank && !(song.clearTypeImproved && (song.allTimeBestUpdated || song.allTimeBestExtended)),
    allTimeGain: song.allTimeBeatenScore ? Math.max(0, song.newScore - song.allTimeBeatenScore) : 0,
    tier,
    scoreSub: song.oldScore > 0 ? (song.scoreIncrease > 0 ? `+${song.scoreIncrease}` : '±0') : t('report.newPlay'),
    grade,
    gradeColor: GRADE_COLORS[grade.mainColorKey] ?? '#94a3b8',
    ...lastColumn(song, tier),
  };
}));

const root = ref<HTMLElement | null>(null);
const measure = ref<HTMLElement | null>(null);

/**
 * 【関数の役割】 曲名を列幅に収める。26px → 20px まで縮め、それでも溢れる分は末尾を「…」で切る。
 * html2canvas が text-overflow: ellipsis を描けないための代替。幅は不可視の span で実測する。
 */
const fitTitles = async () => {
  await nextTick();
  const rootEl = root.value;
  const probe = measure.value;
  if (!rootEl || !probe) return;
  const cell = rootEl.querySelector<HTMLElement>('.sr-title');
  if (!cell) return;
  const maxWidth = cell.clientWidth - 2;
  if (maxWidth <= 0) return;

  const widthOf = (text: string, size: number) => {
    probe.style.fontSize = `${size}px`;
    probe.textContent = text;
    return probe.getBoundingClientRect().width / scaleOf(rootEl);
  };

  const result: Record<string, { text: string; size: number }> = {};
  for (const song of shownSongs.value) {
    const full = displayTitle(song);
    let size = TITLE_SIZE_MAX;
    while (size > TITLE_SIZE_MIN && widthOf(full, size) > maxWidth) size -= 1;
    let text = full;
    if (widthOf(full, size) > maxWidth) {
      // 収まる最長の文字数を二分探索（サロゲートペアを割らないよう配列化して数える）。
      const chars = Array.from(full);
      let lo = 1;
      let hi = chars.length - 1;
      while (lo < hi) {
        const mid = Math.ceil((lo + hi) / 2);
        if (widthOf(chars.slice(0, mid).join('') + '…', size) <= maxWidth) lo = mid;
        else hi = mid - 1;
      }
      text = chars.slice(0, lo).join('').trimEnd() + '…';
    }
    result[songKey(song)] = { text, size };
  }
  probe.textContent = '';
  fitted.value = result;
};

/** プレビューでは親が transform: scale() で縮めるので、実測幅を元の px に戻すための倍率。 */
const scaleOf = (el: HTMLElement) => {
  const w = el.offsetWidth;
  return w > 0 ? el.getBoundingClientRect().width / w : 1;
};

onMounted(fitTitles);
// 曲の入れ替えに加えて、右端の列が単曲ティアになると曲名の列幅が変わるので測り直す。
watch(() => [props.diffData, props.songs, props.column], fitTitles);

/** 親が html2canvas に渡したり高さを測ったりするためのルート要素。 */
defineExpose({ el: root });
</script>

<style scoped>
.sr {
  position: relative;
  width: 1080px;
  box-sizing: border-box;
  padding: 46px 48px 36px;
  color: #f8fafc;
  background-color: #0a0f1d;
  background-image:
    radial-gradient(circle at 10% 0%, rgba(59, 130, 246, 0.30) 0%, rgba(59, 130, 246, 0) 46%),
    radial-gradient(circle at 96% 6%, rgba(16, 185, 129, 0.16) 0%, rgba(16, 185, 129, 0) 40%),
    linear-gradient(180deg, #0e1529 0%, #080c18 100%);
  font-feature-settings: normal;
  overflow: hidden;
}
.sr p { margin: 0; white-space: nowrap; }

.sr-stripe {
  position: absolute;
  top: 0;
  left: 0;
  width: 1080px;
  height: 6px;
  background-image: linear-gradient(90deg, #2563eb 0%, #60a5fa 45%, #34d399 100%);
}

/* ── ヘッダー ── */
.sr-head { display: flex; align-items: center; justify-content: space-between; height: 72px; }
.sr-brand { display: flex; align-items: center; gap: 16px; }
.sr-logo {
  width: 56px; height: 56px; line-height: 56px; border-radius: 14px;
  background-color: #2563eb; color: #fff; text-align: center; font-size: 34px; font-weight: 800;
}
.sr-wordmark { font-size: 32px; height: 40px; line-height: 40px; font-weight: 800; letter-spacing: -0.5px; }
.sr-kicker { font-size: 14px; height: 20px; line-height: 20px; font-weight: 700; letter-spacing: 5px; color: #94a3b8; }
.sr-owner { text-align: right; }
.sr-owner-name { font-size: 28px; height: 38px; line-height: 38px; font-weight: 800; }
.sr-owner-meta { font-size: 16px; height: 24px; line-height: 24px; font-weight: 600; color: #94a3b8; }

/* ── ティアパネル ── */
.sr-hero { display: flex; gap: 20px; margin-top: 26px; }
.sr-panel {
  position: relative; flex: 1 1 0; min-width: 0; box-sizing: border-box;
  padding: 24px 28px 24px;
  border-radius: 18px; border: 1px solid rgba(255, 255, 255, 0.10);
  background-color: rgba(255, 255, 255, 0.045);
  overflow: hidden;
}
.sr-panel-accent { position: absolute; top: 0; left: 0; width: 100%; height: 4px; }
.sr-panel-top { display: flex; align-items: center; justify-content: space-between; height: 26px; }
.sr-label { font-size: 15px; height: 26px; line-height: 26px; font-weight: 800; letter-spacing: 4px; color: #94a3b8; }
.sr-tierup {
  font-size: 13px; height: 26px; line-height: 26px; padding: 0 12px; border-radius: 13px;
  font-weight: 800; letter-spacing: 2px; color: #451a03; background-color: #fbbf24;
}
.sr-panel-body { display: flex; flex-direction: column; gap: 12px; margin-top: 8px; }
.sr-panel-main { display: flex; align-items: center; gap: 12px; margin-left: -10px; }
.sr-icon { width: 116px; height: 116px; flex: none; }
.sr-panel-names { min-width: 0; }
.sr-tier { font-size: 40px; height: 50px; line-height: 50px; font-weight: 800; letter-spacing: -0.5px; }
.sr-total { font-size: 28px; height: 36px; line-height: 36px; font-weight: 700; color: #e2e8f0; }
.sr-total-unit { font-size: 17px; font-weight: 700; color: #94a3b8; margin-left: 6px; }
.sr-delta-row { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; height: 60px; }
.sr-delta { font-size: 54px; height: 60px; line-height: 60px; font-weight: 800; letter-spacing: -1px; }
.sr-from { font-size: 17px; height: 28px; line-height: 28px; font-weight: 700; color: #cbd5e1; }
.sr-next-text { display: flex; align-items: center; justify-content: space-between; height: 24px; }
.sr-next-name { font-size: 15px; height: 24px; line-height: 24px; font-weight: 800; letter-spacing: 1px; color: #94a3b8; }
.sr-next-remaining { font-size: 15px; height: 24px; line-height: 24px; font-weight: 700; color: #cbd5e1; }
.sr-bar { height: 8px; margin-top: 6px; border-radius: 4px; background-color: rgba(255, 255, 255, 0.10); overflow: hidden; }
.sr-bar-fill { height: 8px; border-radius: 4px; }

/* Rate 表示 OFF: 1 枚のパネルを横に 3 分割して使う */
.sr-hero--single .sr-panel-body { flex-direction: row; align-items: center; gap: 40px; }
.sr-hero--single .sr-panel-main { flex: 1.2 1 0; min-width: 0; }
.sr-hero--single .sr-delta-row { flex: 0.9 1 0; min-width: 0; flex-direction: column; align-items: flex-start; justify-content: center; height: 92px; gap: 2px; }
.sr-hero--single .sr-next { flex: 1 1 0; min-width: 0; }

/* ── 集計タイル ── */
.sr-stats { display: flex; gap: 14px; margin-top: 18px; }
.sr-stat {
  flex: 1 1 0; min-width: 0; box-sizing: border-box; padding: 14px 20px 14px;
  border-radius: 14px; border: 1px solid rgba(255, 255, 255, 0.08);
  background-color: rgba(255, 255, 255, 0.035);
}
.sr-stat-value { font-size: 36px; height: 44px; line-height: 44px; font-weight: 800; letter-spacing: -0.5px; }
.sr-stat-label { font-size: 15px; height: 22px; line-height: 22px; font-weight: 700; color: #94a3b8; }

/* ── 更新曲リスト ── */
.sr-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 104px 124px 104px;
  column-gap: 14px;
  align-items: center;
  box-sizing: border-box;
  padding: 0 18px 0 14px;
}
/* 右端が単曲ティアのときはティア名（"Commander 4" など）が入るので列を広げる。曲名の列は fitTitles が測り直す。 */
.sr-grid--tier { grid-template-columns: minmax(0, 1fr) 104px 124px 150px; }
.sr-list-head { margin-top: 26px; height: 30px; }
.sr-list-title { font-size: 15px; height: 30px; line-height: 30px; font-weight: 800; letter-spacing: 4px; color: #e2e8f0; }
.sr-list-count { margin-left: 16px; letter-spacing: 1px; color: #94a3b8; }
.sr-list-sort { margin-left: 14px; letter-spacing: 0; font-weight: 700; color: #64748b; }
.sr-col-cap { font-size: 12px; height: 30px; line-height: 30px; font-weight: 800; letter-spacing: 2px; color: #64748b; text-align: right; }

.sr-rows { display: flex; flex-direction: column; gap: 6px; margin-top: 8px; }
.sr-row {
  height: 60px; border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.07);
  background-color: rgba(255, 255, 255, 0.04);
}
.sr-row--gold { border-color: rgba(251, 191, 36, 0.55); background-color: rgba(251, 191, 36, 0.10); }
.sr-row--softgold { border-color: rgba(251, 191, 36, 0.22); background-color: rgba(251, 191, 36, 0.045); }

.sr-title-cell { display: flex; align-items: center; gap: 14px; min-width: 0; }
.sr-diff {
  flex: none; width: 34px; height: 34px; line-height: 34px; border-radius: 9px;
  text-align: center; font-size: 19px; font-weight: 800; color: #fff;
}
.sr-title-block { flex: 1 1 0; min-width: 0; }
/* 曲名は fitTitles で幅に収めてあるので overflow では切らない（縦に切ると g や y の下端が欠ける）。 */
.sr-title { height: 32px; line-height: 32px; font-weight: 700; color: #f8fafc; }
.sr-sub { display: flex; align-items: center; gap: 14px; height: 20px; }
.sr-sub p { font-size: 14px; height: 20px; line-height: 20px; font-weight: 700; flex: none; }
.sr-sub-level { color: #cbd5e1; }
.sr-lamp-old { color: #64748b; text-decoration: line-through; }
.sr-arrow { margin: 0 6px; color: #64748b; }
.sr-gold { color: #fbbf24; }
.sr-softgold { color: #d6a94a; }
.sr-muted { color: #94a3b8; }
.sr-tiericon { flex: none; width: 40px; height: 40px; }

.sr-num { text-align: right; min-width: 0; }
.sr-num-main { font-size: 25px; height: 30px; line-height: 30px; font-weight: 800; color: #f1f5f9; }
.sr-num-grade { font-size: 21px; height: 30px; line-height: 30px; font-weight: 800; }
.sr-num-tier { font-size: 20px; height: 30px; line-height: 30px; font-weight: 800; letter-spacing: -0.3px; }
.sr-num-sub { font-size: 14px; height: 20px; line-height: 20px; font-weight: 700; }

.sr-empty {
  margin-top: 26px; height: 120px; line-height: 120px; text-align: center; border-radius: 14px;
  border: 2px dashed rgba(255, 255, 255, 0.14); font-size: 22px; font-weight: 700; color: #94a3b8;
}

/* ── フッター ── */
.sr-foot { display: flex; align-items: center; justify-content: space-between; height: 32px; margin-top: 20px; }
.sr-more { font-size: 16px; height: 32px; line-height: 32px; font-weight: 700; color: #94a3b8; }
.sr-site { font-size: 19px; height: 32px; line-height: 32px; font-weight: 800; color: #e2e8f0; }
.sr-tag { margin-left: 16px; color: #60a5fa; }

.sr-measure {
  position: absolute; left: 0; top: 0; visibility: hidden; pointer-events: none;
  white-space: nowrap; font-weight: 700;
}
</style>
