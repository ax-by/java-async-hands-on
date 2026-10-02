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

(여기에 작성)
