package async.m02;

import java.util.concurrent.CompletableFuture;

import async.harness.On;

/**
 * m02 예측 시트. {@link Scenarios} 를 읽고 null 을 예측값으로 바꾼다.
 *
 * <p>규칙
 * <ol>
 *   <li>테스트를 돌리기 전에 <b>전부</b> 채운다. 먼저 PREDICT.md 에 근거 규칙(R 번호)을 적고, 그다음 여기에 옮긴다.</li>
 *   <li>첫 채점 결과는 journal/ledger.tsv 에 고정된다. 틀린 뒤 고쳐도 적중률은 오르지 않는다.</li>
 *   <li>시간은 ms 단위 Long (예: 0L, 300L. 오차 ±50ms 또는 ±25%). 스레드는 On.caller(), On.pool("io"|"small"|"a"|"b").
 *       클래스는 String.class 처럼 쓴다. 문자열은 반환값 그대로 쓴다.</li>
 * </ol>
 */
public final class Predictions {

    private Predictions() {
    }

    // S01 thenApply 에 future 를 반환하는 함수
    public static final Class<?> S01_JOINED_TYPE = /*ANSWER*/ CompletableFuture.class /*END*/;
    public static final Long S01_JOINED_MS = /*ANSWER*/ 100L /*END*/;

    // S02 thenCompose
    public static final Long S02_DONE_MS = /*ANSWER*/ 300L /*END*/;

    // S03 먼저 시작 + thenCombine (100ms, 300ms)
    public static final Long S03_DONE_MS = /*ANSWER*/ 300L /*END*/;

    // S04 루프 안 join (300, 100, 200ms)
    public static final Long S04_DONE_MS = /*ANSWER*/ 600L /*END*/;

    // S05 먼저 전부 시작, 그다음 루프 join
    public static final Long S05_DONE_MS = /*ANSWER*/ 300L /*END*/;

    // S06 6개 × 100ms, 스레드 2개
    public static final Long S06_DONE_MS = /*ANSWER*/ 300L /*END*/;
    public static final Integer S06_MAX_CONCURRENCY = /*ANSWER*/ 2 /*END*/;

    // S07 allOf 수집 (완료 순서 io → a → b)
    public static final On S07_COLLECT = /*ANSWER*/ On.pool("b") /*END*/;
    public static final String S07_RESULT = /*ANSWER*/ "A,B,C" /*END*/;

    // S08 분기: 검증(실패)과 보강
    public static final String S08_RESULT = /*ANSWER*/ "order-db:o1+enriched" /*END*/;
    public static final Boolean S08_VALIDATE_RAN = /*ANSWER*/ true /*END*/;

    // S09 쿠폰과 배송비
    public static final Integer S09_RESULT = /*ANSWER*/ 1030 /*END*/;

    // S10 userDb 호출 횟수
    public static final Integer S10_CALLS = /*ANSWER*/ 3 /*END*/;

    // S11 anyOf: 빠른 실패 vs 느린 성공
    public static final Boolean S11_FAILED = /*ANSWER*/ true /*END*/;
    public static final Long S11_DONE_MS = /*ANSWER*/ 100L /*END*/;
    public static final Integer S11_DB_IN_FLIGHT = /*ANSWER*/ 1 /*END*/;

    // S12 applyToEither (primary 300ms, replica 100ms)
    public static final Long S12_DONE_MS = /*ANSWER*/ 100L /*END*/;

    // S13 이미 완료된 두 future 의 thenCombine
    public static final On S13_COMBINE = /*ANSWER*/ On.caller() /*END*/;

    // S14 allOf 뒤 콜백 안의 join 들이 걸린 시간
    public static final Long S14_JOINS_MS = /*ANSWER*/ 0L /*END*/;

    // S15 user → (orders ∥ points)
    public static final Long S15_DONE_MS = /*ANSWER*/ 400L /*END*/;

    // S16 allOf: 100ms 실패 + 300ms 성공
    public static final Long S16_DONE_MS = /*ANSWER*/ 300L /*END*/;

    // S17 thenCombine: 100ms 실패 + 300ms 성공
    public static final Long S17_DONE_MS = /*ANSWER*/ 300L /*END*/;
}
