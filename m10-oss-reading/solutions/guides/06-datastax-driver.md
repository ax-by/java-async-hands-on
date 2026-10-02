# 6장. Apache Cassandra Java Driver 4.19.3 (DataStax 4.x): CompletionStage API와 비동기 페이징

> 예측: D1, Q_CASSANDRA_* · 구현: E8 · 규칙: O1, O4, R1, R5, R13
> 소스 루트: `java-driver-core-4.19.3-sources.jar`. 경로는 `com/datastax/oss/driver/` 기준이다. `reference.conf`는 jar 루트에 있다.
> Cassandra가 없으므로 실행 실험은 `FakeAsyncPage`(가짜 `AsyncPagingIterable`)로 하고, 드라이버 내부는 소스 읽기 퀴즈로 확인한다.

## 0. 이 장에서 답할 질문

- `executeAsync`는 무엇을 반환하고, 누가 완료시키는가?
- 콜백(드라이버 스레드) 안에서 동기 API(`session.execute`)를 부르면 어떻게 되는가? Lettuce(4장 L3)와 비교하라.
- 동시 요청이 폭주하면 드라이버는 무엇으로 막는가? 막을 때 스레드를 기다리게 하는가?
- `fetchNextPage()`를 재귀로 잇는 코드는 언제나 안전한가?

## 1. 읽기 과제

| # | 위치 | 찾을 것 |
|---|---|---|
| 1 | `api/core/cql/AsyncCqlSession.java:34-45` | 반환 타입과 "언제 반환되는가"에 대한 Javadoc |
| 2 | `api/core/AsyncPagingIterable.java:33-108` | `currentPage()`의 제약(46-54행), `hasMorePages()`, `fetchNextPage()`가 다음 페이지가 없을 때 하는 일(74-81행) |
| 3 | `internal/core/cql/DefaultAsyncResultSet.java:94-110` | `fetchNextPage()`의 실체는 무엇인가? |
| 4 | `internal/core/cql/CqlRequestHandler.java:629-675` (`NodeResponseCallback.onResponse`) → `339-349` (`setFinalResult`) | 사용자 future를 완료시키는 지점과 그 코드가 도는 스레드 (O1) |
| 5 | `internal/core/cql/CqlRequestHandler.java:219-240` | 요청 타임아웃은 무엇이, 어떤 예외로 발화시키는가? 그때 콜백 스레드는? (5장 A3와 비교) |
| 6 | `internal/core/context/DefaultDriverContext.java:258-262` | 세션 이름의 기본값 |
| 7 | `internal/core/context/DefaultNettyOptions.java:79, 83, 86, 91, 99` | 스레드 팩토리 종류와 io/admin/timer 스레드 이름 형식 → Q_CASSANDRA_IO_THREAD |
| 8 | `reference.conf:2273-2301` | io 그룹·admin 그룹 크기 기본값, daemon 여부 |
| 9 | `internal/core/util/concurrent/BlockingOperation.java:24-33, 45-51, 57-68` | "드라이버 스레드"를 어떻게 판별하는가(이름? 타입?) 동기 호출을 감지하면 무엇을 던지는가? → Q_CASSANDRA_SYNC_ON_DRIVER_THREAD |
| 10 | `internal/core/cql/CqlRequestSyncProcessor.java:53-56` | 동기 `execute`는 async API 위에 무엇을 얹은 것인가? |
| 11 | `api/core/session/throttling/RequestThrottler.java:25-37` + `reference.conf:940-977` | 기본 throttler와 대안들 → Q_CASSANDRA_DEFAULT_THROTTLER |
| 12 | `internal/core/session/throttling/ConcurrencyLimitingRequestThrottler.java:84-136, 228-229` | 한도와 대기열이 모두 찼을 때 요청은 어떻게 되는가? → Q_CASSANDRA_THROTTLED |
| 13 | `api/core/retry/RetryPolicy.java:46-50`, `api/core/type/codec/TypeCodec.java:40` | 사용자 확장점(재시도 정책, 코덱)에 대해 스레드와 blocking에 관해 무엇을 요구하는가? (O4) |
| 14 | `internal/core/util/concurrent/CompletableFutures.java:43, 143, 185-198` | 드라이버 내부 CF 헬퍼: `completeFrom`, `getUninterruptibly`, `propagateCancellation` |

