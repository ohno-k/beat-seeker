/**
 * 曲マスタ・難易度表などの曲名を公式 CSV の表記へ揃える一回限りのスクリプト(2026-09-30)
 *
 * 方針(2026-09-30 ユーザー指示): 公式 CSV の曲名が正。bemaniwiki の表記揺れには追従しない。
 * CSV と表記が違ったため scores と突き合わず、MAX-率が出ない・難易度表に載らない状態だった 4 曲:
 *   Any%                                  → Any％            (CSV は全角％)
 *   Shogun's Last Dawn feat. Kanae Asaba  → ... ft. ...      (CSV は ft.)
 *   華麗なる！音戯探偵ひなビタ♫               → ...ひなビタ      (CSV は ♫ 無し。マスタ・難易度表は二重登録だった)
 *   ZEИITH                                → ZENITH           (ZINRAI の CSV から変更。33 の CSV は ZEИITH。
 *                                                             大小文字違いの Zenith(Snail's House)は別曲)
 *
 * ひなビタの二重登録: 曲マスタは ♫ 付き(textage・wiki の ARTIST を持つ方)を残して CSV 表記へ改名し、
 * ♫ 無しの旧行を削除する。難易度表は ♫ 無しが数値帯、♫ 付きが Uncategorized に居るので ♫ 付き側を削除する。
 * past_scores の ZEИITH → ZENITH で同じユーザー・作品・難易度に両方ある行は
 * DataInitializer.mergeBestInto と同じ規則で良いほうを残す。
 *
 * Usage:
 *   node scripts/rename-titles-to-csv-20260930.js          # dry-run(件数表示のみ)
 *   node scripts/rename-titles-to-csv-20260930.js --apply  # 変更前の行を data/rename_titles_backup_20260930.json に保存してから書き換える
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

const HINABITA_OLD = '華麗なる！音戯探偵ひなビタ♫';
const HINABITA_CSV = '華麗なる！音戯探偵ひなビタ';
const RENAMES = [
    ['Any%', 'Any％'],
    ["Shogun's Last Dawn feat. Kanae Asaba", "Shogun's Last Dawn ft. Kanae Asaba"],
    [HINABITA_OLD, HINABITA_CSV],
    ['ZEИITH', 'ZENITH'],
];
// 難易度表は LEGGENDARIA を "曲名[L]" で持つので [L] 付きも対象にする
const RANK_RENAMES = RENAMES.flatMap(([o, n]) => [[o, n], [`${o}[L]`, `${n}[L]`]]);

// 曲名を持つ列(2026-09-30 に旧表記の行があった列。ユニーク制約の衝突は事前確認で 0 件)
const SIMPLE_COLUMNS = [
    ['chart_tendency_profiles', 'title'],
    ['competition_matches', 'song1_title'],
    ['competition_matches', 'song2_title'],
    ['competition_picks', 'song_title'],
    ['tier_votes', 'title'],
    ['option_votes', 'title'],
    ['practice_menu_items', 'title'],
    ['rank_quiz_mistakes', 'title'],
    ['virtual_arena_ranker_scores', 'title'],
];

const CLEAR_RANK = {
    'FULLCOMBO CLEAR': 7, 'EX HARD CLEAR': 6, 'HARD CLEAR': 5, 'CLEAR': 4,
    'EASY CLEAR': 3, 'ASSIST CLEAR': 2, 'FAILED': 1,
};
const clearRank = t => CLEAR_RANK[t] || 0;

/** DataInitializer.mergeBestInto と同じ規則で source の良い値を target へ取り込んだ行を返す。 */
function mergeBest(target, source) {
    const t = { ...target };
    if ((source.score || 0) > (t.score || 0)) {
        t.score = source.score; t.dj_level = source.dj_level; t.pgreat = source.pgreat; t.great = source.great;
    }
    if (clearRank(source.clear_type) > clearRank(t.clear_type)) t.clear_type = source.clear_type;
    if (source.miss_count != null && (t.miss_count == null || source.miss_count < t.miss_count)) t.miss_count = source.miss_count;
    if (source.play_count != null && (t.play_count == null || source.play_count > t.play_count)) t.play_count = source.play_count;
    if (source.last_played_at != null && (t.last_played_at == null || source.last_played_at > t.last_played_at)) t.last_played_at = source.last_played_at;
    return t;
}

