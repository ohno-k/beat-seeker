"""
Fix analysis for songs with non-numeric BPM (※) by extracting BPM from textage HTML.
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
SP_DIFFS = [('a','4'), ('k','3'), ('n','2'), ('l','10'), ('b','1')]

def has_diff_block(html, dl):
    if dl == 'l':
        return bool(re.search(r'if\s*\(\s*kuro\s*\)\s*\{', html)) or \
               bool(re.search(r'if\s*\(\s*l\s*\)\s*\{', html))
    if dl == 'a': return True
    if dl == 'n':
        return bool(re.search(r'if\s*\(\s*n\s*\)\s*\{', html)) or \
               bool(re.search(r'if\s*\(\s*k\s*\)\s*\{', html))
    return bool(re.search(r'(?:else\s+)?if\s*\(\s*' + re.escape(dl) + r'\s*\)\s*\{', html))

def extract_bpm_from_html(html):
    """Extract max BPM from HTML bpm="..." fields."""
    bpm_fields = re.findall(r'bpm="([^"]+)"', html)
    all_nums = []
    for field in bpm_fields:
        nums = re.findall(r'\d+', field)
        all_nums.extend(int(n) for n in nums)
    return max(all_nums) if all_nums else 0

def main():
    # Load song_data
    with open('backend/src/main/resources/data/song_data.json', 'r', encoding='utf-8') as f:
        songs = json.load(f)['body']

    # Find songs with non-numeric BPM
    bad_bpm_songs = set()
    sd_by_title = {}
    for s in songs:
        t = s['title']
        if t not in sd_by_title: sd_by_title[t] = {}
        sd_by_title[t][s.get('difficulty','4')] = s
        nums = re.findall(r'\d+', s.get('bpm', ''))
        if not nums:
            bad_bpm_songs.add(t)

    print(f'Songs with bad BPM: {len(bad_bpm_songs)}')

    # Load matches
    with open('chart_cache/missing_textage_urls.json', 'r', encoding='utf-8') as f:
        matches = json.load(f)

    # Also check if these songs have HTML from the original batch_analyze
    # (they might have textage URLs in song_data)
    for s in songs:
        if s['title'] in bad_bpm_songs and s.get('textage'):
            m = re.match(r'(\w+)/([^.]+)\.html', s['textage'])
            if m and s['title'] not in matches:
                matches[s['title']] = [m.group(1), m.group(2)]

    # Process each bad-BPM song
    analyzed_count = 0
    for title in sorted(bad_bpm_songs):
        if title not in matches:
            print(f'  {title}: no textage match')
            continue

        ver, key = matches[title]
        ver_str = str(ver)
        html_path = HTML_DIR / ver_str / f'{key}.html'
        if not html_path.exists():
            print(f'  {title}: no HTML at {html_path}')
            continue

        with open(html_path, 'r', encoding='utf-8', errors='replace') as f:
            html = f.read()

        bpm_from_html = extract_bpm_from_html(html)
        if bpm_from_html == 0:
            print(f'  {title}: no BPM in HTML')
            continue

        bpm = str(bpm_from_html)
        song_entries = sd_by_title.get(title, {})
        artist = ''
        for dc, s in song_entries.items():
            if s.get('artist'): artist = s['artist']; break

        # Find existing profiles
        existing_by_diff = {}
        for pf in glob.glob('chart_cache/profiles/**/*.json', recursive=True):
            with open(pf, 'r', encoding='utf-8') as f:
                p = json.load(f)
            if p.get('title') == title:
                dc = p.get('difficulty', '4')
                existing_by_diff[dc] = pf

        # Try to analyze each difficulty
        analyzed_diffs = {}
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
                if pct > 0.25:
                    continue

            prof = profile_from_sp(sp, bpm, cn, lndef, ln_map)
            if sd_entry and sd_entry.get('notes', 0) > 0:
                prof['notes'] = sd_entry['notes']

            analyzed_diffs[dc] = prof

            # Save
            if dc in existing_by_diff:
                save_path = existing_by_diff[dc]
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
            diffs_str = ', '.join(f'{dc}' for dc in analyzed_diffs.keys())
            print(f'  {title}: BPM={bpm} analyzed [{diffs_str}]')

            # Inherit to remaining
            for dc, pf_path in existing_by_diff.items():
                if dc in analyzed_diffs:
                    continue

                with open(pf_path, 'r', encoding='utf-8') as f:
                    bp = json.load(f)
                if bp.get('dominant_eff16') and bp['dominant_eff16'] > 0:
                    continue

                bp_order = DIFF_ORDER.get(dc, 3)
                best_dc = min(analyzed_diffs.keys(),
                              key=lambda d: abs(DIFF_ORDER.get(d, 3) - bp_order))
                best_prof = analyzed_diffs[best_dc]

                bp_notes = bp.get('notes', 0) or 0
                src_notes = best_prof.get('notes', 0) or 1
                ratio = bp_notes / src_notes if src_notes > 0 and bp_notes > 0 else 1.0

                for field in ['scratch_pct', 'chord_pct', 'is_soflan', 'bpm_main', 'bpm_raw', 'tags']:
                    if field in best_prof and best_prof[field] is not None:
                        bp[field] = best_prof[field]
                for field in ['dominant_eff16', 'cn_notes']:
                    if field in best_prof and best_prof[field] is not None:
                        if field == 'cn_notes':
                            bp[field] = max(0, round(best_prof[field] * ratio))
                        else:
                            bp[field] = round(best_prof[field] * ratio, 2)

                with open(pf_path, 'w', encoding='utf-8') as f:
                    json.dump(bp, f, ensure_ascii=False, indent=2)

    print(f'\nAnalyzed: {analyzed_count} songs')

if __name__ == '__main__':
    main()
