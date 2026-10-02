# m08 예측 시트

`bugs/` 의 각 사례를 **실행하기 전에** 채운다. 먼저 코드 옆(또는 아래 칸)에 람다마다 `[실행 스레드] / [blocking 여부]` 를 적고,
그다음 관측 조건에서 무엇이 측정될지 고른다. "근거"가 비어 있으면 진단한 것이 아니라 찍은 것이다.

- 증상: `Symptom` 값 하나 (여러 개면 enum 에서 위의 것). 버그가 없으면 `NO_BUG`.
- 체크리스트: docs/review-checklist.md 의 번호 중 가장 직접적인 것 하나. 버그가 없으면 0.

| 사례 | 람다별 [스레드]/[blocking] 요약 | 증상 | 체크리스트 # | 근거 규칙과 한 줄 이유 | 결과 | 틀렸다면 무엇을 놓쳤나 |
|---|---|---|---|---|---|---|
| C01 주문 이력 | | | | | | |
| C02 상품 상세 | | | | | | |
| C03 상품 페이지 | | | | | | |
| C04 주문+포인트 | | | | | | |
| C05 주문 상세 | | | | | | |
| C06 추천 | | | | | | |
| C07 재고 배지 | | | | | | |
| C08 썸네일 | | | | | | |
| C09 결제 승인 | | | | | | |
| C10 장바구니 | | | | | | |
| C11 배송비 견적 | | | | | | |
| C12 주문+분석 이벤트 | | | | | | |
| C13 출고 준비 | | | | | | |
| C14 매출 리포트 | | | | | | |
| C15 상품 검색 | | | | | | |
| C16 배송 상태 | | | | | | |
| C17 주문 상세(격리) | | | | | | |
| C18 결제 확정 | | | | | | |
| C19 상품 카드 | | | | | | |
| C20 주문 내보내기 | | | | | | |
| C21 가격 캐시 | | | | | | |
| C22 결제 취소 | | | | | | |

## 채점 후 서술 문제 (journal 에 답한다)

1. C05 와 C17 은 둘 다 "비동기 클라이언트 + JDBC" 다. 각각 버그인지 아닌지를 두 코드의 차이와 함께 R1/R2/R4 로 설명하고, C17 을 리뷰할 때 **반드시 확인해야 할** 설정 하나를 들어라.
2. C04 와 C12 는 둘 다 future 를 버린다. 각각이 요구사항(실패 로그)을 지키는지 R6 과 R9 로 판정하라. 버린 future 의 실패를 관찰하는 코드는 **어느 future 에** 붙어야 하는가?
3. C18 과 C19 는 관측 조건에 동시 요청 수가 두 가지(평시/피크)다. 각 사례에서 증상이 있다면, 그것이 시작되는 정확한 조건(스레드 수·큐 크기·동시 요청 수의 관계)을 식으로 써라.
4. C14 의 작성자는 "사용자는 200ms 안에 응답을 받는다"고 했다. 이 말은 사실인가? 요청이 계속 들어온다고 가정하고, report 풀과 reportDb 에서 일어나는 일을 시간 순서로 써라.
5. C16 의 마지막 단계를 `thenApplyAsync(fn, mdcAwareIo)` 로 바꾸면 그 단계의 로그에 traceId 가 찍히는가? 왜 그런가? (C22 와 비교하라)

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

