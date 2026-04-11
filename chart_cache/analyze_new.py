"""
Analyze newly fetched textage HTML files that aren't in song_data's textage URLs.
Uses the same analysis pipeline as batch_analyze.py.
"""
import json, sys, os, re, glob
from pathlib import Path
from datetime import datetime, timezone

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

from analyze_chart import parse_from_html, profile_from_sp, count_notes

CACHE_DIR = Path('chart_cache')
HTML_DIR = CACHE_DIR / 'html'
RAW_DIR = CACHE_DIR / 'raw'
PROF_DIR = CACHE_DIR / 'profiles'

SP_DIFFICULTIES = [
    ('b', '1', 'BEGINNER'),
    ('n', '2', 'NORMAL'),
    ('k', '3', 'HYPER'),
    ('a', '4', 'ANOTHER'),
    ('l', '10', 'LEGGENDARIA'),
]

DIFF_URL_CHAR = {'b': 'B', 'n': 'N', 'k': 'K', 'a': 'A', 'l': 'L'}

def now_iso():
    return datetime.now(timezone.utc).isoformat()

def has_diff_block(html, diff_letter):
    if diff_letter == 'l':
        return bool(re.search(r'if\s*\(\s*kuro\s*\)\s*\{', html)) or \
               bool(re.search(r'if\s*\(\s*l\s*\)\s*\{', html))
    if diff_letter == 'a':
        return True
    if diff_letter == 'n':
        return bool(re.search(r'if\s*\(\s*n\s*\)\s*\{', html)) or \
               bool(re.search(r'if\s*\(\s*k\s*\)\s*\{', html))
    return bool(re.search(r'(?:else\s+)?if\s*\(\s*' + re.escape(diff_letter) + r'\s*\)\s*\{', html))

def load_html(ver, key):
    p = HTML_DIR / str(ver) / f"{key}.html"
    if not p.exists():
        return None
    with open(p, encoding='utf-8', errors='replace') as f:
        return f.read()

def main():
    # Load missing_textage_urls.json
    with open(CACHE_DIR / 'missing_textage_urls.json', 'r', encoding='utf-8') as f:
        matches = json.load(f)

    # Load song_data for diff_meta (level, notes)
    with open('backend/src/main/resources/data/song_data.json', 'r', encoding='utf-8') as f:
        songs = json.load(f)['body']

    # Build title -> list of song entries
    song_by_title = {}
    for s in songs:
        t = s.get('title', '')
        if t not in song_by_title:
            song_by_title[t] = []
        song_by_title[t].append(s)

    # Check which profiles already exist
    existing_profiles = set()
    for pf in glob.glob('chart_cache/profiles/**/*.json', recursive=True):
        with open(pf, 'r', encoding='utf-8') as f:
            p = json.load(f)
        if p.get('dominant_eff16') and p['dominant_eff16'] > 0:
            existing_profiles.add(p.get('textage', '') or (p.get('title', '') + '#' + str(p.get('difficulty', ''))))

    ok_pages = 0
    ok_diffs = 0
    skip = 0
    errors = 0

    for title, (ver, key) in matches.items():
        ver_str = str(ver)
        html = load_html(ver_str, key)
        if html is None:
            skip += 1
            continue

        # Get song metadata from song_data
        song_entries = song_by_title.get(title, [])
        diff_info = {}  # diff_code -> {level, notes}
        bpm = '0'
        artist = ''
        for s in song_entries:
            dc = s.get('difficulty', '4')
            diff_info[dc] = {'level': s.get('level'), 'notes': s.get('notes', 0)}
            if s.get('bpm'):
                bpm = s['bpm']
            if s.get('artist'):
                artist = s['artist']

        diffs_processed = []
        decoded_notes_map = {}

        for diff_letter, diff_code, diff_name in SP_DIFFICULTIES:
            # Check if profile already exists
            textage_url = f"{ver_str}/{key}.html?1{DIFF_URL_CHAR[diff_letter]}C00"
            prof_file = PROF_DIR / ver_str / f"{key}_{diff_letter}.json"
            if prof_file.exists():
                try:
                    with open(prof_file, 'r', encoding='utf-8') as f:
                        existing = json.load(f)
                    if existing.get('dominant_eff16') and existing['dominant_eff16'] > 0:
                        continue  # Already analyzed
                except:
                    pass

            if not has_diff_block(html, diff_letter):
                continue

            try:
                sp, cn_events, lndef, ln_map = parse_from_html(html, diff_letter)
            except Exception:
                continue

            decoded_notes = count_notes(sp, lndef, ln_map)
            if decoded_notes == 0:
                continue

            # Fallback detection
            if diff_letter in ('l', 'b') and decoded_notes in decoded_notes_map.values():
                continue

            # Notes validation
            dm = diff_info.get(diff_code)
            if dm and dm['notes'] and dm['notes'] > 0:
                expected = dm['notes']
                if abs(decoded_notes - expected) > expected * 0.2:
                    continue

            decoded_notes_map[diff_letter] = decoded_notes

            # Analyze
            prof = profile_from_sp(sp, bpm, cn_events, lndef, ln_map)

            # Override notes with official count
            if dm and dm['notes'] and dm['notes'] > 0:
                prof['notes'] = dm['notes']

            # Save raw
            raw_file = RAW_DIR / ver_str / f"{key}_{diff_letter}.json"
            raw_file.parent.mkdir(parents=True, exist_ok=True)
            raw_data = {
                'textage': textage_url,
                'diff_letter': diff_letter,
                'fetched_at': now_iso(),
                'sp': {str(k): v for k, v in sp.items()},
                'cn_events': [list(ev) for ev in cn_events] if cn_events else [],
                'lndef': lndef,
                'ln_map': {str(k): v for k, v in (ln_map or {}).items()},
            }
            with open(raw_file, 'w', encoding='utf-8') as f:
                json.dump(raw_data, f, ensure_ascii=False)

            # Save profile
            prof_file.parent.mkdir(parents=True, exist_ok=True)
            profile_data = {
                'textage': textage_url,
                'analyzed_at': now_iso(),
                'title': title,
                'artist': artist,
                'bpm_raw': bpm,
                'level': dm['level'] if dm else None,
                'difficulty': diff_code,
                **prof,
            }
            with open(prof_file, 'w', encoding='utf-8') as f:
                json.dump(profile_data, f, ensure_ascii=False, indent=2)

            diffs_processed.append(f"{diff_name}({prof['notes']})")
            ok_diffs += 1

        if diffs_processed:
            ok_pages += 1
            if ok_pages <= 20 or ok_pages % 50 == 0:
                print(f"[{ok_pages}] {title[:40]:<40}  {' '.join(diffs_processed)}")
        else:
            skip += 1

    print(f'\nDone: {ok_pages} pages analyzed, {ok_diffs} diffs, {skip} skipped')

if __name__ == '__main__':
    main()
