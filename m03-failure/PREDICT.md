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

(여기에 작성)
