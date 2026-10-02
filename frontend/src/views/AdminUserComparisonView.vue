<script setup lang="ts">
/**
 * 【View の役割】 管理者が 1 ユーザーを選び、そのユーザーと<b>全ユーザー</b>のスコア勝敗を
 * 勝率の降順で一覧する画面。
 *
 * 以前は「ユーザー A と B を選んで 1 対 1」だったが、
 * 「1 人選んだら全員との勝敗が出る」形に変更した。
 *
 * 構成:
 *  - 管理者 API `/api/admin/users` で全ユーザー一覧を取得し、プルダウンに表示
 *  - ユーザーを選ぶと `/api/admin/users/{id}/comparison` で勝敗集計を取得して表に描く
 *  - 行をクリックすると AdminComparisonModal が開き、その相手との詳細（曲別・非公式難易度別）を見られる
 *
 * 集計元:
 *  - サーバー側の日次バッチ（UserComparisonStatsService）が作った表を読むだけ。
 *    今日ぶんがまだ無ければサーバーがその場で集計するので、画面側は待つだけでよい。
 *  - レベル帯（Lv.10 以下 / Lv.11 / Lv.12）別の内訳で返ってくるので、
 *    トグルで ON になっている帯だけを足し合わせて表示する。
 *
 * 権限:
 *  - useAdmin.isAdmin で表示ガード (実権限チェックはサーバ側)
 *  - 大会管理 (/competition-admin) とは別ルートの汎用管理者機能として独立
 */
import { ref, computed, onMounted, watch } from 'vue';
import { useAdmin } from '../composables/useAdmin';
import { useScores } from '../composables/useScores';
import AdminComparisonModal, { type AdminUserSummary } from '../components/AdminComparisonModal.vue';
import RankIcon from '../components/RankIcon.vue';
import { getRankInfo } from '../utils/beatTier';
import { jstParts } from '../utils/jstTime';

const { isAdmin } = useAdmin();
const { fetchAllUsers, fetchUserComparison, recalculateUserComparison } = useScores();

/** 全ユーザー一覧 (表示名昇順)。プルダウンの選択肢に使う。 */
const users = ref<AdminUserSummary[]>([]);
const loadError = ref<string | null>(null);

/** 比較の主体となるユーザー ID。 */
const selectedId = ref<number | null>(null);
const selectedUser = computed(() => users.value.find(u => u.id === selectedId.value) ?? null);

/** レベル帯トグル。既定は Lv.11 / Lv.12 のみ (モーダルと同じ既定値)。 */
const showLv10Minus = ref(false);
const showLv11 = ref(true);
const showLv12 = ref(true);

/** ON になっているレベル帯のキー。API の levels のキーと対応する。 */
const activeLevelKeys = computed(() => {
  const keys: string[] = [];
  if (showLv10Minus.value) keys.push('LV10MINUS');
  if (showLv11.value) keys.push('LV11');
  if (showLv12.value) keys.push('LV12');
  return keys;
});

/** API から返ってきた 1 相手ぶんのレベル帯別勝敗。 */
interface LevelCounts {
  win: number;
  loss: number;
  draw: number;
  onlySelf: number;
  onlyOpponent: number;
}

/** API レスポンスの opponents 要素。 */
interface OpponentStat {
  userId: number;
  displayName: string;
  iidxId: string;
  /** 総合 BEAT-PT。名前の横に出す BEAT-Tier アイコンの決定に使う。 */
  totalBeatPt: number;
  levels: Record<string, LevelCounts>;
}

/** 表示用に「ON のレベル帯を合算して勝率まで出した」1 行。 */
interface ComparisonRow extends LevelCounts {
  userId: number;
  displayName: string;
  iidxId: string;
  totalBeatPt: number;
  /** 両者プレイ済みの曲数 (win + loss + draw)。勝率の母数。 */
  decided: number;
  /** 勝率 (%)。母数 0 のときは null。 */
  winRate: number | null;
}

