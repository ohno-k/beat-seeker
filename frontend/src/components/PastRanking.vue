<script setup lang="ts">
/**
 * 【コンポーネントの役割】 過去作の最終 PT ランキング（アーカイブ）を作品を選んで閲覧するページ。
 *
 * 現実世界の概念: IIDX は新作稼働のたびにスコアがリセットされ、beat-seeker でも BEAT-PT / RATE-PT が
 * 0 から積み直しになる。そのままでは「あの作品で自分は何位だったか」が失われるため、初期化の直前に
 * 全員の最終値を焼き付けている。ここはその保存済みデータを順位表として見せる画面。
 *
 * 一世代限りの画面ではない: 作品セレクタでアーカイブ済みの作品を選ぶ形にしてあるので、
 * 34 が終われば 34 のランキングが同じ画面に並び、作品を選んで見比べられる。
 *
 * 設計上の判断:
 *  - <b>ティアは PT から毎回導出する。</b> サーバは PT だけを保存しており、ティア名は持たない
 *    （閾値を調整したときに実体と表示がずれないようにするため）。
 *  - <b>アイコンに外枠は付けない。</b> 外枠は「前作の到達点」を示す装飾で、この画面では
 *    アイコン本体そのものがその作品の到達点を表しているため、重ねると意味が二重になる。
 *    サポーターの光沢だけは現在の設定に従って表示する。
 *  - <b>表示名は撮影時点の値。</b> 当時の順位表の見え方が後からの改名で変わってはならない
 *    （サーバ側 `VersionPtSnapshot` の設計方針）。一方で公開範囲は現在の設定に従う。
 *
 * props/emits: なし。
 */
import { ref, computed, onMounted } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useAuth } from '../composables/useAuth';
import { API_BASE } from '../composables/constants';
import { getRankInfo, getRateTierRankInfo } from '../utils/beatTier';
import { versionName } from '../utils/iidxVersions';
import RankIcon from './RankIcon.vue';

const { t } = useI18n();
const { user } = useAuth();

/** アーカイブ済みの作品 1 件ぶん。 */
interface ArchiveVersion {
    version: number;
    name: string;
    userCount: number;
    capturedAt: string | null;
}

/** 順位表の 1 行。 */
interface ArchiveRow {
    userId: number | null;
    displayName: string;
    iidxId: string;
    totalBeatPt: number | null;
    totalRatePt: number | null;
    beatRank: number | null;
    rateRank: number | null;
    privacyLevel: number | null;
    isSupporter: boolean;
}

/** 表示するランキングの種類。 */
type RankingMode = 'beat' | 'rate';

const versions = ref<ArchiveVersion[]>([]);
const selectedVersion = ref<number | null>(null);
const rows = ref<ArchiveRow[]>([]);
const mode = ref<RankingMode>('beat');
const isLoading = ref(true);
const error = ref('');

/**
 * 【computed の役割】 選択中のモードに応じて並べ替えた行。
 *
 * サーバは BEAT-PT 順で 1 回返すだけで、どちらの PT と順位も 1 行に入っている。
 * RATE-PT 表示はここで並べ替えるだけで作れるので、モード切替でネットワークを消費しない。
 *
 * RATE-PT が 0（＝順位が付いていない）ユーザーは RATE 表示から除く。現行のレートランキングが
 * {@code total_rate_pt > 0} で絞っているのと同じ扱いにして、0 pt が同順位で並ぶのを防ぐ。
 */
const sortedRows = computed(() => {
    if (mode.value === 'rate') {
        return rows.value
            .filter(r => r.rateRank != null)
            .sort((a, b) => (a.rateRank ?? 0) - (b.rateRank ?? 0));
    }
    return [...rows.value].sort((a, b) => (a.beatRank ?? 0) - (b.beatRank ?? 0));
});

/** 【computed の役割】 選択中の作品の要約（人数・撮影日時の表示に使う）。 */
const selected = computed(() => versions.value.find(v => v.version === selectedVersion.value) ?? null);

/**
 * 【関数の役割】 1 行の表示用ティアを返す。
 *
 * BEAT と RATE でティアの体系が違うため、モードに応じて導出関数を使い分ける。
 *
 * @param row 対象の行
 */
