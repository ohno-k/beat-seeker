/**
 * ユーザー増加と CSV 読み込み回数の推移グラフを PNG で出力するスクリプト。
 *
 * 出力: docs/user_growth_YYYY-MM.png（実行時点の年月。既存ファイルがあれば上書き）
 *
 * 【グラフの作り】
 * ユーザー数（〜1,000 人規模）と CSV 読み込み回数（〜2,000 回規模）はスケールが 2 桁違うため、
 * 1 枚に 2 軸で重ねると比率が恣意的になり、どちらの変化も正しく読めない。
 * そこで「軸を分けた 3 段の小倍数」にして、x 軸（週）だけを共有する。
 *
 * 【集計の約束】
 *  - 週は JST の月曜始まり。
 *  - 集計途中の最終週はグラフから除く（数日ぶんしかなく、そのまま描くと急落に見えるため）。
 *    ただし上部の合計値には含める（「現時点の総数」として正しい値を出したいため）。
 *  - CSV 読み込み回数 = score_history_logs の行数（アップロード 1 回 = 1 行）から
 *    **システムの一括処理を除いたもの**。難易度表の適用などで全ユーザーの BEAT-PT を
 *    再計算すると、1 人 1 行の履歴が数分で数百件書き込まれる。これを混ぜると
 *    「利用が急増した週」に見えてしまうため、利用状況の指標としては除外する。
 *    判定は「同じ分に 50 件以上まとまって発生し、かつスコア更新が 0 件」。
 *    人手のアップロードが 1 分に 50 件も同時に起きることはない。
 *  - 新規登録の棒は軸を 90 人で切り、公開初週だけ破断の印を入れて実数を添える
 *    （初週が突出しすぎて、そのままでは以降の推移が読めないため）。
 *
 * 【日付の扱い・注意】
 *  uploaded_at / created_at は timestamp without time zone（UTC 格納）。
 *  これを JS の Date にすると node-pg がローカル TZ として解釈するため、JST 環境では
 *  toISOString() が 9 時間ずれ、日付ラベルが 1 日前になる。
 *  そのため **日付ラベルは必ず SQL 側で to_char して文字列で受け取る**。
 *
 * Usage:
 *   node scripts/build-growth-chart.js
 *   node scripts/build-growth-chart.js --out=docs/foo.png   # 出力先を変える
 *
 * 前提: Node + `pg`（scripts/node_modules）+ `puppeteer`（リポジトリ直下の node_modules）。
 *       puppeteer 同梱のブラウザは未ダウンロードのため、ローカルにインストール済みの
 *       Chrome / Edge を使う。見つからない場合は CHROME_PATH 環境変数で指定する。
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

/** 描画に使うブラウザの候補。先に見つかったものを使う。 */
const CHROME_CANDIDATES = [
    process.env.CHROME_PATH,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
].filter(Boolean);

const ROOT = path.resolve(__dirname, '..');
const outArg = (process.argv.find(a => a.startsWith('--out=')) || '').split('=')[1];
const stamp = new Date().toISOString().slice(0, 7);
const OUT_PNG = path.resolve(ROOT, outArg || `docs/user_growth_${stamp}.png`);

// ── 描画パラメータ ──
const W = 1080, ML = 62, MR = 26;
const PW = W - ML - MR;
const NEW_USER_CAP = 90;   // 新規登録の棒の上限（公開初週だけがこれを超える）
const UPLOAD_CAP = 700;    // CSV 読み込みの棒の上限（一括処理を除くと 342〜646 の範囲に収まる）

const nf = n => n.toLocaleString('en-US');
const md = wk => { const [, m, d] = wk.split('-'); return `${+m}/${+d}`; };

/** 目盛りの値を「切りのいい」段階で返す。 */
function ticks(max, count) {
    const step = Math.pow(10, Math.floor(Math.log10(max / count)));
    const cands = [step, step * 2, step * 2.5, step * 5, step * 10];
    const s = cands.find(c => max / c <= count) || step * 10;
    const out = [];
    for (let v = 0; v <= max + 1e-9; v += s) out.push(Math.round(v));
    return out;
}

/**
 * システムの一括処理とみられる履歴行の id を返す CTE。
 * 難易度表の適用などで全ユーザーの BEAT-PT を再計算すると、スコアの更新が 0 件のまま
 * 1 人 1 行の履歴が数分で数百件書き込まれる。これを利用回数に混ぜない。
 */
