/**
 * 【ユーティリティの役割】 スコア関連の型定義と、ScoreData（曲単位ネスト構造）を
 * ScoreRecord（難易度ごとのフラット構造）へ展開する変換ロジックを提供する。
 *
 * beat-seeker の内部表現:
 *  - {@link ScoreData}   … CSV をパースした直後の「1 曲 × 5 難易度」ネスト型
 *  - {@link ScoreRecord} … UI 表示や集計で使いやすいフラット型（1 行 = 1 譜面）
 *
 * このモジュールでは ScoreData 配列を ScoreRecord 配列へ平坦化しつつ、
 *  - 曲定義から MAX スコア（notes × 2）を引っ張って score rate を計算
 *  - 難易度表から informal rank（例: "12.0"）を付与
 *  - {@link calculatePoints} で BEAT-PT を算出
 * までをまとめて行う。
 *
 * 使い方:
 * ```ts
 * const records = flattenScores(scoreDataArray);
 * ```
 */
import type { ScoreData } from '../types/ScoreData';
import { songData as songDataBody, diffTable as diffTableRanks } from '../composables/useGameData';
import { calculatePoints, getMaxPoints } from './beatTier';

/**
 * 1 譜面分のスコア情報を表すフラットな表示用レコード型。
 * {@link flattenScores} の返り値要素として使われる。
 */
export interface ScoreRecord {
    id?: number;
    title: string;
    artist: string;
    genre: string;
    difficultyName: string;
    difficultyColor: string;
    difficultyLevel: number | null;
    clearType: string;
    score: number;
    scoreRate: number; // calculated percentage
    maxScore: number;
    informalRank: string | undefined;
    djLevel: string;
    pgreat: number;
    great: number;
    missCount: number | null;
    playCount: number;
    lastPlayTime: string;
    beatTierPoints: number;
    maxBeatTierPoints: number;
    /** iidx-memo 等から同期された譜面オプション。読み取り専用。 */
    options?: string[];
    djName?: string;
    /** スコア取得元。"arcade" / "infinitas"。表示の INF タグ判定に使う。 */
    source?: string;
}

/** ScoreData プロパティ名として存在する 5 難易度のキー列挙。forEach の対象に使う。 */
const difficulties = ['beginner', 'normal', 'hyper', 'another', 'leggendaria'] as const;
/** キー（小文字）→ 画面表示用ラベル（大文字） の対応表。 */
const difficultyLabels: Record<string, string> = {
    beginner: 'BEGINNER',
    normal: 'NORMAL',
    hyper: 'HYPER',
    another: 'ANOTHER',
    leggendaria: 'LEGGENDARIA'
};

/**
 * SP の内部難易度コード。
 * IIDX の song_data.json では SP 難易度は 1=BEGINNER, 2=NORMAL, 3=HYPER, 4=ANOTHER, 10=LEGGENDARIA。
 * （5〜9 は DP 側で使用される）
 */
const spIidxDiffMap: Record<string, string> = {
    beginner: '1',
    normal: '2',
    hyper: '3',
    another: '4',
    leggendaria: '10'
};

/** 難易度ごとの Tailwind CSS バッジクラス。UI でラベル装飾に使う。 */
const diffColors: Record<string, string> = {
    beginner: 'text-emerald-700 bg-emerald-100 border border-emerald-300',
    normal: 'text-blue-700 bg-blue-100 border border-blue-300',
    hyper: 'text-amber-700 bg-amber-100 border border-amber-300',
    another: 'text-red-700 bg-red-100 border border-red-300',
    leggendaria: 'text-purple-700 bg-purple-100 border border-purple-300'
};

/** 大文字ラベル → SP 内部難易度コードの逆引きマップ。 */
const diffLabelToCode: Record<string, string> = {
    BEGINNER: '1', NORMAL: '2', HYPER: '3', ANOTHER: '4', LEGGENDARIA: '10'
};

/**
 * 【内部ヘルパー】 リアクティブな曲データから `"タイトル_難易度コード"` をキーとする Map を構築する。
 *
 * flattenScores 内で頻繁にルックアップする都合、毎回構築するコストは小さくないものの、
 * 呼び出し頻度自体は限定的なのでキャッシュは持たない方針。
 */
function buildSongDict(): Map<string, any> {
    const dict = new Map<string, any>();
    const body = songDataBody.value;
    if (body && Array.isArray(body)) {
        body.forEach((s: any) => {
            dict.set(`${s.title}_${s.difficulty}`, s);
        });
    }
    return dict;
}

/**
 * 【内部ヘルパー】 難易度表（非公式 rank を載せたテーブル）から
 * `"タイトル_難易度ラベル"` をキーにした Map を構築する。
 *
 * ルール:
 *  - 曲タイトル末尾に `[L]` が付いていれば LEGGENDARIA の非公式 rank。
 *  - それ以外は ANOTHER の非公式 rank として登録する。
 */
function buildInformalDict(): Map<string, string> {
    const dict = new Map<string, string>();
    const ranks = diffTableRanks.value;
    if (ranks && Array.isArray(ranks)) {
        ranks.forEach((r: any) => {
            r.songs.forEach((songTitle: string) => {
                if (songTitle.endsWith('[L]')) {
                    // "[L]" サフィックスを剥がして LEGGENDARIA 用エントリにする
                    const baseTitle = songTitle.slice(0, -3);
                    dict.set(`${baseTitle}_LEGGENDARIA`, r.rank);
                } else {
                    // それ以外は ANOTHER 難易度を指す
                    dict.set(`${songTitle}_ANOTHER`, r.rank);
                }
            });
        });
    }
    return dict;
}

