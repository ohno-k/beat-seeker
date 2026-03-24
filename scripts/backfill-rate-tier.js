/**
 * Backfill total_rate_pt for all users' latest score_history_logs.
 *
 * How it works:
 *  1. Load song note counts from song_data.json (same file the frontend uses)
 *  2. For each user, get their ANOTHER/LEGGENDARIA scores from the `scores` table
 *  3. Compute scoreRate and apply the Rate-Tier threshold/interpolation logic
 *  4. Take top 100 songs, sum their points
 *  5. Update total_rate_pt on every history log row for that user
 *
 * Usage:
 *   node scripts/backfill-rate-tier.js
 *
 * Requires: pg  (npm install pg  -- or use the system node_modules if present)
 */

const { Client } = require('pg');
const path = require('path');
const songDataRaw = require(path.join(__dirname, '../frontend/src/data/song_data.json'));

// ─── Rate-Tier thresholds (same as beatTier.ts) ────────────────────────────
const SCORE_RATE_THRESHOLDS = [
    { rate: 77.77,  points: 1   },
    { rate: 88.89,  points: 2   },
    { rate: 94.44,  points: 4   },
    { rate: 97.22,  points: 8   },
    { rate: 98.61,  points: 16  },
    { rate: 99.31,  points: 32  },
    { rate: 99.65,  points: 64  },
    { rate: 99.83,  points: 128 },
    { rate: 99.91,  points: 256 },
    { rate: 100,    points: 512 },
];

function calculateScoreRateTierPoints(scoreRate) {
    if (scoreRate <= 0 || scoreRate < SCORE_RATE_THRESHOLDS[0].rate) return 0;
    const last = SCORE_RATE_THRESHOLDS[SCORE_RATE_THRESHOLDS.length - 1];
    if (scoreRate >= last.rate) return last.points;
    for (let i = 0; i < SCORE_RATE_THRESHOLDS.length - 1; i++) {
        const lo = SCORE_RATE_THRESHOLDS[i];
        const hi = SCORE_RATE_THRESHOLDS[i + 1];
        if (scoreRate < hi.rate) {
            const t = (scoreRate - lo.rate) / (hi.rate - lo.rate);
            return lo.points + t * (hi.points - lo.points);
        }
    }
    return 0;
}

// difficultyCode used in song_data matches spIidxDiffMap in scoreData.ts
const DIFF_CODE = { ANOTHER: '4', LEGGENDARIA: '10' };

// Build maxScore lookup: key = `${title}_${difficultyCode}` → maxScore
const maxScoreMap = new Map();
if (Array.isArray(songDataRaw.body)) {
    for (const s of songDataRaw.body) {
        if (s.notes) {
            maxScoreMap.set(`${s.title}_${s.difficulty}`, s.notes * 2);
        }
    }
}

async function main() {
    const client = new Client({
        host:     process.env.PGHOST     || 'localhost',
        port:     parseInt(process.env.PGPORT || '5432'),
        database: process.env.PGDATABASE || 'beatseeker',
        user:     process.env.PGUSER     || 'postgres',
        password: process.env.PGPASSWORD || 'postgres',
        ssl:      process.env.PGHOST && process.env.PGHOST !== 'localhost'
                      ? { rejectUnauthorized: false }
                      : false,
    });

    await client.connect();
    console.log('Connected to database.');

    // Ensure column exists (safe: no-op if already present)
    await client.query(
        'ALTER TABLE score_history_logs ADD COLUMN IF NOT EXISTS total_rate_pt DOUBLE PRECISION DEFAULT 0.0'
    );
    console.log('Column total_rate_pt ensured.');

    // Get all users
    const usersRes = await client.query('SELECT id, display_name FROM users ORDER BY id');
    console.log(`Found ${usersRes.rows.length} users.`);

    for (const user of usersRes.rows) {
        // Get all ANOTHER/LEGGENDARIA scores for this user
        const scoresRes = await client.query(
            `SELECT title, difficulty_name, difficulty_level, score
             FROM scores
             WHERE user_id = $1
               AND difficulty_name IN ('ANOTHER', 'LEGGENDARIA')
               AND score > 0`,
            [user.id]
        );

        // Calculate rate-tier points for each score
        const points = [];
        for (const row of scoresRes.rows) {
            const diffCode = DIFF_CODE[row.difficulty_name];
            if (!diffCode) continue;
            const maxScore = maxScoreMap.get(`${row.title}_${diffCode}`);
            if (!maxScore || maxScore <= 0) continue;
            const scoreRate = (row.score / maxScore) * 100;
            const pt = calculateScoreRateTierPoints(scoreRate);
            if (pt > 0) points.push(pt);
        }

        // Top 100, sum
        points.sort((a, b) => b - a);
        const top100Sum = points.slice(0, 100).reduce((acc, p) => acc + p, 0);
        const totalRatePt = Math.round(top100Sum * 10) / 10;

        // Update all history log rows for this user
        const updateRes = await client.query(
            `UPDATE score_history_logs SET total_rate_pt = $1 WHERE user_id = $2`,
            [totalRatePt, user.id]
        );

        console.log(`  User "${user.display_name}" (id=${user.id}): ${scoresRes.rows.length} scores → totalRatePt=${totalRatePt} (updated ${updateRes.rowCount} log rows)`);
    }

    await client.end();
    console.log('\nDone.');
}

main().catch(err => {
    console.error('Error:', err);
    process.exit(1);
});
