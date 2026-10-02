# m03 예측 시트

`Scenarios.java`를 읽고 **코드를 실행하기 전에** 채운다. 실패 경로는 각 단계 옆에 "여기에 도착하는 것: 값 / 원본 예외 / CompletionException(원인)"을 적어 가며 추적한다.

```
예) supplyAsync(throw X)   → 저장: CompletionException(X)
    .thenApply(f)          → f 건너뜀, 저장: 같은 CompletionException(X)
    .exceptionally(h)      → h 가 받는 것: CompletionException(X)
```

| 시나리오 | 항목 | 예측 | 근거 규칙과 한 줄 이유 | 결과 (채점 후) | 틀렸다면 무엇을 잘못 적용했나 |
|---|---|---|---|---|---|
| S01 | 실행된 단계 | | | | |
| S02 | exceptionally가 받은 타입 | | | | |
| S03 | (a) 바로 붙인 exceptionally | | | | |
| S03 | (b) thenApply 뒤 exceptionally | | | | |
| S04 | (a) get()의 cause 타입 | | | | |
| S04 | (b) join()이 던진 타입 | | | | |
| S05 | 결과 | | | | |
| S06 | 결과 | | | | |
| S07 | (a) 결과 | | | | |
| S07 | (b) suppressed 타입 | | | | |
| S08 | 결과 | | | | |
| S09 | (a) 복구 먼저 | | | | |
| S09 | (b) 복구 나중 | | | | |
| S10 | (a) exceptionally가 받은 타입 | | | | |
| S10 | (b) 직후 inFlight | | | | |
| S11 | (a) render 스레드 | | | | |
| S11 | (b) 원래 future의 값 | | | | |
| S12 | (a) TimeoutException 시점 (ms) | | | | |
| S12 | (b) 직후 isDone() | | | | |
| S13 | interruptedCount | | | | |
| S14 | 상류 isCancelled() | | | | |
| S15 | (a) 하류 join 예외 타입 | | | | |
| S15 | (b) 하류 isCancelled() | | | | |
| S16 | interruptedCount | | | | |

## 채점 후 서술 문제 (journal에 답한다)

1. S02와 S03(a), S10(a)은 모두 "바로 붙인 exceptionally"다. 세 경우에 받은 타입을 한 문장의 규칙으로 정리하라. 그 규칙에서 "unwrap은 항상 필요하다"는 결론이 어떻게 나오는가?
2. 주문 API가 `supplyAsync(() -> inventoryClient.reserve(sku), io).orTimeout(500, ms)`로 재고를 예약한다. 재고 서버가 느려진 날 "응답은 500ms에 타임아웃 에러로 잘 나가는데 30분 뒤 서비스 전체가 멈췄다". S10, S13을 근거로 원인을 설명하고 고칠 방법을 두 가지 이상 적어라.
3. 동료가 "결과 로그를 남기려고" 이렇게 썼다: `return payment.whenComplete((v, ex) -> auditLog.write(v.getId()))`. 이 코드가 운영에서 일으킬 수 있는 문제를 S07, S08로 설명하라(성공/실패 각각).
4. `CompletableFuture<Price> shared = priceCache.computeIfAbsent(sku, k -> fetchAsync(k));` 로 여러 요청이 같은 future를 공유한다. 한 요청 핸들러가 `shared.completeOnTimeout(Price.UNKNOWN, 100, ms)`를 호출하면 어떤 일이 생기는가? 어떻게 고쳐야 하는가? (S11, E3)

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

