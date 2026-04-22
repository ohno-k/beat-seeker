<script setup lang="ts">
/**
 * ArenaView.vue
 *
 * 【Viewの役割】
 * beatmania IIDX の「アリーナモード」の対戦履歴を閲覧するページ。
 * ブックマークレット経由で取り込んだ対戦データ（相手DJ名・スコア・順位など）を
 * 一覧／相手別サマリで表示し、各譜面のMAXスコア・グレード判定を付ける。
 *
 * 【主な機能】
 * - 対戦履歴タブ (history / opponents) + サブタブ (all / online / local)
 * - 自分と相手のスコア比較（トップ=金、2位=銀で着色、MAX譜面グレード付き）
 * - 相手別に対戦回数と曲単位の勝敗率を集計
 * - 他人のIDを `viewingUserId` で指定されたら、管理者用エンドポイントに切替
 */
import { ref, onMounted, computed } from 'vue';
import { useAuth } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';
import UnifiedImport from '../components/UnifiedImport.vue';
import { BOOKMARKLET_CODE } from '../utils/bookmarklet';
import { songData as songDataBodyRef, getDifficultyCode } from '../composables/useGameData';
import type { SongDataEntry } from '../composables/useGameData';
import type { ArenaMatch, ArenaPlayer, ArenaSongScore } from '../types/arena';

// 曲データから "タイトル|難易度コード" → ノーツ数 の検索用 Map を構築
// （MAXスコア計算で毎回配列を走査しないよう事前インデックス化）
const songNotesMap = new Map<string, number>();
for (const entry of (songDataBodyRef.value as SongDataEntry[])) {
  songNotesMap.set(`${entry.title}|${entry.difficulty}`, entry.notes);
}

/**
 * 指定譜面のMAXスコアを算出する。
 * 手順1: 難易度が空なら、タイトル末尾 " / ANOTHER" のような形式からフォールバック抽出
 * 手順2: 難易度テキストをコードに変換（useGameData の getDifficultyCode に集約済み）
 * 手順3: Map から該当ノーツ数を取得し、MAXスコア = notes × 2 を返す
 * @param titleRaw 曲名（必要に応じて難易度が含まれている場合もある）
 * @param diffText 難易度テキスト（例: "ANOTHER"）
 * @returns MAXスコア（見つからなければ 0）
 */
const getSongMaxScore = (titleRaw: string, diffText: string): number => {
  let title = titleRaw;
  let diff = diffText;
  // フォールバック: 難易度が空なら、タイトル末尾の " / XXX" から抽出
  // （例: "Silver Bullet / ANOTHER" → title="Silver Bullet", diff="ANOTHER"）
  if (!diff) {
    const sep = title.lastIndexOf(' / ');
    if (sep !== -1) {
      diff = title.substring(sep + 3).trim();
      title = title.substring(0, sep).trim();
    }
  }
  const code = getDifficultyCode(diff);
  if (code === undefined) return 0;
  const notes = songNotesMap.get(`${title}|${code}`);
  return notes ? notes * 2 : 0;
};

const { isLoggedIn } = useAuth();
const { t } = useI18n();

// props: 他人のIDを指定された場合、管理者として閲覧する
const props = defineProps<{
  viewingUserId?: number | null;
}>();

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

// --- 対戦履歴データの state ---
const matches = ref<ArenaMatch[]>([]);    // サーバから取得した対戦履歴一覧
const isFetching = ref(false);            // 取得中フラグ（ローディング表示用）
const expandedMatchId = ref<number | null>(null); // 展開されている試合ID（一度に1試合のみ詳細表示）
const activeTab = ref<'all' | 'online' | 'local'>('all');  // 履歴のサブタブ（全/オンライン/ローカル）
const mainView = ref<'history' | 'opponents'>('history');  // メイン表示モード（履歴/相手別）
const showImportPanel = ref(false);       // インポートパネル（折りたたみ）の開閉状態

// --- 現在のタブでフィルタされた試合一覧 ---
const filteredMatches = computed(() => {
  // battleType の文字列にキーワードを含むかで判定
  if (activeTab.value === 'online') return matches.value.filter(m => m.battleType?.includes('オンライン'));
  if (activeTab.value === 'local') return matches.value.filter(m => m.battleType?.includes('ローカル'));
  return matches.value;
});

