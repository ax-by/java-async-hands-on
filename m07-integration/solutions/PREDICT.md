# m07 예측 시트

시나리오 코드를 읽고 **실행하기 전에** 채운다. "근거 규칙"이 비어 있으면 예측한 것이 아니라 찍은 것이다.
컨텍스트 시나리오(S01~S10)의 공통 전제: 호출 스레드는 요청 스레드이고 `traceId=t-100`, `user=alice`가 설정되어 있다.

| 시나리오 | 항목 | 예측 | 근거 규칙과 한 줄 이유 | 결과 (채점 후) | 틀렸다면 무엇을 잘못 적용했나 |
|---|---|---|---|---|---|
| S01 | supplyAsync 안의 traceId | | | | |
| S02 | 완료 전 등록한 콜백의 traceId | | | | |
| S02 | 완료 후 등록한 콜백의 traceId | | | | |
| S03 | leaky: 익명 작업에서 보이는 user | | | | |
| S03 | fixed: 익명 작업에서 보이는 user | | | | |
| S04 | map(non-async)의 traceId | | | | |
| S04 | mapAsync(decorated)의 traceId | | | | |
| S05 | 이벤트 루프 future + thenApply 의 traceId | | | | |
| S06 | 이벤트 루프 future + thenApplyAsync(decorated), 완료 전 등록 | | | | |
| S07 | 같은 코드, 완료 후 등록 | | | | |
| S08 | DelegatingSecurityContextExecutor: during / after | | | | |
| S09 | INHERITABLE 모드: bob 의 작업에서 보이는 user | | | | |
| S10 | ContextPropagatingTaskDecorator() 기본 생성자: traceId / user | | | | |
| S11 | 롤백 후 orders 행 / audit 행 / 비동기 안 트랜잭션 활성? | | | | |
| S12 | 커넥션 풀 1개: 걸린 시간(ms) / orders 행 | | | | |
| S13 | @Async 프록시 호출 / self 호출의 스레드 | | | | |
| S14 | Boot 기본 executor 의 최대 스레드 수 (200ms 작업 20개) | | | | |
| S15 | void 예외 → 핸들러? / CF 예외 → 핸들러? | | | | |
| S16 | executor 빈 2개 추가 후 @Async 스레드 | | | | |
| S17 | 동시 10요청 총 ms: join 컨트롤러 / CF 반환 컨트롤러 | | | | |
| S18 | CF 반환: work 스레드 / write 스레드 | | | | |
| S19 | 영원히 미완료인 CF: HTTP 상태 / future 취소됨? | | | | |
| S20 | 상태 코드: CF 반환 / join / get | | | | |
| S21 | Callable 반환의 user / CF(io) 반환의 user | | | | |

## 채점 후 서술 문제 (journal에 답한다)

1. S04와 S06은 둘 다 decorated 풀로 `thenApplyAsync`를 한다. 두 경우에 `execute()`를 호출한 스레드가 각각 누구였는지 R1으로 설명하고, 그 차이가 Async 단계의 traceId에 어떤 차이를 만들었는지, 운영과 테스트 환경에서 이 차이가 어떤 증상으로 나타날 수 있는지 말하라.
2. S03의 `copyingDecorator()`를 코드 리뷰한다고 하자. [리뷰 코멘트 양식](../docs/review-checklist.md)으로 코멘트를 써라(규칙 번호, 위치, 증상, 수정 제안).
3. S11과 S12를 보고, "응답을 빨리 주려고 감사 로그 저장을 비동기로 돌리자"는 제안에 대해 팀에 보낼 답을 써라. 감사 로그를 비동기로 해야 한다면 어떤 설계가 맞는가?
4. 팀의 Spring Boot 3.5 / Java 17 서비스에서 `@Async` 메서드가 운영 중에 스레드를 수천 개 만든 사고가 났다. 가장 가능성 높은 원인을 S14, S16으로 설명하고, 재발 방지 설정을 제안하라.
5. 상품 상세 API(JDBC 2회, 각 100ms, 독립)를 "CF를 반환하는 컨트롤러"로 바꾸자는 제안이 있다. Tomcat 기본 worker 200개, 피크 300 RPS다. 바꾸면 무엇이 좋아지고 무엇이 그대로이며 무엇이 새로 생기는가? (S17, R0, R11)

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

