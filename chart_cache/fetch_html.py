"""
Fetch textage HTML pages for matched songs.
URL pattern: https://textage.cc/score/{ver}/{key}.html
Saved to: chart_cache/html/{ver}/{key}.html
"""
import json, sys, os, time, urllib.request
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

REQUEST_INTERVAL = 0.6

with open('chart_cache/missing_textage_urls.json', 'r', encoding='utf-8') as f:
    matches = json.load(f)

# Filter to only those needing fetch
need_fetch = []
for title, (ver, key) in matches.items():
    ver_str = str(ver)
    dir_path = os.path.join('chart_cache', 'html', ver_str)
    html_path = os.path.join(dir_path, f'{key}.html')
    if not os.path.exists(html_path):
        need_fetch.append((title, ver_str, key))

print(f'Need to fetch: {len(need_fetch)} pages')

success = 0
fail = 0
fail_details = []

for i, (title, ver, key) in enumerate(need_fetch):
    url = f'https://textage.cc/score/{ver}/{key}.html'
    dir_path = os.path.join('chart_cache', 'html', ver)
    os.makedirs(dir_path, exist_ok=True)
    html_path = os.path.join(dir_path, f'{key}.html')

    try:
        req = urllib.request.Request(url, headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
            'Referer': 'https://textage.cc/score/index.html'
        })
        resp = urllib.request.urlopen(req, timeout=15)
        data = resp.read()
        with open(html_path, 'wb') as f:
            f.write(data)
        success += 1
    except Exception as e:
        fail += 1
        fail_details.append((title, key, str(e)))

    if (i + 1) % 50 == 0:
        print(f'  Progress: {i+1}/{len(need_fetch)} (ok={success}, fail={fail})')

    time.sleep(REQUEST_INTERVAL)

print(f'\nDone: {success} fetched, {fail} failed out of {len(need_fetch)}')

if fail_details:
    print(f'\nFailed ({len(fail_details)}):')
    for title, key, err in fail_details[:30]:
        print(f'  {key}: {err} ({title})')
    if len(fail_details) > 30:
        print(f'  ... and {len(fail_details) - 30} more')
