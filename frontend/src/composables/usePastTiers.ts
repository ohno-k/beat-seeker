import { ref, readonly } from 'vue';
import { API_BASE } from './constants';
import { getRankInfo } from '../utils/beatTier';

/**
 * 【Composable の役割】 前作（＝アーカイブ済みで最も新しい作品）の BEAT-TIER を引けるようにする。
 *
 * 現実世界の概念: IIDX は新作稼働のたびにスコアがリセットされ、beat-seeker でも BEAT-PT が
 * 0 から積み直しになる。そのため新作初日のアイコンは全員 Beginner から始まるが、
 * 「前作でどこまで到達したか」はティアアイコンの<b>外枠の発光</b>として残す。
 * その外枠を描くのに必要な前作ティアを供給するのがこの composable。
 *
 * 設計上の判断:
 *  - <b>サーバはティア名ではなく BEAT-PT を返す。</b> ティアは PT から導出でき、導出ロジックは
 *    {@link getRankInfo} に一本化されている。文字列で二重に持つと、閾値を調整したときに
 *    実体と表示がずれる（サーバ側 `VersionPtSnapshot` の設計方針と同じ理由）。
 *  - <b>全ユーザーぶんを 1 回だけ取ってモジュールスコープにキャッシュする。</b> ランキング一覧では
 *    数百行に同時にアイコンが並ぶため、行ごとに問い合わせる作りにはできない。
 *    アーカイブは撮影後に変化しない不変データなので、セッション中に取り直す必要もない。
 *  - <b>アーカイブが無い間は誰にも外枠が付かない。</b> 前作の記録が無い人（新規の方）に
 *    外枠を付けないのと同じ扱いで、初回のスナップショットが撮られるまでは全員がその状態になる。
 *
 * 使い方:
 * ```ts
 * const { ensureLoaded, pastTierOf } = usePastTiers();
 * await ensureLoaded();
 * const past = pastTierOf(userId);        // 記録が無ければ null
 * const other = pastTierOfIidxId(iidxId); // userId が無い画面ではこちら
 * ```
 */

/** 前作のティア 1 件ぶん。{@link getRankInfo} の戻り値に元の PT を添えたもの。 */
export interface PastTierInfo {
    /** ランク名（例: 'Mythic'）。 */
    name: string;
    /** 同一ランク内のサブティア（1〜5）。Beginner / Legend は undefined。 */
    tier?: number;
    /** 導出元になった前作の最終 BEAT-PT。 */
    totalBeatPt: number;
}

/** サーバから返る 1 行。 */
interface PreviousTierRow {
    userId: number;
    iidxId: string | null;
    totalBeatPt: number | null;
}

/** 前作の作品バージョン番号。アーカイブが 1 件も無ければ null。 */
const previousVersion = ref<number | null>(null);

/** userId → 前作の最終 BEAT-PT。取得前は空。 */
const ptByUserId = ref<Map<number, number>>(new Map());

/**
 * iidxId → 前作の最終 BEAT-PT。
 * 他ユーザーのダッシュボードのように、閲覧対象を IIDX ID でしか持たない画面のために用意している。
 */
const ptByIidxId = ref<Map<string, number>>(new Map());

/**
 * 取得済みかどうか。
 * 「取得したが 0 件だった」と「まだ取得していない」を区別するために ref とは別に持つ。
 */
const loaded = ref(false);

/**
 * 実行中の取得。複数のコンポーネントが同時に {@link ensureLoaded} を呼んでも
 * リクエストが 1 本になるよう、Promise を共有する。
 */
let inFlight: Promise<void> | null = null;

/**
 * 【関数の役割】 前作の BEAT-PT 一覧をまだ取っていなければ取得する。
 *
 * 失敗しても例外を投げない。外枠は装飾であって、取得できなければ「外枠なし」で描けば済む。
 * ここで throw すると呼び出し元の描画まで巻き込んで壊れてしまう。
 * ただし失敗時は取得済みフラグを立てないので、次に呼ばれたときに再試行される。
 */
