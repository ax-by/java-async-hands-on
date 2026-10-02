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

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

| 시나리오 | 답 | 근거 |
|---|---|---|
| S01 map | commonPool | R1: 미완료 상태에서 등록 → 완료시킨 스레드. HttpClient는 future를 `whenCompleteAsync(.., defaultExecutor())`로 완료시킨다 |
| S02 body / map | http-worker / commonPool | BodySubscriber는 HttpClient 실행기에서 돈다. 결과 future 완료는 실행기를 지정해도 commonPool |
| S03 map | eventLoop | R1: Netty 계열은 이벤트 루프가 future를 완료시킨다 |
| S04 ping / 감지 | 400 / true | R1+R4: 100ms에 이벤트 루프가 감사 로그 300ms를 떠안는다 → 150ms 예정이던 ping은 400ms에. sleep(=JDBC 대역)은 BlockHound가 감지 |
| S05 ping / 감지 | 150 / false | R2: blocking 본문이 jdbc 풀에서 실행된다. 이벤트 루프는 자유 |
| S06 apply | eventLoop | R1: 이미 완료된 future → **등록한 스레드**. 등록은 이벤트 루프 위의 thenCompose 함수 안에서 일어났다 |
| S07 fallback | eventLoop | R8+R2: 실패면 fn은 건너뛰고 실패를 그 자리(이벤트 루프)에서 전달한다. Async 홉은 fn에만 적용된다 |
| S08 returned / 감지 | 200 / true | R3·R4: completedFuture의 인자는 반환 전에 호출 스레드에서 계산된다. 이벤트 루프에서 부르면 이벤트 루프가 막힌다 |
| S09 결과 | TimeoutException | R4·R5: 이벤트 루프가 get으로 기다리는 동안 price 응답을 전달할 스레드가 없다(자기 자신). join이었다면 영원히 |
| S10 synchronized / lock | false / true | BlockHound는 메서드 호출을 가로챈다. 모니터 진입은 메서드가 아니다. ReentrantLock은 `Unsafe.park`로 감지 |
| S11 원격 처리 | true | R10: orTimeout은 상태만 바꾼다. 응답은 300ms에 여전히 이벤트 루프에서 처리된다 |
| S12 isCancelled / cancel(true) / cancel(false) / 파생 / orTimeout | false / false / true / false / true | HttpClient future는 cancel(true)일 때만 교환을 끊는다. 파생 future도 취소 능력을 물려받는다(R10의 예외). orTimeout은 상태만 바꾸고, f를 완료시켜 이후 cancel도 무력화한다. cancel(true) 후 f는 `CancellationException`을 원인으로 실패하지만 isCancelled()는 false(R8: unwrap해서 판단) |
| S13 결과 | HttpTimeoutException | 요청 타임아웃은 HttpClient가 교환을 끊고 future를 이 예외로 실패시킨다(unwrap 후) |
| S14 A 시간 / A 최대 / B 최대 / B<400 | 2000 / 10 / 100 / true | R7·R11: A는 풀 크기만큼만 동시 진행 → ceil(100/10)×200. B는 기다리는 동안 스레드를 점유하지 않으므로 100개가 동시에 진행 (이 머신 실측: A ≈ 2050~2190ms, 원격 서버가 본 동시 요청 최대 10 / B ≈ 220~250ms, 최대 100, HttpClient 실행기 스레드 2개) |

**서술 1**: 장점: 사용자 콜백(무거운 변환, 실수로 넣은 blocking)이 HttpClient의 I/O 처리 스레드를 점유하지 못한다. 라이브러리가 자기를 방어하는 설계다. 비용: 응답마다 스레드 전환이 한 번 더 생기고, 콜백이 **공용** 풀(commonPool)에서 실행되어 콜백의 blocking이 JVM 전체(parallel stream 등)를 굶긴다. BlockHound도 보지 않는다. Java 17 + vCPU 2개면 commonPool 병렬도가 1이라 `defaultExecutor()`가 ThreadPerTaskExecutor가 되어 **응답마다 새 스레드**가 생긴다(JDK 21에서 `-XX:ActiveProcessorCount=2`로 재현). 대비: 콜백 첫 단계를 `thenApplyAsync(fn, 내 실행기)`로 받아 실행 위치를 고정하거나, `-Djava.util.concurrent.ForkJoinPool.common.parallelism=2` 이상을 설정하고(JDK 21 + `ActiveProcessorCount=2`에서 이 설정으로 commonPool 완료로 돌아오는 것을 확인), 로컬(JDK 25)에서는 재현되지 않는다는 점을 알고 부하 테스트는 운영 JDK로 한다.

**서술 2**: 필요한 동시성 = 100/s × 0.3s = 30인데 스레드가 2개다. 이벤트 루프는 안전하지만(제출만 하고 손을 뗀다) jdbc 풀의 **무제한 큐가 끝없이 자란다**(초당 약 93건씩 적체). 감사 로그 지연이 분 단위로 늘고, 결국 메모리 문제가 된다. 이 결과(cart)를 기다리는 요청 응답도 함께 늦어진다. 설계: Little의 법칙으로 풀 크기를 정하고(30 + 여유, DB 커넥션 수와 함께), 큐에 상한과 거절 정책을 둔다. 감사 로그처럼 응답에 필요 없는 일이면 응답 경로에서 빼서(비동기 배치, 메시지 큐) 응답이 기다리지 않게 한다. 이벤트 루프에서 `CallerRunsPolicy`는 금지(R3 예외 1).

