import json
import unicodedata
import re
import sys
import io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

# Songs from screenshot that show 0.0 score rate
problem_songs = [
    "Xio",
    "Dans la nuit de l'eternite",
    "Friction[!]Function",
    "Ignis\u2020irae",
    "Parvati",
    "POLKAMANIA",
    "[ ]IDENTITY",
    "Ou Legends",
    "RINNE",
    "VOID",
    "ACT0",
    "Flamingo",
    "Raspberry Potion (feat.\u3042\u308c\u305f\u3093 & \u304e\u3083\u308b\u306e\u3057\u3093\u3074)",
    "Uaigh Gealai",
    "uan",
    "FiZZi_POTiON",
    "Space Battleship S4T\u00d8",
    "\u30d3\u30d3\u30c3\u30c9 \u2606\uff0b\uff0a\u3002\u30ad\u30e9\u30ad\u30e9\u30a4\u30e0",
]

with open('src/data/song_data.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

# Build title set from song_data.json
json_titles = set()
for entry in data['body']:
    json_titles.add(entry['title'])

output = []
output.append(f"Total unique titles in song_data.json: {len(json_titles)}")
output.append("")

def normalize(s):
    return unicodedata.normalize('NFKC', s).lower().strip()

for song in problem_songs:
    if song in json_titles:
        output.append(f"EXACT MATCH: '{song}'")
    else:
        norm_song = normalize(song)
        found = False
        for t in json_titles:
            if normalize(t) == norm_song:
                output.append(f"NORM MATCH: CSV='{song}' -> JSON='{t}'")
                found = True
                break
        if not found:
            partial = []
            for t in json_titles:
                nt = normalize(t)
                ns = norm_song
                if ns in nt or nt in ns:
                    partial.append(t)
                clean_s = re.sub(r'[^a-z0-9]', '', ns)
                clean_t = re.sub(r'[^a-z0-9]', '', nt)
                if clean_s and clean_t and (clean_s in clean_t or clean_t in clean_s) and len(clean_s) > 3:
                    if t not in partial:
                        partial.append(t)
            if partial:
                output.append(f"PARTIAL: CSV='{song}' -> Possible: {partial}")
            else:
                output.append(f"NOT FOUND: '{song}'")

with open('mismatch_results.txt', 'w', encoding='utf-8') as f:
    f.write('\n'.join(output))

print("Done. Results written to mismatch_results.txt")
