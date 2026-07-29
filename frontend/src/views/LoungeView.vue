<script setup lang="ts">
/**
 * 【ビューの役割】 隠しページ `/lounge` の軍人将棋（伝統的な 23 枚型・2 本橋の盤）対局画面。
 *
 * サイドバー等を描画しないスタンドアロン。beat-seeker のどこからもリンクしておらず、
 * URL を知っている人だけが辿り着ける。ログインも不要で、入室コードを共有すれば
 * アカウントを持たない友人とそのまま遊べる。
 *
 * 画面は 4 つの局面を 1 コンポーネントで切り替える:
 *   1. ロビー   … 部屋を作る／入室コードで入る
 *   2. 入室待ち … 作った部屋のコードを相手に伝える
 *   3. 布陣     … 自陣 23 マスに駒を並べて提出する
 *   4. 対局     … 交互に着手。決着後は双方の駒を開示して感想戦
 *
 * 情報の非対称性はすべてサーバ側で担保している。相手の駒種は API 応答にそもそも含まれず、
 * 交戦の判定（審判）もサーバが行う。したがってこのコンポーネントに「隠す」ロジックは無い。
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import {
  useGunjin,
  loadSeat,
  saveSeat,
  clearSeat,
  loadName,
  saveName,
  type BoardCell,
  type BoardDef,
  type GameState,
  type LogEntry,
  type PieceTypeName,
  type Seat,
} from '../composables/useGunjin';

const { isBusy, fetchBoard, createRoom, joinRoom, fetchState, suggestSetup, submitSetup, sendMove, resign } =
  useGunjin();

/** 盤のマスに載せる駒の略称（3 文字以内に収めてタイルからはみ出さないようにする）。 */
const SHORT_LABEL: Record<PieceTypeName, string> = {
  TAISHO: '大将',
  CHUJO: '中将',
  SHOSHO: '少将',
  TAISA: '大佐',
  CHUSA: '中佐',
  SHOSA: '少佐',
  TAII: '大尉',
  CHUI: '中尉',
  SHOI: '少尉',
  HIKOKI: '飛行機',
  TANK: 'タンク',
  KIHEI: '騎兵',
  KOHEI: '工兵',
  JIRAI: '地雷',
  GUNKI: '軍旗',
  SPY: 'スパイ',
};

/** 駒箱の並び順（階級順 → 特殊駒）。サーバの enum 順と同じだが意図を明示するため定義する。 */
const TRAY_ORDER: PieceTypeName[] = [
  'TAISHO', 'CHUJO', 'SHOSHO', 'TAISA', 'CHUSA', 'SHOSA', 'TAII', 'CHUI', 'SHOI',
  'HIKOKI', 'TANK', 'KIHEI', 'KOHEI', 'JIRAI', 'GUNKI', 'SPY',
];

// ============================================================
// 状態
// ============================================================

/** 盤の形と駒の定義（サーバから 1 度だけ取得）。 */
const board = ref<BoardDef | null>(null);
/** 自分の座席（入室コード + トークン）。null ならロビー。 */
const seat = ref<Seat | null>(null);
/** 自分の視点から見た盤の状態。 */
const state = ref<GameState | null>(null);
/** エラーメッセージ（赤帯で表示）。 */
const error = ref('');
/** 初回ロード中フラグ。 */
const isLoading = ref(true);

/** ロビーの入力欄。 */
const nameInput = ref(loadName());
const codeInput = ref('');

/** 布陣の下書き（マス ID → 駒種）。提出するまでサーバへ送らない。 */
const draft = ref<Record<string, PieceTypeName>>({});
/** 駒箱で選択中の駒種。 */
const pickedType = ref<PieceTypeName | null>(null);

/** 対局中に選択している自分の駒のマス。 */
const selectedCell = ref<string | null>(null);

/** ルール解説パネルの開閉。 */
const showRules = ref(false);
/** 投了確認の表示。 */
const confirmResign = ref(false);
/** 入室コードをコピーしたことを知らせるフラグ。 */
const copied = ref(false);

/** ポーリングのタイマー ID。 */
let pollTimer: number | undefined;

// ============================================================
// 導出
// ============================================================

/** 現在の局面。 */
const phase = computed<'LOBBY' | 'WAITING' | 'SETUP' | 'PLAYING' | 'FINISHED'>(() => {
  if (!seat.value || !state.value) return 'LOBBY';
  return state.value.status;
});

/** 後手は盤を 180 度回して見る（自陣が手前に来るように）。 */
const flipped = computed(() => (state.value?.me ?? seat.value?.player ?? 1) === 2);

/** 布陣を編集中か（提出前）。 */
const editingSetup = computed(() => state.value?.status === 'SETUP' && !state.value.iAmReady);

/** 駒種名 → 定義。 */
const typeDefs = computed(() => {
  const map = new Map<PieceTypeName, { label: string; count: number; category: string; rank: number; immobile: boolean; canCaptureHq: boolean }>();
  for (const t of board.value?.pieceTypes ?? []) map.set(t.name, t);
  return map;
});

