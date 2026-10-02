# DECISION: 상품 상세 집계 API

> **코드를 쓰기 전에** 채운다. 양식은 [decision-guide.md](../docs/decision-guide.md)를 따른다. 근거에는 반드시 숫자를 넣는다.
> 채운 뒤 `ProductDetailAggregator`(E1, E2)와 `ProductDetailPool`(E3)을 구현하고, 실측 결과와 다르면 이 문서를 고친다.

## 주어진 조건

| 항목 | 값 |
|---|---|
| 서비스 | Java 17, Spring MVC(Tomcat), `GET /products/{id}` |
| 다운스트림 | product(JDBC, 80ms, **필수**), price(RestTemplate, 120ms, **필수**), stock(RestTemplate, 100ms, 선택 → "UNKNOWN"), reviews(JDBC, 150ms, 선택 → 빈 목록) |
| 현재 구현 | 순차 호출 `Scenarios.naiveSequential` |
| SLO | 평소 p99 < 200ms, 전체 하드 타임아웃 300ms |
| 실패 정책 | 필수 소스 실패 → 요청 실패(도메인 예외 `ProductDetailException`, 원래 예외를 cause로). 선택 소스 실패/지연 → 폴백 |
| 트래픽 | 피크 100 RPS |
| 그 밖에 | HikariCP 커넥션 풀은 기본값(`maximumPoolSize` 10)으로 운영 중 |

---

## 결정
- 목적: A / B / 쓰지 않음
- 한 줄 근거:

## 근거
- 독립적인 호출:
- 클라이언트 특성:
- 기대 효과 (숫자로):

## 실행 모델
- 각 단계의 실행 스레드:
- executor (이름, 크기, 큐, 거절 정책, 크기의 근거):
- blocking 지점과 그 지점이 실행되는 스레드:
- 다운스트림 한도 확인 (DB 커넥션):

## 실패 모델
- 전체 타임아웃:            부분 실패 정책:
- 소스별 타임아웃:          예외 unwrap 위치:
- 타임아웃 후 남은 작업 처리 (R10):

## 이 설계가 틀렸다면 어떤 증상이 보일까
-

## (실측 후) 예측과 달랐던 점
-

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

## 결정
- 목적: **A (병렬 blocking fan-out)**
- 한 줄 근거: 네 호출은 모두 productId만 있으면 되는 **독립 호출**이고 클라이언트가 전부 **blocking**(JDBC, RestTemplate)이라 B는 불가능하다. 전용 io 풀에서 동시에 실행해 450ms → ≈150ms로 줄여 SLO(p99 < 200ms)를 맞춘다.

## 근거
- 독립적인 호출: product, price, stock, reviews 모두 입력이 productId뿐이고 서로의 결과를 쓰지 않는다. 의존 체인이 없으므로 R7에 따라 총 시간은 합이 아니라 최댓값이 된다.
- 클라이언트 특성: JDBC와 RestTemplate은 결과를 **값으로** 반환한다(future/콜백이 아니다). 반환될 때까지 호출 스레드를 점유한다. 그래서 `supplyAsync`로 감싸도 **막히는 스레드가 Tomcat 스레드에서 io 스레드로 바뀔 뿐**이다(R0). 이것을 B라고 부르면 안 된다.
- 기대 효과: 순차 80 + 120 + 100 + 150 = **450ms → max = 150ms** (T01 ≈450ms, T04 ≈150ms 실측). 스레드는 아끼지 않는다. 요청당 blocking 스레드 시간은 450 thread·ms로 같고, 오히려 io 스레드 4개를 동시에 쓴다.

## 실행 모델
- 각 단계의 실행 스레드:
  - 네 호출: `supplyAsync(.., io)` → **io** (R2). 요청 스레드는 제출만 하고 즉시 반환(R3).
  - 소스별 `exceptionally`(폴백/도메인 예외 변환): 미완료 상태에서 등록 → 그 소스를 실패시킨 **io** 스레드(R1). 가벼운 코드만 둔다.
  - `allOf(..).thenApply(조립)`: 마지막으로 끝난 입력의 스레드 = 보통 reviews를 실행한 **io**(R1). 안의 `join()`은 모두 완료된 future라 막지 않는다(R5).
  - 타임아웃으로 끝나면 그 뒤의 `handle`은 **타임아웃 스케줄러 스레드**(R10, T06 실측). JVM에 하나뿐이므로 여기서는 예외 변환만 한다.
- executor: 이름 `product-io`, 고정 크기 **59**, 큐 **제한**(예: 30), `AbortPolicy` → (m04 E2의 `supplySafely`처럼) 실패한 future → 503.
  - 크기 근거(Little, R11): 100 RPS × (0.08 + 0.12 + 0.10 + 0.15)s = **45** 동시 스레드. 지연 변동과 타임아웃 후에도 도는 호출(R10)을 위해 ×1.3 → 59.
  - 실측(100 RPS, 요청 40개): 45 → 최대 ≈170ms 전부 성공, 40 → 최대 ≈215ms, 30 → 40개 중 10개 타임아웃, 16 → 31개 타임아웃(L01). 계산값 아래로 내려가면 급격히 무너진다.
  - 큐 근거: 큐에서 50ms 이상 기다리면 300ms 안에 끝내기 어렵다. 59 스레드 × 50ms / 평균 호출 112ms ≈ 26 → 30 정도. 무제한 큐는 과부하를 p99 폭증으로 숨긴다(m04 S05).
