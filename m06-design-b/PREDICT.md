# m06 예측 시트

`Scenarios.java`를 읽고 **코드를 실행하기 전에** 채운다. "근거 규칙"이 비어 있으면 예측한 것이 아니라 찍은 것이다.
README와 [추론 규칙](../docs/reasoning-rules.md)의 **규칙만으로** 추론한다. 시나리오별 관찰 결과는 채점한 뒤에 확인한다.

| 시나리오 | 항목 | 예측 | 근거 규칙과 한 줄 이유 | 결과 (채점 후) | 틀렸다면 무엇을 잘못 적용했나 |
|---|---|---|---|---|---|
| S01 | map (기본 HttpClient) | | | | |
| S02 | body (BodySubscriber) | | | | |
| S02 | map | | | | |
| S03 | map (이벤트 루프 클라이언트) | | | | |
| S04 | ping 처리 시점 (ms) | | | | |
| S04 | BlockHound 감지 (true/false) | | | | |
| S05 | ping 처리 시점 (ms) | | | | |
| S05 | BlockHound 감지 | | | | |
| S06 | apply | | | | |
| S07 | fallback (실패 경로) | | | | |
| S08 | returned (ms) | | | | |
| S08 | BlockHound 감지 | | | | |
| S09 | 결과 ("OK" 또는 예외 이름) | | | | |
| S10 | synchronized 경합 감지 | | | | |
| S10 | ReentrantLock 경합 감지 | | | | |
| S11 | 폴백 후에도 원격 응답 처리됨 | | | | |
| S12 | cancel(true) 후 f.isCancelled() | | | | |
| S12 | cancel(true) 후 교환 계속(BodyHandler 호출) | | | | |
| S12 | cancel(false) 후 교환 계속 | | | | |
| S12 | 파생 future cancel(true) 후 교환 계속 | | | | |
| S12 | f.orTimeout 후 교환 계속 | | | | |
| S13 | 결과 (예외 이름) | | | | |
| S14 | A 총 시간 (ms) | | | | |
| S14 | A 원격 서버 동시 요청 최대 | | | | |
| S14 | B 원격 서버 동시 요청 최대 | | | | |
| S14 | B 총 시간 < 400ms | | | | |

## 채점 후 서술 문제 (journal에 답한다)

1. (S01, S02) S01, S02에서 확인한 `sendAsync` future의 완료 스레드를 보고, JDK HttpClient가 그렇게 설계한 이유를 추측하라. 그 설계의 **장점과 비용**을 하나씩 들어라. 운영이 **Java 17, vCPU 2개 컨테이너**라면 무슨 일이 생기고, 어떻게 대비하는가?
2. (S04, S05) S05의 `jdbc` 풀은 스레드 2개다. 감사 로그가 초당 100건(건당 300ms) 들어온다면 무슨 일이 생기는가? 이벤트 루프는 안전한가? 대신 어디가 아파지고, 그것을 어떻게 설계로 다루는가?
3. (S06, S07) 코드 리뷰에서 "이 람다가 이벤트 루프에서 실행될 수 있는가?"를 판단할 때, 바로 앞 future가 클라이언트 future인지만 보면 충분한가? S06, S07의 결과를 근거로 답하고 각각의 근거 규칙을 적어라.
4. (E3) BlockHound 위반은 0건인데 Redis 명령 지연 p99가 수백 ms로 튄다. 무엇을 의심하고, 어떤 지표/도구로 확인하는가? 후보를 세 가지 이상 들어라.
5. (S12, S13) "가격 API 타임아웃 200ms, 넘으면 기본 가격" 요구사항의 구현 세 가지를 비교하라. (a) `f.orTimeout(200ms).exceptionally(..)` (b) `HttpRequest.timeout(200ms)` + `exceptionally` (c) `f.thenApply(..).orTimeout(200ms).exceptionally(ex -> { f.cancel(true); .. })`. 가격 API가 장애로 10초씩 걸리는 날 각각 연결과 원격 서버에는 무슨 일이 생기는가? (b)와 (c)는 무엇이 다른가?
6. (S14, R11) 초당 2000요청, 요청마다 독립 원격 호출 3개(각 200ms)인 BFF가 있다. A로 만들면 blocking 풀에 스레드가 몇 개 필요한가(Little의 법칙)? B로 만들면 스레드 문제는 사라지지만 **대신 무엇을 설계해야 하는가**?
7. (R12) B 파이프라인의 마지막 단계에서 traceId가 찍힌 로그를 남겨야 한다. 그 단계는 HttpClient future 뒤의 non-async 콜백이다. 무엇이 문제이고, 어떻게 고치는가?

(여기에 작성)
