# m09. 라이브러리 작성자 관점: CF를 "내보내는" 쪽의 책임

> 난이도 ★★★★ · 관련 규칙 **R1, R6, R8, R10, R11** + 이 모듈의 **L1~L8** · 예측 19개 · 구현 7개 · API 리뷰 1개
> 리뷰 체크리스트 **#17, #18, #19** 담당 모듈

## 이 모듈을 마치면

- 공개 API가 `CompletableFuture`/`CompletionStage`를 반환할 때 **무엇이 보호되고 무엇이 보호되지 않는지** 정확히 말할 수 있다.
- 완료 스레드 정책(I/O 스레드에서 완료 vs completion executor)을 근거를 들어 고르고 **문서화**할 수 있다.
- Java 8에서 타임아웃·취소 전파·single-flight·sync/async 쌍을 **누수와 deadlock 없이** 구현할 수 있다.
- OSS 라이브러리(AWS SDK v2, Lettuce, Netty)의 CF 관련 코드를 읽고 "왜 이렇게 했는지"를 설명하고, 같은 수준으로 기여할 수 있다.

---

## 핵심 개념

### 0. 왜 라이브러리는 기준이 더 엄격한가

애플리케이션 코드에서 CF를 잘못 쓰면 내 서비스가 느려진다. **라이브러리**에서 잘못 쓰면 그 라이브러리를 쓰는 **모든 서비스**가 느려지고, 사용자는 원인을 찾지도 못한다. 라이브러리는 사용자 대신 아래 네 가지를 결정해 버리기 때문이다.

