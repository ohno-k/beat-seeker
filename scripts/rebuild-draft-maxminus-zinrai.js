/**
 * ZINRAI 向け「全曲 MAX-率 再判定」ドラフト作成スクリプト
 *
 * 【配置ルール】(2026-08-19 ユーザー確定)
 *  1. **13.1 は手動指定**。MANUAL_TOP の 4 曲を固定で置き、それ以外の曲は 13.1 に入らない
 *     (物量加点でも 13.0 が上限)。MAX-率が 0.0% でも自動昇格はしない。
 *  2. 残りを MAX-率 昇順 → 同率は平均スコアレート昇順(曲別平均スコアレートページと同じ並び)で 1 列化し、
 *     13.0 以下は active の帯別曲数を定員として上から詰める。
 *     **定員を消費するのは既に数値帯にいた曲だけ**で、Uncategorized から新しく表に入る曲は
 *     自分の MAX-率順位が指す帯へ相乗りする(その帯が 1 曲増える。2026-08-19 ユーザー指示)。
 *     これにより既存曲の帯サイズは完全に維持され、増分は新規参入分だけになる。
 *  3. 物量(ノーツ数)加点: 1800以上で +0.1、以降 200 ごとに +0.1。
 *     **加点で到達できるのは 12.8 まで**(12.9 以上へは押し上げない。MAX-率だけで 12.9 以上にいる曲は据え置き)。
 *     少ノーツ側の減点は設けない。
 *  4. MAX-率のデータが無い曲(未プレイ等)は Uncategorized に残す。
 *
 * 【2案】物量加点の効かせ方が異なる。--plan で切り替える。
 *   A(上乗せ)  : 2 の配置に 3 をそのまま加算する。帯サイズは加点の分だけ変動する。
 *   B(詰め直し): 3 を適用した後の序列で改めて定員へ詰め直す。既存曲の帯サイズは active と一致する。
 *
 * MAX-率の定義は ScoreRepository.findSongMaxMinusCounts と同一
 * (score*9 >= notes*17 を満たしたプレイ行の割合。低いほど高難度。平均スコアレートとは別物)。
 *
 * Usage:
 *   node scripts/rebuild-draft-maxminus-zinrai.js                    # dry-run (DB読み取りのみ)
 *   node scripts/rebuild-draft-maxminus-zinrai.js --apply            # 2案をprofileに保存 + draft を B で更新
 *   node scripts/rebuild-draft-maxminus-zinrai.js --apply --plan=A   # draft を A で更新
 *
 * 出力: data/zinrai_rebuild_report.md, data/zinrai_rebuild_changes.json
 * 注意: 本番DB(Render)に直接接続する。active には一切触れない。
 */

const { Client } = require('pg');
const fs = require('fs');
const path = require('path');

const DB_CONFIG = {
    host: 'dpg-d6f68314tr6s73bnbhag-a.oregon-postgres.render.com',
    database: 'beatseeker',
    user: 'postgress',
    password: 'kAw2xymPeLH4mOZuV76hsJCR4L9kFkgM',
    ssl: { rejectUnauthorized: false },
    connectionTimeoutMillis: 20000,
};

const BONUS_BASE = 1800, BONUS_STEP = 200;   // 1800以上で+0.1、以降200ごと(減点は無し)
const BONUS_CAP_TENTHS = 128; // 物量加点で到達できる上限は 12.8(それより上へは押し上げない)
const CAP_TENTHS = 130;   // 13.1 は手動枠なので、自動配置の上限は 13.0
const FLOOR_TENTHS = 110; // 11.0 でクランプ
const TOP_RANK = '13.1';  // 手動指定枠
/** 13.1 に固定で置く曲(手動指定)。ここに無い曲は 13.1 に入らない。 */
const MANUAL_TOP = ['Mare Nectaris', '惑星鉄道', 'SμG@R RU$#', '駅猫のワルツ'];
const PROFILE_A = 'profile:ZINRAI-planA';
const PROFILE_B = 'profile:ZINRAI-planB';

const APPLY = process.argv.includes('--apply');
const PLAN_ARG = (process.argv.find(a => a.startsWith('--plan=')) || '--plan=B').split('=')[1].toUpperCase();

