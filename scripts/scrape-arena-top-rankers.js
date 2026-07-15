/**
 * scripts/scrape-arena-top-rankers.js
 * ---------------------------------------------------------------------------
 * 管理者用バッチ: e-amusement GATE の「アリーナクラス SP TOP RANKER ランキング」を
 * 取り込み、プレイデータを公開しているプレイヤーを「アリーナ仮想プレイヤー」として
 * beat-seeker の専用テーブルに保存する。
 *
 * 確定済みフロー（ユーザー提供）:
 *   1. ランキング: https://p.eagate.573.jp/game/2dx/33/ranking/arena/top_ranking.html （SP、複数ページ）
 *        - 各行の「DJ NAME」セル下段に IIDX ID（例 "2056-8937"）
 *        - アリーナクラスは行内アイコン画像（例 A1）
 *   2. 公開判定: https://p.eagate.573.jp/game/2dx/33/rival/rival_search.html
 *        - 「IIDXIDから探す」フォームに IIDX ID（ハイフンなし）を入力して検索
 *        - ヒット → 公開 / ヒットなし・「非公開」表示 → スキップ
 *        - 検索結果のリンクから rival クエリ（rival=...）を取得
 *   2b. プレイヤーページ: rival_status.html?rival=<TOKEN>（検索結果の DJ NAME リンク）を開く。
 *        - error.html へ飛ぶ or 「非公開」表示 → 非公開としてスキップ
 *        - 先にこのページを開くことで難易度ページの一時エラー(err=4)を回避できる
 *   3. スコア取得: https://p.eagate.573.jp/game/2dx/33/djdata/music/difficulty_rival.html?rival=<TOKEN>
 *        - ANOTHER / LEGGENDARIA のベストを取得（difficult=0..11 を走査して union）
 *        - difficult はレベル厳密フィルタではないため、RATE-PT(全レベル上位100)の正確性のため
 *          既定は 1-12（difficult 0..11）を全走査する
 *   4. 認証: 要ログイン。KONAMI サインインは自動ブラウザだと 403 になるため、**Cookie 方式**
 *      （通常 Chrome でログイン→セッション Cookie を EAGATE_COOKIE で渡す）を推奨。
 *      eagate ゲームページ自体は Cookie さえあれば自動ブラウザでも閲覧可能。
 *
 * 保存先: virtual_arena_rankers / virtual_arena_ranker_scores
 *         BEAT/RATE-PT はバックエンド VirtualArenaRankerService が集計（このスクリプトは raw のみ）。
 *         登録済み IIDX ID（users）はスキップ。
 *
 * 依存: puppeteer（root node_modules）/ pg（scripts/node_modules）
 *
 * 実行例:
 *   # 少数ドライラン（DBに書かず、実DOMの取得内容をログ確認）※Chromeを閉じてから
 *   node scripts/scrape-arena-top-rankers.js --limit=5 --dry-run
 *
 *   # 本実行（DB保存 → バックエンド再集計を叩く）
 *   node scripts/scrape-arena-top-rankers.js \
 *     --recompute-url=http://localhost:8080/api/admin/arena-top-rankers/recompute
 *
 * 認証モード:
 *   既定 = Chrome プロファイル起動（EAGATE_CHROME_USER_DATA_DIR / EAGATE_CHROME_PROFILE）
 *   代替 = EAGATE_COOKIE（Cookie ヘッダ文字列を渡す）
 *   代替 = --connect=http://localhost:9222 （--remote-debugging-port で起動済み Chrome にアタッチ）
 *
 * 主なフラグ:
 *   --limit=N            取り込む人数上限（テスト用。0=無制限）
 *   --dry-run            DB へ書き込まない（取得内容のログのみ）
 *   --levels=1-12        difficulty ページで走査する LEVEL 範囲（既定 1-12）
 *   --delay=800          各リクエスト間の待機ミリ秒
 *   --headless           プロファイルモードでもヘッドレス起動（既定はヘッドあり）
 *   --connect=URL        既存 Chrome(DevTools) にアタッチ
 *   --recompute-url=URL  完了後に POST する再集計エンドポイント
 * ---------------------------------------------------------------------------
 */

'use strict';

const fs = require('fs');
const { Client } = require('pg');
const puppeteer = require('puppeteer');

