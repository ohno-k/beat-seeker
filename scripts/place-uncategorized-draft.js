/**
 * active の Uncategorized 曲を、難易度表 draft の数値帯へ入れ直すスクリプト
 *
 * 【配置ルール】(2026-09-09 ユーザー指示)
 *  1. 対象 = active 難易度表の Uncategorized に居る曲(＝現在ユーザーから「未分類」に見えている曲)。
 *     draft 側で既にどこかの帯に置かれていても、いったん外して置き直す。
 *  2. プレイ人数 n > MIN_PLAYS(既定 200) の曲だけを配置する。n は MAX-率の分母と同じ
 *     (score>0 のスコア行数 = その譜面を登録しているユーザー数)。n が閾値以下・データ無しは Uncategorized に残す。
 *  3. ベース帯 = MAX-率順位が指す帯。draft の既存曲(対象外の数値帯曲)を
 *     MAX-率 昇順 → 平均スコアレート 昇順 で 1 列に並べ、帯別曲数を定員として上から詰めたときに
 *     対象曲の順位が落ちる帯へ「相乗り」する(定員は消費しない = 既存曲は動かさない。ZINRAI 移行計画と同じ扱い)。
 *  4. 物量(ノーツ数)加点 = ZINRAI 移行計画のルール: 1800 以上で +0.1、以降 200 ごとに +0.1、
 *     加点で到達できるのは 12.8 まで(MAX-率だけで 12.9 以上なら据え置き)、減点は無し。
 *  5. 13.1 は手動枠なので自動配置の上限は 13.0。
 *
 * MAX-率の定義は ScoreRepository.findSongMaxMinusCounts と同一
 * (score*9 >= notes*17 を満たしたスコア行の割合。低いほど高難度。平均スコアレートとは別物)。
 *
 * Usage:
 *   node scripts/place-uncategorized-draft.js                 # dry-run (DB読み取りのみ)
 *   node scripts/place-uncategorized-draft.js --apply         # 現 draft を profile にバックアップして draft を更新
 *   node scripts/place-uncategorized-draft.js --min-plays=200 # n の閾値(この値より大きい曲だけ配置)
 *   node scripts/place-uncategorized-draft.js --backup=<name> # バックアップ profile 名(既定 pre-uncat-place-YYYYMMDD)
 *
 * 出力: data/uncat_place_report.md
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

const BONUS_BASE = 1800, BONUS_STEP = 200; // 1800以上で+0.1、以降200ごと(減点は無し)
const BONUS_CAP_TENTHS = 128;              // 物量加点で到達できる上限は 12.8
const CAP_TENTHS = 130;                    // 13.1 は手動枠なので自動配置の上限は 13.0
const FLOOR_TENTHS = 110;

const APPLY = process.argv.includes('--apply');
const argOf = (name, def) => (process.argv.find(a => a.startsWith(`--${name}=`)) || `--${name}=${def}`).split('=').slice(1).join('=');
const MIN_PLAYS = Number(argOf('min-plays', 200));
const today = new Date().toISOString().slice(0, 10).replace(/-/g, '');
const BACKUP_PROFILE = `profile:${argOf('backup', `pre-uncat-place-${today}`)}`;

const isNumericRank = r => /^\d+\.\d$/.test(r);
const tenthsOf = r => Math.round(parseFloat(r) * 10);
const rankOfTenths = t => (t / 10).toFixed(1);
const keyOf = (title, diffName) => (diffName === 'LEGGENDARIA' ? `${title}[L]` : title);

function notesSteps(notes) {
    if (notes == null || notes < BONUS_BASE) return 0;
    return Math.floor((notes - BONUS_BASE) / BONUS_STEP) + 1;
}
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

const RANKS_SQL = `
SELECT r.revision, r.rank_value AS rank, r.sort_order AS "sortOrder", s.song_title AS title
FROM difficulty_ranks r
LEFT JOIN difficulty_rank_songs s ON s.difficulty_rank_id = r.id
WHERE r.revision IN ('active', 'draft')
ORDER BY r.revision, r.sort_order, s.sort_order`;

/** revision ごとに 帯 → {sortOrder, songs[]}(Map, 帯の並び順) と 曲 → 帯 を組み立てる。 */
function buildRevisions(rows) {
    const out = {};
    for (const row of rows) {
        const rev = out[row.revision] || (out[row.revision] = { ranks: new Map(), rankOf: new Map() });
        if (!rev.ranks.has(row.rank)) rev.ranks.set(row.rank, { sortOrder: row.sortOrder, songs: [] });
        if (row.title) {
            rev.ranks.get(row.rank).songs.push(row.title);
            rev.rankOf.set(row.title, row.rank);
        }
    }
    return out;
}