const BATCH_CTE = `
  batch AS (
    WITH m AS (SELECT id, updated_count, date_trunc('minute', uploaded_at) mi FROM score_history_logs),
         dense AS (SELECT mi FROM m GROUP BY mi HAVING COUNT(*) >= 50)
    SELECT m.id FROM m JOIN dense d ON d.mi = m.mi WHERE m.updated_count = 0
  )`;

async function fetchWeekly() {
    const client = new Client(DB_CONFIG);
    await client.connect();
    // 日付は必ず SQL 側で文字列化する（JS Date に変換させると TZ でずれる。冒頭コメント参照）
    const week = col => `to_char(date_trunc('week', ${col} AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Tokyo'), 'YYYY-MM-DD')`;

    const users = await client.query(
        `SELECT ${week('created_at')} AS wk, COUNT(*)::int AS n FROM users GROUP BY 1 ORDER BY 1`);
    const uploads = await client.query(
        `WITH ${BATCH_CTE}
         SELECT ${week('l.uploaded_at')} AS wk, COUNT(*)::int AS n
         FROM score_history_logs l WHERE l.id NOT IN (SELECT id FROM batch)
         GROUP BY 1 ORDER BY 1`);
    const batches = await client.query(
        `WITH ${BATCH_CTE}
         SELECT to_char(l.uploaded_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Tokyo', 'YYYY-MM-DD') AS dd,
                COUNT(*)::int AS n
         FROM score_history_logs l JOIN batch b ON b.id = l.id
         GROUP BY 1 ORDER BY 1`);
    const span = await client.query(
        `SELECT to_char(MIN(created_at) AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Tokyo', 'YYYY-MM-DD') AS a,
                to_char(MAX(created_at) AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Tokyo', 'YYYY-MM-DD') AS b
         FROM users`);
    await client.end();

    const fmt = r => r.rows.map(x => ({ wk: x.wk, n: x.n }));
    return {
        users: fmt(users),
        uploads: fmt(uploads),
        batches: batches.rows.map(x => ({ dd: x.dd, n: x.n })),
        span: span.rows[0],
    };
}

/**
 * 1 段ぶんの SVG を組み立てる。
 *  kind 'area' … 累計（面 + 線 + 終点ラベル）
 *  kind 'bar'  … 週次（棒）。cap を超える棒は上端で切り、破断の印と実数を添える。
 */
function panel({ y0, h, values, kind, color, colorSoft, cap, unit, labelAt, slot, barW }) {
    const max = cap || Math.max(...values);
    const bottom = y0 + h;
    const sy = v => bottom - (Math.min(v, max) / max) * h;
    let g = '';

    for (const t of ticks(max, 4)) {
        const yy = sy(t);
        g += `<line x1="${ML}" y1="${yy.toFixed(1)}" x2="${W - MR}" y2="${yy.toFixed(1)}" stroke="var(--grid)" stroke-width="1"/>`;
        g += `<text x="${ML - 10}" y="${(yy + 4).toFixed(1)}" text-anchor="end" class="tick">${nf(t)}</text>`;
    }

    if (kind === 'area') {
        const pts = values.map((v, i) => [ML + slot * (i + 0.5), sy(v)]);
        const line = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' ');
        g += `<path d="${line} L${pts.at(-1)[0].toFixed(1)} ${bottom} L${pts[0][0].toFixed(1)} ${bottom} Z" fill="${colorSoft}"/>`;
        g += `<path d="${line}" fill="none" stroke="${color}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>`;
        const last = pts.at(-1);
        g += `<circle cx="${last[0].toFixed(1)}" cy="${last[1].toFixed(1)}" r="4.5" fill="${color}" stroke="var(--panel)" stroke-width="2"/>`;
        g += `<text x="${(last[0] - 10).toFixed(1)}" y="${(last[1] - 12).toFixed(1)}" text-anchor="end" class="val" fill="${color}">${nf(values.at(-1))} ${unit}</text>`;
    } else {
        values.forEach((v, i) => {
            const x = ML + slot * i + (slot - barW) / 2;
            const yy = sy(v);
            g += `<rect x="${x.toFixed(1)}" y="${yy.toFixed(1)}" width="${barW.toFixed(1)}" height="${Math.max(2, bottom - yy).toFixed(1)}" rx="3" fill="${color}"/>`;
            if (v > max) {
                // 破断の印: 棒の上端を地色でギザギザに切り、実数を上に添える
                const zy = yy + 7, steps = 4, sw = barW / steps;
                let zig = `M${x} ${zy}`;
                for (let s = 0; s < steps; s++) zig += ` l${(sw / 2).toFixed(1)} -5 l${(sw / 2).toFixed(1)} 5`;
                g += `<path d="${zig} L${(x + barW).toFixed(1)} ${(zy - 9).toFixed(1)} L${x} ${(zy - 9).toFixed(1)} Z" fill="var(--panel)"/>`;
                g += `<path d="${zig}" fill="none" stroke="${color}" stroke-width="1.5" stroke-linejoin="round"/>`;
                g += `<text x="${(x + barW / 2).toFixed(1)}" y="${(yy - 8).toFixed(1)}" text-anchor="middle" class="val" fill="${color}">${nf(v)}</text>`;
            }
        });
        if (labelAt != null) {
            const x = ML + slot * labelAt + slot / 2;
            g += `<text x="${x.toFixed(1)}" y="${(sy(values[labelAt]) - 8).toFixed(1)}" text-anchor="middle" class="val" fill="${color}">${nf(values[labelAt])}</text>`;
        }
    }
    g += `<line x1="${ML}" y1="${bottom}" x2="${W - MR}" y2="${bottom}" stroke="var(--axis)" stroke-width="1"/>`;
    return g;
}

