#!/usr/bin/env python3
"""Проверка качества ночного лога OPW2 Probe.

Считает реальное покрытие ночи (строки против ожидаемых 30-секундных бакетов),
свежесть пульса, провалы и расход батареи. Возвращает код 1, если ночь не годится
для анализа — чтобы «тихую» деградацию сервиса было видно сразу.

Использование:
    python tools/night_report.py <probe_eco_*.csv> [--min-coverage 95] [--min-hr 90]
    python tools/night_report.py --latest <папка с csv>
"""

from __future__ import annotations

import argparse
import csv
import glob
import os
import statistics
import sys
from datetime import datetime, timezone

BUCKET_S = 30
HR_FRESH_MAX_AGE_S = 300

# Windows-консоль по умолчанию cp1251 и падает на юникоде — принудительно UTF-8.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
    except (AttributeError, ValueError):
        pass


def find_latest(folder: str) -> str | None:
    files = glob.glob(os.path.join(folder, "probe_*.csv"))
    return max(files, key=os.path.getmtime) if files else None


def load(path: str) -> list[dict]:
    rows: list[dict] = []
    with open(path, newline="", encoding="utf-8", errors="replace") as fh:
        lines = [ln for ln in fh if not ln.startswith("#") and ln.strip()]
    reader = csv.DictReader(lines)
    for r in reader:
        try:
            r["epoch_ms"] = int(r["epoch_ms"])
        except (KeyError, TypeError, ValueError):
            continue
        rows.append(r)
    return rows


def is_true(v: str | None) -> bool:
    return (v or "").strip().lower() == "true"


def as_float(v: str | None, default: float = 0.0) -> float:
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def as_int(v: str | None, default: int = 0) -> int:
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return default


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", nargs="?", help="путь к probe_*.csv")
    ap.add_argument("--latest", metavar="DIR", help="взять самый свежий probe_*.csv из папки")
    ap.add_argument("--min-coverage", type=float, default=95.0, help="минимальное покрытие, %%")
    ap.add_argument("--min-hr", type=float, default=90.0, help="минимальная доля окон со свежим HR, %%")
    ap.add_argument("--strict", action="store_true", help="падать и на провалах > 2 минут")
    args = ap.parse_args()

    path = args.csv or (find_latest(args.latest) if args.latest else None)
    if not path or not os.path.exists(path):
        print(f"ФАЙЛ НЕ НАЙДЕН: {path}", file=sys.stderr)
        return 2

    rows = load(path)
    if len(rows) < 2:
        print(f"СЛИШКОМ МАЛО ДАННЫХ: {len(rows)} строк в {path}", file=sys.stderr)
        return 2

    t0, t1 = rows[0]["epoch_ms"], rows[-1]["epoch_ms"]
    duration_min = (t1 - t0) / 60000.0
    expected = int(duration_min * 60 / BUCKET_S) + 1
    coverage = 100.0 * len(rows) / expected if expected else 0.0

    gaps = []
    for prev, cur in zip(rows, rows[1:]):
        gaps.append((cur["epoch_ms"] - prev["epoch_ms"]) / 1000.0)
    big_gaps = [g for g in gaps if g > 90]
    lost_min = sum(big_gaps) / 60.0

    fresh = [
        r for r in rows
        if as_int(r.get("hr_samples")) > 0 and as_int(r.get("hr_age_s"), 10**9) <= HR_FRESH_MAX_AGE_S
    ]
    hr_fresh_pct = 100.0 * len(fresh) / len(rows)

    hr_vals = [as_int(r.get("hr_bpm")) for r in rows if as_int(r.get("hr_bpm")) > 0]
    sources = {}
    for r in rows:
        s = (r.get("hr_source") or "none").strip() or "none"
        sources[s] = sources.get(s, 0) + 1

    asleep_rows = sum(1 for r in rows if is_true(r.get("saw_asleep")))
    alarm_states = {}
    for r in rows:
        s = (r.get("alarm_state") or "?").strip()
        alarm_states[s] = alarm_states.get(s, 0) + 1

    # расход батареи только на участках без зарядки
    not_charging = [r for r in rows if not is_true(r.get("charging")) and as_int(r.get("battery_pct"), -1) >= 0]
    drain_rate = None
    if len(not_charging) >= 2:
        hours = (not_charging[-1]["epoch_ms"] - not_charging[0]["epoch_ms"]) / 3600000.0
        if hours > 0.05:
            drain_rate = (as_int(not_charging[0]["battery_pct"]) - as_int(not_charging[-1]["battery_pct"])) / hours

    charging_frac = 100.0 * sum(1 for r in rows if is_true(r.get("charging"))) / len(rows)

    print(f"Файл: {os.path.basename(path)}")
    print(f"Интервал: {datetime.fromtimestamp(t0 / 1000, timezone.utc):%Y-%m-%d %H:%M} .. "
          f"{datetime.fromtimestamp(t1 / 1000, timezone.utc):%H:%M} UTC, {duration_min:.0f} мин")
    print(f"Строк: {len(rows)} (ожидалось {expected} по {BUCKET_S} с)")
    print(f"Покрытие:      {coverage:5.1f}%   (порог {args.min_coverage:.0f}%)")
    print(f"Провалов >90с: {len(big_gaps)}, потеряно {lost_min:.0f} мин "
          f"({100.0 * lost_min / duration_min if duration_min else 0:.0f}% ночи)", end="")
    if gaps:
        print(f", максимальный {max(gaps) / 60:.1f} мин")
    else:
        print()
    print(f"Свежий HR:     {hr_fresh_pct:5.1f}%   (порог {args.min_hr:.0f}%)", end="")
    if hr_vals:
        print(f", min/median/max = {min(hr_vals)}/{statistics.median(hr_vals):.0f}/{max(hr_vals)}")
    else:
        print(", значений HR нет")
    print(f"Источник HR:   {sources}")
    print(f"Сон подтверждён: {asleep_rows} окон ({100.0 * asleep_rows / len(rows):.0f}%)")
    print(f"Состояние будильника: {alarm_states}")
    if drain_rate is not None:
        print(f"Расход:        {drain_rate:5.2f} %/ч без зарядки "
              f"(~{drain_rate * 8:.0f}% за 8 ч), на зарядке {charging_frac:.0f}% ночи")
    else:
        print(f"Расход: не измерить (на зарядке {charging_frac:.0f}% ночи)")

    problems = []
    if coverage < args.min_coverage:
        problems.append(f"покрытие {coverage:.1f}% < {args.min_coverage:.0f}%")
    if hr_fresh_pct < args.min_hr:
        problems.append(f"свежий HR {hr_fresh_pct:.1f}% < {args.min_hr:.0f}%")
    if sources.get("none", 0) == len(rows):
        problems.append("HR не приходил вообще (hr_source=none во всех окнах)")
    if args.strict and len(big_gaps):
        problems.append(f"есть провалы >90 с: {len(big_gaps)}")

    if problems:
        print("\nFAIL: " + "; ".join(problems))
        return 1

    print("\nPASS: ночь пригодна для анализа")
    return 0


if __name__ == "__main__":
    sys.exit(main())