/** 駒種ごとの残り枚数（布陣中の駒箱に出す）。 */
const remaining = computed(() => {
  const rest = new Map<PieceTypeName, number>();
  for (const t of board.value?.pieceTypes ?? []) rest.set(t.name, t.count);
  for (const type of Object.values(draft.value)) {
    rest.set(type, (rest.get(type) ?? 0) - 1);
  }
  return rest;
});

/** 布陣で置き終えた駒数。 */
const placedCount = computed(() => Object.keys(draft.value).length);

/**
 * 布陣の下書きから、指定マスに置いた軍旗の実効強さを求める。
 *
 * 軍旗はすぐ後ろ（自陣の最後列に近い側）の味方駒と同じ強さになるので、
 * 並べている最中もそれを表示しないと布陣が組めない。
 * 提出後はサーバが計算した値（{@code flagStrength}）を使う。
 *
 * @param flagCellId 軍旗を置いたマス ID
 * @returns 実効の駒種。後ろが空なら null（＝最弱）
 */
function draftFlagStrength(flagCellId: string): PieceTypeName | null {
  const b = board.value;
  if (!b) return null;
  const here = b.cells.find((c) => c.id === flagCellId);
  if (!here) return null;
  const me = state.value?.me ?? seat.value?.player ?? 1;
  // 「後ろ」= 前進方向の逆。先手は段が増える側、後手は減る側。
  const behindRow = here.row + (me === 1 ? 1 : -1);
  // 総司令部は 2 列幅なので、列の範囲が重なるマスを後ろとみなす。
  const behind = b.cells.find(
    (c) => c.row === behindRow && c.col <= here.col + here.span - 1 && here.col <= c.col + c.span - 1
  );
  if (!behind) return null;
  const type = draft.value[behind.id];
  return type && type !== 'GUNKI' ? type : null;
}

/** 自分の駒（マス ID → 駒）。布陣編集中は下書きを、それ以外はサーバの盤を使う。 */
const myByCell = computed(() => {
  const map = new Map<string, { type: PieceTypeName; moves?: string[]; flagStrength?: PieceTypeName | null }>();
  if (editingSetup.value) {
    for (const [cell, type] of Object.entries(draft.value)) {
      // 軍旗の実効強さは布陣中も見せる（後ろに何を置くかが布陣の要なので）。
      map.set(cell, { type, flagStrength: type === 'GUNKI' ? draftFlagStrength(cell) : undefined });
    }
  } else {
    for (const p of state.value?.myPieces ?? []) {
      // 自分の駒には必ず駒種が入る（サーバが伏せるのは相手の駒だけ）。
      if (!p.type) continue;
      map.set(p.cell, { type: p.type, moves: p.moves, flagStrength: p.flagStrength });
    }
  }
  return map;
});

/** 相手の駒（マス ID → 駒。決着前は type が入らない = 伏せ札）。 */
const oppByCell = computed(() => {
  const map = new Map<string, { type?: PieceTypeName }>();
  for (const p of state.value?.opponentPieces ?? []) map.set(p.cell, { type: p.type });
  return map;
});

/** 選択中の駒が動ける先のマス。 */
const legalTargets = computed(() => {
  if (!selectedCell.value) return new Set<string>();
  return new Set(myByCell.value.get(selectedCell.value)?.moves ?? []);
});

/** 直前の 1 手（盤のハイライト用）。 */
const lastMove = computed<LogEntry | null>(() => {
  const log = state.value?.log ?? [];
  return log.length ? log[log.length - 1] : null;
});

/** 盤の描画に必要な情報をマスごとにまとめたもの。 */
const cellViews = computed(() => {
  const b = board.value;
  if (!b) return [];
  const me = state.value?.me ?? seat.value?.player ?? 1;
  return b.cells.map((cell) => {
    // 後手視点では盤を 180 度回転させる（段と列の両方を反転）。
    const row = flipped.value ? b.rows - 1 - cell.row : cell.row;
    const col = flipped.value ? b.cols - cell.col - cell.span : cell.col;
    const mine = myByCell.value.get(cell.id);
    const theirs = oppByCell.value.get(cell.id);
    return {
      cell,
      style: {
        gridRow: String(row + 1),
        gridColumn: `${col + 1} / span ${cell.span}`,
      },
      /** 自分の駒（駒種まで見える）。 */
      mine,
      /** 相手の駒（決着前は駒種なし）。 */
      theirs,
      /** 自陣のマスか。 */
      isMyCamp: cell.zone === me,
      /** 自分の総司令部か。 */
      isMyHq: cell.hqOf === me,
      /** 相手の総司令部か（占領すれば勝ち）。 */
      isEnemyHq: cell.hqOf !== 0 && cell.hqOf !== me,
      isSelected: selectedCell.value === cell.id,
      isTarget: legalTargets.value.has(cell.id),
      isLastFrom: lastMove.value?.from === cell.id,
      isLastTo: lastMove.value?.to === cell.id,
    };
  });
});

