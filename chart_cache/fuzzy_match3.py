"""
Third pass: re-parse titletbl.js with .fontcolor() and HTML entity handling,
then match remaining unanalyzed songs.
"""
import json, sys, re, unicodedata, glob, html as html_mod
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

with open('chart_cache/titletbl.js', 'rb') as f:
    raw_bytes = f.read()
content = raw_bytes.decode('shift-jis', errors='replace')

# Parse all entries
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

    if len(elements) >= 6:
        try:
            ver_str = elements[0].strip()
            if ver_str == 'SS':
                ver = 35
            else:
                ver = int(ver_str)
        except ValueError:
            ver = 0

        title_raw = elements[5]
        # Strip .fontcolor(...), .bold(), .italics(), .link() etc
        title_raw = re.sub(r'\.fontcolor\([^)]*\)', '', title_raw)
        title_raw = re.sub(r'\.bold\(\)', '', title_raw)
        title_raw = re.sub(r'\.italics\(\)', '', title_raw)
        title_raw = re.sub(r'\.link\([^)]*\)', '', title_raw)

        if title_raw.startswith('"') and title_raw.endswith('"'):
            title_raw = title_raw[1:-1]
        title_raw = title_raw.replace('\\"', '"')

        # Decode HTML entities
        title_raw = html_mod.unescape(title_raw)
        # Also handle &#xHHHH; patterns
        title_raw = re.sub(r'&#x([0-9a-fA-F]+);', lambda m: chr(int(m.group(1), 16)), title_raw)

        # Remove † suffix (LEGGENDARIA marker in textage)
        title_clean = title_raw.rstrip('†').strip()

        # Get subtitle too
        subtitle = ''
        if len(elements) >= 7:
            sub = elements[6]
            sub = re.sub(r'\.fontcolor\([^)]*\)', '', sub)
            sub = re.sub(r'\.bold\(\)', '', sub)
            if sub.startswith('"') and sub.endswith('"'):
                subtitle = sub[1:-1]
            subtitle = subtitle.replace('\\"', '"')
            subtitle = html_mod.unescape(subtitle)
            subtitle = re.sub(r'<[^>]+>', ' ', subtitle).strip()
            subtitle = subtitle.rstrip('†').strip()

        entries[key] = {
            'ver': ver, 'title': title_clean, 'title_raw': title_raw,
            'subtitle': subtitle, 'key': key
        }

print(f'Parsed {len(entries)} entries from titletbl.js')

# Verify ハルイロメロディー is found
for k, v in entries.items():
    if 'ハルイロ' in v['title']:
        print(f'  Found: {k} -> {v["title"]} (ver={v["ver"]})')

# Load current state
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
print(f'Unanalyzed songs: {len(purely_unanalyzed)}')

with open('chart_cache/missing_textage_urls.json', 'r', encoding='utf-8') as f:
    already_matched = json.load(f)

unmatched = [t for t in sorted(purely_unanalyzed) if t not in already_matched]
print(f'Still unmatched: {len(unmatched)}')

# Normalize
def normalize(s):
    s = s.lower()
    s = unicodedata.normalize('NFKC', s)
    s = re.sub(r'[\s\u3000]+', '', s)
    s = s.replace('\u301c', '~').replace('\uff5e', '~')
    s = s.replace('\u2212', '-').replace('\u2013', '-').replace('\u2014', '-').replace('\u2015', '-')
    return s

def strip_punct(s):
    return re.sub(r'[^a-z0-9\u3040-\u309f\u30a0-\u30ff\u4e00-\u9fff]', '', normalize(s))

# Build lookups
textage_by_norm = {}
textage_by_stripped = {}
textage_full_norm = {}
textage_full_stripped = {}

for key, info in entries.items():
    n = normalize(info['title'])
    sp = strip_punct(info['title'])

    if n not in textage_by_norm:
        textage_by_norm[n] = []
    textage_by_norm[n].append(info)

    if sp:
        if sp not in textage_by_stripped:
            textage_by_stripped[sp] = []
        textage_by_stripped[sp].append(info)

    # Full title + subtitle
    full = info['title']
    if info['subtitle']:
        full = full + ' ' + info['subtitle']
    fn = normalize(full)
    fsp = strip_punct(full)

    if fn not in textage_full_norm:
        textage_full_norm[fn] = []
    textage_full_norm[fn].append(info)

    if fsp:
        if fsp not in textage_full_stripped:
            textage_full_stripped[fsp] = []
        textage_full_stripped[fsp].append(info)

# Match
new_matches = {}
final_unmatched = []

for title in unmatched:
    n = normalize(title)
    sp = strip_punct(title)
    matched = False

    for lookup in [textage_by_norm, textage_full_norm]:
        if n in lookup:
            candidates = lookup[n]
            best = max(candidates, key=lambda x: x['ver'])
            new_matches[title] = [best['ver'], best['key']]
            matched = True
            break

    if not matched:
        for lookup in [textage_by_stripped, textage_full_stripped]:
            if sp and sp in lookup:
                candidates = lookup[sp]
                best = max(candidates, key=lambda x: x['ver'])
                new_matches[title] = [best['ver'], best['key']]
                matched = True
                break

    if not matched:
        final_unmatched.append(title)

print(f'New matches: {len(new_matches)}')
print(f'Final unmatched: {len(final_unmatched)}')

# Show matches
for title, (ver, key) in sorted(new_matches.items()):
    print(f'  MATCHED: {title} -> {key} (ver={ver})')

# Save
all_matches = dict(already_matched)
all_matches.update(new_matches)
with open('chart_cache/missing_textage_urls.json', 'w', encoding='utf-8') as f:
    json.dump(all_matches, f, ensure_ascii=False, indent=2)

print(f'\nTotal matched: {len(all_matches)}')
print('\n--- Still unmatched ---')
for t in final_unmatched:
    print(f'  {t}')
