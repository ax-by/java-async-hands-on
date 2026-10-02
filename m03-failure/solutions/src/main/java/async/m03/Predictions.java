package async.m03;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import async.harness.On;
import async.m03.Errors.OutOfStockException;
import async.m03.Errors.PaymentDeclinedException;

/**
 * m03 예측 시트. {@link Scenarios} 를 읽고 null 을 예측값으로 바꾼다.
 *
 * <p>규칙
 * <ol>
 *   <li>테스트를 돌리기 전에 <b>전부</b> 채운다. 먼저 PREDICT.md 에 근거 규칙(R 번호)을 적고, 그다음 여기에 옮긴다.</li>
 *   <li>첫 채점 결과는 journal/ledger.tsv 에 고정된다. 틀린 뒤 고쳐도 적중률은 오르지 않는다.</li>
 *   <li>예외 타입은 {@code CompletionException.class}, {@code OutOfStockException.class} 처럼 클래스로 쓴다
 *       (도메인 예외는 {@code async.m03.Errors} 안에 있다. 필요한 import 는 직접 추가한다).
 *       결과는 {@link Outcome} 형식 문자열("ok:값", "failed:근본원인클래스"), 시간은 ms 단위 Long,
 *       스레드는 On.caller(), On.pool("io"), On.timeoutScheduler().</li>
 * </ol>
 */
public final class Predictions {

    private Predictions() {
    }

    // S01 실행된 단계 (쉼표로 연결, 공백 없이)
    public static final String S01_STAGES = /*ANSWER*/ "recover,notify" /*END*/;

    // S02 supplyAsync 예외 → 바로 붙인 exceptionally 가 받는 타입
    public static final Class<?> S02_EX_TYPE = /*ANSWER*/ CompletionException.class /*END*/;

    // S03 completeExceptionally → (a) 바로 붙인 exceptionally, (b) thenApply 를 거친 exceptionally
    public static final Class<?> S03_DIRECT = /*ANSWER*/ PaymentDeclinedException.class /*END*/;
    public static final Class<?> S03_AFTER_MAP = /*ANSWER*/ CompletionException.class /*END*/;

    // S04 (a) get() 이 던진 예외의 cause, (b) join() 이 던진 예외
    public static final Class<?> S04_GET_CAUSE = /*ANSWER*/ OutOfStockException.class /*END*/;
    public static final Class<?> S04_JOIN_THROWS = /*ANSWER*/ CompletionException.class /*END*/;

    // S05 instanceof 로 품절 판별
    public static final String S05_OUTCOME = /*ANSWER*/ "failed:OutOfStockException" /*END*/;

    // S06 whenComplete 로 장애 처리
    public static final String S06_OUTCOME = /*ANSWER*/ "failed:ExternalApiException" /*END*/;

    // S07 실패 + whenComplete 액션도 예외
    public static final String S07_OUTCOME = /*ANSWER*/ "failed:PaymentDeclinedException" /*END*/;
    public static final Class<?> S07_SUPPRESSED = /*ANSWER*/ NullPointerException.class /*END*/;

    // S08 성공 + whenComplete 액션 예외
    public static final String S08_OUTCOME = /*ANSWER*/ "failed:NullPointerException" /*END*/;

    // S09 복구 위치 (a) 먼저, (b) 나중
    public static final String S09_RECOVER_FIRST = /*ANSWER*/ "ok:discount=0%" /*END*/;
    public static final String S09_RECOVER_LAST = /*ANSWER*/ "ok:no-discount" /*END*/;

    // S10 orTimeout → (a) 바로 붙인 exceptionally 가 받는 타입, (b) 직후 inFlight
    public static final Class<?> S10_EX_TYPE = /*ANSWER*/ TimeoutException.class /*END*/;
    public static final Integer S10_IN_FLIGHT = /*ANSWER*/ 1 /*END*/;

    // S11 completeOnTimeout → (a) render 스레드, (b) 원래 future 의 값
    public static final On S11_RENDER = /*ANSWER*/ On.timeoutScheduler() /*END*/;
    public static final String S11_SOURCE_VALUE = /*ANSWER*/ "cached" /*END*/;

    // S12 get(100ms) → (a) TimeoutException 시점, (b) 직후 isDone()
    public static final Long S12_TIMED_OUT_MS = /*ANSWER*/ 100L /*END*/;
    public static final Boolean S12_DONE_AFTER = /*ANSWER*/ false /*END*/;

    // S13 CompletableFuture.cancel(true) 후 interruptedCount
    public static final Integer S13_INTERRUPTED = /*ANSWER*/ 0 /*END*/;

    // S14 하류 취소 → 상류 isCancelled
    public static final Boolean S14_UPSTREAM_CANCELLED = /*ANSWER*/ false /*END*/;

    // S15 상류 취소 → (a) 하류 join 예외 타입, (b) 하류 isCancelled
    public static final Class<?> S15_DEP_JOIN_THROWS = /*ANSWER*/ CompletionException.class /*END*/;
    public static final Boolean S15_DEP_IS_CANCELLED = /*ANSWER*/ false /*END*/;

    // S16 ExecutorService.submit → Future.cancel(true) 후 interruptedCount
    public static final Integer S16_INTERRUPTED = /*ANSWER*/ 1 /*END*/;
}
