# 리뷰 체크리스트

다른 사람(또는 어제의 나)의 CF 파이프라인을 리뷰할 때 위에서부터 순서대로 확인한다. 각 항목의 **증상**은 m08 버그 찾기에서 직접 재현한다.

## 0. 목적 (R0)
- [ ] 이 파이프라인의 목적이 A인지 B인지 코드나 주석으로 알 수 있는가? 목적과 구현이 맞는가?
- [ ] 결국 바로 `join()`할 거라면 CF가 필요한가?

## 1. 스레드와 blocking (R1~R5, R11)
| # | 안티패턴 | 찾는 법 | 증상 |
|---|---|---|---|
| 1 | **가짜 async**: `completedFuture(blockingCall())` | 메서드가 future를 반환하는데 본문에서 blocking 호출을 먼저 한다 | 호출자가 비동기라고 믿고 이벤트 루프에서 호출하면 멈춘다 |
| 2 | **중간 join**: `thenApply(x -> other(x).join())` | 람다 안의 `join`/`get` | 실행 스레드 점유, 직렬화, 최악에는 deadlock |
| 3 | **I/O 스레드에서 blocking** | 비동기 클라이언트 future + non-async 콜백 + blocking 본문 | 이벤트 루프 lag 증가, 관계없는 요청까지 함께 느려진다 |
| 4 | **executor 생략 + blocking** | `supplyAsync(() -> jdbc...)` | commonPool 고갈, parallel stream까지 멈춘다. Java 17 소형 컨테이너에서는 스레드 폭증 |
| 5 | **같은 풀 안에서 대기** | 풀 A의 작업이 풀 A에 제출한 future를 `join` | 부하가 높을 때만 영원히 멈춘다(thread starvation deadlock) |
| 6 | **CallerRuns를 이벤트 루프에서 사용** | `CallerRunsPolicy` executor + 이벤트 루프에서 제출 | 큐가 찰 때만 이벤트 루프에서 blocking |
| 7 | **루프 안 join** | `for (...) { supplyAsync(..).join(); }` | 병렬인 줄 알았는데 순차 |
| 8 | **불필요한 Async 남발** | 가벼운 변환마다 `xxxAsync` | 스레드 전환 비용, 컨텍스트 유실 지점 증가 |

## 2. 실패 (R6, R8~R10)
| # | 안티패턴 | 찾는 법 | 증상 |
|---|---|---|---|
| 9 | **fire-and-forget** | 반환된 future를 버린다 | 예외가 로그도 없이 사라진다 |
| 10 | **unwrap 없는 예외 판별** | `ex instanceof MyException` | 폴백이 동작하지 않는다(CompletionException) |
| 11 | **타임아웃 없음** | 파이프라인 끝까지 `orTimeout`/`get(timeout)`이 없다 | 다운스트림 장애 시 요청이 무한 대기 |
| 12 | **타임아웃이 작업을 멈춘다고 착각** | `orTimeout`/`cancel` 후 정리 코드가 없다 | 스레드와 커넥션이 계속 점유되어 결국 풀 고갈 |
| 13 | **whenComplete로 복구 시도** | `whenComplete`에서 기본값을 반환하려 한다 | 실패가 그대로 전파된다 |
| 14 | **thenApply가 future를 반환** | `CompletableFuture<CompletableFuture<T>>` | 중첩 join(2번)으로 이어진다 |

## 3. 컨텍스트 (R12)
| # | 안티패턴 | 증상 |
|---|---|---|
| 15 | 다른 스레드에서 MDC/SecurityContext/트랜잭션 사용 | traceId 유실, 인증 정보 없음, 트랜잭션 밖 쿼리 |
| 16 | 컨텍스트 전파 후 복원 누락 | 풀 스레드에 이전 요청의 인증 정보가 남는다(보안 사고) |
| 16b | 감싼 executor(`TaskDecorator` 등)를 `thenApplyAsync`에 넘기고 안전하다고 믿음. 앞 단계는 이벤트 루프가 완료시킨다 | 데코레이터가 `execute()`를 호출한 이벤트 루프의 **빈 컨텍스트**를 캡처한다. traceId와 인증 정보가 사라진다. 조립 시점에 캡처하라(R12) |

## 4. 공개 API (m09)
| # | 안티패턴 | 증상 |
|---|---|---|
| 17 | 내부 `CompletableFuture`를 그대로 반환 | 호출자가 `complete()`/`obtrudeValue()`로 내부 상태를 오염시킨다 |
| 18 | 콜백이 실행될 스레드를 문서화하지 않음 | 사용자가 I/O 스레드에서 blocking한다 |
| 19 | 라이브러리 내부에서 commonPool 사용 | 사용자의 애플리케이션 전체에 영향을 준다 |

## 리뷰 코멘트 양식
```
[R4][#3] L42 thenApply 안의 userRepository.findById 는 JDBC(blocking)이고,
앞 단계 future 는 lettuce 이벤트 루프가 완료시키므로(R1) 이벤트 루프 스레드를 ~50ms 점유한다.
→ thenComposeAsync(.., dbPool) 또는 supplyAsync(.., dbPool) + thenCompose 로 격리 제안.
증상 예상: Redis 응답 p99 상승 (이벤트 루프 lag).
```