/** 対局結果の見出し。 */
const resultHeadline = computed(() => {
  const s = state.value;
  if (!s || s.status !== 'FINISHED' || !s.winner) return '';
  const won = s.winner === s.me;
  const reason =
    s.winReason === 'HQ' ? '総司令部の占領' :
    s.winReason === 'ANNIHILATED' ? '動かせる駒の全滅' :
    s.winReason === 'RESIGNED' ? '投了' : '';
  return `${won ? '勝ち' : '負け'}（${reason}）`;
});

/** 手番の案内文。 */
const turnNotice = computed(() => {
  const s = state.value;
  if (!s) return '';
  if (s.status === 'SETUP') {
    if (!s.iAmReady) return '自陣に駒を並べてください';
    return s.opponentReady ? '対局を開始します' : '相手が布陣中です…';
  }
  if (s.status === 'PLAYING') return s.myTurn ? 'あなたの手番です' : `${s.opponentName ?? '相手'}の手番です…`;
  return '';
});

// ============================================================
// マスの表示名（棋譜用）
// ============================================================

/** 列番号 → 表示文字（a〜f）。 */
function colLetter(col: number): string {
  return String.fromCharCode(97 + col);
}

/**
 * マス ID を人が読める表記にする。
 * 総司令部は特別扱いし、それ以外は「列文字 + 段番号（上から 1）」で表す。
 */
function cellLabel(id: string): string {
  const cell = board.value?.cells.find((c) => c.id === id);
  if (!cell) return id;
  if (cell.hqOf !== 0) {
    const mine = cell.hqOf === (state.value?.me ?? 1);
    return mine ? '自総司令部' : '敵総司令部';
  }
  return `${colLetter(cell.col)}${cell.row + 1}`;
}

/** 棋譜 1 行のテキスト。駒種は含まない（審判の宣告だけ）。 */
function logLine(entry: LogEntry): string {
  const me = state.value?.me ?? 1;
  const who = entry.o === me ? '自分' : '相手';
  const verdict =
    entry.r === 'MOVE' ? '進軍' :
    entry.r === 'ATTACKER_WINS' ? '攻撃側の勝ち' :
    entry.r === 'DEFENDER_WINS' ? '守備側の勝ち' : '相打ち';
  return `${entry.n}. ${who} ${cellLabel(entry.from)}→${cellLabel(entry.to)} ${verdict}`;
}

// ============================================================
// 通信・ポーリング
// ============================================================

/** 盤の状態を取り直す。 */
async function refresh(): Promise<void> {
  if (!seat.value) return;
  try {
    state.value = await fetchState(seat.value);
    error.value = '';
  } catch (e) {
    // 部屋が消えている（掃除された等）場合はロビーへ戻す。
    error.value = e instanceof Error ? e.message : '通信に失敗しました';
    if (error.value.includes('見つかりません') || error.value.includes('確認できません')) {
      leaveRoom();
    }
  }
}

/** 数秒おきに盤を取り直す。相手の着手はこれで反映される。 */
function startPolling(): void {
  stopPolling();
  pollTimer = window.setInterval(() => {
    // 決着後はもう変化しないのでポーリングを止める。
    if (state.value?.status === 'FINISHED') {
      stopPolling();
      return;
    }
    void refresh();
  }, 2000);
}

/** ポーリングを止める。 */
function stopPolling(): void {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer);
    pollTimer = undefined;
  }
}

// ============================================================
// 操作
// ============================================================

/** 部屋を作って先手として入室する。 */
async function onCreate(): Promise<void> {
  error.value = '';
  try {
    saveName(nameInput.value);
    seat.value = await createRoom(nameInput.value);
    saveSeat(seat.value);
    await refresh();
    startPolling();
  } catch (e) {
    error.value = e instanceof Error ? e.message : '部屋の作成に失敗しました';
  }
}

/** 入室コードで後手として入室する。 */
async function onJoin(): Promise<void> {
  error.value = '';
  try {
    saveName(nameInput.value);
    seat.value = await joinRoom(codeInput.value, nameInput.value);
    saveSeat(seat.value);
    codeInput.value = '';
    await refresh();
    startPolling();
  } catch (e) {
    error.value = e instanceof Error ? e.message : '入室に失敗しました';
  }
}

/** 部屋を出てロビーへ戻る（対局は放棄）。 */
function leaveRoom(): void {
  stopPolling();
  clearSeat();
  seat.value = null;
  state.value = null;
  draft.value = {};
  pickedType.value = null;
  selectedCell.value = null;
  confirmResign.value = false;
}

/** 入室コードを共有用テキストとしてクリップボードへコピーする。 */
async function copyInvite(): Promise<void> {
  const code = state.value?.roomCode ?? seat.value?.roomCode ?? '';
  const text = `${window.location.origin}${window.location.pathname}\n入室コード: ${code}`;
  try {
    await navigator.clipboard.writeText(text);
    copied.value = true;
    window.setTimeout(() => (copied.value = false), 2000);
  } catch {
    error.value = 'コピーできませんでした。手動で控えてください';
  }
}

