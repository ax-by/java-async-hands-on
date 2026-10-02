# m03. 실패: 예외는 어디로 가고, 타임아웃과 취소는 무엇을 멈추는가

> 난이도 ★★ · 관련 규칙 **R8, R9, R10** (복습: R1, R5, R6) · 예측 24개 · 구현 7개

## 이 모듈을 마치면

- 실패가 파이프라인의 **어느 단계를 건너뛰고 어디에서 잡히는지** 코드만 보고 말한다.
- 각 핸들러가 받는 예외가 **원본인지 `CompletionException`으로 감싸진 것인지** 근거와 함께 말한다. 그래서 unwrap 없이 `instanceof`를 쓰는 코드를 보면 바로 버그라고 지적한다.
- `whenComplete`/`handle`/`exceptionally`를 목적에 맞게 고르고, 복구 단계를 **올바른 위치**에 둔다.
- 타임아웃과 취소가 **future의 상태만 바꾸고 실제 작업은 멈추지 않는다**는 것을 증명하고, 실제로 멈추는 코드를 쓴다.

---

## 핵심 개념

### 1. 왜: 실패 경로는 테스트가 거의 지나가지 않는 경로다

정상 경로는 매일 실행되지만 실패 경로는 장애가 났을 때만 실행된다. 그런데 CF의 실패 경로에는 직관과 다른 규칙이 세 가지 있다. **예외가 감싸진다**(R8), **관찰과 복구가 다르다**(R9), **타임아웃과 취소는 일을 멈추지 않는다**(R10). 셋 다 컴파일러가 잡아 주지 않고, 셋 다 장애 상황에서 "폴백이 안 됐다", "스레드 풀이 말랐다"로 드러난다.

### 2. 예외 전파: 건너뛰고, 처음 만나는 핸들러로 (R8)

```java
supplyAsync(() -> couponApi.call("u1"), io)    // CouponExpiredException
    .thenApply(c -> apply(c))                  // 건너뜀
    .thenAccept(c -> save(c))                  // 건너뜀
    .exceptionally(ex -> { fallback(); return null; })  // 여기서 잡힌다 → 이후는 정상 경로
    .thenRun(() -> publish());                 // 실행된다
```

값을 받는 단계(`thenApply`/`thenAccept`/`thenRun`/`thenCompose`/`thenCombine` ...)는 실패를 만나면 **함수를 실행하지 않고** 실패를 그대로 다음으로 넘긴다. 실패를 받는 단계(`exceptionally`/`handle`/`whenComplete`)만 실행된다. 복구한 뒤에는 다시 정상 경로다.

### 3. 감싸기 규칙: 무엇을 받는가 (R8, JDK 25에서 검증)

CF는 내부에 실패를 저장할 때 **"단계를 거쳐 전달된 실패"는 `CompletionException`으로 감싼다.** 직접 넣은 실패는 감싸지 않는다.

| 실패가 생긴 방식 | 바로 붙인 핸들러가 받는 것 | 한 단계 더 거친 뒤 |
|---|---|---|
| `supplyAsync`/`thenApply` 등 **람다 안에서 throw** | `CompletionException(원인)` | `CompletionException(원인)` |
| `completeExceptionally(ex)`, `failedFuture(ex)` | `ex` **그대로** | `CompletionException(ex)` |
| `orTimeout` 시간 초과 | `TimeoutException` **그대로** | `CompletionException(TimeoutException)` |
| `cancel()` | `CancellationException` **그대로** | `CompletionException(CancellationException)` |
| `thenCompose`의 안쪽 future가 원본 예외로 실패 | `CompletionException(원인)` | 같음 |
| 람다 안에서 `throw new CompletionException(ex)` (ex가 이미 껍질) | **두 겹** `CompletionException(CompletionException(원인))` | 같음 |

`orTimeout`과 `cancel`이 원본을 넘기는 이유는 내부적으로 `completeExceptionally`를 호출하기 때문이다. **결론: 핸들러 안에서는 어떤 모양이 올지 모른다고 가정하고 항상 unwrap한다.**

기다리는 쪽에서는:

| 호출 | 던지는 것 |
|---|---|
| `get()` | `ExecutionException`. `getCause()`는 **원인** (`CompletionException` 껍질을 벗겨서 넣어 준다) |
| `join()` | `CompletionException`. `getCause()`가 원인 |
| 취소된 future의 `get()`/`join()` | `CancellationException` (감싸지 않는다) **[버전]** JDK 25는 **새** `CancellationException`을 만들고 원래 취소 예외를 cause로 넣는다(스택 추적 보존). JDK 21은 저장된 객체를 그대로 던진다. 타입은 같다 |

