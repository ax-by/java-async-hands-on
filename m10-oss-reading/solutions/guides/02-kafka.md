# 2장. Kafka clients 4.3.1: CF를 숨긴 래퍼(KafkaFuture)와 CF가 아닌 Future(Producer)

> 예측: K1~K6, Q_KAFKA_* · 구현: E3, E4 · 규칙: O1, O3, R4, R5, R8, R10
> 소스 루트: `kafka-clients-4.3.1-sources.jar`. 경로는 `org/apache/kafka/` 기준이다.

## 0. 이 장에서 답할 질문

- Admin API는 왜 `CompletableFuture`가 아니라 `KafkaFuture`를 돌려줄까? `toCompletionStage()`(3.0+)로 꺼낸 것은 평범한 CF처럼 쓸 수 있는가?
- AdminClient 결과는 어느 스레드가 완료시키는가?
- `KafkaProducer.send()`는 "비동기"인가? 언제 호출 스레드를 막는가? 콜백은 어디서 실행되는가?

배경: Kafka는 AdminClient를 도입할 때(0.11, KIP-117) Java 7 사용자까지 고려해 `KafkaFuture`라는 자체 future를 공개 API로 만들었다. 3.0(KIP-707)에서 `toCompletionStage()`로 표준 타입을 열어 주었다.

## 1. 읽기 과제

### A. KafkaFuture (Admin 결과 타입)

| # | 위치 | 찾을 것 |
|---|---|---|
| 1 | `common/KafkaFuture.java:30-40` (클래스 Javadoc) | KafkaFuture와 CompletionStage는 실패를 어떤 예외로 보여 주는가? |
| 2 | `common/KafkaFuture.java:92-110` (`toCompletionStage`, `@since Kafka 3.0`) | 반환된 stage로 무엇은 되고 무엇은 안 된다고 문서화했는가? → K2 |
| 3 | `common/KafkaFuture.java:115-116, 136-137` | `thenApply`/`whenComplete` 함수는 어느 스레드에서 실행될 수 있다고 말하는가? R1과 비교 |
| 4 | `common/KafkaFuture.java:72-90` (`allOf`) | 내부적으로 무엇에 위임하는가? 실패 시 모든 입력을 기다리는가? (E4와 비교) |
| 5 | `common/internals/KafkaFutureImpl.java:34, 47-50` | 내부 필드의 타입은? `toCompletionStage()`는 새 객체를 만드는가? → K4 |
| 6 | `common/internals/KafkaFutureImpl.java:56-75, 93-107` (`thenApply`, `whenComplete`) | 내부 CF의 무엇에 위임하는가? 사용자 함수가 `CompletionException`을 던지면? 의존 단계의 콜백이 받는 예외는? → K1 |
| 7 | `common/internals/KafkaFutureImpl.java:129-132, 152-181` (`cancel`, `get`) | cancel은 어디로 가는가? get은 어떤 JDK 버그를 우회하는가? |
| 8 | `common/internals/KafkaCompletableFuture.java:24-29` (클래스 Javadoc) | 이 클래스가 존재하는 이유는? |
| 9 | `common/internals/KafkaCompletableFuture.java:52-94` | 어떤 메서드를 오버라이드했고 무엇을 던지는가? 내부 완료는 어떤 메서드로 하는가(38-40, 48-50행)? → K2 |
| 10 | `common/internals/KafkaCompletableFuture.java:72-75` (`newIncompleteFuture`) + JDK `java/util/concurrent/CompletableFuture.java`의 `orTimeout` → `Timeout.run` (JDK 25 소스 2818-2830행) | 의존 단계도 이 타입인가? `orTimeout`은 오버라이드되지 않았는데, 타임아웃이 발화할 때 JDK가 호출하는 메서드는 무엇이고, 그 메서드는 이 클래스에서 어떻게 되어 있는가? → K3 |

### B. AdminClient (누가 KafkaFuture를 완료시키는가)

