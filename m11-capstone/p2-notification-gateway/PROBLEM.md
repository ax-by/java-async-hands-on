# P2. 알림 발송 게이트웨이 (비동기 채널 + JDBC 감사 기록)

> 코드: `src/main/java/async/m11/p2/NotificationGateway.java` · 결정: [DECISION.md](DECISION.md) · 채점: `P2GradingTest` (7개)

## 상황

사내 여러 서비스가 호출하는 **알림 게이트웨이**다. **Spring WebFlux** 로 만들었고, 핸들러는 **이벤트 루프 스레드**에서
`send(notificationId, userId)` 를 호출해 반환된 future 를 `Mono.fromFuture(..)` 로 응답한다.

| 의존성 | 종류 | 평소 응답 | 완료시키는 스레드 |
|---|---|---|---|
| `ChannelPreferences.channelsOf(userId)` | Redis (Lettuce, 비동기) | 20ms | Redis 이벤트 루프 |
| `push` / `email` / `sms` `.fetch(userId + "/" + notificationId)` | 공급자 HTTP/2 비동기 클라이언트 | 50 / 120 / 200ms | 각 클라이언트의 이벤트 루프 |
| `auditDb.call(감사 키)` | **JDBC** (감사 테이블 INSERT + COMMIT) | 30ms | 호출한 스레드를 막는다 |

- 채널 응답은 `"push:u1/n-1"` 같은 문자열, 감사 기록 결과(receipt)는 `"audit-db:{감사 키}"` 다.
- 멀티테넌트: 웹 필터가 요청 스레드(이벤트 루프)의 `TenantContext`(ThreadLocal)에 테넌트 ID 를 넣고, **`send()` 가 반환되면 지운다.**

## 요구사항 (모두 채점된다)

1. **흐름**: 수신 동의 채널 조회 → 동의한 채널로 **동시에** 발송 → 결과를 **감사 기록에 커밋한 뒤** `DeliveryReport` 로 응답.
2. **수신 동의 조회**: 100ms 를 넘기거나 실패하면 기본 채널 `{PUSH}` 만 쓴다.
3. **채널 발송**: 채널마다 최대 300ms. 실패하거나 초과한 채널은 `failed`, 성공한 채널은 `delivered`(채널 → 응답). 채널 실패는 발송 전체의 실패가 아니다.
4. **감사 기록**: 키 형식은 `{tenant}|{notificationId}|{성공 채널 수}/{시도 채널 수}`.
   - `tenant` 는 **`send()` 를 호출한 스레드**의 `TenantContext` 값이다. 없으면 문자열 `null` (`String.valueOf`).
   - 감사 기록이 실패하면 반환 future 는 **그 예외를 원인으로 실패**한다(규정: 기록되지 않은 발송은 성공으로 응답하지 않는다).
5. **non-blocking**: `send()` 는 20ms 안에 반환한다. 이벤트 루프(호출 스레드 포함) 위에서 blocking 이 **0건**이어야 한다. JDBC 는 commonPool 이 아닌 전용 풀에서.
6. **용량**: 피크 초당 1,000건. 동시 60건이 몰려도 전부 450ms 안에 응답하고, 각 이벤트 루프 lag 는 50ms 미만이다. 감사 풀 스레드 수에는 근거 있는 상한(64 이하)이 있어야 한다.
7. **컨텍스트**: 동시에 처리되는 요청들의 테넌트가 섞이면 안 된다. 테넌트 없는 요청에 이전 요청의 테넌트가 보이면 안 된다.
8. **완료 스레드**: 반환 future 는 이벤트 루프나 JDK 타임아웃 스케줄러에서 완료되면 안 된다(호출자가 붙인 콜백이 거기서 돌게 된다). `send()` javadoc 에 어느 스레드에서 완료되는지 적는다.

전체 응답 SLO 는 450ms 다(20 + 200 + 30 = 250ms 가 평소, 채널 초과 시 300 + 30 + α).

## 작성 순서

1. [DECISION.md](DECISION.md) 를 먼저 채운다. "A 인가, B 인가, 혼합인가, 쓰지 않는가"를 정확한 용어로 말해야 한다.
2. 코드를 쓴다. 각 람다 옆에 `// [실행 스레드] / [blocking 여부] / [컨텍스트 있음?]` 을 단다.
3. 자기 리뷰: 체크리스트 #1~#16b, 특히 #3, #6, #15, #16b 를 확인하고 추적 표를 채운다.
