#!/usr/bin/env python3
"""
全SP譜面バッチ取得・分析スクリプト (SPB/SPN/SPH/SPA/SPL)

textageの各曲ページHTMLを1回取得し、全難易度を一括パース・分析する。
HTMLキャッシュにより再取得なしで再分析可能。

出力先:
  chart_cache/
    cache_index.json          # 全曲の取得状況サマリー
    html/{ver}/{key}.html     # textage生HTMLデータ（1ページ1ファイル）
    raw/{ver}/{key}_{d}.json  # sp[]生文字列 + メタ情報
    profiles/{ver}/{key}_{d}.json # 計算済み傾向プロファイル

使い方:
  python batch_analyze.py                # 全曲処理（未取得のみ）
  python batch_analyze.py --reanalyze    # HTMLキャッシュから再パース・全難易度再分析
  python batch_analyze.py --refetch      # HTML再取得
  python batch_analyze.py --limit 10     # テスト用：10ページのみ
  python batch_analyze.py --ver 22       # 指定バージョンのみ
"""

import json
import re
import sys
import time

# Force UTF-8 output on Windows
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
import argparse
from datetime import datetime, timezone
from pathlib import Path

# analyze_chart.py から関数をインポート
sys.path.insert(0, str(Path(__file__).parent))
from analyze_chart import (
    fetch_textage_html, parse_from_html, profile_from_sp, count_notes,
)

# ---------------------------------------------------------------------------
# 定数
# ---------------------------------------------------------------------------
SONG_DATA  = Path('backend/src/main/resources/data/song_data.json')
CACHE_DIR  = Path('chart_cache')
HTML_DIR   = CACHE_DIR / 'html'
RAW_DIR    = CACHE_DIR / 'raw'
PROF_DIR   = CACHE_DIR / 'profiles'
INDEX_FILE = CACHE_DIR / 'cache_index.json'

REQUEST_INTERVAL = 0.6   # 秒（サーバー負荷軽減）
MAX_RETRY        = 3
RETRY_WAIT       = 5.0

# SP全難易度: (diff_letter, song_data difficulty code, 表示名)
# diff_letter は textage HTML の if(?) ブロック識別子
SP_DIFFICULTIES = [
    ('b', '1',  'SPB'),   # BEGINNER
    ('n', '2',  'SPN'),   # NORMAL
    ('k', '3',  'SPH'),   # HYPER
    ('a', '4',  'SPA'),   # ANOTHER
    ('l', '10', 'SPL'),   # LEGGENDARIA
]

# textage URL クエリパラメータの難易度文字マッピング
# 例: ?1AC00 → 2文字目 'A' = ANOTHER
DIFF_URL_CHAR = {
    'b': 'B',   # BEGINNER
    'n': 'N',   # NORMAL
    'k': 'H',   # HYPER
    'a': 'A',   # ANOTHER
    'l': 'X',   # LEGGENDARIA
}


# ---------------------------------------------------------------------------
# ユーティリティ
# ---------------------------------------------------------------------------
def textage_base(textage_url: str):
    """textage URLからベースページ情報 (ver, key) を抽出する。クエリは無視。"""
    m = re.match(r'(\w+)/([^.]+)\.html', textage_url)
    return (m.group(1), m.group(2)) if m else (None, None)


def textage_url_for_diff(base_url: str, diff_letter: str) -> str:
    """ベースtextage URLから指定難易度用のURLを生成する。
    例: '22/foo.html?1AC00' + diff_letter='n' → '22/foo.html?1NC00'
    """
    url_char = DIFF_URL_CHAR.get(diff_letter, 'A')
    if '?' in base_url:
        base, query = base_url.split('?', 1)
        if len(query) >= 2:
            new_query = query[0] + url_char + query[2:]
            return f"{base}?{new_query}"
    # クエリパラメータがない場合はデフォルトを追加
    base = base_url.split('?')[0]
    return f"{base}?1{url_char}C00"


