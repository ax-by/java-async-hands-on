package async.m05;

/** L01 부하 시나리오의 결과 분류. */
public enum LoadOutcome {
    /** 모든 요청 성공, 가장 느린 요청도 250ms 미만. */
    ALL_OK,
    /** 실패가 1~9건이거나, 모두 성공했지만 250ms 이상 걸린 요청이 있다. */
    DEGRADED,
    /** 40건 중 10건 이상이 타임아웃으로 실패. */
    MANY_TIMEOUTS;

    public static LoadOutcome classify(int failed, long maxSuccessLatencyMillis) {
        if (failed >= 10) {
            return MANY_TIMEOUTS;
        }
        if (failed == 0 && maxSuccessLatencyMillis < 250) {
            return ALL_OK;
        }
        return DEGRADED;
    }
}
