<script setup lang="ts">
/**
 * 【View の役割】 OBS ブラウザソース用の個人戦順位表ページ (`/obs/individual/:token`)。
 *
 * 認証不要 (バックエンド側で {@code /api/obs/**} を permitAll)。
 * OBS のブラウザソースは「Use custom frame rate」を有効にして 30fps 程度で十分。
 * 背景は完全透過 (rgba(0,0,0,0)) で出力するため、配信側でクロマキー無しに重ねられる。
 *
 * クエリパラメータ:
 *  - {@code interval} ミリ秒。デフォルト 5000 (5 秒)。配信負荷を抑えたい場合は大きくする。
 *  - {@code bg} = "dark" を指定すると半透明黒背景で視認性を上げる。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { API_BASE } from '../composables/constants';

interface ObsStandingsRow {
  prelimRank: number;
  displayName: string;
  prelimPoints: number;
  first: number;
  fourth: number;
  remainingMatches: number;
}
interface ObsStandingsResponse {
  competitionName: string;
  competitionStatus: string;
  rows: ObsStandingsRow[];
}

const route = useRoute();
const token = computed(() => {
  const fromRoute = String(route.params.token || '');
  if (fromRoute) return fromRoute;
  const match = window.location.pathname.match(/^\/obs\/individual\/([^/]+)/);
  return match ? decodeURIComponent(match[1]) : '';
});

const intervalMs = computed(() => {
  const raw = Number(route.query.interval ?? 5000);
  return Number.isFinite(raw) && raw >= 1000 ? raw : 5000;
});
const useDarkBg = computed(() => route.query.bg === 'dark');

const data = ref<ObsStandingsResponse | null>(null);
const errorMessage = ref<string>('');
let pollTimer: ReturnType<typeof setInterval> | null = null;

const fetchOnce = async () => {
  if (!token.value) return;
  try {
    const res = await fetch(`${API_BASE}/api/obs/individual/${encodeURIComponent(token.value)}/standings`, {
      cache: 'no-store',
    });
    if (!res.ok) {
      errorMessage.value = `HTTP ${res.status}`;
      return;
    }
    data.value = (await res.json()) as ObsStandingsResponse;
    errorMessage.value = '';
  } catch (e) {
    errorMessage.value = (e as Error).message;
  }
};

onMounted(async () => {
  // OBS ブラウザソースで重ねるため、透過背景を保証する。
  // body 全体に背景色が当たっていると後ろが透けないので明示的にクリアする。
  document.documentElement.style.background = 'transparent';
  document.body.style.background = 'transparent';
  document.body.style.margin = '0';

  await fetchOnce();
  pollTimer = setInterval(fetchOnce, intervalMs.value);
});

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = null;
});
</script>

<template>
  <div
    class="obs-root"
    :class="{ 'obs-dark': useDarkBg }"
  >
    <div v-if="!data && !errorMessage" class="obs-msg">Loading…</div>
    <div v-else-if="errorMessage" class="obs-msg obs-err">{{ errorMessage }}</div>
    <table v-else-if="data" class="obs-table">
      <thead>
        <tr>
          <th class="col-rank">順位</th>
          <th class="col-name">参加者</th>
          <th class="col-num">ポイント</th>
          <th class="col-num">1位</th>
          <th class="col-num">4位</th>
          <th class="col-num">残試合</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in data.rows"
          :key="row.displayName"
          :class="{ 'is-top1': row.prelimRank === 1, 'is-top2': row.prelimRank === 2, 'is-top3': row.prelimRank === 3 }"
        >
          <td class="col-rank">{{ row.prelimRank }}</td>
          <td class="col-name">{{ row.displayName }}</td>
          <td class="col-num">{{ row.prelimPoints }}</td>
          <td class="col-num">{{ row.first }}</td>
          <td class="col-num">{{ row.fourth }}</td>
          <td class="col-num">{{ row.remainingMatches }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.obs-root {
  /* OBS のブラウザソースに直接乗せる前提なので、デフォルトでは完全透過。
     ?bg=dark を付けた場合だけ半透明の黒地を敷いて文字を読みやすくする。 */
  background: transparent;
  color: #fff;
  font-family: 'Helvetica Neue', Arial, 'Hiragino Sans', 'Yu Gothic', Meiryo, sans-serif;
  font-feature-settings: 'tnum' on, 'lnum' on;
  padding: 16px;
  min-height: 100vh;
  box-sizing: border-box;
}
.obs-dark {
  background: rgba(0, 0, 0, 0.55);
}
.obs-msg {
  font-size: 18px;
  opacity: 0.8;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.8);
}
.obs-err { color: #fca5a5; }

.obs-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0 4px;
  font-size: 22px;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.7);
}
.obs-table thead th {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.05em;
  padding: 6px 10px;
  text-align: center;
  color: #cbd5e1;
  background: rgba(0, 0, 0, 0.4);
  border-bottom: 1px solid rgba(255, 255, 255, 0.2);
}
.obs-table tbody td {
  padding: 8px 10px;
  background: rgba(15, 23, 42, 0.55); /* slate-900 半透明 */
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  border-bottom: 1px solid rgba(0, 0, 0, 0.5);
  text-align: center;
  font-weight: 500;
}
.obs-table tbody tr td:first-child { border-radius: 6px 0 0 6px; }
.obs-table tbody tr td:last-child  { border-radius: 0 6px 6px 0; }

.col-rank { width: 64px; font-weight: 700; font-size: 24px; }
.col-name { text-align: left !important; min-width: 180px; font-weight: 700; }
.col-num  { width: 100px; }

/* 上位ハイライト (BPL 風の金/銀/銅) */
.is-top1 td { background: rgba(202, 138, 4, 0.85); }   /* gold */
.is-top1 .col-rank { color: #fef3c7; }
.is-top2 td { background: rgba(100, 116, 139, 0.85); } /* silver */
.is-top3 td { background: rgba(180, 83, 9, 0.85); }    /* bronze */
</style>
