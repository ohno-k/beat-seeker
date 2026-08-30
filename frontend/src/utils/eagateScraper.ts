/**
 * 【ユーティリティの役割】 e-amusement GATE（IIDX）上で実行され、スコア CSV と ARENA 対戦履歴を
 * まとめて収集する「スクレイプ中核」を提供する。
 *
 * このモジュール自体は UI を一切持たず、実行環境にも依存しない。呼び出し側（エントリ）が
 * 進捗表示や結果の受け渡し方法を決める。現在のエントリは 2 つ:
 *  - `mainBookmarklet.ts`  … ブックマークレット。取得後にクリップボードへコピー→ beat-seeker へ遷移
 *  - `nativeScraper.ts`    … Android アプリの非表示 WebView。取得結果をネイティブ側へ postMessage
 *
 * どちらも「eagate のオリジン上で JS を実行する」という同じ前提に立つ。ブラウザのクロスオリジン
 * 制約により beat-seeker のページから直接 eagate を fetch することはできないため、この中核は
 * 必ず eagate のページ内で動かす必要がある。
 *
 * 収集内容:
 *  1. URL から IIDX のバージョン番号（例: 33）を推定
 *  2. ARENA モードの対戦データページを取得し、DOM からバトル情報をパース
 *  3. 「楽曲データ/難易度別」(djdata/music/difficulty.html) を LEVEL 1〜12 まで順に
 *     スクレイピング（各レベルは offset を 50 ずつ進めて全ページ巡回）し、
 *     公式 CSV と同じカラム構成の CSV 文字列を組み立てる。
 *       - 1 行＝1 曲（曲名・各難易度のスコア・PGreat/Great・クリアランプ・DJ LEVEL）
 *       - クリアランプ画像 clflg1〜7 を FAILED〜FULLCOMBO CLEAR に変換
 *       - 難易度ページに無い項目（ミスカウント / ジャンル / アーティスト /
 *         バージョン / プレー回数 / 最終プレー日時）は空欄（公式CSVの該当列に合わせる）
 *       - 同一曲の複数譜面は 1 行に集約し、parseScoreCsv がそのまま読める形にする
 *
 * なぜ CSV ダウンロードでなく難易度別スクレイピングか:
 *  - スコアは毎作リセットされるがクリアランプは永続するため、難易度別ページなら
 *    「スコア0・ランプあり」の譜面も含めてレベル順に網羅取得できる。
 *
 * 注意:
 *  - 難易度ページは Shift_JIS 表記だが、eagate は実体 UTF-8 のため fetch().text() で読める。
 */

/** ARENA 対戦の 1 曲分の情報。 */
export type Song = { title: string; difficulty: string };
/** ARENA 対戦の 1 曲あたりのスコアと獲得 pt。 */
export type SongScore = { score: number; pt: number };
/** ARENA 対戦の参加者 1 人分。 */
export type Player = {
  djName: string;
  arenaClass: string;
  totalPt: number;
  rank: number;
  songScores: SongScore[];
};
/** ARENA 対戦 1 戦分。 */
export type Battle = {
  battleType: string;
  date: string;
  songs: Song[];
  players: Player[];
};

/**
 * 【型】 スクレイプ結果。`type` を含めた形がそのまま beat-seeker 側の取り込み入力
 * （`App.vue` の `processBookmarkletData` / `UnifiedImport` の `processText`）になる。
 */
export type ScrapeResult = {
  type: 'beat-seeker-combined';
  /** 公式 CSV 相当の文字列。1 譜面も取得できなかった場合は空文字。 */
  scoresCsv: string;
  /** ログイン中ユーザーの DJ NAME（ARENA ページから取得。取れなければ空文字）。 */
  myDjName: string;
  /** 取得年（西暦 4 桁）。ARENA の対戦日時に年が含まれないため補完に使う。 */
  year: string;
  /** ARENA 対戦履歴。 */
  battles: Battle[];
  /** 取得できた譜面数（進捗・完了表示用）。 */
  chartCount: number;
  /** 取得できた曲数（＝CSV の行数。進捗・完了表示用）。 */
  songCount: number;
};

