#!/usr/bin/env python3
"""PPG → пульс / RR / HRV для OnePlus Watch 2 (Wear OS 4).

Зачем: на OPW2 закрыты `ppg_hrm`, `ppg_spo`, `ecg`, а `android.sensor.heart_rate`
отдаёт 0. Единственный источник «умности» — сырой `PPG_RAW_IR0/R0/G0` (50 Гц,
16 значений на событие). Скрипт проверяет, можно ли из него достать RR-интервалы
и HRV, и считает метрики качества сигнала.

Формат входа — CSV от `RawPpg.capture`:
    ts_ns,sensor,v0;v1;...;v15          (заголовок; данные через запятую)
    11657458062139,PPG_RAW_IR0,4,13812224,0,0,0,4,-9728,...

Структура события: v0 и v5 — маркеры каналов, v1..v4 и v6..v9 — пакеты по 4 сэмпла
(сырые и обработанные). Скрипт перебирает представления: отдельные столбцы на частоте
событий и интерливинг пакетов на 4× частоте, и выбирает лучшее по качеству.

Использование:
    python tools/ppg_hr.py --selftest
    python tools/ppg_hr.py ppg_raw_*.csv
    python tools/ppg_hr.py --file capture.csv --min-sq 3
"""

from __future__ import annotations

import argparse
import glob
import os
import sys

import numpy as np

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
    except (AttributeError, ValueError):
        pass

HR_MIN, HR_MAX = 33.0, 200.0
RR_MIN_S, RR_MAX_S = 60.0 / HR_MAX, 60.0 / HR_MIN
BAND_LO, BAND_HI = 0.60, 3.20


# ---------------------------------------------------------------- чтение

def read_ppg(path: str) -> dict[str, tuple[np.ndarray, np.ndarray]]:
    """Возвращает {sensor: (ts_ns, matrix[n_events, 16])}."""
    sensors: dict[str, list[tuple[int, list[int]]]] = {}
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.startswith("#") or line.startswith("ts_ns") or not line.strip():
                continue
            parts = line.strip().split(",")
            if len(parts) < 18:
                continue
            try:
                ts = int(parts[0])
                vals = [int(x) for x in parts[2:18]]
            except ValueError:
                continue
            sensors.setdefault(parts[1], []).append((ts, vals))
    out: dict[str, tuple[np.ndarray, np.ndarray]] = {}
    for name, rows in sensors.items():
        out[name] = (
            np.array([r[0] for r in rows], dtype=np.int64),
            np.array([r[1] for r in rows], dtype=np.float64),
        )
    return out


# ---------------------------------------------------------------- обработка

def bandpass(x: np.ndarray, fs: float, lo: float = BAND_LO, hi: float = BAND_HI) -> np.ndarray:
    """Нуль-фазовый полосовой фильтр через FFT (offline-анализ).

    Нижняя граница 0.6 Гц, а не 0.9: пульс 45–53 уд/мин (0.75–0.88 Гц) — обычное дело
    в глубоком сне, и старая полоса его просто вырезала.
    """
    if len(x) < 8 or fs <= 0:
        return x - np.mean(x)
    x = x - np.mean(x)
    n = len(x)
    spec = np.fft.rfft(x * np.hanning(n))
    freqs = np.fft.rfftfreq(n, 1.0 / fs)
    spec[(freqs < lo) | (freqs > hi)] = 0
    return np.fft.irfft(spec, n=n)


def signal_quality(sig: np.ndarray, fs: float) -> float:
    """SQ = пик спектра в кардио-диапазоне / медиана спектра. >3 — пульс различим."""
    if len(sig) < fs * 4:
        return 0.0
    n = len(sig)
    spec = np.abs(np.fft.rfft((sig - sig.mean()) * np.hanning(n)))
    freqs = np.fft.rfftfreq(n, 1.0 / fs)
    band = (freqs >= 0.65) & (freqs <= BAND_HI)
    if not band.any():
        return 0.0
    return float(spec[band].max() / (np.median(spec[band]) + 1e-12))


def _prominence(sig: np.ndarray, idx: int, half: int) -> float:
    lo = max(0, idx - half)
    hi = min(len(sig), idx + half + 1)
    return float(sig[idx] - np.min(sig[lo:hi]))


