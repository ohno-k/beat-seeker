"""
複数の (ユーザー, 譜面B) について、|r| 閾値を上げたときに
gap がどう変化するかを検証する。

「rを上げるとgapが下がる」という観察が CORIVER × フォニイLEG 固有の現象か、
全プレイヤー×全譜面で一般に成り立つか確かめる。

ケース選定:
  - 対象 B 譜面: 数本 (難易度・密度が違うものを混ぜる)
  - 各 B について、その譜面を A 以上で持っているユーザーから
    actualB のパーセンタイル別 (top/mid/bottom) に1人ずつ抽出
"""
import math
import sys
import io
from collections import defaultdict
import psycopg2
import psycopg2.extras

DSN = {
    "host": "dpg-d6f68314tr6s73bnbhag-a.oregon-postgres.render.com",
    "dbname": "beatseeker",
    "user": "postgress",
    "password": "kAw2xymPeLH4mOZuV76hsJCR4L9kFkgM",
    "port": 5432,
    "sslmode": "require",
    "connect_timeout": 30,
}
A_GRADE_RATE = 0.6667
MIN_N = 30
R_THRESHOLDS = [0.4, 0.7, 0.8, 0.85, 0.9, 0.92, 0.94, 0.96]

# 検証する譜面 B のリスト
TARGET_CHARTS = [
    ("フォニイ", "LEGGENDARIA"),
    ("KING", "LEGGENDARIA"),
    ("Plan 8", "ANOTHER"),
    ("RED ZONE", "LEGGENDARIA"),
    ("Valanga", "ANOTHER"),
]

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")


def compute_regression(xs, ys):
    n = len(xs)
    if n < 2:
        return None
    sum_x = sum(xs)
    sum_y = sum(ys)
    sum_xx = sum(x * x for x in xs)
    sum_yy = sum(y * y for y in ys)
    sum_xy = sum(x * y for x, y in zip(xs, ys))
    mx = sum_x / n
    my = sum_y / n
    sxx = sum_xx - mx * sum_x
    syy = sum_yy - my * sum_y
    sxy = sum_xy - mx * sum_y
    if sxx <= 0 or syy <= 0:
        return None
    slope = sxy / sxx
    intercept = my - slope * mx
    r = sxy / math.sqrt(sxx * syy)
    return n, slope, intercept, r


