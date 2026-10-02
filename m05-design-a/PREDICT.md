# m05 예측 시트

`Scenarios.java`를 읽고 **코드를 실행하기 전에** 채운다. 이어서 아래 **설계 판단 퀴즈**를 푼다. "근거 규칙"이 비어 있으면 예측한 것이 아니라 찍은 것이다.

| 시나리오 | 항목 | 예측 | 근거 규칙과 한 줄 이유 | 결과 (채점 후) | 틀렸다면 무엇을 잘못 적용했나 |
|---|---|---|---|---|---|
| T01 | 순차 코드 응답시간 (ms) | | | | |
| T01 | product 호출 스레드 | | | | |
| T02 | PR #1 응답시간 (ms) | | | | |
| T02 | PR #1 price 호출 스레드 | | | | |
| T03 | PR #2 future를 반환하기까지 (ms) | | | | |
| T04 | fan-out 반환까지 (ms) | | | | |
| T04 | fan-out 결과까지 (ms) | | | | |
| T05 | 스레드 2개 풀에서 결과까지 (ms) | | | | |
| T06 | 실패 시점 (ms) | | | | |
| T06 | 그 시점 진행 중인 price 호출 수 | | | | |
| T06 | handle이 받은 예외 클래스 | | | | |
| T06 | handle 실행 스레드 | | | | |
| L01 | 풀 60 | | | | |
| L01 | 풀 16 | | | | |
| L02 | 리뷰 장애 + 소스 타임아웃, 풀 60 | | | | |
| D01~D06 | (아래 퀴즈) | | | | |

### 힌트 (막힐 때만)

- T05: 스레드 2개에 호출 4개(80, 120, 100, 150ms)를 제출 순서대로 넣는다. 각 스레드가 언제 비는지 시간축에 그려 보라.
- T06: `orTimeout`은 future에 무엇을 하는가? 그 future를 완료시키는 스레드는? `thenApply`를 한 단계 거친 예외는? (R8, R10)
- L02: 응답은 200ms에 폴백으로 나간다. 그런데 리뷰 호출은?

---

## 설계 판단 퀴즈 (D01~D06)

각 상황에 맞는 설계를 `Design.A` / `Design.B` / `Design.NO_CF` 중에서 고른다. **한 줄 근거**를 decision-guide의 질문 번호(Q1~Q5)와 함께 적는다.

### D01. 상품 요약 API
```java
// Spring MVC. 호출은 JDBC 하나뿐이다.
@GetMapping("/products/{id}/summary")
public ProductSummary summary(@PathVariable long id) {
    return CompletableFuture.supplyAsync(() -> productRepository.findSummary(id), dbPool).join();
}
```
"요청 스레드를 쓰지 않게 하려고 supplyAsync를 썼다"고 한다. 이 API에 맞는 설계는?

### D02. 장바구니 화면 API
Spring MVC. 회원 ID만 있으면 셋 다 호출할 수 있다: 회원 등급(JDBC, 60ms), 배송비 정책(RestTemplate, 110ms), 쿠폰 목록(RestTemplate, 90ms). 지금은 순차로 ≈260ms이고 SLO는 p99 150ms다.

### D03. 주문 상세 API
```java
Order order   = orderRepository.findById(orderId);            // JDBC 40ms
Member member = memberClient.get(order.memberId());            // RestTemplate 60ms (order 결과 필요)
Coupon coupon = couponRepository.findBest(member.grade());     // JDBC 30ms (member 결과 필요)
return new OrderDetail(order, member, coupon);
```
동료가 `supplyAsync(..., pool).thenCompose(...).thenCompose(...)`로 바꾸고 컨트롤러에서 `join()`하자고 제안했다. Spring MVC 서비스다.

### D04. "non-blocking 전환" PR
```java
// PR 제목: "상품 목록 API non-blocking 전환 (WebFlux 없이)"
public CompletableFuture<Page<Item>> list(SearchCond cond) {
    CompletableFuture<List<Item>> items = CompletableFuture.supplyAsync(() -> itemRepository.search(cond), dbPool); // JDBC 90ms
    CompletableFuture<Long> count = CompletableFuture.supplyAsync(() -> itemRepository.count(cond), dbPool);        // JDBC 70ms
    return items.thenCombine(count, Page::new);
}
```
이 코드가 **실제로** 구현한 설계는 무엇인가? (PR 제목이 아니라 코드를 보고 답한다)

### D05. API 게이트웨이의 프로필 화면
Spring WebFlux, 초당 3000 요청, 이벤트 루프 스레드 4개. 사용자 프로필은 Lettuce async(`RedisAsyncCommands.get`, 5ms)로, 추천 목록은 `WebClient`(80ms)로 가져온다. 둘은 서로 독립이고, 중간에 blocking 호출은 없다.

### D06. 설정 화면 API
Spring MVC. 서로 독립인 조회 두 개: Jedis(동기) `GET` 2ms, 로컬 Caffeine 캐시 조회 0.1ms. 응답 SLO는 100ms. 동료가 "독립이니까 병렬로 돌리자"고 한다.

---

## 채점 후 서술 문제 (journal에 답한다)

1. **PR 리뷰**: PR #1(`commonPoolFanOut`)과 PR #2(`joinEachCall`)에 남길 리뷰 코멘트를 [review-checklist](../docs/review-checklist.md)의 양식으로 각각 작성하라. 각 PR이 로컬 테스트를 통과한 이유도 적는다.
2. **T05와 Little**: T05(스레드 2개 풀)의 결과를 바탕으로, 이 풀로 100 RPS를 받으면 어떤 일이 일어나는지 설명하라. L01의 결과와 연결한다.
3. **T06과 L02**: "타임아웃을 걸었으니 느린 다운스트림이 있어도 안전하다"는 주장을 T06과 L02의 결과로 평가하라. 틀린 부분이 있다면 어디이고, 무엇을 추가해야 안전해지는가? 우선순위대로 세 가지.
4. **unwrap**: E1에서 필수 소스 실패를 `ProductDetailException`으로 바꾸는 지점과, 마지막에 unwrap하는 지점을 각각 어디에 두었는가? `handle`에서 `ex instanceof ProductDetailException`을 unwrap 없이 쓰면 어떤 일이 생기는가?
5. **D04**: 이 PR의 코드가 PR 제목대로 "non-blocking"인지 R0과 R4로 판정하라. 그렇지 않다면, 이 API를 진짜 B로 만들려면 무엇이 바뀌어야 하는가?
6. **E1만 있을 때**: 소스별 타임아웃(E2) 없이 리뷰 DB가 400ms로 느려지면 사용자는 무엇을 보는가? E2를 넣으면 어떻게 바뀌는가?

(여기에 작성)
