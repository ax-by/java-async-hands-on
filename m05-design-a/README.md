# m05. 설계 A: 서블릿 서비스의 병렬 blocking fan-out

> 난이도 ★★★ · 관련 규칙 **R0, R7, R8, R9, R10, R11** · 예측 21개 · 설계 문서 1개 · 구현 3개

## 이 모듈을 마치면

- "이 API에 CF를 쓸까?"에 A / B / 쓰지 않음 중 하나로 답하고, **숫자로** 근거를 댈 수 있다.
- JDBC·RestTemplate 기반 서비스에서 독립 호출을 병렬화하는 파이프라인을 **전용 풀, 전체 타임아웃, 소스별 실패 정책, 도메인 예외 unwrap**까지 갖춰 처음부터 작성할 수 있다.
- 동료의 "비동기로 바꿨어요" PR을 읽고 그것이 A인지, 순차인지, commonPool을 막는지 코드만 보고 말할 수 있다.
- 타임아웃이 **응답시간만** 자르고 스레드는 풀어 주지 않는다는 것(R10)을 근거로 풀 여유분과 클라이언트 타임아웃을 설계할 수 있다.

---

## 시나리오: 상품 상세 집계 API

Java 17 + Spring MVC(Tomcat) 서비스의 `GET /products/{id}`. 네 개의 다운스트림을 호출해 하나의 응답을 만든다.

| 소스 | 클라이언트 | 평소 지연 | 필수? | 실패/지연 시 |
|---|---|---|---|---|
| product | JDBC | 80ms | 필수 | 요청 실패 (`ProductDetailException`) |
| price | RestTemplate | 120ms | 필수 | 요청 실패 |
| stock | RestTemplate | 100ms | 선택 | `"UNKNOWN"` |
| reviews | JDBC | 150ms | 선택 | 빈 목록 |

SLO: 평소 p99 < 200ms, 전체 하드 타임아웃 300ms, 피크 100 RPS. 지금 운영 코드(`Scenarios.naiveSequential`)는 네 호출을 순차로 한다.

---

## 핵심 개념

### 1. A는 "스레드를 아끼는 기술"이 아니라 "기다림을 겹치는 기술"이다

```
순차: 요청 스레드 하나가 호출 네 개를 차례로 기다린다
 http-nio-1: [product][price  ][stock ][reviews   ]

A: 요청 스레드는 제출만 하고 반환, io 스레드 네 개가 동시에 기다린다
 io-1: [product]
 io-2: [price  ]
 io-3: [stock ]
 io-4: [reviews   ]
```

- 막히는 스레드 시간의 **합**은 그대로다. **막히는 스레드가 바뀌고, 기다림이 겹칠 뿐이다**(R0). 응답시간이 어떻게 바뀌는지는 R7로 계산한다(T01, T04).
- 그래서 A의 비용은 **스레드 수**다. 요청 하나가 io 스레드를 동시에 여러 개 쓰고, 피크 RPS에서 동시에 막혀 있는 스레드 수는 Little의 법칙으로 계산한다(E3).
- JDBC를 `supplyAsync`로 감싼 코드를 "non-blocking"이라고 부르면 틀린다. B는 I/O 동안 **어떤 스레드도** 점유하지 않는 것이고, 그러려면 드라이버가 진짜 비동기여야 한다(m06).

### 2. 올바른 A의 네 부품

| 부품 | 없으면 | 규칙 |
|---|---|---|
| **전용 풀** (크기 = Little + 여유, 큐 제한) | commonPool 고갈, 피크에 큐 폭증 | R2, R11 |
| **먼저 모두 시작, 나중에 모으기** (`allOf`/`thenCombine`) | 순차와 같은 응답시간 | R3, R7 |
| **전체 타임아웃** (`orTimeout`) + **소스별 실패 정책** (`exceptionally`, `completeOnTimeout`) | 다운스트림 하나가 멈추면 요청도 무한 대기, 선택 소스 하나 때문에 요청 전체 실패 | R9, R10 |
| **도메인 예외 + unwrap** | `CompletionException` 껍데기 때문에 `@ExceptionHandler`가 안 맞고 엉뚱한 500 | R8 |

