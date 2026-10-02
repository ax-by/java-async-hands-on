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

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

| 항목 | 답 | 근거 |
|---|---|---|
| T01 | 450 / caller | 순차: 80 + 120 + 100 + 150 (R7). 요청 스레드가 직접 blocking (R0) |
| T02 | 150 / commonPool | R2: executor 생략 → commonPool(병렬도 9 ≥ 4라서 4개가 동시에). **빠르지만 틀린 코드**다 |
| T03 | 450 | 시작하자마자 join → 순차. 반환 전에 이미 다 끝났다 (R5, R7) |
| T04 | 0 / 150 | 제출만 하고 반환(R3), 모두 먼저 시작 → max (R7) |
| T05 | 270 | io-1: product 0→80, stock 80→180 / io-2: price 0→120, reviews 120→270 (R7 풀 상한) |
| T06 | 300 / 1 / CompletionException / timeoutScheduler | orTimeout이 결과 future를 `TimeoutException`으로 완료(R10). price 호출은 계속 실행 중(R10). `thenApply`를 거치며 `CompletionException`으로 감싸짐(R8). 미완료 상태에서 등록 → 타임아웃을 발화한 스케줄러 스레드가 실행(R1, R10) |
| L01 | ALL_OK / MANY_TIMEOUTS | 필요 스레드 45(Little). 60은 충분, 16은 처리 능력이 수요의 약 1/3이라 큐가 계속 쌓여 300ms를 넘는다 (실측 31/40 실패) |
| L02 | MANY_TIMEOUTS | 리뷰 호출은 응답 후에도 1초씩 스레드를 점유(R10). 100 RPS × 1s = 100 > 60 → 풀 고갈, 필수 소스까지 밀림 (실측 약 100/150 실패) |
| D01 | NO_CF | Q1 아니오: 호출이 하나뿐이다. 게다가 곧바로 join하므로 요청 스레드는 그대로 막히고, dbPool 스레드 하나를 더 쓴다 |
| D02 | A | Q1 예(독립 3개), Q2 아니오(JDBC, RestTemplate), Q3 예(260 → ≈110ms, SLO 150ms 충족). 전용 풀 + 전체 타임아웃 |
| D03 | NO_CF | Q1 아니오: 모든 호출이 앞 결과에 의존한다. thenCompose 체인도 총 시간은 합(130ms)이고(R7), 결국 join하므로 동기 코드와 같은 성능에 복잡도만 늘어난다 |
| D04 | A | 두 JDBC 쿼리를 dbPool에서 병렬로 실행했을 뿐이다. JDBC가 dbPool 스레드를 90ms 막는다(R4). 응답시간 160 → 90ms는 맞지만 non-blocking(B)은 아니다(R0) |
| D05 | B | Q2 예: Lettuce async와 WebClient는 I/O 동안 스레드를 점유하지 않는다. Q5 예: blocking 0. 이벤트 루프 4개로 3000 RPS를 처리하려면 B여야 한다 |
| D06 | NO_CF | Q3 아니오: 병렬로 줄어드는 시간은 최대 0.1ms. 스레드 전환 비용과 컨텍스트 유실 위험(R12)만 늘어난다 |

L01 보충 실측 (100 RPS × 40 요청, 전체 타임아웃 300ms): 풀 60 → 전부 성공(최대 ≈155ms), 45(= Little) → 전부 성공(≈170ms), 40 → 전부 성공(≈215ms, SLO 초과 시작), 30 → 10개 실패, 16 → 31개 실패. 계산값 아래로 내려가면 급격히 무너진다.

L02 보충 실측: 같은 부하(풀 60, 150 요청)에서 리뷰 호출이 **클라이언트 타임아웃으로 200ms에 실패**하도록 바꾸면 150개가 모두 성공했다(최대 ≈203ms). 스레드를 실제로 풀어 주는 것은 클라이언트 타임아웃이다.

