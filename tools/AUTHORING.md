# 출제자용 작성 규칙 (모듈 작성자 필독)

이 repo는 학습자가 아래 능력을 **체화**하도록 만드는 것이 목적이다. 모든 콘텐츠는 이 목적에 기여해야 한다.

1. 상황을 보고 CF를 쓸지(A 병렬 blocking / B 완전 non-blocking / 쓰지 않음) 판단하고 **합당한 근거**를 댈 수 있다.
2. 도구 없이도 **의도대로 동작하는 파이프라인**을 처음부터 거의 오류 없이 작성한다(90% 이상의 확신).
3. 남의 파이프라인(또는 순간 실수한 내 것)의 **잘못된 설계를 코드만 보고** 알아보고 증상을 말할 수 있다.
4. CF를 쓰는 **오픈소스 코드베이스를 읽고**, 올바르게 기여할 수 있다.

## 1. 기술 환경
- JDK 25(Temurin 25.0.3)로 빌드한다. 실습 코드는 `--release 17`(부모 POM 기본값)이다. JDK 21은 `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`에 있다(17과 동작이 같은 버전 비교용).
- Maven 멀티모듈. 부모 POM과 harness는 `~/.m2`에 설치되어 있다. 모듈 빌드: `mvn -q -pl <module> test` (루트에서).
- **harness와 루트 `pom.xml`, `docs/`는 수정하지 않는다.** 필요한 헬퍼는 자기 모듈 안에 만든다. harness에 꼭 필요한 변경이 있으면 최종 보고에 적는다.
- 자기 모듈 `pom.xml`에는 의존성과 플러그인 설정만 추가한다(parent는 유지).
- 패키지: `async.<모듈번호>` (예: `async.m02`). 하위 패키지 자유. 공용 도구는 `async.harness`.
- 문서와 주석은 **한국어**로 쓴다. 식별자는 영어로 쓴다.

## 2. harness 사용법 (`async.harness`)
| 클래스 | 용도 |
|---|---|
| `ThreadRecorder` | `rec.mark("step")` → `rec.on("step")`(On), `rec.atMillis("step")`, `rec.count`, `rec.order()` |
| `On` | `caller()`, `commonPool()`, `pool("io")`, `eventLoop()`, `timeoutScheduler()`, `freshThread()`, `virtualThread()` |
| `Pools` | `Pools.fixed("io", n)`, `bounded(name, n, queue, policy)`, `single(name)`. `NamedPool`은 AutoCloseable이고 `largestPoolSize()`, `rejectedCount()`, `queuedCount()`를 제공한다 |
| `FakeBlockingService` | `new FakeBlockingService("db", 100)`, `.call(key)` (sleep), `latencyFor`, `failOn`, `maxConcurrency()`, `inFlight()`, `interruptedCount()`, `callerThreads()` |
| `FakeEventLoopClient` | 이벤트 루프 스레드 `event-loop-N`이 future를 완료시킨다. `fetch(key)`, `maxLagMillis()`, 생성자에 `completionExecutor` 지정 가능 |
| `BlockHoundSupport` | `reset()`, `violations()`, `mode(THROW)`. `event-loop-`, `nonblocking-`, Netty/Lettuce/AWS/DataStax 이벤트 루프 스레드에서 일어나는 blocking을 감지한다 |
| `Gate` | `await()`/`open()`. sleep 대신 타이밍을 결정적으로 통제한다 |
| `Timed` | `Timed.of(supplier)` → `value()`, `millis()` |
| `Sleep` | `Sleep.ms(n)` |
| `StubHttpServer` | 로컬 HTTP 서버: `route(path, latencyMs, status, body)`, `uri(path)`, `maxConcurrentRequests()` |
| `Grader` | `check(id, predicted, actual, "R1")`, `checkMillis(id, Long, long, rule)`, `checkHashed(id, String, sha256, rule)`, `verify()` |

정답 해시: `python3 tools/hash_answer.py "정답"`. 비교 전에 소문자 변환, 앞뒤 공백 제거, 따옴표 제거를 한다.

## 3. 모듈 구성 (m01을 참조 구현으로 삼는다: `m01-mental-model/`)
```
mXX-.../
├── README.md        # 핵심 개념 (왜 → 무엇 → 규칙 → 실무에서의 증상), 진행 방법, 통과 기준, 실행 명령
├── PREDICT.md       # 예측 워크시트 + 서술 문제 + <!-- ANSWER-BEGIN -->모범답안<!-- ANSWER-END -->
├── DECISION.md      # (설계 실습이 있는 모듈) 판단 양식 + 모범답안(ANSWER 마커)
├── src/main/java/async/mXX/
│   ├── Scenarios.java / *Scenario*.java  # 읽고 예측만 하는 고정 코드 (학습자가 수정하지 않음)
│   ├── Predictions.java                  # public static final 필드, 값은 /*ANSWER*/ 식 /*END*/
│   └── Exercises*.java / 설계 실습 클래스 # TODO 구현 대상, 정답은 // SOLUTION-BEGIN ~ // SOLUTION-END
└── src/test/java/async/mXX/
    ├── PredictionTest.java   # Grader로 예측 채점
    └── *Test.java            # 구현 채점
```