| 시나리오 | 답 | 근거 |
|---|---|---|
| S01 | NONE | R12: io 스레드에는 MDC 가 없다 |
| S02 | NONE / t-100 | R1: 완료 전 등록 → 완료시킨 io 에서 실행(컨텍스트 없음). 완료 후 등록 → 등록한 요청 스레드에서 즉시 실행(컨텍스트 있음) |
| S03 | alice / NONE | leaky: 복원이 없어 alice 의 인증이 풀 스레드에 남고, 익명 제출자는 auth 가 null 이라 덮어쓰지도 않는다. fixed: 작업 후 복원 + 빈 컨텍스트도 그대로 적용 |
| S04 | t-100 / t-100 | map 은 supply 를 완료시킨 io 스레드에서, 즉 데코레이터의 set~restore 범위 **안**에서 실행된다. mapAsync 의 execute 도 그 범위 안의 io 스레드가 호출하므로 t-100 을 캡처한다 |
| S05 | NONE | R1: 이벤트 루프가 완료시킨다. 이벤트 루프에는 요청 컨텍스트가 없다 |
| S06 | NONE | R1+R2: execute() 를 이벤트 루프가 호출 → 데코레이터가 이벤트 루프의 (빈) 컨텍스트를 캡처 |
| S07 | t-100 | 이미 완료 → execute() 를 등록한 요청 스레드가 호출 → 요청 컨텍스트를 캡처 |
| S08 | alice / NONE | DelegatingSecurityContextRunnable 은 실행 후 원래 컨텍스트로 복원한다 |
| S09 | alice | INHERITABLE 은 스레드 **생성 시** 복사. 풀 스레드는 alice 의 요청 중에 만들어졌고 계속 재사용된다 |
| S10 | NONE / alice | 전역 ContextRegistry 에는 Spring Security 의 접근자만 ServiceLoader 로 등록, MDC 접근자는 없음 |
| S11 | 0 / 1 / false | 비동기 쓰기는 다른 커넥션(autocommit)에서 이미 커밋. 트랜잭션은 스레드에 묶여 있다 |
| S12 | ≈250 / 0 | 바깥 트랜잭션이 유일한 커넥션을 잡은 채 join → 비동기 쪽이 connection-timeout(250ms) 후 실패 → CompletionException(RuntimeException) 이 @Transactional 밖으로 나가 롤백 |
| S13 | task / caller | 프록시를 거치면 applicationTaskExecutor(task-N), this 호출은 프록시를 우회해 동기 실행 |
| S14 | 8 | core 8 + 무제한 큐: ThreadPoolExecutor 는 큐가 찰 때만 core 를 넘어 스레드를 만든다 |
| S15 | true / false | void 는 돌려줄 곳이 없어 핸들러로, CF 반환은 future 안으로 (호출자가 버리면 사라진다, R6) |
| S16 | SimpleAsyncTaskExecutor | Executor 빈이 있어 Boot 기본 풀이 물러나고, 후보가 2개라 @Async 가 고르지 못해 SimpleAsyncTaskExecutor(호출마다 새 스레드)로 대체 |
| S17 | ≈1500 / ≈300 | join: worker 2개가 300ms 씩 붙잡힘 → 5라운드. CF 반환: worker 가 즉시 풀려 10개가 io(16)에서 동시에 |
| S18 | io / tomcat | future 완료 후 Spring MVC 가 async dispatch 를 하고, 응답은 컨테이너 스레드가 쓴다 |
| S19 | 500 / false | AsyncRequestTimeoutException(RuntimeException)을 ScenarioAdvice 의 catch-all 이 먼저 잡는다(advice 가 없으면 503). Spring 은 future 를 취소하지 않는다(R10) |
| S20 | 404 / 500 / 404 | CF 반환: Spring 이 CompletionException 을 벗겨 원인으로 매칭. join: CompletionException 자체가 RuntimeException 핸들러에 먼저 매칭. get: ExecutionException 은 checked 라 직접 매칭되는 핸들러가 없어 cause 로 매칭 |
| S21 | alice / NONE | Callable 은 Spring MVC 가 실행하며 Spring Security 가 SecurityContext 를 넘겨준다. 내 풀의 supplyAsync 에는 아무도 넘겨주지 않는다 |