| 시나리오 | 답 | 근거 |
|---|---|---|
| S01 | `recover,notify` | R8: reserve, charge는 건너뛴다. exceptionally에서 복구된 뒤 thenRun은 정상 경로 |
| S02 | `CompletionException` | R8: 람다 안에서 던진 예외는 저장될 때 감싸진다. 바로 붙인 핸들러도 감싼 것을 받는다 |
| S03 | `PaymentDeclinedException` / `CompletionException` | R8: completeExceptionally는 원본 저장. 한 단계를 거치면 감싸진다 |
| S04 | `OutOfStockException` / `CompletionException` | R8: get은 껍질을 벗겨 ExecutionException의 cause로, join은 CompletionException 그대로 |
| S05 | `failed:OutOfStockException` | R8: ex는 CompletionException이라 instanceof가 false → 다시 던짐(두 겹) |
| S06 | `failed:ExternalApiException` | R9: whenComplete는 결과를 바꾸지 않는다 |
| S07 | `failed:PaymentDeclinedException` / `NullPointerException` | R9: 실패 + 액션 예외 → 원래 실패 유지, 액션 예외는 suppressed |
| S08 | `failed:NullPointerException` | R9: 성공 + 액션 예외 → 액션 예외로 실패 |
| S09 | `ok:discount=0%` / `ok:no-discount` | R9: 복구는 그 앞 전체의 실패를 잡는다. 위치가 결과의 의미를 바꾼다 |
| S10 | `TimeoutException` / 1 | R8, R10: 타임아웃은 내부적으로 completeExceptionally(원본). 실제 호출은 계속 진행 중 |
| S11 | timeoutScheduler / `cached` | R10, R1: 타이머가 완료시킨다. completeOnTimeout은 this를 반환하므로 원래 future가 "cached"로 완료됐고, 늦은 결과는 버려졌다 |
| S12 | 100 / false | R5, R10: get(timeout)은 호출 스레드를 100ms 막고 포기할 뿐, future를 완료시키지 않는다 |
| S13 | 0 | R10: CF.cancel은 interrupt하지 않는다 |
| S14 | false | R10: 취소는 상류로 전파되지 않는다 |
| S15 | `CompletionException` / false | R8, R10: 하류는 CompletionException(CancellationException)으로 "실패"한다. 취소 상태가 아니다 |
| S16 | 1 | R10: executor의 Future.cancel(true)는 작업 스레드를 interrupt한다 |

서술 1: "CF가 **직접** 실패를 저장하면(completeExceptionally, orTimeout, cancel) 원본이 저장되고, **람다나 단계를 거쳐** 실패가 전달되면 CompletionException으로 감싸져 저장된다. 핸들러는 저장된 것을 받는다." 같은 핸들러라도 앞 단계가 무엇이냐에 따라 받는 모양이 달라지고, 파이프라인은 리팩터링으로 언제든 바뀐다(예: thenApply 하나 추가). 따라서 받는 쪽에서 모양을 가정하면 안 되고 항상 unwrap해야 한다.

서술 2: orTimeout은 future 상태만 실패로 바꿀 뿐 io 스레드에서 돌고 있는 `reserve` 호출은 계속 실행된다(S10의 inFlight). 요청이 계속 들어오면 응답은 500ms에 나가지만 io 풀의 스레드는 느린 호출에 하나씩 묶이고, 결국 풀이 전부 점유되어 큐가 쌓이고 모든 요청이 타임아웃된다. cancel도 같다(S13). 고치는 법: (1) 클라이언트 자체 타임아웃을 설정한다(HTTP 클라이언트의 connect/read timeout, JDBC queryTimeout). 실제 작업이 끝나야 스레드가 돌아온다. (2) E5처럼 `submit`의 Future를 쥐고 타임아웃 시 `cancel(true)`로 interrupt한다(작업이 interrupt에 반응할 때만 유효). (3) 풀을 bounded 큐 + 거절 정책으로 만들어 고갈 시 빠르게 실패시킨다(R11, m04). (4) 재고 서버 지연을 감지하면 호출 자체를 줄인다(서킷 브레이커).

서술 3: 성공 시: `auditLog.write`가 예외를 던지면(로그 저장소 장애) 결제 future가 그 예외로 실패한다(S08). 돈은 빠져나갔는데 사용자는 실패 응답을 받는다. 실패 시: `v`가 null이므로 `v.getId()`가 NPE를 던지지만, 원래 실패가 유지되고 NPE는 suppressed로 숨는다(S07). 감사 로그에는 아무것도 남지 않고, 그 사실도 잘 드러나지 않는다. 고치기: 액션 안에서 `ex`를 먼저 확인하고, 로그 코드 전체를 try/catch로 감싸 결과에 영향을 주지 않게 한다(E6).

서술 4: completeOnTimeout은 `shared` 자체를 `Price.UNKNOWN`으로 완료시킨다(S11: this 반환). 캐시에 들어 있는 future가 UNKNOWN으로 고정되므로, 그 뒤 같은 sku를 조회하는 **모든 요청**이 실제 가격이 도착한 뒤에도 UNKNOWN을 받는다. 한 요청의 응답 정책이 공유 상태를 오염시킨 것이다. 고치기: 요청마다 새 future를 만들어 그쪽에 타임아웃을 건다. `shared.copy()`(Java 9+) 또는 `shared.thenApply(Function.identity())`에 `completeOnTimeout`을 걸거나, E3의 `withTimeoutFallback`처럼 결과용 future를 따로 만든다.
<!-- ANSWER-END -->
