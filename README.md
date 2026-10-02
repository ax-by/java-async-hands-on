# java-async-hands-on

`CompletableFuture`를 **올바르게 설계하고, 잘못된 설계를 코드만 보고 알아보고, 오픈소스 코드를 읽고 기여할 수 있는"** 수준까지 체화하기 위한 실습 저장소다.

## 졸업했을 때 할 수 있어야 하는 것

1. **판단**: 상황을 보고 CF를 쓸지 정하고, 쓴다면 **A(병렬 blocking fan-out)**인지 **B(end-to-end non-blocking)**인지 정한다. 그 근거를 숫자와 함께 합당하게 설명한다.
2. **작성**: 도구 없이도 의도대로 동작하는 파이프라인을 처음부터 오류 없이 작성한다. 각 단계의 실행 스레드, blocking 여부, 시간, 실패 경로를 90% 이상 확신하고 말할 수 있다.
3. **진단**: 남의 코드나 순간 실수한 내 코드에서 잘못된 설계를 읽어 내고, 어떤 증상이 나타날지 말한다.
4. **오픈소스**: CF를 쓰는 실제 코드베이스(Caffeine, Kafka, Lettuce, AWS SDK v2, DataStax 드라이버)의 파이프라인 지도를 그리고, 그 설계 관례에 맞게 기여한다.

## 준비물

- **JDK 25** (`JAVA_HOME`). 빌드는 JDK 25로 하고, 각 모듈은 `--release`로 대상 버전을 강제한다(대부분 17, m09는 8).
- Maven 3.9+, Python 3 (채점 도구).
- (선택) JDK 21: m04에서 Java 17 계열의 런타임 동작을 재현할 때 쓴다. 없으면 해당 테스트는 건너뛴다.

```bash
mvn -q install -DskipTests          # 처음 한 번: 전체 빌드
python3 tools/score.py              # 모듈별 적중률 보기
```

## 학습 순서

| 단계 | 모듈 | 핵심 질문 | 난이도 |
|---|---|---|---|
| 기초 | [m01-mental-model](m01-mental-model/README.md) | 이 콜백은 어느 스레드에서, 언제 실행되나? | ★ |
| | [m02-composition](m02-composition/README.md) | `thenApply`/`thenCompose`/`thenCombine`/`allOf` 중 무엇을 왜? 순차와 병렬 | ★ |
| 실패 처리 | [m03-failure](m03-failure/README.md) | 예외는 어디로 가나? 타임아웃과 취소는 실제 작업을 멈추나? | ★★ |
| | [m04-executors](m04-executors/README.md) | 이 작업은 어느 풀에서, 몇 개의 스레드로? deadlock은 언제 생기나? | ★★ |
| 설계 | [m05-design-a](m05-design-a/README.md) | A: 병렬 blocking fan-out으로 응답시간 목표를 달성하기 | ★★★ |
| | [m06-design-b](m06-design-b/README.md) | B: 어떤 스레드도 막히지 않는 파이프라인 | ★★★★ |
| | [m07-integration](m07-integration/README.md) | Spring에서: MDC/SecurityContext 전파, `@Async`, 서블릿 경계 | ★★★ |
| | [m07-vt-compare](m07-vt-compare/README.md) | 가상 스레드, `StructuredTaskScope`와 비교 (Java 25) | ★★★ |
| 진단 | [m08-bug-hunt](m08-bug-hunt/README.md) | 코드만 보고 버그와 증상 맞히기 | ★★★★ |
| 오픈소스 | [m09-library-author](m09-library-author/README.md) | 라이브러리 작성자 관점의 공개 API 설계 (Java 8 호환) | ★★★★ |
| | [m10-oss-reading](m10-oss-reading/README.md) | 실제 오픈소스의 파이프라인 지도 그리기와 기여 시뮬레이션 | ★★★★~★★★★★ |
| 졸업 | [m11-capstone](m11-capstone/README.md) | 도구 없이 판단·설계·작성한 뒤 마지막에만 채점 | ★★★★★ |

## 모든 실습의 공통 순서

```
판단 → 예측 → 구현 → 채점 → 오답 기록
```

1. **판단**: 설계 실습이면 먼저 `DECISION.md`를 채운다([판단 가이드](docs/decision-guide.md)).
2. **예측**: 코드를 실행하기 전에 `PREDICT.md`에 각 단계의 실행 스레드, blocking 여부, 시간, 예외 경로와 **근거 규칙**을 적고, 그 값을 `Predictions.java`로 옮긴다.
3. **구현**: TODO를 구현한다.
4. **채점**: 테스트를 돌린다. 예측의 **첫 시도 결과는 `journal/ledger.tsv`에 고정**된다. 결과를 본 뒤 예측을 고쳐도 적중률은 오르지 않는다.
5. **오답 기록**: 틀린 예측은 [journal](journal/README.md)에 "어떤 규칙을 어떻게 잘못 적용했는지" 적는다.

**모듈 통과 기준**: 예측 적중률(첫 시도) **90% 이상** + 구현 테스트 전부 통과. `python3 tools/score.py`로 확인한다.