| # | 위치 | 찾을 것 |
|---|---|---|
| 11 | `clients/admin/KafkaAdminClient.java:345, 622-623, 641` | admin 스레드 이름은 어떻게 만들어지는가? → K5 |
| 12 | `clients/admin/KafkaAdminClient.java:2114-2145` (`listTopics`) | 전형적인 호출: future 생성 → Call 등록 → 어디서 complete/completeExceptionally? |
| 13 | `clients/admin/KafkaAdminClient.java:1357-1406` (`handleResponses`), `1469` (run 루프) | `handleResponse`는 어느 스레드의 루프에서 호출되는가? (O1) |
| 14 | `clients/admin/KafkaAdminClient.java:966-975, 1055-1062` | 기한을 넘기면 어떤 예외로, 어느 스레드에서 실패시키는가? |

### C. Producer (CF가 아닌 이유)

| # | 위치 | 찾을 것 |
|---|---|---|
| 15 | `clients/producer/KafkaProducer.java:975-1080` (`doSend`), 특히 988행 | `send()` 안에서 호출 스레드를 막을 수 있는 지점 두 곳과 그 상한(설정 이름)은? → K6 |
| 16 | `clients/producer/KafkaProducer.java:1049-1061` | `ApiException`이 나면 콜백을 어디서 호출하고 무엇을 반환하는가? 메타데이터 타임아웃 예외의 상속 관계는? → K6 |
| 17 | `clients/producer/KafkaProducer.java:245, 466-469` | I/O 스레드 이름은? → Q_KAFKA_PRODUCER_IO_THREAD |
| 18 | `clients/producer/internals/ProducerBatch.java:297-324` + `internals/Sender.java:757-768, 835-` | 정상 경로에서 콜백은 누가 호출하는가? 콜백이 예외를 던지면? |
| 19 | `clients/producer/Callback.java:18-21`, `KafkaProducer.java:902, 942-945` | 콜백 실행 스레드와 순서 보장에 대해 문서는 무엇을 말하는가? 보호 장치(O4)는 있는가? |
| 20 | `clients/producer/internals/FutureRecordMetadata.java:60-66` + `ProduceRequestResult.java:38, 78-82, 109-120` | `get()`은 결국 무엇을 기다리는가? → Q_KAFKA_RECORD_METADATA_WAIT. 조합 API가 있는가? |

## 2. 파이프라인 지도

README 양식을 Admin과 Producer에 대해 각각 채운다. Producer는 "send() 안에서 막히는 곳"을 반드시 표시한다.

<!-- ANSWER-BEGIN -->
**요약**: 내부는 CompletableFuture지만 공개 API는 CF에 묶이지 않게 했고, 3.0에서 `toCompletionStage()`로 내부 CF를 그대로 열어 주면서도 **사용자가 완료시키는 것만은 막았다**(`KafkaCompletableFuture`). 그 막는 방식 때문에 `completeOnTimeout`은 즉시 예외를 던지고, `orTimeout`은 **조용히 무력화**된다. Producer는 아예 CF가 아니라 latch 기반 `Future` + I/O 스레드 콜백이고, `send()`는 메타데이터·버퍼를 기다리며 막을 수 있다.

