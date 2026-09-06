/**
 * 【ユーティリティの役割】 e-amusement GATE からダウンロードしたスコア CSV を
 * フロントの型（{@link ScoreData}）にパースする処理を提供する。
 *
 * CSV は 1 曲 1 行形式で、BEGINNER / NORMAL / HYPER / ANOTHER / LEGGENDARIA の
 * 各難易度のスコア情報が横に並んでいる。本モジュールではこれを難易度ごとに分解し、
 * {@link ScoreData} 型の配列に変換する。
 *
 * 依存: papaparse（ブラウザ/Node 両対応の高速 CSV パーサ）
 *
 * 使い方:
 * ```ts
 * const scores = await parseScoreCsv(csvFile);
 * ```
 */
import Papa from 'papaparse';
import type { ScoreData, DifficultyStats } from '../types/ScoreData';
import { songData } from '../composables/useGameData';
import { LABEL_TO_VERSION, MIN_PAST_VERSION } from './iidxVersions';
import { canonicalSongTitle } from './songTitleAliases';

/**
 * 【型】 CSV から作品バージョンを自動判定した結果。
 *
 * 判定に失敗した場合は `reason` で理由を区別し、UI 側でそれぞれ別の文言を出す。
 *  - `noLabels`      … 「バージョン」列に既知の作品名が 1 つも無い（列が空のブックマークレット CSV など）
 *  - `unknownLabel`  … 未知の作品名が含まれる。アプリが未対応の新作の可能性が高い
 *  - `tooOld`        … 検知できたが対応範囲（30 RESIDENT 以降）より古い
 */
export type VersionDetectionResult =
    | { ok: true; version: number }
    | { ok: false; reason: 'noLabels' }
    | { ok: false; reason: 'unknownLabel'; unknownLabels: string[] }
    | { ok: false; reason: 'tooOld'; version: number };

/** SP の内部難易度コード。song_data では 1=BEGINNER, 2=NORMAL, 3=HYPER, 4=ANOTHER, 10=LEGGENDARIA。 */
const DIFF_KEY_TO_CODE: Record<string, string> = {
    beginner: '1',
    normal: '2',
    hyper: '3',
    another: '4',
    leggendaria: '10',
};

/**
 * 【関数の役割】 CSV 1 行分から指定難易度の {@link DifficultyStats} を抽出する。
 *
 * CSV のカラム名は「BEGINNER 難易度」「ANOTHER スコア」のように
 * `{難易度ラベル} {項目名}` の形式なので、テンプレートリテラルで組み立てて取り出す。
 *
 * @param row  papaparse が返した 1 行分のオブジェクト（キー = ヘッダ名）
 * @param diff 'BEGINNER' / 'NORMAL' / 'HYPER' / 'ANOTHER' / 'LEGGENDARIA'
 * @returns    難易度ごとの集計値
 */
const parseDifficulty = (row: any, diff: string): DifficultyStats => {
    // '---' は「未プレイ or 譜面なし」を表す eagate 特有のマーカー。null として扱う。
    const parseNum = (val: string) => (val === '---' || !val ? null : parseInt(val, 10));

    return {
        difficulty: parseNum(row[`${diff} 難易度`]),
        score: parseInt(row[`${diff} スコア`], 10) || 0,
        pgreat: parseInt(row[`${diff} PGreat`], 10) || 0,
        great: parseInt(row[`${diff} Great`], 10) || 0,
        missCount: parseNum(row[`${diff} ミスカウント`]),
        // 未プレイ時は 'NO PLAY' に正規化（CSV が空文字で来るパターンへの対策）
        clearType: row[`${diff} クリアタイプ`] || 'NO PLAY',
        // DJ LEVEL は F/E/D/C/B/A/AA/AAA。未プレイは '---' に寄せる。
        djLevel: row[`${diff} DJ LEVEL`] || '---',
    };
};

/**
 * 【関数の役割】 File オブジェクト（ユーザーが選択した CSV）を非同期にパースし、
 * {@link ScoreData} 配列として解決される Promise を返す。
 *
 * 内部的には papaparse の streaming でなく一括 parse を使用。CSV の先頭行を
 * ヘッダとして扱うため `header: true` を指定。
 *
 * 追加のバリデーション:
 *  - DP（ダブルプレー）用 CSV を誤って食わせた場合を検知してエラーを投げる。
 *    判定は {@link detectPlayStyle}（曲マスタの SP/DP レベルとの多数決）を主とし、
 *    マスタが未ロード等で判定不能なときのみ従来の 22DUNK ヒューリスティックにフォールバックする。
 *
 * @param file ユーザーが <input type="file"> などで選択した CSV ファイル
 * @returns    パース結果の {@link ScoreData} 配列に解決される Promise。
 *             失敗時は reject される。
 */