// ─── 設定 ───────────────────────────────────────────────────────────────
const VERSION = process.env.VERSION || '33';
const BASE = `https://p.eagate.573.jp/game/2dx/${VERSION}`;
const RANKING_URL = `${BASE}/ranking/arena/top_ranking.html`;
const RIVAL_SEARCH_URL = `${BASE}/rival/rival_search.html`;

const CHROME_PATH = process.env.EAGATE_CHROME_PATH
    || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const USER_DATA_DIR = process.env.EAGATE_CHROME_USER_DATA_DIR
    || 'C:\\Users\\oonok\\AppData\\Local\\Google\\Chrome\\User Data';
const CHROME_PROFILE = process.env.EAGATE_CHROME_PROFILE || 'Default';
const EAGATE_COOKIE = process.env.EAGATE_COOKIE || '';

const args = process.argv.slice(2);
const hasFlag = (n) => args.some((a) => a === `--${n}`);
const getOpt = (n, d) => { const h = args.find((a) => a.startsWith(`--${n}=`)); return h ? h.slice(n.length + 3) : d; };

const LIMIT = parseInt(getOpt('limit', '0'), 10) || 0;
const DRY_RUN = hasFlag('dry-run');
const HEADLESS = hasFlag('headless');
const DELAY_MS = parseInt(getOpt('delay', '800'), 10);
const CONNECT_URL = getOpt('connect', '');
const RECOMPUTE_URL = getOpt('recompute-url', '');
const LEVELS = parseLevels(getOpt('levels', '1-12'));
const LOGIN_TIMEOUT = parseInt(getOpt('login-timeout', '300'), 10); // ログイン待機の上限秒
const OUT_FILE = getOpt('out', ''); // 指定時: DB ではなく JSONL ファイルへ 1 行=1 プレイヤーで追記（Postgres 不要・再開可能）
const EXCLUDE_FILE = getOpt('exclude-file', ''); // 登録済み IIDX ID の一覧ファイル（1行1ID）。DB 非接続時でもスクレイプ段階で除外できる
const DEADLINE = getOpt('deadline', ''); // "HH:MM"。この時刻になったら安全停止（eagate メンテ前に途中切断されないため。--out で再開可）
const MIN_RANK = parseInt(getOpt('min-rank', '0'), 10) || 0; // このランク未満のプレイヤーは処理しない（再開時に処理済み範囲を丸ごと飛ばす）

const TARGET_DIFFS = ['ANOTHER', 'LEGGENDARIA'];
const CLFLG = {
    '1': 'FAILED', '2': 'ASSIST CLEAR', '3': 'EASY CLEAR', '4': 'CLEAR',
    '5': 'HARD CLEAR', '6': 'EX HARD CLEAR', '7': 'FULLCOMBO CLEAR',
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function parseLevels(spec) {
    const m = String(spec).match(/^(\d+)\s*-\s*(\d+)$/);
    if (m) { const out = []; for (let i = +m[1]; i <= +m[2]; i++) out.push(i); return out; }
    return String(spec).split(',').map((s) => parseInt(s.trim(), 10)).filter(Boolean);
}

/** "20568937" / "2056 - 8937" などを "2056-8937" に正規化。取れなければ null。 */
function normalizeIidxId(raw) {
    if (!raw) return null;
    const d = String(raw).replace(/[^0-9]/g, '');
    if (d.length !== 8) return null;
    return `${d.slice(0, 4)}-${d.slice(4)}`;
}

// ─── ブラウザ起動（認証モード） ────────────────────────────────────────
async function launchBrowser() {
    if (CONNECT_URL) {
        console.log(`[auth] connect to running Chrome: ${CONNECT_URL}`);
        const browser = await puppeteer.connect({ browserURL: CONNECT_URL, defaultViewport: null });
        return { browser, connected: true };
    }
    if (EAGATE_COOKIE) {
        console.log('[auth] EAGATE_COOKIE mode (system Chrome, temp profile)');
        const browser = await puppeteer.launch({
            headless: HEADLESS ? 'new' : false,
            executablePath: CHROME_PATH,
            args: ['--no-sandbox', '--disable-setuid-sandbox', '--no-first-run', '--no-default-browser-check'],
        });
        // Cookie ヘッダ文字列を name=value に分解し、eagate ホスト向けに投入する。
        const cookies = EAGATE_COOKIE.split(';').map((s) => s.trim()).filter(Boolean).map((pair) => {
            const eq = pair.indexOf('=');
            return { name: pair.slice(0, eq).trim(), value: pair.slice(eq + 1).trim(), url: 'https://p.eagate.573.jp' };
        }).filter((c) => c.name);
        const page = await browser.newPage();
        await page.setCookie(...cookies);
        await page.close();
        console.log(`[auth] set ${cookies.length} cookies`);
        return { browser, connected: false };
    }
    // 既定: あなたの Chrome プロファイルで起動（ログイン済みセッションを再利用）
    console.log(`[auth] Chrome profile mode: userDataDir="${USER_DATA_DIR}" profile="${CHROME_PROFILE}"`);
    console.log('       ⚠️ 実行中は通常の Chrome を閉じておくこと（プロファイルロックのため）。');
    const browser = await puppeteer.launch({
        headless: HEADLESS ? 'new' : false,
        executablePath: CHROME_PATH,
        userDataDir: USER_DATA_DIR,
        args: [`--profile-directory=${CHROME_PROFILE}`, '--no-first-run', '--no-default-browser-check'],
    });
    return { browser, connected: false };
}

/** ページ取得後、ログイン状態を判定する。未ログインなら例外。 */
async function gotoChecked(page, url) {
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 60000 });
    const info = await page.evaluate(() => ({
        url: location.href,
        hasLogin: !!document.querySelector('form[action*="login"], a[href*="login"]')
            || /ログインしてください|e-amusement.*ログイン/.test(document.body ? document.body.innerText : ''),
    }));
    if (/\/gate\/.*login|login\.html/.test(info.url)) {
        throw new Error(`未ログインにリダイレクトされました: ${info.url}（Chrome を閉じてから、ログイン済みプロファイルで再実行してください）`);
    }
    return info;
}

