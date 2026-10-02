# m04 예측 시트

`Scenarios.java`와 `probe/DefaultExecutorProbe.java`, `probe/CommonPoolStarvationProbe.java`를 읽고 **코드를 실행하기 전에** 채운다. "근거 규칙"이 비어 있으면 예측한 것이 아니라 찍은 것이다.

값의 형식: 스레드는 `On`, 운명은 `Fate`(THROWS_SYNC / RUNS_ON_CALLER / NEVER_COMPLETES / COMPLETES_EXCEPTIONALLY / COMPLETES_NORMALLY), 결말은 `Outcome`(COMPLETES / DEADLOCK), 시간은 ms.

| 시나리오 | 항목 | 예측 | 근거 규칙과 한 줄 이유 | 결과 (채점 후) | 틀렸다면 무엇을 잘못 적용했나 |
|---|---|---|---|---|---|
| S01 | executor 생략 시 task 스레드 | | | | |
| S02 | AbortPolicy: third | | | | |
| S02 | CallerRunsPolicy: third | | | | |
| S02 | DiscardPolicy: third | | | | |
| S02 | DiscardOldestPolicy: **second** | | | | |
| S03 | largestPoolSize | | | | |
| S03 | 큐에서 기다리던 작업 번호 | | | | |
| S04 | largestPoolSize | | | | |
| S05 | 무제한 큐: 마지막 호출 완료 (ms) | | | | |
| S05 | 큐 4: 거절된 호출 수 | | | | |
| S06 | T=2, N=1 | | | | |
| S06 | T=2, N=2 | | | | |
| S06 | T=4, N=3 | | | | |
| S06 | T=4, N=8 | | | | |
| S07 | 공유 풀: 재고 응답 (ms) | | | | |
| S07 | bulkhead: 재고 응답 (ms) | | | | |
| C01 | 가벼운 supplyAsync 시작 시점 (ms) | | | | |
| C01 | parallel stream 소요 (ms) | | | | |
| C01 | parallel stream을 처리한 스레드 | | | | |
| V01 | JDK 21: 스레드 | | | | |
| V01 | JDK 21: 5개 완료 (ms) | | | | |
| V01 | JDK 25: 스레드 | | | | |
| V01 | JDK 25: 5개 완료 (ms) | | | | |

### 힌트 (막힐 때만 펼쳐 본다)

- S02: `supplyAsync`는 내부에서 `executor.execute(task)`를 호출할 뿐이다. 거절 정책은 그 `execute` 안에서 동작한다. 정책이 task를 "실행하지 않고 버리면" future는 누가 완료시키는가?
- S03/S04: README 4절의 "큐 먼저, max는 나중".
- S06: 바깥 작업이 스레드를 몇 개 잡는가? 남는 스레드가 0개가 되는 조건은?
- C01: parallel stream을 호출한 스레드는 놀고 있지 않는다.

## 채점 후 서술 문제 (journal에 답한다)

