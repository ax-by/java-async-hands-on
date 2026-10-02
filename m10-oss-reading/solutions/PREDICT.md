# m10 예측 시트

각 장의 가이드(`guides/0N-*.md`)의 읽기 과제를 먼저 하고, `*Scenarios.java`를 읽은 뒤 **실행하기 전에** 채운다.
"근거" 칸에는 규칙 번호(R/O)와 **소스 위치(파일:줄)**를 함께 적는다. 소스 위치가 비어 있으면 읽은 것이 아니라 찍은 것이다.

## 1장 Caffeine

| 항목 | 예측 | 근거 (규칙 + 파일:줄) | 결과 | 틀렸다면 무엇을 놓쳤나 |
|---|---|---|---|---|
| C1 loaderCalls | | | | |
| C1 sameInstance | | | | |
| C2 loader | | | | |
| C2 whileLoading | | | | |
| C3 mappingFn | | | | |
| C4 presentAfterFailure | | | | |
| C4 loaderCalls | | | | |
| C5 otherCaller | | | | |
| C6 getIfPresentMs | | | | |
| C6 getMs | | | | |
| Q removalMethod | | | | |
| Q listenerOnFailure | | | | |

## 2장 Kafka

| 항목 | 예측 | 근거 | 결과 | 틀렸다면 |
|---|---|---|---|---|
| K1 afterThenApply | | | | |
| K2 completeOnStage | | | | |
| K3 orTimeoutFired | | | | |
| K4 originalCancelled | | | | |
| K5 callback | | | | |
| K6 sendReturnedMs | | | | |
| K6 callback | | | | |
| Q producerIoThread | | | | |
| Q recordMetadataWait | | | | |

## 3장 Netty

| 항목 | 예측 | 근거 | 결과 | 틀렸다면 |
|---|---|---|---|---|
| N1 listener | | | | |
| N2 listener | | | | |
| N3 listener | | | | |
| N4 syncInLoop | | | | |
| Q deadlockCheck | | | | |
| Q mayInterrupt | | | | |

## 4장 Lettuce

| 항목 | 예측 | 근거 | 결과 | 틀렸다면 |
|---|---|---|---|---|
| L1 callback | | | | |
| L2 sameConnectionMs | | | | |
| L3 syncInCallback | | | | |
| L4 async | | | | |
| L4 reactive | | | | |
| Q asyncCommandSuper | | | | |
| Q publishOnReader | | | | |

## 5장 AWS SDK v2

| 항목 | 예측 | 근거 | 결과 | 틀렸다면 |
|---|---|---|---|---|
| A1 callback | | | | |
| A2 callback | | | | |
| A3 cause | | | | |
| A3 callback | | | | |
| A4 attempts | | | | |
| Q queueCapacity | | | | |
| Q dynamoDefaultAttempts | | | | |

## 6장 DataStax 드라이버

| 항목 | 예측 | 근거 | 결과 | 틀렸다면 |
|---|---|---|---|---|
| D1 immediate | | | | |
| D1 async | | | | |
| Q ioThread | | | | |
| Q syncOnDriverThread | | | | |
| Q defaultThrottler | | | | |
| Q throttled | | | | |

---

## 채점 후 서술 문제 (journal에 답한다)

1. **비교표 완성**: 6개 라이브러리에 대해 "사용자 future를 완료시키는 스레드 / 그 스레드에서 blocking하면 무엇이 멈추는가 / 라이브러리의 보호 장치(O4) / 사용자가 해야 할 일"을 한 표로 정리하라.
2. **같은 실수, 다른 증상**: "응답 콜백 안에서 같은 클라이언트의 동기 API를 호출한다"는 실수가 Netty(N4), Lettuce(L3), DataStax(Q syncOnDriverThread), AWS(A2 설정 시)에서 각각 어떤 증상으로 나타나는가? 가장 발견하기 어려운 것은 무엇이고 왜인가?
3. **O2 일반화**: Caffeine 외에 "같은 future 인스턴스를 여러 호출자에게 돌려주는" 패턴을 쓰는 코드를 실무에서 하나 떠올려라(예: 직접 만든 요청 병합(request coalescing), `ConcurrentHashMap<K, CompletableFuture<V>>` 캐시). 그 코드의 사용자가 지켜야 할 규칙을 API 문서 한 문단으로 써라.
4. **K2~K4 이슈 작성**: K2~K4에서 관찰한 결과 중 사용자가 알아채기 가장 어려운 동작 하나를 골라 Kafka에 이슈로 보고한다고 하자. 재현 코드(10줄 이내), 기대 동작, 실제 동작, 원인(소스 위치), 제안 수정안 두 가지를 적어라.
5. **설계 선택 평가**: Lettuce와 AWS SDK의 완료 방식(L1, A1에서 확인한 것) 중, 당신 팀의 새 내부 비동기 클라이언트 라이브러리는 어느 쪽을 따라야 하는가? 팀의 상황(사용자 대부분이 Spring MVC + JPA)을 근거로 판단하라.
6. **페이징 리뷰**: 아래 코드를 리뷰하라. 어떤 조건에서 문제가 되며(두 가지), 어떻게 고치는가?
   ```java
   CompletionStage<Void> exportAll(CompletionStage<AsyncResultSet> stage, Writer out) {
       return stage.thenCompose(rs -> {
           for (Row row : rs.currentPage()) out.write(toCsv(row));          // 파일 I/O
           return rs.hasMorePages() ? exportAll(rs.fetchNextPage(), out) : CompletableFuture.completedFuture(null);
       });
   }
   ```

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

