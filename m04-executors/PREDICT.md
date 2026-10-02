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

(여기에 작성)