## 2. 파이프라인 지도

README 양식을 채운다. 페이징은 "다음 페이지 future가 미완료일 때"와 "이미 완료됐을 때" 콜백이 어느 스레드·어느 스택에서 실행되는지를 따로 그린다(R1).

<!-- ANSWER-BEGIN -->
**요약**: 공개 API는 **CompletionStage**(CF가 아님)다. 결과는 I/O 스레드(`s0-io-N`)가 완료시키고(O1), 드라이버는 자기 스레드를 **표식(InternalThread)으로 구분해 sync 호출을 즉시 거부**한다(O4). 폭주는 throttler가 **실패로**(기다리게 하지 않고) 막는다. 남은 함정은 사용자 쪽의 **재귀 페이징**이다(D1).

```
 앱 스레드                          s0-io-N (Netty I/O, InternalThread)        s0-timer-0 (HashedWheelTimer)
 ─────────                          ─────────────────────────────────          ───────────────────────────
 session.executeAsync(stmt)
  └ throttler.register (기본: 즉시 통과)
  └ 반환 CompletionStage S1 ───►    write → ... onResponse → setFinalResult → S1.complete(page1)
                                       └ S1 의 non-async 콜백 (O1) ─ 여기서 session.execute(동기) → IllegalStateException (O4)
                                                                                 timeout → S1.completeExceptionally(DriverTimeoutException)
 page1.fetchNextPage() = executeAsync(stmt + pagingState) → S2 ... (페이지마다 반복)
 재귀 페이징:
   S2 가 미완료 → 콜백은 나중에 s0-io 의 새 스택에서 (스택 일정)
   S2 가 이미 완료 → thenCompose 함수가 "지금 이 스택"에서 즉시 실행 → 다음 페이지도 완료면 또 즉시 ... (스택 증가, D1)
```

**실험으로 확인한 사실 (`FakeAsyncPage`, JDK 25)**

- D1(a): 이미 완료된 페이지 5,000개를 `thenCompose` 재귀로 모으면, 256KB 스택 스레드에서 `StackOverflowError`. CF가 이 Error를 잡아 **future를 실패로 완료**하므로(`CompletionException(StackOverflowError)`), 예외가 호출자에게 던져지지 않고 결과 future에 담긴다. 1,000페이지에서도 같았다.
- D1(b): 같은 코드를 단일 `s0-io` 스레드가 페이지를 나중에 완료하게 바꾸면 `rows=10000`으로 성공. 테스트(작은 결과·실제 네트워크)에서는 (b)만 보이고, 운영에서 페이지가 이미 도착해 있거나 캐시 계층이 즉시 완료된 stage를 돌려주면 (a)가 나타난다.
- 드라이버의 `InternalThread` 검사는 이 모듈의 가짜 `s0-io` 스레드(평범한 Thread)에는 적용되지 않는다. 그래서 E8 테스트는 BlockHound(`s0-io-` 접두사)로 blocking을 감지한다.
<!-- ANSWER-END -->

## 3. "왜 이렇게 설계했나" 질문

