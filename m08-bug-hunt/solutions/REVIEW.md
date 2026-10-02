# m08 리뷰 코멘트 실습

> **SymptomTest 채점을 마친 뒤에 연다.** (이 파일에 실린 사례 목록 자체가 힌트가 되기 때문이다.)

PR 리뷰어가 되어 아래 5개 사례에 코멘트를 쓴다. 채점 테스트는 없다. 모범 답안과 비교해 **네 요소**가 모두 있는지 스스로 점검한다.

1. **원인**: 어느 줄의 어떤 코드가, 어떤 규칙(R 번호) 때문에 문제인지
2. **조건**: 언제 드러나는지 (항상 / 부하 시 / 특정 실패 시)
3. **증상**: 운영에서 무엇이 보이는지 (지표, 로그, 사용자 경험)
4. **수정안**: 무엇으로 바꾸는지 (코드 한두 줄 수준)

양식 ([리뷰 체크리스트](../docs/review-checklist.md) 맨 아래와 같다):

```
[R4][#3] L42 thenApply 안의 userRepository.findById 는 JDBC(blocking)이고,
앞 단계 future 는 lettuce 이벤트 루프가 완료시키므로(R1) 이벤트 루프 스레드를 ~50ms 점유한다.
→ thenComposeAsync(.., dbPool) 또는 supplyAsync(.., dbPool) + thenCompose 로 격리 제안.
증상 예상: Redis 응답 p99 상승 (이벤트 루프 lag).
```

작성 규칙: 코멘트는 작성자를 설득해야 한다. "Async 쓰세요" 처럼 근거 없는 지시는 불합격이다. 작성자의 주석(가정)이 **왜 틀렸는지**도 짚는다.

---

## R1. C09Payment.pay

```
(여기에 작성)
```

## R2. C13Fulfillment.fulfill

```
(여기에 작성)
```

## R3. C14SalesReport.salesReport

```
(여기에 작성)
```

## R4. C19ProductCard (newEnrichPool + card)

```
(여기에 작성)
```

## R5. C21PriceCache.price

```
(여기에 작성)
```

<!-- ANSWER-BEGIN -->
### 모범 코멘트 (solutions/ 폴더에만 있음)

**R1. C09**
```
[R8][#10] exceptionally 의 `ex instanceof CardDeclinedException` 은 항상 거짓입니다.
PG 가 completeExceptionally(CardDeclined) 로 실패시켜도 중간에 thenApply 를 거치면서
CompletionException(CardDeclined) 으로 감싸져 전달됩니다(R8). fetch 바로 뒤에 붙였을 때 테스트가 통과한 이유가 이것입니다.
조건: 카드 거절이 날 때마다(항상).
증상: 한도 초과 같은 정상 거절이 5xx 로 응답되고, 장애 알람이 울립니다(폴백 미적용).
→ Throwable cause = unwrap(ex) (CompletionException/ExecutionException 벗기기) 후 판별.
   재던질 때는 받은 ex 를 그대로 던져 cause 사슬을 유지해 주세요.
```

**R2. C13**
```
[R13][#14] `orderClient.fetch(id).thenApply(order -> paymentClient.fetch(..))` 는 함수가 future 를 반환하므로
실제 타입이 CompletableFuture<CompletableFuture<String>> 입니다. `CompletableFuture<?>` 로 선언해서 컴파일러가 잡지 못했습니다.
allOf 는 바깥 future(주문 조회 50ms)만 기다리므로 결제 매입(300ms)이 끝나기 전에 완료됩니다.
조건: 항상. 매입이 실패해도 allOf 는 성공합니다(안쪽 실패를 아무도 보지 않음).
증상: 출고 메일이 매입보다 먼저 나가고, 매입이 실패한 주문도 출고 지시가 내려갑니다.
→ thenCompose(order -> paymentClient.fetch("capture:" + order)) 로 평탄화하고 타입을 CompletableFuture<String> 으로 명시.
```

**R3. C14**
```
[R10][#12] orTimeout(200ms) 은 반환 future 만 실패시키고, report 스레드에서 실행 중인 reportDb.call 은 멈추지 않습니다(R10).
"사용자는 200ms 안에 응답을 받는다"는 맞지만, 스레드와 DB 커넥션은 쿼리가 끝날 때까지(최대 수 초) 계속 점유됩니다.
조건: 느린 쿼리가 풀 크기(2)만큼 동시에 들어오는 순간부터.
증상: report 풀 active=2 고정, 이후 요청은 큐에서 기다리다 전부 캐시 폴백(실제 집계 불가), DB 에는 버려진 쿼리가 계속 실행됨.
같은 풀을 쓰는 다른 리포트도 함께 마비됩니다.
→ 작업을 멈출 손잡이를 남기세요: Future<?> task = reportPool.submit(...) 로 결과 CF 를 완료시키고,
   타임아웃 시 task.cancel(true) 로 interrupt (JDBC 는 Statement.setQueryTimeout / cancel 과 함께).
```

**R4. C19**
```
[R3][R11][#6] enrichPool 이 CallerRunsPolicy 이고, thenApplyAsync(.., enrichPool) 의 제출은 카탈로그 응답을 완료시킨
이벤트 루프 스레드가 합니다(R1). 큐(4)가 차면 CallerRuns 가 priceDb.call(JDBC 100ms)을 **이벤트 루프에서** 실행합니다(R3 예외 1).
"요청을 버리지 않는다"는 의도와 달리 이벤트 루프를 멈춰 그 루프의 모든 응답을 늦춥니다.
조건: 같은 순간 도착한 응답이 2(스레드) + 4(큐) 를 넘을 때만 → 기능 테스트·평시엔 재현되지 않음.
증상: 피크에만 카탈로그 응답 p99 급등(이벤트 루프 lag), BlockHound 를 켜면 이벤트 루프 위 sleep/JDBC 위반.
→ AbortPolicy 로 바꾸고, RejectedExecutionException(unwrap 후)을 "가격 없는 기본 카드"나 503 으로 변환(부하 차단).
   큐를 키우는 것만으로는 해결이 아닙니다(더 큰 피크에서 같은 일이 생기고 지연만 커짐).
```

**R5. C21**
```
[R10][#17] 공유 중인 내부 future 를 그대로 반환합니다. 호출자가 orTimeout/completeOnTimeout/cancel/complete 를 하면
새 future 가 아니라 **공유 future 자체**의 상태가 바뀝니다(orTimeout 은 this 를 반환, R10).
조건: 같은 SKU 를 동시에 묻는 호출자 중 하나라도 짧은 타임아웃을 걸거나 취소할 때.
증상: 검색 화면(50ms 예산)의 타임아웃 때문에 상품 상세(1초 예산)가 TimeoutException 을 받습니다.
가격 API 는 200ms 에 정상 응답했는데도. 호출자 조합에 따라서만 나므로 재현이 매우 어렵습니다.
→ 호출자마다 사본을 반환: `return shared.copy();` (Java 8 이면 shared.thenApply(Function.identity())).
   공개 API 라면 CompletionStage 로 반환하는 것도 고려(m09).
```
<!-- ANSWER-END -->
