import json
import os
import re
import unicodedata

base_dir = r"c:\Users\ohno\.gemini\antigravity\scratch\beat-seeker\frontend\src\data"
diff_table_path = os.path.join(base_dir, "difficulty_table.json")
sp11_path = os.path.join(base_dir, "sp11.json")
sp12_path = os.path.join(base_dir, "sp12.json")
missing_path = os.path.join(base_dir, "missing_songs.txt")
notes_path = os.path.join(base_dir, "notes.txt")

with open(diff_table_path, 'r', encoding='utf-8') as f:
    diff_table = json.load(f)

song_to_level = {}
for rank_group in diff_table.get('ranks', []):
    rank_str = rank_group['rank']
    try:
        rank_val = float(rank_str)
    except:
        continue
    
    level = "12" if rank_val >= 12.0 else "11"
    for song in rank_group.get('songs', []):
        song_to_level[song] = level

with open(sp11_path, 'r', encoding='utf-8') as f:
    sp11_data = json.load(f)
with open(sp12_path, 'r', encoding='utf-8') as f:
    sp12_data = json.load(f)

with open(missing_path, 'r', encoding='utf-8') as f:
    missing_songs = [line.strip() for line in f if line.strip()]

def normalize(name):
    name = unicodedata.normalize('NFKC', name).lower()
    name = re.sub(r'\s+', '', name)
    name = name.replace('ø', 'o').replace('～', '~').replace('и', 'и').replace('λ', 'λ') 
    return name

user_dict = {}
with open(notes_path, 'r', encoding='utf-8') as f:
    for line in f:
        line = line.strip()
        if not line: continue
        parts = line.split('\t')
        if len(parts) >= 3:
            title = parts[0].strip()
            spa = parts[1].strip()
            spl = parts[2].strip()
            if title.startswith('beatmania IIDX') or title == 'TITLE' or not title:
                continue
            n_title = normalize(title)
            user_dict[n_title] = (title, spa, spl)

new_sp11 = []
new_sp12 = []

remaining_missing = []

for miss_s in missing_songs:
    is_leggendaria = miss_s.endswith('[L]')
    base_title = miss_s[:-3] if is_leggendaria else miss_s
    n_base = normalize(base_title)
    
    processed = False
    if n_base in user_dict:
        orig_t, spa, spl = user_dict[n_base]
        
        level_str = song_to_level.get(miss_s, "11")
        
        if is_leggendaria:
            if spl != '-' and spl != '':
                try:
                    notes_val = int(re.sub(r'\D', '', spl))
                    obj = {
                        "title": base_title,
                        "wr": 0, "avg": 0,
                        "difficulty": "10",
                        "notes": notes_val,
                        "bpm": "", "textage": "",
                        "difficultyLevel": level_str,
                        "dpLevel": "0", "coef": -1
                    }
                    if level_str == "11": new_sp11.append(obj)
                    else: new_sp12.append(obj)
                    processed = True
                except ValueError:
                    pass
        else:
            if spa != '-' and spa != '':
                try:
                    notes_val = int(re.sub(r'\D', '', spa))
                    obj = {
                        "title": base_title,
                        "wr": 0, "avg": 0,
                        "difficulty": "4",
                        "notes": notes_val,
                        "bpm": "", "textage": "",
                        "difficultyLevel": level_str,
                        "dpLevel": "0", "coef": -1
                    }
                    if level_str == "11": new_sp11.append(obj)
                    else: new_sp12.append(obj)
                    processed = True
                except ValueError:
                    pass
                
    if not processed:
        remaining_missing.append(miss_s)

sp11_data.extend(new_sp11)
sp12_data.extend(new_sp12)

with open(sp11_path, 'w', encoding='utf-8') as f:
    json.dump(sp11_data, f, ensure_ascii=False, indent=2)

with open(sp12_path, 'w', encoding='utf-8') as f:
    json.dump(sp12_data, f, ensure_ascii=False, indent=2)

with open(missing_path, 'w', encoding='utf-8') as f:
    for s in remaining_missing:
        f.write(s + "\n")

print(f"Added {len(new_sp11)} to SP11, {len(new_sp12)} to SP12.")
print(f"Remaining missing songs: {len(remaining_missing)}")