### 해답 위치와 정답 마커
- 참고 해답은 각 모듈의 `solutions/` 아래에 둔다: `solutions/src/main/java`(main과 **같은 FQCN**, main 전체 사본), 테스트까지 다르면 `solutions/src/test/java`(전체 사본, 그 모듈 pom에 `solutions.test.source.dir` 지정), 모범 답안이 든 Markdown은 모듈 안 같은 상대 경로(`solutions/PREDICT.md`, `solutions/guides/..`).
- **출제는 `solutions/` 쪽을 고친다.** 그다음 `python3 tools/strip_solutions.py`가 마커를 제거한 학습자용 파일(`src/`, 모듈의 *.md)을 다시 만든다.
- 해답 검증: `mvn test -Psolutions` (예측 ledger 를 기록하지 않는다).

마커 (학습자용 파일을 만들 때 제거된다)
- 예측 필드: `public static final On S01_MAP = /*ANSWER*/ On.pool("io") /*END*/;` → `null`
- 구현: 메서드 본문 **전체**를 `// SOLUTION-BEGIN` ~ `// SOLUTION-END`로 감싼다 → `throw new UnsupportedOperationException("TODO");`. 그 바로 위에 `// TODO(Ex): 힌트 (관련 규칙)` 주석을 남긴다.
- 정답에만 필요한 필드나 헬퍼: `// SOLUTION-REMOVE-BEGIN` ~ `// SOLUTION-REMOVE-END` → 통째로 삭제
- Markdown 모범답안: `<!-- ANSWER-BEGIN -->` ~ `<!-- ANSWER-END -->` → "(여기에 작성)"
- 파일 전체가 해답 전용(예측 정답을 단언하는 사실 검증 테스트 등): 파일 안에 `// SOLUTIONS-BRANCH-ONLY` → 학습자 쪽에서 파일 삭제
- **제거 후에도 학습자 코드가 컴파일되어야 한다.** (예: 제거되는 헬퍼를 남는 코드가 참조하면 안 된다.)

## 4. 품질 기준 (반드시 지킨다)
1. **사실 정확성**: 스레드, 시간, 예외 타입에 대한 모든 주장은 JDK 25에서 **실행해서 검증**한다. 버전에 따라 다르면 `docs/java-version-matrix.md`의 표기를 따르고, 필요하면 JDK 21로도 확인한다.
2. **결정성**: 예측 시나리오는 실행할 때마다 같은 결과가 나와야 한다. 타이밍은 `Gate`로 통제하고, 시간 예측은 ≥100ms 단위의 큰 차이로 설계한다. **모든 테스트를 최소 3회 반복 실행해서 flaky하지 않음을 확인한다.**
3. **비결정적 상황 자체를 가르칠 때**는 예측 채점 대상에서 빼고, 서술 문제나 "두 가능성 모두 허용" 형태로 다룬다.
4. 예측 ID는 `"mXX.<시나리오>.<항목>"` 문자열 리터럴로 테스트에 직접 쓴다(`tools/score.py`가 grep으로 센다). 전역에서 고유해야 한다.
5. Grader의 rule 인자에는 `docs/reasoning-rules.md`의 R 번호를 쓴다(모듈 고유 규칙이 있으면 README에 정의하고 그 이름을 쓴다).
6. 구현 테스트는 "결과값"만이 아니라 **목적 달성 여부**를 검증한다: 실행 스레드(ThreadRecorder), blocking 위반(BlockHoundSupport), 소요시간(Timed), 동시성(maxConcurrency), 풀 사용(NamedPool) 등.
7. 테스트 타임아웃을 항상 건다(`get(2, SECONDS)`, `@Timeout`). deadlock 실습이 테스트를 영원히 멈추게 하면 안 된다.
8. 각 테스트 실패 메시지(`as(...)`)에는 **무엇이 왜 틀렸는지와 관련 규칙**을 적는다. 학습자가 테스트 코드를 읽지 않아도 고칠 방향을 알 수 있어야 한다.
9. 난이도는 모듈 안에서 점진적으로 올린다. 같은 규칙을 다른 모양으로 여러 번 연습하게 한다.
10. 실무 맥락: 학습자는 Java/Spring 백엔드 개발자다. 시나리오는 실제 서비스 모양(주문, 상품, 결제, 캐시, 외부 API)으로 만든다.

## 5. 완료 전 체크리스트
- [ ] `mvn -q -pl <module> test -Psolutions` 통과 (해답). 3회 반복해도 안정적인가
- [ ] 학습자 코드 확인: `python3 tools/strip_solutions.py`로 학습자 파일을 다시 만든 뒤 `mvn -q -pl <module> test-compile`이 성공하는가. 테스트를 돌리면 "미작성"/"TODO"로 실패하는가(정답이 새어 나오지 않는가). `mvn -q -pl <module> test -Psolutions`는 통과하는가
- [ ] README에 실행 명령, 통과 기준, 관련 규칙이 있는가
- [ ] 모든 주장이 실험으로 검증되었는가