/**
 * 【関数の役割】 タイトルと大文字難易度ラベルから最大スコア（= notes × 2）を返す。
 *
 * IIDX の最大スコアは「全ノート PGREAT = notes × 2」で決まる。
 * 曲定義が見つからない場合は 0 を返す（呼び出し元で 0% 表示などに使える）。
 *
 * @param title          曲タイトル（CSV の「タイトル」列そのまま）
 * @param difficultyName 大文字難易度ラベル（例: "ANOTHER", "LEGGENDARIA"）
 * @returns              最大スコア（整数）。見つからない場合は 0。
 */
export function getSongMaxScore(title: string, difficultyName: string): number {
    const code = diffLabelToCode[difficultyName];
    if (!code) return 0;
    const songDict = buildSongDict();
    const definition = songDict.get(`${title}_${code}`);
    // notes × 2 が IIDX のパーフェクト時スコア。notes 未定義なら 0。
    return definition?.notes ? definition.notes * 2 : 0;
}

/**
 * 【関数の役割】 ScoreData 配列（1 曲 × 5 難易度のネスト）を
 * ScoreRecord 配列（1 譜面 = 1 レコードのフラット表現）へ平坦化する。
 *
 * 処理内容:
 *  - プレイ実績のある難易度だけを抽出（NO PLAY / '---' はスキップ）
 *  - 最大スコア・score rate・非公式 rank・BEAT-PT を合成して 1 レコードに詰める
 *  - HYPER 譜面で難易度 11 以上のものは「対象外」と見なして BEAT-PT を 0 にする
 *    （同曲に ANOTHER がある場合に二重計上を防ぐための仕様）
 *
 * @param scores 曲単位のスコア配列（CSV パース結果）
 * @returns      譜面単位に展開された表示用レコード配列
 */
export function flattenScores(scores: ScoreData[]): ScoreRecord[] {
    const records: ScoreRecord[] = [];

    // 曲定義・難易度表のルックアップ用 Map を最初に 1 回だけ構築
    const songDict = buildSongDict();
    const informalDict = buildInformalDict();

    scores.forEach(song => {
        difficulties.forEach(diff => {
            const stats = song[diff as keyof ScoreData] as any;
            // 手順1: プレイ実績の有無をチェック。NO PLAY や未収録譜面はスキップ。
            const hasActivity = stats && ((stats.clearType !== 'NO PLAY' && stats.clearType !== '---') || stats.score > 0);
            if (hasActivity) {

                let scoreRate = -1; // 未算出を示すセンチネル値
                let maxScore = 0;

                // 手順2: 曲定義から最大スコアを求め、score rate を百分率で算出。
                const defKey = `${song.title}_${spIidxDiffMap[diff]}`;
                const definition = songDict.get(defKey);

                if (definition && definition.notes) {
                    maxScore = definition.notes * 2;
                    if (maxScore > 0) {
                        scoreRate = (stats.score / maxScore) * 100;
                    }
                }

                const diffLabel = difficultyLabels[diff];
                // 手順3: HYPER 譜面で難易度 11 以上のものは BEAT-Tier 計算対象外。
                //         （BEAT-Tier では主に ANOTHER/LEGGENDARIA を対象とするため、高難度 HYPER は除外）
                const isHyperNonTarget = diffLabel === 'HYPER' && stats.difficulty >= 11;

                // 手順4: 難易度表から非公式ランク（例: "12.1"）を取得。
                const informalKey = `${song.title}_${diffLabel}`;
                let informalRank = informalDict.get(informalKey) || undefined;

                // フォールバック: ANOTHER で取れなかった場合の再検索
                // （同じキー検索だが、将来的に別ルールへ差し替え可能なフック）
                if (!informalRank && diffLabel === 'ANOTHER') {
                    informalRank = informalDict.get(`${song.title}_ANOTHER`) || undefined;
                }

                // 手順5: BEAT-PT を計算。対象外譜面は 0 で確定。
                const beatTierPoints = isHyperNonTarget ? 0 : calculatePoints(scoreRate, informalRank);

                records.push({
                    id: stats.id,
                    title: song.title,
                    artist: song.artist,
                    genre: song.genre,
                    difficultyName: diffLabel,
                    difficultyColor: diffColors[diff],
                    difficultyLevel: stats.difficulty,
                    clearType: stats.clearType,
                    score: stats.score,
                    scoreRate: scoreRate,
                    maxScore: maxScore,
                    informalRank: informalRank,
                    djLevel: stats.djLevel,
                    pgreat: stats.pgreat,
                    great: stats.great,
                    missCount: stats.missCount,
                    playCount: song.playCount,
                    lastPlayTime: song.lastPlayTime,
                    beatTierPoints: beatTierPoints,
                    maxBeatTierPoints: getMaxPoints(informalRank),
                    options: stats.options,
                    djName: stats.djName,
                    source: stats.source
                });
            }
        });
    });

    return records;
}
