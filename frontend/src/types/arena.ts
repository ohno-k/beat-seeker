/**
 * ARENA モード API の DTO 型定義。
 *
 * Backend の {@link com.beatseeker.backend.controller.ArenaController} の
 * record 群（BattleData / PlayerData / SongData / SongScore）と対応する。
 *
 * 注: バックエンドは `GET /api/arena/matches` で Map<String, Object> として
 *     返すため、実行時の型は緩い（余剰フィールドが混じる可能性あり）。
 *     フロント側は取り込み時にキャストする前提の型定義。
 */

/** 楽曲別スコアと獲得ポイント（Backend: ArenaController.SongScore）。 */
export interface ArenaSongScore {
    score: number;
    pt: number;
}

/** 対戦参加プレイヤー 1 名分の情報（Backend: ArenaController.PlayerData）。 */
export interface ArenaPlayer {
    djName: string;
    arenaClass: string;
    totalPt: number;
    rank: number;
    songScores: ArenaSongScore[];
}

/** 対戦で使われた楽曲情報（Backend: ArenaController.SongData）。 */
export interface ArenaSong {
    title: string;
    difficulty: string;
}

/**
 * 対戦 1 件分のデータ。
 *
 * Backend の `GET /api/arena/matches` が返す Map<String, Object> を想定し、
 * id / myRank / myTotalPt / myArenaClass のトップレベルカラムも含む。
 */
export interface ArenaMatch {
    id: number;
    battleType: string;
    matchDate: string;
    myDjName: string;
    myArenaClass: string;
    myRank: number;
    myTotalPt: number;
    players: ArenaPlayer[];
    songs: ArenaSong[];
}
