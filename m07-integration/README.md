# m07. 통합: Spring 안의 CompletableFuture (컨텍스트, 트랜잭션, @Async, 서블릿 경계)

> 난이도 ★★★ · 관련 규칙 **R12, R1, R2, R9, R10, R11** · 예측 21개 시나리오(37개 항목) · 구현 6개
> Spring Boot 3.5.16 (Spring Framework 6.2.19, Spring Security 6.5.11, Micrometer Context Propagation 1.1.4), `--release 17`

## 이 모듈을 마치면

- CF 파이프라인의 각 단계에서 **MDC, SecurityContext, 트랜잭션이 보이는지**를 R1/R2/R12로 말할 수 있다.
- 컨텍스트를 전파하는 executor를 **직접** 만들고(capture → set → **restore**), 복원을 빠뜨렸을 때의 보안 사고를 재현할 수 있다.
- "decorated executor로 `thenApplyAsync`하면 된다"가 **언제 틀리는지**(이벤트 루프가 완료시키는 future) 설명하고 고칠 수 있다.
- `@Transactional` 메서드 안의 `supplyAsync`가 왜 위험한지, 어떻게 설계를 바꾸는지 안다.
- `@Async`와 Boot의 기본 executor가 실제로 어떻게 동작하는지(크기, 큐, 물러남, 예외) 검증된 사실로 안다.
- 컨트롤러가 CF를 반환하는 것이 Java 17 서블릿 앱에서 **언제 실제로 도움이 되는지** 판단할 수 있다.

---

## 핵심 개념

### 1. ThreadLocal 컨텍스트는 스레드를 넘지 않는다 (R12)

Spring 애플리케이션의 "요청 컨텍스트"는 대부분 ThreadLocal이다.

| 컨텍스트 | 저장 위치 | 다른 스레드에서 |
|---|---|---|
| 로그 traceId | `MDC` (Logback: ThreadLocal) | 없음 → 로그 상관관계 끊김 |
| 인증 사용자 | `SecurityContextHolder` (기본 `MODE_THREADLOCAL`) | 없음 → `@PreAuthorize` 실패, 감사 로그에 사용자 없음 |
| 트랜잭션 | `TransactionSynchronizationManager` (커넥션을 스레드에 묶음) | **다른 커넥션, autocommit** → 롤백 대상 아님 |
| 요청 객체 | `RequestContextHolder` | 없음 → `IllegalStateException` |

CF의 각 단계가 **어느 스레드에서 실행되는지**(R1, R2)가 곧 **컨텍스트가 있는지**를 결정한다. 같은 non-async 콜백도 등록 시점에 따라 요청 스레드(컨텍스트 있음)에서 돌기도 하고 풀 스레드(없음)에서 돌기도 한다.

### 2. 전파의 원리: capture → set → restore

```java
TaskDecorator decorator = task -> {
    Map<String, String> captured = MDC.getCopyOfContextMap();   // ① 제출 스레드에서 캡처 (decorate 가 불리는 곳)
    return () -> {
        Map<String, String> previous = MDC.getCopyOfContextMap(); // ② 작업 스레드의 기존 값 보관
        set(captured);                                            // ③ 적용 (captured 가 null 이면 clear)
        try { task.run(); }
        finally { set(previous); }                                // ④ 원래대로 복원 (clear 가 아니라 restore)
    };
};
```

