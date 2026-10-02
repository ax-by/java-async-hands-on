# m06 설계 판단: 주문 화면 BFF (E4 `Ex4IsolatedJdbc`)

E4를 구현하기 **전에** 아래 양식을 채운다. [판단 가이드](../docs/decision-guide.md)의 질문 흐름(Q1~Q5)을 따라가고, 근거는 3단계 "합격" 수준(숫자, 풀, 실패 정책)으로 쓴다.

## 상황

주문 화면 API(`GET /orders/view`)를 다시 만든다. 지금은 Spring MVC 컨트롤러가 아래 세 호출을 **순차로** 한다.

| 호출 | 클라이언트 | 지연 (p99) | 비고 |
|---|---|---|---|
| ① 세션 조회 `session:{token}` → userId | Redis, **Lettuce** (Netty 이벤트 루프) | 30ms | 비동기 API(`RedisAsyncCommands`)가 future를 반환한다 |
| ② 회원 정보 `SELECT ... WHERE id = ?` | MySQL, **JDBC** + HikariCP | 100ms | 비동기 드라이버를 쓸 수 없다(사내 표준) |
| ③ 추천 목록 `GET /reco?user={userId}` | 사내 추천 API, **JDK HttpClient** | 100ms | `sendAsync` 사용 가능 |

- ②와 ③은 userId만 있으면 서로 독립이다. ①은 둘 다의 전제다.
- 피크 트래픽 **250 rps**. 응답 SLO **p99 200ms**. 지금 p99는 30 + 100 + 100 = **230ms**로 SLO를 넘는다.
- Tomcat 요청 스레드 200개. Spring MVC 컨트롤러는 `CompletableFuture`를 반환할 수 있다(서블릿 비동기 처리: 요청 스레드를 반납한다).
- MySQL은 다른 API와 공유한다. 이 API에 허용된 동시 커넥션은 **30개**다.
- 추천이 실패하거나 늦으면 "추천 없음"으로 화면을 보여 줘도 된다. 회원 정보가 없으면 화면을 만들 수 없다.

## 결정
- 목적: A / B / 쓰지 않음
- 한 줄 근거:

## 근거
- 독립적인 호출:
- 클라이언트 특성: (각 호출별로 blocking / non-blocking, 무엇을 보고 판단했나)
- 기대 효과 (숫자로):

## 실행 모델
- 각 단계의 실행 스레드: (R1/R2 근거와 함께)
- executor: 이름, 크기, 큐, 거절 정책, 그리고 그 크기의 근거 (R11)
- blocking 지점과 그 지점이 실행되는 스레드:

## 실패 모델
- 전체 타임아웃:            부분 실패 정책:
- 예외 unwrap 위치:          타임아웃 후 남은 작업 처리: (R10)

## 이 설계가 틀렸다면 어떤 증상이 보일까
-

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

## 결정
- 목적: **B, 단 ② 회원 DB 조회 구간은 A식 격리** (전용 `jdbc` 풀)
- 한 줄 근거: ①과 ③은 진짜 비동기 클라이언트(Lettuce, HttpClient.sendAsync)라서 기다리는 동안 스레드를 점유하지 않고, ②만 JDBC라서 그 구간만 크기를 계산한 전용 blocking 풀로 보낸다. ②와 ③을 동시에 실행해 p99를 230ms → 130ms로 줄여 SLO(200ms)를 맞춘다.

## 근거
- 독립적인 호출: ②와 ③은 userId만 필요하고 서로의 결과를 쓰지 않는다. ①은 둘의 전제이므로 순차(`thenCompose`).
- 클라이언트 특성: Lettuce async와 HttpClient.sendAsync는 future를 반환하고 소수의 I/O 스레드만 쓴다(요청이 늘어도 스레드 수가 그대로). JDBC는 호출 스레드가 응답까지 소켓 read에서 멈춘다(blocking).
- 기대 효과: 응답시간 30 + 100 + 100 = 230ms → 30 + max(100, 100) = **130ms**. 스레드 점유: 지금은 요청마다 Tomcat 스레드 1개가 230ms 점유(250 rps × 0.23s ≈ 58개 상시 점유). B에서는 요청 스레드를 바로 반납하고, 대기 중 점유하는 스레드는 ② 구간의 jdbc 스레드 1개 × 100ms뿐이다(250 × 0.1 = 평균 25개).

