/**
 * 難易度表の Uncategorized 帯を1本化するスクリプト
 *
 * 動作: active / draft の両リビジョンについて、
 *   1. "Uncategorized(IIDX 32)" / "Uncategorized(IIDX 33)" 帯を削除する。
 *      (曲が入っていた場合は "Uncategorized(other)" の末尾へ移してから削除 = 曲は失わない)
 *   2. "Uncategorized(other)" を "Uncategorized" にリネームする。
 *   profile:* リビジョンには触れない。
 *
 * Usage:
 *   node scripts/merge-uncategorized.js            # dry-run
 *   node scripts/merge-uncategorized.js --apply    # 本適用
 *
 * 注意: 本番DB(Render)に直接接続する。数値帯・曲の所属には一切触れない。
 */

const { Client } = require('pg');

const DB_CONFIG = {
    host: 'dpg-d6f68314tr6s73bnbhag-a.oregon-postgres.render.com',
    database: 'beatseeker',
    user: 'postgress',
    password: 'kAw2xymPeLH4mOZuV76hsJCR4L9kFkgM',
    ssl: { rejectUnauthorized: false },
    connectionTimeoutMillis: 15000,
};

const APPLY = process.argv.includes('--apply');
const REVISIONS = ['active', 'draft'];
const DELETE_RANKS = ['Uncategorized(IIDX 32)', 'Uncategorized(IIDX 33)'];
const RENAME_FROM = 'Uncategorized(other)';
const RENAME_TO = 'Uncategorized';

async function main() {
    const client = new Client(DB_CONFIG);
    await client.connect();
    console.log(`接続OK (${APPLY ? 'APPLY モード' : 'dry-run モード'})`);

    for (const revision of REVISIONS) {
        console.log(`\n━━ revision = '${revision}' ━━`);
        const ranks = (await client.query(
            `SELECT dr.id, dr.rank_value, dr.sort_order,
                    (SELECT COUNT(*) FROM difficulty_rank_songs s WHERE s.difficulty_rank_id = dr.id)::int AS song_count
             FROM difficulty_ranks dr
             WHERE dr.revision = $1 AND dr.rank_value LIKE 'Uncategorized%'
             ORDER BY dr.sort_order`, [revision]
        )).rows;
        if (ranks.length === 0) {
            console.log('  Uncategorized系の帯なし。スキップ。');
            continue;
        }
        for (const r of ranks) {
            console.log(`  ${r.rank_value} (id=${r.id}, sort=${r.sort_order}, ${r.song_count}曲)`);
        }

        const toDelete = ranks.filter(r => DELETE_RANKS.includes(r.rank_value));
        const other = ranks.find(r => r.rank_value === RENAME_FROM);
        const already = ranks.find(r => r.rank_value === RENAME_TO);

        if (already) {
            console.log(`  既に '${RENAME_TO}' 帯が存在します。リネームはスキップ。`);
        }
        if (!other && !already) {
            console.log(`  '${RENAME_FROM}' 帯が見つかりません。リネームはスキップ。`);
        }

        // 計画表示
        for (const r of toDelete) {
            if (r.song_count > 0) {
                const songs = (await client.query(
                    `SELECT song_title FROM difficulty_rank_songs WHERE difficulty_rank_id = $1 ORDER BY sort_order`, [r.id]
                )).rows.map(x => x.song_title);
                console.log(`  計画: ${r.rank_value} の ${r.song_count}曲 [${songs.join(', ')}] を ${RENAME_FROM} 末尾へ移動して帯を削除`);
            } else {
                console.log(`  計画: ${r.rank_value} (空) を削除`);
            }
        }
        if (other && !already) {
            console.log(`  計画: ${RENAME_FROM} → ${RENAME_TO} にリネーム`);
        }

        if (!APPLY) continue;

        await client.query('BEGIN');
        try {
            for (const r of toDelete) {
                if (r.song_count > 0) {
                    if (!other) throw new Error(`${r.rank_value} に曲があるのに移動先 ${RENAME_FROM} がありません。中断。`);
                    // 移動先末尾の sort_order に続けて詰める
                    await client.query(
                        `UPDATE difficulty_rank_songs s
                         SET difficulty_rank_id = $1,
                             sort_order = (SELECT COALESCE(MAX(sort_order), 0) FROM difficulty_rank_songs WHERE difficulty_rank_id = $1)
                                          + s.sort_order + 1
                         WHERE s.difficulty_rank_id = $2`, [other.id, r.id]
                    );
                }
                // FK は NO ACTION なので子(曲)を先に空にしてから親を消す(上で移動済みのため残0)。
                await client.query(`DELETE FROM difficulty_rank_songs WHERE difficulty_rank_id = $1`, [r.id]);
                await client.query(`DELETE FROM difficulty_ranks WHERE id = $1`, [r.id]);
            }
            if (other && !already) {
                await client.query(
                    `UPDATE difficulty_ranks SET rank_value = $1 WHERE id = $2`, [RENAME_TO, other.id]
                );
            }
            await client.query('COMMIT');
            console.log('  適用完了。');
        } catch (e) {
            await client.query('ROLLBACK');
            throw e;
        }
    }

    // 適用後の確認
    if (APPLY) {
        const after = (await client.query(
            `SELECT revision, rank_value, sort_order,
                    (SELECT COUNT(*) FROM difficulty_rank_songs s WHERE s.difficulty_rank_id = dr.id)::int AS song_count
             FROM difficulty_ranks dr
             WHERE revision = ANY($1) AND rank_value LIKE 'Uncategorized%'
             ORDER BY revision, sort_order`, [REVISIONS]
        )).rows;
        console.log('\n━━ 適用後の Uncategorized 系帯 ━━');
        for (const r of after) {
            console.log(`  [${r.revision}] ${r.rank_value} (sort=${r.sort_order}, ${r.song_count}曲)`);
        }
    }

    await client.end();
}

main().catch(e => { console.error(e); process.exit(1); });