// ─── ログイン待機（対話ログイン） ──────────────────────────────────────
/** マイページにアクセスし、KONAMI サインインへリダイレクトされるか否かでログイン状態を判定する。 */
async function isLoggedIn(page) {
    try {
        await page.goto('https://p.eagate.573.jp/gate/p/mypage/index.html', { waitUntil: 'networkidle2', timeout: 60000 });
    } catch (_) { return false; }
    const u = page.url();
    if (/konami\.net\/.*signin|\/gate\/p\/login|login\.html/.test(u)) return false;
    return page.evaluate(() => !/ログインが必要|ログインしてください|ご利用にはe-amusement/.test(document.body ? document.body.innerText : ''));
}

/**
 * ログイン済みなら即 return。未ログインなら eagate ログインページを開き、
 * ユーザーが（開いた Chrome ウィンドウで）ログインするまでポーリングして待つ。
 */
async function ensureLogin(page, allowInteractive) {
    if (await isLoggedIn(page)) { console.log('[login] already logged in'); return; }
    if (!allowInteractive) {
        throw new Error('未ログインです。EAGATE_COOKIE が無効/期限切れの可能性があります。'
            + '通常の Chrome でログインした状態の Cookie を取得し直してください。');
    }
    const loginUrl = `${BASE.replace('/game/2dx/' + VERSION, '')}/gate/p/login.html?path=/game/2dx/${VERSION}/rival/rival_search.html`;
    try { await page.goto(loginUrl, { waitUntil: 'networkidle2', timeout: 60000 }); } catch (_) {}
    console.log('');
    console.log('  ============================================================');
    console.log('  ▶ 開いた Chrome ウィンドウで e-amusement にログインしてください。');
    console.log(`    ログインを検知すると自動で続行します（最大 ${LOGIN_TIMEOUT} 秒待機）。`);
    console.log('  ============================================================');
    console.log('');
    const deadline = Date.now() + LOGIN_TIMEOUT * 1000;
    while (Date.now() < deadline) {
        await sleep(4000);
        if (await isLoggedIn(page)) { console.log('[login] ログインを検知しました。続行します。'); return; }
    }
    throw new Error(`ログインがタイムアウトしました（${LOGIN_TIMEOUT}秒）。--login-timeout で延長できます。`);
}