1. **S02 Discard**: Spring MVC 컨트롤러가 `DiscardPolicy` 풀로 `supplyAsync(...).join()`을 한다. 큐가 넘치는 순간 운영에서 보이는 증상을 시간 순서대로 적어라. 로그에는 무엇이 남는가?
2. **S04**: 동료가 "트래픽이 늘어서 `ThreadPoolTaskExecutor`(core만 정하고 나머지는 기본값)의 `maxPoolSize`를 10 → 50으로 올렸다"고 한다. S04 결과로 볼 때 처리량이 늘어나는가? 늘지 않는다면 원인과 고치는 방법을 두 가지 이상 제시하라. 각 방법의 부작용은?
3. **S06**: 이 deadlock이 개발·스테이징 환경에서는 절대 재현되지 않고 운영 피크에만 나는 이유를 N과 T로 설명하라. 스레드 덤프를 떴을 때 무엇이 보이겠는가?
4. **E3 선택 근거**: `paymentPool()`의 큐 종류·용량·거절 정책을 고른 이유를 decision-guide의 "합격" 수준으로 적어라. 다른 세 정책을 각각 왜 탈락시켰는지 한 줄씩 포함한다.
5. **C01**: commonPool이 막힌 동안 가벼운 `supplyAsync`와 parallel stream은 각각 어떻게 되었나(채점 결과의 숫자로 적는다)? 둘의 차이를 만든 원인은 무엇인가? 이 결과로 "parallel stream은 commonPool이 막혀도 괜찮다"고 결론 내릴 수 있는가?
6. **V01**: 운영은 Java 17, CPU 2개 컨테이너(Kubernetes `limits.cpu: 2`)다. 코드 곳곳에 executor 없는 `supplyAsync(() -> restTemplate...)`가 있다. 피크 시 어떤 일이 일어나는가? 그 코드를 그대로 두고 JDK 25로 올리면 증상이 어떻게 바뀌는가?
7. **E1**: 계산한 스레드 수가 DB 커넥션 한도에 막혔다(`limitedByDownstream = true`). 스레드를 늘리는 것 말고 할 수 있는 일 세 가지는?

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

| 시나리오 | 답 | 근거 |
|---|---|---|
| S01 | commonPool | R2: executor 생략 → `defaultExecutor()` = commonPool (JDK 25, 병렬도 ≥ 2) |
| S02 Abort | THROWS_SYNC | R3 예외 2: `execute`가 던진 `RejectedExecutionException`이 `supplyAsync` 밖으로 나온다 |
| S02 CallerRuns | RUNS_ON_CALLER | R3 예외 1: 정책이 제출 스레드에서 `run()`을 호출한다 |
| S02 Discard | NEVER_COMPLETES | 버려진 `AsyncSupply`만이 future를 완료시킬 수 있었다. 예외도 없다 |
| S02 DiscardOldest (second) | NEVER_COMPLETES | 큐 맨 앞의 second가 버려지고 third가 들어간다. 이미 future를 받은 second가 미아가 된다 |
| S03 | 4 / "3,4" | core 2 → 큐 2 → max까지 확장(5, 6) → 7 거절. 5·6이 3·4보다 먼저 실행된다 |
| S04 | 2 | 무제한 큐는 절대 차지 않으므로 max까지 늘어나지 않는다 |
| S05 | 1000 / 14 | 20 × 100ms / 2 = 1000ms (R7 풀 상한). 2 실행 + 4 대기 = 6 수용, 14 거절 |
| S06 | COMPLETES / DEADLOCK / COMPLETES / DEADLOCK | N ≥ T이면 모든 스레드가 바깥 작업에서 join, 안쪽 작업은 큐에서 대기 |
| S07 | 550 / 50 | 공유: 추천 4개가 스레드 4개를 500ms 점유 → 500 + 50. 격리: 바로 50 |
| C01 | 1000 / 600 / caller | 워커 2개가 1000ms blocking. supplyAsync는 워커를 기다리고, parallel stream은 caller가 혼자 6 × 100ms 처리 |
| V01 JDK 21 | freshThread / 100 | 병렬도 1 → `ThreadPerTaskExecutor`, 작업 5개 = 스레드 5개, 동시에 실행 |
| V01 JDK 25 | commonPool / 500 | 항상 commonPool, 병렬도 1 → 워커 1개가 5개를 순서대로 |

### 실측 해설 (이 repo에서 JDK 25 / 21로 측정)

**C01 (commonPool 병렬도 2, 자식 JVM)**
```
 worker-1: [ 외부 API 1000ms ...................... ]
 worker-2: [ 외부 API 1000ms ...................... ]
 가벼운 supplyAsync              ─ 큐에서 대기 ─────────▶ ≈1000ms 에야 시작
 parallel stream (100ms × 6)    ─ caller(main) 혼자 처리 → ≈600ms (평소에는 caller + 워커 2개가 나눠서 ≈200~300ms)
```
parallel stream의 종단 연산을 호출한 스레드는 작업을 쪼개 commonPool에 넣은 뒤 **자기도 작업을 꺼내 처리**한다. 그래서 commonPool이 막혀도 멈추지는 않지만 병렬성을 잃는다. `supplyAsync`는 제출만 하고 돕지 않는다.