def estimate_period(sig: np.ndarray, fs: float, lo: float = 0.60, hi: float = BAND_HI) -> float | None:
    """Период сокращений по спектральному пику в кардио-диапазоне.

    Спектр точнее автокорреляции на коротких записях: 60 с при fs=50 и пульсе 50 уд/мин
    автокорреляция давала период с ошибкой ~5 %, что на выходе означало 3.8 bpm.
    Частота пика уточняется квадратичной интерполяцией по трём бинам.
    """
    n = int(sig.size)
    if n < fs * 3:
        return None
    x = (sig - float(np.mean(sig))) * np.hanning(n)
    spec = np.abs(np.fft.rfft(x))
    freqs = np.fft.rfftfreq(n, 1.0 / fs)
    band = np.where((freqs >= lo) & (freqs <= hi))[0]
    if band.size == 0:
        return None
    i = int(band[int(np.argmax(spec[band]))])
    f0 = float(freqs[i])
    if 0 < i < spec.size - 1:
        y0, y1, y2 = float(spec[i - 1]), float(spec[i]), float(spec[i + 1])
        denom = y0 - 2.0 * y1 + y2
        if denom != 0:
            delta = 0.5 * (y0 - y2) / denom
            if abs(delta) <= 1.0:
                f0 += delta * float(freqs[1] - freqs[0])
    if f0 <= 0:
        return None
    return 1.0 / f0


def _refine_peak(sig: np.ndarray, p: int) -> float:
    """Параболическая интерполяция вершины — суб-сэмпловое время удара."""
    if 0 < p < sig.size - 1:
        y0, y1, y2 = float(sig[p - 1]), float(sig[p]), float(sig[p + 1])
        denom = y0 - 2.0 * y1 + y2
        if denom != 0:
            return float(p) + 0.5 * (y0 - y2) / denom
    return float(p)


def detect_peaks(sig: np.ndarray, fs: float) -> np.ndarray:
    """Пики систолической волны.

    Главная сложность — дикротический пик: он ниже систолического, но тоже локальный
    максимум. Наивный детектор считал его отдельным ударом (на низком пульсе HR
    удваивался). Поэтому период оценивается по автокорреляции и используется как ориентир.
    """
    if len(sig) < fs:
        return np.array([], dtype=float)

    # Сначала грубый период, затем узкая полоса вокруг него: так шум и гармоники
    # не порождают ложных локальных максимумов (на fs=50 и пульсе 50 это давало +4 bpm).
    period = estimate_period(sig, fs)
    if period:
        f0 = 1.0 / period
        sig = bandpass(sig, fs, max(0.50, 0.70 * f0), min(BAND_HI, 1.60 * f0))

    half = max(1, int(0.14 * fs))
    cand = np.where((sig[1:-1] > sig[:-2]) & (sig[1:-1] >= sig[2:]))[0] + 1
    if cand.size == 0:
        return np.array([], dtype=float)

    prom = np.array([_prominence(sig, int(p), half) for p in cand])
    keep = prom >= 0.30 * np.percentile(prom, 90)
    cand, prom = cand[keep], prom[keep]

    period = estimate_period(sig, fs)
    min_dist = max(1, int(0.45 * period * fs)) if period else max(1, int(0.30 * fs))

    peaks: list[int] = []
    for p, pr in zip(cand, prom):
        if not peaks or p - peaks[-1] >= min_dist:
            peaks.append(int(p))
        elif pr > _prominence(sig, int(peaks[-1]), half):
            peaks[-1] = int(p)

    if period:
        merge_gap = 0.75 * period * fs
        for _ in range(3):
            if len(peaks) < 4:
                break
            changed = False
            kept = [peaks[0]]
            for p in peaks[1:]:
                if p - kept[-1] < merge_gap and len(kept) > 1:
                    if _prominence(sig, p, half) > _prominence(sig, kept[-1], half):
                        kept[-1] = p
                    changed = True
                else:
                    kept.append(p)
            peaks = kept
            if not changed:
                break

    return np.array([_refine_peak(sig, p) for p in peaks], dtype=float)


