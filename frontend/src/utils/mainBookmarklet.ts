/**
 * 【ユーティリティの役割】 beat-seeker 汎用ブックマークレットのメインスクリプトを提供する。
 *
 * ブックマークレットとは: ブラウザの「お気に入り」に `javascript:` 始まりの URL を登録し、
 * クリックするだけで任意のページ上でスクリプトを実行できる仕組み。
 *
 * このブックマークレットは e-amusement GATE（IIDX）のどのページからでも動くよう設計されており:
 *  1. URL から IIDX のバージョン番号（例: 33）を推定
 *  2. ARENA モードの対戦データページを取得し、DOM からバトル情報をパース
 *  3. 「楽曲データ/難易度別」(djdata/music/difficulty.html) を LEVEL 1〜12 まで順に
 *     スクレイピング（各レベルは offset を 50 ずつ進めて全ページ巡回）し、
 *     公式 CSV と同じカラム構成の CSV 文字列を組み立てる。
 *       - 1 行＝1 譜面（曲名・難易度・DJ LEVEL・EXスコア・PGreat/Great・クリアランプ）
 *       - クリアランプ画像 clflg1〜7 を FAILED〜FULLCOMBO CLEAR に変換
 *       - 難易度ページに無い項目（ミスカウント / ジャンル / アーティスト /
 *         バージョン / プレー回数 / 最終プレー日時）は空欄（公式CSVの該当列に合わせる）
 *       - 同一曲の複数譜面は 1 行に集約し、parseScoreCsv がそのまま読める形にする
 *  4. 取得した全データ（scoresCsv + battles）を JSON 化し、Base64 で URL fragment に載せる
 *  5. 完了後にボタンを表示。クリックでクリップボードへ全文コピー → beat-seeker へ遷移
 *     （長時間の取得後でもユーザー操作起点でコピーするため確実に成功する）
 *
 * なぜ CSV ダウンロードでなく難易度別スクレイピングか:
 *  - スコアは毎作リセットされるがクリアランプは永続するため、難易度別ページなら
 *    「スコア0・ランプあり」の譜面も含めてレベル順に網羅取得できる。
 *
 * 注意:
 *  - 難易度ページは Shift_JIS 表記だが、eagate は実体 UTF-8 のため fetch().text() で読める。
 *  - データが 50000 文字を超える場合は CSV を URL から外し、クリップボード経由で受け渡す。
 */