async function main() {
    const client = new Client(DB_CONFIG);
    await client.connect();
    console.log(`接続OK (${APPLY ? 'APPLY' : 'dry-run'})`);
    const q = (sql, params) => client.query(sql, params);
    const backup = {};

    await q('BEGIN');
    try {
        // ── 1. 曲マスタ: ひなビタの ♫ 無し旧行を消してから改名 ──
        const sdOlds = RENAMES.map(r => r[0]);
        backup.song_definitions = (await q(
            `SELECT * FROM song_definitions WHERE title = ANY($1) OR title = $2`, [sdOlds, HINABITA_CSV])).rows;
        const sdDel = await q(
            `DELETE FROM song_definitions sd WHERE sd.title = $1
               AND EXISTS (SELECT 1 FROM song_definitions o WHERE o.title = $2 AND o.revision = sd.revision)`,
            [HINABITA_CSV, HINABITA_OLD]);
        console.log(`song_definitions: ひなビタ(♫無し)の重複 ${sdDel.rowCount} 行を削除`);
        for (const [o, n] of RENAMES) {
            const r = await q(`UPDATE song_definitions SET title = $2 WHERE title = $1`, [o, n]);
            console.log(`song_definitions: ${o} → ${n}: ${r.rowCount} 行`);
        }

        // ── 2. 難易度表(全リビジョン): 同じリビジョンに CSV 表記が既にあれば旧表記側を削除、無ければ改名 ──
        backup.difficulty_rank_songs = (await q(
            `SELECT s.*, r.revision, r.rank_value FROM difficulty_rank_songs s
               JOIN difficulty_ranks r ON r.id = s.difficulty_rank_id
              WHERE s.song_title = ANY($1)`, [RANK_RENAMES.map(r => r[0])])).rows;
        for (const [o, n] of RANK_RENAMES) {
            const del = await q(
                `DELETE FROM difficulty_rank_songs s USING difficulty_ranks r
                  WHERE r.id = s.difficulty_rank_id AND s.song_title = $1
                    AND EXISTS (SELECT 1 FROM difficulty_rank_songs s2 JOIN difficulty_ranks r2 ON r2.id = s2.difficulty_rank_id
                                 WHERE r2.revision = r.revision AND s2.song_title = $2)`, [o, n]);
            const upd = await q(`UPDATE difficulty_rank_songs SET song_title = $2 WHERE song_title = $1`, [o, n]);
            if (del.rowCount || upd.rowCount) console.log(`difficulty_rank_songs: ${o} → ${n}: 改名 ${upd.rowCount} / 重複削除 ${del.rowCount}`);
        }

        // ── 3. その他の曲名列 ──
        for (const [table, col] of SIMPLE_COLUMNS) {
            backup[`${table}.${col}`] = (await q(`SELECT * FROM ${table} WHERE ${col} = ANY($1)`, [sdOlds])).rows;
            for (const [o, n] of RENAMES) {
                const r = await q(`UPDATE ${table} SET ${col} = $2 WHERE ${col} = $1`, [o, n]);
                if (r.rowCount) console.log(`${table}.${col}: ${o} → ${n}: ${r.rowCount} 行`);
            }
        }

        // ── 4. past_scores: ZEИITH → ZENITH(同一ユーザー・作品・難易度に両方あれば統合) ──
        const [psOld, psNew] = ['ZEИITH', 'ZENITH'];
        const legacyRows = (await q(`SELECT * FROM past_scores WHERE title = $1`, [psOld])).rows;
        backup.past_scores = legacyRows.slice();
        let renamed = 0, merged = 0;
        for (const row of legacyRows) {
            const cp = (await q(
                `SELECT * FROM past_scores WHERE user_id = $1 AND version = $2 AND title = $3 AND difficulty_name = $4`,
                [row.user_id, row.version, psNew, row.difficulty_name])).rows[0];
            if (!cp) {
                await q(`UPDATE past_scores SET title = $2 WHERE id = $1`, [row.id, psNew]);
                renamed++;
                continue;
            }
            backup.past_scores.push(cp);
            const m = mergeBest(cp, row);
            await q(
                `UPDATE past_scores SET score = $2, dj_level = $3, pgreat = $4, great = $5, clear_type = $6,
                        miss_count = $7, play_count = $8, last_played_at = $9 WHERE id = $1`,
                [cp.id, m.score, m.dj_level, m.pgreat, m.great, m.clear_type, m.miss_count, m.play_count, m.last_played_at]);
            await q(`DELETE FROM past_scores WHERE id = $1`, [row.id]);
            merged++;
            console.log(`  統合: user ${row.user_id} v${row.version} ${row.difficulty_name} score ${row.score}/${cp.score} → ${m.score}`);
        }
        console.log(`past_scores: ${psOld} → ${psNew}: 改名 ${renamed} / 統合 ${merged}`);

        if (!APPLY) {
            await q('ROLLBACK');
            console.log('\n(dry-run のためロールバックしました)');
        } else {
            const file = path.join(__dirname, '..', 'data', 'rename_titles_backup_20260930.json');
            fs.writeFileSync(file, JSON.stringify(backup, null, 1));
            await q('COMMIT');
            console.log(`\nコミットしました。変更前の行: data/rename_titles_backup_20260930.json`);
        }
    } catch (e) {
        await q('ROLLBACK');
        throw e;
    } finally {
        await client.end();
    }
}

main().catch(e => { console.error('ERROR:', e.message); process.exit(1); });
