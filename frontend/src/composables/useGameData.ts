import { ref, readonly } from 'vue';

/**
 * 【Composable の役割】 楽曲データ（song_data）・非公式難易度表（difficulty_table）をアプリ全体で共有する。
 *
 * 機能:
 *  - 初期表示はバンドルされた静的 JSON をフォールバックとして即座に適用
 *  - `fetchGameData()` でバックエンド API から最新版を取得して上書き
 *  - 他コンポーネントは readonly な ref として購読できるので、勝手に改変されない
 *
 * 使い方:
 * ```ts
 * const { songDataBody, diffTableRanks, fetchGameData } = useGameData();
 * await fetchGameData();
 * ```
 */

/** 楽曲 1 件のメタ情報。 */
export interface SongDataEntry {
  title: string;
  artist: string;
  genre: string;
  notes: number;
  bpm: string;
  difficulty: string;
  level: number;
  wr?: number;
  avg?: number;
  textage?: string;
  coef?: number;
  difficultyLevel?: string;
  dpLevel?: string;
}

/** 非公式難易度表の 1 ランク（例: ★12個人差A）に属する楽曲群。 */
export interface DifficultyRankEntry {
  rank: string;
  songs: string[];
}

/** song_data.json のルート構造。 */
interface SongDataRoot {
  version: number;
  requireVersion: string;
  body: SongDataEntry[];
}

/** difficulty_table.json のルート構造。 */
interface DifficultyTableRoot {
  ranks: DifficultyRankEntry[];
}

/** バックエンド API のベース URL。 */
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

// ── グローバルなリアクティブ状態 ────────────────────────────────
// いずれもモジュールトップレベルに置くことで、全コンポーネント間で
// 同じ ref インスタンスを共有する（シングルトン状態）。
/** 楽曲一覧（最新版。取得前は静的 JSON フォールバック）。 */
const songDataBody = ref<SongDataEntry[]>([]);
/**
 * 非公式難易度表（最新版）。
 *
 * 「★12.0」「★11.5」のような非公式ランクの集合。集計や `informalRank` 引きでは
 * この純粋な非公式表だけを参照する。公式 LV10 以下の譜面はここに含まれない。
 */
const diffTableRanks = ref<DifficultyRankEntry[]>([]);
/**
 * 公式 LV10 以下の ANOTHER/LEGGENDARIA を「公式 Lv10」「公式 Lv9」… でまとめた
 * 追加カテゴリ。難易度表 UI で {@link diffTableRanks} の末尾に並べて表示する用途。
 *
 * informalRank（非公式難易度ランク）として使ってはいけない: そもそも Lv10 以下に
 * 非公式難易度は定義されていないので、これを informalRank に流すと「単曲ティア」が
 * 偽情報で計算される。
 */
const extraOfficialLevelRanks = ref<DifficultyRankEntry[]>([]);
/** API からの取得が 1 度でも成功したかどうか。 */
const isLoaded = ref(false);
/** 現在 API 取得中かどうか（二重実行防止にも使う）。 */
const isLoading = ref(false);
/** 取得失敗時のエラーメッセージ。 */
const loadError = ref('');

// ── フォールバック: 初期表示 / オフライン用に静的 JSON を同梱 ──
import songDataFallback from '../data/song_data.json';
import diffTableFallback from '../data/difficulty_table.json';

/**
 * 【内部関数】 公式 LV 10 以下の ANOTHER/LEGGENDARIA 譜面を集めて、
 * 非公式難易度表に追加するための {@link DifficultyRankEntry} 配列を生成する。
 *
 * 非公式難易度表は通常 Lv11.0 以上だけを扱うため、Lv10 以下の譜面は対象から
 * 漏れてしまい譜面別ランキング画面へ辿り着けない。これを補うため、公式 LV ベースの
 * 仮想カテゴリ（例: "公式 Lv10 (ANOTHER)"）を後付けする。
 *
 * 既に非公式表に載っている曲名は除外して二重表示を防ぐ。
 *
 * @param songs         楽曲データ全件（song_data.json の body）
 * @param existingRanks 既存の非公式難易度表エントリ群
 * @returns 公式 LV 別の追加カテゴリ配列（Lv10 → Lv1 の順）
 */
function buildOfficialLowLevelRanks(
  songs: SongDataEntry[],
  existingRanks: DifficultyRankEntry[]
): DifficultyRankEntry[] {
  // 既存表に載っている曲名集合（タイトル単位、難易度は問わない）。
  const inExistingTable = new Set<string>();
  for (const r of existingRanks) {
    for (const t of r.songs) inExistingTable.add(t);
  }
  // (level, difficultyCode) ごとに曲名を集める。
  // beat-seeker は ANOTHER/LEGGENDARIA のみ対応なので、その 2 種に絞る。
  const ANOTHER = '4';
  const LEGGENDARIA = '10';
  const byKey = new Map<string, Set<string>>();
  for (const s of songs) {
    if (s.difficulty !== ANOTHER && s.difficulty !== LEGGENDARIA) continue;
    if (typeof s.level !== 'number' || s.level < 1 || s.level > 10) continue;
    if (inExistingTable.has(s.title)) continue;
    const diffLabel = s.difficulty === LEGGENDARIA ? 'LEGGENDARIA' : 'ANOTHER';
    const key = s.level + '|' + diffLabel;
    if (!byKey.has(key)) byKey.set(key, new Set());
    byKey.get(key)!.add(s.title);
  }
  // Lv10 → Lv1 の順、ANOTHER → LEGGENDARIA の順で並べる。
  const result: DifficultyRankEntry[] = [];
  for (let lv = 10; lv >= 1; lv--) {
    for (const diffLabel of ['ANOTHER', 'LEGGENDARIA'] as const) {
      const set = byKey.get(lv + '|' + diffLabel);
      if (!set || set.size === 0) continue;
      result.push({
        rank: `公式 Lv${lv} (${diffLabel})`,
        songs: [...set].sort(),
      });
    }
  }
  return result;
}

