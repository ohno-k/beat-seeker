"""
投票結果に基づいて難易度表のドラフトを作成するスクリプト

- PROMOTE > DEMOTE: 1段階昇格（例: 12.5 -> 12.6）
- DEMOTE > PROMOTE: 1段階降格（例: 12.5 -> 12.4）
- 同数 or 投票なし: 現状維持
- 数値ランクにない楽曲（Uncategorized等）は対象外
"""

import json
import psycopg2

# ── DB接続 ──────────────────────────────────────────────────
conn = psycopg2.connect(
    host='dpg-d6f68314tr6s73bnbhag-a.oregon-postgres.render.com',
    database='beatseeker',
    user='postgress',
    password='kAw2xymPeLH4mOZuV76hsJCR4L9kFkgM',
    connect_timeout=15
)
cur = conn.cursor()

# ── 現在のアクティブな難易度表を取得 ──────────────────────────
cur.execute("""
    SELECT dr.rank_value, drs.song_title, dr.sort_order, drs.sort_order as song_order
    FROM difficulty_ranks dr
    JOIN difficulty_rank_songs drs ON drs.difficulty_rank_id = dr.id
    WHERE dr.revision = 'active'
    ORDER BY dr.sort_order, drs.sort_order
""")
diff_rows = cur.fetchall()

# 難易度表を構築（sort_order順に保持）
ranks_ordered = []  # [{'rank': '13.0', 'sort': 0, 'songs': [...]}, ...]
rank_index_by_value = {}  # rank_value -> index in ranks_ordered

for rank_value, song_title, rank_sort, song_sort in diff_rows:
    if rank_value not in rank_index_by_value:
        idx = len(ranks_ordered)
        ranks_ordered.append({'rank': rank_value, 'sort': rank_sort, 'songs': []})
        rank_index_by_value[rank_value] = idx
    ranks_ordered[rank_index_by_value[rank_value]]['songs'].append(song_title)

import sys
sys.stdout = open(sys.stdout.fileno(), mode='w', encoding='utf-8', buffering=1)
print(f"アクティブな難易度表: {len(ranks_ordered)}ランク")

# 数値ランクのみ（昇格/降格可能なランク）を特定
def is_numeric_rank(rank_value):
    try:
        float(rank_value)
        return True
    except ValueError:
        return False

numeric_rank_indices = [i for i, r in enumerate(ranks_ordered) if is_numeric_rank(r['rank'])]
print(f"数値ランク数: {len(numeric_rank_indices)}")

# 楽曲→現在のランクインデックスのマップを作成
song_to_rank_idx = {}
for i, r in enumerate(ranks_ordered):
    for song in r['songs']:
        song_to_rank_idx[song] = i

# ── 投票データを取得 ──────────────────────────────────────────
cur.execute("""
    SELECT title, difficulty_name, vote, COUNT(*) as count
    FROM tier_votes
    WHERE vote IN ('PROMOTE', 'DEMOTE')
    GROUP BY title, difficulty_name, vote
    ORDER BY title, difficulty_name, vote
""")
votes_raw = cur.fetchall()

# 投票を集計
vote_map = {}  # (title, diff_name) -> {PROMOTE: n, DEMOTE: n}
for title, diff_name, vote, count in votes_raw:
    key = (title, diff_name)
    if key not in vote_map:
        vote_map[key] = {}
    vote_map[key][vote] = int(count)

cur.close()
conn.close()

print(f"投票がある楽曲: {len(vote_map)}件")

# ── 変動が必要な楽曲を決定 ────────────────────────────────────
# difficulty_name → 難易度表のエントリ名への変換
def get_table_title(title, diff_name):
    if diff_name == 'LEGGENDARIA':
        return title + '[L]'
    else:
        return title  # ANOTHER, etc.

changes = []  # {table_title, direction, old_rank, old_rank_idx, new_rank_idx}