def rr_analysis(peaks: np.ndarray, fs: float) -> dict[str, float | int]:
    """RR-интервалы с физиологической фильтрацией и метрики HRV."""
    if len(peaks) < 4:
        return {"valid": 0, "hr": 0.0, "coverage": 0.0, "rmssd": 0.0, "sdnn": 0.0}
    rr = np.diff(peaks) / fs
    rr = rr[(rr >= RR_MIN_S) & (rr <= RR_MAX_S)]
    if len(rr) < 3:
        return {"valid": int(len(rr)), "hr": 0.0, "coverage": 0.0, "rmssd": 0.0, "sdnn": 0.0}

    coverage = 0.0
    for _ in range(3):
        med = float(np.median(rr))
        keep = np.abs(rr - med) <= 0.30 * med
        coverage = float(keep.mean())
        if keep.all():
            break
        rr = rr[keep]
        if len(rr) < 3:
            return {"valid": int(len(rr)), "hr": 0.0, "coverage": coverage,
                    "rmssd": 0.0, "sdnn": 0.0}

    diffs = np.diff(rr)
    return {
        "valid": int(len(rr)),
        "hr": float(60.0 / np.median(rr)),
        "coverage": coverage,
        "rmssd": float(np.sqrt(np.mean(diffs ** 2)) * 1000.0) if len(diffs) else 0.0,
        "sdnn": float(np.std(rr, ddof=1) * 1000.0) if len(rr) > 2 else 0.0,
    }


def plausibility(sq: float, stats: dict, duration_s: float, fs: float,
                 min_sq: float = 3.0) -> tuple[bool, str]:
    """Проверка, что найденное — действительно пульс, а не периодический артефакт.

    Записи с часов, лежащих на зарядке, давали «пульс» 45–49 уд/мин при RMSSD 300 мс
    и всего 11 RR за минуту. Спектральный пик при этом высокий, поэтому одного SQ мало:
    проверяем долю найденных ударов и (на достаточной частоте) физиологичность HRV.
    """
    reasons: list[str] = []
    if sq < min_sq:
        reasons.append(f"SQ {sq:.1f} < {min_sq:.1f}")
    hr = float(stats.get("hr") or 0.0)
    valid = int(stats.get("valid") or 0)
    if hr <= 0:
        reasons.append("HR не определён")
        return False, "; ".join(reasons)
    if valid < 10:
        reasons.append(f"мало RR ({valid})")
    expected = duration_s / (60.0 / hr)
    if expected > 0 and valid < 0.30 * expected:
        reasons.append(f"RR {valid} из ~{expected:.0f} ожидаемых — удары теряются")
    if float(stats.get("coverage") or 0.0) < 0.70:
        reasons.append(f"стабильных RR {stats['coverage'] * 100:.0f}% < 70%")
    # RMSSD/SDNN в покое — 20–90 мс; 300 мс означает, что детектор считает не удары.
    # Порог применим и на 50 Гц: джиттер дискретизации там даёт максимум ~40 мс.
    if float(stats.get("rmssd") or 0.0) > 150.0:
        reasons.append(f"RMSSD {stats['rmssd']:.0f} мс нефизиологичен")
    if float(stats.get("sdnn") or 0.0) > 120.0:
        reasons.append(f"SDNN {stats['sdnn']:.0f} мс нефизиологичен")
    return (not reasons), "; ".join(reasons)


def best_representation(V: np.ndarray, fs_ev: float) -> list[tuple[str, np.ndarray, float]]:
    """Кандидаты: отдельные столбцы на fs событий и интерливинг пакетов на 4×fs."""
    out: list[tuple[str, np.ndarray, float]] = []
    for j in (1, 2, 3, 4, 6, 7, 8, 9):
        col = V[:, j]
        if len(col) > fs_ev * 4 and np.std(col) > 0:
            out.append((f"v{j}@{fs_ev:.0f}Hz", col, fs_ev))
    for name, cols in (("v1-4", [1, 2, 3, 4]), ("v6-9", [6, 7, 8, 9])):
        flat = V[:, cols].reshape(-1)
        if np.std(flat) > 0:
            out.append((f"{name}@{fs_ev * 4:.0f}Hz", flat, fs_ev * 4))
    return out


