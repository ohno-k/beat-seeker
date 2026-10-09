import { ref, computed } from 'vue';
import { API_BASE, TOKEN_KEY } from './constants';

/**
 * 【Composable の役割】 ダッシュボードの表示設定（簡易表示モード／表示する項目と並び順）を管理する。
 *
 * 機能:
 *  - 初期値は localStorage から復元（未設定なら初期配置）
 *  - 変更は即座に反映し、ログイン中ならサーバー側プロフィール（users.dashboard_layout）にも同期保存
 *    （useRateTierVisibility と同じ方式。別端末でログインしたとき /api/auth/me 経由で同じ表示を復元する）
 *  - サーバーから返ってきた JSON は {@link applyServerLayout} で取り込む（保存はしない）
 *
 * 設定が効くのは「自分のダッシュボード」だけ。他人の閲覧・共有ページは常に初期配置で表示する
 * （判定は ScoreDashboard 側）。
 *
 * 使い方:
 * ```ts
 * const { layout, isSimple, toggleSimple, setWidgetHidden, moveWidget, resetLayout } = useDashboardLayout();
 * ```
 */

/**
 * ダッシュボードに置けるウィジェット（セクション）の識別子。
 *  - recentPlays: 最近の更新（最後に CSV を取り込んだ日に更新された譜面。同日分はまとめる）
 *  - profile*: プロフィール画面の小見出しをそのまま埋め込む（初期値は非表示）。
 *    成長サマリー / 時系列推移 / クリア状況 / 非公式難易度別クリア状況 / BEAT-PT 上位100曲の難易度分布 / BEAT-PT 上位10曲
 */
export type DashboardWidgetId =
  | 'tier' | 'league' | 'ranking' | 'lv12' | 'recentPlays' | 'diffTable' | 'advice' | 'activity'
  | 'profileSummary' | 'profileTrends' | 'profileClearStatus' | 'profileInformalClear' | 'profileTop100Dist' | 'profileTop10';

/** プロフィール由来のウィジェット（並び順・初期非表示の定義で使う）。 */
export const PROFILE_WIDGET_IDS: readonly DashboardWidgetId[] =
  ['profileSummary', 'profileTrends', 'profileClearStatus', 'profileInformalClear', 'profileTop100Dist', 'profileTop10'];

/**
 * 表示モード。言語設定と同じく 3 択で保存する。
 *  - normal: 通常（初期配置 = {@link NORMAL_WIDGETS}）
 *  - simple: 簡易（ティアをコンパクトに、項目は {@link SIMPLE_WIDGETS} だけ）
 *  - custom: カスタマイズ（order / hidden で決めた項目と並び順）
 * カスタマイズの内容（order / hidden）はモードに関わらず保持するので、通常や簡易に切り替えても消えない。
 */
export type DashboardMode = 'normal' | 'simple' | 'custom';
export const DASHBOARD_MODES: readonly DashboardMode[] = ['normal', 'simple', 'custom'];

export interface DashboardLayout {
  /** 形式のバージョン。将来ウィジェットの意味を変えるときに読み替えるため。 */
  v: 1;
  mode: DashboardMode;
  /** カスタマイズ表示の並び順（非表示のものも含む全ウィジェット。先頭は常に {@link PINNED_WIDGET}）。 */
  order: DashboardWidgetId[];
  /** カスタマイズ表示で隠すウィジェット。 */
  hidden: DashboardWidgetId[];
}

/** 初期配置の並び順（= 従来のダッシュボードの上から下。プロフィール系は末尾）。 */
export const DEFAULT_ORDER: readonly DashboardWidgetId[] =
  ['tier', 'league', 'ranking', 'lv12', 'recentPlays', 'diffTable', 'advice', 'activity', ...PROFILE_WIDGET_IDS];

/**
 * 初期値で非表示にするウィジェット。カスタマイズで ON にして足す前提のもの。
 * 保存済みの設定に無い（＝その設定が作られた後に増えた）ウィジェットも、この初期値で足される。
 */
export const DEFAULT_HIDDEN: readonly DashboardWidgetId[] = [...PROFILE_WIDGET_IDS];

