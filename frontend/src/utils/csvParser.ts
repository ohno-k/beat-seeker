import Papa from 'papaparse';
import type { ScoreData, DifficultyStats } from '../types/ScoreData';

const parseDifficulty = (row: any, diff: string): DifficultyStats => {
    const parseNum = (val: string) => (val === '---' || !val ? null : parseInt(val, 10));

    return {
        difficulty: parseNum(row[`${diff} 難易度`]),
        score: parseInt(row[`${diff} スコア`], 10) || 0,
        pgreat: parseInt(row[`${diff} PGreat`], 10) || 0,
        great: parseInt(row[`${diff} Great`], 10) || 0,
        missCount: parseNum(row[`${diff} ミスカウント`]),
        clearType: row[`${diff} クリアタイプ`] || 'NO PLAY',
        djLevel: row[`${diff} DJ LEVEL`] || '---',
    };
};

export const parseScoreCsv = (file: File): Promise<ScoreData[]> => {
    return new Promise((resolve, reject) => {
        Papa.parse(file, {
            header: true,
            skipEmptyLines: true,
            complete: (results: Papa.ParseResult<any>) => {
                try {
                    const parsedData: ScoreData[] = results.data
                        // Filter out any rows that clearly miss a title header (e.g. malformed or empty text at end of file)
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
                    resolve(parsedData);
                } catch (err) {
                    reject(err);
                }
            },
            error: (error: Error) => {
                reject(error);
            }
        });
    });
};