- ④를 빠뜨리면 **풀 스레드에 이전 요청의 값이 남는다.** 다음에 컨텍스트 없이 제출된 작업(배치, 헬스 체크, 익명 요청)이 **남의 인증 정보로 실행된다**(리뷰 체크리스트 #16).
- ③에서 "값이 있을 때만 set"하는 코드도 같은 사고를 낸다. 캡처한 값이 없으면 **비워야** 한다.
- ④가 clear가 아니라 restore여야 하는 이유: `CallerRunsPolicy`나 직접 실행 executor에서는 작업 스레드가 **요청 스레드 자신**이다. clear하면 요청 스레드의 traceId가 사라진다(E1).

**라이브러리로 하기** (검증한 API, context-propagation 1.1.4 / Spring 6.2):

```java
ContextRegistry registry = new ContextRegistry()
        .registerThreadLocalAccessor(new Slf4jThreadLocalAccessor("traceId"))       // MDC 의 traceId 키
        .registerThreadLocalAccessor(new SecurityContextHolderThreadLocalAccessor()); // Spring Security
ContextSnapshotFactory factory = ContextSnapshotFactory.builder().contextRegistry(registry).build();

ExecutorService io = ContextExecutorService.wrap(rawPool, factory);        // Micrometer 방식
executor.setTaskDecorator(new ContextPropagatingTaskDecorator(factory));   // Spring 6.1+ 방식 (ThreadPoolTaskExecutor)
```

둘 다 작업 후 원래 값으로 복원한다. 주의할 점:

- `new ContextPropagatingTaskDecorator()`(기본 생성자)는 **전역** `ContextRegistry`를 쓴다. 전역 레지스트리에는 ServiceLoader로 Spring Security의 `SecurityContextHolderThreadLocalAccessor`가 **자동 등록되지만, MDC 접근자는 등록되지 않는다**(Micrometer Tracing이 없을 때). 기본 생성자가 무엇을 전파하는지는 "전역 레지스트리에 어떤 접근자가 있는가"로 정해진다.
- Spring Security 전용: `DelegatingSecurityContextExecutor`/`ExecutorService`/`AsyncTaskExecutor`. 작업 후 복원한다.
- `SecurityContextHolder.MODE_INHERITABLETHREADLOCAL`은 풀과 함께 쓰면 **틀린다.** 값은 스레드가 **만들어질 때** 한 번 복사된다. 풀 스레드를 처음 만든 요청의 인증 정보가 그 스레드가 사는 동안 계속 보인다.

### 3. CF 고유의 함정: 캡처는 `execute()`를 호출하는 스레드에서 일어난다

TaskDecorator, `ContextExecutorService`, `DelegatingSecurityContextExecutor`는 모두 `execute(task)`가 호출되는 순간, **그 호출 스레드의** 컨텍스트를 캡처한다. 그런데 `f.thenApplyAsync(fn, ex)`에서 `ex.execute(...)`를 호출하는 것은 R1이 정한 스레드다.

| `f.thenApplyAsync(fn, decorated)` 를 등록할 때 | `execute()` 를 호출하는 스레드 | fn 에서 컨텍스트 |
|---|---|---|
| f 가 이미 완료됨 | 등록한 스레드(요청 스레드) | **있음** |
| f 가 미완료, f 를 decorated 풀의 작업이 완료시킴 | 그 풀 스레드 (데코레이터 범위 안이라 컨텍스트가 있다) | **있음** ("되는 것처럼 보이는" 이유) |
| f 가 미완료, f 를 **이벤트 루프 / 타임아웃 스케줄러 / 감싸지 않은 풀**이 완료시킴 | 그 스레드 (컨텍스트 없음) | **없음** |

> 그래서 "이벤트 루프가 완료시키는 future에는 `thenApplyAsync(fn, 감싼 executor)`"만으로는 부족하다. executor가 **실행 시점이 아니라 조립 시점(요청 스레드에서 파이프라인을 만드는 순간)**에 캡처해야 한다.
> ```java
> Executor bound = factory.captureAll().wrapExecutor(io);   // 지금(요청 스레드) 캡처
> redis.fetch(key).thenApplyAsync(this::enrich, bound);      // 누가 execute 를 부르든 캡처한 값을 적용
> ```
> (E3에서 직접 구현한다. `docs/reasoning-rules.md` R12의 마지막 항목은 이 조건을 전제로 읽어야 한다.)

### 4. `@Transactional`은 다른 스레드로 넘어가지 않는다

트랜잭션은 **커넥션을 현재 스레드에 묶어 두는 것**이다. `supplyAsync` 안의 `jdbcTemplate.update`는 풀에서 **새 커넥션**을 받아 autocommit으로 실행한다.

- 바깥 트랜잭션이 롤백돼도 그 쓰기는 **이미 커밋**되어 남는다. 비동기 쪽은 바깥 트랜잭션의 미커밋 데이터도 보지 못한다.
- 커넥션 풀이 작으면 더 나쁘다. 바깥 트랜잭션이 커넥션을 잡은 채 `join()`으로 기다리는 동안, 비동기 쪽은 커넥션을 기다린다. 남은 커넥션이 없으면 비동기 쪽은 `connection-timeout`(Hikari 기본 30초)만큼 기다린 뒤 `CannotGetJdbcConnectionException`으로 실패한다. 그 예외가 바깥으로 어떻게 전파되는지(R8)까지 따라가야 결과가 보인다. 운영에서는 "부하가 높을 때만" 생기는 풀 고갈 deadlock이다(R11의 DB 커넥션 버전).
- **설계 원칙**: 병렬화는 트랜잭션 **밖**에서 읽기에만 쓰고, 쓰기는 한 스레드에서 한 트랜잭션으로 한다(E4). 외부 API를 기다리는 동안 트랜잭션(=커넥션)을 잡지 않는다.

### 5. Spring `@Async` (Boot 3.5에서 검증한 사실)

| 항목 | 실제 동작 |
|---|---|
| 프록시 | `@Async`는 프록시가 가로챈다. **같은 빈 안에서 `this.method()`로 부르면 그냥 동기 호출**이다(호출 스레드에서 실행). |
| 기본 executor | `applicationTaskExecutor` (`ThreadPoolTaskExecutor`): core **8**, max `Integer.MAX_VALUE`, 큐 **`Integer.MAX_VALUE`**, keepAlive 60초, 이름 `task-N`. 실제 스레드 수는 `ThreadPoolExecutor`의 스레드 생성 규칙(core → 큐 → max, R11)으로 따져 본다. 별칭은 `bootstrapExecutor` 하나(`taskExecutor` 별칭 없음). |
| 물러남 | 애플리케이션에 `Executor` 빈이 **하나라도** 있으면 `applicationTaskExecutor`가 만들어지지 않는다(`spring.task.execution.mode=force`로 강제 가능). |
| executor 결정 순서 | `AsyncConfigurer.getAsyncExecutor()` → 유일한 `TaskExecutor` 빈 → (없거나 여럿이면) 이름이 `taskExecutor`인 `Executor` 빈 → 그래도 없으면 **`SimpleAsyncTaskExecutor`**: 호출마다 새 스레드(`SimpleAsyncTaskExecutor-N`), 상한 없음. |
| 가상 스레드 | `spring.threads.virtual.enabled=true` + JDK 21+: `applicationTaskExecutor`가 `SimpleAsyncTaskExecutor`(가상 스레드, 이름 `task-N`)가 된다. **Java 17 운영에서는 효과 없음.** |
| 예외 | `void` 메서드의 예외 → `AsyncUncaughtExceptionHandler`. **CF를 반환하는 메서드의 예외 → 반환된 future 안**. 호출자가 future를 버리면 **어디에도 남지 않는다**(R6). |

권장: `AsyncConfigurer`로 executor(이름, 고정 크기, **유한 큐**, 컨텍스트 전파)와 예외 핸들러를 **명시**한다(E6).

### 6. 서블릿 경계: 컨트롤러가 join할 것인가, CF를 반환할 것인가

```
join 하는 컨트롤러                     CF 를 반환하는 컨트롤러 (Servlet async)
http-nio-exec-1: ──handler──join(300ms 대기)──write──┐     http-nio-exec-1: ─handler─┐ (즉시 반환, 다른 요청 처리)
io-1:                  └──work(300ms)──┘              │     io-1:              └──work(300ms)──complete──┐
                                                      │     http-nio-exec-2:                    async dispatch─write
```

- CF를 반환하면 Spring MVC가 Servlet async를 시작하고 **요청 스레드를 즉시 컨테이너에 돌려준다.** future가 완료되면 **컨테이너 스레드에서 다시 dispatch**해서 응답을 쓴다.
- 처리량의 상한: join하는 컨트롤러는 **worker 수**가 동시 처리 요청 수를 묶고, CF를 반환하는 컨트롤러는 worker가 아니라 **작업을 실행하는 풀**이 묶는다(R7, R11).
- **타임아웃**: `spring.mvc.async.request-timeout`이 지나면 Spring MVC는 `AsyncRequestTimeoutException`을 예외 처리 경로로 보낸다. 기본 처리(`DefaultHandlerExceptionResolver`)는 **503**이다. 하지만 이 예외는 `RuntimeException`이라서, `@ExceptionHandler(RuntimeException.class)` 같은 catch-all이 있으면 **그 핸들러가 먼저 잡는다**. 그리고 **future는 취소되지 않는다.** 실제 작업은 계속 돈다(R10). Tomcat은 비동기 타임아웃을 주기적으로(약 1초) 검사하므로 실제 발생 시점은 설정값보다 늦을 수 있다(300ms 설정에서 약 0.8초에 관측).
- **예외 매핑**: 반환한 CF가 `CompletionException(원인)`으로 실패하면 Spring은 **한 겹 벗겨서** 원인으로 `@ExceptionHandler`를 찾는다. 반면 컨트롤러 안에서 `join()`하면 던져지는 것은 `CompletionException`이고, `@ExceptionHandler`는 **예외 자신을 먼저**, 그다음 cause를 본다. `get()`이 던지는 것은 `ExecutionException`(checked, `RuntimeException`이 아니다)이다. 어떤 핸들러가 잡을지는 등록된 핸들러 목록과 이 규칙을 맞춰 보면 정해진다.
- **Callable vs CF**: `Callable`을 반환하면 Spring MVC가 `applicationTaskExecutor`(`task-N`)에서 실행하고, Spring Security의 `WebAsyncManagerIntegrationFilter`가 SecurityContext를 넘겨준다. CF를 반환할 때 **내가 고른 풀에서 실행되는 작업에는 아무도 넘겨주지 않는다.**

**Java 17 서블릿 앱에서 CF 반환이 실제로 도움이 되는 경우**

| 작업의 성격 | CF 반환의 효과 |
|---|---|
| **B** (진짜 비동기 클라이언트: WebClient, JDK `HttpClient.sendAsync`, Lettuce) | I/O 동안 **어떤 스레드도 점유하지 않는다.** 요청 스레드도 풀린다. 가장 큰 효과 |
| **A** (blocking 호출을 io 풀로 넘김) | 요청 스레드는 풀리지만 **io 스레드가 대신 막힌다.** 전체 스레드 수는 줄지 않는다. 효과는 "요청 스레드 풀이 병목이고, 느린 엔드포인트가 빠른 엔드포인트를 굶기는" 경우로 한정된다. io 풀 크기/큐/거절 정책이 새 병목이 된다 |
| 결국 컨트롤러에서 `join()` | 아무 효과 없음. 스레드 2개를 쓰고 컨텍스트만 잃는다(R0 "쓰지 않음") |

---

## 진행 방법

1. 위 개념을 읽는다. [추론 규칙 카드](../docs/reasoning-rules.md)의 R12, R1, R2, R10, R11을 함께 본다.
2. 시나리오를 읽고 [PREDICT.md](PREDICT.md)에 **예측과 근거 규칙**을 적는다.
   - `src/main/java/async/m07/ctx/ContextScenarios.java` (S01~S10)
   - `src/main/java/async/m07/tx/TxScenario.java` (S11~S12)
   - `src/main/java/async/m07/async/AsyncScenario.java` (S13~S16)
   - `src/main/java/async/m07/web/WebScenario.java` (S17~S21)
3. 예측을 `Predictions.java`로 옮긴다. **전부 채운 뒤** 채점한다(첫 결과는 고정된다).
   ```bash
   mvn -pl m07-integration -am test -Dtest='*PredictionTest*' -Dsurefire.failIfNoSpecifiedTests=false
   python3 tools/score.py
   ```
4. 틀린 항목은 [journal](../journal/README.md)에 "어떤 규칙을 어떻게 잘못 적용했는지" 적는다.
5. 구현 실습. 순서대로 한다(E5는 E2의 executor를 쓴다).

   | 실습 | 파일 | 내용 | 채점 |
   |---|---|---|---|
   | E1 | `Exercises.mdcTaskDecorator` | MDC TaskDecorator 손으로 작성 (restore 포함) | `ExercisesTest` |
   | E2 | `Exercises.orderExecutor` | 운영용 `ThreadPoolTaskExecutor` (고정 크기, 유한 큐, MDC+Security 전파) | `ExercisesTest` |
   | E3 | `Exercises.boundToCurrentContext` | 조립 시점 캡처 executor (이벤트 루프 함정 해결) | `ExercisesTest` |
   | E4 | `tx/OrderPlacementFacade` | 병렬 조회는 트랜잭션 밖, 쓰기는 한 트랜잭션 | `TxExerciseTest` |
   | E5 | `web/ProductController` | CF 반환 컨트롤러 + 타임아웃 504 + 404 | `WebExerciseTest` |
   | E6 | `async/AsyncExerciseConfig` | `AsyncConfigurer` 명시 구성 + 예외 핸들러 | `AsyncExerciseTest` |

   ```bash
   mvn -pl m07-integration -am test -Dtest='*ExerciseTest*,ExercisesTest' -Dsurefire.failIfNoSpecifiedTests=false
   ```
6. 모듈 전체: `mvn -q -pl m07-integration test` (약 15초. Spring 컨텍스트 여러 개와 Tomcat 2개를 띄운다)

**통과 기준**: 예측 적중률(첫 시도) 90% 이상, 구현 테스트 전부 통과, PREDICT.md 서술 문제 답안 작성.

## 검증된 사실 (자주 오해하는 것)

`solutions/src/test/java`의 `VerifiedFactsTest`가 아래 사실들을 고정한다. 예측 정답에 해당하므로 학습자용 `src/test`에는 이 테스트도, 아래 표의 내용도 없다. 채점이 끝난 뒤 "흔한 믿음 → 실제" 표를 직접 채워 보라. 라이브러리 버전을 올리면 `-Psolutions`로 이 테스트부터 돌려 보라.

(여기에 작성)