const isNumericRank = r => /^\d+\.\d$/.test(r);
const tenthsOf = r => Math.round(parseFloat(r) * 10);
const rankOfTenths = t => (t / 10).toFixed(1);
/** 難易度表の曲名キー: LEGGENDARIA は末尾 "[L]"(スペース無)。 */
const keyOf = (title, diffName) => (diffName === 'LEGGENDARIA' ? `${title}[L]` : title);

/** ノーツ数 → 帯の加点ステップ(0.1 単位)。1800 未満は補正なし。 */
function notesSteps(notes) {
    if (notes == null || notes < BONUS_BASE) return 0;
    return Math.floor((notes - BONUS_BASE) / BONUS_STEP) + 1;
}

/**
 * ベース帯に物量加点を乗せた帯を返す。加点で到達できるのは BONUS_CAP_TENTHS(12.8)まで。
 * MAX-率だけで既に 12.9 以上にいる曲は据え置く(加点で下がることはない)。
 */
function applyNotesBonus(baseTenths, notes) {
    const t = baseTenths + notesSteps(notes);
    return Math.max(baseTenths, Math.min(t, BONUS_CAP_TENTHS));
}

const MAXMINUS_SQL = `
SELECT s.title AS title, s.difficulty_name AS "difficultyName",
       COUNT(CASE WHEN s.score * 9 >= sd.notes * 17 THEN 1 END) AS "maxMinusCount",
       COUNT(*) AS "totalCount",
       ROUND(AVG(s.score)::numeric, 1) AS "avgScore",
       MAX(sd.notes) AS notes
FROM scores s
JOIN song_definitions sd ON s.title = sd.title AND sd.revision = 'active'
  AND ((s.difficulty_name = 'ANOTHER' AND sd.difficulty = '4')
    OR (s.difficulty_name = 'LEGGENDARIA' AND sd.difficulty = '10'))
WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA')
  AND s.difficulty_level IN (11, 12)
  AND s.score > 0
  AND sd.level >= 11
GROUP BY s.title, s.difficulty_name`;

const ACTIVE_SQL = `
SELECT r.rank_value AS rank, r.sort_order AS "sortOrder", s.song_title AS title, s.sort_order AS "songOrder"
FROM difficulty_ranks r
LEFT JOIN difficulty_rank_songs s ON s.difficulty_rank_id = r.id
WHERE r.revision = 'active'
ORDER BY r.sort_order, s.sort_order`;

/** 帯構成(空帯を含む)と、曲→現行帯 を組み立てる。 */
function buildActive(rows) {
    const ranks = new Map(); // rank -> {sortOrder, songs[]}
    const currentRank = new Map();
    for (const row of rows) {
        if (!ranks.has(row.rank)) ranks.set(row.rank, { sortOrder: row.sortOrder, songs: [] });
        if (row.title) {
            ranks.get(row.rank).songs.push(row.title);
            currentRank.set(row.title, row.rank);
        }
    }
    return { ranks, currentRank };
}

/**
 * 並んだ曲列を定員へ詰める。定員を消費するのは `existing`(既に数値帯にいた)曲だけで、
 * Uncategorized から新しく入る曲は現在の帯へ相乗りする(定員を食わないので帯が 1 曲増える)。
 * 定員を使い切った後の曲は最下帯が受け止める。
 */
function packByQuota(seq, quota) {
    const placed = new Map();
    let qi = 0, used = 0;
    for (const s of seq) {
        while (qi < quota.length - 1 && used >= quota[qi].count) { qi++; used = 0; }
        placed.set(s.key, quota[qi].tenths);
        if (s.existing) used++;
    }
    return placed;
}