function buildHtml(raw) {
    const weeks = raw.users.slice(0, -1).map(u => u.wk);
    const newUsers = raw.users.slice(0, -1).map(u => u.n);
    const uploads = raw.uploads.slice(0, -1).map(u => u.n);
    const partial = { wk: raw.users.at(-1).wk, users: raw.users.at(-1).n, uploads: raw.uploads.at(-1).n };

    let acc = 0;
    const cum = newUsers.map(n => (acc += n));
    const totalUsers = raw.users.reduce((a, x) => a + x.n, 0);
    const totalUploads = raw.uploads.reduce((a, x) => a + x.n, 0);
    const avgUploads = Math.round(uploads.reduce((a, b) => a + b, 0) / uploads.length);
    const launch = newUsers[0];
    const peakUpload = Math.max(...uploads);
    const peakUploadWk = weeks[uploads.indexOf(peakUpload)];
    const recent4 = newUsers.slice(-4).reduce((a, b) => a + b, 0);

    const N = weeks.length;
    const slot = PW / N;
    const barW = Math.max(6, slot - 4);

    const H_A = 190, H_B = 130, H_C = 170, GAP = 62;
    const Y_A = 46, Y_B = Y_A + H_A + GAP, Y_C = Y_B + H_B + GAP;
    const SVG_H = Y_C + H_C + 40;

    // 月初の週にだけ x 軸ラベルを立てる（全週に振ると潰れる）
    let lastMonth = null;
    const xl = weeks.map((wk, i) => {
        const m = Number(wk.slice(5, 7));
        if (m === lastMonth) return '';
        lastMonth = m;
        return `<text x="${(ML + slot * i + slot / 2).toFixed(1)}" y="${(Y_C + H_C + 22).toFixed(1)}" text-anchor="middle" class="xlab">${m}月</text>`;
    }).join('');

    const p = o => panel({ ...o, slot, barW });
    const svg = `<svg viewBox="0 0 ${W} ${SVG_H}" width="${W}" height="${SVG_H}" xmlns="http://www.w3.org/2000/svg">
  <text x="${ML}" y="${Y_A - 22}" class="ptitle">累計ユーザー数</text>
  <text x="${W - MR}" y="${Y_A - 22}" text-anchor="end" class="pnote">登録の積み上げ</text>
  ${p({ y0: Y_A, h: H_A, values: cum, kind: 'area', color: 'var(--users)', colorSoft: 'var(--users-soft)', unit: '人' })}

  <text x="${ML}" y="${Y_B - 22}" class="ptitle">新規登録（週あたり）</text>
  <text x="${W - MR}" y="${Y_B - 22}" text-anchor="end" class="pnote">公開直後の 1 週で ${nf(launch)} 人</text>
  ${p({ y0: Y_B, h: H_B, values: newUsers, kind: 'bar', color: 'var(--users)', cap: NEW_USER_CAP, labelAt: newUsers.length - 1 })}

  <text x="${ML}" y="${Y_C - 22}" class="ptitle">CSV 読み込み（週あたり）</text>
  <text x="${W - MR}" y="${Y_C - 22}" text-anchor="end" class="pnote">週平均 ${nf(avgUploads)} 回</text>
  ${p({ y0: Y_C, h: H_C, values: uploads, kind: 'bar', color: 'var(--uploads)', cap: UPLOAD_CAP, labelAt: uploads.indexOf(peakUpload) })}

  ${xl}
</svg>`;

    // span は 'YYYY-MM-DD' の文字列（TZ ずれを避けるため SQL 側で整形済み）
    const jst = s => { const [y, m, d] = s.split('-'); return `${+y}年${+m}月${+d}日`; };
    const months = ((Date.parse(raw.span.b + 'T00:00:00Z') - Date.parse(raw.span.a + 'T00:00:00Z')) / 86400000 / 30.44).toFixed(1);
    const batchTotal = raw.batches.reduce((a, x) => a + x.n, 0);
    const batchDays = raw.batches.map(x => `${md(x.dd)}（${nf(x.n)} 件）`).join('・');

    const html = `<title>beat-seeker 半年の推移</title>
<style>
:root {
  --bg: #f3f4f8; --panel: #ffffff;
  --ink: #151922; --ink-2: #545c6e; --ink-3: #8992a6;
  --line: #e2e5ee; --grid: #edeff5; --axis: #c9cedb;
  /* 2 色は dataviz の検証スクリプトで CVD 分離・コントラストとも PASS 済み */
  --users: #3f6ad1; --users-soft: #dfe7f8;
  --uploads: #c2681b;
}
* { box-sizing: border-box; }
body {
  margin: 0; background: var(--bg); color: var(--ink);
  font-family: "Yu Gothic UI", "Yu Gothic", "Hiragino Kaku Gothic ProN", Meiryo, system-ui, sans-serif;
  -webkit-font-smoothing: antialiased;
}
.card { width: 1180px; margin: 0 auto; background: var(--panel); padding: 40px 44px 34px; }
.eyebrow { font-family: Consolas, ui-monospace, monospace; font-size: 11px; letter-spacing: .2em;
  text-transform: uppercase; color: var(--ink-3); margin: 0 0 12px; }
h1 { font-size: 34px; line-height: 1.2; letter-spacing: -.02em; font-weight: 800; margin: 0 0 6px; }
.range { color: var(--ink-2); font-size: 14px; margin: 0; }
.stats { display: flex; gap: 1px; background: var(--line); border: 1px solid var(--line); margin: 26px 0 10px; }
.st { flex: 1; background: var(--panel); padding: 14px 18px 16px; }
.st .k { font-family: Consolas, monospace; font-size: 10.5px; letter-spacing: .12em; text-transform: uppercase; color: var(--ink-3); }
.st .v { font-family: Consolas, ui-monospace, monospace; font-size: 32px; font-weight: 700;
  letter-spacing: -.02em; line-height: 1.25; font-variant-numeric: tabular-nums; }
.st .v small { font-size: 15px; font-weight: 700; margin-left: 3px; }
.st.u .v { color: var(--users); }
.st.c .v { color: var(--uploads); }
.st .s { font-size: 12px; color: var(--ink-2); }
svg { display: block; width: 100%; height: auto; }
.ptitle { font-size: 15px; font-weight: 700; fill: var(--ink); }
.pnote { font-size: 12px; fill: var(--ink-3); }
.tick { font-family: Consolas, monospace; font-size: 11px; fill: var(--ink-3); }
.xlab { font-family: Consolas, monospace; font-size: 11.5px; fill: var(--ink-2); }
.val { font-family: Consolas, monospace; font-size: 12px; font-weight: 700; }
footer { margin-top: 22px; padding-top: 14px; border-top: 1px solid var(--line); color: var(--ink-3); font-size: 11.5px; line-height: 1.7; }
</style>
<div class="card">
  <p class="eyebrow">beat-seeker</p>
  <h1>公開から半年の推移</h1>
  <p class="range">最初の登録 ${jst(raw.span.a)} 〜 ${jst(raw.span.b)}（約 ${months} か月）／ 週次集計・JST（月曜始まり）</p>

  <div class="stats">
    <div class="st u"><div class="k">総ユーザー数</div><div class="v">${nf(totalUsers)}<small>人</small></div><div class="s">うち ${Math.round(launch / totalUsers * 100)}% が公開初週の登録</div></div>
    <div class="st c"><div class="k">CSV 読み込み</div><div class="v">${nf(totalUploads)}<small>回</small></div><div class="s">週平均 ${nf(avgUploads)} 回（一括処理 ${nf(batchTotal)} 件を除く）</div></div>
    <div class="st"><div class="k">直近 4 週の新規</div><div class="v">${nf(recent4)}<small>人</small></div><div class="s">週あたり ${Math.round(recent4 / 4)} 人前後</div></div>
    <div class="st"><div class="k">1 人あたり</div><div class="v">${(totalUploads / totalUsers).toFixed(1)}<small>回</small></div><div class="s">登録から現在までの平均</div></div>
  </div>

  ${svg}

  <footer>
    新規登録の棒グラフは軸を ${NEW_USER_CAP} 人で切っている。公開初週（${md(weeks[0])} の週）の ${nf(launch)} 人だけが突出しており、そのまま描くと以降の推移が読めなくなるため、該当の棒には破断の印を入れて実数を添えた。<br>
    CSV 読み込み回数は <code>score_history_logs</code> の登録件数（アップロード 1 回＝1 件）から<strong>システムの一括処理を除いたもの</strong>。難易度表の適用などで全ユーザーの BEAT-PT を再計算すると、スコアの更新が 0 件のまま 1 人 1 行の履歴が数分で数百件書き込まれる。利用状況の指標としては別物なので除外した（判定: 同じ分に 50 件以上まとまって発生し、かつ更新 0 件）。<br>
    除外した一括処理は ${raw.batches.length} 回・計 ${nf(batchTotal)} 件（全 ${nf(totalUploads + batchTotal)} 件の ${(batchTotal / (totalUploads + batchTotal) * 100).toFixed(0)}%）: ${batchDays}。<br>
    集計途中の ${md(partial.wk)} の週（${partial.users} 人 / ${nf(partial.uploads)} 回）はグラフから除いている。上部の合計値にはこの週も含む。CSV 読み込みの最大は ${md(peakUploadWk)} の週で ${nf(peakUpload)} 回。<br>
    日付の切り方（UTC / JST）で初日の人数が変わるため、特定の公開時刻を基準にせず「最初の登録」からの週次で並べている。
  </footer>
</div>`;

    return { html, stats: { N, totalUsers, totalUploads, avgUploads, launch, peakUpload, peakUploadWk } };
}

