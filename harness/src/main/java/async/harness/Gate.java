package async.harness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 타이밍을 sleep 대신 "문"으로 통제하기 위한 도구. 시나리오를 결정적으로 만든다.
 *
 * <pre>{@code
 * Gate gate = new Gate();
 * CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> { gate.await(); return "v"; }, io);
 * f.thenApply(...);   // gate 가 닫혀 있으므로 f 는 "확실히 미완료" 상태에서 콜백이 등록된다
 * gate.open();        // 이제 io 스레드가 f 를 완료시킨다
 * }</pre>
 */
public final class Gate {

    private final CountDownLatch latch = new CountDownLatch(1);

    /** 문이 열릴 때까지 현재 스레드를 막는다 (최대 5초, 넘으면 실패). */
    public void await() {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Gate 가 5초 안에 열리지 않았다 (시나리오 버그)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public void open() {
        latch.countDown();
    }

    public boolean isOpen() {
        return latch.getCount() == 0;
    }
}
