package async.m09.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Java 8 용 타임아웃. Java 9 의 {@code orTimeout} 은 쓸 수 없다(이 모듈의 main 은 {@code --release 8}).
 */
public final class Timeouts {

    private Timeouts() {
    }

    /** {@link #failAfter(CompletableFuture, long, TimeUnit, ScheduledExecutorService, Runnable)} 에서 onTimeout 이 없는 형태. */
    public static <T> CompletableFuture<T> failAfter(CompletableFuture<T> future, long timeout, TimeUnit unit,
                                                     ScheduledExecutorService timer) {
        return failAfter(future, timeout, unit, timer, () -> { });
    }

    /**
     * E2. Java 8 판 {@code orTimeout}.
     *
     * <ol>
     *   <li>{@code timeout} 안에 {@code future} 가 완료되지 않으면 {@code timer} 스레드에서
     *       {@code future} 를 {@link TimeoutException} 으로 <b>직접</b> 실패시킨다(감싸지 않는다).</li>
     *   <li>타임아웃이 <b>실제로 future 를 완료시킨 경우에만</b> {@code onTimeout} 을 한 번 실행한다
     *       (예: 하부 요청 취소). 이미 완료된 뒤에 타이머가 돌면 아무것도 하지 않는다.</li>
     *   <li>{@code future} 가 먼저 완료되면 <b>타이머 작업을 취소</b>한다. 그러지 않으면 요청마다 타이머 작업이
     *       timeout 시간 동안 스케줄러 큐에 남는다(누수). 단, 큐에서 즉시 빠지려면 스케줄러가
     *       {@code setRemoveOnCancelPolicy(true)} 여야 한다.</li>
     *   <li>{@code future} 자신을 반환한다({@code orTimeout} 과 같은 계약).</li>
     * </ol>
     */
    public static <T> CompletableFuture<T> failAfter(CompletableFuture<T> future, long timeout, TimeUnit unit,
                                                     ScheduledExecutorService timer, Runnable onTimeout) {
        // TODO(E2): timer.schedule(...) 의 결과(ScheduledFuture)를 future.whenComplete 에서 cancel 한다 (L5, R10)
        throw new UnsupportedOperationException("TODO");
    }
}