**V01 (`-XX:ActiveProcessorCount=1`, executor 생략 supplyAsync × 5, 각 100ms)**

| | 스레드 | 서로 다른 스레드 수 | 5개 완료 |
|---|---|---|---|
| JDK 21 | `Thread-0` ~ `Thread-4` | 5 | ≈110ms |
| JDK 25 | `ForkJoinPool.commonPool-worker-1` | 1 | ≈530ms |

로컬(JDK 25, CPU 10개)에서는 병렬 150ms, 운영(Java 17, CPU 2개)에서는 요청마다 스레드 생성, JDK 25로 올린 운영에서는 직렬화. 같은 코드가 세 가지로 동작한다.

**S02**: Discard 계열은 future를 완료시킬 유일한 코드(`AsyncSupply.run`)를 버린다. 예외도 로그도 없다. CF와 함께 쓰면 안 된다고 생각하라. 덤으로 `CallerRunsPolicy`도 **풀이 shutdown된 뒤에는** `run()`하지 않고 조용히 버린다(JDK 소스 `if (!e.isShutdown()) r.run();`, 실측: future 미완료). 애플리케이션 종료 중에 들어온 요청이 매달리는 원인이 된다.

**S03**: 1·2는 core 스레드, 3·4는 큐, 5·6은 새 스레드(큐에 있던 3·4보다 **먼저** 실행된다), 7은 거절. **S04**: 무제한 큐는 절대 차지 않으므로 3단계(max까지 확장)가 일어나지 않는다. Spring `ThreadPoolTaskExecutor` 기본값(core 1, 큐 무제한)은 **사실상 스레드 1개**이고, `setMaxPoolSize(50)`만 해서는 스레드가 늘지 않는다.

**S05**: 무제한 큐는 거절 0건, 마지막 요청 대기 ≈1000ms(에러는 없고 p99만 튐). 큐 4는 6개만 받고 14개를 즉시 거절해서 과부하를 드러낸다.

**S06**: 동시 요청 N이 스레드 T 이상이면 모든 스레드가 바깥 작업에서 join → 안쪽 작업은 큐에서 영원히 대기. N < T이면 남는 스레드가 안쪽 작업을 처리하므로 (느리지만) 끝난다.

**S07**: 공유 풀에서는 추천 호출 4개가 스레드 4개를 500ms 잡으므로 재고 호출은 ≈550ms, 풀을 나누면 ≈50ms.

서술 1: 큐가 넘친 순간부터 넘친 요청의 future는 영원히 완료되지 않는다. (1) 해당 요청의 Tomcat 스레드가 `join()`에서 무한 대기한다. (2) 그런 요청이 쌓이면서 Tomcat 스레드(기본 200)가 고갈되고, 이 풀과 무관한 API까지 응답하지 않는다. (3) 로드밸런서/클라이언트 타임아웃만 남는다. 예외가 없으므로 애플리케이션 로그에는 **아무것도 남지 않는다**. 스레드 덤프에 `CompletableFuture.join`에서 `WAITING`인 `http-nio-*` 스레드가 대량으로 보인다.

서술 2: `ThreadPoolTaskExecutor`의 `queueCapacity` 기본값이 `Integer.MAX_VALUE`라서 큐가 차지 않고, 큐가 차야만 max까지 늘어난다. 해결: (a) `corePoolSize`를 원하는 크기(예: 50)로 올린다. 부작용: 유휴 시에도 스레드 50개 유지(`allowCoreThreadTimeOut`으로 완화). (b) `queueCapacity`를 작게(허용 대기시간 기준) 정해서 큐가 차면 max까지 늘어나게 한다. 부작용: 큐와 max까지 차면 거절되므로 거절 처리(503)가 필요하다. (c) 다운스트림 한도를 먼저 확인한다. DB가 20 커넥션이면 50 스레드는 의미가 없다.

