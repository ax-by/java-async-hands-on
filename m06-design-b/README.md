# m06. 설계 B: end-to-end non-blocking

> 난이도 ★★★★ · 관련 규칙 **R0, R1, R3, R4, R10, R11** (+ R2, R8, R12 일부) · 예측 26개 · 구현 6개 · 판단 양식 1개(DECISION.md)

## 이 모듈을 마치면

- "이 클라이언트로 B가 되는가?"를 **I/O 동안 누가 기다리는가**로 판정하고, A와의 차이를 스레드 수와 동시 요청 수로 말할 수 있다.
- JDK `HttpClient.sendAsync`와 Netty 계열 클라이언트에서 **콜백이 어느 스레드에서 실행되는지** 근거와 함께 말할 수 있다.
- 이벤트 루프를 막는 코드를 **람다 본문만 보고** 찾아내고, 증상(관계없는 요청의 지연)을 예측할 수 있다.
- B 파이프라인 안의 JDBC 한 구간을 올바르게 격리하고, 그 설계를 "B, 단 X 구간은 A식 격리"라고 설명할 수 있다.
- B에서 타임아웃이 **원격 호출을 실제로 끊는지**, 동시성 상한을 **막지 않고** 거는 법을 안다.

---

## 핵심 개념

### 1. B는 "누가 기다리는가"의 문제다

R0의 두 목적을 I/O 대기 중의 모습으로 다시 보자.

```
A: 병렬 blocking fan-out (스레드 20개, 원격 호출 200개 × 100ms)
   io-1  ██████ 대기 ██████ 대기 ██████ ...      ← 스레드가 응답을 기다리며 서 있다
   io-2  ██████ 대기 ██████ 대기 ██████ ...
   ...   동시에 진행되는 호출 = 스레드 수(20) → 총 ≈ ceil(200/20) × 100ms = 1000ms

B: end-to-end non-blocking (HttpClient 내부 스레드 2개 + 셀렉터 1개)
   셀렉터: "소켓 37번 읽을 거리 있음" → 워커가 잠깐 처리하고 손을 뗀다
   동시에 진행되는 호출 = 200 (기다리는 동안 아무 스레드도 점유하지 않는다) → 총 ≈ 100ms
```

S14에서 이것을 직접 잰다. 숫자는 이 그림과 다르니 규칙으로 계산해서 예측한다.

**B가 가능한 조건은 하나다: 클라이언트가 진짜 비동기여야 한다.** 판별법:

| 질문 | 진짜 비동기 (B 가능) | 가짜 비동기 (결국 A) |
|---|---|---|
| future/콜백을 반환하는가 | 예 | 예 (여기까지는 구분이 안 된다) |
| 응답을 기다리는 동안 스레드를 점유하는가 | 아니오. 소수의 I/O 스레드가 소켓 이벤트만 처리한다 | 예. 내부에서 `supplyAsync(() -> blockingCall())` 을 한다 |
| 동시 요청 1000개일 때 스레드 수 | 거의 그대로 | 1000개 근처까지 늘거나 큐에 쌓인다 |
| 예 | JDK `HttpClient.sendAsync`, Lettuce, Reactor Netty(WebClient), AWS SDK v2 async, R2DBC | `@Async` + RestTemplate, `supplyAsync(() -> jdbc...)`, JDBC 드라이버를 감싼 "async" 래퍼 |

확인하는 가장 확실한 방법은 **요청이 몰린 상태에서 스레드 덤프**를 떠 보는 것이다. 소켓 read에서 멈춘 스레드가 요청 수만큼 있으면 B가 아니다.

### 2. JDK HttpClient의 스레드 모델 (JDK 25.0.3, 21.0.11에서 실험으로 확인)