export const parseScoreCsv = (file: File): Promise<ScoreData[]> => {
    return new Promise((resolve, reject) => {
        Papa.parse(file, {
            header: true,       // 先頭行をキー名として扱う
            skipEmptyLines: true, // 空行はスキップ
            complete: (results: Papa.ParseResult<any>) => {
                try {
                    // 手順1: 生の行配列を ScoreData[] に詰め替える。
                    //        曲名は作品によって表記が変わることがある（31 EPOLIS の "VØID" と現行の "VOID" など）ので、
                    //        現行作・過去作どちらの CSV でもここで現行表記に寄せ、以降の突き合わせを曲名一致で済ませる。
                    const parsedData: ScoreData[] = results.data
                        // 末尾の空行や壊れた行（タイトル列が空）を除外する
                        .filter((row: any) => row['タイトル'])
                        .map((row: any) => ({
                            version: row['バージョン'] || '',
                            title: canonicalSongTitle(row['タイトル'] || ''),
                            genre: row['ジャンル'] || '',
                            artist: row['アーティスト'] || '',
                            playCount: parseInt(row['プレー回数'], 10) || 0,
                            beginner: parseDifficulty(row, 'BEGINNER'),
                            normal: parseDifficulty(row, 'NORMAL'),
                            hyper: parseDifficulty(row, 'HYPER'),
                            another: parseDifficulty(row, 'ANOTHER'),
                            leggendaria: parseDifficulty(row, 'LEGGENDARIA'),
                            lastPlayTime: row['最終プレー日時'] || '',
                        }));

                    // 手順2: DP 用 CSV の誤投入を検出する。
                    //        主判定は曲マスタの SP/DP レベルとの多数決（作品をまたいでも壊れない）。
                    //        マスタ未ロード等で判定不能な場合のみ、従来の 22DUNK 判定を保険として使う。
                    const style = detectPlayStyle(parsedData);
                    const isDp = style === 'DP' || (style === 'unknown' && looksLikeDpByLegacyHeuristic(parsedData));
                    if (isDp) {
                        // DP の CSV は beat-seeker がサポートしないため早期エラー
                        throw new Error('DPのCSVは読み込めません。SPのCSVを使用してください。');
                    }

                    // 手順3: 全検証を通過したらパース結果を resolve。
                    resolve(parsedData);
                } catch (err) {
                    // map/filter 内の例外や DP 検証エラーをまとめて reject する
                    reject(err);
                }
            },
            // papaparse 側の致命的エラー（エンコーディング不一致等）
            error: (error: Error) => {
                reject(error);
            }
        });
    });
};

/**
 * 【関数の役割】 パース済み CSV から、それがどの作品のスコアかを自動判定する。
 *
 * 判定原理は {@link file://../utils/iidxVersions.ts} の冒頭コメントを参照。要点は
 * 「バージョン」列が *楽曲の初出作品名* であり、31 EPOLIS の曲が 30 RESIDENT の CSV に
 * 載ることは原理的にあり得ないため、出現する初出作品の最大値がその CSV を出力した作品の
 * 下限になる、という点。
 *
 * 判定順（安全側に倒す設計）:
 *  1. 既知ラベルが 1 つも無ければ `noLabels`（ブックマークレット CSV はここに該当する）
 *  2. 未知のラベルが 1 つでもあれば `unknownLabel`。
 *     未知ラベルを黙って無視すると、未対応の新作 CSV を現行作として取り込んでしまい、
 *     ランキングや BEAT-PT が古い/新しいデータで汚染される。最も避けたい事故なのでここで止める。
 *  3. 対応範囲より古ければ `tooOld`
 *
 * ■ 「欠けている作品がある CSV」を弾いてはいけない
 * 公式 CSV に載るのは *プレーした曲だけ* なので、その作品の曲を 1 曲もプレーしていなければ
 * その作品名は「バージョン」列に一度も現れない。ライトユーザーほど歯抜けになるのが正常で、
 * 「1 作目〜検知作品の間に空の作品があれば不完全な CSV」と見なす判定は誤検知にしかならない。
 *
 * @param scores {@link parseScoreCsv} の返り値
 * @returns 判定結果。成功時は `{ ok: true, version }`
 */
export function detectCsvVersion(scores: ScoreData[]): VersionDetectionResult {
    /** CSV に出現した作品番号の集合。 */
    const present = new Set<number>();
    /** 対応表に無かったラベル（重複除去）。 */
    const unknown = new Set<string>();

    scores.forEach(song => {
        const raw = (song.version || '').trim();
        if (!raw) return; // 空欄は判定材料にしない（ブックマークレット CSV は全行が空）

        const num = LABEL_TO_VERSION[raw.toLowerCase()];
        if (num === undefined) {
            unknown.add(raw);
        } else {
            present.add(num);
        }
    });

    if (unknown.size > 0) {
        return { ok: false, reason: 'unknownLabel', unknownLabels: Array.from(unknown).slice(0, 5) };
    }
    if (present.size === 0) {
        return { ok: false, reason: 'noLabels' };
    }

    // 出現した作品の最大値を採用する。歯抜け（1 曲もプレーしていない作品）は
    // 正常な CSV でも普通に起きるため、欠けていること自体は判定材料にしない。
    const detected = Math.max(...present);

    if (detected < MIN_PAST_VERSION) {
        return { ok: false, reason: 'tooOld', version: detected };
    }

    return { ok: true, version: detected };
}