for (title, diff_name), counts in vote_map.items():
    promote = counts.get('PROMOTE', 0)
    demote = counts.get('DEMOTE', 0)

    if promote == demote:
        continue  # 同数は現状維持

    table_title = get_table_title(title, diff_name)

    if table_title not in song_to_rank_idx:
        print(f"  スキップ（難易度表未登録）: {table_title}")
        continue

    current_rank_idx = song_to_rank_idx[table_title]
    current_rank = ranks_ordered[current_rank_idx]['rank']

    # 数値ランク以外はスキップ
    if not is_numeric_rank(current_rank):
        print(f"  スキップ（非数値ランク '{current_rank}'）: {table_title}")
        continue

    # numeric_rank_indicesの中での現在位置
    pos_in_numeric = numeric_rank_indices.index(current_rank_idx) if current_rank_idx in numeric_rank_indices else -1
    if pos_in_numeric == -1:
        print(f"  スキップ（数値ランクリスト外）: {table_title}")
        continue

    if promote > demote:
        # 昇格: 数値ランクで一つ上（インデックス-1 = sort_orderが小さい = ランクが高い）
        if pos_in_numeric == 0:
            print(f"  スキップ（最上位ランク）: {table_title} ({current_rank})")
            continue
        new_rank_idx = numeric_rank_indices[pos_in_numeric - 1]
        direction = 'PROMOTE'
    else:
        # 降格: 数値ランクで一つ下
        if pos_in_numeric == len(numeric_rank_indices) - 1:
            print(f"  スキップ（最下位ランク）: {table_title} ({current_rank})")
            continue
        new_rank_idx = numeric_rank_indices[pos_in_numeric + 1]
        direction = 'DEMOTE'

    new_rank = ranks_ordered[new_rank_idx]['rank']
    changes.append({
        'table_title': table_title,
        'direction': direction,
        'old_rank': current_rank,
        'new_rank': new_rank,
        'old_rank_idx': current_rank_idx,
        'new_rank_idx': new_rank_idx,
        'promote': promote,
        'demote': demote,
    })

print(f"\n変動対象楽曲: {len(changes)}件")
for c in sorted(changes, key=lambda x: float(x['old_rank']), reverse=True):
    print(f"  {c['direction']:7s} {c['old_rank']} -> {c['new_rank']} | {c['table_title']} (↑{c['promote']} ↓{c['demote']})")

# ── 新しい難易度表を構築 ──────────────────────────────────────
import copy
new_ranks = copy.deepcopy(ranks_ordered)

# 変動を適用
for ch in changes:
    old_idx = ch['old_rank_idx']
    new_idx = ch['new_rank_idx']
    title = ch['table_title']

    # 旧ランクから削除
    new_ranks[old_idx]['songs'] = [s for s in new_ranks[old_idx]['songs'] if s != title]
    # 新ランクに追加
    new_ranks[new_idx]['songs'].append(title)

# ── DBにドラフトとして保存 ───────────────────────────────────
conn2 = psycopg2.connect(
    host='dpg-d6f68314tr6s73bnbhag-a.oregon-postgres.render.com',
    database='beatseeker',
    user='postgress',
    password='kAw2xymPeLH4mOZuV76hsJCR4L9kFkgM',
    connect_timeout=15
)
cur2 = conn2.cursor()

# 既存のドラフトを削除
cur2.execute("SELECT id FROM difficulty_ranks WHERE revision = 'draft'")
draft_ids = [row[0] for row in cur2.fetchall()]
if draft_ids:
    cur2.execute("DELETE FROM difficulty_rank_songs WHERE difficulty_rank_id = ANY(%s)", (draft_ids,))
    cur2.execute("DELETE FROM difficulty_ranks WHERE id = ANY(%s)", (draft_ids,))
    print(f"\n既存ドラフト削除: {len(draft_ids)}件")

# 新しいドラフトを挿入
inserted_ranks = 0
inserted_songs = 0
for rank_data in new_ranks:
    cur2.execute(
        "INSERT INTO difficulty_ranks (rank_value, sort_order, revision) VALUES (%s, %s, 'draft') RETURNING id",
        (rank_data['rank'], rank_data['sort'])
    )
    rank_id = cur2.fetchone()[0]
    inserted_ranks += 1

    for song_order, song_title in enumerate(rank_data['songs']):
        cur2.execute(
            "INSERT INTO difficulty_rank_songs (difficulty_rank_id, song_title, sort_order) VALUES (%s, %s, %s)",
            (rank_id, song_title, song_order)
        )
        inserted_songs += 1

conn2.commit()
cur2.close()
conn2.close()

print(f"\nドラフト保存完了: {inserted_ranks}ランク, {inserted_songs}曲")
print(f"変動楽曲: {len(changes)}件")

# 変動内容をファイルに保存
with open('draft_changes.json', 'w', encoding='utf-8') as f:
    json.dump({
        'changes': changes,
        'total': len(changes)
    }, f, ensure_ascii=False, indent=2)
print("変動内容を draft_changes.json に保存しました")
