# 추론 규칙 카드 (R0 ~ R13)

이 문서는 이 repo의 중심이다. 도구 없이 파이프라인을 읽고 쓸 때, 아래 규칙만으로 각 단계에 대해 네 가지를 답할 수 있어야 한다.

1. **어느 스레드에서 실행되는가**
2. **언제 실행되는가**
3. **그 스레드를 막는가**
4. **실패하면 어디로 가는가**

모든 채점 테스트는 오답일 때 관련 규칙 번호를 알려준다. `journal/`에 "어느 규칙을 잘못 적용했는지" 기록하라.

> 이 규칙들은 JDK 25에서 실험으로 검증했다. JDK 버전에 따라 달라지는 부분은 **[버전]** 표시를 달았다. 자세한 내용은 [java-version-matrix.md](java-version-matrix.md)를 보라.

---

## R0. 목적 규칙: CF를 쓰기 전에 목적부터 정한다

| 목적 | 정의 | 성공 기준 | 필요 조건 |
|---|---|---|---|
| **A. 병렬 blocking fan-out** | 서로 독립적인 blocking 호출 N개를 동시에 실행해 응답시간을 줄인다 | 총 소요시간 ≈ max(각 호출) (합이 아님) | 목적에 맞는 크기의 **전용 blocking 풀**, 전체 타임아웃, 실패 정책 |
| **B. end-to-end non-blocking** | I/O를 기다리는 동안 **어떤 스레드도 점유하지 않는다** | 적은 스레드로 많은 동시 요청 처리. I/O 스레드 위 blocking 0건 | 드라이버/클라이언트가 **진짜 비동기**여야 함 (Netty 기반, JDK `HttpClient.sendAsync` 등) |
| **쓰지 않음** | 호출이 1개이거나, 순차 의존이거나, 결국 곧바로 `join()`할 거라면 | 동기 코드가 더 단순하고 같은 성능 | 해당 없음 |

- A는 스레드를 아끼지 않는다. **막히는 스레드가 바뀔 뿐이다.** A를 해 놓고 B라고 말하면 오답이다.
- B 파이프라인 중간에 blocking 호출이 하나라도 있으면, 그 지점에서 B는 깨진다(R4).

---

## R1. 완료 스레드 규칙 (가장 중요)

> **non-async 콜백**(`thenApply`, `thenAccept`, `thenRun`, `thenCompose`, `thenCombine`, `handle`, `whenComplete`, `exceptionally` 등)은
> - 콜백을 **등록하는 시점에 이미 완료**된 future라면 → **등록한 스레드**에서 등록 즉시 실행된다.
> - 아직 **미완료**라면 → 나중에 **future를 완료시키는 스레드**(`complete()`/`completeExceptionally()`를 호출한 스레드, 또는 앞 단계를 실행한 스레드)에서 실행된다.

그래서 같은 코드라도 **타이밍에 따라 실행 스레드가 바뀐다**(비결정성). 앞 단계의 완료 주체는 다음과 같다.

| 앞 단계 | 완료시키는 스레드 |
|---|---|
| `supplyAsync(f, ex)` | `ex`의 스레드 |
| 비동기 클라이언트가 준 future | 클라이언트의 I/O(이벤트 루프) 스레드 |
| `orTimeout`/`completeOnTimeout`으로 끝난 경우 | JDK 내부 타임아웃 스케줄러 스레드 **[버전]** |
| 직접 만든 `new CompletableFuture<>()` | `complete()`를 호출한 스레드 |
| `thenCombine`/`allOf` | **마지막으로 완료된 입력**을 완료시킨 스레드 |
| `anyOf` | **처음으로 완료된 입력**을 완료시킨 스레드 |

```java
CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> slow(), io);
f.thenApply(a);        // f가 아직 미완료 → a는 io 스레드에서
f.join();
f.thenApply(b);        // f가 이미 완료 → b는 지금 이 줄을 실행하는 스레드(caller)에서 즉시
```

