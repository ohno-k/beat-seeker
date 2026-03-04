import json
import os

target_files = [
    r"c:\Users\ohno\.gemini\antigravity\scratch\beat-seeker\frontend\src\data\sp11.json",
    r"c:\Users\ohno\.gemini\antigravity\scratch\beat-seeker\frontend\src\data\sp12.json"
]

targets = ["COSMIC C3LOCITY", "Feiry Stallion", "A MINSTREL"]

for fpath in target_files:
    if not os.path.exists(fpath): continue
    print(f"Checking {os.path.basename(fpath)}...")
    with open(fpath, "r", encoding="utf-8") as f:
        data = json.load(f)
    
    # Typically sp11/sp12 are lists of objects with 'title' and 'notes' or 'totalNotes'
    # Let's inspect the structure first
    if isinstance(data, list):
        for item in data:
            title = item.get("title", "")
            if any(t.lower() in title.lower() for t in ["COSMIC", "Stallion", "MINSTREL"]):
                print(f"  Found: {title}")
    elif isinstance(data, dict):
        # Maybe it's a dict
        for k, v in data.items():
            if any(t.lower() in k.lower() for t in ["COSMIC", "Stallion", "MINSTREL"]):
                print(f"  Found: {k}")
