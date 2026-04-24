"""
新方針シミュレーション:
  1) 第一段階: |r|≧0.95 で予測。support>=3 なら採用 (HIGH 精度)
  2) フォールバック: support<3 なら |r|≧0.90 にして再予測。support>=3 なら採用 (LOW 精度)
  3) 採用された予測について gap = (pred - actualB) を係数倍して表示

重い回帰計算は pickle にキャッシュしておき、係数違いの試行を高速にする。
"""
import math
import sys
import io
import time
import os
import pickle
from collections import defaultdict
import psycopg2
import psycopg2.extras

DSN = {
    "host": "dpg-d6f68314tr6s73bnbhag-a.oregon-postgres.render.com",
    "dbname": "beatseeker",
    "user": "postgress",
    "password": "kAw2xymPeLH4mOZuV76hsJCR4L9kFkgM",
    "port": 5432, "sslmode": "require", "connect_timeout": 30,
}
A_GRADE_RATE = 0.6667
MIN_N = 30
SUPPORT_MIN = 3
PRIMARY_R = 0.95
FALLBACK_R = 0.90
GAP_COEFFICIENTS = [1.0, 1.2, 1.5]   # 比較する係数
CACHE_FILE = "regs_cache.pkl"

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")


def load_or_build_cache():
    if os.path.exists(CACHE_FILE):
        print(f"キャッシュ {CACHE_FILE} を読み込み中…")
        t = time.time()
        with open(CACHE_FILE, "rb") as f:
            data = pickle.load(f)
        print(f"  読み込み完了 ({time.time()-t:.1f}s)")
        return data

    t0 = time.time()
    conn = psycopg2.connect(**DSN)
    conn.set_client_encoding("UTF8")
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

    cur.execute(
        "SELECT title, "
        " CASE difficulty WHEN '4' THEN 'ANOTHER' WHEN '10' THEN 'LEGGENDARIA' END AS dn, "
        " notes "
        "FROM song_definitions "
        "WHERE revision='active' AND difficulty IN ('4','10') "
        "  AND level >= 11 AND notes IS NOT NULL AND notes > 0"
    )
    notes_by_key = {(r["title"], r["dn"]): r["notes"] for r in cur.fetchall()}
    keys = sorted(notes_by_key.keys())
    key_to_idx = {k: i for i, k in enumerate(keys)}
    notes_arr = [notes_by_key[k] for k in keys]

    cur.execute(
        "SELECT user_id, title, difficulty_name, score "
        "FROM scores "
        "WHERE difficulty_name IN ('ANOTHER','LEGGENDARIA') "
        "  AND difficulty_level >= 11 AND score >= 400"
    )
    user_scores = defaultdict(dict)
    for row in cur.fetchall():
        key = (row["title"], row["difficulty_name"])
        idx = key_to_idx.get(key)
        if idx is None:
            continue
        score = row["score"]
        if score < notes_arr[idx] * 2 * A_GRADE_RATE:
            continue
        user_scores[row["user_id"]][idx] = score

    cur.execute("SELECT id, COALESCE(display_name, iidx_id) AS name FROM users")
    user_name = {r["id"]: r["name"] for r in cur.fetchall()}
    cur.close()
    conn.close()
    print(f"DB読み込み完了 (譜面={len(keys)}, ユーザー={len(user_scores)}, {time.time()-t0:.1f}s)")

    t1 = time.time()
    acc = defaultdict(lambda: [0, 0.0, 0.0, 0.0, 0.0, 0.0])
    for uid, scores in user_scores.items():
        items = list(scores.items())
        for ai, asc in items:
            for bi, bsc in items:
                if ai == bi:
                    continue
                v = acc[(ai, bi)]
                v[0] += 1
                v[1] += asc
                v[2] += bsc
                v[3] += asc * asc
                v[4] += bsc * bsc
                v[5] += asc * bsc
    print(f"全ペア累積 完了 ({len(acc)} ペア, {time.time()-t1:.1f}s)")

    t2 = time.time()
    regs = {}
    for k, v in acc.items():
        n = v[0]
        if n < MIN_N:
            continue
        mx = v[1] / n
        my = v[2] / n
        sxx = v[3] - mx * v[1]
        syy = v[4] - my * v[2]
        sxy = v[5] - mx * v[2]
        if sxx <= 0 or syy <= 0:
            continue
        slope = sxy / sxx
        intercept = my - slope * mx
        r = sxy / math.sqrt(sxx * syy)
        # フォールバック含めて FALLBACK_R 未満は使わないので捨てる
        if abs(r) < FALLBACK_R:
            continue
        regs[k] = (slope, intercept, r, n)
    acc = None
    print(f"回帰計算完了 ({len(regs)} ペア, {time.time()-t2:.1f}s)")

    data = {
        "notes_arr": notes_arr,
        "key_to_idx": key_to_idx,
        "keys": keys,
        "user_scores": dict(user_scores),
        "user_name": user_name,
        "regs": regs,
    }
    with open(CACHE_FILE, "wb") as f:
        pickle.dump(data, f, protocol=pickle.HIGHEST_PROTOCOL)
    print(f"キャッシュを {CACHE_FILE} に保存")
    return data