declare const __APP_ORIGIN__: string;
(
async function(){
  const vMatch = location.pathname.match(/\/game\/2dx\/(\d+)\//);
  const ver = vMatch ? vMatch[1] : '33';
  const base = location.origin + '/game/2dx/' + ver;
  let myName = '';

  type Song = {title: string, difficulty: string};
  type SongScore = {score:number, pt: number};
  type Player = {djName: string, arenaClass: string, totalPt: number, rank: number, songScores: SongScore[]};
  type Battle = {battleType: string, date: string, songs: Song[], players: Player[]};

  const battles : Battle[] = [];
  const statusEl = document.createElement('div');
  statusEl.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:2147483647;background:#0f172a;color:#fff;padding:12px;text-align:center;font:bold 14px sans-serif;box-shadow:0 2px 10px rgba(0,0,0,.45)';
  statusEl.textContent = 'beat-seeker: 取得を開始します…';
  document.body.appendChild(statusEl);

  try {
    let arenaDoc = document;
    if (!document.querySelector('.arena-title')) {
      const ar = await fetch(base + '/djdata/arena_mode/index.html', {credentials: 'same-origin'});
      const ah = await ar.text();
      arenaDoc = new DOMParser().parseFromString(ah, 'text/html');
    }
    const n = arenaDoc.querySelector('a[href*="djdata/status"] .on-name li:last-child');
    myName = n ? (n.textContent || '').trim() : '';

    const titles = arenaDoc.querySelectorAll('.sp-tab > .arena-title');
    const battleDivs = arenaDoc.querySelectorAll('.sp-tab > .arena-battle, #djdata-arena > div.play-tab > center');
    titles.forEach(function(t, i) {
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
      const songs = [];
      for (let j = 2; j < ths.length; j++) {
        const txt = (ths[j].textContent || '').replace(/\u00a0/g, ' ').replace(/\n/g, ' ').trim();
        const sm2 = txt.match(/^(.+?)\s*\/\s*(.+)$/);
        const title = sm2 ? sm2[1].trim() : txt;
        const diff = sm2 ? sm2[2].trim() : '';
        songs.push({title: title, difficulty: diff});
      }

      const players = [];
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

        const songScores = [];
        for (let s = 3; s < tds.length; s++) {
          const stxt = (tds[s].textContent || '').replace(/\u00a0/g, ' ').trim();
          const sm = stxt.match(/(\d+)/);
          const spm = stxt.match(/(\d+)pt/);
          songScores.push({score: sm ? parseInt(sm[1]) : 0, pt: spm ? parseInt(spm[1]) : 0});
        }
        players.push({djName: djName, arenaClass: cls, totalPt: totalPt, rank: rank, songScores: songScores});
      }
      battles.push({battleType: battleType, date: dateRaw, songs: songs, players: players});
    });
  } catch (e) {
    console.warn('ARENA data failed', e);
  }

  const CLFLG: Record<string, string> = {
    '1': 'FAILED',
    '2': 'ASSIST CLEAR',
    '3': 'EASY CLEAR',
    '4': 'CLEAR',
    '5': 'HARD CLEAR',
    '6': 'EX HARD CLEAR',
    '7': 'FULLCOMBO CLEAR'
  };

  const DIFFS = ['BEGINNER', 'NORMAL', 'HYPER', 'ANOTHER', 'LEGGENDARIA'];

  type Chart = {title: string, diff: string, level: number, ex: number, pg: number, gr: number, dj: string, clear: string};
  const charts: Record<string, Chart> = {};

  let chartCount = 0;

  function parsePage(html: string, level: number) {
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const trs = doc.querySelectorAll('.series-difficulty table tr');
    trs.forEach(function(tr) {
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
      let ex = 0, pg = 0, gr = 0;
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
        charts[key] = {title: title, diff: diff, level: level, ex: ex, pg: pg, gr: gr, dj: dj, clear: clear};
        chartCount++;
      }
    });
    return !!doc.querySelector('.next-prev .navi-next a');
  }

  async function scrapeLevel(lv: number) {
    let offset = 0;
    while (true) {
      let html;
      try {
        if (offset === 0) {
          const r = await fetch(base + '/djdata/music/difficulty.html', {method: 'POST', credentials: 'same-origin', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: 'difficult=' + lv + '&style=0&disp=1'});
          html = await r.text();
        } else {
          const r2 = await fetch(base + '/djdata/music/difficulty.html?difficult=' + lv + '&style=0&disp=1&offset=' + offset, {credentials: 'same-origin'});
          html = await r2.text();
        }
      } catch (e) {
        console.warn('fetch failed lv' + lv, e);
        break;
      }
      const hasNext = parsePage(html, lv + 1);
      statusEl.textContent = '難易度別スコア取得中… ' + chartCount + '譜面';
      if (hasNext && offset < 30000) {
        offset += 50;
      } else {
        break;
      }
    }
  }

  try {
    const jobs = [];
    for (let lv = 0; lv < 12; lv++) jobs.push(scrapeLevel(lv));
    await Promise.all(jobs);
  } catch (e) {
    console.warn('Difficulty scrape failed', e);
  }

  const songs: Record<string, Record<string, Chart>> = {};
  Object.keys(charts).forEach(function(kk) {
    const c = charts[kk];
    if (!songs[c.title]) songs[c.title] = {};
    songs[c.title][c.diff] = c;
  });

  const headers = ['バージョン', 'タイトル', 'ジャンル', 'アーティスト', 'プレー回数'];
  DIFFS.forEach(function(d) {
    headers.push(d + ' 難易度', d + ' スコア', d + ' PGreat', d + ' Great', d + ' ミスカウント', d + ' クリアタイプ', d + ' DJ LEVEL');
  });
  headers.push('最終プレー日時');

  function q(v: unknown) {
    return '"' + String(v == null ? '' : v).replace(/"/g, '""') + '"';
  }

  const lines = [headers.map(q).join(',')];
  let songCount = 0;
  Object.keys(songs).forEach(function(t) {
    const s = songs[t];
    const vals = ['', t, '', '', '0'];
    DIFFS.forEach(function(d) {
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

  const scoresCsv = chartCount > 0 ? lines.join('\r\n') : '';
  const year = String(new Date().getFullYear());
  const fullData = JSON.stringify({type: 'beat-seeker-combined', scoresCsv: scoresCsv, myDjName: myName, year: year, battles: battles});

  const urlData = fullData.length > 50000 ? JSON.stringify({type: 'beat-seeker-combined', scoresCsv: '', myDjName: myName, year: year, battles: battles}) : fullData;
  const encoded = btoa(unescape(encodeURIComponent(urlData)));
  const url = `${__APP_ORIGIN__}?import=open#data=` + encoded;

  statusEl.innerHTML = '';
  const info = document.createElement('div');
  info.textContent = '取得完了！ ARENA ' + battles.length + '件 / スコア ' + chartCount + '譜面・' + songCount + '曲';
  info.style.marginBottom = '8px';

  const btn = document.createElement('button');
  btn.textContent = '📋 コピーして beat-seeker を開く';
  btn.style.cssText = 'font:bold 15px sans-serif;padding:10px 20px;border:0;border-radius:8px;background:#2563eb;color:#fff;cursor:pointer';
  btn.onclick = async function() {
    try {
      await navigator.clipboard.writeText(fullData);
    } catch (e) {
    }
    location.href = url;
  };
  statusEl.appendChild(info);
  statusEl.appendChild(btn);
})();
