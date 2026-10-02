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

(여기에 작성)
