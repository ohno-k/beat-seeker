"""
Second pass: try to match remaining 109 songs by matching the base title
(before parentheses, dashes with suffixes, etc.) to textage entries.
"""
import json, sys, re, unicodedata, glob
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

with open('chart_cache/titletbl.js', 'rb') as f:
    raw_bytes = f.read()
try:
    content = raw_bytes.decode('utf-8')
except UnicodeDecodeError:
    content = raw_bytes.decode('shift-jis', errors='replace')

# Parse all entries
entries = {}
line_pattern = re.compile(r"'([^']+)'\s*:\s*\[([^\]]+)\]")
for m in line_pattern.finditer(content):
    key = m.group(1)
    array_str = m.group(2)
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
            ver = int(elements[0])
        except ValueError:
            ver = 0
        title_raw = elements[5]
        if title_raw.startswith('"') and title_raw.endswith('"'):
            title_raw = title_raw[1:-1]
        title_raw = title_raw.replace('\\"', '"')
        # Also get subtitle if present (index 6)
        subtitle = ''
        if len(elements) >= 7:
            sub = elements[6]
            if sub.startswith('"') and sub.endswith('"'):
                subtitle = sub[1:-1]
        entries[key] = {'ver': ver, 'title': title_raw, 'subtitle': subtitle, 'key': key}

print(f'Parsed {len(entries)} textage entries')

# Load current matched
with open('chart_cache/missing_textage_urls.json', 'r', encoding='utf-8') as f:
    all_matches = json.load(f)

# Load unanalyzed songs
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
unmatched = [t for t in sorted(purely_unanalyzed) if t not in all_matches]
print(f'Unmatched: {len(unmatched)}')

def normalize(s):
    s = s.lower()
    s = unicodedata.normalize('NFKC', s)
    s = re.sub(r'[\s\u3000]+', '', s)
    s = s.replace('\u301c', '~').replace('\uff5e', '~')
    s = s.replace('\u2212', '-').replace('\u2013', '-').replace('\u2014', '-').replace('\u2015', '-')
    return s

def strip_punct(s):
    return re.sub(r'[^a-z0-9\u3040-\u309f\u30a0-\u30ff\u4e00-\u9fff]', '', normalize(s))

# Build textage lookup by full title+subtitle (normalized)
textage_full = {}
for key, info in entries.items():
    full = info['title']
    if info['subtitle']:
        # Remove HTML tags from subtitle
        sub = re.sub(r'<[^>]+>', ' ', info['subtitle']).strip()
        full = full + ' ' + sub
    n = normalize(full)
    if n not in textage_full:
        textage_full[n] = []
    textage_full[n].append(info)

# Also build stripped version
textage_full_stripped = {}
for key, info in entries.items():
    full = info['title']
    if info['subtitle']:
        sub = re.sub(r'<[^>]+>', ' ', info['subtitle']).strip()
        full = full + ' ' + sub
    sp = strip_punct(full)
    if sp:
        if sp not in textage_full_stripped:
            textage_full_stripped[sp] = []
        textage_full_stripped[sp].append(info)

# Try matching with title+subtitle
new_matches = {}
still_unmatched = []
for title in unmatched:
    n = normalize(title)
    sp = strip_punct(title)
    if n in textage_full:
        candidates = textage_full[n]
        best = max(candidates, key=lambda x: x['ver'])
        new_matches[title] = [best['ver'], best['key']]
    elif sp and sp in textage_full_stripped:
        candidates = textage_full_stripped[sp]
        best = max(candidates, key=lambda x: x['ver'])
        new_matches[title] = [best['ver'], best['key']]
    else:
        still_unmatched.append(title)

print(f'Matched via title+subtitle: {len(new_matches)}')
print(f'Remaining: {len(still_unmatched)}')

# For remaining, try base title extraction (before parentheses/brackets/dashes)
# and match against textage title only
textage_by_title_norm = {}
for key, info in entries.items():
    n = normalize(info['title'])
    if n not in textage_by_title_norm:
        textage_by_title_norm[n] = []
    textage_by_title_norm[n].append(info)

textage_by_title_stripped = {}
for key, info in entries.items():
    sp = strip_punct(info['title'])
    if sp:
        if sp not in textage_by_title_stripped:
            textage_by_title_stripped[sp] = []
        textage_by_title_stripped[sp].append(info)

# Extract base title (before remix/mix suffixes)
def extract_base(title):
    # Remove parenthesized suffixes
    base = re.sub(r'\s*[\(（\[【].*$', '', title)
    # Remove dash-separated suffixes like "-remix-"
    base = re.sub(r'\s*[-～~].*(?:mix|remix|version|edition|style|Ver\.).*$', '', base, flags=re.IGNORECASE)
    return base.strip()

base_matches = {}
final_unmatched = []
for title in still_unmatched:
    base = extract_base(title)
    bn = normalize(base)
    bsp = strip_punct(base)

    matched = False
    if bn in textage_by_title_norm:
        candidates = textage_by_title_norm[bn]
        best = max(candidates, key=lambda x: x['ver'])
        base_matches[title] = [best['ver'], best['key']]
        matched = True
    elif bsp and bsp in textage_by_title_stripped:
        candidates = textage_by_title_stripped[bsp]
        best = max(candidates, key=lambda x: x['ver'])
        base_matches[title] = [best['ver'], best['key']]
        matched = True

    if not matched:
        final_unmatched.append(title)

print(f'Matched via base title: {len(base_matches)}')
print(f'Final unmatched: {len(final_unmatched)}')

# Update and save
all_matches.update(new_matches)
all_matches.update(base_matches)

with open('chart_cache/missing_textage_urls.json', 'w', encoding='utf-8') as f:
    json.dump(all_matches, f, ensure_ascii=False, indent=2)

print(f'\nTotal matched: {len(all_matches)}')

print('\n--- Final unmatched songs ---')
for t in final_unmatched:
    print(f'  {t}')