서술 1: S04 의 mapAsync 는 미완료 상태에서 등록됐고, 앞 단계(map)를 완료시킨 것은 decorated io 스레드다. 이 스레드는 데코레이터가 set 해 둔 범위 안에서 complete 를 호출하므로 execute 시점의 MDC 가 t-100 이다. S06 은 이벤트 루프가 complete 를 호출하고, 그 자리에서 execute 를 부르므로 데코레이터가 이벤트 루프의 빈 MDC 를 캡처한다. 테스트는 보통 목(mock) 클라이언트가 이미 완료된 future 를 돌려주거나(S07 처럼 등록 시점에 완료) 같은 풀에서 완료시키므로 컨텍스트가 보인다. 운영에서는 진짜 Netty 클라이언트가 이벤트 루프에서 완료시키므로 응답 지연이 있는 요청만 traceId 가 빠진다.

서술 2:
```
[R12][#16] ContextScenarios.copyingDecorator: 작업 후 MDC/SecurityContext 를 복원하지 않고,
캡처 값이 null 일 때 비우지도 않는다. 스레드 1개 풀에서 alice 요청 뒤 익명 작업이 alice 로 실행된다(S03).
→ 작업 전 값을 보관하고 finally 에서 복원, 캡처 값이 없으면 clear. 또는 ContextPropagatingTaskDecorator(MDC+Security 등록 팩토리).
증상 예상: 감사 로그/권한 검사에 다른 사용자가 찍힘(보안 사고), 배치 로그에 엉뚱한 traceId.
```

서술 3: 다른 스레드에서 저장하면 주문 트랜잭션과 원자성이 깨진다. 주문이 롤백돼도 감사 로그가 남고(S11), 커넥션 풀이 작으면 부하 시 deadlock 에 가깝게 멈춘다(S12). 감사 로그가 같은 트랜잭션의 일부여야 하면 같은 스레드에서 같은 트랜잭션으로 쓴다(수 ms 의 INSERT 를 비동기로 뺄 이유가 없다). 정말 비동기가 필요하면 커밋 이후에 발행한다: `@TransactionalEventListener(phase = AFTER_COMMIT)` + 명시적 executor, 또는 아웃박스 테이블에 같은 트랜잭션으로 기록하고 별도 프로세스가 전송한다.

서술 4: 누군가 CF fan-out 용 executor 빈을 2개 이상 등록하면서 Boot 기본 풀이 물러났고, @Async 가 executor 를 고르지 못해 SimpleAsyncTaskExecutor(호출마다 새 스레드, 상한 없음)로 대체됐다(S16). 반대로 기본 풀이 살아 있었다면 core 8 + 무제한 큐라 스레드는 8개에서 멈추고 대신 큐가 무한히 쌓였을 것이다(S14). 재발 방지: AsyncConfigurer 로 @Async 전용 executor 를 명시(고정 크기, 유한 큐, 거절 정책, 컨텍스트 전파), 다른 executor 빈은 @Qualifier 로만 주입, @Async("name") 로 대상 명시.

서술 5: 좋아지는 것: 요청 스레드(Tomcat worker)가 JDBC 를 기다리지 않으므로 worker 풀 고갈로 인한 다른 엔드포인트의 지연이 줄고, 두 JDBC 호출을 병렬로 하면 200ms → 100ms. 그대로인 것: 두 JDBC 호출이 각각 io 스레드를 100ms 씩 점유한다. 300 RPS × 2 호출 × 0.1s = 동시 60 스레드 + DB 커넥션 60 이 필요하다(Little 의 법칙, R11). 스레드 총량은 줄지 않는다(A 는 막히는 스레드를 바꿀 뿐, R0). 새로 생기는 것: io 풀 크기/큐/거절 정책, 전체 타임아웃(orTimeout → 504 매핑), MDC/SecurityContext 전파, async 요청 타임아웃(503/catch-all 주의), 예외 매핑(CompletionException). worker 200 개가 병목이 아니라면 컨트롤러는 동기로 두고 내부에서만 A(병렬 + join with timeout)를 쓰는 편이 단순하다.
<!-- ANSWER-END -->
