package async.m06;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * E6. non-blocking 동시성 제한기 (B 의 backpressure). 채점: Ex6AsyncLimiterTest
 *
 * <p>왜 필요한가: A 에서는 풀 크기가 곧 동시 호출 수의 상한이었다.
 * B 에는 그런 상한이 없다. 요청 1만 개가 오면 원격 서버에 1만 개가 동시에 꽂힌다.
 * 그렇다고 {@code Semaphore.acquire()} 로 기다리면 그 스레드(이벤트 루프일 수도 있다)가 막혀서 B 가 깨진다.
 *
 * <p>요구사항
 * <ul>
 *   <li>동시에 진행 중인 call 은 최대 maxConcurrent 개. 넘치는 것은 큐에 넣어 두고, 진행 중인 것이 끝나면 꺼내서 시작한다.</li>
 *   <li>{@link #submit} 은 어떤 경우에도 막지 않고 즉시 future 를 반환한다. (blocking acquire 금지)</li>
 *   <li>call(Supplier)은 <b>허가를 받은 뒤에야</b> 호출한다. future 를 만드는 순간 원격 호출이 시작되기 때문이다 (R3).</li>
 *   <li>call 이 실패해도(실패한 future 를 주든, Supplier 가 예외를 던지든) 허가는 반드시 반납한다.
 *       Supplier 가 던진 예외는 submit 이 던지지 않고 반환한 future 의 실패로 전달한다.</li>
 *   <li>완료 콜백 안에서 다음 작업을 시작하므로, 즉시 완료되는 call 이 수만 개 쌓여 있어도 스택이 넘치지 않아야 한다.</li>
 * </ul>
 */
public final class Ex6AsyncLimiter {

    // SOLUTION-REMOVE-BEGIN
    private final int maxConcurrent;
    private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    /** work-in-progress: drain 루프를 한 번에 한 곳에서만 돌리기 위한 카운터 (Reactor 등 OSS 에서 흔한 패턴). */
    private final AtomicInteger wip = new AtomicInteger();
    // SOLUTION-REMOVE-END

    public Ex6AsyncLimiter(int maxConcurrent) {
        // TODO(E6): 필드는 직접 설계한다. 힌트: 대기 큐(ConcurrentLinkedQueue), 진행 중 개수(AtomicInteger)
        // SOLUTION-BEGIN
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent >= 1");
        }
        this.maxConcurrent = maxConcurrent;
        // SOLUTION-END
    }

    /** call 을 동시성 한도 안에서 실행하고, 그 결과를 전달하는 future 를 즉시 반환한다. */
    public <T> CompletableFuture<T> submit(Supplier<? extends CompletionStage<T>> call) {
        // TODO(E6): 결과 future 를 먼저 만들고, "시작하는 일"을 큐에 넣은 뒤 drain. 허가 반납은 whenComplete 에서 (R9)
        // 힌트: 완료 콜백 → 반납 → 다음 시작 → 즉시 완료 → 반납 → ... 이 재귀가 되지 않게 drain 루프를 한 곳에서만 돌린다.
        // SOLUTION-BEGIN
        CompletableFuture<T> result = new CompletableFuture<>();
        pending.add(() -> start(call, result));
        drain();
        return result;
        // SOLUTION-END
    }

    /** 지금 진행 중인 call 의 수. */
    public int inFlight() {
        // TODO(E6)
        // SOLUTION-BEGIN
        return inFlight.get();
        // SOLUTION-END
    }

    /** 허가를 기다리며 큐에 있는 call 의 수. */
    public int queued() {
        // TODO(E6)
        // SOLUTION-BEGIN
        return pending.size();
        // SOLUTION-END
    }

    // SOLUTION-REMOVE-BEGIN
    private void drain() {
        if (wip.getAndIncrement() != 0) {
            return; // 이미 누군가(다른 스레드 또는 바깥 프레임) drain 중이다. 그쪽이 한 바퀴 더 돈다.
        }
        int missed = 1;
        do {
            while (inFlight.get() < maxConcurrent) {
                Runnable task = pending.poll();
                if (task == null) {
                    break;
                }
                inFlight.incrementAndGet(); // drain 소유자만 증가시키므로 한도를 넘지 않는다
                task.run();
            }
            missed = wip.addAndGet(-missed);
        } while (missed != 0);
    }

    private <T> void start(Supplier<? extends CompletionStage<T>> call, CompletableFuture<T> result) {
        CompletionStage<T> stage;
        try {
            stage = Objects.requireNonNull(call.get(), "call 이 null 을 반환했다");
        } catch (Throwable t) {
            release();
            result.completeExceptionally(t);
            return;
        }
        stage.whenComplete((v, ex) -> {
            release();
            if (ex != null) {
                result.completeExceptionally(ex);
            } else {
                result.complete(v);
            }
        });
    }

    private void release() {
        inFlight.decrementAndGet();
        drain();
    }
    // SOLUTION-REMOVE-END
}
