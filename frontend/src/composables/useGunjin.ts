/**
 * 【Composable の役割】 隠しページ `/lounge` の軍人将棋 API をまとめて提供する。
 *
 * beat-seeker のログインは使わない。部屋を作る／入るときにサーバから発行される
 * トークンが「自分がどちらの陣営か」の証明で、それを localStorage に保管して
 * リロードしても同じ対局に戻れるようにしている。
 *
 * 重要: 相手の駒種はそもそも API 応答に含まれない（サーバが伏せて返す）。
 * つまりフロント側で「隠す」処理は一切しておらず、DevTools で通信を覗いても
 * 相手の布陣は分からない。審判役はサーバが務めている。
 */
import { ref } from 'vue';
import { API_BASE } from './constants';

/** 駒種の識別子（サーバの PieceType enum と対応）。 */
export type PieceTypeName =
  | 'TAISHO' | 'CHUJO' | 'SHOSHO'
  | 'TAISA' | 'CHUSA' | 'SHOSA'
  | 'TAII' | 'CHUI' | 'SHOI'
  | 'HIKOKI' | 'TANK' | 'KIHEI' | 'KOHEI' | 'JIRAI' | 'GUNKI' | 'SPY';

/** 盤の 1 マス。盤は長方形でないのでサーバ定義をそのまま描画に使う。 */
export interface BoardCell {
  id: string;
  /** 段（0 = 後手最後列 〜 9 = 先手最後列）。 */
  row: number;
  /** 列の左端。 */
  col: number;
  /** 横幅。総司令部だけ 2。 */
  span: number;
  /** 1 = 先手陣 / 2 = 後手陣 / 0 = 河（突入口）。 */
  zone: number;
  /** 総司令部ならその所有者（1 or 2）、そうでなければ 0。 */
  hqOf: number;
  /** 突入口か。 */
  gate: boolean;
}

/** 駒種の定義（枚数・分類・強さ）。凡例と駒箱の表示に使う。 */
export interface PieceTypeDef {
  name: PieceTypeName;
  label: string;
  count: number;
  category: 'SHOKAN' | 'SAKAN' | 'IKAN' | 'SPECIAL';
  rank: number;
  immobile: boolean;
  canCaptureHq: boolean;
}

/** GET /api/lounge/board の応答（盤の形と駒の定義）。 */
export interface BoardDef {
  cols: number;
  rows: number;
  /** 片陣営の駒数（= 自陣のマス数）。 */
  armySize: number;
  cells: BoardCell[];
  pieceTypes: PieceTypeDef[];
}

/** 盤上の駒 1 つ。相手の駒では `type` が省かれる（決着後のみ開示される）。 */
export interface ViewPiece {
  id: number;
  cell: string;
  type?: PieceTypeName;
  /** 自分の手番のときだけ入る、その駒が動ける先のマス ID。 */
  moves?: string[];
  /** 軍旗の実効強さ（後ろの駒に依存）。本人にだけ返る。 */
  flagStrength?: PieceTypeName | null;
}

/** 取り除かれた駒。相手の分は決着まで `type` が入らない。 */
export interface DeadPiece {
  moveNo: number;
  type?: PieceTypeName;
}

/** 棋譜の 1 手。駒種は含まれない（審判の宣告だけ）。 */
export interface LogEntry {
  n: number;
  /** 指した側（1 or 2）。 */
  o: number;
  from: string;
  to: string;
  /** MOVE = 交戦なし / ATTACKER_WINS / DEFENDER_WINS / BOTH_LOSE。 */
  r: 'MOVE' | 'ATTACKER_WINS' | 'DEFENDER_WINS' | 'BOTH_LOSE';
}

/** 対局の進行状態。 */
export type GameStatus = 'WAITING' | 'SETUP' | 'PLAYING' | 'FINISHED';

/** 自分の視点から見た盤の状態（相手の駒は伏せられている）。 */
export interface GameState {
  roomCode: string;
  status: GameStatus;
  /** 自分の陣営（1 = 先手 / 2 = 後手）。 */
  me: number;
  myName: string;
  opponentName: string | null;
  turn: number;
  myTurn: boolean;
  iAmReady: boolean;
  opponentReady: boolean;
  moveCount: number;
  /** 盤が変わるたび増える。ポーリングでこれだけ見れば再描画判断できる。 */
  stateVersion: number;
  winner: number | null;
  /** HQ = 総司令部占領 / ANNIHILATED = 動かせる駒の全滅 / RESIGNED = 投了。 */
  winReason: 'HQ' | 'ANNIHILATED' | 'RESIGNED' | null;
  myPieces: ViewPiece[];
  opponentPieces: ViewPiece[];
  myDead: DeadPiece[];
  opponentDead: DeadPiece[];
  log: LogEntry[];
}

/** 入室時にサーバから受け取る本人確認情報。 */
export interface Seat {
  roomCode: string;
  token: string;
  player: number;
}

/** localStorage のキー。リロードしても対局に戻れるようにする。 */
const SEAT_KEY = 'bs_lounge_seat';
/** プレイヤー名を覚えておくキー（次の対局で入力を省くため）。 */
const NAME_KEY = 'bs_lounge_name';

/** 保存済みの座席情報を取り出す（無ければ null）。 */
export function loadSeat(): Seat | null {
  try {
    const raw = localStorage.getItem(SEAT_KEY);
    if (!raw) return null;
    const seat = JSON.parse(raw) as Seat;
    return seat?.roomCode && seat?.token ? seat : null;
  } catch {
    return null;
  }
}

