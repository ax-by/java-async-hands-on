# m07-vt 예측 시트

`VtScenarios.java`를 읽고 **실행하기 전에** 채운다. 근거에는 R 번호나 이 모듈의 VT 번호(README)를 쓴다.

| 시나리오 | 항목 | 예측 | 근거 규칙과 한 줄 이유 | 결과 (채점 후) | 틀렸다면 무엇을 잘못 적용했나 |
|---|---|---|---|---|---|
| V01 | CF allOf: 예외가 나오기까지 (ms) | | | | |
| V01 | CF: interrupt 된 형제 작업 수 | | | | |
| V02 | StructuredTaskScope: 예외가 나오기까지 (ms) | | | | |
| V02 | StructuredTaskScope: interrupt 된 형제 작업 수 | | | | |
| V03 | 플랫폼 200개 풀, 100ms × 2,000 (ms) | | | | |
| V03 | 작업마다 가상 스레드, 100ms × 2,000 (ms) | | | | |
| V04 | VT executor 작업의 ThreadLocal | | | | |
| V04 | VT executor 작업의 InheritableThreadLocal | | | | |
| V05 | 다음 요청(t-200): 플랫폼 1개 풀의 ITL | | | | |
| V05 | 다음 요청(t-200): VT executor 의 ITL | | | | |
| V06 | ScopedValue: STS fork | | | | |
| V06 | ScopedValue: VT executor submit | | | | |
| V06 | ScopedValue: CF.supplyAsync | | | | |
| V07 | synchronized + 100ms, VT (4 × 코어)개, JDK 25 (ms) | | | | |

## 채점 후 서술 문제 (journal에 답한다)

1. V01과 V02는 같은 서비스, 같은 실패다. 운영에서 "상품 DB가 죽었을 때" 두 구현의 차이가 어떤 지표(응답시간, 스레드 점유, 다운스트림 부하)로 나타나는가? Java 17에서 CF로 V02에 가깝게 만들려면 무엇을 직접 해야 하는가? (R10)
2. V03를 보고 "VT를 쓰면 DB 커넥션 풀 크기도 늘려야 한다"는 주장을 평가하라. (VT1, R11)
3. V04·V05의 결과로, 가상 스레드 환경에서 MDC(traceId)를 어떻게 전달해야 하는지 두 가지 방법을 비교하라. (VT3, VT4, R12)
4. V07을 JDK 21에서 돌리면 결과가 어떻게 달라지는가? 그것을 근거로, Java 21 서비스에 VT를 켜기 전에 코드베이스에서 무엇을 찾아봐야 하는가? (VT5)

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

| 시나리오 | 답 | 근거 |
|---|---|---|
| V01 | ≈500 / 0 | allOf 는 입력이 **모두** 완료돼야 완료된다(실패 포함). 아무것도 취소하지 않는다(R10) |
| V02 | ≈50 / 2 | VT2: 첫 실패에서 join 이 깨어나고 나머지 subtask 를 interrupt. close 는 interrupt 된 subtask 가 끝나기를 기다리지만 곧바로 끝난다 |
| V03 | ≈1000 / ≈100 | 플랫폼: ceil(2000/200) × 100 (R7 풀 상한). VT: 2,000개가 동시에 기다린다(VT1) |
| V04 | NONE / t-100 | VT3: 작업마다 새 스레드 → ThreadLocal 비어 있음. ITL 은 VT 를 만든 스레드(= submit 한 요청 스레드)에서 복사 |
| V05 | t-100 / t-200 | 플랫폼 풀 스레드는 t-100 요청 때 만들어져 그 값을 계속 가진다(stale, m07 S09 와 같은 사고). VT 는 매번 새로 만들어지므로 submit 한 순간의 값 |
| V06 | t-100 / NONE / NONE | VT4: STS fork 로 만든 subtask 에만 상속 |
| V07 | ≈100 | VT5: JDK 24+ 에서 synchronized 안의 blocking 도 캐리어를 놓아준다(JDK 21 은 ≈400) |

서술 1: CF(allOf)는 상품 실패를 알고도 가격·리뷰 호출이 끝날 때까지(500ms) 응답하지 못하고, 그 동안 io 스레드 2개와 다운스트림 커넥션을 계속 점유한다. 장애 시 응답시간이 "가장 느린 호출"로 늘고, 이미 쓸모없는 다운스트림 호출이 계속 나간다. STS 는 50ms 에 실패 응답을 주고 형제 호출을 interrupt 해 자원을 즉시 돌려준다. Java 17 CF 로 흉내 내려면: 각 future 에 `exceptionally`/`whenComplete` 로 "첫 실패" 를 감지해 결과 future 를 즉시 실패시키고(allOf 대신 직접 만든 fail-fast 조합), 실제 작업을 멈추기 위해 `executor.submit` 의 `Future.cancel(true)` 나 취소 플래그를 따로 관리해야 한다(CF.cancel 은 interrupt 하지 않는다, R10).

서술 2: 반은 맞고 반은 틀리다. VT 는 "기다리는 스레드" 를 싸게 만들 뿐 DB 가 동시에 처리할 수 있는 쿼리 수는 그대로다. 플랫폼 풀 200개가 사실상 DB 동시성 상한 역할을 하던 시스템에서 VT 로 바꾸면 그 상한이 사라져 커넥션 대기열이 폭증한다. 커넥션 풀은 DB 가 버틸 수 있는 크기로 유지하고, 필요한 만큼의 동시성 상한을 Semaphore(또는 커넥션 풀 자체의 대기 타임아웃)로 명시해야 한다. 커넥션 풀을 늘리는 것은 DB 용량이 허락할 때만이다.

서술 3: (a) ScopedValue 로 traceId 를 바인딩하고 STS fork 로만 동시성을 쓴다: 자동 상속, 불변, 범위 종료 시 자동 해제라 누출(m07 S03)이 구조적으로 불가능하다. 단 MDC 를 읽는 기존 로깅 코드와 연결하려면 fork 안에서 MDC 에 넣고 빼는 어댑터가 필요하고, STS 는 preview 다. (b) 작업마다 VT 를 만드는 executor 를 쓰면서 제출 시점에 MDC 를 캡처해 작업에서 set/restore 하는 데코레이터를 그대로 쓴다(m07 E1). 기존 코드와 호환되지만 R12 의 함정(누가 execute 를 부르는가)도 그대로다. ITL 로 대신하는 것은 VT-per-task 에서는 "우연히" 동작하지만(V04) 풀 스레드가 섞이면 stale 값이 남으므로(V05) 쓰지 않는다.

서술 4: blocking 호출(JDBC, HTTP, 파일 I/O, sleep)을 감싼 `synchronized` 블록/메서드. 직접 작성한 코드뿐 아니라 라이브러리(오래된 JDBC 드라이버, 커넥션 풀, HTTP 클라이언트)의 synchronized 도 포함이다. JDK 21 에서는 이런 구간이 캐리어를 고정해 동시성이 코어 수로 떨어진다(V07 ≈400ms). JDK 21 에서는 `-Djdk.tracePinnedThreads=short` (이 repo 에서 JDK 21.0.11 로 확인: `reason:MONITOR` 와 위치를 출력) 나 JFR 의 `jdk.VirtualThreadPinned` 이벤트로 찾고, `ReentrantLock` 으로 바꾸거나 JDK 24+ 로 올린다.
<!-- ANSWER-END -->