| 구성 요소 | 스레드 이름 (관찰값) | 하는 일 |
|---|---|---|
| 셀렉터 | `HttpClient-N-SelectorManager` (클라이언트당 1개, **실행기와 별개**) | 소켓 이벤트를 감시하고 일을 실행기로 넘긴다 |
| 실행기 (기본) | `HttpClient-N-Worker-M` (클라이언트마다 새 캐시 풀) | 연결, 요청 쓰기, 응답 읽기, **BodySubscriber** |
| 실행기 (`Builder.executor(ex)` 지정) | `ex`의 스레드 | 위와 같음 |
| 이미 완료된 future에 등록한 콜백 | 등록한 스레드 (R1) | |

**`sendAsync`가 준 future는 누가 완료시키는가?** 실행기일까, 셀렉터일까, 그 밖의 어딘가일까? S01, S02에서 먼저 예측한다. 아래 정리는 채점 뒤에 본다.

(여기에 작성)

그 밖에 알아 둘 사실:

- **4xx/5xx 응답에도 future는 정상 완료된다.** 상태 코드는 직접 확인한다(E2). WebClient `retrieve()`처럼 예외로 바꿔 주지 않는다.
- `HttpClient.close()`/`shutdownNow()`는 **JDK 21+** API다. `--release 17` 코드에서는 호출할 수 없으므로 실행기를 직접 지정하고 그 수명을 관리한다.
- `HttpClient`는 Java 11+다. Java 8에는 없다(Apache HttpAsyncClient, AsyncHttpClient 같은 라이브러리를 쓴다).
- **BlockHound와 HttpClient 내부**: 실행기 스레드 이름을 `nonblocking-http`로 지어 BlockHound 감시 대상으로 만들어 보면, 내 코드가 아무것도 막지 않아도 `HttpClientImpl$SelectorManager.register/lock`, `ConnectionPool.getConnection/returnToPool`의 `ReentrantLock` 경합(`Unsafe#park`, `Thread.onSpinWait`)이 위반으로 기록된다(동시 요청 100개에서 매번 관찰). 라이브러리 내부의 아주 짧은 임계 구역이다. **도구의 오탐이지 내 버그가 아니다.** 운영용 BlockHound 연동은 이런 내부 호출을 허용 목록으로 관리한다. 그래서 이 모듈의 채점은 HttpClient 내부 풀이 아니라 **내 콜백이 실행되는 스레드**를 감시한다(아래 IoThreadHttpClient).

### 3. Netty 계열 클라이언트와 이벤트 루프 규율 (R1, R4)

Lettuce, Reactor Netty, DataStax 드라이버처럼 Netty 위에 만든 클라이언트는 응답을 읽은 **이벤트 루프 스레드가 future를 직접 완료**시키는 것이 기본 동작이다(harness의 `FakeEventLoopClient`가 이 대역이다). R1에 따라 미완료 상태에서 붙인 non-async 콜백은 **이벤트 루프에서 실행**된다.

이벤트 루프 하나는 연결 수백 개를 맡는다. 그래서 콜백 하나가 300ms 동안 이벤트 루프를 잡으면 **그 루프가 맡은 관계없는 응답이 전부 그만큼 밀린다**. 운영에서는 "Redis가 p99에서만 느려진다"로 보인다.

이 모듈의 `IoThreadHttpClient`(support 패키지)는 JDK HttpClient를 감싸서 **future를 단일 I/O 스레드 `nonblocking-io-1`에서 완료**시킨다. 즉 HttpClient를 "Netty 계열처럼 더 엄격하게" 만든 채점용 클라이언트다. E1, E2, E5, E6이 이것을 쓴다.

**이벤트 루프에서 하면 안 되는 일**: blocking 호출(JDBC, 파일, 잠금 대기, 미완료 future의 join/get)과 **오래 걸리는 CPU 작업**(대량 JSON 직렬화, 템플릿 렌더링, 암호화). 둘 다 루프를 점유한다는 점에서 같다.