async function main() {
    console.log('DB から週次データを取得中…');
    const raw = await fetchWeekly();
    const { html, stats } = buildHtml(raw);

    const tmpHtml = path.join(__dirname, '.growth-chart.tmp.html');
    fs.writeFileSync(tmpHtml, html);

    const exe = CHROME_CANDIDATES.find(p => fs.existsSync(p));
    if (!exe) throw new Error('Chrome / Edge が見つからない。CHROME_PATH 環境変数で実行ファイルを指定すること。');

    const puppeteer = require(path.join(ROOT, 'node_modules', 'puppeteer'));
    const browser = await puppeteer.launch({ executablePath: exe, args: ['--no-sandbox'] });
    try {
        const page = await browser.newPage();
        await page.setViewport({ width: 1180, height: 1400, deviceScaleFactor: 2 });
        await page.goto('file:///' + tmpHtml.replace(/\\/g, '/'), { waitUntil: 'networkidle0' });
        await (await page.$('.card')).screenshot({ path: OUT_PNG });
    } finally {
        await browser.close();
        fs.unlinkSync(tmpHtml);
    }

    console.log(`完全な週 ${stats.N} / 総ユーザー ${nf(stats.totalUsers)} 人 / 総CSV ${nf(stats.totalUploads)} 回`);
    console.log(`週平均 ${nf(stats.avgUploads)} 回 / 初週 ${nf(stats.launch)} 人 / CSV最大 ${nf(stats.peakUpload)} (${stats.peakUploadWk})`);
    console.log(`出力: ${path.relative(ROOT, OUT_PNG)} (${(fs.statSync(OUT_PNG).size / 1024).toFixed(0)} KB)`);
}

main().catch(e => { console.error('ERROR:', e.message); process.exit(1); });
