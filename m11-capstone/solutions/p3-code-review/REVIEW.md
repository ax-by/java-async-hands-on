# P3 리뷰: PR #1482 "해외 송금 견적 API 비동기화"

리뷰 대상: `RemittanceQuoteServiceUnderReview.java`. 줄 번호는 그 파일 기준이다.

## 1단계: 추적 표 (리뷰 전에 먼저 채운다)

| 줄 | 단계 | 실행 스레드 (R1/R2) | blocking? (R4/R5) | 실패 시 (R8/R9/R10) | 컨텍스트 (R12) |
|---|---|---|---|---|---|
| 42 | KYC supplyAsync | | | | |
| 43 | KYC handle | | | | |
| 51~52 | 환율 fetch, trim | | | | |
| 53 | 환율 exceptionally | | | | |
| 61 | 수수료 | | | | |
| 64~65 | 스크리닝 | | | | |
| 68~69 | allOf, 조립 | | | | |
| 70~71 | save, join | | | | |
| 75~77 | save 본문 | | | | |

## 2단계: 리뷰 코멘트 (결함마다 하나, 체크리스트 양식)

```
[R?][#?] L?? ...
→ 수정안:
증상 예상:
```

(여기에 작성)

## 3단계: 수상하지만 올바르다고 판단한 코드 (한 줄씩 이유)

(여기에 작성)

<!-- ANSWER-BEGIN -->
### 모범 리뷰 (solutions/ 폴더에만 있음)

**추적 표 요약**: L42 commonPool/[blocking JDBC]. L43 KYC 를 완료시킨 스레드/가벼움. L51~52 fx 이벤트 루프/가벼움. L53 fx 이벤트 루프. L61 **fx 이벤트 루프/[blocking 50ms]**. L64~65 sanctions 이벤트 루프, 결과 버림. L68~69 마지막 입력(보통 fee=fx 이벤트 루프)/가벼움(join 은 완료 보장). L70 같은 스레드/제출만. L71 **같은 스레드에서 join → 저장 30ms 동안 이벤트 루프 blocking**. L75~77 io/[blocking], RequestContext 없음.

**결함 7개**

```
[R2][R11][#4] L42 supplyAsync(() -> kycDb.call(..)) 에 executor 가 없어 JDBC 가 commonPool 에서 40ms blocking 합니다.
→ supplyAsync(.., ioPool). (PR 이 ioPool 을 주입받고도 save 에서만 쓰고 있음)
증상 예상: 피크에 commonPool 고갈 → parallel stream·다른 라이브러리 지연. Java 17 + CPU ≤ 2 컨테이너에서는 견적마다 스레드 생성.
```
```
[R8][#10] L54 `ex instanceof RateUnavailableException` 은 항상 거짓입니다. L52 thenApply 를 거치면서 예외가
CompletionException(RateUnavailable) 로 감싸져 들어옵니다. L57 은 그것을 한 번 더 감쌉니다.
→ Throwable cause = unwrap(ex); 판별은 cause 로, 재던질 때는 cause 를 감싸서.
증상 예상: 환율 고시 전/장애 때 캐시 환율 폴백이 동작하지 않아 견적 API 가 실패(5xx).
```
```
[R1][R4][#3] L61 rate.thenApply(r -> feeEngine.call(..)) 의 rate 는 fx 클라이언트 이벤트 루프가 완료시키므로(R1)
동기 HTTP 수수료 계산 50ms 가 이벤트 루프에서 실행됩니다.
→ rate.thenApplyAsync(r -> feeEngine.call(..), ioPool).
증상 예상: 환율 API 응답 p99 상승, 동시 견적이 많을수록 lag 누적(20건이면 ≈1초).
```
```
[R6][#9] L64 스크리닝 future 를 버립니다. 스크리닝에 걸려도(SanctionsHitException) 아무도 보지 않고 견적이 저장·반환됩니다.
규정상 필수 단계가 사실상 비활성 상태입니다.
→ screening future 를 allOf(kyc, rate, fee, screening) 에 넣어 실패가 견적 실패로 전파되게 하고, 저장은 그 뒤에.
증상 예상: 제재 대상자에게 견적·송금 진행(컴플라이언스 사고). 로그에도 흔적이 없음.
```
```
[R10][#11] 환율(L51)과 스크리닝, 전체 파이프라인에 시간 상한이 없습니다. fx 가 응답하지 않으면 견적 future 가 영원히 완료되지 않습니다.
→ fx: orTimeout(200) 후 TimeoutException 도 캐시 폴백, 스크리닝: orTimeout(300) 실패 = 거절(fail-closed), 전체: orTimeout(600).
증상 예상: 환율 API 장애 시 견적 요청이 무한 대기, 커넥션·메모리 누적.
```
```
[R13][#14] L70~71 thenApply(draft -> save(draft)) 는 CompletableFuture<CompletableFuture<Quote>> 를 만들고,
L71 의 join 은 그 단계를 완료시킨 스레드(보통 fx 이벤트 루프)에서 저장 30ms 동안 blocking 합니다(#2 도 해당).
→ thenCompose(this::save).
증상 예상: 이벤트 루프 lag, 저장 지연이 커지면 이벤트 루프가 그만큼 멈춤.
```
```
[R12][#15] L76 RequestContext.channel() 을 io 스레드에서 읽습니다. ThreadLocal 이라 값이 없어 모든 견적이 채널 null 로 저장됩니다.
→ quote() 첫 줄(요청 스레드)에서 channel 을 값으로 캡처해 save 에 인자로 넘긴다.
증상 예상: 채널별 정산·통계 오류. 테스트 환경에서 우연히 같은 스레드면 재현되지 않음.
```

**수상하지만 올바른 코드**
- L43~48 `handle` + `unwrap`: 실패를 KycRejectedException 으로 바꿔 던진다. unwrap 후 원인을 감싸므로 올바르다(R8, R9).
- L52 `thenApply(String::trim)` 이 이벤트 루프에서 실행: 가벼운 변환이라 괜찮다(R4: 본문이 결정).
- L68~69 `allOf` 뒤의 `rate.join()`, `fee.join()`: allOf 가 완료된 뒤라 막지 않는다(R5).
- L79 save 의 `supplyAsync(.., ioPool)`: blocking 을 전용 풀로 격리한 올바른 부분(단, 컨텍스트 문제는 L76).

ReviewFindings: `Set.of(3, 4, 9, 10, 11, 14, 15)` (14 대신·함께 2 도 인정).
<!-- ANSWER-END -->
