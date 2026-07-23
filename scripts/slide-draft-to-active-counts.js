/**
 * 難易度表draftの序列を維持したまま、各帯の曲数を現行(active)の定員にスライドするスクリプト
 *
 * ルール:
 * - draftの数値帯(13.1〜11.0)の曲を上から順に1列に並べ(帯sort_order→帯内sort_order)、
 *   activeの帯別曲数を定員として上から詰め直す。
 * - 13.1はactiveでは空(0曲)のため、定員を7曲(2026-07-12新設時の曲数)に上書き。
 * - 定員合計が曲数を上回る/下回る場合は最下帯(11.0)が残りを吸収する。
 *   曲数超過時は下位帯から+1ずつ定員を増やして分散する。
 * - Uncategorized帯はそのまま。
 *
 * Usage:
 *   node scripts/slide-draft-to-active-counts.js            # dry-run (DB読み取りのみ)
 *   node scripts/slide-draft-to-active-counts.js --apply    # バックアップprofile作成 + draft書き換え
 *
 * 出力: data/slide_changes.json, data/slide_report.md
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

const CAPACITY_OVERRIDES = { '13.1': 7 }; // activeでは空のため新設時の曲数を採用
const BACKUP_PROFILE = 'profile:pre-slide-20260721';

const APPLY = process.argv.includes('--apply');

function isNumericRank(rankValue) {
    return /^\d+\.\d$/.test(rankValue);
}

async function main() {
    const client = new Client(DB_CONFIG);
    await client.connect();
    console.log(`接続OK (${APPLY ? 'APPLY モード' : 'dry-run モード'})`);

    // ── draft読み込み(序列の源。空帯も保持) ──
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
    console.log(`現draft: ${ranksOrdered.length}帯 / ${totalBefore}曲`);

    // ── activeの帯別曲数(定員) ──
    const activeRows = (await client.query(
        `SELECT dr.rank_value, COUNT(drs.id)::int AS n
         FROM difficulty_ranks dr
         LEFT JOIN difficulty_rank_songs drs ON drs.difficulty_rank_id = dr.id
         WHERE dr.revision = 'active'
         GROUP BY dr.rank_value, dr.sort_order ORDER BY dr.sort_order`
    )).rows;
    const activeCounts = new Map(activeRows.map(r => [r.rank_value, r.n]));

    // ── 序列(数値帯のみ1列化)と定員 ──
    const numericIdx = ranksOrdered
        .map((r, i) => (isNumericRank(r.rank) ? i : -1))
        .filter(i => i >= 0);
    const sequence = numericIdx.flatMap(i =>
        ranksOrdered[i].songs.map(title => ({ title, oldRank: ranksOrdered[i].rank }))
    );
    console.log(`数値帯の序列: ${sequence.length}曲`);

    const capacities = numericIdx.map(i => {
        const rank = ranksOrdered[i].rank;
        if (rank in CAPACITY_OVERRIDES) return { rank, cap: CAPACITY_OVERRIDES[rank] };
        if (!activeCounts.has(rank)) throw new Error(`activeに帯 ${rank} がありません`);
        return { rank, cap: activeCounts.get(rank) };
    });
    let capSum = capacities.reduce((s, c) => s + c.cap, 0);

    // 曲数超過時: 下位帯から+1ずつ分散。不足時: 最下帯が自然に吸収(残数のみ受け取る)
    if (sequence.length > capSum) {
        let excess = sequence.length - capSum;
        console.log(`定員合計${capSum} < 曲数${sequence.length}: 下位帯から+1ずつ${excess}曲を分散`);
        for (let i = capacities.length - 1; excess > 0; i--) {
            if (i < 0) i = capacities.length - 1;
            capacities[i].cap++;
            excess--;
        }
        capSum = sequence.length;
    } else if (sequence.length < capSum) {
        console.log(`定員合計${capSum} > 曲数${sequence.length}: 最下帯が${capSum - sequence.length}曲分不足のまま吸収`);
    }

    // ── 上から詰め直し ──
    const newRanks = ranksOrdered.map(r => ({ rank: r.rank, sort: r.sort, songs: [] }));
    for (const i of ranksOrdered.keys()) {
        if (!numericIdx.includes(i)) newRanks[i].songs = [...ranksOrdered[i].songs];
    }
    const changes = [];
    let cursor = 0;
    capacities.forEach(({ rank, cap }, ci) => {
        const isLast = ci === capacities.length - 1;
        const take = isLast ? sequence.length - cursor : Math.min(cap, sequence.length - cursor);
        const slice = sequence.slice(cursor, cursor + take);
        cursor += take;
        const idx = rankIdxByValue.get(rank);
        newRanks[idx].songs = slice.map(s => s.title);
        for (const s of slice) {
            if (s.oldRank !== rank) changes.push({ title: s.title, old_rank: s.oldRank, new_rank: rank });
        }
    });
    const totalAfter = newRanks.reduce((s, r) => s + r.songs.length, 0);
    if (cursor !== sequence.length || totalAfter !== totalBefore) {
        throw new Error(`曲数不一致: 割当${cursor}/${sequence.length}, before=${totalBefore} after=${totalAfter}`);
    }

    console.log(`移動: ${changes.length}曲`);
    console.log(`13.1 (${newRanks[rankIdxByValue.get('13.1')].songs.length}曲): ${newRanks[rankIdxByValue.get('13.1')].songs.join(' / ')}`);

    // ── 出力ファイル ──
    const dataDir = path.join(__dirname, '..', 'data');
    fs.writeFileSync(
        path.join(dataDir, 'slide_changes.json'),
        JSON.stringify({
            rule: `序列維持のままactive定員へスライド(override: ${JSON.stringify(CAPACITY_OVERRIDES)})`,
            applied: APPLY,
            backupProfile: BACKUP_PROFILE,
            moved: changes.length,
            changes,
        }, null, 2),
        'utf8'
    );

    const lines = [];
    lines.push('# 序列維持スライドレポート(物量加点draft → active定員)');
    lines.push('');
    lines.push(`- ルール: 加点後draftの序列を維持し、activeの帯別曲数を定員に上から詰め直し(13.1のみ${CAPACITY_OVERRIDES['13.1']}曲に上書き)`);
    lines.push(`- モード: ${APPLY ? '**APPLY(draft書き換え済み)**' : 'dry-run(DB未変更)'}`);
    lines.push(`- 移動曲数: ${changes.length}曲`);
    lines.push('');
    lines.push('## 帯別曲数 スライド前(加点後draft) → スライド後 (参考: active定員)');
    lines.push('');
    lines.push('| 帯 | 前 | 後 | active |');
    lines.push('|---|---:|---:|---:|');
    for (const i of ranksOrdered.keys()) {
        const r = ranksOrdered[i].rank;
        lines.push(`| ${r} | ${ranksOrdered[i].songs.length} | ${newRanks[i].songs.length} | ${activeCounts.get(r) ?? '-'} |`);
    }
    lines.push('');
    lines.push('## 13.1 の顔ぶれ');
    lines.push('');
    for (const t of newRanks[rankIdxByValue.get('13.1')].songs) lines.push(`- ${t}`);
    lines.push('');
    lines.push('## 移動した曲一覧(序列順)');
    lines.push('');
    lines.push('| 曲 | 移動 |');
    lines.push('|---|---|');
    for (const c of changes) lines.push(`| ${c.title} | ${c.old_rank} → ${c.new_rank} |`);
    lines.push('');
    fs.writeFileSync(path.join(dataDir, 'slide_report.md'), lines.join('\n'), 'utf8');
    console.log('data/slide_changes.json, data/slide_report.md を出力しました');

    // ── APPLY ──
    if (APPLY) {
        const activeCountBefore = (await client.query(
            `SELECT COUNT(*)::int AS n FROM difficulty_ranks WHERE revision = 'active'`
        )).rows[0].n;

        await client.query('BEGIN');
        try {
            // 同名バックアップprofileがあれば削除
            const oldProfIds = (await client.query(
                `SELECT id FROM difficulty_ranks WHERE revision = $1`, [BACKUP_PROFILE]
            )).rows.map(r => r.id);
            if (oldProfIds.length > 0) {
                await client.query(`DELETE FROM difficulty_rank_songs WHERE difficulty_rank_id = ANY($1)`, [oldProfIds]);
                await client.query(`DELETE FROM difficulty_ranks WHERE id = ANY($1)`, [oldProfIds]);
            }
            // 現draftをprofileとしてサーバーサイドでコピー(高速)
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

            // draft削除 → 新draft挿入(帯ごとにバッチINSERT)
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
