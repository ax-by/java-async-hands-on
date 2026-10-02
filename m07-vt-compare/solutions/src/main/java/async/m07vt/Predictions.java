package async.m07vt;

/**
 * m07-vt 예측 시트. {@link VtScenarios} 를 읽고 null 을 예측값으로 바꾼다.
 * <ul>
 *   <li>시간: ms 단위 Long (오차 ±50ms 또는 ±25%)</li>
 *   <li>개수: Integer</li>
 *   <li>컨텍스트 값: "t-100", "t-200", 없으면 "NONE"</li>
 * </ul>
 * 먼저 PREDICT.md 에 근거(R 번호 또는 README 의 VT 규칙)를 적고 옮긴다. 첫 채점 결과는 고정된다.
 */
public final class Predictions {

    private Predictions() {
    }

    // V01 CF + allOf: product 50ms 실패, price/review 500ms. 예외가 나오기까지 ms / interrupt 된 형제 작업 수
    public static final Long V01_CF_FAIL_MS = /*ANSWER*/ 500L /*END*/;
    public static final Integer V01_CF_INTERRUPTED = /*ANSWER*/ 0 /*END*/;

    // V02 StructuredTaskScope (기본 Joiner): 같은 구성
    public static final Long V02_SCOPE_FAIL_MS = /*ANSWER*/ 50L /*END*/;
    public static final Integer V02_SCOPE_INTERRUPTED = /*ANSWER*/ 2 /*END*/;

    // V03 100ms blocking 호출 2,000개: 플랫폼 스레드 200개 풀 / 작업마다 가상 스레드
    public static final Long V03_PLATFORM_MS = /*ANSWER*/ 1000L /*END*/;
    public static final Long V03_VIRTUAL_MS = /*ANSWER*/ 100L /*END*/;

    // V04 가상 스레드 executor 작업에서 보이는 ThreadLocal / InheritableThreadLocal
    public static final String V04_THREAD_LOCAL = /*ANSWER*/ "NONE" /*END*/;
    public static final String V04_INHERITABLE = /*ANSWER*/ "t-100" /*END*/;

    // V05 다음 요청(t-200)의 작업에서 보이는 InheritableThreadLocal: 플랫폼 1개 풀 / 가상 스레드 executor
    public static final String V05_PLATFORM_POOL = /*ANSWER*/ "t-100" /*END*/;
    public static final String V05_VIRTUAL = /*ANSWER*/ "t-200" /*END*/;

    // V06 ScopedValue: StructuredTaskScope.fork / 가상 스레드 executor / CF.supplyAsync 에서 보이는 값
    public static final String V06_FORK = /*ANSWER*/ "t-100" /*END*/;
    public static final String V06_EXECUTOR = /*ANSWER*/ "NONE" /*END*/;
    public static final String V06_CF = /*ANSWER*/ "NONE" /*END*/;

    // V07 synchronized 안에서 100ms blocking 하는 가상 스레드 (4 × 코어 수)개, JDK 25 총 ms
    public static final Long V07_SYNCHRONIZED_MS = /*ANSWER*/ 100L /*END*/;
}