/**
 * 並んだ曲列を定員へ詰める。定員を消費するのは existing の曲だけで、
 * 対象曲(existing=false)は現在の帯へ相乗りする。
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
    console.log(`接続OK (${APPLY ? 'APPLY モード' : 'dry-run モード'}) / 配置条件 n > ${MIN_PLAYS}`);

    const stat = new Map();
    for (const r of (await client.query(MAXMINUS_SQL)).rows) {
        const total = Number(r.totalCount);
        const notes = r.notes == null ? null : Number(r.notes);
        const avgScore = r.avgScore == null ? null : Number(r.avgScore);
        stat.set(keyOf(r.title, r.difficultyName), {
            total, notes,
            maxMinusRate: total > 0 ? (Number(r.maxMinusCount) / total) * 100 : null,
            avgScoreRate: (avgScore != null && notes) ? (avgScore / (notes * 2)) * 100 : null,
        });
    }
    const revs = buildRevisions((await client.query(RANKS_SQL)).rows);
    const active = revs.active, draft = revs.draft;
    if (!active || !draft) throw new Error('active / draft のどちらかが存在しない');
    const draftUncatRank = [...draft.ranks.keys()].find(r => !isNumericRank(r));
    if (!draftUncatRank) throw new Error('draft に Uncategorized 帯が無い');

    // ── 対象曲 = active の Uncategorized に居る曲 ──
    const targets = [...active.rankOf.entries()].filter(([, rank]) => !isNumericRank(rank)).map(([title]) => title);
    const targetSet = new Set(targets);
    console.log(`対象(active Uncategorized) ${targets.length}曲 / うち draft で数値帯に置かれている ${targets.filter(t => isNumericRank(draft.rankOf.get(t))).length}曲 / draft 未収録 ${targets.filter(t => !draft.rankOf.has(t)).length}曲`);

    // ── 既存曲 = draft の数値帯に居る対象外の曲。順位付け用の列と帯別定員を作る ──
    const existing = [];
    const quotaCount = new Map(); // tenths -> count(MAX-率データを持つ既存曲のみ)
    for (const [title, rank] of draft.rankOf) {
        if (targetSet.has(title) || !isNumericRank(rank)) continue;
        const s = stat.get(title);
        if (!s || s.maxMinusRate == null || s.avgScoreRate == null) continue; // データ無しは順位付けから外し定員にも数えない
        existing.push({ key: title, existing: true, ...s });
        quotaCount.set(tenthsOf(rank), (quotaCount.get(tenthsOf(rank)) || 0) + 1);
    }
    const quota = [...draft.ranks.keys()].filter(isNumericRank).map(tenthsOf)
        .filter(t => t <= CAP_TENTHS).sort((a, b) => b - a)
        .map(t => ({ tenths: t, count: quotaCount.get(t) || 0 }));

    // ── 対象曲の仕分け ──
    const placeable = [], stay = [];
    for (const t of targets) {
        const s = stat.get(t);
        if (!s || s.maxMinusRate == null || s.avgScoreRate == null) stay.push({ key: t, reason: 'データ無し' });
        else if (s.total <= MIN_PLAYS) stay.push({ key: t, reason: `n=${s.total} ≤ ${MIN_PLAYS}`, ...s });
        else placeable.push({ key: t, existing: false, ...s });
    }
    const seq = [...existing, ...placeable].sort((a, b) => (a.maxMinusRate - b.maxMinusRate) || (a.avgScoreRate - b.avgScoreRate));
    const base = packByQuota(seq, quota);
    const rankIdx = new Map(seq.map((s, i) => [s.key, i]));

    const results = placeable.map(s => {
        const b = Math.max(FLOOR_TENTHS, Math.min(CAP_TENTHS, base.get(s.key)));
        const fin = Math.max(FLOOR_TENTHS, Math.min(CAP_TENTHS, applyNotesBonus(b, s.notes)));
        return { ...s, baseTenths: b, finalTenths: fin, steps: notesSteps(s.notes), rankPos: rankIdx.get(s.key) + 1 };
    }).sort((a, b) => a.rankPos - b.rankPos);

    // ── 表示 ──
    const pad = (v, n) => String(v).padStart(n);
    console.log(`\n順位付け母集団: 既存 ${existing.length}曲 + 対象 ${placeable.length}曲 = ${seq.length}曲`);
    console.log('\n' + '曲名'.padEnd(40) + '   n   MAX-率   平均率  notes  順位  ベース 加点  配置   (draft現在)');
    for (const r of results) {
        console.log(`${r.key.padEnd(40)} ${pad(r.total, 4)} ${pad(r.maxMinusRate.toFixed(2), 6)}% ${pad(r.avgScoreRate.toFixed(2), 6)}% ${pad(r.notes, 5)} ${pad(r.rankPos, 5)}  ${rankOfTenths(r.baseTenths)}  ${r.steps ? '+0.' + r.steps : '  - '}  ${rankOfTenths(r.finalTenths)}   (${draft.rankOf.get(r.key) || '未収録'})`);
    }
    if (stay.length) {
        console.log('\nUncategorized に残す:');
        for (const s of stay) console.log(`  ${s.key.padEnd(40)} ${s.reason}`);
    }

    // ── 新しい draft を組み立てる: 対象曲を全帯から外し、配置先の帯末尾へ追加 ──
    const newRanks = [...draft.ranks.entries()].map(([rank, v]) => ({
        rank, sort: v.sortOrder, songs: v.songs.filter(t => !targetSet.has(t)),
    }));
    const byRank = new Map(newRanks.map(r => [r.rank, r]));
    for (const r of results) {
        const rank = rankOfTenths(r.finalTenths);
        if (!byRank.has(rank)) throw new Error(`draft に帯 ${rank} が無い`);
        byRank.get(rank).songs.push(r.key);
    }
    for (const s of stay) byRank.get(draftUncatRank).songs.push(s.key);

    const sizeLine = ranks => ranks.map(r => `${r.rank}:${r.songs.length}`).join('  ');
    console.log(`\n【draft 現在】 ${sizeLine([...draft.ranks.entries()].map(([rank, v]) => ({ rank, songs: v.songs })))}`);
    console.log(`【draft 更新後】 ${sizeLine(newRanks)}`);
    const moved = results.filter(r => draft.rankOf.get(r.key) !== rankOfTenths(r.finalTenths));
    console.log(`\ndraft の現在位置から変わる曲: ${moved.length} / ${results.length}`);

    // ── レポート ──
    const md = [];
    md.push('# Uncategorized 曲の draft 配置（MAX-率順位 + 物量加点）', '');
    md.push(`- 実行日: ${new Date().toISOString().slice(0, 10)} / 配置条件: プレイ人数 n > ${MIN_PLAYS}`);
    md.push(`- 対象 = active の Uncategorized ${targets.length} 曲 → 配置 ${results.length} 曲 / 残置 ${stay.length} 曲（${stay.map(s => `${s.key}: ${s.reason}`).join(', ') || 'なし'}）`);
    md.push('- ベース帯 = draft 既存曲との MAX-率順位（昇順→平均スコアレート昇順）が指す帯へ相乗り（既存曲は動かさない）');
    md.push('- 物量加点 = 1800 以上 +0.1、以降 200 ごと +0.1、到達上限 12.8、減点なし', '');
    md.push('| 順位 | 曲名 | n | MAX-率 | 平均率 | ノーツ | ベース | 加点 | 配置 | draft 現在 |', '| ---: | --- | ---: | ---: | ---: | ---: | --- | --- | --- | --- |');
    for (const r of results) {
        md.push(`| ${r.rankPos} | ${r.key} | ${r.total} | ${r.maxMinusRate.toFixed(2)}% | ${r.avgScoreRate.toFixed(2)}% | ${r.notes} | ${rankOfTenths(r.baseTenths)} | ${r.steps ? '+0.' + r.steps : '-'} | **${rankOfTenths(r.finalTenths)}** | ${draft.rankOf.get(r.key) || '未収録'} |`);
    }
    md.push('', '## 帯別曲数（draft）', '', '| 帯 | 現在 | 更新後 |', '| --- | ---: | ---: |');
    for (const r of newRanks) md.push(`| ${r.rank} | ${draft.ranks.get(r.rank).songs.length} | ${r.songs.length} |`);
    const dataDir = path.join(__dirname, '..', 'data');
    fs.writeFileSync(path.join(dataDir, 'uncat_place_report.md'), md.join('\n') + '\n');
    console.log('\nレポート: data/uncat_place_report.md');

    if (!APPLY) {
        console.log('\n(dry-run のため DB は変更していません)');
        await client.end();
        return;
    }

    // ── 書き込み: 現 draft を profile にバックアップしてから draft を置き換える ──
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
        const currentDraft = [...draft.ranks.entries()].map(([rank, v]) => ({ rank, sort: v.sortOrder, songs: v.songs }));
        await writeRevision(BACKUP_PROFILE, currentDraft);
        await writeRevision('draft', newRanks);
        await client.query('COMMIT');
        console.log(`\n保存完了。適用前の draft は ${BACKUP_PROFILE} に保存済み(管理画面のプロファイル読込で復元可)。`);
    } catch (e) {
        await client.query('ROLLBACK');
        throw e;
    } finally {
        await client.end();
    }
}

main().catch(e => { console.error('ERROR:', e.message); process.exit(1); });
