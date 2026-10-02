#!/usr/bin/env python3
"""Grader.checkHashed 용 정답 해시 생성 (출제자용).  사용: python3 tools/hash_answer.py "정답 문자열" """
import hashlib, re, sys
s = sys.argv[1].strip().lower()
s = re.sub(r"[\"'`]", "", s)
s = re.sub(r"\s+", " ", s)
print(hashlib.sha256(s.encode("utf-8")).hexdigest())