function tierOf(row: ArchiveRow) {
    return mode.value === 'rate'
        ? getRateTierRankInfo(row.totalRatePt ?? 0)
        : getRankInfo(row.totalBeatPt ?? 0);
}

/**
 * 【関数の役割】 1 行の表示用 PT を返す。
 *
 * @param row 対象の行
 */
function ptOf(row: ArchiveRow): number {
    return (mode.value === 'rate' ? row.totalRatePt : row.totalBeatPt) ?? 0;
}

/**
 * 【関数の役割】 1 行の表示用順位を返す。
 *
 * @param row 対象の行
 */
function rankOf(row: ArchiveRow): number | null {
    return mode.value === 'rate' ? row.rateRank : row.beatRank;
}

/** 【関数の役割】 その行がログイン中ユーザー自身かどうか。 */
function isMe(row: ArchiveRow): boolean {
    return !!user.value && row.iidxId === user.value.iidxId;
}

/**
 * 【関数の役割】 撮影日時を「YYYY/MM/DD」の形に整える。
 *
 * @param raw ISO 形式の日時文字列（null 可）
 */
function formatCapturedAt(raw: string | null): string {
    if (!raw) return '';
    const d = new Date(raw);
    if (Number.isNaN(d.getTime())) return '';
    return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}`;
}

/**
 * 【関数の役割】 アーカイブ済みの作品一覧を取得し、最新の作品を初期選択にする。
 */
async function fetchVersions() {
    const res = await fetch(`${API_BASE}/api/past-rankings/versions`);
    if (!res.ok) throw new Error(`versions ${res.status}`);
    const data = await res.json() as ArchiveVersion[];
    versions.value = data.map(v => ({
        ...v,
        // サーバも作品名を返すが、未知の番号のフォールバックはフロント側の表と揃えておく。
        name: v.name || versionName(v.version),
    }));
    if (versions.value.length > 0) {
        selectedVersion.value = versions.value[0].version;
    }
}

/**
 * 【関数の役割】 選択中の作品の順位表を取得する。
 */
async function fetchRanking() {
    if (selectedVersion.value == null) {
        rows.value = [];
        return;
    }
    const res = await fetch(`${API_BASE}/api/past-rankings/${selectedVersion.value}`);
    if (!res.ok) throw new Error(`ranking ${res.status}`);
    rows.value = await res.json() as ArchiveRow[];
}

/**
 * 【関数の役割】 作品セレクタを操作したときに、その作品の順位表へ差し替える。
 *
 * @param version 選択された作品バージョン番号
 */
async function selectVersion(version: number) {
    if (version === selectedVersion.value) return;
    selectedVersion.value = version;
    isLoading.value = true;
    error.value = '';
    try {
        await fetchRanking();
    } catch (e) {
        console.error(e);
        error.value = t('pastRanking.error');
    } finally {
        isLoading.value = false;
    }
}

onMounted(async () => {
    try {
        await fetchVersions();
        await fetchRanking();
    } catch (e) {
        console.error(e);
        error.value = t('pastRanking.error');
    } finally {
        isLoading.value = false;
    }
});
</script>

<template>
  <div class="bg-white dark:bg-slate-800 p-4 sm:p-6 rounded-md border border-slate-100 dark:border-slate-700 transition-colors duration-200">
    <!-- 見出し -->
    <div class="mb-4">
      <h2 class="text-xl font-bold text-slate-800 dark:text-slate-100">{{ t('pastRanking.title') }}</h2>
      <p class="text-xs text-slate-500 dark:text-slate-400 mt-1 leading-relaxed">{{ t('pastRanking.desc') }}</p>
    </div>

    <!-- 読み込み中 -->
    <p v-if="isLoading" class="py-10 text-center text-sm text-slate-400">{{ t('pastRanking.loading') }}</p>

    <!-- 取得失敗 -->
    <p v-else-if="error" class="py-10 text-center text-sm text-rose-500">{{ error }}</p>

    <!-- まだ 1 作品もアーカイブされていない（初回のスナップショット前）状態 -->
    <p v-else-if="versions.length === 0" class="py-10 text-center text-sm text-slate-400">
      {{ t('pastRanking.empty') }}
    </p>

    <template v-else>
      <!-- 作品セレクタ + BEAT/RATE 切替 -->
      <div class="flex flex-wrap items-center gap-2 mb-4">
        <button
          v-for="v in versions"
          :key="v.version"
          type="button"
          class="px-3 py-1.5 rounded text-xs font-bold border transition-colors"
          :class="v.version === selectedVersion
            ? 'bg-blue-600 border-blue-600 text-white'
            : 'bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:border-blue-400'"
          @click="selectVersion(v.version)"
        >
          {{ v.version }} {{ v.name }}
        </button>

        <div class="ml-auto flex rounded overflow-hidden border border-slate-200 dark:border-slate-600">
          <button
            type="button"
            class="px-3 py-1.5 text-xs font-bold transition-colors"
            :class="mode === 'beat' ? 'bg-blue-600 text-white' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300'"
            @click="mode = 'beat'"
          >BEAT-PT</button>
          <button
            type="button"
            class="px-3 py-1.5 text-xs font-bold transition-colors"
            :class="mode === 'rate' ? 'bg-blue-600 text-white' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300'"
            @click="mode = 'rate'"
          >RATE-PT</button>
        </div>
      </div>

      <!-- 撮影時点の情報。「いつの記録か」が分からないと順位の意味が読めないため必ず出す -->
      <p v-if="selected" class="text-[11px] text-slate-400 dark:text-slate-500 mb-3">
        {{ t('pastRanking.players', { count: String(selected.userCount) }) }}
        <span v-if="formatCapturedAt(selected.capturedAt)">
          / {{ t('pastRanking.capturedAt', { date: formatCapturedAt(selected.capturedAt) }) }}
        </span>
      </p>

      <!-- 順位表。横幅が足りない端末では表だけを横スクロールさせる -->
      <div class="overflow-x-auto">
        <table class="w-full min-w-[520px] text-sm">
          <thead>
            <tr class="border-b border-slate-200 dark:border-slate-700 text-[11px] text-slate-400 uppercase tracking-wider">
              <th class="py-2 pl-2 text-left w-16">{{ t('pastRanking.rank') }}</th>
              <th class="py-2 text-left">{{ t('pastRanking.player') }}</th>
              <th class="py-2 px-2 text-center w-20">{{ t('pastRanking.tier') }}</th>
              <th class="py-2 pr-2 text-right">{{ mode === 'rate' ? 'RATE-PT' : 'BEAT-PT' }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in sortedRows"
              :key="`${row.userId}-${row.iidxId}`"
              class="border-b border-slate-100 dark:border-slate-700/60"
              :class="isMe(row) ? 'bg-blue-50/60 dark:bg-blue-900/20' : ''"
            >
              <td class="py-3 pl-2 font-bold tabular-nums text-slate-500 dark:text-slate-400">
                {{ rankOf(row) ?? '―' }}
              </td>
              <td class="py-3">
                <div class="flex items-center gap-2">
                  <span class="font-bold" :class="isMe(row) ? 'text-blue-700 dark:text-blue-300' : 'text-slate-800 dark:text-slate-100'">
                    {{ row.displayName || 'Unnamed Player' }}
                  </span>
                  <span
                    v-if="(row.privacyLevel ?? 1) !== 0"
                    class="text-xs text-slate-400"
                    :title="(row.privacyLevel ?? 1) === 2 ? '非公開' : 'フレンドのみ公開'"
                  >🔒</span>
                  <span v-if="isMe(row)" class="text-[9px] font-bold px-1.5 py-0.5 rounded bg-blue-500 text-white">
                    {{ t('ranking.you') }}
                  </span>
                </div>
              </td>
              <td class="py-3 px-2">
                <div class="flex justify-center">
                  <!-- 外枠は付けない: この画面ではアイコン本体がその作品の到達点そのものを表す -->
                  <RankIcon
                    :rank-name="tierOf(row).name"
                    :tier="tierOf(row).tier"
                    size="sm"
                    disable-party
                    lite
                    :is-supporter="row.isSupporter"
                  />
                </div>
              </td>
              <td class="py-3 pr-2 text-right">
                <span class="text-lg font-bold tabular-nums text-slate-800 dark:text-slate-100">
                  {{ ptOf(row).toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>
