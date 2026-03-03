import json

# Title replacements: old_title_in_json -> new_title_matching_csv
replacements = {
    "FrictionFunction": "Friction[!]Function",
    "Ignis\u2020Ir\u00e6": "Ignis\u2020irae",
    "DENTITY": "[ ]IDENTITY",
    "\u014cu Legends": "Ou Legends",
    "ACT\u00d8": "ACT0",
    "Raspberry Potion (feat.\u3042\u308c\u305f\u3093\u2661 & \u304e\u3083\u308b\u306e\u3057\u3093\u2606)": "Raspberry Potion (feat.\u3042\u308c\u305f\u3093 & \u304e\u3083\u308b\u306e\u3057\u3093\u3074)",
    "Uaigh Geala\u00ed": "Uaigh Gealai",
    "u\u0259n": "uan",
}

with open('src/data/song_data.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

count = 0
for entry in data['body']:
    old_title = entry['title']
    if old_title in replacements:
        entry['title'] = replacements[old_title]
        count += 1

with open('src/data/song_data.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

print(f"Replaced {count} title entries")
for old, new in replacements.items():
    print(f"  '{old}' -> '{new}'")
