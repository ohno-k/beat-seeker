import json
import os
import re
import unicodedata

user_data_raw = """
A Certified Rave Moment	1404	-
AGETEKE ONDO	1297	-
Bang Bang Dance	1415	-
Caramel Pain	832	-
Catch ya Getcha!	1481	-
Countdown to Heaven or Hell	1569	-
Disco Killer Music Lover	1557	-
Fashion Fruit	1534	-
From Human Blood	1850	-
JUST SYNC ft.Kanae Asaba	1014	-
LEMON MELON COOKIE	1128	-
Makin' It	1471	-
Memoria Obscura	2085	-
MOCHIMOCHI DREAMIN!!	1530	-
Nyan Nyan University	1294	-
PALETTE	1031	-
RALLY '25	1674	-
Regina vespaE	1840	-
SHINING☆DANCE	1243	-
SnapSkipSpark	1466	-
Space Battleship S4TØ	1900	-
SPLASH!!!!!!!!!	1153	-
Substance	1578	-
Vitamin	1346	-
2 Be Continued	1872	-
ιgniЯRuina	1851	-
かわいいだけじゃだめですか？	1070	-
鏡像都市	985	-
月下繚乱	1422	-
閃と雷管とロープ	1294	-
それが、ボクの使命～レスキュー隊長 メンキュー～	1093	-
デラむぅのでらっくす☆どり～むぅ	997	-
でんぱ どりる わんにゃー☆三	1679	-
夏色のセレナーデ	1128	-
バブリン	1221	-
ビビッド ☆＋＊。キラキライム	1896	-
百恋ラブラスター	1324	-
ベラ・ベ・カラベラ	1436	-
もんめためたもん	1127	-
煉獄コンフィチュール	1353	-
Shooting Star	1228	-
Lisa-RICCIA	1692	2394
華麗なる！音戯探偵ひなビタ♫	1204	-
レシピのリドル	1181	-
Signs and Wonders	806	-
GOLD RUSH 2025	1420	-
chaplet -IIDX re:build-	1481	-
COLOR BURST	1101	-
好吃来世deエレクトリック	958	-
kors k's Let's make an Image Song!	1392	-
BattleRoyal	1330	-
Begin	1638	-
⁽⁽ଘ( ˙꒳˙ )ଓ⁾⁾ beyond reason	1988	-
Watermelon Explosion	1021	-
REcorrection	823	-
Hallucination	1391	-
voice of echo.	1392	-
クレッシェンド	1742	-
FiZZλ_PØT!0И	1800	-
Show Time	1035	-
タンポポ	1346	-
Nemophila	1404	-
UNDO THE NIGHT	1787	-
PERFECT GREAT!!	1990	-
Amor∞Fati	2171	-
Astra Blaze	1730	-
GO!	1618	-
HORIZON BEATZ	1289	-
King of Tribe	1590	-
Meteor☆Shower	1493	-
RIZING-GAMERS.	1759	-
SILKY BRAVE	1203	-
Last Card	1560	-
TOKAKU=ALMiRAJ	1477	-
色を喪った街	2026	-
HyperTwist	1692	-
fixer	1861	-
evergreen	997	-
KYAMISAMA ONEGAI!	1076	1583
""".strip().split('\n')

base_dir = r"c:\Users\ohno\.gemini\antigravity\scratch\beat-seeker\frontend\src\data"
diff_table_path = os.path.join(base_dir, "difficulty_table.json")
sp11_path = os.path.join(base_dir, "sp11.json")
sp12_path = os.path.join(base_dir, "sp12.json")
missing_path = os.path.join(base_dir, "missing_songs.txt")

with open(diff_table_path, 'r', encoding='utf-8') as f:
    diff_table = json.load(f)

# Build a lookup for missing songs to check their rank
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
    name = name.replace('ø', 'o').replace('～', '~').replace('и', 'и').replace('λ', 'λ') # Wait, let's keep basic ones
    return name

user_dict = {}
for line in user_data_raw:
    parts = line.split('\t')
    if len(parts) >= 3:
        n_title = normalize(parts[0])
        user_dict[n_title] = (parts[0], parts[1].strip(), parts[2].strip())

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
        
        # Determine the level for JSON insertion based on the miss_s exact string in difficulty_table
        level_str = song_to_level.get(miss_s, "11") # default 11? 
        
        if is_leggendaria:
            if spl != '-' and spl != '':
                obj = {
                    "title": base_title,
                    "wr": 0, "avg": 0,
                    "difficulty": "10",
                    "notes": int(spl),
                    "bpm": "", "textage": "",
                    "difficultyLevel": level_str,
                    "dpLevel": "0", "coef": -1
                }
                if level_str == "11": new_sp11.append(obj)
                else: new_sp12.append(obj)
                processed = True
        else:
            if spa != '-' and spa != '':
                obj = {
                    "title": base_title,
                    "wr": 0, "avg": 0,
                    "difficulty": "4",
                    "notes": int(spa),
                    "bpm": "", "textage": "",
                    "difficultyLevel": level_str,
                    "dpLevel": "0", "coef": -1
                }
                if level_str == "11": new_sp11.append(obj)
                else: new_sp12.append(obj)
                processed = True
                
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