- blocking 지점: 네 다운스트림 호출 모두 **io 스레드에서만**. Tomcat 스레드는 `load()`를 호출하고 바로 반환한다. 컨트롤러가 `CompletableFuture`를 그대로 반환하면 Spring MVC가 비동기 응답으로 처리하므로 Tomcat 스레드도 반납된다. 컨트롤러가 `join()`한다면 Tomcat 스레드가 150ms 동안 막히지만 A의 목적(응답시간 단축)은 그대로 달성된다. 이때 필요한 스레드는 Tomcat + io다.
- 다운스트림 한도: JDBC 호출(product 80ms + reviews 150ms)은 요청당 0.23s → 100 RPS × 0.23 = **23개의 커넥션이 동시에 필요**하다. Hikari 기본값 10으로는 부족하다. io 스레드가 커넥션을 기다리며 막히고(R4), 그만큼 io 풀도 고갈된다. → `maximumPoolSize` ≥ 25~30으로 올리고 DB 측 한도와 협의한다. 못 올리면 io 풀이 아니라 커넥션이 병목이므로 스레드를 늘려도 소용없다(m04 E1).

## 실패 모델
- 전체 타임아웃: `orTimeout(300ms)`를 **조립된 결과**에 건다. 넘으면 `ProductDetailException(TIMEOUT, TimeoutException)`.
- 부분 실패 정책: product/price 실패 → 요청 실패(PRODUCT_/PRICE_UNAVAILABLE). stock → "UNKNOWN", reviews → 빈 목록.
- 소스별 타임아웃: 선택 소스에 `completeOnTimeout(폴백, 200ms)` (E2). 리뷰 하나가 느리다고 요청 전체가 300ms에 실패하면 안 되기 때문이다. 필수 소스에는 폴백이 없으므로 전체 타임아웃만으로 충분하다.
- 예외 unwrap 위치: (1) 필수 소스별 `exceptionally`에서 원인을 unwrap해 도메인 예외로 바꾸고, (2) 마지막 `handle` **한 곳**에서 다시 unwrap해 도메인 예외는 그대로 던지고 `TimeoutException`은 TIMEOUT으로 바꾼다(R8). `instanceof`는 unwrap한 뒤에만 쓴다.
- 타임아웃 후 남은 작업 처리 (R10): `orTimeout`/`completeOnTimeout`은 **응답시간만** 제한한다. 늦은 호출은 끝날 때까지 io 스레드와 DB 커넥션을 계속 점유한다(T06: 타임아웃 시점에 price 호출 1개가 여전히 진행 중).
  - L02 실측: 리뷰 DB가 1000ms로 느려지면, 리뷰에 200ms 소스 타임아웃이 있어도 늦은 리뷰 호출이 스레드를 1초씩 잡는다. 100 RPS × 1s = 리뷰만으로 100 스레드가 필요해 풀(60)이 고갈되고, 필수 소스까지 큐에서 밀려 **150개 중 약 100개가 실패**했다.
  - 그래서 **진짜로 스레드를 풀어 주는 타임아웃은 클라이언트 자체의 타임아웃**이다: RestTemplate의 connect/read timeout(기본값은 **무한**: `SimpleClientHttpRequestFactory` -1, `HttpURLConnection` 0), JDBC `Statement.setQueryTimeout`/드라이버 socket timeout. 소스 타임아웃과 같은 값(≈200ms)으로 맞춘다. (리뷰 호출이 200ms에 실패하도록 바꾸면 같은 부하에서 150개가 모두 성공했다.)
  - 추가 방어: 선택 소스를 **별도 풀(bulkhead)**로 분리해 리뷰 장애가 필수 소스의 스레드를 빼앗지 못하게 한다(m04 E5). 풀 여유분(×1.3)은 평소 변동용이지 장애 대응용이 아니다.

## 이 설계가 틀렸다면 어떤 증상이 보일까
- 풀이 작다: 평소엔 괜찮다가 피크에 io 큐가 쌓이고 p99가 오르다가 300ms 타임아웃이 급증한다(L01).
- executor를 생략했다(PR #1): 로컬(CPU 10개)에서는 150ms로 빨라 보이지만 commonPool을 막는다. 같은 JVM의 parallel stream 등이 느려지고, Java 17 소형 컨테이너에서는 요청마다 스레드가 생긴다(m04 C01, V01).
- 호출마다 join(PR #2): 전용 풀을 써도 순차라 450ms 그대로다. 요청 스레드도 450ms 막힌다(T03).
- 클라이언트 타임아웃 없음: 다운스트림 하나가 느려지면 io 풀이 늦은 호출로 가득 차고 관계없는 필수 소스까지 타임아웃이 난다(L02).
- unwrap 누락: 필수 소스 실패가 TIMEOUT이나 500으로 잘못 분류된다. `@ExceptionHandler(ProductDetailException.class)`가 동작하지 않는다.
- Hikari 커넥션 부족: io 스레드가 `getConnection()`에서 막히고, 결국 `SQLTransientConnectionException`(connection is not available)과 타임아웃이 함께 늘어난다.

## 이 API에서 "CF를 쓰지 않음"이 정답이 되는 경우
- 다운스트림이 하나뿐이거나(예: 상품 요약 API = product만), 앞 결과가 다음 호출에 필요한 순차 의존이면 병렬화할 것이 없다. 동기 코드가 같은 시간에 더 단순하다(D01, D03).
- 병렬화로 줄어드는 시간이 SLO에 의미가 없을 때(예: 2ms + 0.1ms)도 쓰지 않는다(D06).
<!-- ANSWER-END -->