**리뷰할 때 던질 질문**: "이 non-async 콜백이 등록될 때 앞 단계는 완료됐는가? 아니라면 누가 앞 단계를 완료시키는가?"

---

## R2. Async 규칙

| 형태 | 실행 스레드 |
|---|---|
| `xxxAsync(fn, executor)` | 항상 `executor` |
| `xxxAsync(fn)` | `defaultExecutor()` = 보통 `ForkJoinPool.commonPool()` |

- **[버전] JDK 8~24**: commonPool 병렬도가 1 이하이면(CPU 1~2개 컨테이너) commonPool 대신 **작업마다 새 스레드**(`Thread-N`)를 만든다. **JDK 25**부터는 항상 commonPool을 쓴다.
- commonPool은 JVM 전체가 공유한다(parallel stream, 다른 라이브러리). **blocking 작업을 넣으면 모두가 굶는다**(R11).
- `Async`는 앞 단계가 이미 완료됐어도 **항상 executor로 넘긴다**. 비결정성을 없애는 도구지만, 스레드 전환 비용이 든다.
- **단, 앞 단계가 실패했으면 executor로 넘기지 않는다.** `thenApplyAsync(fn, ex)`는 실패를 받으면 `fn`을 건너뛰고, 실패를 받은 스레드에서 바로 자기 결과를 실패로 완료시킨다. 그래서 그 뒤의 non-async `exceptionally`/`handle`은 `ex`가 아니라 **앞 단계를 실패시킨 스레드**(예: 이벤트 루프)에서 실행된다. 복구 로직이 무겁거나 blocking이면 `exceptionallyAsync`(12+)나 `handleAsync(.., ex)`를 쓴다.

---

## R3. 시작 규칙

- `supplyAsync`/`runAsync`는 executor에 **제출만 하고 즉시 반환**한다. 호출 스레드는 막히지 않는다.
- **예외 1**: executor가 호출 스레드에서 직접 실행하면(`Runnable::run`, 큐가 찬 `CallerRunsPolicy`) 그 작업은 **호출 스레드에서 동기 실행**된다. 이벤트 루프에서 호출하면 거기서 blocking이 일어난다.
- **예외 2**: executor가 거절하면(`AbortPolicy`) `supplyAsync`가 `RejectedExecutionException`을 **동기적으로 던진다**. future가 실패로 완료되는 게 아니다.
- 반대로, 이미 만들어진 future에 `thenXxx`를 붙이는 것은 작업을 시작하지 않는다. **작업은 future를 만든 순간 이미 시작됐다**(lazy 아님).

---

## R4. 람다 본문 규칙

> 조합 API는 모두 non-blocking이다. **단계가 blocking인지는 람다 본문이 결정한다.**

- `thenApply(x -> jdbc.query(x))`: `thenApply`는 non-blocking이지만, 이 단계는 R1이 정한 스레드를 쿼리 시간만큼 **막는다**.
- 본문 안의 blocking 예: JDBC, `RestTemplate`, 파일 I/O, `Thread.sleep`, `lock()`, `synchronized` 경합, **다른 future의 `get()`/`join()`**, `BlockingQueue.put/take`.
- 본문 안에서 future를 **만들기만** 하는 것(`client.fetchAsync(...)`, `supplyAsync(..., ex)`)은 non-blocking이다(R3의 예외 제외).

**리뷰 방법**: 각 람다 옆에 `// [실행 스레드] / [blocking 여부]` 주석을 단다.

---

## R5. 대기 규칙

- `get()`, `get(timeout)`, `join()`은 future가 완료될 때까지 **호출 스레드를 막는다**.
- **이미 완료된 future**라면 즉시 반환한다. 그래서 `allOf(fs).thenApply(v -> fs.stream().map(CompletableFuture::join)...)`의 `join`은 막히지 않는다.
- `getNow(default)`, `isDone()`, `resultNow()`(19+), `state()`(19+)는 막지 않는다.
- 판단 기준은 **"그 시점에 완료가 보장되는가"**다. `join`이 보인다고 무조건 오답은 아니다.