/** 【型】 進捗通知のコールバック。エントリ側が画面表示やネイティブ通知に使う。 */
export type ProgressReporter = (message: string) => void;

/** クリアランプ画像 `clflg<N>.gif` の N → 公式 CSV のクリアタイプ表記。 */
const CLFLG: Record<string, string> = {
  '1': 'FAILED',
  '2': 'ASSIST CLEAR',
  '3': 'EASY CLEAR',
  '4': 'CLEAR',
  '5': 'HARD CLEAR',
  '6': 'EX HARD CLEAR',
  '7': 'FULLCOMBO CLEAR',
};

/** 公式 CSV の難易度カラム順。 */
const DIFFS = ['BEGINNER', 'NORMAL', 'HYPER', 'ANOTHER', 'LEGGENDARIA'];

/** 難易度別ページから抽出した 1 譜面分の情報。 */
type Chart = {
  title: string;
  diff: string;
  level: number;
  ex: number;
  pg: number;
  gr: number;
  dj: string;
  clear: string;
};

/**
 * 【関数の役割】 現在の URL から IIDX のバージョン番号を推定し、djdata のベース URL を組み立てる。
 * バージョンが読み取れない場合は現行作（33）にフォールバックする。
 */
function resolveBase(): string {
  const vMatch = location.pathname.match(/\/game\/2dx\/(\d+)\//);
  const ver = vMatch ? vMatch[1] : '33';
  return location.origin + '/game/2dx/' + ver;
}

/**
 * 【関数の役割】 ARENA モードの対戦データページから対戦履歴と DJ NAME を収集する。
 *
 * 現在表示中のページが ARENA ページならその DOM をそのまま使い、そうでなければ
 * `djdata/arena_mode/index.html` を fetch して解析する（どのページからでも動かすため）。
 * 取得失敗時は空の結果を返し、スコア取得は継続させる（ARENA は付随情報のため）。
 */
async function scrapeArena(base: string): Promise<{ battles: Battle[]; myDjName: string }> {
  const battles: Battle[] = [];
  let myName = '';

  try {
    let arenaDoc: Document = document;
    if (!document.querySelector('.arena-title')) {
      const ar = await fetch(base + '/djdata/arena_mode/index.html', { credentials: 'same-origin' });
      const ah = await ar.text();
      arenaDoc = new DOMParser().parseFromString(ah, 'text/html');
    }
    const n = arenaDoc.querySelector('a[href*="djdata/status"] .on-name li:last-child');
    myName = n ? (n.textContent || '').trim() : '';

    const titles = arenaDoc.querySelectorAll('.sp-tab > .arena-title');
    const battleDivs = arenaDoc.querySelectorAll(
      '.sp-tab > .arena-battle, #djdata-arena > div.play-tab > center'
    );
    titles.forEach(function (t, i) {
      const b = battleDivs[i];
      if (!b) return;

      const bt = t.querySelector('.battle');
      const dt = t.querySelector('.date');
      const battleType = bt ? (bt.textContent || '').trim() : '';
      const dateRaw = dt ? (dt.textContent || '').replace(/対戦日時[\s\S]*?：/, '').trim() : '';

      const tbl = b.querySelector('table');
      if (!tbl) return;

      const rows = tbl.querySelectorAll('tr');
      const ths = rows[0].querySelectorAll('th');
      const songs: Song[] = [];
      for (let j = 2; j < ths.length; j++) {
        songs.push({
          title: ths[j].childNodes[0].textContent?.trim() ?? '',
          difficulty: ths[j].childNodes[3].textContent?.trim() ?? '',
        });
      }

      const players: Player[] = [];
      for (let k = 1; k < rows.length; k++) {
        const tds = rows[k].querySelectorAll('td');
        if (tds.length < 3) continue;

        const djName = (tds[0].textContent || '').trim();
        const img = tds[1].querySelector('img');
        let cls = '';
        if (img) {
          const m = img.src.match(/arena_icon\/(a\d+)\.png/);
          if (m) cls = m[1].toUpperCase();
        }

        const ptTxt = (tds[2].textContent || '').replace(/\u00a0/g, ' ').trim();
        const pmTotal = ptTxt.match(/(\d+)pt/);
        const pmRank = ptTxt.match(/(\d+)位/);
        const totalPt = pmTotal ? parseInt(pmTotal[1]) : 0;
        const rank = pmRank ? parseInt(pmRank[1]) : 0;

        const songScores: SongScore[] = [];
        for (let s = 3; s < tds.length; s++) {
          songScores.push({
            score: parseInt(tds[s].childNodes[0].textContent?.trim() ?? '0'),
            pt: parseInt(tds[s].childNodes[3].textContent?.trim() ?? '0'),
          });
        }
        players.push({ djName, arenaClass: cls, totalPt, rank, songScores });
      }
      battles.push({ battleType, date: dateRaw, songs, players });
    });
  } catch (e) {
    console.warn('ARENA data failed', e);
  }

  return { battles, myDjName: myName };
}

/**
 * 【関数の役割】 CSV の 1 セルをダブルクォートで囲み、内部の `"` をエスケープする。
 */
function q(v: unknown): string {
  return '"' + String(v == null ? '' : v).replace(/"/g, '""') + '"';
}

/**
 * 【関数の役割】 収集済みの譜面辞書から公式 CSV と同じカラム構成の CSV 文字列を組み立てる。
 * 同一曲の複数譜面は 1 行に集約する（`parseScoreCsv` が期待する形）。
 *
 * @returns CSV 本文と曲数。
 */
function buildCsv(charts: Record<string, Chart>): { csv: string; songCount: number } {
  const songs: Record<string, Record<string, Chart>> = {};
  Object.keys(charts).forEach(function (kk) {
    const c = charts[kk];
    if (!songs[c.title]) songs[c.title] = {};
    songs[c.title][c.diff] = c;
  });

  const headers = ['バージョン', 'タイトル', 'ジャンル', 'アーティスト', 'プレー回数'];
  DIFFS.forEach(function (d) {
    headers.push(
      d + ' 難易度',
      d + ' スコア',
      d + ' PGreat',
      d + ' Great',
      d + ' ミスカウント',
      d + ' クリアタイプ',
      d + ' DJ LEVEL'
    );
  });
  headers.push('最終プレー日時');

  const lines = [headers.map(q).join(',')];
  let songCount = 0;
  Object.keys(songs).forEach(function (t) {
    const s = songs[t];
    const vals = ['', t, '', '', '0'];
    DIFFS.forEach(function (d) {
      const c = s[d];
      if (c) {
        vals.push(String(c.level), String(c.ex), String(c.pg), String(c.gr), '', c.clear, c.dj);
      } else {
        vals.push('', '', '', '', '', '', '');
      }
    });
    vals.push('');
    lines.push(vals.map(q).join(','));
    songCount++;
  });

  return { csv: lines.join('\r\n'), songCount };
}

/**
 * 【関数の役割】 eagate 上でスコアと ARENA 対戦履歴を収集し、beat-seeker が取り込める形にして返す。
 *
 * 処理の流れ:
 *  手順1: URL からバージョンを推定し djdata のベース URL を決める。
 *  手順2: ARENA 対戦履歴と DJ NAME を収集（失敗しても続行）。
 *  手順3: LEVEL 1〜12 を並列にスクレイプし、譜面辞書へ集約。
 *  手順4: 譜面辞書から公式 CSV 相当の文字列を組み立てて返す。
 *
 * @param onProgress 進捗メッセージの通知先（省略可）。
 */
export async function scrapeEagate(onProgress?: ProgressReporter): Promise<ScrapeResult> {
  const report: ProgressReporter = onProgress ?? (() => {});
  const base = resolveBase();

  const { battles, myDjName } = await scrapeArena(base);

  const charts: Record<string, Chart> = {};
  let chartCount = 0;

  /**
   * 難易度別ページ 1 ページ分をパースして `charts` に積む。
   * @returns 次ページが存在するか（`.next-prev .navi-next a` の有無）。
   */
  function parsePage(html: string, level: number): boolean {
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const trs = doc.querySelectorAll('.series-difficulty table tr');
    trs.forEach(function (tr) {
      const tds = tr.querySelectorAll('td');
      if (tds.length < 5) return;

      const a = tds[0].querySelector('a.music_info');
      if (!a) return;
      const title = (a.textContent || '').trim();

      const diff = (tds[1].textContent || '').replace(/\u00a0/g, ' ').trim();
      if (DIFFS.indexOf(diff) < 0) return;

      let dj = '---';
      const djImg = tds[2].querySelector('img');
      if (djImg) {
        const dm = (djImg.getAttribute('src') || '').match(/score_icon\/(.+?)\.gif/);
        if (dm) dj = dm[1];
      }

      const sc = (tds[3].textContent || '').replace(/\u00a0/g, ' ');
      let ex = 0,
        pg = 0,
        gr = 0;
      const sm = sc.match(/(\d+)\s*\(\s*(\d+)\s*\/\s*(\d+)\s*\)/);
      if (sm) {
        ex = parseInt(sm[1]);
        pg = parseInt(sm[2]);
        gr = parseInt(sm[3]);
      } else {
        const s1 = sc.match(/\d+/);
        if (s1) ex = parseInt(s1[0]);
      }

      let clear = 'NO PLAY';
      const clImg = tds[4].querySelector('img');
      if (clImg) {
        const cm = (clImg.getAttribute('src') || '').match(/clflg(\d)\.gif/);
        if (cm) clear = CLFLG[cm[1]] || 'NO PLAY';
      }

      const key = title + '||' + diff;
      if (!charts[key]) {
        charts[key] = { title, diff, level, ex, pg, gr, dj, clear };
        chartCount++;
      }
    });
    return !!doc.querySelector('.next-prev .navi-next a');
  }

  /**
   * 指定レベルの難易度別ページを offset 50 刻みで最後まで巡回する。
   * 引数 `lv` は eagate の POST パラメータ準拠の 0 始まり（表示上の LEVEL は `lv + 1`）。
   */
  async function scrapeLevel(lv: number): Promise<void> {
    let offset = 0;
    while (true) {
      let html: string;
      try {
        if (offset === 0) {
          const r = await fetch(base + '/djdata/music/difficulty.html', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: 'difficult=' + lv + '&style=0&disp=1',
          });
          html = await r.text();
        } else {
          const r2 = await fetch(
            base +
              '/djdata/music/difficulty.html?difficult=' +
              lv +
              '&style=0&disp=1&offset=' +
              offset,
            { credentials: 'same-origin' }
          );
          html = await r2.text();
        }
      } catch (e) {
        console.warn('fetch failed lv' + lv, e);
        break;
      }
      const hasNext = parsePage(html, lv + 1);
      report('難易度別スコア取得中… ' + chartCount + '譜面');
      if (hasNext && offset < 30000) {
        offset += 50;
      } else {
        break;
      }
    }
  }

  try {
    const jobs: Promise<void>[] = [];
    for (let lv = 0; lv < 12; lv++) jobs.push(scrapeLevel(lv));
    await Promise.all(jobs);
  } catch (e) {
    console.warn('Difficulty scrape failed', e);
  }

  const { csv, songCount } = buildCsv(charts);

  return {
    type: 'beat-seeker-combined',
    scoresCsv: chartCount > 0 ? csv : '',
    myDjName,
    year: String(new Date().getFullYear()),
    battles,
    chartCount,
    songCount,
  };
}