def main():
    conn = psycopg2.connect(**DSN)
    conn.set_client_encoding("UTF8")
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

    # 1) 譜面定義 (notes)
    cur.execute(
        "SELECT title, "
        " CASE difficulty WHEN '4' THEN 'ANOTHER' WHEN '10' THEN 'LEGGENDARIA' END AS dn, "
        " notes "
        "FROM song_definitions "
        "WHERE revision='active' AND difficulty IN ('4','10') "
        "  AND level >= 11 AND notes IS NOT NULL AND notes > 0"
    )
    notes_by_key = {(r["title"], r["dn"]): r["notes"] for r in cur.fetchall()}

    # 2) 全ユーザーの A以上 ANOTHER/LEGG スコア
    cur.execute(
        "SELECT user_id, title, difficulty_name, score "
        "FROM scores "
        "WHERE difficulty_name IN ('ANOTHER','LEGGENDARIA') "
        "  AND difficulty_level >= 11 AND score >= 400"
    )
    all_user_scores = defaultdict(dict)
    for row in cur.fetchall():
        key = (row["title"], row["difficulty_name"])
        notes = notes_by_key.get(key)
        if notes is None:
            continue
        score = row["score"]
        if score < notes * 2 * A_GRADE_RATE:
            continue
        all_user_scores[row["user_id"]][key] = score
    print(f"全ユーザー数 (A以上を1譜面以上持つ) = {len(all_user_scores)}")

    # 3) 各 B 譜面について検証
    for b_key in TARGET_CHARTS:
        if b_key not in notes_by_key:
            print(f"\n##### {b_key} 譜面定義なし、スキップ #####")
            continue
        b_notes = notes_by_key[b_key]
        b_max = b_notes * 2

        # B を A 以上で持つユーザーのスコア分布
        b_users = [(uid, scores[b_key]) for uid, scores in all_user_scores.items() if b_key in scores]
        if len(b_users) < 30:
            print(f"\n##### {b_key} はサンプル {len(b_users)} 人で少なすぎ、スキップ #####")
            continue
        b_users.sort(key=lambda x: x[1])

        # top/mid/bottom 各1人 + CORIVER (id=23 if applies)
        nu = len(b_users)
        cases = []
        cases.append(("bottom", b_users[int(nu * 0.10)][0], b_users[int(nu * 0.10)][1]))
        cases.append(("mid",    b_users[int(nu * 0.50)][0], b_users[int(nu * 0.50)][1]))
        cases.append(("top",    b_users[int(nu * 0.90)][0], b_users[int(nu * 0.90)][1]))
        # CORIVER 追加 (このBを持っていれば)
        coriver_b = next((s for u, s in b_users if u == 23), None)
        if coriver_b is not None:
            cases.append(("CORIVER", 23, coriver_b))

        # 4) この B に対する全 A の回帰を一度だけ計算 (ペア回帰キャッシュ相当)
        # B を A 以上で持っているユーザーの全スコアを使って (A → reg) を作る
        relevant_users = {uid: all_user_scores[uid] for uid, _ in b_users}
        # A 候補 = relevant_users が A 以上で持っている全譜面
        a_candidates = set()
        for scores in relevant_users.values():
            a_candidates.update(scores.keys())
        a_candidates.discard(b_key)

        regs = {}  # a_key -> (n, slope, intercept, r)
        for a_key in a_candidates:
            xs, ys = [], []
            for uid, scores in relevant_users.items():
                sa = scores.get(a_key)
                sb = scores.get(b_key)
                if sa is None or sb is None:
                    continue
                xs.append(sa); ys.append(sb)
            if len(xs) < MIN_N:
                continue
            res = compute_regression(xs, ys)
            if res is None:
                continue
            n, slope, intercept, r = res
            if abs(r) < 0.4:
                continue
            regs[a_key] = (n, slope, intercept, r)

        print(f"\n##### B = {b_key[0]} ({b_key[1]}), maxScore={b_max} #####")
        print(f"  Bを持つユーザー数 = {nu}, |r|≥0.4 を通過した A 候補 = {len(regs)}")

        # 5) 各ケースで r 閾値スイープ
        rate_pct = lambda s: s * 100 / b_max
        header_users = " | ".join(f"{lbl}(uid={uid}, actual={s}/{b_max}={rate_pct(s):.1f}%)"
                                  for lbl, uid, s in cases)
        print(f"  cases: {header_users}")

        # 各 r 閾値で gap を出力
        print(f"  {'|r|≥':>6} | " + " | ".join(f"{lbl:>10}" for lbl, _, _ in cases) + " | (support, raw_pred, clamped, gap) per case")
        for thr in R_THRESHOLDS:
            row_parts = []
            detail_parts = []
            for lbl, uid, actual_b in cases:
                user_scores = all_user_scores[uid]
                # 自分が A 以上で持っている A 譜面 ∩ regs.keys
                sum_w = 0.0
                sum_wp = 0.0
                support = 0
                for a_key, (n, slope, intercept, r) in regs.items():
                    if abs(r) < thr:
                        continue
                    actual_a = user_scores.get(a_key)
                    if actual_a is None:
                        continue
                    pred = slope * actual_a + intercept
                    w = r * r
                    sum_w += w
                    sum_wp += pred * w
                    support += 1
                if sum_w <= 0 or support < 3:
                    row_parts.append("   N/A    ")
                    detail_parts.append(f"{lbl}: support={support} N/A")
                    continue
                raw = sum_wp / sum_w
                clamped = max(0, min(b_max, raw))
                gap = clamped - actual_b
                row_parts.append(f"{gap:>+8.1f} ")
                detail_parts.append(f"{lbl}: sup={support} raw={raw:.1f} clamp={clamped:.1f} gap={gap:+.1f}")
            print(f"  {thr:>6.2f} | " + " | ".join(row_parts))

    cur.close()
    conn.close()


if __name__ == "__main__":
    main()