## 실행 모델
| 단계 | 실행 스레드 | blocking? |
|---|---|---|
| `sessionStore.fetch(..)` 호출 | 요청 스레드 (즉시 반환, R3) | 아니오 |
| `thenCompose(session -> ...)` 함수 | Lettuce 이벤트 루프 (R1: 미완료 상태에서 등록) | 아니오. **future를 만들기만 한다** |
| `supplyAsync(() -> userDb.call(id), jdbc)` 본문 | `jdbc` 풀 (R2) | **예, 100ms. 유일한 blocking 지점** |
| `recoApi.fetch(..)` / `sendAsync` | 이벤트 루프에서 호출만 (R3) | 아니오 |
| `thenCombine(..)` 합치기 | 나중에 끝난 쪽: jdbc 스레드 또는 추천 클라이언트의 완료 스레드 (R1 표) | 아니오 (문자열 조립만) |

- executor `jdbc`: 고정 30 스레드 = 이 API의 DB 커넥션 예산 30과 같게(풀이 커넥션보다 크면 스레드가 Hikari 대기로 막힐 뿐이다). Little: 250 rps × 0.1s = 25 → 여유 20%. 큐는 **상한 100**(약 0.3초 분량), 넘치면 `AbortPolicy` → `RejectedExecutionException`은 `supplyAsync`가 **동기적으로 던진다**(R3 예외 2). 이것이 이벤트 루프 위의 `thenCompose` 함수 안에서 던져지면 결과 future가 그 예외로 실패하므로, 바깥에서 503으로 변환한다. `CallerRunsPolicy`는 이벤트 루프에서 JDBC를 실행하게 되므로 **금지**.
- blocking 지점: ② 하나, `jdbc` 스레드에서만. 이벤트 루프와 HttpClient 완료 스레드(commonPool) 위 blocking은 0건이어야 한다(BlockHound + 루프 지연 지표로 확인).

## 실패 모델
- 전체 타임아웃: 180ms(`orTimeout`은 파생 future에). 부분 실패 정책: ③ 추천 실패/지연 → `exceptionally`로 "추천 없음"(부분 결과), ② 회원 실패 → 전체 실패(5xx), ① 세션 없음 → 401.
- 예외 unwrap 위치: 컨트롤러로 돌려주기 직전의 `handle`/`exceptionally` 한 곳에서 unwrap(R8)해 `RejectedExecutionException` → 503, `TimeoutException` → 504, 그 외 → 500으로 분류.
- 타임아웃 후 남은 작업(R10): `orTimeout`은 상태만 바꾼다. ③은 원본 `sendAsync` future를 `cancel(true)`(또는 `HttpRequest.timeout`)로 실제로 끊는다. ②의 JDBC는 멈출 수 없으므로 **JDBC 쿼리 타임아웃**(`Statement#setQueryTimeout`, 커넥션 풀/드라이버 설정)을 따로 걸어 jdbc 스레드와 커넥션이 오래 묶이지 않게 한다.

## 이 설계가 틀렸다면 어떤 증상이 보일까
- ②를 `thenApply`로 이벤트 루프에서 실행했다면: Lettuce 명령 지연 p99 급등, 이 API와 무관한 Redis 호출까지 느려짐, BlockHound 위반(E4 테스트의 `maxLagMillis`, 위반 기록).
- `supplyAsync(..)`에 executor를 빠뜨렸다면: JDBC가 commonPool에서 실행 → BlockHound는 조용하지만 parallel stream과 HttpClient 콜백이 함께 느려짐. Java 17 소형 컨테이너에서는 호출마다 새 스레드.
- `jdbc` 풀이 너무 작다면: 풀 큐 적체 → p99만 튐 → 큐 상한에 닿으면 503 증가. 너무 크다면: Hikari 커넥션 대기로 스레드가 막히고 DB 부하 증가.
- ②와 ③을 순차(`thenCompose` 중첩)로 만들었다면: p99가 230ms 그대로(SLO 위반).
- 타임아웃 후 교환을 끊지 않았다면: 추천 API 장애 시 열린 연결 수가 계속 늘어난다.
<!-- ANSWER-END -->
