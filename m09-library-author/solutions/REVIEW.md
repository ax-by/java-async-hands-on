# m09 API 리뷰 실습: `BadKvClient`

사내 공용 라이브러리 "kv-client v0.1"의 첫 PR이다. 이 라이브러리는 사내 수십 개 서비스(Spring MVC, WebFlux, 배치)가 쓰게 된다. **라이브러리 작성자 관점**(L1~L8, 체크리스트 #17~#19)으로 리뷰하라.

> PR 설명: "비동기 KV 클라이언트를 추가합니다. 같은 키 동시 조회는 캐시로 합쳤고, 동기 API도 제공합니다. 성능을 위해 CompletableFuture를 사용했습니다."

`src/main/java/async/m09/review/BadKvClient.java` (전송 계층 `FakeTransport`는 minikv와 같다. `send`는 non-blocking이고 I/O 스레드가 콜백한다.)

```java
16  public class BadKvClient {
17
18      private final FakeTransport transport;
19      private final Map<String, CompletableFuture<String>> cache = new ConcurrentHashMap<>();
20
21      public BadKvClient(FakeTransport transport) {
22          this.transport = transport;
23      }
24
25      /**
26       * 값을 비동기로 조회한다.
27       */
28      public CompletableFuture<String> getAsync(String key) {
29          return cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> fetch(k)));
30      }
31
32      /**
33       * 캐시를 거치지 않고 최신 값을 비동기로 조회한다.
34       */
35      public CompletableFuture<String> getFreshAsync(String key) {
36          return CompletableFuture.completedFuture(fetch(key));
37      }
38
39      /**
40       * 값을 조회한다.
41       */
42      public String get(String key) {
43          return getAsync(key).join();
44      }
45
46      private String fetch(String key) {
47          CompletableFuture<String> response = new CompletableFuture<>();
48          transport.send(key, (value, error) -> {
49              if (error != null) {
50                  response.completeExceptionally(error);
51              } else {
52                  response.complete(value);
53              }
54          });
55          try {
56              return response.get();
57          } catch (Exception e) {
58              throw new RuntimeException("kv fetch failed", e);
59          }
60      }
61  }
```

## 작성 방법

- [리뷰 체크리스트](../docs/review-checklist.md)의 양식을 따른다: `[규칙][#번호] 위치 — 문제 → 증상(누가, 언제, 무엇을 겪는가) → 수정 제안`.
- **증상**을 구체적으로 쓴다. "좋지 않다"가 아니라 "어떤 부하에서 어떤 지표가 어떻게 된다"를 쓴다.
- 지적 사항은 9개 정도다. 다 쓴 뒤 `ReviewEvidenceTest`를 읽고 실행해서 증상을 확인한다.
- 마지막에 "이 PR을 머지하기 위한 최소 수정안"을 공개 API 시그니처와 Javadoc 수준으로 적는다.

## 내 리뷰

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

```
[L1][#17] L28-29 getAsync 가 캐시에 든 내부 future 를 그대로 반환한다.
호출자는 CompletableFuture 를 받았으므로 complete/obtrudeValue/cancel 을 할 수 있고, 그 객체는 같은 키의 모든 호출자와 캐시가 공유한다.
증상: 한 호출자가 complete("기본값") 하면 그 키는 영구히 "기본값"이 된다. 한 호출자가 cancel 하면 같은 키를 기다리던
모든 호출자가 CancellationException 을 받는다. (ReviewEvidenceTest.callerCanPoisonTheCacheForEveryone, callerCancelBreaksOtherCallers)
→ 호출마다 새 future 를 반환한다(whenComplete 중계 + unwrap, minikv E1/E4). 반환 타입을 CompletionStage 로 바꾸는 것만으로는 막히지 않는다(S06).
```

```
[L3][#19][#4][R2, R11] L29 supplyAsync 에 executor 가 없다 → commonPool. 그 안의 fetch 는 response.get() 으로 응답(200ms)까지 blocking 한다.
증상: 키마다 commonPool 워커 하나가 응답 시간 동안 멈춘다. commonPool 은 JVM 전체(parallel stream, 다른 라이브러리, CompletableFuture 기본값)가
공유하므로, 사용자 서비스의 관계없는 parallel stream 까지 느려진다. Java 8~24 에서 CPU 2개 이하 컨테이너면 commonPool 대신 작업마다 새 스레드를
만들어 스레드 수가 폭증한다. (getAsync_blocksACommonPoolWorker: 요청을 보낸 스레드가 ForkJoinPool.commonPool-worker-N)
→ 내부에서 commonPool 을 쓰지 않는다. 이 경우는 executor 자체가 필요 없다(다음 항목).
```

```
[R0][L4] L46-60 transport 는 이미 non-blocking(콜백)인데 fetch 가 그것을 response.get() 으로 기다려 blocking 으로 바꾸고,
getAsync 는 그 blocking 을 다시 스레드에 올려 비동기처럼 보이게 한다(sync-over-async-over-sync).
증상: 요청 1개당 스레드 1개를 응답 시간 동안 점유한다. 목적 B(non-blocking)의 이점이 사라지고 목적 A(blocking fan-out)의 비용만 남는다.
→ 콜백에서 곧바로 future 를 완료시킨다(MiniKvClient.start). 스레드를 점유하지 않는다.
```

```
[L4][#1] L35-37 getFreshAsync 는 가짜 async 다. completedFuture(fetch(key)) 는 fetch 가 끝날 때까지 호출자를 막는다.
증상: "Async" 이름을 믿고 WebFlux 핸들러나 이벤트 루프에서 호출하면 그 스레드가 응답 시간(200ms)만큼 멈추고, 그 루프의 다른 요청이 모두 밀린다.
반환된 future 는 이미 완료 상태라 콜백도 호출자 스레드에서 실행된다(R1). (getFreshAsync_isFakeAsync: 반환까지 ≥150ms, 콜백 = caller)
→ getAsync 와 같은 non-blocking 경로에서 캐시만 건너뛴다.
```

```
[L7] L29 computeIfAbsent 캐시가 비워지지 않는다. single-flight(동시 조회 합치기)가 의도라면 완료 시 제거해야 한다.
증상: (1) 실패한 future 가 영구히 캐시된다. 일시적 장애(커넥션 리셋) 한 번이면 그 키는 재시작 전까지 계속 실패한다(failureIsCachedForever: 요청 수 1).
(2) 성공 값도 영구히 캐시되어 "최신 값"을 볼 수 없고, 키 수만큼 메모리가 계속 늘어난다.
→ 실패·타임아웃 포함 완료 시 remove(key, future). 알리기 전에 제거. 캐시가 의도라면 TTL/크기 제한이 있는 캐시를 별도로 둔다.
```

```
[L5][R10][#11, #12] 타임아웃이 어디에도 없다(L43 join, L56 get 모두 무기한). 취소도 하부 요청까지 전달되지 않는다.
증상: 서버가 응답하지 않으면 get() 호출 스레드(서블릿 스레드)와 commonPool 워커가 영원히 멈춘다. 요청이 쌓이면 톰캣 스레드 풀이 고갈된다.
사용자가 cancel 해도 요청과 commonPool 워커는 응답까지 계속 점유된다(callerCancelBreaksOtherCallers: abortedCount 0, responseCount 1).
→ builder 로 타임아웃을 받고, Java 8 이면 ScheduledExecutorService 기반 타이머(완료 시 cancel + removeOnCancel), 타임아웃·취소 시 request.abort().
```

```
[L6][R8] L57-58, L43 예외가 세 겹으로 감싸진다. fetch 가 ExecutionException 을 RuntimeException 으로 감싸고, supplyAsync 가 다시
CompletionException 으로 감싼다. 또한 catch (Exception) 이 InterruptedException 을 삼키고 interrupt 상태를 복원하지 않는다.
증상: get("k") 의 사용자는 CompletionException → RuntimeException → ExecutionException → IOException 을 벗겨야 원인을 안다
(syncGet_leaksThreeLayersOfWrapping). ex instanceof IOException 같은 판별은 모두 실패한다(#10). 종료 중 interrupt 가 사라져 스레드가 멈추지 않는다.
→ 도메인 예외 계층(unchecked) 하나로 감싸지 않고 completeExceptionally. sync API 는 그 예외를 그대로 던진다. InterruptedException 은 상태 복원.
```

```
[L2][#18] L25-27, L32-34, L39-41 Javadoc 에 콜백이 실행될 스레드, 예외 타입, 타임아웃, 캐시 의미가 하나도 없다.
증상: getAsync 결과에 붙인 콜백은 진행 중이면 commonPool 워커에서, 이미 캐시된 완료 future 면 호출자 스레드에서 실행된다(R1, 비결정적).
사용자는 이것을 모르고 콜백에서 JDBC 를 호출하거나 MDC 를 기대한다(R12). 예외 타입을 몰라 unwrap 코드를 서비스마다 다르게 짠다.
→ 완료 스레드 정책을 정하고(I/O 스레드 또는 completion executor 옵션) Javadoc 에 "어느 스레드에서 완료되는가, blocking 금지, 예외 계약,
   취소 의미"를 쓴다(MiniKvClient Javadoc 참고).
```

```
[L4][R11] L42-43 get = getAsync(key).join(). 지금은 commonPool 위에서 fetch 가 돌기 때문에, 사용자가 commonPool 작업(parallel stream,
executor 생략 supplyAsync) 안에서 get() 을 부르면 같은 풀 안에서 기다리게 된다(commonPool 은 보상 스레드를 만들어 버티기도 하지만 상한이 있다). 나중에 위 지적대로 I/O 스레드 완료로 고치면, 이번에는
I/O 스레드 위의 콜백에서 get() 을 부를 때 deadlock 이 된다.
→ sync API 는 라이브러리 스레드에서 부르면 즉시 IllegalStateException(Netty BlockingOperationException 과 같은 방어),
   그 외에는 내부 raw future 를 타임아웃과 함께 기다리고 도메인 예외를 그대로 던진다(minikv E7).
```

### 최소 수정안 (공개 API)

```java
/** 스레드 정책, 예외 정책, 취소 의미를 Javadoc 에 명시 (MiniKvClient 참고) */
public final class KvClient implements AutoCloseable {
    public static Builder builder(Transport transport);           // timeout(기본 1s), completionExecutor(선택), timer(선택, 없으면 소유 + close 시 종료)
    public CompletionStage<String> getAsync(String key);          // 호출마다 새 stage, 즉시 반환, 취소 → 요청 abort, 실패 = KvException 계열(unwrapped)
    public CompletionStage<String> getSharedAsync(String key);    // single-flight. 완료 시 제거, 호출자별 독립 stage, 한 호출자의 취소는 공유 요청을 끊지 않음
    public String get(String key);                                // 라이브러리 스레드에서 호출 시 IllegalStateException, KvException 을 그대로 던짐
    public void close();
}
```

빌드: `maven-compiler-plugin`에 `<release>8</release>`(지원 최저 버전, L8).
<!-- ANSWER-END -->
