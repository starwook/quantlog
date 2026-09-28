"""워뇨띠(aoa) BitMEX 체결내역 분석.

입력: data/wonyotti/raw/aoa-execution-*.csv, aoa-wallet-*.csv (원본, 수정하지 않음)
출력: data/wonyotti/derived/*.csv, 표준출력에 요약 JSON

방법
- 체결(exectype=Trade)을 시간순으로 심볼별 FIFO 로 매칭해 "청산 주문" 단위 수익률(%)을 만든다.
  수익률 = 진입가 대비 청산가 변동률 (롱: (청산-진입)/진입, 숏: (진입-청산)/진입). 수수료·펀딩·레버리지 미반영.
- 가중치는 청산 수량 x 계약 1개당 명목가치(sat, execcost/lastqty). 정산통화가 XBt 가 아닌 심볼(USDT)은 가중치 0.
- 지갑 파일로 일별 실현손익률(전일 잔고 대비), 입출금 제외 복리 곡선, MDD 를 계산한다.
표준 라이브러리만 사용한다 (pandas 불필요).
"""

import bisect
import csv
import glob
import json
import os
import statistics
from collections import Counter, defaultdict, deque
from datetime import datetime, timezone

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
RAW = os.path.join(ROOT, "data", "wonyotti", "raw")
OUT = os.path.join(ROOT, "data", "wonyotti", "derived")
os.makedirs(OUT, exist_ok=True)

MAJOR = ["XBTUSD", "ETHUSD", "XRPUSD"]


def epoch(s):
    """BitMEX 시각은 UTC. 문자열을 UTC 로 해석한다."""
    return datetime.fromisoformat(s.replace(" ", "T")).replace(tzinfo=timezone.utc).timestamp()


def utc(ts):
    return datetime.fromtimestamp(ts, timezone.utc)


def wpercentile(pairs, qs):
    """pairs: [(value, weight)] -> {q: value}"""
    pairs = sorted(pairs)
    total = sum(w for _, w in pairs)
    if total <= 0:
        return {q: None for q in qs}
    out, acc, i = {}, 0.0, 0
    for q in qs:
        target = total * q
        while i < len(pairs) - 1 and acc + pairs[i][1] < target:
            acc += pairs[i][1]
            i += 1
        out[q] = pairs[i][0]
    return out


def summarize(orders, label_filter=None):
    """orders: list of dict(ret, w, hold, ...)"""
    if label_filter:
        orders = [o for o in orders if label_filter(o)]
    n = len(orders)
    if n == 0:
        return {"n": 0}
    rets = [o["ret"] * 100 for o in orders]
    wins = [r for r in rets if r > 0]
    losses = [r for r in rets if r <= 0]
    wpairs = [(o["ret"] * 100, o["w"]) for o in orders if o["w"] > 0]
    tw = sum(w for _, w in wpairs)
    ww = sum(w for r, w in wpairs if r > 0)
    wl = sum(w for r, w in wpairs if r <= 0)
    wavg_win = sum(r * w for r, w in wpairs if r > 0) / ww if ww else None
    wavg_loss = sum(r * w for r, w in wpairs if r <= 0) / wl if wl else None
    pct = wpercentile([(o["ret"] * 100, 1.0) for o in orders], [0.05, 0.25, 0.5, 0.75, 0.95])
    holds = sorted(o["hold_h"] for o in orders)

    def qs(vals, qlist):
        vals = sorted(vals)
        return [round(vals[min(len(vals) - 1, int(len(vals) * q))], 3) for q in qlist] if vals else None

    win_hold = [o["hold_h"] for o in orders if o["ret"] > 0]
    loss_hold = [o["hold_h"] for o in orders if o["ret"] <= 0]
    return {
        "win_pct_p10_p25_p50_p75_p90": qs(wins, (0.1, 0.25, 0.5, 0.75, 0.9)),
        "loss_pct_p10_p25_p50_p75_p90": qs([-x for x in losses], (0.1, 0.25, 0.5, 0.75, 0.9)),
        "hold_hours_median_win_vs_loss": [round(statistics.median(win_hold), 2) if win_hold else None,
                                          round(statistics.median(loss_hold), 2) if loss_hold else None],
        "n": n,
        "win_rate_pct": round(len(wins) / n * 100, 1),
        "avg_win_pct": round(statistics.mean(wins), 3) if wins else None,
        "avg_loss_pct": round(statistics.mean(losses), 3) if losses else None,
        "payoff_ratio": round(statistics.mean(wins) / abs(statistics.mean(losses)), 2) if wins and losses and statistics.mean(losses) != 0 else None,
        "expectancy_pct": round(statistics.mean(rets), 4),
        "weighted_win_rate_pct": round(ww / tw * 100, 1) if tw else None,
        "weighted_avg_win_pct": round(wavg_win, 3) if wavg_win is not None else None,
        "weighted_avg_loss_pct": round(wavg_loss, 3) if wavg_loss is not None else None,
        "ret_pct_p5_p25_p50_p75_p95": [round(pct[q], 3) for q in (0.05, 0.25, 0.5, 0.75, 0.95)],
        "worst_pct": round(min(rets), 2),
        "best_pct": round(max(rets), 2),
        "hold_hours_p25_p50_p75_p95": [round(holds[int(len(holds) * q)], 2) for q in (0.25, 0.5, 0.75, 0.95)],
    }