// ─── (1) ランキングページ ──────────────────────────────────────────────
/** 現在表示中のランキング表の行を抽出する。 */
async function parseRankingRows(page) {
    return page.evaluate(() => {
        const text = (el) => (el ? (el.innerText || el.textContent || '').replace(/ /g, ' ').trim() : '');
        const rows = [];
        // top_ranking.html は SP 表(pc_table1, 表示) と DP 表(pc_table2, display:none) の
        // 2 テーブルを DOM に持つ。SP のみが欲しいので「表示されているランキング表」だけを対象にする。
        // （両方拾うと SP+DP 混在で件数が倍増する）
        const visibleTables = Array.from(document.querySelectorAll('table'))
            .filter((t) => t.offsetParent !== null && /\d{4}-\d{4}/.test(t.innerText || ''));
        const spTable = visibleTables[0] || null;
        const trs = spTable ? Array.from(spTable.querySelectorAll('tr')) : [];
        for (const tr of trs) {
            const tds = tr.querySelectorAll('td');
            if (tds.length < 3) continue;
            // 念のため各行の可視性もチェック（隠れ行を除外）
            if (tr.getClientRects().length === 0) continue;
            const rowText = text(tr);
            const idm = rowText.match(/(\d{4})\s*-\s*(\d{4})/);
            if (!idm) continue;
            const iidxId = `${idm[1]}-${idm[2]}`;

            const rankPos = parseInt(text(tds[0]).replace(/[^0-9]/g, ''), 10) || null;

            // DJ NAME/IIDX ID セル: 上段 DJ NAME、下段 IIDX ID
            let djName = '';
            for (const td of tds) {
                const t = text(td);
                if (t.includes(iidxId) || t.includes(idm[1] + '-' + idm[2])) {
                    // IIDX ID 行を除いた残りの先頭行を DJ NAME とみなす
                    djName = t.replace(/(\d{4})\s*-\s*(\d{4})/, '').split(/\n|\r/)[0].trim();
                    break;
                }
            }

            // アリーナクラス: 行内アイコン画像から取得（例 arena_icon/a1.png → A1）
            let arenaClass = '';
            const img = tr.querySelector('img[src*="arena"]');
            if (img) {
                const m = (img.getAttribute('src') || '').match(/([abcd]\d+)\.(png|gif|jpg)/i)
                    || (img.getAttribute('alt') || '').match(/([abcd]\d+)/i);
                if (m) arenaClass = m[1].toUpperCase();
            }

            rows.push({ rankPos, djName, iidxId, arenaClass });
        }
        return rows;
    });
}

/** ランキング表示ページの「N/M」からページ総数を返す（取れなければ 1）。 */
async function readTotalPages(page) {
    return page.evaluate(() => {
        const t = document.body ? document.body.innerText : '';
        const m = t.match(/(\d+)\s*\/\s*(\d+)/);
        return m ? parseInt(m[2], 10) : 1;
    });
}

/** 現在のページ番号（"N/M" の N）を返す。取れなければ null。 */
async function readCurrentPage(page) {
    return page.evaluate(() => {
        const m = (document.body ? document.body.innerText : '').match(/(\d+)\s*\/\s*(\d+)/);
        return m ? parseInt(m[1], 10) : null;
    });
}

/**
 * 次ページへ遷移する。eagate のページャは `<div class="page-next" onclick="next_paging(0)">▶</div>`
 * で、クリックすると AJAX で表を差し替える（全送り ▶▶ は誤って踏まないよう厳密に page-next を叩く）。
 * ページ番号が増えたら成功。
 */
async function goToNextRankingPage(page, current) {
    const clicked = await page.evaluate(() => {
        // 1) 単ステップの次ボタンを厳密に取得
        let el = document.querySelector('.page-next[onclick]')
            || Array.from(document.querySelectorAll('[onclick]')).find((e) => /next_paging/.test(e.getAttribute('onclick') || ''));
        if (el) { el.click(); return true; }
        return false;
    });
    if (!clicked) return false;
    // ページ番号が current+1 になるまで待つ（AJAX 差し替え）
    for (let i = 0; i < 20; i++) {
        await sleep(500);
        const now = await readCurrentPage(page);
        if (now && now > current) return true;
    }
    return false;
}

