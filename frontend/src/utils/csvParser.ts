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
 *    判定は曲 '22DUNK' の NORMAL/HYPER/ANOTHER 難易度値が SP と DP で異なる点を利用。
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
                    const parsedData: ScoreData[] = results.data
                        // 末尾の空行や壊れた行（タイトル列が空）を除外する
                        .filter((row: any) => row['タイトル'])
                        .map((row: any) => ({
                            version: row['バージョン'] || '',
                            title: row['タイトル'] || '',
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
                    //        22DUNK は SP では N/H/A = 3/4/5、DP では N/H/A = 5/5/5 となる特性を使う。
                    const dunk22 = parsedData.find(d => d.title === '22DUNK');
                    if (dunk22 &&
                        dunk22.normal.difficulty === 5 &&
                        dunk22.hyper.difficulty === 5 &&
                        dunk22.another.difficulty === 5) {
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