async function ensureLoaded(): Promise<void> {
    if (loaded.value) return;
    if (inFlight) return inFlight;

    inFlight = (async () => {
        try {
            const res = await fetch(`${API_BASE}/api/past-rankings/previous-tiers`);
            if (!res.ok) return;
            const data = await res.json() as { version: number | null; entries: PreviousTierRow[] };
            const byUserId = new Map<number, number>();
            const byIidxId = new Map<string, number>();
            for (const row of data.entries ?? []) {
                if (row.totalBeatPt == null) continue;
                const pt = Number(row.totalBeatPt);
                if (row.userId != null) byUserId.set(Number(row.userId), pt);
                if (row.iidxId) byIidxId.set(row.iidxId, pt);
            }
            previousVersion.value = data.version ?? null;
            ptByUserId.value = byUserId;
            ptByIidxId.value = byIidxId;
            tierCache.clear();
            loaded.value = true;
        } catch {
            // ネットワークエラー。外枠なしで描画を続ける（次回呼び出しで再試行）。
        } finally {
            inFlight = null;
        }
    })();

    return inFlight;
}

/**
 * 導出済みティアのメモ。
 *
 * {@link getRankInfo} は呼ぶたびに全ランク定義（53 件）をソートするため、
 * ランキング一覧のように 1 行あたり複数回引かれる場所では素直に呼ぶと無駄が大きい。
 * 元データが不変なので、一度導出した結果はそのまま使い回せる。
 */
const tierCache = new Map<string, PastTierInfo>();

/**
 * 【関数の役割】 指定ユーザーの前作ティアを返す。
 *
 * @param userId 対象ユーザー ID。null/undefined は「記録なし」と同じ扱い。
 * @returns 前作の記録があればティア情報。無ければ null（＝外枠を付けない）
 */
function pastTierOf(userId: number | null | undefined): PastTierInfo | null {
    if (userId == null) return null;
    return lookup(`u:${Number(userId)}`, ptByUserId.value.get(Number(userId)));
}

/**
 * 【関数の役割】 指定 IIDX ID の前作ティアを返す。
 *
 * 他ユーザーのダッシュボードなど、閲覧対象を userId で持っていない画面向け。
 *
 * @param iidxId 対象の IIDX ID。null/undefined は「記録なし」と同じ扱い。
 */
function pastTierOfIidxId(iidxId: string | null | undefined): PastTierInfo | null {
    if (!iidxId) return null;
    return lookup(`i:${iidxId}`, ptByIidxId.value.get(iidxId));
}

/**
 * 【関数の役割】 引き当てた BEAT-PT をティアへ変換し、メモに載せて返す。
 *
 * @param cacheKey メモの鍵。userId 由来と iidxId 由来が衝突しないよう接頭辞で分けてある
 * @param pt       引き当てた前作の最終 BEAT-PT。undefined なら記録なし
 */
function lookup(cacheKey: string, pt: number | undefined): PastTierInfo | null {
    const cached = tierCache.get(cacheKey);
    if (cached) return cached;
    if (pt == null) return null;
    const rank = getRankInfo(pt);
    const info: PastTierInfo = { name: rank.name, tier: rank.tier, totalBeatPt: pt };
    tierCache.set(cacheKey, info);
    return info;
}

/**
 * 【関数の役割】 BEAT-PT から直接ティアを組み立てる。
 *
 * 過去作ランキング画面のように、行そのものが前作の PT を持っている場合に使う
 * （userId 経由の引き当てを挟まずに済む）。
 *
 * @param totalBeatPt 前作の最終 BEAT-PT
 */
function pastTierFromPt(totalBeatPt: number | null | undefined): PastTierInfo | null {
    if (totalBeatPt == null) return null;
    const rank = getRankInfo(totalBeatPt);
    return { name: rank.name, tier: rank.tier, totalBeatPt };
}

export function usePastTiers() {
    return {
        /** 前作の作品バージョン番号（アーカイブが無ければ null）。 */
        previousVersion: readonly(previousVersion),
        /** 取得済みかどうか。 */
        loaded: readonly(loaded),
        ensureLoaded,
        pastTierOf,
        pastTierOfIidxId,
        pastTierFromPt,
    };
}
