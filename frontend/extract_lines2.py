# UNUSED: 一度きりの調査用アドホックスクリプト。frontend/SCRIPTS.md §2.4 参照。
with open(r'c:\Users\ohno\.gemini\antigravity\scratch\beat-seeker\frontend\src\data\notes.txt', 'r', encoding='utf-8', errors='ignore') as f, open(r'c:\Users\ohno\.gemini\antigravity\scratch\beat-seeker\frontend\src\data\temp_matches2.txt', 'w', encoding='utf-8') as out:
    for line in f:
        lower_line = line.lower()
        if 'fizz' in lower_line or 'pot' in lower_line or 'pøt' in lower_line or 'pot' in lower_line or '0и' in lower_line:
            out.write(line)
