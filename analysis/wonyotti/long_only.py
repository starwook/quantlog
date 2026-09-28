"""롱 포지션 청산만 따로 집계한다. 입력: data/wonyotti/derived/closing_orders.csv (analyze.py 산출물)."""
import csv
import statistics as st
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
rows = []
with open(ROOT / "data/wonyotti/derived/closing_orders.csv", encoding="utf-8") as f:
    for d in csv.DictReader(f):
        if d["entry_side"] == "long":
            rows.append((d["time_utc"][:4], d["symbol"], d["exit_ordtype"], float(d["return_pct"]), float(d["hold_hours"]), float(d["weight_sat"])))


def q(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * p))] if xs else float("nan")


def stats(rs, label):
    n = len(rs)
    if not n:
        return
    wins = [r[3] for r in rs if r[3] > 0]
    loss = [-r[3] for r in rs if r[3] < 0]
    aw = st.mean(wins) if wins else 0
    al = st.mean(loss) if loss else 0
    wr = len(wins) / n * 100
    exp = st.mean(r[3] for r in rs)
    print(f"{label:12s} n={n:5d} 승률={wr:5.1f}% 평균익절={aw:5.2f}% 평균손실=-{al:5.2f}% 손익비={aw / al if al else 0:4.2f} 기대값={exp:+5.2f}% "
          f"익절중앙={st.median(wins) if wins else 0:4.2f}% 손실중앙={st.median(loss) if loss else 0:4.2f}%")


print("== 롱 전체 / 심볼")
stats(rows, "롱 전체")
for s in ("XBTUSD", "ETHUSD"):
    stats([r for r in rows if r[1] == s], s)
print("== 연도별")
by = defaultdict(list)
for r in rows:
    by[r[0]].append(r)
for y in sorted(by):
    stats(by[y], y)
print("== 분위수 (전체 롱)")
wins = [r[3] for r in rows if r[3] > 0]
loss = [-r[3] for r in rows if r[3] < 0]
for name, xs in (("익절", wins), ("손실", loss)):
    print(name, {f"p{int(p * 100)}": round(q(xs, p), 2) for p in (0.1, 0.25, 0.5, 0.75, 0.9, 0.95)})
print("== 청산 방식별 손실")
for ot in ("Limit", "Market", "Stop", "StopLimit"):
    ls = [-r[3] for r in rows if r[2] == ot and r[3] < 0]
    if ls:
        print(ot, len(ls), "손실중앙", round(st.median(ls), 2), "p90", round(q(ls, 0.9), 2))
print("== 임계 비중 (전체 롱 청산 대비)")
n = len(rows)
for span, lab in ((("2018", "2019"), "2018-19"), (("2020", "2021"), "2020-21")):
    sub = [r for r in rows if r[0] in span]
    m = len(sub)
    print(lab, m, {f"이익>={t}%": f"{sum(1 for r in sub if r[3] >= t) / m * 100:.1f}%" for t in (1, 3, 5)},
          {f"손실<=-{t}%": f"{sum(1 for r in sub if r[3] <= -t) / m * 100:.1f}%" for t in (1, 3, 5)})
print("== 손실 금액 집중 (명목가치 가중)")
tot = sum(r[5] * -r[3] for r in rows if r[3] < 0)
big = sum(r[5] * -r[3] for r in rows if r[3] <= -2)
print(f"손실률 2% 이상이 손실 금액의 {big / tot * 100:.1f}%")
print("== 보유시간 중앙값(시간)")
print("익절", round(st.median(r[4] for r in rows if r[3] > 0), 2), "손실", round(st.median(r[4] for r in rows if r[3] < 0), 2))
print("== ±3% 근처 몰림 (±0.05%p)")
for x in (-5, -3, -1, 1, 3, 5):
    c = sum(1 for r in rows if abs(r[3] - x) <= 0.05)
    print(f"{x:+}% 근처 {c}건 ({c / n * 100:.2f}%)")
print("== 롱이 -3%에서 잘렸다면? (단순 시나리오: 손실 -3% 초과분을 -3%로 절단, 익절은 유지. 경로 데이터 없어 참고용)")
capped = [max(r[3], -3.0) for r in rows]
print("기대값", round(st.mean(r[3] for r in rows), 3), "→", round(st.mean(capped), 3), "%/건")
