/**
 * 難易度表draftの指定帯範囲を「ほんの少し上へ」スライドするスクリプト
 *
 * 動作: 範囲内(--from〜--to)の各帯について、帯先頭(最上位)の n 曲をひとつ上の帯の末尾へ繰り上げる。
 *       序列(全体の並び順)は不変で、帯境界だけが n 曲分下へ移動する。
 *       結果として曲数が変わるのは「--toのひとつ上の帯」(+n)と「--fromの帯」(-n)のみ。
 * 例:   --from=11.5 --to=12.9 --n=1 → 12.9の先頭1曲が13.0へ、12.8の先頭1曲が12.9へ、…、11.5の先頭1曲が11.6へ。
 *
 * Usage:
 *   node scripts/nudge-range-up.js --from=11.5 --to=12.9 --n=1            # dry-run
 *   node scripts/nudge-range-up.js --from=11.5 --to=12.9 --n=1 --apply    # バックアップprofile作成 + draft書き換え
 *   (--backup=profile:名前 でバックアップ名を上書き可)
 *
 * 出力: data/nudge_changes.json, data/nudge_report.md
 * 注意: 本番DB(Render)に直接接続する。activeには一切触れない。
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
    connectionTimeoutMillis: 15000,
};

function arg(name, dflt) {
    const m = process.argv.find(a => a.startsWith(`--${name}=`));
    return m ? m.split('=')[1] : dflt;
}
const FROM = arg('from', '11.5');
const TO = arg('to', '12.9');
const N = parseInt(arg('n', '1'), 10);
const APPLY = process.argv.includes('--apply');
const BACKUP_PROFILE = arg('backup', `profile:pre-nudge-${new Date().toISOString().slice(0, 10).replace(/-/g, '')}`);

function isNumericRank(rankValue) {
    return /^\d+\.\d$/.test(rankValue);
}

async function main() {
    if (!(N >= 1)) throw new Error('--n は1以上を指定してください');
    const client = new Client(DB_CONFIG);
    await client.connect();
    console.log(`接続OK (${APPLY ? 'APPLY モード' : 'dry-run モード'}) 範囲=${FROM}〜${TO}, n=${N}`);

    // ── draft読み込み ──
    const rankRows = (await client.query(
        `SELECT id, rank_value, sort_order FROM difficulty_ranks
         WHERE revision = 'draft' ORDER BY sort_order`
    )).rows;
    if (rankRows.length === 0) throw new Error('draft が空です。中断します。');
    const songRows = (await client.query(
        `SELECT dr.rank_value, drs.song_title
         FROM difficulty_ranks dr
         JOIN difficulty_rank_songs drs ON drs.difficulty_rank_id = dr.id
         WHERE dr.revision = 'draft'
         ORDER BY dr.sort_order, drs.sort_order`
    )).rows;
    const ranksOrdered = rankRows.map(r => ({ rank: r.rank_value, sort: r.sort_order, songs: [] }));
    const rankIdxByValue = new Map(ranksOrdered.map((r, i) => [r.rank, i]));
    for (const row of songRows) {
        ranksOrdered[rankIdxByValue.get(row.rank_value)].songs.push(row.song_title);
    }
    const totalBefore = songRows.length;
    const beforeCounts = ranksOrdered.map(r => ({ rank: r.rank, n: r.songs.length }));
    console.log(`現draft: ${ranksOrdered.length}帯 / ${totalBefore}曲`);

    // ── 対象帯の検証(数値帯・連続・上の受け皿があること) ──
    const fromIdx = rankIdxByValue.get(FROM);
    const toIdx = rankIdxByValue.get(TO);
    if (fromIdx == null || toIdx == null) throw new Error(`帯 ${FROM} / ${TO} がdraftにありません`);
    if (toIdx >= fromIdx) throw new Error(`--to (${TO}) は --from (${FROM}) より上の帯にしてください`);
    for (let i = toIdx - 1; i <= fromIdx; i++) {
        if (!isNumericRank(ranksOrdered[i].rank)) throw new Error(`範囲(受け皿含む)に非数値帯 ${ranksOrdered[i].rank} が含まれます`);
    }

    // ── 繰り上げ: 上の帯(TO)から順に、先頭n曲をひとつ上の帯の末尾へ ──
    const newRanks = ranksOrdered.map(r => ({ rank: r.rank, sort: r.sort, songs: [...r.songs] }));
    const changes = [];
    for (let i = toIdx; i <= fromIdx; i++) {
        const donor = newRanks[i];
        const receiver = newRanks[i - 1];
        if (donor.songs.length <= N) throw new Error(`帯 ${donor.rank} の曲数(${donor.songs.length})がn=${N}以下です`);
        const movedSongs = donor.songs.splice(0, N);
        receiver.songs.push(...movedSongs);
        for (const t of movedSongs) changes.push({ title: t, old_rank: donor.rank, new_rank: receiver.rank });
    }
    const totalAfter = newRanks.reduce((s, r) => s + r.songs.length, 0);
    if (totalAfter !== totalBefore) throw new Error(`曲数不一致: before=${totalBefore} after=${totalAfter}`);

    console.log(`移動: ${changes.length}曲`);
    for (const c of changes) console.log(`  ${c.old_rank} → ${c.new_rank} | ${c.title}`);

    // ── 出力ファイル ──
    const dataDir = path.join(__dirname, '..', 'data');
    fs.writeFileSync(
        path.join(dataDir, 'nudge_changes.json'),
        JSON.stringify({
            rule: `${FROM}〜${TO} の各帯先頭${N}曲をひとつ上の帯末尾へ繰り上げ`,
            applied: APPLY,
            backupProfile: BACKUP_PROFILE,
            moved: changes.length,
            changes,
        }, null, 2),
        'utf8'
    );
    const lines = [];
    lines.push('# 帯範囲繰り上げ(nudge)レポート');
    lines.push('');
    lines.push(`- ルール: ${FROM}〜${TO} の各帯先頭${N}曲をひとつ上の帯末尾へ繰り上げ(序列不変)`);
    lines.push(`- モード: ${APPLY ? '**APPLY(draft書き換え済み)**' : 'dry-run(DB未変更)'}`);
    lines.push('');
    lines.push('## 帯別曲数 before → after');
    lines.push('');
    lines.push('| 帯 | before | after | 増減 |');
    lines.push('|---|---:|---:|---:|');
    for (const i of newRanks.keys()) {
        const b = beforeCounts[i].n, a = newRanks[i].songs.length, d = a - b;
        lines.push(`| ${newRanks[i].rank} | ${b} | ${a} | ${d > 0 ? '+' + d : d === 0 ? '±0' : d} |`);
    }
    lines.push('');
    lines.push('## 移動した曲');
    lines.push('');
    lines.push('| 曲 | 移動 |');
    lines.push('|---|---|');
    for (const c of changes) lines.push(`| ${c.title} | ${c.old_rank} → ${c.new_rank} |`);
    lines.push('');
    fs.writeFileSync(path.join(dataDir, 'nudge_report.md'), lines.join('\n'), 'utf8');
    console.log('data/nudge_changes.json, data/nudge_report.md を出力しました');

    // ── APPLY ──
    if (APPLY) {
        const activeCountBefore = (await client.query(
            `SELECT COUNT(*)::int AS n FROM difficulty_ranks WHERE revision = 'active'`
        )).rows[0].n;

        await client.query('BEGIN');
        try {
            const oldProfIds = (await client.query(
                `SELECT id FROM difficulty_ranks WHERE revision = $1`, [BACKUP_PROFILE]
            )).rows.map(r => r.id);
            if (oldProfIds.length > 0) {
                await client.query(`DELETE FROM difficulty_rank_songs WHERE difficulty_rank_id = ANY($1)`, [oldProfIds]);
                await client.query(`DELETE FROM difficulty_ranks WHERE id = ANY($1)`, [oldProfIds]);
            }
            await client.query(
                `INSERT INTO difficulty_ranks (rank_value, sort_order, revision)
                 SELECT rank_value, sort_order, $1 FROM difficulty_ranks WHERE revision = 'draft'`,
                [BACKUP_PROFILE]
            );
            await client.query(
                `INSERT INTO difficulty_rank_songs (difficulty_rank_id, song_title, sort_order)
                 SELECT nr.id, drs.song_title, drs.sort_order
                 FROM difficulty_ranks odr
                 JOIN difficulty_ranks nr ON nr.revision = $1 AND nr.rank_value = odr.rank_value
                 JOIN difficulty_rank_songs drs ON drs.difficulty_rank_id = odr.id
                 WHERE odr.revision = 'draft'`,
                [BACKUP_PROFILE]
            );
            console.log(`バックアップ作成: ${BACKUP_PROFILE}`);

            const draftIds = rankRows.map(r => r.id);
            await client.query(`DELETE FROM difficulty_rank_songs WHERE difficulty_rank_id = ANY($1)`, [draftIds]);
            await client.query(`DELETE FROM difficulty_ranks WHERE id = ANY($1)`, [draftIds]);
            let insertedSongs = 0;
            for (const r of newRanks) {
                const { rows } = await client.query(
                    `INSERT INTO difficulty_ranks (rank_value, sort_order, revision) VALUES ($1, $2, 'draft') RETURNING id`,
                    [r.rank, r.sort]
                );
                if (r.songs.length > 0) {
                    await client.query(
                        `INSERT INTO difficulty_rank_songs (difficulty_rank_id, song_title, sort_order)
                         SELECT $1, t.title, t.ord - 1
                         FROM unnest($2::text[]) WITH ORDINALITY AS t(title, ord)`,
                        [rows[0].id, r.songs]
                    );
                    insertedSongs += r.songs.length;
                }
            }
            await client.query('COMMIT');
            console.log(`draft保存完了: ${newRanks.length}帯 / ${insertedSongs}曲`);
        } catch (e) {
            await client.query('ROLLBACK');
            throw e;
        }

        // ── 適用後検証 ──
        const verify = (await client.query(
            `SELECT dr.rank_value, COUNT(drs.id)::int AS n
             FROM difficulty_ranks dr
             LEFT JOIN difficulty_rank_songs drs ON drs.difficulty_rank_id = dr.id
             WHERE dr.revision = 'draft'
             GROUP BY dr.rank_value, dr.sort_order ORDER BY dr.sort_order`
        )).rows;
        let ok = true;
        for (const i of newRanks.keys()) {
            const v = verify[i];
            if (!v || v.rank_value !== newRanks[i].rank || v.n !== newRanks[i].songs.length) {
                console.error(`  検証NG: ${newRanks[i].rank} 期待${newRanks[i].songs.length} 実際${v ? v.n : 'なし'}`);
                ok = false;
            }
        }
        const activeCountAfter = (await client.query(
            `SELECT COUNT(*)::int AS n FROM difficulty_ranks WHERE revision = 'active'`
        )).rows[0].n;
        if (activeCountBefore !== activeCountAfter) {
            console.error(`  検証NG: active帯数が変化 ${activeCountBefore}→${activeCountAfter}`);
            ok = false;
        }
        const profCount = (await client.query(
            `SELECT COUNT(*)::int AS n FROM difficulty_rank_songs drs
             JOIN difficulty_ranks dr ON dr.id = drs.difficulty_rank_id
             WHERE dr.revision = $1`, [BACKUP_PROFILE]
        )).rows[0].n;
        if (profCount !== totalBefore) {
            console.error(`  検証NG: バックアップ曲数 ${profCount} != 旧draft ${totalBefore}`);
            ok = false;
        }
        console.log(ok ? '適用後検証: すべてOK (draft帯別曲数・active無変化・バックアップ完全)' : '適用後検証: NGあり!上記を確認してください');
    }

    await client.end();
}

main().catch(e => {
    console.error('エラー:', e.message);
    process.exit(1);
});
