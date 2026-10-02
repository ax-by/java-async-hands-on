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

(여기에 작성)

## 3. "왜 이렇게 설계했나" 질문

1. 왜 드라이버 4는 `CompletableFuture`가 아니라 `CompletionStage`를 반환할까? (드라이버 3은 Guava `ListenableFuture`였다.)
2. 왜 sync 호출 금지를 **스레드 이름**이 아니라 **스레드 타입**으로 판별할까?
3. 기본 throttler(읽기 과제 11)는 무엇을 하는가? 왜 그것을 기본으로 골랐을까? 동시성 제한 throttler를 켜면 무엇이 좋아지고, 애플리케이션은 무엇을 처리해야 하는가?
4. 왜 `fetchNextPage()`는 자동으로 다음 페이지를 미리 받아 두지 않을까(sync `ResultSet` 순회와 비교)? 비동기 페이징의 backpressure를 누가 통제하는가?
5. D1의 결과를 바탕으로: 재귀 페이징의 위험을 드라이버가 막아 줄 수 있었을까? 사용자 쪽 대안 두 가지를 비교하라(트램펄린 vs `thenComposeAsync(.., executor)`).

(여기에 작성)

## 4. 기여 시뮬레이션: E8 `AsyncPaging.fetchAll`

사내 DAO 라이브러리에 "모든 페이지 모으기"를 추가하는 PR. 요구사항은 `AsyncPaging` Javadoc에 있다. 시그니처는 `AsyncResultSet`에도 그대로 쓸 수 있다: `fetchAll(session.executeAsync(stmt))`.

리뷰어 질문에 대비하라: "결과가 100만 행이면?" → 이 API는 전부 메모리에 모은다. 큰 결과에는 페이지 단위 처리(`forEachPage(stage, consumer)`)가 맞다. PR 설명에 한계를 적는다.
