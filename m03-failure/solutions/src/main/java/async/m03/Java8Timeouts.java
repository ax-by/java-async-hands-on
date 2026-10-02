package async.m03;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * E4. 운영 환경이 Java 8 이라고 가정한다. Java 9 에 추가된 타임아웃 API 를 쓰지 않고 타임아웃을 구현한다.
 *
 * <p>이 파일에서는 Java 9+ 의 타임아웃/지연 API(CompletableFuture 의 시간 관련 메서드)를 쓰지 않는다.
 * 테스트가 이 파일의 소스를 읽어 확인한다.
 */
public final class Java8Timeouts {

    private Java8Timeouts() {
    }

    /**
     * source 가 timeoutMillis 안에 끝나지 않으면 TimeoutException 으로 실패하는 <b>새</b> future 를 반환한다.
     * <ul>
     *   <li>시간 안에 끝나면 source 의 결과(성공 또는 실패)를 그대로 따른다.</li>
     *   <li>source 자체는 건드리지 않는다.</li>
     *   <li>타이머는 주어진 scheduler 로 건다. source 가 먼저 끝나면 <b>타이머를 취소</b>해서 scheduler 큐에 쌓이지 않게 한다
     *       (요청마다 타이머가 하나씩 남으면 트래픽이 많을 때 메모리가 샌다).</li>
     * </ul>
     * 생각해 볼 것: 타임아웃으로 실패했을 때, 반환 future 에 붙은 non-async 콜백은 어느 스레드에서 실행될까? (R1, R10)
     */
    public static <T> CompletableFuture<T> within(CompletableFuture<T> source, long timeoutMillis, ScheduledExecutorService scheduler) {
        // TODO(E4): new CompletableFuture + scheduler.schedule(...) + source.whenComplete(...) (R10)
        // SOLUTION-BEGIN
        CompletableFuture<T> result = new CompletableFuture<>();
        ScheduledFuture<?> timer = scheduler.schedule(
                () -> result.completeExceptionally(new TimeoutException(timeoutMillis + "ms 안에 완료되지 않았다")),
                timeoutMillis, TimeUnit.MILLISECONDS);
        source.whenComplete((v, ex) -> {
            timer.cancel(false);
            if (ex != null) {
                result.completeExceptionally(ex);
            } else {
                result.complete(v);
            }
        });
        return result;
        // SOLUTION-END
    }
}