```
조립 순서 (각 단계를 무엇으로 구현할지는 E1에서 직접 고른다)
 1) 네 호출을 "모두 먼저" 시작한다 ............ 어느 executor 에서? (R2, R3)
 2) 소스마다 실패 정책을 붙인다 ................ 필수 → 도메인 예외, 선택 → 폴백 (R9)
 3) 네 결과를 모은다 ........................... 모으는 단계에서 join 해도 막지 않는 조건은? (R5, R13)
 4) 전체 상한을 건다 ........................... 스레드를 막지 않는 방법으로 (R10)
 5) 한 곳에서 unwrap 해서 도메인 예외로 바꾼다 .. cause 는 원래 예외 (R8)
```

### 3. 풀 크기: 계산값보다 작으면 "조금 느려지는" 게 아니다

- 필요 스레드 ≈ RPS × 요청당 blocking 시간의 합(초) × 여유율(R11). 이 값은 **평균 동시 점유 수**다.
- 풀이 이보다 작으면 들어오는 일이 처리 능력보다 많다. 그러면 큐가 **계속** 자라고, 큐 대기 + 실행이 전체 타임아웃을 넘는 순간부터 실패가 쏟아진다. 타임아웃 난 호출도 끝까지 스레드를 쓰므로(R10) 더 나빠진다. 계산값의 몇 분의 일인 풀이 100 RPS에서 어떻게 되는지 L01에서 예측해 보라.
- 다운스트림 한도도 함께 본다. 요청당 JDBC 시간이 0.23s(product + reviews)면 100 RPS에서 커넥션이 몇 개 동시에 필요한가? **HikariCP 기본 `maximumPoolSize`는 10**이다(실측). 커넥션이 모자라면 io 풀만 키워 봐야 io 스레드가 커넥션을 기다리며 막힌다.

### 4. 타임아웃은 응답을 자를 뿐, 일을 멈추지 않는다 (R10)

- `orTimeout`이 발화하면 **future의 상태만** 바뀐다. 진행 중인 JDBC/HTTP 호출은 끝날 때까지 io 스레드(와 DB 커넥션)를 계속 점유한다.
- 소스별 `completeOnTimeout`도 똑같다. 응답은 폴백으로 먼저 나가지만, 늦은 호출은 그대로 스레드를 잡고 있다. 다운스트림 하나가 **모든 요청에서** 느려지면, 그 늦은 호출들이 동시에 몇 개의 스레드를 잡고 있게 될지 Little의 법칙으로 계산해 보라(L02).
- 스레드를 **실제로** 풀어 주는 것은 클라이언트 자체의 타임아웃이다. RestTemplate connect/read timeout(기본값 **무한**: `SimpleClientHttpRequestFactory` -1, `HttpURLConnection` 0, 실측), JDBC `Statement.setQueryTimeout`, 드라이버의 socket timeout.
- 그러므로 설계 순서는 이렇다. ① 클라이언트 타임아웃 ≤ 소스 타임아웃 ② CF 소스 타임아웃(폴백) ③ CF 전체 타임아웃(SLO 상한) ④ 풀 여유분 ⑤ 느려지기 쉬운 선택 소스는 bulkhead(m04 E5).

### 5. 언제 CF를 쓰지 않는가

[decision-guide](../docs/decision-guide.md)의 Q1·Q3에서 걸러진다.

- **호출이 하나뿐**이거나, 만들자마자 `join()`할 거라면: 스레드만 하나 더 쓰고 얻는 것이 없다.
- **순차 의존**(주문 → 회원 → 쿠폰): `thenCompose`로 이어도 총 시간은 합이다(R7). 동기 코드가 같은 성능에 더 단순하고, 스택 트레이스와 트랜잭션·MDC(R12)도 온전하다.
- **줄어드는 시간이 의미 없을 때**(2ms + 0.1ms): 스레드 전환 비용과 복잡도만 늘어난다.

### 6. 실무에서의 증상 → 원인

