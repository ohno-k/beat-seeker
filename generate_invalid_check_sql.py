import json
import os

# Paths
BASE_DIR = r'c:\Users\ohno\.gemini\antigravity\scratch\beat-seeker'
SP11_PATH = os.path.join(BASE_DIR, 'frontend', 'src', 'data', 'sp11.json')
SP12_PATH = os.path.join(BASE_DIR, 'frontend', 'src', 'data', 'sp12.json')
OUTPUT_SQL = 'find_invalid_scores.sql'

# Difficulty mapping (JSON difficulty ID -> DB difficultyName)
diff_map = {
    "2": "NORMAL",
    "3": "HYPER",
    "4": "ANOTHER",
    "10": "LEGGENDARIA"
}

def generate_sql():
    song_data = []
    
    # Load SP11
    if os.path.exists(SP11_PATH):
        with open(SP11_PATH, 'r', encoding='utf-8') as f:
            data = json.load(f)
            for item in data:
                diff_name = diff_map.get(str(item.get('difficulty')))
                if diff_name:
                    song_data.append({
                        'title': item['title'].replace("'", "''"),
                        'difficulty': diff_name,
                        'max_score': int(item['notes']) * 2
                    })

    # Load SP12
    if os.path.exists(SP12_PATH):
        with open(SP12_PATH, 'r', encoding='utf-8') as f:
            data = json.load(f)
            for item in data:
                diff_name = diff_map.get(str(item.get('difficulty')))
                if diff_name:
                    song_data.append({
                        'title': item['title'].replace("'", "''"),
                        'difficulty': diff_name,
                        'max_score': int(item['notes']) * 2
                    })

    # Generate SQL
    with open(OUTPUT_SQL, 'w', encoding='utf-8') as f:
        f.write("-- This script finds scores that exceed 100% rate (potential DP uploads)\n")
        f.write("CREATE TEMP TABLE temp_song_theory (title TEXT, diff TEXT, max_score INT);\n\n")
        
        # Insert in chunks to avoid massive single statements if needed, 
        # but 1500 rows is fine for a few INSERT statements.
        f.write("INSERT INTO temp_song_theory (title, diff, max_score) VALUES\n")
        for i, song in enumerate(song_data):
            comma = "," if i < len(song_data) - 1 else ";"
            f.write(f"('{song['title']}', '{song['difficulty']}', {song['max_score']}){comma}\n")
        
        f.write("\n-- Identify invalid scores and their users\n")
        f.write("SELECT \n")
        f.write("    u.id as user_id, \n")
        f.write("    u.display_name, \n")
        f.write("    s.title, \n")
        f.write("    s.difficulty_name, \n")
        f.write("    s.score as actual_score, \n")
        f.write("    t.max_score as theoretical_max\n")
        f.write("FROM scores s\n")
        f.write("JOIN temp_song_theory t ON s.title = t.title AND s.difficulty_name = t.diff\n")
        f.write("JOIN users u ON s.user_id = u.id\n")
        f.write("WHERE s.score > t.max_score\n")
        f.write("ORDER BY u.id;\n\n")
        
        f.write("-- Summary of count per user\n")
        f.write("SELECT \n")
        f.write("    u.id, \n")
        f.write("    u.display_name, \n")
        f.write("    COUNT(*) as invalid_score_count\n")
        f.write("FROM scores s\n")
        f.write("JOIN temp_song_theory t ON s.title = t.title AND s.difficulty_name = t.diff\n")
        f.write("JOIN users u ON s.user_id = u.id\n")
        f.write("WHERE s.score > t.max_score\n")
        f.write("GROUP BY u.id, u.display_name;\n")

    print(f"SQL script generated: {OUTPUT_SQL}")

if __name__ == "__main__":
    generate_sql()