서술 1 (예시):
```
[R2][#4] commonPoolFanOut L3-6: executor 없이 supplyAsync 로 JDBC/RestTemplate 을 호출한다. 이 blocking 호출들이
commonPool 워커를 점유한다(R2, R11). 로컬(CPU 10개, 병렬도 9)에서는 150ms 로 빨라 보이지만, 피크에 commonPool 이
고갈되면 같은 JVM 의 parallel stream·다른 기능까지 느려진다. Java 17 CPU 2개 컨테이너에서는 요청마다 새 스레드
(Thread-N)가 생긴다. → 전용 io 풀을 생성자로 받아 supplyAsync(.., io) 로 넘길 것. 전체 타임아웃과 폴백도 없다(R10, R9).

[R7][#7] joinEachCall L2-5: 각 supplyAsync 직후 join 하므로 네 호출이 순차로 실행된다(450ms). 요청 스레드도
450ms 동안 막힌 뒤 이미 완료된 future 를 반환하므로 "비동기" API 처럼 보일 뿐이다. → 네 future 를 먼저 모두 만들고
allOf/thenCombine 으로 모을 것(R3, R7).
```
로컬 테스트를 통과한 이유: 결과값만 검증했기 때문이다. PR #1은 CPU가 많은 개발 PC에서 commonPool이 충분히 컸고 동시 요청이 1개였다. PR #2는 응답시간이나 반환 시점을 재지 않았다.

서술 2: 스레드 2개의 처리 능력은 초당 2 / 0.1125s ≈ 18 호출이다. 100 RPS는 초당 400 호출을 요구한다. 수요가 공급의 20배가 넘으므로 큐가 끝없이 자라고, 대기시간이 300ms를 넘는 순간부터 거의 모든 요청이 타임아웃 난다. L01의 16 스레드(공급이 수요의 약 1/3)에서도 40개 중 31개가 실패했다. Little의 법칙으로 필요한 45개보다 작으면 "조금 느려지는" 게 아니라 무너진다.

서술 3: 틀린 부분은 "타임아웃이 일을 멈춘다"는 가정이다. CF 타임아웃은 future의 상태만 바꾸고 늦은 호출은 스레드와 커넥션을 계속 점유한다(R10). 그래서 다운스트림이 느려지면 늦은 호출이 풀을 채우고, 결국 정상 요청까지 실패한다(L02). 추가할 것: (1) **클라이언트 자체 타임아웃**(RestTemplate connect/read timeout, JDBC query timeout)을 소스 타임아웃 이하로 설정해 스레드를 실제로 풀어 준다. (2) 느려지기 쉬운 선택 소스를 **별도 풀(bulkhead)**로 분리해 필수 소스의 스레드를 보호한다. (3) **제한 큐 + 거절 → 503**, 그리고 풀 여유분. 가능하면 서킷 브레이커로 장애 중인 다운스트림 호출을 아예 건너뛴다.

서술 4: 필수 소스는 소스별 `exceptionally`에서 `unwrap(ex)`한 원인으로 `ProductDetailException`을 만든다(가장 가까운 곳에서 의미를 붙인다). 마지막 `handle`에서 한 번 더 unwrap해 도메인 예외는 그대로 다시 던지고 `TimeoutException`은 TIMEOUT으로 바꾼다. unwrap 없이 `ex instanceof ProductDetailException`을 쓰면, 예외가 `allOf`/`thenApply`를 거치며 `CompletionException`으로 감싸져 있으므로 항상 false다. 그 결과 PRICE_UNAVAILABLE이 엉뚱한 분기(예: TIMEOUT이나 일반 500)로 처리된다(R8).

서술 5: `supplyAsync`는 JDBC 호출을 **다른 스레드로 옮겼을 뿐**이고, JDBC는 응답까지 그 dbPool 스레드를 점유한다(R4). 요청당 막히는 스레드 시간은 그대로이고(R0), 동시 요청이 늘면 dbPool이 고갈된다. 진짜 B가 되려면 드라이버가 비동기여야 한다. R2DBC 같은 non-blocking 드라이버 + WebFlux(또는 CompletionStage를 끝까지 전달하는 스택)로 바꿔서 I/O 동안 어떤 스레드도 점유하지 않게 해야 한다. 그게 불가능하면 이 코드는 A라고 정직하게 부르고 풀 크기와 타임아웃을 A의 기준으로 설계한다.

서술 6: E1만 있으면 리뷰 DB가 400ms일 때 결과가 400ms에야 모이고, 그전에 전체 타임아웃 300ms가 발화해 **요청 전체가 TIMEOUT으로 실패**한다. 선택 소스 하나 때문에 상품 상세가 통째로 안 보인다. E2를 넣으면 200ms에 리뷰를 빈 목록으로 포기하고 나머지로 응답하므로 사용자는 리뷰만 빠진 페이지를 본다. 단, 늦은 리뷰 호출은 여전히 스레드를 400ms 점유한다(R10, 서술 3).
<!-- ANSWER-END -->
