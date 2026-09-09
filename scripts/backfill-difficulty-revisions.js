/**
 * 更新履歴「難易度改訂」の第1〜5版を本番 DB の difficulty_revisions へ補填するスクリプト(2026-09-09 の一回限り)
 *
 * 背景: 2026-09-09 の「難易度表を適用」(Uncategorized 21 曲の配置)は、自動記録機能(commit 157fd507)の
 * バックエンドが本番で稼働する前に旧コードで実行されたため、第5版が記録されなかった。
 * さらに起動時シードは「テーブルが空のときだけ」なので、第5版だけを入れると第1〜4版が永久に入らない。
 * → 第1〜4版(frontend/src/data/difficulty_revisions.json)と第5版(適用前 active → 適用後 active の差分)を
 *   まとめて INSERT する。edition には一意制約があるので ON CONFLICT DO NOTHING で二重実行に耐える。
 *
 * 第5版の差分の復元方法:
 *   適用前 active = profile:pre-uncat-place-20260909(=適用前 active + Uncat 17 曲の相乗り)の数値帯から
 *   「適用前 active の Uncategorized に居た 22 曲」を除いたもの。適用後 active は現在の active。
 *   DifficultyTableDiff と同じ判定(数値帯のみ・表示順)で added / changed / removed を求める。
 *
 * Usage:
 *   node scripts/backfill-difficulty-revisions.js            # dry-run(差分を表示するだけ)
 *   node scripts/backfill-difficulty-revisions.js --apply    # INSERT 実行
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
const APPLY = process.argv.includes('--apply');
const BACKUP_PROFILE = 'profile:pre-uncat-place-20260909';

/** 適用前 active の Uncategorized に居た曲(2026-09-09 午前の実測)。Any% は今も Uncategorized。 */
const OLD_UNCATEGORIZED = [
    'Any%', 'Smintheus', '断罪のミメシス', 'D.K.N. ON', 'Dolphin Kick', 'Limelight', 'Ignited Night',
    'メルト CPK! Remix (初音ミク ver.)', 'イガク[L]', 'かわいいだけじゃだめですか？[L]', '寝起きヤシの木[L]',
    'Urban Constellations[L]', 'Alpheratz[L]', 'super double play sessions', 'Digitized Ocean',
    'じぇりー じゅえる ジャングル', 'Iridescent Memories', 'サタデーナイト☆ギャロップ', 'Prohibited Props[L]',
    '27th style[L]', "Raison d'etre～交差する宿命～[L]", '夢色ワンダー',
];

const isNumeric = r => /^\d+\.\d+$/.test((r || '').trim());

const RANKS_SQL = `
SELECT r.revision, r.rank_value AS rank, r.sort_order AS "sortOrder", s.song_title AS title, s.sort_order AS "songOrder"
FROM difficulty_ranks r
LEFT JOIN difficulty_rank_songs s ON s.difficulty_rank_id = r.id
WHERE r.revision = ANY($1)
ORDER BY r.revision, r.sort_order, s.sort_order`;

/** revision → LinkedHashMap(title → 数値帯) を表示順で。 */
function placements(rows, revision) {
    const m = new Map();
    for (const row of rows) {
        if (row.revision !== revision || !row.title || !isNumeric(row.rank)) continue;
        if (!m.has(row.title.trim())) m.set(row.title.trim(), row.rank.trim());
    }
    return m;
}

function diff(before, after) {
    const added = [], changed = [], removed = [];
    for (const [title, rank] of after) {
        const prev = before.get(title);
        if (prev == null) added.push({ title, rank });
        else if (prev !== rank) changed.push({ title, from: prev, to: rank });
    }
    for (const [title, rank] of before) if (!after.has(title)) removed.push({ title, rank });
    return { added, changed, removed };
}

