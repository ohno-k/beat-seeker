with open(r'c:\Users\ohno\.gemini\antigravity\scratch\beat-seeker\frontend\src\data\notes.txt', 'r', encoding='utf-8', errors='ignore') as f, open(r'c:\Users\ohno\.gemini\antigravity\scratch\beat-seeker\frontend\src\data\temp_matches.txt', 'w', encoding='utf-8') as out:
    for line in f:
        if 'Punch' in line or 'Rasp' in line or 'POL' in line or 'POT' in line or 'FiZZ' in line:
            out.write(line)