def analyze_capture(path: str, min_sq: float = 3.0) -> dict:
    data = read_ppg(path)
    result: dict = {"file": os.path.basename(path), "sensors": {}}
    for sensor, (ts, V) in sorted(data.items()):
        if len(ts) < 10:
            continue
        duration_s = float((ts[-1] - ts[0]) / 1e9)
        fs_ev = 1.0 / float(np.median(np.diff(ts) / 1e9))
        per_sensor = []
        for name, sig, fs in best_representation(V, fs_ev):
            bp = bandpass(sig, fs)
            sq = signal_quality(bp, fs)
            stats = rr_analysis(detect_peaks(bp, fs), fs)
            ok, why = plausibility(sq, stats, duration_s, fs, min_sq)
            per_sensor.append({"repr": name, "sq": sq, "plausible": ok, "why": why, **stats})
        per_sensor.sort(key=lambda r: (r["plausible"], r["sq"], r["valid"]), reverse=True)
        result["sensors"][sensor] = per_sensor
    return result


# ---------------------------------------------------------------- самопроверка

def synthetic_ppg(fs: float = 200.0, seconds: float = 60.0, hr_bpm: float = 72.0,
                  noise: float = 0.01, dicrotic: float = 0.18) -> np.ndarray:
    """Синтетическая PPG: кардио + дыхание + дикротический пик + шум, HR известен."""
    t = np.arange(0, seconds, 1.0 / fs)
    base = 1.0 + 0.10 * np.sin(2 * np.pi * 0.25 * t)
    pulse = np.sin(2 * np.pi * (hr_bpm / 60.0) * t)
    dicro = dicrotic * np.sin(2 * np.pi * (hr_bpm / 60.0) * 2 * t + 0.7)
    return base * (0.5 + 0.03 * pulse + 0.03 * dicro) \
        + noise * np.random.default_rng(7).standard_normal(t.size)


def selftest() -> int:
    print("=== SELF-TEST: синтетическая PPG с известным пульсом ===")
    failures = 0
    cases = [(fs, hr, noise) for fs in (50.0, 200.0)
             for hr in (45.0, 50.0, 55.0, 60.0, 72.0, 84.0, 96.0, 110.0)
             for noise in (0.005, 0.02)]
    for fs, hr_true, noise in cases:
        x = synthetic_ppg(fs=fs, seconds=60.0, hr_bpm=hr_true, noise=noise)
        bp = bandpass(x, fs)
        sq = signal_quality(bp, fs)
        stats = rr_analysis(detect_peaks(bp, fs), fs)
        hr = stats["hr"]
        err = abs(hr - hr_true) if hr else 999.0
        ok = hr > 0 and err <= 2.5 and sq > 3.0 and stats["coverage"] > 0.80
        failures += 0 if ok else 1
        print(f"  fs={fs:5.0f} true={hr_true:5.1f} noise={noise:5.3f}  got={hr:6.1f}  "
              f"err={err:4.1f}  SQ={sq:6.1f}  RR={stats['valid']:3d}  "
              f"ok={stats['coverage']*100:5.1f}%  RMSSD={stats['rmssd']:5.1f}  "
              f"{'OK' if ok else 'FAIL'}")
    total = len(cases)
    print(f"итог: {total - failures}/{total} прошли" if failures else f"итог: все {total} прошли")
    return 0 if failures == 0 else 1


# ---------------------------------------------------------------- CLI

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="*")
    ap.add_argument("--file")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--min-sq", type=float, default=3.0, help="порог качества сигнала")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    files = [args.file] if args.file else (args.files or sorted(glob.glob("ppg_raw_*.csv")))
    if not files:
        ap.print_help()
        return 2

    rc = 0
    for path in files:
        res = analyze_capture(path, args.min_sq)
        print(f"\n{'=' * 84}\n{res['file']}")
        for sensor, rows in res["sensors"].items():
            print(f"\n  {sensor}")
            for r in rows[:3]:
                verdict = "ПУЛЬС ЕСТЬ" if r["plausible"] else f"не пульс: {r['why']}"
                print(f"    {r['repr']:14s} SQ={r['sq']:6.1f}  HR={r['hr']:6.1f} bpm  "
                      f"RR={r['valid']:3d}  ok={r['coverage'] * 100:5.1f}%  "
                      f"RMSSD={r['rmssd']:6.1f} ms  SDNN={r['sdnn']:6.1f} ms  -> {verdict}")
            best = rows[0] if rows else None
            if best is None or not best["plausible"]:
                rc = 1

    if rc:
        print("\nЕсть записи без пригодного пульса. Нужен захват на руке: часы плотно на запястье,"
              "\nне на зарядке, 60 с без движения (кнопка «P Тест PPG» в диагностике).")
    return rc


if __name__ == "__main__":
    sys.exit(main())