**서술 3**: (1) 이미 완료된 future에 이벤트 루프 위에서 콜백을 붙이는 경우(S06, R1: 등록 스레드). 캐시 히트, `completedFuture`, 앞서 join한 future가 여기에 해당한다. (2) Async 단계 뒤라도 **실패 경로**의 non-async 콜백(`exceptionally`, `handle`, `whenComplete`)은 앞 단계를 실패시킨 스레드(이벤트 루프)에서 실행될 수 있다(S07, R2+R8). 그 밖에 `thenCombine`/`allOf`는 마지막에 완료된 입력의 스레드라서 입력 중 하나가 이벤트 루프 future면 그 루프에서 실행될 수 있다(R1 표).

**서술 4**: BlockHound가 못 보는 점유를 의심한다. (a) `synchronized` 경합(S10), (b) 이벤트 루프 위의 무거운 CPU 작업(대량 직렬화, 렌더링, 암호화: E3), (c) NIO 파일 쓰기, DNS 조회 같은 감지 목록 밖의 blocking, (d) 이미 완료된 future에 붙인 무거운 콜백(S06), (e) GC 정지. 확인 수단: 이벤트 루프 지연 지표(이 repo의 `maxLagMillis`, 운영에서는 루프/명령 지연 p99), 지연 순간의 스레드 덤프나 JFR에서 `event-loop`/`nioEventLoop` 스레드의 스택(BLOCKED면 모니터 경합, RUNNABLE에 내 코드면 CPU), async-profiler의 wall-clock 프로파일.

**서술 5**: (a) f 자체가 TimeoutException으로 완료되어 사용자에게는 200ms에 기본 가격이 나가지만, **교환은 계속된다.** 장애 날 요청마다 연결이 10초씩 열려 있고 응답도 끝까지 읽는다. 동시 연결이 요청률 × 10초만큼 쌓여 연결/포트/원격 서버 자원이 고갈된다. (b) HttpClient가 200ms에 교환을 끊는다(HTTP/1.1이면 연결이 닫힐 수 있다: javadoc). 단, **응답 헤더까지만** 덮으므로 헤더는 빨리 오고 본문이 느린 경우는 막지 못한다. (c) 파생 future에 타임아웃을 걸어 **본문까지 포함한 전체**를 덮고, 원본 f를 `cancel(true)`해 교환을 끊는다. f는 아직 미완료라서 cancel이 효과가 있다. 차이는 상한의 범위(헤더까지 vs 전체)와 폴백 콜백이 실행되는 스레드다. (c)의 폴백은 JDK 타임아웃 스케줄러 스레드에서 실행되므로 가볍게 유지해야 한다(R10). 실무에서는 (b)를 기본으로 두고, 본문이 클 수 있으면 (c)를 겹쳐 쓴다. 어느 경우든 요청은 이미 서버에 도착했을 수 있다(멱등성).

**서술 6**: A: 2000/s × 0.2s × 3 = 1200개의 스레드가 동시에 blocking한다(병렬로 실행해도 각 호출이 스레드를 하나씩 잡는다). 스레드 스택 메모리, 컨텍스트 스위칭, 풀 큐 적체가 문제다. B: 스레드는 수 개면 되지만 **원격 서버에 동시 요청 1200개**가 그대로 꽂힌다. 그래서 (1) 다운스트림별 동시성 상한(E6 같은 non-blocking 제한기, 대기 큐 상한과 거절), (2) 연결 수와 커넥션 풀 한도, (3) 실제로 교환을 끊는 타임아웃(서술 5), (4) 이벤트 루프 위 blocking 0을 지키는 리뷰/BlockHound/지연 지표, (5) 컨텍스트 전파 방식을 설계해야 한다. "스레드를 아낀다"는 B의 장점은 이 다섯 가지를 설계한다는 전제에서만 성립한다.

**서술 7**: HttpClient future 뒤의 non-async 콜백은 commonPool(또는 이미 완료됐다면 등록 스레드)에서 실행되므로 요청 스레드의 MDC가 없다(R12). 공용 "컨텍스트 전파 실행기"로 `thenAcceptAsync(fn, 감싼 실행기)`를 해도 **고쳐지지 않는다**. 감싼 실행기는 `execute()`를 호출한 스레드의 컨텍스트를 캡처하는데, 여기서 `execute()`를 호출하는 것은 future를 완료시킨 commonPool 스레드라서 빈 컨텍스트가 전파된다. 고치는 법: 파이프라인을 **조립하는 시점(요청 스레드)에 캡처**한다. `MDC.getCopyOfContextMap()`을 지역 변수로 잡아 두고 람다 안에서 `setContextMap` → 로그 → `finally`에서 이전 값으로 복원하거나(복원 누락 시 그 스레드를 쓰는 다른 요청에 traceId가 남는다), 요청마다 조립 시점에 `ContextSnapshot.captureAll().wrapExecutor(pool)`로 만든 실행기를 그 단계에 넘긴다. traceId만 필요하면 값으로 캡처해 로그 인자로 직접 넘기는 것이 가장 단순하다.
<!-- ANSWER-END -->