// --- 上部に表示する統計（フィルタ済み試合数 + 順位別の集計） ---
const stats = computed(() => {
  // 1位〜4位それぞれのカウント用配列を初期化
  const ranks = [0, 0, 0, 0];
  for (const m of filteredMatches.value) {
    // 自分の順位 (myRank) が 1〜4 の範囲内ならインクリメント
    if (m.myRank >= 1 && m.myRank <= 4) ranks[m.myRank - 1]++;
  }
  return { total: filteredMatches.value.length, ranks };
});

// --- 相手別サマリ表示用 state ---
const expandedOpponent = ref<string | null>(null); // 展開されている相手DJ名

/**
 * DJ名が「人間プレイヤー」かどうかを判定するヘルパー。
 * CPU（例: "CPU1"）や、名前が全てひらがな・カタカナ・漢字のみ（NPCとみなす）は除外。
 * @param djName 判定対象のDJ名
 * @returns 人間プレイヤーと見なせれば true
 */
const isHumanOpponent = (djName: string): boolean => {
  if (/^CPU\d+$/i.test(djName)) return false;
  if (/^[\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF\u3400-\u4DBF\s]+$/.test(djName)) return false;
  return true;
};

/**
 * 相手別の対戦統計を算出する computed。
 * 手順1: 全試合をループし、各プレイヤーごとに Map に集計
 * 手順2: 自分以外・人間プレイヤーのみ集計対象に
 * 手順3: 曲単位で自分との勝敗（songWins / songLosses / songTotal）を比較
 * 手順4: 最後に対戦回数が多い順にソートして配列として返す
 */
const opponentStats = computed(() => {
  const map = new Map<string, {
    djName: string;
    matches: number;
    songWins: number;
    songLosses: number;
    songTotal: number;
  }>();

  for (const m of matches.value) {
    // 自分のプレイヤーオブジェクト（自分のスコアを取得するために使用）
    const myPlayer = m.players.find((p) => p.djName === m.myDjName);
    for (const p of m.players) {
      if (p.djName === m.myDjName) continue;       // 自分自身はスキップ
      if (!isHumanOpponent(p.djName)) continue;    // CPU/NPC はスキップ
      // まだ未登録の相手なら初期エントリを作成
      if (!map.has(p.djName)) {
        map.set(p.djName, { djName: p.djName, matches: 0, songWins: 0, songLosses: 0, songTotal: 0 });
      }
      const entry = map.get(p.djName)!;
      entry.matches++;
      if (myPlayer) {
        // 曲単位で比較: 自分と相手のスコア配列を突き合わせる
        const mySongs: ArenaSongScore[] = myPlayer.songScores || [];
        const theirSongs: ArenaSongScore[] = p.songScores || [];
        const count = Math.min(mySongs.length, theirSongs.length);
        for (let si = 0; si < count; si++) {
          const myScore = mySongs[si]?.score ?? 0;
          const theirScore = theirSongs[si]?.score ?? 0;
          entry.songTotal++;
          if (myScore > theirScore) entry.songWins++;
          else if (myScore < theirScore) entry.songLosses++;
          // タイ（同点）は勝ちにも負けにもカウントせず、songTotal だけ増える
        }
      }
    }
  }

  // 対戦回数の多い順にソートして返却
  return Array.from(map.values()).sort((a, b) => b.matches - a.matches);
});

/**
 * 指定したDJ名と対戦した試合のみを取り出すヘルパー。
 * @param djName 相手のDJ名
 * @returns 該当する試合の配列
 */
const matchesWithOpponent = (djName: string) =>
  matches.value.filter((m) =>
    m.players.some((p) => p.djName !== m.myDjName && p.djName === djName)
  );

/**
 * サーバから対戦履歴を取得する。
 * 手順1: 未ログインなら何もせず終了
 * 手順2: viewingUserId があれば管理者用 `/api/admin/users/.../arena/matches` を叩く
 * 手順3: 通常は自分の `/api/arena/matches` を叩く
 */
