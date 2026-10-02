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

(여기에 작성)
