"""Add measure_notes_kbd and measure_notes_scr to all profiles by re-parsing HTML."""
import json, glob, sys, os, re
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
from analyze_chart import parse_from_html, decode_measure, get_ln

HTML_DIR = Path('chart_cache/html')
DIFF_LETTER = {'1': 'b', '2': 'n', '3': 'k', '4': 'a', '10': 'l'}

def extract_measure_split(sp, lndef, ln_map):
    """Extract per-measure kbd/scr note counts from parsed sp data."""
    sorted_measures = sorted(sp.keys())
    if not sorted_measures:
        return [], []

    kbd_list = {}
    scr_list = {}
    for mes in sorted_measures:
        sdd = sp.get(mes)
        if not sdd:
            kbd_list[mes] = 0
            scr_list[mes] = 0
            continue
        ln_n = get_ln(mes, lndef, ln_map)
        decoded = decode_measure(sdd, ln_n)
        kbd_list[mes] = sum(1 for (_, k) in decoded if k != 0)
        scr_list[mes] = sum(1 for (_, k) in decoded if k == 0)

    min_m = min(sorted_measures)
    max_m = max(sorted_measures)
    kbd = [kbd_list.get(m, 0) for m in range(min_m, max_m + 1)]
    scr = [scr_list.get(m, 0) for m in range(min_m, max_m + 1)]
    return kbd, scr


def main():
    # Load missing_textage_urls for songs not matched by profile textage
    with open('chart_cache/missing_textage_urls.json', 'r', encoding='utf-8') as f:
        matches = json.load(f)

    updated = 0
    skipped = 0
    no_html = 0

    profiles = sorted(glob.glob('chart_cache/profiles/**/*.json', recursive=True))
    total = len(profiles)

    for idx, pf in enumerate(profiles):
        with open(pf, 'r', encoding='utf-8') as f:
            p = json.load(f)

        # Already has split data?
        if p.get('measure_notes_kbd') and len(p['measure_notes_kbd']) > 0:
            skipped += 1
            continue

        title = p.get('title', '')
        diff = p.get('difficulty', '4')
        dl = DIFF_LETTER.get(diff, 'a')
        tg = p.get('textage', '')

        # Find HTML path from textage
        html_path = None
        if tg and '?' in tg:
            base = tg.split('?')[0]  # e.g. "21/sigmund.html"
            html_path = HTML_DIR / base

        if html_path is None or not html_path.exists():
            # Try from matches
            if title in matches:
                ver, key = matches[title]
                html_path = HTML_DIR / str(ver) / f'{key}.html'

        if html_path is None or not html_path.exists():
            no_html += 1
            continue

        try:
            with open(html_path, 'r', encoding='utf-8', errors='replace') as f:
                html = f.read()
            sp, cn, lndef, ln_map = parse_from_html(html, dl)
            kbd, scr = extract_measure_split(sp, lndef, ln_map)

            if len(kbd) > 0:
                p['measure_notes_kbd'] = kbd
                p['measure_notes_scr'] = scr
                with open(pf, 'w', encoding='utf-8') as f:
                    json.dump(p, f, ensure_ascii=False, indent=2)
                updated += 1
            else:
                skipped += 1
        except Exception as e:
            skipped += 1

        if (idx + 1) % 1000 == 0:
            print(f'  Progress: {idx + 1}/{total} (updated={updated})')

    print(f'\nDone: updated={updated}, skipped={skipped}, no_html={no_html}, total={total}')


if __name__ == '__main__':
    main()
