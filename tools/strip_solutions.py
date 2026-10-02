#!/usr/bin/env python3
"""모듈의 solutions/ (참고 해답, 정답 마커 포함) 로부터 학습자용 파일을 다시 만든다 (출제자용).

각 모듈 mXX/ 에 대해
  1. solutions/src/main/java  → src/main/java 로 통째로 복사 (기존 src/main/java 는 지운다)
  2. solutions/src/test/java  → (있으면) src/test/java 로 통째로 복사
  3. solutions/ 아래의 *.md     → 모듈 안의 같은 상대 경로로 복사 (PREDICT.md, DECISION.md, guides/... 등)
  4. 복사된 학습자 쪽 파일에서 정답을 제거한다. solutions/ 자체는 건드리지 않는다.

마커 규칙
  Java
    /*ANSWER*/ <식> /*END*/            -> null                       (예측 시트의 답)
    // SOLUTION-BEGIN ... // SOLUTION-END -> throw new UnsupportedOperationException("TODO");
    // SOLUTION-REMOVE-BEGIN ... // SOLUTION-REMOVE-END -> 삭제   (정답에만 필요한 필드/헬퍼/import)
    파일 안에 "// SOLUTIONS-BRANCH-ONLY" 가 있으면 학습자 쪽에서 파일 삭제 (정답을 단언하는 사실 검증 테스트 등)
  Markdown
    <!-- ANSWER-BEGIN --> ... <!-- ANSWER-END -->  -> "(여기에 작성)"

사용: python3 tools/strip_solutions.py [저장소 루트]
"""
import pathlib
import re
import shutil
import sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

ANSWER = re.compile(r"/\*ANSWER\*/.*?/\*END\*/", re.S)
SOLUTION = re.compile(r"^([ \t]*)// SOLUTION-BEGIN[^\n]*\n.*?^[ \t]*// SOLUTION-END[^\n]*\n", re.S | re.M)
REMOVE = re.compile(r"^[ \t]*// SOLUTION-REMOVE-BEGIN[^\n]*\n.*?^[ \t]*// SOLUTION-REMOVE-END[^\n]*\n", re.S | re.M)
MD_ANSWER = re.compile(r"<!-- ANSWER-BEGIN -->.*?<!-- ANSWER-END -->", re.S)


def strip_file(path: pathlib.Path) -> None:
    src = path.read_text(encoding="utf-8")
    if path.suffix == ".java":
        if "// SOLUTIONS-BRANCH-ONLY" in src:
            path.unlink()
            return
        out = ANSWER.sub("null", src)
        out = SOLUTION.sub(lambda m: f'{m.group(1)}throw new UnsupportedOperationException("TODO");\n', out)
        out = REMOVE.sub("", out)
    else:
        out = MD_ANSWER.sub("(여기에 작성)", src)
    if out != src:
        path.write_text(out, encoding="utf-8")


def copy_tree(src: pathlib.Path, dst: pathlib.Path) -> list:
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst)
    return [p for p in dst.rglob("*.java")]


touched = 0
for module in sorted(p for p in root.glob("m*") if (p / "solutions").is_dir()):
    sol = module / "solutions"
    files = copy_tree(sol / "src/main/java", module / "src/main/java")
    if (sol / "src/test/java").is_dir():
        files += copy_tree(sol / "src/test/java", module / "src/test/java")
    for md in sol.rglob("*.md"):
        target = module / md.relative_to(sol)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(md, target)
        files.append(target)
    for f in files:
        strip_file(f)
    touched += len(files)
print(f"학습자 파일 재생성 완료: {touched}개 파일 처리")
