import json
import os
import sys
from pathlib import Path

# Force UTF-8 output on Windows
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

def main():
    song_data_path = r"frontend/src/data/song_data.json"
    profiles_dir = r"chart_cache/profiles"

    if not os.path.exists(song_data_path):
        print(f"Error: {song_data_path} not found.")
        return

    print(f"Loading {song_data_path}...")
    with open(song_data_path, "r", encoding="utf-8") as f:
        song_data = json.load(f)

    # Build lookup map: textage -> notes
    # Some entries don't have textage, so we'll use (title, difficulty) as secondary key
    textage_map = {}
    title_diff_map = {}

    for item in song_data.get("body", []):
        notes = item.get("notes")
        if notes is None:
            continue
            
        textage = item.get("textage")
        if textage:
            textage_map[textage] = notes
            
        title = item.get("title")
        difficulty = str(item.get("difficulty"))
        if title and difficulty:
            title_diff_map[(title, difficulty)] = notes

    print(f"Found {len(textage_map)} textage entries and {len(title_diff_map)} title/diff entries.")

    mismatches = []
    missing_in_song_data = []

    profile_files = list(Path(profiles_dir).rglob("*.json"))
    print(f"Analyzing {len(profile_files)} profiles...")

    for profile_file in profile_files:
        try:
            with open(profile_file, "r", encoding="utf-8") as f:
                profile = json.load(f)
        except Exception as e:
            print(f"Error reading {profile_file}: {e}")
            continue

        textage = profile.get("textage")
        notes = profile.get("notes")
        title = profile.get("title")
        difficulty = str(profile.get("difficulty"))

        if notes is None:
            continue

        expected_notes = None
        if textage and textage in textage_map:
            expected_notes = textage_map[textage]
        elif (title, difficulty) in title_diff_map:
            expected_notes = title_diff_map[(title, difficulty)]

        if expected_notes is not None:
            # Check for mismatch or 1% error
            diff = abs(notes - expected_notes)
            error_pct = diff / expected_notes if expected_notes > 0 else 0
            
            if diff != 0:
                is_minor = error_pct <= 0.01
                mismatches.append({
                    "file": str(profile_file),
                    "title": title,
                    "difficulty": difficulty,
                    "actual": notes,
                    "expected": expected_notes,
                    "diff": diff,
                    "error_pct": error_pct * 100,
                    "is_minor": is_minor
                })
        else:
            missing_in_song_data.append({
                "file": str(profile_file),
                "title": title,
                "textage": textage,
                "difficulty": difficulty
            })

    # Sort mismatches by error percentage
    mismatches.sort(key=lambda x: x["error_pct"], reverse=True)

    # Redirect output to file
    with open("verification_report.txt", "w", encoding="utf-8") as out:
        def log(msg):
            print(msg)
            out.write(msg + "\n")

        log("\n=== Significant Mismatches (> 1%) ===")
        sig_mismatches = [m for m in mismatches if not m["is_minor"]]
        for m in sig_mismatches:
            log(f"[{m['title']} ({m['difficulty']})] Notes: {m['actual']} (Profile) vs {m['expected']} (SongData) | Diff: {m['diff']} ({m['error_pct']:.2f}%) | File: {m['file']}")

        log("\n=== Minor Mismatches (<= 1%) ===")
        minor_mismatches = [m for m in mismatches if m["is_minor"]]
        for m in minor_mismatches:
            log(f"[{m['title']} ({m['difficulty']})] Notes: {m['actual']} (Profile) vs {m['expected']} (SongData) | Diff: {m['diff']} ({m['error_pct']:.2f}%) | File: {m['file']}")

        log("\n=== Not Found in song_data.json ===")
        for m in missing_in_song_data:
            log(f"[{m['title']} ({m['difficulty']})] textage: {m['textage']} | File: {m['file']}")

        log("\n=== Summary ===")
        log(f"Total profiles checked: {len(profile_files)}")
        log(f"Mismatches (> 1%): {len(sig_mismatches)}")
        log(f"Mismatches (<= 1%): {len(minor_mismatches)}")
        log(f"Not found in SongData: {len(missing_in_song_data)}")

if __name__ == "__main__":
    main()
