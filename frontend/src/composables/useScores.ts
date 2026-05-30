import { ref } from 'vue';
import type { ScoreData, DifficultyStats } from '../types/ScoreData';
import { useAuth } from './useAuth';

/** バックエンド API のベース URL。 */
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

/**
 * 【内部ヘルパー】 同一(曲,難易度)に複数 source（arcade / infinitas）の行が来たとき、
 * 表示用に 1 つの DifficultyStats へ集約する。
 *
 * ルール:
 *  - スロットがまだ emptyDiff プレースホルダ（`source` 未設定）なら無条件で採用する
 *  - 既に実レコードが入っている場合は **EX SCORE が高い方を残す**（同点は先着＝先に
 *    アップロードされた方を維持。CSV→画面取得の順で取り込むため通常 arcade が残る）
 *
 * stat には呼び出し側で `source` を必ず付与しておくこと（API が source を返さない場合は
 * 'arcade' 既定にする）。これにより「source 未設定＝プレースホルダ」が安定して判定できる。
 */
function assignBestStat(entry: any, diffKey: string, stat: DifficultyStats): void {
    const cur = entry[diffKey] as DifficultyStats | undefined;
    if (!cur || cur.source === undefined || stat.score > cur.score) {
        entry[diffKey] = stat;
    }
}

/**
 * 【Composable の役割】 スコア取得系 API（自分 / 他ユーザ / トップランカー / プロフィール / メモ）を一括提供する。
 *
 * API が返す「譜面フラット配列」を、UI が扱いやすい「曲単位にグルーピングした構造」に変換する
 * ロジックが本 composable の主な責務。
 *
 * 使い方:
 * ```ts
 * const { fetchMyScores, fetchUserScores } = useScores();
 * const myScores = await fetchMyScores();
 * ```
 */