서술 3: deadlock 조건은 "동시에 처리 중인 바깥 요청 수 N ≥ 풀 스레드 수 T"다. 개발·스테이징은 동시 요청이 1~2개라 항상 N < T이고, 남는 스레드가 안쪽 작업을 처리한다. 운영 피크에 순간적으로 N ≥ T가 되면 그 순간 모든 스레드가 멈추고, 이후 들어오는 요청도 큐에 쌓여 영원히 처리되지 않는다. 스레드 덤프에는 풀 스레드 전원이 `CompletableFuture.join` → `LockSupport.park`에서 `WAITING`이고, 풀 큐에는 안쪽 작업이 쌓여 있다. CPU 사용률은 0에 가깝다.

서술 4 (예시): "결제 호출 전용 `payment` 풀: 스레드 8(결제사 동시 호출 한도), `ArrayBlockingQueue(16)`(8 × 허용 대기 200ms / 호출 100ms), `AbortPolicy`. 넘친 요청은 즉시 `RejectedExecutionException` → (E2의 `supplySafely`로) 실패한 future → 컨트롤러가 503으로 변환. 무제한 큐는 허용 대기 200ms를 지킬 수 없고 과부하를 숨겨서 탈락. CallerRuns는 Tomcat 스레드가 결제 호출을 직접 실행해 요청 스레드가 고갈되므로 탈락. Discard/DiscardOldest는 future가 영원히 미완료되어 '조용히 버려지는 요청 0건' 요구를 어기므로 탈락."

서술 5: parallel stream은 **호출한 스레드도 작업을 나눠 처리**하므로, 워커가 모두 막혀 있어도 caller 혼자 6개를 순차로 처리해 600ms에 끝났다. `supplyAsync`는 작업을 큐에 넣고 반환할 뿐 호출자가 돕지 않으므로 워커가 풀려나는 1000ms까지 시작하지 못했다. "괜찮다"가 아닌 이유: 평소 200ms(caller + 워커 2개)이던 작업이 3배 느려졌고, CPU가 많은 서버일수록 손실(병렬성)이 크다. 그리고 parallel stream 안에서 또 다른 commonPool 작업을 기다리면 그 작업은 똑같이 막힌다.

서술 6: Java 17 + CPU 2개 → commonPool 병렬도 1 → `supplyAsync(fn)`마다 새 플랫폼 스레드가 만들어진다. 피크에 RestTemplate 호출이 느려지면 스레드가 수백~수천 개로 늘어나 메모리(스레드 스택)와 컨텍스트 스위칭 비용이 폭증하고, 최악에는 `OutOfMemoryError: unable to create native thread`. 동시에 다운스트림에도 무제한 동시 호출이 간다. JDK 25로 올리면 반대로 모든 호출이 commonPool 워커 1개에 줄을 서서 처리량이 직렬로 떨어지고, 같은 JVM의 parallel stream까지 막힌다. 두 경우 모두 해결은 전용 풀을 명시하는 것(R2, R11).

서술 7: (a) 큐 용량을 허용 대기시간 기준으로 제한하고 넘치면 즉시 거절해서 빠르게 실패시킨다(503, 재시도 안내). (b) 호출 자체를 줄인다: 캐시, 배치 조회(N번 → 1번), 불필요한 호출 제거로 `callsPerRequest`나 `blockingMillisPerCall`을 줄인다. (c) 다운스트림 용량을 늘리는 것을 DB 팀과 협의한다(커넥션 풀, 읽기 복제본). (d) 우선순위가 낮은 기능은 별도 풀(bulkhead)로 분리해서 핵심 기능의 커넥션을 보호한다.
<!-- ANSWER-END -->
