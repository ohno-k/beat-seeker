import json
import os

json_file = r"c:\Users\ohno\.gemini\antigravity\scratch\beat-seeker\frontend\src\data\song_data.json"
output_file = r"c:\Users\ohno\.gemini\antigravity\scratch\beat-seeker\frontend\src\data\investigation_output.txt"

with open(json_file, "r", encoding="utf-8") as f:
    song_data = json.load(f)

titles = sorted(list(set(s["title"] for s in song_data["body"])))

targets = ["COSMIC C3LOCITY", "Feiry Stallion", "A MINSTREL"]

with open(output_file, "w", encoding="utf-8") as f:
    for target in targets:
        f.write(f"Target: {target}\n")
        parts = target.split()
        matches = []
        for t in titles:
            if any(p.lower() in t.lower() for p in parts if len(p) > 2):
                matches.append(t)
        
        if matches:
            f.write(f"  Possible matches: {', '.join(matches[:10])}\n")
        else:
            f.write("  No similar titles found.\n")

print("Done.")
