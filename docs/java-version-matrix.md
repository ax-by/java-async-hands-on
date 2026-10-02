# Java 버전별 CompletableFuture 차이

운영 환경이 Java 8이나 17이어도, 이 repo는 JDK 25로 실행한다. 그래서 **API 차이**는 컴파일러(`--release`)가 막아 주지만, **런타임 동작 차이**는 직접 알고 있어야 한다.

## 1. API 차이 (컴파일러가 막아 준다)

| 도입 | API | Java 8 대체 방법 |
|---|---|---|
| 8 | `supplyAsync`, `thenXxx[Async]`, `handle`, `whenComplete`, `exceptionally`, `allOf`, `anyOf`, `get`, `join`, `getNow`, `complete`, `completeExceptionally`, `obtrudeValue` | 해당 없음 |
| 9 | `orTimeout`, `completeOnTimeout` | `ScheduledExecutorService`로 타이머를 걸고 `completeExceptionally(new TimeoutException())` |
| 9 | `failedFuture`, `completedStage`, `failedStage` | `new CompletableFuture<>()` 후 `completeExceptionally` |
| 9 | `delayedExecutor` | `ScheduledExecutorService.schedule` |
| 9 | `copy`, `minimalCompletionStage` | `f.thenApply(Function.identity())` (복사본), `CompletionStage` 타입으로 반환 |
| 9 | `defaultExecutor`, `newIncompleteFuture` (서브클래스 확장점) | 없음 |
| 12 | `exceptionallyCompose`, `exceptionallyAsync` | `handle((v, ex) -> ex == null ? completed(v) : fallback()).thenCompose(identity())` |
| 19 | `resultNow`, `exceptionNow`, `state` (Future 인터페이스) | `isDone()` + `join()` |
| 19 | `ExecutorService.close()` | `shutdown()` + `awaitTermination` |
| 21 | 가상 스레드, `Executors.newVirtualThreadPerTaskExecutor()` | 없음 |
| 25 | `ScopedValue` (정식) | 없음 |
| 25 | `StructuredTaskScope` (**preview**) | 없음 |

## 2. 런타임 동작 차이 (직접 알아야 한다)

아래 결과는 이 repo를 만들면서 **Temurin 21.0.11과 25.0.3에서 직접 실험**한 것이다. 17은 21과 같은 동작이다.

| 상황 | JDK 8 ~ 24 | JDK 25 |
|---|---|---|
| `xxxAsync(fn)`, 병렬도 ≥ 2 | `ForkJoinPool.commonPool-worker-N` | 같음 |
| `xxxAsync(fn)`, **commonPool 병렬도 ≤ 1** (CPU 1~2개 컨테이너, `-XX:ActiveProcessorCount=1/2`) | **작업마다 새 스레드** `Thread-N` (`ThreadPerTaskExecutor`) | commonPool 워커 |
| `orTimeout`/`completeOnTimeout` 발화 스레드 | `CompletableFutureDelayScheduler` (JVM에 1개) | `ForkJoinPool.commonPool-delayScheduler` |
| `delayedExecutor(d)`로 실행되는 작업 | 지연 후 기본 executor(위 두 행 규칙)로 넘김 | commonPool 워커 |
| 취소된 future의 `join()`/`get()` | 저장된 `CancellationException`을 그대로 던짐 (`getCause()== null`) | **새** `CancellationException`을 던지고 저장된 예외를 cause로 담음 |
| JDK `HttpClient.sendAsync`가 반환한 future의 완료 스레드 | `xxxAsync(fn)`과 같은 기본 executor 규칙(병렬도 ≤ 1이면 새 스레드). **`HttpClient.Builder.executor`를 지정해도 바뀌지 않는다** | commonPool 워커 |
| 취소된 future의 의존 단계 | `CompletionException(CancellationException)`으로 실패, `isCancelled() == false` | 같음 |

### 운영(Java 17) 관점에서 읽는 법

- **CPU 2개 이하 컨테이너에서 `supplyAsync(fn)`(executor 생략)를 쓰면** Java 17에서는 호출할 때마다 스레드를 새로 만든다. 요청량이 많으면 스레드 수가 폭증한다. 같은 코드를 JDK 25 로컬에서 돌리면 재현되지 않는다. → **executor를 항상 명시하라**(R2, R11).
- 취소 예외의 **타입**은 두 버전이 같다. 예외 체인을 검사하는 코드(`getCause()`로 원인 추적)만 결과가 달라진다.
- `harness`의 `On.timeoutScheduler()`와 `On.freshThread()`는 두 버전을 같은 값으로 분류하므로, 예측은 버전과 무관하게 작성할 수 있다.
- m04에는 JDK 21이 설치되어 있으면 자식 JVM으로 Java 17 계열 동작을 재현하는 실습이 있다.

## 3. 컴파일 대상 정책

| 모듈 | `--release` | 이유 |
|---|---|---|
| 대부분의 실습 | 17 | 운영 환경(Java 17)에서 그대로 쓸 수 있는 코드만 작성한다 |
| m09-library-author (main) | 8 | 라이브러리 작성자 관점: Java 8 사용자를 지원하는 공개 API |
| m07-vt-compare | 25 (+preview) | 가상 스레드, `ScopedValue`, `StructuredTaskScope` 비교 |
