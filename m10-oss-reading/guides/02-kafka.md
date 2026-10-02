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

(여기에 작성)

## 3. "왜 이렇게 설계했나" 질문

1. 왜 Admin API는 `CompletableFuture`가 아니라 `KafkaFuture`를 반환했을까? (지원하던 Java 버전과 "공개 API에 JDK 타입을 노출하는 것"의 장단점을 생각하라.)
2. 3.0에서 `toCompletionStage()`를 추가하면서 왜 `KafkaCompletableFuture`라는 서브클래스로 complete 계열을 막았을까? 막지 않았다면 어떤 사고가 가능한가?
3. K3의 결과는 의도된 것일까, 부작용일까? Kafka에 이슈를 낸다면 어떤 수정안을 제안하겠는가?
4. Producer는 왜 CF가 아니라 `Future` + `Callback`인가? 성능(레코드당 할당), 배치 단위 완료, Java 버전을 생각하라.
5. Producer 콜백을 별도 executor로 넘기지 않은 이유는? 그 선택의 비용은 누가 지는가?
6. `send()`가 막을 수 있다는 것은 "비동기 API" 설계로서 옳은가? 웹 요청 스레드나 이벤트 루프에서 `send()`를 호출할 때 무엇을 설정·설계해야 하는가?

(여기에 작성)

## 4. 기여 시뮬레이션

- **E3 `KafkaFutures.toCompletableFuture`**: Kafka 2.x도 지원해야 하는 사내 라이브러리. `toCompletionStage()`를 쓰지 않고(`whenComplete` 기반) 평범한 CF로 옮긴다. 원인 예외를 벗기고(R8), CF 쪽 취소를 원본으로 전달한다(R10은 "자동으로는 전파되지 않는다"이므로 **직접 연결**한다).
- **E4 `KafkaFutures.allOf`**: `DescribeTopicsResult.topicNameValues()` 같은 키별 맵을 fail-fast로 모은다. Kafka의 `KafkaFuture.allOf`(읽기 과제 4)와 무엇이 다른지 PR 설명에 적는다.