---

## R6. 분기 규칙

- 모든 `thenXxx`는 **새 future를 반환**한다. 원래 future는 변하지 않는다.
- 한 future에 콜백 여러 개를 따로 붙이면 **분기**다. 분기 사이 실행 순서는 **보장되지 않는다**(구현상 역순이 흔하지만 의존하면 안 된다).
- 반환된 future를 버리면(**fire-and-forget**) 그 분기의 **결과와 예외도 함께 버려진다**. 아무도 관찰하지 않은 예외는 로그조차 남지 않는다.

```java
CompletableFuture<Order> f = load();
f.thenApply(this::validate);         // ❌ 결과 버림: 검증 실패해도 아무도 모름
return f.thenApply(this::enrich);    // validate 와는 무관한 분기
```

---

## R7. 시간 규칙

- **순차**: `a.thenCompose(x -> b(x)).thenCompose(...)`의 총 시간은 각 단계 시간의 **합**이다.
- **병렬**: 독립 future들을 **먼저 모두 시작**하고(변수에 담기) `thenCombine`/`allOf`로 모으면 총 시간은 **최댓값**이다.
- **풀 상한**: 병렬 작업 N개를 스레드 T개짜리 풀에 넣으면 총 시간은 ≈ `ceil(N / T) × 지연시간`이다.
- 흔한 실수: 루프 안에서 `supplyAsync(...).join()`을 하면 **순차**다. 시작과 대기를 분리하라.

```java
// 병렬: 둘 다 먼저 시작
CompletableFuture<User> u = supplyAsync(() -> userDb.call(id), io);
CompletableFuture<Price> p = supplyAsync(() -> priceApi.call(id), io);
return u.thenCombine(p, View::new);            // ≈ max(user, price)

// 순차: 두 번째는 첫 번째가 끝나야 시작
return supplyAsync(() -> userDb.call(id), io)
        .thenCompose(user -> supplyAsync(() -> priceApi.call(id), io)
                .thenApply(price -> new View(user, price)));  // ≈ user + price
```

---

## R8. 예외 전파 규칙

- 예외가 나면 `thenApply`/`thenAccept`/`thenRun`/`thenCompose`/`thenCombine`의 함수는 **실행되지 않고 건너뛴다**. 예외는 처음 만나는 `exceptionally`/`handle`/`whenComplete`까지 전파된다.
- **감싸기**: 예외가 단계를 거쳐 전달되면 `CompletionException`으로 감싸진다.
  - `supplyAsync` 안에서 던진 예외 → 바로 붙인 `exceptionally`도 `CompletionException(원인)`을 받는다.
  - `completeExceptionally(ex)`로 직접 실패시킨 future → 바로 붙인 콜백은 `ex`를 **그대로** 받는다. 그 뒤 단계부터는 감싸진다.
  - `get()`은 `ExecutionException(원인)`, `join()`은 `CompletionException(원인)`을 던진다.
- 그러므로 **항상 unwrap한 뒤 판단**한다. `ex instanceof MyException`을 바로 쓰는 코드는 버그 후보다.

```java
static Throwable unwrap(Throwable t) {
    while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
        t = t.getCause();
    }
    return t;
}
```

---

## R9. 관찰과 복구 규칙

| API | 결과를 바꾸는가 | 용도 |
|---|---|---|
| `whenComplete((v, ex) -> ...)` | **아니오**. 원래 결과/예외를 그대로 통과시킨다 | 로그, 메트릭, 자원 정리 |
| `handle((v, ex) -> r)` | **예**. 성공이든 실패든 `r`로 바뀐다 | 성공·실패를 모두 하나의 값으로 변환 |
| `exceptionally(ex -> r)` | 실패일 때만 `r`로 복구한다. 성공은 그대로 | 폴백 |
| `exceptionallyCompose`(12+) | 실패일 때 다른 future로 복구한다 | 비동기 폴백 |

