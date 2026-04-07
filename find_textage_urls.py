#!/usr/bin/env python3
"""Match missing textage URLs by comparing titletbl.js to song_data.json
and patch them into the JSON.
"""
import urllib.request, re, json, html as html_mod

BASE = 'https://textage.cc/score/'

def fetch(path):
    req = urllib.request.Request(BASE + path, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req) as r:
        return r.read().decode('shift_jis', errors='replace')

def parse_titletbl(js):
    """Parse titletbl.js: key -> {ver, title}"""
    def parse_body(s):
        tokens = []
        i = 0
        while i < len(s):
            if s[i] == '"':
                j = i + 1
                buf = ''
                while j < len(s):
                    ch = s[j]
                    if ord(ch) == 92 and j + 1 < len(s):   # backslash
                        buf += s[j+1]
                        j += 2
                    elif ch == '"':
                        j += 1
                        break
                    else:
                        buf += ch
                        j += 1
                tokens.append(('str', buf))
                i = j
            elif s[i].isdigit():
                j = i
                while j < len(s) and s[j].isdigit():
                    j += 1
                tokens.append(('num', int(s[i:j])))
                i = j
            else:
                i += 1
        return tokens

    tbl = {}
    for line in js.splitlines():
        # Strip JS string styling calls
        line = re.sub(r'\.(fontcolor|link|bold|italics)\("[^"]*"\)', '', line)
        m = re.match(r"\s*'([^']+)'\s*:\s*\[(.+)\],?\s*$", line)
        if not m:
            continue
        key = m.group(1)
        tokens = parse_body(m.group(2))
        ver = 0
        strs = []
        for typ, val in tokens:
            if typ == 'num' and ver == 0:
                ver = val
            elif typ == 'str':
                strs.append(val)
        title    = strs[2] if len(strs) >= 3 else ''
        subtitle = strs[3] if len(strs) >= 4 else ''
        full_title = html_mod.unescape(title) + html_mod.unescape(subtitle)
        tbl[key] = {'ver': ver, 'title': full_title}
    return tbl

# ---- normalization --------------------------------------------------------
# Oslash is used as both 'O' and '0' in different songs; handle both via
# a two-pass approach in normalize().
CHAR_MAP_COMMON = [
    # Cyrillic lookalikes
    ('\u0418', 'n'),  # И -> n
    ('\u042f', 'r'),  # Я -> r  (already in old map)
    ('\u03b9', 'i'),  # ι -> i
    ('\u03bb', 'l'),  # λ -> l
    # Latin extended
    ('ä', 'a'), ('ö', 'o'), ('ü', 'u'), ('ë', 'e'), ('ï', 'i'),
    ('â', 'a'), ('ê', 'e'), ('î', 'i'), ('ô', 'o'), ('û', 'u'),
    ('à', 'a'), ('è', 'e'), ('ì', 'i'), ('ò', 'o'), ('ù', 'u'),
    ('á', 'a'), ('é', 'e'), ('í', 'i'), ('ó', 'o'), ('ú', 'u'),
    ('ā', 'a'), ('ē', 'e'), ('ī', 'i'), ('ō', 'o'), ('ū', 'u'),
    ('Ä', 'a'), ('Ö', 'o'), ('Ü', 'u'),
    ('Â', 'a'), ('Ê', 'e'), ('Î', 'i'), ('Ô', 'o'), ('Û', 'u'),
    ('À', 'a'), ('È', 'e'), ('Ì', 'i'), ('Ò', 'o'), ('Ù', 'u'),
    ('Á', 'a'), ('É', 'e'), ('Í', 'i'), ('Ó', 'o'), ('Ú', 'u'),
    ('Ā', 'a'), ('Ē', 'e'), ('Ī', 'i'), ('Ō', 'o'), ('Ū', 'u'),
    ('ã', 'a'), ('õ', 'o'), ('ñ', 'n'), ('ç', 'c'),
    ('Ã', 'a'), ('Õ', 'o'), ('Ñ', 'n'), ('Ç', 'c'),
    ('æ', 'ae'), ('Æ', 'ae'),
    # Misc symbols
    ('†', 't'),
    ('\u04d9', 'e'),  # ɵ (Cyrillic schwa, used as stylized 'e' in song titles)
    ('\u04e9', 'o'),  # ө (Cyrillic O with bar)
    ('∞', 'inf'), ('⇒', ''),
]