```java
// ❌ supplyAsync 의 실패는 CompletionException 으로 온다 → instanceof 가 항상 false
.exceptionally(ex -> { if (ex instanceof OutOfStockException) return "SOLD_OUT"; throw new CompletionException(ex); })

// ✅ unwrap 후 판별, 다시 던질 때는 껍질을 한 겹만
.exceptionally(ex -> {
    if (unwrap(ex) instanceof OutOfStockException) return "SOLD_OUT";
    throw ex instanceof CompletionException ce ? ce : new CompletionException(ex);
})
```

### 4. 관찰과 복구 (R9)

| API | 결과를 바꾸는가 | 쓰는 곳 |
|---|---|---|
| `whenComplete((v, ex) -> ...)` | **아니오** | 로그, 메트릭, 자원 정리 |
| `handle((v, ex) -> r)` | 예. 성공·실패 모두 `r` | 결과를 `Result`/`Optional` 같은 값으로 바꿀 때 |
| `exceptionally(ex -> r)` | 실패만 `r`로 | 값 폴백 |
| `exceptionallyCompose(ex -> future)` **[Java 12+]** | 실패만 다른 future로 | 비동기 폴백 (replica 재조회 등) |

Java 8~11에서 `exceptionallyCompose`가 필요하면: `f.handle((v, ex) -> ex == null ? completedFuture(v) : fallbackAsync()).thenCompose(Function.identity())`.

**`whenComplete`의 액션이 예외를 던지면** (JDK 21, 25에서 확인):
- 원래 **성공**이었다면 → 결과가 **액션의 예외로 실패**한다. 로그 코드의 NPE가 결제 성공을 실패로 바꾼다.
- 원래 **실패**였다면 → 원래 실패가 유지되고, 액션의 예외는 저장된 예외 객체에 **suppressed**로 붙는다. 저장된 예외가 `CompletionException` 껍질이면 껍질에 붙는다. 찾으려면 cause 체인 전체의 `getSuppressed()`를 봐야 한다.

**복구 위치가 의미를 바꾼다**:

```java
points.exceptionally(ex -> 0).thenApply(p -> "points=" + p);          // 포인트만 복구하고 계속 진행
points.thenApply(p -> "points=" + p).exceptionally(ex -> "no-points"); // 그 앞 전체(포인트 조회 + 변환)의 실패를 덮는다
```

복구 단계는 **"그 앞에 있는 모든 것"의 실패를 잡는다.** 선택적 정보의 폴백은 그 정보의 분기에만 붙이고, 필수 정보와 합친 뒤에 붙이지 않는다(E7).

### 5. 타임아웃 (R10)

| 방법 | 누가 기다리는가 | future 상태 | 실제 작업 |
|---|---|---|---|
| `orTimeout(t)` **[Java 9+]** | 아무도 (타이머) | `TimeoutException`으로 실패 | **계속 실행** |
| `completeOnTimeout(v, t)` **[Java 9+]** | 아무도 (타이머) | `v`로 성공 | **계속 실행**, 늦은 결과는 버려진다 |
| `get(t, unit)` | **호출 스레드가 t 동안 막힌다** | **바뀌지 않는다** (`isDone() == false`) | 계속 실행 |

- **`orTimeout`/`completeOnTimeout`은 새 future가 아니라 `this`를 반환한다.** 받은 future 자체를 완료시킨다. 다른 곳에서도 쓰는 future에 걸면 그쪽 결과까지 바뀐다. "모든 `thenXxx`는 새 future"(R6)의 예외다.
- 타임아웃으로 완료되면 뒤에 붙은 non-async 콜백은 **타임아웃 스케줄러 스레드**에서 실행된다(R1). JVM에 하나뿐이므로 거기서 blocking하면 모든 타임아웃이 밀린다. **[버전]** JDK 25: `ForkJoinPool.commonPool-delayScheduler`, JDK 9~24: `CompletableFutureDelayScheduler`.
- Java 8에는 둘 다 없다. `ScheduledExecutorService`로 직접 구현한다(E4). 이때 콜백은 내 scheduler 스레드에서 실행된다.

### 6. 취소 (R10)

- `CompletableFuture.cancel(true)`의 `mayInterruptIfRunning`은 **무시된다**. 상태만 `CANCELLED`로 바꾸고 실행 중인 작업은 interrupt하지 않는다. CF는 자기를 완료시킬 작업이 어느 스레드에서 도는지 모른다.
- 취소는 **상류로 전파되지 않는다**. 하류를 `cancel()`해도 상류 future는 취소되지 않는다.
- 상류가 취소되면 하류는 **취소가 아니라 실패**한다. `CompletionException(CancellationException)`으로 완료되고 `isCancelled()`는 `false`, `state()`(Java 19+)는 `FAILED`다.
- 정말 멈추려면 작업을 실행하는 쪽의 핸들이 필요하다. `ExecutorService.submit`이 돌려주는 `Future.cancel(true)`는 그 스레드를 **interrupt한다**. 단, 실행 중인 코드가 interrupt에 반응해야 멈춘다(`Thread.sleep`, `BlockingQueue.take` 등은 반응한다. 반응하지 않는 blocking 호출도 많으므로 클라이언트별 취소 수단을 확인한다). E5에서 둘을 연결한다.