```
[Admin]
 caller: admin.describeCluster()  ── new KafkaFutureImpl (내부 KafkaCompletableFuture) ── 즉시 반환 (R3)
                                         │
 kafka-admin-client-thread | adminclient-1 (단일 스레드 루프)
   handleResponses → call.handleResponse → future.complete(v)          ─► 미완료 시 등록된 콜백 실행 (O1)
   timeout(default.api.timeout.ms)      → call.fail → completeExceptionally(TimeoutException)
 caller: future.toCompletionStage()  = 같은 KafkaCompletableFuture
           .toCompletableFuture().complete(x)   → UnsupportedOperationException (K2)
           .orTimeout(..)                        → 발화 안 함 (K3)
           .cancel(..)                           → 원본 KafkaFuture 취소 (K4)

[Producer]
 caller: send(record, cb)
   ├ interceptors.onSend               (caller)
   ├ waitOnMetadata ≤ max.block.ms     (caller BLOCKING)  ─ 실패 시 cb(caller) + FutureFailure (K6)
   └ accumulator.append ≤ 남은 시간    (caller, 버퍼 부족 시 BLOCKING)
 kafka-producer-network-thread | producer-1
   Sender.run → 응답 → ProducerBatch.completeFutureAndFireCallbacks → cb(metadata, ex)   (I/O 스레드, O1)
   → 여기서 blocking 하면 모든 send 의 전송/ack 처리 지연
 FutureRecordMetadata.get()  = CountDownLatch.await (caller BLOCKING, 조합 불가)
```

**실험으로 확인한 사실**

- 원본 실패 → `thenApply`로 만든 KafkaFuture의 `whenComplete`는 `CompletionException`을 받는다(CF와 같다). 원본에 직접 붙인 `whenComplete`는 원인 그대로. (K1)
- `toCompletionStage().toCompletableFuture()`는 같은 객체를 돌려주고, `complete`는 `UnsupportedOperationException: User code should not complete futures returned from Kafka clients`. (K2)
- 그 stage(및 `thenApply` 의존 단계)에 `orTimeout(100ms)`를 걸어도 300ms 뒤 `isDone() == false`. JDK 21.0.11에서도 같다. JDK의 타임아웃 작업(`Timeout.run`)이 public `completeExceptionally`를 호출하는데 그것이 예외를 던지고, 그 예외는 스케줄러 스레드에서 삼켜진다. (K3)
- 그 CF를 `cancel(true)`하면 원본 KafkaFuture가 취소된다(같은 객체이므로). (K4)
- 브로커 없는 Admin: `describeCluster()` 반환 ~1ms, 500ms 뒤 `kafka-admin-client-thread | adminclient-1`에서 TimeoutException으로 완료. (K5)
- 브로커 없는 Producer(`max.block.ms=300`): `send()`가 **~300ms 동안 호출 스레드를 막고**, 콜백은 **호출 스레드**에서 `TimeoutException: Topic ... not present in metadata after 300 ms`로 실행. 반환된 Future는 이미 완료된 `FutureFailure`. (K6)
<!-- ANSWER-END -->

## 3. "왜 이렇게 설계했나" 질문

1. 왜 Admin API는 `CompletableFuture`가 아니라 `KafkaFuture`를 반환했을까? (지원하던 Java 버전과 "공개 API에 JDK 타입을 노출하는 것"의 장단점을 생각하라.)
2. 3.0에서 `toCompletionStage()`를 추가하면서 왜 `KafkaCompletableFuture`라는 서브클래스로 complete 계열을 막았을까? 막지 않았다면 어떤 사고가 가능한가?
3. K3의 결과는 의도된 것일까, 부작용일까? Kafka에 이슈를 낸다면 어떤 수정안을 제안하겠는가?
4. Producer는 왜 CF가 아니라 `Future` + `Callback`인가? 성능(레코드당 할당), 배치 단위 완료, Java 버전을 생각하라.
5. Producer 콜백을 별도 executor로 넘기지 않은 이유는? 그 선택의 비용은 누가 지는가?
6. `send()`가 막을 수 있다는 것은 "비동기 API" 설계로서 옳은가? 웹 요청 스레드나 이벤트 루프에서 `send()`를 호출할 때 무엇을 설정·설계해야 하는가?

<!-- ANSWER-BEGIN -->
**모범 답안**

