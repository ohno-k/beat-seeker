"""Fix duplicate textage URLs: find correct keys for original/remix pairs."""
import json, sys, re, html as html_mod
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
        title_raw = title_raw.rstrip('\u2020').strip()
        entries[key] = {'ver': ver, 'title': title_raw, 'key': key}

# Build title -> [(ver, key)] lookup
title_to_keys = {}
for key, info in entries.items():
    t = info['title']
    if t not in title_to_keys:
        title_to_keys[t] = []
    title_to_keys[t].append((info['ver'], key))

# Problem songs from the duplicate analysis
problem_songs = [
    'Abyss', 'Abyss -The Heavens Remix-',
    'I Was The One', "I Was The One (80's EUROBEAT STYLE)",
    'Cyber Force', 'Cyber Force -DJ Yoshitaka Remix-',
    "Dazzlin' Darlin", "Dazzlin' Darlin-秋葉工房mix-",
    'Mermaid girl', 'Mermaid girl-秋葉工房 MIX-',
    'CaptivAte～裁き～', 'CaptivAte～裁き～(SUBLIME TECHNO MIX)',
    'thunder', 'thunder HOUSE NATION Remix',
    'セピアの軌跡 ft. 天宮みや(少女フラクタル)', '果たせぬ約束 ft. 小林マナ',
]

for title in problem_songs:
    if title in title_to_keys:
        keys = title_to_keys[title]
        print(f'{title}: {[(v, k) for v, k in keys]}')
    else:
        # fuzzy search
        matches = []
        for key, info in entries.items():
            if title.lower().replace(' ', '') in info['title'].lower().replace(' ', ''):
                matches.append((info['ver'], key, info['title']))
        if matches:
            print(f'{title}: FUZZY -> {matches[:3]}')
        else:
            print(f'{title}: NOT FOUND')
