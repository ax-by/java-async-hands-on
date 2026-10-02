package async.m04;

/**
 * executor 에 제출한 작업(그리고 그 작업의 CompletableFuture)이 맞게 되는 운명. S02 예측에 쓴다.
 */
public enum Fate {
    /** 정상적으로 executor 스레드에서 실행되고 값으로 완료된다. */
    COMPLETES_NORMALLY,
    /** {@code supplyAsync} 호출 자체가 예외를 던진다. future 는 만들어지지도 않는다. */
    THROWS_SYNC,
    /** 작업이 제출한 스레드(caller)에서 그 자리에서 실행된다. */
    RUNS_ON_CALLER,
    /** 예외 없이 future 가 반환되지만, 영원히 완료되지 않는다. */
    NEVER_COMPLETES,
    /** future 가 반환되고, 나중에 예외로 완료된다. */
    COMPLETES_EXCEPTIONALLY
}
