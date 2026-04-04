import psycopg2
import json

conn = psycopg2.connect(
    host='dpg-d6f68314tr6s73bnbhag-a.oregon-postgres.render.com',
    database='beatseeker',
    user='postgress',
    password='kAw2xymPeLH4mOZuV76hsJCR4L9kFkgM',
    connect_timeout=15
)
cur = conn.cursor()

# Get aggregated PROMOTE/DEMOTE votes (excluding STAY and numeric placements)
cur.execute("""
    SELECT title, difficulty_name, vote, COUNT(*) as count
    FROM tier_votes
    WHERE vote IN ('PROMOTE', 'DEMOTE')
    GROUP BY title, difficulty_name, vote
    ORDER BY title, difficulty_name, vote
""")
votes_raw = cur.fetchall()

# Aggregate into dict: {(title, difficultyName): {PROMOTE: n, DEMOTE: n}}
vote_map = {}
for title, diff_name, vote, count in votes_raw:
    key = (title, diff_name)
    if key not in vote_map:
        vote_map[key] = {}
    vote_map[key][vote] = int(count)

# Determine which songs need rank change
changes = []
for (title, diff_name), counts in vote_map.items():
    promote = counts.get('PROMOTE', 0)
    demote = counts.get('DEMOTE', 0)
    if promote > demote:
        changes.append({'title': title, 'difficultyName': diff_name, 'direction': 'PROMOTE', 'promote': promote, 'demote': demote})
    elif demote > promote:
        changes.append({'title': title, 'difficultyName': diff_name, 'direction': 'DEMOTE', 'promote': promote, 'demote': demote})

# Get current active difficulty table from DB
cur.execute("""
    SELECT dr.rank_value, drs.song_title, dr.sort_order, drs.sort_order
    FROM difficulty_ranks dr
    JOIN difficulty_rank_songs drs ON drs.difficulty_rank_id = dr.id
    WHERE dr.revision = 'active'
    ORDER BY dr.sort_order, drs.sort_order
""")
diff_rows = cur.fetchall()

# Build difficulty table as ordered list of {rank, songs[]}
ranks_map = {}
ranks_order = []
for rank_value, song_title, rank_sort, song_sort in diff_rows:
    if rank_value not in ranks_map:
        ranks_map[rank_value] = {'rank': rank_value, 'sort': rank_sort, 'songs': []}
        ranks_order.append(rank_value)
    ranks_map[rank_value]['songs'].append(song_title)

ranks_list = [ranks_map[r] for r in ranks_order]

cur.close()
conn.close()

result = {
    'changes': changes,
    'ranks': ranks_list
}
with open('votes_result.json', 'w', encoding='utf-8') as f:
    json.dump(result, f, ensure_ascii=False, indent=2)
print("Done! Written to votes_result.json")