보조 도구는 뒤로 갈수록 줄어든다. m01~m04는 결과를 바로 보여 준다. m05~m10은 예측을 먼저 확정해야 채점한다. m11은 작성을 끝낸 뒤 한 번만 채점한다.

## 실행 명령

```bash
# 모듈 하나 전체
mvn -pl m01-mental-model -am test

# 예측 채점만
mvn -pl m01-mental-model -am test -Dtest=PredictionTest -Dsurefire.failIfNoSpecifiedTests=false

# 구현 채점만
mvn -pl m01-mental-model -am test -Dtest=ExercisesTest -Dsurefire.failIfNoSpecifiedTests=false

# 졸업 과제 채점 (m11)
mvn -pl m11-capstone -am verify -Pgrade

# 참고 해답을 같은 테스트로 검증 (모두 통과해야 한다. 예측 ledger 는 기록하지 않는다)
mvn -pl m01-mental-model -am test -Psolutions
mvn install -Psolutions                          # 전체
mvn -pl m11-capstone -am verify -Pgrade,solutions
```

- **시작 코드는 처음부터 컴파일된다.** 예측은 `null`, 구현은 `throw new UnsupportedOperationException("TODO")`라서 아직 하지 않은 부분의 테스트만 실패한다.
- `-Psolutions` 프로필은 소스 폴더를 각 모듈의 `solutions/src/main/java`(테스트까지 다른 m07, m07-vt, m09는 `solutions/src/test/java`도)로 바꾼다. 테스트는 같은 것을 쓴다.
- 루트에서 `mvn test`를 하면 시작 코드의 테스트가 일부러 실패하므로 첫 모듈에서 멈춘다. 전체 진행 상황은 `mvn test -fae -Dmaven.test.failure.ignore=true`로 본다.

## 핵심 문서

| 문서 | 내용 |
|---|---|
| [docs/reasoning-rules.md](docs/reasoning-rules.md) | **추론 규칙 카드 R0~R13.** 도구 없이 판단할 때 쓰는 규칙. 모든 채점은 이 번호로 피드백한다 |
| [docs/decision-guide.md](docs/decision-guide.md) | CF를 쓸지, A인지 B인지 판단하는 흐름과 `DECISION.md` 양식 |
| [docs/review-checklist.md](docs/review-checklist.md) | 리뷰 체크리스트(안티패턴 19개와 증상)와 리뷰 코멘트 양식 |
| [docs/java-version-matrix.md](docs/java-version-matrix.md) | Java 8/9/12/17/19/21/25 API 차이와 **실험으로 확인한 런타임 동작 차이** |

## 저장소 구조

```
java-async-hands-on/
├── docs/            # 규칙 카드, 판단 가이드, 리뷰 체크리스트, 버전 차이
├── harness/         # 공용 도구: 스레드 기록기, 가짜 blocking 서비스, 가짜 이벤트 루프 클라이언트,
│                    #   BlockHound 연동, 로컬 HTTP 스텁 서버, 예측 채점기
├── m01 … m11/
│   ├── README.md                  # 핵심 개념, 진행 방법, 통과 기준
│   ├── PREDICT.md (DECISION.md, REVIEW.md)   # 예측 워크시트, 판단 양식 (모범 답안 칸은 비어 있음)
│   ├── src/main/java/async/mXX/          # 학습자 코드: 시나리오(읽기 전용), Predictions(예측), TODO 실습
│   ├── src/test/java/async/mXX/          # 채점 테스트
│   └── solutions/                            # 참고 해답 — main과 같은 클래스 이름(FQCN)
│       ├── src/main/java/…                   #   예측 정답과 구현 해답
│       ├── src/test/java/…                   #   (m07, m07-vt, m09만) 해답 쪽 테스트
│       └── PREDICT.md, DECISION.md, …        #   모범 답안이 채워진 문서 (같은 상대 경로)
├── journal/         # 내 예측 기록(ledger.tsv, 자동)과 오답 노트(notes.md, 직접 작성)
└── tools/           # score.py(적중률), hash_answer.py, strip_solutions.py·AUTHORING.md(출제자용)
```

## `solutions/` 사용법

- 해답은 각 모듈의 `solutions/` 아래에 **학습자 코드와 같은 패키지·클래스 이름**으로 있다. 섞이지 않도록 폴더를 분리했다.
- **먼저 스스로 풀어라.** 예측은 첫 시도가 ledger에 고정되므로, 모듈의 예측을 모두 채점하기 전에는 `solutions/`를 열지 않는다. 구현 실습은 테스트가 통과한 뒤나 충분히 막혔을 때 해당 파일 하나만 열어 비교한다.
- 비교할 때는 결과보다 **실행 스레드, blocking 위치, 시간, 실패 경로를 왜 그렇게 설계했는지**를 본다. `solutions/PREDICT.md`의 모범 답안에는 근거 규칙이 적혀 있다.
- 해답 파일을 `src/`에 **복사하지 마라.** 내 답이 덮어써진다. 해답이 테스트를 통과하는지는 `-Psolutions`로 확인한다.
- 해답은 "유일한 정답"이 아니다. 테스트가 확인하는 목적(시간, 스레드, blocking 0건, 실패 정책)을 만족하면 다른 설계도 옳다. 차이가 있으면 `journal/notes.md`에 이유를 적는다.