| 증상 | 의심할 원인 | 규칙 |
|---|---|---|
| 운영 피크에 이 API와 관계없는 기능까지 느려진다 | executor 생략 → commonPool | R2, R11 |
| "비동기로 바꿨는데" 응답시간이 그대로다 | 시작하자마자 join (호출마다 join) | R7 |
| 피크에만 p99가 폭증하고 타임아웃이 쏟아진다 | 풀이 Little 계산값보다 작다, 무제한 큐 | R11 |
| 선택 기능(리뷰) 장애인데 상품 상세 전체가 실패한다 | 클라이언트 타임아웃 없음 → 늦은 호출이 풀 점유, bulkhead 없음 | R10, R11 |
| `@ExceptionHandler(ProductDetailException)`가 안 맞고 500이 나간다 | `CompletionException`을 unwrap하지 않음 | R8 |
| io 스레드 덤프가 `HikariPool.getConnection`에서 대기 중 | 커넥션 풀이 io 풀보다 작다 | R11 |

---

## 진행 방법

1. 위 개념과 [decision-guide.md](../docs/decision-guide.md)를 읽는다.
2. `Scenarios.java`(팀에서 나온 구현 4종과 시나리오)와 [PREDICT.md](PREDICT.md)의 **설계 판단 퀴즈 D01~D06**을 읽고 예측을 적는다. 그다음 `Predictions.java`로 옮긴다.
3. 예측을 채점한다. **첫 채점 결과는 고정된다.** (부하 시나리오 L01, L02 때문에 10초쯤 걸린다.)
   ```bash
   mvn -pl m05-design-a -am test -Dtest=PredictionTest -Dsurefire.failIfNoSpecifiedTests=false
   python3 tools/score.py
   ```
4. **코드를 쓰기 전에** [DECISION.md](DECISION.md)를 채운다.
5. 구현한다.

   | 실습 | 파일 | 내용 | 규칙 |
   |---|---|---|---|
   | E1 | `ProductDetailAggregator.load` | 병렬 fan-out + 폴백 + 전체 타임아웃 + 도메인 예외 | R3, R7, R8, R9, R10 |
   | E2 | `ProductDetailAggregator.loadWithSourceTimeouts` | 선택 소스별 타임아웃 (스레드를 막지 않고) | R9, R10 |
   | E3 | `ProductDetailPool.threadsFor` | Little의 법칙으로 풀 크기 → 100 RPS 부하 테스트 | R11 |

   ```bash
   mvn -pl m05-design-a -am test -Dtest='AggregatorTest,PoolSizingTest' -Dsurefire.failIfNoSpecifiedTests=false
   ```
6. 실측 결과를 보고 DECISION.md의 "예측과 달랐던 점"을 채운다. PREDICT.md의 서술 문제에 답한다.

**통과 기준**: 예측 적중률(첫 시도) 90% 이상, `AggregatorTest`와 `PoolSizingTest` 전부 통과, DECISION.md가 decision-guide의 "합격" 수준(숫자가 있는 근거, 풀 크기의 근거, 타임아웃 후 남은 작업 처리).

### 테스트가 확인하는 것 (목적 달성 여부)

- `load`가 **즉시** future를 반환한다(요청 스레드를 막지 않는다).
- 총 시간이 합이 아니라 최댓값 수준이고(R7), 네 호출이 **동시에** 진행된다(`maxTotalConcurrency == 4`).
- 모든 다운스트림 호출이 `io-` 스레드에서 실행된다(commonPool, 요청 스레드 금지).
- 선택 소스 실패 → 폴백, 필수 소스 실패 → `ProductDetailException`이고 cause는 **원래 예외 그 자체**.
- 가격 API가 1000ms면 ≈300ms에 TIMEOUT으로 실패하고, 늦은 호출도 결국 끝나 새는 작업이 없다.
- io 스레드를 50ms 이상 점유한 작업은 다운스트림 호출 4개뿐이다. **기다리기만 하는 작업이 풀에 없다.**
- E3 크기의 풀로 100 RPS 부하를 실패 없이, 최대 250ms 미만으로 처리한다.
