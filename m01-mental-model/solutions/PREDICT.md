# m01 예측 시트

`Scenarios.java`를 읽고 **코드를 실행하기 전에** 채운다. "근거 규칙"이 비어 있으면 예측한 것이 아니라 찍은 것이다.

| 시나리오 | 항목 | 예측 | 근거 규칙과 한 줄 이유 | 결과 (채점 후) | 틀렸다면 무엇을 잘못 적용했나 |
|---|---|---|---|---|---|
| S01 | supply | | | | |
| S01 | map | | | | |
| S02 | map | | | | |
| S03 | map | | | | |
| S04 | map | | | | |
| S05 | map | | | | |
| S06 | map | | | | |
| S07 | combine | | | | |
| S08 | first | | | | |
| S09 | supply | | | | |
| S09 | parse | | | | |
| S09 | after | | | | |
| S10 | afterTimeout | | | | |
| S11 | task | | | | |
| S12 | returned (ms) | | | | |
| S12 | joined (ms) | | | | |
| S13 | map | | | | |
| S14 | compose-fn | | | | |
| S14 | inner | | | | |
| S14 | after | | | | |
| S15 | 미완료 future에 등록 (ms) | | | | |
| S15 | 완료된 future에 등록 (ms) | | | | |

## 채점 후 서술 문제 (journal에 답한다)

1. S01과 S02는 콜백 코드가 똑같다. 두 결과를 비교하고, 같은 콜백이 타이밍에 따라 다른 스레드에서 실행될 수 있다는 점이 운영 환경에서 문제가 되는 상황을 하나 들어라.
2. S15의 두 결과를 R1과 R4로 설명하라. 이벤트 루프에서 같은 일이 일어나면 어떤 증상이 보일까?
3. S11에서 배운 것을 바탕으로, `CallerRunsPolicy`를 쓰는 executor로 `supplyAsync`를 호출하는 코드가 왜 위험할 수 있는지 설명하라.

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

| 시나리오 | 답 | 근거 |
|---|---|---|
| S01 supply / map | io / io | R3: io에 제출. R1: 미완료 상태에서 등록 → 완료시킨 io가 실행 |
| S02 map | caller | R1: 이미 완료 → 등록한 스레드에서 즉시 실행 |
| S03 map | commonPool | R2: executor 생략 → defaultExecutor |
| S04 map | cpu | R2: Async + executor는 완료 여부와 무관하게 항상 executor |
| S05 map | completer | R1: complete()를 호출한 스레드 |
| S06 map | caller | R1: caller가 complete()를 호출 |
| S07 combine | b | R1 표: thenCombine은 나중에 완료된 입력의 스레드 |
| S08 first | a | R1 표: anyOf는 먼저 완료된 입력의 스레드 |
| S09 | io / cpu / cpu | parse가 미완료일 때 after가 등록됨 → parse를 완료시킨 cpu |
| S10 | timeoutScheduler | R10: 타임아웃이 future를 완료시킨다 |
| S11 | caller | R3 예외 1: 직접 실행 executor |
| S12 | 0 / 200 | R3: 제출만 하고 반환, R5: join은 완료까지 대기 |
| S13 | caller | R1: completedFuture는 이미 완료 |
| S14 | io / cpu / cpu | compose-fn은 바깥 future를 완료시킨 io, after는 안쪽 future를 완료시킨 cpu (UniRelay) |
| S15 | 0 / 200 | 미완료면 쌓아 두고 반환, 완료됐으면 등록 스레드가 blocking 본문을 직접 실행 |

서술 1: 요청 스레드(서블릿)나 이벤트 루프에서 콜백을 등록할 때, 응답이 이미 와 있으면(캐시 히트, 빠른 응답) 무거운 콜백이 요청 스레드에서 실행되고, 아니면 I/O 스레드에서 실행된다. 부하나 지연 분포에 따라 실행 위치가 바뀌므로 테스트에서는 재현되지 않고 운영에서만 MDC 유실이나 이벤트 루프 지연이 생긴다.

서술 2: 미완료일 때는 콜백을 쌓기만 하므로 0ms다. 완료됐을 때는 등록한 스레드가 즉시 200ms blocking 본문을 실행한다. 이벤트 루프에서 이렇게 등록하면 이벤트 루프가 200ms 멈추고, 그 루프가 담당하는 모든 연결의 응답이 200ms씩 밀린다(lag 증가, p99 급등).

서술 3: 큐가 차면 `CallerRunsPolicy`가 제출한 스레드에서 작업을 실행한다. 이벤트 루프나 요청 스레드에서 제출했다면 그 스레드가 blocking 작업을 떠안게 된다. "부하가 높을 때만" 발생하므로 발견하기 어렵다.
<!-- ANSWER-END -->