| 항목 | 답 | 근거 |
|---|---|---|
| C1 | 1 / true | O2. LocalAsyncCache.java:91-107 computeIfAbsent가 맵에 저장된 future 자체를 반환 |
| C2 | commonPool / commonPool | Caffeine.java:373-375 기본 executor, LocalAsyncCache.java:78-83 supplyAsync. 콜백은 R1로 완료시킨 commonPool |
| C3 | caller | LocalAsyncCache.java:96-101 BiFunction은 computeIfAbsent 안에서 호출 스레드가 실행 |
| C4 | false / 2 | LocalAsyncCache.java:202-213 handleCompletion이 실패 future 제거 |
| C5 | TimeoutException | O2 + R10. orTimeout이 공유 future 자체를 실패시키고, 제거까지 된다 |
| C6 | 0 / 200 | LocalAsyncCache.java:575-579 getIfReady(진행 중이면 null), 609-612·623-638 resolve → join |
| Q | handleCompletion / no | LocalAsyncCache.java:202-242, Async.java:85-95 (`value != null`일 때만 통지) |
| K1 | CompletionException | R8. KafkaFutureImpl.java:56-75, 93-107은 내부 CF에 위임하므로 의존 단계는 감싼 예외를 본다 |
| K2 | UnsupportedOperationException | KafkaCompletableFuture.java:52-55, 92-94 |
| K3 | false | KafkaCompletableFuture는 orTimeout을 막지 않지만 JDK orTimeout이 내부에서 호출하는 completeExceptionally가 예외를 던져 발화가 무력화된다 |
| K4 | true | KafkaFutureImpl.java:47-50 같은 인스턴스, cancel은 오버라이드되지 않음 |
| K5 | kafka-admin-client-thread \| adminclient | KafkaAdminClient.java:622-623, 966-975 |
| K6 | 300 / caller | KafkaProducer.java:988 waitOnMetadata(max.block.ms), 1049-1061 ApiException 경로의 콜백은 호출 스레드 |
| Q | kafka-producer-network-thread / CountDownLatch | KafkaProducer.java:245, 466-469 / ProduceRequestResult.java:38 |
| N1, N2 | multiThreadIoEventLoopGroup | DefaultPromise.java:498-520 루프 밖이면 루프에 제출, 190-202 이미 완료여도 notifyListeners |
| N3 | completer | ImmediateEventExecutor.java:60-68 inEventLoop 항상 true → 완료시킨 스레드에서 즉시 |
| N4 | BlockingOperationException | DefaultPromise.java:474-479 checkDeadLock |
| Q | checkDeadLock / no | DefaultPromise.java:474-479 / 391-406 |
| L1 | lettuce-nioEventLoop | CommandHandler.java:578-615, 692 디코딩 루프에서 complete |
| L2 | 300 | R4: 디코딩 루프가 콜백에 갇혀 다음 응답을 완료하지 못한다 |
| L3 | RedisCommandTimeoutException | 응답을 디코딩할 루프가 대기 중인 콜백 자신 → 명령 타임아웃까지 교착 |
| L4 | lettuce-nioEventLoop / lettuce-eventExecutorLoop | AbstractRedisReactiveCommands.java:166-180만 옵션을 읽는다 |
| Q | CompletableFuture / AbstractRedisReactiveCommands | AsyncCommand.java:47-48 / 위 |
| A1 | sdk-async-response | MakeAsyncHttpRequestStage.java:170-175 handleAsync(.., futureCompletionExecutor) |
| A2 | aws-java-sdk-NettyEventLoop | Runnable::run = 넘기는 스레드(I/O)에서 실행 (R3 예외 1) |
| A3 | ApiCallTimeoutException / sdk-ScheduledExecutor | AsyncTimeoutTask.java:47-52 타이머 스레드가 completeExceptionally |
| A4 | 3 | DefaultRetryStrategy.java:125 Standard.MAX_ATTEMPTS = 3 |
| Q | 1000 / 9 | SdkDefaultClientBuilder.java:536-537 / DynamoDbRetryPolicy.java:50 |
| D1 | StackOverflowError / rows=10000 | R1: 완료된 future의 thenCompose 함수는 등록 스레드에서 즉시 실행 → 재귀 깊이 = 페이지 수 |
| Q | s0-io / IllegalStateException / PassThroughRequestThrottler / RequestThrottlingException | DefaultNettyOptions.java:83, BlockingOperation.java:45-51, reference.conf:965, ConcurrencyLimitingRequestThrottler.java:126-135, 228-229 |