- `whenComplete`의 액션이 예외를 던지면: 원래 성공이었다면 결과가 그 예외로 실패하고, 원래 실패였다면 원래 예외가 유지된다.
- 복구 단계의 **위치**가 의미를 바꾼다. `a.exceptionally(..).thenApply(b)`와 `a.thenApply(b).exceptionally(..)`는 다른 파이프라인이다.

---

## R10. 타임아웃·취소 규칙

- `orTimeout`, `completeOnTimeout`, `cancel`, `complete`는 **future의 상태만 바꾼다**. 이미 실행 중인 실제 작업(blocking 호출)은 **계속 실행되고 스레드도 계속 점유한다**.
- `cancel(true)`는 interrupt하지 **않는다**(`mayInterruptIfRunning`은 무시된다). 작업을 정말 멈추려면 `Future`(executor.submit)의 `cancel(true)`나 직접 만든 취소 신호가 필요하다.
- 취소와 타임아웃은 **상류로 전파되지 않는다**. `f.thenApply(..).cancel(true)`는 `f`를 취소하지 않는다.
- 반대로 하류로는 전파된다. 취소된 future의 의존 단계는 `CompletionException(CancellationException)`으로 **실패**한다. 이때 그 의존 단계의 `isCancelled()`는 `false`다.
- `orTimeout`/`completeOnTimeout`은 **새 future가 아니라 원본(`this`)을 바꾼다.** 여러 곳이 공유하는 future에 걸면 모두에게 영향을 준다.
- 타임아웃을 직접 구현할 때(`ScheduledExecutorService.schedule`), 먼저 완료되면 타이머를 `cancel`한다. 이때 `ScheduledThreadPoolExecutor`는 `setRemoveOnCancelPolicy(true)`가 아니면 취소된 타이머도 큐에 남는다.
- 타임아웃으로 완료되면 그 뒤의 non-async 콜백은 **타임아웃 스케줄러 스레드**에서 실행된다(R1의 특수 사례). 이 스레드는 JVM에 하나뿐이므로 여기서 blocking하면 **모든 타임아웃이 밀린다** **[버전: 스레드 이름이 다름]**.
- **[버전]** `orTimeout`/`completeOnTimeout`은 Java 9+다. Java 8에서는 `ScheduledExecutorService`로 직접 구현한다(m03, m09).

---

## R11. 풀 규칙

- **I/O blocking 작업은 전용 풀**에 넣는다. commonPool은 CPU 작업용이다.
- **크기는 동시성 목표로 정한다**(Little의 법칙): 필요 스레드 수 ≈ 초당 요청 수 × 호출당 blocking 시간(초) × 요청당 호출 수. 이 값을 다운스트림(DB 커넥션 수 등)이 버틸 수 있는지 함께 확인한다.
- **같은 bounded 풀 안에서 기다리지 않는다**: 풀 안의 작업이 같은 풀에 제출한 작업을 `join()`하면, 모든 스레드가 기다리는 순간 **영원히 멈춘다**(thread starvation deadlock).
  - 이것은 `ThreadPoolExecutor`(고정 크기 풀) 이야기다. `ForkJoinPool`(commonPool 포함)은 워커가 `join`으로 기다릴 때 대기 중인 작업을 **직접 실행하거나**(JDK 25) **보충 스레드를 추가해서**(JDK 21) 같은 패턴에서도 멈추지 않는다(m04에서 실험). 그렇다고 안전한 것은 아니다. 스레드가 예상보다 늘어나거나, 순서가 예측과 달라진다.
- 큐 정책을 의식한다. 무제한 큐는 장애를 지연시간 폭증으로 숨긴다. `CallerRunsPolicy`는 R3의 예외 1을 일으킨다.
- `ThreadPoolExecutor`는 스레드 수가 코어 수에 도달하면 **놀고 있는 스레드가 있어도 작업을 큐를 거쳐 전달한다.** 그래서 큐가 제출 버스트보다 작으면 스레드가 남는데도 **거절**이 난다(실측: 스레드가 이미 코어 수만큼 있는 풀에 큐 크기의 두 배쯤 되는 작업을 연속 제출하면, 스레드 대부분이 놀고 있는데도 일부가 거절됐다). 대기 시간을 줄이려고 큐를 작게 잡지 말고, 대기 시간의 상한은 타임아웃으로 정한다.