def main():
    # 1) 체결 로드 ---------------------------------------------------------
    fills = []
    for f in sorted(glob.glob(os.path.join(RAW, "aoa-execution-*.csv"))):
        with open(f, encoding="utf-8-sig", newline="") as fh:
            for d in csv.DictReader(fh):
                if d["exectype"] != "Trade" or not d["lastqty"]:
                    continue
                q = int(float(d["lastqty"]))
                if q <= 0:
                    continue
                cost = abs(float(d["execcost"] or 0))
                unit = cost / q if d["settlcurrency"] == "XBt" else 0.0
                fills.append(
                    (
                        epoch(d["transacttime"]),
                        d["symbol"],
                        1 if d["side"] == "Buy" else -1,
                        q,
                        float(d["lastpx"]),
                        d["ordtype"],
                        d["orderid"],
                        unit,
                    )
                )
    fills.sort(key=lambda x: x[0])
    print(f"fills loaded: {len(fills)}", flush=True)

    # 2) FIFO 매칭 ---------------------------------------------------------
    lots = defaultdict(deque)  # sym -> deque([sign, qty, px, t])
    sum_q = defaultdict(float)
    sum_qp = defaultdict(float)
    net = defaultdict(int)
    unitv = {}
    closing = {}  # (sym, orderid) -> dict
    scale_in = []  # (sym, adverse_pct, size_weight)
    day_max_notional = defaultdict(float)  # 'YYYY-MM-DD' -> sat
    entry_orders = {}  # (sym, orderid) -> [qty, ordtype]
    open_hours = Counter()

    for t, sym, s, q, p, ot, oid, unit in fills:
        if unit:
            unitv[sym] = unit
        remaining = q
        dq = lots[sym]
        # 반대 방향 lot 청산
        while remaining > 0 and dq and dq[0][0] != s:
            lot = dq[0]
            m = min(remaining, lot[1])
            lsign, lpx, lt = lot[0], lot[2], lot[3]
            ret = lsign * (p - lpx) / lpx
            key = (sym, oid)
            c = closing.get(key)
            if c is None:
                c = closing[key] = {
                    "sym": sym, "oid": oid, "ordtype": ot, "t": t, "qty": 0.0, "wret": 0.0,
                    "wq": 0.0, "hold": 0.0, "w": 0.0, "wr": 0.0, "side": "long" if lsign > 0 else "short",
                }
            c["qty"] += m
            c["wret"] += ret * m
            c["hold"] += (t - lt) * m
            w = m * unitv.get(sym, 0.0)
            c["w"] += w
            c["wr"] += ret * w
            lot[1] -= m
            sum_q[sym] -= m
            sum_qp[sym] -= m * lpx
            net[sym] -= lsign * m
            remaining -= m
            if lot[1] == 0:
                dq.popleft()
        # 남은 수량은 신규/추가 진입
        if remaining > 0:
            if dq and dq[0][0] == s and sum_q[sym] > 0:
                avg = sum_qp[sym] / sum_q[sym]
                adverse = s * (avg - p) / avg  # 롱이면 평단보다 싸게 살수록(+) = 물타기
                scale_in.append((sym, adverse * 100, remaining * unitv.get(sym, 0.0)))
            dq.append([s, remaining, p, t])
            sum_q[sym] += remaining
            sum_qp[sym] += remaining * p
            net[sym] += s * remaining
        # 레버리지용 일별 최대 명목가치
        tot = 0.0
        for sy, nq in net.items():
            if nq:
                tot += abs(nq) * unitv.get(sy, 0.0)
        day = utc(t).strftime("%Y-%m-%d")
        if tot > day_max_notional[day]:
            day_max_notional[day] = tot

    orders = []
    for c in closing.values():
        if c["qty"] <= 0:
            continue
        ret = c["wret"] / c["qty"]
        w = c["w"]
        orders.append(
            {
                "sym": c["sym"], "ordtype": c["ordtype"], "side": c["side"], "t": c["t"],
                "ret": ret, "w": w, "hold_h": (c["hold"] / c["qty"]) / 3600.0, "qty": c["qty"],
            }
        )
    orders.sort(key=lambda o: o["t"])
    print(f"closing orders: {len(orders)}", flush=True)

    with open(os.path.join(OUT, "closing_orders.csv"), "w", newline="", encoding="utf-8") as fh:
        w_ = csv.writer(fh)
        w_.writerow(["time_utc", "symbol", "entry_side", "exit_ordtype", "qty", "return_pct", "hold_hours", "weight_sat"])
        for o in orders:
            w_.writerow([utc(o["t"]).strftime("%Y-%m-%d %H:%M:%S"), o["sym"], o["side"], o["ordtype"],
                         int(o["qty"]), round(o["ret"] * 100, 4), round(o["hold_h"], 3), int(o["w"])])

    result = {}
    result["overall"] = summarize(orders)
    for s_ in MAJOR:
        result[s_] = summarize(orders, lambda o, s_=s_: o["sym"] == s_)
    result["by_exit_ordtype"] = {}
    for ot in ("Limit", "Stop", "StopLimit", "Market"):
        result["by_exit_ordtype"][ot] = summarize(orders, lambda o, ot=ot: o["ordtype"] == ot)
    result["by_entry_side"] = {sd: summarize(orders, lambda o, sd=sd: o["side"] == sd) for sd in ("long", "short")}
    result["by_year"] = {}
    for y in range(2018, 2022):
        result["by_year"][str(y)] = summarize(orders, lambda o, y=y: utc(o["t"]).year == y)

    # 수익률 분포 (청산 주문 단위, 0.1% 단위 버킷)
    hist = Counter()
    for o in orders:
        b = max(-10.0, min(10.0, round(o["ret"] * 100 / 0.1) * 0.1))
        hist[round(b, 1)] += 1
    with open(os.path.join(OUT, "return_hist.csv"), "w", newline="", encoding="utf-8") as fh:
        w_ = csv.writer(fh)
        w_.writerow(["return_pct_bucket", "closing_orders"])
        for b in sorted(hist):
            w_.writerow([b, hist[b]])
    rets = [o["ret"] * 100 for o in orders]
    result["threshold_share"] = {
        f"|ret|>={x}%": {
            "gain_share_pct": round(sum(1 for r in rets if r >= x) / len(rets) * 100, 2),
            "loss_share_pct": round(sum(1 for r in rets if r <= -x) / len(rets) * 100, 2),
        }
        for x in (0.1, 0.25, 0.5, 1, 2, 3, 5, 10)
    }

    for name, yrs in (("2018_2019", (2018, 2019)), ("2020_2021", (2020, 2021))):
        rr = [o["ret"] * 100 for o in orders if utc(o["t"]).year in yrs]
        result["threshold_share_" + name] = {
            f"{x}%": {
                "gain_ge_pct": round(sum(1 for r in rr if r >= x) / len(rr) * 100, 1),
                "loss_le_pct": round(sum(1 for r in rr if r <= -x) / len(rr) * 100, 1),
            }
            for x in (1, 2, 3, 5, 10)
        }

    # 손절성 청산: Stop/StopLimit/Market 중 손실 건의 손실률 분포
    stops = [o for o in orders if o["ordtype"] in ("Stop", "StopLimit") and o["ret"] < 0]
    mk = [o for o in orders if o["ordtype"] == "Market" and o["ret"] < 0]
    for name, arr in (("stop_orders_loss", stops), ("market_orders_loss", mk)):
        if arr:
            pc = wpercentile([(-o["ret"] * 100, 1.0) for o in arr], [0.1, 0.25, 0.5, 0.75, 0.9])
            result[name] = {"n": len(arr), "loss_pct_p10_p25_p50_p75_p90": [round(pc[q], 3) for q in (0.1, 0.25, 0.5, 0.75, 0.9)]}
    # 지정가 손실 청산 (손절도 지정가로 걸었는지)
    lim_loss = [o for o in orders if o["ordtype"] == "Limit" and o["ret"] < 0]
    lim_win = [o for o in orders if o["ordtype"] == "Limit" and o["ret"] > 0]
    result["limit_exit_split"] = {"loss_orders": len(lim_loss), "win_orders": len(lim_win)}
    if lim_loss:
        pc = wpercentile([(-o["ret"] * 100, 1.0) for o in lim_loss], [0.5, 0.9, 0.99])
        result["limit_exit_split"]["loss_pct_p50_p90_p99"] = [round(pc[q], 3) for q in (0.5, 0.9, 0.99)]

    # 큰 손실(>=2%) 주문이 전체 손실 가중 합계에서 차지하는 비중
    wl_total = sum(-o["ret"] * o["w"] for o in orders if o["ret"] < 0 and o["w"] > 0)
    wl_big = sum(-o["ret"] * o["w"] for o in orders if o["ret"] <= -0.02 and o["w"] > 0)
    result["big_loss_share_of_weighted_loss_pct"] = round(wl_big / wl_total * 100, 1) if wl_total else None

    # 3) 물타기(추가 진입) --------------------------------------------------
    if scale_in:
        adv = [(a, w) for _, a, w in scale_in if w > 0]
        against = [a for _, a, _ in scale_in if a > 0]
        result["scale_in"] = {
            "adds": len(scale_in),
            "adds_against_position_share_pct": round(len(against) / len(scale_in) * 100, 1),
            "adverse_pct_p50_p90_p99_of_adverse": [
                round(v, 3) for v in (wpercentile([(a, 1.0) for a in against], [0.5, 0.9, 0.99]).values())
            ] if against else None,
            "weighted_adverse_share_ge_1pct": round(
                sum(w for a, w in adv if a >= 1) / sum(w for _, w in adv) * 100, 1) if adv else None,
        }

    # 4) 지갑: 일별 수익률, 복리곡선, MDD, 레버리지 -----------------------------
    wrows = []
    with open(os.path.join(RAW, "aoa-wallet-2018-03-01-2021-12-31.csv"), encoding="utf-8-sig", newline="") as fh:
        for d in csv.DictReader(fh):
            if not d["date"] or d["transactstatus"] != "Completed":
                continue
            wrows.append((d["date"], d["transacttype"], float(d["amount"]), float(d["walletbalance"])))
    daily = defaultdict(lambda: {"pnl": 0.0, "flow": 0.0, "end": None})
    order_days = []
    for date, tt, amt, wb in wrows:
        if date not in daily:
            order_days.append(date)
        dd = daily[date]
        if tt == "RealisedPNL":
            dd["pnl"] += amt
        elif tt in ("Deposit", "Withdrawal"):
            dd["flow"] += amt
        dd["end"] = wb
    equity, curve, day_rets = 1.0, [], []
    prev_end = None
    peak, mdd, mdd_day = 1.0, 0.0, None
    yearly = defaultdict(lambda: 1.0)
    monthly = defaultdict(lambda: 1.0)
    end_by_day = {}
    for date in order_days:
        dd = daily[date]
        end_by_day[date] = dd["end"]
        base = (prev_end or 0) + max(dd["flow"], 0.0)  # 같은 날 입금분은 분모에 포함
        if prev_end and base >= 1e8:  # 1 BTC 이상일 때부터 수익률 계산
            r = max(dd["pnl"] / base, -0.999)
            day_rets.append((date, r))
            equity *= 1 + r
            yearly[date[:4]] *= 1 + r
            monthly[date[:7]] *= 1 + r
            peak = max(peak, equity)
            dd_ = equity / peak - 1
            if dd_ < mdd:
                mdd, mdd_day = dd_, date
            curve.append((date, equity))
        prev_end = dd["end"]
    rs = [r for _, r in day_rets]
    if rs:
        pos, neg = [r for r in rs if r > 0], [r for r in rs if r < 0]
        streak = cur = 0
        for r in rs:
            cur = cur + 1 if r < 0 else 0
            streak = max(streak, cur)
        srt = sorted(rs)
        result["wallet_daily"] = {
            "days": len(rs),
            "win_day_rate_pct": round(len(pos) / len(rs) * 100, 1),
            "avg_win_day_pct": round(statistics.mean(pos) * 100, 3),
            "avg_loss_day_pct": round(statistics.mean(neg) * 100, 3),
            "worst_day_pct": round(srt[0] * 100, 2),
            "best_day_pct": round(srt[-1] * 100, 2),
            "p5_p50_p95_pct": [round(srt[int(len(srt) * q)] * 100, 3) for q in (0.05, 0.5, 0.95)],
            "daily_stdev_pct": round(statistics.pstdev(rs) * 100, 3),
            "longest_losing_streak_days": streak,
            "final_compounded_multiple_ex_flows": round(equity, 2),
            "max_drawdown_pct": round(mdd * 100, 1),
            "max_drawdown_end_day": mdd_day,
            "yearly_return_pct": {y: round((v - 1) * 100, 1) for y, v in sorted(yearly.items())},
            "monthly_win_rate_pct": round(sum(1 for v in monthly.values() if v > 1) / len(monthly) * 100, 1),
            "worst_month_pct": round((min(monthly.values()) - 1) * 100, 1),
            "best_month_pct": round((max(monthly.values()) - 1) * 100, 1),
        }
    with open(os.path.join(OUT, "wallet_daily.csv"), "w", newline="", encoding="utf-8") as fh:
        w_ = csv.writer(fh)
        w_.writerow(["date", "daily_return_pct", "compounded_equity_ex_flows", "wallet_balance_btc"])
        cmap = dict(curve)
        for date, r in day_rets:
            w_.writerow([date, round(r * 100, 4), round(cmap[date], 4), round(end_by_day[date] / 1e8, 4)])

    # 레버리지 추정: 일별 최대 명목가치 / 전일 말 잔고
    lev = []
    days_sorted = sorted(end_by_day)
    for date, mx in day_max_notional.items():
        i = bisect.bisect_left(days_sorted, date) - 1
        if i >= 0 and end_by_day[days_sorted[i]] >= 1e8:  # 1 BTC 이상
            lev.append(mx / end_by_day[days_sorted[i]])
    if lev:
        lev.sort()
        result["leverage_est_daily_max"] = {
            "days": len(lev),
            "p25_p50_p75_p95_max": [round(lev[int(len(lev) * q)], 2) for q in (0.25, 0.5, 0.75, 0.95)] + [round(lev[-1], 1)],
        }
    result["wallet_flows_btc"] = {
        "deposits": round(sum(a for _, tt, a, _ in wrows if tt == "Deposit") / 1e8, 2),
        "withdrawals": round(-sum(a for _, tt, a, _ in wrows if tt == "Withdrawal") / 1e8, 2),
        "final_wallet_btc": round(wrows[-1][3] / 1e8, 2),
        "total_realised_pnl_btc": round(sum(a for _, tt, a, _ in wrows if tt == "RealisedPNL") / 1e8, 2),
    }

    # 5) 체결 통계 -------------------------------------------------------
    per_sym_orders = Counter(o["sym"] for o in orders)
    result["closing_orders_by_symbol_top"] = per_sym_orders.most_common(8)
    result["fills_by_ordtype"] = dict(Counter(f[5] for f in fills))
    result["period"] = [utc(fills[0][0]).strftime("%Y-%m-%d"), utc(fills[-1][0]).strftime("%Y-%m-%d")]

    with open(os.path.join(OUT, "summary.json"), "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
