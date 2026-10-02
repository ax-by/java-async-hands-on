package async.m05;

import async.harness.On;

/**
 * m05 예측 시트. {@link Scenarios} 와 PREDICT.md 의 설계 판단 퀴즈(D01~D06)를 읽고 null 을 예측값으로 바꾼다.
 *
 * <p>규칙
 * <ol>
 *   <li>테스트를 돌리기 전에 <b>전부</b> 채운다. 먼저 PREDICT.md 에 근거 규칙(R 번호)을 적고, 그다음 여기에 옮긴다.</li>
 *   <li>첫 채점 결과는 journal/ledger.tsv 에 고정된다. 틀린 뒤 고쳐도 적중률은 오르지 않는다.</li>
 *   <li>사용할 수 있는 값: On.caller(), On.commonPool(), On.pool("io"), On.timeoutScheduler(),
 *       시간은 ms 단위 Long (오차 ±50ms 또는 ±25%), 정수(Integer), 예외 클래스(예: IllegalStateException.class),
 *       {@link LoadOutcome}, {@link Design}</li>
 * </ol>
 */
public final class Predictions {

    private Predictions() {
    }

    // T01 기존 순차 코드
    public static final Long T01_NAIVE_MS = /*ANSWER*/ 450L /*END*/;
    public static final On T01_PRODUCT_THREAD = /*ANSWER*/ On.caller() /*END*/;

    // T02 PR #1 (executor 생략)
    public static final Long T02_COMMON_POOL_MS = /*ANSWER*/ 150L /*END*/;
    public static final On T02_PRICE_THREAD = /*ANSWER*/ On.commonPool() /*END*/;

    // T03 PR #2 (호출마다 join): future 를 반환하기까지
    public static final Long T03_RETURNED_MS = /*ANSWER*/ 450L /*END*/;

    // T04 병렬 fan-out (io 8개)
    public static final Long T04_RETURNED_MS = /*ANSWER*/ 0L /*END*/;
    public static final Long T04_TOTAL_MS = /*ANSWER*/ 150L /*END*/;

    // T05 병렬 fan-out (io 2개)
    public static final Long T05_TWO_THREADS_MS = /*ANSWER*/ 270L /*END*/;

    // T06 가격 API 1000ms + 전체 타임아웃 300ms
    public static final Long T06_FAILED_AT_MS = /*ANSWER*/ 300L /*END*/;
    public static final Integer T06_PRICE_IN_FLIGHT = /*ANSWER*/ 1 /*END*/;
    public static final Class<?> T06_EXCEPTION_SEEN_BY_HANDLE = /*ANSWER*/ java.util.concurrent.CompletionException.class /*END*/;
    public static final On T06_HANDLE_THREAD = /*ANSWER*/ On.timeoutScheduler() /*END*/;

    // L01 100 RPS × 40 요청, 병렬 fan-out + 전체 타임아웃 300ms
    public static final LoadOutcome L01_POOL_60 = /*ANSWER*/ LoadOutcome.ALL_OK /*END*/;
    public static final LoadOutcome L01_POOL_16 = /*ANSWER*/ LoadOutcome.MANY_TIMEOUTS /*END*/;

    // L02 리뷰 DB 1000ms 장애 + 리뷰 소스 타임아웃 200ms, 풀 60, 100 RPS × 150 요청
    public static final LoadOutcome L02_SLOW_OPTIONAL = /*ANSWER*/ LoadOutcome.MANY_TIMEOUTS /*END*/;

    // D01~D06 설계 판단 퀴즈 (PREDICT.md)
    public static final Design D01 = /*ANSWER*/ Design.NO_CF /*END*/;
    public static final Design D02 = /*ANSWER*/ Design.A /*END*/;
    public static final Design D03 = /*ANSWER*/ Design.NO_CF /*END*/;
    public static final Design D04 = /*ANSWER*/ Design.A /*END*/;
    public static final Design D05 = /*ANSWER*/ Design.B /*END*/;
    public static final Design D06 = /*ANSWER*/ Design.NO_CF /*END*/;
}