async function main() {
    const client = new Client(DB_CONFIG);
    await client.connect();
    console.log(`接続OK (${APPLY ? `APPLY モード / draft = 案${PLAN_ARG}` : 'dry-run モード'})`);

    const mmRows = (await client.query(MAXMINUS_SQL)).rows;
    const { ranks: activeRanks, currentRank } = buildActive((await client.query(ACTIVE_SQL)).rows);

    // ── MAX-率 / 平均スコアレート ──
    const stat = new Map();
    for (const r of mmRows) {
        const total = Number(r.totalCount);
        const notes = r.notes == null ? null : Number(r.notes);
        const avgScore = r.avgScore == null ? null : Number(r.avgScore);
        stat.set(keyOf(r.title, r.difficultyName), {
            total, notes,
            maxMinusRate: total > 0 ? (Number(r.maxMinusCount) / total) * 100 : null,
            avgScoreRate: (avgScore != null && notes) ? (avgScore / (notes * 2)) * 100 : null,
        });
    }

    // ── 対象曲(active に載っていて MAX-率データがある曲) ──
    // existing = 既に数値帯にいた曲。false は Uncategorized から新しく表に入る曲。
    const songs = [];
    const noData = [];
    for (const [title, rank] of currentRank) {
        if (stat.has(title)) songs.push({ key: title, existing: isNumericRank(rank), ...stat.get(title) });
        else noData.push(title);
    }
    songs.sort((a, b) => (a.maxMinusRate - b.maxMinusRate) || (a.avgScoreRate - b.avgScoreRate));
    console.log(`対象 ${songs.length}曲 / MAX-率データ無し ${noData.length}曲(Uncategorized へ残置)`);

    // ── 定員(13.0以下、active の帯別曲数) ──
    const quota = [...activeRanks.entries()]
        .filter(([rank]) => isNumericRank(rank))
        .map(([rank, v]) => ({ rank, tenths: tenthsOf(rank), count: v.songs.length }))
        .sort((a, b) => b.tenths - a.tenths)
        .filter(q => q.tenths <= tenthsOf(TOP_RANK) - 1);

    const manualSet = new Set(MANUAL_TOP);
    const missing = MANUAL_TOP.filter(t => !songs.some(s => s.key === t));
    if (missing.length) throw new Error(`13.1 手動指定の曲が見つからない: ${missing.join(', ')}`);
    const topSongs = songs.filter(s => manualSet.has(s.key));
    const restSongs = songs.filter(s => !manualSet.has(s.key));
    const base = packByQuota(restSongs, quota);
    for (const s of topSongs) base.set(s.key, tenthsOf(TOP_RANK));
    const newcomers = songs.filter(s => !s.existing);
    console.log(`${TOP_RANK} = 手動指定 ${topSongs.length}曲: ${MANUAL_TOP.join(' / ')}`);
    console.log(`Uncategorized からの新規参入 ${newcomers.length}曲 = 定員を消費せず相乗り（その帯が増える）`);

    // ── 案A: 上乗せ(13.1 の手動枠はそのまま据え置く) ──
    const planA = new Map();
    for (const s of songs) {
        if (manualSet.has(s.key)) { planA.set(s.key, tenthsOf(TOP_RANK)); continue; }
        const t = applyNotesBonus(base.get(s.key), s.notes);
        planA.set(s.key, Math.max(FLOOR_TENTHS, Math.min(CAP_TENTHS, t)));
    }

    // ── 案B: 補正後の序列で定員へ詰め直し ──
    const orderIdx = new Map(songs.map((s, idx) => [s.key, idx]));
    const seqB = restSongs
        .map(s => ({
            key: s.key,
            existing: s.existing,
            band: Math.max(FLOOR_TENTHS, Math.min(CAP_TENTHS, applyNotesBonus(base.get(s.key), s.notes))),
            idx: orderIdx.get(s.key),
        }))
        .sort((a, b) => (b.band - a.band) || (a.idx - b.idx));
    const planB = packByQuota(seqB, quota);
    for (const s of topSongs) planB.set(s.key, tenthsOf(TOP_RANK));

    // ── 帯構成の組み立て(帯内は MAX-率順) ──
    function buildRanks(plan) {
        const byRank = new Map();
        for (const [rank, v] of activeRanks) byRank.set(rank, { sortOrder: v.sortOrder, songs: [] });
        for (const s of songs) {
            const rank = rankOfTenths(plan.get(s.key));
            if (!byRank.has(rank)) byRank.set(rank, { sortOrder: 0, songs: [] });
            byRank.get(rank).songs.push(s.key);
        }
        for (const t of noData) {
            const uncat = [...byRank.keys()].find(r => !isNumericRank(r));
            byRank.get(uncat).songs.push(t);
        }
        return [...byRank.entries()]
            .sort((a, b) => a[1].sortOrder - b[1].sortOrder)
            .map(([rank, v]) => ({ rank, sort: v.sortOrder, songs: v.songs }));
    }

    const ranksA = buildRanks(planA);
    const ranksB = buildRanks(planB);

    const sizeLine = rs => rs.filter(r => r.songs.length || isNumericRank(r.rank))
        .map(r => `${r.rank}:${r.songs.length}`).join('  ');
    const moveStat = plan => {
        let up = 0, down = 0, same = 0, uncat = 0, maxUp = 0, maxDown = 0;
        for (const [key, t] of plan) {
            const cur = currentRank.get(key);
            if (!isNumericRank(cur)) { uncat++; continue; }
            const d = t - tenthsOf(cur);
            if (d > 0) { up++; maxUp = Math.max(maxUp, d); }
            else if (d < 0) { down++; maxDown = Math.min(maxDown, d); }
            else same++;
        }
        return { up, down, same, uncat, maxUp: maxUp / 10, maxDown: maxDown / 10 };
    };

    const mvA = moveStat(planA), mvB = moveStat(planB);
    console.log(`\n【現行active】 ${sizeLine([...activeRanks.entries()].sort((a, b) => a[1].sortOrder - b[1].sortOrder).map(([rank, v]) => ({ rank, songs: v.songs })))}`);
    console.log(`\n【案A 上乗せ】   ${sizeLine(ranksA)}`);
    console.log(`  昇格${mvA.up} 降格${mvA.down} 据置${mvA.same} Uncat由来${mvA.uncat} (最大 +${mvA.maxUp} / ${mvA.maxDown})`);
    console.log(`\n【案B 詰め直し】 ${sizeLine(ranksB)}`);
    console.log(`  昇格${mvB.up} 降格${mvB.down} 据置${mvB.same} Uncat由来${mvB.uncat} (最大 +${mvB.maxUp} / ${mvB.maxDown})`);

    // ── レポート出力 ──
    const changes = songs.map(s => ({
        title: s.key,
        current: currentRank.get(s.key),
        base: rankOfTenths(base.get(s.key)),
        planA: rankOfTenths(planA.get(s.key)),
        planB: rankOfTenths(planB.get(s.key)),
        maxMinusRate: Number(s.maxMinusRate.toFixed(2)),
        avgScoreRate: s.avgScoreRate == null ? null : Number(s.avgScoreRate.toFixed(2)),
        notes: s.notes,
        plays: s.total,
        notesSteps: notesSteps(s.notes),
    }));
    const dataDir = path.join(__dirname, '..', 'data');
    fs.writeFileSync(path.join(dataDir, 'zinrai_rebuild_changes.json'), JSON.stringify(changes, null, 1));

    const md = [];
    md.push('# ZINRAI 全曲再判定ドラフト（MAX-率ベース）', '');
    md.push(`- 対象 ${songs.length} 曲 / データ無し ${noData.length} 曲（${noData.join(', ') || 'なし'}）`);
    md.push(`- ${TOP_RANK} = 手動指定 ${topSongs.length} 曲（${MANUAL_TOP.join(' / ')}）。それ以外は 13.0 が上限`);
    md.push(`- Uncategorized からの新規参入 ${newcomers.length} 曲 = 定員を消費せず相乗り（その帯が増える）`);
    md.push(`  - 着地先: ${[...newcomers.reduce((m, s) => m.set(rankOfTenths(planB.get(s.key)), (m.get(rankOfTenths(planB.get(s.key))) || 0) + 1), new Map())]
        .sort((a, b) => parseFloat(b[0]) - parseFloat(a[0])).map(([r, n]) => `${r}×${n}`).join(' / ')}`);
    md.push(`- 物量加点: ${songs.filter(s => notesSteps(s.notes) > 0).length} 曲（1800 以上 +0.1、以降 200 ごと +0.1、到達上限 12.8）`, '');
    md.push('## 帯別曲数', '', '| 帯 | 現行 | 案A | 案B |', '| --- | ---: | ---: | ---: |');
    const cntA = new Map(ranksA.map(r => [r.rank, r.songs.length]));
    const cntB = new Map(ranksB.map(r => [r.rank, r.songs.length]));
    for (const [rank, v] of [...activeRanks.entries()].sort((a, b) => a[1].sortOrder - b[1].sortOrder)) {
        md.push(`| ${rank} | ${v.songs.length} | ${cntA.get(rank) ?? 0} | ${cntB.get(rank) ?? 0} |`);
    }
    md.push('', '## 移動量', '', `- 案A: 昇格 ${mvA.up} / 降格 ${mvA.down} / 据置 ${mvA.same}（最大 +${mvA.maxUp} / ${mvA.maxDown}）`);
    md.push(`- 案B: 昇格 ${mvB.up} / 降格 ${mvB.down} / 据置 ${mvB.same}（最大 +${mvB.maxUp} / ${mvB.maxDown}）`, '');
    md.push('## 案B で 0.3 帯以上動く曲', '', '| 差 | 曲名 | 現行 → 案B | MAX-率 | ノーツ |', '| ---: | --- | --- | ---: | ---: |');
    for (const c of changes
        .filter(c => isNumericRank(c.current) && Math.abs(parseFloat(c.planB) - parseFloat(c.current)) >= 0.3)
        .sort((a, b) => Math.abs(parseFloat(b.planB) - parseFloat(b.current)) - Math.abs(parseFloat(a.planB) - parseFloat(a.current)))) {
        const d = (parseFloat(c.planB) - parseFloat(c.current)).toFixed(1);
        md.push(`| ${d > 0 ? '+' : ''}${d} | ${c.title} | ${c.current} → ${c.planB} | ${c.maxMinusRate}% | ${c.notes} |`);
    }
    fs.writeFileSync(path.join(dataDir, 'zinrai_rebuild_report.md'), md.join('\n'));
    console.log('\nレポート: data/zinrai_rebuild_report.md / data/zinrai_rebuild_changes.json');

    if (!APPLY) {
        console.log('\n(dry-run のため DB は変更していません)');
        await client.end();
        return;
    }

    // ── 書き込み: 2案を profile に保存し、指定案を draft へ ──
    async function writeRevision(revision, ranks) {
        const oldIds = (await client.query(`SELECT id FROM difficulty_ranks WHERE revision = $1`, [revision])).rows.map(r => r.id);
        if (oldIds.length > 0) {
            await client.query(`DELETE FROM difficulty_rank_songs WHERE difficulty_rank_id = ANY($1)`, [oldIds]);
            await client.query(`DELETE FROM difficulty_ranks WHERE id = ANY($1)`, [oldIds]);
        }
        let n = 0;
        for (const r of ranks) {
            const { rows } = await client.query(
                `INSERT INTO difficulty_ranks (rank_value, sort_order, revision) VALUES ($1, $2, $3) RETURNING id`,
                [r.rank, r.sort, revision]
            );
            if (r.songs.length > 0) {
                await client.query(
                    `INSERT INTO difficulty_rank_songs (difficulty_rank_id, song_title, sort_order)
                     SELECT $1, t.title, t.ord - 1
                     FROM unnest($2::text[]) WITH ORDINALITY AS t(title, ord)`,
                    [rows[0].id, r.songs]
                );
                n += r.songs.length;
            }
        }
        console.log(`  ${revision}: ${ranks.length}帯 / ${n}曲`);
    }

    await client.query('BEGIN');
    try {
        await writeRevision(PROFILE_A, ranksA);
        await writeRevision(PROFILE_B, ranksB);
        await writeRevision('draft', PLAN_ARG === 'A' ? ranksA : ranksB);
        await client.query('COMMIT');
        console.log(`\n保存完了。draft = 案${PLAN_ARG}。管理画面のプロファイル読込で ${PROFILE_A} / ${PROFILE_B} を切り替えられます。`);
    } catch (e) {
        await client.query('ROLLBACK');
        throw e;
    } finally {
        await client.end();
    }
}

main().catch(e => { console.error('ERROR:', e.message); process.exit(1); });
