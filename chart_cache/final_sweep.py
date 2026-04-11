"""
Final sweep: for every profile without analysis data,
1. Try to parse its HTML (using matching textage key)
2. If parsing succeeds for at least one diff, save and inherit to other diffs
"""
import json, sys, glob, os, re
from pathlib import Path
from datetime import datetime, timezone
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
from analyze_chart import parse_from_html, profile_from_sp, count_notes

PROF_DIR = Path('chart_cache/profiles')
HTML_DIR = Path('chart_cache/html')
DIFF_URL_CHAR = {'b': 'B', 'n': 'N', 'k': 'K', 'a': 'A', 'l': 'L'}
DIFF_ORDER = {'1': 0, '2': 1, '3': 2, '4': 3, '10': 4}
SP_DIFFS = [('b','1'), ('n','2'), ('k','3'), ('a','4'), ('l','10')]

def has_diff_block(html, dl):
    if dl == 'l':
        return bool(re.search(r'if\s*\(\s*kuro\s*\)\s*\{', html)) or \
               bool(re.search(r'if\s*\(\s*l\s*\)\s*\{', html))
    if dl == 'a': return True
    if dl == 'n':
        return bool(re.search(r'if\s*\(\s*n\s*\)\s*\{', html)) or \
               bool(re.search(r'if\s*\(\s*k\s*\)\s*\{', html))
    return bool(re.search(r'(?:else\s+)?if\s*\(\s*' + re.escape(dl) + r'\s*\)\s*\{', html))

def main():
    # Load song_data
    with open('backend/src/main/resources/data/song_data.json', 'r', encoding='utf-8') as f:
        songs = json.load(f)['body']
    sd_by_title = {}
    for s in songs:
        t = s['title']
        if t not in sd_by_title: sd_by_title[t] = {}
        sd_by_title[t][s.get('difficulty','4')] = s

    # Load matches
    with open('chart_cache/missing_textage_urls.json', 'r', encoding='utf-8') as f:
        matches = json.load(f)

    # Read all profiles
    all_profiles = []
    for pf in glob.glob('chart_cache/profiles/**/*.json', recursive=True):
        with open(pf, 'r', encoding='utf-8') as f:
            p = json.load(f)
        p['_path'] = pf
        all_profiles.append(p)

    # Group by title
    by_title = {}
    for p in all_profiles:
        t = p.get('title', '')
        if t not in by_title: by_title[t] = []
        by_title[t].append(p)

    # Find songs where NO diff has analysis
    no_analysis_songs = []
    for title, profs in by_title.items():
        if not any(p.get('dominant_eff16') and p['dominant_eff16'] > 0 for p in profs):
            no_analysis_songs.append(title)

    print(f'Songs with zero analysis: {len(no_analysis_songs)}')

    analyzed_count = 0
    inherited_count = 0

    for title in sorted(no_analysis_songs):
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

        # Map existing profiles by difficulty
        existing_by_diff = {}
        for p in by_title.get(title, []):
            dc = p.get('difficulty', '4')
            existing_by_diff[dc] = p

        # Try to analyze each difficulty from HTML
        analyzed_diffs = {}  # diff_code -> prof
        for dl, dc in SP_DIFFS:
            if not has_diff_block(html, dl):
                continue
            try:
                sp, cn, lndef, ln_map = parse_from_html(html, dl)
                decoded = count_notes(sp, lndef, ln_map)
            except:
                continue
            if decoded == 0:
                continue

            sd_entry = song_entries.get(dc)
            if sd_entry and sd_entry.get('notes', 0) > 0:
                expected = sd_entry['notes']
                pct = abs(decoded - expected) / expected
                if pct > 0.25:  # slightly relaxed threshold
                    continue

            prof = profile_from_sp(sp, bpm, cn, lndef, ln_map)
            if sd_entry and sd_entry.get('notes', 0) > 0:
                prof['notes'] = sd_entry['notes']

            analyzed_diffs[dc] = prof

            # Save to existing profile path
            if dc in existing_by_diff:
                save_path = existing_by_diff[dc]['_path']
            else:
                save_path = str(PROF_DIR / ver_str / f'{key}_{dl}.json')

            data = {
                'textage': f'{ver_str}/{key}.html?1{DIFF_URL_CHAR[dl]}C00',
                'analyzed_at': datetime.now(timezone.utc).isoformat(),
                'title': title,
                'artist': artist,
                'bpm_raw': bpm,
                'level': sd_entry.get('level') if sd_entry else None,
                'difficulty': dc,
                **prof,
            }
            os.makedirs(os.path.dirname(save_path), exist_ok=True)
            with open(save_path, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False, indent=2)

        if analyzed_diffs:
            analyzed_count += 1
            diffs_str = ', '.join(f'{dc}({p["notes"]})' for dc, p in analyzed_diffs.items())
            print(f'  Analyzed: {title} [{diffs_str}]')

            # Inherit to remaining basic profiles
            for dc, ep in existing_by_diff.items():
                if dc in analyzed_diffs:
                    continue  # already analyzed
                if ep.get('dominant_eff16') and ep['dominant_eff16'] > 0:
                    continue  # already has analysis

                # Find nearest analyzed diff
                bp_order = DIFF_ORDER.get(dc, 3)
                best_dc = min(analyzed_diffs.keys(),
                              key=lambda d: abs(DIFF_ORDER.get(d, 3) - bp_order))
                best_prof = analyzed_diffs[best_dc]

                bp_notes = ep.get('notes', 0) or 0
                src_notes = best_prof.get('notes', 0) or 1
                ratio = bp_notes / src_notes if src_notes > 0 and bp_notes > 0 else 1.0

                for field in ['scratch_pct', 'chord_pct', 'is_soflan', 'bpm_main', 'bpm_raw', 'tags']:
                    if field in best_prof and best_prof[field] is not None:
                        ep[field] = best_prof[field]
                for field in ['dominant_eff16', 'cn_notes']:
                    if field in best_prof and best_prof[field] is not None:
                        if field == 'cn_notes':
                            ep[field] = max(0, round(best_prof[field] * ratio))
                        else:
                            ep[field] = round(best_prof[field] * ratio, 2)

                save_data = {k: v for k, v in ep.items() if k != '_path'}
                with open(ep['_path'], 'w', encoding='utf-8') as f:
                    json.dump(save_data, f, ensure_ascii=False, indent=2)
                inherited_count += 1

    print(f'\nAnalyzed: {analyzed_count} songs')
    print(f'Inherited: {inherited_count} profiles')

if __name__ == '__main__':
    main()