**서술 1**

| 라이브러리 | 완료 스레드 | blocking 시 멈추는 것 | 보호 장치 | 사용자 할 일 |
|---|---|---|---|---|
| Caffeine | Caffeine.executor(기본 commonPool) | commonPool 전체(병렬 스트림, 다른 라이브러리) | 없음 | blocking 로더면 executor 지정, 공유 future 변경 금지 |
| Kafka Admin | admin 스레드(클라이언트당 1개) | 그 Admin의 모든 요청 처리 | 없음 | 무거운 후속 처리는 Async로 이동, 타임아웃은 자신의 CF에 |
| Kafka Producer | producer I/O 스레드(또는 호출 스레드) | 그 producer를 공유하는 모든 전송 | 문서 경고뿐 | 콜백은 가볍게, 무거우면 executor로. send()의 max.block.ms 관리 |
| Lettuce | lettuce 이벤트 루프 | 그 루프의 모든 연결 | 없음(async) | thenXxxAsync(.., 앱 풀) |
| AWS SDK | sdk-async-response(정상), sdk-ScheduledExecutor(타임아웃), 이벤트 루프(포화 시) | 정상 경로는 클라이언트 completion 풀. 타임아웃 경로는 공유 타이머 | completion executor | Runnable::run 금지, 무거운 단계는 Async로 |
| DataStax | s0-io | 그 io 루프의 모든 연결 | sync 호출 즉시 거부(InternalThread) | 콜백에서 blocking 금지, 재귀 페이징 스택 주의 |

**서술 2**: Netty는 즉시 `BlockingOperationException`(빠르고 명확). DataStax는 즉시 `IllegalStateException`(명확). Lettuce는 **명령 타임아웃(기본 60초)까지 그 루프의 모든 연결이 멈춘 뒤** `RedisCommandTimeoutException`. 원인이 "Redis가 느리다"처럼 보이므로 가장 발견하기 어렵다. AWS는 `Runnable::run`일 때 이벤트 루프에서 SDK를 다시 호출하게 되어 Javadoc 경고대로 교착 가능성이 있고, 기본 설정에서는 completion 풀 스레드를 점유할 뿐이다.

**서술 3**: 예시 문서: "이 메서드가 돌려주는 future는 같은 키의 다른 호출자와 **공유**된다. 반환된 future에 `cancel`, `complete`, `orTimeout`, `completeOnTimeout`, `obtrude*`를 호출하지 말라. 호출자별 타임아웃·취소가 필요하면 `future.copy()`(Java 9+) 또는 `future.thenApply(Function.identity())`로 자신의 의존 future를 만든 뒤 그것에 적용하라. 의존 future의 취소·타임아웃은 공유 future에 전파되지 않는다."

**서술 4**: 재현: `KafkaFutureImpl<String> f = new KafkaFutureImpl<>(); CompletableFuture<String> cf = f.toCompletionStage().toCompletableFuture().orTimeout(100, MILLISECONDS); Thread.sleep(300); assert cf.isDone();` 기대: TimeoutException으로 완료. 실제: 미완료(영원히). 원인: KafkaCompletableFuture.java:57-60이 `completeExceptionally`를 막는데, JDK `orTimeout`의 타임아웃 작업이 그 public 메서드를 호출한다. 수정안: ① `orTimeout`도 오버라이드해 즉시 `UnsupportedOperationException`(조용한 실패 제거) ② `orTimeout`을 오버라이드해 내부 완료 경로(`kafkaCompleteExceptionally`)로 타임아웃을 구현(지원). ②는 "사용자가 결과를 바꿀 수 없다"는 원칙과 충돌하는지 논의가 필요하다.

**서술 5**: 사용자 대부분이 blocking 코드(JPA)를 콜백에 섞을 가능성이 높으므로 **AWS 방식(전용 completion executor, 교체 가능)**이 맞다. Lettuce 방식은 사용자가 R1/R4를 정확히 이해한다는 전제가 필요하다. 다만 executor를 노출하고 기본값의 크기·큐·포화 시 동작(AWS의 I/O 스레드 폴백 같은 숨은 동작은 피하고, 메트릭과 함께 문서화)을 명시한다.

**서술 6**: ① 콜백이 `s0-io` 스레드에서 실행되면(페이지가 나중에 도착) 파일 I/O가 드라이버 I/O 스레드를 막는다(O1, R4). ② 페이지 future가 이미 완료된 상태로 오면 재귀가 같은 스택에서 이어져 StackOverflowError가 난다(D1). 고치는 법: 파일 쓰기를 전용 executor로 옮기고(`thenComposeAsync(rs -> ..., exportPool)`: 실행 위치 고정과 스택 비우기를 한 번에 해결), 또는 E8처럼 트램펄린으로 페이지를 돌면서 쓰기만 `exportPool`로 넘긴다. `Writer`는 스레드 안전하지 않으므로 한 번에 한 페이지만 쓰도록 순서를 유지해야 한다(재귀 구조가 이를 보장한다).
<!-- ANSWER-END -->