/** 静的 JSON をリアクティブ状態へ反映する。API 取得成功前の初期表示を担う。 */
function applyFallback() {
  if (songDataFallback && Array.isArray(songDataFallback.body)) {
    songDataBody.value = songDataFallback.body as SongDataEntry[];
  }
  if (diffTableFallback && Array.isArray(diffTableFallback.ranks)) {
    const base = diffTableFallback.ranks as DifficultyRankEntry[];
    diffTableRanks.value = base;
    // 難易度表 UI 専用の追加カテゴリは別 ref に格納し、informalRank 用途から隔離する。
    extraOfficialLevelRanks.value = buildOfficialLowLevelRanks(songDataBody.value, base);
  }
}

// モジュール読み込み時点でフォールバックを即適用しておくことで、
// API 取得を待たずにコンポーネントがデータを表示できる。
applyFallback();

export function useGameData() {
  /** 楽曲データと難易度表を並列で取得し、成功分だけリアクティブ状態を上書きする。 */
  const fetchGameData = async () => {
    // 二重フェッチ防止
    if (isLoading.value) return;
    isLoading.value = true;
    loadError.value = '';

    try {
      // 2 つの API を並列取得（片方だけ失敗しても片方は反映する設計）
      const [songRes, diffRes] = await Promise.all([
        fetch(`${API_BASE}/api/game-data/songs`),
        fetch(`${API_BASE}/api/game-data/difficulty-table`),
      ]);

      if (songRes.ok) {
        const songJson: SongDataRoot = await songRes.json();
        if (songJson.body && Array.isArray(songJson.body)) {
          songDataBody.value = songJson.body;
        }
      }

      if (diffRes.ok) {
        const diffJson: DifficultyTableRoot = await diffRes.json();
        if (diffJson.ranks && Array.isArray(diffJson.ranks)) {
          diffTableRanks.value = diffJson.ranks;
          // 公式 LV10 以下の追加カテゴリは別 ref へ。informalRank 用途から完全に隔離する。
          extraOfficialLevelRanks.value = buildOfficialLowLevelRanks(songDataBody.value, diffJson.ranks);
        }
      }

      isLoaded.value = true;
    } catch (e: any) {
      // API 失敗時はフォールバックを使い続ける（オフライン可用性）
      console.warn('Failed to fetch game data from API, using fallback:', e.message);
      loadError.value = e.message;
      // フォールバックを保持
    } finally {
      isLoading.value = false;
    }
  };

  return {
    /** 楽曲一覧（readonly）。勝手に書き換えられないよう readonly で公開。 */
    songDataBody: readonly(songDataBody),
    /** 非公式難易度表（readonly）。 */
    diffTableRanks: readonly(diffTableRanks),
    /** 公式 LV10 以下の追加カテゴリ（難易度表 UI 表示専用、informalRank 用途禁止）。 */
    extraOfficialLevelRanks: readonly(extraOfficialLevelRanks),
    /** 初回取得が成功したかどうか。 */
    isLoaded: readonly(isLoaded),
    /** ロード中フラグ。 */
    isLoading: readonly(isLoading),
    /** エラーメッセージ。 */
    loadError: readonly(loadError),
    /** API 取得トリガ関数。 */
    fetchGameData,
  };
}

// ── Composable 外から使うためのダイレクトエクスポート ──
// useRateSongRanking などのユーティリティからは composable を経由せずに
// 直接書き換え可能な ref へアクセスしたいケースがあるため、こちらも公開している。
export const songData = songDataBody;
export const diffTable = diffTableRanks;

// ── 難易度コード変換ヘルパー ────────────────────────────────
// beatmania IIDX の song_data.json では difficulty が文字列のコード番号で
// 格納されている（例: '4' = ANOTHER）。Frontend では難易度名（"ANOTHER" など）を
// 使うことも多いため、名前 → コード番号のマッピングをここに集約する。

/** 【定数】 難易度名 → コード番号のマッピング。beatmania IIDX の内部コード体系に合わせる。 */
export const DIFFICULTY_NAME_TO_CODE: Record<string, number> = {
  BEGINNER: 1,
  NORMAL: 2,
  HYPER: 3,
  ANOTHER: 4,
  LEGGENDARIA: 10,
};

/** 【関数】 難易度名 → コード番号変換。未知の名前は undefined を返す。 */
export function getDifficultyCode(name: string): number | undefined {
  return DIFFICULTY_NAME_TO_CODE[name];
}

/**
 * 【関数】 曲タイトル + 難易度名から最大スコア（notes × 2）を取得する。
 *
 * song_data.json から当該譜面エントリを検索し、`notes × 2` を返す。
 * 見つからない／難易度名が未知の場合は null を返す。
 *
 * @param title 曲タイトル
 * @param difficultyName 難易度名（例: "ANOTHER" / "LEGGENDARIA"）
 */
export function getMaxScoreByDifficulty(title: string, difficultyName: string): number | null {
  const code = getDifficultyCode(difficultyName);
  if (code === undefined) return null;
  const codeStr = String(code);
  // songDataBody は ref。fallback が同期適用されているので通常は空ではない。
  for (const s of songDataBody.value) {
    if (s.title === title && s.difficulty === codeStr) {
      return s.notes ? s.notes * 2 : null;
    }
  }
  return null;
}