const opponents = ref<OpponentStat[]>([]);
/** 集計が作られた日時 (サーバーの日次バッチ実行時刻)。 */
const computedAt = ref<string | null>(null);
const isLoadingStats = ref(false);
const statsError = ref<string | null>(null);
const isRecalculating = ref(false);
const recalcMessage = ref<string | null>(null);

/**
 * 【computed の役割】 ON になっているレベル帯を合算し、勝率降順に並べた表を作る。
 *
 * 並び順は「勝率降順 → 母数の多い順 → 名前順」。
 * 勝率が同じなら、より多くの曲で比較できているほうを上に置く
 * (1 勝 0 敗の 100% より 80 勝 20 敗の 80% のほうが情報として重い、という考え方ではなく、
 *  あくまで同率のときの安定した並びを作るためのタイブレーク)。
 * 母数 0 (両者プレイ済みの曲が無い) の相手は勝率を出しようがないので常に末尾。
 */
const rows = computed<ComparisonRow[]>(() => {
  const keys = activeLevelKeys.value;
  const result = opponents.value.map(o => {
    const sum: LevelCounts = { win: 0, loss: 0, draw: 0, onlySelf: 0, onlyOpponent: 0 };
    keys.forEach(key => {
      const counts = o.levels[key];
      if (!counts) return;
      sum.win += counts.win;
      sum.loss += counts.loss;
      sum.draw += counts.draw;
      sum.onlySelf += counts.onlySelf;
      sum.onlyOpponent += counts.onlyOpponent;
    });
    const decided = sum.win + sum.loss + sum.draw;
    return {
      userId: o.userId,
      displayName: o.displayName,
      iidxId: o.iidxId,
      totalBeatPt: o.totalBeatPt ?? 0,
      ...sum,
      decided,
      winRate: decided > 0 ? (sum.win / decided) * 100 : null,
    };
  });

  return result.sort((a, b) => {
    // 勝率が出せない相手 (母数 0) は常に末尾へ。
    if (a.winRate == null && b.winRate == null) return a.displayName.localeCompare(b.displayName, 'ja');
    if (a.winRate == null) return 1;
    if (b.winRate == null) return -1;
    if (b.winRate !== a.winRate) return b.winRate - a.winRate;
    if (b.decided !== a.decided) return b.decided - a.decided;
    return a.displayName.localeCompare(b.displayName, 'ja');
  });
});

/** 全相手を通した総合成績。ヘッダーのサマリーに出す。 */
const totals = computed(() => {
  const acc = rows.value.reduce((sum, r) => {
    sum.win += r.win;
    sum.loss += r.loss;
    sum.draw += r.draw;
    return sum;
  }, { win: 0, loss: 0, draw: 0 });
  const decided = acc.win + acc.loss + acc.draw;
  return { ...acc, decided, winRate: decided > 0 ? (acc.win / decided) * 100 : null };
});

/** BEAT-PT から BEAT-Tier（アイコンの称号名と段階）を引く。 */
const beatTier = (pt: number | null | undefined) => getRankInfo(pt ?? 0);

/** 勝率を「64.3%」形式に整形する。母数 0 は "-"。 */
const formatRate = (rate: number | null) => rate == null ? '-' : `${rate.toFixed(1)}%`;