/**
 * 【関数の役割】 CSV 内の「最終プレー日時」の最大値を返す（取り込み確認ダイアログの表示用）。
 *
 * 書式は "YYYY-MM-DD HH:mm" で辞書順 = 時系列順のため、単純な文字列比較で最大を取れる。
 *
 * @returns 最終プレー日時。1 件も無ければ空文字
 */
export function getCsvLastPlayTime(scores: ScoreData[]): string {
    let latest = '';
    scores.forEach(song => {
        const t = (song.lastPlayTime || '').trim();
        if (t && t > latest) latest = t;
    });
    return latest;
}

/** BEGINNER 譜面がこの数以上あれば SP と断定する（DP には BEGINNER 譜面が存在しない）。 */
const BEGINNER_SP_THRESHOLD = 20;
/** SP レベル一致率による判定に必要な最小サンプル数。 */
const SP_MATCH_MIN_SAMPLES = 300;
/** SP と判断する曲マスタ★一致率の下限。 */
const SP_MATCH_THRESHOLD = 0.8;

/**
 * 【関数の役割】 CSV が SP / DP どちらのものかを判定する。
 *
 * 従来は「22DUNK の N/H/A が 5/5/5 なら DP」という 1 曲依存の判定だったが、
 * ★は作品ごとに改定されるため、過去作の CSV では誤判定し得た
 * （SP の CSV を DP と誤認して弾いてしまう）。ここでは独立した 2 つの信号で判定する。
 *
 * 信号1: BEGINNER 譜面の有無（主判定）
 *   IIDX の DP には BEGINNER 譜面が存在しない。したがって BEGINNER の★が入った行が
 *   一定数あれば SP と断定できる。曲マスタに依存せず、★改定の影響も受けない構造的な判定。
 *   （実際の SP CSV では 1,800 曲中 190 曲前後が BEGINNER 譜面を持つ）
 *
 * 信号2: 曲マスタの SP レベル（`level`）との一致率（副判定）
 *   SP の CSV なら★はほぼ一致する。作品をまたぐ★改定は多くても数十譜面なので、
 *   過去作の CSV でも一致率は十分高いままになる。
 *   ※ 曲マスタの `dpLevel` は現状すべて未設定（undefined / "0"）のため、
 *      SP レベルと DP レベルを比較する方式は採れない。
 *
 * DP と断定するのは「BEGINNER 譜面が 1 つも無い」かつ「★一致率が低い」の両方が
 * 揃ったときだけにしている。正しい SP の CSV を誤って弾く方が、DP の CSV を
 * 通してしまうより体験上の実害が大きいため、迷ったら 'unknown' に倒す設計。
 *
 * @param scores パース済みスコア
 * @returns 'SP' / 'DP' / 'unknown'（判断材料が足りない場合）
 */
export function detectPlayStyle(scores: ScoreData[]): 'SP' | 'DP' | 'unknown' {
    // ── 信号1: BEGINNER 譜面の数 ──
    let beginnerCharts = 0;
    for (const song of scores) {
        const lv = song.beginner?.difficulty;
        if (lv != null && lv > 0) beginnerCharts++;
    }
    if (beginnerCharts >= BEGINNER_SP_THRESHOLD) return 'SP';

    // ── 信号2: 曲マスタの SP レベルとの一致率 ──
    let samples = 0;
    let matches = 0;
    const body = songData.value;
    if (body && Array.isArray(body) && body.length > 0) {
        const dict = new Map<string, any>();
        body.forEach((s: any) => dict.set(`${s.title}_${s.difficulty}`, s));

        for (const song of scores) {
            for (const key of ['normal', 'hyper', 'another'] as const) {
                const csvLevel = (song[key] as DifficultyStats | undefined)?.difficulty;
                if (csvLevel == null || csvLevel <= 0) continue;

                const def = dict.get(`${song.title}_${DIFF_KEY_TO_CODE[key]}`);
                if (!def || typeof def.level !== 'number' || def.level <= 0) continue;

                samples++;
                if (csvLevel === def.level) matches++;
            }
        }
    }

    // サンプルが足りなければ判定しない（曲マスタ未ロード・部分的な CSV など）。
    if (samples < SP_MATCH_MIN_SAMPLES) return 'unknown';

    const matchRate = matches / samples;
    if (matchRate >= SP_MATCH_THRESHOLD) return 'SP';

    // 2 つの信号が揃ったときだけ DP と断定する。
    if (beginnerCharts === 0) return 'DP';
    return 'unknown';
}

/**
 * 【内部関数】 旧来の 22DUNK ヒューリスティックによる DP 判定。
 *
 * 22DUNK は SP では N/H/A = 3/4/5、DP では 5/5/5。
 * {@link detectPlayStyle} が 'unknown'（曲マスタ未ロード等）のときだけ保険として使う。
 */
function looksLikeDpByLegacyHeuristic(scores: ScoreData[]): boolean {
    const dunk22 = scores.find(d => d.title === '22DUNK');
    return !!dunk22
        && dunk22.normal.difficulty === 5
        && dunk22.hyper.difficulty === 5
        && dunk22.another.difficulty === 5;
}
