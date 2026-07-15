/**
 * scripts/import-arena-top-rankers.js
 * ---------------------------------------------------------------------------
 * scrape-arena-top-rankers.js が --out で出力した JSONL（1 行 = 1 プレイヤー）を、
 * Postgres の virtual_arena_rankers / virtual_arena_ranker_scores へ取り込む。
 *
 * - 既に users に登録済みの IIDX ID はスキップ（本人登録との重複回避）。
 * - IIDX ID 単位で upsert（再実行で最新に置き換え）。
 * - 取り込み後、--recompute-url を指定すればバックエンドの集計キャッシュを再構築する。
 *
 * 実行例:
 *   PGHOST=localhost PGPASSWORD=postgres \
 *     node scripts/import-arena-top-rankers.js --in=C:/Users/oonok/arena_top_rankers.jsonl \
 *       --recompute-url=http://localhost:8080/api/admin/arena-top-rankers/recompute
 *
 * 環境変数: PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD（未設定は localhost/postgres/beatseeker）
 *          ADMIN_API_TOKEN … --recompute-url 用の Bearer トークン（任意）
 * ---------------------------------------------------------------------------
 */
'use strict';

const fs = require('fs');
const { Client } = require('pg');

const args = process.argv.slice(2);
const getOpt = (n, d) => { const h = args.find((a) => a.startsWith(`--${n}=`)); return h ? h.slice(n.length + 3) : d; };
const IN_FILE = getOpt('in', '');
const RECOMPUTE_URL = getOpt('recompute-url', '');
const MAX_RANK = parseInt(getOpt('max-rank', '0'), 10) || 0; // 指定時: rankPos がこれ以下のレコードのみ取り込む

function normalizeIidxId(raw) {
    if (!raw) return null;
    const d = String(raw).replace(/[^0-9]/g, '');
    if (d.length !== 8) return null;
    return `${d.slice(0, 4)}-${d.slice(4)}`;
}

async function upsert(client, rec) {
    const iidxId = normalizeIidxId(rec.iidxId);
    if (!iidxId) return false;
    await client.query('BEGIN');
    try {
        const res = await client.query(
            `INSERT INTO virtual_arena_rankers (iidx_id, dj_name, arena_class, rank_pos, scraped_at, created_at)
             VALUES ($1,$2,$3,$4,NOW(),NOW())
             ON CONFLICT (iidx_id) DO UPDATE
               SET dj_name = EXCLUDED.dj_name, arena_class = EXCLUDED.arena_class,
                   rank_pos = EXCLUDED.rank_pos, scraped_at = NOW()
             RETURNING id`,
            [iidxId, rec.djName || null, rec.arenaClass || null, rec.rankPos || null]
        );
        const rankerId = res.rows[0].id;
        await client.query('DELETE FROM virtual_arena_ranker_scores WHERE ranker_id = $1', [rankerId]);
        // リモートDBへの往復を減らすため、スコアは複数行を1回のINSERTでまとめて投入する（最大500行/クエリ）。
        const scores = rec.scores || [];
        const CHUNK = 500; // 500行 × 10列 = 5000 パラメータ（Postgres上限 65535 未満）
        for (let i = 0; i < scores.length; i += CHUNK) {
            const batch = scores.slice(i, i + CHUNK);
            const values = [];
            const params = [];
            batch.forEach((s, j) => {
                const b = j * 10;
                values.push(`($${b + 1},$${b + 2},$${b + 3},$${b + 4},$${b + 5},$${b + 6},$${b + 7},$${b + 8},$${b + 9},$${b + 10})`);
                params.push(rankerId, s.title, s.difficultyName, s.difficultyLevel || null, s.score || 0,
                    s.pgreat || null, s.great || null, null, s.clearType || 'NO PLAY', s.djLevel || null);
            });
            await client.query(
                `INSERT INTO virtual_arena_ranker_scores
                   (ranker_id, title, difficulty_name, difficulty_level, score, pgreat, great, miss_count, clear_type, dj_level)
                 VALUES ${values.join(',')}`,
                params
            );
        }
        await client.query('COMMIT');
        return true;
    } catch (e) { await client.query('ROLLBACK'); throw e; }
}

async function main() {
    if (!IN_FILE || !fs.existsSync(IN_FILE)) { console.error(`--in=<jsonl> が必要です（存在しません: ${IN_FILE}）`); process.exit(1); }
    const client = new Client({
        host: process.env.PGHOST || 'localhost',
        port: parseInt(process.env.PGPORT || '5432'),
        database: process.env.PGDATABASE || 'beatseeker',
        user: process.env.PGUSER || 'postgres',
        password: process.env.PGPASSWORD || 'postgres',
        ssl: process.env.PGHOST && process.env.PGHOST !== 'localhost' ? { rejectUnauthorized: false } : false,
    });
    await client.connect();
    const reg = new Set((await client.query('SELECT iidx_id FROM users WHERE iidx_id IS NOT NULL')).rows.map((r) => r.iidx_id));
    console.log(`Connected. registered=${reg.size}`);

    let imported = 0, skipped = 0, failed = 0, skippedRank = 0;
    for (const line of fs.readFileSync(IN_FILE, 'utf8').split('\n')) {
        const t = line.trim(); if (!t) continue;
        let rec; try { rec = JSON.parse(t); } catch (_) { failed++; continue; }
        if (MAX_RANK && (rec.rankPos || 0) > MAX_RANK) { skippedRank++; continue; } // rank 上限で除外
        const iidxId = normalizeIidxId(rec.iidxId);
        if (!iidxId) { failed++; continue; }
        if (reg.has(iidxId)) { skipped++; continue; }
        try { if (await upsert(client, rec)) imported++; } catch (e) { console.warn(`upsert failed ${iidxId}: ${e.message}`); failed++; }
    }
    console.log(`Done. imported=${imported} skippedRegistered=${skipped} skippedByRank(>${MAX_RANK})=${skippedRank} failed=${failed}`);
    await client.end();

    if (RECOMPUTE_URL) {
        try {
            const headers = { 'Content-Type': 'application/json' };
            if (process.env.ADMIN_API_TOKEN) headers['Authorization'] = `Bearer ${process.env.ADMIN_API_TOKEN}`;
            const resp = await fetch(RECOMPUTE_URL, { method: 'POST', headers });
            console.log(`Recompute POST -> ${resp.status}`);
        } catch (e) { console.warn(`Recompute failed: ${e.message}`); }
    }
}

main().catch((e) => { console.error(e); process.exit(1); });