| 사례 | 증상 | # | 핵심 추적 |
|---|---|---|---|
| C01 | SEQUENTIAL_NOT_PARALLEL | 7 | stream 은 lazy 라 원소마다 `map(supplyAsync)` → `map(join)` 이 이어서 실행된다. 다음 원소의 future 는 앞 원소 join 이 끝나야 만들어진다(R7, R5). 루프 안 join 과 같다. 100ms × 5 = 500ms |
| C02 | CALLER_THREAD_BLOCKED | 1 | `computeIfAbsent(sku, productDb::call)` 이 [caller]/[blocking 150ms]. future 는 그 뒤에 만들어진다(가짜 async). 이벤트 루프가 부르면 루프가 멈춘다. warmUp 은 올바르다 |
| C03 | NO_BUG | 0 | 세 작업을 먼저 시작(R3)하고 allOf. `thenApply` 안의 join 은 allOf 완료 후라 막지 않는다(R5). 타임아웃은 호출자가 건다고 명시 |
| C04 | EXCEPTION_LOST | 9 | `saved.thenAcceptAsync(loyalty)` 의 반환값을 버렸다. `whenComplete` 는 **다른 분기**(saved → thenApply)에 붙어 있어 적립 실패를 보지 못한다(R6) |
| C05 | EVENT_LOOP_STALL | 3 | `thenApply(... couponRepository.call ...)` 은 [event-loop]/[blocking 50ms] (R1: 등록 시 미완료, 완료는 이벤트 루프). 10건이면 lag ≈ 450ms. "평소 3ms" 여도 루프 위 JDBC 는 버그다 |
| C06 | FALLBACK_NOT_APPLIED | 13 | `whenComplete` 는 결과를 바꾸지 않는다(R9). 실패는 그대로 전파되고, 실패면 `thenApply` 는 **실행되지 않는다**(R8). recs==null 분기는 영원히 실행되지 않는다. 로그만 "대체"라고 거짓말한다 |
| C07 | NO_BUG | 0 | `thenApply` 두 개는 [event-loop]/[non-blocking 문자열 처리]. orTimeout 은 future 상태만 바꾸고 비동기 요청은 스레드를 잡지 않는다 |
| C08 | COMMON_POOL_STARVATION | 4 | `supplyAsync(() -> imageCdn.call(..))` executor 생략 → commonPool(R2)에서 300ms blocking. 병렬도 × 2 개면 commonPool 이 600ms 동안 꽉 찬다. Java 17 + CPU ≤ 2 컨테이너에서는 작업마다 새 스레드(버전표) |
| C09 | FALLBACK_NOT_APPLIED | 10 | PG 가 `completeExceptionally(CardDeclined)` 로 실패시켜도 `thenApply` 를 거치면 `CompletionException(CardDeclined)` 로 감싸진다(R8). `instanceof` 가 거짓 → 재던짐. fetch 바로 뒤에 붙였을 땐 동작했다(그래서 테스트를 통과했다) |
| C10 | EVENT_LOOP_STALL | 2 (3도 정답) | `thenApply(cart -> pricing.fetch(cart).join())` 은 [cart 이벤트 루프]/[blocking: join 100ms]. 5건이면 lag ≈ 400ms. 두 클라이언트가 **같은 이벤트 루프**였다면 영원히 멈춘다(응답을 줄 스레드가 기다리고 있으므로) |
| C11 | NEVER_COMPLETES | 11 | b 분기에는 시간 상한이 없다. `exceptionally` 는 실패해야 실행되는데 무응답은 실패가 아니다. `thenCombine` 은 b 를 영원히 기다린다(R10) |
| C12 | NO_BUG | 0 | 버린 분기의 **끝**에 `exceptionally` 로 로그를 남긴다(R6, R9). 응답은 analytics 를 기다리지 않는다. 의도된 best-effort |
| C13 | COMPLETES_TOO_EARLY | 14 | `thenApply(order -> paymentClient.fetch(..))` 의 타입은 `CompletableFuture<CompletableFuture<String>>` 인데 `<?>` 로 숨겼다. allOf 는 **바깥** future(주문 조회 50ms)만 기다린다 → 50ms 에 완료, 매입 실패도 무시(R13) |
| C14 | TIMEOUT_DOES_NOT_FREE_RESOURCES | 12 | orTimeout 은 future 만 실패시킨다. `reportDb.call` 은 report 스레드에서 2초 동안 계속 돈다(R10). 다음 요청은 큐에서 기다리다 200ms 에 폴백 → 실제 집계는 영영 못 한다 |
| C15 | HOP_QUEUEING_DELAY | 8 | 가벼운 단계 3개가 매번 io 큐 **맨 뒤**로 다시 들어간다. io 2스레드가 다른 검색으로 바쁘면 첫 요청도 ≈ 300ms. non-async 로 두면 방금 끝난 io 스레드가 바로 처리한다(R1) |
| C16 | CONTEXT_LOST | 15 | 마지막 `thenApply` 는 thenCompose 의 **안쪽** future(deliveryClient)를 완료시킨 이벤트 루프에서 실행된다(R1). 데코레이터는 io 에 **제출된 작업**에만 MDC 를 복사한다(R12) |
| C17 | NO_BUG | 0 | `thenCompose` 함수는 [event-loop]/[제출만: non-blocking], JDBC 는 [jdbc]/[blocking], 마지막 thenApply 는 [jdbc]/[가벼움]. 큐 무제한 + AbortPolicy 라 CallerRuns 없음 |
| C18 | STARVATION_DEADLOCK | 5 (2도 정답) | `thenApplyAsync(.. reserveStock(order).join() .., taskExecutor)` 는 task 스레드가 **같은 풀**에 제출한 작업을 기다린다. 동시 요청 ≥ 4(풀 크기)면 4스레드 모두 join 에서 대기, 예약 작업은 큐 뒤 → 영원히 멈춤(R11). 1건이면 정상 |
| C19 | EVENT_LOOP_STALL | 6 | `thenApplyAsync(.., enrichPool)` 의 제출은 이벤트 루프(카탈로그 응답을 완료시킨 스레드)가 한다. 동시 요청 > 2(스레드)+4(큐)면 CallerRuns 가 **이벤트 루프에서** priceDb 를 실행한다(R3 예외 1). 평시 3건은 정상 |
| C20 | CONTEXT_LEAK | 16 | 데코레이터가 복원(finally)하지 않고, 사용자가 null 이면 **설정하지 않는다** → 풀 스레드에 alice 가 남아 배치가 alice 권한으로 실행(R12) |
| C21 | SHARED_FUTURE_CORRUPTED | 17 | `orTimeout` 은 새 future 가 아니라 **this** 를 반환한다. A 의 50ms 타임아웃이 공유 future 자체를 실패시켜 B 도 TimeoutException(R10: future 의 상태를 바꾼다). `cancel`, `complete` 도 같다 |
| C22 | CONTEXT_LOST | 16b (15도 정답) | `thenApplyAsync(.., mdcAwareIo)` 는 [io] 에서 실행되지만 그 작업을 `execute()` 하는 것은 PG 응답을 완료시킨 **이벤트 루프**다(R1). 데코레이터는 execute() 를 부른 스레드의 MDC(비어 있음)를 캡처한다(R12). JDBC 격리는 올바르다 |