/** ランキング全ページを走査して行を集める。 */
async function scrapeAllRanking(page) {
    await gotoChecked(page, RANKING_URL);
    const total = await readTotalPages(page);
    console.log(`  ranking pages: ${total}`);
    const all = [];
    const seen = new Set();
    for (let p = 1; p <= total; p++) {
        const rows = await parseRankingRows(page);
        let added = 0;
        for (const r of rows) {
            if (seen.has(r.iidxId)) continue;
            seen.add(r.iidxId);
            all.push({ ...r, rankPos: r.rankPos ?? all.length + 1 });
            added++;
            if (LIMIT && all.length >= LIMIT) { console.log(`  page ${p}: +${added} (reached limit ${LIMIT})`); return all; }
        }
        console.log(`  page ${p}/${total}: +${added} (total ${all.length})`);
        if (p < total) {
            const ok = await goToNextRankingPage(page, p);
            if (!ok) { console.warn('  次ページボタンを特定できませんでした（1ページ目のみ取得）。実DOM確認が必要。'); break; }
        }
    }
    return all;
}

// ─── (2) プレイヤー検索 → プレイヤーページ → 公開判定 ────────────────────
/**
 * rival_search.html で IIDX ID（ハイフンなし）を検索し、結果の
 * 「rival_status.html?rival=<TOKEN>」リンクから rival トークンを得る。
 * 見つからなければ検索ヒットなし（= 非公開扱い）。
 *
 * 戻り値: { hit: boolean, rivalToken: string|null }
 */