export function useScores() {
    /** 何らかのフェッチ実行中フラグ（スピナー表示用）。 */
    const isFetching = ref(false);
    const { authHeaders } = useAuth();

    /**
     * 自分のスコア一覧を取得する。
     *
     * 変換:
     *  - API は「譜面 1 件 = 1 レコード」で返してくるので、同一タイトル内で
     *    beginner / normal / hyper / another / leggendaria を 1 オブジェクトにまとめる
     *
     * 認証エラー (401) の場合は空配列を返す（ログアウト中でもクラッシュしない）。
     */
    const fetchMyScores = async (): Promise<ScoreData[]> => {
        isFetching.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/scores/me`, {
                headers: authHeaders()
            });

            if (!res.ok) {
                if (res.status === 401) {
                    return [];
                }
                throw new Error(`Fetch failed: ${res.status}`);
            }

            const flatScores = await res.json();

            // タイトル単位でグルーピング
            const grouped = new Map<string, any>();

            /** 未プレイ状態の 1 難易度分のダミー値。全難易度を埋めておくため。 */
            const emptyDiff = (): DifficultyStats => ({
                difficulty: null,
                score: 0,
                pgreat: 0,
                great: 0,
                missCount: null,
                clearType: 'NO PLAY',
                djLevel: '---',
                options: undefined,
                id: undefined
            });

            flatScores.forEach((s: any) => {
                const title = s.title;
                // 初出タイトルは「全難易度 NO PLAY」のテンプレートを用意
                if (!grouped.has(title)) {
                    grouped.set(title, {
                        version: '0',
                        title: title,
                        genre: s.genre || '',
                        artist: s.artist || '',
                        playCount: s.playCount || 0,
                        lastPlayTime: '',
                        beginner: emptyDiff(),
                        normal: emptyDiff(),
                        hyper: emptyDiff(),
                        another: emptyDiff(),
                        leggendaria: emptyDiff(),
                    });
                }

                const entry = grouped.get(title);
                // API の 'ANOTHER' を UI の 'another' キーに正規化
                const diffKey = s.difficultyName.toLowerCase() as keyof ScoreData;

                // arcade / infinitas が並走する場合は EX SCORE が高い方を残し、その source を持たせる。
                assignBestStat(entry, diffKey, {
                    id: s.id,
                    difficulty: s.difficultyLevel,
                    score: s.score,
                    pgreat: s.pgreat || 0,
                    great: s.great || 0,
                    missCount: s.missCount,
                    clearType: s.clearType,
                    djLevel: s.djLevel,
                    options: Array.isArray(s.options) ? s.options : undefined,
                    source: s.source || 'arcade',
                });
            });

            return Array.from(grouped.values());
        } finally {
            isFetching.value = false;
        }
    };


    /**
     * 管理画面で使う「全ユーザー一覧」取得。
     * 管理者権限が無い場合はサーバ側で 403 が返る。
     */
    const fetchAllUsers = async () => {
        isFetching.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/admin/users`, {
                headers: authHeaders()
            });

            if (!res.ok) {
                throw new Error(`Fetch failed: ${res.status}`);
            }
            return await res.json();
        } finally {
            isFetching.value = false;
        }
    };

    /**
     * 他ユーザーのスコア一覧を取得する（閲覧権限は mode で切り分け）。
     *
     * @param userId 対象ユーザーの ID
     * @param mode   - `admin`: 管理者閲覧 / `friend`: フレンド閲覧 / `public`: 全公開ユーザー閲覧
     *
     * 401 / 403 は空配列を返す（「見れない」だけで例外にはしない）。
     */
    const fetchUserScores = async (
        userId: number,
        mode: 'admin' | 'friend' | 'public' = 'admin'
    ): Promise<ScoreData[]> => {
        isFetching.value = true;
        try {
            // 管理者 → admin / フレンド閲覧 → friend / 全公開ユーザ → public
            const url =
                mode === 'friend' ? `${API_BASE}/api/friends/${userId}/scores`
                : mode === 'public' ? `${API_BASE}/api/users/${userId}/scores`
                : `${API_BASE}/api/admin/users/${userId}/scores`;
            const res = await fetch(url, {
                headers: authHeaders()
            });

            if (!res.ok) {
                if (res.status === 401 || res.status === 403) {
                    return [];
                }
                throw new Error(`Fetch failed: ${res.status}`);
            }

            const flatScores = await res.json();
            // fetchMyScores と同じグルーピング処理
            const grouped = new Map<string, any>();

            const emptyDiff = (): DifficultyStats => ({
                difficulty: null,
                score: 0,
                pgreat: 0,
                great: 0,
                missCount: null,
                clearType: 'NO PLAY',
                djLevel: '---',
                options: undefined,
                id: undefined
            });

            flatScores.forEach((s: any) => {
                const title = s.title;
                if (!grouped.has(title)) {
                    grouped.set(title, {
                        version: '0',
                        title: title,
                        genre: s.genre || '',
                        artist: s.artist || '',
                        playCount: s.playCount || 0,
                        lastPlayTime: '',
                        beginner: emptyDiff(),
                        normal: emptyDiff(),
                        hyper: emptyDiff(),
                        another: emptyDiff(),
                        leggendaria: emptyDiff(),
                    });
                }

                const entry = grouped.get(title);
                const diffKey = s.difficultyName.toLowerCase() as keyof ScoreData;

                // arcade / infinitas が並走する場合は EX SCORE が高い方を残し、その source を持たせる。
                assignBestStat(entry, diffKey, {
                    id: s.id,
                    difficulty: s.difficultyLevel,
                    score: s.score,
                    pgreat: s.pgreat || 0,
                    great: s.great || 0,
                    missCount: s.missCount,
                    clearType: s.clearType,
                    djLevel: s.djLevel,
                    options: Array.isArray(s.options) ? s.options : undefined,
                    source: s.source || 'arcade',
                });
            });

            return Array.from(grouped.values());
        } finally {
            isFetching.value = false;
        }
    };

    /**
     * 特定バージョン × 都道府県の「トップランカー」プロフィールとスコアを取得する。
     *
     * 公開 API なので未ログインでも叩ける（認証ヘッダなし）。
     *
     * @returns プロフィールとスコア。失敗時は `{ profile: null, scores: [] }`
     */
    const fetchTopRankerProfile = async (
        versionNum: number,
        prefectureFileNum: number
    ): Promise<{ profile: any | null; scores: ScoreData[] }> => {
        isFetching.value = true;
        try {
            const res = await fetch(
                `${API_BASE}/api/scores/top-ranker-profile?versionNum=${versionNum}&prefectureFileNum=${prefectureFileNum}`
            );
            if (!res.ok) return { profile: null, scores: [] };
            const data = await res.json();

            const grouped = new Map<string, any>();
            const emptyDiff = (): DifficultyStats => ({
                difficulty: null,
                score: 0,
                pgreat: 0,
                great: 0,
                missCount: null,
                clearType: 'NO PLAY',
                djLevel: '---',
                options: undefined,
                id: undefined
            });

            // トップランカー API は pgreat/great/missCount を持たないため、その辺は 0/null で埋める
            (data.scores ?? []).forEach((s: any) => {
                const title = s.title;
                if (!grouped.has(title)) {
                    grouped.set(title, {
                        version: '0',
                        title,
                        genre: '',
                        artist: '',
                        playCount: 0,
                        lastPlayTime: '',
                        djName: s.djName ?? '',
                        beginner: emptyDiff(),
                        normal: emptyDiff(),
                        hyper: emptyDiff(),
                        another: emptyDiff(),
                        leggendaria: emptyDiff(),
                    });
                }
                const entry = grouped.get(title);
                const diffKey = s.difficultyName.toLowerCase() as keyof ScoreData;
                assignBestStat(entry, diffKey, {
                    id: undefined,
                    difficulty: s.difficultyLevel,
                    score: s.score,
                    pgreat: 0,
                    great: 0,
                    missCount: null,
                    clearType: s.clearType ?? 'NO PLAY',
                    djLevel: s.djLevel ?? '---',
                    options: undefined,
                    djName: s.djName ?? undefined,
                    source: s.source || 'arcade',
                } as any);
            });

            return {
                profile: {
                    versionNum: data.versionNum,
                    versionName: data.versionName,
                    prefectureFileNum: data.prefectureFileNum,
                    prefectureName: data.prefectureName,
                },
                scores: Array.from(grouped.values()),
            };
        } finally {
            isFetching.value = false;
        }
    };

    /**
     * ユーザーの公開プロフィール情報を取得する（未ログインでも叩ける）。
     */
    const fetchPublicProfile = async (userId: number): Promise<any | null> => {
        const res = await fetch(`${API_BASE}/api/users/${userId}/profile`);
        if (!res.ok) return null;
        return await res.json();
    };

    /**
     * 指定ユーザーとの「フレンド関係」を問い合わせる。
     * @returns `'none' | 'friend' | 'pending'` 等のステータス文字列
     */
    const fetchFriendStatus = async (userId: number): Promise<string> => {
        const res = await fetch(`${API_BASE}/api/users/${userId}/friend-status`, {
            headers: authHeaders(),
        });
        if (!res.ok) return 'none';
        const data = await res.json();
        return data.status ?? 'none';
    };

    return {
        /** 自分のスコア一覧取得。 */
        fetchMyScores,
        /** 他ユーザーのスコア一覧取得（権限モード切替可）。 */
        fetchUserScores,
        /** 管理画面用: 全ユーザー一覧。 */
        fetchAllUsers,
        /** トップランカーのプロフィール + スコア取得。 */
        fetchTopRankerProfile,
        /** 公開プロフィール取得。 */
        fetchPublicProfile,
        /** フレンド関係ステータス取得。 */
        fetchFriendStatus,
        /** フェッチ実行中フラグ。 */
        isFetching,
    };
}
