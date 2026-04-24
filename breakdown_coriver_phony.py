"""
CORIVER のフォニイ LEGGENDARIA の伸びしろ予測値の算出過程を出力する
使い捨て調査スクリプト。

PairRegressionService の computeGrowthPotential() と同じロジックを
(A, B=フォニイLEG) の組について再現し、寄与する A 譜面ごとの内訳を表示する。

条件:
- ANOTHER / LEGGENDARIA 難易度のみ
- difficulty_level >= 11
- A以上 (score_rate >= 66.67%)
- 回帰採用条件: n >= 30 かつ |r| >= 0.4
"""
import math
import sys
import io
import csv
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

TARGET_DISPLAY = "CORIVER"
TARGET_TITLE = "フォニイ"
TARGET_DIFF = "LEGGENDARIA"  # LEGGENDARIA

MIN_N = 30
MIN_R = 0.4
A_GRADE_RATE = 0.6667

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")


def main():
    conn = psycopg2.connect(**DSN)
    conn.set_client_encoding("UTF8")
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

    # 1) CORIVER のユーザーを特定
    cur.execute(
        "SELECT id, iidx_id, display_name FROM users "
        "WHERE display_name = %s OR iidx_id = %s",
        (TARGET_DISPLAY, TARGET_DISPLAY),
    )
    users = cur.fetchall()
    if not users:
        print(f"ユーザー '{TARGET_DISPLAY}' が見つかりません")
        return
    if len(users) > 1:
        print(f"候補複数: {users}")
    user = users[0]
    user_id = user["id"]
    print(f"Target user: id={user_id} iidx_id={user['iidx_id']} display={user['display_name']}")

    # 2) 譜面定義 (Lv11+ の ANOTHER/LEGG, notes 付き) を取得
    cur.execute(
        "SELECT title, "
        " CASE difficulty WHEN '4' THEN 'ANOTHER' WHEN '10' THEN 'LEGGENDARIA' END AS difficulty_name, "
        " notes "
        "FROM song_definitions "
        "WHERE revision = 'active' AND difficulty IN ('4','10') "
        "  AND level >= 11 AND notes IS NOT NULL AND notes > 0"
    )
    notes_by_key = {}
    for row in cur.fetchall():
        key = (row["title"], row["difficulty_name"])
        notes_by_key[key] = row["notes"]

    target_key = (TARGET_TITLE, TARGET_DIFF)
    if target_key not in notes_by_key:
        print(f"対象譜面 {target_key} が song_definitions にありません")
        # try to search similar titles
        cur.execute(
            "SELECT DISTINCT title FROM song_definitions "
            "WHERE title LIKE %s",
            ("%フォニイ%",),
        )
        print("類似タイトル:", cur.fetchall())
        return
    target_notes = notes_by_key[target_key]
    target_max = target_notes * 2
    print(f"フォニイ LEG notes={target_notes}, maxScore={target_max}")

    # 3) CORIVER の ANOTHER/LEGG スコア全取得 → A 以上のみ残す
    cur.execute(
        "SELECT title, difficulty_name, score, difficulty_level "
        "FROM scores "
        "WHERE user_id = %s AND difficulty_name IN ('ANOTHER','LEGGENDARIA') "
        "  AND score > 0",
        (user_id,),
    )
    my_a_scores = {}  # key -> (score, level)
    for row in cur.fetchall():
        key = (row["title"], row["difficulty_name"])
        notes = notes_by_key.get(key)
        if notes is None:
            continue
        score = row["score"]
        if score < notes * 2 * A_GRADE_RATE:
            continue
        my_a_scores[key] = (score, row["difficulty_level"])
    print(f"CORIVER の A以上 ANOTHER/LEGG 譜面数 = {len(my_a_scores)}")

    # 対象の actualB
    if target_key not in my_a_scores:
        print("CORIVER は フォニイLEG を A 未満。対象外のはず。")
        return
    actual_b = my_a_scores[target_key][0]
    print(f"CORIVER のフォニイLEG 実スコア = {actual_b} ({actual_b/target_max*100:.2f}%)")

    # 4) (A, B) ペア回帰を計算するため、A 以上フィルタを通過した全ユーザースコアを取得
    # PairRegressionService.findAllAnotherLeggScores と同等
    cur.execute(
        "SELECT user_id, title, difficulty_name, score "
        "FROM scores "
        "WHERE difficulty_name IN ('ANOTHER','LEGGENDARIA') "
        "  AND difficulty_level >= 11 AND score >= 400"
    )
    # user_id -> {key: score}  (A 以上のみ残す)
    all_user_scores = defaultdict(dict)
    n_rows = 0
    for row in cur.fetchall():
        key = (row["title"], row["difficulty_name"])
        notes = notes_by_key.get(key)
        if notes is None:
            continue
        score = row["score"]
        if score < notes * 2 * A_GRADE_RATE:
            continue
        all_user_scores[row["user_id"]][key] = score
        n_rows += 1
    print(f"全ユーザーの A以上 スコア行数 = {n_rows}、ユーザー数 = {len(all_user_scores)}")

    # 5) 各 A 譜面について、(A, フォニイLEG) の回帰を計算
    # chartB = target_key、chartA は CORIVER が A 以上を持っている全譜面（対象以外）
    b_scores = []  # (a_key, actualA, slope, intercept, r, n, predicted, weight)
    excluded_no_reg = 0  # 閾値未達で捨てた件数
    excluded_no_samples = 0
    for a_key, (actual_a, _level) in my_a_scores.items():
        if a_key == target_key:
            continue

        # 両譜面を A 以上で持っているユーザーを抽出
        xs = []
        ys = []
        for uid, scores in all_user_scores.items():
            sa = scores.get(a_key)
            sb = scores.get(target_key)
            if sa is None or sb is None:
                continue
            xs.append(sa)
            ys.append(sb)

        n = len(xs)
        if n < 2:
            excluded_no_samples += 1
            continue

        # 単純線形回帰
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
            excluded_no_samples += 1
            continue
        slope = sxy / sxx
        intercept = my - slope * mx
        r = sxy / math.sqrt(sxx * syy)

        if n < MIN_N or abs(r) < MIN_R:
            excluded_no_reg += 1
            continue

        pred = slope * actual_a + intercept
        w = r * r
        b_scores.append({
            "a_title": a_key[0],
            "a_diff": a_key[1],
            "actual_a": actual_a,
            "slope": slope,
            "intercept": intercept,
            "r": r,
            "n": n,
            "predicted_b": pred,
            "weight": w,
            "wp": pred * w,
        })

    print(f"回帰採用ペア (support) = {len(b_scores)}")
    print(f"  -> 閾値未達で除外 = {excluded_no_reg}、サンプル数不足で除外 = {excluded_no_samples}")

    def summarize(pairs, label):
        if not pairs:
            print(f"\n[{label}] 該当なし")
            return
        sum_w = sum(x["weight"] for x in pairs)
        sum_wp = sum(x["wp"] for x in pairs)
        raw = sum_wp / sum_w if sum_w > 0 else float("nan")
        clamped = max(0, min(target_max, raw))
        gap = clamped - actual_b
        # 内訳補足
        over_cnt = sum(1 for x in pairs if x["predicted_b"] > target_max)
        n_min = min(x["n"] for x in pairs)
        n_max = max(x["n"] for x in pairs)
        r_avg = sum(abs(x["r"]) for x in pairs) / len(pairs)
        print(f"\n[{label}]")
        print(f"  support (採用ペア)    = {len(pairs)}   (n範囲 {n_min}-{n_max}, |r|平均 {r_avg:.3f})")
        print(f"  Σw                    = {sum_w:.4f}")
        print(f"  Σw·pred               = {sum_wp:.4f}")
        print(f"  raw prediction        = {raw:.4f}")
        print(f"  clamp(0, {target_max}) 後  = {clamped:.4f}")
        print(f"  actualB               = {actual_b}")
        print(f"  gap                   = {gap:.4f}")
        print(f"  pred > maxScore のペア= {over_cnt} / {len(pairs)}")

    # 6) n 閾値の違いによる比較
    summarize(b_scores, "現行: n≥30 (全体)")
    summarize([x for x in b_scores if x["n"] <= 100], "n ≤ 100 のペアだけ使用")
    summarize([x for x in b_scores if x["n"] >= 100], "n ≥ 100 のペアだけ使用")
    summarize([x for x in b_scores if x["n"] >= 200], "参考: n ≥ 200 のペアだけ使用")
    summarize([x for x in b_scores if x["n"] >= 300], "参考: n ≥ 300 のペアだけ使用")

    # |r| 閾値を厳しくしてみる
    for thr in (0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.92, 0.94, 0.96, 0.97):
        summarize([x for x in b_scores if abs(x["r"]) >= thr], f"|r| ≥ {thr} のペアだけ使用")

    # 参考: |r| の分布
    print("\n=== |r| の分布 ===")
    bins = [(0.4, 0.5), (0.5, 0.6), (0.6, 0.7), (0.7, 0.8),
            (0.8, 0.85), (0.85, 0.9), (0.9, 0.92),
            (0.92, 0.94), (0.94, 0.96), (0.96, 0.98), (0.98, 1.01)]
    for lo, hi in bins:
        cnt = sum(1 for x in b_scores if lo <= abs(x["r"]) < hi)
        print(f"  [{lo:.2f}, {hi:.2f}) : {cnt}")

    # 以降の詳細表示は元データに戻して実施
    # 6b) 加重平均
    sum_w = sum(x["weight"] for x in b_scores)
    sum_wp = sum(x["wp"] for x in b_scores)
    raw_pred = sum_wp / sum_w if sum_w > 0 else None

    # 7) 上位寄与 & 「予測が maxScore を超える要因」上位を抽出
    b_scores.sort(key=lambda x: x["wp"], reverse=True)

    print()
    print("=== 寄与度 (w·pred) 上位 20 件 ===")
    print(f"{'#':>3} {'r':>6} {'n':>5} {'slope':>8} {'intercept':>10} {'actualA':>8} {'pred':>8} {'w':>6} {'w*pred':>10}  title (diff)")
    for i, x in enumerate(b_scores[:20], 1):
        print(f"{i:>3} {x['r']:>6.3f} {x['n']:>5} {x['slope']:>8.4f} {x['intercept']:>10.2f} "
              f"{x['actual_a']:>8} {x['predicted_b']:>8.1f} {x['weight']:>6.3f} {x['wp']:>10.2f}  "
              f"{x['a_title']} ({x['a_diff']})")

    # 予測が maxScore を超えている A 譜面
    over_max = [x for x in b_scores if x["predicted_b"] > target_max]
    print()
    print(f"=== 予測値が maxScore({target_max}) を超えている寄与 = {len(over_max)} 件 ===")
    over_max.sort(key=lambda x: x["predicted_b"], reverse=True)
    for i, x in enumerate(over_max[:20], 1):
        print(f"{i:>3} {x['r']:>6.3f} {x['n']:>5} {x['slope']:>8.4f} {x['intercept']:>10.2f} "
              f"{x['actual_a']:>8} {x['predicted_b']:>8.1f} {x['weight']:>6.3f} "
              f"{x['a_title']} ({x['a_diff']})")

    # 8) 全件を CSV 出力
    csv_path = "coriver_phony_breakdown.csv"
    with open(csv_path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["rank_by_wp", "a_title", "a_diff", "actual_a", "slope", "intercept",
                    "r", "n", "predicted_b", "weight_r2", "w_times_pred", "predicted_over_max"])
        for i, x in enumerate(b_scores, 1):
            w.writerow([i, x["a_title"], x["a_diff"], x["actual_a"],
                        f"{x['slope']:.6f}", f"{x['intercept']:.4f}", f"{x['r']:.4f}",
                        x["n"], f"{x['predicted_b']:.4f}", f"{x['weight']:.6f}",
                        f"{x['wp']:.4f}", x["predicted_b"] > target_max])
    print(f"\n全 {len(b_scores)} 件を {csv_path} に出力しました")

    cur.close()
    conn.close()


if __name__ == "__main__":
    main()