def main():
    data = load_or_build_cache()
    notes_arr = data["notes_arr"]
    key_to_idx = data["key_to_idx"]
    user_scores = data["user_scores"]
    user_name = data["user_name"]
    regs = data["regs"]

    # 各 (user, B) について 0.95/0.90 両方を計算
    t3 = time.time()
    per_user = defaultdict(list)  # uid -> list of dict
    for uid, scores in user_scores.items():
        score_items = list(scores.items())
        for b_idx, actual_b in score_items:
            max_b = notes_arr[b_idx] * 2
            sw95 = swp95 = 0.0; sup95 = 0
            sw90 = swp90 = 0.0; sup90 = 0
            for a_idx, actual_a in score_items:
                if a_idx == b_idx:
                    continue
                reg = regs.get((a_idx, b_idx))
                if reg is None:
                    continue
                slope, intercept, r, _n = reg
                ar = abs(r)
                # 0.90 以上は両方に積む
                if ar >= FALLBACK_R:
                    pred = slope * actual_a + intercept
                    w = r * r
                    wp = pred * w
                    sw90 += w; swp90 += wp; sup90 += 1
                    if ar >= PRIMARY_R:
                        sw95 += w; swp95 += wp; sup95 += 1

            mode = None
            sup = 0; raw = None; clamp_v = None
            if sup95 >= SUPPORT_MIN and sw95 > 0:
                mode = "HIGH"   # 0.95 採用
                sup = sup95
                raw = swp95 / sw95
                clamp_v = max(0, min(max_b, raw))
            elif sup90 >= SUPPORT_MIN and sw90 > 0:
                mode = "LOW"    # 0.90 フォールバック
                sup = sup90
                raw = swp90 / sw90
                clamp_v = max(0, min(max_b, raw))

            if mode is None:
                continue  # 表示対象外

            base_gap = clamp_v - actual_b   # 係数=1 のときの gap (素のクランプ後)
            per_user[uid].append({
                "b_idx": b_idx, "actual_b": actual_b, "max_b": max_b,
                "mode": mode, "sup": sup, "raw": raw, "clamp": clamp_v,
                "base_gap": base_gap,
            })
    print(f"予測計算完了 ({time.time()-t3:.1f}s)\n")

    # 0.4 単独 (現行) も比較用に再計算
    print("0.4 (現行) ベースラインを別途計算中…")
    t4 = time.time()
    # 0.4 は regs に入っていない (FALLBACK_R=0.90 で打ち切り済み) ので別途累積し直す
    # ここでは省略し、前回シミュレーションの数値を引用する
    per_user_04 = None
    print(f"  (省略: 全体サマリは前回のシミュレーションを参照: visible=396576, gap平均=70.6)")

    # ========= 集計1: 全体サマリ =========
    print()
    print("===== 全体サマリ (0.95 + 0.90フォールバック) =====")
    visible = 0
    high_cnt = 0; low_cnt = 0
    base_pos_gaps = []
    for uid, entries in per_user.items():
        for e in entries:
            visible += 1
            if e["mode"] == "HIGH": high_cnt += 1
            else: low_cnt += 1
            if e["base_gap"] > 0:
                base_pos_gaps.append(e["base_gap"])

    def median(xs):
        if not xs: return 0
        xs = sorted(xs); n = len(xs)
        return xs[n//2] if n % 2 else (xs[n//2-1]+xs[n//2])/2

    base_avg = sum(base_pos_gaps)/len(base_pos_gaps) if base_pos_gaps else 0
    base_med = median(base_pos_gaps)
    print(f"表示件数: {visible}  (うち HIGH={high_cnt}  LOW={low_cnt})")
    print(f"  HIGH 比率: {high_cnt*100/max(1,visible):.1f}%")
    print(f"  比較: 0.95単独={286002}, 0.90単独={392244}, 0.4(現行)={396576}")
    print(f"  → フォールバック有り: {visible} (0.95単独より {visible-286002:+}, 0.90単独より {visible-392244:+})")
    print()
    print(f"係数=1.0 (素の gap):")
    print(f"  正gap件数={len(base_pos_gaps)}, 平均={base_avg:.2f}, 中央値={base_med:.2f}")

    # 係数別の gap 分布 + クランプ後再評価
    print()
    print("===== 係数別の gap 分布 (係数掛けてから再クランプ) =====")
    print(f"{'coef':>5} | {'pos_cnt':>8} {'gap平均':>8} {'gap中央':>8} {'gap最大':>8}  vs現行0.4平均(70.6)")
    for coef in GAP_COEFFICIENTS:
        gaps = []
        for uid, entries in per_user.items():
            for e in entries:
                # predicted_new = actualB + (clamp - actualB) * coef、ただし maxB でクランプ
                new_pred = e["actual_b"] + e["base_gap"] * coef
                new_pred = max(0, min(e["max_b"], new_pred))
                g = new_pred - e["actual_b"]
                if g > 0:
                    gaps.append(g)
        avg = sum(gaps)/len(gaps) if gaps else 0
        med = median(gaps)
        mx = max(gaps) if gaps else 0
        print(f"  {coef:>4.1f} | {len(gaps):>8} {avg:>8.2f} {med:>8.2f} {mx:>8.0f}  ({avg/70.6*100:.0f}%)")

    # ========= 集計2: 主要 B 譜面ごと =========
    print()
    print("===== 主要 B 譜面ごとの内訳 =====")
    target_bs = [("フォニイ","LEGGENDARIA"), ("KING","LEGGENDARIA"),
                 ("Plan 8","ANOTHER"), ("RED ZONE","LEGGENDARIA"),
                 ("Valanga","ANOTHER"), ("Apocalypse","ANOTHER"),
                 ("海神","ANOTHER")]
    for tk in target_bs:
        if tk not in key_to_idx:
            continue
        bi = key_to_idx[tk]
        users_high = 0; users_low = 0
        gaps_base = []
        for uid, entries in per_user.items():
            for e in entries:
                if e["b_idx"] != bi: continue
                if e["mode"] == "HIGH": users_high += 1
                else: users_low += 1
                gaps_base.append(e["base_gap"])
        total = users_high + users_low
        if total == 0:
            print(f"{tk[0]} ({tk[1]}): 該当なし")
            continue
        avg = sum(gaps_base)/len(gaps_base) if gaps_base else 0
        # 係数掛け後の平均 (参考)
        gaps_x12 = []
        gaps_x15 = []
        max_b = None
        for uid, entries in per_user.items():
            for e in entries:
                if e["b_idx"] != bi: continue
                max_b = e["max_b"]
                np12 = max(0, min(e["max_b"], e["actual_b"] + e["base_gap"] * 1.2))
                np15 = max(0, min(e["max_b"], e["actual_b"] + e["base_gap"] * 1.5))
                gaps_x12.append(np12 - e["actual_b"])
                gaps_x15.append(np15 - e["actual_b"])
        avg12 = sum(gaps_x12)/len(gaps_x12)
        avg15 = sum(gaps_x15)/len(gaps_x15)
        print(f"{tk[0]} ({tk[1]}, maxB={max_b}):")
        print(f"  total={total}  HIGH={users_high}  LOW={users_low}  ({users_low*100/total:.0f}% がフォールバック)")
        print(f"  base_gap平均={avg:+.2f}  ×1.2平均={avg12:+.2f}  ×1.5平均={avg15:+.2f}")

    # ========= 集計3: 個別ユーザー =========
    print()
    print("===== 個別ユーザー (上位50件 gap 合計) =====")
    samples = [23, 156, 196, 416, 243, 376]  # 前回と同じ顔ぶれ
    print(f"{'uid':>5} {'name':<18} {'visible':>7} {'HIGH':>5} {'LOW':>5}  "
          f"{'top50_x1.0':>10} {'top50_x1.2':>10} {'top50_x1.5':>10}")
    for uid in samples:
        entries = per_user.get(uid, [])
        if not entries:
            print(f"{uid:>5} {'?':<18} (no data)")
            continue
        nm = (user_name.get(uid, "?") or "?")[:18]
        h = sum(1 for e in entries if e["mode"] == "HIGH")
        l = sum(1 for e in entries if e["mode"] == "LOW")
        def topsum(coef):
            gs = []
            for e in entries:
                np = max(0, min(e["max_b"], e["actual_b"] + e["base_gap"] * coef))
                g = np - e["actual_b"]
                if g > 0:
                    gs.append(g)
            return sum(sorted(gs, reverse=True)[:50])
        print(f"{uid:>5} {nm:<18} {len(entries):>7} {h:>5} {l:>5}  "
              f"{topsum(1.0):>10.0f} {topsum(1.2):>10.0f} {topsum(1.5):>10.0f}")

    # ========= 集計4: CORIVER × フォニイLEG =========
    print()
    print("===== CORIVER × フォニイLEG =====")
    for e in per_user.get(23, []):
        if e["b_idx"] != key_to_idx.get(("フォニイ","LEGGENDARIA")):
            continue
        print(f"mode={e['mode']}, support={e['sup']}, raw={e['raw']:.2f}, clamp={e['clamp']:.2f}")
        print(f"actualB={e['actual_b']}, maxB={e['max_b']}, base_gap={e['base_gap']:+.2f}")
        for coef in GAP_COEFFICIENTS:
            np = max(0, min(e["max_b"], e["actual_b"] + e["base_gap"] * coef))
            g = np - e["actual_b"]
            print(f"  係数 {coef}: predicted={np:.2f}, gap={g:+.2f}")

    # ========= 集計5: フォールバック発動率の高い B 譜面 =========
    print()
    print("===== フォールバック (LOW) 比率が高い B 譜面 上位 15 =====")
    by_chart = defaultdict(lambda: [0, 0])  # b_idx -> [high, low]
    for uid, entries in per_user.items():
        for e in entries:
            if e["mode"] == "HIGH": by_chart[e["b_idx"]][0] += 1
            else: by_chart[e["b_idx"]][1] += 1
    # 全表示なし (=0.95でも0.90でもsupport不足) は別途
    rows = []
    for bi, (h, l) in by_chart.items():
        total = h + l
        if total < 30: continue  # ノイズ除外
        rows.append((bi, h, l, total, l/total))
    rows.sort(key=lambda x: x[4], reverse=True)
    keys_list = data["keys"]
    for bi, h, l, total, ratio in rows[:15]:
        title, diff = keys_list[bi]
        print(f"  LOW率 {ratio*100:>5.1f}%  HIGH={h:>4} LOW={l:>4}  {title} ({diff})")


if __name__ == "__main__":
    main()
