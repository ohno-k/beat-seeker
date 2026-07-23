/**
 * 物量(ノーツ数)加点を難易度表ドラフトに適用するスクリプト
 *
 * ルール: song_definitions(active).notes が 1800以上で +0.1、以降200ごとに +0.1
 *        (1800〜1999=+0.1, 2000〜2199=+0.2, 2200〜2399=+0.3, ...)
 *        上限は 13.1 でクランプ(超過分はレポートに「本来13.2相当」等と記録のみ)。
 * 対象: revision='draft' の数値帯(13.1〜11.0)。Uncategorized帯・ノーツ数不明曲はそのまま。
 * 帯内の並び: 元帯が高い順 → 元帯内の並び順(昇格曲が上、非移動曲は相対順維持)。
 *
 * Usage:
 *   node scripts/apply-notes-bonus-draft.js            # dry-run (DB読み取りのみ)
 *   node scripts/apply-notes-bonus-draft.js --apply    # バックアップprofile作成 + draft書き換え
 *
 * 出力: data/notes_bonus_changes.json, data/notes_bonus_report.md
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

const BONUS_BASE = 1800;   // このノーツ数以上で +0.1
const BONUS_STEP = 200;    // 以降この刻みごとに +0.1
const CAP_TENTHS = 131;    // 13.1 でクランプ
const BACKUP_PROFILE = 'profile:pre-notes-bonus-20260721';

const APPLY = process.argv.includes('--apply');

function isNumericRank(rankValue) {
    return /^\d+\.\d$/.test(rankValue);
}

function tenthsOf(rankValue) {
    return Math.round(parseFloat(rankValue) * 10);
}

function rankOfTenths(tenths) {
    return (tenths / 10).toFixed(1);
}

function bonusSteps(notes) {
    if (notes == null || notes < BONUS_BASE) return 0;
    return Math.floor((notes - BONUS_BASE) / BONUS_STEP) + 1;
}

async function main() {
    const client = new Client(DB_CONFIG);
    await client.connect();
    console.log(`接続OK (${APPLY ? 'APPLY モード' : 'dry-run モード'})`);

    // ── 現draftを読み込み(空帯も保持するため ranks を先に取得) ──
    const rankRows = (await client.query(
        `SELECT id, rank_value, sort_order FROM difficulty_ranks
         WHERE revision = 'draft' ORDER BY sort_order`
    )).rows;
    if (rankRows.length === 0) {
        throw new Error('draft が空です。中断します。');
    }
    const songRows = (await client.query(
        `SELECT dr.rank_value, drs.song_title
         FROM difficulty_ranks dr
         JOIN difficulty_rank_songs drs ON drs.difficulty_rank_id = dr.id
         WHERE dr.revision = 'draft'
         ORDER BY dr.sort_order, drs.sort_order`
    )).rows;

    const ranksOrdered = rankRows.map(r => ({
        rank: r.rank_value,
        sort: r.sort_order,
        songs: [],
    }));
    const rankIdxByValue = new Map(ranksOrdered.map((r, i) => [r.rank, i]));
    for (const row of songRows) {
        ranksOrdered[rankIdxByValue.get(row.rank_value)].songs.push(row.song_title);
    }
    const totalBefore = songRows.length;
    console.log(`現draft: ${ranksOrdered.length}帯 / ${totalBefore}曲`);

    // ── ノーツ数マップ (song_definitions active, ANOTHER='4' / LEGGENDARIA='10') ──
    const noteRows = (await client.query(
        `SELECT title, difficulty, notes FROM song_definitions
         WHERE revision = 'active' AND difficulty IN ('4', '10')`
    )).rows;
    const notesMap = new Map();
    for (const r of noteRows) {
        const key = r.difficulty === '10' ? r.title + '[L]' : r.title;
        notesMap.set(key, r.notes);
    }
    console.log(`ノーツ数マップ: ${notesMap.size}譜面`);

    // ── 加点計算 ──
    const numericIdx = ranksOrdered
        .map((r, i) => (isNumericRank(r.rank) ? i : -1))
        .filter(i => i >= 0);
    const idxByTenths = new Map(numericIdx.map(i => [tenthsOf(ranksOrdered[i].rank), i]));

    // song assignments: 各曲 { title, origIdx, origPos, newIdx } / changes / missing
    const assignments = [];
    const changes = [];
    const missing = [];

    for (const i of numericIdx) {
        const band = ranksOrdered[i];
        const origTenths = tenthsOf(band.rank);
        band.songs.forEach((title, pos) => {
            const notes = notesMap.get(title);
            if (notes == null) {
                missing.push({ title, rank: band.rank });
                assignments.push({ title, origIdx: i, origPos: pos, newIdx: i });
                return;
            }
            const steps = bonusSteps(notes);
            const unclamped = origTenths + steps;
            const newTenths = Math.min(CAP_TENTHS, unclamped);
            const newIdx = idxByTenths.get(newTenths);
            if (newIdx == null) {
                throw new Error(`帯 ${rankOfTenths(newTenths)} がdraftに存在しません (${title})`);
            }
            assignments.push({ title, origIdx: i, origPos: pos, newIdx });
            if (steps > 0) {
                changes.push({
                    title,
                    notes,
                    bonus: +(steps / 10).toFixed(1),
                    old_rank: band.rank,
                    new_rank: rankOfTenths(newTenths),
                    unclamped_rank: rankOfTenths(unclamped),
                    clamped: unclamped > CAP_TENTHS,
                });
            }
        });
    }

    // ── 新draft構築(数値帯のみ再配置、Uncategorizedはそのまま) ──
    const newRanks = ranksOrdered.map(r => ({ rank: r.rank, sort: r.sort, songs: [] }));
    for (const i of ranksOrdered.keys()) {
        if (!numericIdx.includes(i)) {
            newRanks[i].songs = [...ranksOrdered[i].songs];
        }
    }
    // 帯内の並び: 元帯tenths降順 → 元帯内pos昇順
    const byNewIdx = new Map();
    for (const a of assignments) {
        if (!byNewIdx.has(a.newIdx)) byNewIdx.set(a.newIdx, []);
        byNewIdx.get(a.newIdx).push(a);
    }
    for (const [newIdx, list] of byNewIdx) {
        list.sort((a, b) => {
            const ta = tenthsOf(ranksOrdered[a.origIdx].rank);
            const tb = tenthsOf(ranksOrdered[b.origIdx].rank);
            if (ta !== tb) return tb - ta;
            return a.origPos - b.origPos;
        });
        newRanks[newIdx].songs = list.map(a => a.title);
    }

    const totalAfter = newRanks.reduce((s, r) => s + r.songs.length, 0);
    if (totalAfter !== totalBefore) {
        throw new Error(`曲数不一致: before=${totalBefore} after=${totalAfter}`);
    }

    // ── サマリー ──
    const moved = changes.filter(c => c.new_rank !== c.old_rank);
    const clamped = changes.filter(c => c.clamped);
    const byBonus = {};
    for (const c of changes) {
        byBonus[c.bonus] = (byBonus[c.bonus] || 0) + 1;
    }
    console.log(`加点対象: ${changes.length}曲 (うち実移動 ${moved.length}曲, クランプ ${clamped.length}曲)`);
    for (const b of Object.keys(byBonus).sort()) {
        console.log(`  +${b}: ${byBonus[b]}曲`);
    }
    console.log(`ノーツ数不明(加点なし): ${missing.length}曲`);

    // 検証用の代表例
    for (const probe of ['冥', 'Mare Nectaris']) {
        const c = changes.find(x => x.title === probe);
        const n = notesMap.get(probe);
        console.log(`  [検証] ${probe}: notes=${n ?? '不明'} ${c ? `${c.old_rank}→${c.new_rank} (本来${c.unclamped_rank})` : '加点なし'}`);
    }

    // ── 出力ファイル ──
    const dataDir = path.join(__dirname, '..', 'data');
    fs.writeFileSync(
        path.join(dataDir, 'notes_bonus_changes.json'),
        JSON.stringify({
            rule: `notes >= ${BONUS_BASE} で+0.1、以降${BONUS_STEP}ごとに+0.1、上限${rankOfTenths(CAP_TENTHS)}`,
            applied: APPLY,
            backupProfile: BACKUP_PROFILE,
            total: changes.length,
            moved: moved.length,
            changes,
            missing,
        }, null, 2),
        'utf8'
    );

    const lines = [];
    lines.push('# 物量(ノーツ数)加点レポート');
    lines.push('');
    lines.push(`- ルール: ノーツ数 ${BONUS_BASE}以上 = +0.1、以降${BONUS_STEP}ごとに +0.1(上限 ${rankOfTenths(CAP_TENTHS)} でクランプ)`);
    lines.push(`- モード: ${APPLY ? '**APPLY(draft書き換え済み)**' : 'dry-run(DB未変更)'}`);
    lines.push(`- 加点対象: ${changes.length}曲 / 実移動: ${moved.length}曲 / クランプ: ${clamped.length}曲 / ノーツ数不明: ${missing.length}曲`);
    lines.push('');
    lines.push('## 帯別曲数 before → after');
    lines.push('');
    lines.push('| 帯 | before | after | 増減 |');
    lines.push('|---|---:|---:|---:|');
    for (const i of ranksOrdered.keys()) {
        const b = ranksOrdered[i].songs.length;
        const a = newRanks[i].songs.length;
        const d = a - b;
        lines.push(`| ${ranksOrdered[i].rank} | ${b} | ${a} | ${d > 0 ? '+' + d : d} |`);
    }
    lines.push('');
    lines.push('## 加点内訳');
    lines.push('');
    for (const b of Object.keys(byBonus).sort()) {
        lines.push(`- +${b}: ${byBonus[b]}曲`);
    }
    lines.push('');
    if (clamped.length > 0) {
        lines.push('## クランプされた曲(本来はさらに上の帯相当)');
        lines.push('');
        lines.push('| 曲 | notes | 元帯 | 本来 | 適用後 |');
        lines.push('|---|---:|---|---|---|');
        for (const c of clamped.sort((a, b2) => b2.unclamped_rank.localeCompare(a.unclamped_rank))) {
            lines.push(`| ${c.title} | ${c.notes} | ${c.old_rank} | ${c.unclamped_rank} | ${c.new_rank} |`);
        }
        lines.push('');
    }
    lines.push('## 移動した曲一覧');
    lines.push('');
    lines.push('| 曲 | notes | 加点 | 移動 |');
    lines.push('|---|---:|---:|---|');
    for (const c of moved.sort((a, b2) => b2.notes - a.notes)) {
        lines.push(`| ${c.title} | ${c.notes} | +${c.bonus} | ${c.old_rank} → ${c.new_rank} |`);
    }
    lines.push('');
    if (missing.length > 0) {
        lines.push('## ノーツ数不明(加点なし・現帯維持)');
        lines.push('');
        for (const m of missing) {
            lines.push(`- ${m.title} (${m.rank})`);
        }
        lines.push('');
    }
    fs.writeFileSync(path.join(dataDir, 'notes_bonus_report.md'), lines.join('\n'), 'utf8');
    console.log('data/notes_bonus_changes.json, data/notes_bonus_report.md を出力しました');

    // ── APPLY: バックアップ + draft書き換え ──
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
            // 現draftをprofileとしてコピー
            let backupSongs = 0;
            for (const r of ranksOrdered) {
                const { rows } = await client.query(
                    `INSERT INTO difficulty_ranks (rank_value, sort_order, revision) VALUES ($1, $2, $3) RETURNING id`,
                    [r.rank, r.sort, BACKUP_PROFILE]
                );
                for (let j = 0; j < r.songs.length; j++) {
                    await client.query(
                        `INSERT INTO difficulty_rank_songs (difficulty_rank_id, song_title, sort_order) VALUES ($1, $2, $3)`,
                        [rows[0].id, r.songs[j], j]
                    );
                    backupSongs++;
                }
            }
            console.log(`バックアップ作成: ${BACKUP_PROFILE} (${ranksOrdered.length}帯 / ${backupSongs}曲)`);

            // draft削除 → 新draft挿入
            const draftIds = rankRows.map(r => r.id);
            await client.query(`DELETE FROM difficulty_rank_songs WHERE difficulty_rank_id = ANY($1)`, [draftIds]);
            await client.query(`DELETE FROM difficulty_ranks WHERE id = ANY($1)`, [draftIds]);
            let insertedSongs = 0;
            for (const r of newRanks) {
                const { rows } = await client.query(
                    `INSERT INTO difficulty_ranks (rank_value, sort_order, revision) VALUES ($1, $2, 'draft') RETURNING id`,
                    [r.rank, r.sort]
                );
                for (let j = 0; j < r.songs.length; j++) {
                    await client.query(
                        `INSERT INTO difficulty_rank_songs (difficulty_rank_id, song_title, sort_order) VALUES ($1, $2, $3)`,
                        [rows[0].id, r.songs[j], j]
                    );
                    insertedSongs++;
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