def normalize(t, oslash_as='o'):
    """Normalize for matching. oslash_as: 'o' or '0'"""
    t = html_mod.unescape(t)
    t = t.replace('Ø', oslash_as).replace('ø', oslash_as)
    for src, dst in CHAR_MAP_COMMON:
        t = t.replace(src, dst)
    n = re.sub(r'[^a-zA-Z0-9]', '', t).lower()
    # Treat digit '0' as letter 'o' for fuzzy matching
    return n.replace('0', 'o')

def normalize_full(t):
    """Full Unicode normalisation (for pure-CJK titles)."""
    t = html_mod.unescape(t)
    t = t.replace('Ø', 'o').replace('ø', 'o')
    for src, dst in CHAR_MAP_COMMON:
        t = t.replace(src, dst)
    return re.sub(r'[^\w]', '', t).lower()

# ---------------------------------------------------------------------------

print("Fetching titletbl.js ...", flush=True)
tbl = parse_titletbl(fetch('titletbl.js'))
print(f"  {len(tbl)} entries")

with open('backend/src/main/resources/data/song_data.json', encoding='utf-8') as f:
    data = json.load(f)
songs = data['body']

target = [s for s in songs
          if s.get('level') in (11, 12)
          and s.get('difficulty') in ('4', '10')
          and not s.get('textage')]
print(f"Missing textage: {len(target)}")

# Build lookup tables  (two Ø variants × Latin/full-unicode)
def build_lookups(tbl):
    lt_o, lt_0, full = {}, {}, {}
    for key, info in tbl.items():
        t = info['title']
        for d, lk in [('o', lt_o), ('0', lt_0)]:
            n = normalize(t, oslash_as=d)
            if n:
                lk.setdefault(n, []).append((key, info))
        nf = normalize_full(t)
        if nf:
            full.setdefault(nf, []).append((key, info))
    return lt_o, lt_0, full

lt_o, lt_0, full_lk = build_lookups(tbl)

def find_match(title):
    for lk in [lt_o, lt_0]:
        for variant_o in ['o', '0']:
            n = normalize(title, oslash_as=variant_o)
            if n and n in lk:
                return lk[n][0]
    nf = normalize_full(title)
    if nf:
        cands = full_lk.get(nf, [])
        if len(cands) == 1:
            return cands[0]
    return None

found, not_found = [], []
for s in target:
    match = find_match(s['title'])
    if match:
        found.append((s, match[0], match[1]))
    else:
        not_found.append(s)

print(f"\nMatched: {len(found)}/{len(target)}")
print(f"Not found: {len(not_found)}")

print("\nMatched mappings:")
for s, k, info in sorted(found, key=lambda x: x[0]['title']):
    url = f"{info['ver']}/{k}.html?1AC00"
    print(f"  {s['title']:45s} -> {url}")

print(f"\nNot found in textage ({len(not_found)}):")
for s in sorted(not_found, key=lambda x: x['title']):
    print(f"  {s['title']}")

# ---- patch song_data.json ------------------------------------------------
# Build a set of (title, difficulty, level) -> textage_url for patching
patch_map = {}
for s, k, info in found:
    key_tuple = (s['title'], s.get('difficulty'), s.get('level'))
    url = f"{info['ver']}/{k}.html?1AC00"
    patch_map[key_tuple] = url

patched = 0
for s in songs:
    if s.get('textage'):
        continue
    kt = (s['title'], s.get('difficulty'), s.get('level'))
    if kt in patch_map:
        s['textage'] = patch_map[kt]
        patched += 1

print(f"\nPatched {patched} entries in song_data.json")

out_path = 'backend/src/main/resources/data/song_data.json'
with open(out_path, 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
print(f"Written: {out_path}")