### 7. 실무에서의 증상

| 코드 모양 | 증상 |
|---|---|
| `ex instanceof 도메인예외` (unwrap 없음) | 품절인데 500 응답. "폴백이 안 탄다" |
| `throw new CompletionException(ex)`로 다시 던짐 | 상위 핸들러의 `getCause()` 판별이 틀린다(두 겹) |
| `whenComplete`로 기본값 반환 시도 | 장애 시 폴백 없이 실패 |
| `whenComplete` 안의 로그 코드 버그 | **성공한 결제가 실패로 응답된다** |
| 합친 뒤에 `exceptionally` | 필수 정보 실패까지 폴백으로 덮여 빈 화면이 정상 응답으로 나간다 (E7) |
| `orTimeout`만 걸고 정리 없음 | 응답은 빨리 나가는데 풀 스레드·DB 커넥션은 계속 점유. 장애 시 풀 고갈 |
| 공유 future에 `completeOnTimeout` | 캐시에 기본값이 저장되는 등 다른 소비자의 결과가 바뀐다 |
| `get(timeout)`을 요청 스레드에서 | 요청 스레드가 타임아웃만큼 막힌다. 타임아웃 후에도 작업은 계속 |
| `cf.cancel(true)`로 작업 중단 기대 | 사용자가 취소해도 리포트 생성이 끝까지 돌고 자원을 쓴다 |

---

## 진행 방법

1. 위 개념을 읽는다. [추론 규칙 카드](../docs/reasoning-rules.md)의 R8, R9, R10을 함께 본다.
2. `src/main/java/async/m03/Scenarios.java`를 읽고 [PREDICT.md](PREDICT.md)에 **예측과 근거 규칙**을 적는다. 결과는 `Outcome` 형식(`"ok:값"`, `"failed:근본원인클래스"`)으로 적는다.
3. 예측을 `Predictions.java`로 옮긴다(`null` → 값). 예외 클래스를 쓰려면 import를 추가한다.
4. 채점한다. **첫 채점 결과는 고정된다.** 반드시 전부 채운 뒤 실행하라.
   ```bash
   mvn -pl m03-failure -am test -Dtest=PredictionTest -Dsurefire.failIfNoSpecifiedTests=false
   python3 tools/score.py
   ```
5. 틀린 항목은 [journal](../journal/README.md)에 "어떤 규칙을 어떻게 잘못 적용했는지" 적는다.
6. `Exercises.java`(E1~E3, E5~E7)와 `Java8Timeouts.java`(E4)의 TODO를 구현하고 채점한다.
   ```bash
   mvn -pl m03-failure -am test -Dtest=ExercisesTest -Dsurefire.failIfNoSpecifiedTests=false
   ```

| 실습 | 연습하는 판단 | 규칙 |
|---|---|---|
| E1 `unwrap` | 껍질만 벗기고 도메인 예외의 cause는 남긴다 | R8 |
| E2 `fallbackOn` | 특정 예외만 폴백, 나머지는 한 겹으로 전파 | R8, R9 |
| E3 `withTimeoutFallback` | 원본을 건드리지 않는 타임아웃 폴백, 늦은 결과 기록 | R10, R6 |
| E4 `Java8Timeouts.within` | Java 8 방식 타임아웃, 타이머 정리 (`orTimeout` 등 사용 금지) | R10, R1 |
| E5 `interruptibleCall` | 취소·타임아웃 시 실제 작업을 interrupt | R10 |
| E6 `logged` | 관찰만 하고 결과는 그대로. 로거 장애 격리 | R9 |
| E7 `productDetail` | 필수/선택 정보의 실패 정책과 복구 위치 | R9, R10, R7 |

**통과 기준**: 예측 적중률(첫 시도) 90% 이상, `ExercisesTest` 전부 통과.

## 더 깊이: JDK 소스로 확인하기 (선택)

`java.util.concurrent.CompletableFuture`에서 아래를 찾아 읽어 보라.

1. `encodeThrowable(Throwable x)`: 이미 `CompletionException`이 아니면 감싼다. 단계를 거쳐 전달될 때 쓰인다.
2. `completeExceptionally(ex)` → `internalComplete(new AltResult(ex))`: 감싸지 않고 그대로 저장한다.
3. `reportGet` / `reportJoin`: `get`은 `CompletionException`의 cause를 꺼내 `ExecutionException`에 넣고, `join`은 그대로 던진다. 취소는 `new CancellationException(details, x)`로 던진다(JDK 25).
4. `uniWhenComplete`: 액션이 던진 예외를 `x.addSuppressed(ex)`로 붙이는 부분.
5. `orTimeout` → `return this;`, `cancel(boolean mayInterruptIfRunning)`: 인자를 쓰지 않는다.