/** 通常表示で出すウィジェット（初期配置から初期非表示を除いたもの。カスタマイズの内容には影響されない）。 */
export const NORMAL_WIDGETS: readonly DashboardWidgetId[] = DEFAULT_ORDER.filter(id => !DEFAULT_HIDDEN.includes(id));

/** 簡易表示モードで出すウィジェット（この順）。ティアはコンパクト表示になる。 */
export const SIMPLE_WIDGETS: readonly DashboardWidgetId[] = ['tier', 'league', 'ranking', 'recentPlays'];

/** 先頭固定・非表示にできないウィジェット（ダッシュボードの顔なので外せない）。 */
export const PINNED_WIDGET: DashboardWidgetId = 'tier';

const STORAGE_KEY = 'dashboardLayout';
const KNOWN_IDS = new Set<string>(DEFAULT_ORDER);

/** 初期配置の設定を新しいオブジェクトで返す。 */
export function defaultLayout(): DashboardLayout {
  return { v: 1, mode: 'normal', order: [...DEFAULT_ORDER], hidden: [...DEFAULT_HIDDEN] };
}

/** order / hidden が初期配置と同じか。 */
function isDefaultArrangement(order: readonly DashboardWidgetId[], hidden: readonly DashboardWidgetId[]): boolean {
  return order.length === DEFAULT_ORDER.length
    && order.every((id, i) => id === DEFAULT_ORDER[i])
    && hidden.length === DEFAULT_HIDDEN.length
    && DEFAULT_HIDDEN.every(id => hidden.includes(id));
}

/**
 * 【関数の役割】 localStorage / サーバー / 古い形式など出所不明の値を、必ず妥当な設定に正規化する。
 *  - 知らないウィジェット ID は捨て、足りない ID は初期配置の順で末尾に足す（将来ウィジェットが増えても壊れない）。
 *    足した ID が初期値で非表示のものなら hidden にも入れる（設定を作った後に増えたウィジェットが勝手に出ないように）
 *  - 先頭固定のウィジェットは常に order の先頭に置き、hidden からは外す
 *  - mode が不明な値（旧形式の 'full' など）なら、内容が初期配置から変わっていれば custom、そうでなければ normal
 */
export function normalizeLayout(raw: unknown): DashboardLayout {
  const base = defaultLayout();
  if (!raw || typeof raw !== 'object') return base;
  const r = raw as Record<string, unknown>;

  const pick = (v: unknown): DashboardWidgetId[] => {
    if (!Array.isArray(v)) return [];
    const out: DashboardWidgetId[] = [];
    for (const x of v) {
      if (typeof x === 'string' && KNOWN_IDS.has(x) && !out.includes(x as DashboardWidgetId)) out.push(x as DashboardWidgetId);
    }
    return out;
  };

  const order = pick(r.order).filter(id => id !== PINNED_WIDGET);
  const hidden = pick(r.hidden).filter(id => id !== PINNED_WIDGET);
  for (const id of DEFAULT_ORDER) {
    if (id === PINNED_WIDGET || order.includes(id)) continue;
    order.push(id);
    if (DEFAULT_HIDDEN.includes(id) && !hidden.includes(id)) hidden.push(id);
  }
  order.unshift(PINNED_WIDGET);

  const mode: DashboardMode = r.mode === 'normal' || r.mode === 'simple' || r.mode === 'custom'
    ? r.mode
    : isDefaultArrangement(order, hidden) ? 'normal' : 'custom';

  return { v: 1, mode, order, hidden };
}

/** JSON 文字列を正規化済みの設定にする。壊れた文字列・空文字・null は初期配置。 */
export function parseLayout(json: string | null | undefined): DashboardLayout {
  if (!json) return defaultLayout();
  try {
    return normalizeLayout(JSON.parse(json));
  } catch {
    return defaultLayout();
  }
}

function loadFromStorage(): DashboardLayout {
  try {
    return parseLayout(localStorage.getItem(STORAGE_KEY));
  } catch {
    return defaultLayout();
  }
}