/** 駒箱の駒種を選ぶ／選択を外す。 */
function pickType(type: PieceTypeName): void {
  if ((remaining.value.get(type) ?? 0) <= 0 && pickedType.value !== type) return;
  pickedType.value = pickedType.value === type ? null : type;
}

/** 布陣中にマスをクリックしたときの処理（置く／取り上げる）。 */
function onSetupCellClick(cellId: string, isMyCamp: boolean): void {
  if (!isMyCamp) return;
  const picked = pickedType.value;
  if (picked) {
    // 置き換えの場合、元の駒は自動的に駒箱へ戻る（remaining は下書きから逆算している）。
    if ((remaining.value.get(picked) ?? 0) <= 0 && draft.value[cellId] !== picked) return;
    draft.value = { ...draft.value, [cellId]: picked };
    // 打ち止めになったら選択を外す。
    if ((remaining.value.get(picked) ?? 0) <= 0) pickedType.value = null;
    return;
  }
  // 駒箱で何も選んでいなければ、そのマスの駒を取り上げる。
  if (draft.value[cellId]) {
    const next = { ...draft.value };
    const removed = next[cellId];
    delete next[cellId];
    draft.value = next;
    pickedType.value = removed;
  }
}

/** サーバにおまかせ布陣を作ってもらって下書きに流し込む。 */
async function onSuggestSetup(): Promise<void> {
  if (!seat.value) return;
  error.value = '';
  try {
    draft.value = await suggestSetup(seat.value);
    pickedType.value = null;
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'おまかせ配置に失敗しました';
  }
}

/** 布陣の下書きを空にする。 */
function onClearSetup(): void {
  draft.value = {};
  pickedType.value = null;
}

/** 布陣を提出する。 */
async function onSubmitSetup(): Promise<void> {
  if (!seat.value) return;
  error.value = '';
  try {
    state.value = await submitSetup(seat.value, draft.value);
    pickedType.value = null;
  } catch (e) {
    error.value = e instanceof Error ? e.message : '布陣の提出に失敗しました';
  }
}

/** 対局中にマスをクリックしたときの処理（駒を選ぶ／動かす）。 */
async function onPlayCellClick(cellId: string): Promise<void> {
  const s = state.value;
  if (!s || s.status !== 'PLAYING' || !s.myTurn || isBusy.value) return;

  // 動かす先として選んだ。
  if (selectedCell.value && legalTargets.value.has(cellId)) {
    const from = selectedCell.value;
    selectedCell.value = null;
    error.value = '';
    try {
      state.value = await sendMove(seat.value!, from, cellId);
    } catch (e) {
      error.value = e instanceof Error ? e.message : '着手できませんでした';
      await refresh();
    }
    return;
  }

  // 自分の駒を選ぶ（動けない駒は選択できない）。同じ駒をもう一度押すと選択解除。
  const mine = myByCell.value.get(cellId);
  if (mine) {
    selectedCell.value = selectedCell.value === cellId ? null : (mine.moves?.length ? cellId : null);
  } else {
    selectedCell.value = null;
  }
}

/** マスがクリックされたときの入口（局面で処理を振り分ける）。 */
function onCellClick(view: { cell: BoardCell; isMyCamp: boolean }): void {
  if (editingSetup.value) {
    onSetupCellClick(view.cell.id, view.isMyCamp);
    return;
  }
  void onPlayCellClick(view.cell.id);
}

/** 投了する。 */
async function onResign(): Promise<void> {
  if (!seat.value) return;
  confirmResign.value = false;
  try {
    state.value = await resign(seat.value);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '投了に失敗しました';
  }
}

// ============================================================
// ライフサイクル
// ============================================================

onMounted(async () => {
  try {
    board.value = await fetchBoard();
  } catch (e) {
    error.value = e instanceof Error ? e.message : '盤の取得に失敗しました';
  }
  // 前回の対局が残っていれば復帰する（リロードしても続けられる）。
  const saved = loadSeat();
  if (saved) {
    seat.value = saved;
    await refresh();
    if (state.value) startPolling();
  }
  isLoading.value = false;
});

onBeforeUnmount(stopPolling);
</script>