async function searchRivalToken(page, iidxDigits) {
    await page.goto(RIVAL_SEARCH_URL, { waitUntil: 'networkidle2', timeout: 60000 });
    // IIDX ID 検索フォーム（input[name=iidxid]）に入力して送信
    const ok = await page.evaluate((digits) => {
        const form = Array.from(document.querySelectorAll('form')).find((f) => f.querySelector('input[name="iidxid"]'));
        if (!form) return false;
        form.querySelector('input[name="iidxid"]').value = digits;
        form.submit();
        return true;
    }, iidxDigits);
    if (!ok) return { hit: false, rivalToken: null };
    try { await page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 30000 }); } catch (_) {}
    await sleep(600);
    // 検索結果の「rival_status.html?rival=...」（=プレイヤーページへのリンク）からトークン抽出
    const token = await page.evaluate(() => {
        const a = document.querySelector('a[href*="rival_status.html?rival="]');
        if (!a) return null;
        const m = (a.getAttribute('href') || '').match(/[?&]rival=([^&#"']+)/);
        return m ? decodeURIComponent(m[1]) : null;
    });
    return { hit: !!token, rivalToken: token };
}

/**
 * プレイヤーページ（rival_status.html?rival=TOKEN）を開き、公開状態を判定する。
 * ユーザー提供フロー「プレイヤーページが開けたら MENU から難易度へ」に対応。
 * エラーページ or 「非公開」表示なら公開されていない。
 *
 * 戻り値: { public: boolean, reason: string }
 */
async function openPlayerPage(page, rivalToken) {
    const url = `${BASE}/djdata/rival/rival_status.html?rival=${rivalToken}`;
    // 一時的な error.html リダイレクト（混雑等）で公開プレイヤーを誤スキップしないよう、1 回だけリトライ。
    for (let attempt = 0; attempt < 2; attempt++) {
        try { await page.goto(url, { waitUntil: 'networkidle2', timeout: 60000 }); }
        catch (e) { if (attempt === 1) return { public: false, reason: 'status-goto-failed' }; await sleep(1500); continue; }
        const r = await page.evaluate(() => {
            if (/error\.html/.test(location.href)) return { public: false, reason: 'status-error' };
            const body = document.body ? document.body.innerText : '';
            if (/非公開|公開されていません|公開していません/.test(body)) return { public: false, reason: 'private' };
            return { public: true, reason: 'public' };
        });
        if (r.reason !== 'status-error' || attempt === 1) return r;
        await sleep(1500); // status-error のみリトライ
    }
    return { public: false, reason: 'status-error' };
}

/**
 * 【メソッド】 IIDX ID から「公開プレイヤーの rival トークン」を返す。
 * 手順: 検索 → rival_status リンク取得 → プレイヤーページを開いて公開判定。
 * 戻り値: { public: boolean, rivalToken: string|null, reason: string }
 */
async function searchRival(page, iidxDigits) {
    const { hit, rivalToken } = await searchRivalToken(page, iidxDigits);
    if (!hit || !rivalToken) return { public: false, rivalToken: null, reason: 'not-found' };
    const status = await openPlayerPage(page, rivalToken);
    if (!status.public) return { public: false, rivalToken: null, reason: status.reason };
    return { public: true, rivalToken, reason: 'public' };
}

// ─── (3) rival の楽曲データ（ANOTHER/LEGGENDARIA） ──────────────────────
/** 1 レベル分の difficulty_rival ページを parse する（bookmarklet の parsePage 流用）。 */
async function parseDifficultyPage(page, level) {
    return page.evaluate((level, TARGET_DIFFS, CLFLG) => {
        const rows = [];
        const trs = document.querySelectorAll('.series-difficulty table tr');
        trs.forEach((tr) => {
            const tds = tr.querySelectorAll('td');
            if (tds.length < 5) return;
            const a = tds[0].querySelector('a.music_info');
            if (!a) return;
            const title = (a.innerText || a.textContent || '').trim();
            const diff = (tds[1].innerText || tds[1].textContent || '').replace(/ /g, ' ').trim();
            if (TARGET_DIFFS.indexOf(diff) < 0) return;

            let dj = '---';
            const djImg = tds[2].querySelector('img');
            if (djImg) { const dm = (djImg.getAttribute('src') || '').match(/score_icon\/(.+?)\.gif/); if (dm) dj = dm[1]; }
            const sc = (tds[3].innerText || tds[3].textContent || '').replace(/ /g, ' ');
            let ex = 0, pg = 0, gr = 0;
            const sm = sc.match(/(\d+)\s*\(\s*(\d+)\s*\/\s*(\d+)\s*\)/);
            if (sm) { ex = parseInt(sm[1]); pg = parseInt(sm[2]); gr = parseInt(sm[3]); }
            else { const s1 = sc.match(/\d+/); if (s1) ex = parseInt(s1[0]); }

            let clear = 'NO PLAY';
            const clImg = tds[4].querySelector('img');
            if (clImg) { const cm = (clImg.getAttribute('src') || '').match(/clflg(\d)\.gif/); if (cm) clear = CLFLG[cm[1]] || 'NO PLAY'; }

            rows.push({ title, difficultyName: diff, difficultyLevel: level, score: ex, pgreat: pg, great: gr, clearType: clear, djLevel: dj });
        });
        const hasNext = !!document.querySelector('.next-prev .navi-next a');
        return { rows, hasNext };
    }, level, TARGET_DIFFS, CLFLG);
}

/** rival の全 LEVEL の ANOTHER/LEGGENDARIA を集める。 */
async function scrapeRivalDifficulty(page, rivalToken) {
    const charts = {};
    for (const level of LEVELS) {
        const difficult = level - 1; // difficult=0 → level 1（bookmarklet と同じ）
        let offset = 0;
        while (true) {
            const url = `${BASE}/djdata/music/difficulty_rival.html?rival=${encodeURIComponent(rivalToken)}`
                + `&difficult=${difficult}&style=0&disp=1${offset ? `&offset=${offset}` : ''}`;
            try {
                await page.goto(url, { waitUntil: 'networkidle2', timeout: 60000 });
            } catch (e) { console.warn(`    goto failed lv${level}: ${e.message}`); break; }
            const { rows, hasNext } = await parseDifficultyPage(page, level);
            for (const r of rows) {
                const key = r.title + '||' + r.difficultyName;
                if (!charts[key]) charts[key] = r;
            }
            if (hasNext && offset < 3000) { offset += 50; await sleep(DELAY_MS); } else break;
        }
        await sleep(DELAY_MS);
    }
    return Object.values(charts);
}

// ─── DB ─────────────────────────────────────────────────────────────────
async function upsertRanker(client, ranker, scores) {
    const iidxId = normalizeIidxId(ranker.iidxId);
    if (!iidxId) return;
    await client.query('BEGIN');
    try {
        const res = await client.query(
            `INSERT INTO virtual_arena_rankers (iidx_id, dj_name, arena_class, rank_pos, scraped_at, created_at)
             VALUES ($1,$2,$3,$4,NOW(),NOW())
             ON CONFLICT (iidx_id) DO UPDATE
               SET dj_name = EXCLUDED.dj_name, arena_class = EXCLUDED.arena_class,
                   rank_pos = EXCLUDED.rank_pos, scraped_at = NOW()
             RETURNING id`,
            [iidxId, ranker.djName || null, ranker.arenaClass || null, ranker.rankPos || null]
        );
        const rankerId = res.rows[0].id;
        await client.query('DELETE FROM virtual_arena_ranker_scores WHERE ranker_id = $1', [rankerId]);
        for (const s of scores) {
            await client.query(
                `INSERT INTO virtual_arena_ranker_scores
                   (ranker_id, title, difficulty_name, difficulty_level, score, pgreat, great, miss_count, clear_type, dj_level)
                 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`,
                [rankerId, s.title, s.difficultyName, s.difficultyLevel || null, s.score || 0,
                 s.pgreat || null, s.great || null, null, s.clearType || 'NO PLAY', s.djLevel || null]
            );
        }
        await client.query('COMMIT');
    } catch (e) { await client.query('ROLLBACK'); throw e; }
}

// ─── メイン ─────────────────────────────────────────────────────────────
async function main() {
    // --out 指定時は既存 JSONL から取り込み済み iidx を読み、再開（skip）する。
    const done = new Set();
    if (OUT_FILE && fs.existsSync(OUT_FILE)) {
        for (const line of fs.readFileSync(OUT_FILE, 'utf8').split('\n')) {
            const t = line.trim(); if (!t) continue;
            try { const o = JSON.parse(t); if (o.iidxId) done.add(o.iidxId); } catch (_) {}
        }
        console.log(`[out] resume: ${done.size} players already in ${OUT_FILE}`);
    }

    const client = new Client({
        host: process.env.PGHOST || 'localhost',
        port: parseInt(process.env.PGPORT || '5432'),
        database: process.env.PGDATABASE || 'beatseeker',
        user: process.env.PGUSER || 'postgres',
        password: process.env.PGPASSWORD || 'postgres',
        ssl: process.env.PGHOST && process.env.PGHOST !== 'localhost' ? { rejectUnauthorized: false } : false,
    });
    // DB は「登録済み IIDX ID の除外」と保存に使う。--out（ファイル出力）や DRY_RUN では
    // DB 未起動でも続行する（除外は表示時にバックエンドが行うため問題ない）。それ以外は必須。
    let dbReady = false;
    const registered = new Set();
    try {
        await client.connect();
        const regRes = await client.query('SELECT iidx_id FROM users WHERE iidx_id IS NOT NULL');
        regRes.rows.forEach((r) => registered.add(r.iidx_id));
        dbReady = true;
    } catch (e) {
        if (!DRY_RUN && !OUT_FILE) { console.error(`DB 接続に失敗しました（DB 保存には Postgres が必須。--out でファイル出力も可）: ${e.message}`); process.exit(1); }
        console.warn(`[warn] DB 未接続で続行（除外・DB保存はスキップ）: ${e.message}`);
    }
    // DB 非接続時でも、登録済み IIDX の一覧ファイルがあればスクレイプ段階で除外する（時短）。
    if (EXCLUDE_FILE && fs.existsSync(EXCLUDE_FILE)) {
        let n = 0;
        for (const line of fs.readFileSync(EXCLUDE_FILE, 'utf8').split(/\r?\n/)) {
            const id = normalizeIidxId(line.trim());
            if (id) { registered.add(id); n++; }
        }
        console.log(`[exclude] loaded ${n} registered IIDX from ${EXCLUDE_FILE}`);
    }
    // ハード締切: 指定時刻になったら安全停止（メンテ前に途中切断されないため）。
    let deadlineMs = null;
    if (DEADLINE && /^\d{1,2}:\d{2}$/.test(DEADLINE)) {
        const [h, m] = DEADLINE.split(':').map(Number);
        const d = new Date(); d.setHours(h, m, 0, 0);
        if (d.getTime() < Date.now()) d.setDate(d.getDate() + 1);
        deadlineMs = d.getTime();
        console.log(`[deadline] 安全停止時刻: ${d.toString()}`);
    }
    console.log(`DB=${dbReady ? 'ready' : 'skipped'} OUT=${OUT_FILE || '(db)'} Registered=${registered.size} DRY_RUN=${DRY_RUN} LIMIT=${LIMIT || '∞'} LEVELS=${LEVELS.join(',')} DELAY=${DELAY_MS}`);

    const { browser, connected } = await launchBrowser();
    const page = await browser.newPage();
    await page.setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36');

    let saved = 0, skippedRegistered = 0, skippedNonPublic = 0, failed = 0;
    try {
        // 要ログイン。プロファイルモードのみ対話ログインを許可（Cookie/connect は検証のみ）。
        const allowInteractive = !EAGATE_COOKIE && !CONNECT_URL;
        await ensureLogin(page, allowInteractive);

        console.log(`Scraping ranking: ${RANKING_URL}`);
        const rankers = await scrapeAllRanking(page);
        console.log(`Ranking rows: ${rankers.length}`);

        let idx = 0, processedSinceCheck = 0;
        for (const ranker of rankers) {
            idx++;
            // 再開時: 指定ランク未満（処理済み範囲）は丸ごとスキップ（再検索の無駄を省く）
            if (MIN_RANK && (ranker.rankPos || 0) < MIN_RANK) continue;
            // ハード締切に達したら安全停止（部分結果は保持、--out で再開可）
            if (deadlineMs && Date.now() >= deadlineMs) {
                console.error(`\n[deadline] 締切時刻に到達したため安全停止します（idx=${idx}）。残りは --out 再開で続行できます。`);
                break;
            }
            const iidxId = normalizeIidxId(ranker.iidxId);
            if (!iidxId) { failed++; continue; }
            if (done.has(iidxId)) { skippedRegistered++; continue; } // --out 再開: 取得済みは飛ばす

            // 長時間 run 中のセッション切れ対策: 40 人ごとにログイン状態を確認し、
            // 切れていたら安全停止（部分結果は保持済み。Cookie 更新後に --out で再開可能）。
            if (++processedSinceCheck >= 40) {
                processedSinceCheck = 0;
                if (!(await isLoggedIn(page))) {
                    console.error(`\n[stop] セッション切れを検知しました（idx=${idx}）。Cookie を更新して同じ --out で再実行すると続きから再開します。`);
                    break;
                }
            }
            if (registered.has(iidxId)) { skippedRegistered++; console.log(`  [${idx}] ${iidxId} skip(registered)`); continue; }

            await sleep(DELAY_MS);
            const digits = iidxId.replace('-', '');
            let search;
            try { search = await searchRival(page, digits); }
            catch (e) { console.warn(`  [${idx}] ${iidxId} search error: ${e.message}`); failed++; continue; }

            if (!search.public) { skippedNonPublic++; console.log(`  [${idx}] ${iidxId} ${ranker.djName} skip(${search.reason})`); continue; }

            let scores = [];
            try { scores = await scrapeRivalDifficulty(page, search.rivalToken); }
            catch (e) { console.warn(`  [${idx}] ${iidxId} difficulty error: ${e.message}`); failed++; continue; }
            scores = scores.filter((s) => s.score > 0);
            if (scores.length === 0) { skippedNonPublic++; console.log(`  [${idx}] ${iidxId} ${ranker.djName} skip(no-scores)`); continue; }

            if (DRY_RUN) {
                console.log(`  [${idx}] [dry] ${iidxId} ${ranker.djName} (${ranker.arenaClass}) rival=${search.rivalToken} charts=${scores.length}`);
            } else if (OUT_FILE) {
                // JSONL に 1 行追記（Postgres 不要・クラッシュしても取得済み分は残る）
                const rec = { iidxId, djName: ranker.djName || null, arenaClass: ranker.arenaClass || null, rankPos: ranker.rankPos || null, scores };
                fs.appendFileSync(OUT_FILE, JSON.stringify(rec) + '\n');
                console.log(`  [${idx}] [out] ${iidxId} ${ranker.djName} (${ranker.arenaClass}) charts=${scores.length}`);
            } else {
                await upsertRanker(client, ranker, scores);
                console.log(`  [${idx}] [save] ${iidxId} ${ranker.djName} (${ranker.arenaClass}) charts=${scores.length}`);
            }
            saved++;
        }
    } finally {
        if (connected) await browser.disconnect(); else await browser.close();
    }

    console.log(`\nDone. saved=${saved} skippedRegistered=${skippedRegistered} skippedNonPublic=${skippedNonPublic} failed=${failed} (dryRun=${DRY_RUN})`);
    if (dbReady) await client.end();

    if (!DRY_RUN && RECOMPUTE_URL) {
        try {
            const headers = { 'Content-Type': 'application/json' };
            if (process.env.ADMIN_API_TOKEN) headers['Authorization'] = `Bearer ${process.env.ADMIN_API_TOKEN}`;
            const resp = await fetch(RECOMPUTE_URL, { method: 'POST', headers });
            console.log(`Recompute POST ${RECOMPUTE_URL} -> ${resp.status}`);
        } catch (e) { console.warn(`Recompute call failed: ${e.message}`); }
    }
}

main().catch((e) => { console.error(e); process.exit(1); });
