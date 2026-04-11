import json, sys, re, unicodedata, glob
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

with open('chart_cache/titletbl.js', 'rb') as f:
    raw_bytes = f.read()
try:
    content = raw_bytes.decode('utf-8')
except UnicodeDecodeError:
    content = raw_bytes.decode('shift-jis', errors='replace')

# Format: 'key':[ver,id,opt,"GENRE","ARTIST","TITLE","SUBTITLE"],
# TITLEINDEX=5
entries = {}
line_pattern = re.compile(r"'([^']+)'\s*:\s*\[([^\]]+)\]")

for m in line_pattern.finditer(content):
    key = m.group(1)
    array_str = m.group(2)

    # Parse array elements
    elements = []
    current = ''
    in_string = False
    escape_next = False
    for ch in array_str:
        if escape_next:
            current += ch
            escape_next = False
            continue
        if ch == '\\' and in_string:
            escape_next = True
            current += ch
            continue
        if ch == '"':
            in_string = not in_string
            current += ch
            continue
        if ch == ',' and not in_string:
            elements.append(current.strip())
            current = ''
            continue
        current += ch
    if current.strip():
        elements.append(current.strip())

    # ver=index0, title=index5
    if len(elements) >= 6:
        try:
            ver = int(elements[0])
        except ValueError:
            ver = 0
        title_raw = elements[5]
        if title_raw.startswith('"') and title_raw.endswith('"'):
            title_raw = title_raw[1:-1]
        title_raw = title_raw.replace('\\"', '"')
        entries[key] = {'ver': ver, 'title': title_raw, 'key': key}

print(f'Parsed {len(entries)} entries from titletbl.js')

# Show a few
for i, (k, v) in enumerate(list(entries.items())[:5]):
    print(f'  {k}: ver={v["ver"]} title={v["title"]}')

# Load unmatched songs
with open('backend/src/main/resources/data/song_data.json', 'r', encoding='utf-8') as f:
    raw = json.load(f)
song_data = raw['body']

profiles = glob.glob('chart_cache/profiles/**/*.json', recursive=True)
songs_with_analysis = set()
songs_without_analysis = set()
for pf in profiles:
    with open(pf, 'r', encoding='utf-8') as f:
        p = json.load(f)
    title = p.get('title', '')
    if p.get('dominant_eff16') and p['dominant_eff16'] > 0:
        songs_with_analysis.add(title)
    else:
        songs_without_analysis.add(title)

purely_unanalyzed = songs_without_analysis - songs_with_analysis

with open('chart_cache/missing_textage_urls.json', 'r', encoding='utf-8') as f:
    already_matched = json.load(f)

unmatched = [t for t in sorted(purely_unanalyzed) if t not in already_matched]
print(f'Still unmatched: {len(unmatched)}')

# Normalize function
def normalize(s):
    s = s.lower()
    s = unicodedata.normalize('NFKC', s)
    s = re.sub(r'[\s\u3000]+', '', s)
    s = s.replace('\u301c', '~').replace('\uff5e', '~')
    s = s.replace('\u2212', '-').replace('\u2013', '-').replace('\u2014', '-').replace('\u2015', '-')
    return s

# Build normalized lookup
textage_by_norm = {}
for key, info in entries.items():
    n = normalize(info['title'])
    if n not in textage_by_norm:
        textage_by_norm[n] = []
    textage_by_norm[n].append(info)

# Exact normalized match
new_matches = {}
still_unmatched = []
for title in unmatched:
    n = normalize(title)
    if n in textage_by_norm:
        candidates = textage_by_norm[n]
        best = max(candidates, key=lambda x: x['ver'])
        new_matches[title] = [best['ver'], best['key']]
    else:
        still_unmatched.append(title)

print(f'New normalized matches: {len(new_matches)}')
print(f'Still unmatched after normalization: {len(still_unmatched)}')

# Fuzzy: strip all punctuation/symbols
def strip_punct(s):
    return re.sub(r'[^a-z0-9\u3040-\u309f\u30a0-\u30ff\u4e00-\u9fff]', '', normalize(s))

textage_by_stripped = {}
for key, info in entries.items():
    sp = strip_punct(info['title'])
    if sp:
        if sp not in textage_by_stripped:
            textage_by_stripped[sp] = []
        textage_by_stripped[sp].append(info)

fuzzy_matches = {}
final_unmatched = []
for title in still_unmatched:
    sp = strip_punct(title)
    if sp and sp in textage_by_stripped:
        candidates = textage_by_stripped[sp]
        best = max(candidates, key=lambda x: x['ver'])
        fuzzy_matches[title] = [best['ver'], best['key']]
    else:
        final_unmatched.append(title)

print(f'Fuzzy (stripped punct) matches: {len(fuzzy_matches)}')
print(f'Final unmatched: {len(final_unmatched)}')

# Merge all matches
all_matches = dict(already_matched)
all_matches.update(new_matches)
all_matches.update(fuzzy_matches)

with open('chart_cache/missing_textage_urls.json', 'w', encoding='utf-8') as f:
    json.dump(all_matches, f, ensure_ascii=False, indent=2)

print(f'\nTotal matched (all methods): {len(all_matches)}')
print(f'Remaining unmatched: {len(final_unmatched)}')

print('\n--- Final unmatched songs ---')
for t in final_unmatched[:80]:
    print(f'  {t}')
if len(final_unmatched) > 80:
    print(f'  ... and {len(final_unmatched) - 80} more')
