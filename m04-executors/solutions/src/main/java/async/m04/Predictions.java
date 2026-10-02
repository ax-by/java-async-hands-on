package async.m04;

import async.harness.On;

/**
 * m04 예측 시트. {@link Scenarios} 와 {@code probe} 패키지를 읽고 null 을 예측값으로 바꾼다.
 *
 * <p>규칙
 * <ol>
 *   <li>테스트를 돌리기 전에 <b>전부</b> 채운다. 먼저 PREDICT.md 에 근거 규칙(R 번호)을 적고, 그다음 여기에 옮긴다.</li>
 *   <li>첫 채점 결과는 journal/ledger.tsv 에 고정된다. 틀린 뒤 고쳐도 적중률은 오르지 않는다.</li>
 *   <li>사용할 수 있는 값: On.caller(), On.commonPool(), On.freshThread(), On.pool("이름"),
 *       {@link Fate} 상수, {@link Outcome} 상수, 정수(Integer), 시간은 ms 단위 Long (오차 ±50ms 또는 ±25%),
 *       작업 번호 목록은 "1,2" 같은 문자열(오름차순, 공백 없이)</li>
 * </ol>
 */
public final class Predictions {

    private Predictions() {
    }

    // S01 executor 생략 (테스트 JVM: JDK 25, CPU 여러 개)
    public static final On S01_NO_EXECUTOR = /*ANSWER*/ On.commonPool() /*END*/;

    // S02 스레드 1 + 큐 1 이 꽉 찬 상태에서 셋째 작업을 제출 (third), DiscardOldest 는 큐에 있던 둘째 작업(second)
    public static final Fate S02_ABORT_THIRD = /*ANSWER*/ Fate.THROWS_SYNC /*END*/;
    public static final Fate S02_CALLER_RUNS_THIRD = /*ANSWER*/ Fate.RUNS_ON_CALLER /*END*/;
    public static final Fate S02_DISCARD_THIRD = /*ANSWER*/ Fate.NEVER_COMPLETES /*END*/;
    public static final Fate S02_DISCARD_OLDEST_SECOND = /*ANSWER*/ Fate.NEVER_COMPLETES /*END*/;

    // S03 core 2, max 4, 큐 2, 작업 7개
    public static final Integer S03_LARGEST_POOL_SIZE = /*ANSWER*/ 4 /*END*/;
    public static final String S03_QUEUED_IDS = /*ANSWER*/ "3,4" /*END*/;

    // S04 core 2, max 10, 무제한 큐, 100ms 작업 10개
    public static final Integer S04_LARGEST_POOL_SIZE = /*ANSWER*/ 2 /*END*/;

    // S05 스레드 2, 100ms 호출 20개
    public static final Long S05_UNBOUNDED_LAST_MS = /*ANSWER*/ 1000L /*END*/;
    public static final Integer S05_BOUNDED_REJECTED = /*ANSWER*/ 14 /*END*/;

    // S06 같은 풀 안에서 join (스레드 T, 동시 요청 N)
    public static final Outcome S06_T2_N1 = /*ANSWER*/ Outcome.COMPLETES /*END*/;
    public static final Outcome S06_T2_N2 = /*ANSWER*/ Outcome.DEADLOCK /*END*/;
    public static final Outcome S06_T4_N3 = /*ANSWER*/ Outcome.COMPLETES /*END*/;
    public static final Outcome S06_T4_N8 = /*ANSWER*/ Outcome.DEADLOCK /*END*/;

    // S07 느린 추천 API 와 빠른 재고 API: 재고 호출의 응답시간 (ms)
    public static final Long S07_SHARED_INVENTORY_MS = /*ANSWER*/ 550L /*END*/;
    public static final Long S07_BULKHEAD_INVENTORY_MS = /*ANSWER*/ 50L /*END*/;

    // C01 (자식 JVM, commonPool 병렬도 2) commonPool 을 blocking 호출로 채운 뒤
    public static final Long C01_SUPPLY_ASYNC_START_MS = /*ANSWER*/ 1000L /*END*/;
    public static final Long C01_PARALLEL_STREAM_MS = /*ANSWER*/ 600L /*END*/;
    public static final On C01_PARALLEL_STREAM_RAN_ON = /*ANSWER*/ On.caller() /*END*/;

    // V01 (자식 JVM, -XX:ActiveProcessorCount=1) executor 생략 supplyAsync 로 100ms 작업 5개
    public static final On V01_JDK21_THREAD = /*ANSWER*/ On.freshThread() /*END*/;
    public static final Long V01_JDK21_TOTAL_MS = /*ANSWER*/ 100L /*END*/;
    public static final On V01_JDK25_THREAD = /*ANSWER*/ On.commonPool() /*END*/;
    public static final Long V01_JDK25_TOTAL_MS = /*ANSWER*/ 500L /*END*/;
}
