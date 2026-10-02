package async.m07;

import async.harness.On;

/**
 * m07 예측 시트. 시나리오 코드를 읽고 null 을 예측값으로 바꾼다.
 * <ul>
 *   <li>컨텍스트: {@code async.m07.ctx.ContextScenarios} (S01~S10)</li>
 *   <li>트랜잭션: {@code async.m07.tx.TxScenario} (S11~S12)</li>
 *   <li>@Async: {@code async.m07.async.AsyncScenario} (S13~S16)</li>
 *   <li>서블릿 경계: {@code async.m07.web.WebScenario} (S17~S21)</li>
 * </ul>
 *
 * <p>규칙
 * <ol>
 *   <li>테스트를 돌리기 전에 <b>전부</b> 채운다. 먼저 PREDICT.md 에 근거 규칙(R 번호)을 적고, 그다음 여기에 옮긴다.</li>
 *   <li>첫 채점 결과는 journal/ledger.tsv 에 고정된다.</li>
 *   <li>사용할 수 있는 값
 *     <ul>
 *       <li>traceId / user: {@code "t-100"}, {@code "t-alice"}, {@code "alice"}, {@code "bob"}, 없으면 {@code "NONE"}</li>
 *       <li>스레드: {@code On.caller()}, {@code On.pool("io")}, {@code On.pool("task")}, {@code On.pool("tomcat")},
 *           {@code On.pool("SimpleAsyncTaskExecutor")}, {@code On.pool("product")}, {@code On.pool("review")}, {@code On.eventLoop()}</li>
 *       <li>시간: ms 단위 Long (오차 ±50ms 또는 ±25%), 개수/HTTP 상태: Integer, 예/아니오: Boolean</li>
 *     </ul>
 *   </li>
 * </ol>
 */
public final class Predictions {

    private Predictions() {
    }

    // ── 1. MDC 와 executor (요청 스레드: traceId=t-100, user=alice) ──────────────────

    // S01 supplyAsync(io) 안에서 보이는 traceId
    public static final String S01_SUPPLY_TRACE = /*ANSWER*/ "NONE" /*END*/;

    // S02 non-async 콜백: 완료 전 등록(before) / 완료 후 등록(after) 에서 보이는 traceId
    public static final String S02_BEFORE_TRACE = /*ANSWER*/ "NONE" /*END*/;
    public static final String S02_AFTER_TRACE = /*ANSWER*/ "t-100" /*END*/;

    // S03 스레드 1개 풀. alice 다음에 컨텍스트 없는 스레드가 제출한 작업에서 보이는 user
    public static final String S03_LEAKY_ANONYMOUS_USER = /*ANSWER*/ "alice" /*END*/;
    public static final String S03_FIXED_ANONYMOUS_USER = /*ANSWER*/ "NONE" /*END*/;

    // S04 복원하는 decorated 풀 안의 체인: map(non-async), mapAsync(같은 decorated 풀) 에서 보이는 traceId
    public static final String S04_MAP_TRACE = /*ANSWER*/ "t-100" /*END*/;
    public static final String S04_MAP_ASYNC_TRACE = /*ANSWER*/ "t-100" /*END*/;

    // ── 4. CF 고유의 함정: 내가 통제하지 못하는 스레드가 완료시키는 future ──────────────

    // S05 이벤트 루프가 완료시키는 future + non-async 콜백에서 보이는 traceId
    public static final String S05_MAP_TRACE = /*ANSWER*/ "NONE" /*END*/;

    // S06 같은 future + thenApplyAsync(fn, decorated 풀), 완료 전 등록
    public static final String S06_MAP_ASYNC_TRACE = /*ANSWER*/ "NONE" /*END*/;

    // S07 같은 코드, 완료 후 등록
    public static final String S07_MAP_ASYNC_TRACE = /*ANSWER*/ "t-100" /*END*/;

    // ── 2. SecurityContext ─────────────────────────────────────────────────────────

    // S08 DelegatingSecurityContextExecutor: during(감싼 executor), after(같은 스레드, 감싸지 않음) 에서 보이는 user
    public static final String S08_DURING_USER = /*ANSWER*/ "alice" /*END*/;
    public static final String S08_AFTER_USER = /*ANSWER*/ "NONE" /*END*/;

    // S09 MODE_INHERITABLETHREADLOCAL: bob 의 요청이 제출한 작업에서 보이는 user
    public static final String S09_BOB_TASK_USER = /*ANSWER*/ "alice" /*END*/;