const fetchMatches = async () => {
  if (!isLoggedIn.value) return;
  isFetching.value = true;
  try {
    const token = localStorage.getItem('beat-seeker-token');
    // viewingUserId がある場合は他人（管理者閲覧）、無ければ自分のデータ
    const endpoint = props.viewingUserId
      ? `${API_BASE}/api/admin/users/${props.viewingUserId}/arena/matches`
      : `${API_BASE}/api/arena/matches`;
    const res = await fetch(endpoint, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (res.ok) {
      matches.value = await res.json();
    }
  } catch (e) {
    console.error(e);
  } finally {
    isFetching.value = false;
  }
};

// マウント時に1度だけ履歴を取得
onMounted(fetchMatches);

/**
 * 指定IDの試合を削除する。
 * 手順1: 他人閲覧モードの場合は削除させない（早期 return）
 * 手順2: confirm() で最終確認（キャンセルされたら中止）
 * 手順3: DELETE API を呼び、成功したらローカル state からも取り除く
 */
const handleDelete = async (id: number) => {
  if (props.viewingUserId) return;                         // 他人のデータは削除できない
  if (!confirm(t('arena.deleteConfirm'))) return;          // 削除の確認ダイアログ
  const token = localStorage.getItem('beat-seeker-token');
  await fetch(`${API_BASE}/api/arena/matches/${id}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${token}` },
  });
  // サーバの削除成功を待たずにローカル配列から除去（楽観的UI更新）
  matches.value = matches.value.filter(m => m.id !== id);
};

// 順位ラベル（1位=arena.rank1, ... 4位まで。範囲外は "-"）
const rankLabel = (rank: number) => (rank >= 1 && rank <= 4) ? t(`arena.rank${rank}`) : '-';
// 順位ごとの色（1位=金、2位=銀、3位=橙、4位=灰）
const rankColor = (rank: number) => {
  return [
    'text-yellow-600 dark:text-yellow-400 font-black',   // 1位: 金
    'text-slate-500 dark:text-slate-300 font-bold',      // 2位: 銀
    'text-orange-600 dark:text-orange-400 font-bold',    // 3位: 銅
    'text-slate-400 dark:text-slate-500',                // 4位: 灰
  ][rank - 1] ?? '';
};

/**
 * 譜面番号 si について、全プレイヤー中の [トップスコア, 2位スコア] を求める。
 * @param players 試合参加プレイヤー配列
 * @param si 何曲目か（譜面のインデックス）
 * @returns [トップ, 2位 or undefined]。2位は「トップ未満の最高スコア」
 */
const songTopTwo = (players: ArenaPlayer[], si: number): [number, number | undefined] => {
  const scores = players.map((p) => p.songScores?.[si]?.score ?? 0).sort((a, b) => b - a);
  const top = scores[0] ?? 0;
  const second = scores.find(s => s < top); // 同点トップが複数いる場合は、トップ未満の最初の値
  return [top, second];
};

/**
 * スコアの着色クラスを返す。
 * トップ（>0）は金、2位は銀、それ以外は通常色。
 */
const scoreColorClass = (score: number, top: number, second: number | undefined): string => {
  if (score === top && top > 0) return 'text-yellow-500 dark:text-yellow-400 font-black';
  if (second !== undefined && score === second) return 'text-slate-400 dark:text-slate-200 font-bold';
  return 'text-slate-600 dark:text-slate-400';
};

/**
 * 実MAXスコアに対する達成率からグレードを判定する。
 * 閾値: A+(6/9=66.67%), AA-(72.23%), AA+(7/9=77.78%), AAA-(83.34%), AAA+(8/9=88.89%), MAX-(94.45%), MAX
 * @param score 実スコア
 * @param maxScore その譜面のMAX（notes×2）
 * @returns グレード文字列（該当しなければ空文字）
 */
const scoreGrade = (score: number, maxScore: number): string => {
  if (maxScore === 0 || score <= 0) return '';
  if (score >= maxScore) return 'MAX';
  const pct = score / maxScore;
  if (pct >= 0.9445) return 'MAX-';
  if (pct >= 8 / 9) return 'AAA+';
  if (pct >= 0.8334) return 'AAA-';
  if (pct >= 7 / 9) return 'AA+';
  if (pct >= 0.7223) return 'AA-';
  if (pct >= 6 / 9) return 'A+';
  return '';
};