<template>
  <div class="min-h-screen bg-stone-900 text-stone-100">
    <div class="mx-auto w-full max-w-5xl px-4 py-6 sm:py-8">
      <!-- ============================================================ -->
      <!-- ヘッダ -->
      <!-- ============================================================ -->
      <header class="mb-6 flex flex-wrap items-end justify-between gap-3">
        <div>
          <p class="text-[11px] font-semibold uppercase tracking-[0.2em] text-amber-500/80">Lounge</p>
          <h1 class="mt-0.5 text-2xl font-bold tracking-wide sm:text-3xl">軍人将棋</h1>
          <p class="mt-1 text-xs text-stone-400">
            伝統的な 23 枚型・2 本橋の盤。審判はサーバが務めます。
          </p>
        </div>
        <button
          type="button"
          class="rounded-lg border border-stone-700 px-3 py-1.5 text-xs font-bold text-stone-300 transition hover:bg-stone-800"
          @click="showRules = !showRules"
        >
          {{ showRules ? 'ルールを閉じる' : 'ルールを見る' }}
        </button>
      </header>

      <!-- ============================================================ -->
      <!-- エラー帯 -->
      <!-- ============================================================ -->
      <p
        v-if="error"
        class="mb-4 rounded-lg border border-red-800 bg-red-950/60 px-3 py-2 text-sm text-red-200"
      >
        {{ error }}
      </p>

      <!-- ============================================================ -->
      <!-- ルール解説 -->
      <!-- ============================================================ -->
      <section
        v-if="showRules"
        class="mb-6 space-y-4 rounded-xl border border-stone-700 bg-stone-800/60 p-4 text-sm leading-relaxed text-stone-300"
      >
        <div>
          <h2 class="mb-1 font-bold text-stone-100">目的</h2>
          <p>
            相手の<b class="text-amber-400">総司令部</b>を 大将・中将・少将・大佐・中佐・少佐 のいずれかで占領すれば勝ち。
            相手の動かせる駒を全滅させても勝ちです。互いの駒種は見えません。
          </p>
        </div>

        <div>
          <h2 class="mb-1 font-bold text-stone-100">駒（片陣営 23 枚）</h2>
          <p class="mb-1">
            強さの序列: 大将 &gt; 中将 &gt; 少将 &gt; 大佐 &gt; 中佐 &gt; 少佐 &gt; 大尉 &gt; 中尉 &gt; 少尉
          </p>
          <ul class="ml-4 list-disc space-y-0.5">
            <li><b>スパイ</b> … 大将にのみ勝ち、他には全敗</li>
            <li><b>ヒコーキ</b> … 将官にのみ負け、他には勝つ。地雷も除去できる</li>
            <li><b>タンク</b> … 将官・ヒコーキ・工兵に負け、他には勝つ</li>
            <li><b>騎兵</b> … スパイと工兵にのみ勝つ</li>
            <li><b>工兵</b> … 地雷・スパイ・タンクに勝ち、階級駒には負ける</li>
            <li><b>地雷</b> … 動けない。ヒコーキと工兵にのみ負け、他の駒とは相打ち（道連れにして自分も爆発）</li>
            <li><b>軍旗</b> … 動けない。すぐ後ろの味方駒と同じ強さになる（後ろが空だと最弱）</li>
          </ul>
          <p class="mt-1 text-xs text-stone-400">同じ駒種同士がぶつかると相打ちです。</p>
        </div>

        <div>
          <h2 class="mb-1 font-bold text-stone-100">駒の動き</h2>
          <ul class="ml-4 list-disc space-y-0.5">
            <li>階級駒・スパイ … 前後左右 1 マス</li>
            <li>タンク・騎兵 … 前後左右 1 マス、または前方 2 マス（駒は飛び越せない）</li>
            <li>工兵 … 前後左右に何マスでも（駒は飛び越せない）</li>
            <li>ヒコーキ … 縦は同じ列を何マスでも（駒も河も飛び越える）、横は 1 マス</li>
            <li>地雷・軍旗 … 動けない</li>
          </ul>
        </div>

        <div>
          <h2 class="mb-1 font-bold text-stone-100">盤</h2>
          <p>
            自陣は 4 段 × 6 列。総司令部が 2 マス分を占めるので置けるマスはちょうど 23 =駒数と一致し、
            布陣では自陣が隙間なく埋まります。中央は河で、渡れるのは
            <b class="text-emerald-400">突入口</b>（左右 2 本の橋）だけ。ヒコーキだけが河を無視して侵入できます。
          </p>
          <p class="mt-1 text-xs text-stone-400">
            敵の総司令部マスへは誰でも進めますが、占領して勝てるのは大将〜少佐の 6 種だけです。
          </p>
        </div>

        <div>
          <h2 class="mb-1 font-bold text-stone-100">審判について</h2>
          <p>
            駒がぶつかったときの判定はサーバが行い、<b>「どちらが勝ったか」だけ</b>を両者に伝えます。
            相手の駒種は API 応答にも棋譜にも含まれないため、通信を覗いても布陣は分かりません。
          </p>
        </div>
      </section>

      <p v-if="isLoading" class="py-16 text-center text-sm text-stone-400">読み込み中…</p>

      <!-- ============================================================ -->
      <!-- 局面 1: ロビー -->
      <!-- ============================================================ -->
      <section v-else-if="phase === 'LOBBY'" class="max-w-md space-y-6">
        <div>
          <label class="mb-1 block text-xs font-bold text-stone-400">あなたの名前</label>
          <input
            v-model="nameInput"
            type="text"
            maxlength="40"
            placeholder="名無し"
            class="w-full rounded-lg border border-stone-700 bg-stone-800 px-3 py-2 text-sm focus:border-amber-600 focus:outline-none"
          />
        </div>

        <div class="rounded-xl border border-stone-700 bg-stone-800/60 p-4">
          <h2 class="mb-1 font-bold">部屋を作る</h2>
          <p class="mb-3 text-xs text-stone-400">先手になります。表示された入室コードを友達に伝えてください。</p>
          <button
            type="button"
            :disabled="isBusy"
            class="w-full rounded-lg bg-amber-700 px-4 py-2 text-sm font-bold text-white transition hover:bg-amber-600 disabled:opacity-50"
            @click="onCreate()"
          >
            部屋を作る
          </button>
        </div>

        <div class="rounded-xl border border-stone-700 bg-stone-800/60 p-4">
          <h2 class="mb-1 font-bold">部屋に入る</h2>
          <p class="mb-3 text-xs text-stone-400">後手になります。友達から聞いた 4 文字の入室コードを入力してください。</p>
          <div class="flex gap-2">
            <input
              v-model="codeInput"
              type="text"
              maxlength="8"
              placeholder="7K2M"
              class="w-32 rounded-lg border border-stone-700 bg-stone-800 px-3 py-2 font-mono text-lg uppercase tracking-widest focus:border-amber-600 focus:outline-none"
              @keyup.enter="onJoin()"
            />
            <button
              type="button"
              :disabled="isBusy || !codeInput.trim()"
              class="flex-1 rounded-lg bg-stone-700 px-4 py-2 text-sm font-bold text-white transition hover:bg-stone-600 disabled:opacity-50"
              @click="onJoin()"
            >
              入る
            </button>
          </div>
        </div>
      </section>

      <!-- ============================================================ -->
      <!-- 局面 2〜4: 対局中の部屋 -->
      <!-- ============================================================ -->
      <section v-else-if="state" class="space-y-5">
        <!-- 対局情報バー -->
        <div class="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-stone-700 bg-stone-800/60 px-4 py-3">
          <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
            <span class="font-mono text-lg font-bold tracking-widest text-amber-400">{{ state.roomCode }}</span>
            <span class="text-stone-300">
              {{ state.me === 1 ? '先手' : '後手' }} <b>{{ state.myName }}</b>
              <span class="text-stone-500"> vs </span>
              <b>{{ state.opponentName ?? '（入室待ち）' }}</b>
            </span>
            <span v-if="state.status === 'PLAYING'" class="text-xs text-stone-400">{{ state.moveCount }} 手目</span>
          </div>
          <div class="flex items-center gap-2">
            <button
              type="button"
              class="rounded-lg border border-stone-700 px-3 py-1.5 text-xs font-bold text-stone-300 transition hover:bg-stone-800"
              @click="copyInvite()"
            >
              {{ copied ? 'コピーしました' : '招待をコピー' }}
            </button>
            <button
              type="button"
              class="rounded-lg border border-stone-700 px-3 py-1.5 text-xs font-bold text-stone-400 transition hover:bg-stone-800"
              @click="leaveRoom()"
            >
              部屋を出る
            </button>
          </div>
        </div>

        <!-- 状況の案内 -->
        <div
          v-if="state.status === 'WAITING'"
          class="rounded-xl border border-amber-800/60 bg-amber-950/40 px-4 py-5 text-center"
        >
          <p class="text-sm text-amber-200">対戦相手の入室を待っています…</p>
          <p class="mt-2 text-xs text-amber-200/70">
            この URL と入室コード <b class="font-mono">{{ state.roomCode }}</b> を友達に伝えてください。
          </p>
        </div>

        <div
          v-else-if="state.status === 'FINISHED'"
          class="rounded-xl px-4 py-4 text-center"
          :class="state.winner === state.me ? 'border border-emerald-700 bg-emerald-950/50' : 'border border-red-800 bg-red-950/50'"
        >
          <p class="text-lg font-bold" :class="state.winner === state.me ? 'text-emerald-300' : 'text-red-300'">
            {{ resultHeadline }}
          </p>
          <p class="mt-1 text-xs text-stone-400">互いの駒を開示しています（感想戦）。</p>
          <button
            type="button"
            class="mt-3 rounded-lg bg-amber-700 px-4 py-2 text-sm font-bold text-white transition hover:bg-amber-600"
            @click="leaveRoom()"
          >
            もう一局
          </button>
        </div>

        <p v-else class="text-sm font-bold" :class="state.myTurn || state.status === 'SETUP' ? 'text-amber-400' : 'text-stone-400'">
          {{ turnNotice }}
        </p>

        <!--
          盤と右カラムの 2 段組み。flex-col + lg:flex-row ではなく grid を使っているのは、
          後から読み込まれる src/output.css に素の `.flex-col` があり `lg:flex-row` を打ち消してしまうため。
          grid なら既定が 1 列（縦積み）で、lg 以上だけ 2 列にできて衝突しない。
        -->
        <div class="grid gap-5 lg:grid-cols-[26rem_minmax(0,1fr)] lg:items-start">
          <!-- ============================================================ -->
          <!-- 盤 -->
          <!-- ============================================================ -->
          <div v-if="board && state.status !== 'WAITING'" class="mx-auto w-full max-w-[26rem] shrink-0">
            <div
              class="relative grid gap-1 rounded-xl border border-stone-700 bg-stone-800 p-1.5"
              :style="{
                gridTemplateColumns: `repeat(${board.cols}, minmax(0, 1fr))`,
                gridTemplateRows: 'repeat(4, minmax(0, 1fr)) minmax(0, 0.6fr) minmax(0, 0.6fr) repeat(4, minmax(0, 1fr))',
                aspectRatio: '6 / 9.2',
              }"
            >
              <!-- 河（中央 2 段の帯）。突入口のマスはこの上に重なる。 -->
              <div
                class="pointer-events-none rounded bg-sky-950/70"
                style="grid-row: 5 / span 2; grid-column: 1 / -1"
              ></div>

              <!-- マス -->
              <button
                v-for="v in cellViews"
                :key="v.cell.id"
                type="button"
                :disabled="state.status === 'FINISHED' || (!editingSetup && !state.myTurn)"
                :style="v.style"
                class="relative z-10 flex flex-col items-center justify-center rounded border text-center transition"
                :class="[
                  // 地の色: 自陣 / 敵陣 / 突入口
                  v.cell.gate
                    ? 'border-sky-700 bg-sky-900/70'
                    : v.isMyCamp
                      ? 'border-stone-600 bg-stone-700/60'
                      : 'border-stone-700 bg-stone-800/80',
                  // 総司令部は縁を光らせる。ring ではなく outline を使うのは、
                  // 駒が乗っていても勝利目標のマスが分かるようにしつつ、
                  // ring を選択・移動可能先の表示に空けておくため（両者を同時に描ける）。
                  v.isMyHq ? 'outline outline-2 outline-amber-500/70' : '',
                  v.isEnemyHq ? 'outline outline-2 outline-rose-400/80' : '',
                  // 選択・移動可能先・直前の手
                  v.isSelected ? 'ring-2 ring-amber-400' : '',
                  v.isTarget ? 'ring-2 ring-emerald-400' : '',
                  v.isLastFrom || v.isLastTo ? 'ring-1 ring-sky-400/70' : '',
                  editingSetup && v.isMyCamp ? 'cursor-pointer hover:border-amber-600' : '',
                ]"
                @click="onCellClick(v)"
              >
                <!-- 総司令部の見出し（駒が乗っていないときだけ） -->
                <span
                  v-if="v.cell.hqOf !== 0 && !v.mine && !v.theirs"
                  class="text-[9px] leading-none"
                  :class="v.isMyHq ? 'text-amber-600/80' : 'text-red-700/80'"
                >総司令部</span>

                <!-- 自分の駒（駒種が見える） -->
                <span
                  v-else-if="v.mine"
                  class="w-full px-0.5 text-[10px] font-bold leading-tight sm:text-[11px]"
                  :class="typeDefs.get(v.mine.type)?.immobile ? 'text-stone-400' : 'text-amber-100'"
                >
                  {{ SHORT_LABEL[v.mine.type] }}
                  <!-- 軍旗の実効強さ（本人にだけ見える） -->
                  <span
                    v-if="v.mine.type === 'GUNKI'"
                    class="block text-[8px] font-normal text-stone-400"
                  >{{ v.mine.flagStrength ? `=${SHORT_LABEL[v.mine.flagStrength]}` : '=最弱' }}</span>
                </span>

                <!-- 相手の駒（決着前は伏せ札） -->
                <span
                  v-else-if="v.theirs"
                  class="flex h-full w-full items-center justify-center rounded"
                  :class="v.theirs.type ? '' : 'bg-red-900/60'"
                >
                  <span v-if="v.theirs.type" class="px-0.5 text-[10px] font-bold leading-tight text-red-200 sm:text-[11px]">
                    {{ SHORT_LABEL[v.theirs.type] }}
                  </span>
                  <span v-else class="text-[13px] leading-none text-red-300/70">●</span>
                </span>

                <!-- 突入口の表示（空のとき） -->
                <span v-else-if="v.cell.gate" class="text-[9px] leading-none text-sky-500/70">突入口</span>
              </button>
            </div>

            <p class="mt-2 text-center text-[11px] text-stone-500">
              手前があなたの陣地です
              <span v-if="state.status === 'PLAYING' && state.myTurn"> ／ 駒を押すと動ける先が緑で光ります</span>
            </p>
          </div>

          <!-- ============================================================ -->
          <!-- 右カラム: 布陣の駒箱 / 対局中の情報 -->
          <!-- ============================================================ -->
          <div class="min-w-0 flex-1 space-y-4">
            <!-- 布陣の駒箱 -->
            <div v-if="editingSetup && board" class="rounded-xl border border-stone-700 bg-stone-800/60 p-4">
              <div class="mb-2 flex items-center justify-between">
                <h2 class="font-bold">駒箱</h2>
                <span class="text-xs" :class="placedCount === board.armySize ? 'text-emerald-400' : 'text-stone-400'">
                  {{ placedCount }} / {{ board.armySize }}
                </span>
              </div>
              <p class="mb-3 text-xs text-stone-400">
                駒を選んで自陣のマスを押すと置けます。何も選んでいない状態でマスを押すと取り上げます。
              </p>
              <div class="grid grid-cols-4 gap-1.5">
                <button
                  v-for="type in TRAY_ORDER"
                  :key="type"
                  type="button"
                  :disabled="(remaining.get(type) ?? 0) <= 0 && pickedType !== type"
                  class="flex flex-col items-center rounded border py-1.5 text-[11px] font-bold transition disabled:opacity-30"
                  :class="pickedType === type
                    ? 'border-amber-500 bg-amber-900/50 text-amber-200'
                    : 'border-stone-600 bg-stone-700/60 text-stone-200 hover:border-amber-700'"
                  @click="pickType(type)"
                >
                  <span>{{ SHORT_LABEL[type] }}</span>
                  <span class="text-[10px] font-normal text-stone-400">×{{ remaining.get(type) ?? 0 }}</span>
                </button>
              </div>
              <div class="mt-3 flex flex-wrap gap-2">
                <button
                  type="button"
                  :disabled="isBusy"
                  class="rounded-lg border border-stone-600 px-3 py-1.5 text-xs font-bold text-stone-300 transition hover:bg-stone-700 disabled:opacity-50"
                  @click="onSuggestSetup()"
                >
                  おまかせ配置
                </button>
                <button
                  type="button"
                  class="rounded-lg border border-stone-600 px-3 py-1.5 text-xs font-bold text-stone-300 transition hover:bg-stone-700"
                  @click="onClearSetup()"
                >
                  全部戻す
                </button>
                <button
                  type="button"
                  :disabled="isBusy || placedCount !== board.armySize"
                  class="ml-auto rounded-lg bg-amber-700 px-4 py-1.5 text-xs font-bold text-white transition hover:bg-amber-600 disabled:opacity-40"
                  @click="onSubmitSetup()"
                >
                  この布陣で開始
                </button>
              </div>
              <p class="mt-2 text-[11px] text-stone-500">
                提出すると布陣は変えられません。軍旗はすぐ後ろに強い駒を置くと硬くなります。
              </p>
            </div>

            <!-- 失った駒（自分の分だけ。決着後は相手の分も） -->
            <div v-if="state.status !== 'SETUP'" class="rounded-xl border border-stone-700 bg-stone-800/60 p-4">
              <h2 class="mb-2 font-bold">失った駒</h2>
              <div class="space-y-2 text-xs">
                <div>
                  <p class="mb-1 text-stone-400">自分（{{ state.myDead.length }} 枚）</p>
                  <p v-if="!state.myDead.length" class="text-stone-500">まだありません</p>
                  <div v-else class="flex flex-wrap gap-1">
                    <span
                      v-for="(d, i) in state.myDead"
                      :key="`mine-${i}`"
                      class="rounded bg-stone-700 px-1.5 py-0.5 font-bold text-stone-300 line-through"
                    >{{ d.type ? SHORT_LABEL[d.type] : '?' }}</span>
                  </div>
                </div>
                <div>
                  <p class="mb-1 text-stone-400">相手（{{ state.opponentDead.length }} 枚）</p>
                  <p v-if="!state.opponentDead.length" class="text-stone-500">まだありません</p>
                  <div v-else class="flex flex-wrap gap-1">
                    <span
                      v-for="(d, i) in state.opponentDead"
                      :key="`opp-${i}`"
                      class="rounded bg-red-950/70 px-1.5 py-0.5 font-bold text-red-300"
                      :class="d.type ? 'line-through' : ''"
                    >{{ d.type ? SHORT_LABEL[d.type] : '？' }}</span>
                  </div>
                  <p v-if="state.status !== 'FINISHED'" class="mt-1 text-[11px] text-stone-500">
                    相手が失った駒の種類は決着まで分かりません。
                  </p>
                </div>
              </div>
            </div>

            <!-- 棋譜（審判の宣告ログ） -->
            <div v-if="state.log.length" class="rounded-xl border border-stone-700 bg-stone-800/60 p-4">
              <h2 class="mb-2 font-bold">審判の記録</h2>
              <ol class="max-h-64 space-y-0.5 overflow-y-auto text-xs">
                <li
                  v-for="entry in [...state.log].reverse()"
                  :key="entry.n"
                  class="font-mono"
                  :class="[
                    entry.n === state.moveCount ? 'text-amber-300' : 'text-stone-400',
                    entry.r !== 'MOVE' ? 'font-bold' : '',
                  ]"
                >{{ logLine(entry) }}</li>
              </ol>
            </div>

            <!-- 投了 -->
            <div v-if="state.status === 'PLAYING'">
              <button
                v-if="!confirmResign"
                type="button"
                class="text-xs font-bold text-stone-500 transition hover:text-red-400"
                @click="confirmResign = true"
              >
                投了する
              </button>
              <div v-else class="flex items-center gap-2 text-xs">
                <span class="text-stone-300">投了しますか？</span>
                <button
                  type="button"
                  class="rounded bg-red-800 px-3 py-1 font-bold text-white transition hover:bg-red-700"
                  @click="onResign()"
                >
                  はい
                </button>
                <button
                  type="button"
                  class="rounded border border-stone-600 px-3 py-1 font-bold text-stone-300 transition hover:bg-stone-700"
                  @click="confirmResign = false"
                >
                  やめる
                </button>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>
