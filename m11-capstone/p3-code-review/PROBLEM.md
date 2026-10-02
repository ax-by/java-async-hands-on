# P3. 코드 리뷰: 해외 송금 견적 API 비동기화 PR

> 리뷰 대상: `src/main/java/async/m11/p3/RemittanceQuoteServiceUnderReview.java` (수정 금지)
> 산출물: [REVIEW.md](REVIEW.md), `ReviewFindings.java`, `RemittanceQuoteService.java` · 채점: `P3GradingTest` (9개)

## 상황

동료가 "해외 송금 견적 API 비동기화" PR 을 올렸다. PR 설명은 리뷰 대상 클래스 javadoc 에 있다. 당신이 리뷰어다.
이 PR 에는 **결함이 여러 개(5~7개)** 있고, **겉보기에 수상하지만 올바른 코드도 몇 군데** 있다. 올바른 코드를 결함으로 지적하면 오탐이다.

## 이 API 가 지켜야 할 요구사항 (PR 이 만족해야 하는 것 = 고친 코드가 채점되는 기준)

| 단계 | 의존성 | 평소 응답 | 요구사항 |
|---|---|---|---|
| KYC 확인 | `kycDb` (JDBC) | 40ms | 실패하면 `KycRejectedException`(cause = 원래 예외)으로 견적 실패 |
| 환율 | `fxClient` (비동기, 이벤트 루프가 완료) | 30ms | `RateUnavailableException` 이거나 **200ms 초과**면 캐시 환율(`fx-cache:{pair}`) |
| 수수료 | `feeEngine` (사내 동기 HTTP 클라이언트) | 50ms | 환율이 필요하다. 키 = `{amount}@{rate}` |
| 제재 스크리닝 | `sanctions` (비동기) | 60ms | **규정상 필수.** `SanctionsHitException` 이면 견적 실패, **300ms 안에 응답이 없으면 거절**(fail-closed, `TimeoutException`). 두 경우 모두 **저장하지 않는다** |
| 저장 | `quoteDb` (JDBC) | 30ms | 키 = `{customerId}/{rate}/{요청 채널}`. 요청 채널은 `quote()` 를 호출한 스레드의 `RequestContext.channel()` (APP/WEB). 견적 future 는 **저장이 끝난 뒤** 완료된다 |

- 전체 SLO 600ms. 넘으면 `TimeoutException` 으로 실패한다.
- 호출 스레드(요청 스레드)를 막지 않는다. 이벤트 루프 위에서 blocking 0건. blocking 작업은 주어진 `ioPool` 에서만.
- 독립적인 단계(KYC, 환율→수수료, 스크리닝)는 동시에 진행한다(PR 의도 유지).
- 실패의 원인은 unwrap 했을 때 위 표의 예외여야 한다.
- `RequestContext` 는 서블릿 필터가 `quote()` 호출 직후 지운다.

## 할 일

1. **리뷰** ([REVIEW.md](REVIEW.md)): 결함마다 [리뷰 체크리스트](../../docs/review-checklist.md) 양식으로 코멘트를 쓴다(원인 규칙·조건·증상·수정안).
   올바르다고 판단한 수상한 코드도 "왜 괜찮은지" 한 줄씩 남긴다.
2. **요약** (`ReviewFindings.CHECKLIST_NUMBERS`): 찾은 결함마다 해당하는 체크리스트 번호를 적는다(같은 번호는 한 번).
3. **수정** (`RemittanceQuoteService`): 리뷰 대상 코드를 옮겨 와서 모든 결함을 고친다. 요구사항 표가 기준이다.
4. 자기 리뷰: 고친 코드에 람다마다 `[스레드]/[blocking]/[컨텍스트]` 를 달고 추적 표를 채운다.