def has_diff_block(html, diff_letter):
    """HTMLに指定難易度のデータが存在するか簡易チェック。"""
    if diff_letter == 'l':
        return bool(re.search(r'if\s*\(\s*kuro\s*\)\s*\{', html)) or \
               bool(re.search(r'if\s*\(\s*l\s*\)\s*\{', html))
    if diff_letter == 'a':
        return True   # ANOTHERはグローバルデータの場合もあるため常に試行
    if diff_letter == 'n':
        # NORMAL: if(n)ブロックがあるか、グローバルsp[]がNORMAL（if(k)が存在する場合）
        return bool(re.search(r'if\s*\(\s*n\s*\)\s*\{', html)) or \
               bool(re.search(r'if\s*\(\s*k\s*\)\s*\{', html))
    # b, k 等: 厳密にブロック開始パターンをチェック
    return bool(re.search(r'(?:else\s+)?if\s*\(\s*' + re.escape(diff_letter) + r'\s*\)\s*\{', html))


def html_path(ver, key):
    return HTML_DIR / ver / f"{key}.html"


def raw_path(ver, key, diff_letter):
    return RAW_DIR / ver / f"{key}_{diff_letter}.json"


def prof_path(ver, key, diff_letter):
    return PROF_DIR / ver / f"{key}_{diff_letter}.json"


def load_index():
    if INDEX_FILE.exists():
        with open(INDEX_FILE, encoding='utf-8') as f:
            return json.load(f)
    return {'cached': {}}


def save_index(index):
    INDEX_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(INDEX_FILE, 'w', encoding='utf-8') as f:
        json.dump(index, f, ensure_ascii=False, indent=2)


def now_iso():
    return datetime.now(timezone.utc).isoformat()


# ---------------------------------------------------------------------------
# 取得・保存
# ---------------------------------------------------------------------------
def save_html(ver, key, html: str):
    p = html_path(ver, key)
    p.parent.mkdir(parents=True, exist_ok=True)
    with open(p, 'w', encoding='utf-8') as f:
        f.write(html)


def load_html(ver, key):
    p = html_path(ver, key)
    if not p.exists():
        return None
    with open(p, encoding='utf-8') as f:
        return f.read()


def save_raw(ver, key, diff_letter, textage_url, sp, cn_events=None,
             lndef=384, ln_map=None):
    p = raw_path(ver, key, diff_letter)
    p.parent.mkdir(parents=True, exist_ok=True)
    data = {
        'textage':     textage_url,
        'diff_letter': diff_letter,
        'fetched_at':  now_iso(),
        'sp':          {str(k): v for k, v in sp.items()},
        'cn_events':   [list(ev) for ev in cn_events] if cn_events else [],
        'lndef':       lndef,
        'ln_map':      {str(k): v for k, v in (ln_map or {}).items()},
    }
    with open(p, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False)
    return data