/** 集計日時を「2026/08/27 04:00」形式（JST 固定）に整形する。 */
const formatComputedAt = (value: string | null) => {
  if (!value) return '未集計';
  const p = jstParts(value);
  if (!p) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${p.year}/${pad(p.month)}/${pad(p.day)} ${pad(p.hour)}:${pad(p.minute)}`;
};

/**
 * 【関数の役割】 選択中ユーザーの勝敗集計を取得する。
 *
 * サーバー側で「今日ぶんが無ければその場で集計」まで面倒を見てくれるので、
 * ここは素直に叩いて待つだけでよい。
 */
const loadStats = async () => {
  if (selectedId.value == null) {
    opponents.value = [];
    computedAt.value = null;
    return;
  }
  isLoadingStats.value = true;
  statsError.value = null;
  recalcMessage.value = null;
  try {
    const res = await fetchUserComparison(selectedId.value);
    opponents.value = res?.opponents ?? [];
    computedAt.value = res?.computedAt ?? null;
  } catch (e: any) {
    opponents.value = [];
    computedAt.value = null;
    statsError.value = e?.message ?? '勝敗集計の取得に失敗しました';
  } finally {
    isLoadingStats.value = false;
  }
};

watch(selectedId, () => { loadStats(); });

/** 日次バッチを待たずに全ユーザーぶん再集計し、表示中のユーザーを読み直す。 */
const recalculate = async () => {
  if (isRecalculating.value) return;
  isRecalculating.value = true;
  recalcMessage.value = null;
  statsError.value = null;
  try {
    const res = await recalculateUserComparison();
    recalcMessage.value = res?.message ?? '再集計しました';
    await loadStats();
  } catch (e: any) {
    statsError.value = e?.message ?? '再集計に失敗しました';
  } finally {
    isRecalculating.value = false;
  }
};

/** 詳細モーダルで表示中の相手。null ならモーダルは閉じている。 */
const detailOpponent = ref<AdminUserSummary | null>(null);
const openDetail = (row: ComparisonRow) => {
  detailOpponent.value = { id: row.userId, displayName: row.displayName, iidxId: row.iidxId };
};
const closeDetail = () => {
  detailOpponent.value = null;
};

const isLoadingUsers = ref(false);

onMounted(async () => {
  if (!isAdmin.value) return;
  isLoadingUsers.value = true;
  try {
    const list: any[] = await fetchAllUsers();
    users.value = list
      .map(u => ({
        id: u.id,
        displayName: u.displayName ?? '(no name)',
        iidxId: u.iidxId ?? '',
      }))
      .sort((a, b) => a.displayName.localeCompare(b.displayName, 'ja'));
  } catch (e: any) {
    loadError.value = e?.message ?? 'ユーザー一覧の取得に失敗しました';
  } finally {
    isLoadingUsers.value = false;
  }
});
</script>

<template>
  <div class="max-w-5xl mx-auto p-4 sm:p-6 space-y-6">
    <header class="space-y-1">
      <h1 class="text-2xl sm:text-3xl font-bold text-slate-900 dark:text-white">
        ユーザー間スコア比較 (管理者)
      </h1>
      <p class="text-sm font-bold text-slate-500 dark:text-slate-400">
        1 ユーザーを選ぶと、全ユーザーとの EX-SCORE 勝敗を勝率の高い順に並べます。
      </p>
    </header>

    <!-- 権限なし時の警告 -->
    <v-alert v-if="!isAdmin" type="warning">
      <p>
        この画面は管理者のみアクセス可能です。
      </p>
    </v-alert>

    <template v-else>
      <!-- ロードエラー -->
      <v-alert v-if="loadError" type="error">
        {{ loadError }}
      </v-alert>

      <!-- 選択カード -->
      <v-card tag="section">
        <v-card-text class="space-y-4">
        <div v-if="isLoadingUsers && users.length === 0" class="flex items-center gap-3">
          <v-progress-circular indeterminate size="20" width="2" />
          ユーザー一覧を取得中...
        </div>

        <v-select
          v-else
          v-model="selectedId"
          :items="users"
          :item-title="(u: AdminUserSummary) => `${u.displayName} (${u.iidxId})`"
          item-value="id"
          label="比較するユーザー"
          placeholder="-- 選択してください --"
          clearable
          hide-details
          class="w-full"
        />

        <!-- レベル帯トグル -->
        <div class="flex flex-wrap items-center gap-x-3 gap-y-2 sm:gap-4">
          <span class="text-sm font-bold">公式レベル</span>
          <v-checkbox v-model="showLv10Minus" color="primary" label="Lv.10以下" density="compact" hide-details class="flex-none" />
          <v-checkbox v-model="showLv11" color="primary" label="Lv.11" density="compact" hide-details class="flex-none" />
          <v-checkbox v-model="showLv12" color="primary" label="Lv.12" density="compact" hide-details class="flex-none" />
        </div>
        </v-card-text>

        <!-- 集計日時と再集計 -->
        <v-divider />
        <v-card-actions class="flex flex-wrap items-center justify-between gap-3">
          <p class="text-xs text-slate-500 dark:text-slate-400 ml-2">
            集計日時: {{ formatComputedAt(computedAt) }}
            <span class="hidden sm:inline">（1 日 1 回のバッチで更新されます）</span>
          </p>
          <v-btn
            color="primary"
            variant="tonal"
            :disabled="isRecalculating"
            @click="recalculate"
          >
            {{ isRecalculating ? '再集計中...' : '今すぐ再集計' }}
          </v-btn>
        </v-card-actions>
        <v-card-text v-if="recalcMessage" class="pt-0 text-success">
          {{ recalcMessage }}
        </v-card-text>
      </v-card>

      <!-- 未選択時の案内 -->
      <v-card v-if="selectedId == null" variant="tonal" class="text-center"><v-card-text>
        <p>
          ユーザーを選択すると、全ユーザーとの勝敗が表示されます。
        </p>
      </v-card-text></v-card>

      <template v-else>
        <!-- 集計エラー -->
        <v-alert v-if="statsError" type="error">
          {{ statsError }}
        </v-alert>

        <!-- ローディング -->
        <div v-if="isLoadingStats" class="flex flex-col items-center justify-center py-16">
          <v-progress-circular indeterminate size="48" width="4" class="mb-4" />
          <p class="text-slate-500 font-bold">勝敗を集計中...</p>
        </div>

        <template v-else>
          <!-- 総合サマリー -->
          <v-card tag="section">
            <v-card-subtitle class="pt-3">
              {{ selectedUser?.displayName }} の全ユーザー通算
            </v-card-subtitle>
            <v-card-text>
            <div class="grid grid-cols-4 gap-2 text-center font-bold">
              <div class="flex flex-col">
                <span class="text-xl sm:text-3xl text-blue-600 dark:text-blue-400">{{ totals.win }}</span>
                <span class="text-[10px] text-slate-400 dark:text-slate-500">WIN</span>
              </div>
              <div class="flex flex-col">
                <span class="text-xl sm:text-3xl text-slate-400 dark:text-slate-500">{{ totals.draw }}</span>
                <span class="text-[10px] text-slate-400 dark:text-slate-500">DRAW</span>
              </div>
              <div class="flex flex-col">
                <span class="text-xl sm:text-3xl text-red-500 dark:text-red-400">{{ totals.loss }}</span>
                <span class="text-[10px] text-slate-400 dark:text-slate-500">LOSS</span>
              </div>
              <div class="flex flex-col">
                <span class="text-xl sm:text-3xl text-emerald-600 dark:text-emerald-400">{{ formatRate(totals.winRate) }}</span>
                <span class="text-[10px] text-slate-400 dark:text-slate-500">勝率</span>
              </div>
            </div>
            </v-card-text>
          </v-card>

          <!-- 対戦相手ランキング -->
          <section>
            <h2 class="text-base sm:text-xl font-bold text-slate-800 dark:text-white mb-3 sm:mb-4 flex items-center gap-2">
              <span class="w-1.5 h-5 sm:h-6 bg-indigo-500 rounded-full"></span>
              相手別 勝敗 (勝率降順)
              <span class="text-xs sm:text-sm text-slate-500 dark:text-slate-400 font-bold">(クリックで詳細)</span>
            </h2>

            <v-card v-if="rows.length === 0" variant="tonal" class="text-center"><v-card-text>
              <p>
                比較できる相手がいません。レベルのトグルを確認してください。
              </p>
            </v-card-text></v-card>

            <v-table v-else hover class="opponent-table">
                <thead>
                  <tr>
                    <th class="w-10 sm:w-16 text-center">#</th>
                    <th>相手</th>
                    <th class="text-center w-16 sm:w-24">勝率</th>
                    <th class="text-center">WIN</th>
                    <th class="text-center">DRAW</th>
                    <th class="text-center">LOSS</th>
                    <th class="text-center">自分のみ</th>
                    <th class="text-center">相手のみ</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="(row, idx) in rows"
                    :key="row.userId"
                    @click="openDetail(row)"
                    class="cursor-pointer select-none"
                  >
                    <td class="text-center font-bold text-slate-400">{{ idx + 1 }}</td>
                    <!-- 列幅を変えないため、アイコンは shrink-0・名前側は min-w-0 で
                         はみ出したときに列を押し広げずに省略されるようにする。 -->
                    <td class="font-bold">
                      <div class="flex items-center gap-2">
                        <RankIcon
                          :rank-name="beatTier(row.totalBeatPt).name"
                          :tier="beatTier(row.totalBeatPt).tier"
                          size="2xs"
                          lite
                          disable-party
                          :title="`${beatTier(row.totalBeatPt).name} ${beatTier(row.totalBeatPt).tier}`"
                        />
                        <div class="flex flex-col min-w-0">
                          <span class="text-slate-800 dark:text-slate-200 truncate">{{ row.displayName }}</span>
                          <span class="text-[10px] text-slate-400 truncate">{{ row.iidxId }}</span>
                        </div>
                      </div>
                    </td>
                    <td class="text-center font-bold">
                      <span class="text-emerald-600 dark:text-emerald-400">{{ formatRate(row.winRate) }}</span>
                      <!-- 勝率バー: 母数 0 のときは描かない -->
                      <div v-if="row.winRate != null" class="mt-1 h-1.5 w-full bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden">
                        <div class="h-full bg-emerald-500" :style="{ width: `${row.winRate}%` }"></div>
                      </div>
                      <div class="text-[10px] text-slate-400 mt-0.5">{{ row.decided }} 曲</div>
                    </td>
                    <td class="text-center font-bold text-blue-600 dark:text-blue-400">{{ row.win }}</td>
                    <td class="text-center font-bold text-slate-400">{{ row.draw }}</td>
                    <td class="text-center font-bold text-red-500 dark:text-red-400">{{ row.loss }}</td>
                    <td class="text-center font-bold text-blue-500/80">{{ row.onlySelf }}</td>
                    <td class="text-center font-bold text-red-500/80">{{ row.onlyOpponent }}</td>
                  </tr>
                </tbody>
            </v-table>
          </section>

          <!-- 注意書き -->
          <v-alert type="info">
            <div class="text-sm min-w-0">
              <p>・集計対象は ANOTHER / LEGGENDARIA 譜面のみ。BEGINNER / NORMAL / HYPER は除外しています。</p>
              <p>・勝率 = WIN ÷ (WIN + DRAW + LOSS)。両者プレイ済みの楽曲だけが母数です。</p>
              <p>・自分のみ / 相手のみ: 片方だけがプレイ済みの楽曲数。勝率には含みません。</p>
              <p>・集計は 1 日 1 回のバッチで更新されます。当日ぶんが無い場合は表示時にその場で集計します。</p>
            </div>
          </v-alert>
        </template>
      </template>
    </template>

    <!-- 詳細比較モーダル (行クリックで開く) -->
    <AdminComparisonModal
      v-if="selectedUser && detailOpponent"
      :user-a="selectedUser"
      :user-b="detailOpponent"
      :is-open="true"
      @close="closeDetail"
    />
  </div>
</template>

<style scoped>
/* 旧 <table class="min-w-[640px]">。v-table は内側に <table> を描くので :deep で当てる（はみ出しは v-table が横スクロール） */
.opponent-table :deep(table) {
  min-width: 640px;
}
</style>
