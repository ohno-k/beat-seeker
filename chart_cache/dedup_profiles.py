"""
Remove duplicate profiles. For each title+difficulty combo with multiple profiles,
keep the one with the best analysis data. Delete the rest.
"""
import json, sys, glob, os
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

profiles = glob.glob('chart_cache/profiles/**/*.json', recursive=True)

# Group by title+difficulty
by_key = {}
for pf in profiles:
    with open(pf, 'r', encoding='utf-8') as f:
        p = json.load(f)
    key = (p.get('title', ''), p.get('difficulty', '4'))
    has_analysis = bool(p.get('dominant_eff16') and p['dominant_eff16'] > 0)
    notes = p.get('notes', 0) or 0
    if key not in by_key:
        by_key[key] = []
    by_key[key].append((pf, has_analysis, notes, p))

deleted = 0
for key, items in by_key.items():
    if len(items) <= 1:
        continue

    # Sort: analyzed first, then by notes count (higher = better)
    items.sort(key=lambda x: (x[1], x[2]), reverse=True)

    # Keep first, delete rest
    for pf, _, _, _ in items[1:]:
        os.remove(pf)
        deleted += 1

print(f'Deleted {deleted} duplicate profiles')

# Verify
remaining = glob.glob('chart_cache/profiles/**/*.json', recursive=True)
print(f'Remaining profiles: {len(remaining)}')