/**
 * グレードのデルタ（上位/下位グレードまでの差分）を返す。
 * 「+」側は現グレード基準からの上振れ、「-」側は次グレードまでの残り。
 * 例: グレード AA+ なら "AA+ 閾値からいくら超えているか" を、
 *     AA- なら "AA+ 閾値までいくら足りないか" を返す。
 */
const scoreGradeDelta = (score: number, maxScore: number): string => {
  if (maxScore === 0 || score <= 0) return '';
  const grade = scoreGrade(score, maxScore);
  if (grade === 'A+')   return `${score - Math.ceil(maxScore * 6 / 9)}`;  // A+ 閾値（6/9）からの差
  if (grade === 'AA-')  return `${Math.ceil(maxScore * 7 / 9) - score}`;  // AA+ 閾値（7/9）までの残り
  if (grade === 'AA+')  return `${score - Math.ceil(maxScore * 7 / 9)}`;
  if (grade === 'AAA-') return `${Math.ceil(maxScore * 8 / 9) - score}`;
  if (grade === 'AAA+') return `${score - Math.ceil(maxScore * 8 / 9)}`;
  if (grade === 'MAX-') return `${maxScore - score}`;                     // MAX までの残り
  return '';
};

// グレードラベルの色（MAX=金、MAX-=紫、AAA+=琥珀。それ以外は色なし）
const gradeColor = (grade: string): string => {
  if (grade === 'MAX') return 'text-yellow-500 dark:text-yellow-400';
  if (grade === 'MAX-') return 'text-purple-500 dark:text-purple-400';
  if (grade === 'AAA+') return 'text-amber-500 dark:text-amber-400';
  return '';
};

// アリーナクラス（A1〜A5）用のバッジ背景色。未知のクラスはデフォルトの灰色。
const classColor = (cls: string) => {
  const map: Record<string, string> = {
    A1: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/40 dark:text-yellow-300',
    A2: 'bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-300',
    A3: 'bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-300',
    A4: 'bg-purple-100 text-purple-800 dark:bg-purple-900/40 dark:text-purple-300',
    A5: 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300',
  };
  return map[cls] ?? 'bg-slate-100 text-slate-700 dark:bg-slate-700 dark:text-slate-300';
};

</script>