---

## R12. 컨텍스트 규칙

- `ThreadLocal` 기반 컨텍스트(MDC, `SecurityContextHolder`, `@Transactional`의 트랜잭션, `RequestContextHolder`)는 **스레드를 넘어가지 않는다**.
- R1이나 R2 때문에 실행 스레드가 바뀔 수 있는 단계에서는 **컨텍스트가 없다고 가정**한다.
- executor를 감싸서(`TaskDecorator`, Micrometer Context Propagation) 전파할 때는 **작업 후 원래 값으로 복원**해야 한다. 그러지 않으면 풀 스레드에 이전 요청의 인증 정보가 남는다.
- **캡처 시점이 핵심이다.** `TaskDecorator`, `ContextExecutorService.wrap`, `DelegatingSecurityContextExecutor`는 모두 **`execute()`를 호출한 스레드의 컨텍스트**를 캡처한다. 그런데 `thenApplyAsync(fn, 감싼 executor)`에서 `execute()`를 호출하는 것은 **앞 단계를 완료시킨 스레드**다(R1). 앞 단계를 이벤트 루프나 타임아웃 스케줄러가 완료시키면 **빈 컨텍스트가 캡처되어 전파된다.**
  - 감싼 executor는 `supplyAsync(.., ex)`처럼 **요청 스레드가 직접 제출하는 경우에만** 안전하다.
  - 남의 스레드가 완료시키는 future 뒤에서 컨텍스트가 필요하면 **파이프라인을 조립하는 시점(요청 스레드)에 캡처**한다. 예: `Executor ex = ContextSnapshot.captureAll().wrapExecutor(pool)`(Micrometer)을 요청마다 만들어 `thenApplyAsync(fn, ex)`에 넘긴다. 또는 조립 시점에 `MDC.getCopyOfContextMap()`을 캡처해 람다 안에서 복원한다(m07에서 실험).

---

## R13. 평탄화 규칙

- 함수가 **값**을 반환하면 `thenApply`, **future**를 반환하면 `thenCompose`를 쓴다.
- `thenApply`가 future를 반환하면 `CompletableFuture<CompletableFuture<T>>`가 된다. 이것을 `.join()`으로 꺼내는 코드는 R4/R5 위반 후보다.
- 독립된 두 future를 합칠 때는 `thenCombine`, N개는 `allOf` + `join`(R5)을 쓴다. 둘 다 **fail-fast가 아니다**: 하나가 일찍 실패해도 나머지가 **모두 끝날 때까지** 결과가 나오지 않는다. 빨리 실패해야 한다면 각 입력에 실패 시 결과를 직접 완료시키는 콜백을 붙인다(m02, m03). 하나라도 끝나면 되는 경우는 `anyOf`를 쓴다(나머지 작업은 계속 실행된다, R10).

---

## 파이프라인 손으로 추적하기 (최종 훈련 형식)

모든 파이프라인을 이 표로 바꿀 수 있으면 졸업이다.

| 단계 | 실행 스레드 (R1/R2/R3) | blocking? (R4/R5) | 시간 (R7) | 실패 시 (R8/R9/R10) | 컨텍스트 (R12) |
|---|---|---|---|---|---|
| `supplyAsync(order, io)` | io | 예 (io 스레드를 점유) | 100ms | 건너뛰고 → exceptionally | 없음 |
| `.thenApply(toView)` | io (미완료 상태에서 등록) | 아니오 | ~0 | 건너뜀 | 없음 |
| `.orTimeout(1s)` | 타임아웃 스케줄러(시간 초과 시) | 아니오 | 상한 1s | TimeoutException | 없음 |
| `.exceptionally(fallback)` | 실패를 만든 스레드 | 아니오 | ~0 | 복구 | 없음 |