/**
 * 現在の設定。モジュールトップに置くことで、どのコンポーネントから呼んでも同じ ref が共有される
 * （ScoreDashboard の描画とカスタマイズモーダルの編集が同じ値を見る）。
 */
export const dashboardLayoutRef = ref<DashboardLayout>(loadFromStorage());

/**
 * 設定をサーバー側のユーザープロフィールに保存する。
 * ログイン中だけ実行。ネットワーク失敗は黙殺（ローカル状態は既に更新済みで、次のログイン時にサーバー値で揃う）。
 */
function saveToDb(layout: DashboardLayout) {
  const token = localStorage.getItem(TOKEN_KEY);
  if (!token) return;
  fetch(`${API_BASE}/api/auth/me/profile`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
    body: JSON.stringify({ dashboardLayout: JSON.stringify(layout) }),
  }).catch(() => {});
}

/** 正規化して ref・localStorage・サーバーの 3 か所へ反映する唯一の書き込み口。 */
function commit(next: DashboardLayout) {
  const layout = normalizeLayout(next);
  dashboardLayoutRef.value = layout;
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(layout)); } catch { /* 容量超過等は無視 */ }
  saveToDb(layout);
}

/**
 * 【関数の役割】 /api/auth/me が返した設定を取り込む（useAuth から呼ぶ）。
 * サーバー値が正。null（未設定）は初期配置に戻す。ここではサーバーへ書き戻さない。
 */
export function applyServerLayout(json: string | null | undefined) {
  const layout = parseLayout(json);
  dashboardLayoutRef.value = layout;
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(layout)); } catch { /* 無視 */ }
}

export function useDashboardLayout() {
  /** 現在の表示モード。 */
  const mode = computed(() => dashboardLayoutRef.value.mode);
  /** 簡易表示か。 */
  const isSimple = computed(() => mode.value === 'simple');

  /** 初期状態（通常表示・初期配置）のままか（カスタマイズ画面の「初期設定に戻す」の活性判定用）。 */
  const isDefault = computed(() => {
    const l = dashboardLayoutRef.value;
    return l.mode === 'normal' && isDefaultArrangement(l.order, l.hidden);
  });

  /** カスタマイズ表示で実際に並べるウィジェット（order から hidden を除いたもの）。 */
  const customOrder = computed(() =>
    dashboardLayoutRef.value.order.filter(id => !dashboardLayoutRef.value.hidden.includes(id)));

  /** 表示モードを切り替える（通常／簡易／カスタマイズ）。言語設定と同じくその場で保存される。 */
  const setMode = (next: DashboardMode) => commit({ ...dashboardLayoutRef.value, mode: next });

  /**
   * ウィジェットの表示/非表示。先頭固定のものは変更できない。
   * 項目を触った時点で「カスタマイズ」表示に切り替える（編集した結果がその場で見えるように）。
   */
  const setWidgetHidden = (id: DashboardWidgetId, hidden: boolean) => {
    if (id === PINNED_WIDGET) return;
    const cur = dashboardLayoutRef.value;
    const next = cur.hidden.filter(x => x !== id);
    if (hidden) next.push(id);
    commit({ ...cur, mode: 'custom', hidden: next });
  };

  /** ウィジェットを 1 つ上（-1）/ 下（+1）へ。先頭固定のものは動かさず、その位置へも入れない。 */
  const moveWidget = (id: DashboardWidgetId, delta: -1 | 1) => {
    if (id === PINNED_WIDGET) return;
    const order = [...dashboardLayoutRef.value.order];
    const from = order.indexOf(id);
    const to = from + delta;
    if (from < 0 || to < 1 || to >= order.length) return;
    [order[from], order[to]] = [order[to], order[from]];
    commit({ ...dashboardLayoutRef.value, mode: 'custom', order });
  };

  /** 通常表示・初期配置に戻す（カスタマイズの内容も初期化）。 */
  const resetLayout = () => commit(defaultLayout());

  return {
    /** 現在の設定（読み取り用。変更は下の関数経由で）。 */
    layout: dashboardLayoutRef,
    mode,
    isSimple,
    isDefault,
    customOrder,
    setMode,
    setWidgetHidden,
    moveWidget,
    resetLayout,
  };
}