| 라이브러리가 대신 정하는 것 | 잘못 정하면 사용자에게 보이는 증상 |
|---|---|
| 사용자 콜백이 실행될 **스레드** | 사용자가 I/O 스레드에서 JDBC를 호출해서 전체 응답이 밀린다 (#18) |
| 내부 작업을 돌릴 **풀** | 사용자의 parallel stream, 다른 라이브러리까지 굶는다 (#19) |
| 반환한 future의 **소유권** | 한 호출자의 `complete`/`cancel`이 다른 호출자를 망가뜨린다 (#17) |
| **예외 모양** | 사용자가 `CompletionException → RuntimeException → ExecutionException`을 세 번 벗긴다 |

그래서 이 모듈은 R0~R13에 더해 라이브러리 전용 규칙 **L1~L8**을 쓴다.

| 규칙 | 내용 |
|---|---|
| **L1 소유권** | 내부 future를 그대로 내보내지 않는다. **호출마다** 새 future를 준다. 공유 사본은 보호가 아니다 |
| **L2 완료 스레드** | 반환한 stage를 **누가 완료시키는지** 정하고 Javadoc에 쓴다. completion executor 옵션을 제공한다 |
| **L3 풀** | 내부에서 `commonPool`(executor 생략 `xxxAsync`)을 쓰지 않는다. 필요한 executor는 builder로 받는다 |
| **L4 sync/async 쌍** | async를 `completedFuture(sync())`로 만들지 않는다(가짜 async). sync를 라이브러리 스레드에서 기다리게 두지 않는다(deadlock) |
| **L5 타임아웃·취소** | 타이머는 완료 시 **취소**하고 큐에서 **제거**한다. 사용자의 취소를 **하부 요청까지** 연결한다 |
| **L6 예외 정책** | 도메인 예외(unchecked) 하나의 계층으로, **감싸지 않고** 완료시킨다. sync API는 그대로 던진다 |
| **L7 single-flight** | 키당 진행 중인 요청 하나. 실패·타임아웃 포함 **완료 시 제거**, 호출자별 독립 stage |
| **L8 컴파일 대상** | 지원하는 최저 버전으로 `--release N` 컴파일. `-source/-target`만 쓰면 안 된다 |

---

### 1. 공개 API 형태: 무엇이 보호되는가 (L1, #17)

`CompletableFuture`를 받은 사람은 누구나 **쓰기 연산**을 할 수 있다.

```java
cf.complete(v)  cf.completeExceptionally(ex)  cf.cancel(true)  cf.obtrudeValue(v)  cf.obtrudeException(ex)
```

`obtrudeValue`는 **이미 완료된 future의 값도 덮어쓴다.** 그래서 라이브러리가 진행 중인 요청을 캐시해 두고(키당 하나) **그 future 자체**를 여러 호출자에게 주면, 한 호출자의 실수가 모두에게 퍼진다.

```java
// ❌ 내부 future 를 그대로 반환
CompletableFuture<String> getAsync(String key) {
    return inFlight.computeIfAbsent(key, this::send);
}
callerA.complete("hacked");  // → 같은 키의 callerB 는 무엇을 받을까? (S04)
callerA.cancel(true);        // → callerB 의 join 은? (S05). 요청 자체는 계속 진행된다 (R10)
```

#### Java 9+: `copy()`와 `minimalCompletionStage()`

| API | 무엇을 주는가 | 호출자가 할 수 있는 것 (JDK 25에서 확인) |
|---|---|---|
| `f.copy()` | 의존 future(사본) | 사본에만 `complete`/`cancel` 가능. `f`는 무사 |
| `f.minimalCompletionStage()` | `CompletableFuture$MinimalStage` | **CF로 캐스팅은 성공**하지만 `complete`, `cancel`, `obtrudeValue`, `isDone`, `getNow`, `join` 모두 `UnsupportedOperationException`. `toCompletableFuture()`는 **매번 새 사본**을 준다. 그 사본을 `cancel`해도 원본은 취소되지 않는다. 즉 **사용자는 취소할 방법이 없다** |

#### Java 8: 반환 타입을 `CompletionStage`로 바꾸면 보호될까?

`CompletionStage`에는 쓰기 메서드가 없다. 하지만 `toCompletableFuture()`가 있고, CF 기반 stage에서는 **자기 자신을 반환**한다. 캐스팅도 된다. 그러므로 **반환 타입은 보호 장치가 아니다.** 보호는 "**호출마다 새 객체**를 준다"에서 나온다.

| 반환 방식 (Java 8) | 다른 호출자 | 내부 future | 사용자 취소 | 바로 붙인 `exceptionally`가 받는 것 |
|---|---|---|---|---|
| 내부 CF 그대로 | ❌ 오염 | ❌ | 모두가 취소됨 | 원래 예외 |
| 호출마다 `f.thenApply(x -> x)` | ✅ | ✅ | 자기 사본만 취소됨. 요청은 계속 (R10) | **`CompletionException`** |
| `thenApply` 사본 **하나를 캐시**해서 공유 | ❌ 오염 | ✅ | 모두가 취소됨 | `CompletionException` |
| 호출마다 **whenComplete로 중계**한 새 CF (E1 `Stages.copyOf`) | ✅ | ✅ | 자기 사본만. **라이브러리가 연결하면** 요청 취소 (E5) | **원래 예외** |

정리하면 다음과 같다.

- `thenApply(x -> x)` 사본은 **소유권 문제는 풀지만 예외를 감싼다.** 원본이 `completeExceptionally(ex)`로 실패해도 사본은 `CompletionException(ex)`로 실패한다(R8: "그 뒤 단계부터 감싸진다"). Java 9의 `copy()`와 `minimalCompletionStage()`도 마찬가지다(JDK 25에서 확인).
- 그래서 라이브러리는 `new CompletableFuture<>()` + `whenComplete`로 **직접 중계**하면서 unwrap한다(E1). 덤으로 원본이 취소되면 사본도 `isCancelled() == true`가 된다(`thenApply` 사본은 `CompletionException(CancellationException)`이고 `isCancelled()`는 `false`다).
- 실제 라이브러리를 보면, AWS SDK v2는 `CompletableFuture`를 반환하지만 **요청마다 새로 만든 future**(`toReturn`)를 준다. Lettuce는 `RedisFuture`(`CompletionStage` + `Future`) 인터페이스를 반환하고, 구현체 `AsyncCommand`가 `CompletableFuture`를 상속해 `cancel()`을 오버라이드해서 명령을 취소한다. 반환 타입보다 **누가 그 객체를 공유하는가**가 핵심이다.

### 2. 완료 스레드 정책 (L2, L3, #18, #19)

R1에 따라 반환한 stage에 응답 전에 붙은 non-async 콜백은 **그 stage를 완료시키는 스레드**에서 실행된다. 즉 "누가 완료시키는가"가 **사용자 코드가 실행될 스레드**다. 라이브러리가 고를 수 있는 정책은 두 가지다.

| | 정책 1: 완료를 일으킨 스레드에서 바로 완료 | 정책 2: completion executor로 넘겨서 완료 |
|---|---|---|
| 예 | Netty, Lettuce(이벤트 루프에서 완료) | AWS SDK v2 `FUTURE_COMPLETION_EXECUTOR` |
| 사용자 콜백 스레드 | I/O 스레드(응답) / 타이머 스레드(타임아웃) | 그 executor. 타임아웃도 넘긴다 |
| 장점 | 스레드 전환 0회, 가장 빠름 | 사용자가 콜백에서 blocking해도 I/O 스레드는 안전 |
| 단점 | 사용자가 콜백에서 blocking하면 **이 클라이언트의 모든 응답**이 밀린다 | 요청마다 hop 1회. executor가 포화되면 지연 |
| 함정 | 문서화하지 않으면 사용자는 모른다 | executor가 `CallerRunsPolicy`면 다시 I/O 스레드에서 실행된다(R3 예외 1). 거절되면 결과를 잃지 않게 해야 한다 |

- AWS SDK v2(sdk-core 2.55.10)의 기본값은 클라이언트마다 SDK가 관리하는 `ThreadPoolExecutor`(스레드 이름 `sdk-async-response-*`, 큐 1000)다. Javadoc은 `Runnable::run`(= I/O 스레드에서 완료)을 쓰지 말라고 강하게 경고한다. `MakeAsyncHttpRequestStage`는 `handleAsync(.., futureCompletionExecutor)`로 완료를 넘기고, **그것이 실패하면(거절) 현재 스레드에서 동기 완료**한다. E4가 같은 설계다.
- 어느 정책이든 **이미 완료된 stage에 붙인 콜백은 붙인 스레드에서 실행된다**. 이것은 라이브러리가 바꿀 수 없다. 사용자에게는 "실행 스레드를 고정하려면 `xxxAsync(fn, 당신의 executor)`"라고 문서화한다.
- 라이브러리 내부에 executor가 필요하면(타이머, 완료 executor, 디코딩) **builder로 받고, 받지 않았다면 직접 소유하고 `close()`에서 정리한다.** executor 생략 `supplyAsync`/`thenApplyAsync`는 JVM 전체가 공유하는 commonPool을 쓴다(R2, #19). Java 8~24의 작은 컨테이너에서는 작업마다 새 스레드를 만든다([버전표](../docs/java-version-matrix.md)).
- `minikv`는 정책 1을 기본값으로 두고 정책 2를 옵션으로 제공한다. `MiniKvClient`의 Javadoc이 이 정책을 문서화한 예다.

### 3. sync/async 쌍 (L4, #1)

| 잘못된 구현 | 증상 |
|---|---|
| async = `completedFuture(syncCall())` (가짜 async) | 호출자가 응답 시간만큼 막힌다. 이벤트 루프에서 호출하면 루프가 멈춘다. 콜백은 이미 완료된 future에 붙으므로 호출자 스레드에서 실행된다 |
| sync = `async().join()`을 **I/O 스레드**에서 호출 | 응답을 전달할 스레드가 기다리고 있으므로 응답이 오지 않는다 → deadlock(타임아웃이 있으면 타임아웃까지) |
| sync = `async().get()`을 **completion executor 스레드**에서 호출 | 응답은 왔지만 완료 작업이 executor 큐에서 기다린다. executor 스레드가 바로 그 완료를 기다리고 있다 → deadlock(타임아웃이 있으면 타임아웃까지) |
| sync가 `ExecutionException`을 그대로 던짐 | 사용자가 매번 unwrap한다 |

올바른 sync 구현(E7)은 다음과 같다.
1. **라이브러리 스레드에서 호출되면 즉시 거절**한다. Netty의 `DefaultPromise.checkDeadLock()`이 이벤트 루프 안에서 `await`/`sync`를 부르면 `BlockingOperationException`을 던지는 것과 같은 방어다.
2. completion executor를 거치는 사용자용 future가 아니라 **내부 raw future**를 기다린다.
3. 예외를 unwrap해서 **도메인 예외를 그대로 던진다.** Lettuce의 sync API도 내부적으로 `Futures.awaitOrCancel`(시간 초과 시 `cancel`, 예외는 `Exceptions.bubble`로 벗김)을 쓴다.
4. interrupt되면 interrupt 상태를 복원하고 요청을 abort한다.

### 4. Java 8 타임아웃 (L5, R10)

`orTimeout`은 Java 9+다. Java 8 라이브러리는 직접 만든다(E2).

```java
ScheduledFuture<?> task = timer.schedule(() -> {
    if (f.completeExceptionally(new TimeoutException())) onTimeout.run();   // 이긴 경우에만 하부 요청 취소
}, timeout, unit);
f.whenComplete((v, ex) -> task.cancel(false));                              // 먼저 끝나면 타이머 취소
```

- `cancel`만으로는 부족하다. `ScheduledThreadPoolExecutor`는 기본 설정에서 **취소된 작업을 지연시간이 다 될 때까지 큐에 남긴다**. 10초 타임아웃에 초당 1만 요청이면 큐에 10만 개가 쌓인다. 라이브러리 소유 타이머는 `setRemoveOnCancelPolicy(true)`로 만든다. `Executors.newSingleThreadScheduledExecutor()`는 감싼 객체(`DelegatedScheduledExecutorService`)를 반환하므로 이 설정을 할 수 없다. `new ScheduledThreadPoolExecutor(1, factory)`를 직접 만든다.
- 타임아웃으로 완료되면 그 뒤의 non-async 콜백은 **타이머 스레드**에서 실행된다(R10). 타이머 스레드에서 blocking하면 **모든 타임아웃이 밀린다**. 그래서 정책 2는 타임아웃 완료도 completion executor로 넘기고, E7은 소유 타이머 스레드에서의 sync 호출도 거절한다.
- `completeExceptionally(new TimeoutException())`로 **직접** 실패시켰으므로 바로 붙인 콜백은 `TimeoutException`을 그대로 받는다(R8).

### 5. 취소 전파 (L5, R10)

`CompletableFuture.cancel`은 **상태만 바꾼다.** interrupt하지 않고, 상류로 전파되지 않으며, 네트워크 요청은 그대로 진행된다. 사용자의 취소를 의미 있게 만들려면 **라이브러리가 직접 연결**해야 한다(E5).

```java
CompletableFuture<String> out = deliver(call.raw);
out.whenComplete((v, ex) -> {
    if (out.isCancelled()) {          // 사용자가 내 stage 를 취소했다
        call.request.abort();         // 하부 요청 취소 (소켓 RST, 커넥션 반환)
        call.raw.cancel(false);       // raw 의 whenComplete 가 타이머도 정리한다
    }
});
```

- 사용자는 `stage.toCompletableFuture().cancel(true)`로 취소한다. Java 8에서 `CompletionStage`를 반환하면 이것이 유일한 취소 경로다. 그래서 `minimalCompletionStage()`처럼 취소 경로를 막으면 사용자는 요청을 끊을 수 없다.
- AWS SDK v2는 `CompletableFutureUtils.forwardExceptionTo(toReturn, executeFuture)`로 반환 future의 실패(취소 포함)를 HTTP 실행 future로 전달한다. Lettuce는 `AsyncCommand.cancel()`을 오버라이드해서 명령을 취소한다.
- **공유 요청(single-flight)은 한 호출자의 취소로 abort하면 안 된다**(E6). 다른 호출자가 기다리고 있다.
- 참고(구현 세부, JDK 25에서 확인): `supplyAsync` 작업이 **아직 시작 전**에 그 future가 취소되면 supplier는 실행되지 않는다(`AsyncSupply.run`이 `result == null`인지 확인한다). 이미 **실행 중**이면 멈추지 않는다. 라이브러리의 하부 I/O는 이와 무관하게 계속 진행되므로 직접 abort해야 한다.

### 6. 예외 정책 (L6, R8)

```
minikv 계약
 - 반환 stage 의 실패 원인 = MiniKvException 계열(unchecked) 또는 CancellationException(사용자 취소)
 - 바로 붙인 exceptionally/handle/whenComplete 는 MiniKvException 을 그대로 받는다
 - join() → CompletionException(MiniKvException), get() → ExecutionException(MiniKvException)   ← JDK 계약, 피할 수 없다
 - sync get() → MiniKvException 을 그대로 던진다
```

- `exceptionally`는 **완료시킬 때 넘긴 그 예외**를 받는다. 단, 그 stage에 **바로** 붙였을 때만이다. 라이브러리가 사용자에게 주는 stage를 `thenApply` 사본으로 만들면 `CompletionException`이 한 겹 생긴다. 중계할 때 unwrap하고 `completeExceptionally(도메인 예외)`로 직접 완료시킨다(E1, E3, E4).
- 도메인 예외를 unchecked로 두는 이유: 사용자의 `Function`/`BiFunction` 콜백은 checked 예외를 던질 수 없다. Lettuce `RedisException`, AWS `SdkException`이 모두 `RuntimeException`이다.
- 원인(cause)은 버리지 않는다. `IOException`은 `MiniKvException(cause=IOException)`, `TimeoutException`은 `MiniKvTimeoutException(cause=TimeoutException)`이 된다.

### 7. 동시 로드 합치기: single-flight (L7)

```java
CompletableFuture<String> mine = new CompletableFuture<>();
CompletableFuture<String> existing = inFlight.putIfAbsent(key, mine);   // 원자적으로 자리 차지
if (existing != null) return deliver(existing);                         // 호출자별 독립 stage
start(key).raw.whenComplete((v, ex) -> {
    inFlight.remove(key, mine);                                         // 실패·타임아웃 포함, 알리기 "전에" 제거
    if (ex != null) mine.completeExceptionally(ex); else mine.complete(v);
});
return deliver(mine);
```

- `get` 후 `put`은 원자적이지 않아서 요청이 2개 나간다. `putIfAbsent`나 `computeIfAbsent`를 쓴다.
- `computeIfAbsent`의 매핑 함수 안에서 **같은 맵을 수정**하면(예: 요청이 동기적으로 실패해서 `whenComplete(remove)`가 즉시 실행) Java 9+는 `IllegalStateException: Recursive update`를 던지고(JDK 25에서 확인), Java 8은 무한 루프에 빠질 수 있다(JDK-8062841). 자리표시 future + `putIfAbsent` 패턴은 이 문제가 없다.
- 실패를 지우지 않으면 장애가 영구화된다(리뷰 대상 `BadKvClient`의 증상).
- `remove(key, mine)`(값까지 비교)로 지운다. 그사이 새 요청이 같은 키를 차지했다면 그것을 지우면 안 된다.

### 8. 컴파일 대상 (L8)

이 모듈의 main은 `--release 8`로 컴파일된다(`pom.xml`의 `<release>8</release>`, 테스트는 `<testRelease>17</testRelease>`). JDK 25의 javac도 `--release 8`을 받는다(obsolete 경고만 내고, 루트 POM이 `-Xlint:-options`로 끈다). 그래서 main에서는 harness(Java 17 바이트코드)를 쓸 수 없고, `Predictions.java`는 test 소스에 있다.

```
$ javac --release 8 A.java          # CompletableFuture.failedFuture(ex)
A.java:2: error: cannot find symbol   ← Java 9+ API 는 컴파일 에러 (copy, minimalCompletionStage, var ... 모두 같다)

$ javac -source 8 -target 8 A.java  # 같은 코드
(성공, major version 52)             ← JDK 25 의 클래스 라이브러리로 컴파일되었기 때문. Java 8 JVM 에서 실행하면 NoSuchMethodError
```

- `-source 8 -target 8`은 **문법과 바이트코드 버전만** 8로 맞춘다. API는 컴파일하는 JDK 것을 쓴다. javac가 "bootstrap class path is not set" 경고를 내지만, `-Xlint:-options`를 켜 두면 그 경고까지 사라진다.
- 더 교묘한 예: `ByteBuffer b; b.flip()`을 `-source/-target 8`로 컴파일하면 Java 9+에서 생긴 공변 반환 메서드 `ByteBuffer.flip()Ljava/nio/ByteBuffer;`를 참조하는 바이트코드가 나온다(javap로 확인). Java 8에는 `Buffer.flip()`만 있으므로 실행 시 `NoSuchMethodError`가 난다. `--release 8`은 이 코드를 컴파일 단계에서 거부한다(`Buffer cannot be converted to ByteBuffer`).
- 결론: 라이브러리는 **지원하는 최저 버전으로 `--release`** 를 지정한다. 테스트는 더 높은 버전으로 컴파일해도 된다.

---

## 실습 구성

| 파일 | 내용 |
|---|---|
| `src/main/java/async/m09/minikv/FakeTransport.java` | 가짜 네트워크. I/O 스레드 `nonblocking-minikv-io-1`이 지연 후 콜백한다. `abort()`, 실패 키, 통계 제공 (수정 금지) |
| `src/main/java/async/m09/minikv/MiniKvClient.java` | **실습 대상 라이브러리**. builder(completion executor, timer, timeout), `getAsync`, `getShared`, `get` |
| `src/main/java/async/m09/util/Stages.java`, `Timeouts.java` | Java 8 유틸리티 (E1, E2) |
| `src/main/java/async/m09/minikv/Errors.java` | 예외 정책 (E3) |
| `src/main/java/async/m09/Scenarios.java` | 예측 시나리오 (release 8, 수정 금지) |
| `src/test/java/async/m09/ModernScenarios.java` | Java 9+ API가 필요한 시나리오 S09 (수정 금지) |
| `src/test/java/async/m09/Predictions.java` | 예측 시트 (harness의 `On`을 쓰므로 test 소스에 있다) |
| `src/main/java/async/m09/review/BadKvClient.java` + [REVIEW.md](REVIEW.md) | API 리뷰 실습 |

| 실습 | 위치 | 규칙 | 채점 |
|---|---|---|---|
| E1 `Stages.copyOf` | Java 8 판 `copy()`. 호출마다 새 future, 예외 unwrap | L1, R8 | `StagesTest` |
| E2 `Timeouts.failAfter` | Java 8 판 `orTimeout` + 타이머 정리 + `onTimeout` | L5, R10 | `TimeoutsTest` |
| E3 `Errors.toPublic` | 예외 정책 | L6, R8 | `ErrorsTest` |
| E4 `MiniKvClient.deliver` | 완료 스레드 정책 + 거절 시 폴백 + 보호 | L1, L2 | `MiniKvClientTest$E4_Deliver` |
| E5 `MiniKvClient.getAsync` | 가짜 async 금지 + 취소 전파 | L4, L5 | `MiniKvClientTest$E5_GetAsync` |
| E6 `MiniKvClient.getShared` | single-flight | L7, L1 | `MiniKvClientTest$E6_GetShared` |
| E7 `MiniKvClient.get` | 안전한 sync API | L4, L6 | `MiniKvClientTest$E7_SyncGet` |

E4~E7은 E1~E3을 사용한다. 순서대로 진행한다.

## 진행 방법

1. 위 개념을 읽는다. [추론 규칙 카드](../docs/reasoning-rules.md)의 R1, R8, R10과 [리뷰 체크리스트](../docs/review-checklist.md) #17~#19를 함께 본다.
2. `Scenarios.java`와 `ModernScenarios.java`를 읽고 [PREDICT.md](PREDICT.md)에 **예측과 근거 규칙**을 적은 뒤 `src/test/java/async/m09/Predictions.java`로 옮긴다.
3. 채점한다. **첫 채점 결과는 고정된다.**
   ```bash
   mvn -pl m09-library-author -am test -Dtest=PredictionTest -Dsurefire.failIfNoSpecifiedTests=false
   python3 tools/score.py
   ```
4. 틀린 항목은 [journal](../journal/README.md)에 적는다.
5. E1 → E7 순서로 구현하고 채점한다.
   ```bash
   mvn -pl m09-library-author -am test -Dtest='StagesTest,TimeoutsTest,ErrorsTest' -Dsurefire.failIfNoSpecifiedTests=false
   mvn -pl m09-library-author -am test -Dtest='MiniKvClientTest*' -Dsurefire.failIfNoSpecifiedTests=false
   ```
6. main에 Java 9+ API를 써 보고 컴파일이 실패하는 것을 직접 확인한다(예: `Stages`에서 `source.copy()`). 확인한 뒤 되돌린다.
   ```bash
   mvn -q -pl m09-library-author compile   # → cannot find symbol: method copy()
   ```
7. [REVIEW.md](REVIEW.md)에 `BadKvClient` 리뷰 코멘트를 쓴다. 다 쓴 뒤 증거 테스트를 읽고 돌린다.
   ```bash
   mvn -pl m09-library-author -am test -Dtest=ReviewEvidenceTest -Dsurefire.failIfNoSpecifiedTests=false
   ```
8. 전체: `mvn -q -pl m09-library-author test`

**통과 기준**: 예측 적중률(첫 시도) 90% 이상, 모든 테스트 통과, REVIEW.md에서 모범답안의 지적 사항 9개 중 7개 이상을 규칙 번호와 증상까지 적었다.

## 더 깊이: OSS 소스로 확인하기 (선택, 권장, m10 예습)

| 소스 | 볼 것 | 이 모듈의 대응 |
|---|---|---|
| AWS SDK v2 `software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption#FUTURE_COMPLETION_EXECUTOR` | Javadoc의 기본값과 `Runnable::run` 경고 | L2, E4 |
| AWS SDK v2 `...core.internal.http.pipeline.stages.MakeAsyncHttpRequestStage` | `toReturn` 새 future, `forwardExceptionTo`(취소 전파), `handleAsync(.., futureCompletionExecutor)` 실패 시 동기 완료 | L1, L2, L5, E4, E5 |
| AWS SDK v2 `SdkDefaultClientBuilder#resolveAsyncFutureCompletionExecutor` | 기본 completion executor 구성(`sdk-async-response`) | L3 |
| Lettuce `io.lettuce.core.protocol.AsyncCommand#cancel`, `io.lettuce.core.internal.Futures#awaitOrCancel` | 취소 시 명령 취소, sync API의 대기와 unwrap | L4, L5, E7 |
| Netty `io.netty.util.concurrent.DefaultPromise#checkDeadLock` | 이벤트 루프 안의 blocking 대기를 즉시 거절 | L4, E7 |
| JDK `CompletableFuture$MinimalStage`, `uniCopyStage`, `encodeRelay` | `copy()`가 예외를 `CompletionException`으로 감싸는 지점 | L1, R8 |

`~/.m2/repository`에 sources jar가 있다(sdk-core 2.55.10, lettuce-core 7.5.2, netty-common 4.2.15).