async function main() {
    const client = new Client(DB_CONFIG);
    await client.connect();
    console.log(`接続OK (${APPLY ? 'APPLY' : 'dry-run'})`);

    const existing = (await client.query(`SELECT edition FROM difficulty_revisions ORDER BY edition`)).rows.map(r => r.edition);
    console.log('difficulty_revisions 既存 edition:', existing.length ? existing.join(',') : '(空)');

    const rows = (await client.query(RANKS_SQL, [['active', BACKUP_PROFILE]])).rows;
    const activeNow = placements(rows, 'active');
    const profile = placements(rows, BACKUP_PROFILE);
    if (activeNow.size === 0 || profile.size === 0) throw new Error('active か バックアップ profile が読めない');

    const allActiveTitles = new Set(rows.filter(r => r.revision === 'active' && r.title).map(r => r.title.trim()));
    const missing = OLD_UNCATEGORIZED.filter(t => !allActiveTitles.has(t));
    if (missing.length) throw new Error(`現在の active に見つからない曲名(表記ゆれ?): ${missing.join(' / ')}`);

    // 適用前 active の数値帯 = profile の数値帯 − 適用前 Uncategorized の曲
    const oldUncat = new Set(OLD_UNCATEGORIZED);
    const activeBefore = new Map([...profile].filter(([t]) => !oldUncat.has(t)));
    console.log(`適用前 active(復元) 数値帯 ${activeBefore.size}曲 / 適用後 active 数値帯 ${activeNow.size}曲`);

    const d = diff(activeBefore, activeNow);
    console.log(`第5版 差分: 新規追加 ${d.added.length} / 既存変更 ${d.changed.length} / 表から除外 ${d.removed.length}`);
    for (const a of d.added) console.log(`  + ${a.title.padEnd(40)} ${a.rank}`);
    for (const c of d.changed) console.log(`  ~ ${c.title.padEnd(40)} ${c.from} → ${c.to}`);
    for (const r of d.removed) console.log(`  - ${r.title.padEnd(40)} ${r.rank}`);
    if (d.added.length + d.changed.length + d.removed.length === 0) throw new Error('差分が無い');

    // 第1〜4版(手書き JSON)
    const seedPath = path.join(__dirname, '..', 'frontend', 'src', 'data', 'difficulty_revisions.json');
    const seed = JSON.parse(fs.readFileSync(seedPath, 'utf8'));
    const editions = seed.map(e => ({
        edition: e.version, appVersion: e.appVersion || null,
        appliedAt: `${e.date}-01 00:00:00`,
        added: e.added || [], changed: e.changed || [], removed: e.removed || [],
    }));
    const next = Math.max(...editions.map(e => e.edition)) + 1;
    editions.push({ edition: next, appVersion: null, appliedAt: null, ...d });
    console.log('\nINSERT 予定:', editions.map(e => `第${e.edition}版(${e.appVersion || '自動'} +${e.added.length}/~${e.changed.length}/-${e.removed.length})`).join(' / '));

    if (!APPLY) { console.log('\n(dry-run のため DB は変更していません)'); await client.end(); return; }

    await client.query('BEGIN');
    try {
        let inserted = 0;
        for (const e of editions) {
            const res = await client.query(
                `INSERT INTO difficulty_revisions (edition, app_version, applied_at, added_count, changed_count, removed_count, added_json, changed_json, removed_json)
                 VALUES ($1, $2, COALESCE($3::timestamp, (now() AT TIME ZONE 'Asia/Tokyo')), $4, $5, $6, $7, $8, $9)
                 ON CONFLICT (edition) DO NOTHING`,
                [e.edition, e.appVersion, e.appliedAt, e.added.length, e.changed.length, e.removed.length,
                 JSON.stringify(e.added), JSON.stringify(e.changed), JSON.stringify(e.removed)]
            );
            inserted += res.rowCount;
        }
        await client.query('COMMIT');
        console.log(`\n完了: ${inserted} 行 INSERT(既存 edition はスキップ)`);
        console.log((await client.query(`SELECT edition, app_version, to_char(applied_at, 'YYYY-MM-DD HH24:MI') AS applied_at, added_count, changed_count, removed_count FROM difficulty_revisions ORDER BY edition`)).rows);
    } catch (err) {
        await client.query('ROLLBACK');
        throw err;
    } finally {
        await client.end();
    }
}

main().catch(e => { console.error('ERROR:', e.message); process.exit(1); });