서술 1: C05 는 `thenApply` 안에서 JDBC 를 호출하므로 이벤트 루프가 실행한다(R1, R4). C17 은 `thenCompose` 함수 안에서 `supplyAsync(.., jdbcPool)` 로 **제출만** 하므로 이벤트 루프는 막히지 않는다(R3). C17 리뷰에서 꼭 볼 것: jdbcPool 의 **큐/거절 정책**. CallerRunsPolicy 면 C19 와 같은 장애가 된다(그리고 jdbcPool 크기가 DB 커넥션 풀 크기와 맞는지).

서술 2: C04 는 버린 분기(`thenAcceptAsync`)의 실패를 아무도 관찰하지 않는다. `whenComplete` 는 다른 분기에 있다(R6). C12 는 버린 분기의 **끝 future** 에 `exceptionally` 로 로그를 남긴다. 관찰 코드는 "버리는 바로 그 future 의 체인 끝"에 붙어야 한다.

서술 3: C18: 동시 요청 수 ≥ 풀 스레드 수(4)이고, 모든 스레드가 바깥 작업을 잡은 뒤 안쪽 작업이 큐 뒤에 쌓이면 멈춘다. C19: 같은 순간(이벤트 루프 한 번의 처리 묶음)에 도착하는 응답 수 > 스레드 수(2) + 큐 크기(4)이면, 초과분은 이벤트 루프에서 실행된다.

서술 4: (1) 요청 1, 2 가 report 스레드 2개를 잡고 2초 쿼리 시작 (2) 200ms 에 둘 다 폴백 응답 — 사용자는 만족 (3) 요청 3 이 들어오면 스레드가 없어 큐에서 대기, 200ms 에 폴백 — 쿼리는 시작도 못 함 (4) 계속 들어오는 요청은 큐에 쌓이고(무제한 큐면 메모리), 스레드가 풀리면 이미 응답한 요청의 쿼리를 뒤늦게 실행 → DB 부하는 줄지 않고, 실제 리포트는 영영 나오지 않는다. 풀을 공유하는 다른 리포트도 모두 폴백만 받는다.

서술 5: 여전히 traceId 가 없다. 마지막 단계는 앞 단계가 미완료일 때 등록됐으므로, 그 Async 작업을 executor 에 **제출하는 것은** 앞 단계를 완료시킨 이벤트 루프다(R1). 데코레이터는 제출하는 스레드(이벤트 루프)의 MDC — 비어 있음 — 를 캡처한다. 해결: 조립 시점(요청 스레드)에 MDC 를 캡처해 콜백을 감싸거나(Micrometer `ContextSnapshot.wrap` 방식), 컨텍스트를 값으로 넘긴다.

자주 틀리는 지점 (README 에서 옮김): 관측 조건에 "평시/피크"가 있는 사례(C18, C19)에서 "QA 에서 수백 번 통과했다", "기능 테스트는 모두 통과" 같은 주석은 **증거가 되지 못한다**. 피크 조건으로 다시 추적하라.
<!-- ANSWER-END -->