| 상황 | 처방 | 비고 |
|---|---|---|
| CPU 무거운 변환 | `thenApplyAsync(fn, cpuPool)` | E3 |
| blocking 호출 하나 (JDBC 등) | `thenCompose(x -> supplyAsync(() -> jdbc(x), jdbcPool))` 또는 `thenApplyAsync(.., jdbcPool)` | **"B, 단 이 구간은 A식 격리"** (E4, [판단 가이드](../docs/decision-guide.md) Q5) |
| 모든 콜백을 이벤트 루프 밖에서 | 클라이언트의 완료 실행기 옵션 (AWS SDK v2 `FUTURE_COMPLETION_EXECUTOR`, `FakeEventLoopClient` 생성자의 `completionExecutor`) | 응답마다 스레드 전환 비용. 그 실행기의 크기와 큐도 설계해야 한다 |
| 가벼운 변환 (필드 꺼내기, 문자열 조립) | 그대로 non-async | 불필요한 Async 남발은 비용이다 ([체크리스트](../docs/review-checklist.md) #8) |

함정 두 가지:

- **이미 완료된 future에 붙인 콜백도 이벤트 루프에서 실행될 수 있다.** 이벤트 루프 위에서 실행 중인 콜백 안에서 캐시된(이미 완료된) future에 `thenApply`를 붙이면, R1에 따라 **등록한 스레드 = 이벤트 루프**에서 즉시 실행된다. "그 future는 worker가 완료시켰으니 worker에서 돌겠지"는 틀린 추론이다.
- **실패 경로는 Async 홉을 건너뛴다.** `x.thenApplyAsync(fn, ex).exceptionally(h)`에서 `x`가 실패하면 `fn`은 실행되지 않고(R8), 실패는 `ex`로 넘어가지 않고 **앞 단계를 완료시킨 스레드에서 그 자리로** 전달된다([R2](../docs/reasoning-rules.md)). 그래서 `h`도 그 스레드에서 실행된다. `xxxAsync(fn, ex)`가 고정하는 것은 **fn이 실행될 스레드**뿐이다. 실패 처리도 이벤트 루프 밖에서 해야 하면 `handleAsync`/`whenCompleteAsync`/`exceptionallyAsync`(12+)에 실행기를 준다(이들은 실패여도 실행기로 넘어간다). `IoThreadHttpClient`가 `thenApplyAsync`가 아니라 `whenCompleteAsync`로 완료를 넘기는 이유도 이것이다.

### 4. "비동기처럼 보이는" 코드 속의 blocking (R4: 모든 람다 본문을 본다)

조합 API는 전부 non-blocking이다. **blocking 여부는 람다 본문과, 메서드가 future를 반환하기 전에 하는 일이 결정한다.**

```java
CompletableFuture<Profile> loadProfileAsync(String id) {
    return CompletableFuture.completedFuture(profileDb.call(id));   // 반환 전에 이미 blocking 호출이 끝나 있다
}
```

이름도 타입도 비동기지만 호출한 스레드에서 `profileDb.call`이 끝나야 반환된다. 이벤트 루프 위의 `thenCompose` 안에서 부르면 이벤트 루프가 막힌다.

**탐지 도구 두 가지를 함께 쓴다**: BlockHound(무엇이 막았는지 알려 준다)와 **이벤트 루프 지연 지표**(`maxLagMillis`, 운영에서는 루프 지연/명령 지연 p99). BlockHound가 못 보는 것이 꽤 있다. BlockHound는 **JDK의 blocking 메서드 호출**(`Thread.sleep`, `Unsafe#park`, 소켓/파일 네이티브 읽기·쓰기 등)을 바이트코드 계측으로 가로채서 감지한다. 그 목록에 있는 메서드를 거치지 않고 스레드를 붙잡는 것은 보지 못한다. 아래는 BlockHound 1.0.17 + JDK 25에서 `event-loop-` 스레드 위에서 직접 확인한 결과다(S08, S10 시나리오의 결과는 채점 뒤에 정리한다).

| 람다 본문 | 이벤트 루프 점유 | BlockHound |
|---|---|---|
| JDBC, `Thread.sleep` (FakeBlockingService) | 예 | 감지 |
| 미완료 future의 `get`/`join` | 예 | 감지 (`Unsafe#park`) |
| 무거운 CPU 계산 (E3) | 예 | **감지 못함** |
| `System.out`, `FileOutputStream` 쓰기 (동기 로그 appender) | 예 | 감지 (`FileOutputStream#writeBytes`) |
| `Files.writeString` (NIO `FileChannel`) | 예 | **감지 못함** (이 버전 설정에서) |
| `InetAddress.getByName` (DNS 조회) | 예 (캐시 미스 시) | **감지 못함** |
| 경합 없는 `lock()` | 사실상 아니오 | 감지 안 함 (정상) |

(여기에 작성)

리뷰할 때는 m01처럼 각 람다 옆에 `// [실행 스레드] / [blocking?]`을 적는다. "BlockHound가 조용하다"는 "막지 않는다"의 증명이 아니다.

**같은 루프에서 자기 응답을 기다리면 영원히 멈춘다.** 이벤트 루프 위의 콜백이 같은 클라이언트의 다른 요청을 `join()`하면, 그 응답을 전달할 스레드가 바로 지금 기다리고 있는 자신이다. 응답은 영원히 오지 않는다. Netty 클라이언트는 한 연결을 한 이벤트 루프에 고정하므로 운영에서도 똑같이 일어난다. E2에서 콜백 안에서 `join`을 쓰면 테스트가 이 이유로 멈춘다.

### 5. B에서의 타임아웃과 취소 (R10)

`orTimeout`/`completeOnTimeout`은 B에서도 **future의 상태만 바꾼다**. B에서는 점유된 스레드는 없지만 **연결, 소켓, 원격 서버의 작업, 응답 처리 비용**이 그대로 남는다. 장애 날 이런 호출이 쌓이면 연결 고갈로 이어진다.

JDK HttpClient는 R10의 예외를 하나 갖고 있다. **`sendAsync`가 준 future는 취소 가능하다**(javadoc의 "cancelable" 절. **[버전]** JDK 16에서 도입된 동작이므로 Java 11에서는 기대하지 않는다). 어떤 시도가 실제로 교환을 끊고 어떤 시도가 future의 상태만 바꾸는지는 S12, S13에서 먼저 예측한다. 아래 관찰 결과는 채점 뒤에 본다.

(여기에 작성)

- 어떤 방법으로 중단하든 **요청은 이미 서버에 도착했을 수 있다**(S12는 `StubHttpServer.requestCount()`로 도착을 확인한 뒤에 중단을 시도한다). 취소는 보낸 요청을 되돌리지 못한다. 원격의 부작용(결제, 주문)은 일어났을 수 있으므로 재시도에는 멱등성이 필요하다.
- `HttpRequest.timeout`은 **응답 헤더를 받을 때까지만** 덮는다(JDK 소스 `MultiExchange`: 헤더를 받으면 타이머를 취소한다). 헤더를 바로 보내고 본문을 500ms 뒤에 보내는 서버에 100ms 타임아웃을 걸었더니 543ms에 **정상 완료**됐다. 본문까지 포함한 전체 상한이 필요하면 아래 2번 패턴을 쓴다.

올바른 패턴 (E5):

```java
// 1) 요청 타임아웃: 헤더까지의 상한 + 교환 중단을 HttpClient가 해 준다
client.sendAsync(HttpRequest.newBuilder(uri).timeout(d).build(), ofString())
      .thenApply(..).exceptionally(ex -> fallback);

// 2) 전체 상한: 파생 future 에 orTimeout, 원본 f 를 cancel(true)
CompletableFuture<HttpResponse<String>> f = client.sendAsync(req, ofString());
return f.thenApply(..)
        .orTimeout(d.toMillis(), MILLISECONDS)             // 파생 future 의 상태만 바꾼다
        .exceptionally(ex -> { f.cancel(true); return fallback; });   // 원본은 아직 미완료 → 교환 중단
```

이 콜백들이 어디서 실행되는지도 따져 본다. `orTimeout`이 발화하면 `exceptionally`는 **타임아웃 스케줄러 스레드**(JVM에 1개)에서 실행된다(R10). 여기서 무거운 폴백을 계산하면 모든 타임아웃이 밀린다.

### 6. B의 backpressure: 동시성 상한은 직접 건다

A에서는 **풀 크기가 곧 동시 호출의 상한**이었다. B에는 그런 상한이 없다. 요청 1만 개가 몰리면 원격 서버에 1만 개가 동시에 꽂히고, HTTP/1.1이라면 연결도 1만 개 열린다. B는 스레드를 아끼는 대신 **다운스트림 보호 책임을 설계자에게 넘긴다.**

`Semaphore.acquire()`로 막으면 안 된다. 그 스레드가 이벤트 루프라면 B 전체가 멈춘다. 대신 **막지 않는 제한기**를 쓴다(E6).

```
submit(call) → 결과 future 를 즉시 반환
            → 대기 큐에 "시작하는 일"을 넣는다
            → 허가가 남아 있으면 큐에서 꺼내 call.get() 으로 시작 (허가를 받은 뒤에야 시작: R3)
call 의 future 완료(whenComplete) → 허가 반납 → 큐에 다음 일이 있으면 시작
```

- 허가 반납은 성공/실패/Supplier 예외 **모든 경로**에서 해야 한다(`whenComplete`, R9).
- 즉시 완료되는 call이 수만 개 쌓여 있으면 "완료 → 반납 → 다음 시작 → 즉시 완료 → ..."가 **재귀로 쌓여 StackOverflowError**가 난다. 해법은 drain 루프를 한 번에 한 곳에서만 돌리는 **WIP(work-in-progress) 카운터** 패턴이다. Reactor 같은 OSS의 연산자 구현에서 자주 보게 될 모양이다(m10).
- 운영용이라면 대기 큐에도 상한을 두고 넘치면 즉시 거절한다(R11의 큐 정책과 같은 이야기). 실무 도구로는 Reactor의 `flatMap(fn, concurrency)`, Resilience4j `Bulkhead` 등이 있다.

### 7. 컨텍스트 (R12, 가볍게)

B 파이프라인은 HttpClient의 완료 스레드, 이벤트 루프, jdbc 풀을 오간다. 어느 단계에서도 요청 스레드의 MDC/SecurityContext는 **없다고 가정**한다. 이벤트 루프와 HttpClient의 완료 스레드는 내가 감쌀 수 없는 스레드다.

**함정: 공용 "컨텍스트 전파 실행기"를 `thenApplyAsync(fn, ex)`에 넘겨도 B에서는 소용없다.** `TaskDecorator`류는 `execute()`를 **호출한 스레드**의 컨텍스트를 캡처하는데, `thenApplyAsync`에서 `execute()`를 호출하는 것은 앞 단계를 완료시킨 스레드(이벤트 루프, HttpClient의 완료 스레드, 타임아웃 스케줄러)다(R1). 빈 컨텍스트가 캡처되어 전파된다. 해법은 **파이프라인을 조립하는 시점(요청 스레드)에 캡처**하는 것이다([R12](../docs/reasoning-rules.md)).

```java
Map<String, String> ctx = MDC.getCopyOfContextMap();          // 요청 스레드에서, 조립할 때 캡처
return client.sendAsync(req, ofString())
        .thenApply(res -> {
            Map<String, String> prev = MDC.getCopyOfContextMap();
            if (ctx != null) MDC.setContextMap(ctx); else MDC.clear();
            try {
                log.info("price={}", res.body());               // traceId 가 찍힌다
                return res.body();
            } finally {                                          // 반드시 복원 (R12)
                if (prev != null) MDC.setContextMap(prev); else MDC.clear();
            }
        });
```

또는 요청마다 조립 시점에 `ContextSnapshot.captureAll().wrapExecutor(pool)`(Micrometer)로 실행기를 만들어 넘긴다. 가장 단순한 방법은 traceId 같은 값을 람다가 캡처하는 **명시적 인자**로 다루는 것이다. 자세한 실험은 m07에서 한다.

---

## 실습 구성

| 파일 | 내용 | 규칙 |
|---|---|---|
| `Scenarios.java` + `Predictions.java` | 예측 26개 (S01~S14) | R0, R1, R2, R4, R5, R8, R10, R11 |
| `Ex1FanOut` | sendAsync로 N개 fan-out, 순서 유지 (allOf + join) | R0, R3, R5, R7, R13 |
| `Ex2ProductPage` | 3개 HTTP API 집계 + 상태 코드 처리, I/O 스레드 위 blocking 0 | R1, R4, R7, R8 |
| `Ex3EventLoopFix` | 이벤트 루프를 막는 운영 코드 고치기 (CPU + blocking) | R1, R2, R4, R11 |
| `Ex4IsolatedJdbc` | **설계 실습.** B 안의 JDBC 한 구간 격리. DECISION.md 먼저 | R0, R3, R4, R7, R11 |
| `Ex5TimeoutFallback` | 타임아웃 + 폴백, 포기한 교환은 실제로 중단 | R5, R9, R10 |
| `Ex6AsyncLimiter` | non-blocking 동시성 제한기 | R3, R4, R9 |

채점 테스트가 확인하는 것: 결과값뿐 아니라 BlockHound 위반(`nonblocking-io`/`event-loop` 스레드), 이벤트 루프 지연(`maxLagMillis`), blocking `send()` 호출 횟수, 원격 서버가 본 동시 요청 수, 소요시간, 실행 풀(스레드 이름), 타임아웃 후 교환 중단 여부(BodyHandler 호출 횟수).

## 진행 방법

1. 위 개념을 읽는다. [추론 규칙](../docs/reasoning-rules.md)의 R0, R1, R4, R10과 [판단 가이드](../docs/decision-guide.md)를 함께 본다.
2. `Scenarios.java`를 읽고 [PREDICT.md](PREDICT.md)에 예측과 근거 규칙을 적은 뒤 `Predictions.java`로 옮긴다. **첫 채점 결과는 고정된다.**
   ```bash
   mvn -pl m06-design-b -am test -Dtest=PredictionTest -Dsurefire.failIfNoSpecifiedTests=false
   python3 tools/score.py
   ```
3. 틀린 항목은 [journal](../journal/README.md)에 기록한다. PREDICT.md의 서술 문제에 답한다.
4. E1 → E2 → E3를 구현한다. E4 전에 [DECISION.md](DECISION.md)를 채운다. 그다음 E5, E6.
   ```bash
   mvn -pl m06-design-b -am test -Dtest='Ex*Test' -Dsurefire.failIfNoSpecifiedTests=false
   # 하나만: -Dtest=Ex3EventLoopFixTest
   ```
5. 전체: `mvn -q -pl m06-design-b test` (루트에서, 약 20초)

**통과 기준**: 예측 적중률(첫 시도) 90% 이상, `Ex1`~`Ex6` 테스트 전부 통과, DECISION.md가 [판단 가이드](../docs/decision-guide.md) 3단계의 "합격" 수준.

## 더 깊이: JDK 소스로 확인하기 (선택, 권장)

`$JAVA_HOME/lib/src.zip`의 `java.net.http` 모듈에서:

1. `jdk.internal.net.http.HttpClientImpl#sendAsync`: 결과 future를 반환하기 직전에 무엇을 하는가? 그 future를 완료시키는 스레드는 어디인가? 바로 위 주석은 `send()`(동기)가 왜 호출 스레드에서 인라인으로 진행되는지도 설명한다.
2. `jdk.internal.net.http.common.MinimalFuture#cancel`, `#newIncompleteFuture`: 파생 future도 취소 능력을 갖는가?
3. `jdk.internal.net.http.MultiExchange#cancel(boolean)`: `mayInterruptIfRunning` 값에 따라 무엇이 달라지는가? `cancelTimer()`가 호출되는 위치를 보면 요청 타임아웃의 범위(헤더까지)가 보인다.
4. `java.util.concurrent.CompletableFuture.UniApply#tryFire`: 앞 단계 결과가 예외일 때 `claim()`(실행기로 넘기기)과 실패 전달 중 무엇을 먼저 하는가?
