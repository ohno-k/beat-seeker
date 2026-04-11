"""Fix LEGGENDARIA textage URLs: use correct base URL from titletbl.js dagger entries."""
import json, sys, re, html as html_mod
from collections import defaultdict
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

# Parse titletbl.js
with open('chart_cache/titletbl.js', 'rb') as f:
    raw = f.read()
content = raw.decode('shift-jis', errors='replace')

entries = {}
line_pattern = re.compile(r"'([^']+)'\s*:\s*\[([^\]]+)\]")
for m in line_pattern.finditer(content):
    key = m.group(1)
    arr = m.group(2)
    elements = []
    cur = ''
    in_str = False
    esc = False
    for ch in arr:
        if esc:
            cur += ch; esc = False; continue
        if ch == '\\' and in_str:
            esc = True; cur += ch; continue
        if ch == '"':
            in_str = not in_str; cur += ch; continue
        if ch == ',' and not in_str:
            elements.append(cur.strip()); cur = ''; continue
        cur += ch
    if cur.strip():
        elements.append(cur.strip())
    if len(elements) >= 6:
        try:
            ver_str = elements[0].strip()
            ver = 35 if ver_str == 'SS' else int(ver_str)
        except:
            ver = 0
        title_raw = elements[5]
        title_raw = re.sub(r'\.fontcolor\([^)]*\)', '', title_raw)
        title_raw = re.sub(r'\.bold\(\)', '', title_raw)
        title_raw = re.sub(r'\.italics\(\)', '', title_raw)
        if title_raw.startswith('"') and title_raw.endswith('"'):
            title_raw = title_raw[1:-1]
        title_raw = title_raw.replace('\\"', '"')
        title_raw = html_mod.unescape(title_raw)

        has_dagger = title_raw.endswith('\u2020')
        title_clean = title_raw.rstrip('\u2020').strip()
        entries[key] = {'ver': ver, 'title': title_clean, 'has_dagger': has_dagger, 'key': key}

# Group by title: normal keys and dagger(LEG) keys
title_keys = defaultdict(lambda: {'normal': [], 'leg': []})
for key, info in entries.items():
    if info['has_dagger']:
        title_keys[info['title']]['leg'].append((info['ver'], key))
    else:
        title_keys[info['title']]['normal'].append((info['ver'], key))

# For LEGGENDARIAs: textage uses the SAME base URL as ANOTHER, with ?1X[side]00
# The dagger entry in titletbl is just a list marker, the actual URL uses the normal key
# Check: do dagger entries have different keys from normal?

print("Songs with dagger (LEG) entries in titletbl:")
for title, keys in sorted(title_keys.items()):
    if keys['leg']:
        normal_keys = [k for _, k in keys['normal']]
        leg_keys = [k for _, k in keys['leg']]
        if set(normal_keys) != set(leg_keys):
            print(f"  {title}: normal={normal_keys}, leg={leg_keys}")

# Load song_data
with open('backend/src/main/resources/data/song_data.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

# Build ANOTHER base URL map
ano_base = {}
for s in data['body']:
    if s.get('difficulty') == '4' and s.get('textage'):
        base = s['textage'].split('?')[0]
        ano_base[s['title']] = base

# Fix LEG URLs: use ANOTHER's base URL with ?1X[side]00
fixed = 0
for s in data['body']:
    if s.get('difficulty') != '10':
        continue
    tg = s.get('textage', '')
    if not tg or '?' not in tg:
        continue
    title = s['title']
    leg_base = tg.split('?')[0]
    param = tg.split('?')[1]
    side = param[2] if len(param) >= 3 else 'C'

    if title in ano_base:
        correct_base = ano_base[title]
        correct_tg = f"{correct_base}?1X{side}00"
        if s['textage'] != correct_tg:
            print(f"  FIX: {title}")
            print(f"    old: {s['textage']}")
            print(f"    new: {correct_tg}")
            s['textage'] = correct_tg
            fixed += 1

print(f"\nFixed song_data LEG URLs: {fixed}")

with open('backend/src/main/resources/data/song_data.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
with open('frontend/src/data/song_data.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

# Also fix profiles
import glob
fixed_p = 0
for pf in glob.glob('chart_cache/profiles/**/*.json', recursive=True):
    with open(pf, 'r', encoding='utf-8') as f:
        p = json.load(f)
    if p.get('difficulty') != '10':
        continue
    tg = p.get('textage', '')
    if not tg or '?' not in tg:
        continue
    title = p.get('title', '')
    leg_base = tg.split('?')[0]
    param = tg.split('?')[1]
    side = param[2] if len(param) >= 3 else 'C'

    if title in ano_base:
        correct_base = ano_base[title]
        correct_tg = f"{correct_base}?1X{side}00"
        if p['textage'] != correct_tg:
            p['textage'] = correct_tg
            with open(pf, 'w', encoding='utf-8') as f:
                json.dump(p, f, ensure_ascii=False, indent=2)
            fixed_p += 1

print(f"Fixed profiles LEG URLs: {fixed_p}")

# Verify
for s in data['body']:
    if s['title'] == 'Sigmund':
        print(f"  Sigmund [{s['difficulty']}] textage={s['textage']}")
