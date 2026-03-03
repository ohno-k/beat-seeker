import csv
import unicodedata
import re

def normalize(name):
    name = unicodedata.normalize('NFKC', name).lower()
    name = re.sub(r'\s+', '', name)
    name = name.replace('ø', 'o').replace('～', '~').replace('и', 'и').replace('λ', 'λ')
    return name

def clean_level(v):
    v = v.replace('\n', ' ').strip()
    # Remove bracketed modifiers like [CN], [BSS], [HCN], [HBSS], [MSS] from levels
    v = re.sub(r'\[.*?\]', '', v).strip()
    return v

notes_data = {}
with open('src/data/notes.txt', 'r', encoding='utf-8') as f:
    reader = csv.reader(f, delimiter='\t')
    for parts in reader:
        if len(parts) >= 10 and parts[0] != 'TITLE' and parts[0] != '' and 'beatmania IIDX' not in parts[0]:
            title = parts[0].strip()
            norm_title = normalize(title)
            notes_data[norm_title] = parts[1:10]

with open('src/data/song_data.txt', 'r', encoding='utf-8') as f, \
     open('src/data/song_data.csv', 'w', encoding='utf-8-sig', newline='') as out:
    
    reader = csv.reader(f, delimiter='\t')
    writer = csv.writer(out)
    header = ['title', 'artist', 'genre', 'bpm', 
              'sp_b_level', 'sp_n_level', 'sp_h_level', 'sp_a_level', 'sp_l_level', 
              'dp_n_level', 'dp_h_level', 'dp_a_level', 'dp_l_level',
              'sp_b_notes', 'sp_n_notes', 'sp_h_notes', 'sp_a_notes', 'sp_l_notes',
              'dp_n_notes', 'dp_h_notes', 'dp_a_notes', 'dp_l_notes']
    writer.writerow(header)
    
    for parts in reader:
        if len(parts) >= 13 and parts[11] != 'TITLE' and parts[11] != '' and 'beatmania IIDX' not in parts[11]:
            title = clean_level(parts[11])
            if title == 'TITLE' or title == '':
                continue
                
            sp_b_level = clean_level(parts[0])
            sp_n_level = clean_level(parts[1])
            sp_h_level = clean_level(parts[2])
            sp_a_level = clean_level(parts[3])
            sp_l_level = clean_level(parts[4])
            dp_n_level = clean_level(parts[5])
            dp_h_level = clean_level(parts[6])
            dp_a_level = clean_level(parts[7])
            dp_l_level = clean_level(parts[8])
            bpm = clean_level(parts[9])
            genre = clean_level(parts[10])
            artist = clean_level(parts[12])
            
            norm_title = normalize(title)
            notes = notes_data.get(norm_title)
            if not notes:
                for k in notes_data.keys():
                    if k in norm_title or norm_title in k:
                        notes = notes_data[k]
                        break
            
            if not notes:
                notes = ['-'] * 9
            else:
                notes = [clean_level(n) for n in notes]
                
            writer.writerow([title, artist, genre, bpm, 
                             sp_b_level, sp_n_level, sp_h_level, sp_a_level, sp_l_level, 
                             dp_n_level, dp_h_level, dp_a_level, dp_l_level,
                             notes[0], notes[1], notes[2], notes[3], notes[4], 
                             notes[5], notes[6], notes[7], notes[8]])

print("Successfully generated src/data/song_data.csv")
