"""
MIN_R を 0.95 に上げた場合の全ユーザー × 全譜面 シミュレーション。

PairRegressionService.computeGrowthPotential() と同じロジックで
現行 (|r|≧0.4) と 0.95 の両方を一度に計算し、
譜面/ユーザー/全体レベルで影響を集計する。

出力:
  - 全体: 表示される (user, chart) 件数, gap 平均/中央値の変化
  - 表示から消える件数 (support<3 で非表示になる)
  - フォニイLEG など個別 B 譜面の影響
  - ユーザー個別: 上位50件の合計 gap がどう変わるか
"""
import math
import sys
import io
import time
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
R_LO = 0.4
R_THRESHOLDS = [0.4, 0.85, 0.90, 0.92, 0.95]  # 0.4=現行 と比較
SUPPORT_MIN = 3

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")


def main():
    t0 = time.time()
    conn = psycopg2.connect(**DSN)
    conn.set_client_encoding("UTF8")
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

    # 1) 譜面定義
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
    print(f"対象譜面 {len(keys)} 本")

    # 2) 全ユーザーの A以上 スコア
    cur.execute(
        "SELECT user_id, title, difficulty_name, score "
        "FROM scores "
        "WHERE difficulty_name IN ('ANOTHER','LEGGENDARIA') "
        "  AND difficulty_level >= 11 AND score >= 400"
    )
    user_scores = defaultdict(dict)  # uid -> {chart_idx: score}
    for row in cur.fetchall():
        key = (row["title"], row["difficulty_name"])
        idx = key_to_idx.get(key)
        if idx is None:
            continue
        score = row["score"]
        if score < notes_arr[idx] * 2 * A_GRADE_RATE:
            continue
        user_scores[row["user_id"]][idx] = score

    # users -> 表示名
    cur.execute("SELECT id, COALESCE(display_name, iidx_id) AS name FROM users")
    user_name = {r["id"]: r["name"] for r in cur.fetchall()}

    cur.close()
    conn.close()
    print(f"対象ユーザー {len(user_scores)} 人 (DB読み込み {time.time()-t0:.1f}s)")

    # 3) 全ペア(A,B)の累積。(A,B) と (B,A) はそれぞれ別エントリで保持
    # acc[a_idx, b_idx] = [n, sumX, sumY, sumXX, sumYY, sumXY]
    t1 = time.time()
    acc = defaultdict(lambda: [0, 0.0, 0.0, 0.0, 0.0, 0.0])
    for uid, scores in user_scores.items():
        items = list(scores.items())  # [(idx, score)]
        for i, (ai, asc) in enumerate(items):
            for j, (bi, bsc) in enumerate(items):
                if ai == bi:
                    continue
                v = acc[(ai, bi)]
                v[0] += 1
                v[1] += asc
                v[2] += bsc
                v[3] += asc * asc
                v[4] += bsc * bsc
                v[5] += asc * bsc
    print(f"全ペア累積 完了 ({len(acc)} 有向ペア, {time.time()-t1:.1f}s)")

    # 4) 各ペアの回帰係数を計算 (n>=30 のみ残す。r フィルタは予測時に行う)
    t2 = time.time()
    regs = {}  # (a_idx, b_idx) -> (slope, intercept, r, n)
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
        regs[k] = (slope, intercept, r, n)
    acc = None
    print(f"回帰計算完了 ({len(regs)} ペア, {time.time()-t2:.1f}s)")

    # 5) 各ユーザー × B について、各 r 閾値で予測 & gap
    # per_user[uid] = list of dict: {b_idx, actual_b, max_b,
    #   per_thr: {thr: {sup, sw, raw, clamp, gap}}}
    t3 = time.time()
    THRS = R_THRESHOLDS
    per_user = defaultdict(list)
    for uid, scores in user_scores.items():
        score_items = list(scores.items())
        for b_idx, actual_b in score_items:
            max_b = notes_arr[b_idx] * 2
            sw = {thr: 0.0 for thr in THRS}
            swp = {thr: 0.0 for thr in THRS}
            sup = {thr: 0 for thr in THRS}
            for a_idx, actual_a in score_items:
                if a_idx == b_idx:
                    continue
                reg = regs.get((a_idx, b_idx))
                if reg is None:
                    continue
                slope, intercept, r, _n = reg
                ar = abs(r)
                if ar < THRS[0]:
                    continue
                pred = slope * actual_a + intercept
                w = r * r
                wp = pred * w
                for thr in THRS:
                    if ar >= thr:
                        sw[thr] += w
                        swp[thr] += wp
                        sup[thr] += 1
            per_thr = {}
            for thr in THRS:
                if sup[thr] >= SUPPORT_MIN and sw[thr] > 0:
                    raw = swp[thr] / sw[thr]
                    clamp = max(0, min(max_b, raw))
                    per_thr[thr] = {
                        "sup": sup[thr], "raw": raw,
                        "clamp": clamp, "gap": clamp - actual_b,
                    }
                else:
                    per_thr[thr] = None
            per_user[uid].append({
                "b_idx": b_idx, "actual_b": actual_b, "max_b": max_b,
                "per_thr": per_thr,
            })
    print(f"予測計算完了 ({time.time()-t3:.1f}s)\n")

    THRS = R_THRESHOLDS

    def median(xs):
        if not xs: return None
        xs = sorted(xs); n = len(xs)
        return xs[n//2] if n % 2 else (xs[n//2-1]+xs[n//2])/2

    # ===========================
    # 集計1: 全体サマリ (各閾値ごと)
    # ===========================
    print("===== 全体サマリ (各 |r| 閾値) =====")
    print(f"{'thr':>6} | {'visible':>8} {'lost vs 0.4':>12} {'clamp件数':>10} {'clamp%':>7} | "
          f"{'pos_cnt':>8} {'gap平均':>8} {'gap中央':>8} {'gap最大':>8}")
    base_visible = None
    for thr in THRS:
        visible = 0
        clamp_cnt = 0
        pos_gaps = []
        for uid, entries in per_user.items():
            for e in entries:
                d = e["per_thr"].get(thr)
                if d is None:
                    continue
                visible += 1
                if d["raw"] > e["max_b"] - 0.5:
                    clamp_cnt += 1
                if d["gap"] > 0:
                    pos_gaps.append(d["gap"])
        if base_visible is None:
            base_visible = visible
            lost_str = "-"
        else:
            lost_str = f"{visible - base_visible:+}"
        gap_avg = sum(pos_gaps)/len(pos_gaps) if pos_gaps else 0
        gap_med = median(pos_gaps) or 0
        gap_max = max(pos_gaps) if pos_gaps else 0
        clamp_pct = clamp_cnt * 100 / max(1, visible)
        print(f"  {thr:>4.2f} | {visible:>8} {lost_str:>12} {clamp_cnt:>10} {clamp_pct:>6.1f}% | "
              f"{len(pos_gaps):>8} {gap_avg:>8.2f} {gap_med:>8.2f} {gap_max:>8.0f}")

    # ===========================
    # 集計2: 主要 B 譜面ごとの影響 (各閾値ごと)
    # ===========================
    print()
    print("===== 主要 B 譜面ごとの影響 (各 |r| 閾値) =====")
    target_bs = [("フォニイ","LEGGENDARIA"), ("KING","LEGGENDARIA"),
                 ("Plan 8","ANOTHER"), ("RED ZONE","LEGGENDARIA"),
                 ("Valanga","ANOTHER"), ("Apocalypse","ANOTHER"),
                 ("海神","ANOTHER")]
    for tk in target_bs:
        if tk not in key_to_idx:
            continue
        bi = key_to_idx[tk]
        print(f"\n{tk[0]} ({tk[1]}):")
        print(f"  {'thr':>6} | {'users':>6} {'gap平均':>8} {'正gap':>6} {'clamp':>6}")
        for thr in THRS:
            gaps = []
            clamp_cnt = 0
            for uid, entries in per_user.items():
                for e in entries:
                    if e["b_idx"] != bi: continue
                    d = e["per_thr"].get(thr)
                    if d is None: continue
                    gaps.append(d["gap"])
                    if d["raw"] > e["max_b"] - 0.5:
                        clamp_cnt += 1
            if not gaps:
                print(f"  {thr:>4.2f} | {'  N/A':>6}")
                continue
            avg = sum(gaps) / len(gaps)
            pos_cnt = sum(1 for g in gaps if g > 0)
            print(f"  {thr:>4.2f} | {len(gaps):>6} {avg:>+8.1f} {pos_cnt:>6} {clamp_cnt:>6}")

    # ===========================
    # 集計3: 個別ユーザーの上位50件 gap 合計の変化
    # ===========================
    print()
    print("===== 個別ユーザー (上位50件 gap 合計) の変化 =====")
    samples = [23]  # CORIVER
    user_total04 = {}
    for uid, entries in per_user.items():
        positives = sorted(
            [e["per_thr"][THRS[0]]["gap"] for e in entries
             if e["per_thr"].get(THRS[0]) is not None and e["per_thr"][THRS[0]]["gap"] > 0],
            reverse=True)[:50]
        if positives:
            user_total04[uid] = sum(positives)
    sorted_users = sorted(user_total04.items(), key=lambda x: x[1])
    if sorted_users:
        samples.append(sorted_users[len(sorted_users)//4][0])
        samples.append(sorted_users[len(sorted_users)//2][0])
        samples.append(sorted_users[len(sorted_users)*3//4][0])
        samples.append(sorted_users[-1][0])
        samples.append(sorted_users[0][0])
    samples = list(dict.fromkeys(samples))

    header = f"{'uid':>5} {'name':<20}"
    for thr in THRS:
        header += f" {('top50_'+str(thr)):>11}"
    for thr in THRS:
        header += f" {('vis_'+str(thr)):>9}"
    print(header)
    for uid in samples:
        entries = per_user.get(uid, [])
        nm = (user_name.get(uid, "?") or "?")[:20]
        line = f"{uid:>5} {nm:<20}"
        for thr in THRS:
            top = sorted(
                [e["per_thr"][thr]["gap"] for e in entries
                 if e["per_thr"].get(thr) is not None and e["per_thr"][thr]["gap"] > 0],
                reverse=True)[:50]
            line += f" {sum(top):>11.0f}"
        for thr in THRS:
            vis = sum(1 for e in entries if e["per_thr"].get(thr) is not None)
            line += f" {vis:>9}"
        print(line)

    # ===========================
    # 集計4: CORIVER × フォニイLEG の各閾値での詳細
    # ===========================
    print()
    print("===== CORIVER × フォニイLEG の各閾値での詳細 =====")
    coriver_entries = per_user.get(23, [])
    phony_idx = key_to_idx.get(("フォニイ","LEGGENDARIA"))
    if phony_idx is not None:
        for e in coriver_entries:
            if e["b_idx"] != phony_idx: continue
            print(f"actualB={e['actual_b']}, maxB={e['max_b']}")
            for thr in THRS:
                d = e["per_thr"].get(thr)
                if d is None:
                    print(f"  |r|≧{thr}: 表示なし")
                else:
                    print(f"  |r|≧{thr}: support={d['sup']:>4} raw={d['raw']:>8.2f} "
                          f"clamp={d['clamp']:>8.2f} gap={d['gap']:>+7.2f}")


if __name__ == "__main__":
    main()