/** 座席情報を保存する。 */
export function saveSeat(seat: Seat): void {
  localStorage.setItem(SEAT_KEY, JSON.stringify(seat));
}

/** 座席情報を破棄する（部屋を出るとき）。 */
export function clearSeat(): void {
  localStorage.removeItem(SEAT_KEY);
}

/** 前回使ったプレイヤー名。 */
export function loadName(): string {
  return localStorage.getItem(NAME_KEY) ?? '';
}

/** プレイヤー名を覚える。 */
export function saveName(name: string): void {
  localStorage.setItem(NAME_KEY, name);
}

/** エラー応答の `error` フィールドを拾って Error にする。 */
async function toError(res: Response, fallback: string): Promise<Error> {
  const body = await res.json().catch(() => ({} as { error?: string }));
  return new Error(body?.error || `${fallback} (${res.status})`);
}

/**
 * 【Composable の役割】 軍人将棋の対局 API を関数の束として返す。
 *
 * 状態そのもの（盤・手番）は View 側で持ち、ここは通信だけを担当する。
 */
export function useGunjin() {
  /** 通信中フラグ（ボタンの二重押し防止に使う）。 */
  const isBusy = ref(false);

  /** 通信を実行しつつ isBusy を面倒見るラッパ。 */
  const guard = async <T>(fn: () => Promise<T>): Promise<T> => {
    isBusy.value = true;
    try {
      return await fn();
    } finally {
      isBusy.value = false;
    }
  };

  /** 盤の形と駒の定義を取得する（対局に入る前でも取れる静的データ）。 */
  const fetchBoard = async (): Promise<BoardDef> => {
    const res = await fetch(`${API_BASE}/api/lounge/board`);
    if (!res.ok) throw await toError(res, '盤の取得に失敗しました');
    return (await res.json()) as BoardDef;
  };

  /** 部屋を作って先手として入室する。 */
  const createRoom = (name: string): Promise<Seat> =>
    guard(async () => {
      const res = await fetch(`${API_BASE}/api/lounge/rooms`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      });
      if (!res.ok) throw await toError(res, '部屋の作成に失敗しました');
      return (await res.json()) as Seat;
    });

  /** 入室コードで後手として入室する。 */
  const joinRoom = (roomCode: string, name: string): Promise<Seat> =>
    guard(async () => {
      const res = await fetch(`${API_BASE}/api/lounge/rooms/join`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ roomCode, name }),
      });
      if (!res.ok) throw await toError(res, '入室に失敗しました');
      return (await res.json()) as Seat;
    });

  /** 自分から見た盤の状態を取得する（ポーリングの本体）。 */
  const fetchState = async (seat: Seat): Promise<GameState> => {
    const res = await fetch(
      `${API_BASE}/api/lounge/rooms/${encodeURIComponent(seat.roomCode)}?token=${encodeURIComponent(seat.token)}`
    );
    if (!res.ok) throw await toError(res, '対局の取得に失敗しました');
    return (await res.json()) as GameState;
  };

  /** おまかせ布陣を生成してもらう（提出はされない）。 */
  const suggestSetup = (seat: Seat): Promise<Record<string, PieceTypeName>> =>
    guard(async () => {
      const res = await fetch(
        `${API_BASE}/api/lounge/rooms/${encodeURIComponent(seat.roomCode)}/suggest-setup?token=${encodeURIComponent(seat.token)}`
      );
      if (!res.ok) throw await toError(res, 'おまかせ配置に失敗しました');
      return (await res.json()) as Record<string, PieceTypeName>;
    });

  /** 布陣を提出する。両者が出し終わると対局が始まる。 */
  const submitSetup = (seat: Seat, placements: Record<string, PieceTypeName>): Promise<GameState> =>
    guard(async () => {
      const res = await fetch(
        `${API_BASE}/api/lounge/rooms/${encodeURIComponent(seat.roomCode)}/setup?token=${encodeURIComponent(seat.token)}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ placements }),
        }
      );
      if (!res.ok) throw await toError(res, '布陣の提出に失敗しました');
      return (await res.json()) as GameState;
    });

  /** 1 手指す。交戦の判定はサーバ（審判）が返す状態に反映される。 */
  const sendMove = (seat: Seat, from: string, to: string): Promise<GameState> =>
    guard(async () => {
      const res = await fetch(
        `${API_BASE}/api/lounge/rooms/${encodeURIComponent(seat.roomCode)}/move?token=${encodeURIComponent(seat.token)}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ from, to }),
        }
      );
      if (!res.ok) throw await toError(res, '着手できませんでした');
      return (await res.json()) as GameState;
    });

  /** 投了する。 */
  const resign = (seat: Seat): Promise<GameState> =>
    guard(async () => {
      const res = await fetch(
        `${API_BASE}/api/lounge/rooms/${encodeURIComponent(seat.roomCode)}/resign?token=${encodeURIComponent(seat.token)}`,
        { method: 'POST' }
      );
      if (!res.ok) throw await toError(res, '投了に失敗しました');
      return (await res.json()) as GameState;
    });

  return {
    isBusy,
    fetchBoard,
    createRoom,
    joinRoom,
    fetchState,
    suggestSetup,
    submitSetup,
    sendMove,
    resign,
  };
}