1. 왜 드라이버 4는 `CompletableFuture`가 아니라 `CompletionStage`를 반환할까? (드라이버 3은 Guava `ListenableFuture`였다.)
2. 왜 sync 호출 금지를 **스레드 이름**이 아니라 **스레드 타입**으로 판별할까?
3. 기본 throttler(읽기 과제 11)는 무엇을 하는가? 왜 그것을 기본으로 골랐을까? 동시성 제한 throttler를 켜면 무엇이 좋아지고, 애플리케이션은 무엇을 처리해야 하는가?
4. 왜 `fetchNextPage()`는 자동으로 다음 페이지를 미리 받아 두지 않을까(sync `ResultSet` 순회와 비교)? 비동기 페이징의 backpressure를 누가 통제하는가?
5. D1의 결과를 바탕으로: 재귀 페이징의 위험을 드라이버가 막아 줄 수 있었을까? 사용자 쪽 대안 두 가지를 비교하라(트램펄린 vs `thenComposeAsync(.., executor)`).

<!-- ANSWER-BEGIN -->
**모범 답안**

1. 사용자가 `complete`/`obtrude`로 드라이버 결과를 위조하지 못하게 읽기 전용 타입을 노출하고, 내부 구현(CF)을 바꿀 자유를 남긴다. Guava 의존을 공개 API에서 없애 의존성 충돌도 줄였다(Guava는 내부에서 shaded로만 쓴다: `java-driver-guava-shaded`).
2. 스레드 이름은 사용자 설정(세션 이름)에 따라 바뀌고 우연히 겹칠 수 있다. 드라이버가 만든 스레드만 `InternalThread` 서브클래스로 만들면 `instanceof` 한 번으로 정확하고 싸게 판별할 수 있다. `NettyOptions` Javadoc도 사용자 정의 이벤트 루프에 `SafeThreadFactory`를 쓰라고 권한다(`internal/core/context/NettyOptions.java:35-37`).
3. 어떤 한도가 적절한지는 클러스터와 애플리케이션마다 달라 드라이버가 알 수 없다. 그래서 기본은 "제한 없음"(PassThrough)이다. ConcurrencyLimiting을 켜면 폭주 시 Cassandra와 드라이버 내부 큐를 보호하고 지연시간 폭증을 빠른 실패로 바꾼다. 대신 애플리케이션은 `RequestThrottlingException`을 처리해야 한다(재시도, 503 응답, 부하 차단). 대기열이 찼을 때 스레드를 막지 않고 실패시키는 것은 I/O 스레드에서도 호출될 수 있는 API이기 때문이다.
4. 미리 받으면 메모리 사용량과 서버 부하를 드라이버가 결정하게 된다. 비동기 페이징에서는 **사용자가 다음 페이지를 언제 요청할지** 정하므로(현재 페이지 처리가 끝난 뒤 `fetchNextPage`), 소비 속도에 맞춘 자연스러운 backpressure가 된다.
5. 드라이버는 사용자가 stage를 어떻게 이을지 알 수 없으므로 막기 어렵다(JDK CF의 동작이다). 트램펄린(E8 모범답안): 완료된 페이지는 루프로, 미완료 페이지만 콜백으로 처리한다. 스레드 전환이 없고 스택이 일정하다. 대신 코드가 조금 길다. `thenComposeAsync(.., executor)`: 매 페이지마다 executor로 넘겨 스택을 비운다. 코드는 짧지만 페이지마다 스레드 전환 비용이 들고 executor를 하나 더 관리해야 한다. 이 실습은 "새 executor를 만들지 않는다"는 제약으로 트램펄린을 요구한다.
<!-- ANSWER-END -->

## 4. 기여 시뮬레이션: E8 `AsyncPaging.fetchAll`

사내 DAO 라이브러리에 "모든 페이지 모으기"를 추가하는 PR. 요구사항은 `AsyncPaging` Javadoc에 있다. 시그니처는 `AsyncResultSet`에도 그대로 쓸 수 있다: `fetchAll(session.executeAsync(stmt))`.

리뷰어 질문에 대비하라: "결과가 100만 행이면?" → 이 API는 전부 메모리에 모은다. 큰 결과에는 페이지 단위 처리(`forEachPage(stage, consumer)`)가 맞다. PR 설명에 한계를 적는다.
