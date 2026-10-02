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

(여기에 작성)
