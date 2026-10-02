package async.harness;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 이름 있는 스레드 풀 팩토리. 스레드 이름이 "{name}-{번호}" 가 되므로 {@link On#pool(String)} 으로 예측할 수 있다.
 *
 * <pre>{@code
 * try (NamedPool io = Pools.fixed("io", 4)) {
 *     CompletableFuture.supplyAsync(() -> ..., io);
 * }
 * }</pre>
 */
public final class Pools {

    private Pools() {
    }

    /** 고정 크기 풀, 큐 무제한. */
    public static NamedPool fixed(String name, int threads) {
        return new NamedPool(name, threads, threads, new LinkedBlockingQueue<>(), new ThreadPoolExecutor.AbortPolicy());
    }

    /** 고정 크기 풀, 큐 크기 제한 + 거절 정책 지정. */
    public static NamedPool bounded(String name, int threads, int queueCapacity, RejectedExecutionHandler onReject) {
        return new NamedPool(name, threads, threads, new ArrayBlockingQueue<>(queueCapacity), onReject);
    }

    public static NamedPool single(String name) {
        return fixed(name, 1);
    }

    /** "{name}-1", "{name}-2" ... 이름의 데몬 스레드를 만든다. */
    public static ThreadFactory namedFactory(String name) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * 관찰 가능한 ThreadPoolExecutor. try-with-resources 로 닫는다.
     * (JDK 19+ 의 ExecutorService.close() 는 작업 완료를 기다리지만, 실습에서는 즉시 중단한다.)
     */
    public static final class NamedPool extends ThreadPoolExecutor implements AutoCloseable {
        private final String name;
        private final AtomicInteger rejected = new AtomicInteger();

        NamedPool(String name, int core, int max, BlockingQueue<Runnable> queue, RejectedExecutionHandler onReject) {
            super(core, max, 30, TimeUnit.SECONDS, queue, namedFactory(name), onReject);
            this.name = name;
            setRejectedExecutionHandler((r, ex) -> {
                rejected.incrementAndGet();
                onReject.rejectedExecution(r, ex);
            });
        }

        public String name() {
            return name;
        }

        /** 동시에 가장 많이 살아 있던 스레드 수. */
        public int largestPoolSize() {
            return getLargestPoolSize();
        }

        public int rejectedCount() {
            return rejected.get();
        }

        public int queuedCount() {
            return getQueue().size();
        }

        @Override
        public void close() {
            shutdownNow();
        }

        @Override
        public String toString() {
            return "NamedPool(" + name + ", active=" + getActiveCount() + ", queued=" + queuedCount()
                    + ", completed=" + getCompletedTaskCount() + ")";
        }
    }
}
