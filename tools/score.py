#!/usr/bin/env python3
"""예측 적중률(첫 시도 기준)을 모듈별로 보여준다.  사용: python3 tools/score.py"""
import collections
import pathlib
import re

root = pathlib.Path(__file__).resolve().parent.parent
ledger = root / "journal" / "ledger.tsv"

# 테스트 코드에 등장하는 예측 ID 전체 (분모)
ids = collections.defaultdict(set)
for test in root.glob("m*/src/test/java/**/*.java"):
    for m in re.finditer(r'"(m\d\d[a-z-]*\.[A-Za-z0-9_.\-]+)"', test.read_text(encoding="utf-8")):
        pid = m.group(1)
        ids[pid.split(".")[0]].add(pid)

hits = collections.defaultdict(int)
answered = collections.defaultdict(int)
if ledger.exists():
    for line in ledger.read_text(encoding="utf-8").splitlines():
        cols = line.split("\t")
        if len(cols) < 4:
            continue
        mod = cols[0].split(".")[0]
        answered[mod] += 1
        hits[mod] += cols[3] == "HIT"

print(f"{'모듈':<8}{'진행':>12}{'첫 시도 적중률':>16}   판정 (기준: 전부 답하고 90% 이상)")
for mod in sorted(set(ids) | set(answered)):
    total, a, h = len(ids[mod]), answered[mod], hits[mod]
    rate = (h / a * 100) if a else 0.0
    done = total and a >= total
    verdict = "통과" if done and rate >= 90 else ("재도전: 오답 규칙 복습" if done else "진행 중")
    print(f"{mod:<8}{f'{a}/{total}':>12}{rate:>15.1f}%   {verdict}")
