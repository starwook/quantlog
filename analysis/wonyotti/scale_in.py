"""포지션(순포지션 0→0) 단위로 추가 진입(추매) 깊이와 최종 결과를 집계한다. 입력: data/wonyotti/raw 체결내역 (XBTUSD·ETHUSD)."""
import csv
import glob
import statistics as st
from collections import defaultdict, deque
from pathlib import Path

RAW = Path(__file__).resolve().parents[2] / "data/wonyotti/raw"
SYMBOLS = ("XBTUSD", "ETHUSD")

fills = []
for f in sorted(glob.glob(str(RAW / "aoa-execution-*.csv"))):
    with open(f, encoding="utf-8-sig", newline="") as fh:
        for d in csv.DictReader(fh):
            if d["exectype"] != "Trade" or not d["lastqty"] or d["symbol"] not in SYMBOLS:
                continue
            q = int(float(d["lastqty"]))
            if q > 0:
                fills.append((d["transacttime"], d["symbol"], 1 if d["side"] == "Buy" else -1, q, float(d["lastpx"]), d["orderid"]))
fills.sort(key=lambda x: x[0])

lots = defaultdict(deque)  # sym -> deque([부호, 수량, 가격])
cur = {}  # sym -> 진행 중 포지션
cycles = []
for t, sym, s, q, p, oid in fills:
    dq, c, rem = lots[sym], cur.get(sym), q
    while rem > 0 and dq and dq[0][0] != s:  # 반대 방향 lot 청산 (FIFO)
        lot = dq[0]
        m = min(rem, lot[1])
        c["rq"] += m
        c["rw"] += m * lot[0] * (p - lot[2]) / lot[2]
        c["mae"] = max(c["mae"], c["side"] * (c["first"] - p) / c["first"])
        lot[1] -= m
        rem -= m
        if lot[1] == 0:
            dq.popleft()
    if c and not dq:
        cycles.append(c)
        del cur[sym]
        c = None
    if rem > 0:
        if not dq:
            cur[sym] = c = {"side": s, "first": p, "t": t, "adds": {}, "rq": 0, "rw": 0.0, "mae": 0.0}
        else:
            held = sum(l[1] for l in dq)
            avg = sum(l[1] * l[2] for l in dq) / held
            a = c["adds"].setdefault(oid, [0.0, 0, held])  # 주문 단위로 묶는다 (held = 이 주문 시작 시점 보유 수량)
            a[0] += s * (avg - p) / avg * 100 * rem  # 평단보다 싸게(롱) 살수록 +
            a[1] += rem
            c["mae"] = max(c["mae"], s * (c["first"] - p) / c["first"])
        dq.append([s, rem, p])

for c in cycles:
    c["ret"] = c["rw"] / c["rq"] * 100 if c["rq"] else 0.0
    c["ao"] = [(v[0] / v[1], v[1]) for v in c["adds"].values()]
    c["ratio"] = [v[1] / v[2] for v in c["adds"].values()]  # 추매 수량 ÷ 추매 직전 보유 수량


def pct(xs, ps):
    xs = sorted(xs)
    return [round(xs[min(len(xs) - 1, int(len(xs) * p))], 2) for p in ps] if xs else None


def report(label, cs):
    wa = [c for c in cs if c["ao"]]
    print(f"\n== {label}: 포지션 {len(cs)}, 추매 있음 {len(wa)} ({len(wa) / max(1, len(cs)) * 100:.0f}%)")
    if not wa:
        return
    n = [len(c["ao"]) for c in wa]
    ds = [d for c in wa for d, _ in c["ao"]]
    print(f" 추매 주문 수 p50/p75/p90 {pct(n, [.5, .75, .9])}, 평균 {st.mean(n):.1f}")
    print(f" 평단 대비 추매 깊이 p10/25/50/75/90 {pct(ds, [.1, .25, .5, .75, .9])}, 평단보다 유리한 가격 {sum(d <= 0 for d in ds) / len(ds) * 100:.0f}%")
    for a, b in [(-99, 0), (0, .5), (.5, 1), (1, 2), (2, 3), (3, 5), (5, 10), (10, 999)]:
        print(f"   깊이 {a}~{b}%: {sum(a < d <= b for d in ds) / len(ds) * 100:.0f}%")
    rs = [r for c in wa for r in c["ratio"]]
    print(f" 추매 수량 ÷ 직전 보유 수량 p10/25/50/75/90 {pct(rs, [.1, .25, .5, .75, .9])}, 2배 이상(≥1.0) {sum(r >= 1 for r in rs) / len(rs) * 100:.0f}%")
    print(" 최대 추매 깊이별 최종 결과:")
    for a, b in [(-99, 1), (1, 3), (3, 5), (5, 10), (10, 999)]:
        g = [c for c in wa if a < max(d for d, _ in c["ao"]) <= b]
        if g:
            print(f"   {a}~{b}%: n={len(g)} 승률 {sum(c['ret'] > 0 for c in g) / len(g) * 100:.0f}% 평균 {st.mean(c['ret'] for c in g):.2f}% 최악 {min(c['ret'] for c in g):.1f}%")


for name, side in (("롱", 1), ("숏", -1)):
    for era, sel in (("전체", lambda c: True), ("2018-19", lambda c: c["t"] < "2020"), ("2020-21", lambda c: c["t"] >= "2020")):
        report(f"{name} {era}", [c for c in cycles if c["side"] == side and sel(c)])
