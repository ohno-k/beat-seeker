import json
import math
import os

data_dir = r"c:\Users\ohno\AppData\Local\Google\Antigravity\scratch\beat-seeker\frontend\src\data"
if not os.path.exists(data_dir):
    data_dir = r"c:\Users\ohno\.gemini\antigravity\scratch\beat-seeker\frontend\src\data"

input_file = os.path.join(data_dir, "miteigi_BPI.txt")
json_file = os.path.join(data_dir, "song_data.json")
csv_file = os.path.join(data_dir, "miteigi_BPI.csv")

with open(json_file, "r", encoding="utf-8") as f:
    song_data = json.load(f)

# Build a lookup dictionary for song data
# Key: (title, difficulty)
lookup = {}
for song in song_data["body"]:
    title = song["title"]
    diff = str(song["difficulty"])
    lookup[(title, diff)] = song

with open(input_file, "r", encoding="utf-8") as f:
    lines = [line.strip() for line in f if line.strip()]

results = []
not_found = []

for line in lines:
    original_line = line
    title = line
    difficulty = "4"
    
    if title.endswith("[L]"):
        title = title[:-3]
        difficulty = "10"
    
    # Try finding the song
    song = lookup.get((title, difficulty))
    
    if not song:
        # Some songs might have slight variations or I might need to match case-insensitively
        # Let's try case-insensitive title match with specific difficulty
        found = False
        for (t, d), s in lookup.items():
            if t.lower() == title.lower() and d == difficulty:
                song = s
                found = True
                break
        
        if not found:
            not_found.append(line)
            results.append((line, "NOT FOUND"))
            continue

    notes = song["notes"]
    max_score = notes * 2
    # 94.44% threshold score: math.floor(MAX * 0.9444) + 1
    # 3162 * 0.9444 = 2986.1928 -> 2987
    threshold_score = math.floor(max_score * 0.9444) + 1
    formatted_score = "{:,}".format(threshold_score)
    
    results.append((line, formatted_score))

# Update miteigi_BPI.txt
with open(input_file, "w", encoding="utf-8") as f:
    for name, score in results:
        f.write(f"{name},{score}\n")

# Create miteigi_BPI.csv
with open(csv_file, "w", encoding="utf-8") as f:
    for name, score in results:
        # Removing commas from score for CSV if needed, but the user asked to add commas to the score
        # Let's quote the name in case it contains commas
        f.write(f'"{name}","{score}"\n')

print(f"Processed {len(results)} songs.")
if not_found:
    print(f"Not found ({len(not_found)}): {', '.join(not_found)}")
else:
    print("All songs found.")
