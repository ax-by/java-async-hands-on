# P1. 여행 통합 검색 (공급사 SDK 4종)

> 코드: `src/main/java/async/m11/p1/TravelSearchService.java` · 결정: [DECISION.md](DECISION.md) · 채점: `P1GradingTest` (7개)

## 상황

여행 앱의 "항공+호텔+렌터카 한 번에 검색" API 를 새로 만든다. 서비스는 **Spring MVC(서블릿)** 이고,
컨트롤러는 `search(route)` 가 돌려준 `CompletableFuture<SearchResult>` 를 그대로 반환한다(서블릿 async: 요청 스레드는 반환 즉시 풀로 돌아간다).

공급사는 네 곳이고, 각자 제공한 **Java SDK 를 써야 한다**. SDK 내부는 동기 HTTP/SOAP 다.

| 공급사 이름 | SDK 호출 | 평소 응답(p50) | 비고 |
|---|---|---|---|
| `airline-a` | `airlineA.call(route)` | 120ms | |
| `airline-b` | `airlineB.call(route)` | 150ms | 가끔 수 초간 응답이 없다 |
| `hotel` | `hotel.call(route)` | 100ms | 가끔 예외를 던진다 |
| `car` | `car.call(route)` | 80ms | |

- 네 SDK 모두 **호출한 스레드를 응답이 올 때까지 막는다.** 서로 독립적이다(어느 결과도 다른 호출의 입력이 아니다).
- SDK 문서: "호출 중인 스레드가 **interrupt 되면 즉시 요청을 중단**하고 `IllegalStateException` 을 던진다."
- 실습에서는 `FakeBlockingService` 가 SDK 역할을 한다(`call` 은 응답 문자열 `"{공급사}:{route}"` 를 반환).

## 요구사항 (모두 채점된다)

1. **API**: `CompletableFuture<SearchResult> search(String route)`.
   - `flights` = airline-a 와 airline-b 응답을 합쳐 **문자열 오름차순**, `hotels` = hotel 응답(0 또는 1개), `cars` = car 응답(0 또는 1개).
   - `unavailable` = 실패했거나 제한 시간 안에 응답하지 않은 공급사 이름들.
2. **요청 스레드를 막지 않는다**: `search()` 는 30ms 안에 반환한다. SDK 호출은 요청 스레드에서도, commonPool 에서도 실행하지 않는다.
3. **응답시간 SLO 300ms (항상)**: 어떤 공급사가 느리거나 실패해도 결과 future 는 300ms 안에 완료된다. 평소에는 네 호출의 **최댓값** 수준이어야 한다.
4. **공급사별 최대 대기 250ms**: 넘으면 그 공급사는 `unavailable`. 검색 자체는 실패하지 않는다(부분 결과).
5. **실패 격리**: SDK 가 예외를 던지면 그 공급사만 `unavailable`.
6. **자원 회수**: 250ms 를 넘긴 SDK 호출은 **interrupt 로 중단**해서, 응답 직후(300ms 이내) 그 호출이 스레드를 점유하지 않게 한다. 응답 없는 공급사가 계속 있어도 스레드가 쌓이면 안 된다.
7. **피크 용량**: 초당 200건 × 검색당 150ms ≈ **동시에 진행 중인 검색 30건**. 이 상태에서 어떤 공급사도 빠지지 않고 SLO 를 지켜야 한다.
8. **종료**: `close()` 는 이 서비스가 만든 스레드를 모두 정리한다.

설계 한도를 크게 넘는 과부하(동시 30건의 수 배)에서의 정책은 DECISION.md 에 적는다(채점하지 않는다).

## 작성 순서

1. [DECISION.md](DECISION.md) 를 먼저 채운다. 숫자(스레드 수, 시간, 큐)를 근거와 함께 적는다.
2. 코드를 쓴다. 각 람다 옆에 `// [실행 스레드] / [blocking 여부]` 를 단다.
3. 자기 리뷰: [리뷰 체크리스트](../../docs/review-checklist.md) #1~#14 를 하나씩 확인하고, [추적 표](../../docs/reasoning-rules.md#파이프라인-손으로-추적하기-최종-훈련-형식)를 채운다.