    // S10 new ContextPropagatingTaskDecorator() (기본 생성자) 로 감싼 작업에서 보이는 traceId / user
    public static final String S10_TRACE = /*ANSWER*/ "NONE" /*END*/;
    public static final String S10_USER = /*ANSWER*/ "alice" /*END*/;

    // ── 3. @Transactional ──────────────────────────────────────────────────────────

    // S11 롤백 후 orders / audit 행 수, 비동기 작업 안에서 트랜잭션이 활성이었나
    public static final Integer S11_ORDERS_ROWS = /*ANSWER*/ 0 /*END*/;
    public static final Integer S11_AUDIT_ROWS = /*ANSWER*/ 1 /*END*/;
    public static final Boolean S11_ASYNC_IN_TX = /*ANSWER*/ Boolean.FALSE /*END*/;

    // S12 커넥션 풀 1개: placeOrderAndCommit 이 끝나는(예외든 정상이든) 데 걸리는 시간(ms), 그 뒤 orders 행 수
    public static final Long S12_ELAPSED_MS = /*ANSWER*/ 250L /*END*/;
    public static final Integer S12_ORDERS_ROWS = /*ANSWER*/ 0 /*END*/;

    // ── 5. Spring @Async ───────────────────────────────────────────────────────────

    // S13 프록시를 통한 호출(proxy) / 같은 빈 안에서 this 로 호출(self) 의 실행 스레드
    public static final On S13_PROXY = /*ANSWER*/ On.pool("task") /*END*/;
    public static final On S13_SELF = /*ANSWER*/ On.caller() /*END*/;

    // S14 200ms @Async 20개를 한꺼번에 호출: 자동 구성 executor 가 동시에 띄운 최대 스레드 수
    public static final Integer S14_LARGEST_POOL = /*ANSWER*/ 8 /*END*/;

    // S15 예외가 AsyncUncaughtExceptionHandler 로 갔는가: void 메서드 / CF 반환 메서드
    public static final Boolean S15_VOID_TO_HANDLER = /*ANSWER*/ Boolean.TRUE /*END*/;
    public static final Boolean S15_FUTURE_TO_HANDLER = /*ANSWER*/ Boolean.FALSE /*END*/;

    // S16 executor 빈 2개를 추가한 뒤 @Async 메서드의 실행 스레드
    public static final On S16_THREAD = /*ANSWER*/ On.pool("SimpleAsyncTaskExecutor") /*END*/;

    // ── 6. 서블릿 경계 (Tomcat worker 2개) ─────────────────────────────────────────

    // S17 동시 요청 10개의 총 소요시간(ms): join 하는 컨트롤러 / CF 를 반환하는 컨트롤러
    public static final Long S17_BLOCKING_TOTAL_MS = /*ANSWER*/ 1500L /*END*/;
    public static final Long S17_ASYNC_TOTAL_MS = /*ANSWER*/ 300L /*END*/;

    // S18 CF 반환 컨트롤러: 실제 작업(work) / 응답 본문을 쓰는(write) 스레드
    public static final On S18_WORK = /*ANSWER*/ On.pool("io") /*END*/;
    public static final On S18_WRITE = /*ANSWER*/ On.pool("tomcat") /*END*/;

    // S19 영원히 완료되지 않는 CF 반환: HTTP 상태 / 타임아웃 뒤 그 future 가 취소(cancelled)됐나
    public static final Integer S19_STATUS = /*ANSWER*/ 500 /*END*/;
    public static final Boolean S19_FUTURE_CANCELLED = /*ANSWER*/ Boolean.FALSE /*END*/;

    // S20 OrderNotFoundException(→404 핸들러)과 RuntimeException(→500 핸들러)이 함께 있을 때 HTTP 상태
    public static final Integer S20_RETURN_CF_STATUS = /*ANSWER*/ 404 /*END*/;
    public static final Integer S20_JOIN_STATUS = /*ANSWER*/ 500 /*END*/;
    public static final Integer S20_GET_STATUS = /*ANSWER*/ 404 /*END*/;

    // S21 alice 로 인증한 요청: Callable 반환 / CF(supplyAsync io) 반환에서 보이는 user
    public static final String S21_CALLABLE_USER = /*ANSWER*/ "alice" /*END*/;
    public static final String S21_CF_USER = /*ANSWER*/ "NONE" /*END*/;
}
