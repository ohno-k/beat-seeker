"""
Inherit analysis data from analyzed profiles to basic-only profiles of the same song.
For each basic profile, find the nearest analyzed difficulty of the same song
and copy analysis fields with proportional scaling for density/count fields.
"""
import json, sys, glob, os
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ANALYSIS_FIELDS = [
    'dominant_eff16', 'scratch_pct', 'chord_pct', 'cn_notes',
    'is_soflan', 'bpm_main', 'tags'
]

# Fields that should be scaled by notes ratio
SCALE_FIELDS = {'dominant_eff16', 'cn_notes'}
# Fields that should be copied as-is
COPY_FIELDS = {'scratch_pct', 'chord_pct', 'is_soflan', 'bpm_main', 'bpm_raw', 'tags'}

DIFF_ORDER = {'1': 0, '2': 1, '3': 2, '4': 3, '10': 4}

def has_analysis(p):
    return p.get('dominant_eff16') and p['dominant_eff16'] > 0

def main():
    profiles = glob.glob('chart_cache/profiles/**/*.json', recursive=True)

    # Group by title
    by_title = {}
    for pf in profiles:
        with open(pf, 'r', encoding='utf-8') as f:
            p = json.load(f)
        p['_path'] = pf
        title = p.get('title', '')
        if title not in by_title:
            by_title[title] = []
        by_title[title].append(p)

    updated = 0
    no_source = 0

    for title, profs in by_title.items():
        analyzed = [p for p in profs if has_analysis(p)]
        basic = [p for p in profs if not has_analysis(p)]

        if not basic:
            continue
        if not analyzed:
            no_source += len(basic)
            continue

        for bp in basic:
            bp_diff = DIFF_ORDER.get(bp.get('difficulty', '4'), 3)
            bp_notes = bp.get('notes', 0) or 0

            # Find nearest analyzed difficulty
            best = None
            best_dist = 999
            for ap in analyzed:
                ap_diff = DIFF_ORDER.get(ap.get('difficulty', '4'), 3)
                dist = abs(bp_diff - ap_diff)
                if dist < best_dist:
                    best_dist = dist
                    best = ap

            if best is None:
                no_source += 1
                continue

            source_notes = best.get('notes', 0) or 1
            ratio = bp_notes / source_notes if source_notes > 0 and bp_notes > 0 else 1.0

            # Copy/scale fields
            for field in COPY_FIELDS:
                if field in best and best[field] is not None:
                    bp[field] = best[field]

            for field in SCALE_FIELDS:
                if field in best and best[field] is not None:
                    if field == 'cn_notes':
                        bp[field] = max(0, round(best[field] * ratio))
                    else:
                        bp[field] = round(best[field] * ratio, 2)

            # Save
            pf_path = bp['_path']
            save_data = {k: v for k, v in bp.items() if k != '_path'}
            with open(pf_path, 'w', encoding='utf-8') as f:
                json.dump(save_data, f, ensure_ascii=False, indent=2)
            updated += 1

    print(f'Updated: {updated}')
    print(f'No source (song has no analysis at all): {no_source}')

if __name__ == '__main__':
    main()