<template>
  <div class="w-full max-w-5xl mx-auto space-y-8 animate-fade-in">
    <!-- ページヘッダ（タイトル + サブタイトル） -->
    <div>
      <h1 class="text-2xl font-extrabold text-slate-900 dark:text-white tracking-tight">{{ t('arena.title') }}</h1>
      <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">{{ t('arena.subtitle') }}</p>
    </div>

    <!-- 未ログイン時: ログインを促すメッセージのみ表示 -->
    <div v-if="!isLoggedIn" class="p-8 text-center bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700">
      <p class="text-slate-500 dark:text-slate-400">{{ t('arena.loginPrompt') }}</p>
    </div>

    <template v-else>
      <!-- 統計バー: 総試合数 + 1〜4位の獲得回数を横並びに表示 -->
      <div v-if="matches.length > 0" class="grid grid-cols-5 gap-3">
        <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-4 text-center">
          <p class="text-2xl font-black text-slate-800 dark:text-white">{{ stats.total }}</p>
          <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">{{ t('arena.totalMatches') }}</p>
        </div>
        <div v-for="(cnt, i) in stats.ranks" :key="i" class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-4 text-center">
          <p class="text-2xl font-black" :class="rankColor(i + 1)">{{ cnt }}</p>
          <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">{{ rankLabel(i + 1) }}</p>
        </div>
      </div>

      <!-- インポートパネル（折りたたみ式）。他人閲覧モード時は非表示。 -->
      <div v-if="!props.viewingUserId" class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden">
        <!-- トグル行: クリックで showImportPanel を反転 -->
        <button
          @click="showImportPanel = !showImportPanel"
          :aria-expanded="showImportPanel"
          aria-controls="arena-import-panel"
          class="w-full flex items-center justify-between px-5 py-3.5 hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors"
        >
          <span class="flex items-center gap-2 text-sm font-bold text-slate-700 dark:text-slate-200">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
            </svg>
            {{ t('arena.importData') }}
          </span>
          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-slate-400 transition-transform" :class="showImportPanel ? 'rotate-180' : ''" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
          </svg>
        </button>

        <!-- 展開時: 統合インポートコンポーネント（ブックマークレットコード + 手動貼り付け） -->
        <div v-if="showImportPanel" id="arena-import-panel" class="border-t border-slate-100 dark:border-slate-700 p-5">
          <UnifiedImport :bookmarklet-code="BOOKMARKLET_CODE" @close="showImportPanel = false; fetchMatches()" />
        </div>
      </div>

      <!-- メイン本体: 対戦履歴 / 相手別サマリの切り替え表示 -->
      <div class="space-y-3">
        <div class="flex items-center justify-between gap-2 flex-wrap">
          <!-- メイン表示モード切替（履歴 / 相手別） -->
          <div class="flex gap-1 bg-slate-100 dark:bg-slate-800 rounded-xl p-1 border border-slate-200 dark:border-slate-700">
            <button
              v-for="v in [{ id: 'history', label: t('arena.matchHistory') }, { id: 'opponents', label: t('arena.opponents') }]"
              :key="v.id"
              @click="mainView = v.id as 'history' | 'opponents'"
              class="px-3 py-1 text-xs font-bold rounded-lg transition-colors"
              :class="mainView === v.id
                ? 'bg-white dark:bg-slate-600 text-slate-800 dark:text-white shadow-sm'
                : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
            >{{ v.label }}</button>
          </div>
          <!-- 履歴サブタブ（履歴ビュー時のみ表示）: 全 / オンライン / ローカル -->
          <div v-if="mainView === 'history'" class="flex gap-1 bg-slate-100 dark:bg-slate-800 rounded-xl p-1 border border-slate-200 dark:border-slate-700">
            <button
              v-for="tab in [{ id: 'all', label: t('arena.all') }, { id: 'online', label: t('arena.online') }, { id: 'local', label: t('arena.local') }]"
              :key="tab.id"
              @click="activeTab = tab.id as 'all' | 'online' | 'local'; expandedMatchId = null"
              class="px-3 py-1 text-xs font-bold rounded-lg transition-colors"
              :class="activeTab === tab.id
                ? 'bg-white dark:bg-slate-600 text-slate-800 dark:text-white shadow-sm'
                : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
            >{{ tab.label }}</button>
          </div>
        </div>

        <div v-if="isFetching" class="flex justify-center py-8">
          <div class="w-8 h-8 border-4 border-blue-200 dark:border-blue-900 border-t-blue-600 rounded-full animate-spin"></div>
        </div>

        <div v-else-if="matches.length === 0" class="text-center py-10 text-slate-400 dark:text-slate-500">
          {{ t('arena.noRecords') }}
        </div>

        <!-- 相手別サマリ表示モード: 相手ごとに対戦回数・曲単位勝敗率のテーブル -->
        <template v-else-if="mainView === 'opponents'">
          <div v-if="opponentStats.length === 0" class="text-center py-10 text-slate-400 dark:text-slate-500">
            {{ t('arena.noOpponents') }}
          </div>
          <div v-else class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden">
            <table class="w-full text-sm">
              <thead>
                <tr class="bg-slate-50 dark:bg-slate-900/50 border-b border-slate-100 dark:border-slate-700">
                  <th scope="col" class="px-4 py-3 text-left font-bold text-slate-600 dark:text-slate-400 text-xs">{{ t('arena.opponentTable.djName') }}</th>
                  <th scope="col" class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 text-xs">{{ t('arena.opponentTable.matches') }}</th>
                  <th scope="col" class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 text-xs">{{ t('arena.opponentTable.songWins') }}</th>
                  <th scope="col" class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 text-xs">{{ t('arena.opponentTable.songLosses') }}</th>
                  <th scope="col" class="px-4 py-3 text-left font-bold text-slate-600 dark:text-slate-400 text-xs">{{ t('arena.opponentTable.winRate') }}</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="opp in opponentStats" :key="opp.djName">
                  <!-- 相手1人分の行（クリックで展開/折り畳み） -->
                  <tr
                    class="border-t border-slate-100 dark:border-slate-700 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors"
                    role="button"
                    tabindex="0"
                    :aria-expanded="expandedOpponent === opp.djName"
                    @click="expandedOpponent = expandedOpponent === opp.djName ? null : opp.djName"
                    @keydown.enter.prevent="expandedOpponent = expandedOpponent === opp.djName ? null : opp.djName"
                    @keydown.space.prevent="expandedOpponent = expandedOpponent === opp.djName ? null : opp.djName"
                  >
                    <td class="px-4 py-2.5">
                      <div class="flex items-center gap-2">
                        <svg xmlns="http://www.w3.org/2000/svg" class="h-3 w-3 text-slate-400 transition-transform shrink-0" :class="expandedOpponent === opp.djName ? 'rotate-180' : ''" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
                        </svg>
                        <span class="font-bold text-slate-800 dark:text-slate-100 text-sm">{{ opp.djName }}</span>
                      </div>
                    </td>
                    <td class="px-3 py-2.5 text-center text-sm font-mono font-bold text-slate-700 dark:text-slate-300">{{ opp.matches }}</td>
                    <td class="px-3 py-2.5 text-center text-sm font-bold text-blue-600 dark:text-blue-400">{{ opp.songWins }}</td>
                    <td class="px-3 py-2.5 text-center text-sm font-bold text-rose-500 dark:text-rose-400">{{ opp.songLosses }}</td>
                    <td class="px-4 py-2.5">
                      <div class="flex items-center gap-2 min-w-[80px]">
                        <div class="flex-1 h-1.5 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
                          <div
                            class="h-full bg-blue-500 rounded-full"
                            :style="{ width: opp.songTotal > 0 ? `${Math.round(opp.songWins / opp.songTotal * 100)}%` : '0%' }"
                          ></div>
                        </div>
                        <span class="text-xs font-mono text-slate-500 dark:text-slate-400 w-8 text-right">
                          {{ opp.songTotal > 0 ? Math.round(opp.songWins / opp.songTotal * 100) : 0 }}%
                        </span>
                      </div>
                    </td>
                  </tr>
                  <!-- 展開時: その相手と対戦した各試合の曲単位内訳を表示 -->
                  <tr v-if="expandedOpponent === opp.djName">
                    <td colspan="5" class="p-0 bg-slate-50 dark:bg-slate-900/40">
                      <div class="divide-y divide-slate-100 dark:divide-slate-700/50">
                        <div v-for="m in matchesWithOpponent(opp.djName)" :key="m.id" class="px-4 py-3">
                          <!-- 試合ヘッダ: 対戦タイプ・日時・自分と相手の順位 -->
                          <div class="flex items-center gap-2 mb-2 flex-wrap">
                            <span class="text-[10px] font-bold px-2 py-0.5 bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded-full">{{ m.battleType }}</span>
                            <span class="text-xs font-mono text-slate-500 dark:text-slate-400">{{ m.matchDate }}</span>
                            <span class="text-xs font-bold" :class="rankColor(m.myRank)">{{ t('arena.self') }}: {{ rankLabel(m.myRank) }}</span>
                            <span class="text-[10px] text-slate-400">{{ t('arena.vs') }}</span>
                            <span class="text-xs font-bold" :class="rankColor(m.players.find((p) => p.djName === opp.djName)?.rank ?? 0)">
                              {{ t('arena.opponent') }}: {{ rankLabel(m.players.find((p) => p.djName === opp.djName)?.rank ?? 0) }}
                            </span>
                          </div>
                          <!-- 曲単位の対戦結果テーブル: 自分スコア vs 相手スコア + 差分 -->
                          <table class="w-full text-xs">
                            <tbody>
                              <template v-for="(song, si) in m.songs" :key="si">
                                <tr
                                  v-if="m.players.find((p) => p.djName === m.myDjName)?.songScores?.[si] !== undefined"
                                  class="border-t border-slate-100 dark:border-slate-700/30"
                                >
                                  <td class="py-1.5 pr-3 max-w-[160px]">
                                    <span class="block truncate text-slate-700 dark:text-slate-300 font-medium">{{ song.title }}</span>
                                    <span v-if="song.difficulty" class="text-[10px] text-slate-400 dark:text-slate-500">{{ song.difficulty }}</span>
                                  </td>
                                  <td class="py-1.5 px-2 text-right font-mono w-20"
                                    :class="((m.players.find((p) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0) > (m.players.find((p) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0)) ? 'text-blue-600 dark:text-blue-400 font-bold' : ((m.players.find((p) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0) < (m.players.find((p) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0)) ? 'text-rose-500 dark:text-rose-400' : 'text-slate-600 dark:text-slate-400'"
                                  >{{ (m.players.find((p) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0).toLocaleString() }}</td>
                                  <td class="py-1.5 px-1 text-center text-slate-400 dark:text-slate-500 w-4">{{ t('arena.vs') }}</td>
                                  <td class="py-1.5 px-2 font-mono w-20"
                                    :class="((m.players.find((p) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0) > (m.players.find((p) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0)) ? 'text-blue-600 dark:text-blue-400 font-bold' : ((m.players.find((p) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0) < (m.players.find((p) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0)) ? 'text-rose-500 dark:text-rose-400' : 'text-slate-600 dark:text-slate-400'"
                                  >{{ (m.players.find((p) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0).toLocaleString() }}</td>
                                  <td class="py-1.5 pl-2 font-mono text-right w-14">
                                    <span :class="((m.players.find((p) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0) - (m.players.find((p) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0)) > 0 ? 'text-blue-600 dark:text-blue-400' : ((m.players.find((p) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0) - (m.players.find((p) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0)) < 0 ? 'text-rose-500 dark:text-rose-400' : 'text-slate-400'">
                                      {{ ((m.players.find((p) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0) - (m.players.find((p) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0)) > 0 ? '+' : '' }}{{ (m.players.find((p) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0) - (m.players.find((p) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0) }}
                                    </span>
                                  </td>
                                </tr>
                              </template>
                            </tbody>
                          </table>
                        </div>
                      </div>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>
        </template>

        <!-- 対戦履歴モード: 試合カード一覧 + 展開時に詳細テーブル -->
        <template v-else>
        <div v-if="filteredMatches.length === 0" class="text-center py-10 text-slate-400 dark:text-slate-500">
          {{ t('arena.noFilteredRecords') }}
        </div>

        <div
          v-for="match in filteredMatches"
          :key="match.id"
          class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden"
        >
          <!-- 試合ヘッダ（クリックで詳細テーブルの開閉をトグル） -->
          <div
            class="flex items-center justify-between p-4 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors"
            role="button"
            tabindex="0"
            :aria-expanded="expandedMatchId === match.id"
            @click="expandedMatchId = expandedMatchId === match.id ? null : match.id"
            @keydown.enter.prevent="expandedMatchId = expandedMatchId === match.id ? null : match.id"
            @keydown.space.prevent="expandedMatchId = expandedMatchId === match.id ? null : match.id"
          >
            <div class="flex items-center gap-3 flex-wrap">
              <span class="text-xs font-bold px-2 py-0.5 bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded-full">
                {{ match.battleType }}
              </span>
              <span class="text-sm font-mono text-slate-500 dark:text-slate-400">{{ match.matchDate }}</span>
              <span class="text-xs font-bold px-2 py-0.5 rounded-full" :class="classColor(match.myArenaClass)">
                {{ match.myArenaClass }}
              </span>
              <span class="text-sm font-bold" :class="rankColor(match.myRank)">
                {{ rankLabel(match.myRank) }}
              </span>
              <span class="text-xs text-slate-400 dark:text-slate-500">{{ match.myTotalPt }}pt</span>
            </div>
            <div class="flex items-center gap-2">
              <button
                v-if="!props.viewingUserId"
                @click.stop="handleDelete(match.id)"
                :aria-label="t('arena.deleteConfirm')"
                class="p-1.5 text-slate-300 hover:text-red-500 dark:text-slate-600 dark:hover:text-red-400 transition-colors rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20"
              >
                <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                </svg>
              </button>
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-slate-400 transition-transform" :class="expandedMatchId === match.id ? 'rotate-180' : ''" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </div>
          </div>

          <!-- 試合詳細: 参加者全員を行、曲を列にした横長テーブル -->
          <div v-if="expandedMatchId === match.id" class="border-t border-slate-100 dark:border-slate-700 overflow-x-auto">
            <table class="w-full text-sm min-w-max">
              <thead>
                <tr class="bg-slate-50 dark:bg-slate-900/50">
                  <th scope="col" class="px-4 py-2 text-left font-bold text-slate-600 dark:text-slate-400 text-xs">{{ t('arena.opponentTable.djName') }}</th>
                  <th scope="col" class="px-3 py-2 text-center font-bold text-slate-600 dark:text-slate-400 text-xs">CLS</th>
                  <th scope="col" class="px-3 py-2 text-center font-bold text-slate-600 dark:text-slate-400 text-xs">{{ t('common.total') }}</th>
                  <th scope="col" class="px-3 py-2 text-center font-bold text-slate-600 dark:text-slate-400 text-xs">{{ t('ranking.colRank') }}</th>
                  <th
                    v-for="(song, si) in match.songs"
                    :key="si"
                    scope="col"
                    class="px-3 py-2 text-center font-bold text-slate-600 dark:text-slate-400 text-xs max-w-[120px]"
                  >
                    <span class="block truncate">{{ song.title }}</span>
                    <span class="block text-[10px] text-slate-400 dark:text-slate-500 font-normal">{{ song.difficulty }}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(player, pi) in match.players"
                  :key="pi"
                  class="border-t border-slate-100 dark:border-slate-700/50 transition-colors"
                  :class="player.djName === match.myDjName ? 'bg-blue-50 dark:bg-blue-950/30' : 'hover:bg-slate-50 dark:hover:bg-slate-700/30'"
                >
                  <td class="px-4 py-2 font-bold text-slate-800 dark:text-white text-xs">
                    {{ player.djName }}
                    <span v-if="player.djName === match.myDjName" class="ml-1 text-[10px] font-black text-blue-600 dark:text-blue-400">{{ t('arena.you') }}</span>
                  </td>
                  <td class="px-3 py-2 text-center">
                    <span class="text-[10px] font-bold px-1.5 py-0.5 rounded-full" :class="classColor(player.arenaClass)">{{ player.arenaClass }}</span>
                  </td>
                  <td class="px-3 py-2 text-center text-xs font-mono text-slate-700 dark:text-slate-300">{{ player.totalPt }}pt</td>
                  <td class="px-3 py-2 text-center text-xs font-bold" :class="rankColor(player.rank)">{{ rankLabel(player.rank) }}</td>
                  <td
                    v-for="(ss, si) in player.songScores"
                    :key="si"
                    class="px-3 py-2 text-center"
                  >
                    <!-- スコア本体: トップは金、2位は銀に着色 -->
                    <span
                      class="block text-xs font-mono"
                      :class="scoreColorClass(ss.score, songTopTwo(match.players, si)[0], songTopTwo(match.players, si)[1])"
                    >{{ ss.score.toLocaleString() }}</span>
                    <!-- トップとの差分（トップ以外のプレイヤーに -NNN で表示） -->
                    <span
                      v-if="ss.score < songTopTwo(match.players, si)[0]"
                      class="block text-[10px] font-mono text-rose-400 dark:text-rose-500"
                    >-{{ (songTopTwo(match.players, si)[0] - ss.score).toLocaleString() }}</span>
                    <!-- グレードバッジ（song_data.json 由来の実MAXスコア基準） -->
                    <span
                      v-if="match.songs[si] && scoreGrade(Number(ss.score), getSongMaxScore(match.songs[si].title, String(match.songs[si].difficulty)))"
                      class="block text-[10px] font-bold"
                      :class="gradeColor(scoreGrade(Number(ss.score), getSongMaxScore(match.songs[si].title, String(match.songs[si].difficulty))))"
                    >{{ scoreGrade(Number(ss.score), getSongMaxScore(match.songs[si].title, String(match.songs[si].difficulty))) }}{{ scoreGradeDelta(Number(ss.score), getSongMaxScore(match.songs[si].title, String(match.songs[si].difficulty))) }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
        </template>
      </div>
    </template>
  </div>
</template>