def save_profile(ver, key, diff_letter, textage_url, song_meta, prof):
    p = prof_path(ver, key, diff_letter)
    p.parent.mkdir(parents=True, exist_ok=True)
    data = {
        'textage':      textage_url,
        'analyzed_at':  now_iso(),
        'title':        song_meta.get('title', ''),
        'artist':       song_meta.get('artist', ''),
        'bpm_raw':      song_meta.get('bpm', ''),
        'level':        song_meta.get('level'),
        'difficulty':   song_meta.get('difficulty'),
        **prof,
    }
    with open(p, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    return data


# ---------------------------------------------------------------------------
# メイン処理
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description='Beat Seeker 全譜面バッチ分析')
    parser.add_argument('--reanalyze', action='store_true',
                        help='HTMLキャッシュから全難易度を再パース・profile再計算')
    parser.add_argument('--refetch', action='store_true',
                        help='HTMLを再取得（textageにアクセス）')
    parser.add_argument('--limit', type=int, default=0,
                        help='処理する最大ページ数（0=全件）')
    parser.add_argument('--ver', type=str, default='',
                        help='指定バージョンフォルダのみ処理 (例: 22)')
    args = parser.parse_args()

    # ── song_data.json 読み込み → ページ単位でグルーピング ──────
    with open(SONG_DATA, encoding='utf-8') as f:
        songs = json.load(f)['body']

    # textage URLを持つ全エントリ
    all_songs = [s for s in songs if s.get('textage')]

    # ページ単位 (ver, key) のメタデータを構築
    # page_meta: 曲名・BPM等（ページ共通）
    # diff_meta: 難易度別のレベル・ノーツ数（期待値チェック用）
    page_meta = {}   # (ver, key) -> {title, artist, bpm, textage}
    diff_meta = {}   # (ver, key, diff_code) -> {level, notes}

    # タイトル→(ver,key)の逆引き（textage無しエントリのdiff_meta補完用）
    title_to_page = {}   # title -> (ver, key)

    for s in all_songs:
        ver, key = textage_base(s['textage'])
        if not ver:
            continue

        pk = (ver, key)
        if pk not in page_meta:
            page_meta[pk] = {
                'title':   s.get('title', key),
                'artist':  s.get('artist', ''),
                'bpm':     s.get('bpm', '0'),
                'textage': s['textage'],
            }
            title_to_page[s.get('title', key)] = pk

        diff_code = s.get('difficulty', '4')
        dk = (ver, key, diff_code)
        if dk not in diff_meta:
            diff_meta[dk] = {
                'level': s.get('level'),
                'notes': s.get('notes', 0),
                'textage': s['textage'],   # song_data の元URL（DB参照用）
            }

    # textage URLを持たないエントリからも diff_meta を補完
    # （BEGINNER/NORMAL/HYPER等、textage無しでもlevel/notes情報を使用）
    for s in songs:
        if s.get('textage'):
            continue  # textage付きは上で処理済み
        title = s.get('title', '')
        pk = title_to_page.get(title)
        if not pk:
            continue
        ver, key = pk
        diff_code = s.get('difficulty', '4')
        dk = (ver, key, diff_code)
        if dk not in diff_meta:
            diff_meta[dk] = {
                'level': s.get('level'),
                'notes': s.get('notes', 0),
                'textage': '',
            }

    # ── フィルタ・制限 ──────────────────────────────────────────
    page_list = list(page_meta.items())
    if args.ver:
        page_list = [(k, v) for k, v in page_list if k[0] == args.ver]

    total = len(page_list)
    print(f"対象ページ数: {total}")
    if args.limit:
        page_list = page_list[:args.limit]
        print(f"  --limit {args.limit} を適用")

    index = load_index()
    cached_set = set(index.get('cached', {}).keys())

    ok_pages = 0
    ok_diffs = 0
    skip = 0
    err = 0
    errors = []

    # ── メインループ（ページ単位） ──────────────────────────────
    for i, ((ver, key), pm) in enumerate(page_list, 1):
        title       = pm['title']
        textage_url = pm['textage']
        bpm         = pm['bpm']

        # ---- HTML取得 / キャッシュ確認 ----
        html = load_html(ver, key)
        fetched_from_network = False

        need_fetch = (html is None) or args.refetch
        if need_fetch:
            html = None
            for attempt in range(1, MAX_RETRY + 1):
                try:
                    html = fetch_textage_html(textage_url)
                    break
                except Exception as e:
                    if attempt < MAX_RETRY:
                        print(f"  リトライ {attempt}/{MAX_RETRY}: {e}")
                        time.sleep(RETRY_WAIT)
                    else:
                        print(f"[{i}/{len(page_list)}] エラー: {title} -- {e}")
                        errors.append({'textage': textage_url, 'title': title, 'error': str(e)})
                        err += 1

            if html:
                save_html(ver, key, html)
                fetched_from_network = True
            else:
                if fetched_from_network:
                    time.sleep(REQUEST_INTERVAL)
                continue

        # ---- 各難易度を処理 ----
        diffs_processed = []
        decoded_notes_map = {}   # diff_letter -> decoded_notes（フォールバック検出用）

        for diff_letter, diff_code, diff_name in SP_DIFFICULTIES:
            cache_key = f"{ver}/{key}#{diff_letter}"

            # キャッシュ済みスキップ（reanalyze/refetch時は除く）
            if not args.reanalyze and not args.refetch and cache_key in cached_set:
                continue

            # HTMLに該当難易度のブロックがあるか簡易チェック
            if not has_diff_block(html, diff_letter):
                continue

            # パース
            try:
                sp, cn_events, lndef, ln_map = parse_from_html(html, diff_letter)
            except Exception:
                continue

            decoded_notes = count_notes(sp, lndef, ln_map)
            if decoded_notes == 0:
                continue

            # フォールバック検出: SPL/SPBが他難易度と同じノーツ数ならスキップ
            if diff_letter in ('l', 'b') and decoded_notes in decoded_notes_map.values():
                continue

            # 期待ノーツ数との照合（song_dataにエントリがある場合）
            dm = diff_meta.get((ver, key, diff_code))
            if dm and dm['notes'] and dm['notes'] > 0:
                expected = dm['notes']
                if abs(decoded_notes - expected) > expected * 0.2:
                    continue   # 乖離が大きい → 誤った難易度データの可能性

            decoded_notes_map[diff_letter] = decoded_notes

            # 分析
            prof = profile_from_sp(sp, bpm, cn_events, lndef, ln_map)

            # 公式ノーツ数でprofileのnotesを上書き（分析データは保持）
            if dm and dm['notes'] and dm['notes'] > 0:
                prof['notes'] = dm['notes']

            # 保存（song_dataの元URLがあればそれを使用、なければ生成）
            diff_textage_url = (dm.get('textage') if dm else None) \
                               or textage_url_for_diff(textage_url, diff_letter)
            save_raw(ver, key, diff_letter, diff_textage_url, sp, cn_events, lndef, ln_map)
            song_meta = {
                'title':      title,
                'artist':     pm['artist'],
                'bpm':        bpm,
                'level':      dm['level'] if dm else None,
                'difficulty':  diff_code,
            }
            save_profile(ver, key, diff_letter, diff_textage_url, song_meta, prof)

            # インデックス更新
            index.setdefault('cached', {})[cache_key] = {
                'fetched_at': now_iso(),
                'notes': prof['notes'],
                'tags':  prof['tags'],
            }
            cached_set.add(cache_key)

            diffs_processed.append(f"{diff_name}({prof['notes']})")
            ok_diffs += 1

        if diffs_processed:
            ok_pages += 1
            print(f"[{i}/{len(page_list)}] {title[:40]:<40}  {' '.join(diffs_processed)}")
        else:
            skip += 1

        # インデックス定期保存
        if ok_pages % 50 == 0 and ok_pages > 0:
            save_index(index)

        if fetched_from_network:
            time.sleep(REQUEST_INTERVAL)

    # ── 最終保存 ──────────────────────────────────────────────
    save_index(index)

    # ── 結果サマリー ──────────────────────────────────────────
    print()
    print("=" * 60)
    print(f"  処理ページ: {ok_pages}  処理譜面: {ok_diffs}  スキップ: {skip}  エラー: {err}")
    print(f"  キャッシュ総数: {len(index.get('cached', {}))}件")
    if errors:
        print(f"\n  エラー一覧:")
        for e in errors:
            print(f"    {e['textage']}  {e['title']}  -- {e['error']}")
    print("=" * 60)

    if errors:
        err_file = CACHE_DIR / 'errors.json'
        with open(err_file, 'w', encoding='utf-8') as f:
            json.dump(errors, f, ensure_ascii=False, indent=2)
        print(f"  エラー詳細: {err_file}")


if __name__ == '__main__':
    main()
