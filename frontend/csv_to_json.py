import csv
import json
import re
import unicodedata

def normalize(name):
    name = unicodedata.normalize('NFKC', name).lower()
    name = re.sub(r'\s+', '', name)
    return name

def parse_notes(val):
    val = val.strip()
    if val == '-' or val == '':
        return None
    try:
        return int(val)
    except ValueError:
        return None

def parse_level(val):
    val = re.sub(r'\[.*?\]', '', val).strip()
    if val == '-' or val == '':
        return None
    try:
        return int(val)
    except ValueError:
        return val

# Load existing song_data.json to preserve wr/avg/textage/coef
existing_data = {}
try:
    with open('src/data/song_data.json', 'r', encoding='utf-8') as f:
        old_json = json.load(f)
        if 'body' in old_json and isinstance(old_json['body'], list):
            for entry in old_json['body']:
                key = f"{entry.get('title', '')}_{entry.get('difficulty', '')}"
                existing_data[key] = entry
                # Also store by normalized title
                norm_key = f"{normalize(entry.get('title', ''))}_{entry.get('difficulty', '')}"
                existing_data[norm_key] = entry
    print(f"Loaded {len(old_json['body'])} existing entries from song_data.json")
except Exception as e:
    print(f"Warning: Could not load existing song_data.json: {e}")

# SP difficulty mappings: column_index -> (difficulty_code, notes_column_index)
# CSV columns: title(0), artist(1), genre(2), bpm(3),
#   sp_b_level(4), sp_n_level(5), sp_h_level(6), sp_a_level(7), sp_l_level(8),
#   dp_n_level(9), dp_h_level(10), dp_a_level(11), dp_l_level(12),
#   sp_b_notes(13), sp_n_notes(14), sp_h_notes(15), sp_a_notes(16), sp_l_notes(17),
#   dp_n_notes(18), dp_h_notes(19), dp_a_notes(20), dp_l_notes(21)

sp_diffs = [
    # (difficulty_code, level_col, notes_col)
    ('1', 4, 13),   # BEGINNER
    ('2', 5, 14),   # NORMAL
    ('3', 6, 15),   # HYPER
    ('4', 7, 16),   # ANOTHER
    ('10', 8, 17),  # LEGGENDARIA
]

body = []
seen_keys = set()

with open('src/data/song_data.csv', 'r', encoding='utf-8-sig') as f:
    reader = csv.reader(f)
    header = next(reader)  # skip header

    for row in reader:
        if len(row) < 18:
            continue

        title = row[0].strip()
        artist = row[1].strip()
        genre = row[2].strip()
        bpm = row[3].strip()

        for diff_code, level_col, notes_col in sp_diffs:
            notes = parse_notes(row[notes_col]) if notes_col < len(row) else None
            level = parse_level(row[level_col]) if level_col < len(row) else None

            if notes is None or notes == 0:
                continue

            key = f"{title}_{diff_code}"
            if key in seen_keys:
                continue
            seen_keys.add(key)

            # Try to find existing entry to preserve wr/avg/textage/coef
            existing = existing_data.get(key)
            if not existing:
                norm_key = f"{normalize(title)}_{diff_code}"
                existing = existing_data.get(norm_key)

            entry = {
                "title": title,
                "artist": artist,
                "genre": genre,
                "notes": notes,
                "bpm": bpm,
                "difficulty": diff_code,
            }

            if level is not None:
                entry["level"] = level

            # Preserve existing fields if available
            if existing:
                if 'wr' in existing: entry['wr'] = existing['wr']
                if 'avg' in existing: entry['avg'] = existing['avg']
                if 'textage' in existing: entry['textage'] = existing['textage']
                if 'coef' in existing: entry['coef'] = existing['coef']
                if 'difficultyLevel' in existing: entry['difficultyLevel'] = existing['difficultyLevel']
                if 'dpLevel' in existing: entry['dpLevel'] = existing['dpLevel']

            body.append(entry)

# Sort by title then difficulty code
body.sort(key=lambda x: (x['title'], x['difficulty']))

result = {
    "version": 20241114,
    "requireVersion": "87",
    "body": body
}

with open('src/data/song_data.json', 'w', encoding='utf-8') as f:
    json.dump(result, f, ensure_ascii=False, indent=2)

print(f"Generated song_data.json with {len(body)} entries")
