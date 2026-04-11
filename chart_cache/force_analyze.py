"""
Force analyze remaining songs that have HTML but no analysis data.
Directly writes analyzed profiles, overwriting basic ones.
"""
import json, sys, glob, os, re
from pathlib import Path
from datetime import datetime, timezone
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
from analyze_chart import parse_from_html, profile_from_sp, count_notes

PROF_DIR = Path('chart_cache/profiles')
HTML_DIR = Path('chart_cache/html')

SP_DIFFICULTIES = [
    ('b', '1', 'BEGINNER'),
    ('n', '2', 'NORMAL'),
    ('k', '3', 'HYPER'),
    ('a', '4', 'ANOTHER'),
    ('l', '10', 'LEGGENDARIA'),
]

DIFF_URL_CHAR = {'b': 'B', 'n': 'N', 'k': 'K', 'a': 'A', 'l': 'L'}

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

def main():
    # Load song_data
    with open('backend/src/main/resources/data/song_data.json', 'r', encoding='utf-8') as f:
        songs = json.load(f)['body']

    sd_by_title = {}
    for s in songs:
        t = s['title']
        if t not in sd_by_title:
            sd_by_title[t] = {}
        sd_by_title[t][s.get('difficulty', '4')] = s

    # Find songs without analysis
    profiles = glob.glob('chart_cache/profiles/**/*.json', recursive=True)
    songs_with_analysis = set()
    songs_without_analysis = set()
    profile_paths = {}  # (title, difficulty) -> path

    for pf in profiles:
        with open(pf, 'r', encoding='utf-8') as f:
            p = json.load(f)
        title = p.get('title', '')
        diff = p.get('difficulty', '4')
        has = bool(p.get('dominant_eff16') and p['dominant_eff16'] > 0)
        if has:
            songs_with_analysis.add(title)
        else:
            songs_without_analysis.add(title)
        key = (title, diff)
        if key not in profile_paths or has:
            profile_paths[key] = (pf, has)

    unanalyzed_songs = songs_without_analysis - songs_with_analysis
    print(f'Songs without any analysis: {len(unanalyzed_songs)}')

    # Load matches
    with open('chart_cache/missing_textage_urls.json', 'r', encoding='utf-8') as f:
        matches = json.load(f)

    ok = 0
    for title in sorted(unanalyzed_songs):
        if title not in matches:
            continue

        ver, key = matches[title]
        ver_str = str(ver)
        html_path = HTML_DIR / ver_str / f'{key}.html'
        if not html_path.exists():
            continue

        with open(html_path, 'r', encoding='utf-8', errors='replace') as f:
            html = f.read()

        song_entries = sd_by_title.get(title, {})
        bpm = '0'
        artist = ''
        for dc, s in song_entries.items():
            if s.get('bpm'): bpm = s['bpm']
            if s.get('artist'): artist = s['artist']

        analyzed_any = False
        for diff_letter, diff_code, diff_name in SP_DIFFICULTIES:
            if not has_diff_block(html, diff_letter):
                continue
            try:
                sp, cn, lndef, ln_map = parse_from_html(html, diff_letter)
                decoded_notes = count_notes(sp, lndef, ln_map)
            except:
                continue
            if decoded_notes == 0:
                continue

            # Validate against song_data
            sd_entry = song_entries.get(diff_code)
            if sd_entry and sd_entry.get('notes', 0) > 0:
                expected = sd_entry['notes']
                pct = abs(decoded_notes - expected) / expected
                if pct > 0.20:
                    continue

            prof = profile_from_sp(sp, bpm, cn, lndef, ln_map)
            if sd_entry and sd_entry.get('notes', 0) > 0:
                prof['notes'] = sd_entry['notes']

            # Find the existing profile path or create new
            pf_key = (title, diff_code)
            if pf_key in profile_paths:
                save_path = profile_paths[pf_key][0]
            else:
                save_path = str(PROF_DIR / ver_str / f'{key}_{diff_letter}.json')

            data = {
                'textage': f'{ver_str}/{key}.html?1{DIFF_URL_CHAR[diff_letter]}C00',
                'analyzed_at': datetime.now(timezone.utc).isoformat(),
                'title': title,
                'artist': artist,
                'bpm_raw': bpm,
                'level': sd_entry.get('level') if sd_entry else None,
                'difficulty': diff_code,
                **prof,
            }
            os.makedirs(os.path.dirname(save_path), exist_ok=True)
            with open(save_path, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False, indent=2)

            analyzed_any = True

        if analyzed_any:
            ok += 1
            if ok <= 30:
                print(f'  Analyzed: {title}')

    print(f'\nForce-analyzed: {ok} songs')

    # Now run inheritance for remaining basic profiles
    profiles2 = glob.glob('chart_cache/profiles/**/*.json', recursive=True)
    by_title = {}
    for pf in profiles2:
        with open(pf, 'r', encoding='utf-8') as f:
            p = json.load(f)
        p['_path'] = pf
        t = p.get('title', '')
        if t not in by_title:
            by_title[t] = []
        by_title[t].append(p)

    inherited = 0
    DIFF_ORDER = {'1': 0, '2': 1, '3': 2, '4': 3, '10': 4}
    for title, profs in by_title.items():
        analyzed = [p for p in profs if p.get('dominant_eff16') and p['dominant_eff16'] > 0]
        basic = [p for p in profs if not (p.get('dominant_eff16') and p['dominant_eff16'] > 0)]
        if not basic or not analyzed:
            continue
        for bp in basic:
            bp_diff = DIFF_ORDER.get(bp.get('difficulty', '4'), 3)
            bp_notes = bp.get('notes', 0) or 0
            best = min(analyzed, key=lambda ap: abs(DIFF_ORDER.get(ap.get('difficulty', '4'), 3) - bp_diff))
            source_notes = best.get('notes', 0) or 1
            ratio = bp_notes / source_notes if source_notes > 0 and bp_notes > 0 else 1.0
            for field in ['scratch_pct', 'chord_pct', 'is_soflan', 'bpm_main', 'bpm_raw', 'tags']:
                if field in best and best[field] is not None:
                    bp[field] = best[field]
            for field in ['dominant_eff16', 'cn_notes']:
                if field in best and best[field] is not None:
                    if field == 'cn_notes':
                        bp[field] = max(0, round(best[field] * ratio))
                    else:
                        bp[field] = round(best[field] * ratio, 2)
            save_data = {k: v for k, v in bp.items() if k != '_path'}
            with open(bp['_path'], 'w', encoding='utf-8') as f:
                json.dump(save_data, f, ensure_ascii=False, indent=2)
            inherited += 1

    print(f'Inherited: {inherited}')

if __name__ == '__main__':
    main()
