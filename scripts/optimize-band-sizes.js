/**
 * 難易度表draftの序列を固定したまま、帯サイズ(境界21箇所)を最適化して
 * 全ユーザーの BEAT-PT 変化(active→draft)を最小化するスクリプト。
 *
 * 目的関数: 全ユーザーの delta(=draft時top100合計 − active時top100合計) の加重RMSを最小化。
 *           重みはactive BEAT-PTランキング順位 rank に対し w = rank^(-RANKPOWER)
 *           (デフォルト0.5: 1位=1.0, 4位=0.5, 100位=0.1, 900位≈0.033)。
 *           上位プレイヤーほど「ポイントが変わらない」ことを強く優先する。--rankpower= で調整可。
 * 手法:     現draftの帯サイズを初期値に、隣接帯間の境界を ±1〜±32 曲ずつ動かす座標降下法。
 *           BEAT-PT は calculateDifficultySimulation と同一ロジックをローカル再現
 *           (bg_pt = weight × [(rate/100)^1.3 + boost], ユーザー上位100曲合計)。
 *
 * Usage:
 *   node scripts/optimize-band-sizes.js            # dry-run (最適化と評価のみ)
 *   node scripts/optimize-band-sizes.js --apply    # バックアップprofile作成 + draft書き換え
 *
 * 出力: data/optimize_band_sizes.json, data/optimize_band_sizes_report.md
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
    statement_timeout: 300000,
};

const BACKUP_PROFILE = 'profile:pre-optimize-20260721';
const MIN_BAND_SIZE = 0; // 曲数は考慮しない(空帯も許容)
const APPLY = process.argv.includes('--apply');
const RANK_POWER = parseFloat((process.argv.find(a => a.startsWith('--rankpower=')) || '--rankpower=0.5').split('=')[1]);

// 帯(上から) 13.1〜11.0 の weight (BeatPtCalculator.WEIGHTS と同一)
const BAND_RANKS = [];
const BAND_WEIGHTS = [];
{
    const tmp = [];
    let w = 145;
    for (let i = 0; i <= 21; i++) {
        const rv = (11.0 + i * 0.1);
        tmp.push({ rank: rv.toFixed(1), w });
        w += rv >= 12.49 ? 3 : 2;
    }
    tmp.reverse();
    for (const t of tmp) { BAND_RANKS.push(t.rank); BAND_WEIGHTS.push(t.w); }
}

function fOf(rate) {
    let boost = 0;
    if (rate > 94.44) boost = 0.03;
    else if (rate > 88.88) boost = 0.02;
    else if (rate > 77.77) boost = 0.01;
    return Math.pow(rate / 100, 1.3) + boost;
}

function top100SumDesc(arr, n) {
    const sub = arr.subarray(0, n);
    sub.sort(); // TypedArray.sort は数値昇順
    let s = 0;
    for (let i = n - 1, c = 0; i >= 0 && c < 100; i--, c++) s += sub[i];
    return s;
}

async function main() {
    const client = new Client(DB_CONFIG);
    await client.connect();
    console.log(`接続OK (${APPLY ? 'APPLY モード' : 'dry-run モード'})`);

    // ── draft読み込み(序列と現行帯サイズ) ──
    const rankRows = (await client.query(
        `SELECT id, rank_value, sort_order FROM difficulty_ranks
         WHERE revision = 'draft' ORDER BY sort_order`
    )).rows;
    const songRows = (await client.query(
        `SELECT dr.rank_value, drs.song_title
         FROM difficulty_ranks dr
         JOIN difficulty_rank_songs drs ON drs.difficulty_rank_id = dr.id
         WHERE dr.revision = 'draft'
         ORDER BY dr.sort_order, drs.sort_order`
    )).rows;
    const ranksOrdered = rankRows.map(r => ({ rank: r.rank_value, sort: r.sort_order, songs: [] }));
    const rankIdxByValue = new Map(ranksOrdered.map((r, i) => [r.rank, i]));
    for (const row of songRows) ranksOrdered[rankIdxByValue.get(row.rank_value)].songs.push(row.song_title);
    const totalBefore = songRows.length;

    // 序列 = 数値帯(BAND_RANKS順)を上から1列化
    const sequence = [];
    const initialSizes = [];
    for (const rank of BAND_RANKS) {
        const idx = rankIdxByValue.get(rank);
        if (idx == null) throw new Error(`draftに帯 ${rank} がありません`);
        initialSizes.push(ranksOrdered[idx].songs.length);
        sequence.push(...ranksOrdered[idx].songs);
    }
    const seqIdxByTitle = new Map(sequence.map((t, i) => [t, i]));
    console.log(`序列: ${sequence.length}曲 / 現行帯サイズ: ${initialSizes.join(',')}`);

    // ── active表(曲→weight) ──
    const activeRows = (await client.query(
        `SELECT drs.song_title, dr.rank_value
         FROM difficulty_ranks dr
         JOIN difficulty_rank_songs drs ON drs.difficulty_rank_id = dr.id
         WHERE dr.revision = 'active' AND dr.rank_value ~ '^[0-9]+\\.[0-9]$'`
    )).rows;
    const weightByRank = new Map(BAND_RANKS.map((r, i) => [r, BAND_WEIGHTS[i]]));
    const activeWByTitle = new Map(activeRows.map(r => [r.song_title, weightByRank.get(r.rank_value)]));

    // ── 全ユーザーのベストスコア(base_scoresと同一条件) ──
    console.log('全ユーザーのスコア取得中...');
    const scoreRows = (await client.query(
        `SELECT s.user_id,
           (CASE WHEN s.difficulty_name = 'LEGGENDARIA' THEN s.title || '[L]' ELSE s.title END) AS mapped_title,
           (s.score * 100.0 / NULLIF(sd.notes * 2.0, 0)) AS rate
         FROM (SELECT user_id, title, difficulty_name, MAX(score) AS score FROM scores
               WHERE difficulty_name IN ('ANOTHER','LEGGENDARIA') AND score > 0 AND difficulty_level >= 11
               GROUP BY user_id, title, difficulty_name) s
         JOIN song_definitions sd ON s.title = sd.title AND sd.revision = 'active'
           AND ((s.difficulty_name = 'ANOTHER' AND sd.difficulty = '4') OR (s.difficulty_name = 'LEGGENDARIA' AND sd.difficulty = '10'))
         WHERE (s.score * 100.0 / NULLIF(sd.notes * 2.0, 0)) > 66.666`
    )).rows;
    console.log(`スコア行: ${scoreRows.length}`);

    // ユーザーごとに (seqIdx, f) と activeTotal を構築
    const userMap = new Map();
    for (const r of scoreRows) {
        let u = userMap.get(r.user_id);
        if (!u) { u = { seq: [], f: [], activePts: [] }; userMap.set(r.user_id, u); }
        const f = fOf(parseFloat(r.rate));
        const si = seqIdxByTitle.get(r.mapped_title);
        if (si != null) { u.seq.push(si); u.f.push(f); }
        const aw = activeWByTitle.get(r.mapped_title);
        if (aw != null) u.activePts.push(aw * f);
    }
    const users = [];
    for (const [id, u] of userMap) {
        if (u.activePts.length === 0) continue; // activeで無得点のユーザーは比較対象外(シミュレーションと同じ)
        const ap = Float64Array.from(u.activePts);
        const activeTotal = top100SumDesc(ap, ap.length);
        users.push({
            id,
            seq: Int32Array.from(u.seq),
            f: Float64Array.from(u.f),
            buf: new Float64Array(u.seq.length),
            activeTotal,
        });
    }
    // ランキング(active BEAT-PT降順)に基づく重み: w = rank^(-RANK_POWER)
    users.sort((a, b) => b.activeTotal - a.activeTotal);
    users.forEach((u, i) => { u.rank = i + 1; u.w = Math.pow(i + 1, -RANK_POWER); });
    console.log(`評価対象ユーザー: ${users.length}人 (重み=rank^-${RANK_POWER}: 1位=1.0, ${users.length}位=${Math.pow(users.length, -RANK_POWER).toFixed(3)})`);

    // ── 目的関数 ──
    const bandOfSeq = new Uint8Array(sequence.length);
    function evalSizes(sizes) {
        let pos = 0;
        for (let b = 0; b < sizes.length; b++) {
            bandOfSeq.fill(b, pos, pos + sizes[b]);
            pos += sizes[b];
        }
        let sum = 0, sumSq = 0, sumW = 0;
        for (const u of users) {
            const n = u.seq.length;
            for (let i = 0; i < n; i++) u.buf[i] = BAND_WEIGHTS[bandOfSeq[u.seq[i]]] * u.f[i];
            const d = top100SumDesc(u.buf, n) - u.activeTotal;
            sum += u.w * d; sumSq += u.w * d * d; sumW += u.w;
        }
        const mean = sum / sumW;               // 加重平均
        const rms = Math.sqrt(sumSq / sumW);   // 加重RMS
        return { rms, mean };
    }

    // ── 現状(現draftサイズ)の評価 ──
    const before = evalSizes(initialSizes);
    console.log(`現draft: 平均${before.mean.toFixed(1)}pt / RMS ${before.rms.toFixed(1)}pt`);

    // ── 座標降下法 ──
    const sizes = [...initialSizes];
    let best = evalSizes(sizes);
    const DELTAS = [-128, -64, -32, -16, -8, -4, -2, -1, 1, 2, 4, 8, 16, 32, 64, 128];
    for (let sweep = 1; sweep <= 40; sweep++) {
        let improved = false;
        for (let k = 0; k < sizes.length - 1; k++) {
            let bestD = 0, bestScore = best;
            for (const d of DELTAS) {
                if (sizes[k] + d < MIN_BAND_SIZE || sizes[k + 1] - d < MIN_BAND_SIZE) continue;
                sizes[k] += d; sizes[k + 1] -= d;
                const s = evalSizes(sizes);
                sizes[k] -= d; sizes[k + 1] += d;
                if (s.rms < bestScore.rms - 1e-9) { bestScore = s; bestD = d; }
            }
            if (bestD !== 0) {
                sizes[k] += bestD; sizes[k + 1] -= bestD;
                best = bestScore;
                improved = true;
            }
        }
        console.log(`  sweep ${sweep}: RMS ${best.rms.toFixed(2)} / 平均 ${best.mean.toFixed(2)}`);
        if (!improved) break;
    }
    console.log(`最適化結果: 平均${best.mean.toFixed(1)}pt / RMS ${best.rms.toFixed(1)}pt (現draft: 平均${before.mean.toFixed(1)} / RMS ${before.rms.toFixed(1)})`);
    console.log('帯サイズ(現draft→最適化):');
    for (let b = 0; b < BAND_RANKS.length; b++) {
        if (sizes[b] !== initialSizes[b]) console.log(`  ${BAND_RANKS[b]}: ${initialSizes[b]} → ${sizes[b]}`);
    }

    // ── 参考統計(最適化後のユーザー別delta) ──
    {
        let pos = 0;
        for (let b = 0; b < sizes.length; b++) { bandOfSeq.fill(b, pos, pos + sizes[b]); pos += sizes[b]; }
    }
    const results2 = users.map(u => {
        const n = u.seq.length;
        for (let i = 0; i < n; i++) u.buf[i] = BAND_WEIGHTS[bandOfSeq[u.seq[i]]] * u.f[i];
        return { id: u.id, rank: u.rank, delta: top100SumDesc(u.buf, n) - u.activeTotal, active: u.activeTotal };
    });
    const deltas = [...results2].sort((a, b) => a.delta - b.delta);
    const top20 = [...results2].sort((a, b) => a.rank - b.rank).slice(0, 20);
    const nameRows = (await client.query(
        `SELECT id, display_name FROM users WHERE id = ANY($1)`,
        [[...new Set(deltas.slice(0, 15).map(d => d.id).concat(top20.map(d => d.id)))]]
    )).rows;
    const nameById = new Map(nameRows.map(r => [r.id, r.display_name]));
    console.log('\n最適化後のランキング上位20人(優先グループ)の変化:');
    for (const d of top20) {
        console.log(`  ${d.rank}位 ${nameById.get(d.id) ?? d.id}: ${d.active.toFixed(0)} → ${(d.active + d.delta).toFixed(0)} (${d.delta >= 0 ? '+' : ''}${d.delta.toFixed(1)})`);
    }
    console.log('\n最適化後の下落ワースト15:');
    for (const d of deltas.slice(0, 15)) {
        console.log(`  ${d.rank}位 ${nameById.get(d.id) ?? d.id}: ${d.active.toFixed(0)} → ${(d.active + d.delta).toFixed(0)} (${d.delta.toFixed(1)})`);
    }
    const up = deltas.filter(d => d.delta > 0).length;
    const uMean = deltas.reduce((s, d) => s + d.delta, 0) / deltas.length;
    const uRms = Math.sqrt(deltas.reduce((s, d) => s + d.delta * d.delta, 0) / deltas.length);
    console.log(`上昇: ${up}人 / ${deltas.length}人 / 非加重: 平均${uMean.toFixed(1)} RMS ${uRms.toFixed(1)}`);

    // ── 出力 ──
    const dataDir = path.join(__dirname, '..', 'data');
    const sizeTable = BAND_RANKS.map((r, b) => ({ rank: r, before: initialSizes[b], after: sizes[b] }));
    fs.writeFileSync(path.join(dataDir, 'optimize_band_sizes.json'), JSON.stringify({
        objective: 'RMS(active→draft delta) 最小化(序列固定・帯境界の座標降下法)',
        applied: APPLY,
        backupProfile: BACKUP_PROFILE,
        users: users.length,
        before: { mean: before.mean, rms: before.rms },
        after: { mean: best.mean, rms: best.rms },
        sizes: sizeTable,
    }, null, 2), 'utf8');
    const lines = [];
    lines.push('# 帯サイズ最適化レポート(序列固定・BEAT-PT変化最小化)');
    lines.push('');
    lines.push(`- 目的: 全${users.length}人の active→draft BEAT-PT差分のRMS最小化(平均も0へ寄る)`);
    lines.push(`- モード: ${APPLY ? '**APPLY(draft書き換え済み)**' : 'dry-run(DB未変更)'}`);
    lines.push(`- 現draft: 平均 ${before.mean.toFixed(1)}pt / RMS ${before.rms.toFixed(1)}pt`);
    lines.push(`- 最適化後: 平均 ${best.mean.toFixed(1)}pt / RMS ${best.rms.toFixed(1)}pt`);
    lines.push('');
    lines.push('## 帯サイズ');
    lines.push('');
    lines.push('| 帯 | 現draft | 最適化後 | 増減 |');
    lines.push('|---|---:|---:|---:|');
    for (const s of sizeTable) {
        const d = s.after - s.before;
        lines.push(`| ${s.rank} | ${s.before} | ${s.after} | ${d > 0 ? '+' + d : d === 0 ? '±0' : d} |`);
    }
    lines.push('');
    fs.writeFileSync(path.join(dataDir, 'optimize_band_sizes_report.md'), lines.join('\n'), 'utf8');
    console.log('data/optimize_band_sizes.json, data/optimize_band_sizes_report.md を出力しました');

    // ── APPLY ──
    if (APPLY) {
        // 新draft構築: 序列を最適化サイズで詰め直し(非数値帯はそのまま)
        const newRanks = ranksOrdered.map(r => ({ rank: r.rank, sort: r.sort, songs: [] }));
        for (const r of ranksOrdered) {
            if (!BAND_RANKS.includes(r.rank)) newRanks[rankIdxByValue.get(r.rank)].songs = [...r.songs];
        }
        let cursor = 0;
        for (let b = 0; b < BAND_RANKS.length; b++) {
            newRanks[rankIdxByValue.get(BAND_RANKS[b])].songs = sequence.slice(cursor, cursor + sizes[b]);
            cursor += sizes[b];
        }
        const totalAfter = newRanks.reduce((s, r) => s + r.songs.length, 0);
        if (cursor !== sequence.length || totalAfter !== totalBefore) {
            throw new Error(`曲数不一致: 割当${cursor}/${sequence.length}, before=${totalBefore} after=${totalAfter}`);
        }

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

        // 適用後検証
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
        if (activeCountBefore !== activeCountAfter) { console.error('  検証NG: active帯数が変化'); ok = false; }
        const profCount = (await client.query(
            `SELECT COUNT(*)::int AS n FROM difficulty_rank_songs drs
             JOIN difficulty_ranks dr ON dr.id = drs.difficulty_rank_id
             WHERE dr.revision = $1`, [BACKUP_PROFILE]
        )).rows[0].n;
        if (profCount !== totalBefore) { console.error(`  検証NG: バックアップ曲数 ${profCount} != ${totalBefore}`); ok = false; }
        console.log(ok ? '適用後検証: すべてOK' : '適用後検証: NGあり!');
    }

    await client.end();
}

main().catch(e => {
    console.error('エラー:', e.message);
    process.exit(1);
});
