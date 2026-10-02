# P2 결정 기록: 알림 발송 게이트웨이

[판단 가이드](../../docs/decision-guide.md)의 질문 흐름을 따라 **코드를 쓰기 전에** 채운다.

## 결정
- 목적: A / B / B + 격리 구간 / 쓰지 않음
- 한 줄 근거:

## 근거
- 독립적인 호출:
- 클라이언트 특성 (각 의존성마다 blocking 인지, 무엇을 보고 판단했나):
- 기대 효과 (숫자로: 응답시간, 요청당 점유 스레드):

## 실행 모델
- 단계별 실행 스레드 (R1/R2): 조회 → 발송 → 조립 → 감사 → 응답
- blocking 지점과 그 지점이 실행되는 스레드:
- executor: 이름, 크기, 큐, 거절 정책, 크기 근거 (R11). 거절 정책이 CallerRuns 면 안 되는 이유:
- 반환 future 를 완료시키는 스레드 (javadoc 에 쓸 문장):

## 실패 모델
- 조회 타임아웃/실패:          채널 타임아웃/실패:
- 감사 실패:                    전체 SLO 보장 방법:
- 타임아웃 후 남은 작업 (R10: 비동기 요청과 JDBC 의 차이):

## 컨텍스트 (R12)
- 테넌트를 어디서, 언제 캡처하고 어떻게 감사 단계까지 전달하는가:
- TaskDecorator 같은 executor 데코레이터로 해결하면 어떤 문제가 생기는가 (#16b):

## 이 설계가 틀렸다면 어떤 증상이 보일까 (3가지 이상)
-

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

## 결정
- 목적: **B, 단 감사 기록(JDBC) 구간은 A 식 격리**
- 한 줄 근거: 조회·발송은 진짜 비동기 클라이언트라 I/O 대기 중 스레드를 잡지 않는다(B). 감사 기록만 JDBC 라 전용 `audit` 풀(32)로 격리해 이벤트 루프 blocking 0건을 지킨다.

## 근거
- 독립적인 호출: 채널 발송 3개는 서로 독립(동시에). 조회 → 발송 → 감사는 순차 의존(발송할 채널, 감사할 결과가 앞 단계에 필요).
- 클라이언트 특성: Lettuce·HTTP/2 클라이언트는 future 를 반환하고 이벤트 루프가 완료시킨다(non-blocking). auditDb 는 JDBC(blocking).
- 기대 효과: 응답 ≈ 20 + max(50,120,200) + 30 = 250ms(순차였다면 400ms). 요청당 점유 스레드: I/O 대기 중 0, 감사 30ms 동안만 1개. 피크 1,000건/s 에서도 감사 풀 ≈ 30 스레드.

## 실행 모델
- `channelsOf` 결과: Redis 이벤트 루프(또는 100ms 초과면 JDK 타임아웃 스케줄러)가 완료.
- `thenCompose(channels -> fanOut)`: 위 스레드에서 **요청만 보낸다**(non-blocking, R3).
- 채널 `orTimeout(300).handle(..)` 와 `allOf(..).thenApply(조립)`: 마지막 채널의 이벤트 루프 또는 타임아웃 스케줄러. 조립은 가벼운 Map 작업뿐.
- `thenCompose(partial -> supplyAsync(audit, auditPool))`: 위 스레드는 **제출만** 하고, JDBC 는 [audit]/[blocking].
- `thenApply(receipt -> report)`: audit 스레드(안쪽 future 를 완료시킨 스레드, R1·thenCompose).
- executor: `audit`, 32 스레드, 큐 1,000(ArrayBlockingQueue: 1초분), **AbortPolicy**.
  - 크기: 1,000건/s × 0.03s = 동시 30 → 32. DB 커넥션 풀도 32 이상이어야 한다.
  - CallerRuns 금지: 제출하는 스레드가 이벤트 루프/타임아웃 스케줄러라, 큐가 차면 거기서 JDBC 가 실행된다(R3 예외 1, #6).
  - 거절되면 `supplyAsync` 가 동기적으로 RejectedExecutionException 을 던져(R3 예외 2) thenCompose 단계가 실패 → 호출자는 503/재시도.
- 반환 future 완료 스레드: **audit 풀 스레드**(감사가 마지막 단계). javadoc: "반환 future 는 감사 기록을 마친 audit 스레드에서 완료된다. 콜백에서 오래 blocking 하지 말 것. 단, 감사 풀이 포화로 거절하면 제출한 스레드에서 실패로 완료될 수 있다."

## 실패 모델
- 조회: `completeOnTimeout({PUSH}, 100ms)` + `exceptionally(→ {PUSH})`. (`channelsOf` 는 호출마다 새 future 라 completeOnTimeout 으로 상태를 바꿔도 안전 — 공유 future 였다면 #17)
- 채널: 각 future 에 `orTimeout(300)` 후 `handle` 로 null → failed. 비동기 요청이라 시간 초과 후에도 **스레드를 잡지 않는다**(R10 의 자원 문제는 JDBC/blocking 에서만 생긴다. 필요하면 클라이언트의 요청 취소 API 로 연결만 정리).
- 감사 실패: 그대로 전파 → 반환 future 실패(원인 = 감사 예외).
- SLO: 조회 ≤ 100 + 채널 ≤ 300 + 감사 30 ≈ 430ms < 450ms. 감사 JDBC 자체에는 쿼리 타임아웃을 건다(실무).

## 컨텍스트
- `send()` 첫 줄에서 `TenantContext.get()` 을 **값으로** 캡처해 감사 키를 만들 때 쓴다. 이후 단계는 이벤트 루프·타이머·audit 스레드에서 돌아 ThreadLocal 이 없다(R12).
- `TaskDecorator` 로 audit 풀을 감싸면: `supplyAsync(.., auditPool)` 을 **제출하는 스레드**가 요청 스레드가 아니라 마지막 채널을 완료시킨 이벤트 루프/타이머다. 데코레이터는 그 스레드의 (빈) 컨텍스트를 캡처한다(#16b). 게다가 복원을 빠뜨리면 이전 요청의 테넌트가 남는다(#16).

## 틀렸다면 보일 증상
- 감사를 `thenApply` 안에서 바로 호출: 이벤트 루프 lag 증가, 모든 채널 응답 지연(#3).
- 감사 풀이 CallerRuns: 피크에만 이벤트 루프 blocking(#6).
- 테넌트를 감사 람다 안에서 읽음: 감사 기록 tenant=null → 규정 위반.
- 채널 타임아웃 누락: 한 공급자가 응답을 안 주면 발송 API 가 무한 대기(#11).
- 반환 future 가 이벤트 루프에서 완료: 호출자 콜백의 blocking 이 이벤트 루프를 막는다.
<!-- ANSWER-END -->