1. 공개 API에 CF를 노출하면 사용자가 `complete`/`obtrude`로 내부 상태를 오염시킬 수 있고(review-checklist #17), 구현을 바꿀 자유를 잃는다. 또 KafkaFuture가 만들어질 당시 클라이언트는 Java 7 사용자도 고려해야 했다(초기 KafkaFutureImpl은 CF 없이 자체 구현). 래퍼 덕분에 나중에 내부를 CF로 바꿀 수 있었다. 대가는 생태계 호환성(다른 CF 기반 코드와 조합하려면 브리지가 필요)이다.
2. `toCompletionStage()`는 내부 CF를 그대로 노출하는 가장 싼 방법이다(복사본을 만들면 할당이 늘고, 취소 의미도 달라진다). 대신 사용자가 `complete(null)`로 Admin 결과를 위조하는 사고를 막기 위해 complete 계열만 막았다. `newIncompleteFuture`까지 오버라이드해 의존 단계도 보호했다.
3. 부작용으로 보는 것이 합리적이다. Javadoc은 "complete 계열이 예외"라고만 말하고 `orTimeout`은 언급하지 않는다. JDK `orTimeout`이 public `completeExceptionally`를 쓰는 구현 세부에 의존한 결과다. 수정안: `orTimeout`도 명시적으로 예외를 던지게 해 "조용한 실패"를 "즉시 실패"로 바꾸거나(fail fast), 문서에 "타임아웃은 `KafkaFuture.get(timeout)`이나 자신의 CF로 옮긴 뒤 걸라"를 추가한다. 사용자 쪽 해결책이 E3(평범한 CF로 옮기기)다.
4. 레코드마다 CF를 만들면 할당과 콜백 체인 비용이 크다. Producer는 배치(ProducerBatch) 단위로 응답이 오고, 배치 하나가 레코드 N개의 결과를 한 번에 확정한다. latch 하나(`ProduceRequestResult`)를 공유하고 레코드별로는 가벼운 `FutureRecordMetadata`만 만든다. 또 이 API는 Java 7 시대에 설계되었다.
5. executor로 넘기면 레코드마다 작업 제출 비용이 들고, 순서 보장("같은 파티션 레코드의 콜백은 순서대로 실행된다", KafkaProducer.java:902)이 깨질 수 있다. 대신 "콜백은 빠르게, 무거우면 직접 executor로 넘겨라"는 책임을 문서로 사용자에게 넘겼다. 비용은 사용자가 진다: 콜백에서 DB를 부르면 그 producer를 공유하는 모든 스레드의 전송이 늦어진다.
6. "완전 비동기"는 아니다. 메타데이터가 없거나(첫 전송, 브로커 장애) 버퍼가 가득 차면 막는다. 요청 스레드에서 호출한다면 `max.block.ms`를 요청 타임아웃 예산 안으로 줄이고, 이벤트 루프(WebFlux/Netty)에서는 `send()` 자체를 전용 executor로 옮긴다(`supplyAsync(() -> producer.send(..), kafkaSendPool)`). 애플리케이션 시작 시 `partitionsFor(topic)`로 메타데이터를 미리 받아 두는 것도 방법이다. 메타데이터 실패 시 콜백이 **호출 스레드에서** 실행된다는 점도 콜백 작성자가 알아야 한다(MDC 등 R12).
<!-- ANSWER-END -->

## 4. 기여 시뮬레이션

- **E3 `KafkaFutures.toCompletableFuture`**: Kafka 2.x도 지원해야 하는 사내 라이브러리. `toCompletionStage()`를 쓰지 않고(`whenComplete` 기반) 평범한 CF로 옮긴다. 원인 예외를 벗기고(R8), CF 쪽 취소를 원본으로 전달한다(R10은 "자동으로는 전파되지 않는다"이므로 **직접 연결**한다).
- **E4 `KafkaFutures.allOf`**: `DescribeTopicsResult.topicNameValues()` 같은 키별 맵을 fail-fast로 모은다. Kafka의 `KafkaFuture.allOf`(읽기 과제 4)와 무엇이 다른지 PR 설명에 적는다.
