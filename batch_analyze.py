#!/usr/bin/env python3
"""
全Lv11/12 ANOTHER/LEGGENDARIA 譜面のバッチ分析スクリプト

出力先:
  chart_cache/
    cache_index.json          # 全曲の取得状況���マリー
    raw/{ver}/{key}.json      # sp[]生文字列 + ��タ情報
    profiles/{ver}/{key}.json # 計算済み傾向プロファイル

使い方:
  python batch_analyze.py              # 全曲処理（未取得の���）
  python batch_analyze.py --reanalyze  # raw再利用・profile再計算
  python batch_analyze.py --limit 10   # テスト用：10曲のみ
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
from analyze_chart import fetch_raw, profile_from_sp

# ---------------------------------------------------------------------------
# 定数
# ---------------------------------------------------------------------------
SONG_DATA  = Path('backend/src/main/resources/data/song_data.json')
CACHE_DIR  = Path('chart_cache')
RAW_DIR    = CACHE_DIR / 'raw'
PROF_DIR   = CACHE_DIR / 'profiles'
INDEX_FILE = CACHE_DIR / 'cache_index.json'

REQUEST_INTERVAL = 0.6   # 秒（サ���バー負荷軽減）
MAX_RETRY        = 3
RETRY_WAIT       = 5.0


# ---------------------------------------------------------------------------
# ユーティリティ
# ---------------------------------------------------------------------------
# URLクエリの難易度文字 -> if(?)ブロック文字
# ?1AC00 -> 'A' -> if(a){}  ANOTHER
# ?1XC00 -> 'X' -> if(l){}  LEGGENDARIA（共有ページ）
# ?1AB00 -> 'A' -> if(a){}  LEGGENDARIA（専用ページ）
QUERY_DIFF_MAP = {'A': 'a', 'X': 'l', 'B': 'a', 'D': 'a'}


def textage_to_parts(textage_url: str):
    """
    "22/chrono_p.html?1AC00" -> (ver="22", key="chrono_p", diff_letter="a")
    URLクエリ文字列の2文字目から難易度を判定する。
    """
    m = re.match(r'(\w+)/([^.]+)\.html\?(.+)', textage_url)
    if not m:
        m2 = re.match(r'(\w+)/([^.]+)\.html', textage_url)
        return (m2.group(1), m2.group(2), 'a') if m2 else (None, None, 'a')
    ver, key, query = m.group(1), m.group(2), m.group(3)
    # クエリ2文字目が難易度コード (例: "1AC00" -> 'A')
    diff_char    = query[1].upper() if len(query) >= 2 else 'A'
    diff_letter  = QUERY_DIFF_MAP.get(diff_char, 'a')
    return ver, key, diff_letter


def raw_path(ver, key, diff_letter):
    return RAW_DIR / ver / f"{key}_{diff_letter}.json"


def prof_path(ver, key, diff_letter):
    return PROF_DIR / ver / f"{key}_{diff_letter}.json"


def load_index():
    if INDEX_FILE.exists():
        with open(INDEX_FILE, encoding='utf-8') as f:
            return json.load(f)
    return {'cached': {}}   # {textage_url: {"fetched_at": ...}}


def save_index(index):
    INDEX_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(INDEX_FILE, 'w', encoding='utf-8') as f:
        json.dump(index, f, ensure_ascii=False, indent=2)


def now_iso():
    return datetime.now(timezone.utc).isoformat()


# ---------------------------------------------------------------------------
# 取得・保存
# ---------------------------------------------------------------------------
def save_raw(ver, key, diff_letter, textage_url, sp: dict, cn_events=None,
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


def load_raw(ver, key, diff_letter):
    p = raw_path(ver, key, diff_letter)
    if not p.exists():
        return None
    with open(p, encoding='utf-8') as f:
        d = json.load(f)
    d['sp'] = {int(k): v for k, v in d['sp'].items()}
    d.setdefault('lndef', 384)
    d['ln_map'] = {int(k): v for k, v in d.get('ln_map', {}).items()}
    return d


def save_profile(ver, key, diff_letter, textage_url, song_meta: dict, prof: dict):
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
    parser = argparse.ArgumentParser(description='Beat Seeker 譜面バッチ分析')
    parser.add_argument('--reanalyze', action='store_true',
                        help='rawキャッシュを再利用してprofileのみ再計算')
    parser.add_argument('--refetch', action='store_true',
                        help='キャッシュ��みも含めて全曲再取得')
    parser.add_argument('--limit', type=int, default=0,
                        help='処理する���大曲数（0=全件）')
    parser.add_argument('--ver', type=str, default='',
                        help='指定バージョンフォルダのみ処理 (例: 22)')
    args = parser.parse_args()

    # song_data.json 読み込み
    with open(SONG_DATA, encoding='utf-8') as f:
        songs = json.load(f)['body']

    # Lv11/12 ANOTHER/LEGGENDARIA で textage あり
    targets_raw = [
        s for s in songs
        if s.get('level') in (11, 12)
        and s.get('difficulty') in ('4', '10')
        and s.get('textage')
    ]

    # (textage_url, difficulty) の組み合わせで重複排除
    # → ANOTHER と LEGGENDARIA は別々に処理する
    seen = {}
    for s in targets_raw:
        k = (s["textage"], s.get("difficulty", "4"))
        if k not in seen:
            seen[k] = s
    targets = list(seen.values())

    # バージョンフィルタ
    if args.ver:
        targets = [s for s in targets if s['textage'].startswith(args.ver + '/')]

    total = len(targets)
    print(f"��象曲数: {total}曲")
    if args.limit:
        targets = targets[:args.limit]
        print(f"  --limit {args.limit} を適用")

    index = load_index()

    ok = skip = err = 0
    errors = []

    for i, song in enumerate(targets, 1):
        textage_url = song['textage']
        ver, key, diff_letter_url = textage_to_parts(textage_url)
        if not ver or not key:
            print(f"[{i}/{len(targets)}] URLパース失敗: {textage_url}")
            err += 1
            continue

        # difficulty='10' (LEGGENDARIA) なのに URL が 'a' になる場合は 'l' を使う
        # 共有ページ(?1XC00)は URL から正しく 'l' が取れるが、
        # song_data の difficulty と URL が食い違う場合（同一URL で両難易度を持つ曲）を補正
        if song.get('difficulty') == '10' and diff_letter_url == 'a':
            diff_letter = 'l'
        else:
            diff_letter = diff_letter_url

        title = song.get('title', '')
        bpm   = song.get('bpm', '0')

        # ---------- rawキャッシュ確認 ----------
        cache_key = f"{textage_url}#{diff_letter}"
        already_cached = (not args.refetch) and (cache_key in index.get('cached', {}))

        if args.reanalyze and already_cached:
            raw_data = load_raw(ver, key, diff_letter)
            if raw_data:
                sp   = raw_data['sp']
                cn_events = raw_data.get('cn_events', [])
                # cn_events stored as list of [s, e, k] → convert to tuples
                cn_events = [tuple(x) for x in cn_events]
                raw_lndef = raw_data.get('lndef', 384)
                raw_ln_map = raw_data.get('ln_map', {})
                prof = profile_from_sp(sp, bpm, cn_events, raw_lndef, raw_ln_map)
                save_profile(ver, key, diff_letter, textage_url, song, prof)
                print(f"[{i}/{len(targets)}] 再分析: {title}  ({prof['notes']}notes)")
                ok += 1
                continue

        if already_cached and not args.reanalyze:
            skip += 1
            print(f"[{i}/{len(targets)}] スキップ (キャッシュ済): {title}")
            continue

        # ---------- textage フェッチ ----------
        fetch_result = None
        for attempt in range(1, MAX_RETRY + 1):
            try:
                fetch_result = fetch_raw(textage_url, diff_letter)
                break
            except Exception as e:
                if attempt < MAX_RETRY:
                    print(f"  リトライ {attempt}/{MAX_RETRY}: {e}")
                    time.sleep(RETRY_WAIT)
                else:
                    print(f"[{i}/{len(targets)}] エラー: {title} -- {e}")
                    errors.append({'textage': textage_url, 'title': title, 'error': str(e)})
                    err += 1

        if fetch_result is None:
            time.sleep(REQUEST_INTERVAL)
            continue

        sp, cn_events, lndef, ln_map = fetch_result

        # ---------- 保存 ----------
        save_raw(ver, key, diff_letter, textage_url, sp, cn_events, lndef, ln_map)

        prof = profile_from_sp(sp, bpm, cn_events, lndef, ln_map)
        save_profile(ver, key, diff_letter, textage_url, song, prof)

        index.setdefault('cached', {})[cache_key] = {
            'fetched_at': now_iso(),
            'notes': prof['notes'],
            'tags':  prof['tags'],
        }

        ok += 1
        tag_str = ' '.join(prof['tags'])
        print(f"[{i}/{len(targets)}] {title[:40]:<40}  "
              f"{prof['notes']:4d}notes  eff16={prof['dominant_eff16']:5.0f}  {tag_str}")

        # イ��デックスを定期保存（50件ごと）
        if ok % 50 == 0:
            save_index(index)

        time.sleep(REQUEST_INTERVAL)

    # 最終保存
    save_index(index)

    # ---------- 結果サマリー ----------
    print()
    print("=" * 60)
    print(f"  完了: {ok}曲  スキップ: {skip}曲  エラー: {err}曲")
    print(f"  キャッシュ総数: {len(index.get('cached', {}))}曲")
    if errors:
        print(f"\n  エラー一覧:")
        for e in errors:
            print(f"    {e['textage']}  {e['title']}  -- {e['error']}")
    print("=" * 60)

    # エラーリ���ト���ファイルに書き出し
    if errors:
        err_file = CACHE_DIR / 'errors.json'
        with open(err_file, 'w', encoding='utf-8') as f:
            json.dump(errors, f, ensure_ascii=False, indent=2)
        print(f"  エラー詳細: {err_file}")


if __name__ == '__main__':
    main()
