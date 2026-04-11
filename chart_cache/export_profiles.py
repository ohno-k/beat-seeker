"""Export all profiles to a single JSON array for DB import via API."""
import json, glob, sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

def main():
    profiles = []
    skipped = 0
    for pf in sorted(glob.glob('chart_cache/profiles/**/*.json', recursive=True)):
        try:
            with open(pf, 'r', encoding='utf-8') as f:
                p = json.load(f)
            if not p.get('textage'):
                skipped += 1
                continue
            profiles.append(p)
        except Exception as e:
            print(f'  Skip {pf}: {e}')
            skipped += 1

    out = 'chart_cache/all_profiles.json'
    with open(out, 'w', encoding='utf-8') as f:
        json.dump(profiles, f, ensure_ascii=False)

    size_mb = len(json.dumps(profiles, ensure_ascii=False).encode('utf-8')) / 1024 / 1024
    print(f'Exported {len(profiles)} profiles to {out} ({size_mb:.1f} MB), skipped {skipped}')


if __name__ == '__main__':
    main()
